// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

internal fun PhoneFeedView.setGenreImpl(value: String?) {
    if (value == feedGenre) return
    feedGenre = value
    feedRecommendStoreSlot = newRecommendStoreForGenre()
    feedRecommendPlaybackSlot = newRecommendPlaybackForGenre()
    // 换体裁 = 换一条流：把上一类还在飞的 playIndex 作废（详情 / 取地址
    // 晚回来时不能再把媒体源挂上去，否则新一类的首屏会被旧内容顶掉）。
    feedPlayTokenSlot++
    feedChromeEpoch++
    feedList.removeCallbacks(feedChromeHideRunnable)
    feedRecommendFailures = 0
    feedPlayingIndex = -1
    feedItems.clear()
    feedOffset = 0
    feedSessionId = ""
    feedEnded = false
    feedLoadingSlot = false
    // 换体裁 = 换一条流：游标与落盘快照都要换成那一类自己的
    //（三条流各存各的，见 FeedCursor 与 RecommendCache.fileOf）。
    feedCursorRestored = false
    feedFilterMiss = 0
    feedFirstFrameFillQueued = false
    feedNotifyDataSetChanged()
    // 新的一类先铺它自己的快照：首屏不必等取流，也不会铺出上一类的卡片。
    restoreSnapshot()
    if (!feedHidden) loadMore()
}

internal fun PhoneFeedView.onShownImpl() {
    if (!resumeOnShownImpl()) return
    // 当前页真正上屏后才取流；有快照时先播快照，补流由首帧回调触发。
    if (feedItems.isEmpty()) loadMore()
    // 进程里已经有画面时不会再有新的首帧回调，这里补一次延后补流。
    if (feedItems.isNotEmpty() && feedPlayer?.isPlaying == true) {
        scheduleFeedFillAfterFirstFrame()
    }
    // 设置页可能刚改过弹幕（显示方式 / 速度 / 字号 / 透明度）：回到这一页时重刷一次，
    // 否则要等下一次换集才看得到，用户读到的就是「弹幕设置不生效」。
    feedPageAt(feedPlayingIndex)?.let { applyDanmakuSettingsImpl(it) }
    // 这一路接完（集号 / 进度条都已经对齐）**之后**才把控制层还回来。
    // 顺序反了就是用户读到的「退回来内容断了一下」：控制层先亮，标题、集号、
    // 进度条还是离开前的那一份，过一拍才跳到正确的位置。
    PhoneSession.ensureFeedChrome()
    revealChromeImpl(currentChromePageImpl())
}

internal fun PhoneFeedView.onIdentityReadyImpl() {
    feedIdentityRefreshPending = true
    if (!feedLoading) refreshAfterIdentity()
}
