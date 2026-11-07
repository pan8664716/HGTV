// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone.page

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.hgtv.star.core.CategoryFilterRow
import com.hgtv.star.core.CategoryGenres
import com.hgtv.star.core.CategoryPanel
import com.hgtv.star.core.CategorySelection
import com.hgtv.star.phone.PhonePlayerActivity
import com.hgtv.star.phone.PhoneServices
import com.hgtv.star.phone.phoneNav
import com.hgtv.star.shared.nav.Route
import com.hgtv.star.phone.ui.PhonePosterFeed
import com.hgtv.star.phone.ui.PhoneCardMenu
import com.hgtv.star.phone.ui.PhoneStyle
import com.hgtv.star.phone.ui.PhoneTopBar
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.phoneSize
import com.hgtv.star.phone.ui.roundedBackground
import com.hgtv.star.phone.ui.tabBarInset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 分类页：**完全按 TV 端的两层筛选结构**。
 *
 * ```
 * ① 体裁（常驻）：推荐 / 漫剧 / AI剧 / 真人      ← CategoryGenres.options，顺序固定
 * ② 细分筛选行：主题 / 设定 / 背景 / 推荐 / 受众 / 时间 / 状态
 *                                               ← 接口 selector_rows（need_selector_panel=true）
 * ③ 三列海报
 * ```
 *
 * 与 TV 一致的三条规则：
 *
 * * 体裁的**四枚固定顺序**是写死的（`CategoryGenres.options`），不跟接口返回顺序走；
 * * 细分行**随体裁变化**：换体裁时清掉 theme / role / epoch / creationStatus，
 *   并重新取一次面板（接口在 genre 行上给了 `refresh_other_selector_row=true`）；
 * * 每行开头都有「全部」，语义是「不传这个维度」。
 *
 * 顶栏固定；子分类筛选行是网格的第一项，和海报一起滚动。
 */
class PhoneCategoryPage(
    context: Context,
    initialState: State? = null,
) : LinearLayout(context) {

    data class State(
        val selection: CategorySelection,
        val genreIndex: Int,
        val panel: CategoryPanel?,
        val feed: PhonePosterFeed.Snapshot,
    )

    private val topBar = PhoneTopBar(context)
    /** 子分类筛选：作为列表头部随海报一起滚动，不自动收起。 */
    private val filterBox = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(0, 0, 0, context.dp(2))
    }
    private val feed = PhonePosterFeed(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var selection = initialState?.selection ?: CategorySelection()
    private var genreIndex = initialState?.genreIndex ?: 0
    private var panel: CategoryPanel? = initialState?.panel

    init {
        orientation = VERTICAL
        setBackgroundColor(PhoneStyle.background)

        // 顶栏固定；筛选行交给网格当第一项，滚动时和列表一起自然移出屏幕。
        feed.setHeader(filterBox)
        addView(topBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(feed, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // 列数跟着设置：竖屏 1–3，横屏映射为 4–6；书库未就绪时先用出厂值。
        feed.applyColumnsFromSettings()
        feed.bottomInset = context.tabBarInset() + context.dp(8)
        // 点海报先进详情页（与 TV 一致）：收藏 / 挑集 / 相关推荐都在那一页。
        feed.onOpen = { series ->
            context.phoneNav().open(Route.Detail(series.id, series.title))
        }
        // 长按海报 = 电视端的「菜单键」：继续观看 / 收藏 / 稍后看 / 详情（见 PhoneCardMenu）。
        // 分类页不是历史、也不是热播榜，因此只有那四项。
        feed.onLongPress = { series -> PhoneCardMenu.show(feed, series) }

        topBar.setTabs(
            CategoryGenres.options.map { it.label },
            genreIndex,
        ) { index -> selectGenre(index) }
        // 子分类紧贴主分类：顶栏底部的 2dp 与网格顶部的 4dp 一起收掉，
        // 否则两段留白叠起来就是用户读到的「空隙太大」。
        topBar.tightenBottom()
        feed.gridTopInset = 0
        topBar.onSearch = {
            context.phoneNav().open(Route.Search)
        }

        val restored = initialState?.feed
        if (restored?.loaded == true) {
            renderFilters()
            val repo = PhoneServices.repository(context)
            feed.restore(restored) { page ->
                visible(repo.categoryFeed(selection, page).page)
            }
        } else {
            loadPanel()
        }
    }

    fun snapshotState(): State = State(
        selection = selection,
        genreIndex = genreIndex,
        panel = panel,
        feed = feed.snapshot(),
    )

    // ---------------------------------------------------------------- 体裁

    private fun selectGenre(index: Int) {
        if (index == genreIndex) return
        genreIndex = index
        topBar.applyTabSelection(index)
        selection = selection.newGenre(CategoryGenres.options[index].id)
        panel = null
        renderFilters()
        loadPanel()
    }

    /**
     * 取筛选面板。
     *
     * 面板与内容是同一次请求回来的（`need_selector_panel=true`），这里只为拿面板，
     * 顺手把第一页内容交给网格——不多发一次请求。
     */
    private fun loadPanel() {
        val repo = PhoneServices.repository(context)
        val snapshot = selection
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repo.categoryFeed(snapshot, 1, needPanel = true) }.getOrNull()
            } ?: return@launch
            if (snapshot != selection) return@launch
            panel = result.panel
            renderFilters()
            feed.submit(result.page.items, result.page.hasMore)
            if (result.page.hasMore) {
                feed.load { page -> visible(repo.categoryFeed(selection, page).page) }
            }
        }
    }

    private fun reload() {
        val repo = PhoneServices.repository(context)
        val snapshot = selection
        feed.load { page -> visible(repo.categoryFeed(snapshot, page).page) }
    }

    /** 长按菜单隐藏过的剧不出现在分类里（与 TV 的 `SeriesGrid` 同一条规则）。 */
    private fun visible(page: com.hgtv.star.core.CatalogPage): com.hgtv.star.core.CatalogPage {
        val hidden = runCatching { PhoneServices.library(context).hidden() }.getOrDefault(emptySet())
        return com.hgtv.star.core.CatalogFilter.visible(page, hidden)
    }

    // ---------------------------------------------------------------- 筛选行

    private fun renderFilters() {
        filterBox.removeAllViews()
        val rows = panel?.rows?.filter { it.type != CategorySelection.TYPE_GENRE }.orEmpty()
        if (rows.isEmpty()) {
            filterBox.visibility = View.GONE
            feed.refreshHeader()
            return
        }
        filterBox.visibility = View.VISIBLE
        rows.forEach { row -> filterBox.addView(buildRow(row)) }
        feed.refreshHeader()
    }

    /**
     * 一行筛选：左边固定宽度的标签（「主题」「设定」…），右边可横滑的 chips。
     *
     * 与 TV 的排版同构：标签只说明这一栏管什么、不参与选择；chips 的第一个是「全部」。
     */
    private fun buildRow(row: CategoryFilterRow): View {
        val line = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(12), 0, context.dp(4), 0)
            // 30 → 24（用户要求「子分类太高」）：行高只留 chip 本身 + 一圈薄留白。
            minimumHeight = context.dp(24)
        }
        val label = row.title.removePrefix("全部").ifBlank { row.title }
        line.addView(TextView(context).apply {
            text = label
            setTextColor(PhoneStyle.textDim)
            phoneSize(11)
            maxLines = 1
        }, LayoutParams(context.dp(38), LayoutParams.WRAP_CONTENT))

        val chips = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val selectedId = selection.valueOf(row.type)
        // 「全部」排第一：语义是「不传这个维度」，接口返回的 options 里通常没有它。
        val options = listOf<Pair<String?, String>>(null to "全部") +
            row.options.filter { it.id != null }.map { it.id to it.label }
        options.forEach { (id, text) ->
            val on = id == selectedId
            chips.addView(TextView(context).apply {
                this.text = text
                phoneSize(11)
                gravity = Gravity.CENTER
                setTextColor(if (on) PhoneStyle.text else PhoneStyle.textDim)
                background = if (on) {
                    roundedBackground(PhoneStyle.surfaceHigh, PhoneStyle.RADIUS_PILL)
                } else null
                // 上下内边距 4 → 3：行高收到 24dp 之后，4 会让 chip 顶满整行。
                setPadding(context.dp(9), context.dp(3), context.dp(9), context.dp(3))
                setOnClickListener {
                    selection = selection.with(row.type, id)
                    renderFilters()
                    reload()
                }
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                rightMargin = context.dp(3)
            })
        }
        line.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }, LayoutParams(0, context.dp(24), 1f))
        return line
    }

    fun scrollToTop() = feed.scrollToTop()

    /** 从设置页返回时重新对齐「列表每行」。 */
    fun onShown() {
        feed.applyColumnsFromSettings()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scope.cancel()
    }
}
