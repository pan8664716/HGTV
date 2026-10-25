// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 一次可用的更新：信息来自发布仓库的 `update.json`（Release 附件，由发布工作流生成）。
 *
 * 判定「有没有新版」只看 versionCode，不猜版本号字符串；`minSdk` 只做展示，
 * 不再参与「算不算更新」的判断——能不能装由系统安装器决定。
 */
data class AppUpdate(
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val tag: String,
    val fileName: String,
    val size: Long,
    val sha256: String,
    val notes: String
) {
    fun newerThan(installed: Long, sdk: Int) = versionCode > installed && minSdk <= sdk
    val downloadUrl get() = "$RELEASES/download/$tag/$fileName"

    companion object {
        const val REPOSITORY = "pan8664716/HGTV"
        const val PACKAGE = "com.hgtv.star"
        const val RELEASES = "https://github.com/$REPOSITORY/releases"
        /** 元数据地址：Release 的 latest 附件，改仓库只改 [REPOSITORY]。 */
        const val METADATA_URL = "$RELEASES/latest/download/update.json"
        const val MAX_APK_BYTES = 80L * 1024 * 1024
        const val MAX_METADATA_BYTES = 64 * 1024
        const val MAX_NOTES = 1500

        /**
         * 手动安装入口（镜像前缀 + 发布页）。应用内更新被拦下时把它写给用户：
         * 连不上 `github.com` 的网络也能打得开。
         */
        val MANUAL_URL get() = UpdateSource.MIRRORS.first().resolve("$RELEASES/latest")

        private val VERSION = Regex("[0-9][0-9A-Za-z.+-]{0,63}")
        private val TAG = Regex("v[0-9][0-9A-Za-z.+-]{0,80}")
        private val APK = Regex("HGTV_[0-9][0-9A-Za-z.+-]{0,80}\\.apk")
        private val SHA256 = Regex("[0-9a-f]{64}")

        /** 解析并逐字段校验；任何一项不对就抛 [IOException]，调用方按「更新信息不可用」处理。 */
        fun parse(json: String): AppUpdate {
            val root = try { JSONObject(json) } catch (_: Exception) { throw IOException("更新信息格式不正确") }
            if (root.optInt("schema") != 1 || root.optString("packageName") != PACKAGE) throw IOException("更新信息不匹配")
            val apk = root.optJSONObject("apk") ?: throw IOException("更新信息缺少安装包")
            val update = AppUpdate(
                versionCode = root.optLong("versionCode"),
                versionName = root.optString("versionName"),
                minSdk = root.optInt("minSdk"),
                tag = root.optString("tag"),
                fileName = apk.optString("name"),
                size = apk.optLong("size"),
                sha256 = apk.optString("sha256").lowercase(),
                notes = root.optString("notes").take(MAX_NOTES)
            )
            if (update.versionCode !in 1..Int.MAX_VALUE.toLong()) throw IOException("更新信息里的版本号不可用")
            if (update.minSdk !in 19..100) throw IOException("更新信息里的最低系统版本不可用")
            if (!VERSION.matches(update.versionName)) throw IOException("更新信息里的版本名不可用")
            if (!TAG.matches(update.tag)) throw IOException("更新信息里的版本标签不可用")
            if (!APK.matches(update.fileName)) throw IOException("更新信息里的安装包名不可用")
            if (update.size !in 1..MAX_APK_BYTES) throw IOException("更新信息里的安装包大小不可用")
            if (!SHA256.matches(update.sha256)) throw IOException("更新信息里的校验值不可用")
            return update
        }
    }
}

/**
 * 一个下载源。
 *
 * GitHub 直连用空前缀；公共镜像按各家代理的约定，把**原始地址整条**接在前缀后面，例如
 * `https://gh-proxy.org/https://github.com/pan8664716/HGTV/releases/download/v1.1.1/HGTV_1.1.1.apk`。
 *
 * 镜像只负责把字节搬过来：「有没有新版」仍由发布页的 `update.json` 定义，
 * 下载后的 SHA-256 与安装前的包名 / 版本 / 签名比对照旧，走镜像一条都不放松。
 */
data class UpdateSource(val name: String, val prefix: String) {

    /** 原始地址 → 实际要请求的地址。 */
    fun resolve(url: String) = if (prefix.isEmpty()) url else prefix + url

    /** 前缀里的主机名，用来放行网络请求；直连是空串。 */
    val host: String get() = prefix.substringAfter("://").substringBefore('/')

    companion object {
        /** GitHub 直连。 */
        val GITHUB = UpdateSource("GitHub 直连", "")

        /**
         * 内置镜像，按顺序试，都失败才回退 [GITHUB]。
         *
         * 只收「HTTPS + 前缀式」（镜像地址 = 前缀 + 原始地址）的代理；加一条镜像只改这里，
         * 主机放行名单会自动跟着变（见 [AppUpdateRepository]）。
         */
        val MIRRORS = listOf(UpdateSource("gh-proxy.org 镜像", "https://gh-proxy.org/"))

        /** 默认顺序：镜像优先，直连兜底。 */
        val ALL = MIRRORS + GITHUB
    }
}

/** 安装包的落盘与校验：流式接收，边收边算，先写 `.part` 再改名，失败不留半截文件。 */
object UpdateFiles {
    fun verify(file: File, update: AppUpdate): Boolean {
        if (!file.isFile || file.length() != update.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32768)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == update.sha256
    }

    /**
     * 把 [input] 原样收进 [destination]，**不校验长度、不算哈希**。
     *
     * 用于「只负责取到包、装不装由系统安装器决定」的更新路径：这里只保证
     * 「先写 .part、写完再改名」，中途失败（断网 / 取消）不会留下半截文件。
     * 进度按 [expected] 换算；实际字节多于它时停在 100%，不再当作错误。
     */
    fun receiveUnchecked(input: InputStream, destination: File, expected: Long, progress: (Int) -> Unit = {}) {
        val partial = File(destination.parentFile, destination.name + ".part")
        destination.parentFile?.mkdirs()
        try {
            partial.outputStream().use { output ->
                val buffer = ByteArray(32768)
                var total = 0L
                var last = -1
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("下载已取消")
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    output.write(buffer, 0, n)
                    val percent = if (expected > 0) (total * 100 / expected).toInt().coerceIn(0, 100) else 0
                    if (percent != last) { last = percent; progress(percent) }
                }
                output.fd.sync()
            }
            if (!partial.renameTo(destination)) throw IOException("无法保存安装包")
        } finally {
            partial.delete()
        }
    }

    /** 把 [input] 收进 [destination]；长度超出 [AppUpdate.size] 立即中止，避免磁盘被写满。 */
    fun receive(input: InputStream, destination: File, update: AppUpdate, progress: (Int) -> Unit = {}) {
        val partial = File(destination.parentFile, destination.name + ".part")
        destination.parentFile?.mkdirs()
        try {
            partial.outputStream().use { output ->
                val buffer = ByteArray(32768)
                var total = 0L
                var last = -1
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("下载已取消")
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > update.size) throw IOException("安装包长度异常")
                    output.write(buffer, 0, n)
                    val percent = (total * 100 / update.size).toInt()
                    if (percent != last) { last = percent; progress(percent) }
                }
                output.fd.sync()
            }
            if (!verify(partial, update)) throw IOException("安装包校验未通过，请重新下载")
            if (!partial.renameTo(destination)) throw IOException("无法保存安装包")
        } finally {
            partial.delete()
        }
    }
}

/**
 * 更新仓库的读取端。
 *
 * 请求只会发往两类主机：GitHub 自己的下载 / 接口主机，以及内置镜像的主机（连同它的子域，
 * 有的代理会把请求转到自家 CDN）。主机由 [UpdateSource] 决定，元数据里的标签与文件名
 * 只影响路径，因此客户端不会变成「下载任意地址」的通道。
 *
 * 每个源依次试，全部失败才算失败；[source] 记录当前 / 最近一次用的是哪一条，
 * 界面据此说明这一次是从哪儿拿的。
 */
class AppUpdateRepository(private val sources: List<UpdateSource> = UpdateSource.ALL) : java.io.Closeable {

    /** 当前（最近一次）使用的下载源。 */
    @Volatile
    var source: UpdateSource? = null
        private set

    /** 放行名单：GitHub 的下载主机 + 内置镜像的主机（子域一并放行）。 */
    private val allowedHosts = listOf(
        "github.com", "api.github.com",
        "release-assets.githubusercontent.com", "objects.githubusercontent.com"
    ) + UpdateSource.MIRRORS.map { it.host }

    private val http = HgtvTls.configure(OkHttpClient.Builder())
        // 钉死 HTTP/1.1：见 ContentRepository.defaultHttpClient 的说明。
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES)
        .addNetworkInterceptor { chain ->
            val url = chain.request().url()
            val host = url.host()
            val allowed = url.scheme() == "https" && allowedHosts.any { host == it || host.endsWith(".$it") }
            if (!allowed) throw IOException("更新下载地址不受支持")
            chain.proceed(chain.request())
        }
        .build()

    private fun request(url: String) = Request.Builder().url(url)
        .header("User-Agent", "HGTV-Updater")
        .header("Cache-Control", "no-cache")
        .build()

    /**
     * 读最新一次发布的元数据；没有发布过时给一句可读的提示，而不是空指针。
     * 镜像读不到（连不上 / 报错 / 502）就接着试下一个源。
     */
    fun latest(): AppUpdate = firstWorking { source ->
        val call = http.newCall(request(source.resolve(AppUpdate.METADATA_URL)))
        call.timeout().timeout(25, TimeUnit.SECONDS)
        call.execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException(if (response.code() == 404) "尚无可用的更新版本" else "无法检查更新，请稍后重试")
            }
            val body = response.body() ?: throw IOException("更新信息为空")
            val text = body.byteStream().use { read(it, AppUpdate.MAX_METADATA_BYTES, "更新信息过大") }
            AppUpdate.parse(text.toString(Charsets.UTF_8))
        }
    }

    /** 下载安装包；同样按源依次试，前一条中途断了（半截文件会自己清掉）就换下一条重来。 */
    fun download(update: AppUpdate, file: File, progress: (Int) -> Unit) {
        if (UpdateFiles.verify(file, update)) return
        firstWorking { source ->
            progress(0)
            http.newCall(request(source.resolve(update.downloadUrl))).execute().use { response ->
                if (!response.isSuccessful) throw IOException("安装包下载失败，请稍后重试")
                val body = response.body() ?: throw IOException("安装包为空")
                val declared = body.contentLength()
                if (declared >= 0 && declared != update.size) throw IOException("安装包长度异常")
                body.byteStream().use { UpdateFiles.receive(it, file, update, progress) }
            }
        }
    }

    /**
     * 只把安装包取回来，**不做任何校验**（不比长度、不算哈希）。
     *
     * 装不装、装不装得上交给系统安装器判断；这里只负责把字节完整落到本地。
     */
    fun downloadUnchecked(update: AppUpdate, file: File, progress: (Int) -> Unit) {
        firstWorking { source ->
            progress(0)
            http.newCall(request(source.resolve(update.downloadUrl))).execute().use { response ->
                if (!response.isSuccessful) throw IOException("安装包下载失败，请稍后重试")
                val body = response.body() ?: throw IOException("安装包为空")
                body.byteStream().use { UpdateFiles.receiveUnchecked(it, file, update.size, progress) }
            }
        }
    }

    fun cancel() = http.dispatcher().cancelAll()

    override fun close() {
        cancel()
        http.connectionPool().evictAll()
        http.dispatcher().executorService().shutdown()
    }

    /** 依次试每个源，第一个成功的结果就是答案；全失败时抛出最后一条错误。 */
    private fun <T> firstWorking(step: (UpdateSource) -> T): T {
        var failure: IOException? = null
        sources.forEachIndexed { index, next ->
            // 上一次失败可能是用户按了「取消」：线程已经中断就不必再试下一条。
            if (index > 0 && Thread.currentThread().isInterrupted) throw failure ?: IOException("已取消")
            source = next
            try {
                return step(next)
            } catch (e: IOException) {
                // 逐源记一行：排查「连不上更新服务器」时，要能看出是哪一条源、哪一类失败
                //（DNS / 连接 / TLS / 超时），否则界面上一句话什么都说明不了。
                NetLog.log("更新源 ${next.name} 失败：${e.javaClass.simpleName} ${e.message}")
                failure = readable(e)
            } catch (e: Exception) {
                NetLog.log("更新源 ${next.name} 异常：${e.javaClass.simpleName} ${e.message}")
                failure = IOException(e.message ?: "更新失败，请稍后重试")
            }
        }
        throw failure ?: IOException("更新失败，请稍后重试")
    }

    /**
     * 把底层网络错误换成一句能看懂的话。解析 / 校验这类自己抛的中文提示原样保留，
     * 不把 OkHttp 的英文异常直接摆给电视前面的人看。
     */
    private fun readable(e: IOException): IOException = when (e) {
        is java.net.UnknownHostException,
        is java.net.ConnectException,
        is java.net.NoRouteToHostException,
        is java.net.SocketTimeoutException,
        is javax.net.ssl.SSLException -> IOException("连不上更新服务器，请检查电视网络后重试")
        else -> e
    }

    private fun read(input: InputStream, limit: Int, tooLarge: String): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > limit) throw IOException(tooLarge)
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
