// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone.ui

import android.graphics.Color
import android.view.View
import com.hgtv.star.shared.ui.SoftDrawable
import com.hgtv.star.shared.ui.SoftStyle

/**
 * 手机端的新拟态绘制入口。
 *
 * 与 TV 端同一套 `SoftDrawable`（已挪到 `shared`），差别只在**柔光带更窄**：
 * 电视 3 米外看，投影要铺得开才看得出层次；手机 30 厘米，6dp 的柔光带已经足够，
 * 再宽会把卡片内容挤掉。
 *
 * ## 为什么必须留柔光带
 *
 * 新拟态的凸起 = 右下深投影 + 左上浅高光。`GradientDrawable` 没有模糊，
 * `setShadowLayer` 在硬件加速画布上对形状无效，所以投影是**预渲染位图**，
 * 必须画在控件自己的边界内——也就是四周那条透明 padding。
 * 没有这条带，投影会被父容器裁掉，看起来就是「一张没有立体感的纯色块」。
 */
object PhoneSoft {

    /**
     * 给 View 套一层新拟态底板。
     *
     * 重复调用只换配色，不会把柔光带叠加进 padding（否则焦点来回切几次，
     * 卡片会被越挤越小）。调用方**先设好自己的 padding**，这里再往外扩一圈。
     */
    fun View.applySoft(
        style: SoftStyle = SoftStyle.RAISED,
        fill: Int = PhoneStyle.surface,
        radiusDp: Float = PhoneStyle.RADIUS_CARD,
        bandDp: Float = PhoneStyle.SOFT_BAND,
        ring: Int = Color.TRANSPARENT,
        ringWidthDp: Float = 0f,
        glow: Int = Color.TRANSPARENT,
        depth: Float = 1f,
        heavy: Boolean = false,
        bottomRadiusDp: Float = radiusDp,
    ): SoftDrawable {
        val existing = background as? SoftDrawable
        if (existing != null) {
            existing.restyle(
                fill = fill,
                ring = ring,
                ringWidth = context.dp(ringWidthDp).toFloat(),
                glow = glow,
                depth = depth,
            )
            return existing
        }
        val band = context.dp(bandDp)
        val drawable = SoftDrawable(
            fill = fill,
            radiusPx = context.dp(radiusDp).toFloat(),
            bottomRadiusPx = context.dp(bottomRadiusDp).toFloat(),
            bandPx = band.toFloat(),
            style = style,
            ringColor = ring,
            ringWidthPx = context.dp(ringWidthDp).toFloat(),
            glowColor = glow,
            depth = depth,
            heavy = heavy,
        )
        background = drawable
        setPadding(paddingLeft + band, paddingTop + band, paddingRight + band, paddingBottom + band)
        return drawable
    }

    /** 只换配色（焦点 / 选中态），不重复加 padding。 */
    fun View.restyleSoft(
        fill: Int = PhoneStyle.surface,
        ring: Int = Color.TRANSPARENT,
        glow: Int = Color.TRANSPARENT,
        depth: Float = 1f,
    ) {
        (background as? SoftDrawable)?.restyle(fill = fill, ring = ring, glow = glow, depth = depth)
    }

    /** 凹陷槽（搜索框、进度槽）。 */
    fun View.applyWell(
        fill: Int = PhoneStyle.well,
        radiusDp: Float = PhoneStyle.RADIUS_PILL,
        bandDp: Float = PhoneStyle.SOFT_BAND,
    ): SoftDrawable = applySoft(SoftStyle.INSET, fill, radiusDp, bandDp)

    /**
     * 一整条贴底导航栏的底板。
     *
     * 它不需要柔光带（上下都是屏幕边缘 / 内容区，没有父容器裁剪问题），
     * 因此单独走一个「只填充 + 一条上分隔线」的路径，避免 50dp 高的条里
     * 被 6dp 的 padding 吃掉内容高度。
     */
    fun View.applyBar(fill: Int = PhoneStyle.surface) {
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(fill)
        }
    }
}
