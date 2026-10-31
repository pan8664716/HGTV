// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 快进 / 快退的**调度外框**：连按计数、合并窗、待落地目标与最终那一次 seek。
 *
 * [SeekStep] 管的是「跳多少、跳到哪」，这里管的是「什么时候真的跳、连按怎么攒」。
 * 拆成两层是因为电视与手机的**触发源不同**（遥控器按键 repeat / 触屏点击），
 * 但「窗内合并、攒够就加速、一次落地」这套手感必须一样。
 *
 * 手机端两处入口（推荐流与播放页）原先各写了一份 `tap/tapAt/pending` 三件套 + 一个
 * 延时 Runnable，改一处忘另一处就是「推荐页能合并、播放页不能」。现在两边只留一个
 * 定时器，状态都在这里。
 *
 * 线程：全部在主线程调用（定时器也是主线程 Handler），因此不加锁。
 *
 * @param now 单调时钟（`SystemClock.elapsedRealtime`）。
 * @param position 播放器**当前**位置；合并窗里还没落地的跳转不算在内（那是 [pendingTarget]）。
 * @param duration 当前这一集的时长；<=0 时 [request] 返回 null，调用方什么都不做。
 * @param seek 真正落地的那一次跳转。
 */
class SeekStepPump(
    private val now: () -> Long,
    private val position: () -> Long,
    private val duration: () -> Long,
    private val seek: (Long) -> Unit,
) {

    /** 一次请求的结果：[stepMs] 用来写提示（「快进 30s」），[targetMs] 是落地位置。 */
    data class Plan(val stepMs: Long, val targetMs: Long, val tap: Int)

    private var tap = 0
    private var tapAt = 0L

    /** 合并窗里还没落地的目标（-1 = 没有在途）。 */
    var pendingTarget: Long = -1L
        private set

    /**
     * 记下一次快退 / 快进意图；到达合并窗之后由调用方调 [flush]。
     *
     * 返回 null 表示这次按不了（没有播放器 / 时长未知），调用方连提示都不该写。
     */
    fun request(direction: Int, seconds: Int): Plan? {
        val total = duration()
        if (total <= 0) return null
        tap = SeekStep.nextTap(tap, now() - tapAt)
        tapAt = now()
        val step = SeekStep.stepMs(seconds, tap)
        val from = if (pendingTarget >= 0) pendingTarget else position()
        val target = SeekStep.target(from, direction, step, total)
        pendingTarget = target
        return Plan(step, target, tap)
    }

    /** 合并窗到点：把在途目标真正交出去。返回是否发生了跳转。 */
    fun flush(): Boolean {
        val target = pendingTarget
        pendingTarget = -1L
        if (target < 0) return false
        seek(target)
        return true
    }

    /**
     * 作废在途跳转与连按计数。
     *
     * 三处必调：换集（时间轴变了）、手拖进度条（用户已经明确去了别处）、
     * 无感换集。不调的话，那一步会在新时间轴上落地，读起来就是「刚换集就自己往前跳」。
     */
    fun cancel() {
        pendingTarget = -1L
        tap = 0
        tapAt = 0L
    }
}
