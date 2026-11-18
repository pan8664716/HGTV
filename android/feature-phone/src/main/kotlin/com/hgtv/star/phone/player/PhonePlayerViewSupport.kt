// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.PlayerView
import com.hgtv.star.core.VideoFrameMode
import com.hgtv.star.core.toFit
import com.hgtv.star.shared.data.ArtworkLoader
import com.hgtv.star.phone.ui.PhoneSheet
import com.hgtv.star.phone.ui.PhoneGlyph
import com.hgtv.star.phone.ui.PhoneStyle
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.phoneSize
import com.hgtv.star.phone.ui.resizeMode
import com.hgtv.star.phone.ui.roundedBackground
import kotlin.math.abs

/**
 * 正式播放换集时冻结旧画面的宽高比。
 *
 * PlayerView 默认在 `onVideoSizeChanged` 就把内容框切到新比例，旧帧会在新首帧
 * 到达前被拉伸一下。这里先把新比例暂存，等 `onRenderedFirstFrame` 后一次性应用；
 * 应用时短暂合上快门，完成布局后再揭开，因此用户只会看到旧帧稳定停住，再切到新帧。
 */
internal class FixedFirstFramePlayerView(context: Context) : PlayerView(context) {
    private var holdAspectRatio = false
    private var firstFrameSeen = false
    private var pendingAspectRatio = 0f
    private var appliedAspectRatio = 0f
    private var contentFrame: AspectRatioFrameLayout? = null
    private val shutterView by lazy {
        findViewById<View>(com.google.android.exoplayer2.ui.R.id.exo_shutter)
    }

    override fun onContentAspectRatioChanged(
        contentFrame: AspectRatioFrameLayout?,
        aspectRatio: Float,
    ) {
        this.contentFrame=contentFrame
        if(holdAspectRatio) {
            pendingAspectRatio=aspectRatio
            if(firstFrameSeen) completeFirstFrameAspectRatio()
            return
        }
        appliedAspectRatio=aspectRatio
        super.onContentAspectRatioChanged(contentFrame,aspectRatio)
    }

    fun holdFirstFrameAspectRatio() {
        holdAspectRatio=true
        firstFrameSeen=false
        pendingAspectRatio=0f
    }

    fun completeFirstFrameAspectRatio() {
        if(!holdAspectRatio) return
        firstFrameSeen=true
        val ratio=pendingAspectRatio
        if(ratio<=0f) return
        holdAspectRatio=false
        val frame=contentFrame ?: videoSurfaceView?.parent as? AspectRatioFrameLayout
        pendingAspectRatio=0f
        if(frame==null || abs(ratio-appliedAspectRatio)<.0001f) return
        appliedAspectRatio=ratio
        val shutter=shutterView
        shutter?.visibility=View.VISIBLE
        frame.setAspectRatio(ratio)
        frame.requestLayout()
        val observer=frame.viewTreeObserver
        if(!observer.isAlive) {
            shutter?.visibility=View.INVISIBLE
            return
        }
        observer.addOnPreDrawListener(object: android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if(observer.isAlive) observer.removeOnPreDrawListener(this)
                shutter?.visibility=View.INVISIBLE
                return true
            }
        })
    }
}

internal fun PlayerView.holdFirstFrameAspectRatio() {
    (this as? FixedFirstFramePlayerView)?.holdFirstFrameAspectRatio()
}

internal fun PlayerView.completeFirstFrameAspectRatio() {
    (this as? FixedFirstFramePlayerView)?.completeFirstFrameAspectRatio()
}

internal fun PhonePlayerActivity.holdFirstFrameAspectRatio() {
    if(playerPageInitialized) playerPage.playerView.holdFirstFrameAspectRatio()
    if(playerLandscapeVideoInitialized) playerLandscapeVideo.holdFirstFrameAspectRatio()
    if(playerFeedUnderlay) PhoneSession.holdFeedFirstFrameAspectRatio()
}

internal fun PhonePlayerActivity.completeFirstFrameAspectRatio() {
    if(playerPageInitialized) playerPage.playerView.completeFirstFrameAspectRatio()
    if(playerLandscapeVideoInitialized) playerLandscapeVideo.completeFirstFrameAspectRatio()
    if(playerFeedUnderlay) PhoneSession.completeFeedFirstFrameAspectRatio()
}

internal fun episodeCoverView(context: Context): ImageView = ImageView(context).apply {
    setBackgroundColor(Color.BLACK)
    scaleType = ImageView.ScaleType.FIT_CENTER
    visibility = View.GONE
    isFocusable = false
    isClickable = false
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
}

private fun PhonePlayerActivity.loadLandscapeEpisodeCover(url: String) {
    val cover = playerLandscapeCover ?: return
    cover.animate().cancel()
    cover.alpha = 1f
    cover.visibility = View.VISIBLE
    if (url.isBlank()) {
        cover.setImageDrawable(null)
        return
    }
    val metrics = resources.displayMetrics
    PhoneServices.artwork(this).load(
        cover,
        url,
        ArtworkLoader.PRIORITY_FOCUSED,
        metrics.widthPixels,
        metrics.heightPixels,
    )
}

internal fun PhonePlayerActivity.showEpisodeCover(url: String) {
    if (playerPageInitialized) playerPage.showEpisodeCover(url)
    loadLandscapeEpisodeCover(url)
}

internal fun PhonePlayerActivity.showLandscapeGhostEpisodeCover(url: String) {
    val cover = playerLandscapeGhostCover ?: return
    cover.animate().cancel()
    cover.alpha = 1f
    cover.visibility = View.VISIBLE
    if (url.isBlank()) {
        cover.setImageDrawable(null)
        return
    }
    val metrics = resources.displayMetrics
    PhoneServices.artwork(this).load(
        cover,
        url,
        ArtworkLoader.PRIORITY_FOCUSED,
        metrics.widthPixels,
        metrics.heightPixels,
    )
}

internal fun PhonePlayerActivity.hideEpisodeCover() {
    if (playerPageInitialized) playerPage.hideEpisodeCover()
    listOfNotNull(playerLandscapeCover, playerLandscapeGhostCover).forEach { cover ->
        if (cover.visibility != View.VISIBLE) return@forEach
        cover.animate().cancel()
        cover.animate().alpha(0f).setDuration(120L).withEndAction {
            if (cover.alpha == 0f) {
                cover.visibility = View.GONE
                cover.setImageDrawable(null)
            }
        }.start()
    }
}

internal object PhonePlayerViewSupport {
    /**
     * SurfaceView 默认透明区域会挖穿 Activity 窗口。视频 Surface 显式设为 OPAQUE，
     * 避免视频画面本身透出下层页面；弹幕那一层仍是透明 Surface，黑边由
     * [PhoneLetterboxMask] 在窗口层遮住。
     */
    fun useOpaqueVideoSurface(view: PlayerView) {
        runCatching {
            (view.videoSurfaceView as? SurfaceView)?.holder?.setFormat(PixelFormat.OPAQUE)
        }
    }

    /**
     * 横屏底栏入口。常态半透明白、按下更亮：点下去要有反馈。
     */
    fun chip(activity: Activity, text: String, onClick: () -> Unit): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(Color.WHITE)
        phoneSize(11)
        gravity = Gravity.CENTER
        setPadding(activity.dp(8), activity.dp(4), activity.dp(8), activity.dp(4))
        background = PhoneSheet.pillBackground(
            0x33FFFFFF, 0x73FFFFFF, activity.dp(11).toFloat()
        )
        setOnClickListener { onClick() }
    }

    fun chipLp(activity: Activity, left: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        activity.dp(64), ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { leftMargin = activity.dp(left) }

    fun lockButton(
        context: Context,
        sizeDp: Float,
        onClick: () -> Unit,
    ): Pair<FrameLayout, PhoneGlyph> {
        val glyph = PhoneGlyph(context, PhoneGlyph.Kind.LOCK)
        val box = FrameLayout(context).apply {
            tag = sizeDp
            addView(
                glyph,
                FrameLayout.LayoutParams(
                    context.dp(sizeDp * .58f),
                    context.dp(sizeDp * .58f),
                    Gravity.CENTER,
                ),
            )
            setOnClickListener { onClick() }
        }
        styleLockButton(box, glyph, locked = false)
        return box to glyph
    }

    fun styleLockButton(box: FrameLayout, glyph: PhoneGlyph, locked: Boolean) {
        val fill = if (locked) {
            (PhoneStyle.accent and 0x00FFFFFF) or (0x44 shl 24)
        } else {
            0x33FFFFFF
        }
        val sizeDp = box.tag as? Float ?: 28f
        box.background = roundedBackground(fill, box.context.dp(sizeDp / 2f).toFloat())
        glyph.kind = if (locked) PhoneGlyph.Kind.UNLOCK else PhoneGlyph.Kind.LOCK
        glyph.tint = if (locked) PhoneStyle.accent else Color.WHITE
        val label = if (locked) "解锁播放控制" else "锁定播放控制"
        box.contentDescription = label
        glyph.contentDescription = label
    }

    /** 进度条染色是 API 21 才有的，19/20 上保持系统默认（不影响功能）。 */
    fun tintSeekBar(bar: SeekBar) {
        if (Build.VERSION.SDK_INT < 21) return
        runCatching {
            bar.progressTintList = android.content.res.ColorStateList.valueOf(PhoneStyle.accent)
            bar.secondaryProgressTintList = android.content.res.ColorStateList.valueOf(
                0x66FFFFFF
            )
            bar.thumbTintList = android.content.res.ColorStateList.valueOf(PhoneStyle.accent)
            bar.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x4DFFFFFF)
        }
    }

    fun requestOrientation(activity: Activity, value: Int): Boolean = runCatching {
        activity.requestedOrientation = value
    }.onFailure {
        android.util.Log.w("HGTV-PHONE", "orientation $value rejected: ${it.message}")
    }.isSuccess

    fun autoRotateEnabled(activity: Activity): Boolean = runCatching {
        android.provider.Settings.System.getInt(
            activity.contentResolver,
            android.provider.Settings.System.ACCELEROMETER_ROTATION,
            0,
        ) == 1
    }.getOrDefault(false)

    fun applyFrameMode(
        page: PhoneVideoPage,
        landscapeVideo: PlayerView,
        mode: VideoFrameMode,
        feedUnderlay: Boolean,
    ) {
        val target = mode.toFit().resizeMode()
        page.setResizeMode(target)
        landscapeVideo.resizeMode = target
        if (feedUnderlay) PhoneSession.setFeedVideoResize(target)
    }

    fun keepScreenOn(window: Window, on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

/**
 * 播放器 FIT 黑边几何与兜底遮罩。
 *
 * 正常有弹幕时，黑边由 `DanmakuView` 在自己的透明 Surface 内先画，再画弹幕，
 * 这样既挡住下层 Activity，也不会裁掉滚到黑边里的弹幕。弹幕层不可见时，
 * 这个普通 View 才作为兜底遮住黑边。
 */
internal class PhoneLetterboxMask(context: Context) : View(context) {
    private val paint = Paint().apply {
        color = Color.BLACK
        isAntiAlias = false
    }
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    private var fallbackScale = 1f
    private var videoView: View? = null

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun sync(
        videoView: View?,
        videoWidth: Int,
        videoHeight: Int,
        resizeMode: Int,
        videoScale: Float = 1f,
    ) {
        this.videoView = videoView
        sourceWidth = videoWidth
        sourceHeight = videoHeight
        this.resizeMode = resizeMode
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
        actualVideoBounds()?.let { return it }
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        val bounds = fallbackVideoBounds(hostWidth, hostHeight)
        if (bounds.width() <= 0f || bounds.height() <= 0f) return null
        if (resizeMode != AspectRatioFrameLayout.RESIZE_MODE_FIT) return bounds

        val sourceRatio = sourceWidth.toFloat() / sourceHeight
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

    private fun actualVideoBounds(): RectF? {
        val player = videoView as? PlayerView ?: return null
        if (player.visibility != VISIBLE) return null
        val frame = player.videoSurfaceView?.parent as? AspectRatioFrameLayout ?: return null
        if (frame.width <= 0 || frame.height <= 0) return null
        return RectF(
            (player.left + frame.left).toFloat(),
            (player.top + frame.top).toFloat(),
            (player.left + frame.right).toFloat(),
            (player.top + frame.bottom).toFloat(),
        )
    }

    private fun fallbackVideoBounds(hostWidth: Int, hostHeight: Int): RectF {
        val view = videoView
        if (view != null && view.visibility == VISIBLE && view.width > 0 && view.height > 0) {
            return RectF(
                view.left.toFloat(),
                view.top.toFloat(),
                view.right.toFloat(),
                view.bottom.toFloat(),
            )
        }
        val scaledWidth = hostWidth * fallbackScale
        val scaledHeight = hostHeight * fallbackScale
        val left = (hostWidth - scaledWidth) / 2f
        val top = (hostHeight - scaledHeight) / 2f
        return RectF(left, top, left + scaledWidth, top + scaledHeight)
    }
}
