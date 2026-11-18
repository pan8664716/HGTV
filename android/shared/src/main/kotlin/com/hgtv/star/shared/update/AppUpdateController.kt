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
import kotlinx.coroutines.cancel
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
    val downloadUrl: String
        get() = "$RELEASES/download/$tag/$fileName"

    companion object {
        const val REPOSITORY = "pan8664716/HGTV"
        const val PACKAGE = "com.hgtv.star"
        const val RELEASES = "https://github.com/$REPOSITORY/releases"
        const val METADATA_URL = "$RELEASES/latest/download/update.json"
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
class AppUpdateController(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository = AppUpdateRepository()
    private val apk = File(appContext.filesDir, "updates/update.apk")
    private val _state = MutableStateFlow(AppUpdateState())
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    fun check(force: Boolean = false) {
        val current = _state.value
        if (current.checking || current.downloading) return
        if (!force && (current.checked || current.hasUpdate)) return
        _state.update { it.copy(checking = true, error = "") }
        scope.launch {
            try {
                val installedCode = installedVersionCode()
                val update = withContext(AppExecutors.network.dispatcher) {
                    repository.latest()
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
                    repository.download(update, apk) { percent ->
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

    fun close() {
        scope.cancel()
        repository.close()
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

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
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
    private val sources = listOf(
        "https://gh-proxy.org/",
        "",
    )
    private val client = OkHttpClient.Builder()
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES)
        .build()

    fun latest(): AppUpdate {
        var failure: IOException? = null
        for (prefix in sources) {
            try {
                val call = client.newCall(request(prefix + AppUpdate.METADATA_URL))
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
            } catch (e: IOException) {
                failure = e
            }
        }
        throw failure ?: IOException("无法检查更新，请稍后重试")
    }

    fun download(update: AppUpdate, file: File, progress: (Int) -> Unit) {
        if (UpdateFiles.verify(file, update)) return
        var failure: IOException? = null
        for (prefix in sources) {
            try {
                val call = client.newCall(request(prefix + update.downloadUrl))
                call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("安装包下载失败，请稍后重试")
                    val body = response.body() ?: throw IOException("安装包为空")
                    val declared = body.contentLength()
                    if (declared >= 0 && declared != update.size) throw IOException("安装包长度异常")
                    body.byteStream().use {
                        UpdateFiles.receive(it, file, update, progress)
                    }
                }
                return
            } catch (e: IOException) {
                failure = e
            }
        }
        throw failure ?: IOException("安装包下载失败，请稍后重试")
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
