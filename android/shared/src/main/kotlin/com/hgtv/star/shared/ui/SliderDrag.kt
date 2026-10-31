// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.ui

import kotlin.math.abs

/**
 * 横向滑杆的触摸状态机：**按下 → 越过 slop 判定拖动 / 轻点 → 抬起或取消**。
 *
 * 这里只有判定，不碰 `View`、不碰 `MotionEvent`：调用方把坐标喂进来，
 * 按返回值决定「要不要抢父容器的拦截」「要不要把 x 当成新值」。
 *
 * ## 为什么这一小段值得单独抽
 *
 * 全站有三处滑杆各写过一遍这段判定：电视的设置滑杆、电视的弹幕大小滑杆、
 * 手机的设置滑杆。三份代码的差别只在「算出的值怎么落地」，判定本身逐字相同，
 * 而判定恰恰是最容易改错的那一半：
 *
 * * **按下时不抢事件**——否则外层 `ScrollView` 在滑杆那一行就滚不动了；
 * * **横向位移超过 slop 且大于纵向位移**才算拖动——否则斜着划一下就把纵向滚动吃掉；
 * * **没拖动就抬手 = 轻点**，直接把值跳到该位置（手机端最常见的用法）；
 * * 抬起与取消都要把状态清干净，否则下一次按下会带着上一次的 `dragging` 进来。
 *
 * 抽成纯逻辑后这四条能被单测钉住（见 `SliderDragTest`），不必上真机用手划。
 *
 * ## 用法
 *
 * ```kotlin
 * ACTION_DOWN   -> drag.down(x, y)
 * ACTION_MOVE   -> if (drag.move(x, y)) parent?.requestDisallowInterceptTouchEvent(true)
 *                  if (drag.dragging) apply(x)
 * ACTION_UP     -> if (drag.up()) apply(x)          // 轻点
 *                  parent?.requestDisallowInterceptTouchEvent(false)
 * ACTION_CANCEL -> drag.cancel(); parent?.requestDisallowInterceptTouchEvent(false)
 * ```
 */
class SliderDrag(private val touchSlop: Int) {

    /** 这一次按下之后是否已经进入拖动。轻点（没拖动）时为 `false`。 */
    var dragging = false
        private set

    private var downX = 0f
    private var downY = 0f

    /** 按下：记录起点。**不要**在这里抢父容器的事件（见类注释）。 */
    fun down(x: Float, y: Float) {
        downX = x
        downY = y
        dragging = false
    }

    /**
     * 移动：返回 `true` 表示「这一次刚刚开始拖动」——调用方此时才该
     * `requestDisallowInterceptTouchEvent(true)`，且整个手势只会有一次。
     */
    fun move(x: Float, y: Float): Boolean {
        if (dragging) return false
        val dx = abs(x - downX)
        val dy = abs(y - downY)
        if (dx > touchSlop && dx > dy) {
            dragging = true
            return true
        }
        return false
    }

    /** 抬起：返回 `true` 表示这是一次**轻点**（整段手势都没拖动过），调用方应按 x 落值。 */
    fun up(): Boolean {
        val tap = !dragging
        dragging = false
        return tap
    }

    /** 取消（来电、被父容器接管等）：只清状态，不落值。 */
    fun cancel() {
        dragging = false
    }
}
