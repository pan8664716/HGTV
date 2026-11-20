// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.media

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * 计算 FIT 画面在宿主中的实际矩形。
 *
 * 优先使用播放器真实内容框；内容框尚未布局时，再按片源比例和画面缩放计算兜底。
 * 这个函数只返回几何结果，不绘制任何内容，手机端用弹幕 Surface 自行画黑边。
 */
fun videoPictureBounds(
    hostWidth: Int,
    hostHeight: Int,
    videoWidth: Int,
    videoHeight: Int,
    fitMode: Boolean,
    videoScale: Float = 1f,
    videoBoundsProvider: (() -> RectF?)? = null,
): RectF? {
    if (hostWidth <= 0 || hostHeight <= 0) return null
    videoBoundsProvider?.invoke()
        ?.takeIf { it.width() > 0f && it.height() > 0f }
        ?.let { return it }
    if (videoWidth <= 0 || videoHeight <= 0) return null

    val scale = videoScale.coerceIn(.1f, 1f)
    val scaledWidth = hostWidth * scale
    val scaledHeight = hostHeight * scale
    val left = (hostWidth - scaledWidth) / 2f
    val top = (hostHeight - scaledHeight) / 2f
    val bounds = RectF(left, top, left + scaledWidth, top + scaledHeight)
    if (!fitMode) return bounds

    val sourceRatio = videoWidth.toFloat() / videoHeight
    val boundsRatio = bounds.width() / bounds.height()
    if (boundsRatio > sourceRatio) {
        val pictureWidth = bounds.height() * sourceRatio
        val inset = (bounds.width() - pictureWidth) / 2f
        return RectF(
            bounds.left + inset,
            bounds.top,
            bounds.right - inset,
            bounds.bottom,
        )
    }
    val pictureHeight = bounds.width() / sourceRatio
    val inset = (bounds.height() - pictureHeight) / 2f
    return RectF(
        bounds.left,
        bounds.top + inset,
        bounds.right,
        bounds.bottom - inset,
    )
}

/**
 * TV 端 FIT 黑边的普通 View 兜底遮罩。
 *
 * 弹幕层是透明 SurfaceView；它可见时会自己画黑边，这层普通 View 只在弹幕层
 * 尚未就绪或隐藏时遮住透明挖孔。手机端已移除这层 View，只复用上方几何计算。
 * 换集时调用方通过 [sync] 更新画面比例，
 * 首帧比例冻结解除后还要再同步一次，避免新黑边短暂透出下层页面。
 */
class PlayerLetterboxMask(context: Context) : View(context) {
    private val paint = Paint().apply {
        color = Color.BLACK
        isAntiAlias = false
    }
    private var videoBoundsProvider: (() -> RectF?)? = null
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var fitMode = true
    private var fallbackScale = 1f

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun sync(
        videoBoundsProvider: (() -> RectF?)?,
        videoWidth: Int,
        videoHeight: Int,
        fitMode: Boolean,
        videoScale: Float = 1f,
    ) {
        this.videoBoundsProvider = videoBoundsProvider
        sourceWidth = videoWidth
        sourceHeight = videoHeight
        this.fitMode = fitMode
        fallbackScale = videoScale.coerceIn(.1f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val picture = pictureBounds()
        if (picture == null) {
            canvas.drawColor(Color.BLACK)
            return
        }
        val left = picture.left.coerceIn(0f, width.toFloat())
        val top = picture.top.coerceIn(0f, height.toFloat())
        val right = picture.right.coerceIn(0f, width.toFloat())
        val bottom = picture.bottom.coerceIn(0f, height.toFloat())
        if (left > 0f) canvas.drawRect(0f, 0f, left, height.toFloat(), paint)
        if (right < width) canvas.drawRect(right, 0f, width.toFloat(), height.toFloat(), paint)
        if (top > 0f) canvas.drawRect(0f, 0f, width.toFloat(), top, paint)
        if (bottom < height) {
            canvas.drawRect(0f, bottom, width.toFloat(), height.toFloat(), paint)
        }
    }

    fun pictureBounds(hostWidth: Int = width, hostHeight: Int = height): RectF? {
        return videoPictureBounds(
            hostWidth = hostWidth,
            hostHeight = hostHeight,
            videoWidth = sourceWidth,
            videoHeight = sourceHeight,
            fitMode = fitMode,
            videoScale = fallbackScale,
            videoBoundsProvider = videoBoundsProvider,
        )
    }
}
