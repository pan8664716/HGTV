// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.hgtv.star.phone.PhoneServices
import com.hgtv.star.phone.ui.PhoneSoft.applySoft
import com.hgtv.star.phone.ui.PhoneSoft.restyleSoft
import com.hgtv.star.shared.ui.SoftStyle

/**
 * 手机底部导航：整条柔光底板 + 四枚独立圆形按钮。
 *
 * 视觉参考 ShadeCraft 的 Elevated Tab：未选中项落在栏内，当前项上浮半条栏高、
 * 放大并切换为强调色。业务入口仍是推荐 / 分类 / 榜单 / 我的，切页与双击回顶
 * 继续由 [com.hgtv.star.phone.PhoneMainActivity] 统一处理。
 */
class PhoneTabBar(context: Context, private val compact: Boolean = false) : LinearLayout(context) {

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
    private val reduceMotion = runCatching {
        PhoneServices.library(context).takeIf { it.isLoaded }?.reduceAnim == true
    }.getOrDefault(false)

    private val plate = Path()
    private val plateRect = RectF()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val topShadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val argb = ArgbEvaluator()

    var onSelect: ((Int) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        setPadding(context.dp(PhoneDimen.TAB_INSET), 0, context.dp(PhoneDimen.TAB_INSET), 0)
        clipChildren = false
        clipToPadding = false
        setWillNotDraw(false)
        // 栏内空白也要吃掉点击，避免穿透到下面的视频 / 列表。
        isClickable = true
        setOnClickListener { }

        items.forEachIndexed { index, item ->
            val cell = Cell(context, item, compact, reduceMotion)
            cell.setOnClickListener {
                select(index)
                onSelect?.invoke(index)
            }
            cells += cell
            addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        applySelected(0, animate = false)
    }

    fun select(index: Int) = applySelected(index, animate = true)

    /** 宿主切页后同步选中态，不重复播放入场动画。 */
    fun setSelected(index: Int) = applySelected(index, animate = false)

    private fun applySelected(index: Int, animate: Boolean) {
        if (index !in cells.indices || index == selected) return
        selected = index
        cells.forEachIndexed { i, cell -> cell.applySelected(i == index, animate) }
    }

    override fun onDraw(canvas: Canvas) {
        val radius = dp(if (compact) 12 else 18).toFloat()
        plateRect.set(0f, 0f, width.toFloat(), height.toFloat() + radius)
        plate.reset()
        plate.addRoundRect(
            plateRect,
            floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f),
            Path.Direction.CW,
        )

        fillPaint.color = PhoneStyle.surface
        canvas.drawPath(plate, fillPaint)

        // 底板与内容之间用一条深色渐隐 + 顶部高光分开，替代悬浮卡片的硬阴影。
        val shadeHeight = dp(if (compact) 2 else 4).coerceAtLeast(1)
        topShadePaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            shadeHeight.toFloat(),
            PhoneStyle.shadowDark,
            PhoneStyle.shadowDark and 0x00FFFFFF,
            Shader.TileMode.CLAMP,
        )
        canvas.save()
        canvas.clipPath(plate)
        canvas.drawRect(0f, 0f, width.toFloat(), shadeHeight.toFloat(), topShadePaint)
        canvas.restore()

        edgePaint.color = PhoneStyle.shadowLight
        edgePaint.strokeWidth = 1f
        canvas.drawLine(radius, .5f, width - radius, .5f, edgePaint)
    }

    private inner class Cell(
        context: Context,
        private val item: Item,
        compact: Boolean,
        private val reduceMotion: Boolean,
    ) : FrameLayout(context) {

        private val barDp = if (compact) PhoneColor.TAB_BAR_LANDSCAPE_DP else PhoneColor.TAB_BAR_DP
        private val circleDp = barDp * .88f
        private val circleSize = context.dp(circleDp)
        private val iconSize = context.dp(barDp * .42f)
        private val lift = context.dp(barDp / 2f).toFloat()
        private val idleScale = if (compact) .82f else .84f
        private val iconView = Glyph(context, item.icon)
        private val button = FrameLayout(context)
        private var fill = PhoneStyle.surface
        private var tint = PhoneStyle.textFaint
        private var animator: ValueAnimator? = null

        init {
            isClickable = true
            isFocusable = true
            contentDescription = item.label
            clipChildren = false
            clipToPadding = false

            button.applySoft(
                style = SoftStyle.RAISED,
                fill = fill,
                radiusDp = circleDp / 2f,
                bandDp = if (compact) 2.2f else 2.8f,
                depth = 1f,
            )
            button.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            button.addView(
                iconView,
                LayoutParams(iconSize, iconSize, Gravity.CENTER),
            )
            addView(
                button,
                LayoutParams(circleSize, circleSize, Gravity.CENTER),
            )
            iconView.tint = tint
            button.scaleX = idleScale
            button.scaleY = idleScale
        }

        fun applySelected(selected: Boolean, animate: Boolean) {
            isSelected = selected
            val targetFill = if (selected) PhoneStyle.surfaceHigh else PhoneStyle.surface
            val targetTint = if (selected) PhoneStyle.accent else PhoneStyle.textFaint
            val targetScale = if (selected) 1f else idleScale
            val targetY = if (selected) -lift else 0f

            animator?.cancel()
            if (!animate || reduceMotion || !isAttachedToWindow) {
                setFrame(targetFill, targetTint, targetScale, targetY)
                return
            }

            val startFill = fill
            val startTint = tint
            val startScale = button.scaleX
            val startY = button.translationY
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 300L
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    val t = it.animatedValue as Float
                    setFrame(
                        argb.evaluate(t, startFill, targetFill) as Int,
                        argb.evaluate(t, startTint, targetTint) as Int,
                        startScale + (targetScale - startScale) * t,
                        startY + (targetY - startY) * t,
                    )
                }
                start()
            }
        }

        private fun setFrame(nextFill: Int, nextTint: Int, scale: Float, translation: Float) {
            fill = nextFill
            tint = nextTint
            button.restyleSoft(fill = nextFill)
            iconView.tint = nextTint
            button.scaleX = scale
            button.scaleY = scale
            button.translationY = translation
        }

        override fun onDetachedFromWindow() {
            animator?.cancel()
            super.onDetachedFromWindow()
        }
    }

    private class Glyph(context: Context, private val icon: Icon) : View(context) {
        var tint: Int = PhoneStyle.textFaint
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
