// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 底部导航栏：**贴底固定**，不做悬浮胶囊。
 *
 * 新版式与官方那种「浮在内容上的圆角胶囊」相反——新拟态的层次靠材质本身表达，
 * 悬浮块会让内容区看起来被切掉一块；贴底之后整条就是一块抬起的底板，
 * 与页面的石墨底色只差一档明度，选中项再「按进去」一格。
 *
 * 结构：
 * ```
 * LinearLayout（整条，surface 色 + 顶部 1px 高光）
 * ├── 格 1（图标 + 文字，选中红）
 * ├── 格 2
 * └── 格 3
 * └── paddingBottom = 系统导航条（三键导航时不被遮住）
 * ```
 */
class PhoneTabBar(context: Context) : LinearLayout(context) {

    enum class Icon { HOME, GRID, RANK, MINE }

    private class Item(val label: String, val icon: Icon)

    /** 与 TV 端顶部导航同一套入口：推荐 / 分类 / 榜单 / 我的（设置收进「我的」页）。 */
    private val items = listOf(
        Item("推荐", Icon.HOME),
        Item("分类", Icon.GRID),
        Item("榜单", Icon.RANK),
        Item("我的", Icon.MINE),
    )
    private val cells = ArrayList<Cell>()
    private var selected = 0

    var onSelect: ((Int) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        setBackgroundColor(PhoneStyle.surface)
        // 整条自己也是可点的：点到格子之间的空隙时事件在这里被吃掉，
        // 不会穿透到底下的播放页（之前点底部会误触「选集」就是这个原因）。
        isClickable = true
        setOnClickListener { }
        // 顶部一条 1px 高光：新拟态的「抬起」在贴底面板上只能靠这条线表达，
        // 否则 50dp 高的条和页面底色差 3%，暗处几乎看不出边界。
        // **不留系统导航条的空白**：官方 App 的底栏也是贴到屏幕最底（`[14,1833]–[1066,1920]`）。
        // 之前按 navigation_bar_height 预留 48dp，看起来就是「文字离底边一大截」。
        setPadding(0, context.dp(1), 0, context.dp(1))

        items.forEachIndexed { index, item ->
            val cell = Cell(context, item)
            cell.setOnClickListener {
                // 不判断「是不是已经选中」：选中态由宿主（PhoneMainActivity.show）统一驱动，
                // 这里再判一次会和真实显示的页面对不上——用户点了没反应，就是这个原因。
                select(index)
                onSelect?.invoke(index)
            }
            cells += cell
            addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        applySelected(0)
    }

    fun select(index: Int) {
        applySelected(index)
    }

    /** 只改视觉选中态，不回调（宿主切页后同步用，避免递归）。 */
    fun setSelected(index: Int) = applySelected(index)

    private fun applySelected(index: Int) {
        selected = index
        cells.forEachIndexed { i, cell -> cell.applySelected(i == index) }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 顶部高光线
        highlight.color = PhoneStyle.shadowLight
        canvas.drawRect(0f, 0f, width.toFloat(), 1f, highlight)
    }

    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG)

    private inner class Cell(context: Context, item: Item) : LinearLayout(context) {
        private val iconView = Glyph(context, item.icon)
        private val label = TextView(context).apply {
            text = item.label
            // 底栏是**框架**不是内容：字号跟正文（13）走，不再跟 14 的副标题走。
            // 16dp 图标 + 13 号字（×0.5 后约 20px） = 图标占格 40%，视觉重心在图标上，
            // 字只做注脚——之前 22dp 图标 + 14 号字两者一样重，整条看起来又高又挤。
            phoneSize(13)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            addView(iconView, LinearLayout.LayoutParams(context.dp(16), context.dp(16)))
            addView(label, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dp(2) })
        }

        fun applySelected(selected: Boolean) {
            val color = if (selected) PhoneStyle.accent else PhoneStyle.textFaint
            iconView.tint = color
            label.setTextColor(color)
            label.setTypeface(
                label.typeface,
                if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )
        }
    }

    private class Glyph(context: Context, private val icon: Icon) : View(context) {
        var tint: Int = PhoneStyle.textFaint
            set(value) { field = value; invalidate() }

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = context.dp(1.3f).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            fill.color = tint
            stroke.color = tint
            val w = width.toFloat()
            val h = height.toFloat()
            when (icon) {
                Icon.HOME -> {
                    path.reset()
                    path.moveTo(w * .18f, h * .48f)
                    path.lineTo(w * .5f, h * .2f)
                    path.lineTo(w * .82f, h * .48f)
                    canvas.drawPath(path, stroke)
                    rect.set(w * .26f, h * .46f, w * .74f, h * .82f)
                    canvas.drawRoundRect(rect, w * .06f, w * .06f, stroke)
                }
                Icon.RANK -> {
                    // 榜单：三根高低柱
                    val w3 = w / 3f
                    val base = h * .84f
                    val heights = floatArrayOf(h * .34f, h * .56f, h * .24f)
                    for (i in 0..2) {
                        rect.set(
                            w3 * i + w3 * .18f, base - heights[i],
                            w3 * i + w3 * .82f, base
                        )
                        canvas.drawRoundRect(rect, w * .04f, w * .04f, fill)
                    }
                }
                Icon.GRID -> {
                    val pad = w * .18f
                    val cell = (w - pad * 2) / 2f
                    val gap = cell * .16f
                    for (r in 0..1) for (c in 0..1) {
                        rect.set(
                            pad + c * (cell + gap), pad + r * (cell + gap),
                            pad + c * (cell + gap) + cell - gap, pad + r * (cell + gap) + cell - gap
                        )
                        canvas.drawRoundRect(rect, gap, gap, fill)
                    }
                }
                Icon.MINE -> {
                    canvas.drawCircle(w * .5f, h * .33f, w * .15f, fill)
                    path.reset()
                    path.addArc(w * .22f, h * .52f, w * .78f, h * 1.0f, 180f, 180f)
                    canvas.drawPath(path, stroke)
                }
            }
        }
    }
}
