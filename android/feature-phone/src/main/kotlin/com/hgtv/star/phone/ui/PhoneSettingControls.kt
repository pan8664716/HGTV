// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.hgtv.star.core.DanmakuStyle
import com.hgtv.star.phone.ui.PhoneSoft.applySoft
import com.hgtv.star.shared.ui.SoftStyle
import com.hgtv.star.shared.ui.SliderDrag

/**
 * 设置页整行开关：左侧项名，右侧一枚自绘轨道。
 *
 * 与电视端 `TvSwitchRow` 使用同一种行高、胶囊底板和开关比例；手机上按触屏点击整行切换。
 */
class PhoneSwitchRow(
    context: Context,
    private val label: String,
    checked: Boolean,
    private val onToggle: (Boolean) -> Unit,
) : View(context) {

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG)
    private var checkedValue = checked

    init {
        isClickable = true
        isFocusable = true
        setPadding(context.dp(14), 0, context.dp(12), 0)
        applySoft(SoftStyle.RAISED, PhoneStyle.surface, PhoneStyle.RADIUS_PILL, 4f)
        paint.textSize = context.sp(15)
        track.color = PhoneStyle.wellDeep
        knob.color = PhoneStyle.lit
        describe()
        setOnClickListener {
            checkedValue = !checkedValue
            describe()
            invalidate()
            onToggle(checkedValue)
        }
    }

    fun setChecked(value: Boolean) {
        if (checkedValue == value) return
        checkedValue = value
        describe()
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> alpha = .82f
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> alpha = 1f
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val right = (width - paddingRight).toFloat()
        val trackWidth = context.dp(36).toFloat()
        val trackHeight = context.dp(19).toFloat()
        val left = right - trackWidth
        val top = (height - trackHeight) / 2f
        val bottom = top + trackHeight

        track.color = if (checkedValue) PhoneStyle.accent else PhoneStyle.wellDeep
        canvas.drawRoundRect(left, top, right, bottom, trackHeight / 2f, trackHeight / 2f, track)
        val inset = context.dp(2.5f).toFloat()
        val diameter = trackHeight - inset * 2
        val centerX = if (checkedValue) right - inset - diameter / 2f else left + inset + diameter / 2f
        canvas.drawCircle(centerX, top + trackHeight / 2f, diameter / 2f, knob)

        paint.color = PhoneStyle.text
        val fm = paint.fontMetrics
        val baseline = height / 2f - (fm.ascent + fm.descent) / 2f
        val nameRight = left - context.dp(10)
        val shown = TextUtils.ellipsize(
            label, paint, (nameRight - paddingLeft).coerceAtLeast(0f), TextUtils.TruncateAt.END
        )
        canvas.drawText(shown, 0, shown.length, paddingLeft.toFloat(), baseline, paint)
    }

    private fun describe() {
        contentDescription = "$label，${if (checkedValue) "开" else "关"}"
    }
}

/**
 * 设置页整行滑动条：项名、轨道、读数画在同一块胶囊底板里。
 *
 * 电视端 `SettingSlider` 的触屏版：按下不抢纵向滚动，横向超过 slop 才接管，
 * 轻点轨道直接跳档；拖动和轻点都会立刻落盘。
 */
class PhoneSettingSlider(
    context: Context,
    private val label: String,
    private val min: Float,
    private val max: Float,
    private val step: Float,
    initial: Float,
    private val onValue: (Float) -> Unit,
    private val format: (Float) -> String = DanmakuStyle::percent,
) : View(context) {

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val readingPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.sp(13)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.RIGHT
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    var value: Float = normalize(initial)
        private set

    init {
        isClickable = true
        setPadding(context.dp(14), 0, context.dp(12), 0)
        applySoft(SoftStyle.RAISED, PhoneStyle.surface, PhoneStyle.RADIUS_PILL, 4f)
        paint.textSize = context.sp(15)
        describe()
    }

    /** 手指判定（按下 / 越过 slop / 轻点 / 取消）在 `:shared` 的 [SliderDrag] 里，与电视端同一份。 */
    private val drag = SliderDrag(ViewConfiguration.get(context).scaledTouchSlop)

    private val readingReserve: Float by lazy {
        maxOf(readingPaint.measureText(format(min)), readingPaint.measureText(format(max)))
    }

    private fun dpf(value: Number): Float = context.dp(value).toFloat()

    private fun normalize(candidate: Float): Float {
        val stepped = (Math.round((candidate - min) / step) * step + min)
        return (Math.round(stepped * 100f) / 100f).coerceIn(min, max)
    }

    private fun trackBounds(): Pair<Float, Float> {
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val trackRight = (right - readingReserve - dpf(12)).coerceAtLeast(left + dpf(80))
        val nameEnd = left + paint.measureText(label) + dpf(12)
        val trackLeft = nameEnd.coerceAtMost(trackRight - dpf(80)).coerceAtLeast(left)
        return trackLeft to trackRight
    }

    private fun updateAt(x: Float) {
        val (left, right) = trackBounds()
        if (right <= left) return
        val fraction = ((x - left) / (right - left)).coerceIn(0f, 1f)
        val next = normalize(min + fraction * (max - min))
        if (next == value) return
        value = next
        describe()
        invalidate()
        onValue(next)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag.down(event.x, event.y)
                alpha = .9f
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (drag.move(event.x, event.y)) parent?.requestDisallowInterceptTouchEvent(true)
                if (drag.dragging) updateAt(event.x)
                return true
            }
            MotionEvent.ACTION_UP -> {
                alpha = 1f
                if (drag.up()) updateAt(event.x)
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                alpha = 1f
                drag.cancel()
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val middle = height / 2f
        val (trackLeft, trackRight) = trackBounds()
        val fm = paint.fontMetrics
        val baseline = middle - (fm.ascent + fm.descent) / 2f

        paint.color = PhoneStyle.text
        val nameRight = (trackLeft - dpf(12)).coerceAtLeast(left)
        val shown = TextUtils.ellipsize(
            label, paint, (nameRight - left).coerceAtLeast(0f), TextUtils.TruncateAt.END
        )
        canvas.drawText(shown, 0, shown.length, left, baseline, paint)

        val thickness = dpf(3).coerceAtLeast(1f)
        val radius = thickness / 2f
        val fraction = ((value - min) / (max - min)).coerceIn(0f, 1f)
        val knobRadius = dpf(5)
        val filled = (trackLeft + (trackRight - trackLeft) * fraction)
            .coerceIn(trackLeft + knobRadius, (trackRight - knobRadius).coerceAtLeast(trackLeft + knobRadius))
        trackPaint.color = (PhoneStyle.accent and 0x00FFFFFF) or (0x38 shl 24)
        canvas.drawRoundRect(trackLeft, middle - radius, trackRight, middle + radius, radius, radius, trackPaint)
        trackPaint.color = PhoneStyle.accent
        canvas.drawRoundRect(trackLeft, middle - radius, filled, middle + radius, radius, radius, trackPaint)
        canvas.drawCircle(filled, middle, knobRadius, trackPaint)

        readingPaint.color = PhoneStyle.accent
        val readFm = readingPaint.fontMetrics
        canvas.drawText(
            format(value),
            right,
            middle - (readFm.ascent + readFm.descent) / 2f,
            readingPaint,
        )
    }

    private fun describe() {
        contentDescription = "$label ${format(value)}"
    }
}
