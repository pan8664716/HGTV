// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * 通用矢量图标（自绘）。
 *
 * 只画手机端真正用到的那几个：心形、评论气泡、星形、分享、播放/暂停、返回。
 * 走 Path 而不是位图：4.4 的机器上矢量资源要 `VectorDrawableCompat`，
 * 自绘反而更轻，也能随手改描边粗细。
 */
class PhoneGlyph(context: Context, kind: Kind) : View(context) {

    /** 图标种类。允许运行时换（全屏 / 收起是同一个按钮的两种状态）。 */
    var kind: Kind = kind
        set(value) { field = value; invalidate() }


    enum class Kind {
        HEART, COMMENT, STAR, SHARE, PLAY, PAUSE, BACK, MORE, SEARCH, SETTINGS, DANMAKU,
        FULLSCREEN, SHRINK, INFO, CHEVRON, BOOKMARK,
    }

    var tint: Int = PhoneColor.TEXT
        set(value) { field = value; invalidate() }

    /** 实心还是描边。心形 / 星形选中时用实心。 */
    var filled: Boolean = false
        set(value) { field = value; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1.8f).toFloat()
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
        val paint = if (filled) fill else stroke
        when (kind) {
            Kind.HEART -> {
                path.reset()
                path.moveTo(w * .5f, h * .82f)
                path.cubicTo(w * .12f, h * .55f, w * .12f, h * .2f, w * .34f, h * .18f)
                path.cubicTo(w * .44f, h * .17f, w * .49f, h * .26f, w * .5f, h * .31f)
                path.cubicTo(w * .51f, h * .26f, w * .56f, h * .17f, w * .66f, h * .18f)
                path.cubicTo(w * .88f, h * .2f, w * .88f, h * .55f, w * .5f, h * .82f)
                path.close()
                canvas.drawPath(path, paint)
            }
            Kind.COMMENT -> {
                rect.set(w * .14f, h * .16f, w * .86f, h * .68f)
                canvas.drawRoundRect(rect, w * .16f, w * .16f, paint)
                // 小尾巴
                path.reset()
                path.moveTo(w * .3f, h * .68f)
                path.lineTo(w * .3f, h * .86f)
                path.lineTo(w * .48f, h * .68f)
                canvas.drawPath(path, if (filled) fill else stroke)
            }
            Kind.STAR -> {
                path.reset()
                val cx = w * .5f
                val cy = h * .5f
                val outer = w * .38f
                val inner = outer * .45f
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) outer else inner
                    val angle = Math.toRadians(-90.0 + i * 36.0)
                    val x = (cx + r * Math.cos(angle)).toFloat()
                    val y = (cy + r * Math.sin(angle)).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                canvas.drawPath(path, paint)
            }
            Kind.BOOKMARK -> {
                path.reset()
                path.moveTo(w * .28f, h * .18f)
                path.lineTo(w * .72f, h * .18f)
                path.lineTo(w * .72f, h * .82f)
                path.lineTo(w * .5f, h * .67f)
                path.lineTo(w * .28f, h * .82f)
                path.close()
                canvas.drawPath(path, paint)
            }
            Kind.SHARE -> {
                path.reset()
                path.moveTo(w * .22f, h * .78f)
                path.lineTo(w * .78f, h * .22f)
                canvas.drawPath(path, stroke)
                path.reset()
                path.moveTo(w * .46f, h * .22f)
                path.lineTo(w * .78f, h * .22f)
                path.lineTo(w * .78f, h * .54f)
                canvas.drawPath(path, stroke)
            }
            Kind.PLAY -> {
                path.reset()
                path.moveTo(w * .32f, h * .22f)
                path.lineTo(w * .8f, h * .5f)
                path.lineTo(w * .32f, h * .78f)
                path.close()
                canvas.drawPath(path, fill)
            }
            Kind.PAUSE -> {
                rect.set(w * .3f, h * .22f, w * .43f, h * .78f)
                canvas.drawRoundRect(rect, w * .04f, w * .04f, fill)
                rect.set(w * .57f, h * .22f, w * .7f, h * .78f)
                canvas.drawRoundRect(rect, w * .04f, w * .04f, fill)
            }
            Kind.BACK -> {
                path.reset()
                path.moveTo(w * .6f, h * .2f)
                path.lineTo(w * .3f, h * .5f)
                path.lineTo(w * .6f, h * .8f)
                canvas.drawPath(path, stroke)
            }
            Kind.MORE -> {
                val r = w * .06f
                canvas.drawCircle(w * .5f, h * .24f, r, fill)
                canvas.drawCircle(w * .5f, h * .5f, r, fill)
                canvas.drawCircle(w * .5f, h * .76f, r, fill)
            }
            Kind.SEARCH -> {
                canvas.drawCircle(w * .44f, h * .42f, w * .22f, stroke)
                path.reset()
                path.moveTo(w * .6f, h * .58f)
                path.lineTo(w * .8f, h * .78f)
                canvas.drawPath(path, stroke)
            }
            Kind.SETTINGS -> {
                // 八齿外圈 + 中间的圆孔，轮廓在 16dp 下仍能认出是设置。
                val cx = w * .5f
                val cy = h * .5f
                val teeth = 8
                val step = Math.PI * 2 / teeth
                val outer = w * .38f
                val root = w * .27f
                fun point(angle: Double, radius: Float): Pair<Float, Float> {
                    return (cx + radius * Math.cos(angle)).toFloat() to
                        (cy + radius * Math.sin(angle)).toFloat()
                }
                path.reset()
                repeat(teeth) { index ->
                    val base = -Math.PI / 2 + index * step
                    val start = point(base, root)
                    val toothStart = point(base + step * .18, outer)
                    val toothEnd = point(base + step * .48, outer)
                    val end = point(base + step * .68, root)
                    if (index == 0) path.moveTo(start.first, start.second) else path.lineTo(start.first, start.second)
                    path.lineTo(toothStart.first, toothStart.second)
                    path.lineTo(toothEnd.first, toothEnd.second)
                    path.lineTo(end.first, end.second)
                }
                path.close()
                canvas.drawPath(path, stroke)
                canvas.drawCircle(cx, cy, w * .1f, stroke)
            }
            Kind.FULLSCREEN -> {
                // 四个角：左上 + 右下各两条短线
                val len = w * .2f
                val pad = w * .2f
                path.reset()
                path.moveTo(pad, pad + len); path.lineTo(pad, pad); path.lineTo(pad + len, pad)
                path.moveTo(w - pad - len, pad); path.lineTo(w - pad, pad); path.lineTo(w - pad, pad + len)
                path.moveTo(w - pad, h - pad - len); path.lineTo(w - pad, h - pad); path.lineTo(w - pad - len, h - pad)
                path.moveTo(pad + len, h - pad); path.lineTo(pad, h - pad); path.lineTo(pad, h - pad - len)
                canvas.drawPath(path, stroke)
            }
            Kind.SHRINK -> {
                val len = w * .2f
                val pad = w * .2f
                path.reset()
                path.moveTo(pad, pad + len); path.lineTo(pad + len, pad + len); path.lineTo(pad + len, pad)
                path.moveTo(w - pad - len, pad); path.lineTo(w - pad - len, pad + len); path.lineTo(w - pad, pad + len)
                path.moveTo(w - pad, h - pad - len); path.lineTo(w - pad - len, h - pad - len); path.lineTo(w - pad - len, h - pad)
                path.moveTo(pad + len, h - pad); path.lineTo(pad + len, h - pad - len); path.lineTo(pad, h - pad - len)
                canvas.drawPath(path, stroke)
            }
            Kind.INFO -> {
                canvas.drawCircle(w * .5f, h * .5f, w * .3f, stroke)
                path.reset()
                path.moveTo(w * .5f, h * .44f); path.lineTo(w * .5f, h * .7f)
                canvas.drawPath(path, stroke)
                canvas.drawCircle(w * .5f, h * .3f, w * .045f, fill)
            }
            Kind.CHEVRON -> {
                path.reset()
                path.moveTo(w * .38f, h * .26f)
                path.lineTo(w * .64f, h * .5f)
                path.lineTo(w * .38f, h * .74f)
                canvas.drawPath(path, stroke)
            }
            Kind.DANMAKU -> {
                rect.set(w * .12f, h * .24f, w * .68f, h * .54f)
                canvas.drawRoundRect(rect, w * .08f, w * .08f, stroke)
                rect.set(w * .32f, h * .5f, w * .88f, h * .8f)
                canvas.drawRoundRect(rect, w * .08f, w * .08f, stroke)
            }
        }
    }
}
