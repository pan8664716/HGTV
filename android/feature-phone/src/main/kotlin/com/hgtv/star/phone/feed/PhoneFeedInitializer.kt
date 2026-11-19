// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.viewpager2.widget.ViewPager2
import com.hgtv.star.phone.ui.PhoneColor
import com.hgtv.star.phone.ui.dp
import kotlinx.coroutines.launch

internal fun PhoneFeedView.initializeFeed() {
    setBackgroundColor(PhoneColor.BG)
    feedList.adapter = feedAdapter
    feedList.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
        override fun onPageScrolled(
            position: Int,
            positionOffset: Float,
            positionOffsetPixels: Int,
        ) {
            if (!feedList.userScroll || feedHidden || feedItems.isEmpty()) return
            val target = if (positionOffset > 0.5f) position + 1 else position
            if (target in feedItems.indices) updateFeedSwipePreviewImpl(target)
        }

        override fun onPageScrollStateChanged(state: Int) {
            if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
                finishFeedSwipePreviewImpl()
                if (feedList.userScroll) {
                    // 相邻页可能正在 0 音量预渲染首帧；开始滑动只暂停当前出声实例，
                    // 不能把即将上屏的那一页重新按停，否则过半时又要等解码器。
                    pausePooledPlayersExcept(feedPlayer, preservePreRendering = true)
                    pauseCurrentForFeedSwipeImpl()
                }
                return
            }
            if (state != ViewPager2.SCROLL_STATE_IDLE) return
            val index = feedList.currentItem
            val userScroll = feedList.userScroll
            feedList.userScroll = false
            if (userScroll) {
                if (index in feedItems.indices && index != feedPlayingIndex) {
                    playIndexForSettings(index, -1L, clearDanmakuOnSwitch = true)
                } else {
                    finishFeedSwipePreviewImpl()
                    resumeCurrentAfterFeedSwipeImpl()
                }
            }
            if (index >= 0 &&
                com.hgtv.star.shared.feed.RecommendFeedPolicy.shouldPrefetch(
                    feedItems.size - index - 1,
                )
            ) loadMore()
        }
    })
    addView(
        feedList,
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ),
    )
    addView(
        feedHint,
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = context.dp(64)
        },
    )

    feedScope.launch {
        feedLibrarySlot = PhoneServices.awaitLibrary(context)
        feedUserSpeed = feedLibrary?.playbackSpeed ?: 1f
        scheduleChromeHideImpl()
    }
    // 冷启动先铺落盘快照（电视端 RecommendCache 的同一条路）：
    // 列表与「上一次停在哪」都在盘上，首屏不必等取流那 1 秒，也不会每次都从同一部开始。
    restoreSnapshot()
    // 当前页由 PhoneRecommendPage.onShown() 触发加载；相邻体裁页先不取流，
    // 避免首屏同时发出多条推荐请求。
}

internal fun PhoneFeedView.pauseCurrentForFeedSwipeImpl() {
    if (feedHidden || PhoneSession.underlayActive || feedSwipePausedCurrent) return
    val exo = feedPlayer ?: return
    feedSwipePausedCurrent = true
    feedSwipeResumeCurrent = exo.playWhenReady || exo.isPlaying
    exo.playWhenReady = false
    runCatching { exo.pause() }
    PhoneServices.enableAudioFocus(exo, false)
    notifyPlayStateChangedImpl()
}

internal fun PhoneFeedView.resumeCurrentAfterFeedSwipeImpl() {
    if (!feedSwipePausedCurrent) return
    val resume = feedSwipeResumeCurrent
    clearFeedSwipePauseImpl()
    if (!resume || feedHidden) return
    feedPlayer?.let { exo ->
        pausePooledPlayersExcept(exo)
        exo.volume = 1f
        PhoneServices.enableAudioFocus(exo, true)
        exo.playWhenReady = true
    }
    notifyPlayStateChangedImpl()
}

internal fun PhoneFeedView.clearFeedSwipePauseImpl() {
    feedSwipePausedCurrent = false
    feedSwipeResumeCurrent = false
}

private fun PhoneFeedView.updateFeedSwipePreviewImpl(target: Int) {
    if (target < 0) {
        // 50% 边界处两个页面可能短暂都不到半屏；此时保留已经开始的预览，
        // 等当前页重新成为主导页或 IDLE 时再决定是否停止。
        return
    }
    if (target == feedPlayingIndex) {
        finishFeedSwipePreviewImpl()
        return
    }
    if (feedSwipePreviewIndex == target) {
        startFeedSwipePreviewImpl(target)
        return
    }
    stopFeedSwipePreviewImpl()
    feedSwipePreviewIndex = target
    startFeedSwipePreviewImpl(target)
}

internal fun PhoneFeedView.startFeedSwipePreviewImpl(index: Int): Boolean {
    if (feedHidden || index == feedPlayingIndex) return false
    pauseCurrentForFeedSwipeImpl()
    val slot = pooledPlayerSlot(index) ?: return false
    if (!slot.sourcePrepared || !slot.player.isFeedPlayerAlive()) return false
    pausePooledPlayersExcept(slot.player)
    feedPageAt(index)?.let { page -> attachPooledPlayerToPageImpl(page, index) }
    slot.preRendering = false
    slot.previewPlaying = true
    slot.player.volume = 1f
    PhoneServices.enableAudioFocus(slot.player, true)
    slot.player.playWhenReady = true
    return true
}

internal fun PhoneFeedView.stopFeedSwipePreviewImpl(index: Int = feedSwipePreviewIndex) {
    if (index < 0) return
    val slot = pooledPlayerSlot(index) ?: return
    if (slot.player === feedPlayer) {
        slot.previewPlaying = false
        return
    }
    slot.previewPlaying = false
    slot.player.volume = 0f
    slot.player.playWhenReady = false
    runCatching { slot.player.pause() }
    PhoneServices.enableAudioFocus(slot.player, false)
}

internal fun PhoneFeedView.finishFeedSwipePreviewImpl() {
    stopFeedSwipePreviewImpl()
    feedSwipePreviewIndex = -1
}

internal fun PhoneFeedView.prepareFeedSwipePreviewForCommit(index: Int) {
    val preview = feedSwipePreviewIndex
    feedSwipePreviewIndex = -1
    if (preview >= 0 && preview != index) stopFeedSwipePreviewImpl(preview)
}
