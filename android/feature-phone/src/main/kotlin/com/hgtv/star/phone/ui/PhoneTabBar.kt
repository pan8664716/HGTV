// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.hgtv.star.phone.ui.PhoneSoft.applySoft
import com.hgtv.star.shared.ui.SoftStyle

/**
 * 手机底部导航：满宽直角长条 + 四枚等尺寸圆形入口。
 *
 * 版式参考 ShadeCraft 的 Elevated Tab：不显示文字，不做选中项位移；
 * 未选中入口凸起，当前入口按进去并使用强调色图标。推荐 / 分类 / 榜单 / 我的四个业务入口保持不变，
 * 切页与双击回顶继续由 [com.hgtv.star.phone.PhoneMainActivity] 统一处理。
 */
class PhoneTabBar(context: Context, private val compact: Boolean = false) : FrameLayout(context) {

    enum class Icon { HOME, GRID, RANK, MINE }

    private class Item(val label: String, val icon: Icon)

    private val items = listOf(
        Item("推荐", Icon.HOME),
        Item("分类", Icon.GRID),
        Item("榜单", Icon.RANK),
        Item("我的", Icon.MINE),
    )
    private val cells = ArrayList<Cell>()
    private var selected = -1
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    var onSelect: ((Int) -> Unit)? = null

    init {
        setBackgroundColor(PhoneStyle.surface)
        setWillNotDraw(false)
        isClickable = true
        setOnClickListener { }

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(
            panel,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )

        items.forEachIndexed { index, item ->
            val cell = Cell(context, item, compact)
            cell.setOnClickListener {
                select(index)
                onSelect?.invoke(index)
            }
            cells += cell
            panel.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        applySelected(0)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 与参考实现一致：长条本身保持平直，只用一条顶部高光交代材质边界。
        edgePaint.color = PhoneStyle.shadowLight
        edgePaint.strokeWidth = 1f
        canvas.drawLine(0f, .5f, width.toFloat(), .5f, edgePaint)
    }

    fun select(index: Int) = applySelected(index)

    /** 宿主切页后同步选中态，不触发业务回调。 */
    fun setSelected(index: Int) = applySelected(index)

    private fun applySelected(index: Int) {
        if (index !in cells.indices || index == selected) return
        selected = index
        cells.forEachIndexed { i, cell -> cell.applySelected(i == index) }
    }

    private inner class Cell(
        context: Context,
        private val item: Item,
        compact: Boolean,
    ) : FrameLayout(context) {

        private val barDp = if (compact) PhoneColor.TAB_BAR_LANDSCAPE_DP else PhoneColor.TAB_BAR_DP
        private val circleDp = barDp * if (compact) .80f else .82f
        private val circleSize = context.dp(circleDp)
        private val iconSize = context.dp(barDp * if (compact) .38f else .40f)
        private val softBandDp = if (compact) 2.2f else 3.2f
        private val iconView = Glyph(context, item.icon)
        private val circle = FrameLayout(context)

        init {
            isClickable = true
            isFocusable = true
            contentDescription = item.label
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> styleCircle(selected = true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> styleCircle(isSelected)
                }
                false
            }

            circle.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            circle.addView(
                iconView,
                LayoutParams(iconSize, iconSize, Gravity.CENTER),
            )
            addView(
                circle,
                LayoutParams(circleSize, circleSize, Gravity.CENTER),
            )
            styleCircle(selected = false)
            iconView.tint = PhoneStyle.textDim
        }

        fun applySelected(selected: Boolean) {
            isSelected = selected
            styleCircle(selected)
            iconView.tint = if (selected) PhoneStyle.accent else PhoneStyle.textDim
        }

        private fun styleCircle(selected: Boolean) {
            circle.background = null
            circle.setPadding(0, 0, 0, 0)
            circle.applySoft(
                style = if (selected) SoftStyle.INSET else SoftStyle.RAISED,
                fill = if (selected) PhoneStyle.well else PhoneStyle.surfaceHigh,
                radiusDp = circleDp / 2f,
                bandDp = softBandDp,
                depth = 1f,
            )
        }
    }

    private class Glyph(context: Context, private val icon: Icon) : View(context) {
        var tint: Int = PhoneStyle.textDim
            set(value) {
                field = value
                invalidate()
            }

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
                    val column = w / 3f
                    val base = h * .84f
                    val heights = floatArrayOf(h * .34f, h * .56f, h * .24f)
                    for (i in 0..2) {
                        rect.set(
                            column * i + column * .18f,
                            base - heights[i],
                            column * i + column * .82f,
                            base,
                        )
                        canvas.drawRoundRect(rect, w * .04f, w * .04f, fill)
                    }
                }
                Icon.GRID -> {
                    val pad = w * .18f
                    val cell = (w - pad * 2) / 2f
                    val gap = cell * .16f
                    for (row in 0..1) {
                        for (column in 0..1) {
                            rect.set(
                                pad + column * (cell + gap),
                                pad + row * (cell + gap),
                                pad + column * (cell + gap) + cell - gap,
                                pad + row * (cell + gap) + cell - gap,
                            )
                            canvas.drawRoundRect(rect, gap, gap, fill)
                        }
                    }
                }
                Icon.MINE -> {
                    canvas.drawCircle(w * .5f, h * .33f, w * .15f, fill)
                    path.reset()
                    rect.set(w * .22f, h * .52f, w * .78f, h)
                    path.addArc(rect, 180f, 180f)
                    canvas.drawPath(path, stroke)
                }
            }
        }
    }
}
