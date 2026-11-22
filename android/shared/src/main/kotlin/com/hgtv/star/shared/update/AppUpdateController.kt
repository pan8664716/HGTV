// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.update

import android.content.Context
import android.os.Build
import android.widget.Toast
import com.hgtv.star.shared.architecture.AppExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Release 元数据。tag 兼容当前无 `v` 的 `1.3.8` 和旧版的 `v1.2.4.2`。
 */
data class AppUpdate(
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val tag: String,
    val fileName: String,
    val size: Long,
    val sha256: String,
    val notes: String,
) {
    companion object {
        const val PACKAGE = "com.hgtv.star"
        const val MAX_APK_BYTES = 80L * 1024 * 1024
        const val MAX_METADATA_BYTES = 64 * 1024
        const val MAX_NOTES = 1500

        private val VERSION = Regex("[0-9][0-9A-Za-z.+-]{0,63}")
        private val TAG = Regex("v?[0-9][0-9A-Za-z.+-]{0,80}")
        private val APK = Regex("HGTV_[0-9][0-9A-Za-z.+-]{0,80}\\.apk")
        private val SHA256 = Regex("[0-9a-f]{64}")

        fun parse(json: String): AppUpdate {
            val root = runCatching { JSONObject(json) }
                .getOrElse { throw IOException("更新信息格式不正确") }
            if (root.optInt("schema") != 1 || root.optString("packageName") != PACKAGE) {
                throw IOException("更新信息不匹配")
            }
            val apk = root.optJSONObject("apk") ?: throw IOException("更新信息缺少安装包")
            val update = AppUpdate(
                versionCode = root.optLong("versionCode"),
                versionName = root.optString("versionName"),
                minSdk = root.optInt("minSdk"),
                tag = root.optString("tag"),
                fileName = apk.optString("name"),
                size = apk.optLong("size"),
                sha256 = apk.optString("sha256").lowercase(),
                notes = root.optString("notes").take(MAX_NOTES),
            )
            if (update.versionCode !in 1..Int.MAX_VALUE.toLong()) throw IOException("版本号不可用")
            if (update.minSdk !in 19..100) throw IOException("最低系统版本不可用")
            if (!VERSION.matches(update.versionName)) throw IOException("版本名不可用")
            if (!TAG.matches(update.tag)) throw IOException("版本标签不可用")
            if (!APK.matches(update.fileName)) throw IOException("安装包名不可用")
            if (update.size !in 1..MAX_APK_BYTES) throw IOException("安装包大小不可用")
            if (!SHA256.matches(update.sha256)) throw IOException("校验值不可用")
            return update
        }
    }
}

/**
 * 内置更新来源。
 *
 * 写死官方仓库，不再支持用户自行设置：GitHub 项目归一成最新 Release 的 update.json；
 * 直接地址保留元数据地址，并按同级 releases/download 目录拼出安装包地址。
 */
data class AppUpdateSource(
    val metadataUrl: String,
    val downloadBaseUrl: String,
) {
    fun downloadUrl(update: AppUpdate): String =
        "${downloadBaseUrl.trimEnd('/')}/${update.tag}/${update.fileName}"

    companion object {
        private const val REPOSITORY = "pan8664716/HGTV"
        private const val GITHUB_RELEASES = "https://github.com/$REPOSITORY/releases"
        /** 国内网络下 GitHub 直连不稳，元数据与安装包都走 gh 镜像。 */
        private const val MIRROR = "https://gh-proxy.org/"

        /** 写死的唯一更新地址。没有设置入口，也没有偏好存储。 */
        val BUILT_IN = AppUpdateSource(
            metadataUrl = "$MIRROR$GITHUB_RELEASES/latest/download/update.json",
            downloadBaseUrl = "$MIRROR$GITHUB_RELEASES/download",
        )
    }
}

data class AppUpdateState(
    val checked: Boolean = false,
    val checking: Boolean = false,
    val available: AppUpdate? = null,
    val downloading: Boolean = false,
    val ready: Boolean = false,
    val installing: Boolean = false,
    val progress: Int = 0,
    val error: String = "",
) {
    val hasUpdate: Boolean get() = available != null
}

/**
 * 静默检查 + 被动更新入口。
 *
 * 检查不会弹窗；TV / Phone 只读取 [state] 决定红点 / 角标。
 * 用户点击入口后才下载；安装包与 SmartTube 一样存放在内部缓存根目录的
 * `update.apk`，下载完成后用 `ACTION_VIEW` 拉起系统安装器。
 */
class AppUpdateController private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository = AppUpdateRepository()
    private val apk = File(appContext.cacheDir, "update.apk")
    private val _state = MutableStateFlow(AppUpdateState())
    private val checkedThisProcess = AtomicBoolean(false)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    fun check(force: Boolean = false): Boolean {
        val configured = AppUpdateSource.BUILT_IN
        val current = _state.value
        if (current.checking || current.downloading || current.installing) return false
        if (!force && (current.checked || current.hasUpdate)) return false
        // 自动检查每个应用进程只发一次；用户主动点击检查仍可 force 重试。
        if (!force && !checkedThisProcess.compareAndSet(false, true)) return false
        _state.update { it.copy(checking = true, error = "") }
        scope.launch {
            try {
                val installedCode = installedVersionCode()
                val update = withContext(AppExecutors.network.dispatcher) {
                    repository.latest(configured)
                }
                val found = update.takeIf { it.versionCode > installedCode }
                val cached = found != null &&
                    withContext(AppExecutors.disk.dispatcher) { UpdateFiles.verify(apk, found) }
                _state.update {
                    it.copy(
                        checked = true,
                        checking = false,
                        available = found,
                        ready = cached,
                        installing = false,
                        progress = if (cached) 100 else 0,
                        error = "",
                    )
                }
                if (found == null) {
                    withContext(AppExecutors.disk.dispatcher) {
                        if (apk.exists()) apk.delete()
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        checked = true,
                        checking = false,
                        error = e.message ?: "检查更新失败，请稍后重试",
                    )
                }
            }
        }
        return true
    }

    /** 用户明确点击“下载并安装”后才走这里。 */
    fun downloadAndInstall() {
        val update = _state.value.available ?: return
        if (_state.value.installing) return
        if (_state.value.ready) {
            install()
            return
        }
        if (_state.value.downloading) return
        _state.update { it.copy(downloading = true, progress = 0, error = "") }
        scope.launch {
            try {
                withContext(AppExecutors.network.dispatcher) {
                    repository.download(AppUpdateSource.BUILT_IN, update, apk) { percent ->
                        _state.update { it.copy(progress = percent) }
                    }
                }
                _state.update {
                    it.copy(downloading = false, ready = true, progress = 100)
                }
                install()
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        downloading = false,
                        ready = false,
                        error = e.message ?: "下载更新失败，请稍后重试",
                    )
                }
            }
        }
    }

    /** 与 SmartTube 相同：只把缓存中的 APK 交给系统安装器。 */
    fun install() {
        val current = _state.value
        if (current.installing) return
        if (!current.ready || !apk.isFile) {
            showInstallError("安装包尚未准备好")
            return
        }
        _state.update {
            it.copy(installing = true, error = "")
        }
        runCatching { UpdateInstaller.install(appContext, apk) }
            .onSuccess {
                _state.update { it.copy(installing = false) }
            }
            .onFailure {
                showInstallError(it.message ?: "无法启动系统安装器")
            }
    }

    private fun showInstallError(message: String) {
        _state.update {
            it.copy(
                installing = false,
                error = message,
            )
        }
        notify(message)
    }

    private fun notify(message: String) {
        runCatching {
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun installedVersionCode(): Long {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    companion object {
        @Volatile private var instance: AppUpdateController? = null

        fun get(context: Context): AppUpdateController =
            instance ?: synchronized(this) {
                instance ?: AppUpdateController(context).also { instance = it }
            }
    }
}

private object UpdateFiles {
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

    fun receive(input: InputStream, destination: File, update: AppUpdate, progress: (Int) -> Unit) {
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
                    if (percent != last) {
                        last = percent
                        progress(percent)
                    }
                }
                output.fd.sync()
            }
            if (!verify(partial, update)) throw IOException("安装包校验未通过，请重试")
            if (!partial.renameTo(destination)) throw IOException("无法保存安装包")
        } finally {
            partial.delete()
        }
    }
}

private class AppUpdateRepository : java.io.Closeable {
    private val client = OkHttpClient.Builder()
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES)
        .build()

    fun latest(source: AppUpdateSource): AppUpdate {
        val call = client.newCall(request(source.metadataUrl))
        return call.execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException(
                    if (response.code() == 404) "尚无可用的更新版本"
                    else "无法检查更新，请稍后重试"
                )
            }
            val body = response.body() ?: throw IOException("更新信息为空")
            val text = body.byteStream().use {
                read(it, AppUpdate.MAX_METADATA_BYTES, "更新信息过大")
            }
            AppUpdate.parse(text.toString(Charsets.UTF_8))
        }
    }

    fun download(
        source: AppUpdateSource,
        update: AppUpdate,
        file: File,
        progress: (Int) -> Unit,
    ) {
        if (UpdateFiles.verify(file, update)) return
        val call = client.newCall(request(source.downloadUrl(update)))
        call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("安装包下载失败，请稍后重试")
            val body = response.body() ?: throw IOException("安装包为空")
            val declared = body.contentLength()
            if (declared >= 0 && declared != update.size) throw IOException("安装包长度异常")
            body.byteStream().use {
                UpdateFiles.receive(it, file, update, progress)
            }
        }
    }

    private fun request(url: String): Request = Request.Builder()
        .url(url)
        .header("User-Agent", "HGTV-Updater")
        .header("Cache-Control", "no-cache")
        .build()

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

    override fun close() {
        client.dispatcher().cancelAll()
        client.connectionPool().evictAll()
        client.dispatcher().executorService().shutdown()
    }
}
