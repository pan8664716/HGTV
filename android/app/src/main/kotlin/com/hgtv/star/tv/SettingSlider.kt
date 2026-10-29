// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.hgtv.star.compat.drawRoundRectCompat
import com.hgtv.star.core.DanmakuStyle

/**
 * 设置页里「项名 + 滑轨」合成一行的滑动条。
 *
 * 与旧写法（左边一条固定的项名文字、右边一条 [com.hgtv.star.player.DanmakuSlider]）相比，
 * 这里**整行就是一枚控件**：底板、焦点反馈、按下态与同一栏里其它设置项完全一致，
 * 项名画在这块底板内部的左段，滑轨接在右段。因此一栏里的「按钮 / 开关 / 滑动条」
 * 看起来是同一块料，行高、左右内边距、文字左边缘全都对齐，中间不会多出一条缝。
 *
 * 为什么不让 `SeekBar` 来做：它在电视上会自己抢焦点、自己吃左右键，与全站
 * `nextFocusLeftId / RightId` 那一套四向锁定打架。这里沿用按钮的焦点体系——
 * 左右键由控件自己在 [onKeyDown] 里吃掉（按一下走一档），**顶到两端时不吃**，
 * 这一下让给焦点系统，用户才能横着离开这一行。
 *
 * 触屏（手机 / 平板）与 [`com.hgtv.star.player.DanmakuSlider`] 同一套：按下不抢事件
 * （纵向拖动仍交给外层滚动），横向位移超过 slop 才接管，轻点轨道直接跳到该位置。
 */
internal class SettingSlider(
    context: Context,
    private val label: String,
    private val min: Float,
    private val max: Float,
    private val step: Float,
    initial: Float,
    private val onValue: (Float) -> Unit,
    /** 右侧读数的写法。默认「比例 → 百分比」（`1f` → `100%`）；1..100 的百分数直接传 `{ "${it.toInt()}%" }`。 */
    private val format: (Float) -> String = DanmakuStyle::percent,
    /**
     * 行高档位。设置页用 [ButtonSize.NORMAL]（与同栏的设置项同高）；
     * 播放界面那块浮层用 [ButtonSize.COMPACT]——那一栏的其它行是紧凑档选项（52dp），
     * 滑动条再高一头就会在滚动列表里显得一截一截的。
     */
    size: ButtonSize = ButtonSize.NORMAL,
    /** 覆盖档位自带的左右内边距（见 [TvSettingButton]）；null = 用档位值。 */
    pad: Int? = null,
    /**
     * 项名是否画在本控件内部。默认画（设置页那一栏就是「整行一枚控件」）。
     *
     * 播放界面那块浮层里传 `false`：那里的上一行都是「左边一条固定 110dp 的项名 + 右边一排选项」，
     * 项名由行自己摆（见 `PlaybackMenu.addSlider`）。滑动条再自己画一遍项名，轨道就只能从
     * 「自己的项名宽度 + 18dp」起算——三枚滑块的项名长短不同（弹幕大小 / 弹幕透明度 / 画面缩放），
     * 轨道起点于是各不相同，也都不在上一行的选项列上。关掉内部项名之后轨道从左边距起算，
     * 正好落在上面那些行的选项列同一条竖线上。
     */
    private val showName: Boolean = true
) : TvSettingButton(context, label, size, ButtonTone.SECONDARY, pad = pad) {

    private val dp = { value: Number -> context.tvDp(value) }
    /** 同一档设计缩放的浮点版本：轨道坐标全是 Float，混用 Int 会在每处相加时都要转一次。 */
    private val dpf = { value: Number -> context.tvDp(value).toFloat() }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val reading = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // 与设置项的字号走同一条设计缩放：读数与项名同高，不会一半大一半小。
        textSize = context.designPx(size.text).toFloat()
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.RIGHT
    }

    /** 当前值（已归一）。赋值会重画并刷新无障碍描述，但**不**触发 [onValue]——避免初始化时回调。 */
    var value: Float = initial
        set(newValue) {
            val next = normalize(newValue)
            if (field == next) return
            field = next
            describe()
            invalidate()
        }

    init {
        value = initial
        describe()
        isClickable = true
    }

    private fun describe() {
        contentDescription = "$label ${format(value)}，左右键调节"
    }

    private fun normalize(candidate: Float) =
        (Math.round((candidate - min) / step) * step + min).let { Math.round(it * 100f) / 100f }.coerceIn(min, max)

    // —— 键位 ——

    /** 调一档；返回「值真的变了」——顶到两端时返回 false（见 [onKeyDown]）。 */
    private fun nudge(direction: Int): Boolean {
        val before = value
        val index = Math.round((value - min) / step) + direction
        value = min + index * step
        if (value == before) return false
        onValue(value)
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> nudge(-1)
        KeyEvent.KEYCODE_DPAD_RIGHT -> nudge(1)
        // 确定键吃掉：这一行没有「点开」这回事，别让它触发按钮的空动作。
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> true
        else -> super.onKeyDown(keyCode, event)
    }

    // —— 触屏 ——

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    /**
     * 读数区固定占的宽度：**按这个滑动条可能出现的最宽读数**算，与当前值无关。
     *
     * 早先直接用当前读数的宽度当让位距离（`right − measureText(当前值)`）：1% 时让得少、
     * 100% 时让得多，于是轨道右端每次跨过一位数就缩短一截——看起来像「进度条会自己伸缩」。
     * 这里取 min / max 两端的读数取宽的那一个（`1%` 与 `100%` → `100%`），
     * 整条轨道与读数的相对位置因此从 1 到 100 全程不变。
     */
    private val readingReserve: Float by lazy {
        maxOf(reading.measureText(format(min)), reading.measureText(format(max)))
    }

    /** 项名与轨道之间的固定间隙：标题的字尾到轨道起点就这么远，不再由「轨道总长」倒推。 */
    private val nameGap = { dpf(18) }

    /** 轨道至少留这么长：项名再长也不会把滑轨挤成一段小圆点。 */
    private val minTrack = { dpf(96) }

    /**
     * 轨道左右端点（绘制与触屏映射共用同一份计算）。
     *
     * 两个端点各自**独立**算出来，互不倒推：
     * - 左端贴着项名（`项名宽度 + 固定间隙`），因此标题与进度条之间不会再空出一大段；
     * - 右端让开**固定宽度**的读数区（见 [readingReserve]），因此轨长不随 1 / 2 / 3 位数变化。
     *
     * 项名过长时左端让位（轨道保底 [minTrack]），此时 [drawRowName] 会按它自己的规则
     * 省略 / 跑马灯——与同一栏的其它设置项一致。
     */
    private fun trackBounds(): Pair<Float, Float> {
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val trackRight = (right - readingReserve - dpf(16)).coerceAtLeast(left + minTrack())
        // 不画项名时轨道直接贴左边距：这一行外面已经有一条项名列（见 [showName]）。
        val nameEnd = if(showName) left + paint.measureText(label) + nameGap() else left
        val trackLeft = nameEnd.coerceAtMost(trackRight - minTrack()).coerceAtLeast(left)
        return trackLeft to trackRight
    }

    private fun applyTouchX(x: Float) {
        val (left, right) = trackBounds()
        if (right - left <= 0f) return
        // 轻点落在右侧那串百分比读数上：那不是轨道，不该把值拽到 100%。
        if (!dragging && x > right + dpf(4)) return
        val fraction = ((x - left) / (right - left)).coerceIn(0f, 1f)
        val next = normalize(min + fraction * (max - min))
        if (next == value) return
        value = next
        onValue(next)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = Math.abs(event.x - downX)
                val dy = Math.abs(event.y - downY)
                if (!dragging && dx > touchSlop && dx > dy) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) applyTouchX(event.x)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) applyTouchX(event.x)
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // —— 绘制 ——

    override fun onDraw(canvas: Canvas) {
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val middle = height / 2f
        val color = currentTextColor
        val text = format(value)

        // 1) 项名：与同一栏的按钮 / 开关同一条左边缘；过长时按同一套跑马灯规则处理。
        // 项名那一段就截到轨道起点前 [nameGap]，因此「标题 → 进度条」的间隙是固定的。
        // [showName] 为 false 时这一行外面有项名列，这里不再画第二遍。
        val (trackLeft, trackRight) = trackBounds()
        val metrics = paint.fontMetrics
        if(showName) {
            val nameRight = (trackLeft - nameGap()).coerceAtLeast(left)
            drawRowName(canvas, left, nameRight, middle - (metrics.ascent + metrics.descent) / 2f)
        }

        // 2) 滑轨：底色轨道 + 已选填充 + 圆钮，颜色取本行字色（焦点 / 禁用态随按钮走）。
        val thickness = dpf(4).coerceAtLeast(1f)
        val radius = thickness / 2f
        val fraction = ((value - min) / (max - min)).coerceIn(0f, 1f)
        val knob = dpf(5)
        val filled = (trackLeft + (trackRight - trackLeft) * fraction)
            .coerceIn(trackLeft + knob, (trackRight - knob).coerceAtLeast(trackLeft + knob))
        bar.color = color
        bar.alpha = 0x38
        drawRoundRectCompat(canvas, trackLeft, middle - radius, trackRight, middle + radius, radius, bar)
        bar.alpha = 0xFF
        drawRoundRectCompat(canvas, trackLeft, middle - radius, filled, middle + radius, radius, bar)
        canvas.drawCircle(filled, middle, knob, bar)

        // 3) 读数：贴右边缘，与同一栏设置项右侧那枚「>」落在同一条竖线上。
        reading.color = color
        val readMetrics = reading.fontMetrics
        canvas.drawText(text, right, middle - (readMetrics.ascent + readMetrics.descent) / 2f, reading)
    }
}
