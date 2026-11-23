// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.hgtv.star.phone.ui.PhoneTouch

/** 建一页播放界面（ViewPager2 当前页与前后集同一份配置，否则翻页时两侧观感会不一样）。 */
internal fun PhonePlayerActivity.makePage(): PhoneVideoPage = PhoneVideoPage(
    this,
    withTabBar = false,
    fullscreenEntry = true,
    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
    // 竖屏上下翻集由外层 ViewPager2 接管；页面只处理点击、长按和横滑进度。
    verticalSwipe = false,
    doubleTapPlayback = true,
    showLockButton = true,
    underlay = playerFeedUnderlay,
    transparentForPaging = true,
)

/**
 * 给一页接上手势回调。
 *
 * 每个集页都接同一份；纵向拖动由外层 ViewPager2 接管，页面自己的状态机只处理
 * 点击、长按和横滑进度。
 */
internal fun PhonePlayerActivity.wirePage(p: PhoneVideoPage) {
    p.onTap = {
        setChromeVisible(!playerPage.isChromeVisible())
    }
    p.onDoubleTap = { if (!playerControlsLocked) togglePlay() }
    p.onLongPress = { zone ->
        if (!playerControlsLocked) {
            when (zone) {
                PhoneTouch.Zone.CENTER -> openSettings()
                else -> beginTempSpeed()
            }
        }
    }
    p.onLongPressEnd = { endTempSpeed() }
    p.onWatch = { openEpisodes() }
    p.onLock = { togglePlaybackLock() }
    p.onTitle = { detail?.series?.let { openSeries(it) } }
    p.onFullscreen = { enterFullscreen() }
    p.onSeekStart = { progressDragging = true }
    p.onSeek = { fraction -> showSeekHint(fraction) }
    p.onSeekEnd = { fraction ->
        progressDragging = false
        seekToForPage(fraction)
        clearSeekHint()
    }
    // 竖屏横滑也拖进度（起点离左右边缘 24dp 以外才接管，见 PhoneTouch.edgeSafe）。
    p.onDragStart = {
        if (!playerControlsLocked) {
            progressDragging = true
            playerSeekFrom = player?.currentPosition ?: 0L
        }
    }
    p.onDragDelta = { delta -> if (!playerControlsLocked) dragTo(delta) }
    p.onDragEnd = {
        if (!playerControlsLocked) {
            progressDragging = false
            val duration = player?.duration ?: 0L
            if (duration > 0) player?.seekTo(playerSeekTarget)
            clearSeekHint()
        }
    }
    p.setPlaybackLocked(playerControlsLocked)
}
