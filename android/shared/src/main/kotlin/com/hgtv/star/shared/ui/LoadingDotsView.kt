// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.ceil
import kotlin.math.abs

/**
 * 全站统一的三点加载动画。
 *
 * 三个点固定为 64 × 18dp，与启动页完全一致；需要说明当前阶段时，用 [label]
 * 把一行短文案直接放在动画下方，TV / Phone 不再各自拼 ProgressBar 或状态字。
 */
class LoadingDotsView(context: Context) : View(context) {
    private val density=resources.displayMetrics.density
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=0xFF4DA3FF.toInt() }
    private val labelPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=0xCCFFFFFF.toInt()
        textAlign=Paint.Align.CENTER
        textSize=14f*resources.displayMetrics.scaledDensity
    }
    private var labelText=""
    private var progress=0f
    private val animator=ValueAnimator.ofFloat(0f,1f).apply {
        duration=900L
        repeatCount=ValueAnimator.INFINITE
        interpolator=LinearInterpolator()
        addUpdateListener {
            progress=(it.animatedValue as Float)%1f
            invalidate()
        }
    }

    init {
        contentDescription="正在加载"
    }

    /** 加载阶段文案；为空时保持原来的 64 × 18dp 纯三点动画。 */
    var label: CharSequence
        get()=labelText
        set(value) {
            val next=value.toString()
            if(next==labelText) return
            labelText=next
            contentDescription=next.ifBlank { "正在加载" }
            requestLayout()
            invalidate()
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.cancel()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val dotsWidth=dp(WIDTH_DP)
        val dotsHeight=dp(HEIGHT_DP)
        val labelHeight=if(labelText.isEmpty()) {
            0f
        } else {
            dp(6f)+(labelPaint.fontMetrics.bottom-labelPaint.fontMetrics.top)
        }
        val labelWidth=if(labelText.isEmpty()) {
            0f
        } else {
            ceil(labelPaint.measureText(labelText)).toFloat()+dp(16f)
        }
        setMeasuredDimension(
            maxOf(dotsWidth,labelWidth).toInt(),
            (dotsHeight+labelHeight).toInt(),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val gap=dp(12f)
        val dotsHeight=dp(HEIGHT_DP)
        val centerY=if(labelText.isEmpty()) height/2f else dotsHeight/2f
        val startX=width/2f-gap
        repeat(3) { index ->
            val phase=(progress+index/3f)%1f
            val pulse=1f-abs(phase*2f-1f)
            paint.alpha=(90+pulse*165).toInt().coerceIn(0,255)
            canvas.drawCircle(
                startX+gap*index,
                centerY,
                dp(3.2f)*(0.82f+0.18f*pulse),
                paint,
            )
        }
        if(labelText.isNotEmpty()) {
            val baseline=dotsHeight+dp(6f)-labelPaint.fontMetrics.top
            canvas.drawText(labelText,width/2f,baseline,labelPaint)
        }
    }

    private fun dp(value: Float): Float = value*density

    companion object {
        const val WIDTH_DP=64f
        const val HEIGHT_DP=18f
    }
}
