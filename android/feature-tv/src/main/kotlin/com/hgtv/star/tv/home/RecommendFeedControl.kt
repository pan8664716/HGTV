// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

/**
 * 冷启动续播时把「取推荐流」推迟到首帧之后（[RECOMMEND_FEED_DELAY_MS] 兜底）。
 *
 * 为什么值得推迟：续播的动作是「读盘 + 解码」，一个字节的网络都不需要；
 * 而在这台盒子上解码初始化那 1~2 秒里，主线程与 CPU 都很紧（见 `playEpisode` 里
 * `primePlaybackClasses` 那段）。取流是一次 38KB 的 POST，插在这中间只会把首帧推后。
 *
 * 推迟不会让「新内容」晚太多：续播的首帧正常 0.7~1.2 秒，那时才发；
 * 而它本身就是补在列表末尾的（`recommendRestored` 分支只往后接），用户在看第一部时它早就回来了。
 * 兜底时间留到 8 秒，避免首次安装的注册、详情和播放准备较慢时，
 * 推荐补页在真正出画面前抢同一条下行。
 */
private const val RECOMMEND_FEED_DELAY_MS=8_000L

fun TvMainActivity.requestRecommendFeedAfterFirstFrame() {
    if(recommendFeedPending) return
    recommendFeedPending=true
    val timer=Runnable {
        recommendFeedTimer=null
        if(screen!="recommend") { recommendFeedPending=false; return@Runnable }
        fireRecommendFeed()
    }
    recommendFeedTimer=timer
    main.postDelayed(timer,RECOMMEND_FEED_DELAY_MS)
}

/** 真的去取那一页推荐（首帧已出 / 超时）。只生效一次。 */
fun TvMainActivity.fireRecommendFeed() {
    if(!recommendFeedPending) return
    recommendFeedPending=false
    recommendFeedTimer?.let { main.removeCallbacks(it) }
    recommendFeedTimer=null
    // 空回调：这一次只为把新条目接到列表末尾，不换正在播的这一部。
    loadRecommendPage(append=false) { }
}

fun TvMainActivity.cancelRecommendFeed() {
    recommendFeedPending=false
    recommendFeedTimer?.let { main.removeCallbacks(it) }
    recommendFeedTimer=null
}

/**
 * 丢掉所有预热好的相邻剧集（关闭画面、退出应用、内存吃紧时）。
 *
 * 推荐列表每次进页面都会重新取一页，这几份预热只在当前这一屏有意义；一旦离开推荐页
 * 就不再需要，留着只会白占内存（每部约 1MB）。
 */
fun TvMainActivity.closeRecommendCache() {
    cancelRecommendWarm()
    cancelScheduledPreload()
    cancelRecommendFeed()
    recommendPrepared.values.forEach { runCatching { it.close() } }
    recommendPrepared.clear()
    recommendPreparedIndex.clear(); recommendPreparedAt.clear(); recommendStore().clearWarm()
    recommendDetailLoading.clear()
}

/**
 * 取消某一部的在途预热（它正好要被起播 / 已经划过去了）。
 *
 * 三件事一起做：把键从「在途」里摘掉、把线程任务打断、把这次预热自己的取消域关掉。
 * 只做前两件时，那个线程还在 OkHttp 里读 1MB——结果没人要，带宽照样占着。
 */
fun TvMainActivity.cancelRecommendWarm(key: String) {
    recommendStore().cancelWarm(key)
    val warm=recommendWarmJobs.remove(key) ?: return
    warm.future?.cancel(true)
    warm.scope.cancel()
}

/** 取消全部在途预热（离开推荐页 / 进正片 / 退出应用）。 */
fun TvMainActivity.cancelRecommendWarm() {
    val jobs=recommendWarmJobs.values.toList()
    recommendWarmJobs.clear()
    recommendStore().clearWarm()
    jobs.forEach { warm -> warm.future?.cancel(true); warm.scope.cancel() }
}
