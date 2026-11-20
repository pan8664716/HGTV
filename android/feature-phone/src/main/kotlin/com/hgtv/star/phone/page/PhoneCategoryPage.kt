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
import com.hgtv.star.core.CategoryPanelCache
import com.hgtv.star.core.CategorySelection
import com.hgtv.star.core.CatalogPage
import com.hgtv.star.core.CatalogPageCache
import com.hgtv.star.core.CatalogPaging
import com.hgtv.star.core.categoryFeed
import com.hgtv.star.phone.PhonePlayerActivity
import com.hgtv.star.phone.PhoneServices
import com.hgtv.star.phone.phoneNav
import com.hgtv.star.shared.nav.Route
import com.hgtv.star.phone.ui.PhonePosterFeed
import com.hgtv.star.phone.ui.PhoneCardMenu
import com.hgtv.star.phone.ui.PhoneStyle
import com.hgtv.star.phone.ui.PhoneTabSwipeHost
import com.hgtv.star.phone.ui.PhoneTopBar
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.phoneSize
import com.hgtv.star.phone.ui.roundedBackground
import com.hgtv.star.phone.ui.tabBarInset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 *   并读取对应面板（首次请求接口，之后直接使用分类缓存）；
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
        val feeds: Map<String, PhonePosterFeed.Snapshot> = emptyMap(),
    )

    private val topBar = PhoneTopBar(context)
    /** 子分类筛选：作为列表头部随海报一起滚动，不自动收起。 */
    private val filterBox = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(0, 0, 0, 0)
    }
    private val feed = PhonePosterFeed(context)
    private val swipeHost = PhoneTabSwipeHost(context)
    private var scope = newScope()
    private var panelJob: Job? = null
    private var panelLoadingKey = ""
    private var panelGeneration = 0
    private val prefetchJobs = linkedMapOf<String, Job>()

    private var selection = initialState?.selection ?: CategorySelection()
    private var genreIndex = initialState?.genreIndex ?: 0
    private var panel: CategoryPanel? =
        initialState?.panel ?: CategoryPanelCache.load(selection.genre)
    private val feedCache = linkedMapOf<String, PhonePosterFeed.Snapshot>().apply {
        putAll(initialState?.feeds.orEmpty())
    }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun ensureScope() {
        if (scope.coroutineContext[Job]?.isActive != true) scope = newScope()
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(PhoneStyle.background)

        // 顶栏固定；筛选行交给网格当第一项，滚动时和列表一起自然移出屏幕。
        feed.setHeader(filterBox)
        swipeHost.setContent(feed)
        swipeHost.canSwipe = { direction ->
            genreIndex + direction in CategoryGenres.options.indices
        }
        swipeHost.onSwipe = { direction ->
            selectGenre((genreIndex + direction).coerceIn(CategoryGenres.options.indices))
        }
        swipeHost.onFocusDirection = { direction ->
            topBar.applyTabSelection(
                (genreIndex + direction).coerceIn(CategoryGenres.options.indices),
            )
        }
        swipeHost.previewProvider = ::previewFor
        swipeHost.previewReleaser = { (it.tag as? PhonePosterFeed)?.release() }
        swipeHost.refreshSwipeSetting()
        addView(topBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(swipeHost, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // 列数跟着设置：竖屏 1–3，横屏映射为 4–6；书库未就绪时先用出厂值。
        feed.applyColumnsFromSettings()
        applySystemBottomInset(0)
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
            feedCache[selection.key()] = restored
            renderFilters()
            feed.restore(restored,loaderFor(selection))
            prefetchNeighbors()
        } else {
            // 面板已落盘时先画出来，不再等列表接口回来才显示子分类。
            renderFilters()
            showCachedOrLoad()
        }
    }

    internal fun applySystemBottomInset(@Suppress("UNUSED_PARAMETER") bottomInsetPx: Int) {
        feed.bottomInset = context.tabBarInset() + context.dp(8)
    }

    fun snapshotState(): State = State(
        selection = selection,
        genreIndex = genreIndex,
        panel = panel,
        feed = feed.snapshot(),
        feeds = feedCache.toMap(),
    )

    // ---------------------------------------------------------------- 体裁

    private fun selectGenre(index: Int) {
        if (index == genreIndex) return
        rememberCurrentFeed()
        genreIndex = index
        topBar.applyTabSelection(index)
        selection = selection.newGenre(CategoryGenres.options[index].id)
        panel = CategoryPanelCache.load(selection.genre)
        renderFilters()
        showSelectedFeed()
        prefetchNeighbors()
    }

    /**
     * 取筛选面板。
     *
     * 面板与内容是同一次请求回来的（`need_selector_panel=true`），这里只为拿面板，
     * 顺手把第一页内容交给网格——不多发一次请求。
     */
    private fun loadPanel(showLoading: Boolean = true) {
        ensureScope()
        val repo = PhoneServices.repository(context)
        val snapshot = selection
        val key = snapshot.key()
        if (panelJob?.isActive == true && panelLoadingKey == key) return
        panelJob?.cancel()
        val generation = ++panelGeneration
        panelLoadingKey = key
        val needPanel = panel == null
        if(showLoading) feed.showLoading()
        panelJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching { repo.categoryFeed(snapshot, 1, needPanel) }.getOrNull()
                } ?: return@launch
                if (snapshot != selection) return@launch
                result.panel?.let { panel = it }
                renderFilters()
                feed.loadFirstPage(visible(result.page),loaderFor(snapshot))
                rememberCurrentFeed()
                prefetchNeighbors()
            } finally {
                if (generation == panelGeneration) {
                    panelJob = null
                    panelLoadingKey = ""
                }
            }
        }
    }

    private fun showSelectedFeed() {
        val cached = feedCache[selection.key()]
        if (cached != null) {
            feed.restore(cached,loaderFor(selection))
            prefetchNeighbors()
            return
        }
        showCachedOrLoad()
    }

    private fun showCachedOrLoad() {
        val cached=CatalogPageCache.load(selection)
        if(cached!=null) {
            val snapshot=snapshotOf(visible(cached))
            feedCache[selection.key()]=snapshot
            feed.restore(snapshot,loaderFor(selection))
            if(panel==null) loadPanel(showLoading=false)
            return
        }
        loadPanel()
    }

    private fun snapshotOf(page: CatalogPage): PhonePosterFeed.Snapshot =
        PhonePosterFeed.Snapshot(
            items=page.items,
            page=CatalogPaging.nextPageForRestored(page.items.size,page.hasMore),
            ended=!page.hasMore,
            heatBy=page.ranking?.positions?.mapValues { it.value.heat }.orEmpty(),
            emptyVisible=page.items.isEmpty(),
            emptyText=if(page.items.isEmpty()) "暂无内容" else "",
            loaded=true,
        )

    private fun loaderFor(
        snapshot: CategorySelection,
    ): suspend (Int) -> CatalogPage? {
        val repo=PhoneServices.repository(context)
        return { page -> visible(repo.categoryFeed(snapshot,page).page) }
    }

    private fun previewFor(direction: Int): View? {
        val index = genreIndex + direction
        if (index !in CategoryGenres.options.indices) return null
        val previewSelection = selection.newGenre(CategoryGenres.options[index].id)
        val previewFilters = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(0, 0, 0, 0)
        }
        val preview = PhonePosterFeed(context).apply {
            applyColumnsFromSettings()
            gridTopInset = 0
            bottomInset = context.tabBarInset() + context.dp(8)
            setHeader(previewFilters)
        }
        val root = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(PhoneStyle.background)
            tag = preview
            addView(preview, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val loader = loaderFor(previewSelection)
        val cachedFeed = feedCache[previewSelection.key()]
        val previewPanel = CategoryPanelCache.load(previewSelection.genre)
        if (previewPanel != null) {
            renderFilterRows(previewFilters, previewSelection, previewPanel, interactive = false)
            if (cachedFeed?.loaded == true) {
                preview.restore(cachedFeed, loader)
            } else {
                loadPreviewContent(preview, previewSelection, loader)
            }
            return root
        }
        previewFilters.visibility = View.GONE
        preview.showLoading()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    PhoneServices.repository(context).categoryFeed(previewSelection, 1, true)
                }.getOrNull()
            } ?: return@launch
            val visiblePage = visible(result.page)
            if (feedCache[previewSelection.key()]?.loaded != true) {
                feedCache[previewSelection.key()] = snapshotOf(visiblePage)
            }
            if (root.isAttachedToWindow) {
                renderFilterRows(previewFilters, previewSelection, result.panel, interactive = false)
                preview.refreshHeader()
                preview.loadFirstPage(visiblePage, loader)
            }
        }
        return root
    }

    private fun loadPreviewContent(
        preview: PhonePosterFeed,
        previewSelection: CategorySelection,
        loader: suspend (Int) -> CatalogPage?,
    ) {
        val cachedPage = CatalogPageCache.load(previewSelection)
        if (cachedPage != null) {
            preview.restore(snapshotOf(visible(cachedPage)), loader)
        } else {
            preview.load { page, _ -> loader(page) }
        }
    }

    private fun prefetchNeighbors() {
        ensureScope()
        listOf(genreIndex - 1, genreIndex + 1).forEach { index ->
            if (index !in CategoryGenres.options.indices) return@forEach
            val target = selection.newGenre(CategoryGenres.options[index].id)
            val key = target.key()
            if (feedCache[key]?.loaded == true && CategoryPanelCache.load(target.genre) != null) {
                return@forEach
            }
            if (prefetchJobs[key]?.isActive == true) return@forEach
            prefetchJobs[key] = scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            PhoneServices.repository(context).categoryFeed(target, 1, true)
                        }.getOrNull()
                    } ?: return@launch
                    if (feedCache[key]?.loaded != true) {
                        feedCache[key] = snapshotOf(visible(result.page))
                    }
                    while (feedCache.size > 16) {
                        feedCache.remove(feedCache.keys.firstOrNull() ?: break)
                    }
                } finally {
                    prefetchJobs.remove(key)
                }
            }
        }
    }

    private fun rememberCurrentFeed() {
        val snapshot = feed.snapshot()
        if (!snapshot.loaded) return
        feedCache.remove(selection.key())
        feedCache[selection.key()] = snapshot
        while (feedCache.size > 16) {
            feedCache.remove(feedCache.keys.firstOrNull() ?: break)
        }
    }

    /** 长按菜单隐藏过的剧不出现在分类里（与 TV 的 `SeriesGrid` 同一条规则）。 */
    private fun visible(page: com.hgtv.star.core.CatalogPage): com.hgtv.star.core.CatalogPage {
        val hidden = runCatching { PhoneServices.library(context).hidden() }.getOrDefault(emptySet())
        return com.hgtv.star.core.CatalogFilter.visible(page, hidden)
    }

    // ---------------------------------------------------------------- 筛选行

    private fun renderFilters() {
        renderFilterRows(filterBox, selection, panel, interactive = true)
        feed.refreshHeader()
    }

    private fun renderFilterRows(
        container: LinearLayout,
        selected: CategorySelection,
        source: CategoryPanel?,
        interactive: Boolean,
    ) {
        container.removeAllViews()
        val rows = source?.visibleRows().orEmpty()
        if (rows.isEmpty()) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        rows.forEach { row ->
            container.addView(buildRow(row, selected, interactive))
        }
    }

    /**
     * 一行筛选：左边固定宽度的标签（「主题」「设定」…），右边可横滑的 chips。
     *
     * 与 TV 的排版同构：标签只说明这一栏管什么、不参与选择；chips 的第一个是「全部」。
     */
    private fun buildRow(
        row: CategoryFilterRow,
        selected: CategorySelection,
        interactive: Boolean,
    ): View {
        val line = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(10), 0, context.dp(2), 0)
            minimumHeight = context.dp(18)
        }
        val label = row.title.removePrefix("全部").ifBlank { row.title }
        line.addView(TextView(context).apply {
            text = label
            setTextColor(PhoneStyle.textDim)
            // 子分类字号按 Phone 单独调整到 14sp。
            phoneSize(14)
            maxLines = 1
        }, LayoutParams(context.dp(30), LayoutParams.WRAP_CONTENT))

        val chips = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val selectedId = selected.valueOf(row.type)
        // 「全部」排第一：语义是「不传这个维度」，接口返回的 options 里通常没有它。
        val options = listOf<Pair<String?, String>>(null to "全部") +
            row.options.map { it.id to it.label }
        options.forEach { (id, text) ->
            val on = id == selectedId
            chips.addView(TextView(context).apply {
                this.text = text
                // 子分类选项字号与原标签保持同一档。
                phoneSize(14)
                gravity = Gravity.CENTER
                setTextColor(if (on) PhoneStyle.text else PhoneStyle.textDim)
                background = if (on) {
                    roundedBackground(PhoneStyle.surfaceHigh, PhoneStyle.RADIUS_PILL)
                } else null
                setPadding(context.dp(6), 0, context.dp(6), 0)
                if (interactive) {
                    setOnClickListener {
                        rememberCurrentFeed()
                        selection = selection.with(row.type, id)
                        renderFilters()
                        showSelectedFeed()
                    }
                }
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                rightMargin = context.dp(1)
            })
        }
        line.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }, LayoutParams(0, context.dp(18), 1f))
        return line
    }

    fun scrollToTop() = feed.scrollToTop()

    /** 从设置页返回时重新对齐「列表每行」。 */
    fun onShown() {
        feed.applyColumnsFromSettings()
        swipeHost.refreshSwipeSetting()
        // 切到别的 tab 时页面会 detach，正在拉的小分类请求会被取消。
        // 回到分类页时如果面板还没落地，必须补一次，否则小分类会永久消失。
        if (panel == null) {
            loadPanel(showLoading = feed.snapshot().items.isEmpty())
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ensureScope()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        release()
    }

    /** 释放页面任务；预加载但从未 attach 的分类页也由宿主在销毁时调用。 */
    fun release() {
        swipeHost.cancelSwipe()
        panelGeneration++
        panelJob = null
        panelLoadingKey = ""
        prefetchJobs.values.forEach { it.cancel() }
        prefetchJobs.clear()
        scope.cancel()
    }
}
