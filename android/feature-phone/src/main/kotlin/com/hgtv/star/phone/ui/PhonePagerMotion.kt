// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone.ui

import android.view.animation.Interpolator
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.max

/**
 * 手机端所有整页翻动的统一运动参数。
 *
 * 正式播放页的上下翻页验证过这套手感：距离决定基础时长，松手速度再补一段惯性，
 * 最后留一点稳定收尾；插值使用五次缓出。推荐流、体裁横向 pager 与选集面板
 * 都从这里取参数，避免一个地方 600ms、另一个地方 150ms。
 */
object PhonePagerMotion {
    private const val BASE_MS = 300f
    private const val SETTLE_TAIL_MS = 80f
    private const val INERTIA_MAX_MS = 180f
    private const val INERTIA_PER_SCREEN_PER_SECOND = 120f
    private const val MIN_DURATION_MS = 380L
    private const val MAX_DURATION_MS = 1_200L

    /** 与 RecyclerView 默认 pager 吸附相同的五次缓出曲线。 */
    val interpolator: Interpolator = Interpolator { t ->
        val u = t - 1f
        u * u * u * u * u + 1f
    }

    /**
     * @param distancePx 还需要移动的像素距离。
     * @param viewportPx 一页的宽或高。
     * @param velocityPxPerSecond 松手速度；程序性切页传 0。
     */
    fun duration(
        distancePx: Float,
        viewportPx: Int,
        velocityPxPerSecond: Float = 0f,
    ): Long {
        val viewport = viewportPx.toFloat().coerceAtLeast(1f)
        val base = (abs(distancePx) / viewport + 1f) * BASE_MS
        val inertia = (abs(velocityPxPerSecond) / viewport * INERTIA_PER_SCREEN_PER_SECOND)
            .coerceIn(0f, INERTIA_MAX_MS)
        return (base + inertia + SETTLE_TAIL_MS)
            .toLong()
            .coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
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
