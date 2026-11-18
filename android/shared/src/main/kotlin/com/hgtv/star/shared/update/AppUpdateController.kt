// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
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
 * 用户自行配置的更新来源。
 *
 * 发布包不内置任何更新地址。GitHub 项目会归一成最新 Release 的 update.json；
 * 直接地址则保留用户填写的元数据地址，并按同级 releases/download 目录拼出安装包地址。
 */
data class AppUpdateSource(
    val metadataUrl: String,
    val downloadBaseUrl: String,
) {
    fun downloadUrl(update: AppUpdate): String =
        "${downloadBaseUrl.trimEnd('/')}/${update.tag}/${update.fileName}"

    companion object {
        const val MAX_INPUT_LENGTH = 4096

        private val OWNER = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")
        private val REPOSITORY = Regex("[A-Za-z0-9._-]{1,100}")

        fun parse(input: String): AppUpdateSource {
            val value = input.trim()
            require(value.isNotEmpty()) { "请输入 GitHub 用户/项目或更新地址" }
            require(value.length <= MAX_INPUT_LENGTH) { "输入内容过长" }
            require(value.none { it.isISOControl() }) { "更新地址包含无效字符" }

            parseProject(value)?.let { return it }
            val candidate = when {
                value.startsWith("github.com/", ignoreCase = true) ||
                    value.startsWith("www.github.com/", ignoreCase = true) -> "https://$value"
                value.startsWith("http://", ignoreCase = true) ||
                    value.startsWith("https://", ignoreCase = true) -> value
                else -> value
            }
            val uri = runCatching { Uri.parse(candidate) }.getOrNull()
            if (uri != null && uri.scheme != null) {
                val scheme = uri.scheme.orEmpty().lowercase()
                require(scheme == "http" || scheme == "https") { "更新地址只支持 http 或 https" }
                require(!uri.host.isNullOrBlank()) { "更新地址缺少主机名" }
                github(uri)?.let { return it }
                return direct(uri)
            }
            throw IllegalArgumentException("请输入 GitHub 用户/项目或 http(s) 更新地址")
        }

        private fun parseProject(value: String): AppUpdateSource? {
            val project = value.trimEnd('/')
            val parts = project.split('/', limit = 3)
            if (parts.size != 2 || !validOwner(parts[0]) || !validRepository(parts[1])) return null
            return github(parts[0], parts[1].removeSuffix(".git"))
        }

        private fun github(uri: Uri): AppUpdateSource? {
            val host = uri.host?.lowercase() ?: return null
            if (host != "github.com" && host != "www.github.com") return null
            val segments = uri.pathSegments
            require(segments.size >= 2) { "GitHub 地址缺少用户或项目" }
            return github(segments[0], segments[1].removeSuffix(".git"))
        }

        private fun github(owner: String, repository: String): AppUpdateSource {
            require(validOwner(owner) && validRepository(repository)) { "GitHub 用户或项目格式不正确" }
            val base = "https://github.com/$owner/$repository"
            return AppUpdateSource(
                metadataUrl = "$base/releases/latest/download/update.json",
                downloadBaseUrl = "$base/releases/download",
            )
        }

        private fun direct(uri: Uri): AppUpdateSource {
            val metadataUrl = uri.buildUpon().fragment(null).build().toString()
            val path = uri.encodedPath.orEmpty()
            val lower = path.lowercase()
            val basePath = when {
                lower.endsWith("/latest/download/update.json") ->
                    path.dropLast("/latest/download/update.json".length) + "/download"
                lower.endsWith("/download/update.json") ->
                    path.dropLast("/update.json".length)
                lower.endsWith("/update.json") ->
                    path.dropLast("/update.json".length) + "/download"
                else -> path.trimEnd('/') + "/download"
            }
            val downloadBaseUrl = uri.buildUpon()
                .encodedPath(basePath)
                .query(null)
                .fragment(null)
                .build()
                .toString()
                .trimEnd('/')
            return AppUpdateSource(metadataUrl, downloadBaseUrl)
        }

        private fun validOwner(value: String): Boolean =
            value.length in 1..39 && OWNER.matches(value)

        private fun validRepository(value: String): Boolean =
            value != "." && value != ".." && REPOSITORY.matches(value)
    }
}

data class AppUpdateState(
    val checked: Boolean = false,
    val checking: Boolean = false,
    val available: AppUpdate? = null,
    val downloading: Boolean = false,
    val ready: Boolean = false,
    val progress: Int = 0,
    val error: String = "",
) {
    val hasUpdate: Boolean get() = available != null
}

/**
 * 静默检查 + 被动更新入口。
 *
 * 检查不会弹窗；TV / Phone 只读取 [state] 决定红点 / 角标。
 * 用户点击入口后才下载，下载完成后用系统安装器打开 APK，不建立
 * PackageInstaller 会话，也不等待安装结果广播。
 */
class AppUpdateController private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository = AppUpdateRepository()
    private val apk = File(appContext.filesDir, "updates/update.apk")
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    @Volatile private var source = loadSource()
    private val _state = MutableStateFlow(AppUpdateState())
    private val checkedThisProcess = AtomicBoolean(false)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    fun hasSource(): Boolean = source != null

    /** 保存用户输入；失败时保留原来源，不触发任何网络请求。 */
    fun setSource(input: String): Result<AppUpdateSource> = runCatching {
        val parsed = AppUpdateSource.parse(input)
        preferences.edit()
            .putString(KEY_METADATA_URL, parsed.metadataUrl)
            .putString(KEY_DOWNLOAD_BASE_URL, parsed.downloadBaseUrl)
            .apply()
        source = parsed
        _state.value = AppUpdateState()
        parsed
    }

    fun validateSource(input: String): String? =
        runCatching { AppUpdateSource.parse(input) }.exceptionOrNull()?.message

    fun check(force: Boolean = false): Boolean {
        val configured = source ?: return false
        val current = _state.value
        if (current.checking || current.downloading) return false
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
                        progress = if (cached) 100 else 0,
                        error = "",
                    )
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
        if (_state.value.ready) {
            install()
            return
        }
        if (_state.value.downloading) return
        _state.update { it.copy(downloading = true, progress = 0, error = "") }
        scope.launch {
            try {
                withContext(AppExecutors.network.dispatcher) {
                    val configured = source ?: throw IOException("尚未设置更新地址")
                    repository.download(configured, update, apk) { percent ->
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

    /** SmartTube 的入口：直接把 APK 交给系统安装器。 */
    fun install() {
        if (!_state.value.ready || !apk.isFile) return
        try {
            val uri = installerUri(apk)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            appContext.startActivity(intent)
        } catch (e: Exception) {
            _state.update {
                it.copy(error = "这台设备没有可用的系统安装器")
            }
        }
    }

    private fun installerUri(file: File): Uri =
        if (Build.VERSION.SDK_INT >= 24) {
            FileProvider.getUriForFile(appContext, "${appContext.packageName}.updates", file)
        } else {
            @Suppress("DEPRECATION")
            file.setReadable(true, false)
            Uri.fromFile(file)
        }

    @Suppress("DEPRECATION")
    private fun installedVersionCode(): Long {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    private fun loadSource(): AppUpdateSource? {
        val metadataUrl = preferences.getString(KEY_METADATA_URL, null).orEmpty()
        val downloadBaseUrl = preferences.getString(KEY_DOWNLOAD_BASE_URL, null).orEmpty()
        if (metadataUrl.isBlank() || downloadBaseUrl.isBlank()) return null
        return AppUpdateSource(metadataUrl, downloadBaseUrl)
    }

    companion object {
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val PREFERENCES = "app_update"
        private const val KEY_METADATA_URL = "metadata_url"
        private const val KEY_DOWNLOAD_BASE_URL = "download_base_url"
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
