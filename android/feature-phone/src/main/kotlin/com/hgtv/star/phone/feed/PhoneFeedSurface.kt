// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.google.android.exoplayer2.Player
import android.view.View
import com.hgtv.star.phone.ui.resizeMode

internal fun PhoneFeedView.keepLivePlaybackImpl() {
    if (feedHidden) return
    if (PhoneSession.owner != PhoneSession.Owner.FEED) return
    if (!feedSessionBelongsToThisFeed()) return
    val exo = feedPlayer ?: return
    if (exo.playbackState == Player.STATE_READY && exo.playWhenReady && !exo.isPlaying) {
        runCatching { exo.play() }
    }
    if (PhoneSession.underlayActive) return
    val page = feedPageAt(feedPlayingIndex) ?: return
    if (page.playerView.player !== exo) page.playerView.player = exo
}

internal fun PhoneFeedView.bindFeedSurfaceForHandoffImpl() {
    PhoneSession.bindFeedSurface { force ->
        val index = feedPlayingIndex
        val page = feedBoundPage ?: feedPageAt(index) ?: return@bindFeedSurface false
        val series = feedItems.getOrNull(index) ?: return@bindFeedSurface false
        if (PhoneSession.seriesId != series.id) return@bindFeedSurface false
        val exo = feedPlayer ?: PhoneServices.peekPlayer() ?: return@bindFeedSurface false
        feedPlayerSlot = exo
        if (force && page.playerView.player === exo) page.playerView.player = null
        if (page.playerView.player !== exo) page.playerView.player = exo
        page.bind(series, "第${(feedCurrentEpisodeIndex[index] ?: 0) + 1}集")
        feedBoundPageSlot = page
        true
    }
    PhoneSession.feedVideoOffset = { dy ->
        val page = feedBoundPage ?: feedPageAt(feedPlayingIndex)
        page?.playerView?.translationY = dy
    }
    PhoneSession.feedVideoResize = { mode ->
        (feedBoundPage ?: feedPageAt(feedPlayingIndex))?.playerView?.resizeMode = mode
    }
    PhoneSession.feedVideoScale = { ratio ->
        (feedBoundPage ?: feedPageAt(feedPlayingIndex))?.setVideoScale(ratio)
    }
    PhoneSession.feedHoldFirstFrameAspectRatio = {
        (feedBoundPage ?: feedPageAt(feedPlayingIndex))?.playerView?.holdFirstFrameAspectRatio()
    }
    PhoneSession.feedCompleteFirstFrameAspectRatio = {
        (feedBoundPage ?: feedPageAt(feedPlayingIndex))?.playerView?.completeFirstFrameAspectRatio()
    }
}

internal fun PhoneFeedView.rebindCurrentPageImpl() {
    if (PhoneSession.owner != PhoneSession.Owner.FEED) return
    if (feedHidden || !feedSessionBelongsToThisFeed()) return
    val exo = feedPlayer ?: return
    val index = feedPlayingIndex
    if (index < 0 || index !in feedItems.indices) return
    val page = feedPageAt(index) ?: return
    val series = feedItems[index]
    if (page.playerView.player === exo && feedBoundPage === page) return
    attachPageForSurface(
        exo,
        page,
        series,
        feedCurrentEpisodeIndex[index] ?: 0,
        forceRebind = false,
    )
}

/**
 * 横竖屏后当前页尺寸变化，Surface 仍可能是旧尺寸。
 *
 * 同一台播放器留在同一个 `PlayerView` 上时，ExoPlayer 不会重新提交输出面；
 * 部分机型因此只剩声音。这里不改媒体源、不 seek，只把当前输出重新挂一次。
 */
internal fun PhoneFeedView.rebindCurrentSurfaceAfterSizeChangeImpl() {
    if (feedHidden || PhoneSession.owner != PhoneSession.Owner.FEED) return
    if (!feedSessionBelongsToThisFeed()) return
    val exo = feedPlayer ?: return
    val index = feedPlayingIndex
    if (index < 0 || index !in feedItems.indices) return
    feedList.post {
        if (feedHidden || feedPlayer !== exo || feedPlayingIndex != index) return@post
        if (PhoneSession.underlayActive) return@post
        val page = feedPageAt(index) ?: feedBoundPage?.takeIf {
            feedList.getChildAdapterPosition(it) == index
        } ?: return@post
        if (page.playerView.player === exo) page.playerView.detachPooledOutput()
        page.playerView.player = exo
        feedBoundPageSlot = page
    }
}

internal fun PhoneFeedView.setUnderlayModeImpl(on: Boolean) {
    if (on) {
        closeSheet()
        feedChromeEpoch++
        feedList.removeCallbacks(feedChromeHideRunnable)
    }
    // `pageAt` 在列表刚恢复布局 / 页面正在回收时可能取不到；此时用最后绑定的那一页。
    val page = feedPageAt(feedPlayingIndex) ?: feedBoundPage
    page?.setUnderlayMode(on)
    if (!on) {
        page?.playerView?.translationY = 0f
        revealChromeImpl(page)
    }
    // 收回画面时按用户的设置重刷弹幕显隐（关了弹幕的不要被顺手打开）。
    if (!on && page != null) applyDanmakuSettingsImpl(page)
    if (on) feedHint.visibility = View.GONE
}
