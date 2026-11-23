// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.hgtv.star.shared.ui.UiText
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
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
import com.google.android.exoplayer2.ExoPlayer
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
/**
 * 手机端统一的视频层：`TextureView` 版本。
 *
 * **不要再用代码 `new SurfaceView` 的 `PlayerView`**：`SurfaceView` 是独立图层，
 * 走 `composition type=DEVICE`（HWC 叠加层）合成，和 window 里的文字 / 按钮
 * 不在同一条更新路径上。放在 `ViewPager2` 这类滚动容器里翻页时，视频层会落后
 * 若干帧——用户读到的就是「文字滑过去了、视频没跟着走」，以及「翻页时露出一段
 * 既不是当前页也不是下一页的画面」。`TextureView` 与文字同 buffer 合成，不会错位。
 *
 * `surface_type` 必须来自 layout 属性（`app:surface_type="texture_view"`）：
 * `PlayerView` 在构造期读它，用 theme / `applyStyle` 合并进去读不到。
 */
internal fun createPhoneVideoPlayer(context: Context): PlayerView =
    LayoutInflater.from(context)
        .inflate(R.layout.phone_video_player, null, false) as PlayerView

internal class FixedFirstFramePlayerView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0,
) : PlayerView(context, attrs, defStyleAttr) {
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

/**
 * 强制把播放器重新交给这一块输出面。
 *
 * `PlayerView.setPlayer` 在实例相同时会直接返回；旋转后如果 View 上的 player 字段没变，
 * 但 ExoPlayer 的实际输出面已经被另一形态接管，单靠重新赋值不会恢复画面。
 *
 * 视频层现在是 `TextureView`（见 [createPhoneVideoPlayer]），走 `setVideoTextureView`；
 * 仍保留 `SurfaceView` 分支给低版本回退路径。
 */
internal fun PlayerView.forceBindVideoSurface(exo: ExoPlayer) {
    if (player !== exo) player = exo
    (videoSurfaceView as? SurfaceView)?.let { surface ->
        // ExoPlayer 对同一个 Surface 实例也会走“输出面没变”的短路。必须先清掉，
        // 再把 holder 交回去，渲染器才会重新执行 setOutputSurface / 重建解码输出。
        exo.clearVideoSurfaceView(surface)
        exo.setVideoSurfaceView(surface)
        surface.requestLayout()
        surface.invalidate()
        return
    }
    (videoSurfaceView as? android.view.TextureView)?.let { texture ->
        exo.clearVideoTextureView(texture)
        exo.setVideoTextureView(texture)
        texture.requestLayout()
        texture.invalidate()
    }
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


internal object PhonePlayerViewSupport {
    /**
     * 正式播放页只使用 SurfaceView。显式关闭上浮层级并设为 OPAQUE，
     * 避免 Surface 透明区域透出 Activity 或旧页面；弹幕那一层仍是透明 Surface，
     * 黑边由弹幕 Surface 自己绘制，不再额外挂普通 View 兜底层。
     */
    fun useOpaqueVideoSurface(view: PlayerView) {
        runCatching {
            val surface = view.videoSurfaceView as? SurfaceView ?: return@runCatching
            surface.setZOrderOnTop(false)
            surface.setZOrderMediaOverlay(false)
            surface.holder.setFormat(PixelFormat.OPAQUE)
            surface.setBackgroundColor(Color.BLACK)
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
        val label = if (locked) UiText.get(com.hgtv.star.shared.R.string.ui_0346) else UiText.get(com.hgtv.star.shared.R.string.ui_0350)
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

/** 返回播放器实际内容框在 [host] 坐标系里的位置；不是同一棵子树时返回 null。 */
internal fun PlayerView.visibleVideoBoundsIn(host: View): RectF? {
    if (visibility != View.VISIBLE || parent !== host.parent) return null
    val frame = videoSurfaceView?.parent as? AspectRatioFrameLayout ?: return null
    if (frame.width <= 0 || frame.height <= 0) return null
    return RectF(
        (left + frame.left).toFloat(),
        (top + frame.top).toFloat(),
        (left + frame.right).toFloat(),
        (top + frame.bottom).toFloat(),
    )
}
