// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.hgtv.star.shared.compat.setVectorIcon
import com.hgtv.star.shared.ui.SoftDrawable
import com.hgtv.star.shared.ui.SoftStyle

/**
 * 顶部导航的圆形图标入口（搜索 / 排行榜 / 设置）。
 * 常态是凸起的同色圆盘，焦点是打亮的圆盘 + 光晕，当前页是抬一档的面（图标与常态同为正文色，
 * 靠「抬起来」而不是靠颜色区分）。
 *
 * 控件尺寸由柔光带撑开：调用方给的 48dp 是**底板**直径，
 * 因此这里自己保证最小边长 = 底板 + 两条柔光带。
 */
class IconButton(
    context: Context,
    iconRes: Int,
    contentLabel: String,
    /** 圆盘直径（不含柔光带）；设置页头部的小圆钮用紧凑档。 */
    private val diameter: Int = TvStyle.BUTTON_NORMAL,
    private val action: () -> Unit
) : FrameLayout(context) {
    private val dp = { value: Number -> context.tvDp(value) }
    private val icon = ImageView(context)
    private var active = false
    /** 唯一的底板：焦点 / 当前页只就地改配色（见 [SoftDrawable.restyle]）。 */
    private val plate = TvStyle.soft(
        context, TvStyle.surface, TvStyle.RADIUS_PILL, TvStyle.SOFT_BAND, SoftStyle.RAISED, depth = 1.2f
    )

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        id = View.generateViewId()
        contentDescription = contentLabel
        icon.setVectorIcon(iconRes)
        icon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val glyph = if (diameter >= TvStyle.BUTTON_NORMAL) 24 else 20
        addView(icon, LayoutParams(dp(glyph), dp(glyph), Gravity.CENTER))
        minimumWidth = dp(diameter + 2 * TvStyle.SOFT_BAND)
        minimumHeight = dp(diameter + 2 * TvStyle.SOFT_BAND)
        background = plate
        paint(false)
        setOnClickListener { action() }
        setOnFocusChangeListener { _, focused ->
            paint(focused)
            TvStyle.focus(this, focused)
        }
    }

    fun setActive(value: Boolean) {
        active = value
        paint(false)
    }

    private fun paint(focused: Boolean) {
        plate.restyle(
            fill = when {
                focused -> TvStyle.lit
                // 当前页：抬一档 + 更深的柔光（周围是凸起的常态，抬起来就是「我在这里」）。
                active -> TvStyle.selectedFace
                else -> TvStyle.surface
            },
            glow = if (focused) TvStyle.litGlow else Color.TRANSPARENT,
            depth = when {
                focused -> 1.2f
                active -> 1.3f
                else -> 1f
            }
        )
        icon.setColorFilter(
            when {
                focused -> TvStyle.onLit
                else -> TvStyle.text
            }
        )
    }
}
