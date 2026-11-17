// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration

/**
 * 播放界面的手势状态机（竖屏信息流与播放页共用这一份）。
 *
 * ```
 * DOWN ──► PRESSING ──(450ms 无位移)──► LONG_PRESSED ──UP──► 结束（2 倍速 / 设置）
 *            │
 *            ├──(横向位移 > slop)──► DRAGGING ──UP──► 提交 seek
 *            ├──(纵向位移 > slop，正式播放页)──► VERTICAL ──UP──► 上一集 / 下一集（横竖屏都接管）
 *            │
 *            └──(UP)──► 信息流：立刻单击；正式播放器：等 260ms 看有没有第二次点击
 *                         ├── 有 ──► 双击
 *                         └── 无 ──► 单击
 * ```
 *
 * 三条优先级由状态迁移保证，不再靠「堆监听器、各自判一次」：
 * 进入 DRAGGING 之后不再判长按；长按一旦触发就不再算单击；横屏的单击要等双击窗口结束。
 *
 * 信息流不等双击窗口：它的单击是「播放 / 暂停」，等 260ms 会让人觉得点了没反应。
 * 正式播放器横竖屏都启用双击，因此单击显隐信息、双击播放 / 暂停。
 */
class PhoneTouch(
    private val host: View,
    /** 当前是不是横屏；横屏横滑从任意位置起手，竖屏保留左右系统返回手势区。 */
    private val isLandscape: () -> Boolean,
    private val onTap: () -> Unit,
    private val onDoubleTap: () -> Unit,
    private val onLongPress: (Zone) -> Unit,
    private val onLongPressEnd: () -> Unit,
    /**
     * 横滑是否接管为拖进度。
     *
     * 竖屏也开：短剧应用里竖屏左右滑调进度是常规操作。但竖屏的左右边缘是系统返回手势，
     * 因此**起点必须离屏幕左右边缘 [EDGE_GUARD_DP] 之外**才接管（见 [edgeSafe]）。
     */
    private val dragEnabled: Boolean = true,
    private val onDragStart: () -> Unit = {},
    /** 横向位移占屏宽的比例（可正可负），由调用方换算成时间。 */
    private val onDrag: (Float) -> Unit = {},
    private val onDragEnd: () -> Unit = {},
    /**
     * 纵滑是否接管为「上一集 / 下一集」。
     *
     * 只有**正式播放页**开：信息流竖滑是翻下一条视频（由 RecyclerView 自己处理），
     * 播放页没有列表，竖滑换集才不会和别的手势打架。
     */
    private val verticalEnabled: Boolean = false,
    private val onVerticalStart: () -> Unit = {},
    /** 纵向位移占屏高的比例：负 = 手指向上（下一集），正 = 向下（上一集）。 */
    private val onVertical: (Float) -> Unit = {},
    /**
     * 纵滑抬手：[DIRECTION_NEXT] 向上（下一集）、[DIRECTION_PREV] 向下（上一集）、
     * [DIRECTION_NONE] 位移不够（当作没发生）。
     */
    private val onVerticalEnd: (direction: Int, velocityY: Float) -> Unit = { _, _ -> },
    /**
     * 单击是否等待双击窗口。
     *
     * 正式播放器开启：单击显隐信息，双击播放 / 暂停；信息流关闭，单击仍立即播放 / 暂停。
     */
    private val doubleTapEnabled: Boolean = false,
) : View.OnTouchListener {

    /** 长按落在画面的哪一段：左 / 中 / 右各 1/3。 */
    enum class Zone { LEFT, CENTER, RIGHT }

    private enum class Mode { IDLE, PRESSING, LONG, DRAG, VERTICAL }

    companion object {
        /** 长按判定。500ms 太短（点一下稍慢就误触），650ms 又像没反应。 */
        const val LONG_PRESS_MS = 450L

        /** 横屏双击窗口。 */
        const val DOUBLE_TAP_MS = 260L

        /** 横滑起点与屏幕左右边缘的最小距离（dp）：留出系统返回手势的那一条。 */
        const val EDGE_GUARD_DP = 24f

        /**
         * 屏幕顶部这一条（dp）不接手势：那是系统的「下拉通知栏 / 控制中心」手势区。
         *
         * 播放页的触摸层是**整屏铺满**的，一旦在顶部按下并向下拖，事件全被这一层吃掉：
         * 用户想拉状态栏，看到的却是「上一集」被切了出来（向下滑 = 上一集）。
         * 因此从这一条之内起手的手势直接不接管，交给系统去判。
         *
         * 取值要小：竖屏的返回按钮从 `dp(12)` 开始，16dp 只盖住它上方那条空白。
         */
        const val TOP_GUARD_DP = 16f

        /**
         * 从顶部这一带（dp）**向下**拉，不当作「上一集」。
         *
         * 系统的下拉手势区在部分 ROM 上比 [TOP_GUARD_DP] 更深（整条标题栏都算）。
         * 那里起手向下拖，用户的意图是拉通知栏，不是换集；因此即使事件已经被这一层接住，
         * 也一律不进入纵滑换集。点击、长按、横滑不受影响。
         */
        const val TOP_PULL_GUARD_DP = 64f

        /** 与推荐页一致：慢拖越过半页才换集，快速轻扫由系统最小 fling 速度决定。 */
        const val VERTICAL_COMMIT_RATIO = PhonePagerMotion.SNAP_DISTANCE_RATIO

        /**
         * 方向锁定的主轴优势。
         *
         * 推荐页的纵向列表与横向 pager 都以 1:1 分界：
         * `|dy| > |dx|` 归纵向，`|dx| > |dy|` 归横向。
         */
        const val VERTICAL_AXIS_BIAS = 1f
        const val HORIZONTAL_AXIS_BIAS = 1f

        /** 纵滑方向（见 `onVerticalEnd`）。 */
        const val DIRECTION_NONE = 0
        const val DIRECTION_NEXT = -1
        const val DIRECTION_PREV = 1
    }

    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(host.context).scaledTouchSlop
    private val minimumFlingVelocity =
        PhonePagerMotion.minimumFlingVelocityPxPerSecond(host.context).toFloat()

    private var mode = Mode.IDLE
    private var downX = 0f
    /** 手指按下的屏幕坐标：页面跟着手指平移后，局部坐标会被抵消，位移必须按原始坐标算。 */
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastUpAt = 0L
    private var tapPending = false
    /** 纵滑抬手时的位移比例：正 = 向下（上一集），负 = 向上（下一集）。 */
    private var lastDy = 0f
    /** 已经超过触摸阈值：即使最终没有锁定方向，也不能再当成单击。 */
    private var moved = false
    private var velocityTracker: VelocityTracker? = null

    private val longPress = Runnable {
        if (mode != Mode.PRESSING) return@Runnable
        mode = Mode.LONG
        onLongPress(zoneOf(downX))
    }

    private val singleTap = Runnable {
        if (!tapPending) return@Runnable
        tapPending = false
        onTap()
    }

    /**
     * 速度采样也要用屏幕坐标：翻页时 [host] 自己会平移，局部坐标下的速度会失真。
     * `VelocityTracker` 只认 `event.x/y`，因此复制一份事件并平移到原始坐标位置。
     */
    private fun addVelocitySample(event: MotionEvent) {
        val copy = MotionEvent.obtain(event)
        copy.offsetLocation(event.rawX - event.x, event.rawY - event.y)
        velocityTracker?.addMovement(copy)
        copy.recycle()
    }

    private fun width(): Int = host.width.takeIf { it > 0 }
        ?: host.context.resources.displayMetrics.widthPixels

    private fun height(): Int = host.height.takeIf { it > 0 }
        ?: host.context.resources.displayMetrics.heightPixels

    /** 起点是否离左右边缘足够远（竖屏用来避开系统返回手势）。 */
    private fun edgeSafe(x: Float): Boolean {
        val w = width().toFloat().coerceAtLeast(1f)
        val guard = host.context.dp(EDGE_GUARD_DP).toFloat()
        return x > guard && x < w - guard
    }

    private fun zoneOf(x: Float): Zone {
        val w = width().toFloat().coerceAtLeast(1f)
        return when {
            x < w / 3f -> Zone.LEFT
            x > w * 2f / 3f -> Zone.RIGHT
            else -> Zone.CENTER
        }
    }

    /** 起点是否在屏幕顶部那条系统手势区里（见 [TOP_GUARD_DP]）。 */
    private fun topGuardSafe(rawY: Float): Boolean =
        rawY >= host.context.dp(TOP_GUARD_DP).toFloat()

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        // 顶部那一条不接：从那里按下就是用户在拉通知栏，交回系统。
        // 一旦接了，后续 MOVE 全归我们，系统再也拉不下来，反而会切出一集。
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !topGuardSafe(event.rawY)) {
            mode = Mode.IDLE
            handler.removeCallbacks(longPress)
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downRawX = event.rawX
                downRawY = event.rawY
                lastDy = 0f
                moved = false
                mode = Mode.PRESSING
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                addVelocitySample(event)
                handler.removeCallbacks(longPress)
                handler.postDelayed(longPress, LONG_PRESS_MS)
            }

            MotionEvent.ACTION_MOVE -> {
                addVelocitySample(event)
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (mode == Mode.PRESSING && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                    handler.removeCallbacks(longPress)
                    moved = true
                    // 主轴要拉开足够差距才锁定。只按第一帧大小判方向时，竖滑开头的一点点
                    // 横向抖动会把状态机锁成拖进度，后面怎么上下滑都不再换集。
                    val ax = Math.abs(dx)
                    val ay = Math.abs(dy)
                    val verticalDominant = ay > ax * VERTICAL_AXIS_BIAS
                    val horizontalDominant = ax > ay * HORIZONTAL_AXIS_BIAS
                    val dragAllowed = dragEnabled && horizontalDominant &&
                        (isLandscape() || edgeSafe(downX))
                    // 从顶部一带向下拉 = 用户想拉通知栏，不是「上一集」。
                    val pullDownFromTop = dy > 0f &&
                        downRawY < host.context.dp(TOP_PULL_GUARD_DP).toFloat()
                    when {
                        verticalEnabled && verticalDominant && !pullDownFromTop -> {
                            mode = Mode.VERTICAL
                            lastDy = 0f
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                            onVerticalStart()
                        }
                        dragAllowed -> {
                            mode = Mode.DRAG
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                            onDragStart()
                        }
                        // 方向还不明确（或横滑起点在系统返回区）：继续等下一帧，
                        // 不要直接进入 IDLE，否则同一次手势再也接不上纵滑。
                    }
                }
                if (mode == Mode.DRAG) onDrag(dx / width())
                if (mode == Mode.VERTICAL) {
                    lastDy = dy / height()
                    onVertical(lastDy)
                }
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                val vy = velocityTracker?.run {
                    computeCurrentVelocity(1000)
                    yVelocity
                } ?: 0f
                when (mode) {
                    Mode.DRAG -> onDragEnd()
                    Mode.LONG -> onLongPressEnd()
                    Mode.VERTICAL -> {
                        val next = lastDy < -VERTICAL_COMMIT_RATIO ||
                            (lastDy < 0f && vy < -minimumFlingVelocity)
                        val previous = lastDy > VERTICAL_COMMIT_RATIO ||
                            (lastDy > 0f && vy > minimumFlingVelocity)
                        val direction = when {
                            next -> DIRECTION_NEXT
                            previous -> DIRECTION_PREV
                            else -> DIRECTION_NONE
                        }
                        onVerticalEnd(direction, vy)
                    }
                    Mode.PRESSING -> if (!moved) tap()
                    Mode.IDLE -> Unit
                }
                mode = Mode.IDLE
                moved = false
                v.parent?.requestDisallowInterceptTouchEvent(false)
                velocityTracker?.recycle()
                velocityTracker = null
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                when (mode) {
                    Mode.DRAG -> onDragEnd()
                    Mode.LONG -> onLongPressEnd()
                    Mode.VERTICAL -> onVerticalEnd(DIRECTION_NONE, 0f)
                    else -> Unit
                }
                mode = Mode.IDLE
                moved = false
                v.parent?.requestDisallowInterceptTouchEvent(false)
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
        return true
    }

    /** 抬手判定：需要双击语义的页面先等双击窗口，其余页面立即单击。 */
    private fun tap() {
        if (!doubleTapEnabled) {
            onTap()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastUpAt < DOUBLE_TAP_MS) {
            lastUpAt = 0L
            tapPending = false
            handler.removeCallbacks(singleTap)
            onDoubleTap()
        } else {
            lastUpAt = now
            tapPending = true
            handler.removeCallbacks(singleTap)
            handler.postDelayed(singleTap, DOUBLE_TAP_MS)
        }
    }
}
