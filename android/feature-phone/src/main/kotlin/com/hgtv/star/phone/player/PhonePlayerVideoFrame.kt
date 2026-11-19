// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.hgtv.star.phone.ui.dp

/** 把播放器当前的画面尺寸补到页面上（`bind` 之后调用）。 */
internal fun PhonePlayerActivity.syncVideoFrameImpl() {
    val size = player?.videoSize ?: return
    if (size.width <= 0 || size.height <= 0) return
    listenerDebugVideo = "${size.width}x${size.height}"
    playerPage.updateVideoFrame(size.width, size.height)
    updateLandscapeVideoFrameImpl(size.width, size.height)
}

internal fun PhonePlayerActivity.updateLandscapeVideoFrameImpl(width: Int, height: Int) {
    if (width <= 0 || height <= 0) return
    playerLandVideoWidth = width
    playerLandVideoHeight = height
    syncLandscapeLetterboxImpl()
    placeLandscapeFullscreenImpl()
}

/** 横屏弹幕 Surface 的黑边背景与兜底遮罩：只跟随实际视频画面，不跟随控制层。 */
internal fun PhonePlayerActivity.syncLandscapeLetterboxImpl() {
    if (
        !playerLandscapeVideoInitialized ||
        !playerLandDanmakuInitialized ||
        !playerLandLetterboxInitialized
    ) return
    val scale = if (playerLandscape.width > 0 && playerLandscapeVideo.width > 0) {
        playerLandscapeVideo.width.toFloat() / playerLandscape.width
    } else {
        1f
    }
    playerLandLetterbox.sync(
        videoBoundsProvider = {
            playerLandscapeVideo.visibleVideoBoundsIn(playerLandLetterbox)
        },
        videoWidth = playerLandVideoWidth,
        videoHeight = playerLandVideoHeight,
        fitMode = playerLandscapeVideo.resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT,
        videoScale = scale,
    )
    val picture = playerLandLetterbox.pictureBounds(
        playerLandscape.width,
        playerLandscape.height,
    )
    playerLandDanmaku.letterboxRect = picture
    playerLandLetterbox.visibility =
        if (
            playerLandDanmaku.isSurfaceActive &&
            picture != null
        ) View.GONE
        else View.VISIBLE
}

/**
 * 横屏全屏入口只给竖版片源。
 *
 * 位置取视频 FIT 后的真实下边缘，再受底部控制层上沿限制。竖版视频在横屏里
 * 通常占满高度，若只看画面下边缘，按钮会直接落到控制层后面。
 */
internal fun PhonePlayerActivity.placeLandscapeFullscreenImpl() {
    if (!playerLandFullscreenInitialized || !playerLandscapeVideoInitialized) return
    val portraitVideo = playerLandVideoHeight > playerLandVideoWidth
    if (!playerLandscapeMode || !playerChromeVisible || !portraitVideo) {
        playerLandFullscreen.visibility = View.GONE
        return
    }
    if (playerLandscape.width <= 0 || playerLandscape.height <= 0) return

    playerLandFullscreen.visibility = View.VISIBLE
    val videoWidth = playerLandscapeVideo.width.takeIf { it > 0 } ?: playerLandscape.width
    val videoHeight = playerLandscapeVideo.height.takeIf { it > 0 } ?: playerLandscape.height
    val ratio = playerLandVideoWidth.toFloat() / playerLandVideoHeight
    val videoRatio = videoWidth.toFloat() / videoHeight
    val pictureHeight = if (ratio >= videoRatio) videoWidth / ratio else videoHeight.toFloat()
    val videoTop = playerLandscapeVideo.top.toFloat()
    val pictureBottom = videoTop + (videoHeight + pictureHeight) / 2f

    val buttonHeight = playerLandFullscreen.measuredHeight.takeIf { it > 0 } ?: dp(18)
    val gap = dp(10)
    val bottomLimit = if (playerLandBottomInitialized && playerLandBottom.height > 0) {
        playerLandBottom.top
    } else {
        playerLandscape.height
    }
    val maxTop = bottomLimit - buttonHeight - gap
    val top = (pictureBottom - buttonHeight - gap)
        .coerceAtMost(maxTop.toFloat())
        .coerceAtLeast(0f)
        .toInt()
    val lp = playerLandFullscreen.layoutParams as FrameLayout.LayoutParams
    if (lp.topMargin != top) {
        lp.topMargin = top
        playerLandFullscreen.layoutParams = lp
    }
    if (playerLandFullscreen.measuredHeight <= 0) {
        playerLandFullscreen.post {
            if (playerLandscapeMode && playerLandFullscreen.isAttachedToWindow) {
                placeLandscapeFullscreenImpl()
            }
        }
    }
}

/**
 * 画面缩放（设置 → 播放偏好，1–100%）。
 *
 * 只缩播放画面本身：竖屏改 `PhoneVideoPage` 的布局宽高，横屏改另一块 `PlayerView`，
 * 页面底板仍是整屏黑。
 */
internal fun PhonePlayerActivity.applyVideoScaleImpl() {
    val ratio = ((library?.videoScale ?: 100f) / 100f).coerceIn(.1f, 1f)
    playerPage.setVideoScale(ratio)
    applyLandscapeVideoScaleImpl(ratio)
    if (playerFeedUnderlay) PhoneSession.setFeedVideoScale(ratio)
}

/** 横屏画面缩放：改布局宽高，缩小时四周露出黑底。 */
private fun PhonePlayerActivity.applyLandscapeVideoScaleImpl(ratio: Float) {
    playerLandscapeVideo.scaleX = 1f
    playerLandscapeVideo.scaleY = 1f
    val lp = playerLandscapeVideo.layoutParams as? FrameLayout.LayoutParams ?: return
    if (ratio >= .999f) {
        lp.width = FrameLayout.LayoutParams.MATCH_PARENT
        lp.height = FrameLayout.LayoutParams.MATCH_PARENT
    } else {
        val w = playerLandscape.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val h = playerLandscape.height.takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels
        lp.width = (w * ratio).toInt().coerceAtLeast(1)
        lp.height = (h * ratio).toInt().coerceAtLeast(1)
    }
    lp.gravity = Gravity.CENTER
    playerLandscapeVideo.layoutParams = lp
    playerLandscapeVideo.requestLayout()
    syncLandscapeLetterboxImpl()
}
