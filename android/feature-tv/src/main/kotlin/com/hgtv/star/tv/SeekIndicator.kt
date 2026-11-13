// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import com.hgtv.star.shared.compat.drawRoundRectCompat

/**
 * 播放进度条（对应播放器控制层，**不套新拟态**）。
 *
 * 4dp 圆角轨道 + 18% 白底槽 + 缓存段 + 已播段 + 10dp 圆形滑块：这是播放界面的原始样式，
 * 播放时它压在画面上，任何柔光底板都会和画面抢注意力，因此这里保持原样。
 * 电视端左右键已经负责 seek，这里只做显示，因此直接自绘一个 View，
 * 比套一层 SeekBar 省掉三份 drawable，也不会抢焦点。
 */
class SeekIndicator(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dp = { value: Number -> context.tvDp(value) }

    /**
     * 手指拖动进度：非 null 时这个控件可点可拖（**电视上永远是 null**）。
     *
     * 回调第一个参数是 0–1 的进度比例，第二个是「这一次是不是抬手」。
     * 拖动期间只改显示、抬手才真跳，原因见 `TvMainActivity.onScrubProgress`：
     * 连续 `seekTo` 会让 ExoPlayer 反复重置渲染管线。
     *
     * 做成可空回调而不是新增一个控件：电视端的进度条一个字节都不变，
     * 触摸设备也只是多一条 `onTouchEvent`，画法（底槽 + 已播段 + 圆钮）完全复用。
     */
    var onScrub: ((fraction: Float, finished: Boolean) -> Unit)? = null
    /** 这一串事件是不是本控件自己在拖（决定抬手的处理要不要执行）。 */
    private var scrubbing = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val listener = onScrub ?: return false
        if (width <= 0) return false
        val fraction = (event.x / width).coerceIn(0f, 1f)
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                scrubbing = true
                // 不让外层 ScrollView 抢走横向拖动：控制条整个在一层 ScrollView 里。
                parent?.requestDisallowInterceptTouchEvent(true)
                listener(fraction, false)
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!scrubbing) return false
                scrubbing = false
                parent?.requestDisallowInterceptTouchEvent(false)
                listener(fraction, true)
                true
            }
            else -> false
        }
    }

    /** 0f–1f 的播放进度，越界自动收敛。 */
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** 0f–1f 的缓存进度；用较淡的暖白画在已播段下面。 */
    var bufferedProgress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val middle = height / 2f
        val trackHeight = dp(4).toFloat().coerceAtLeast(1f)
        val radius = trackHeight / 2f
        val top = middle - radius
        val bottom = middle + radius
        paint.color = TvStyle.playerLine
        drawRoundRectCompat(canvas, 0f, top, width.toFloat(), bottom, radius, paint)
        val knobRadius = dp(5).toFloat()
        val edge = width.toFloat() - knobRadius
        val played = (width * progress).coerceIn(0f, width.toFloat())
        val buffered = (width * maxOf(progress,bufferedProgress)).coerceIn(played, width.toFloat())
        paint.color = 0x66F4F3F1
        if (buffered > radius) drawRoundRectCompat(canvas, 0f, top, buffered, bottom, radius, paint)
        paint.color = TvStyle.playerText
        if (played > radius) drawRoundRectCompat(canvas, 0f, top, played, bottom, radius, paint)
        // 进度条比圆钮还窄时（极窄屏 / 布局未完成），上界会小于下界，coerceIn 会抛异常。
        if (width > 0) canvas.drawCircle(played.coerceIn(knobRadius, edge.coerceAtLeast(knobRadius)), middle, knobRadius, paint)
    }
}
