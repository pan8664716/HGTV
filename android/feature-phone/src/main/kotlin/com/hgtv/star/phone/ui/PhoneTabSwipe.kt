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
 * 分类 / 榜单 / 我的页的横向切页容器。
 *
 * 松手后页面自然翻动；翻页动画走到约 70% 时再切顶部焦点。
 * 第一次越过 touch slop 时按 1:1 锁定横纵轴。筛选 chips 等横向滚动区域不接管。
 * 拖动时会同步铺出目标邻页预览，避免当前页移开时露出深色背景。
 */
internal class PhoneTabSwipeHost(context: Context) : FrameLayout(context) {

    companion object {
        const val DIRECTION_PREV = -1
        const val DIRECTION_NEXT = 1
        const val COMMIT_DISTANCE_RATIO = PhonePagerMotion.SNAP_DISTANCE_RATIO
    }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minimumFlingVelocity =
        PhonePagerMotion.minimumFlingVelocityPxPerSecond(context).toFloat()
    private var content: View? = null
    private var preview: View? = null
    private var previewDirection = 0
    private var downX = 0f
    private var downY = 0f
    /** 0 = 未定性，1 = 纵向，2 = 横向。 */
    private var axis = 0
    private var tracking = false
    private var intercepting = false
    private var settling = false
    private var velocityTracker: VelocityTracker? = null

    var canSwipe: (Int) -> Boolean = { false }
    var onSwipe: (Int) -> Unit = {}
    /** 翻页动画约走到 70% 后通知顶部标签切换到目标页。 */
    var onFocusDirection: ((Int) -> Unit)? = null
    var previewProvider: ((Int) -> View?)? = null
    var previewReleaser: ((View) -> Unit)? = null
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
        onFocusDirection?.invoke(0)
        velocityTracker?.recycle()
        velocityTracker = null
        content?.animate()?.setUpdateListener(null)
        content?.animate()?.cancel()
        content?.translationX = 0f
        removePreview()
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
                    ensurePreview(directionFor(dx))
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
                velocityTracker?.recycle()
                velocityTracker = null
                val direction = commitDirection(dx, velocity)
                parent?.requestDisallowInterceptTouchEvent(false)
                if (direction != 0 && canSwipe(direction)) {
                    ensurePreview(direction)
                    settleCommit(direction)
                } else {
                    settleBack()
                }
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
        val direction = directionFor(dx)
        val movable = canSwipe(direction)
        if (movable) ensurePreview(direction) else removePreview()
        view.translationX = if (movable) {
            dx.coerceIn(-pageWidth, pageWidth)
        } else {
            dx * 0.22f
        }
        preview?.translationX = if (movable) {
            direction * pageWidth + dx
        } else {
            direction * pageWidth
        }
    }

    private fun commitDirection(dx: Float, velocity: Float): Int {
        val threshold = width.toFloat().coerceAtLeast(1f) * COMMIT_DISTANCE_RATIO
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
        val startX = view.translationX
        val distance = abs(target - startX).coerceAtLeast(1f)
        var focusSent = false
        val duration = PhonePagerMotion.duration(
            distancePx = target - startX,
            viewportPx = pageWidth,
        )
        view.animate()
            .translationX(target)
            .setDuration(duration)
            .setInterpolator(PhonePagerMotion.interpolator)
            .setUpdateListener {
                if (focusSent || !settling) return@setUpdateListener
                val progress = (abs(view.translationX - startX) / distance).coerceIn(0f, 1f)
                if (progress >= PhonePagerMotion.FOCUS_SWITCH_PROGRESS) {
                    focusSent = true
                    onFocusDirection?.invoke(direction)
                }
            }
            .withEndAction { view.animate().setUpdateListener(null) }
            .start()
        val previewView = preview
        if (previewView == null) {
            view.postDelayed({
                onSwipe(direction)
                settleWithoutPreview(direction, pageWidth)
            }, duration)
            return
        }
        previewView.animate()
            .translationX(0f)
            .setDuration(duration)
            .setInterpolator(PhonePagerMotion.interpolator)
            .withEndAction {
                onSwipe(direction)
                view.translationX = 0f
                releasePreviewAfterHandoff(previewView)
            }
            .start()
    }

    private fun settleBack() {
        val view = content ?: return
        view.animate().setUpdateListener(null)
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
        val previewView = preview
        if (previewView == null) {
            view.animate().withEndAction { settling = false }.start()
            return
        }
        previewView.animate()
            .translationX(previewDirection * pageWidth.toFloat())
            .setDuration(duration)
            .setInterpolator(PhonePagerMotion.interpolator)
            .withEndAction {
                removePreview()
                settling = false
            }
            .start()
    }

    private fun settleWithoutPreview(direction: Int, pageWidth: Int) {
        val view = content ?: return
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

    private fun ensurePreview(direction: Int) {
        if (previewDirection == direction && preview != null) return
        removePreview()
        val view = previewProvider?.invoke(direction) ?: return
        preview = view
        previewDirection = direction
        view.translationX = direction * width.toFloat()
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        bringChildToFront(view)
    }

    private fun removePreview() {
        val view = preview ?: return
        view.animate().cancel()
        removeView(view)
        previewReleaser?.invoke(view)
        preview = null
        previewDirection = 0
    }

    /**
     * 正式列表已在预览层下面完成换页。等两帧让它先完成绑定和绘制，再原子移除预览；
     * 不做交叉淡出，避免同一张卡片的左上角标签被两层重复绘制后看起来又亮了一次。
     */
    private fun releasePreviewAfterHandoff(previewView: View) {
        previewView.postOnAnimation {
            if (preview !== previewView) {
                settling = false
                return@postOnAnimation
            }
            previewView.postOnAnimation {
                if (preview !== previewView) {
                    settling = false
                    return@postOnAnimation
                }
                removePreview()
                settling = false
            }
        }
    }

    private fun resetGesture() {
        velocityTracker?.recycle()
        velocityTracker = null
        tracking = false
        intercepting = false
        axis = 0
    }

    private fun addVelocitySample(tracker: VelocityTracker, event: MotionEvent) {
        val copy = MotionEvent.obtain(event)
        copy.offsetLocation(event.rawX - event.x, event.rawY - event.y)
        tracker.addMovement(copy)
        copy.recycle()
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
