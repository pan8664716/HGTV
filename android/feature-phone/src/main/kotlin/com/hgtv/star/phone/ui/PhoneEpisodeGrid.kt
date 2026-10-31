// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 选集网格（**带分页**，做法照 TV 端的 `EpisodeGrid`）。
 *
 * 短剧动辄上百集，一屏铺不下；TV 端的解法是「固定列数 × 每页集数 + 一枚一枚的分页芯片」，
 * 手机上沿用同一套：
 *
 * ```
 * 选集 · 正在看第 3 集 · 共 85 集
 * 第 1—20 集
 * [1-20] [21-40] [41-60] [61-80] [81-85]      ← 分页芯片，当前页高亮
 *  1   2   3   4   5
 *  6   7   8   9  10
 * 11  12  13  14  15
 * 16  17  18  19  20
 * ```
 *
 * 为什么不是一条长滚动列表：一屏 20 集是「扫一眼就能定位」的上限；再多就得靠滑，
 * 而滑到第 60 集之后想回第 3 集要滑回去——分页把这件事变成一次点击。
 *
 * **每行固定 5 个**，一页几行由设置页的「选集每页」决定（20 / 30 / 50 / 80 / 100 集 →
 * 4 / 6 / 10 / 16 / 20 行）。全屏（右侧 1/3 抽屉）与非全屏（底部浮层）用的是同一个
 * 页大小与同一个列数，因此两处翻页的节奏完全一致。
 */
class PhoneEpisodeGrid(
    context: Context,
    private val count: Int,
    private val current: Int,
    /**
     * 一页多少集（设置页「选集每页」：20 / 30 / 50 / 80 / 100）。
     *
     * **每行固定 5 个**，行数 = 一页集数 ÷ 5；全屏与非全屏用的是同一个值与同一个列数，
     * 因此两处翻页的节奏完全一致。
     */
    pageSize: Int,
    /** 横屏：格子略矮、字号略小（右侧只有 1/3 屏宽）。 */
    private val compact: Boolean,
    /** 网格区域的最大高度（像素）：超过就内部滚动，不把浮层顶出屏幕。 */
    private val maxGridHeight: Int,
    /**
     * 分页边缘翻页（设置 → 内容管理与来源，与 TV 同一份落盘值）。
     *
     * 电视上是「在最右一列再按右 → 下一页」。手机没有方向键，等价动作就是**在网格上左右滑**：
     * 打开时滑一下就翻一页，关掉时只能点上面的分页芯片。
     */
    private val edgeTurn: Boolean = true,
    /** 播放页选集浮层：无论设置如何，都允许在网格上左右滑翻页。 */
    private val swipePages: Boolean = false,
    private val onPick: (Int) -> Unit,
) : LinearLayout(context) {

    /** 每行固定 5 个（用户要求，全屏与非全屏一致）。 */
    private val columns = COLUMNS
    /** 一页集数：按 5 的整数倍归一，最小一行、最大 100。 */
    private val perPage = (pageSize / COLUMNS * COLUMNS).coerceIn(COLUMNS, 100)
    private var page = (current / perPage).coerceAtLeast(0)

    /**
     * 网格容器：**竖向 LinearLayout**，每一行再是横向 LinearLayout。
     *
     * 这里曾经写成 `GridLayout(columnCount = 5)` 却往里面塞「整行」的 LinearLayout：
     * GridLayout 会把每一行当成**一个格子**排进 5 列，于是 5 行并排挤在 1/5 宽度里、
     * 行内的 5 个集数再被压成零宽——表现就是「只看到 5 个，其余全不见」。
     * 分列已经在行内用 weight 做完了，容器只需要竖向堆叠。
     */
    private val grid = LinearLayout(context).apply { orientation = VERTICAL }
    /** 网格的滚动外壳：一页 100 集 = 20 行，必须能滚。 */
    private val gridScroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(grid, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }
    private val range = TextView(context).apply {
        setTextColor(PhoneStyle.textDim)
        phoneSize(if (compact) 11 else 12)
    }
    private val pagerLine = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val pagerScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(pagerLine, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }
    private val chips = ArrayList<TextView>()
    /** 右侧的「第 1/5 页」读数：分页芯片被滚出视口时，用户仍然知道现在在第几页。 */
    private val pageLabel = TextView(context).apply {
        setTextColor(PhoneStyle.textFaint)
        phoneSize(if (compact) 10 else 11)
        setPadding(context.dp(8), 0, 0, 0)
    }

    init {
        orientation = VERTICAL

        // 第一行：分段读数（TV 端同一处位置）
        addView(range, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(if (compact) 6 else 8) })

        // 第二行：分页芯片。**永远显示**——只有一页时也留一枚（`1-N`），
        // 否则用户会以为「分页功能又没了」（一页 100 集、全剧 85 集时就是这种情形）。
        val pagerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        pagerRow.addView(pagerScroll, LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        pagerRow.addView(pageLabel, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        addView(pagerRow, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(6) })

        addView(gridScroll, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(8) })

        render()
    }

    private fun dp(value: Number): Int = context.dp(value)

    private var downX = 0f
    private var downY = 0f
    private var interceptingSwipe = false
    private var swipeAllowed = false

    /**
     * 左右滑翻页：在整块网格上识别「横向为主」的手势。
     *
     * 手势必须在这里拦截：集数格和分页芯片都是可点击的 TextView，事件会先被它们消费，
     * 挂在内部 ScrollView 上的监听器根本收不到完整的滑动。
     */
    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (!edgeTurn && !swipePages) return super.onInterceptTouchEvent(ev)
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                interceptingSwipe = false
                // 分页芯片那一行本身要能横向滚动，左右翻页只从网格区域起手。
                swipeAllowed = ev.y >= gridScroll.top
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (swipeAllowed && !interceptingSwipe && kotlin.math.abs(dx) > dp(48) &&
                    kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f
                ) {
                    interceptingSwipe = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
        }
        return interceptingSwipe || super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (!interceptingSwipe) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_UP -> {
                val dx = ev.x - downX
                parent?.requestDisallowInterceptTouchEvent(false)
                interceptingSwipe = false
                swipeAllowed = false
                if (kotlin.math.abs(dx) > dp(48)) {
                    jump(page + if (dx < 0) 1 else -1)
                }
                return true
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                interceptingSwipe = false
                swipeAllowed = false
                return true
            }
        }
        return true
    }

    /** 页数与区间文案走 `:core`（`EpisodePaging`，电视端同一份）：两端读起来必须逐字相同。 */
    private fun pageCount(): Int = com.hgtv.star.core.EpisodePaging.pages(count, perPage)

    private fun render() {
        // 「第 A—B 集 · 共 N 集」「第 3/5 页」由 `:core` 算，不在这一层各写一遍。
        range.text = com.hgtv.star.core.EpisodePaging.rangeText(count, perPage, page)

        // 分页芯片：页数变了才重建，翻页只改高亮。
        val pages = pageCount()
        pageLabel.text = com.hgtv.star.core.EpisodePaging.pageText(count, perPage, page)
        if (chips.size != pages) {
            pagerLine.removeAllViews()
            chips.clear()
            for (p in 0 until pages) {
                val chip = TextView(context).apply {
                    text = com.hgtv.star.core.EpisodePaging.chipText(count, perPage, p)
                    gravity = Gravity.CENTER
                    phoneSize(if (compact) 11 else 12)
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    setOnClickListener { jump(p) }
                }
                chips += chip
                pagerLine.addView(chip, LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { if (p > 0) leftMargin = dp(6) })
            }
        }
        chips.forEachIndexed { p, chip ->
            val on = p == page
            chip.setTextColor(if (on) Color.WHITE else PhoneStyle.textDim)
            chip.background = PhoneSheet.pillBackground(
                if (on) PhoneStyle.accent else PhoneStyle.surface,
                if (on) 0xFF7CBEFF.toInt() else 0xFF3D4652.toInt(),
                dp(12).toFloat()
            )
        }

        // 网格：按本页**实际**集数铺行，缺的位置用空格子补满，半行里的格子不会被拉宽。
        // 区间同样来自 `:core`：页码 → 集号只有一个算法。
        val cellRange = com.hgtv.star.core.EpisodePaging.range(count, perPage, page)
        val start = cellRange.first
        val end = if (cellRange.isEmpty()) 0 else cellRange.last + 1
        grid.removeAllViews()
        // 格子高度按 50% 字阶定：12 号字落成 18px，28dp 的格子上下各留 6dp，
        // 仍是一根能点准的按钮（38dp 的旧值在缩字之后会变成四个字悬在一大块空白里）。
        val cellH = dp(if (compact) 26 else 28)
        // 横屏抽屉窄：格距收到 3dp，把宽度留给格子本身（5 列时每格能多出 1dp）。
        val gap = dp(if (compact) 3 else 4)
        val lines = ((end - start + columns - 1) / columns).coerceAtLeast(1)
        for (r in 0 until lines) {
            val line = LinearLayout(context).apply { orientation = HORIZONTAL }
            for (c in 0 until columns) {
                val i = start + r * columns + c
                if (i < end) {
                    line.addView(cell(i), LayoutParams(0, cellH, 1f).apply {
                        if (c < columns - 1) rightMargin = gap
                    })
                } else {
                    line.addView(View(context), LayoutParams(0, cellH, 1f).apply {
                        if (c < columns - 1) rightMargin = gap
                    })
                }
            }
            grid.addView(line, LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { if (r > 0) topMargin = gap })
        }

        // 网格高度：内容多高就多高，但不超过上限——超出的部分交给内部滚动，
        // 而不是把整个浮层顶出屏幕（一页 100 集 = 20 行，必然超出）。
        val natural = lines * cellH + (lines - 1) * gap
        val lp = gridScroll.layoutParams as? LayoutParams
        val want = natural.coerceAtMost(maxGridHeight.coerceAtLeast(cellH))
        if (lp != null && lp.height != want) {
            lp.height = want
            gridScroll.layoutParams = lp
        }

        // 当前页那一枚芯片滚进可视区（翻页时焦点不一定在芯片上）。
        pagerScroll.post {
            val chip = chips.getOrNull(page) ?: return@post
            if (chip.width <= 0 || pagerScroll.width <= 0) return@post
            val margin = dp(8)
            when {
                chip.left - margin < pagerScroll.scrollX ->
                    pagerScroll.smoothScrollTo((chip.left - margin).coerceAtLeast(0), 0)
                chip.right + margin > pagerScroll.scrollX + pagerScroll.width ->
                    pagerScroll.smoothScrollTo(chip.right + margin - pagerScroll.width, 0)
            }
        }
    }

    /** 一个集数格：当前集高亮，点一下切集。 */
    private fun cell(i: Int): TextView {
        val on = i == current
        return TextView(context).apply {
            text = "${i + 1}"
            gravity = Gravity.CENTER
            phoneSize(if (compact) 12 else 14)
            // 集数最多三位（100 集/页）：不许换行、不许省略，否则窄抽屉里会变成「1…」。
            maxLines = 1
            ellipsize = null
            includeFontPadding = false
            setTextColor(if (on) Color.WHITE else PhoneStyle.textDim)
            background = PhoneSheet.pillBackground(
                if (on) PhoneStyle.accent else PhoneStyle.surface,
                if (on) 0xFF7CBEFF.toInt() else 0xFF3D4652.toInt(),
                dp(8).toFloat()
            )
            contentDescription = "第 ${i + 1} 集" + if (on) "，当前播放" else ""
            setOnClickListener { onPick(i) }
        }
    }

    private fun jump(target: Int) {
        if (target !in 0 until pageCount()) return
        page = target
        render()
        // 换页之后从这一页的第一行看起：停在上一次的滚动位置会让人以为翻页没生效。
        gridScroll.scrollTo(0, 0)
    }
}

/** 每行固定 5 个：全屏与非全屏一致（用户要求）。 */
private const val COLUMNS = 5
