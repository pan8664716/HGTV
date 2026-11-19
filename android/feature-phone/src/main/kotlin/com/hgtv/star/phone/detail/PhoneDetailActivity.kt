// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hgtv.star.core.Detail
import com.hgtv.star.core.DetailCache
import com.hgtv.star.core.RecommendBehavior
import com.hgtv.star.core.Series
import com.hgtv.star.phone.ui.PhoneCards
import com.hgtv.star.phone.ui.PhoneEpisodeGrid
import com.hgtv.star.phone.ui.PhoneGlyph
import com.hgtv.star.phone.ui.PhoneSheet
import com.hgtv.star.phone.ui.PhoneSoft.applySoft
import com.hgtv.star.phone.ui.PhoneStyle
import com.hgtv.star.phone.ui.applyPhoneNavigationBar
import com.hgtv.star.phone.ui.bindPhoneInsets
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.phoneEdgeToEdge
import com.hgtv.star.phone.ui.phoneBottomInsetPx
import com.hgtv.star.phone.ui.phoneNavigationBarUsesLightIcons
import com.hgtv.star.phone.ui.phoneSize
import com.hgtv.star.phone.ui.roundedBackground
import com.hgtv.star.shared.data.Library
import com.hgtv.star.shared.data.AppRuntime
import com.hgtv.star.shared.feature.detail.DetailController
import com.hgtv.star.shared.compat.roundCornersCompat
import com.hgtv.star.shared.ui.SoftStyle
import com.hgtv.star.shared.nav.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 详情页：**与 TV 端的详情页一一对应**，按手机形态重排。
 *
 * ```
 * ‹ 返回
 * ┌─────────────┬──────────────────────────┐
 * │             │ 剧名                      │
 * │   封面       │ 题材标签                  │
 * │  （7:10）    │ 第N季 · 全85集 · 作者      │
 * │             │ [继续第3集]               │
 * │             │ [收藏]                    │
 * │             │ [稍后看]                  │
 * └─────────────┴──────────────────────────┘
 * 简介（全部）
 * 选集 · 全85集
 * 第 1—20 集 · 共 85 集            [1-20][21-40] 1/5
 * [1] [2] [3] [4] [5]
 * …
 * 相关剧集
 * [卡片] [卡片] [卡片] …（横向滚动）
 * ```
 *
 * 为什么要有这一页：TV 端的详情页是**收藏 / 选集 / 相关推荐**的落点，手机端早先点海报
 * 直接进播放页，等于把「收藏」「挑集」「看同系列其他季」三件事都丢了。现在海报一律先进这里。
 *
 * 与 TV 一致的三条规则：
 * * 有观看记录时主按钮是「继续第 N 集」，没有时是「播放」；
 * * 选集网格与播放页的选集面板**同一份实现**（竖屏 5 列 / 横屏 10 列、分页集数取设置、边缘滑翻页）；
 * * 未上架（`upcoming`）的剧没有分集，主按钮变成「预约」。
 */
class PhoneDetailActivity : PhoneActivity() {

    companion object {
        const val EXTRA_SERIES_ID = "seriesId"
        const val EXTRA_TITLE = "title"
        private const val DESCRIPTION_COLLAPSED_LINES = 2
    }

    private lateinit var root: FrameLayout
    private lateinit var scroller: ScrollView
    private lateinit var column: LinearLayout
    internal var contentEdge = 0
    internal var landscape = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val detailController by lazy {
        DetailController(
            repository = PhoneServices.repository(this),
            library = PhoneServices.library(this),
            scope = scope,
            account = AppRuntime.accountController(this),
        )
    }

    private val seriesId: String get() = intent.getStringExtra(EXTRA_SERIES_ID).orEmpty()

    internal var library: Library? = null
    internal var detail: Detail? = null
    private var detailAuthorLoading = false

    /** 详情请求的令牌：换剧（`onNewIntent`）时旧请求落地必须丢掉。 */
    private var detailToken = 0

    private lateinit var titleView: TextView
    private lateinit var tagView: TextView
    internal lateinit var metaView: TextView
    private lateinit var descView: TextView
    private lateinit var descRow: LinearLayout
    private lateinit var descToggle: TextView
    private lateinit var cover: ImageView
    private lateinit var primary: DetailActionButton
    private lateinit var favoriteButton: DetailActionButton
    private lateinit var laterButton: DetailActionButton
    private lateinit var episodeHead: TextView
    private lateinit var episodeBox: LinearLayout
    internal lateinit var relatedBox: LinearLayout

    /** 「作者作品」那一栏（作者名为空 / 搜不到时不出现）。 */
    internal lateinit var authorBox: LinearLayout
    internal var authorFor = ""
    private var authorToken = 0
    internal var authorItems: List<Series> = emptyList()
    internal var authorLoading = false
    internal var authorFailed = false

    /**
     * 「相关剧集」那一栏的状态。
     *
     * 与 TV 的 `relatedFor` / `relatedWorks` / `relatedLoading` / `relatedFailed` 一一对应：
     * 同一部剧只请求一次，失败可重试，结果落地后就地重铺这一块（不整页重建）。
     */
    private var relatedFor = ""
    private var relatedToken = 0
    internal var relatedItems: List<Series> = emptyList()
    /** 同系列季数（TV 的标题在 >1 时写成「同系列 N 季 · 相关剧集」）。 */
    internal var relatedSeasons = 0
    internal var relatedLoading = false
    internal var relatedFailed = false

    /** 简介当前是否展开；换剧时回到两行的简单介绍。 */
    private var descExpanded = false
    private var descText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PhoneStyle.ensure(this)
        phoneEdgeToEdge()
        applyPhoneNavigationBar(phoneNavigationBarUsesLightIcons())
        landscape = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (seriesId.isBlank()) { finish(); return }
        buildUi()
        scope.launch {
            detailController.state.collect { state ->
                if (state.detail != null) detail = state.detail
                relatedFor = state.relatedFor
                relatedItems = state.related?.items.orEmpty()
                relatedSeasons = state.related?.seasons?.size ?: 0
                relatedLoading = state.relatedLoading
                relatedFailed = state.relatedError != null
                val related = state.related
                if (related != null && state.relatedFor.isNotBlank()) {
                    PhoneSession.rememberRelated(state.relatedFor, related)
                }
                authorFor = state.authorFor
                authorItems = state.authorWorks.orEmpty()
                authorLoading = state.authorWorksLoading
                authorFailed = state.authorWorksError != null
                detailAuthorLoading = state.authorLoading
                if (detail != null) {
                    paintMeta(detail!!)
                    renderRelated()
                    renderAuthorWorks()
                }
            }
        }
        load()
    }

    // ---------------------------------------------------------------- 界面

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(PhoneStyle.background) }

        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 左右边距按**屏宽比例**（约等于常规档的 14dp），不随字号档位放大：
            // `dp(14)` 在最大档上是 63px，两侧吃掉 126px，右侧内容被白白挤窄。
            // 封面与图片本身不动，只收这一圈留白。
            val edge = (resources.displayMetrics.widthPixels * .039f).toInt()
            contentEdge = edge
            // 底部留白要盖过系统导航条：`phoneEdgeToEdge()` 之后内容会铺到手势区，
            // 最后一行卡片正好落在那里，点击会被系统手势拦走——表现就是「卡片点不动」。
            setPadding(edge, dp(6), edge, dp(32) + phoneBottomInsetPx())
        }
        scroller = ScrollView(this).apply {
            isFillViewport = false
            addView(column, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(scroller, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { topMargin = statusBarHeight() })

        buildHead()
        buildEpisodeArea()
        buildAuthorArea()
        buildRelatedArea()

        setContentView(root)
        root.bindPhoneInsets { bottom -> applyBottomInset(bottom) }
    }

    private fun applyBottomInset(bottomInsetPx: Int) {
        column.setPadding(contentEdge, dp(6), contentEdge, dp(32) + bottomInsetPx)
    }

    /** 头部：左封面 + 右信息列（标题 / 元信息 / 三个按钮），完整简介接在头部下方。 */
    private fun buildHead() {
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(6))
        }

        val coverBox = FrameLayout(this).apply {
            applySoft(SoftStyle.RAISED, PhoneStyle.surface, PhoneStyle.RADIUS_CARD, 4f)
        }
        cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedBackground(
                PhoneStyle.well,
                dp(PhoneStyle.RADIUS_CARD).toFloat(),
            )
            roundCornersCompat(this,dp(PhoneStyle.RADIUS_CARD).toFloat())
        }
        coverBox.addView(cover, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        // 封面宽度**保持原来的 104dp**（跟着字号档位一起放大），不为了给右边让位去压它。
        // 大字档下要省的是**留白**：图片到文字列那一段从 `dp(14)` 改成按屏宽的 2.2%
        // （常规档约 24px），不再随字号一起膨胀。
        val coverWidth = dp(104)
        val infoGap = (resources.displayMetrics.widthPixels * .022f).toInt()
        head.addView(coverBox, LinearLayout.LayoutParams(
            coverWidth, if (landscape) dp(148) else ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 封面内部还有 4dp 留白；信息列用同样的上下留白，标题/按钮才会和图片
            // 的实际上下边落在同一条线上，而不是按封面外框对齐。
            setPadding(infoGap, dp(4), 0, dp(4))
        }
        titleView = TextView(this).apply {
            text = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "加载中…" }
            setTextColor(PhoneStyle.text)
            phoneSize(18)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            // 默认**预留两行**：标题一行还是两行，下面的元信息与简介都不会跟着上下跳。
            // `minLines` 要 API 23，这里用最小高度等价实现：18 号字两行约 21dp。
            minHeight = dp(21)
        }
        info.addView(titleView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        tagView = TextView(this).apply {
            setTextColor(PhoneStyle.textFaint)
            phoneSize(12)
            // 标签整行放在封面那一块**下面**（`column` 里紧跟 head），不再挤在标题与元信息之间：
            // 那一列只有 104dp 封面右侧的宽度，标签稍长就被省略号吃掉。
            setPadding(0, dp(if (landscape) 4 else 8), 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        if (landscape) {
            info.addView(tagView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        metaView = TextView(this).apply {
            setTextColor(PhoneStyle.textDim)
            phoneSize(13)
            setPadding(0, dp(4), 0, 0)
            // 元信息只占一行：字号抬高之后「第3季 · 全99集 · 短剧版权…」正好折成两行，
            // 把三行简介往下推，头部整块变成 215dp 高。次要信息折行是最典型的版面噪音，
            // 一行 + 省略号即可，想看全的往下滚有简介。
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        info.addView(metaView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val actions = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setPadding(0, dp(7), 0, 0)
        }
        primary = DetailActionButton(PhoneGlyph.Kind.PLAY, "播放", primary = true) {
            onPrimary()
        }
        favoriteButton = DetailActionButton(PhoneGlyph.Kind.HEART, "收藏", primary = false) {
            toggleFavorite()
        }
        laterButton = DetailActionButton(PhoneGlyph.Kind.BOOKMARK, "稍后看", primary = false) {
            toggleLater()
        }
        if (landscape) {
            listOf(primary, favoriteButton, laterButton).forEachIndexed { index, button ->
                actions.addView(button, LinearLayout.LayoutParams(0, dp(30), 1f).apply {
                    if (index > 0) leftMargin = dp(6)
                })
            }
        } else {
            actions.addView(primary, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(22)
            ))
            actions.addView(android.widget.Space(this), LinearLayout.LayoutParams(1, 0, 1f))
            actions.addView(favoriteButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(22)
            ))
            actions.addView(android.widget.Space(this), LinearLayout.LayoutParams(1, 0, 1f))
            actions.addView(laterButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(22)
            ))
        }
        info.addView(actions, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (landscape) ViewGroup.LayoutParams.WRAP_CONTENT else 0,
            if (landscape) 0f else 1f,
        ))

        head.addView(info, LinearLayout.LayoutParams(
            0, if (landscape) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT, 1f
        ))
        column.addView(head, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (landscape) ViewGroup.LayoutParams.WRAP_CONTENT else dp(148),
        ))
        if (!landscape) {
            column.addView(tagView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        descRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(if (landscape) 6 else 10), 0, dp(2))
            visibility = View.GONE
        }
        descView = TextView(this).apply {
            setTextColor(PhoneStyle.textFaint)
            phoneSize(13)
            setLineSpacing(dp(2).toFloat(), 1f)
            // 默认只给一段简单介绍；右侧的「展开」按真实布局判断是否真的溢出。
            maxLines = DESCRIPTION_COLLAPSED_LINES
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        descRow.addView(descView, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        descToggle = TextView(this).apply {
            text = "展开"
            setTextColor(PhoneStyle.accent)
            phoneSize(13)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(10), 0, dp(2), 0)
            visibility = View.GONE
            setOnClickListener { setDescriptionExpanded(!descExpanded) }
        }
        descRow.addView(descToggle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        if (landscape) {
            info.addView(descRow, 2, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        } else {
            column.addView(descRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun buildEpisodeArea() {
        episodeHead = TextView(this).apply {
            setTextColor(PhoneStyle.textDim)
            phoneSize(12)
            setPadding(dp(2), dp(14), 0, dp(4))
        }
        column.addView(episodeHead)
        episodeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(episodeBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    /**
     * 作者作品：与电视端同一份数据（`ContentRepository.authorWorks`：出品方直连 + 按页搜）。
     *
     * 手机上它比相关剧集更值钱：用户看完一部想找同一个作者的其它剧，
     * 而「相关剧集」只覆盖同一部剧的其它季与平台给的相似项。
     */
    private fun buildAuthorArea() {
        authorBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        column.addView(authorBox)
    }

    private fun buildRelatedArea() {
        relatedBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        column.addView(relatedBox)
    }

    /** 详情页的一枚动作按钮：整行横排，图标在左、文案在右。 */
    private inner class DetailActionButton(
        kind: PhoneGlyph.Kind,
        label: String,
        private val primary: Boolean,
        onClick: () -> Unit,
    ) : LinearLayout(this@PhoneDetailActivity) {
        private val glyph = PhoneGlyph(context, kind).apply {
            tint = if (primary) Color.WHITE else PhoneStyle.text
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val labelView = TextView(context).apply {
            text = label
            setTextColor(if (primary) Color.WHITE else PhoneStyle.text)
            phoneSize(if (landscape) 11 else 12)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(
                dp(if (landscape) 4 else 8),
                dp(if (landscape) 1 else 2),
                dp(if (landscape) 4 else 8),
                dp(if (landscape) 1 else 2),
            )
            background = PhoneSheet.pillBackground(
                if (primary) PhoneStyle.accent else PhoneStyle.surface,
                if (primary) 0xFF7CBEFF.toInt() else PhoneStyle.surfaceHigh,
                dp(PhoneStyle.RADIUS_CARD).toFloat(),
            )
            val iconSize = dp(if (landscape) 14 else 16)
            addView(glyph, LinearLayout.LayoutParams(iconSize, iconSize))
            addView(labelView, LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f,
            ).apply { leftMargin = dp(if (landscape) 4 else 7) })
            contentDescription = label
            setOnClickListener { onClick() }
        }

        fun update(
            kind: PhoneGlyph.Kind,
            label: String,
            active: Boolean = false,
            description: String = label,
        ) {
            val tone = when {
                primary -> Color.WHITE
                active -> PhoneStyle.accent
                else -> PhoneStyle.text
            }
            glyph.kind = kind
            glyph.filled = active
            glyph.tint = tone
            labelView.text = label
            labelView.setTextColor(tone)
            contentDescription = description
        }
    }

    // ---------------------------------------------------------------- 数据

    private fun load() {
        detailToken++
        detailAuthorLoading=false
        val id = seriesId
        titleView.text = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "加载中…" }
        scope.launch {
            library = PhoneServices.awaitLibrary(this@PhoneDetailActivity)
            if (isFinishing || isDestroyed) return@launch
            detailController.load(
                series = Series(
                    id = id,
                    title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                ),
                onCached = { cached ->
                    if (!isFinishing && !isDestroyed) {
                        detail = cached
                        bindDetail(cached)
                    }
                },
                onLoaded = { merged, _, changed ->
                    if (!isFinishing && !isDestroyed) {
                        detail = merged
                        if (changed) bindDetail(merged)
                    }
                },
                onError = {
                    if (!isFinishing && !isDestroyed && detail == null) metaView.text = "详情加载失败"
                },
            )
        }
    }

    private fun bindDetail(d: Detail) {
        val s = d.series
        titleView.text = s.title
        runCatching {
            PhoneServices.artwork(this).load(
                cover, s.cover, widthPx = dp(118), heightPx = dp(168)
            )
        }

        paintMeta(d)
        tagView.text = s.tags.trim()
        tagView.visibility = if (tagView.text.isBlank()) View.GONE else View.VISIBLE

        bindDescription(s.description.replace('\n', ' ').trim())

        // 主按钮：有记录 → 「继续」；未上架 → 「预约」；否则「播放」。
        val saved = runCatching { library?.progress(s.id) }.getOrNull()
        val resume = saved?.let { p -> d.episodes.indexOf(p.episodeId).takeIf { it >= 0 } }
        when {
            d.upcoming -> {
                // 状态取本机「最后已知」，接口值只在本地没有记录时兜底：用户自己点过的那一次
                // 才是最可信的，而接口在刚写入后会滞后（按它画就成了「预约过了还显示预约」）。
                val reserved = reservedState(d)
                paintReserve(reserved)
            }
            resume != null -> primary.update(
                PhoneGlyph.Kind.PLAY, "继续", description = "继续第 ${resume + 1} 集"
            )
            else -> primary.update(PhoneGlyph.Kind.PLAY, "播放")
        }
        refreshFavorite()

        if (d.episodes.isEmpty()) {
            episodeHead.visibility = View.GONE
            episodeBox.visibility = View.GONE
            return
        }
        // 横屏简介页使用 TV 同一套标题行；竖屏仍保留手机页面的小节标题。
        episodeHead.visibility = if(landscape) View.GONE else View.VISIBLE
        episodeHead.text = "选集 · 全${d.episodes.size}集"
        episodeBox.visibility = View.VISIBLE
        episodeBox.removeAllViews()
        val grid = PhoneEpisodeGrid(
            this, d.episodes.size, resume ?: 0,
            pageSize = library?.episodePageSize ?: 20,
            compact = false,
            columns = if (landscape) 10 else 5,
            // **不限高**：整页本来就在 `ScrollView` 里，网格再套一层内层滚动会互相抢事件
            //（普通 `ScrollView` 不支持嵌套滚动），用户读到的就是「选集显示不全、还滑不动」。
            // 一页 100 集 = 20 行，展开后跟整页一起滚，滚动只有一条。
            maxGridHeight = Int.MAX_VALUE,
            tvStyle = landscape,
            tvSideInsetDp = 14,
            // 与播放页的选集**完全对齐**：手机上「分页边缘翻页」这一项已经没有设置入口
            // （那一项改成了「推荐页左右滑动」），所以这里不再读 `episodeEdgeTurn`——
            // 老设备上它可能是历史遗留的 false，会让详情页「怎么滑都不翻页」。
            swipePages = true,
        ) { index -> openPlayer(index) }
        episodeBox.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    /** 简介默认两行；只有真的被截断时才显示右侧「展开」。 */
    private fun bindDescription(text: String) {
        descText = text
        descExpanded = false
        descView.text = text
        descView.maxLines = DESCRIPTION_COLLAPSED_LINES
        descView.ellipsize = android.text.TextUtils.TruncateAt.END
        descToggle.text = "展开"
        descToggle.visibility = View.GONE
        descRow.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        if (text.isBlank()) return
        descView.post { updateDescriptionToggle() }
    }

    private fun setDescriptionExpanded(expanded: Boolean) {
        descExpanded = expanded
        descView.maxLines = if (expanded) Int.MAX_VALUE else DESCRIPTION_COLLAPSED_LINES
        descView.ellipsize =
            if (expanded) null else android.text.TextUtils.TruncateAt.END
        descToggle.text = if (expanded) "收起" else "展开"
        descToggle.visibility = View.VISIBLE
        descView.requestLayout()
    }

    /**
     * 按真实排版判断简介是否需要入口。
     *
     * `lineCount` 在 `maxLines` 约束下最多是两行，因此还必须看最后一行的省略段：
     * 两行且带省略号才是长简介；刚好两行读完的短简介不摆「展开」。
     */
    private fun updateDescriptionToggle() {
        if (descText.isBlank() || descExpanded) return
        val layout = descView.layout
        val truncated = layout != null && layout.lineCount > 0 &&
            layout.getEllipsisCount(layout.lineCount - 1) > 0
        descToggle.visibility = if (truncated) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- 动作

    private fun onPrimary() {
        val d = detail ?: return
        if (d.upcoming) {
            val lib = library ?: return
            // 预约是账号侧数据（要 `sessionid`）：没登录时不改按钮状态，只提示一句
            // ——与 TV 的 `reserveSeries` 同一套语义：**未登录也记本机**（只提示一句）。
            // 切换基于**按钮上正显示的状态**，不重读接口值：接口滞后会让「取消」变成再预约一次。
            val next = !reservedState(d)
            runCatching { lib.setReserved(d.series.id, next) }
            detail = d.copy(reserved = next)
            paintReserve(next)
            reportRecommendBehavior(
                this,
                if(next) RecommendBehavior.Event.RESERVE else RecommendBehavior.Event.UNRESERVE,
                d.series.id,
            )
            PhoneAccountSync.reserve(
                this,
                d.series,
                next,
                onNeedSignIn = { toast(if (next) "已预约 · 仅保存在本机" else "已取消预约") },
                onDone = { ok ->
                // 失败：本机状态翻回去。用户在这期间又点过的话（本地已经不是 next）就不动，
                // 免得把第二次点击的结果也一起抹掉。
                if (!ok && runCatching { library?.reserved(d.series.id) }.getOrNull() == next) {
                    runCatching { library?.setReserved(d.series.id, !next) }
                }
                // 请求回来时页面可能已经退出：已经销毁的视图不再碰。
                if (!isFinishing && !isDestroyed && !ok) {
                    detail = detail?.copy(reserved = !next)
                    paintReserve(!next)
                    toast(if (next) "预约失败，稍后再试" else "取消预约失败，稍后再试")
                }
                },
            )
            return
        }
        val saved = runCatching { library?.progress(d.series.id) }.getOrNull()
        val index = saved?.let { p -> d.episodes.indexOf(p.episodeId).takeIf { it >= 0 } } ?: 0
        openPlayer(index)
    }

    private fun paintReserve(reserved: Boolean) {
        val label = if (reserved) "已预约" else "预约"
        primary.update(PhoneGlyph.Kind.STAR, label, reserved, label)
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun openPlayer(index: Int) {
        val d = detail ?: return
        phoneNav().open(Route.Player(d.series.id, d.series.title, episode = index))
    }

    /**
     * 收藏 / 取消收藏。
     *
     * 本机先改、账号侧随后推（与 TV 的 `syncFavorite` 同一条路）：登录着的时候，
     * 手机上收藏的剧要出现在别的设备上；没登录时给一句提示，不让「界面变了、云端没变」
     * 变成一件没人说的事。
     */
    private fun toggleFavorite() {
        val d = detail ?: return
        val lib = library ?: return
        val wasFavorite = runCatching { lib.favorite(d.series.id) }.getOrDefault(false)
        val adding = runCatching { lib.toggle(d.series) }.getOrElse { !wasFavorite }
        reportRecommendBehavior(
            this,
            if(adding) RecommendBehavior.Event.FAVORITE else RecommendBehavior.Event.UNFAVORITE,
            d.series.id,
        )
        PhoneAccountSync.favorite(this, d.series, adding) {
            android.widget.Toast.makeText(this, "未登录，收藏只保存在本机", android.widget.Toast.LENGTH_SHORT).show()
        }
        refreshFavorite()
    }

    private fun toggleLater() {
        val d = detail ?: return
        val lib = library ?: return
        runCatching { lib.toggleLater(d.series) }
        refreshFavorite()
    }

    private fun refreshFavorite() {
        val d = detail ?: return
        val lib = library ?: return
        val fav = runCatching { lib.favorite(d.series.id) }.getOrDefault(false)
        favoriteButton.update(
            PhoneGlyph.Kind.HEART,
            "收藏",
            fav,
            if (fav) "已收藏" else "收藏",
        )
        val later = runCatching { lib.queued(d.series.id) }.getOrNull() == true
        laterButton.update(
            PhoneGlyph.Kind.BOOKMARK,
            "稍后看",
            later,
            if (later) "已加入稍后看" else "稍后看",
        )
    }

    internal fun openDetail(series: Series) {
        phoneNav().open(Route.Detail(series.id, series.title))
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    /**
     * 从详情页再点一张「相关剧集 / 作者作品」卡片。
     *
     * 本页在 Manifest 里是 `singleTop`：同一栈顶再 `startActivity` 不会新建实例，而是走这里。
     * 早先没有这个方法，`intent` 还是上一部，`seriesId` 也就还是上一部——页面纹丝不动，
     * 用户读到的就是「卡片点不动」。这里换掉 intent、清掉上一部的所有附加内容，再整页重载。
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent ?: return
        setIntent(intent)
        if (seriesId.isBlank()) return
        detail = null
        relatedFor = ""
        relatedToken++
        relatedItems = emptyList()
        relatedSeasons = 0
        relatedLoading = false
        relatedFailed = false
        relatedBox.removeAllViews()
        relatedBox.visibility = View.GONE
        authorFor = ""
        authorToken++
        authorItems = emptyList()
        authorLoading = false
        authorFailed = false
        authorBox.removeAllViews()
        authorBox.visibility = View.GONE
        episodeHead.visibility = View.GONE
        episodeBox.removeAllViews()
        episodeBox.visibility = View.GONE
        bindDescription("")
        tagView.text = ""
        tagView.visibility = View.GONE
        metaView.text = ""
        scroller.scrollTo(0, 0)
        load()
    }

    override fun onResume() {
        super.onResume()
        // 从播放页回来：收藏态与「继续第N集」都要按最新的记录重画。
        detail?.let { bindDetail(it) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
