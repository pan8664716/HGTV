// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.hgtv.star.core.RecommendTab
import com.hgtv.star.shared.data.DeviceRegistration
import com.hgtv.star.shared.debug.PlaybackStartupTrace
import com.hgtv.star.shared.feed.RecommendFeedPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 冷启动先铺落盘快照：列表 + 「上一次停在哪」。
 *
 * 与电视端 `MainActivityRecommend` 的恢复逻辑同一套语义，只是简化成手机上够用的那部分：
 * 快照里的条目直接上屏、从**上一次停的那一部的下一部**接着播（不是永远第 1 条），
 * 之后由首帧后的 [loadMore] 在后台把新的一页补进来。快照过期 / 没有就什么都不做。
 */
internal fun PhoneFeedView.restoreSnapshot() {
    if (feedItems.isNotEmpty()) return
    val hiddenIds = runCatching { PhoneServices.library(context).hidden() }.getOrDefault(emptySet())
    val usable = feedRecommendStore.restoreSnapshot(hiddenIds)
    if (usable.isEmpty()) return
    feedNotifyDataSetChanged()
    // 起点 = 上一次停的那一部的下一条（到末尾就回到第一条）。
    val last = runCatching { com.hgtv.star.core.RecommendCache.lastPlayed(feedCacheKey()) }
        .getOrDefault("")
    val lastIndex = usable.indexOfFirst { it.id == last }
    val start = if (lastIndex >= 0) (lastIndex + 1) % usable.size else 0
    feedPlayingIndex = start
    // 还没上屏的那一条只摆好列表与下标，不出声音：真正起播由 `onShown()` 负责。
    if (!feedHidden) feedPlayIndex(start)
    // 滚到这一屏要等布局完成（此刻高度可能还是 0）。
    feedPostScrollToPosition(start)
    if (com.hgtv.star.shared.BuildConfig.DEBUG) {
        android.util.Log.i("HGTV-PHONE", "feed restore items=${usable.size} start=$start last=$last")
    }
}

/**
 * 取下一页推荐。
 *
 * 规则与电视端 `loadRecommendPage` **同源**（那是踩出来的，手机端不该重新踩一遍）：
 *
 * * **续流游标落盘**（按体裁分开，见 [com.hgtv.star.core.FeedCursor]）：接口是有状态的，
 *   `session_id` 传空 + `offset=0` 永远回同一套默认列表，于是「每次打开都是这几部」；
 * * 曝光记录不再回传 `filter_ids`；取页只回传当前列表少量 ID 给服务端去重；
 * * 不再按曝光过的 ID / 剧名删除推荐流卡片；
 * * 仍过滤隐藏、追完和当前列表内已加载的重复项，一页全被滤光就接着取下一页（最多 3 页）；
 * * 游标不前进（服务端把 `next_offset` 钳住）或首屏空页：退回流头重取一次。
 */
internal fun PhoneFeedView.loadMore() {
    if (feedRecommendStore.loading || !feedRecommendStore.hasMore) return
    feedRecommendStore.loading = true
    val traceStarted=PlaybackStartupTrace.begin(
        "feed.load",
        "genre=${feedGenre ?: "all"} items=${feedItems.size} restored=${feedRecommendStore.restored}",
    )
    feedScope.launch {
        val library = feedLibrarySlot ?: PhoneServices.awaitLibrary(context).also { feedLibrarySlot = it }
        if (!feedCursorRestored) {
            feedCursorRestored = true
            feedRecommendStore.restoreCursor()
        }
        val oldSize = feedItems.size
        // “推荐（全部）”入口与 TV 使用同一份推荐范围；明确的体裁 tab 仍只取自己的体裁。
        val selectedGenres = feedGenre?.let { listOf(it) }
            ?: library.recommendGenres()
        val input=feedRecommendStore.input(
            // 首屏从快照恢复时按“恢复列表”处理，和 TV 一样使用首屏预算；
            // 只有用户已经滑到列表末尾后的补页才走 append。
            append=feedItems.isNotEmpty() && !feedRecommendStore.restored,
            genres=selectedGenres,
            tab=RecommendTab.fromGenre(feedGenre),
            // 首次无快照只要求一个可播放条目：先起播，剩余条目由首帧后的补页完成。
            minItemsPerLoad=if(
                feedItems.isEmpty() && feedPlayingIndex<0 && !feedRecommendStore.restored
            ) 1 else RecommendFeedPolicy.MIN_ITEMS_PER_LOAD,
            // 书库可能已完成主设备注册，但主仓库仍可能是空身份。
            ensureRegistered = {
                DeviceRegistration.ensureBound(
                    library = library,
                    repository = PhoneServices.repository(context),
                )
            },
        )
        val result = withContext(Dispatchers.IO) {
            runCatching { feedRecommendStore.load(input) }.getOrNull()
        }
        feedRecommendStore.loading = false
        if (result == null) {
            PlaybackStartupTrace.done("feed.load",traceStarted,"result=null")
            if (feedItems.isEmpty()) feedNotifyDataSetChanged()
            refreshAfterIdentity()
            return@launch
        }
        feedRecommendStore.apply(result)
        // 引擎在后续页失败时会带回已经成功取到并过滤好的部分结果。
        // 先上屏这部分，再保留 `ended=false` 让下一次滑动继续补页。
        when {
            oldSize == 0 && feedItems.isNotEmpty() -> feedNotifyDataSetChanged()
            feedItems.size > oldSize -> feedNotifyItemRangeInserted(oldSize, feedItems.size - oldSize)
            feedItems.size != oldSize -> feedNotifyDataSetChanged()
        }
        if (feedItems.isEmpty()) feedNotifyDataSetChanged()
        PlaybackStartupTrace.done(
            "feed.load",
            traceStarted,
            "items=${feedItems.size} added=${(feedItems.size-oldSize).coerceAtLeast(0)} " +
                "pages=${result.pages}",
        )
        if (feedItems.isNotEmpty()) {
            PlaybackStartupTrace.point("feed.firstItem","genre=${feedGenre ?: "all"}")
        }
        if (com.hgtv.star.shared.BuildConfig.DEBUG) {
            android.util.Log.i(
                "HGTV-PHONE",
                "feed page genre=${feedGenre ?: "all"} offset=${feedRecommendStore.nextOffset} " +
                    "added=${(feedItems.size - oldSize).coerceAtLeast(0)} " +
                    "ended=${!feedRecommendStore.hasMore} pages=${result.pages} restarts=${result.restarts}",
            )
        }
        if (feedPlayingIndex < 0 && feedItems.isNotEmpty() && !feedHidden) {
            val resumed = adoptableIndexOf()
            if (resumed >= 0) {
                feedScrollToPosition(resumed)
                feedPlayIndex(resumed)
            } else {
                feedPlayIndex(0)
            }
        }
        refreshAfterIdentity()
    }
}

/**
 * 首帧之后补齐首屏推荐。
 *
 * 首条内容优先用于起播；这里只补列表余量，不参与首帧判定。READY 与
 * onRenderedFirstFrame 都可能调用，[feedFirstFrameFillQueued] 保证只排一次。
 */
internal fun PhoneFeedView.scheduleFeedFillAfterFirstFrame() {
    if(feedFirstFrameFillQueued || feedHidden ||
        feedItems.size>=RecommendFeedPolicy.MIN_ITEMS_PER_LOAD
    ) return
    feedFirstFrameFillQueued=true
    feedPostDelayed({
        feedFirstFrameFillQueued=false
        if(!feedHidden && feedItems.size<RecommendFeedPolicy.MIN_ITEMS_PER_LOAD) {
            loadMore()
        }
    },300L)
}

internal fun PhoneFeedView.refreshAfterIdentity() {
    if(!feedIdentityRefreshPending || feedItems.isNotEmpty()) return
    feedIdentityRefreshPending = false
    // 匿名身份可能已经把这一轮判成“没有更多”。换到注册身份后这是一条新流，
    // 从空游标重来；已有内容绝不重排或打断。
    feedRecommendStore.hasMore = true
    feedRecommendStore.nextOffset = 0
    feedRecommendStore.sessionId = ""
    feedCursorRestored = true
    if (!feedHidden) loadMore()
}

/**
 * 会话里那一部在当前列表中的位置；没有 / 不在列表里返回 -1。
 *
 * 判据与 [adoptPlaying] 一致（[PhoneSession.live]，不看地址新鲜度）：
 * 地址过期的那一路照样定位过去，由 [reopenAtCurrentPosition] 原地换地址，
 * 而不是从第一条重新起播。
 */
internal fun PhoneFeedView.adoptableIndexOf(): Int {
    if (!feedSessionBelongsToThisFeed()) return -1
    val sid = PhoneSession.seriesId
    if (sid.isBlank() || !PhoneSession.live(sid)) return -1
    return feedItems.indexOfFirst { it.id == sid }
}
