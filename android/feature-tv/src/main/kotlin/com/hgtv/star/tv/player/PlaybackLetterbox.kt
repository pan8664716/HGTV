// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.view.View
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout

/**
 * 把当前片源的 FIT 黑边同步给弹幕透明 Surface 与普通 View 兜底。
 *
 * 换集跨横竖比例时，`PlayerView` 会在首帧后改变内容框；若仍按上一集比例
 * 绘制黑边，透明 Surface 的新黑边区域会短暂露出窗口后面的页面。
 */
internal fun TvMainActivity.syncPlaybackLetterbox() {
    val video = playerView ?: return
    val danmaku = danmakuView ?: return
    val mask = playerLetterboxMask ?: return
    val hostWidth = danmaku.width.takeIf { it > 0 } ?: overlayLayer?.width ?: 0
    val hostHeight = danmaku.height.takeIf { it > 0 } ?: overlayLayer?.height ?: 0
    val scale = if (hostWidth > 0 && video.width > 0) {
        video.width.toFloat() / hostWidth
    } else {
        1f
    }
    mask.sync(
        videoBoundsProvider = null,
        videoWidth = videoWidth,
        videoHeight = videoHeight,
        fitMode = video.resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT,
        videoScale = scale,
    )
    val picture = if (hostWidth > 0 && hostHeight > 0) {
        mask.pictureBounds(hostWidth, hostHeight)
    } else {
        null
    }
    danmaku.letterboxRect = picture
    if (picture == null) {
        // 尺寸未到时整页已有黑底与换集封面；遮罩留在最上层会盖住中心加载三点。
        mask.visibility = View.GONE
        return
    }
    mask.visibility = if (danmaku.isSurfaceActive) {
        View.GONE
    } else {
        View.VISIBLE
    }
}
