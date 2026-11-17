// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.View
import android.widget.ImageView
import com.hgtv.star.core.PosterCardContent
import com.hgtv.star.core.Series
import com.hgtv.star.shared.ui.PosterCardMaterial
import com.hgtv.star.shared.ui.PosterCardView
import com.hgtv.star.shared.ui.SoftStyle

/**
 * 电视端海报卡：结构来自 [PosterCardView]，这里只补电视的材质、字号、进度条与焦点。
 */
class PosterCard(
    context: Context,
    posterHeight: Int,
    private val coverLoader: (ImageView, String) -> Unit,
    /**
     * 卡片文字的整体缩放：每行 4 / 5 张时卡片更宽，剧名与状态行按同一比例放大，
     * 否则大卡片下面会吊着两行小字。1f = 旧版 6 列时的字号。
     */
    val textScale: Float = 1f
) : PosterCardView(context, PosterCardMaterial.config(
    px = { value -> context.tvDp(value) },
    posterHeight = posterHeight,
    textScale = textScale,
    rankTopColor = TvStyle.tips,
    progressTrackColor = TvStyle.wellDeep,
    progressFillColor = TvStyle.accent,
)) {
    private val dp = { value: Number -> context.tvDp(value) }
    private val band = context.tvDp(PosterCardMaterial.SOFT_BAND)
    private val marqueeOn = TvStyle.animate
    private val plate = TvStyle.soft(
        context, TvStyle.surface, PosterCardMaterial.RADIUS_CARD, PosterCardMaterial.SOFT_BAND,
        SoftStyle.RAISED, depth = PosterCardMaterial.CARD_DEPTH, heavy = true
    )

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        id = View.generateViewId()
        setPadding(band, band, band, band)

        title.designTextSize(PosterCardMaterial.TITLE_TEXT * textScale)
        title.setTextColor(TvStyle.textDim)
        title.setTypeface(title.typeface, Typeface.BOLD)
        title.marqueeRepeatLimit = -1

        status.designTextSize(PosterCardMaterial.META_TEXT * textScale)
        status.setTextColor(TvStyle.textDim)
        tagLine.designTextSize(PosterCardMaterial.META_TEXT * textScale)
        tagLine.setTextColor(TvStyle.textDim)

        listOf(badge, season, corner, heat).forEach { view ->
            view.designTextSize(PosterCardMaterial.BADGE_TEXT * textScale)
            view.setTextColor(TvStyle.playerText)
        }

        background = plate
        applyFocus(false)
    }

    override fun loadCover(image: ImageView, url: String) = coverLoader(image, url)

    /**
     * 绑定一张卡。内容规则全部走 [PosterCardContent]：
     * 季号默认第 1 季、榜单页只显示名次、集数优先于兜底角标、热度原串优先。
     */
    fun bind(
        item: Series,
        statusText: String,
        badgeText: String,
        progressPercent: Int?,
        rankTop: Boolean,
        rank: Int? = null,
        seasonBadge: Boolean = true,
        rankText: String = "",
        /** 服务端原串（`9014万`）；空串时退回 [Series.heat] 解析值。 */
        heatServerText: String = "",
        preferStatus: Boolean = false,
        hideTags: Boolean = false,
    ) {
        super.bind(item, PosterCardContent.of(
            series = item,
            statusText = statusText,
            badgeFallback = badgeText,
            rankText = rankText,
            rank = rank,
            rankTop = rankTop,
            seasonBadge = seasonBadge,
            heatServerText = heatServerText,
            preferStatus = preferStatus,
            hideTags = hideTags,
        ))
        setProgress(progressPercent)
    }

    fun setFocused(focused: Boolean) = applyFocus(focused)

    private fun applyFocus(focused: Boolean) {
        plate.restyle(
            fill = if (focused) TvStyle.surfaceHigh else TvStyle.surface,
            ring = if (focused) TvStyle.focusRing else Color.TRANSPARENT,
            ringWidth = if (focused) context.designPx(TvStyle.RING).toFloat() else 0f,
            glow = if (focused) TvStyle.litGlow else Color.TRANSPARENT,
            depth = if (focused) PosterCardMaterial.FOCUS_DEPTH else 1f
        )
        title.setTextColor(if (focused) TvStyle.text else TvStyle.textDim)
        status.setTextColor(if (focused) TvStyle.textStrong else TvStyle.textDim)
        tagLine.setTextColor(TvStyle.textDim)
        if (marqueeOn) {
            title.isSelected = false
            title.ellipsize = if (focused) TextUtils.TruncateAt.MARQUEE else TextUtils.TruncateAt.END
            if (focused) {
                title.isSelected = true
                title.scrollTo(0, 0)
            }
        } else {
            title.isSelected = false
            title.ellipsize = TextUtils.TruncateAt.END
        }
        TvStyle.focus(this, focused)
    }
}
