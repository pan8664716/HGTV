// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import okhttp3.Call
import okhttp3.Response
import java.io.IOException

/**
 * 网络失败的唯一出口。
 *
 * 为什么需要：首页/详情这些接口的异常最终都被界面翻成一句提示，logcat 里什么都不留。
 * 客户设备上「首页一直是空的」时，只能看到 [com.hgtv.star.core.ContentRepository] 的
 * `Home displayed`，看不到到底是 SNI、证书还是协议版本挂的。
 *
 * 由 `MainActivity` 接到 `Log.i("HGTV-NET", ...)`；不接就是空操作。
 * 只记录**非取消**的 IO 失败（用户切页时会大量 cancel，那些不是问题）。
 */
/**
 * 这条异常是不是「线程被打断」造成的（而不是真的连不上）。
 *
 * 判据有三条，缺一不可地覆盖 Android 上几种写法：
 * * 类型是 [java.io.InterruptedIOException]（OkHttp 在 `Thread.interrupt()` 后抛的就是它）；
 * * 消息里带 `interrupted` / `interrupt`（部分厂商 ROM 包成普通 `IOException`）；
 * * 消息是 OkHttp 自己的 `Canceled`（取消域主动 cancel，与「被打断」同一类）。
 *
 * 这类异常在调试面板里**不记**：它们全部来自我们自己切页 / 换剧 / 退出时的取消，
 * 记下来只会把真正的网络故障挤出那 40 条近况，看起来像「一直在报错」。
 */
fun isInterruption(problem: Throwable): Boolean {
    var current: Throwable? = problem
    var depth = 0
    while (current != null && depth < 6) {
        if (current is java.io.InterruptedIOException) return true
        val message = current.message.orEmpty()
        if (message.contains("interrupt", ignoreCase = true) || message.equals("Canceled", ignoreCase = true)) return true
        current = current.cause
        depth++
    }
    return false
}

object NetLog {
    var log: (String) -> Unit = {}

    /** 当前线程是否处于「不逐条上报」的静默段（见 [quiet]）。 */
    private val silent=ThreadLocal<Boolean>()

    /**
     * 这段请求不逐条上报，由调用方在结束时汇总一条。
     *
     * 为什么需要：官网搜索一次要并发拉 10 个物理页，后端抖动时每个 502 都会经
     * [RequestScope.execute] 写进调试面板的「近况」。一次搜索就能把 40 条环形缓冲刷满，
     * 用户看到的是一屏 `HTTP 502`，读起来像「整个搜索坏了」——而实际上多数页是好的。
     * 静默段里仍然照常抛异常（判读逻辑不变），只是不往面板里逐条写。
     */
    fun <T> quiet(action: () -> T): T {
        val previous=silent.get()
        silent.set(true)
        try {
            return action()
        } finally {
            if(previous==null) silent.remove() else silent.set(previous)
        }
    }

    /** 真正的上报口：[RequestScope] 走这一条，静默段里直接丢掉。 */
    internal fun emit(line: String) {
        if(silent.get()==true) return
        log(line)
    }
}

/** Cancels only the work owned by one navigation/playback intent, including body reads. */
class RequestScope {
    private var cancelled = false
    private val calls = mutableSetOf<Call>()

    @Synchronized fun cancel() {
        cancelled = true
        calls.forEach { it.cancel() }
        calls.clear()
    }

    @Synchronized private fun attach(call: Call) {
        if (cancelled || Thread.currentThread().isInterrupted) {
            call.cancel()
            throw IOException("请求已取消")
        }
        calls += call
    }

    @Synchronized private fun detach(call: Call) { calls -= call }

    fun <T> run(action: () -> T): T {
        val previous = current.get()
        current.set(this)
        try {
            synchronized(this) { if (cancelled) throw IOException("请求已取消") }
            return action()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    companion object {
        private val current = ThreadLocal<RequestScope>()

        /**
         * 当前线程所属的取消域；不在任何域里时返回 null。
         *
         * 首页要并发取四个榜单，而 [RequestScope] 是**按线程**记录的：工作线程池里的线程
         * 看不到界面线程那一次导航的域，页面切走时也就取消不掉在途的请求。
         * 因此调用方先把界面线程的域取出来（[current]），再用 [withScope] 交给工作线程。
         */
        fun current(): RequestScope? = current.get()

        /** 让这段代码在给定的取消域里跑：域已经取消就直接抛「请求已取消」。 */
        fun <T> withScope(scope: RequestScope?, action: () -> T): T = if (scope == null) action() else scope.run(action)

        fun <T> execute(call: Call, consume: (Response) -> T): T {
            val owner = current.get()
            owner?.attach(call)
            try {
                return call.execute().use { response ->
                    // 非 2xx 也留一行：签名过期 / 设备时间不对时服务端会这么回，
                    // 而界面只显示「取不到」，logcat 里原本一点线索都没有。
                    if (!response.isSuccessful) {
                        val url = call.request().url()
                        NetLog.emit("${url.host()}${url.encodedPath()} HTTP ${response.code()}")
                    }
                    consume(response)
                }
            }
            catch (e: IOException) {
                // 「线程被打断」不是网络故障：切页 / 换剧 / 退出时取消域会打断工作线程，
                // OkHttp 于是抛 `InterruptedIOException: interrupted`。它是**我们自己**主动停的，
                // 记进调试面板只会把真正的失败挤出那 40 条近况。
                if (!call.isCanceled() && !isInterruption(e)) {
                    val url = call.request().url()
                    NetLog.emit("${url.host()}${url.encodedPath()} ${e.javaClass.simpleName}: ${e.message}")
                }
                throw e
            }
            finally { owner?.detach(call) }
        }
    }
}
