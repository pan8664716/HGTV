// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.ui

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity

/**
 * 海报卡的唯一材质基准，以电视端为准。
 *
 * 两端仍然各自把设计尺寸换算成像素，但圆角、柔光带、内衬、角标、暗角与进度条
 * 不再各写一份。这样手机与电视的卡片材质只会在这里改一次。
 */
object PosterCardMaterial {

    const val SOFT_BAND = 6
    const val RADIUS_CARD = 20
    const val RADIUS_POSTER = 16

    const val POSTER_INSET = 4
    const val POSTER_TOP_MARGIN = 2

    const val BADGE_TEXT = 12f
    const val TITLE_TEXT = 18f
    const val META_TEXT = 14f

    const val BADGE_PAD_LEFT = 6
    const val BADGE_PAD_TOP = 3
    const val BADGE_PAD_RIGHT = 8
    const val BADGE_PAD_BOTTOM = 3
    const val BADGE_MARGIN = 5

    const val TITLE_PAD_LEFT = 9f
    const val TITLE_PAD_TOP = 8f
    const val TITLE_PAD_RIGHT = 9f
    const val META_PAD_LEFT = 9f
    const val META_PAD_TOP = 4f
    const val META_PAD_RIGHT = 9f
    const val META_PAD_BOTTOM = 3f

    const val CARD_DEPTH = 1.2f
    const val FOCUS_DEPTH = 1.35f
    const val PROGRESS_HEIGHT = 4
    const val SCRIM = 0x99000000.toInt()

    /**
     * 两端共用的卡片配置。平台只传自己的像素换算、海报高度和主题色。
     */
    fun config(
        px: (Number) -> Int,
        posterHeight: Int,
        textScale: Float,
        rankTopColor: Int,
        progressTrackColor: Int,
        progressFillColor: Int,
        /**
         * 海报井内衬（dp），默认 [POSTER_INSET]。
         *
         * 手机端把它收到 2：卡片本身已经很小，四周各 4dp 的内衬（1080P 上 12px）
         * 读起来就是「卡片外面还套着一圈空边」。电视屏大，保持默认。
         */
        posterInsetDp: Number = POSTER_INSET,
    ): PosterCardView.Config = PosterCardView.Config(
        posterHeight = posterHeight,
        posterInset = px(posterInsetDp),
        posterTopMargin = px(POSTER_TOP_MARGIN),
        badgePadding = PosterCardView.Padding(
            px(BADGE_PAD_LEFT),
            px(BADGE_PAD_TOP),
            px(BADGE_PAD_RIGHT),
            px(BADGE_PAD_BOTTOM),
        ),
        badgeMargin = px(BADGE_MARGIN),
        titlePadding = PosterCardView.Padding(
            px(TITLE_PAD_LEFT * textScale),
            px(TITLE_PAD_TOP * textScale),
            px(TITLE_PAD_RIGHT * textScale),
            0,
        ),
        metaPadding = PosterCardView.Padding(
            px(META_PAD_LEFT * textScale),
            px(META_PAD_TOP * textScale),
            px(META_PAD_RIGHT * textScale),
            px(META_PAD_BOTTOM * textScale),
        ),
        cardRadius = px(RADIUS_CARD),
        posterRadius = px(RADIUS_POSTER),
        scrim = { place -> scrim(px, place) },
        posterOverlay = overlay(),
        rankTopColor = rankTopColor,
        metaMode = PosterCardView.MetaMode.BOTH,
        showProgress = true,
        progressHeight = px(PROGRESS_HEIGHT),
        progressTrackColor = progressTrackColor,
        progressFillColor = progressFillColor,
    )

    /** 封面暗角：与电视端同一枚 SHADE，手机不再省掉这一层。 */
    private fun overlay(): Drawable = SoftDrawable(
        fill = Color.TRANSPARENT,
        radiusPx = 0f,
        bandPx = 0f,
        style = SoftStyle.SHADE,
    )

    /**
     * 四角角标的压暗层。方向与电视端一致，圆角也按同一处贴边圆。
     */
    private fun scrim(px: (Number) -> Int, place: Int): Drawable {
        val radius = when (place) {
            Gravity.TOP or Gravity.END,
            Gravity.TOP or Gravity.START -> px(RADIUS_POSTER).toFloat()
            else -> px(RADIUS_CARD).toFloat()
        }
        val dark = SCRIM
        val clear = Color.TRANSPARENT
        val (orientation, colors) = when (place) {
            Gravity.TOP or Gravity.END ->
                GradientDrawable.Orientation.TOP_BOTTOM to intArrayOf(dark, clear)
            Gravity.TOP or Gravity.START ->
                GradientDrawable.Orientation.LEFT_RIGHT to intArrayOf(dark, clear)
            Gravity.BOTTOM or Gravity.START ->
                GradientDrawable.Orientation.LEFT_RIGHT to intArrayOf(dark, clear)
            else ->
                GradientDrawable.Orientation.TOP_BOTTOM to intArrayOf(clear, dark)
        }
        return GradientDrawable(orientation, colors).apply {
            cornerRadii = when (place) {
                Gravity.TOP or Gravity.END ->
                    floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
                Gravity.TOP or Gravity.START ->
                    floatArrayOf(radius, radius, 0f, 0f, 0f, 0f, radius, radius)
                else ->
                    floatArrayOf(0f, 0f, 0f, 0f, radius, radius, radius, radius)
            }
        }
    }
}
