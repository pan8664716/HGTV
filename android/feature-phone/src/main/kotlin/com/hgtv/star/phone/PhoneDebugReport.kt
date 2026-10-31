// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.hgtv.star.shared.architecture.AppExecutors
import com.hgtv.star.shared.compat.DeviceForm
import com.hgtv.star.shared.compat.DeviceProfile
import com.hgtv.star.shared.compat.connectivityManager
import com.hgtv.star.shared.compat.detectDeviceForm
import com.hgtv.star.shared.compat.isMeteredConnection
import com.hgtv.star.shared.data.Library
import com.hgtv.star.shared.data.PlaybackCapability
import com.hgtv.star.shared.debug.DebugLog
import com.hgtv.star.shared.debug.NetSpeed
import com.hgtv.star.shared.debug.PerfStats

/**
 * 手机调试面板的公共现场。
 *
 * TV 端的面板固定先列「页面 / 机型 / 形态 / 解码 / 性能 / TLS」，手机端也走同一份，
 * 播放页再往后接播放事实。这样用户在两台设备上报同一个问题时，读到的字段与顺序一致。
 */
internal object PhoneDebugReport {

    private const val ENV_TTL_MS = 5_000L
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var capability: PlaybackCapability.DebugCapability? = null
    @Volatile private var sampling = false
    private var profile: DeviceProfile? = null
    private var meteredAt = 0L
    private var metered = false
    private var registeredAt = 0L
    private var registered = false

    fun lines(
        context: Context,
        page: String,
        library: Library?,
        extras: List<Pair<String, String>> = emptyList(),
        problem: String = "无",
        error: String = "",
        recent: Int = 5,
    ): List<String> {
        val rows = ArrayList<Pair<String, String>>(16)
        rows += "页面" to pageStatus(context, page, library)
        rows += "机型" to deviceRow()
        rows += "形态" to deviceFormRow(context, library)
        rows += "解码" to capabilityRow()
        rows += "性能" to PerfStats.summary.ifBlank { "采样中…" }
        rows += "TLS" to tlsRow()
        rows += extras
        rows += "问题" to problem.ifBlank { "无" }
        if (error.isNotBlank()) rows += "错误" to error
        val logs = recentRows(recent)
        if (logs.isEmpty()) rows += "近况" to "无" else rows += logs
        return rows.filter { it.second.isNotBlank() }
            .map { (label, value) -> label + "  " + value }
    }

    /** 后台采性能、网速与解码能力；同一时间只跑一份，采样完成后再回主线程重画面板。 */
    fun sampleAsync(context: Context, onReady: () -> Unit) {
        val app = context.applicationContext
        val needPerf = PerfStats.due()
        val needCapability = capability == null
        if (!needPerf && !needCapability) return
        synchronized(this) {
            if (sampling) return
            sampling = true
        }
        if (needPerf) PerfStats.sampledAt = SystemClock.elapsedRealtime()
        AppExecutors.disk.executor.execute {
            if (needPerf) {
                runCatching { PerfStats.sample(app) }
                runCatching { NetSpeed.sample() }
            }
            if (needCapability) {
                capability = runCatching { PlaybackCapability.debugSummary() }.getOrNull()
            }
            synchronized(this) { sampling = false }
            main.post { onReady() }
        }
    }

    fun resetSamples() {
        PerfStats.reset()
        NetSpeed.reset()
    }

    private fun pageStatus(context: Context, page: String, library: Library?): String {
        val signed = runCatching { PhoneServices.repository(context).signedIn }.getOrDefault(false)
        val registered = deviceRegistered(library)
        val metered = metered(context)
        return buildString {
            append(page)
            append(" · 账号 ").append(if (signed) "已登录" else "未登录")
            append(" · 设备 ").append(if (registered) "已注册" else "未注册")
            append(" · 网络 ").append(if (metered) "移动" else "wifi")
            append(" · 搜索源 ").append(library?.searchSource?.label ?: "-")
        }
    }

    private fun deviceRow(): String =
        "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}(${Build.VERSION.SDK_INT})"

    private fun deviceFormRow(context: Context, library: Library?): String {
        val profile = profile ?: runCatching {
            context.applicationContext.detectDeviceForm()
        }.getOrNull()?.also { profile = it } ?: return "检测中…"
        val manual = library?.deviceForm.orEmpty()
        val selected = DeviceForm.ofStored(manual)
        val source = if (selected != null) "手动" else "自动"
        return "${selected?.label ?: profile.form.label}（$source） · ${profile.summary}"
    }

    private fun capabilityRow(): String {
        val value = capability ?: return "硬解 探测中… · 软解 探测中…"
        return "硬解 ${value.hardware} · 软解 ${value.software}"
    }

    private fun tlsRow(): String =
        PhoneNetDebug.tlsSummary.ifBlank { "尚未建立连接" } +
            " · " + NetSpeed.summary.ifBlank { "采样中…" }

    private fun recentRows(count: Int): List<Pair<String, String>> = DebugLog.lines(count).map { line ->
        val parts = line.split(' ', limit = 3)
        if (parts.size >= 3) parts[1] to (parts[0] + " " + parts[2])
        else "近况" to line
    }

    private fun metered(context: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (meteredAt > 0L && now - meteredAt in 0..ENV_TTL_MS) return metered
        metered = runCatching {
            context.connectivityManager()?.isMeteredConnection() ?: false
        }.getOrDefault(false)
        meteredAt = now
        return metered
    }

    private fun deviceRegistered(library: Library?): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (registeredAt > 0L && now - registeredAt in 0..ENV_TTL_MS) return registered
        registered = runCatching { library?.deviceRegistered() == true }.getOrDefault(false)
        registeredAt = now
        return registered
    }
}
