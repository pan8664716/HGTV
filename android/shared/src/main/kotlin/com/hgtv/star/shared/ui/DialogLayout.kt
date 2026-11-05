// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.ui

/**
 * 弹窗尺寸预算：TV 与手机共用同一套「屏幕占比 + 固定开销 + 可滚动区」算法。
 *
 * 只做纯像素计算，不依赖 density，也不负责绘制；两端仍各自提供自己的材质与控件。
 */
object DialogLayout {

    data class Viewport(
        val widthPx: Int,
        val maxHeightPx: Int,
    )

    /** 手机弹窗：竖屏偏宽，横屏收窄，避免一整块面板盖满画面。 */
    fun phoneViewport(
        screenWidthPx: Int,
        screenHeightPx: Int,
        landscape: Boolean,
        screenMarginPx: Int = 0,
    ): Viewport {
        val widthShare = if (landscape) .52f else .88f
        val heightShare = if (landscape) .82f else .88f
        return Viewport(
            widthPx = (screenWidthPx * widthShare).toInt().coerceAtLeast(1),
            maxHeightPx = ((screenHeightPx * heightShare).toInt() - screenMarginPx * 2)
                .coerceAtLeast(1),
        )
    }

    /** 给定屏幕宽度时，优先用设计宽度，但不能超过「屏宽 - 两侧留白」。 */
    fun cappedWidthPx(
        screenWidthPx: Int,
        preferredPx: Int,
        screenMarginPx: Int,
    ): Int {
        val max = (screenWidthPx - screenMarginPx * 2).coerceAtLeast(1)
        return preferredPx.coerceAtMost(max).coerceAtLeast(1)
    }

    /** 面板总高减去标题/按钮/内边距等固定开销后，留给可滚动区域的高度。 */
    fun scrollHeightPx(
        maxPanelHeightPx: Int,
        fixedHeightPx: Int,
        minHeightPx: Int = 1,
    ): Int = (maxPanelHeightPx - fixedHeightPx).coerceAtLeast(minHeightPx)

    /** TV 弹窗的最大面板高度：可用高度扣掉上下屏幕余量。 */
    fun panelMaxHeightPx(
        usableHeightPx: Int,
        screenMarginPx: Int,
    ): Int = (usableHeightPx - screenMarginPx * 2).coerceAtLeast(1)
}
