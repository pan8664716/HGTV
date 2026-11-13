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
        // 清屏状态下，第一下先把信息叫回来，不顺手暂停。
        if (!playerPage.isChromeVisible()) setChromeVisible(true) else togglePlay()
    }
    p.onLongPress = { zone ->
        when (zone) {
            PhoneTouch.Zone.CENTER -> openSettings()
            else -> beginTempSpeed()
        }
    }
    p.onLongPressEnd = { endTempSpeed() }
    p.onWatch = { openEpisodes() }
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
        progressDragging = true
        playerSeekFrom = player?.currentPosition ?: 0L
    }
    p.onDragDelta = { delta -> dragTo(delta) }
    p.onDragEnd = {
        progressDragging = false
        val duration = player?.duration ?: 0L
        if (duration > 0) player?.seekTo(playerSeekTarget)
        clearSeekHint()
    }
    // 上下滑换集：向上 = 下一集，向下 = 上一集（与推荐页上滑看下一条同一手感）。
    p.onVerticalStart = { beginSwipeForPage() }
    p.onVerticalDelta = { delta -> dragSwipeForPage(delta) }
    p.onVerticalEnd = { direction, _ -> endSwipeForPage(direction) }
}
