// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import com.hgtv.star.core.Series
import com.hgtv.star.core.detail
import com.hgtv.star.core.lruPut
import com.hgtv.star.shared.debug.StartupTrace

/**
 * 播放推荐列表里的第 [index] 部。
 *
 * 详情有缓存就直接起播；没有就先整屏 / 浮层显示「正在加载《剧名》…」，取到再起播。
 * 取不到时自动跳到下一部（最多连跳 [RECOMMEND_SKIP_LIMIT] 部），不把用户卡在一屏错误上。
 */
fun TvMainActivity.playRecommendAt(index: Int,position: Long=0L,direction: Int=0,skipped: Int=0,autoplay: Boolean=true) {
    if(screen!="recommend" || recommendItems.isEmpty()) return
    val safe=index.coerceIn(0,recommendItems.lastIndex)
    recommendIndex=safe; recommendShift=direction
    // 换剧方向与时刻：相邻预热按「用户正在走的方向」排优先级（见 [prefetchRecommendNeighbors]）。
    // 方向为 0 时（进页面第一次起播、重试同一部）不动它，沿用上一次的方向。
    if(direction!=0) {
        recommendPrefetchDir=if(direction>0) 1 else -1
        recommendLastSwitchAt=android.os.SystemClock.elapsedRealtime()
    }
    // 双向滑动的窗口决策：速度 / 方向 / 回看三个判定都在这一步更新（见 `core/SlideCache.kt`）。
    // 换剧时先取消上一次排定的窗口预热——它是按旧锚点算的，晚一点照跑等于拉四条没用的。
    cancelScheduledPreload()
    slideCache.onItemChanged(safe,direction,android.os.SystemClock.elapsedRealtime())
    // 内存吃紧时收窄的窗口在这里放开：那一次 `onTrimMemory` 已经过去了。
    slideCache.resetTrim()
    if(slideCache.mode==com.hgtv.star.core.SlideCacheManager.Mode.FLING && BuildConfig.DEBUG) {
        android.util.Log.i("HGTV-PERF","preloadSkip reason=fling anchor=$safe")
    }
    val series=recommendItems[safe]
    // 这一部真的上屏了：记进剧级曝光窗口（`series_id` + 归一化剧名，7 天）。
    // 这是文档 §6.7 那个 `onExpose` 的等价物——只记**上过屏**的，不记「列表里有」的。
    recommendPlaybackController.rememberShown(series)
    // 换到另一部之前，先把上一部「待了多久 / 播了多久」结掉：那一条信号决定它进 14 天
    // 冷却还是 30 天冷却（见 [settleRecommendWatch]）。同一部重试时不动它。
    if(recommendWatchingId.isNotEmpty() && recommendWatchingId!=series.id) settleRecommendWatch()
    // 每一次「切到某一部」都换一次令牌：上一次那部还在取详情时，它的结果回来就作废。
    // （缓存命中这条早退路径也必须换，否则上一次在途的结果会把刚切过来的这一部顶掉。）
    val token=recommendToken.incrementAndGet()
    val cached=recommendDetails[series.id]
    val playStarted=StartupTrace.now()
    StartupTrace.mark("recommend.play.start", "index=$safe cached=${cached!=null}")
    if(cached!=null) {
        StartupTrace.done("recommend.play.cached",playStarted)
        startRecommendEpisode(cached,position,autoplay)
        return
    }
    if(playerView==null) showRecommendLoading(series)
    else {
        // 换剧那一下先把上一部停住：详情要几百毫秒才回来，这段时间里画面与声音
        // 若还是上一部，用户读到的就是「按了下键没换台」。
        player?.pause()
        recommendTitle?.text=series.title
        val episodeText=recommendEpisodeLabel(1,series.episodeCount)
        recommendEpisode?.text=episodeText
        recommendEpisode?.visibility=if(episodeText.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        // 换剧：详情还没回来，加载框显示在屏幕正中（浮层里那一行已经去掉了）。
        showCenterLoading("正在加载《${series.title}》…")
    }
    val detailStarted=StartupTrace.now()
    StartupTrace.mark("recommend.detail.request", "index=$safe id=${series.id}")
    io.submit {
        // `cached=true`：推荐页起播只要「第 1 集是哪个 vid」。详情页仍然现取（见 `ContentRepository.detail`），
        // 但这里命中落盘缓存就省掉 0.4~0.6s——进程被回收过之后那一次尤其明显。
        val result=runCatching { repository.detail(series.id,cached=true) }
        main.post {
            StartupTrace.done(
                "recommend.detail.request",
                detailStarted,
                "index=$safe success=${result.isSuccess}",
            )
            if(isDestroyed || screen!="recommend") return@post
            result.onSuccess { data ->
                // 详情回来了但用户已经划到别的部：**照样收进缓存**。
                // 早先这里连缓存一起丢掉（`token!=recommendToken` 直接 return），
                // 等于把一次已经付过 0.5s 的请求白扔——划回来又要重新等一次，
                // 连划时「每一步都在等网络」有一半来自这里。
                if(data.episodes.isNotEmpty()) recommendDetails.lruPut(RECOMMEND_DETAIL_LIMIT,series.id,data)
                if(token!=recommendToken.get()) return@post
                if(data.episodes.isEmpty()) {
                    // 预告 / 未上架：没有分集可播，直接跳过（推荐流里偶尔会混进这种）。
                    // 留一行日志：推荐页「一直转圈、一直不播」最常见的成因就是这种条目连着来，
                    // 光看界面只有「正在加载《…》」在换名字，看不出是被跳过的。
                    android.util.Log.w("HGTV","Recommend skip no episodes id=${series.id} title=${data.series.title} count=${data.series.episodeCount} upcoming=${data.upcoming}")
                    recommendPlaybackController.reportEvent(
                        series,
                        com.hgtv.star.core.RecommendBehavior.Event.ERROR,
                        reason="no_episodes",
                    )
                    skipRecommend(series,safe,direction,skipped)
                    return@post
                }
                // 同上：后台只把详情收进缓存，起播留给回到前台那一次。
                if(!foreground) return@post
                startRecommendEpisode(data,position,autoplay)
            }.onFailure { problem ->
                if(token!=recommendToken.get()) return@post
                android.util.Log.w("HGTV","Recommend detail failed id=${series.id}",problem)
                recommendPlaybackController.reportEvent(
                    series,
                    com.hgtv.star.core.RecommendBehavior.Event.ERROR,
                    reason="detail_failed",
                )
                skipRecommend(series,safe,direction,skipped)
            }
        }
    }
}

/** 这一部放不了：跳到下一部；连跳太多次就停在一句提示上，不无限空转。 */
private fun TvMainActivity.skipRecommend(series: Series,index: Int,direction: Int,skipped: Int) {
    if(skipped>=RECOMMEND_SKIP_LIMIT) {
        showRecommendMessage("《${series.title}》暂时无法播放\n按确定键重试，按返回键回主页面")
        return
    }
    val next=index+1
    when {
        next in recommendItems.indices -> playRecommendAt(next,0L,1,skipped+1)
        // 末尾先补一页再往下走（这条流到底了，补页那一侧会换一条新流）。
        // 补页发不出去 / 失败时回调会立刻到，那时列表没变长，就环绕回第一部，
        // 别让用户停在一屏「放不了」上。
        else -> {
            // 补页回调可能来得比用户下一手还晚：起播令牌变了（已经换了另一部 / 进了正片）
            // 就说明这一下补页的意图已经过时，直接放弃，别再把人拽回列表开头。
            val guard=recommendToken.get()
            loadRecommendPage(true) {
                if(recommendToken.get()!=guard) return@loadRecommendPage
                val target=index+1
                if(target in recommendItems.indices) playRecommendAt(target,0L,1,skipped+1)
                else if(recommendItems.isNotEmpty()) playRecommendAt(0,0L,1,skipped+1)
                else showRecommendMessage("暂时没有可播放的推荐\n按确定键重试，按返回键回主页面")
            }
        }
    }
}
