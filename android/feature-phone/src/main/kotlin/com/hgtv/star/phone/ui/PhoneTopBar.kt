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
    private val actionGlyph = PhoneGlyph(context, PhoneGlyph.Kind.SEARCH).apply {
        tint = PhoneStyle.text
        contentDescription = "搜索"
    }
    private val actionBox = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        addView(actionGlyph, LinearLayout.LayoutParams(context.dp(16), context.dp(16)))
        setPadding(context.dp(6), context.dp(4), context.dp(6), context.dp(4))
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
        setPadding(context.dp(16), statusBar(context) + context.dp(4), context.dp(10), context.dp(2))

        mainRow.addView(mainSlot, LinearLayout.LayoutParams(0, context.dp(34), 1f))
        mainRow.addView(actionBox, LinearLayout.LayoutParams(context.dp(28), context.dp(28)))
        addView(mainRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(34)))

        addView(chipScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        chipScroll.visibility = GONE
    }

    private fun statusBar(context: Context): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else context.dp(24)
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
            phoneSize(20)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
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
                phoneSize(19)
                setPadding(0, context.dp(3), context.dp(18), context.dp(3))
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
    }

    /** 副行的小分类 chips（分类页的题材）。 */
    fun setChips(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        chipRow.removeAllViews()
        chips.clear()
        labels.forEachIndexed { index, label ->
            val chip = TextView(context).apply {
                text = label
                phoneSize(15)
                gravity = Gravity.CENTER
                setPadding(context.dp(12), context.dp(4), context.dp(12), context.dp(4))
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
