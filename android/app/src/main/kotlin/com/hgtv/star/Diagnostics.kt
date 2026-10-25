// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import com.hgtv.star.compat.connectivityManager
import com.hgtv.star.compat.isMeteredConnection
import com.umeng.analytics.MobclickAgent
import com.umeng.umcrash.UMCrash

/**
 * 全站诊断的唯一出口。
 *
 * 三档语义（换了上报后端也保持不变）：
 *
 * * [trace]  **状态**：阶段、计数、耗时。只记本地 / 控制台，不发网络。
 * * [warn]   **异常但用户无感**：重试成功、镜像写失败、OOM 被兜住。
 * * [fail]   **用户可感知的失败**：页面空白、播放失败、登录失败。走 U-APM 自定义异常。
 *
 * **高频路径一律不许进**：封面逐张解码、缓存 miss、焦点兜底、单条弹幕解析——
 * 一次滚动几百条就把信噪比冲垮了，反而让真正有用的现场淹没。
 *
 * ## 为什么不用 `MobclickAgent.onEvent` 记这些
 *
 * 统计事件只有「计数 + 几个短参数」，而诊断现场是一行长文本（解码器名、codec string、
 * 分辨率帧率、可用解码器列表、播放进度）。塞进事件参数会被截断，也查不到上下文。
 * 自定义异常（`UMCrash.generateCustomLog`）才能带整段文本 + 当时的 logcat。
 *
 * ## 聚合规则
 *
 * 自定义异常的 `type` 是**聚合键**，必须是稳定常量；动态细节全部进 message / 附件。
 * 否则每台设备、每一次失败都会各自变成一条 case，后台只剩碎渣。
 */
internal object Diagnostics {
    /** 单进程上报上限：异常路径不能变成刷屏。 */
    private const val MAX_ISSUES = 30

    private var issues = 0
    private val claimed = mutableSetOf<String>()

    /**
     * 状态日志。
     *
     * 只写 logcat：这一档是高频的（每个 HTTP 状态码一行），进自定义异常会把
     * U-APM 的额度吃光，而它本身的信息量不足以单独成案。
     * 需要它的时候，`adb logcat` 一直在。
     */
    fun trace(tag: String,line: String) {
        android.util.Log.i(tag,line)
    }

    /**
     * 异常但用户无感（被兜住、已重试成功、降级可用）。
     *
     * 与 [fail] 的区别只在**聚合键与去重预算**：这一类不进 issue（用户没受影响），
     * 但原因要留下来。走自定义异常的 message，stage 前缀带 `soft:`，
     * 后台可以按前缀把「软失败」与「用户可见失败」分开筛。
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
            UMCrash.generateCustomLog(DiagnosticsException(stage,line.take(120),problem),stage,text,false,false)
            true
        }.getOrDefault(false)
        if(!first) android.util.Log.w(tag,line)
        // 调试模式那块面板要能读到「刚才发生了什么」：去重挡掉的只是上报，这里照记。
        // 但「线程被打断」不记：那是切页 / 换剧 / 退出时我们自己停掉的请求，不是故障
        // （判据见 `com.hgtv.star.core.isInterruption`）。
        if(problem==null || !com.hgtv.star.core.isInterruption(problem)) DebugLog.record("异常",line)
    }

    /**
     * 用户可感知的失败：走 U-APM 自定义异常。
     *
     * [stage] 是聚合键，必须稳定（`content_home` / `device_register` …）。
     * 同一 stage 一次启动只报一条：用户连按十次「重试」不该把后台刷成十条。
     *
     * 第三个参数 `withLogcat=true`：自定义异常会连同**当时进程的 logcat** 一起上传。
     * 这是它相对统计事件最大的价值——出问题时那几十行上下文都在里面。
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
            UMCrash.generateCustomLog(DiagnosticsException(stage,title,problem),stage,message,false,false)
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
 * 上报载体。**堆栈保持原样**，不伪造帧。
 *
 * 不在栈顶压人工帧（`fileName=null`、`line=-1`）：那样的帧没有合法的类 / 文件 / 行号，
 * 服务端的符号化与聚合解析会判它无效。阶段靠 U-APM 的 `type` 参数区分，
 * 堆栈就是真实的那一条。
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
