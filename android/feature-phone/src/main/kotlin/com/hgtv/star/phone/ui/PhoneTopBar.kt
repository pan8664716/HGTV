// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 手机端统一的页面顶部栏。
 *
 * 四个主页面（推荐 / 分类 / 榜单 / 我的）都是同一套结构：
 *
 * ```
 * 状态栏留白
 * ├── 主行：[标题 或 大分类 tab]                    右侧：动作按钮（默认搜索）
 * └── 副行（可选）：小分类 chips
 * ```
 *
 * 动作按钮固定在右上角，默认是搜索；「我的」页把它换成设置。与 TV 端导航栏里
 * 对应的圆形入口是同一个角色，只是位置从顶部导航栏挪到了页面右上角。
 *
 * 主行用 [setTitle] / [setTabs]，副行用 [setChips]。两者互不干扰：
 * 分类页要「大类 tab + 小类 chips」两层，推荐页与榜单页只有一层。
 */
class PhoneTopBar(context: Context) : LinearLayout(context) {

    private val compact = context.resources.configuration.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE

    /** 默认搜索动作；调用 [setAction] 后由自定义动作接管。 */
    var onSearch: (() -> Unit)? = null

    private var customAction: (() -> Unit)? = null

    private val mainRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val mainSlot = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    /**
     * tab 行可横向滑动。
     *
     * 字号调到最大档之后，四五个 tab 的总宽会超过屏宽；直接塞进 `weight=1` 的容器里，
     * `LinearLayout` 会把每个 TextView 压窄，中文就按字换行——用户看到的是「两个字竖排」。
     * 套一层横向滚动之后，每个 tab 保持自己的宽度，挤不下就滑。
     */
    private val tabScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(mainSlot, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }
    private val actionGlyph = PhoneGlyph(context, PhoneGlyph.Kind.SEARCH).apply {
        tint = PhoneStyle.text
        contentDescription = "搜索"
    }
    private val actionBox = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, 0)
        addView(actionGlyph, LinearLayout.LayoutParams(context.dp(16), context.dp(16)))
        contentDescription = "搜索"
        setOnClickListener { (customAction ?: onSearch)?.invoke() }
    }
    private val chipRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val chipScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(chipRow)
    }

    private val mainCells = ArrayList<TextView>()
    private val chips = ArrayList<TextView>()

    init {
        orientation = VERTICAL
        val actionSize = context.dp(if (compact) 24 else 28)
        // 搜索 / 设置只保留图标本身，不加圆形底色；图标容器仍固定为正方形，
        // 避免父行高度变化时把设置齿轮拉成椭圆。
        actionBox.background = null
        setPadding(
            context.dp(if (compact) 12 else 16),
            if (compact) 0 else statusBar(context) + context.dp(4),
            context.dp(if (compact) 8 else 10),
            if (compact) 0 else context.dp(2),
        )

        mainRow.addView(tabScroll, LinearLayout.LayoutParams(0, context.dp(if (compact) 24 else 34), 1f))
        mainRow.addView(actionBox, LinearLayout.LayoutParams(actionSize, actionSize))
        addView(mainRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, context.dp(if (compact) 24 else 34)
        ))

        addView(chipScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        chipScroll.visibility = GONE
    }

    private fun statusBar(context: Context): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else context.dp(24)
    }

    /**
     * 把底部的 2dp 留白收掉。
     *
     * 分类页的筛选行（子分类）紧贴在体裁 tab（主分类）下面，两段留白叠在一起
     * （顶栏底部 2dp + 网格顶部 4dp + tab 行的居中留白）就是用户读到的「空隙太大」。
     * 其它页面没有紧贴的内容，保持原样。
     */
    fun tightenBottom() {
        if (paddingBottom == 0) return
        setPadding(paddingLeft, paddingTop, paddingRight, 0)
    }

    /**
     * 沉浸式：状态栏不显示时，顶栏不再为它留白。
     *
     * 推荐页整屏都是视频、状态栏是藏起来的（见 `PhoneMainActivity.applyStatusBarFor`），
     * 不收这段留白的话，体裁标签与右上角搜索会凭空下移一条状态栏的高度，
     * 视频明明已经铺到顶部，控件却还悬在下面。
     */
    fun setImmersive(on: Boolean) {
        val top = if (compact) 0 else if (on) context.dp(10) else statusBar(context) + context.dp(4)
        if (paddingTop == top) return
        setPadding(paddingLeft, top, paddingRight, paddingBottom)
    }

    /** 换掉右上角动作：图标、无障碍名称与点击行为一起换。 */
    fun setAction(kind: PhoneGlyph.Kind, description: String, onClick: () -> Unit) {
        actionGlyph.kind = kind
        actionGlyph.contentDescription = description
        actionBox.contentDescription = description
        customAction = onClick
    }

    /** 单行标题。 */
    fun setTitle(text: String) {
        mainSlot.removeAllViews()
        mainCells.clear()
        mainSlot.addView(TextView(context).apply {
            this.text = text
            setTextColor(PhoneStyle.text)
            phoneSize(if (compact) 16 else 20)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            // 单行 + 省略：标题再长也不许换行（换行会把 34dp 的行撑高）。
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
    }

    /**
     * 用自定义入口替代左侧标题，例如「我的」页顶部的账号头像。
     *
     * [rowHeight] 非空时同时调整主行高度；「我的」页的账号区比普通标题高，
     * 不调整会把头像和副标题裁在 34dp 的主行里。其它页面仍使用默认高度。
     */
    fun setLeadingView(view: View, rowHeight: Int? = null) {
        mainSlot.removeAllViews()
        mainCells.clear()
        mainSlot.addView(view, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        if (rowHeight != null) {
            val height = rowHeight.coerceAtLeast(0)
            (mainRow.layoutParams as? LinearLayout.LayoutParams)?.height = height
            (tabScroll.layoutParams as? LinearLayout.LayoutParams)?.height = height
            mainRow.requestLayout()
            tabScroll.requestLayout()
        }
    }

    /**
     * 主行的大分类 tab（推荐页的 漫剧/真人/AI，榜单页的四个榜单，分类页的三个大类）。
     *
     * 选中项用主文字色 + 加粗，其余用次级色——与 TV 端顶部芯片的选中规则一致。
     */
    fun setTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        mainSlot.removeAllViews()
        mainCells.clear()
        labels.forEachIndexed { index, label ->
            val cell = TextView(context).apply {
                text = label
                phoneSize(if (compact) 16 else 19)
                setPadding(0, context.dp(if (compact) 0 else 3),
                    context.dp(if (compact) 12 else 18), context.dp(if (compact) 0 else 3))
                // 绝不换行：字号最大档时一行放不下就由外层 `tabScroll` 横向滑动，
                // 而不是把「分类」压成上下两个字。
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setOnClickListener { onSelect(index) }
            }
            mainCells += cell
            mainSlot.addView(cell, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        applyTabSelection(selected)
    }

    fun applyTabSelection(selected: Int) {
        mainCells.forEachIndexed { index, cell ->
            val on = index == selected
            cell.setTextColor(if (on) PhoneStyle.text else PhoneStyle.textFaint)
            cell.setTypeface(
                cell.typeface,
                if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )
        }
        // 选中项滚进可视区：字号大时整排 tab 超出屏宽，切换后不滚的话
        // 用户会看到「点了没反应」（其实选中项在屏幕外）。
        tabScroll.post {
            val cell = mainCells.getOrNull(selected) ?: return@post
            if (cell.width <= 0 || tabScroll.width <= 0) return@post
            val margin = context.dp(12)
            when {
                cell.left - margin < tabScroll.scrollX ->
                    tabScroll.smoothScrollTo((cell.left - margin).coerceAtLeast(0), 0)
                cell.right + margin > tabScroll.scrollX + tabScroll.width ->
                    tabScroll.smoothScrollTo(cell.right + margin - tabScroll.width, 0)
            }
        }
    }

    /** 副行的小分类 chips（分类页的题材）。 */
    fun setChips(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        chipRow.removeAllViews()
        chips.clear()
        labels.forEachIndexed { index, label ->
            val chip = TextView(context).apply {
                text = label
                phoneSize(if (compact) 12 else 15)
                gravity = Gravity.CENTER
                setPadding(context.dp(if (compact) 8 else 12), context.dp(if (compact) 2 else 4),
                    context.dp(if (compact) 8 else 12), context.dp(if (compact) 2 else 4))
                setOnClickListener { onSelect(index) }
            }
            chips += chip
            chipRow.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = context.dp(8) })
        }
        chipScroll.visibility = VISIBLE
        applyChipSelection(selected)
    }

    fun applyChipSelection(selected: Int) {
        chips.forEachIndexed { index, chip ->
            val on = index == selected
            chip.setTextColor(if (on) PhoneStyle.text else PhoneStyle.textDim)
            chip.background = if (on) {
                roundedBackground(PhoneStyle.surfaceHigh, context.dp(14).toFloat())
            } else null
        }
    }

    fun hideChips() {
        chipScroll.visibility = GONE
    }

    /** 压在视频上时用透明底 + 顶部渐变（推荐页）。 */
    fun makeOverlay() {
        background = topScrim(170)
    }
}
