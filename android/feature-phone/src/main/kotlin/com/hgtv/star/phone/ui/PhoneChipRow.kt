// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.hgtv.star.phone.ui.PhoneSoft.applySoft
import com.hgtv.star.shared.ui.SoftStyle

/**
 * 自动换行的芯片容器（搜索历史 / 热门搜索 / 题材标签）。
 *
 * 为什么不用横向滚动的一行：电视上遥控器左右按很自然，手机上一行放不下就要横着蹭，
 * 用户看不到「还有多少」。换行铺开，一屏能看完，点哪一枚都是直接命中。
 *
 * 只做一件事：按可用宽度把子 View 一行行排下去，行高取该行最高者。
 * 不做权重、不做基线对齐——芯片高度本来就一致。
 */
class PhoneChipRow(context: Context) : ViewGroup(context) {

    /** 芯片之间的横向 / 纵向间距。 */
    var gapX: Int = context.dp(8)
        set(value) { field = value; requestLayout() }
    var gapY: Int = context.dp(8)
        set(value) { field = value; requestLayout() }

    init {
        // 芯片自带柔光带（padding），容器不再留白，否则两边会叠成一条宽缝。
        setPadding(0, 0, 0, 0)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        var x = 0
        var y = 0
        var lineHeight = 0
        var maxRight = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            measureChild(child, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val w = child.measuredWidth
            val h = child.measuredHeight
            if (x > 0 && x + w > available) {
                x = 0
                y += lineHeight + gapY
                lineHeight = 0
            }
            x += w + gapX
            maxRight = maxRight.coerceAtLeast(x - gapX)
            lineHeight = lineHeight.coerceAtLeast(h)
        }
        val contentHeight = if (childCount == 0) 0 else y + lineHeight
        setMeasuredDimension(
            resolveSize(width, widthMeasureSpec),
            resolveSize(contentHeight + paddingTop + paddingBottom, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        var x = paddingLeft
        var y = paddingTop
        var lineHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val w = child.measuredWidth
            val h = child.measuredHeight
            if (x > paddingLeft && x + w > paddingLeft + available) {
                x = paddingLeft
                y += lineHeight + gapY
                lineHeight = 0
            }
            child.layout(x, y, x + w, y + h)
            x += w + gapX
            lineHeight = lineHeight.coerceAtLeast(h)
        }
    }

    /** 清空并铺一批芯片。 */
    fun setChips(items: List<String>, onClick: (String) -> Unit) {
        removeAllViews()
        items.forEach { text -> addView(chip(text, onClick)) }
        requestLayout()
    }

    /** 一枚芯片：凹陷槽 + 一行字，宽度随文字。 */
    private fun chip(text: String, onClick: (String) -> Unit): View =
        android.widget.TextView(context).apply {
            this.text = text
            setTextColor(PhoneStyle.text)
            phoneSize(13)
            maxLines = 1
            // 单个词过长时截断，不要撑破一整行（换行容器按测量宽度排布）。
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = context.dp(220)
            setPadding(context.dp(12), context.dp(7), context.dp(12), context.dp(7))
            applySoft(SoftStyle.RAISED, PhoneStyle.surface, PhoneStyle.RADIUS_PILL, 4f)
            setOnClickListener { onClick(this.text.toString()) }
        }
}
