// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.hgtv.star.phone.ui.isSystemBarGestureOrigin

/**
 * 竖向信息流容器：当前页前后各常驻一页。
 *
 * `ViewPager2` 的 `offscreenPageLimit=1` 会提前创建、绑定当前页和前后相邻页，
 * 每个 `PhoneVideoPage` 因此能在滑动前拿到自己的 `SurfaceView` 并完成首帧预渲染。
 *
 * 同时保留原信息流的方向锁：第一次越过 touch slop 时，只有 `|dy| > |dx|`
 * 才允许 ViewPager2 接收拖动；横滑交还给外层体裁 pager。
 */
internal class VerticalFeedPager(context: Context) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    /** 0 = 未定性，1 = 纵向（这一下归列表），2 = 横向（这一下不归列表）。 */
    private var axis = 0
    /** 当前这次滚动是否由真实纵向手势发起；布局补发的 IDLE 不得换播。 */
    var userScroll = false
    /** 从系统状态栏 / 导航手势区起手时，这一下不交给纵向翻页。 */
    private var systemBarGesture = false

    private val pager = ViewPager2(context).apply {
        orientation = ViewPager2.ORIENTATION_VERTICAL
        offscreenPageLimit = 1
        overScrollMode = View.OVER_SCROLL_NEVER
    }

    /** ViewPager2 内部真正承载页面 ViewHolder 的 RecyclerView。 */
    val pageRecycler: RecyclerView
        get() = pager.getChildAt(0) as RecyclerView

    var adapter: RecyclerView.Adapter<*>?
        get() = pager.adapter
        set(value) {
            pager.adapter = value
        }

    val currentItem: Int
        get() = pager.currentItem

    init {
        setBackgroundColor(android.graphics.Color.BLACK)
        addView(
            pager,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        // 横滑必须交给外层体裁 pager，竖滑才由本层消费。
        pageRecycler.isNestedScrollingEnabled = false
    }

    fun registerOnPageChangeCallback(callback: ViewPager2.OnPageChangeCallback) {
        pager.registerOnPageChangeCallback(callback)
    }

    fun setActive(active: Boolean) {
        pager.offscreenPageLimit = if (active) 1 else ViewPager2.OFFSCREEN_PAGE_LIMIT_DEFAULT
    }

    fun setCurrentItem(position: Int, smoothScroll: Boolean) {
        pager.setCurrentItem(position, smoothScroll)
    }

    fun scrollToPosition(position: Int) {
        pager.setCurrentItem(position, false)
    }

    fun smoothScrollToPosition(position: Int) {
        pager.setCurrentItem(position, true)
    }

    fun stopScroll() {
        pageRecycler.stopScroll()
    }

    fun findViewHolderForAdapterPosition(position: Int): RecyclerView.ViewHolder? =
        pageRecycler.findViewHolderForAdapterPosition(position)

    fun getChildAdapterPosition(view: View): Int =
        pageRecycler.getChildAdapterPosition(view)

    fun pageChildCount(): Int = pageRecycler.childCount

    fun pageChildAt(index: Int): View? = pageRecycler.getChildAt(index)

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                axis = 0
                userScroll = false
                systemBarGesture = isSystemBarGestureOrigin(e.rawY)
                pager.isUserInputEnabled = !systemBarGesture
            }
            MotionEvent.ACTION_MOVE -> {
                if (currentPage()?.progressTouching == true) {
                    pager.isUserInputEnabled = false
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return super.dispatchTouchEvent(e)
                }
                if (axis == 0) {
                    val dx = Math.abs(e.x - downX)
                    val dy = Math.abs(e.y - downY)
                    if (dx > slop || dy > slop) {
                        axis = if (dy > dx) 1 else 2
                        if (axis == 1) {
                            userScroll = !systemBarGesture
                        } else {
                            pager.isUserInputEnabled = false
                            parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                }
                if (axis == 2) pager.isUserInputEnabled = false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                axis = 0
                systemBarGesture = false
                pager.isUserInputEnabled = true
            }
        }
        return super.dispatchTouchEvent(e)
    }

    private fun currentPage(): PhoneVideoPage? =
        pageRecycler.findViewHolderForAdapterPosition(pager.currentItem)?.itemView as? PhoneVideoPage
}

internal class PageHolder(view: View) : RecyclerView.ViewHolder(view)
