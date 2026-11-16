// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.debug
import com.hgtv.star.core.AppConfig

/**
 * 调试模式的「近况」环形缓冲：两端共用。
 *
 * 电视端与手机端都有「设置 → 调试模式」：打开之后，面板上除了设备 / 播放现场，
 * 还要能看到**刚才发生了什么**（接口失败、登录失败、播放失败）。这条缓冲本身与
 * 平台无关，因此放在 `:shared`，两端只负责把它画出来。
 *
 * 只在打开调试模式时才写（见 [enabled]）：正常使用时不拼一次字符串。
 */
const val DEBUG_LOG_LIMIT = AppConfig.Diagnostics.DEBUG_LOG_LIMIT

/**
 * 最近发生的事：网络、接口、登录、播放失败。
 *
 * 只在**打开调试模式**时才写（见 [DebugLog.record] 的判据由调用方给），
 * 因此正常使用时它连一次字符串拼接都没有。
 */
object DebugLog {
    private val entries=ArrayDeque<String>()

    /**
     * 调试模式是否打开。
     *
     * [record] 的调用点里有每一条 HTTP 状态（见 `NetLog`），关掉时必须**先判这一位就返回**，
     * 否则正常使用也会为每一条网络日志拼一次字符串。
     */
    @Volatile var enabled=false

    /**
     * 记一条近况。
     *
     * [force] 用于**播放失败**这一类：它们不常发生（一次启动最多几条），而用户往往是
     * 「先看到错误码、之后才去开调试模式」。关着就丢的话，打开面板时那条已经没了，
     * 用户报的错误码对不上任何现场。因此这几条无条件留下，其余照旧只在调试模式里记。
     */
    @Synchronized fun record(tag: String,line: String,force: Boolean=false) {
        if(!enabled && !force) return
        val clean=line.replace('\n',' ').take(220)
        entries.addLast("${clock()} $tag $clean")
        while(entries.size>DEBUG_LOG_LIMIT) entries.removeFirst()
    }

    /**
     * 取最近 [count] 条。
     *
     * 只拷要用的这几条：原先 `entries.toList().takeLast(count)` 每一轮都把整圈（40 条）
     * 复制成一个新 List 再丢掉 35 条，而面板播放页是每秒问一次。
     */
    @Synchronized fun lines(count: Int): List<String> {
        if(count<=0 || entries.isEmpty()) return emptyList()
        val from=(entries.size-count).coerceAtLeast(0)
        val out=ArrayList<String>(entries.size-from)
        for(index in from until entries.size) out.add(entries[index])
        return out
    }

    @Synchronized fun clear() = entries.clear()

    /**
     * 日志时刻：**墙上时间**（`HH:mm:ss`）。
     *
     * 早先用的是 `SystemClock.elapsedRealtime()`（开机以来的时长，按 `mm:ss` 显示）：
     * 面板上出现的是一串与用户手表完全无关的数字，报「几点开始卡的」时对不上，
     * 分钟位还会在 60 分钟处绕回 00。近况是给客服对时用的，必须是真实时刻。
     */
    private fun clock(): String = WALL_CLOCK.format(java.util.Date())

    private val WALL_CLOCK=java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.CHINA)
}
