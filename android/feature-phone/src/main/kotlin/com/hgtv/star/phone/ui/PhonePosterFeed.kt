// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hgtv.star.core.CATALOG_PREFETCH_REMAINING
import com.hgtv.star.core.CatalogPage
import com.hgtv.star.core.PosterCardContent
import com.hgtv.star.core.Series
import com.hgtv.star.phone.PhoneServices
import com.hgtv.star.shared.ui.LoadingDotsView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 海报网格（竖屏基准 1–3 列，横屏映射为 4–6 列），分类 / 榜单 / 搜索三个页面共用。
 *
 * 分页、去重、加载更多都在这里，页面只提供「第 N 页取什么」：
 *
 * ```kotlin
 * feed.load { page -> repository.categoryFeed(selection, page) }
 * ```
 *
 * 名次（[ranked]）只影响卡片左上角那枚角标：榜单页要画，分类页与搜索结果不画——
 * 榜单页四个榜都显示服务端名次；这里保留开关供非榜单列表复用。
 */
class PhonePosterFeed(context: Context) : FrameLayout(context) {

    /** 跨 Activity 重建复用的纯数据快照，不持有 View / Context。 */
    data class Snapshot(
        val items: List<Series>,
        val page: Int,
        val ended: Boolean,
        val heatBy: Map<String, String>,
        val emptyVisible: Boolean,
        val emptyText: String,
        val loaded: Boolean,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val items = ArrayList<Series>()
    private val adapter = PosterAdapter()
    private val grid = RecyclerView(context)

    private var page = 1
    private var loading = false
    private var ended = false
    /** 列表自带的一整行头部（筛选 / 账号与栏目）；与海报共用同一个 RecyclerView，随列表自然滚走。 */
    private var header: View? = null
    private var emptyVisible = false
    private var loadingVisible = false
    private var loader: (suspend (Int) -> CatalogPage?)? = null
    private var job: Job? = null
    private var hasLoadedOnce = false

    /**
     * 服务端给的热度原串，按剧 id 存（`id → "9014万"`）。
     *
     * 榜单接口把热度放在 `CatalogPage.ranking.positions` 里，而列表只留 `items`；
     * 不在这儿接住，卡片就拿不到那串——它比 `Series.heat` 的解析值更准
     * （服务端自己排的，带单位、已经取整）。翻页时按页并入，不整体覆盖。
     */
    private var heatBy: Map<String, String> = emptyMap()

    /** 点卡片。 */
    var onOpen: ((Series) -> Unit)? = null

    /** 批量选择模式：点击卡片改为勾选，不再打开详情。 */
    private var batchSelection = false
    private val selectedIds = LinkedHashSet<String>()
    var onSelectionChanged: ((Int) -> Unit)? = null

    /**
     * 长按卡片。
     *
     * 手机上「删除一条收藏 / 一条历史 / 一条稍后看」没有电视那样的菜单键，
     * 长按是这一平台唯一通用的入口（与系统相册、下载列表一致）。
     *
     * **赋值时要把已经绑好的卡片一起更新**：页面可能在 `load()` 之后才挂这个回调
     * （缓存先铺、回调后设），那批卡片的 `onLongPress` 会一直是 null——长按没反应，
     * 抬手还被当成点击跳去详情页。
     */
    var onLongPress: ((Series) -> Unit)? = null
        set(value) {
            field = value
            var applied = 0
            for (i in 0 until grid.childCount) {
                val cell = grid.getChildAt(i) as? PhonePosterCard
                if (cell != null) {
                    cell.onLongPress = value
                    applied++
                }
            }
            android.util.Log.i(
                "HGTV-CARD",
                "set onLongPress handler=${value != null} applied=$applied children=${grid.childCount}",
            )
        }

    /**
     * 每行列数。
     *
     * 默认取设置里的「列表每行」（竖屏 1–3、横屏 4–6，出厂 2 / 5）。分类 / 榜单 / 收藏这些栅格页
     * 全部跟着它走——同一个应用里两种列数会让人以为「这两页不是一套」。
     */
    var columns = 2
        set(value) {
            val next = value.coerceIn(1, 6)
            if (field == next) return
            field = next
            grid.layoutManager = gridManager(next)
            // `PosterCell` 的宽度、海报高度和字号是在创建时按列宽算死的。
            // 只 notify 会复用旧 ViewHolder，屏幕上已铺出的卡片仍是上一档大小；
            // 先解绑，让屏幕上的旧 ViewHolder 回收；再清池并重新挂 adapter，
            // 避免它们带着旧列宽被复用。
            grid.adapter = null
            grid.recycledViewPool.clear()
            grid.adapter = adapter
            adapter.notifyDataSetChanged()
        }

    /**
     * 覆盖卡片第二行（副标题）。
     *
     * 「我的 → 收藏」用它显示 `library.favoriteLabel(id)`（「新增 3 集 · 更新至 78 集」）：
     * 那一栏回答的是「追的剧有没有更新」，集数与热度在这里没有意义。
     * 返回 null 时回落到默认那一行（集数 · 热度 · 题材）。
     */
    var subtitleOf: ((Series) -> String?)? = null
        set(value) { field = value; adapter.notifyDataSetChanged() }

    /** 卡片海报底部的已播进度（0..100）；没有进度时返回 null。 */
    var progressOf: ((Series) -> Int?)? = null
        set(value) { field = value; adapter.notifyDataSetChanged() }

    /** 是否画名次角标。 */
    var ranked = false
        set(value) { field = value; adapter.notifyDataSetChanged() }

    /**
     * 回到顶部（双击底部当前导航按钮）。
     *
     * **直接跳**，不走平滑滚动：`smoothScrollToPosition(0)` 会让列表从当前行一路滚回第一行，
     * 翻过几百条时要滑好几秒——用户点了按钮却看着画面一直往上跑，读起来就是「按钮没反应」。
     * 栅格是窗口化列表，逐行滚过还会把沿途每一行都物化再回收一遍。
     * 直接 `scrollToPositionWithOffset(0, 0)` 只做一次布局，第一行立刻回到原位。
     */
    fun scrollToTop() {
        if (adapter.itemCount == 0) return
        grid.stopScroll()
        (grid.layoutManager as? GridLayoutManager)?.scrollToPositionWithOffset(0, 0)
            ?: grid.scrollToPosition(0)
        grid.post { onScrollSettled?.invoke(0) }
    }

    /** 空态文案。每个 ViewHolder 单独建 View，避免刷新时复用仍挂在列表上的同一个实例。 */
    private var emptyText = "暂无内容"

    private fun newEmptyView() = TextView(context).apply {
        text = emptyText
        setTextColor(PhoneStyle.textFaint)
        phoneSize(13)
        gravity = Gravity.CENTER
        setPadding(0, context.dp(60), 0, context.dp(60))
    }

    private fun newLoadingView() = LoadingDotsView(context)

    /**
     * 列表滚动回调（当前 `scrollY` 与本次 `dy`，单位像素）。
     *
     * 页面需要列表位置时使用；头部已经是列表自身的一项，不再靠这里收起 / 展开。
     */
    var onScrollY: ((y: Int, dy: Int) -> Unit)? = null

    /** 列表被程序性置顶或重新挂载：直接同步页头，不受收展动画的状态锁影响。 */
    var onScrollSettled: ((Int) -> Unit)? = null

    /** 当前滚动量（像素）。子项还没铺出来时一律按 0 算（见 `onScrolled` 那条注释）。 */
    fun scrollY(): Int = if (grid.childCount > 0) grid.computeVerticalScrollOffset() else 0

    /** 列表底部留白（页面自己决定要不要避开底部导航）。 */
    var bottomInset = 0
        set(value) {
            field = value
            grid.setPadding(grid.paddingLeft, grid.paddingTop, grid.paddingRight, value)
        }

    /**
     * 网格顶部的呼吸位（像素）；`-1` = 用默认的 `dp(4)`。
     *
     * 分类页把筛选行紧贴在主分类下面，需要把这一段收掉（见 `PhoneCategoryPage`）；
     * 其它页面没有紧贴的内容，保持默认。
     */
    var gridTopInset = -1
        set(value) {
            if (field == value) return
            field = value
            if (value >= 0) grid.setPadding(0, value, 0, grid.paddingBottom)
        }

    init {
        setBackgroundColor(PhoneStyle.background)
        grid.layoutManager = gridManager(columns)
        grid.adapter = adapter
        grid.clipToPadding = false
        // 左右留白交给海报项的 ItemDecoration：列表头部要铺满整宽，不能跟海报一起内缩。
        grid.setPadding(0, context.dp(4), 0, context.dp(8))
        grid.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: android.graphics.Rect,
                view: View,
                parent: RecyclerView,
                state: RecyclerView.State,
            ) {
                val position = parent.getChildAdapterPosition(view)
                if (position < 0 || adapter.isFullSpan(position)) return
                val column = (position - adapter.posterStart) % columns
                val screenWidth = resources.displayMetrics.widthPixels
                val gap = dp(PhoneDimen.POSTER_GAP)
                val inset = dp(PhoneDimen.PAGE_INSET.toInt())
                val cardWidth = (screenWidth - inset * 2 - gap * (columns - 1)) / columns
                val cellWidth = screenWidth / columns
                // 卡片宽度固定，列宽由 span 均分；把两端留白和列间距折算成每格的
                // 左偏移，才能让每张卡都落在「页边距 + 等距列缝」的真实位置上。
                outRect.left = (inset + column * (cardWidth + gap - cellWidth)).coerceAtLeast(0)
                outRect.right = 0
            }
        })
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val last = (rv.layoutManager as GridLayoutManager).findLastVisibleItemPosition()
                val lastPoster=(last-adapter.posterStart).coerceAtMost(items.lastIndex)
                // 与 TV 同一阈值：当前页只剩 50 条时提前取下一页。
                if(items.isNotEmpty() && items.size-(lastPoster+1)<=CATALOG_PREFETCH_REMAINING) loadMore()
                // **只在真的铺出子项之后**才报位置：首帧布局里 RecyclerView 还没量过
                // 子项，`computeVerticalScrollOffset` 可能返回一个非 0 的估值。
                if (rv.childCount > 0) {
                    onScrollY?.invoke(rv.computeVerticalScrollOffset(), dy)
                }
            }
        })
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun gridManager(spanCount: Int) = GridLayoutManager(context, spanCount).apply {
        spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (adapter.isFullSpan(position)) spanCount else 1
        }
    }

    /**
     * 本机状态变了（收藏 / 稍后看），只重绑可见项。
     *
     * 比调用方自己 `render()` 整页重建轻得多：不动头部、不清空列表、**滚动位置不变**。
     * 长按菜单里点「收藏 / 稍后看」原来会把整页重铺一次，用户看到的是屏幕闪一下、
     * 位置还可能被顶走。
     */
    fun refreshItems() {
        adapter.notifyDataSetChanged()
    }

    fun setBatchSelection(enabled: Boolean) {
        if (batchSelection == enabled) return
        batchSelection = enabled
        if (!enabled) selectedIds.clear()
        adapter.notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedIds.size)
    }

    fun selectedCount(): Int = selectedIds.size

    fun selectedItems(): List<Series> = items.filter { it.id in selectedIds }

    fun allSelected(): Boolean = items.isNotEmpty() && items.all { it.id in selectedIds }

    fun selectAll() {
        if (!batchSelection || items.isEmpty()) return
        selectedIds.clear()
        selectedIds += items.map { it.id }
        adapter.notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedIds.size)
    }

    fun clearSelection() {
        if (selectedIds.isEmpty()) return
        selectedIds.clear()
        adapter.notifyDataSetChanged()
        onSelectionChanged?.invoke(0)
    }

    private fun toggleSelection(id: String) {
        if (!batchSelection) return
        if (!selectedIds.add(id)) selectedIds.remove(id)
        adapter.notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedIds.size)
    }

    /**
     * 从当前列表里就地抽掉一项（「不在热播榜中显示」）。
     *
     * 原来这一步走的是整页重取：隐藏完把列表清空、回到第 1 页重新请求一遍，
     * 屏幕会闪一下、滚动位置被顶回顶部。而被隐藏的那部**已经不在结果里**了
     * （`CatalogFilter.visible` 会过滤 `library.hidden()`），
     * 唯一要做的只是把这一格从内存列表里摘掉。
     *
     * 摘掉之后要重绑后面的项：名次角标是按位置算的（`第 N 名`），
     * 不重绑就会留着旧名次，看起来像「抽掉一张后名次错了」。
     */
    fun removeItem(seriesId: String) {
        val index = items.indexOfFirst { it.id == seriesId }
        if (index < 0) return
        items.removeAt(index)
        selectedIds.remove(seriesId)
        adapter.notifyItemRemoved(adapter.posterStart + index)
        val rest = items.size - index
        if (rest > 0) adapter.notifyItemRangeChanged(adapter.posterStart + index, rest)
        if (items.isEmpty()) showEmpty("暂无内容")
    }

    /** 把一整行头部放进网格第一项：它会和海报一起滚动，不再由页面单独收起 / 展开。 */
    fun setHeader(view: View) {        (view.parent as? ViewGroup)?.removeView(view)
        header = view
        adapter.notifyDataSetChanged()
    }

    /** 头部内部内容变了（换栏目、筛选行重建）：让 RecyclerView 重新量这一行。 */
    fun refreshHeader() {
        // 头部 ViewHolder 始终复用同一个 View。对它发 item change 通知时，
        // RecyclerView 的变更动画可能尝试把仍挂着的 View 再 attach 一次；
        // 分类页切换体裁恰好还会紧跟一次整页刷新，因此这里只请求重新布局。
        header?.requestLayout()
    }

    /** 每次挂回窗口都按当前设置重排列数，并纠正一次页头状态。 */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 设置页可能刚改过「列表每行」：切回这一页时按新值重排列数，
        // 而不是留着建页时那一份（否则用户会以为那个设置没用）。
        applyColumnsFromSettings()
        // 挂回来时按真实位置通知一次；头部现在是列表第一项，不需要恢复任何收起态。
        post { onScrollSettled?.invoke(scrollY()) }
    }

    /**
     * 按设置里的「列表每行」刷新列数。
     *
     * 页面在 `onAttachedToWindow` / `onShown` 里调一次即可：设置页改完回来时
     * 网格会整片重排（`columns` 的 setter 已经 `notifyDataSetChanged`）。
     */
    fun applyColumnsFromSettings() {
        val value = runCatching {
            com.hgtv.star.phone.PhoneServices.library(context)
                .takeIf { it.isLoaded }?.gridColumns
        }.getOrNull() ?: return
        val landscape = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        columns = value.coerceIn(1, 3) + if (landscape) 3 else 0
    }

    /** 换数据源：清空后从第 1 页重新取。 */
    fun load(loader: suspend (Int) -> CatalogPage?) {
        this.loader = loader
        reset()
    }

    /** 请求尚未拿到第一页时显示统一的加载动画。 */
    fun showLoading() {
        job?.cancel()
        items.clear()
        selectedIds.clear()
        heatBy = emptyMap()
        page = 1
        loading = false
        ended = false
        loadingVisible = true
        emptyVisible = false
        hasLoadedOnce = false
        adapter.notifyDataSetChanged()
    }

    /**
     * 保留当前缓存内容，后台刷新第 1 页；刷新成功后整体替换，失败则继续显示缓存。
     *
     * 与 [load] 的区别是首帧不闪空页：用户进榜单时先看到上次内容，
     * 新数据落地后再无动画地换掉。
     */
    fun refresh(loader: suspend (Int) -> CatalogPage?) {
        this.loader = loader
        job?.cancel()
        loading = true
        if (items.isEmpty()) {
            loadingVisible = true
            emptyVisible = false
            adapter.notifyDataSetChanged()
        }
        job = scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { loader(1) }.getOrNull() }
            loading = false
            loadingVisible = false
            if (result == null) {
                if (items.isEmpty()) showEmpty("加载失败，下拉重试")
                return@launch
            }
            hasLoadedOnce = true
            items.clear()
            items += result.items
            selectedIds.clear()
            heatBy = result.ranking?.positions?.mapValues { it.value.heat }.orEmpty()
            page = 2
            ended = !result.hasMore || result.items.isEmpty()
            emptyVisible = result.items.isEmpty()
            emptyText = "暂无内容"
            adapter.notifyDataSetChanged()
        }
    }

    fun snapshot(): Snapshot = Snapshot(
        items = items.toList(),
        page = page,
        ended = ended,
        heatBy = heatBy,
        emptyVisible = emptyVisible,
        emptyText = emptyText,
        loaded = hasLoadedOnce,
    )

    /**
     * 用重建前的数据直接铺回网格。
     *
     * [loader] 只用于之后继续翻页；恢复本身不会发请求。
     */
    fun restore(
        snapshot: Snapshot,
        loader: (suspend (Int) -> CatalogPage?)? = null,
    ) {
        job?.cancel()
        this.loader = loader
        items.clear()
        items += snapshot.items
        selectedIds.clear()
        page = snapshot.page
        loading = false
        loadingVisible = false
        ended = snapshot.ended
        heatBy = snapshot.heatBy
        emptyVisible = snapshot.emptyVisible
        emptyText = snapshot.emptyText
        hasLoadedOnce = snapshot.loaded
        adapter.notifyDataSetChanged()
    }

    fun reset() {
        job?.cancel()
        items.clear()
        selectedIds.clear()
        heatBy = emptyMap()
        emptyVisible = false
        adapter.notifyDataSetChanged()
        page = 1
        loading = false
        ended = false
        hasLoadedOnce = false
        loadMore()
    }

    private fun loadMore() {
        val loader = loader ?: return
        if (loading || ended) return
        loading = true
        if (items.isEmpty()) {
            loadingVisible = true
            emptyVisible = false
            adapter.notifyDataSetChanged()
        }
        job = scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { loader(page) }.getOrNull() }
            loading = false
            loadingVisible = false
            if (result == null) {
                ended = items.isNotEmpty()
                if (items.isEmpty()) showEmpty("加载失败，下拉重试")
                return@launch
            }
            hasLoadedOnce = true
            val fresh = result.items.filter { s -> items.none { it.id == s.id } }
            // 榜单这一页的名次与热度按 id 并入：翻页只带自己那一页，整体覆盖会把前几页的丢掉。
            result.ranking?.positions?.let { positions ->
                if (positions.isNotEmpty()) {
                    heatBy = heatBy + positions.mapValues { it.value.heat }
                }
            }
            val start = items.size
            items += fresh
            page += 1
            if (!result.hasMore || fresh.isEmpty()) ended = true
            if (fresh.isNotEmpty() && emptyVisible) {
                emptyVisible = false
                adapter.notifyDataSetChanged()
            }
            if (start == 0) adapter.notifyDataSetChanged()
            else if (fresh.isNotEmpty()) {
                adapter.notifyItemRangeInserted(adapter.posterStart + start, fresh.size)
            }
            if (items.isEmpty()) showEmpty("暂无内容")
        }
    }

    private fun showEmpty(text: String) {
        loadingVisible = false
        emptyText = text
        if (emptyVisible) {
            adapter.notifyItemChanged(adapter.headerCount)
        } else {
            emptyVisible = true
            adapter.notifyDataSetChanged()
        }
    }

    /** 外部直接塞一批数据（搜索结果、收藏 / 历史）。 */
    fun submit(rows: List<Series>, more: Boolean = false, loading: Boolean = false) {
        job?.cancel()
        items.clear()
        // 外部塞进来的没有榜单上下文：热度只能靠 `Series.heat`（搜索结果带），
        // 其余来源为 0，卡片上那一枚自然收起。
        heatBy = emptyMap()
        items += rows
        selectedIds.retainAll(rows.map { it.id }.toSet())
        ended = !more
        page = 1
        loadingVisible = loading && rows.isEmpty()
        hasLoadedOnce = !loadingVisible
        emptyVisible = rows.isEmpty() && !loadingVisible
        if (emptyVisible) emptyText = "暂无内容"
        adapter.notifyDataSetChanged()
    }

    fun release() {
        scope.cancel()
    }

    private inner class PosterAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        val headerCount: Int
            get() = if (header == null) 0 else 1
        private val statusCount: Int
            get() = if (loadingVisible || emptyVisible) 1 else 0
        val posterStart: Int
            get() = headerCount + statusCount

        fun isFullSpan(position: Int): Boolean = position < posterStart

        override fun getItemViewType(position: Int): Int {
            if (position < headerCount) return TYPE_HEADER
            if (position < posterStart) return if (loadingVisible) TYPE_LOADING else TYPE_EMPTY
            return TYPE_POSTER
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == TYPE_HEADER) {
                val view = header ?: error("header missing")
                view.layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                return FixedHolder(view)
            }
            if (viewType == TYPE_EMPTY) {
                val view = newEmptyView()
                view.layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                return FixedHolder(view)
            }
            if (viewType == TYPE_LOADING) {
                val view = FrameLayout(context).apply {
                    addView(newLoadingView(), FrameLayout.LayoutParams(
                        context.dp(64), context.dp(18), Gravity.CENTER
                    ))
                }
                view.layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, context.dp(120)
                )
                return FixedHolder(view)
            }
            val gap = dp(PhoneDimen.POSTER_GAP)
            val width = (resources.displayMetrics.widthPixels
                - dp(PhoneDimen.PAGE_INSET.toInt()) * 2
                - gap * (columns - 1)) / columns
            // 卡宽由列数算出来（列表自己定），**高度交给 wrap_content**：
            // 电视卡片是「3:4 海报井 + 剧名 + 第二行」，总高度不是宽度的固定倍数，
            // 写死比例（旧版 3:5）会把剧名那一行压进海报里。
            val card = PosterCell(context, width)
            card.layoutParams = RecyclerView.LayoutParams(
                width, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = gap
            }
            return PosterHolder(card)
        }

        override fun getItemCount(): Int = posterStart + items.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (position < posterStart) {
                // 空态和头部共用前置项；只有空态项才需要写文案。
                // 这里不能用 `position == headerCount`：没有空态时它会误伤第一张海报。
                if (position == headerCount && !loadingVisible) {
                    (holder.itemView as? TextView)?.text = emptyText
                }
                return
            }
            val itemPosition = position - posterStart
            if (itemPosition < 0 || itemPosition >= items.size) return
            (holder.itemView as PosterCell).bind(items[itemPosition], itemPosition)
        }
    }

    private class FixedHolder(view: View) : RecyclerView.ViewHolder(view)
    private class PosterHolder(view: View) : RecyclerView.ViewHolder(view)

    private companion object {
        const val TYPE_HEADER = 1
        const val TYPE_EMPTY = 2
        const val TYPE_POSTER = 3
        const val TYPE_LOADING = 4
    }

    /**
     * 网格卡只补页面上下文：统一外观在 [PhonePosterCard]，横排相关剧集用的是同一个类。
     */
    private inner class PosterCell(
        context: Context,
        cardWidth: Int,
    ) : PhonePosterCard(
        context,
        cardWidth,
        PhoneServices.artwork(context),
        onOpen = { item ->
            if (batchSelection) toggleSelection(item.id)
            else this@PhonePosterFeed.onOpen?.invoke(item)
        },
    ) {
        fun bind(item: Series, position: Int) {
            onLongPress = if (batchSelection) null else this@PhonePosterFeed.onLongPress
            // 现取入口：长按那一刻再问宿主，避免「页面晚设回调」导致这批卡片永远没有 handler。
            longPressResolver = { if (batchSelection) null else this@PhonePosterFeed.onLongPress }
            android.util.Log.i(
                "HGTV-CARD",
                "bind ${item.id} handler=${onLongPress != null}",
            )
            super.bind(item, PosterCardContent.of(
                series = item,
                statusText = subtitleOf?.invoke(item).orEmpty(),
                rankText = if (ranked) "第 ${position + 1} 名" else "",
                rankTop = position < 3,
                heatServerText = heatBy[item.id],
            ))
            setProgress(progressOf?.invoke(item))
            setBatchSelection(batchSelection, item.id in selectedIds)
        }
    }
}
