// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.ui

import android.graphics.drawable.GradientDrawable
import android.view.Gravity

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
 * @param place 角标在海报中的位置；只有贴着海报圆角的外角需要跟随图片弧度。
 */
fun labelBackground(
    colors: List<Int>,
    radiusPx: Int,
    place: Int = Gravity.TOP or Gravity.START,
): GradientDrawable? {
    if (colors.isEmpty()) return null
    val radii = cornerRadii(place, radiusPx.toFloat())
    return if (colors.size == 1) {
        GradientDrawable().apply {
            setColor(colors[0])
            cornerRadii = radii
        }
    } else {
        GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(colors[0], colors[1])
        ).apply {
            cornerRadii = radii
        }
    }
}

/**
 * 四角角标只圆贴海报边缘的那个外角，内侧两角保持直角。
 * 外角弧度与海报井一致，贴边后正好被图片的圆弧裁切。
 */
internal fun cornerRadii(place: Int, radiusPx: Float): FloatArray = when (place) {
    Gravity.TOP or Gravity.START ->
        floatArrayOf(radiusPx, radiusPx, 0f, 0f, 0f, 0f, 0f, 0f)
    Gravity.TOP or Gravity.END ->
        floatArrayOf(0f, 0f, radiusPx, radiusPx, 0f, 0f, 0f, 0f)
    Gravity.BOTTOM or Gravity.START ->
        floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, radiusPx, radiusPx)
    else ->
        floatArrayOf(0f, 0f, 0f, 0f, radiusPx, radiusPx, 0f, 0f)
}
