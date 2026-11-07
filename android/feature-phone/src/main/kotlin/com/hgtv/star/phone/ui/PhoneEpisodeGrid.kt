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
 * 列数按方向统一：竖屏 5 个、横屏 10 个。一页几行由设置页的「选集每页」决定
 * （20 / 30 / 50 / 80 / 100 集 → 2 / 3 / 5 / 8 / 10 行）。简介页与播放页浮层
 * 共用这一份实现，因此两处的翻页节奏与按钮尺寸完全一致。
 */
class PhoneEpisodeGrid(
    context: Context,
    private val count: Int,
    private val current: Int,
    /**
     * 一页多少集（设置页「选集每页」：20 / 30 / 50 / 80 / 100）。
     *
     * 列数由 [columns] 决定：竖屏 5 个、横屏 10 个；行数 = 一页集数 ÷ 列数。
     * 简介页与播放页浮层共用这一份实现，因此两处翻页的节奏完全一致。
     */
    pageSize: Int,
    /** 横屏：格子略矮、字号略小，避免底部浮层占用过高。 */
    private val compact: Boolean,
    /** 0 = 按方向自动选择：竖屏 5 列，横屏 10 列。 */
    columns: Int = 0,
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

    /** 列数：竖屏 5 个、横屏 10 个；调用方也可以显式指定。 */
    private val columns = columns.takeIf { it > 0 }?.coerceIn(1, 10)
        ?: if (context.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        ) 10 else 5
    /** 一页集数：按列数的整数倍归一，最小一行、最大 100。 */
    private val perPage = (pageSize / columns * columns).coerceIn(columns, 100)
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
    private var downAt = 0L
    private var interceptingSwipe = false
    private var swipeAllowed = false
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private val minimumFlingVelocity =
        PhonePagerMotion.minimumFlingVelocityPxPerSecond(context).toFloat()
    /** 翻页动画进行中：这一小段时间不再接新手势，避免两段动画叠在一起把位移算歪。 */
    private var animating = false
    /** 滑出动画期间已经确定、但还没渲染的目标页。连续操作时先提交它，再计算下一页。 */
    private var pendingPage = -1

    /** 一页的宽度：跟手位移与滑出距离都以它为基准。 */
    private fun pageWidth(): Float =
        width.toFloat().takeIf { it > 0f } ?: resources.displayMetrics.widthPixels.toFloat()

    /**
     * 拖动 / 翻页动画期间把网格转成**硬件层**。
     *
     * 一页最多 100 个格子（20 行 × 5 列），`translationX` 每变一次都要把整块内容重新光栅化；
     * 播放页里同时还跑着视频解码与弹幕绘制，低端机上就是明显的掉帧。
     * 挂上硬件层之后，平移只改一次纹理的变换矩阵，拖动期间不再逐帧重绘。
     * 动画一结束立刻摘掉（见 [endDragLayer]）：长期挂着会占一块和网格等大的显存。
     */
    private fun beginDragLayer() {
        if (gridScroll.layerType != View.LAYER_TYPE_HARDWARE) {
            gridScroll.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
    }

    private fun endDragLayer() {
        if (gridScroll.layerType != View.LAYER_TYPE_NONE) {
            gridScroll.setLayerType(View.LAYER_TYPE_NONE, null)
        }
    }

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
                // 上一段翻页动画还没走完就再按下来：立刻收掉它，让新手势接管。
                // 否则用户连着滑两下，第二下会被动画挡掉，读起来就是「卡了一下」。
                if (animating) {
                    gridScroll.animate().cancel()
                    if (pendingPage >= 0) {
                        page = pendingPage
                        pendingPage = -1
                        render()
                        gridScroll.scrollTo(0, 0)
                    }
                    gridScroll.translationX = 0f
                    animating = false
                    endDragLayer()
                }
                downX = ev.x
                downY = ev.y
                downAt = ev.eventTime
                interceptingSwipe = false
                // 分页芯片那一行本身要能横向滚动，左右翻页只从网格区域起手。
                swipeAllowed = ev.y >= gridScroll.top
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                // 与推荐页横向 pager 一致：越过系统 touch slop 后按 1:1 主轴锁定。
                if (swipeAllowed && !interceptingSwipe && kotlin.math.abs(dx) > slop &&
                    kotlin.math.abs(dx) > kotlin.math.abs(dy)
                ) {
                    interceptingSwipe = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    // 从这里开始整段手势都在平移网格：先挂硬件层，后面每一帧就只剩变换。
                    beginDragLayer()
                }
            }
        }
        return interceptingSwipe || super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (!interceptingSwipe) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_MOVE -> {
                applyDrag(ev.x - downX)
                return true
            }
            android.view.MotionEvent.ACTION_UP -> {
                val dx = ev.x - downX
                val dt = (ev.eventTime - downAt).coerceAtLeast(1L)
                parent?.requestDisallowInterceptTouchEvent(false)
                interceptingSwipe = false
                swipeAllowed = false
                settleDrag(dx, dx / dt)
                return true
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                interceptingSwipe = false
                swipeAllowed = false
                animateBack()
                return true
            }
        }
        return true
    }

    /** 手指拖动时只有网格那一块跟手；到页边界后不再位移，与推荐页关闭 overScroll 的行为一致。 */
    private fun applyDrag(dx: Float) {
        val w = pageWidth()
        val hasNext = page + 1 < pageCount()
        val hasPrev = page > 0
        val blocked = (dx < 0 && !hasNext) || (dx > 0 && !hasPrev)
        gridScroll.translationX = if (blocked) 0f else dx.coerceIn(-w, w)
    }

    /**
     * 松手结算：过阈值就翻页，否则弹回。
     *
     * 翻页分两段——当前页沿手势方向滑出，换页后新页从反方向滑入。看起来是「翻过去了」，
     * 而不是原来那种「松手瞬间换一块内容」。快速轻扫（速度够）不必滑过半屏也能翻。
     */
    private fun settleDrag(dx: Float, velocity: Float) {
        val w = pageWidth()
        val velocityPxPerSecond = velocity * 1_000f
        val threshold = w * PhonePagerMotion.SNAP_DISTANCE_RATIO
        val dir = when {
            dx <= -threshold || velocityPxPerSecond <= -minimumFlingVelocity -> 1
            dx >= threshold || velocityPxPerSecond >= minimumFlingVelocity -> -1
            else -> 0
        }
        if (dir != 0 && page + dir in 0 until pageCount()) {
            val nextPage = page + dir
            pendingPage = nextPage
            animating = true
            val target = -dir * w
            val duration = PhonePagerMotion.duration(
                distancePx = kotlin.math.abs(target - gridScroll.translationX),
                viewportPx = w.toInt(),
            )
            gridScroll.animate().translationX(target).setDuration(duration)
                .setInterpolator(PhonePagerMotion.interpolator).withEndAction {
                page = nextPage
                pendingPage = -1
                render()
                gridScroll.scrollTo(0, 0)
                gridScroll.translationX = dir * w
                val enterDuration = PhonePagerMotion.duration(w, w.toInt())
                gridScroll.animate().translationX(0f).setDuration(enterDuration)
                    .setInterpolator(PhonePagerMotion.interpolator)
                    .withEndAction {
                        animating = false
                        endDragLayer()
                    }.start()
            }.start()
        } else {
            animateBack()
        }
    }

    /** 没到阈值 / 手势取消：滑回原位。 */
    private fun animateBack() {
        if (gridScroll.translationX == 0f) { animating = false; endDragLayer(); return }
        animating = true
        val duration = PhonePagerMotion.duration(
            distancePx = kotlin.math.abs(gridScroll.translationX),
            viewportPx = pageWidth().toInt(),
        )
        gridScroll.animate().translationX(0f).setDuration(duration)
            .setInterpolator(PhonePagerMotion.interpolator)
            .withEndAction {
                animating = false
                endDragLayer()
            }.start()
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
        val cellH = dp(if (columns >= 10) 24 else if (compact) 26 else 28)
        // 10 列时把格距再收一档，横屏简介页和播放浮层都能完整铺开。
        val gap = dp(if (columns >= 10) 2 else if (compact) 3 else 4)
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
            phoneSize(if (columns >= 10) 10 else if (compact) 12 else 14)
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

    /**
     * 翻到第 [target] 页（点分页芯片）。
     *
     * 点芯片不再瞬间换内容：新页从翻页方向滑进来（下一页从右侧进、上一页从左侧进），
     * 与手势翻页同一套观感，只是省掉「当前页滑出」那一段——点芯片本来就知道要去哪。
     */
    private fun jump(target: Int) {
        if (target !in 0 until pageCount() || target == page) return
        if (animating) {
            gridScroll.animate().cancel()
            if (pendingPage >= 0) {
                page = pendingPage
                pendingPage = -1
                render()
                gridScroll.scrollTo(0, 0)
            }
            gridScroll.translationX = 0f
            animating = false
            endDragLayer()
            if (target == page) return
        }
        val dir = if (target > page) 1 else -1
        page = target
        render()
        // 换页之后从这一页的第一行看起：停在上一次的滚动位置会让人以为翻页没生效。
        gridScroll.scrollTo(0, 0)
        gridScroll.translationX = dir * pageWidth() * PhonePagerMotion.SNAP_DISTANCE_RATIO
        animating = true
        beginDragLayer()
        val duration = PhonePagerMotion.duration(
            distancePx = kotlin.math.abs(gridScroll.translationX),
            viewportPx = pageWidth().toInt(),
        )
        gridScroll.animate().translationX(0f).setDuration(duration)
            .setInterpolator(PhonePagerMotion.interpolator)
            .withEndAction {
                animating = false
                endDragLayer()
            }.start()
    }
}
