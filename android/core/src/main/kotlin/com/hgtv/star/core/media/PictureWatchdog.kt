// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 「有声音、没画面」与「一直卡在缓冲」这两种**不会抛异常**的故障的判据。
 *
 * ## 为什么需要它
 *
 * 用户报得最多的一类问题不是「播放失败」，而是「有声音没画面」「一直在转圈」：
 * 播放器没报错、时间轴在走、音频在响，屏幕上却什么都没有。这类故障在两端都出现过
 * （电视盒子的 HEVC 硬解、手机的隧道模式与厂商解码器），而它们的判据与处置完全一样，
 * 因此收敛在这里——纯逻辑、可单测，两端只负责喂时间戳与执行处置。
 *
 * ## 两条判据（与电视端现场磨出来的阈值一致）
 *
 * | 现象 | 起点 | 阈值 |
 * | --- | --- | --- |
 * | 已就绪但一直没画面 | **进入 READY 那一刻** | [NO_FRAME_MS]（15 秒） |
 * | 一直没进入可播放状态 | 起播那一刻 | [STALL_MS]（90 秒） |
 *
 * 「就绪」必须单独起算：解码器初始化 + 首帧渲染都发生在 READY 之后，
 * 从起播算起会把「起播慢」误判成「没画面」。
 *
 * ## 三件必须让路的事
 *
 * * **暂停 / 退到后台**：那不是卡死。调用方在这些时刻 [disarm]，回来重新 [arm]——
 *   不能只「跳过几次 check」：暂停两分钟再继续会当场命中 stall_buffer，白做一次整层重建；
 * * **用户本来就没让它播**：不自动播的那一次不该被盯；
 * * **已经出过画面**：看门狗到此为止，不再判。
 */
class PictureWatchdog(
    private val clock: () -> Long,
    private val noFrameMs: Long = NO_FRAME_MS,
    private val stallMs: Long = STALL_MS,
) {

    /** 看门狗给出的结论。 */
    enum class Verdict {
        /** 一切正常（或还没到阈值）：继续等。 */
        WAITING,
        /** 播放器已就绪，但过了 [NO_FRAME_MS] 仍然一帧都没有。 */
        NO_FRAME,
        /** 过了 [STALL_MS] 还没进入可播放状态。 */
        STALL_BUFFER,
        /** 已经出过画面 / 已经放弃：这条看门狗不再有意义。 */
        DONE,
    }

    private var armedAt = 0L
    private var readyAt = 0L
    private var sawFrame = false
    private var running = false

    /** 是否还在盯。 */
    val active: Boolean get() = running

    /** 起播：从现在开始盯。[alreadyReady] = 起播这一刻播放器已经是 READY。 */
    fun arm(alreadyReady: Boolean = false) {
        val now = clock()
        armedAt = now
        readyAt = if (alreadyReady) now else 0L
        sawFrame = false
        running = true
    }

    /** 播放器进入 READY。 */
    fun onReady() {
        if (!running || readyAt != 0L) return
        readyAt = clock()
    }

    /**
     * 出了一帧。
     *
     * 一旦出过帧就**永久**算「有画面」：偶发掉帧不是这个看门狗要管的事
     * （那是另一个「停帧」判据，它要看的是「最近一帧距今多久」，不是「有没有出过帧」）。
     */
    fun onFrame() {
        sawFrame = true
    }

    /** 撤销（换集 / 出错 / 离开播放页）。 */
    fun disarm() {
        running = false
        armedAt = 0L
        readyAt = 0L
        sawFrame = false
    }

    /**
     * 判一次。调用方按 [Verdict] 决定做什么：
     * [Verdict.NO_FRAME] / [Verdict.STALL_BUFFER] 各处置一次之后必须 [disarm]，
     * 否则同一个故障会被反复处置。
     */
    fun check(): Verdict {
        if (!running) return Verdict.DONE
        if (sawFrame) {
            running = false
            return Verdict.DONE
        }
        val now = clock()
        if (readyAt != 0L) {
            return if (now - readyAt >= noFrameMs) Verdict.NO_FRAME else Verdict.WAITING
        }
        return if (now - armedAt >= stallMs) Verdict.STALL_BUFFER else Verdict.WAITING
    }

    companion object {
        /** 就绪之后等多久还没画面就判「有声音没画面」。 */
        const val NO_FRAME_MS = AppConfig.Playback.WATCHDOG_NO_FRAME_MS

        /** 起播之后等多久还没进入可播放状态就判「卡在缓冲」。 */
        const val STALL_MS = AppConfig.Playback.WATCHDOG_STALL_MS

        /** 轮询间隔。必须**远小于** [NO_FRAME_MS]：否则「READY 了但没画面」会被拖到 30 秒才处置。 */
        const val TICK_MS = AppConfig.Playback.WATCHDOG_TICK_MS
    }
}
