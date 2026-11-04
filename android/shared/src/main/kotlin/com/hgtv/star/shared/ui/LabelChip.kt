// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.ui

import android.graphics.drawable.GradientDrawable

/**
 * 系统标签（`新剧` / `爆剧`…）的底色。
 *
 * 颜色由 [com.hgtv.star.core.LabelStyle] 解析好再传进来，这里只负责变成 Drawable：
 *
 * * 一个颜色 → 纯色块；
 * * 两个颜色 → **从左到右的渐变**（服务端给「爆剧」那种红到深红就是两色）；
 * * 空列表 → `null`，调用方退回默认的深色压暗层。
 *
 * @param radiusPx 圆角（像素）。调用方自己换算——电视走设计画布、手机走自己的画布，
 *   这里收 dp 会有一端算错。
 */
fun labelBackground(colors: List<Int>, radiusPx: Int): GradientDrawable? {
    if (colors.isEmpty()) return null
    return if (colors.size == 1) {
        GradientDrawable().apply {
            setColor(colors[0])
            cornerRadius = radiusPx.toFloat()
        }
    } else {
        GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(colors[0], colors[1])
        ).apply {
            cornerRadius = radiusPx.toFloat()
        }
    }
}
