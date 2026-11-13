// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import com.hgtv.star.shared.compat.drawRoundRectCompat

/**
 * 设置页的整行开关：左边是项名，右边是一枚自绘的轨道 + 圆钮。
 *
 * 为什么不用系统 `Switch`：它的配色、尺寸、焦点反馈都是 ROM 自带的一套，
 * 与全站的柔光材质对不上（浅色主题下尤其明显）。这里用和 [TvSettingButton]
 * 完全一致的底板与焦点反馈，只是右端画一枚开 / 关的滑块。
 *
 * 文案沿用「项名  ·  当前值」：右端**不画**「>」，直接画滑块，
 * 当前值是「显示 / 隐藏」这类两个字的读数。
 */
class TvSwitchRow(
    context: Context,
    label: String,
    private var checked: Boolean,
    private val onToggle: (Boolean) -> Unit
) : TvSettingButton(context, label, ButtonSize.NORMAL, ButtonTone.SECONDARY) {
    init {
        setOnClickListener {
            checked = !checked
            onToggle(checked)
            invalidate()
        }
    }

    /** 外部改了状态（例如从别处恢复设置）时重画一次。 */
    fun setChecked(value: Boolean) {
        if (checked == value) return
        checked = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val textPaint = paint
        val right = (width - paddingRight).toFloat()
        val trackWidth = context.tvDp(52).toFloat()
        val trackHeight = context.tvDp(26).toFloat()
        val left = right - trackWidth
        val top = (height - trackHeight) / 2f
        // 轨道：关闭 = 凹槽色，打开 = 强调色（只做「小色块」，与进度条同一条规则）。
        trackPaint.color = if (checked) TvStyle.accent else TvStyle.wellDeep
        drawRoundRectCompat(canvas, left, top, right, top + trackHeight, trackHeight / 2f, trackPaint)
        // 滑块：白面圆钮，左右各留 3dp 内缩。
        val inset = context.tvDp(3).toFloat()
        val knob = trackHeight - inset * 2
        val knobLeft = if (checked) right - inset - knob else left + inset
        canvas.drawCircle(knobLeft + knob / 2f, top + trackHeight / 2f, knob / 2f, knobPaint)

        // 项名：与设置项同一条左边缘，右边让出滑块与一段间距；
        // 过长时与设置项同一套（有焦点才滚，失焦省略号，见 [drawRowName]）。
        val gap = context.tvDp(12).toFloat()
        val nameRight = left - gap
        val fm = textPaint.fontMetrics
        val baseline = height / 2f - (fm.ascent + fm.descent) / 2f
        drawRowName(canvas, paddingLeft.toFloat(), nameRight, baseline)
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TvStyle.lit }
}
