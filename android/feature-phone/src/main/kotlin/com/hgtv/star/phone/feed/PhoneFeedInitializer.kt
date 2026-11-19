// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hgtv.star.phone.ui.PhoneColor
import com.hgtv.star.phone.ui.PhonePagerSnapHelper
import com.hgtv.star.phone.ui.dp
import kotlinx.coroutines.launch

internal fun PhoneFeedView.initializeFeed() {
    setBackgroundColor(PhoneColor.BG)
    feedList.layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
    feedList.adapter = feedAdapter
    feedList.overScrollMode = View.OVER_SCROLL_NEVER
    /*
     * 关掉这一层列表的**嵌套滚动**。
     *
     * 推荐页外面是横向 pager，本列表只吃纵向位移，横向那一份会被
     * `dispatchNestedPreScroll` 交给 pager——那条分发不经过 `onInterceptTouchEvent`，
     * 于是 pager 的「锁住横滑」形同虚设：设置里关掉「推荐页左右滑动」后，
     * 手指在视频上横着一划照样整页翻走，用户读到的就是「关了没生效」。
     *
     * 关掉它不影响任何纵向行为：上下滑、翻页吸附、惯性都是列表自己算的；
     * 横向手势此后只走标准的触摸分发（父 pager 的 `onInterceptTouchEvent`），
     * 那条路上才有锁。
     */
    feedList.isNestedScrollingEnabled = false
    PhonePagerSnapHelper { feedList.height }.attachToRecyclerView(feedList)
    var pendingVisiblePlay: Runnable? = null
    feedList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
        override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
            if (!feedList.userScroll || dy == 0 || feedHidden) return
            val index = dominantVisiblePage(rv)
            updateFeedSwipePreviewImpl(index)
            if (rv.scrollState != RecyclerView.SCROLL_STATE_SETTLING) return
            if (index < 0 || index == feedPlayingIndex) return
            pendingVisiblePlay?.let(feedList::removeCallbacks)
            val task = Runnable {
                pendingVisiblePlay = null
                if (feedList.userScroll && !feedHidden && index != feedPlayingIndex) {
                    playIndexForSettings(index, -1L, clearDanmakuOnSwitch = true)
                }
            }
            pendingVisiblePlay = task
            feedList.postDelayed(task, 90L)
        }

        override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
            if (state != RecyclerView.SCROLL_STATE_IDLE) {
                if (state == RecyclerView.SCROLL_STATE_DRAGGING) {
                    pendingVisiblePlay?.let(feedList::removeCallbacks)
                    pendingVisiblePlay = null
                    if (feedList.userScroll) pauseCurrentForFeedSwipeImpl()
                }
                return
            }
            pendingVisiblePlay?.let(feedList::removeCallbacks)
            pendingVisiblePlay = null
            val index = (rv.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
            val userScroll = feedList.userScroll
            feedList.userScroll = false
            if (userScroll && index >= 0 && index != feedPlayingIndex) {
                playIndexForSettings(index, -1L, clearDanmakuOnSwitch = true)
            } else if (userScroll) {
                stopFeedSwipePreviewImpl()
                resumeCurrentAfterFeedSwipeImpl()
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

/**
 * 目标页超过半屏就开始激活。
 *
 * `PagerSnapHelper` 在 `IDLE` 时才给出最终页；这里只在松手吸附后、滚动短暂停顿
 * 时提前接管，既避开手指拖动中的抖动，也避免快速滑行时逐页误激活。
 */
private fun dominantVisiblePage(list: RecyclerView): Int {
    var bestIndex = -1
    var bestVisible = 0
    for (childIndex in 0 until list.childCount) {
        val child = list.getChildAt(childIndex)
        val position = list.getChildAdapterPosition(child)
        if (position == RecyclerView.NO_POSITION) continue
        val visible = minOf(child.bottom, list.height) - maxOf(child.top, 0)
        if (visible > bestVisible) {
            bestVisible = visible
            bestIndex = position
        }
    }
    return if (bestVisible * 2 >= list.height) bestIndex else -1
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
    feedPlayer?.let {
        PhoneServices.enableAudioFocus(it, true)
        it.playWhenReady = true
    }
    notifyPlayStateChangedImpl()
}

internal fun PhoneFeedView.clearFeedSwipePauseImpl() {
    feedSwipePausedCurrent = false
    feedSwipeResumeCurrent = false
}

private fun PhoneFeedView.updateFeedSwipePreviewImpl(target: Int) {
    if (target < 0 || target == feedPlayingIndex) {
        stopFeedSwipePreviewImpl()
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

internal fun PhoneFeedView.prepareFeedSwipePreviewForCommit(index: Int) {
    val preview = feedSwipePreviewIndex
    feedSwipePreviewIndex = -1
    if (preview >= 0 && preview != index) stopFeedSwipePreviewImpl(preview)
}
