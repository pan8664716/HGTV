// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.hgtv.star.phone.ui.PhoneTouch

/** 建一页播放界面（当前页与相邻页同一份配置，否则翻页时两侧观感会不一样）。 */
internal fun PhonePlayerActivity.makePage(): PhoneVideoPage = PhoneVideoPage(
    this,
    withTabBar = false,
    fullscreenEntry = true,
    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
    // 上下滑换集（用户要求）：信息流里的同名手势是翻下一条视频，因此只在这里开。
    verticalSwipe = true,
    doubleTapPlayback = true,
    showLockButton = true,
    underlay = playerFeedUnderlay,
)

/**
 * 给一页接上手势回调。
 *
 * 两页都接同一份：只有**当前页**在屏幕正中、能收到触摸（翻页过程中触摸由当前页的
 * 状态机一直持有），因此回调里一律按 playerPage 取当前页，不会串到相邻页上。
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
    // 上下滑换集：向上 = 下一集，向下 = 上一集（与推荐页上滑看下一条同一手感）。
    p.onVerticalStart = { if (!playerControlsLocked) beginSwipeForPage() }
    p.onVerticalDelta = { delta -> if (!playerControlsLocked) dragSwipeForPage(delta) }
    p.onVerticalEnd = { direction, _ ->
        if (!playerControlsLocked) endSwipeForPage(direction)
    }
    p.setPlaybackLocked(playerControlsLocked)
}
