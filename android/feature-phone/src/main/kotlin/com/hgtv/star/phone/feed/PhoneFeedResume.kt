// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.os.SystemClock

internal fun PhoneFeedView.resumeOnShownImpl(): Boolean {
    cancelPoolIdleRelease()
    // 播放页是半透明窗口：转屏时下层本页可能也会收到 onResume。
    // 这时本页仍被播放页盖着，不能接手播放器、不能注销借 Surface 的入口，
    // 也不能把控制层透出来。真正退回本页时播放页会先清掉这个标志。
    if (PhoneSession.playerForeground) return false
    val restorePosition = takeRestorePositionMs()
    // 之前确实被收起来过（离开去播放页 / 退到后台）。重复通知时不要动 Surface：
    // 重挂一次 `player` 会让 `PlayerView` 先清掉再交出去，白闪一下。
    val resuming = feedHidden
    feedHiddenSlot = false
    feedPaused = false
    if (feedPlayingIndex in feedItems.indices && feedRecommendWatchingId.isEmpty()) {
        feedRecommendWatchingId = feedItems[feedPlayingIndex].id
        feedRecommendWatchStart = SystemClock.elapsedRealtime()
    }
    notifyPlayStateChangedImpl()
    // 回到本页：不再需要把 Surface 借给播放页，注销那个入口。
    // **不要在这里恢复本页的控制层**：此刻播放页只是 onPause，窗口还在上层，
    // 恢复出来的标题 / 进度条会透过它的透明底板double一次。等播放页 onDestroy
    // 通过 `PhoneSession.restoreFeedChrome()` 统一还回来。
    PhoneSession.bindFeedSurface(null)
    PhoneSession.feedVideoOffset = null
    PhoneSession.feedVideoResize = null
    PhoneSession.feedVideoScale = null
    // 上一次交接可能停在翻页动画中间：那块视频的位移要清掉，否则回来是歪的。
    feedBoundPage?.playerView?.translationY = 0f
    // 从正片回来：允许这一屏再次因片尾切走。
    feedAdvancing = false

    // 会话里的位置（播放页那边每秒刷新）：接手时按它对齐进度读数。
    // **只有会话属于本体裁流时才算数**：切 tab 时另一条流的那一部不能拿来对齐，
    // 否则本页的 `playingIndex` 会被改写成“另一条流正在播的那一集”。
    val sessionSameFeed = feedSessionBelongsToThisFeed()
    if (com.hgtv.star.shared.BuildConfig.DEBUG && !sessionSameFeed &&
        PhoneSession.feedKey.isNotEmpty() && PhoneSession.seriesId.isNotEmpty()
    ) {
        android.util.Log.i(
            "HGTV-PHONE",
            "feed switch genre=${feedCacheKey()} sessionFeed=${PhoneSession.feedKey} " +
                "sessionSeries=${PhoneSession.seriesId} keepIndex=$feedPlayingIndex"
        )
    }
    if (feedPlayingIndex < 0) {
        // 本视图是新建的（Activity 重建 / 体裁重铺），但进程里可能还在放这一部：
        // 先找它，找不到才从第一条起播。
        val resumed = adoptableIndexOf()
        if (resumed >= 0) {
            feedList.scrollToPosition(resumed)
            playIndexForSettings(resumed, restorePosition)
        } else {
            stopOrphanPlaybackImpl()
            if (feedItems.isNotEmpty()) playIndexForSettings(0, -1L)
        }
        return true
    }
    // 列表可能被重铺过（换体裁、快照恢复、Activity 重建）：本页记的下标这时指向的
    // 已经不是会话里那一部了。先按会话对齐下标，否则下面的「接手」会拿错卡片去比，
    // 判成接不上，接着重新取详情、从 0 起播——用户读到的就是「退回来内容断了」。
    // 切 tab 时 `sessionSameFeed` 为 false，这一段整个跳过：本页保持自己的下标。
    val sessionIndex = if (sessionSameFeed) adoptableIndexOf() else -1
    if (sessionIndex >= 0 && sessionIndex != feedPlayingIndex) {
        feedPlayingIndex = sessionIndex
        feedBoundPage?.playerView?.translationY = 0f
        feedList.scrollToPosition(sessionIndex)
    }
    if (adoptPlayingForResume(forceRebind = resuming)) {
        // 接上了：交接窗口到此为止，之后真的退到后台仍然要暂停。
        PhoneSession.endHandoff()
        return true
    }
    // 接不上（播放器已经 IDLE / 视图被重建过）但**会话里还是这部剧的这一集**：
    // 原地换一条地址接着放，不要退回 `playIndex`——那一条会重新取详情、重新读本机进度，
    // 用户看到的是「退回来从头开始」。
    if (reopenAtCurrentPositionForResume(feedPlayingIndex)) {
        PhoneSession.endHandoff()
        return true
    }
    stopOrphanPlaybackImpl()
    playIndexForSettings(feedPlayingIndex, restorePosition)
    return true
}
