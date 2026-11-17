// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 网络失败的唯一出口。
 *
 * 为什么需要：首页/详情这些接口的异常最终都被界面翻成一句提示，logcat 里什么都不留。
 * 客户设备上「首页一直是空的」时，只能看到 [com.hgtv.star.core.ContentRepository] 的
 * `Home displayed`，看不到到底是 SNI、证书还是协议版本挂的。
 *
 * 由 `TvMainActivity` 接到 `Log.i("HGTV-NET", ...)`；不接就是空操作。
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
        val message = current.message.orEmpty()
        // 超时同样是 InterruptedIOException，但它不是「用户取消」：必须进入失败处置。
        if (current is java.io.InterruptedIOException &&
            !message.contains("timeout",ignoreCase=true)) return true
        if (message.contains("interrupt", ignoreCase = true) || message.equals("Canceled", ignoreCase = true)) return true
        current = current.cause
        depth++
    }
    return false
}

object NetLog {
    var log: (String) -> Unit = {}
    /**
     * 分级日志出口。DEBUG 只用于请求/响应现场，WARN 用于重试，ERROR 用于请求失败。
     *
     * 电视端和手机端各自把它接到 logcat；未接线时退回原来的 [log]，保持旧调用点行为不变。
     */
    var event: (NetLogLevel, String) -> Unit = { _, line -> log(line) }
    /**
     * 是否记录每个请求的连接耗时分解（DNS / TCP / TLS / 首字节）。
     *
     * 由 `HgtvApplication` 按 `BuildConfig.DEBUG` 置位：只有调试包才装这个监听器
     * （见 `defaultHttpClient`），正式包一个字节的开销都不加。
     */
    @Volatile var timing: Boolean = false
    /** 是否记录每个 API 请求的请求/响应与重试现场。只由调试包打开。 */
    @Volatile var apiLogging: Boolean = false

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
    fun emit(line: String) {
        if(silent.get()==true) return
        log(line)
    }

    fun debug(line: String) {
        if(!apiLogging) return
        event(NetLogLevel.DEBUG, line)
    }

    fun warn(line: String) {
        if(!apiLogging) return
        event(NetLogLevel.WARN, line)
    }

    fun error(line: String) {
        if(!apiLogging) return
        event(NetLogLevel.ERROR, line)
    }

    /**
     * 重试现场。不要在这里带请求体、Cookie、token 或签名。
     *
     * [nextAttempt] 是从 1 开始的“即将执行”的尝试号，[maxAttempts] 是总次数。
     */
    fun retry(
        scope: String,
        nextAttempt: Int,
        maxAttempts: Int,
        reason: String = "",
        delayMs: Long = -1L,
    ) {
        if(!apiLogging) return
        val detail=buildString {
            append("[API] retry scope=").append(scope)
            append(" attempt=").append(nextAttempt).append('/').append(maxAttempts)
            if(delayMs>=0L) append(" delay=").append(delayMs).append("ms")
            if(reason.isNotBlank()) append(" reason=").append(reason.replace('\n',' ').take(220))
        }
        warn(detail)
    }
}

enum class NetLogLevel { DEBUG, WARN, ERROR }

/**
 * 一次 API 请求的诊断上下文。
 *
 * 只记录方法、主机、路径、query key、body 大小、状态码、耗时和少量响应摘要。
 * **不记录 query value、Cookie、token、签名头或完整响应体**。
 */
private class ApiRequestTrace(
    private val id: Long,
    private val request: Request,
    private val startedAt: Long,
) {
    private var errorResponse=false

    fun start() {
        NetLog.debug("[API] start ${requestLine()}")
    }

    fun response(response: Response) {
        errorResponse=!response.isSuccessful
        val elapsed=(System.nanoTime()-startedAt)/1_000_000L
        val summary=responseSummary(response)
        val line="[API] response id=$id status=${response.code} ${elapsed}ms "+
            "body=${bodyLength(response)} $summary ${requestLine(includeId=false)}"
        if(response.isSuccessful) NetLog.debug(line) else NetLog.error(line)
    }

    fun failure(problem: IOException) {
        if(errorResponse) return
        val elapsed=(System.nanoTime()-startedAt)/1_000_000L
        NetLog.error(
            "[API] error id=$id ${elapsed}ms ${problem.javaClass.simpleName}: "+
                "${problem.message.orEmpty().take(220)} ${requestLine(includeId=false)}"
        )
    }

    private fun requestLine(includeId: Boolean = true): String {
        val url=request.url
        val queryKeys=url.queryParameterNames().joinToString(",").take(180)
        val bodySize=runCatching { request.body?.contentLength() }.getOrNull()
        val identity=when {
            request.header("X-HGTV-Anonymous")!=null -> "anonymous"
            request.header("X-HGTV-Identity")=="account" -> "account"
            else -> "main"
        }
        return buildString {
            if(includeId) append("id=").append(id).append(' ')
            append("method=").append(request.method)
            append(" host=").append(url.host)
            append(" path=").append(url.encodedPath)
            if(queryKeys.isNotBlank()) append(" queryKeys=").append(queryKeys)
            append(" body=").append(bodySize?.toString() ?: "?")
            append(" identity=").append(identity)
        }
    }

    private fun bodyLength(response: Response): String {
        val length=runCatching { response.body?.contentLength() }.getOrNull() ?: -1L
        return if(length>=0L) "${length}B" else "?"
    }

    private fun responseSummary(response: Response): String {
        val headers=buildString {
            response.header("x-tt-logid")?.takeIf { it.isNotBlank() }?.let {
                append(" logid=").append(it.take(48))
            }
            response.header("x-tt-tnc-control")?.takeIf { it.isNotBlank() }?.let {
                append(" tnc=").append(it.take(24))
            }
            response.header("x-ss-etag")?.takeIf { it.isNotBlank() }?.let {
                append(" etag=").append(it.take(48))
            }
            response.header("tt_stable")?.takeIf { it.isNotBlank() }?.let {
                append(" stable=").append(it.take(16))
            }
        }
        val body=runCatching { response.peekBody(4096).string() }.getOrNull().orEmpty()
        if(body.isBlank()) return "empty=true$headers"
        val json=runCatching { JSONObject(body) }.getOrNull() ?: return "empty=false$headers"
        val code=json.opt("code")?.toString()?.take(32).orEmpty()
        val message=(json.optString("message").ifBlank { json.optString("msg") }).replace('\n',' ').take(120)
        return buildString {
            append("empty=false")
            if(code.isNotBlank()) append(" code=").append(code)
            if(message.isNotBlank()) append(" message=").append(message)
            append(headers)
        }
    }
}

private val apiRequestSequence=AtomicLong(0L)

private fun requestTrace(call: Call): ApiRequestTrace? {
    if(!NetLog.apiLogging) return null
    val request=call.request()
    // 视频 Range 分片不是业务 API；播放取地址、详情、搜索等没有 Range，照常记录。
    if(!request.header("Range").isNullOrBlank()) return null
    return ApiRequestTrace(
        id=apiRequestSequence.incrementAndGet(),
        request=request,
        startedAt=System.nanoTime(),
    )
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
         * 首页要并发取八个榜单，而 [RequestScope] 是**按线程**记录的：工作线程池里的线程
         * 看不到界面线程那一次导航的域，页面切走时也就取消不掉在途的请求。
         * 因此调用方先把界面线程的域取出来（[current]），再用 [withScope] 交给工作线程。
         */
        fun current(): RequestScope? = current.get()

        /** 让这段代码在给定的取消域里跑：域已经取消就直接抛「请求已取消」。 */
        fun <T> withScope(scope: RequestScope?, action: () -> T): T = if (scope == null) action() else scope.run(action)

        fun <T> execute(call: Call, consume: (Response) -> T): T {
            val owner = current.get()
            owner?.attach(call)
            val trace=requestTrace(call)
            trace?.start()
            try {
                return call.execute().use { response ->
                    trace?.response(response)
                    // 非 2xx 也留一行：签名过期 / 设备时间不对时服务端会这么回，
                    // 而界面只显示「取不到」，logcat 里原本一点线索都没有。
                    if (!response.isSuccessful && !NetLog.apiLogging) {
                        val url = call.request().url
                        NetLog.emit("${url.host}${url.encodedPath} HTTP ${response.code}")
                    }
                    consume(response)
                }
            }
            catch (e: IOException) {
                // 「线程被打断」不是网络故障：切页 / 换剧 / 退出时取消域会打断工作线程，
                // OkHttp 于是抛 `InterruptedIOException: interrupted`。它是**我们自己**主动停的，
                // 记进调试面板只会把真正的失败挤出那 40 条近况。
                if (!call.isCanceled() && !isInterruption(e)) {
                    trace?.failure(e)
                }
                if (!call.isCanceled() && !isInterruption(e) && !NetLog.apiLogging) {
                    val url = call.request().url
                    NetLog.emit("${url.host}${url.encodedPath} ${e.javaClass.simpleName}: ${e.message}")
                }
                throw e
            }
            finally { owner?.detach(call) }
        }
    }
}

/** 一个启动关键阶段超过预算。调用方可以据此跳过当前推荐或显示可重试错误。 */
class StageTimeoutException(
    val stage: String,
    cause: Throwable? = null,
) : IOException("$stage 超过启动预算", cause)

/**
 * 给一段阻塞网络 / 本地准备加硬预算。
 *
 * 计时到点后取消同一 [RequestScope] 内的 OkHttp Call；如果代码还没开始发请求，
 * 下一次进入 [RequestScope.execute] 会立即抛出“请求已取消”，最终统一转换成
 * [StageTimeoutException]。不依赖协程取消，因为当前启动链路大量使用阻塞调用。
 */
class StageDeadline(
    private val name: String,
    timeoutMs: Long,
) {
    private val scope = RequestScope()
    private val timedOut = AtomicBoolean(false)
    private val timeout: ScheduledFuture<*>? =
        if (timeoutMs > 0L) {
            scheduler.schedule(
                {
                    timedOut.set(true)
                    scope.cancel()
                },
                timeoutMs,
                TimeUnit.MILLISECONDS,
            )
        } else {
            null
        }

    fun <T> run(action: () -> T): T {
        try {
            val value = scope.run(action)
            if (timedOut.get()) throw StageTimeoutException(name)
            return value
        } catch (problem: StageTimeoutException) {
            throw problem
        } catch (problem: Throwable) {
            if (timedOut.get()) throw StageTimeoutException(name, problem)
            throw problem
        }
    }

    /**
     * 不使用 `AutoCloseable` / `Closeable`。
     *
     * 少数 4.x 盒子把 `SDK_INT` 报成 19，框架实际仍是 4.2；加载带
     * `AutoCloseable` 超类型的类会直接失败。这里保留原来的 `use {}` 调用形式，
     * 由普通成员函数负责 finally 关闭。
     */
    inline fun <T> use(block: (StageDeadline) -> T): T =
        try {
            block(this)
        } finally {
            close()
        }

    fun close() {
        timeout?.cancel(false)
        if (timedOut.get()) scope.cancel()
    }

    private companion object {
        private val scheduler = Executors.newScheduledThreadPool(1) { runnable ->
            Thread(runnable, "hgtv-stage-deadline").apply { isDaemon = true }
        }
    }
}
