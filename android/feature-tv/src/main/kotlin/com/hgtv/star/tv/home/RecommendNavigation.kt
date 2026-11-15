// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.view.View
import com.google.android.exoplayer2.Player
import com.hgtv.star.tv.list.showCatalog

/**
 * 确定键：进入正式播放界面。
 *
 * 这一刻才写观看历史（需求：只有进入正式播放界面才加入历史）。
 * [advanceNext] 为真时是「一集自动播完」那条路：进正片并从下一集接着播。
 */
fun TvMainActivity.enterFormalPlayback(advanceNext: Boolean=false) {
    if(screen!="recommend") return
    val data=detail
    // 没有详情（还在取 / 上一次整屏停在提示上）或画面已经被收掉（连续失败后的重试）：
    // 这一下确定键就是「重试」，重新起播当前这一部。
    if(data==null || playerView==null) {
        // 用户主动按确定 = 重新给一次机会：连续失败计数从这里重新算。
        recommendPlaybackFailures=0
        if(recommendItems.isEmpty()) openRecommend() else playRecommendAt(recommendIndex)
        return
    }
    // 先把状态切到正片：下面 playEpisode 会按进入时的 screen 决定留在哪一页。
    screen="player"
    // 进正片：过渡黑场立刻收掉（画面本来就没断，继续盖着会黑一下）。
    dismissRecommendCover()
    // 记下来源：从这里进的正片，返回键要直接回主页（需求 4），不回简介页。
    playbackFromRecommend=true
    // 在途的相邻预热到此为止：它们拉的是「别的剧」，进了正片就没人要了，
    // 留着只会和正片开头的「下一集预取」抢带宽（两条线程都在拉 1MB）。
    // 已经预热好的那几份留着（回推荐页还能用）。
    cancelRecommendWarm()
    cancelRecommendBatchModels()
    recommendToken.incrementAndGet()
    dismissRecommendOverlay()
    hud.visibility=View.GONE
    syncPlaybackInfo()
    // 这一页的控件是「推荐页」那一次建的（`playEpisode` 没有重跑），手势层 / 底部那一块 /
    // 进度拖动因此在这里重接一次（三者都幂等）。**必须先把推荐页那块摘掉**：
    // 那一块只有一枚剧名（点它进正片），正片里要的是六枚按键，两者内容不同。
    if(touchArmed) { attachTouchLayer(); resetTouchBar(); attachTouchBar(); wireSeekTouch() }
    // 进正片 = 开始正式观看：立刻把这一集记进历史。
    // 画面还没 READY 时 `saveProgress` 会拒绝写（时长未知），这时挂一个待写标记，
    // 由播放监听器的 READY 分支补上——「进了正片就一定有历史」不依赖时序。
    recommendHistoryPending=true
    saveProgress()
    // 正式开看：与从详情页进播放同一件事——「已看完」标记清掉，首页 / 历史里显示成「看到第几集」。
    library.setWatched(data.series.id,false)
    // 按确定进正片 = 明确要看这一部：直接按「看了」记 30 天冷却，并停掉停留计时，
    // 免得随后那次 `goHomeFromRecommend` 又把它记成一次「划走」（取两者更长的那个，本来也不会缩短，
    // 但少写一次盘）。
    recommendWatchingId=""; recommendWatchStart=0L
    recommendStore().markWatched(data.series)
    // 推荐页的「自动连播」只在播放器停在片尾时才有意义：如果这一集其实是**播到一半
    // 被别的路径（看门狗 / 队列故障）判成结束，`episodeIndex+1` 会直接跳掉剩下的内容。
    // 判据用播放器自己的位置，且只放行「确实走到结尾」这一种。
    val atEnd=runCatching {
        val p=player ?: return@runCatching false
        val duration=p.duration.coerceAtLeast(0)
        duration>0 && p.currentPosition>=duration-2_000
    }.getOrDefault(true)
    if(!advanceNext) {
        recommendPlaybackController.reportEventById(
            data.series.id,
            com.hgtv.star.core.RecommendBehavior.Event.CLICK,
            episodeId=data.episodes.getOrNull(episodeIndex).orEmpty(),
            episodeIndex=episodeIndex,
            positionMs=runCatching { player?.currentPosition ?: 0L }.getOrDefault(0L),
            durationMs=runCatching { player?.duration ?: 0L }.getOrDefault(0L),
        )
    }
    if(atEnd) {
        recommendPlaybackController.reportEventById(
            data.series.id,
            com.hgtv.star.core.RecommendBehavior.Event.PLAY_COMPLETE,
            episodeId=data.episodes.getOrNull(episodeIndex).orEmpty(),
            episodeIndex=episodeIndex,
            positionMs=runCatching { player?.currentPosition ?: 0L }.getOrDefault(0L),
            durationMs=runCatching { player?.duration ?: 0L }.getOrDefault(0L),
        )
    }
    if(advanceNext && atEnd) {
        if(episodeIndex<data.episodes.lastIndex) {
            // 上面那次 `saveProgress` 已经在「本集已播完」的状态下写了一条 completed 记录
            //（此刻播放器停在片尾、时长已知），因此这里只管起播下一集。
            playEpisode(episodeIndex+1,0L,autoplay=true)
        } else {
            advanceToNextSeasonOrFinish(data.series)
        }
    } else {
        // 停在当前这一集继续看：画面本来就没断，这里只把控制层交还给正式播放的按键。
        // 这一集已经播到结尾（手动按确定时正好赶上片尾）：从头再放一次这一集没有意义，
        // 直接按正片自己的连播规则往下走（有下一集就下一集，最后一集就交给「全剧已播完」）。
        val ended=runCatching { player?.playbackState==Player.STATE_ENDED }.getOrDefault(false)
        when {
            ended && episodeIndex<data.episodes.lastIndex -> playEpisode(episodeIndex+1,0L,autoplay=true)
            ended -> advanceToNextSeasonOrFinish(data.series)
            else -> {
                if(player?.isPlaying!=true && !playError) requestPlayback(true)
                showHud()
            }
        }
    }
}

/**
 * 回主页面（首页）。推荐页返回键与「从推荐进正片后按返回」共用这一条路（需求 4）。
 *
 * 与以前的区别有两点：
 * 1. 首页**一定重新取一次**（[showCatalog] 带 `load=true`）：用户从推荐页退出来，
 *    要看到的是首页最新的一批数据，而不是进来之前那一屏；
 * 2. 推荐列表本身**不清**（会话级缓存）：再点推荐按钮，接着上次那一部往下刷。
 */
fun TvMainActivity.goHomeFromRecommend() {
    // 离开推荐页：把这一部的停留时长结掉（看了 → 30 天冷却，划走 → 14 天）。
    settleRecommendWatch()
    // 补页在途 / 排队的连按意图一起作废：回到首页后它们已经没有去处，
    // 留着会让下次进推荐的第一次按键凭空多跨几部。
    recommendWaiters.clear(); recommendAdvancePending=0
    recommendToken.incrementAndGet(); recommendPageToken.incrementAndGet()
    recommendPlaybackFailures=0; recommendHistoryPending=false; playbackFromRecommend=false
    dismissRecommendCover()
    // 在途的「取推荐流」这一下作废：它的回调会因为令牌 / 页面不对而丢弃，
    // 这个标记必须在这里一起清掉，否则下次进推荐会一直以为还在加载、什么都不做。
    recommendLoading=false
    // 相邻剧集的预热：只留**当前这一部**那一份（约 1MB），其余收掉。
    //
    // 早先这里整份 `closeRecommendCache()`（连 `recommendItems` / `recommendDetails` 一起清），
    // 于是「主页 → 推荐」变成一次完整冷启动——用户读到的就是「缓存没用上」。
    // 现在：列表与详情留在内存（几十 KB），首集只保一份，回来时按原下标接着放，
    // 取地址 / 索引都命中磁盘（见 `PlaybackCache`）。
    cancelRecommendWarm()
    cancelScheduledPreload()
    evictRecommendPrepared(limit=1)
    dismissRecommendOverlay()
    // 推荐详情可能在途中留下「正在加载《…》」；离开推荐页后不能再把它带进首页 / 设置。
    hideCenterLoading()
    detail=null
    detachPlayerSurface(deferResources=true)
    screen="catalog"
    tab=TAB_HOME; page=1; collection=null; catalogFocus=""
    initialCatalogPending=false; restoreStoppedPage=false
    showCatalog(load=true)
}

/** 推荐页返回键：回主页面（首页）。不写历史、不回详情。 */
fun TvMainActivity.exitRecommendToHome() {
    if(screen!="recommend") return
    goHomeFromRecommend()
}

/**
 * 从推荐进入的正式播放，按返回**直接回主页**（需求 4）。
 *
 * 先按正式播放的规则写一次进度（这一集确实看过了），再走与推荐页返回同一条收尾：
 * 不经过简介页、首页重新取数据。
 */
fun TvMainActivity.leaveRecommendPlaybackToHome() {
    if(screen!="player") return
    sleepTimer.cancel(); sleepChoice=0; sleepStopped=false
    saveProgress(); library.flush(); invalidatePage()
    goHomeFromRecommend()
}
