// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hgtv.star.core.Detail
import com.hgtv.star.core.Series
import com.hgtv.star.phone.ui.PhoneCards
import com.hgtv.star.phone.ui.PhoneEpisodeGrid
import com.hgtv.star.phone.ui.PhoneGlyph
import com.hgtv.star.phone.ui.PhoneSheet
import com.hgtv.star.phone.ui.PhoneSoft.applySoft
import com.hgtv.star.phone.ui.PhoneStyle
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.navigationBarPx
import com.hgtv.star.phone.ui.phoneEdgeToEdge
import com.hgtv.star.phone.ui.phoneSize
import com.hgtv.star.phone.ui.roundedBackground
import com.hgtv.star.shared.compat.clipToCircleCompat
import com.hgtv.star.shared.data.Library
import com.hgtv.star.shared.ui.SoftStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * * 选集网格与播放页的选集面板**同一份实现**（每行 5 个、分页集数取设置、边缘滑翻页）；
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val seriesId: String get() = intent.getStringExtra(EXTRA_SERIES_ID).orEmpty()

    private var library: Library? = null
    private var detail: Detail? = null

    /** 详情请求的令牌：换剧（`onNewIntent`）时旧请求落地必须丢掉。 */
    private var detailToken = 0

    private lateinit var titleView: TextView
    private lateinit var tagView: TextView
    private lateinit var metaView: TextView
    private lateinit var descView: TextView
    private lateinit var descRow: LinearLayout
    private lateinit var descToggle: TextView
    private lateinit var cover: ImageView
    private lateinit var primary: DetailActionButton
    private lateinit var favoriteButton: DetailActionButton
    private lateinit var laterButton: DetailActionButton
    private lateinit var episodeHead: TextView
    private lateinit var episodeBox: LinearLayout
    private lateinit var relatedBox: LinearLayout

    /** 「作者作品」那一栏（作者名为空 / 搜不到时不出现）。 */
    private lateinit var authorBox: LinearLayout
    private var authorFor = ""
    private var authorToken = 0
    private var authorItems: List<Series> = emptyList()
    private var authorAvatar = ""
    private var authorLoading = false
    private var authorFailed = false

    /**
     * 「相关剧集」那一栏的状态。
     *
     * 与 TV 的 `relatedFor` / `relatedWorks` / `relatedLoading` / `relatedFailed` 一一对应：
     * 同一部剧只请求一次，失败可重试，结果落地后就地重铺这一块（不整页重建）。
     */
    private var relatedFor = ""
    private var relatedToken = 0
    private var relatedItems: List<Series> = emptyList()
    /** 同系列季数（TV 的标题在 >1 时写成「同系列 N 季 · 相关剧集」）。 */
    private var relatedSeasons = 0
    private var relatedLoading = false
    private var relatedFailed = false

    /** 简介当前是否展开；换剧时回到两行的简单介绍。 */
    private var descExpanded = false
    private var descText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PhoneStyle.ensure(this)
        phoneEdgeToEdge()
        if (seriesId.isBlank()) { finish(); return }
        buildUi()
        load()
    }

    // ---------------------------------------------------------------- 界面

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(PhoneStyle.background) }

        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 底部留白要盖过系统导航条：`phoneEdgeToEdge()` 之后内容会铺到手势区，
            // 最后一行卡片正好落在那里，点击会被系统手势拦走——表现就是「卡片点不动」。
            setPadding(dp(14), dp(6), dp(14), dp(32) + navigationBarPx())
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
            clipToOutline = true
        }
        coverBox.addView(cover, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        // 7:10 竖版封面（接口封面的常见比例），与 TV 详情页同一档。
        // 字缩到 50% 之后 118×168 的封面会把右侧文字列挤成一条，收成 104×148。
        // 把封面与右侧信息区钉成同一高度：标题与按钮的分区始终和图片上下边对齐。
        head.addView(coverBox, LinearLayout.LayoutParams(
            dp(104), ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 封面内部还有 4dp 留白；信息列用同样的上下留白，标题/按钮才会和图片
            // 的实际上下边落在同一条线上，而不是按封面外框对齐。
            setPadding(dp(14), dp(4), 0, dp(4))
        }
        titleView = TextView(this).apply {
            text = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "加载中…" }
            setTextColor(PhoneStyle.text)
            phoneSize(18)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        info.addView(titleView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        tagView = TextView(this).apply {
            setTextColor(PhoneStyle.textFaint)
            phoneSize(12)
            setPadding(0, dp(3), 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        info.addView(tagView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

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
            orientation = LinearLayout.VERTICAL
            // 紧接集数 / 作者行，不再用一整段弹性空白把按钮推到底部；
            // 剩余高度均分给三枚按钮之间的两个间隙。
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
        actions.addView(primary, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(22)
        ))
        actions.addView(android.widget.Space(this), LinearLayout.LayoutParams(
            1, 0, 1f
        ))
        actions.addView(favoriteButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(22)
        ))
        actions.addView(android.widget.Space(this), LinearLayout.LayoutParams(
            1, 0, 1f
        ))
        actions.addView(laterButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(22)
        ))
        info.addView(actions, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        head.addView(info, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
        ))
        column.addView(head, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(148)
        ))

        descRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(10), 0, dp(2))
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
        column.addView(descRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
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
            phoneSize(12)
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
            setPadding(dp(8), dp(2), dp(8), dp(2))
            background = PhoneSheet.pillBackground(
                if (primary) PhoneStyle.accent else PhoneStyle.surface,
                if (primary) 0xFF7CBEFF.toInt() else PhoneStyle.surfaceHigh,
                dp(PhoneStyle.RADIUS_CARD).toFloat(),
            )
            addView(glyph, LinearLayout.LayoutParams(dp(16), dp(16)))
            addView(labelView, LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f,
            ).apply { leftMargin = dp(7) })
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
        val token = ++detailToken
        val id = seriesId
        titleView.text = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "加载中…" }
        scope.launch {
            val lib = PhoneServices.awaitLibrary(this@PhoneDetailActivity)
            if (token != detailToken || isFinishing || isDestroyed) return@launch
            library = lib
            val d = withContext(Dispatchers.IO) {
                runCatching { PhoneServices.repository(this@PhoneDetailActivity).detail(id) }
                    .getOrNull()
            }
            // 换剧之后旧请求才回来：直接丢掉，不能拿上一部的详情覆盖这一部。
            if (token != detailToken || isFinishing || isDestroyed) return@launch
            if (d == null) {
                metaView.text = "详情加载失败"
                return@launch
            }
            detail = d
            bindDetail(d)
            // 与 TV 完全同一条时序：详情先上屏，**附加内容丢后台**（`prefetchRelated` /
            // `prefetchAuthorWorks`）。不 await、不占详情的关键路径——用户看到标题与选集时，
            // 这两栏还在路上，落地之后各自就地补上。
            prefetchRelated(d.series)
            prefetchAuthorWorks(d.series)
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

        // 元信息：第N季 · 全N集 · 作者（与 TV 详情页同一套，缺哪项就不占位）。
        val meta = ArrayList<String>(4)
        if (s.seasonIndex > 0) meta += "第${s.seasonIndex}季"
        if (d.upcoming) {
            meta += "尚未上线"
        } else if (d.episodes.isNotEmpty()) {
            meta += "全${d.episodes.size}集"
        }
        if (s.author.isNotBlank()) meta += s.author
        metaView.text = meta.joinToString("  ·  ")
        metaView.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE
        tagView.text = s.tags.trim()
        tagView.visibility = if (tagView.text.isBlank()) View.GONE else View.VISIBLE

        bindDescription(s.description.replace('\n', ' ').trim())

        // 主按钮：有记录 → 「继续」；未上架 → 「预约」；否则「播放」。
        val saved = runCatching { library?.progress(s.id) }.getOrNull()
        val resume = saved?.let { p -> d.episodes.indexOf(p.episodeId).takeIf { it >= 0 } }
        when {
            d.upcoming -> {
                val label = if (d.reserved) "已预约" else "预约"
                primary.update(PhoneGlyph.Kind.STAR, label, d.reserved, label)
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
        episodeHead.visibility = View.VISIBLE
        episodeHead.text = "选集 · 全${d.episodes.size}集"
        episodeBox.visibility = View.VISIBLE
        episodeBox.removeAllViews()
        val grid = PhoneEpisodeGrid(
            this, d.episodes.size, resume ?: 0,
            pageSize = library?.episodePageSize ?: 20,
            compact = false,
            // 详情页在滚动容器里，网格自身也要能滚（一页 100 集 = 20 行）。
            maxGridHeight = (resources.displayMetrics.heightPixels * .46f).toInt(),
            edgeTurn = library?.episodeEdgeTurn ?: true,
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

    // ---------------------------------------------------------------- 相关剧集

    /**
     * 相关剧集：**后台预取**，详情上屏之后才发。
     *
     * 与 TV 的 `prefetchRelated` 同一条规则：
     * * 同一部剧只请求一次（已经在跑 / 已经取到就直接返回）；
     * * 单独持有 token，换剧作废旧请求，落地前再确认「还是同一部剧」；
     * * 不走详情页的加载协程，失败只影响这一栏，不影响详情本身。
     */
    private fun prefetchRelated(s: Series) {
        if (relatedFor == s.id && (relatedItems.isNotEmpty() || relatedLoading)) return
        val token = ++relatedToken
        relatedFor = s.id
        relatedLoading = true
        relatedFailed = false
        relatedItems = emptyList()
        relatedSeasons = 0
        renderRelated()
        scope.launch {
            val works = withContext(Dispatchers.IO) {
                runCatching {
                    PhoneServices.repository(this@PhoneDetailActivity).related(s.id, s.title)
                }.getOrNull()
            }
            if (token != relatedToken || isFinishing || isDestroyed) return@launch
            relatedLoading = false
            if (works == null) {
                relatedFailed = true
            } else {
                relatedItems = works.items
                relatedSeasons = works.seasons.size
                // 记到进程里：播放页片尾的「换一部接着看」直接铺这一份，
                // 不必再发一次请求（电视端同样是 `relatedWorks` 共用）。
                PhoneSession.rememberRelated(s.id, works)
            }
            renderRelated()
        }
    }

    /**
     * 铺「相关剧集」这一栏。
     *
     * **不截断、不过滤正在看的这一部**：TV 的卡片行把当前这一季留在原位、角标写「正在看」，
     * 手机早先 `filter { it.id != s.id }` + `take(20)` 把当前季和 20 条之后的作品一起丢了，
     * 用户读到的就是「相关作品显示不全」。现在与 TV 同一份列表、同一套角标。
     */
    private fun renderRelated() {
        val s = detail?.series
        relatedBox.removeAllViews()
        if (s == null || (!relatedLoading && !relatedFailed && relatedItems.isEmpty())) {
            relatedBox.visibility = View.GONE
            return
        }
        relatedBox.visibility = View.VISIBLE
        val status = when {
            relatedLoading -> "正在查找…"
            relatedFailed -> "加载失败，点此重试"
            else -> "共 ${relatedItems.size} 部"
        }
        val heading = if (relatedSeasons > 1) "同系列 ${relatedSeasons} 季 · 相关剧集" else "相关剧集"
        relatedBox.addView(sectionHead(heading, status, relatedFailed) {
            prefetchRelated(s)
        })
        if (relatedItems.isNotEmpty()) {
            relatedBox.addView(cardRow(relatedItems, s.id), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    // ---------------------------------------------------------------- 作者作品

    /**
     * 作者作品：**后台预取 + 两段交付**。
     *
     * 与 TV 的 `prefetchAuthorWorks` 一一对应：这一栏要按作者名搜（再按家族补季），
     * 比相关剧集慢得多，因此第一页到手就先铺、完整结果回来再就地换一份。
     * 同一作者只请求一次；换剧作废旧请求。
     */
    private fun prefetchAuthorWorks(s: Series) {
        val name = s.author.trim()
        if (name.isEmpty()) {
            authorBox.removeAllViews()
            authorBox.visibility = View.GONE
            return
        }
        if (authorFor == name && (authorItems.isNotEmpty() || authorLoading)) return
        val token = ++authorToken
        authorFor = name
        authorAvatar = ""
        authorLoading = true
        authorFailed = false
        authorItems = emptyList()
        renderAuthorWorks()
        scope.launch {
            val repo = PhoneServices.repository(this@PhoneDetailActivity)
            val works = withContext(Dispatchers.IO) {
                runCatching {
                    repo.authorWorks(
                        name, s,
                        onPartial = { partial ->
                            // 第一段：搜索阶段一结束就先铺上屏（补季还在后台跑）。
                            runOnUiThread {
                                if (token != authorToken || isFinishing || isDestroyed) return@runOnUiThread
                                if (partial.isEmpty()) return@runOnUiThread
                                authorLoading = false
                                authorItems = partial
                                renderAuthorWorks()
                            }
                        },
                        onAvatar = { avatar ->
                            runOnUiThread {
                                if (token != authorToken || isFinishing || isDestroyed) return@runOnUiThread
                                if (avatar.isBlank() || avatar == authorAvatar) return@runOnUiThread
                                authorAvatar = avatar
                                renderAuthorWorks()
                            }
                        }
                    )
                }.getOrNull()
            }
            if (token != authorToken || isFinishing || isDestroyed) return@launch
            authorLoading = false
            if (works == null) authorFailed = true else authorItems = works
            renderAuthorWorks()
        }
    }

    /**
     * 铺「作者作品」这一栏：圆形首字头像 + 作者名 + 状态行 + 一整排卡片。
     *
     * 摆法与 TV 的 `authorHead` / `authorRow` 一致：**当前这一部排第一张**（不从列表里去掉），
     * 其余按 core 返回的服务端顺序原样摆；不截断——早先 `take(20)` 让高产作者少了小半。
     */
    private fun renderAuthorWorks() {
        val s = detail?.series
        authorBox.removeAllViews()
        if (s == null || authorFor.isBlank()) {
            authorBox.visibility = View.GONE
            return
        }
        authorBox.visibility = View.VISIBLE
        val name = authorFor
        val items = listOf(s) + authorItems.filter { it.id != s.id }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(16), dp(2), dp(8))
        }
        // 作者头像是用户搜索 / 资料接口的 `user_avatar`，不是作品封面。
        // 没有头像时保留同色圆底，不拿本剧封面或作者作品封面顶替。
        val avatar = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedBackground(authorColor(name), dp(15).toFloat())
            contentDescription = name
            clipToCircleCompat(this)
        }
        if (authorAvatar.isNotBlank()) {
            runCatching {
                PhoneServices.artwork(this).load(
                    avatar, authorAvatar, widthPx = dp(30), heightPx = dp(30)
                )
            }
        }
        head.addView(avatar, LinearLayout.LayoutParams(dp(30), dp(30)))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, 0, 0)
        }
        texts.addView(TextView(this).apply {
            text = name
            setTextColor(PhoneStyle.text)
            phoneSize(15)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val hint = when {
            authorLoading -> "正在查找该作者的作品…"
            authorFailed -> "没能取到该作者的作品，点此重试"
            items.size <= 1 -> "这一位暂时只有这一部作品"
            else -> "共 ${items.size} 部作品"
        }
        texts.addView(TextView(this).apply {
            text = hint
            setTextColor(if (authorFailed) PhoneStyle.accent else PhoneStyle.textFaint)
            phoneSize(11)
            setPadding(0, dp(3), 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            if (authorFailed) setOnClickListener { prefetchAuthorWorks(s) }
        })
        head.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        authorBox.addView(head, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        authorBox.addView(cardRow(items, s.id), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    // ---------------------------------------------------------------- 卡片行

    /** 一栏的标题行：标题 + 右侧状态（加载中 / 失败可重试 / 共 N 部）。 */
    private fun sectionHead(
        title: String,
        status: String,
        retry: Boolean,
        onRetry: () -> Unit,
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(16), dp(2), dp(6))
        }
        row.addView(TextView(this).apply {
            text = title
            setTextColor(PhoneStyle.textDim)
            phoneSize(12)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = status
            setTextColor(if (retry) PhoneStyle.accent else PhoneStyle.textFaint)
            phoneSize(11)
            if (retry) {
                setPadding(dp(8), dp(2), dp(2), dp(2))
                setOnClickListener { onRetry() }
            }
        })
        return row
    }

    /**
     * 横向卡片行（相关剧集与作者作品共用）。
     *
     * 真正的铺法在 [PhoneCards.row]：**播放页的「换一部接着看」用的是同一份**，
     * 两处卡片因此不可能长得不一样（电视端也是 `buildRelatedRow` 一处实现）。
     */
    private fun cardRow(items: List<Series>, currentId: String): HorizontalScrollView =
        PhoneCards.row(this, PhoneServices.artwork(this), items, currentId) { item ->
            openDetail(item)
        }

    /** 作者头像底色：色盘与算法在 `:shared`（见 [com.hgtv.star.shared.ui.authorColor]），电视端用同一份。 */
    private fun authorColor(name: String): Int = com.hgtv.star.shared.ui.authorColor(name)

    // ---------------------------------------------------------------- 动作

    private fun onPrimary() {
        val d = detail ?: return
        if (d.upcoming) {
            val lib = library ?: return
            runCatching { lib.setReserved(d.series.id, !d.reserved) }
            d.let { detail = it.copy(reserved = !it.reserved) }
            val reserved = detail?.reserved == true
            val label = if (reserved) "已预约" else "预约"
            primary.update(PhoneGlyph.Kind.STAR, label, reserved, label)
            return
        }
        val saved = runCatching { library?.progress(d.series.id) }.getOrNull()
        val index = saved?.let { p -> d.episodes.indexOf(p.episodeId).takeIf { it >= 0 } } ?: 0
        openPlayer(index)
    }

    private fun openPlayer(index: Int) {
        val d = detail ?: return
        startActivity(Intent(this, PhonePlayerActivity::class.java).apply {
            putExtra(PhonePlayerActivity.EXTRA_SERIES_ID, d.series.id)
            putExtra(PhonePlayerActivity.EXTRA_TITLE, d.series.title)
            putExtra(PhonePlayerActivity.EXTRA_EPISODE, index)
        })
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

    private fun openDetail(series: Series) {
        startActivity(Intent(this, PhoneDetailActivity::class.java).apply {
            putExtra(EXTRA_SERIES_ID, series.id)
            putExtra(EXTRA_TITLE, series.title)
        })
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
        authorAvatar = ""
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
