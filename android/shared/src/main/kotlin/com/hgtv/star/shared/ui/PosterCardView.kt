// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.hgtv.star.core.PosterCardContent
import com.hgtv.star.core.Series
import com.hgtv.star.shared.compat.roundCornersCompat

/**
 * 电视与手机共用的竖版海报卡骨架。
 *
 * ```
 * ┌─ 卡片底板（平台自己提供）──────────────────────┐
 * │ ┌─ 海报井 ─────────────────────────────────┐ │
 * │ │ [系统标签 / 名次]              [第 N 季] │ │
 * │ │                 封面                     │ │
 * │ │ [🔥热度]              [共 N 集 / 兜底]   │ │
 * │ │ 已播进度条（可选）                        │ │
 * │ └──────────────────────────────────────────┘ │
 * │  剧名                                        │
 * │  类型标签                          状态       │
 * └──────────────────────────────────────────────┘
 * ```
 *
 * 公共层负责**结构与规则**：四枚角标的摆放、季号、标签优先、集数兜底、热度、
 * 第二行显隐、封面解绑与复用、无障碍描述。平台层只提供尺寸、字号、颜色、
 * 压暗层、进度条配色，以及电视的焦点态。
 *
 * 这样两端不可能再出现「手机上默认第 1 季、电视上留空」这种分叉；
 * 材质与文字大小仍可按平台各自调整。
 */
open class PosterCardView(
    context: Context,
    private val config: Config,
) : LinearLayout(context) {

    data class Padding(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    enum class MetaMode {
        /** 标签与状态互斥：只给极窄卡片保留。 */
        EXCLUSIVE,

        /** 标签靠左、状态靠右同时显示。电视与手机默认都用这一档。 */
        BOTH,
    }

    /**
     * 平台差异。
     *
     * 所有尺寸与颜色都已经是像素 / ARGB，公共层不再猜平台密度。
     */
    data class Config(
        val posterHeight: Int,
        val posterInset: Int,
        val posterTopMargin: Int,
        val badgePadding: Padding,
        val badgeMargin: Int,
        val titlePadding: Padding,
        val metaPadding: Padding,
        val cardRadius: Int,
        val posterRadius: Int,
        val scrim: (place: Int) -> Drawable,
        val posterBackground: Drawable? = null,
        val posterOverlay: Drawable? = null,
        val rankTopColor: Int = 0xFFFFD166.toInt(),
        val metaMode: MetaMode = MetaMode.BOTH,
        val showProgress: Boolean = false,
        val progressHeight: Int = 2,
        val progressTrackColor: Int = Color.TRANSPARENT,
        val progressFillColor: Int = Color.TRANSPARENT,
    )

    /** 这一张卡现在绑定的是哪一部；复用卡片的点击回调应读它，而不是捕获旧条目。 */
    var series: Series? = null

    val image = ImageView(context)
    val poster = PosterWell(context, config.posterOverlay, config.posterRadius)
    val title = TextView(context)
    val status = TextView(context)
    val tagLine = TextView(context)
    val badge = TextView(context)
    val corner = TextView(context)
    val season = TextView(context)
    val heat = TextView(context)
    val progress = PosterProgressView(context)

    private val meta: LinearLayout
    private var preferStatus = false
    private var cover = ""
    /** 封面是否因低内存策略被暂时摘掉（URL 仍保留，见 [resumeCover]）。 */
    var coverSuspended = false
        private set
    private var fallbackBadge = ""

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false

        poster.background = config.posterBackground
        poster.clipChildren = true
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        image.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        poster.addView(image, FrameLayout.LayoutParams(-1, -1))

        if (config.showProgress) {
            progress.visibility = View.GONE
            progress.trackColor = config.progressTrackColor
            progress.fillColor = config.progressFillColor
            progress.radius = config.posterRadius.toFloat()
            poster.addView(progress, FrameLayout.LayoutParams(-1, config.progressHeight, Gravity.BOTTOM))
        }

        listOf(
            badge to (Gravity.BOTTOM or Gravity.END),
            season to (Gravity.TOP or Gravity.END),
            corner to (Gravity.TOP or Gravity.START),
            heat to (Gravity.BOTTOM or Gravity.START),
        ).forEach { (view, place) ->
            view.setTextColor(Color.WHITE)
            view.includeFontPadding = false
            view.setTypeface(view.typeface, Typeface.BOLD)
            view.gravity = place
            view.background = config.scrim(place)
            view.setPadding(
                config.badgePadding.left,
                config.badgePadding.top,
                config.badgePadding.right,
                config.badgePadding.bottom,
            )
            view.visibility = View.GONE
            poster.addView(view, badgeLayout(place))
        }

        addView(
            poster,
            LinearLayout.LayoutParams(-1, config.posterHeight).apply {
                leftMargin = config.posterInset
                rightMargin = config.posterInset
                topMargin = config.posterTopMargin
            },
        )

        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        title.setSingleLine(true)
        title.includeFontPadding = false
        title.setPadding(
            config.titlePadding.left,
            config.titlePadding.top,
            config.titlePadding.right,
            config.titlePadding.bottom,
        )

        status.maxLines = 1
        status.setSingleLine(true)
        status.ellipsize = TextUtils.TruncateAt.END
        status.includeFontPadding = false
        tagLine.maxLines = 1
        tagLine.setSingleLine(true)
        tagLine.ellipsize = TextUtils.TruncateAt.END
        tagLine.includeFontPadding = false
        tagLine.gravity = Gravity.START or Gravity.CENTER_VERTICAL

        meta = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                config.metaPadding.left,
                config.metaPadding.top,
                config.metaPadding.right,
                config.metaPadding.bottom,
            )
            visibility = View.GONE
            addView(tagLine, LinearLayout.LayoutParams(0, -2).apply { weight = 1f })
            addView(status, LinearLayout.LayoutParams(-2, -2))
        }
        addView(title, LinearLayout.LayoutParams(-1, -2))
        addView(meta, LinearLayout.LayoutParams(-1, -2))
    }

    /**
     * 绑定一张卡。
     *
     * 所有文案与「显示谁」的判断在 [PosterCardContent] 已经完成；这里只负责落到 View。
     */
    fun bind(item: Series, content: PosterCardContent) {
        series = item
        title.text = content.title
        tagLine.text = content.tags
        status.text = content.status
        preferStatus = content.preferStatus
        syncMeta()

        season.text = content.season
        season.visibility = if (content.season.isBlank()) View.GONE else View.VISIBLE

        heat.text = content.heat
        heat.visibility = if (content.heat.isBlank()) View.GONE else View.VISIBLE

        if (content.corner.isBlank()) {
            corner.visibility = View.GONE
        } else {
            corner.visibility = View.VISIBLE
            corner.text = content.corner
            corner.setTextColor(if (content.cornerTop) config.rankTopColor else Color.WHITE)
            corner.background = labelBackground(content.cornerColors, config.posterRadius)
                ?: config.scrim(Gravity.TOP or Gravity.START)
        }

        fallbackBadge = content.episode
        setEpisodeBadge(item.episodeCount)
        contentDescription = content.contentDescription
        setCover(item.cover)
    }

    fun setCover(url: String) {
        if (url == cover && !coverSuspended) return
        coverSuspended = false
        cover = url
        image.setImageDrawable(null)
        // 空 URL 也要交给平台加载器：它会解绑旧请求，避免看不见的卡片继续占队列。
        loadCover(image, url)
    }

    /** 平台层提供封面加载；默认不做任何事，只负责卡片结构。 */
    protected open fun loadCover(image: ImageView, url: String) = Unit

    /** 卡片离开视口：解除 ImageView 对位图的引用，但清掉 URL。 */
    fun releaseCover() {
        cover = ""
        coverSuspended = false
        image.setImageDrawable(null)
        loadCover(image, "")
    }

    /** 低内存：只摘位图，保留地址，视口回来时可直接重挂。 */
    fun suspendCover(): Boolean {
        if (cover.isBlank() || coverSuspended) return false
        coverSuspended = true
        image.setImageDrawable(null)
        loadCover(image, "")
        return true
    }

    fun resumeCover(): Boolean {
        if (!coverSuspended) return false
        coverSuspended = false
        loadCover(image, cover)
        return true
    }

    fun setStatus(value: String) {
        val next = com.hgtv.star.core.EpisodeLabel.cardStatus(value)
        if (status.text.toString() != next) status.text = next
        syncMeta()
    }

    fun setTags(value: String) {
        val next = PosterCardContent.tags(value)
        if (tagLine.text.toString() == next) return
        tagLine.text = next
        syncMeta()
    }

    fun setEpisodeBadge(count: Int) {
        val text = com.hgtv.star.core.EpisodeLabel.total(count).ifEmpty { fallbackBadge }
        badge.text = text
        badge.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    fun setProgress(value: Int?) {
        val next = value?.coerceIn(0, 100) ?: -1
        progress.visibility = if (next < 0) View.GONE else View.VISIBLE
        if (next >= 0) progress.percent = next
    }

    fun resizePoster(height: Int) {
        val params = poster.layoutParams
        if (params.height == height) return
        params.height = height
        poster.layoutParams = params
    }

    private fun syncMeta() {
        val hasTag = tagLine.text.isNotBlank()
        val hasStatus = status.text.isNotBlank()
        when (config.metaMode) {
            MetaMode.BOTH -> {
                tagLine.visibility = if (hasTag) View.VISIBLE else View.GONE
                status.visibility = if (hasStatus) View.VISIBLE else View.GONE
                meta.visibility = if (hasTag || hasStatus) View.VISIBLE else View.GONE
            }
            MetaMode.EXCLUSIVE -> {
                val showStatus = hasStatus && (preferStatus || !hasTag)
                tagLine.visibility = if (!showStatus && hasTag) View.VISIBLE else View.GONE
                status.visibility = if (showStatus) View.VISIBLE else View.GONE
                meta.visibility = if (showStatus || hasTag) View.VISIBLE else View.GONE
            }
        }
    }

    private fun badgeLayout(place: Int) = FrameLayout.LayoutParams(-2, -2, place).apply {
        when {
            place == (Gravity.TOP or Gravity.START) -> {
                leftMargin = config.badgeMargin
                topMargin = config.badgeMargin
            }
            place == (Gravity.TOP or Gravity.END) -> {
                rightMargin = config.badgeMargin
                topMargin = config.badgeMargin
            }
            place == (Gravity.BOTTOM or Gravity.START) -> {
                leftMargin = config.badgeMargin
                bottomMargin = config.badgeMargin
            }
            else -> {
                rightMargin = config.badgeMargin
                bottomMargin = config.badgeMargin
            }
        }
    }
}

/**
 * 海报井：封面先画、压暗层居中、进度与四枚角标最后画。
 *
 * 压暗层可以换成各平台的 `SoftDrawable`；为 null 时不画。
 */
class PosterWell(
    context: Context,
    private val overlay: Drawable?,
    private val radiusPx: Int,
) : FrameLayout(context) {
    init {
        clipChildren = true
        clipToPadding = true
        if (radiusPx > 0) roundCornersCompat(this, radiusPx.toFloat())
    }

    override fun dispatchDraw(canvas: Canvas) {
        val count = childCount
        if (count == 0 || width <= 0 || height <= 0) return
        drawChild(canvas, getChildAt(0), drawingTime)
        overlay?.let {
            it.setBounds(0, 0, width, height)
            it.draw(canvas)
        }
        for (index in 1 until count) drawChild(canvas, getChildAt(index), drawingTime)
    }
}

/**
 * 海报底边进度条。轨道与已播段各一个颜色，圆角跟海报一致。
 */
class PosterProgressView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = RectF()

    var percent: Int = 0
        set(value) {
            val next = value.coerceIn(0, 100)
            if (next == field) return
            field = next
            invalidate()
        }

    var trackColor: Int = Color.TRANSPARENT
    var fillColor: Int = Color.TRANSPARENT
    var radius: Float = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        track.set(0f, 0f, w.toFloat(), h.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        paint.color = trackColor
        canvas.drawRoundRect(track, radius, radius, paint)
        val filled = width * (percent / 100f)
        if (filled > 0f) {
            paint.color = fillColor
            canvas.drawRoundRect(RectF(0f, 0f, filled, height.toFloat()), radius, radius, paint)
        }
    }
}
