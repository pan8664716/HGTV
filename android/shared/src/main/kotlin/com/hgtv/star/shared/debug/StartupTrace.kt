// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.debug

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Temporary cold-start timing trace.
 *
 * Every line uses [TAG] and [Log.w], so it remains visible in release builds where
 * informational logs may be stripped by R8.
 */
object StartupTrace {
    const val TAG = "HGTV-STARTUP-PROF"

    private val bootAt = SystemClock.elapsedRealtime()

    fun now(): Long = SystemClock.elapsedRealtime()
    fun bootElapsedMs(): Long = (now() - bootAt).coerceAtLeast(0L)

    fun mark(phase: String, detail: String = "") {
        log(phase, elapsed = -1L, detail = detail)
    }

    fun done(phase: String, startedAt: Long, detail: String = "") {
        log(phase, elapsed = (now() - startedAt).coerceAtLeast(0L), detail = detail)
    }

    fun <T> section(phase: String, block: () -> T): T {
        val startedAt = now()
        return try {
            block()
        } finally {
            done(phase, startedAt)
        }
    }

    private fun log(phase: String, elapsed: Long, detail: String) {
        val now = now()
        val took = if (elapsed >= 0L) " dt=${elapsed}ms" else ""
        val extra = detail.takeIf { it.isNotBlank() }?.let { " | $it" }.orEmpty()
        Log.w(
            TAG,
            "t=+${now - bootAt}ms$took thread=${Thread.currentThread().name} | $phase$extra",
        )
    }
}

/**
 * 首屏与首帧阶段预算。
 *
 * 网络层仍有自己的连接 / 读取超时；这里的预算只约束启动关键路径，
 * 到点后主动取消该阶段已经挂到 [com.hgtv.star.core.RequestScope] 上的请求，
 * 避免注册、推荐、详情或播放准备把用户无限期卡在加载态。
 */
object StartupBudgets {
    const val REGISTER_MS = 6_000L
    const val FEED_FIRST_MS = 4_000L
    const val DETAIL_MS = 4_000L
    const val STREAM_MS = 6_000L
    const val PREPARE_MS = 8_000L
}

/**
 * 首帧之后才值得启动的工作。
 *
 * 播放首帧会立即放行；如果首帧一直没来（例如设置页停留、播放失败），
 * 每个任务仍有独立兜底时间，不能永久不执行。
 */
object StartupDeferredTasks {
    private data class Task(
        val action: () -> Unit,
        val timeout: ScheduledFuture<*>?,
    )

    private val lock = Any()
    private val scheduledKeys = HashSet<String>()
    private val tasks = LinkedHashMap<String, Task>()
    private val playbackReady = AtomicBoolean(false)
    private val executor = Executors.newScheduledThreadPool(2) { runnable ->
        Thread(runnable, "hgtv-startup-deferred").apply { isDaemon = true }
    }

    fun schedule(key: String, timeoutMs: Long = 20_000L, action: () -> Unit) {
        var runNow = false
        synchronized(lock) {
            if (!scheduledKeys.add(key)) return
            if (playbackReady.get()) {
                runNow = true
            } else {
                val timeout = executor.schedule(
                    { run(key, "timeout") },
                    timeoutMs.coerceAtLeast(1L),
                    TimeUnit.MILLISECONDS,
                )
                tasks[key] = Task(action, timeout)
            }
        }
        if (runNow) submit(key, "already-ready", action)
    }

    fun notifyPlaybackFirstFrame() {
        if (!playbackReady.compareAndSet(false, true)) return
        val ready = synchronized(lock) {
            val values = tasks.values.toList()
            tasks.clear()
            values
        }
        ready.forEachIndexed { index, task ->
            task.timeout?.cancel(false)
            submit("first-frame-$index", "first-frame", task.action)
        }
        StartupTrace.mark("startup.deferred.release", "tasks=${ready.size}")
    }

    private fun run(key: String, reason: String) {
        val task = synchronized(lock) { tasks.remove(key) } ?: return
        submit(key, reason, task.action)
    }

    private fun submit(key: String, reason: String, action: () -> Unit) {
        executor.execute {
            val startedAt = StartupTrace.now()
            StartupTrace.mark("startup.deferred.start", "key=$key reason=$reason")
            runCatching { action() }
                .onFailure { problem ->
                    Log.w(
                        StartupTrace.TAG,
                        "startup.deferred.failed key=$key " +
                            "error=${problem.javaClass.simpleName}:${problem.message.orEmpty().take(160)}",
                    )
                }
            StartupTrace.done("startup.deferred", startedAt, "key=$key reason=$reason")
        }
    }
}

/**
 * 从进程打开到推荐首帧的同一条追踪链。
 *
 * 现有 `StartupTrace` 能看单段耗时，但推荐页、注册、详情和播放内核分散在不同模块；
 * 这里用同一个 launch id 把用户真正感受到的链路串起来。
 */
object PlaybackStartupTrace {
    private val launchId = Integer.toHexString(System.identityHashCode(this))
    private val firstFrameLogged = AtomicBoolean(false)

    fun point(phase: String, detail: String = "") {
        StartupTrace.mark(
            "play.$phase",
            "launch=$launchId sinceLaunch=${StartupTrace.bootElapsedMs()}ms" +
                detail.takeIf { it.isNotBlank() }?.let { " | $it" }.orEmpty(),
        )
    }

    fun begin(phase: String, detail: String = ""): Long {
        val startedAt = StartupTrace.now()
        point("$phase.start", detail)
        return startedAt
    }

    fun done(phase: String, startedAt: Long, detail: String = "") {
        StartupTrace.done(
            "play.$phase",
            startedAt,
            "launch=$launchId sinceLaunch=${StartupTrace.bootElapsedMs()}ms" +
                detail.takeIf { it.isNotBlank() }?.let { " | $it" }.orEmpty(),
        )
    }

    fun firstFrame(source: String, detail: String = "") {
        if (!firstFrameLogged.compareAndSet(false, true)) return
        point(
            "firstFrame",
            "source=$source sinceLaunch=${StartupTrace.bootElapsedMs()}ms" +
                detail.takeIf { it.isNotBlank() }?.let { " | $it" }.orEmpty(),
        )
        StartupDeferredTasks.notifyPlaybackFirstFrame()
    }
}
