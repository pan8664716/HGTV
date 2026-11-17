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
        LOCK, UNLOCK,
        /** 竖屏转全屏：左边一个竖框、右边一个横框，一眼看出「从这里横过来」。 */
        ROTATE_FULLSCREEN,
        /** 刷新：开口圆环 + 箭头。 */
        REFRESH,
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
    /**
     * 「设置」图标的矢量路径（用户提供的 `设置.svg`，1024×1024 画布）。
     *
     * 只解析一次：`PathParser` 每次都要走一遍 tokenizer，而这个图标每帧都在画。
     * 解析失败时留空，绘制分支会跳过（不崩）。
     */
    private val settingsPath: Path? by lazy {
        runCatching {
            androidx.core.graphics.PathParser.createPathFromPathData(SETTINGS_PATH_DATA)
        }.getOrNull()
    }
    private val rect = RectF()
    /**
     * 细笔画：小尺寸图标（10~12dp）专用。
     *
     * 默认那支笔固定 1.8dp，铺到 10dp 的图标上就是 18% 宽——「竖框 + 箭头」这类
     * 两三个元素挤在一起的图形会糊成一块。这支按图标宽度自适应收细。
     */
    private val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        fill.color = tint
        stroke.color = tint
        val w = width.toFloat()
        val h = height.toFloat()
        thin.color = tint
        val maxThin = stroke.strokeWidth.coerceAtLeast(0f)
        val minThin = minOf(1.5f, maxThin)
        thin.strokeWidth = (w * .075f).coerceIn(minThin, maxThin)
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
                // 用户指定的齿轮（原 SVG 的 1024×1024 实心路径），等比缩到当前画布。
                // 整块用 fill 笔填，不走描边——原图就是实心的，描边会走形。
                settingsPath?.let { p ->
                    canvas.save()
                    canvas.scale(w / 1024f, h / 1024f)
                    canvas.drawPath(p, fill)
                    canvas.restore()
                }
            }
            Kind.FULLSCREEN -> {
                // 四角边框：每个角两条短线拼成 L。
                // 用细笔：这个图标以 10dp 显示，1.8dp 的笔画会让四角糊成四个方块。
                val len = w * .22f
                val pad = w * .14f
                path.reset()
                path.moveTo(pad, pad + len); path.lineTo(pad, pad); path.lineTo(pad + len, pad)
                path.moveTo(w - pad - len, pad); path.lineTo(w - pad, pad); path.lineTo(w - pad, pad + len)
                path.moveTo(w - pad, h - pad - len); path.lineTo(w - pad, h - pad); path.lineTo(w - pad - len, h - pad)
                path.moveTo(pad + len, h - pad); path.lineTo(pad, h - pad); path.lineTo(pad, h - pad - len)
                canvas.drawPath(path, thin)
            }
            Kind.ROTATE_FULLSCREEN -> {
                // 左边竖框 = 现在这一屏，右边箭头 = 横过来铺满。
                // 比四角箭头更直观：用户点它要的是「把竖着的画面转横」。
                // 用细笔：这个图标会以 10dp 显示，默认 1.8dp 的笔画会把三个元素糊成一块。
                val r = w * .04f
                rect.set(w * .06f, h * .16f, w * .38f, h * .84f)
                canvas.drawRoundRect(rect, r, r, thin)
                path.reset()
                path.moveTo(w * .48f, h * .5f)
                path.lineTo(w * .90f, h * .5f)
                path.moveTo(w * .74f, h * .36f)
                path.lineTo(w * .90f, h * .5f)
                path.lineTo(w * .74f, h * .64f)
                canvas.drawPath(path, thin)
            }
            Kind.REFRESH -> {
                // Material 那一套：一段 280° 的圆弧 + 弧末端一枚实心三角箭头。
                // 早先是「圆环 + 三点折线」，箭头与弧线脱开、比例也歪，看着像随手画的。
                val cx = w * .5f
                val cy = h * .5f
                val r = minOf(w, h) * .30f
                rect.set(cx - r, cy - r, cx + r, cy + r)
                // 缺口留在右上（-45° 起画 280°）。
                canvas.drawArc(rect, -45f, 280f, false, thin)
                // 箭头尖在弧的末端（235°），沿切线方向往外；三角用实心笔。
                val angle = Math.toRadians(235.0)
                val ax = cx + r * kotlin.math.cos(angle).toFloat()
                val ay = cy + r * kotlin.math.sin(angle).toFloat()
                val tx = -kotlin.math.sin(angle).toFloat()
                val ty = kotlin.math.cos(angle).toFloat()
                val len = w * .17f
                val half = w * .11f
                path.reset()
                path.moveTo(ax + tx * len, ay + ty * len)
                path.lineTo(ax - ty * half, ay + tx * half)
                path.lineTo(ax + ty * half, ay - tx * half)
                path.close()
                canvas.drawPath(path, fill)
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
            Kind.LOCK, Kind.UNLOCK -> {
                // 简洁线框：锁体 + 锁扣，不再画钥匙孔，避免小尺寸下糊成一团。
                rect.set(w * .28f, h * .45f, w * .72f, h * .82f)
                canvas.drawRoundRect(rect, w * .07f, w * .07f, thin)
                path.reset()
                if (kind == Kind.LOCK) {
                    path.moveTo(w * .36f, h * .45f)
                    path.lineTo(w * .36f, h * .36f)
                    path.cubicTo(
                        w * .36f, h * .22f,
                        w * .64f, h * .22f,
                        w * .64f, h * .36f,
                    )
                    path.lineTo(w * .64f, h * .45f)
                } else {
                    path.moveTo(w * .36f, h * .45f)
                    path.lineTo(w * .36f, h * .36f)
                    path.cubicTo(
                        w * .36f, h * .22f,
                        w * .64f, h * .22f,
                        w * .68f, h * .32f,
                    )
                }
                canvas.drawPath(path, thin)
            }
        }
    }
}

/**
 * 「设置」图标的 SVG 路径（viewBox 1024×1024，来自用户提供的 `设置.svg`）。
 *
 * 原样保留、不做简化：这类齿轮的轮廓细节一改就走形。
 */
private const val SETTINGS_PATH_DATA = """M956.444 422.418l-82.59-34.303c-0.267 0-0.526-0.267-0.795-0.267-6.334-20.054-14.512-39.844-24.54-58.841 0.267-0.526 0.526-1.318 0.795-1.849l33.513-81.539c5.541-13.191 2.378-28.498-7.65-38.79l-64.383-64.646c-10.029-10.291-25.332-13.191-38.79-7.65l-82.325 34.037c-0.267 0.267-0.795 0.267-1.056 0.526-18.998-10.029-38.79-18.207-58.841-24.54-0.267-0.526-0.267-0.795-0.526-1.318l-34.037-81.797c-5.541-13.191-18.471-21.9-32.985-21.9h-91.299c-14.512 0-27.439 8.705-32.985 21.9l-34.303 82.59c0 0.267-0.267 0.526-0.267 0.795-20.054 6.334-39.844 14.512-58.841 24.54-0.526-0.267-1.318-0.526-1.849-0.795l-81.004-33.776c-13.191-5.541-28.498-2.378-38.79 7.65l-64.383 64.383c-10.291 10.291-13.191 25.597-7.65 38.79l34.037 82.325c0.267 0.267 0.267 0.795 0.526 1.056-10.029 19.261-18.207 39.049-24.54 58.841-0.526 0.267-1.056 0.267-1.318 0.526l-81.797 34.037c-13.458 5.541-21.9 18.471-21.9 32.985v91.033c0 14.25 8.705 27.439 21.9 32.985l82.325 34.303c0.267 0 0.526 0.267 0.795 0.267 6.334 20.054 14.512 39.844 24.54 58.841-0.267 0.795-0.526 1.318-0.795 2.113l-33.513 81.271c-5.541 13.191-2.378 28.498 7.65 38.79l64.383 64.383c10.291 10.291 25.597 13.191 38.79 7.65l82.325-34.037c0.267-0.267 0.795-0.267 1.056-0.526 18.998 9.761 38.79 18.207 58.841 24.54 0.267 0.526 0.267 1.056 0.526 1.318l34.037 81.797c5.541 13.191 18.471 21.9 32.985 21.9h91.299c14.512 0 27.439-8.705 32.985-21.9l34.303-82.59c0-0.267 0.267-0.526 0.267-0.795 20.054-6.334 39.844-14.512 58.841-24.54 0.526 0.267 1.318 0.526 1.849 0.795l81.271 33.513c13.191 5.541 28.498 2.378 38.79-7.65l64.383-64.383c10.291-10.291 13.191-25.597 7.65-38.79l-34.037-82.325c-0.267-0.267-0.267-0.795-0.526-1.056 9.761-18.998 18.207-38.79 24.54-58.841 0.526-0.267 1.056-0.267 1.318-0.526l81.797-34.037c13.191-5.541 21.9-18.471 21.9-32.985v-91.299c0-13.724-8.705-26.653-21.9-32.192zM516.84 633.51c-73.356 0-132.727-59.369-132.727-132.727s59.369-132.727 132.727-132.727c73.356 0 132.727 59.369 132.727 132.727 0 73.356-59.369 132.727-132.727 132.727z"""
