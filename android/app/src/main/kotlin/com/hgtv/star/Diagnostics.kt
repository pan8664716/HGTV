// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import com.hgtv.star.compat.connectivityManager
import com.hgtv.star.compat.isMeteredConnection

/**
 * 全站诊断的唯一出口。
 *
 * 三档语义：
 *
 * * [trace]  **状态**：阶段、计数、耗时。只记本地 / 控制台，不发网络。
 * * [warn]   **异常但用户无感**：重试成功、镜像写失败、OOM 被兜住。
 * * [fail]   **用户可感知的失败**：页面空白、播放失败、登录失败。进调试面板 + logcat。
 *
 * **高频路径一律不许进**：封面逐张解码、缓存 miss、焦点兜底、单条弹幕解析——
 * 一次滚动几百条就把信噪比冲垮了，反而让真正有用的现场淹没。
 *
 * ## 为什么不再远端上报
 *
 * U-APM 已整体移除（见 `build.gradle.kts`）：这条链路改成**只落本地**——
 * 调试面板的近况行 + logcat。用户反馈问题时打开「设置 → 调试模式」就能把现场读出来，
 * 不再依赖第三方后台，也不再往体外发任何诊断数据。
 *
 * ## 聚合规则
 *
 * [fail] 的 `stage` 是**聚合键**，必须是稳定常量；同一 stage 一次启动只记一条，
 * 动态细节（解码器名、错误码、进度）全部进 detail。这样面板上的近况不会被同一件事刷屏。
 */
internal object Diagnostics {
    /** 单进程记录上限：异常路径不能变成刷屏。 */
    private const val MAX_ISSUES = 30

    private var issues = 0
    private val claimed = mutableSetOf<String>()

    /**
     * 状态日志。
     *
     * 只写 logcat：这一档是高频的（每个 HTTP 状态码一行），
     * 它本身的信息量不足以单独成案，留在 `adb logcat` 里就够。
     * 需要它的时候，`adb logcat` 一直在。
     */
    fun trace(tag: String,line: String) {
        android.util.Log.i(tag,line)
    }

    /**
     * 异常但用户无感（被兜住、已重试成功、降级可用）。
     *
     * 与 [fail] 的区别只在**聚合键与去重预算**：这一类用户没受影响，
     * 因此不进「问题」行，只留 logcat 一行（stage 前缀带 `soft_`，便于按前缀筛）。
     */
    fun warn(tag: String,line: String,problem: Throwable?=null) {
        val stage="soft_"+tag.lowercase().replace('-','_')
        val first=runCatching {
            if(issues>=MAX_ISSUES) return@runCatching false
            if(!claimed.add(stage)) return@runCatching false
            issues++
            val text=buildString {
                append('[').append(tag).append("] ").append(line.take(400))
                if(problem!=null) append(" | ").append(chainOf(problem))
            }
            android.util.Log.w(tag,text)
            true
        }.getOrDefault(false)
        if(!first) android.util.Log.w(tag,line)
        // 调试模式那块面板要能读到「刚才发生了什么」：去重挡掉的只是记一条，这里照记。
        // 但「线程被打断」不记：那是切页 / 换剧 / 退出时我们自己停掉的请求，不是故障
        // （判据见 `com.hgtv.star.core.isInterruption`）。
        if(problem==null || !com.hgtv.star.core.isInterruption(problem)) DebugLog.record("异常",line)
    }

    /**
     * 用户可感知的失败：进调试面板「问题」行 + logcat。
     *
     * [stage] 是聚合键，必须稳定（`content_home` / `device_register` …）。
     * 同一 stage 一次启动只记一条：用户连按十次「重试」不该把面板刷成十条。
     */
    fun fail(stage: String,title: String,detail: String,problem: Throwable?=null) {
        val first=runCatching {
            if(issues>=MAX_ISSUES) return@runCatching false
            if(!claimed.add(stage)) return@runCatching false
            issues++
            // 消息里带 stage 前缀，方便在后台按关键字筛；title 是给人看的那句。
            val message=buildString {
                append('[').append(stage).append("] ").append(title)
                if(detail.isNotEmpty()) append(" | ").append(detail.take(700))
            }
            android.util.Log.w("HGTV","[$stage] $message")
            true
        }.getOrDefault(false)
        if(!first) android.util.Log.w("HGTV","Diagnostics dropped stage=$stage (duplicate or over budget)")
        // 用户可感知的失败一定进调试面板：搜索 / 登录 / 接口的报错就是靠这一条显示出来的。
        // 打断（切页 / 换剧 / 退出）不算：它没有用户可感知的结果，只是我们主动停了一次请求。
        if(problem==null || !com.hgtv.star.core.isInterruption(problem))
            DebugLog.record("失败",title+(if(detail.isNotEmpty()) " · "+detail.take(120) else ""))
    }

    /** cause 链压成一行：只留类名与 message，够定位，又不撑爆字段。 */
    fun chainOf(problem: Throwable?): String {
        if(problem==null) return "-"
        val parts=mutableListOf<String>(); var current=problem; var guard=0
        while(current!=null && guard++<6) {
            val name=current.javaClass.name.substringAfterLast('.')
            val message=current.message?.replace('\n',' ')?.take(160).orEmpty()
            parts+=if(message.isEmpty()) name else "$name: $message"
            current=current.cause
        }
        return parts.joinToString(" <- ")
    }

    /** 仅供测试：重新打开预算。 */
    fun resetForTest() { issues=0; claimed.clear() }
}

/**
 * 记录载体。**堆栈保持原样**，不伪造帧。
 *
 * 仍然保留这个类型（而不是直接传原始 `Throwable`）：`stage` 要跟着异常一起走，
 * 面板 / 日志里读「哪一步失败」时不必再从 message 前缀里猜。
 */
internal class DiagnosticsException(
    val stage: String,
    message: String,
    cause: Throwable?=null
): Exception(message,cause)

/**
 * 设备与构建的公共前缀：每条现场都带上，省得每处各写一遍。
 *
 * 不带任何凭据、不带剧名（用户内容），只带「哪台机器、哪个版本、什么网络」。
 */
internal fun devicePrefix(context: android.content.Context?): String {
    val net=runCatching {
        val cm=context?.connectivityManager() ?: return@runCatching "?"
        if(cm.isMeteredConnection()) "mobile" else "wifi"
    }.getOrDefault("?")
    return "dev=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"+
        " os=${android.os.Build.VERSION.RELEASE}(${android.os.Build.VERSION.SDK_INT})"+
        " app=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) net=$net"
}
