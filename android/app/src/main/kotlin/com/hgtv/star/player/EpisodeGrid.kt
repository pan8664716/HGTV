// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.player

import android.content.Context
import android.graphics.Canvas
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import com.hgtv.star.tv.ButtonSize
import com.hgtv.star.tv.EpisodeCell
import com.hgtv.star.tv.ToggleChip
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.tv.focusBleed
import com.hgtv.star.tv.tvColumn
import com.hgtv.star.tv.tvDp
import com.hgtv.star.tv.tvRow
import com.hgtv.star.tv.tvScrollHost
import com.hgtv.star.tv.tvText

/**
 * 选集网格：简介页与播放界面共用同一份实现。
 *
 * 两处只有「宿主」不同——播放界面把它放进贴底的 [EpisodePanel] 弹窗，简介页直接贴在页面末尾——
 * 标题行、集数行、单元格尺寸、间距与翻页方式完全一致，改一处两处同步生效。
 *
 * 两处只在**背板**上不同，由 [player] 决定：播放界面压在视频上，铺一层主题浮层背板
 * （[TvStyle.sheet]，与播放设置面板同一份）；简介页贴在页面底色上，**不画任何背板**——
 * 它就是页面里的一块内容，再套一层底板会读成「一个可以按进去的大盒子」。
 *
 * 文字颜色**两处一致**：标题走 [TvStyle.textStrong]、说明走 [TvStyle.muted]。
 * 早先播放界面固定用 [TvStyle.playerText] 的暖白，是配着黑渐变背板的写法；
 * 背板换成主题材质之后，浅色主题下的暖白就不该再出现了。
 *
 * 一页放多少集由 [pageSize] 决定（10 / 20 / 30 / 40 / 50，设置页「内容管理与来源 → 选集分页数量」），
 * 列数固定 10，因此页高就是 `pageSize / 10` 行。**单元格大小按实测宽度与这个设定动态算**
 * （见 [fitCells]）：一页 10 集时比例 1.0，50 集时收到 0.47；屏幕宽窄 / 密度不同会跟着变，
 * 不写死 dp——但两头都夹住：最大就是改版前的默认档（[CELL_MAX]，60dp），最小 [CELL_MIN]（32dp）。
 * 最后一页只剩几集时仍按设定行数算大小，翻页不会看到按钮忽大忽小。
 * 边缘翻页可以用设置页「内容管理与来源 → 分页边缘翻页」关掉（[edgeTurn]）：关掉后右端按右选下一集、
 * 左端按左选上一集、最后一行按下不再翻页，换页改由分页行自己选。开启时翻页有两条路：
 *
 * * **左右**到本行两端翻同一行的相邻页（这一条保留，遥控器上最顺手）；
 * * **下**在最后一行（或本页只有一行时）翻到下一页；
 * * **上不再翻页**，改成把焦点交给**分页行**——那一行是「1-20 / 21-40 / …」每 20 集一枚芯片，
 *   与搜索页的推荐词同一套横向滚动 + 硬裁剪视口（见 [ClipFrame]），左右移动选页、确认即跳页。
 *
 * 「第 x—y 集」这段分段读数与标题、当前集**同在第一行**（播放面板那 300dp 里，
 * 单独一行读数就是实实在在一行单元格）；分页芯片行放在第一行**下面**、网格**上面**：
 * 这样从第一格按上是「往上」，与视觉方向一致；从分页行按确认（或按下）直接进那一页的第一格。
 *
 * **简介页与播放面板都用这一套，按钮大小因此永远一致**（同一套「宽度 × 行数比例」算法，
 * 连内容线都是同一条 [PAGE_SIDE_PAD]）。两处只在高度上分工：
 *
 * * 简介页：这一块的高度由页面钉死（[maxBlockHeight] = 视口高 − 上方信息区），
 *   一屏之内放得下五行，焦点落到最后一行时整页不动；
 * * 播放面板：高度**可变**，最小是 [TvStyle.PLAYER_PANEL_HEIGHT]，一页集数多时
 *   由 `EpisodePanel` 按 [naturalHeight] 加高——不压缩按钮，改加高面板。
 *
 * 只有一页（`count <= pageSize`）时整行隐藏，此时第一行按上照旧交回焦点系统离开选集——
 * 播放面板里焦点停在原地，简介页回到「播放 / 收藏 / 相关剧集」那一行。
 * 只有确认单元格才真正换集，翻页只移动焦点。
 */
internal class EpisodeGrid(
    context: Context,
    private val count: Int,
    private val current: Int,
    /** true：宿主是播放界面（压在视频上，文字固定暖白）；false：简介页（跟主题走）。 */
    private val player: Boolean,
    private val select: (Int) -> Unit,
    /**
     * 一页放多少集（10 / 20 / 30 / 40 / 50）。
     *
     * 由调用方按本机设置传入（见 `Library.episodePageSize`）：简介页与播放界面的选集面板
     * 因此**永远同一个分段**，不会出现「简介页 1-20、播放面板 1-30」这种对不上的情况。
     */
    pageSize: Int = 20,
    /**
     * 分页边缘翻页（设置页「内容管理与来源 → 分页边缘翻页」，默认开启）。
     *
     * 开启：左右到本行两端、下到最后一行时**翻页**（[turn]）。
     * 关闭：边缘不再翻页——右端按右键选**下一集**、左端按左键选**上一集**
     *（[step]，跨页时自动跟到相邻那一页），换页改由分页行自己选。
     */
    private val edgeTurn: Boolean = true
) : LinearLayout(context) {

    private val dp = { value: Number -> context.tvDp(value) }
    /** 一页集数（归一过）：只认 10 的整数倍，范围 10..50。 */
    private val perPage = pageSize.coerceIn(10, 50) / COLUMNS * COLUMNS
    /** 一页的行数：10 列固定，因此页高由一页集数决定（1..5 行）。 */
    private val rowsPerPage = (perPage / COLUMNS).coerceAtLeast(1)
    /** 标题行与说明行的字色：两处宿主一致，跟着主题走（见类注释）。 */
    private val titleColor = TvStyle.textStrong
    private val dimColor = TvStyle.muted
    private val scroll = ScrollView(context)
    /** 滚动容器的外壳：裁剪由它做，容器自己只留焦点余量（见 TvComponents.tvScrollHost）。 */
    private val scrollHost = context.tvScrollHost(scroll)
    private val grid = context.tvColumn()
    private val range = context.tvText("", 14f, dimColor)
    private var cells: List<EpisodeCell> = emptyList()
    private var group = current / perPage
    private var focused = current
    /** 标题那一行（「选集 · 正在看第 N 集」+ 当前分段）。 */
    private lateinit var headLine: LinearLayout
    /** 单元格的每一行：`fitCells()` 按实测宽度统一改它们的高度。 */
    private val rowLines = mutableListOf<LinearLayout>()
    /** 已经排了一次「布局完成后再算高度」的回调（防止一帧里排多次）。 */
    private var fitScheduled = false
    /**
     * 当前单元格高度（像素）：`0` 表示还没量过，先按屏宽估一个占位值（见 [provisionalCellHeight]）。
     *
     * **不是固定 dp**：由「实测格子宽度 × 每页行数对应的比例」算出（见 [fitCells]），
     * 屏幕宽窄、密度不同都会跟着变。两个宿主用同一套算法，因此按钮永远一样大；
     * 简介页放不下时才会再压一档（见 [heightBudgetPerRow]），播放面板则去加高自己。
     * 计算只认**设定的行数**（`rowsPerPage`），不认这一页实际几集：设定 50 集/页时，
     * 最后一页只剩 5 集，格子仍然是五行那一档的大小，翻页不会看到按钮忽大忽小。
     */
    private var cellHeight = 0
    /**
     * 上一次算高度时的输入（行宽 / 格子高 / 外壳开销 / 高度上限）：**四个都没变**才跳过重算。
     *
     * 只比「行宽 + 格子高」是不够的：外壳（标题行、分页行）的高度要等布局完成才准，
     * 首次计算时它还是 0，格子高恰好没变就会把这一轮当成「没变化」跳掉，
     * 于是块高按偏小的外壳算、内层就多出一截可滚的距离——表现就是「选集区还是会往下滑一点」。
     */
    private var fittedWidth = -1
    private var fittedHeight = -1
    private var fittedChrome = -1
    private var fittedLimit = -1
    /**
     * 简介页用：这一整块**最多**能占多高（像素）；`0` = 不限（整页滚动）。
     *
     * 简介页要一屏放下（50 集/页的五行不许把页面顶出一截、焦点落到最后一行时整页再往下滑），
     * 因此由页面算出「屏高 − 上方信息区」交给这里：网格把这一块的高度**正好钉在这个值上**
     *（见 [applyBlockHeight]），并在必要时把格子收一档（见 [heightBudgetPerRow]）。
     * 播放面板不用它：面板按 [naturalHeight] 自己加高，不压缩格子。
     */
    var maxBlockHeight: Int = 0
        set(value) {
            if (field == value) return
            field = value
            // 上限变了要重算一次（[fitCells] 的「没变就跳过」判据里带着它）。
            fittedLimit = -1
            post { fitCells() }
        }

    /**
     * 格子尺寸变了之后的回调（只有播放面板会挂）。
     *
     * 面板要按新尺寸决定自己该多高（见 `EpisodePanel.growToFit`）：格子在布局完成之后
     * 才算得准，面板没法在 `show()` 那一刻就问出来，因此由网格反过来通知。
     */
    var onCellSizeChanged: (()->Unit)? = null

    // ---- 分页行（1-20 / 21-40 …）：与搜索页推荐词同一套「横向滚动 + 硬裁剪视口」 ----
    private val pagerLine = context.tvRow().apply {
        gravity = Gravity.CENTER_VERTICAL
        // 与单元格同一条竖线：单元格在内层滚动容器里多留了 FOCUS_BLEED 的余量。
        setPadding(dp(TvStyle.FOCUS_BLEED), 0, dp(TvStyle.FOCUS_BLEED), 0)
    }
    private val pagerScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        // 裁剪交给外层 [ClipFrame]：这里打开会把焦点放大溢出的描边切平。
        clipChildren = false; clipToPadding = false
        isFillViewport = true
        // 滚到两端时不画越界的边缘光，避免看起来像「滚出了范围」。
        overScrollMode = View.OVER_SCROLL_NEVER
    }
    private val pagerChips = mutableListOf<ToggleChip>()
    private var pager: View? = null

    /** true：纵向撑满宿主高度（播放器弹窗）；false：按内容高度自适应（简介页）。 */
    var fillHeight = false
        set(value) {
            field = value
            stretch()
        }

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        // 背板：只有播放界面需要——它压在画面上，用与播放设置面板**同一份**主题浮层背板
        // （[TvStyle.sheet]），两块浮层的材质因此永远一致；简介页没有背板。
        // 压在画面上就取更透的那一档（[TvStyle.SHEET_ALPHA_VIDEO]）与直角（与播放设置面板同值）：
        // 这块浮层整宽贴底，圆角会在上沿切出两个缺口，露出后面一截画面。
        background = if (player) TvStyle.sheet(context, alpha = TvStyle.SHEET_ALPHA_VIDEO, radius = 0) else null
        // 横向内边距：**两个宿主取同一条内容线**（页面栅格 78dp）。
        //
        // 播放面板是整幅压屏的浮层，本来可以缩进少一点；但格子宽度就是「这一行宽 ÷ 10」，
        // 两边内容线不同，同一档分页在两处算出来的格子就会差几个 dp。用户要的是
        // 「播放页的选集按钮和简介页一样大」，因此这里用同一条线，宽度一致 → 格子一致。
        // 简介页那边由页面给同样的内边距（`pageInset()` 减掉焦点余量），网格自己的 side 传 0；
        // 播放面板这边也要扣掉同一个焦点余量，标题行与网格再各自加回来，两边落点才完全一致。
        val side = if (player) dp(PAGE_SIDE_PAD - TvStyle.FOCUS_BLEED) else 0
        // 上下内边距两个宿主也统一：外壳高度参与「一屏能放下几行」的计算（见 [chromeHeight]），
        // 两处不同会让同一档分页的格子大小对不上。
        setPadding(side, dp(18), side, dp(12))

        // 第一行：标题 + 当前集 + **当前分段（第 x—y 集）** + 操作提示。
        //
        // 分段读数原先自己占一整行（「第 1—20 集」），在播放面板那 300dp 的高度里
        // 那一行就是实打实的一行单元格：挪到标题行右侧之后，50 集/页的五行才排得下。
        // 标题行与集数行跟着单元格一起右移 FOCUS_BLEED：单元格在内层滚动容器里多留了这么多余量。
        headLine = context.tvRow().apply {
            setPadding(dp(TvStyle.FOCUS_BLEED), 0, dp(TvStyle.FOCUS_BLEED), 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        headLine.addView(context.tvText("选集", 20f, titleColor, bold = true))
        headLine.addView(
            context.tvText("正在看第 ${current + 1} 集 · 共 $count 集", 14f, dimColor),
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(14) }
        )
        headLine.addView(View(context), LinearLayout.LayoutParams(0, 1).apply { weight = 1f })
        range.setPadding(dp(14), 0, 0, 0)
        headLine.addView(range)
        // 操作提示在最右侧，窄屏（手机竖屏 / 小屏电视）上这一行已经挤了标题、集数与分段读数，
        // 再塞一段提示只会被切掉半截，因此窄屏不放它（方向键本身也够直观）。
        val wide = context.resources.configuration.screenWidthDp >= 900
        if (!player || wide) {
            headLine.addView(
                context.tvText("左 / 右 / 下 到边缘翻页 · 上 选页", 14f, dimColor).apply {
                    maxLines = 1
                    setPadding(dp(14), 0, 0, 0)
                }
            )
        }
        addView(headLine)

        // 分页行：「选集」字段下面、网格上面。只在多于一页时显示（见 [renderPager]）。
        pagerScroll.addView(pagerLine)
        val pagerViewport = ClipFrame(context).apply {
            addView(pagerScroll, FrameLayout.LayoutParams(-1, -1))
            visibility = View.GONE
        }
        // 分页行高度：两个宿主同一档（外壳高度参与一屏能放几行的计算，见 [chromeHeight]）。
        addView(
            pagerViewport,
            LinearLayout.LayoutParams(-1, dp(PAGER_HEIGHT)).apply { topMargin = dp(6) }
        )
        pager = pagerViewport

        scroll.isVerticalScrollBarEnabled = false
        scroll.setPadding(0, 0, 0, dp(TvStyle.GAP_TIGHT))
        scroll.focusBleed()
        scroll.addView(grid)
        addView(scrollHost, LinearLayout.LayoutParams(-1, 0))
        stretch()
        render()
    }

    /**
     * 布局完成之后按**实测尺寸**重算单元格高度（见 [fitCells]）。
     *
     * 不能在 `init` 里 `post` 一次就完事：`View.post` 的队列在视图挂上窗口时执行，
     * 那一刻还没测量，宽高仍是 0。放在 `onLayout` 之后才量得准；
     * 改单元格高度会再触发一轮布局，[fitCells] 用「行宽 / 预算没变就跳过」来收口，
     * 因此不会来回抖，换分辨率（或旋转）时又会重新算一次。
     */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (fitScheduled) return
        if (width <= 0 || rowLines.isEmpty()) return
        fitScheduled = true
        post {
            fitScheduled = false
            fitCells()
        }
    }

    /** 焦点落到当前集：弹窗打开后、或详情页重新构建后调用。 */
    fun focusCurrent() {
        if (cells.isEmpty()) return
        cells[(focused - group * perPage).coerceIn(0, cells.lastIndex)].requestFocus()
    }

    /** 当前这一页的第一格：简介页用它把「按钮行 → 选集」的上下指向接上。 */
    fun firstCell(): View? = cells.getOrNull(0)

    /**
     * 分页行的第一枚芯片：简介页用它把「按钮行 → 分页行 → 网格」的上下指向接上。
     *
     * [up] 是分页行再往上要回的地方（简介页是「播放 / 收藏 / 相关剧集」那一行按钮）；
     * 传 null 表示没有上方目标，按上停在原地（播放面板就是这种）。
     */
    fun pagerTop(up: View? = null): View? {
        pagerChips.forEach { chip -> chip.nextFocusUpId = (up ?: chip).id }
        return pagerChips.firstOrNull()
    }

    /**
     * 到边缘再按方向键就翻页。返回 true 表示这次按键已经被网格用掉，
     * false 交回系统做普通焦点移动（例如第一页再按上就离开选集区域）。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && pageKey(event.keyCode)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun pageKey(key: Int): Boolean {
        val index = cells.indexOfFirst { it.hasFocus() }
        if (index < 0) return false
        val row = index / COLUMNS
        val column = index % COLUMNS
        return when (key) {
            KeyEvent.KEYCODE_DPAD_LEFT ->
                column == 0 && if (edgeTurn) turn(-1, row, COLUMNS - 1) else step(index, -1)
            KeyEvent.KEYCODE_DPAD_RIGHT ->
                (column == COLUMNS - 1 || index == cells.lastIndex) &&
                    if (edgeTurn) turn(1, 0, 0) else step(index, 1)
            // 「上」不再翻页：第一行按上把焦点交给分页行（两处宿主一致）。
            // 只有一页时 focusPager 返回 false，按键交回框架照旧离开选集。
            KeyEvent.KEYCODE_DPAD_UP -> row == 0 && focusPager()
            // 关闭边缘翻页时最后一行不再翻页：交回框架（下面没有东西，焦点停在原地）。
            KeyEvent.KEYCODE_DPAD_DOWN ->
                (row >= rowsPerPage - 1 || cells.size <= COLUMNS) &&
                    (edgeTurn && turn(1, 0, column))
            else -> false
        }
    }

    /**
     * 逐集移动：「分页边缘翻页」关闭时，右端按右键 / 左端按左键不是翻页，而是**选到相邻那一集**。
     *
     * 与 [turn] 的区别：翻页保的是「行号 + 列号」，这里保的是「集数本身」——
     * 一行末格按右就是下一集的格子，哪怕它落在下一页的第一格；跨页时自动换页再落焦。
     * 到头（第 1 集再按左、最后一集再按右）返回 false，按键交回框架，焦点停在原地。
     */
    private fun step(index: Int, delta: Int): Boolean {
        val target = group * perPage + index + delta
        if (target < 0 || target >= count) return false
        group = target / perPage
        focused = target
        render()
        return true
    }

    /** 把焦点交给当前页那一枚分页芯片；没有分页行（只有一页）时返回 false。 */
    private fun focusPager(): Boolean {
        val chip = pagerChips.getOrNull(group) ?: return false
        chip.requestFocus()
        return true
    }

    /** 跳到第 [page] 页并落焦到那一页的第 [row] 行第 [column] 列。 */
    private fun jump(page: Int, row: Int, column: Int) {
        if (page < 0 || page * perPage >= count) return
        group = page
        focused = (page * perPage + row * COLUMNS + column)
            .coerceIn(page * perPage, minOf(page * perPage + perPage - 1, count - 1))
        render()
    }

    /** 翻页并渲染；目标页不存在时返回 false，交给系统按普通焦点移动处理。 */
    private fun turn(delta: Int, row: Int, column: Int): Boolean {
        val target = group + delta
        if (target < 0 || target * perPage >= count) return false
        group = target
        focused = (target * perPage + row * COLUMNS + column)
            .coerceIn(target * perPage, minOf(target * perPage + perPage - 1, count - 1))
        render()
        return true
    }

    private fun stretch() {
        val params = scrollHost.layoutParams as? LinearLayout.LayoutParams ?: return
        if (fillHeight) {
            params.height = 0
            params.weight = 1f
        } else {
            params.height = LinearLayout.LayoutParams.WRAP_CONTENT
            params.weight = 0f
        }
        scrollHost.layoutParams = params
    }

    private fun render() {
        grid.removeAllViews()
        rowLines.clear()
        val page = mutableListOf<EpisodeCell>()
        val start = group * perPage
        val end = minOf(start + perPage, count)
        // 铺行只按这一页**实际**需要的行数（`ceil(本页集数 / 10)`），不足一行的位置用 [Space] 补满，
        // 这样半行里的格子不会被权重拉宽。**格子高度一律按设定行数 `rowsPerPage` 算**
        //（见 [cellHeight]）：最后一页只剩 5 集时按钮不会突然变大，与满页完全同尺寸。
        val rows = ((end - start + COLUMNS - 1) / COLUMNS).coerceAtLeast(1)
        repeat(rows) { row ->
            // 行内上间距：播放面板里收一档（一页 50 集时这 4dp × 5 行就是 20dp 的单元格高度）。
            val line = context.tvRow().apply { setPadding(0, dp(TvStyle.GAP_TIGHT), 0, 0) }
            repeat(COLUMNS) { column ->
                val index = start + row * COLUMNS + column
                if (index < end) {
                    val cell = EpisodeCell(context, "${index + 1}", index == current) { select(index) }
                    // 高度是动态算的（见 [fitCells]），可能低于常规档的 60dp：
                    // 留着这个下限，TextView 会把测量结果顶回 60，动态尺寸就失效了。
                    cell.allowCompactHeight()
                    cell.contentDescription = "第 ${index + 1} 集" + if (index == current) "，当前播放" else ""
                    line.addView(cell, cellParams(if (cellHeight > 0) cellHeight else provisionalCellHeight()))
                    page += cell
                } else {
                    line.addView(Space(context), cellParams(if (cellHeight > 0) cellHeight else provisionalCellHeight()))
                }
            }
            grid.addView(line, LinearLayout.LayoutParams(-1, -2))
            rowLines += line
        }
        cells = page

        page.forEachIndexed { index, cell ->
            cell.nextFocusLeftId = page[if (index % COLUMNS == 0) index else index - 1].id
            cell.nextFocusRightId =
                page[if (index % COLUMNS == COLUMNS - 1 || index == page.lastIndex) index else index + 1].id
            cell.nextFocusUpId = if (index >= COLUMNS) page[index - COLUMNS].id else View.NO_ID
            cell.nextFocusDownId = if (index + COLUMNS < page.size) page[index + COLUMNS].id else View.NO_ID
        }
        range.text = "第 ${group * perPage + 1}—${minOf((group + 1) * perPage, count)} 集"
        renderPager()
        focusCurrent()
        // 换页 = 换了一批单元格：新建的格子已经按 [cellHeight] 摆好，
        // 这里再排一次 [fitCells] 只是让「最后一页行数少」时也能确认高度一致
        //（预算没变时它会直接返回，不会抖动）。
        post { fitCells() }
    }

    /**
     * 按**实测尺寸**算一格该多大（不是写死 dp）。
     *
     * 三步：
     *
     * 1. **宽度定基准**：列数固定 10，格子宽度就是「这一行实际宽 − 9 道格距」除以 10。
     *    720P / 1080P / 4K 的内容宽不同，格子宽度自然不同，不再出现「大屏上格子小、
     *    小屏上格子挤」；
     * 2. **行数定高宽比**：一页 10 集（一行）时比例 1.0，行数越多比例越小
     *    （50 集/页 ≈ 0.47，见 [ratioFor]），整块仍然铺得开、又不至于变成竖条；
     * 3. **再夹到 [CELL_MIN] .. [CELL_MAX]**：上限就是改版前的默认档（60dp），
     *    大屏上不会越算越大；下限 32dp，再窄就交给内部滚动。
     *
     * 4. **简介页再受高度上限约束**：上限由页面给（[maxBlockHeight] = 视口高 − 上方信息区），
     *    50 集/页的五行因此**不会把整页顶出一截**，焦点落到最后一行时页面不动
     *    （见 [applyBlockHeight]）。
     *    **播放面板不压格子**：它的高度是可变的，放不下就自己加高（见 [naturalHeight] 与
     *    `EpisodePanel`），因此两处的按钮大小永远一致。
     *
     * 只认**设定的行数**（`rowsPerPage`），不认这一页实际几集：设定 50 集/页时，
     * 最后一页只剩 5 集，格子仍然是五行那一档的大小，翻页不会看到按钮忽大忽小。
     */
    private fun fitCells() {
        val line = rowLines.firstOrNull() ?: return
        val lineWidth = line.width
        if (lineWidth <= 0) return
        val gap = dp(CELL_GAP)
        val cellWidth = (lineWidth - gap * COLUMNS) / COLUMNS
        if (cellWidth <= 0) return
        // 一格该多大：**只由实测宽度与设定行数决定**，两个宿主同一套算法，因此按钮一样大。
        val desired = (cellWidth * ratioFor(rowsPerPage)).toInt().coerceIn(dp(CELL_MIN), dp(CELL_MAX))
        // 简介页还要再受「一屏能放下多少」的约束（播放面板不压格子，改去加高面板，见 [naturalHeight]）。
        val limit = if (player) 0 else maxBlockHeight
        val budget = if (limit > 0) heightBudgetPerRow(limit) else 0
        val target = (if (budget > 0) minOf(desired, budget) else desired)
            .coerceIn(dp(CELL_MIN), dp(CELL_MAX))
        val chrome = chromeHeight()
        // 行宽 / 格子高 / 外壳开销 / 上限**四个都没变**才算「已经算过」：
        // 外壳高度要等布局完成才准，只比前两项会把「外壳从 0 变成真实值」的那一轮跳掉。
        if (lineWidth == fittedWidth && target == fittedHeight &&
            chrome == fittedChrome && limit == fittedLimit
        ) return
        fittedWidth = lineWidth
        fittedHeight = target
        fittedChrome = chrome
        fittedLimit = limit
        applyCellHeight(target)
        if (player) {
            // 播放面板：格子不压，多出来的行交给面板加高（见 [naturalHeight]）。
            // 行数少时面板仍是默认高度，这一块竖直居中，不贴着上沿。
            grid.gravity = Gravity.CENTER_VERTICAL
            grid.minimumHeight = (scrollHost.height - scroll.paddingTop - scroll.paddingBottom).coerceAtLeast(0)
            onCellSizeChanged?.invoke()
        } else if (maxBlockHeight > 0) {
            // 简介页：把这一块的高度正好钉在「一屏剩余空间」上，页面因此没有可滚余量。
            applyBlockHeight(maxBlockHeight)
        }
    }

    /**
     * 这一块按当前格子高度自然需要多高（像素）：外壳 + 设定行数 ×（格高 + 行距）。
     *
     * 播放面板用它决定自己该多高——**分页集数变多时不压缩格子，而是把面板加高**，
     * 因此面板高度 = max(默认高度, 这个值)。
     */
    fun naturalHeight(): Int {
        val cell = if (cellHeight > 0) cellHeight else provisionalCellHeight()
        return chromeHeight() + rowsPerPage * (cell + dp(TvStyle.GAP_TIGHT))
    }

    /**
     * 每行能分到多少高度（像素）：`0` 表示不设限（还没量出来）。
     *
     * 可用高度 = 上限 − 这一块的「外壳」（见 [chromeHeight]），再扣掉行距（按**设定行数**），
     * 余下的平均给每一行。
     */
    private fun heightBudgetPerRow(limit: Int): Int {
        if (limit <= 0) return 0
        val chrome = chromeHeight()
        val available = limit - chrome
        if (available <= 0) return 0
        val gap = dp(TvStyle.GAP_TIGHT) * rowsPerPage
        return ((available - gap) / rowsPerPage).coerceAtLeast(1)
    }

    /**
     * 这一块除格子以外的固定开销（像素）：自身上下内边距 + 标题行 + 分页行（含上间距）
     * + 内层滚动容器的上下焦点余量。
     */
    private fun chromeHeight(): Int {
        val pagerBlock = pager?.takeIf { it.visibility == View.VISIBLE }?.let {
            it.height + ((it.layoutParams as? MarginLayoutParams)?.topMargin ?: 0)
        } ?: 0
        val head = if (::headLine.isInitialized) headLine.height else 0
        return paddingTop + paddingBottom + head + pagerBlock + scroll.paddingTop + scroll.paddingBottom
    }

    /**
     * 简介页：把这一块的高度**正好钉在 [limit]**（= 一屏减掉上方信息区之后的剩余空间）。
     *
     * 为什么不按「自然高度」收：自然高度是「外壳 + 行数 ×（格高 + 行距）」算出来的，
     * 而外壳里的标题行、分页行高度要等布局完成才准，两边差一两个像素就会让页面多出
     * 一截可滚的距离——表现就是「选集区还是会往下滑一点」。
     * 钉成上限之后，这一块加上上方信息区**正好是一屏**，页面没有可滚余量，
     * 焦点落到最后一行时整页一个像素都不动。
     *
     * 内容在块内竖直居中（`grid.gravity = CENTER_VERTICAL`）：行数少时上下留白对称，
     * 不会贴着上沿、把空白全留在底部。
     */
    private fun applyBlockHeight(limit: Int) {
        val height = limit.coerceAtLeast(1)
        val params = layoutParams as? LinearLayout.LayoutParams
        if (params != null && params.height != height) {
            params.height = height
            layoutParams = params
        }
        grid.gravity = Gravity.CENTER_VERTICAL
        // 内层滚动：内容（自然高度）超过这一块才需要，否则按内容收，滚无可滚。
        val available = (height - chromeHeight() + scroll.paddingTop + scroll.paddingBottom)
            .coerceAtLeast(0)
        val natural = rowsPerPage * (cellHeight + dp(TvStyle.GAP_TIGHT))
        val overflow = natural > available
        val sp = scrollHost.layoutParams as? LinearLayout.LayoutParams
        if (sp != null) {
            val h = if (overflow) 0 else LinearLayout.LayoutParams.WRAP_CONTENT
            val w = if (overflow) 1f else 0f
            if (sp.height != h || sp.weight != w) {
                sp.height = h
                sp.weight = w
                scrollHost.layoutParams = sp
            }
        }
    }

    /**
     * 一页 [rows] 行时格子的「高 ÷ 宽」。
     *
     * 一行时正方（1.0）：10 集铺满一整屏，格子大一点才读得清；
     * 五行时 0.47：简介页要在**一屏之内**放下五行（50 集），再高整页就会被顶出一截。
     * 中间三档线性过渡，档位之间差约 13%。
     *
     * 系数按「1280×720 设计画布上简介页能给选集多少竖向空间」定的：
     * 五行 × (格高 + 行距) + 外壳 ≈ 一屏减掉上方信息区之后剩下的高度。
     * 播放面板用的是同一个格子尺寸（不压缩），多出来的高度由面板自己加高（见 [naturalHeight]）。
     */
    private fun ratioFor(rows: Int): Float {
        val span = (rows - 1).coerceIn(0, 4)
        return 1f - 0.133f * span
    }

    /**
     * 还没测量时的占位高度：按屏宽估一个，避免首帧先按 0 / 常规档画一遍再跳。
     *
     * 估法与 [fitCells] 同一套，只是行宽用「屏宽 − 已知的横向留白」：
     * 播放面板是两侧各 58dp 加焦点余量，简介页是页面栅格两侧各 78dp。
     */
    private fun provisionalCellHeight(): Int {
        val screen = context.resources.displayMetrics.widthPixels
        val side = dp(PAGE_SIDE_PAD)
        val lineWidth = (screen - 2 * side).coerceAtLeast(1)
        val cellWidth = ((lineWidth - dp(CELL_GAP) * COLUMNS) / COLUMNS).coerceAtLeast(1)
        return (cellWidth * ratioFor(rowsPerPage)).toInt().coerceIn(dp(CELL_MIN), dp(CELL_MAX))
    }

    /** 单元格（含补位）的布局参数：宽度等分，高度取 [height]。 */
    private fun cellParams(height: Int) = LinearLayout.LayoutParams(0, height).apply {
        weight = 1f
        rightMargin = dp(CELL_GAP)
    }

    /**
     * 把这一页所有格子（包括补位的 [Space]）改成同一个高度。
     *
     * 只在真变了的时候改，避免在布局回调里反复 `requestLayout`。
     */
    private fun applyCellHeight(target: Int) {
        if (target <= 0 || target == cellHeight) return
        cellHeight = target
        rowLines.forEach { line ->
            for (index in 0 until line.childCount) {
                val child = line.getChildAt(index)
                val params = child.layoutParams as? LinearLayout.LayoutParams ?: continue
                if (params.height == target) continue
                params.height = target
                child.layoutParams = params
            }
        }
    }

    /**
     * 铺 / 更新分页行。
     *
     * 芯片只在页数变化时重建（页数在详情页里是固定的，重建一次就够）；每次翻页只改
     * 「哪一枚是当前页」的高亮，并把每枚芯片的「下」接到当前页第一格——按确认跳页，
     * 按下也直接进那一页。
     */
    private fun renderPager() {
        val viewport = pager ?: return
        val pages = (count + perPage - 1) / perPage
        // 只有一页时这一行没有意义，收起来（第一行按上照旧离开选集）。
        // 两处宿主都显示：播放面板的高度与播放设置面板同高，分页行不会把网格挤扁。
        if (pages <= 1) {
            // 只有一页：这一行没有意义，收起来（第一行按上照旧离开选集）。
            viewport.visibility = View.GONE
            return
        }
        viewport.visibility = View.VISIBLE
        if (pagerChips.size != pages) {
            pagerLine.removeAllViews(); pagerChips.clear()
            repeat(pages) { page ->
                val start = page * perPage + 1
                val end = minOf((page + 1) * perPage, count)
                val chip = ToggleChip(context, "$start-$end", page == group, ButtonSize.DENSE) {
                    jump(page, 0, 0)
                }
                chip.contentDescription = "第 $start 到 $end 集，按确认跳到这一页"
                // 左右在行内走，走到两端停在原地（与搜索页推荐词同一套）。
                chip.nextFocusLeftId = pagerChips.lastOrNull()?.id ?: chip.id
                chip.nextFocusRightId = chip.id
                chip.nextFocusUpId = chip.id
                // **只有确认（点 / OK 键）才跳页**：方向键只移动焦点。
                // 早先这里按下键直接 `jump(page)`，于是「上 → 下」这种随手一按就换了页，
                // 用户只是想回到选集里继续看，页面却被翻走了。
                // 按下键照常往下走：目标由 [renderPager] 统一接到**当前页**第一格。
                if (pagerChips.isNotEmpty()) pagerChips.last().nextFocusRightId = chip.id
                pagerChips += chip
                pagerLine.addView(chip, LinearLayout.LayoutParams(-2, -2).apply {
                    if (pagerChips.size > 1) leftMargin = dp(6)
                })
            }
        }
        pagerChips.forEachIndexed { page, chip -> chip.setActive(page == group) }
        // 每枚芯片的「下」都指向**当前显示这一页**的第一格：按下只是回到选集，
        // 不换页；要换页得在芯片上按确认。
        val firstOfGroup = cells.firstOrNull()?.id
        if (firstOfGroup != null) pagerChips.forEach { chip -> chip.nextFocusDownId = firstOfGroup }
        // 当前页那一枚滚进可视区（横向滚动容器自己会把请求焦点的子控件滚进来，
        // 但「当前页」不一定有焦点——翻页时焦点还在网格里，因此这里显式滚一次）。
        pagerScroll.post {
            val chip = pagerChips.getOrNull(group) ?: return@post
            if (chip.width <= 0 || pagerScroll.width <= 0) return@post
            val margin = dp(TvStyle.GAP_INLINE)
            val viewportStart = pagerScroll.scrollX
            val viewportEnd = viewportStart + pagerScroll.width
            // 芯片在视口左边之外 → 把它贴到左边；在右边之外 → 贴到右边。留一档间距。
            when {
                chip.left - margin < viewportStart ->
                    pagerScroll.smoothScrollTo((chip.left - margin).coerceAtLeast(0), 0)
                chip.right + margin > viewportEnd ->
                    pagerScroll.smoothScrollTo(chip.right + margin - pagerScroll.width, 0)
            }
        }
    }
}

/** 分页行的固定高度：芯片（30dp 底板 + 柔光带）再加一点上下余量。 */
private val PAGER_HEIGHT = ButtonSize.DENSE.height + 2 * TvStyle.SOFT_BAND + 8

/** 格子之间的横向间距（dp）：10 列各留一道，算格子宽度时要先扣掉。 */
private const val CELL_GAP = 4

/**
 * 格子高度的下限（dp）：16sp 的集数上下各留一条柔光带大致就是这个高度。
 *
 * 再矮数字就会顶到柔光带上、焦点框也压不住。碰到这个下限说明屏幕太窄或一页集数太多，
 * 此时宁可让面板内部滚动，也不把格子继续压扁。
 */
private const val CELL_MIN = 32

/**
 * 格子高度的上限（dp）= **改版前的默认大小**（[TvStyle.BUTTON_NORMAL] + 两条柔光带 = 60dp）。
 *
 * 动态计算只在「默认档及以下」里收放：大屏上宽度更大时格子不会一路涨到变成方块，
 * 观感与之前的固定尺寸一致；小屏 / 一页集数多时按比例收，最低到 [CELL_MIN]。
 */
private val CELL_MAX = TvStyle.BUTTON_NORMAL + 2 * TvStyle.SOFT_BAND

/**
 * 内容线（dp）：页面栅格的左右内边距，**两个宿主共用**。
 *
 * 播放面板是整幅压屏的浮层，本来可以缩进少一点；但格子宽度就是「这一行宽 ÷ 10」，
 * 两边内容线不同，同一档分页算出来的格子就会差几个 dp。统一之后播放页与简介页的
 * 选集按钮一样大（见 [EpisodeGrid.fitCells]）。首帧占位也按它估算。
 */
private const val PAGE_SIDE_PAD = 78

/**
 * 一页固定 10 列。
 *
 * 列数不跟「一页集数」一起变：一页放 10 集时铺 1 行、放 50 集时铺 5 行。
 * 列数一变，单元格宽度跟着变，两种宿主（简介页 / 播放面板）的观感就会随设置跳来跳去。
 */
private const val COLUMNS = 10

/**
 * 分页行的显示边界：与搜索页推荐词那一行同一套做法。
 *
 * `HorizontalScrollView` 自己的 `clipChildren` 在某些机型 / 焦点放大动画下靠不住，
 * 外面套一层在 `dispatchDraw` 里直接 `clipRect` 的容器，滚到哪儿都只显示这一段。
 */
private class ClipFrame(context: Context) : FrameLayout(context) {
    init {
        clipChildren = true
        clipToPadding = true
    }

    override fun dispatchDraw(canvas: Canvas) {
        val save = canvas.save()
        canvas.clipRect(0, 0, width, height)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }
}
