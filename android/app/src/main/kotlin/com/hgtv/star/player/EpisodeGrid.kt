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
 * 两处不一致的是**文字颜色与背板**，由 [player] 决定：播放界面压在视频上，永远用
 * [TvStyle.playerText] 的暖白（浅色主题下也不会变成深色字），背板是与播放设置面板同一份的浮层渐变；
 * 简介页贴在页面底色上，跟着主题走，**不画任何背板**——它就是页面里的一块内容，
 * 再套一层底板会读成「一个可以按进去的大盒子」。
 *
 * 一页 20 集（10 列 × 2 行）。翻页有两条路，都与「到边缘」无关：
 *
 * * **左右**到本行两端翻同一行的相邻页（这一条保留，遥控器上最顺手）；
 * * **下**在最后一行（或本页只有一行时）翻到下一页；
 * * **上不再翻页**，改成把焦点交给**分页行**——那一行是「1-20 / 21-40 / …」每 20 集一枚芯片，
 *   与搜索页的推荐词同一套横向滚动 + 硬裁剪视口（见 [ClipFrame]），左右移动选页、确认即跳页。
 *
 * 分页行放在「选集」标题与集数行**下面**、单元格网格**上面**：这样从第一行按上是「往上」，
 * 与视觉方向一致；从分页行按确认（或按下）直接进那一页的第一格。
 *
 * **简介页与播放面板都用这一套**：播放面板原先因为高度固定 300dp 放不下第三行而保留「上」翻页，
 * 现在它的高度跟着分页行一起长（见 [pagerExtra]），两处行为完全一致。
 *
 * 只有一页（`count <= 20`）时整行隐藏，此时第一行按上照旧交回焦点系统离开选集——
 * 播放面板里焦点停在原地，简介页回到「继续观看 / 收藏 / 完整简介」那一行。
 * 只有确认单元格才真正换集，翻页只移动焦点。
 */
internal class EpisodeGrid(
    context: Context,
    private val count: Int,
    private val current: Int,
    /** true：宿主是播放界面（压在视频上，文字固定暖白）；false：简介页（跟主题走）。 */
    private val player: Boolean,
    private val select: (Int) -> Unit
) : LinearLayout(context) {

    private val dp = { value: Number -> context.tvDp(value) }
    /** 标题行的字色：播放界面固定暖白，简介页跟主题。 */
    private val titleColor = if (player) TvStyle.playerText else TvStyle.textStrong
    private val dimColor = if (player) TvStyle.playerDim else TvStyle.muted
    private val scroll = ScrollView(context)
    /** 滚动容器的外壳：裁剪由它做，容器自己只留焦点余量（见 TvComponents.tvScrollHost）。 */
    private val scrollHost = context.tvScrollHost(scroll)
    private val grid = context.tvColumn()
    private val range = context.tvText("", 14f, dimColor)
    private var cells: List<EpisodeCell> = emptyList()
    private var group = current / PAGE_SIZE
    private var focused = current

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

    /**
     * 分页行额外占掉的高度（设计 dp）；只有一页时为 0。
     *
     * 播放面板的高度是固定写死的，加了分页行之后必须跟着长——否则网格会被挤到只剩一行，
     * 或者分页行直接被裁掉（见 [EpisodePanel]）。
     */
    val pagerExtra: Int get() = if(count > PAGE_SIZE) PAGER_HEIGHT + 6 else 0

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
        // 背板：只有播放界面需要——它压在画面上，用与播放设置面板**同一份**浮层渐变
        // （TvStyle.overlayPanel()），两块浮层的透明度因此永远一致；简介页没有背板。
        background = if (player) TvStyle.overlayPanel() else null
        // 横向内边距只在浮层里有意义（浮层是整幅压屏的，内容要离开屏幕边）。
        // 简介页贴在页面里，内容线由页面给（容器的 pageInset），这里不再自己缩进，
        // 于是「选集」这一块与上面的海报、按钮同一条竖线。
        val side = if (player) dp(58) else 0
        setPadding(side, dp(18), side, dp(12))

        // 标题行与集数行跟着单元格一起右移 FOCUS_BLEED：单元格在内层滚动容器里多留了这么多余量。
        val head = context.tvRow().apply { setPadding(dp(TvStyle.FOCUS_BLEED), 0, dp(TvStyle.FOCUS_BLEED), 0) }
        head.addView(context.tvText("选集", 20f, titleColor, bold = true))
        head.addView(
            context.tvText("正在看第 ${current + 1} 集 · 共 $count 集", 14f, dimColor),
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(14) }
        )
        head.addView(View(context), LinearLayout.LayoutParams(0, 1).apply { weight = 1f })
        head.addView(
            context.tvText("左 / 右 / 下 到边缘翻页 · 上 选页", 14f, dimColor)
        )
        addView(head)

        val rangeRow = context.tvRow().apply { setPadding(dp(TvStyle.FOCUS_BLEED), 0, dp(TvStyle.FOCUS_BLEED), 0) }
        rangeRow.addView(range, LinearLayout.LayoutParams(0, -2).apply { weight = 1f })
        addView(rangeRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        // 分页行：「选集」字段下面、网格上面。只在多于一页时显示（见 [renderPager]）。
        pagerScroll.addView(pagerLine)
        val pagerViewport = ClipFrame(context).apply {
            addView(pagerScroll, FrameLayout.LayoutParams(-1, -1))
            visibility = View.GONE
        }
        addView(pagerViewport, LinearLayout.LayoutParams(-1, dp(PAGER_HEIGHT)).apply { topMargin = dp(6) })
        pager = pagerViewport

        scroll.isVerticalScrollBarEnabled = false
        scroll.setPadding(0, 0, 0, dp(TvStyle.GAP_TIGHT))
        scroll.focusBleed()
        scroll.addView(grid)
        addView(scrollHost, LinearLayout.LayoutParams(-1, 0))
        stretch()
        render()
    }

    /** 焦点落到当前集：弹窗打开后、或详情页重新构建后调用。 */
    fun focusCurrent() {
        if (cells.isEmpty()) return
        cells[(focused - group * PAGE_SIZE).coerceIn(0, cells.lastIndex)].requestFocus()
    }

    /** 当前这一页的第一格：简介页用它把「按钮行 → 选集」的上下指向接上。 */
    fun firstCell(): View? = cells.getOrNull(0)

    /**
     * 分页行的第一枚芯片：简介页用它把「按钮行 → 分页行 → 网格」的上下指向接上。
     *
     * [up] 是分页行再往上要回的地方（简介页是「相关剧集 / 完整简介」那一行按钮）；
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
        val row = index / 10
        val column = index % 10
        return when (key) {
            KeyEvent.KEYCODE_DPAD_LEFT -> column == 0 && turn(-1, row, 9)
            KeyEvent.KEYCODE_DPAD_RIGHT ->
                (column == 9 || index == cells.lastIndex) && turn(1, 0, 0)
            // 「上」不再翻页：第一行按上把焦点交给分页行（两处宿主一致）。
            // 只有一页时 focusPager 返回 false，按键交回框架照旧离开选集。
            KeyEvent.KEYCODE_DPAD_UP -> row == 0 && focusPager()
            KeyEvent.KEYCODE_DPAD_DOWN ->
                (row >= 1 || cells.size <= 10) && turn(1, 0, column)
            else -> false
        }
    }

    /** 把焦点交给当前页那一枚分页芯片；没有分页行（只有一页）时返回 false。 */
    private fun focusPager(): Boolean {
        val chip = pagerChips.getOrNull(group) ?: return false
        chip.requestFocus()
        return true
    }

    /** 跳到第 [page] 页并落焦到那一页的第 [row] 行第 [column] 列。 */
    private fun jump(page: Int, row: Int, column: Int) {
        if (page < 0 || page * PAGE_SIZE >= count) return
        group = page
        focused = (page * PAGE_SIZE + row * 10 + column)
            .coerceIn(page * PAGE_SIZE, minOf(page * PAGE_SIZE + PAGE_SIZE - 1, count - 1))
        render()
    }

    /** 翻页并渲染；目标页不存在时返回 false，交给系统按普通焦点移动处理。 */
    private fun turn(delta: Int, row: Int, column: Int): Boolean {
        val target = group + delta
        if (target < 0 || target * PAGE_SIZE >= count) return false
        group = target
        focused = (target * PAGE_SIZE + row * 10 + column)
            .coerceIn(target * PAGE_SIZE, minOf(target * PAGE_SIZE + PAGE_SIZE - 1, count - 1))
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
        val page = mutableListOf<EpisodeCell>()
        (group * PAGE_SIZE until minOf((group + 1) * PAGE_SIZE, count)).toList().chunked(10).forEach { indexes ->
            val line = context.tvRow().apply { setPadding(0, dp(TvStyle.GAP_TIGHT), 0, 0) }
            indexes.forEach { index ->
                val cell = EpisodeCell(context, "${index + 1}", index == current) { select(index) }
                cell.contentDescription = "第 ${index + 1} 集" + if (index == current) "，当前播放" else ""
                line.addView(
                    cell,
                    LinearLayout.LayoutParams(0, dp(TvStyle.BUTTON_NORMAL + 2 * TvStyle.SOFT_BAND)).apply {
                        weight = 1f
                        rightMargin = dp(4)
                    }
                )
                page += cell
            }
            repeat(10 - indexes.size) {
                line.addView(
                    Space(context),
                    LinearLayout.LayoutParams(0, dp(TvStyle.BUTTON_NORMAL + 2 * TvStyle.SOFT_BAND)).apply {
                        weight = 1f
                        rightMargin = dp(4)
                    }
                )
            }
            grid.addView(line, LinearLayout.LayoutParams(-1, -2))
        }
        cells = page

        page.forEachIndexed { index, cell ->
            cell.nextFocusLeftId = page[if (index % 10 == 0) index else index - 1].id
            cell.nextFocusRightId =
                page[if (index % 10 == 9 || index == page.lastIndex) index else index + 1].id
            cell.nextFocusUpId = if (index >= 10) page[index - 10].id else View.NO_ID
            cell.nextFocusDownId = if (index + 10 < page.size) page[index + 10].id else View.NO_ID
        }
        range.text = "第 ${group * PAGE_SIZE + 1}—${minOf((group + 1) * PAGE_SIZE, count)} 集"
        renderPager()
        focusCurrent()
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
        val pages = (count + PAGE_SIZE - 1) / PAGE_SIZE
        // 只有一页时这一行没有意义，收起来（第一行按上照旧离开选集）。
        // 两处宿主都显示：播放面板的高度由 [pagerExtra] 补上，不会把网格挤扁。
        if (pages <= 1) {
            // 只有一页：这一行没有意义，收起来（第一行按上照旧离开选集）。
            viewport.visibility = View.GONE
            return
        }
        viewport.visibility = View.VISIBLE
        if (pagerChips.size != pages) {
            pagerLine.removeAllViews(); pagerChips.clear()
            repeat(pages) { page ->
                val start = page * PAGE_SIZE + 1
                val end = minOf((page + 1) * PAGE_SIZE, count)
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

/** 一页 20 集（10 列 × 2 行），分页芯片也按 20 一段。 */
private const val PAGE_SIZE = 20

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
