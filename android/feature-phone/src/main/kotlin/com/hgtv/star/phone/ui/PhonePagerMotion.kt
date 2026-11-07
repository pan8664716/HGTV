// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone.ui

import android.content.Context
import android.view.ViewConfiguration
import android.view.animation.Interpolator
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.max

/**
 * 手机端所有整页翻动的统一运动参数。
 *
 * 推荐流、体裁横向 pager、正式播放页与选集面板都从这里取参数。
 * 时长公式与 RecyclerView 默认 `smoothScrollBy` 一致，插值也使用同一枚五次缓出曲线。
 */
object PhonePagerMotion {
    /**
     * 推荐页的 `PagerSnapHelper` 在慢拖时按“离哪一页更近”吸附，
     * 等价于越过半页才翻页。正式播放页与选集面板复用同一个阈值。
     */
    const val SNAP_DISTANCE_RATIO = 0.5f

    private const val BASE_MS = 300f
    private const val MAX_DURATION_MS = 2_000L

    /** 与 RecyclerView / PagerSnapHelper 使用的系统快甩阈值完全一致。 */
    fun minimumFlingVelocityPxPerSecond(context: Context): Int =
        ViewConfiguration.get(context).scaledMinimumFlingVelocity

    /** 与 RecyclerView 默认 pager 吸附相同的五次缓出曲线。 */
    val interpolator: Interpolator = Interpolator { t ->
        val u = t - 1f
        u * u * u * u * u + 1f
    }

    /**
     * @param distancePx 还需要移动的像素距离。
     * @param viewportPx 一页的宽或高。
     */
    fun duration(
        distancePx: Float,
        viewportPx: Int,
    ): Long {
        val viewport = viewportPx.toFloat().coerceAtLeast(1f)
        return ((abs(distancePx) / viewport + 1f) * BASE_MS)
            .toLong()
            .coerceAtMost(MAX_DURATION_MS)
    }
}

/**
 * 使用 [PhonePagerMotion] 的 PagerSnapHelper。
 *
 * 推荐流的纵向 pager 和体裁横向 pager 都直接复用同一套吸附时长与插值，
 * 不再各自吃 RecyclerView 的默认参数。
 */
class PhonePagerSnapHelper(
    private val viewportSize: () -> Int,
) : PagerSnapHelper() {

    private var recycler: RecyclerView? = null

    override fun attachToRecyclerView(recyclerView: RecyclerView?) {
        recycler = recyclerView
        super.attachToRecyclerView(recyclerView)
    }

    /** 点 tab / 程序性切页也走同一套运动参数。 */
    fun smoothTo(position: Int): Boolean {
        val rv = recycler ?: return false
        val layoutManager = rv.layoutManager ?: return false
        val scroller = createScroller(layoutManager) ?: return false
        scroller.setTargetPosition(position)
        layoutManager.startSmoothScroll(scroller)
        return true
    }

    override fun createScroller(
        layoutManager: RecyclerView.LayoutManager,
    ): RecyclerView.SmoothScroller? {
        val rv = recycler ?: return null
        return object : LinearSmoothScroller(rv.context) {
            override fun onTargetFound(
                targetView: android.view.View,
                state: RecyclerView.State,
                action: Action,
            ) {
                val distance = calculateDistanceToFinalSnap(layoutManager, targetView) ?: return
                val dx = distance[0]
                val dy = distance[1]
                val duration = PhonePagerMotion.duration(
                    distancePx = max(abs(dx), abs(dy)).toFloat(),
                    viewportPx = viewportSize(),
                )
                if (duration > 0) {
                    action.update(dx, dy, duration.toInt(), PhonePagerMotion.interpolator)
                }
            }
        }
    }
}
