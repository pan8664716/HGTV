// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.view.View
import com.hgtv.star.core.Series
import com.hgtv.star.core.detail
import com.hgtv.star.core.lruPut
import com.hgtv.star.core.StageDeadline
import com.hgtv.star.shared.debug.PlaybackStartupTrace
import com.hgtv.star.shared.debug.StartupBudgets
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
    val switchStartedAt=android.os.SystemClock.elapsedRealtime()
    fun switchTrace(label: String) {
        if(BuildConfig.DEBUG) {
            android.util.Log.i(
                "HGTV-SWITCH",
                "$label +${android.os.SystemClock.elapsedRealtime()-switchStartedAt}ms "+
                    "from=$recommendIndex to=$safe direction=$direction",
            )
        }
    }
    switchTrace("enter")
    // 换到另一部时先和上一部彻底断开：保留稳定的 PlayerView（避免快速连切反复
    // removeAllViews / 新建 Surface 导致 FrameLayout 布局崩溃），但清空旧 Surface 内容、
    // 停止旧媒体并作废旧 work。旧作品不能再发声，新作品也不会继承旧首帧。
    val series=recommendItems[safe]
    // 目标页通常已经由 offscreenPageLimit=1 常驻；先抓住它，切换后即使
    // ViewPager2 正在重新布局，也不需要把起播硬等到底部那次 layout。
    val targetPageBefore=tvPlaybackPager?.pageAt(safe)
    switchTrace("target-page-before=${targetPageBefore!=null}")
    val switching=safe!=recommendIndex && (player!=null || playerView!=null)
    if(switching) {
        // 旧活动实例先收进池：返回上一部时直接提升，不必重新取源。
        storeActivePlayerInTvPool()
        switchTrace("stored-active")
        player=null
        video=null
        playbackHttp=null
        playerView=null
        resetRecommendPlaybackForSwitch()
        switchTrace("reset-surface")
    }
    recommendIndex=safe; recommendShift=direction
    if(tvPlaybackPager!=null && tvPlaybackPager?.screen!="recommend") {
        switchTvPlaybackPagerScreen("recommend",safe)
    }
    tvPlaybackPager?.refreshPages()
    tvPlaybackPager?.setCurrentIndex(safe,smooth=false)
    switchTrace("pager-positioned")
    (tvPlaybackPager?.pageAt(safe) ?: targetPageBefore)?.let { playerView=it.playerView }
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
    fun startWhenPageReady(data: com.hgtv.star.core.Detail,entryToken: Long) {
        val pager=tvPlaybackPager
        if(pager==null) {
            startRecommendEpisode(data,position,autoplay)
            return
        }
        if(targetPageBefore!=null) {
            showCenterLoading("正在加载《${data.series.title}》…")
            startRecommendEpisode(data,position,autoplay)
            switchTrace("started-existing-page")
            return
        }
        pager.awaitPage(safe) {
            if(isDestroyed || screen!="recommend" || entryToken!=recommendToken.get()) return@awaitPage
            switchTrace("page-ready")
            // 等目标页完成布局后再挂 loading 文案，避免它把 pager 的下一帧顶后。
            showCenterLoading("正在加载《${data.series.title}》…")
            startRecommendEpisode(data,position,autoplay)
            switchTrace("started")
        }
    }
    // 硬切后没有播放层了：先在纯黑画面上显示当前作品的 loading，
    // 不能在详情缓存命中时直接带着上一部的根视图进入 playEpisode。
    if(playerView==null) {
        showRecommendCover()
        showRecommendLoading(series)
        switchTrace("loading-shown")
    }
    val playStarted=StartupTrace.now()
    StartupTrace.mark("recommend.play.start", "index=$safe cached=${cached!=null}")
    if(cached!=null) {
        StartupTrace.done("recommend.play.cached",playStarted)
        switchTrace("cached-detail")
        startWhenPageReady(cached,token)
        switchTrace("start-dispatched")
        return
    }
    if(playerView!=null) {
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
    val playbackDetailStarted=PlaybackStartupTrace.begin(
        "detail",
        "id=${series.id} cached=false",
    )
    StartupTrace.mark("recommend.detail.request", "index=$safe id=${series.id}")
    io.submit {
        // `cached=true`：推荐页起播只要「第 1 集是哪个 vid」。详情页仍然现取（见 `ContentRepository.detail`），
        // 但这里命中落盘缓存就省掉 0.4~0.6s——进程被回收过之后那一次尤其明显。
        val result=runCatching {
            StageDeadline("recommend.detail",StartupBudgets.DETAIL_MS).use { deadline ->
                deadline.run {
                    repository.detail(series.id,cached=true,includeAuthor=false)
                }
            }
        }
        main.post {
            StartupTrace.done(
                "recommend.detail.request",
                detailStarted,
                "index=$safe success=${result.isSuccess}",
            )
            PlaybackStartupTrace.done(
                "detail",
                playbackDetailStarted,
                "id=${series.id} success=${result.isSuccess}",
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
                startWhenPageReady(data,token)
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

/**
 * 推荐换作的稳定清屏：不重建整棵播放层，只把当前 PlayerView 的旧内容清掉。
 *
 * 快速连续换作时，整层 removeAllViews + 新建 PlayerView 会让旧 work、旧 Surface
 * 和布局遍历交错，既可能串音，也可能触发 FrameLayout 布局异常。这里保留视图，
 * 用 ExoPlayer 的 keep-content 开关让 Surface 回到黑屏，再复用同一个播放器实例。
 */
private fun TvMainActivity.resetRecommendPlaybackForSwitch() {
    // 先盖黑，再清旧内容；顺序不能反，否则清 Surface 的一帧可能露出上一部。
    // 推荐流不再加载 cover；相邻首块预热负责把新画面尽快接上。
    showRecommendCover()
    // 上下键换剧的那一刻就停住弹幕：弹幕加载是延迟调度的（见 loadDanmaku），
    // 若等它的 clear 落地，中间这段「正在获取地址 / 正在缓冲」里上一集的弹幕时钟
    // 还在按系统时钟外推，会继续走一段再被清掉，用户看到的就是"旧弹幕又跑了一下"。
    // clear() 同步停时钟 + 清 surface + 作废在途回包，新剧弹幕由随后的 load 挂起等首帧。
    danmakuLoader.clear()
    // 立即作废旧起播 work；只在后续 playEpisode 里等 generation 变化太晚，
    // 旧请求可能在这中间回来并重新挂上上一部音轨。
    generation++
    cancelWork()
    cancelPrefetch()
    val p=player
    val view=playerView
    playerListener?.let { listener -> p?.removeListener(listener) }
    playerListener=null
    view?.setKeepContentOnPlayerReset(false)
    view?.setShutterBackgroundColor(android.graphics.Color.BLACK)
    view?.player=null
    p?.let { runCatching { it.pause(); it.stop(); it.clearMediaItems() } }
    // player 保持 detach，但 PlayerView 本身保持 VISIBLE。
    // SurfaceView 一旦设为 INVISIBLE，系统会直接销毁它的 surface；新源准备好再挂回来时
    // 要先重建一层黑底（真机采样到视频层在切换那一刻消失），这就是「亮暗」里的一跳。
    // 旧帧仍然不会露出：setKeepContentOnPlayerReset(false) 让 ExoPlayer 的 shutter 显示黑，
    // 上面还有一整屏 recommendCover 盖着（普通 View，绘制在 SurfaceView 之上）。
    playbackReady=false
    playbackSessionState.beginSession()
    video?.let { runCatching { it.close() } }
    video=null
    closeTransport(playbackHttp)
    playbackHttp=null
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
            loadRecommendPage(
                append=true,
                minItemsPerLoad=recommendItems.size+1,
            ) {
                if(recommendToken.get()!=guard) return@loadRecommendPage
                val target=index+1
                if(target in recommendItems.indices) playRecommendAt(target,0L,1,skipped+1)
                else if(recommendItems.isNotEmpty()) playRecommendAt(0,0L,1,skipped+1)
                else showRecommendMessage("暂时没有可播放的推荐\n按确定键重试，按返回键回主页面")
            }
        }
    }
}
