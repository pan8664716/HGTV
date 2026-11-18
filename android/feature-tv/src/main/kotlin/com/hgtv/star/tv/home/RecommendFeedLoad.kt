// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import com.hgtv.star.shared.debug.StartupTrace
import com.hgtv.star.shared.debug.PlaybackStartupTrace
import com.hgtv.star.shared.architecture.network

/**
 * 取一页推荐流。
 *
 * [append] 为假是首次进入（重置列表），为真在末尾接一页（上下键走到最后一部时补货）。
 * 结果落地前会核对令牌与当前页面：中途退出 / 重新进入，旧结果直接丢掉。
 *
 * [depth] / [restarts] 只给本函数自己递归用：前者数「这一次加载已经连取了几页」，
 * 后者数「这一次加载已经换了几条新流」，两个上限见 [RECOMMEND_MAX_CHAIN] /
 * [RECOMMEND_MAX_RESTARTS]。
 */
fun TvMainActivity.loadRecommendPage(
    append: Boolean,
    @Suppress("UNUSED_PARAMETER") depth: Int = 0,
    @Suppress("UNUSED_PARAMETER") restarts: Int = 0,
    noFilter: Boolean = false,
    minItemsPerLoad: Int = RECOMMEND_FIRST_ITEMS,
    onReady: () -> Unit,
) {
    val traceStarted=StartupTrace.now()
    StartupTrace.mark("recommend.feed.request", "append=$append")
    if(recommendLoading) {
        StartupTrace.mark("recommend.feed.join", "append=$append")
        recommendWaiters += onReady
        return
    }
    val playbackTrace=PlaybackStartupTrace.begin(
        "feed.load",
        "append=$append minItems=$minItemsPerLoad",
    )
    recommendLoading = true
    val token = recommendPageToken.incrementAndGet()
    val input=recommendStore().input(
        append = append,
        noFilter = noFilter,
        genres = library.recommendGenres(),
        minItemsPerLoad = minItemsPerLoad,
        ensureRegistered = {
            // 书库可能已完成主设备注册，但主仓库仍是空身份。
            // 这里按 ID 做一次幂等补齐，只有不一致时才重绑。
            com.hgtv.star.shared.data.DeviceRegistration.ensureBound(library, repository)
        },
    )
    appScope.network {
        StartupTrace.mark("recommend.feed.worker.start", "append=$append")
        val loaded = runCatching {
            recommendStore().load(input)
        }
        main.post {
            StartupTrace.done("recommend.feed.request",traceStarted, "append=$append")
            PlaybackStartupTrace.done(
                "feed.load",
                playbackTrace,
                "append=$append success=${loaded.isSuccess}",
            )
            if(isDestroyed || token != recommendPageToken.get() || screen != "recommend") {
                recommendAdvancePending = 0
                recommendAdvanceWaiterQueued = false
                settleRecommendLoad()
                return@post
            }
            recommendLoading = false
            loaded.onSuccess { result ->
                recommendStore().apply(result)
                recommendFilterMiss = result.filterMiss
                recommendFilterUnsupported = result.filterIdsOff
                recommendGenderOff = result.genderOff
                recommendGenderProbe = result.genderProbe
                recommendSession = result.sessionId
                recommendNext = result.nextOffset
                val currentSeriesId=recommendItems.getOrNull(recommendIndex)?.id
                recommendItems = result.items
                // 补页会合并新旧列表；不能只保留旧数字下标。按剧 ID 重映射，
                // 否则列表一变，用户按“上一条”就会落到错误位置，甚至一直停在第一条。
                if(currentSeriesId!=null) {
                    val remapped=result.items.indexOfFirst { it.id==currentSeriesId }
                    if(remapped>=0) recommendIndex=remapped
                    else recommendIndex=recommendIndex.coerceIn(0,result.items.lastIndex.coerceAtLeast(0))
                } else if(result.items.isNotEmpty()) {
                    recommendIndex=recommendIndex.coerceIn(0,result.items.lastIndex)
                }
                recommendPage = result.page
                recommendHasMore = result.hasMore
                if(result.error == null) recommendRestored = false
                PlaybackStartupTrace.point(
                    "feed.firstItem",
                    "items=${result.items.size} pages=${result.pages} append=$append",
                )

                val feedError=result.error
                if(feedError != null) {
                    StartupTrace.mark(
                        "recommend.feed.result",
                        "append=$append error=${feedError.javaClass.simpleName} items=${result.items.size}",
                    )
                    android.util.Log.w("HGTV", "Recommend feed failed", feedError)
                    if(recommendItems.isEmpty()) {
                        showRecommendMessage("推荐暂时取不到\n按确定键重试，按返回键回主页面")
                    } else {
                        showRecommendToast("后面的推荐暂时取不到")
                        onReady()
                    }
                    settleRecommendLoad()
                    return@onSuccess
                }
                if(result.empty) {
                    StartupTrace.mark("recommend.feed.result", "append=$append empty=true")
                    showRecommendMessage("推荐暂时取不到\n按确定键重试，按返回键回主页面")
                    settleRecommendLoad()
                    return@onSuccess
                }
                if(!foreground) {
                    StartupTrace.mark("recommend.feed.result", "append=$append foreground=false")
                    settleRecommendLoad()
                    return@onSuccess
                }
                onReady()
                StartupTrace.mark(
                    "recommend.feed.result",
                    "append=$append items=${result.items.size} page=${result.page}",
                )
                settleRecommendLoad()
            }.onFailure { problem ->
                StartupTrace.mark(
                    "recommend.feed.result",
                    "append=$append failure=${problem.javaClass.simpleName}:${problem.message.orEmpty().take(120)}",
                )
                android.util.Log.w("HGTV", "Recommend feed failed", problem)
                if(recommendItems.isEmpty()) {
                    showRecommendMessage("推荐暂时取不到\n按确定键重试，按返回键回主页面")
                } else {
                    showRecommendToast("后面的推荐暂时取不到")
                    onReady()
                }
                settleRecommendLoad()
            }
        }
    }
}

/**
 * 快刷到这一批末尾时，提前把下一页取回来。
 *
 * 为什么需要：补页是有网络的（实测一页 0.8~2s）。以前只在**按到最后一部**那一下才知道
 * 要补页，于是每一次补页都必然是「停在最后一部 → 黑场 → 等网络 → 跳到下一部」——
 * 用户读到的就是「每隔十几部有一个画面没有播放、被略过」。
 * 提前 [RECOMMEND_LOOKAHEAD]（20）部开始取，等真按到末尾时新条目已经在列表里，
 * 切换就是即时的一帧。
 *
 * 只在**用户自己的按键**之后调用（见 `startRecommendEpisode`），不在取页成功里递归调用：
 * 那样一旦某一页一条新内容都筛不出来，就会变成无限次网络往返。
 */
fun TvMainActivity.ensureRecommendLookahead() {
    if(screen!="recommend" || recommendLoading || !recommendHasMore) return
    // 快照 / 首条起播期间，补页由首帧后的 requestRecommendFeedAfterFirstFrame 负责。
    // 这里如果立即补，会把新推荐流插到注册、详情、取地址和首帧之间。
    if(recommendRestored) return
    if(recommendItems.isEmpty()) return
    if(!com.hgtv.star.shared.feed.RecommendFeedPolicy.shouldPrefetch(
            recommendItems.size-recommendIndex-1,
        )) return
    val now=android.os.SystemClock.elapsedRealtime()
    // 限流 1.5s：连按上下键时每一下都会走到这里，不该每一下都上去要一页。
    if(now-recommendLookaheadAt<1_500L) return
    recommendLookaheadAt=now
    // 空回调：这一次只为把下一页取到手上，不换剧。取不到也不提示——用户还没按到末尾。
    loadRecommendPage(
        append=true,
        minItemsPerLoad=recommendItems.size+1,
    ) { }
}

/**
 * 一次取页真正结束（成功 / 失败 / 放弃都算）：把补页期间排队的那几下按键放出来。
 *
 * 只在主线程调用；排队的回调自己带令牌守卫，过期的那几个会变成空操作。
 * 排空后再清空列表，因此重复调用无害（正常情况只有一次非空）。
 */
private fun TvMainActivity.settleRecommendLoad() {
    if(recommendWaiters.isEmpty()) return
    val queued=recommendWaiters.toList()
    recommendWaiters.clear()
    queued.forEach { callback -> runCatching { callback() } }
}
