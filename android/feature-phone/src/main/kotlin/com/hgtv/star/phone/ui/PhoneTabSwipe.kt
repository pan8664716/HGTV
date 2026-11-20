// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone.ui

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import com.hgtv.star.phone.PhoneServices
import kotlin.math.abs

/**
 * 分类 / 榜单页的横向切页容器。
 *
 * 判定参数与推荐页一致：越过半页吸附，或达到系统最小快甩速度即切页；
 * 第一次越过 touch slop 时按 1:1 锁定横纵轴。筛选 chips 等横向滚动区域不接管。
 */
internal class PhoneTabSwipeHost(context: Context) : FrameLayout(context) {

    companion object {
        const val DIRECTION_PREV = -1
        const val DIRECTION_NEXT = 1
    }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minimumFlingVelocity =
        PhonePagerMotion.minimumFlingVelocityPxPerSecond(context).toFloat()
    private var content: View? = null
    private var velocityTracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    /** 0 = 未定性，1 = 纵向，2 = 横向。 */
    private var axis = 0
    private var tracking = false
    private var intercepting = false
    private var settling = false

    var canSwipe: (Int) -> Boolean = { false }
    var onSwipe: (Int) -> Unit = {}
    var swipeEnabled = true
        set(value) {
            field = value
            if (!value) settleBack()
        }

    fun setContent(view: View) {
        content?.let(::removeView)
        content = view
        (view.parent as? ViewGroup)?.removeView(view)
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun refreshSwipeSetting() {
        swipeEnabled = runCatching {
            PhoneServices.library(context)
                .takeIf { it.isLoaded }?.recommendSwipe ?: true
        }.getOrDefault(true)
    }

    fun cancelSwipe() {
        velocityTracker?.recycle()
        velocityTracker = null
        content?.animate()?.cancel()
        content?.translationX = 0f
        tracking = false
        intercepting = false
        settling = false
        axis = 0
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (settling) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            begin(event)
            return false
        }
        if (!tracking) return false
        return handleMove(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!intercepting) return false
        return handleMove(event)
    }

    private fun begin(event: MotionEvent) {
        resetGesture()
        val view = content ?: return
        if (!swipeEnabled || containsHorizontalScroller(view, event.x, event.y)) return
        downX = event.x
        downY = event.y
        tracking = true
        velocityTracker = VelocityTracker.obtain().also { addVelocitySample(it, event) }
    }

    private fun handleMove(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.let { addVelocitySample(it, event) }
                val dx = event.x - downX
                val dy = event.y - downY
                if (axis == 0 && (abs(dx) > slop || abs(dy) > slop)) {
                    if (abs(dx) <= abs(dy)) {
                        resetGesture()
                        return false
                    }
                    axis = 2
                    if (!canSwipe(directionFor(dx))) {
                        resetGesture()
                        return false
                    }
                    intercepting = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (axis == 2) {
                    applyDrag(dx)
                    return true
                }
                return false
            }

            MotionEvent.ACTION_UP -> {
                if (!intercepting) {
                    resetGesture()
                    return false
                }
                velocityTracker?.let { tracker ->
                    addVelocitySample(tracker, event)
                    tracker.computeCurrentVelocity(1_000)
                }
                val dx = event.x - downX
                val velocity = velocityTracker?.xVelocity ?: 0f
                val direction = commitDirection(dx, velocity)
                parent?.requestDisallowInterceptTouchEvent(false)
                if (direction != 0 && canSwipe(direction)) {
                    settleCommit(direction)
                } else {
                    settleBack()
                }
                velocityTracker?.recycle()
                velocityTracker = null
                tracking = false
                intercepting = false
                axis = 0
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                val handled = intercepting
                if (handled) settleBack()
                parent?.requestDisallowInterceptTouchEvent(false)
                resetGesture()
                return handled
            }
        }
        return intercepting
    }

    private fun directionFor(dx: Float): Int =
        if (dx < 0f) DIRECTION_NEXT else DIRECTION_PREV

    private fun applyDrag(dx: Float) {
        val view = content ?: return
        val pageWidth = width.toFloat().coerceAtLeast(1f)
        view.translationX = if (canSwipe(directionFor(dx))) {
            dx.coerceIn(-pageWidth, pageWidth)
        } else {
            dx * 0.22f
        }
    }

    private fun commitDirection(dx: Float, velocity: Float): Int {
        val threshold = width.toFloat().coerceAtLeast(1f) *
            PhonePagerMotion.SNAP_DISTANCE_RATIO
        return when {
            dx <= -threshold || velocity <= -minimumFlingVelocity -> DIRECTION_NEXT
            dx >= threshold || velocity >= minimumFlingVelocity -> DIRECTION_PREV
            else -> 0
        }
    }

    private fun settleCommit(direction: Int) {
        val view = content ?: return
        settling = true
        val pageWidth = width.coerceAtLeast(1)
        val target = -direction * pageWidth.toFloat()
        val duration = PhonePagerMotion.duration(
            distancePx = target - view.translationX,
            viewportPx = pageWidth,
        )
        view.animate()
            .translationX(target)
            .setDuration(duration)
            .setInterpolator(PhonePagerMotion.interpolator)
            .withEndAction {
                onSwipe(direction)
                view.translationX = direction * pageWidth.toFloat()
                val enterDuration = PhonePagerMotion.duration(
                    distancePx = pageWidth.toFloat(),
                    viewportPx = pageWidth,
                )
                view.animate()
                    .translationX(0f)
                    .setDuration(enterDuration)
                    .setInterpolator(PhonePagerMotion.interpolator)
                    .withEndAction { settling = false }
                    .start()
            }
            .start()
    }

    private fun settleBack() {
        val view = content ?: return
        val distance = abs(view.translationX)
        if (distance < 1f) {
            view.translationX = 0f
            settling = false
            return
        }
        settling = true
        val pageWidth = width.coerceAtLeast(1)
        val duration = PhonePagerMotion.duration(distance, pageWidth)
        view.animate()
            .translationX(0f)
            .setDuration(duration)
            .setInterpolator(PhonePagerMotion.interpolator)
            .withEndAction { settling = false }
            .start()
    }

    private fun addVelocitySample(tracker: VelocityTracker, event: MotionEvent) {
        val copy = MotionEvent.obtain(event)
        copy.offsetLocation(event.rawX - event.x, event.rawY - event.y)
        tracker.addMovement(copy)
        copy.recycle()
    }

    private fun resetGesture() {
        velocityTracker?.recycle()
        velocityTracker = null
        tracking = false
        intercepting = false
        axis = 0
    }

    private fun containsHorizontalScroller(view: View, x: Float, y: Float): Boolean {
        if (view.visibility != View.VISIBLE) return false
        val parent = view.parent as? ViewGroup
        val localX = x + (parent?.scrollX ?: 0) - view.left
        val localY = y + (parent?.scrollY ?: 0) - view.top
        if (localX < 0f || localY < 0f || localX > view.width || localY > view.height) {
            return false
        }
        if (view is HorizontalScrollView) return true
        if (view is ViewGroup) {
            for (index in view.childCount - 1 downTo 0) {
                val child = view.getChildAt(index)
                if (containsHorizontalScroller(child, localX, localY)) return true
            }
        }
        return false
    }

    override fun onDetachedFromWindow() {
        cancelSwipe()
        super.onDetachedFromWindow()
    }
}
