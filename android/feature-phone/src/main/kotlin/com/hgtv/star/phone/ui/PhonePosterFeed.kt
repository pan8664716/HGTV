// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hgtv.star.core.CatalogPage
import com.hgtv.star.core.Series
import com.hgtv.star.phone.PhoneServices
import com.hgtv.star.phone.ui.PhoneSoft.applySoft
import com.hgtv.star.shared.ui.SoftStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 海报网格（默认两列，可在设置里选 1 / 2 / 3 列），分类 / 榜单 / 搜索三个页面共用。
 *
 * 分页、去重、加载更多都在这里，页面只提供「第 N 页取什么」：
 *
 * ```kotlin
 * feed.load { page -> repository.categoryFeed(selection, page) }
 * ```
 *
 * 名次（[ranked]）只影响卡片左下角那枚角标：榜单页要画，分类页与搜索结果不画——
 * 与 TV 端 `RankBoard.ranked` 的判断一致（「上新」那一栏同样不画名次）。
 */
class PhonePosterFeed(context: Context) : FrameLayout(context) {

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
    private var loader: (suspend (Int) -> CatalogPage?)? = null
    private var job: Job? = null

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

    /**
     * 长按卡片。
     *
     * 手机上「删除一条收藏 / 一条历史 / 一条稍后看」没有电视那样的菜单键，
     * 长按是这一平台唯一通用的入口（与系统相册、下载列表一致）。
     */
    var onLongPress: ((Series) -> Unit)? = null

    /**
     * 每行列数。
     *
     * 默认取设置里的「列表每行」（1 / 2 / 3，出厂 2）。分类 / 榜单 / 收藏这些栅格页
     * 全部跟着它走——同一个应用里两种列数会让人以为「这两页不是一套」。
     */
    var columns = 2
        set(value) {
            val next = value.coerceIn(1, 3)
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

    /** 空态提示。 */
    private val empty = TextView(context).apply {
        setTextColor(PhoneStyle.textFaint)
        phoneSize(13)
        gravity = Gravity.CENTER
        setPadding(0, context.dp(60), 0, context.dp(60))
    }

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
                if (last >= adapter.itemCount - 5) loadMore()
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

    /** 把一整行头部放进网格第一项：它会和海报一起滚动，不再由页面单独收起 / 展开。 */
    fun setHeader(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
        header = view
        adapter.notifyDataSetChanged()
    }

    /** 头部内部内容变了（换栏目、筛选行重建）：让 RecyclerView 重新量这一行。 */
    fun refreshHeader() {
        if (header != null) adapter.notifyItemChanged(0)
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
        columns = value
    }

    /** 换数据源：清空后从第 1 页重新取。 */
    fun load(loader: suspend (Int) -> CatalogPage?) {
        this.loader = loader
        reset()
    }

    fun reset() {
        job?.cancel()
        items.clear()
        heatBy = emptyMap()
        emptyVisible = false
        adapter.notifyDataSetChanged()
        page = 1
        loading = false
        ended = false
        loadMore()
    }

    private fun loadMore() {
        val loader = loader ?: return
        if (loading || ended) return
        loading = true
        job = scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { loader(page) }.getOrNull() }
            loading = false
            if (result == null) {
                ended = items.isNotEmpty()
                if (items.isEmpty()) showEmpty("加载失败，下拉重试")
                return@launch
            }
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
        empty.text = text
        if (emptyVisible) {
            adapter.notifyItemChanged(adapter.headerCount)
        } else {
            emptyVisible = true
            adapter.notifyDataSetChanged()
        }
    }

    /** 外部直接塞一批数据（搜索结果、收藏 / 历史）。 */
    fun submit(rows: List<Series>, more: Boolean = false) {
        job?.cancel()
        items.clear()
        // 外部塞进来的没有榜单上下文：热度只能靠 `Series.heat`（搜索结果带），
        // 其余来源为 0，卡片上那一枚自然收起。
        heatBy = emptyMap()
        items += rows
        ended = !more
        page = 1
        emptyVisible = rows.isEmpty()
        if (emptyVisible) empty.text = "暂无内容"
        adapter.notifyDataSetChanged()
    }

    fun release() {
        scope.cancel()
    }

    private inner class PosterAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        val headerCount: Int
            get() = if (header == null) 0 else 1
        private val emptyCount: Int
            get() = if (emptyVisible) 1 else 0
        val posterStart: Int
            get() = headerCount + emptyCount

        fun isFullSpan(position: Int): Boolean = position < posterStart

        override fun getItemViewType(position: Int): Int {
            if (position < headerCount) return TYPE_HEADER
            if (position < posterStart) return TYPE_EMPTY
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
                empty.layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                return FixedHolder(empty)
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
    }

    /**
     * 一张海报卡：**逐项复刻电视端的 `PosterCard`**（`feature-tv` 的 `TvComponents.kt`）。
     *
     * ```
     * ┌─ 底板（surface，RAISED 柔光，小圆角，四周 6dp 柔光带）─────────┐
     * │ ┌─ 海报井（内缩 4dp，小圆角裁剪）──────────────────────────────┐ │
     * │ │                                              [第 3 季]      │ │
     * │ │                 封面 CENTER_CROP                            │ │
     * │ │ [第 3 名]                                    [共 99 集]    │ │
     * │ └──────────────────────────────────────────────────────────┘ │
     * │  剧名（18×scale，粗体，单行省略）                              │
     * │  类型标签（14×scale，靠左）              状态（14×scale，靠右）│
     * └──────────────────────────────────────────────────────────────┘
     * ```
     *
     * 与电视端的对应关系是**一对一**的，不是「照着抄个大概」：
     *
     * | 元素 | 电视 `PosterCard` | 这里 |
     * | --- | --- | --- |
     * | 底板 / 海报井 | 小圆角 / 内缩 | 手机端卡片与图片同为小圆角 |
     * | 海报比例 | `POSTER_ASPECT` 3:4 | 同值（原先手机是 3:5，偏高） |
     * | 角标字号 / 内边距 / 外边距 | 12sp / 6,3,8,3 / 5dp | 同值（走 `phoneSize`，按 50% 字阶） |
     * | 角标压暗层 | 朝贴边那一侧的渐变 | 同值（直角边缘） |
     * | 剧名 | 18sp 粗体、单行 END、内边距 9/8/9/0 | 同值 |
     * | 第二行 | 14sp、标签靠左占满、状态靠右、内边距 9/4/9/3 | 同值 |
     * | 无标签时 | 整行收起，不留空档 | 同值 |
     *
     * 唯一的差别是**字号单位**：电视用绝对 sp，手机整套版面按 50% 字阶，
     * 因此同样写 18、14、12，落到像素上是电视的一半。这是全站统一的比例，
     * 不是这张卡特例——否则卡片文字会比标题栏、播放页同一层级重一倍。
     *
     * `textScale` 与电视同一条算法：卡片越宽（1 列 / 2 列），字号按比例抬一点，
     * 否则宽卡片下面会吊着两行小字。基准取电视 6 列的卡片宽（94 设计单位）。
     */
    private inner class PosterCell(context: Context, private val cardWidth: Int) : LinearLayout(context) {

        /** 与电视 `MainActivity.cardTextScale` 同一套：卡宽 / 基准宽，夹在 0.85–1.45。 */
        private val scale = (cardWidth.toFloat() / context.dp(94f))
            .coerceIn(.85f, 1.45f)

        /** 卡片自己的柔光带：比按钮窄一档，封面才能铺得更满。 */
        private val cardBand = 4f
        private val posterInset = 2f

        private val poster = FrameLayout(context).apply {
            background = roundedBackground(
                PhoneStyle.surface,
                context.dp(PhoneStyle.RADIUS_CARD).toFloat(),
            )
            clipToOutline = true
            clipChildren = true
        }

        private val cover = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        /** 右下角：**总集数**（`共 99 集`），没有集数时整枚收起。 */
        private val badge = TextView(context)

        /** 右上角：第几季（`第 3 季`），所有页面都标——与电视同一口径。 */
        private val season = TextView(context)

        /** 左上角：榜单名次（`第 3 名`），只在榜单页出现。 */
        private val rank = TextView(context)

        /**
         * 左下角：**热度**（`🔥 9014万`）。
         *
         * 只在接口真的给了热度时出现（榜单页与搜索结果有，分类页 / 本机记录没有），
         * 没有就整枚收起。文案见 [com.hgtv.star.core.HeatLabel]。
         */
        private val heat = TextView(context)

        private val title = TextView(context).apply {
            phoneSize(18f * scale)
            setTextColor(PhoneStyle.textDim)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setSingleLine(true)
            setPadding(
                context.dp(9f * scale), context.dp(8f * scale), context.dp(9f * scale), 0
            )
        }

        private val tagLine = TextView(context).apply {
            phoneSize(14f * scale)
            setTextColor(PhoneStyle.textDim)
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setSingleLine(true)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }

        private val status = TextView(context).apply {
            phoneSize(14f * scale)
            setTextColor(PhoneStyle.textDim)
            includeFontPadding = false
            maxLines = 1
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        private lateinit var meta: LinearLayout

        init {
            orientation = VERTICAL
            clipChildren = false
            clipToPadding = false
            // 先放 0 padding，再交给 `applySoft` 补一圈柔光带（它只加一次，重复调用不会叠加）。
            setPadding(0, 0, 0, 0)
            applySoft(SoftStyle.RAISED, PhoneStyle.surface, PhoneStyle.RADIUS_CARD, cardBand)

            // 海报井只留柔光带和一圈很窄的内缩，剩下的宽度全部给封面。
            val bandPx = context.dp(cardBand)
            val insetPx = context.dp(posterInset)
            val posterWidth = (cardWidth - bandPx * 2 - insetPx * 2).coerceAtLeast(1)
            val posterHeight = (posterWidth / (3f / 4f)).toInt()
            addView(poster, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, posterHeight
            ).apply {
                leftMargin = insetPx
                rightMargin = insetPx
                topMargin = context.dp(1)
            })
            poster.addView(cover, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))

            // 四枚角标共用一套：12 号字、粗体、无字体内边距、朝贴边那一侧压暗、6/3/8/3 内边距。
            listOf(
                badge to (Gravity.BOTTOM or Gravity.END),
                season to (Gravity.TOP or Gravity.END),
                rank to (Gravity.TOP or Gravity.START),
                heat to (Gravity.BOTTOM or Gravity.START),
            ).forEach { (view, place) ->
                view.phoneSize(12f * scale)
                view.setTextColor(Color.WHITE)
                view.includeFontPadding = false
                view.setTypeface(view.typeface, android.graphics.Typeface.BOLD)
                view.gravity = place
                view.background = badgeScrim(place)
                view.setPadding(context.dp(6), context.dp(3), context.dp(8), context.dp(3))
                view.visibility = View.GONE
            }
            // 三枚都只按内容宽度量：角标是一枚小标签，不该在封面上铺出一条暗带。
            poster.addView(badge, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply { rightMargin = context.dp(5); bottomMargin = context.dp(5) })
            poster.addView(rank, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply { leftMargin = context.dp(5); topMargin = context.dp(5) })
            poster.addView(season, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply { rightMargin = context.dp(5); topMargin = context.dp(5) })
            // 左下角热度：与右下角的集数角标同一条底边，一左一右。
            poster.addView(heat, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            ).apply { leftMargin = context.dp(5); bottomMargin = context.dp(5) })

            addView(title, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))

            meta = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    context.dp(9f * scale), context.dp(4f * scale),
                    context.dp(9f * scale), context.dp(3f * scale)
                )
                visibility = GONE
                addView(tagLine, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(status, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }
            addView(meta, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        /**
         * 角标压暗层：朝**贴边那一侧**渐深，圆角跟海报井一致。
         *
         * 与电视 `TvStyle.scrimTop / scrimTopStart / scrimBottomStart / scrim` 四件套同一个效果：
         * 上角与下角的暗头在上下、左侧两枚的暗头在左。
         */
        private fun badgeScrim(place: Int): android.graphics.drawable.GradientDrawable {
            val dark = 0x99000000.toInt()
            val clear = Color.TRANSPARENT
            val fromStart = place == (Gravity.TOP or Gravity.START) ||
                place == (Gravity.BOTTOM or Gravity.START)
            val (colors, orientation) = when {
                fromStart -> intArrayOf(dark, clear) to android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT
                place == (Gravity.TOP or Gravity.END) -> intArrayOf(dark, clear) to android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM
                else -> intArrayOf(clear, dark) to android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM
            }
            return android.graphics.drawable.GradientDrawable(orientation, colors).apply {
                cornerRadius = context.dp(PhoneStyle.RADIUS_CARD).toFloat()
            }
        }

        fun bind(series: Series, position: Int) {
            title.text = series.title

            // 第二行：类型标签靠左（接口自带，最多取三个，与电视「标签优先」同一语义）。
            tagLine.text = series.tags.split(',', '·', '、', '|')
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.contains("集") }
                .take(3)
                .joinToString(" · ")
            // 状态靠右：收藏栏是「更新至 N 集」，其余页面留空（历史那一条由 `subtitleOf` 给）。
            status.text = subtitleOf?.invoke(series).orEmpty()
            meta.visibility = if (tagLine.text.isBlank() && status.text.isBlank()) GONE else VISIBLE

            val seasonText = com.hgtv.star.core.SeriesSeasons.badge(series)
            season.text = seasonText
            season.visibility = if (seasonText.isBlank()) GONE else VISIBLE

            val badgeText = com.hgtv.star.core.EpisodeLabel.total(series.episodeCount)
            badge.text = badgeText
            badge.visibility = if (badgeText.isBlank()) GONE else VISIBLE

            if (ranked) {
                rank.visibility = VISIBLE
                rank.text = "第 ${position + 1} 名"
                rank.setTextColor(if (position < 3) PhoneStyle.tips else Color.WHITE)
            } else {
                rank.visibility = GONE
            }

            // 左下角热度：服务端原串优先（榜单页），没有就用搜索卡片解析出的数字；
            // 两者都没有（分类页 / 收藏 / 历史）→ 整枚收起。火苗是 emoji 字符，不自绘。
            val heatText = com.hgtv.star.core.HeatLabel.badge(heatBy[series.id], series.heat)
            heat.text = heatText
            heat.visibility = if (heatText.isBlank()) GONE else VISIBLE

            contentDescription = listOf(
                badgeText, seasonText, series.title, tagLine.text.toString(), status.text.toString(),
                if (heatText.isBlank()) "" else "$heatText 热度",
            ).filter { it.isNotBlank() }.joinToString("，")

            PhoneServices.artwork(context).load(
                cover, series.cover, widthPx = cardWidth
            )
            setOnClickListener { onOpen?.invoke(series) }
            setOnLongClickListener {
                val handler = onLongPress ?: return@setOnLongClickListener false
                handler(series)
                true
            }
        }
    }
}
