// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.content.Context
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * 竖向信息流：**只有明显纵向的手势才滚**。
 *
 * RecyclerView 自己的判据只有 `|dy| > slop`，没有主轴偏向。横滑拖进度取消之后
 * （见 `PhoneVideoPage` 的 `dragSeek`），横着划的那一下不再有谁去
 * `requestDisallowInterceptTouchEvent`，于是「横滑带一点纵向分量」会被它认成上滑，
 * `PagerSnapHelper` 顺势吸附到下一页。
 *
 * 这里加一把方向锁：整个手势在第一次越过 slop 时定性，判成横向就彻底不滚。
 * 判据取 `|dy| > |dx|`（纵向必须**比横向多**），横滑（含斜着横滑）一律不触发翻页；
 * 正常的上下滑 `|dy|` 通常是 `|dx|` 的两三倍以上，不受影响。
 */
internal class VerticalPagerList(context: Context) : RecyclerView(context) {
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    /** 0 = 未定性，1 = 纵向（这一下归列表），2 = 横向（这一下不归列表）。 */
    private var axis = 0

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                axis = 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (axis == 0) {
                    val dx = Math.abs(e.x - downX)
                    val dy = Math.abs(e.y - downY)
                    if (dx > slop || dy > slop) axis = if (dy > dx) 1 else 2
                }
                if (axis == 2) return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> axis = 0
        }
        return super.onInterceptTouchEvent(e)
    }
}

internal class PageHolder(view: View) : RecyclerView.ViewHolder(view)
