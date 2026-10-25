// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.player

import com.hgtv.star.compat.drawRoundRectCompat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.KeyEvent
import com.hgtv.star.core.DanmakuStyle
import com.hgtv.star.tv.ButtonSize
import com.hgtv.star.tv.ButtonTone
import com.hgtv.star.tv.TvButton
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.tv.designPx
import com.hgtv.star.tv.tvDp

/**
 * 弹幕字号 / 透明度的滑动调节条（播放设置面板与设置页共用同一个控件）。
 *
 * 外观就是全站紧凑档按钮（[TvButton] 的凸起 / 打亮 / 按进去三态、胶囊圆角、同样的柔光带），
 * 里面画三样东西：一条轨道、一段已选填充 + 圆钮、右对齐的百分比读数。
 *
 * **为什么不是 SeekBar**：电视上 SeekBar 会自己抢焦点、自己处理左右键，
 * 和 `nextFocusLeftId/RightId` 那一套四向锁定打架，遥控器按到边缘会跳出去。
 * 这里直接复用按钮的焦点体系：左右键由控件自己在 [onKeyDown] 里吃掉（按一下走一档，
 * 长按连续走），上下键仍然交给焦点系统在功能行之间移动。
 *
 * 值一律经过 [DanmakuStyle] 归一后再回调，因此面板上滑到的数、写盘的数、下次读回来的数
 * 是同一个浮点值，不会出现「显示 130% 但和 130% 不相等」的抖档。
 */
internal class DanmakuSlider(
    context: Context,
    private val label: String,
    private val min: Float,
    private val max: Float,
    private val step: Float,
    initial: Float,
    private val onValue: (Float) -> Unit
) : TvButton(context, "", ButtonSize.COMPACT, ButtonTone.SECONDARY, alignStart = true) {

    private val dp = { value: Number -> context.tvDp(value) }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val reading = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // 与 TextView 的字号换算走同一条路（设计缩放），面板上的读数与按钮文字同高。
        textSize = context.designPx(TvStyle.BUTTON_TEXT_COMPACT).toFloat()
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.RIGHT
    }

    /** 当前值（已归一）。赋值会重画并刷新无障碍描述，但**不**触发 [onValue]——避免初始化时回调。 */
    var value: Float = initial
        set(newValue) {
            val next = normalize(newValue)
            if (field == next) return
            field = next
            contentDescription = "$label ${DanmakuStyle.percent(next)}，左右键调节"
            invalidate()
        }

    init {
        value = initial
        contentDescription = "$label ${DanmakuStyle.percent(value)}，左右键调节"
    }

    private fun normalize(candidate: Float) =
        (Math.round((candidate - min) / step) * step + min).let { Math.round(it * 100f) / 100f }.coerceIn(min, max)

    /**
     * 左右键由控件自己消化：`onKeyDown` 比焦点导航先执行，
     * 这里返回 true 就等于告诉框架「这一下不用找下一个焦点了」。
     * 确认键也吃掉，避免点下去触发按钮的空动作。
     *
     * **已经顶到两端时不吃**：这一下没改任何值，让给焦点系统，用户才能横着离开这一格。
     * 播放设置面板里两条滑块的左右出口都指回自己，因此那里照旧停在原地；
     * 设置页的弹幕栏里，滑块左边就是「弹幕显示 / 弹幕速度」，按左能真的回到那一格。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> nudge(-1)
        KeyEvent.KEYCODE_DPAD_RIGHT -> nudge(1)
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> true
        else -> super.onKeyDown(keyCode, event)
    }

    /** 调一档；返回「值真的变了」——顶到两端时返回 false（见 [onKeyDown]）。 */
    private fun nudge(direction: Int): Boolean {
        val before = value
        val index = Math.round((value - min) / step) + direction
        value = min + index * step
        if (value == before) return false
        onValue(value)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val text = DanmakuStyle.percent(value)
        val color = currentTextColor
        // 读数占了右边一条，轨道让开它；窄屏上至少留 24dp，滑块不会被读数挤成一点。
        val trackRight = (right - reading.measureText(text) - dp(12)).coerceAtLeast(left + dp(24))
        val middle = height / 2f
        val thickness = dp(4).toFloat().coerceAtLeast(1f)
        val radius = thickness / 2f
        val fraction = ((value - min) / (max - min)).coerceIn(0f, 1f)
        val knob = dp(5).toFloat()
        val filled = (left + (trackRight - left) * fraction).coerceIn(left + knob, (trackRight - knob).coerceAtLeast(left + knob))

        bar.color = color
        bar.alpha = 0x38
        drawRoundRectCompat(canvas, left, middle - radius, trackRight, middle + radius, radius, bar)
        bar.alpha = 0xFF
        drawRoundRectCompat(canvas, left, middle - radius, filled, middle + radius, radius, bar)
        canvas.drawCircle(filled, middle, knob, bar)

        reading.color = color
        val metrics = reading.fontMetrics
        canvas.drawText(text, right, middle - (metrics.ascent + metrics.descent) / 2f, reading)
    }
}
