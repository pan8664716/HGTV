// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.hgtv.star.core.Series
import com.hgtv.star.phone.ui.PhoneSoft.applySoft
import com.hgtv.star.shared.data.ArtworkLoader
import com.hgtv.star.shared.ui.PosterCardMaterial
import com.hgtv.star.shared.ui.PosterCardView
import com.hgtv.star.shared.ui.SoftStyle

/**
 * 手机端统一海报卡：网格页与详情页 / 播放页的横排相关剧集共用。
 *
 * 结构、角标与文案规则来自 [PosterCardView]；这里只提供手机的字号、柔光、
 * 封面加载和点击回调。横排与网格因此不会再有「一个 7:10、一个 3:4」这种分叉。
 */
open class PhonePosterCard(
    context: Context,
    private val cardWidth: Int,
    private val artwork: ArtworkLoader,
    private val onOpen: ((Series) -> Unit)? = null,
    onLongPress: ((Series) -> Unit)? = null,
) : PosterCardView(context, phonePosterConfig(context, cardWidth)) {

    private val scale = phonePosterScale(context, cardWidth)
    private val coverHeight = phonePosterPosterHeight(context, cardWidth)
    private val selectionMark = TextView(context).apply {
        gravity = Gravity.CENTER
        phoneSize(11)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
        includeFontPadding = false
        visibility = GONE
    }

    /** 卡片复用后由列表在每次绑定时更新；为空时长按继续交给父容器处理。 */
    var onLongPress: ((Series) -> Unit)? = onLongPress

    /**
     * 长按处理器的**现取**入口。
     *
     * 只在绑定时抄一份不够：页面可能在列表已经铺好之后才挂 `feed.onLongPress`
     * （缓存先出、回调后设），那批卡片的字段会一直是 null，长按就成了「没反应」。
     * 有这个东西之后，长按触发的那一刻才去问宿主要处理器，时序问题不再存在。
     */
    var longPressResolver: (() -> ((Series) -> Unit)?)? = null

    init {
        isClickable = true
        isFocusable = true
        setPadding(0, 0, 0, 0)
        applySoft(
            style = SoftStyle.RAISED,
            fill = PhoneStyle.surface,
            radiusDp = PosterCardMaterial.RADIUS_CARD.toFloat(),
            bandDp = PHONE_SOFT_BAND.toFloat(),
            depth = PosterCardMaterial.CARD_DEPTH,
            heavy = true,
        )
        title.phoneSize(PosterCardMaterial.TITLE_TEXT * scale)
        title.setTextColor(PhoneStyle.textDim)
        title.setTypeface(title.typeface, Typeface.BOLD)

        status.phoneSize(PosterCardMaterial.META_TEXT * scale)
        status.setTextColor(PhoneStyle.textDim)
        tagLine.phoneSize(PosterCardMaterial.META_TEXT * scale)
        tagLine.setTextColor(PhoneStyle.textDim)

        listOf(badge, season, corner, heat).forEach { view ->
            view.phoneSize(PosterCardMaterial.BADGE_TEXT * scale)
            view.setTextColor(PhoneStyle.playerText)
        }

        setOnClickListener {
            val handler = onOpen ?: return@setOnClickListener
            series?.let(handler)
        }
        setOnLongClickListener {
            // **始终返回 true**：长按一旦被识别就该被吃掉。
            // 之前 handler 为空时返回 false，View 会把这次长按当成「没消费」，
            // 抬手时再走一遍 click —— 用户看到的就是「长按松手跳去了详情页」。
            val item = series
            // 现取优先：宿主的回调可能比这次绑定晚设置。
            val handler = longPressResolver?.invoke() ?: onLongPress
            if (item == null || handler == null) {
                android.util.Log.i(
                    "HGTV-CARD",
                    "long press ignored: item=${item?.id} handler=${handler != null} " +
                        "resolver=${longPressResolver != null}",
                )
                return@setOnLongClickListener true
            }
            android.util.Log.i("HGTV-CARD", "long press ${item.id}")
            handler(item)
            true
        }
    }

    override fun loadCover(image: ImageView, url: String) {
        artwork.load(image, url, widthPx = cardWidth, heightPx = coverHeight)
    }

    /** 批量选择模式：显示勾选圆点，选中项填充主题色。 */
    fun setBatchSelection(visible: Boolean, selected: Boolean) {
        if (!visible) {
            if (selectionMark.parent === poster) poster.removeView(selectionMark)
            selectionMark.visibility = View.GONE
            return
        }
        if (selectionMark.parent == null) {
            poster.addView(selectionMark, FrameLayout.LayoutParams(
                context.dp(20), context.dp(20), Gravity.TOP or Gravity.END
            ).apply {
                rightMargin = context.dp(8)
                topMargin = context.dp(8)
            })
        }
        selectionMark.visibility = View.VISIBLE
        selectionMark.text = if (selected) "✓" else ""
        selectionMark.background = roundedBackground(
            if (selected) PhoneStyle.accent else 0x99000000.toInt(),
            context.dp(10).toFloat(),
        )
        selectionMark.contentDescription = if (selected) "已选中" else "未选中"
    }
}

private fun phonePosterScale(context: Context, cardWidth: Int): Float =
    (cardWidth.toFloat() / context.dp(94f)).coerceIn(.85f, 1.45f)

/**
 * 手机端海报卡的内缩（dp）。
 *
 * * [PHONE_POSTER_INSET] 海报井内衬：**0**，封面直接占满卡片；
 * * [PHONE_SOFT_BAND] 柔光带：**2**。两列贴合时它是唯一的分界——卡片之间共 12px
 *   （各 6px），再小就分不出边界了。
 */
private const val PHONE_POSTER_INSET = 0
private const val PHONE_SOFT_BAND = 2

private fun phonePosterPosterHeight(context: Context, cardWidth: Int): Int {
    // 与电视端同一条：卡宽先扣掉两侧柔光带与海报井内衬，再按 3:4 求高。
    val band = context.dp(PHONE_SOFT_BAND)
    val inset = context.dp(PHONE_POSTER_INSET)
    val posterWidth = (cardWidth - band * 2 - inset * 2).coerceAtLeast(1)
    return (posterWidth / (3f / 4f)).toInt()
}

private fun phonePosterConfig(context: Context, cardWidth: Int): PosterCardView.Config {
    val scale = phonePosterScale(context, cardWidth)
    return PosterCardMaterial.config(
        px = { value -> context.dp(value) },
        posterHeight = phonePosterPosterHeight(context, cardWidth),
        textScale = scale,
        rankTopColor = PhoneStyle.tips,
        progressTrackColor = PhoneStyle.wellDeep,
        progressFillColor = PhoneStyle.accent,
        // 手机卡片小，四周的内衬再按电视那档走就会显得「卡片外面套了一圈空边」。
        posterInsetDp = PHONE_POSTER_INSET,
    )
}
