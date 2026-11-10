// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.feed

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.hgtv.star.core.SlideCacheManager

/**
 * 推荐流「相邻预热」的**排程编排**：电视与手机共用这一份。
 *
 * ## 为什么要抽出来
 *
 * 窗口怎么算、快滑怎么降级、回看怎么升级，本来就在 `core/SlideCache.kt` 里（纯逻辑、带单测）。
 * 但**什么时候真的去拉**是另一套规则，两端各自写了一遍：
 *
 * | 规则 | 两端原本的实现 |
 * | --- | --- |
 * | 首帧之后延迟（`SlideCacheManager.firstFrameDelay()`）再发 | 各写一次 `postDelayed` |
 * | 当前这一部 buffer < 1.5 秒就整轮让路，0.75 秒后再试 | 各写一次 |
 * | 连划之后停 1 秒，把主方向升回完整预热 | 各写一次 |
 * | 离开推荐页 / 换剧就取消排定的那一次 | 各写一次 |
 *
 * 四件事一样、写法两样，结果是「同一个问题修在了一端、另一端照旧」——
 * 手机端早先那次「被节流的那一次把已排好的预热一起取消」就是只有一端踩到、
 * 另一端没踩到才暴露出来的。现在只有这一份实现。
 *
 * ## 各端的差异只在注入的这几个 lambda 里
 *
 * * [anchor]：现在停在列表的第几条；
 * * [active]：这一页还在前台吗（离开就丢弃排定的那一次）；
 * * [gateOpen]：带宽能不能让出来（正在看的那一部 buffer 够不够）；
 * * [plan]：这一轮要按哪份计划发（手机端会优先用手上那份带 `REVISIT` / `FLING` 判定结果的）；
 * * [apply]：真正去取详情 / 取地址 / 拉首块。
 *
 * 排程本身（延迟、让路重试、停留确认、取消）不留给各端改写。
 */
class PreloadScheduler(
    private val slideCache: SlideCacheManager,
    private val anchor: () -> Int,
    private val active: () -> Boolean,
    private val gateOpen: () -> Boolean,
    private val plan: (reason: String) -> SlideCacheManager.Plan?,
    private val apply: (SlideCacheManager.Plan, String) -> Unit,
    private val main: Handler = Handler(Looper.getMainLooper()),
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
) {
    /** 让路之后隔多久再看一次（电视端与手机端原本都是这个值）。 */
    private var windowTask: Runnable? = null
    private var dwellTask: Runnable? = null

    /** 现在有没有排着的预热（窗口那一次）。 */
    val windowScheduled: Boolean get() = windowTask != null

    /**
     * 排定一次窗口预热。
     *
     * 重复调用只保留最后一次：用户连着换了几部，只有最后停下的那一部值得预热。
     */
    fun scheduleWindow(delayMs: Long, reason: String) {
        cancelWindow()
        post(delayMs, reason)
    }

    /**
     * 停留确认：连划之后停下来了，把主方向那一步重新升回完整预热。
     *
     * 只在**刚才是在连划**时有意义（[SlideCacheManager.mode] 为 `FLING`）：
     * 正常模式下主方向第一步本来就是完整预热，1 秒时再跑一遍只会提前跟 buffer 抢带宽。
     */
    fun scheduleDwell() {
        cancelDwell()
        if (slideCache.mode != SlideCacheManager.Mode.FLING) return
        val task = Runnable {
            dwellTask = null
            if (!active()) return@Runnable
            val index = anchor()
            if (index < 0) return@Runnable
            val promoted = slideCache.onDwell(index, now())
            if (promoted.mode != SlideCacheManager.Mode.NORMAL) return@Runnable
            if (!gateOpen()) return@Runnable
            apply(promoted, "dwell")
        }
        dwellTask = task
        main.postDelayed(task, DWELL_DELAY_MS)
    }

    /** 离开推荐页 / 换剧 / 宿主销毁：两条排定都取消。 */
    fun cancel() {
        cancelWindow()
        cancelDwell()
    }

    private fun post(delayMs: Long, reason: String) {
        val task = Runnable {
            windowTask = null
            if (!active()) return@Runnable
            if (anchor() < 0) return@Runnable
            // 带宽让路：用户正盯着的那一帧比「下一步可能看什么」重要得多。
            // 让路不是放弃，0.75 秒后再看一次（那时 buffer 通常已经够了）。
            if (!gateOpen()) {
                post(RETRY_DELAY_MS, "retry")
                return@Runnable
            }
            val planned = plan(reason) ?: return@Runnable
            apply(planned, reason)
        }
        windowTask = task
        main.postDelayed(task, delayMs)
    }

    private fun cancelWindow() {
        windowTask?.let { main.removeCallbacks(it) }
        windowTask = null
    }

    private fun cancelDwell() {
        dwellTask?.let { main.removeCallbacks(it) }
        dwellTask = null
    }

    private companion object {
        /** 让路之后重试的间隔：缓冲一旦够，就尽快把相邻一部接上。 */
        const val RETRY_DELAY_MS = 750L

        /** 停留多久算「确认」（与 `SlideCacheManager.dwellPromoteMs` 同值）。 */
        const val DWELL_DELAY_MS = 600L
    }
}
