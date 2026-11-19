// SPDX-License-Identifier: GPL-3.0-only
@file:JvmName("MainActivityPlaybackKt")
@file:JvmMultifileClass
package com.hgtv.star.tv

import com.hgtv.star.shared.compat.hasInternet
import com.hgtv.star.shared.compat.isMeteredConnection

import android.app.Activity
import android.view.*
import android.widget.*
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.mediacodec.MediaCodecRenderer
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.ui.PlayerView
import com.hgtv.star.core.*
import com.hgtv.star.shared.data.WatchProgress
import com.hgtv.star.shared.data.PlaybackCapability
import com.hgtv.star.shared.data.AppRuntime
import com.hgtv.star.shared.media.VideoDataSource
import com.hgtv.star.shared.compat.setVectorIcon
import com.hgtv.star.tv.player.PlaybackMenu
import com.hgtv.star.shared.media.MediaSessionHost
import com.hgtv.star.tv.TvDialog
import com.hgtv.star.shared.compat.attachedToWindow
import com.hgtv.star.shared.architecture.network
import com.hgtv.star.shared.debug.NetSpeed
import com.hgtv.star.tv.R

// 本文件是从 TvMainActivity.kt 拆出来的「播放中控：缓冲抑制、下一集预取、重试恢复、HUD 与传输层回收」这一块。
// 这些成员仍然属于 TvMainActivity，只是以扩展成员（TvMainActivity.xxx）的形式放在这里，
// 逻辑与拆之前逐行一致；改这里等于改 Activity 本体。

/**
 * 收掉一集的传输层。
 *
 * 2026-10-19 起播放链路共用 [com.hgtv.star.shared.data.PlaybackTransport.client]：
 * **不关连接池、不 shutdown dispatcher**，只由 `RemoteVideo.close()` 取消这一集自己的 call。
 *
 * 为什么改：线上崩溃是 h2 读线程在平台 TLS 里越界——
 * 每集新建 client、切集时 `cancelAll + evictAll + shutdown`，在途的 TLS 连接被关，
 * 而 h2 的读线程正阻塞在 `read()` 里，异常无人接住直接打死进程（见 PlaybackTransport 的类注释）。
 * 连接池留着复用之后，「在途连接被关闭」这件事本身就不再发生。
 */
fun TvMainActivity.closeTransport(http: okhttp3.OkHttpClient?) {
    if (http == null) return
    if (http === com.hgtv.star.shared.data.PlaybackTransport.client) return
    // 兜底：万一以后有别的 client 走到这里（当前没有），仍按老办法收掉。
    appScope.network { http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
}
/**
 * 无感切集时的缓冲指示：过渡那一两百毫秒不转圈，画面停在上一集最后一帧就够了。
 *
 * 默认只关到新源 READY 为止，而且**最多 1.2 秒**——万一预热的数据被换出、新源还得走网络，
 * 转圈该回来就得回来，不能让用户对着一帧静止画面干等。跨季时解码器可能原地重配，
 * 调用方可以传更长的首帧窗口。
 */
fun TvMainActivity.suppressBufferingUi(timeoutMs: Long=1200L) {
    bufferingSuppressed=true
    syncBufferingDots()
    main.removeCallbacks(restoreBuffering); main.postDelayed(restoreBuffering,timeoutMs)
}
fun TvMainActivity.restoreBufferingUi() {
    if(!bufferingSuppressed) return
    bufferingSuppressed=false
    main.removeCallbacks(restoreBuffering)
    syncBufferingDots()
}

/** 把 ExoPlayer 的缓冲状态映射到统一三点动画；过渡窗口内临时隐藏。 */
fun TvMainActivity.syncBufferingDots() {
    val currentId=detail?.episodes?.getOrNull(episodeIndex).orEmpty()
    val mediaId=player?.currentMediaItem?.mediaId.orEmpty()
    val actuallyPlaying=player?.isPlaying==true &&
        player?.playbackState==Player.STATE_READY &&
        currentId.isNotEmpty() && mediaId==currentId
    if(actuallyPlaying) playbackReady=true
    val frameUp=actuallyPlaying && pictureUpNow()
    if(frameUp && recommendCover==null && centerLoadingMessage!=null) {
        hideCenterLoading()
    }
    val label=when {
        playError || sleepStopped -> null
        frameUp -> null
        !playbackReady -> if(video==null) "正在获取播放地址…" else "正在缓冲…"
        player?.playbackState==Player.STATE_BUFFERING -> "正在缓冲…"
        else -> null
    }
    // 屏幕正中已经有一枚「正在加载《…》」时，这一枚让位：两个三点 + 两行文案叠在一起
    // 就是用户说的「几个堆积在一起」。全站同一时刻只留一处等待。
    val centerBusy=centerLoadingMessage!=null
    val show=!bufferingSuppressed && label!=null && !centerBusy
    bufferingDots?.apply {
        this.label=label.orEmpty()
        visibility=if(show) View.VISIBLE else View.GONE
        if(show) bringToFront()
    }
}
/**
 * 上一集的视频与传输：等新源出画面（READY）、这一集出错、或退出播放时再关。
 *
 * [scheduleRetire] 是兜底：万一新源一直没到 READY（切走、出错这些极端路径），
 * 3 秒后也把它收掉——那时候旧源早就被换下、不可能还在读，收掉不会打断任何东西。
 */
fun TvMainActivity.scheduleRetire() {
    main.removeCallbacks(retireForced); main.postDelayed(retireForced,3000)
}
/**
 * 强制收掉旧资源：调用方**刚刚整条替换 / 清空**了播放列表，那一项已经不可能再被读。
 *
 * 与 [retirePreviousEpisode] 的区别只有一条：不再等自动切集的稳定清理令牌。
 */
fun TvMainActivity.forceRetirePrevious() {
    cancelAutoTransitionCleanup()
    retirePreviousEpisode()
}
fun TvMainActivity.retirePreviousEpisode() {
    main.removeCallbacks(retireForced)
    // 播完的那一项还挂在播放列表里时不能关：播放线程可能仍在释放旧 period。
    // 必须等新集 READY + 首帧、旧项确实从列表移除后再关。
    if(playedItemPending) return
    retiringVideo?.let { runCatching { it.close() } }
    retiringVideo=null
    closeTransport(retiringHttp); retiringHttp=null
}
/** 作废当前自动切集的清理任务；下一次自动切集会生成新令牌。 */
fun TvMainActivity.cancelAutoTransitionCleanup() {
    transitionCleanupToken++
    transitionMediaId=""
    transitionReady=false
    transitionFirstFrame=false
    transitionDropRequested=false
    transitionConfirmAttempts=0
    playedItemPending=false
    playedItemId=""
}
/**
 * 开始一次自动切集清理：只记录旧项，不在这里删除。
 *
 * 新集必须同时满足「播放器 READY」和「首帧已上屏」，才允许修改播放列表。
 */
fun TvMainActivity.startAutoTransitionCleanup(mediaId: String,playedId: String) {
    transitionCleanupToken++
    transitionMediaId=mediaId
    transitionReady=player?.playbackState==Player.STATE_READY
    transitionFirstFrame=lastRenderedMediaId==mediaId
    transitionDropRequested=false
    transitionConfirmAttempts=0
    playedItemPending=playedId.isNotEmpty()
    playedItemId=playedId
    val token=transitionCleanupToken
    main.post { if(!isDestroyed && token==transitionCleanupToken) maybeDropPlayedItem() }
}
/** 新集 READY 后补一次稳定条件。 */
fun TvMainActivity.markAutoTransitionReady(mediaId: String) {
    if(mediaId.isEmpty() || mediaId!=transitionMediaId) return
    transitionReady=true
    maybeDropPlayedItem()
}
/** 新集首帧后补一次稳定条件。 */
fun TvMainActivity.markAutoTransitionFirstFrame(mediaId: String) {
    if(mediaId.isEmpty()) return
    lastRenderedMediaId=mediaId
    if(mediaId!=transitionMediaId) return
    transitionFirstFrame=true
    maybeDropPlayedItem()
}
/**
 * 只有新集 READY 且首帧已上屏，才移除上一集。
 *
 * `removeMediaItem` 只是把任务交给播放线程；这里发起后仍保持 `playedItemPending=true`，
 * 等 `onTimelineChanged` 或下一拍确认旧项真的不在列表里，再关闭它的数据源。
 */
private fun TvMainActivity.maybeDropPlayedItem() {
    val token=transitionCleanupToken
    if(!playedItemPending || transitionDropRequested || !transitionReady || !transitionFirstFrame) return
    val p=player ?: return
    if(p.currentMediaItem?.mediaId!=transitionMediaId) return
    val index=p.currentMediaItemIndex
    val target=if(index>0) {
        (0 until index).firstOrNull { p.getMediaItemAt(it).mediaId==playedItemId }
    } else null
    if(target==null) {
        // 旧项已经不在列表里：只等播放线程确认一次。
        transitionDropRequested=true
        transitionConfirmAttempts=0
        main.postDelayed({ if(!isDestroyed && token==transitionCleanupToken) confirmPlayedItemRemoved() },200)
        return
    }
    transitionDropRequested=true
    transitionConfirmAttempts=0
    if(runCatching { p.removeMediaItem(target) }.isFailure) {
        transitionDropRequested=false
        return
    }
    // onTimelineChanged 正常会先到；这里再兜一拍，防止个别播放器不回调。
    main.postDelayed({ if(!isDestroyed && token==transitionCleanupToken) confirmPlayedItemRemoved() },200)
}
/**
 * 播放线程确认旧项已经不在列表里后，再释放它持有的视频与传输。
 *
 * 这里不直接信任 `removeMediaItem()` 的返回：该 API 返回时删除消息可能还在播放线程队列中。
 */
fun TvMainActivity.confirmPlayedItemRemoved() {
    if(!playedItemPending || !transitionDropRequested) return
    val p=player ?: return
    if(p.currentMediaItem?.mediaId!=transitionMediaId) return
    val stillListed=playedItemId.isNotEmpty() && (0 until p.mediaItemCount).any {
        p.getMediaItemAt(it).mediaId==playedItemId
    }
    if(stillListed) {
        if(transitionConfirmAttempts++<8) {
            val token=transitionCleanupToken
            main.postDelayed({
                if(!isDestroyed && token==transitionCleanupToken) confirmPlayedItemRemoved()
            },250)
        }
        return
    }
    playedItemPending=false
    playedItemId=""
    transitionDropRequested=false
    retirePreviousEpisode()
}
/**
 * 把**已经预热好**的下一集挂成播放器的第二个 MediaItem。
 *
 * 为什么这样才不卡：`playEpisode` 那条路必然调用 `setMediaSource`，ExoPlayer 会
 * `resetInternal → disableRenderer → releaseCodec`，再重新 `prepare` 一遍，
 * 于是有黑屏、有转圈、有解码器重建。挂进队列则不重置播放器：片尾
 * `maybeUpdatePlayingPeriod` 只是把 playing period 前移一格
 * （`DISCONTINUITY_REASON_AUTO_TRANSITION`）。
 *
 * **注意**：这不保证解码器一定复用。前后两集的编码参数、分辨率、音轨配置不同，
 * 或设备/渲染器不支持原地切换时，`MediaCodecRenderer` 仍可能重新配置甚至重建解码器。
 * 队列化保证的是「不重设媒体源、不重置播放器、不走 BUFFERING 全流程」；
 * 是否真的复用，看 debug 日志里 `Decoder init` 是否每集都出现。
 *
 * 挂的时机不是片尾那一下，而是预热结果一落地（[maybePrefetch] 只在片尾 30 秒窗口内发起）。
 * 挂上去之后由 ExoPlayer 自己决定何时 prepare：`shouldLoadNextMediaPeriod()` 要求
 * 「当前 period 已完全缓冲」，所以提前挂不会抢正在播的这一集的带宽。
 */
fun TvMainActivity.enqueuePreparedNext() {
    val p=player ?: return
    val data=detail ?: return
    // 用户按过暂停 / 息屏 / 切后台：不要挂下一集，否则回来一按播放就自己跳到下一集了。
    if(!foreground || screen!="player" || playError || sleepStopped || !requestedAutoplay || pausedForLifecycle) return
    if(!library.autoNext) return
    // 按集数计的定时必须在片尾那一下拦住，因此这种定时下不挂队列（走 STATE_ENDED 老路）。
    // 按分钟计的定时由 tick 自己停，和片尾无关，仍然可以无感连播。
    if(sleepTimer.episodesLeft>0) return
    if(queuedEpisodeId!=null) return
    // 不在片尾窗口里也没关系：`MediaPeriodQueue.shouldLoadNextMediaPeriod()` 要求
    // 「当前 period 已完全缓冲」才会真的去 prepare 下一集，因此早挂不会抢当前这一集的带宽，
    // 只是把「预热结果 → 播放器知道有下一集」这一步提前做完，片尾推进时少一次等待。
    val seasonBoundary=episodeIndex==data.episodes.lastIndex
    val targetDetail=if(seasonBoundary) {
        nextSeasonDetail?.takeIf {
            it.episodes.isNotEmpty() && nextSeason?.id==it.series.id
        }
    } else data
    val target=targetDetail ?: return
    val id=if(seasonBoundary) target.episodes.firstOrNull()
        else target.episodes.getOrNull(episodeIndex+1)
    if(id==null) return
    val quality=playbackMaxQuality
    val key=preparedKeyFor(id)
    // **先读时刻、再取走**：`take` 会清空槽位，之后读不到 readyAt。
    // `queuedAt` 记的是「地址是什么时候取的」，不是「什么时候挂进队列」——
    // 两者最多差 2 分钟（预取完成到入队之间可能隔着一次暂停），按入队时刻算会让
    // 10 分钟的窗口实际变成 12 分钟（见 `PreparedSlot.readyAtOf`）。
    val fetchedAt=preparedNext.readyAtOf(key)
    val remote=preparedNext.take(key) ?: return
    val http=prefetchHttp.also { prefetchHttp=null }
    if(http==null) { remote.close(); return }
    queuedEpisodeId=id; queuedVideo=remote; queuedHttp=http; queuedQuality="${quality}P"
    queuedDetail=if(seasonBoundary) targetDetail else null
    queuedAt=if(fetchedAt>0L) fetchedAt else android.os.SystemClock.elapsedRealtime()
    val source=ProgressiveMediaSource.Factory { VideoDataSource(remote) }
        .createMediaSource(MediaItem.Builder().setMediaId(id).setUri("hgtv://episode/$id")
            .setMimeType(com.google.android.exoplayer2.util.MimeTypes.VIDEO_MP4).build())
    // 挂失败（列表被别处清空、播放器正好在重建）就当没预热过：把资源收回来，
    // 片尾仍然走 `STATE_ENDED → playEpisode` 那条兜底，不会留下半截状态。
    if(runCatching { p.addMediaSource(source) }.isFailure) {
        queuedEpisodeId=null; queuedVideo=null; queuedHttp=null; queuedQuality=""; queuedDetail=null; queuedAt=0
        runCatching { remote.close() }; closeTransport(http)
        return
    }
    android.util.Log.i(
        "HGTV",
        "${if(seasonBoundary) "Next season" else "Next episode"} queued id=$id quality=$quality"
    )
}
/**
 * tick 里的兜底：预热结果落地时那一次 post 可能被别的重建吞掉，这里再补一次。
 *
 * 同时负责**把躺太久的队列项换掉**：队列项里装的是带时效的播放地址，用户可能在片尾
 * 暂停十几分钟再继续，那时 ExoPlayer 会在片尾直接推进到这一项，根本不经过
 * `playEpisode` 的年龄校验（见 [com.hgtv.star.core.PlaybackCache.PREPARED_FRESH_MS]）。
 * 这里提前把它摘掉，下一次 tick 会按新地址重新预热 / 重新挂队列。
 */
fun TvMainActivity.maybeQueueNext() {
    val now=android.os.SystemClock.elapsedRealtime()
    if(queuedEpisodeId!=null) {
        if(com.hgtv.star.core.PlaybackCache.isPreparedFresh(queuedAt,now)) return
        // 地址已经躺过期：摘掉（连同播放列表里那一项），让下面重新走一次预热。
        //
        // **不动 `preparedNext`**：它有自己的 2 分钟 TTL，如果刚刚落地一份新的，
        // 下面 `isFresh` 会立刻把它挂上队列；清掉它等于白扔一次已经付过网络的预热。
        android.util.Log.i("HGTV","Queued episode url too old; dropping id=$queuedEpisodeId age=${(now-queuedAt)/1000}s")
        dropQueuedEpisode()
    }
    val data=detail ?: return
    val seasonBoundary=episodeIndex==data.episodes.lastIndex
    val nextId=if(seasonBoundary) {
        nextSeasonDetail?.takeIf { nextSeason?.id==it.series.id }?.episodes?.firstOrNull()
    } else data.episodes.getOrNull(episodeIndex+1)
    if(nextId==null) return
    if(!preparedNext.isFresh(preparedKeyFor(nextId))) return
    enqueuePreparedNext()
}
/**
 * 摘掉队列里的下一集。
 *
 * 只摘**当前项之后**的那一段：当前 period 被保留，ExoPlayer 不会为此 seek 或重建渲染器。
 * 用户手动切集 / 换清晰度 / 退出播放器时调用——那时候新源要顶上来，队列里的旧目标必须让位。
 */
fun TvMainActivity.dropQueuedEpisode() {
    val p=player
    var removed=true
    if(p!=null && p.mediaItemCount>1) {
        val index=p.currentMediaItemIndex
        removed=runCatching { p.removeMediaItems(minOf(index+1,p.mediaItemCount),p.mediaItemCount) }.isSuccess
    }
    queuedEpisodeId=null; queuedQuality=""; queuedDetail=null; queuedAt=0
    // 没摘掉就别关：那一项还在播放列表里，ExoPlayer 随时可能去读它的数据源。
    // 这种情况只会出现在播放器正在重建的瞬间，退出播放器时整条列表一起释放。
    if(removed) {
        queuedVideo?.let { runCatching { it.close() } }
        closeTransport(queuedHttp)
    } else {
        // 摘不掉：所有权移交给 [releasePlayer] 统一释放，避免泄漏或过早关闭。
        orphanResources+=queuedVideo to queuedHttp
    }
    queuedVideo=null; queuedHttp=null
}
/**
 * 片尾自动推进到下一集之后的状态交接。
 *
 * 这一刻 ExoPlayer 已经把 playing period 换成下一集（旧 period 已 release），
 * 所以这里只做「界面与记录跟上」：集号、标题、弹幕、清晰度、媒体会话，
 * 以及把播完那一集的进度按 completed 落盘（`STATE_ENDED` 不会再触发，必须在这里记）。
 */
fun TvMainActivity.onEpisodeAdvanced(next: Int,mediaId: String) {
    val data=detail ?: return
    if(next !in data.episodes.indices) return
    finishSeamlessAdvance(data,next,mediaId,data)
}

/**
 * 片尾从上一季最后一集直接推进到下一季第 1 集。
 *
 * 播放器换 period 的动作和同季连播完全一样；不同的只是分集表、剧名与播放模型
 * 要在这里一起换成下一季的详情。
 */
fun TvMainActivity.onSeasonAdvanced(target: Detail,mediaId: String) {
    if(target.episodes.isEmpty()) return
    val previous=detail ?: return
    episodePanel?.dismiss(); episodePanel=null
    finishSeamlessAdvance(target,0,mediaId,previous)
}

private fun TvMainActivity.finishSeamlessAdvance(
    nextData: Detail,
    next: Int,
    mediaId: String,
    previousData: Detail,
) {
    val p=player ?: return
    if(next !in nextData.episodes.indices) return
    if(screen=="player") {
        showRecommendCover(
            nextData.episodeCover(nextData.episodes[next]).ifBlank { nextData.series.cover }
        )
    }
    val previous=episodeIndex
    val queuedFor=queuedAt
    advancedAt=android.os.SystemClock.elapsedRealtime()
    if(screen=="player") {
        val coverToken=recommendCoverToken
        main.postDelayed({
            if(!isDestroyed && screen=="player" && recommendCoverToken==coverToken &&
                PlaybackDiag.lastFrameAt>=advancedAt) {
                revealRecommendCover()
            }
        },600L)
    }
    // 播完那一集：用切走之前记下的时长写一条 completed 记录。
    // 这里**不能**读 `p.duration`：回调到达时播放器已经在报下一集的时长了。
    // 时长未知（这一集没走到 READY）就不写：写一条 0/0 的「已看完」比不写更糟，
    // 它会把这个剧的续播位置直接推到下一集开头。
    if(previous in previousData.episodes.indices && lastEpisodeDuration>0) {
        val duration=lastEpisodeDuration
        // 整集看完的写法与手机端共用 `:core`（`WatchProgress.completed`）：
        // 四个字段（位置 / 时长 / completed / 时间）不会再有一端只改一半。
        val progress=WatchProgress.completed(previousData.series,previousData.episodes[previous],previous,duration)
        library.save(progress); syncProgress(progress,previousData.episodes.size)
        if(playbackFromRecommend) {
            recommendPlaybackController.reportEventById(
                previousData.series.id,
                com.hgtv.star.core.RecommendBehavior.Event.PLAY_COMPLETE,
                episodeId=previousData.episodes[previous],
                episodeIndex=previous,
                positionMs=duration,
                durationMs=duration,
            )
        }
    }
    // 新一集的时长还没读到：先清零，等 READY / tick 按新 mediaId 重新采样。
    // 不清的话，新一集若在 READY 之前就出错，写出去的是上一集的长度。
    lastEpisodeDuration=0
    // 旧一集的视频与传输交给兜底回收（旧 period 刚 release，晚一点关更稳）。
    retirePreviousEpisode()
    // 极端情况下上一次切集的旧项还没确认移除：先把它的资源托管到播放器释放时，
    // 避免下面覆盖 `retiringVideo` 后永久丢失。
    if(playedItemPending) {
        orphanResources+=retiringVideo to retiringHttp
        retiringVideo=null; retiringHttp=null
    }
    retiringVideo=video; retiringHttp=playbackHttp
    video=queuedVideo; playbackHttp=queuedHttp
    queuedVideo=null; queuedHttp=null; queuedEpisodeId=null
    quality=video?.info?.quality ?: queuedQuality
    queuedQuality=""; queuedDetail=null; queuedAt=0
    episodeIndex=next
    detail=nextData
    sessionSeriesId=nextData.series.id
    if(screen=="player") {
        releaseTvPoolSlotAt(next)
        tvPlaybackPager?.refreshPages()
        tvPlaybackPager?.setCurrentIndex(next,false)
        tvPlaybackPager?.pageAt(next)?.let { playerView=it.playerView }
        tvPlaybackPager?.syncCurrentPlayerWhenReady(next)
        tvPlaybackPager?.post {
            if(!isDestroyed && screen=="player" && episodeIndex==next) {
                tvPlaybackPager?.pageAt(next)?.let { playerView=it.playerView }
                tvPlaybackPager?.syncCurrentPlayerWhenReady(next)
                applyVideoGeometry()
            }
        }
    }
    if(playbackFromRecommend) {
        recommendPlaybackController.reportEventById(
            nextData.series.id,
            com.hgtv.star.core.RecommendBehavior.Event.PLAY_START,
            episodeId=nextData.episodes[next],
            episodeIndex=next,
            positionMs=0,
            durationMs=0,
        )
    }
    startAutoTransitionCleanup(mediaId,previousData.episodes.getOrNull(previous).orEmpty())
    // 无感切集换的是 period，不走 [playEpisode]，诊断现场得在这里跟着换：
    // 换集之后报出来的必须是**这一集**的 id / 集号 / 清晰度，否则上报里张冠李戴。
    // 解码器与渲染器是复用的，`decoderName` 因此保留——那正是这一条最有用的信息。
    PlaybackDiag.nextEpisode(nextData.episodes[next],next,quality)
    // 上一集出帧时看门狗已经撤了，这一集要重新挂：无感切集同样可能卡在黑屏上。
    armPlaybackWatchdog(playbackSession)
    armFrameStallWatchdog(playbackSession)
    readySince=android.os.SystemClock.elapsedRealtime()
    prefetchAttempts=0; prefetchAttemptAt=0
    // 这一集是全新开始的：续播点、待跳转位置、错误与重试预算都要归零。
    // 不归零的话，上一集留下的 `pendingSeek` 会在下一次 tick 把画面跳到错误的位置。
    startPosition=0; pendingSeek=null; main.removeCallbacks(seekRunnable)
    playError=false; retryPending=false; autoRecovery=false
    // 换了一集：自动重试 / 画面重建 / 队列重开 / 地址自愈 / **连播链的失败计数**全部归零。
    // 早先这里散着四句、`recovery.reset()` 还单写一行，漏一处就会出现
    // 「新一集没有自动重试」或「错误页顶着上一集的建议」（见 `PlaybackAttemptLedger`）。
    // 连播链的计数也在这里清：走到这里说明「下一集」真的接上了，链条重新开始。
    playbackCoordinator.onEpisodeStart(nextData.episodes[next])
    // **不**在这里设故障互斥窗——那会把新一集紧接着发生的真实错误也一起吞掉。
    // 故障互斥窗是「**这一次故障**」的，不是「这一段播放」的：换了集必须作废。
    //
    // 无感切集**不**增加 `playbackSession`（有意如此：监听器的 `active()` 要活过换集），
    // 因此 `playerFailure` 开头那条「同一会话 1.5 秒内只处理第一条」会把**新一集**开播
    // 1.5 秒内的第一个真实错误当成重复回调丢掉：画面全黑、`playError` 仍是 false、
    // 控制条还写着「播放中」，只能等 15 秒起播看门狗来救。上一集在片尾失败、300ms 后
    // 新一集立刻报 4001 或 IO，正是这条最常见的命中方式。
    failureHandledSession=-1; failureHandledAt=0L
    // 标题只放剧名：集数由顶部那一项单独显示（见 [syncPlaybackInfo]）。
    playbackTitle.text=nextData.series.title
    // 集号这一格必须在这一帧就换掉：新 period 的第一帧已经上屏，读数还写着上一集，
    // 用户看到的就是「明明播到第 5 集，顶部还写第 4 集」。只改一处文字，不触发整条重排。
    syncPlaybackEpisodeLabel(nextData)
    // 弹幕与界面状态的刷新放到下一帧之后：这一帧正在把新 period 的第一帧推上屏，
    // 主线程上任何一次重排都会把它顶后一帧，用户看到的就是「换集时闪一下」。
    main.post {
        if(isDestroyed || screen!="player" || episodeIndex!=next) return@post
        loadDanmaku(nextData.episodes[next],nextData.series.id)
        // 开始过渡后再把新一集的真实位置交给弹幕层：首批数据若先到会暂存，
        // 等这一次时钟落地后再切换，不会沿用上一集片尾的位置。
        syncDanmakuClock()
        updatePlaybackText(); syncMediaSession()
    }
    syncNextSeasonForCurrentEpisode()
    val waited=if(queuedFor>0) android.os.SystemClock.elapsedRealtime()-queuedFor else -1
    android.util.Log.i(
        "HGTV",
        "Episode advanced seamlessly to series=${nextData.series.id} episode=${next+1} " +
            "quality=$quality queuedForMs=$waited"
    )
}
fun TvMainActivity.cancelPrefetch() {
    prefetchScope?.cancel(); prefetchScope=null
    preparedNext.clear(); prefetchJob?.cancel(true); prefetchJob=null
    closeTransport(prefetchHttp); prefetchHttp=null; prefetchedFor=""
}
fun TvMainActivity.maybePrefetch() {
    val p=player ?: return; val data=detail ?: return
    // 推荐页不挂下一集：一集播完就进正片（见 STATE_ENDED 分支），由正片接着播，
    // 这里再预取一集只会白占带宽，还会让片尾被 ExoPlayer 自己推进到队列里那一集。
    if(screen=="recommend") return
    // 起播正在取地址：这会儿再发一次就是两个并发，而这条接口对并发极敏感
    //（20 并发 0 命中、单发 50~80% 命中）。让起播先拿到地址，下一帧再预热。
    if(playbackFetching) return
    if(!foreground || !p.isPlaying || !library.autoNext || playError || sleepStopped) return
    val seasonBoundary=episodeIndex==data.episodes.lastIndex
    // 同季只要片尾前 30 秒的数据；跨季还要先查详情、再取第 1 集，
    // 等到片尾窗口才发请求就已经晚了。进入最后一集后详情一就绪就提前预热。
    if(!seasonBoundary &&
        !PrefetchPolicy.nearEnd(p.currentPosition,p.duration,p.playbackParameters.speed)) return
    val targetDetail=if(seasonBoundary) {
        nextSeasonDetail?.takeIf {
            it.episodes.isNotEmpty() && nextSeason?.id==it.series.id
        }
    } else data
    val target=targetDetail ?: return
    val id=if(seasonBoundary) target.episodes.firstOrNull()
        else target.episodes.getOrNull(episodeIndex+1)
    if(id==null) return
    // 下一集自己的封面也提前取好，片尾/手动换集时先显示目标集画面图，
    // 不再沿用上一集的首帧。
    target.episodeCover(id).ifBlank { target.series.cover }.takeIf { it.isNotBlank() }?.let { cover ->
        artwork.prefetch(cover,1920,1080)
    }
    // 键 = 集 + 上限（见 [preparedKeyFor]）：全站只有 HEVC 一种编码，不需要再区分编码族。
    val quality=playbackMaxQuality
    val key=preparedKeyFor(id)
    // 已经挂进播放器队列的那一集不用再取一次地址。
    if(queuedEpisodeId==id) return
    if(preparedNext.isFresh(key) || prefetchJob?.isDone==false) return
    val now=android.os.SystemClock.elapsedRealtime()
    if(readySince==0L || now-readySince<3000 || p.bufferedPosition-p.currentPosition<5000) return
    // 预取窗口只有 30 秒（见 [PrefetchPolicy.nearEnd]），而这条接口约一半概率回空响应。
    // 重发间隔从 6 秒放宽到 10 秒：空响应是网关按并发挡的（2026-10-27），
    // 贴得太近的重发只会让这一侧一直占着一份并发额度，把随后那次真正的起播挤死。
    if(prefetchAttempts>=5 || (prefetchAttempts>0 && now-prefetchAttemptAt<10_000) || connectivity.isMeteredConnection() || !networkAvailable()) return
    cancelPrefetch(); prefetchedFor=key; prefetchAttemptAt=now; prefetchAttempts++
    val ticket=preparedNext.begin(key)
    // 共用进程级传输层（HTTP/1.1）与播放工作仓库：切集、预取都不再新建或关闭连接池。
    val http=com.hgtv.star.shared.data.PlaybackTransport.client
    val source=AppRuntime.playbackRepository(this); prefetchHttp=http
    val sourceCoordinator=com.hgtv.star.shared.architecture.PlaybackSourceCoordinator(source,http)
    val scope=RequestScope(); prefetchScope=scope
    prefetchJob=prefetchWorker.submit {
        var remote: RemoteVideo?=null
        try {
            // 与 [playEpisode] 同一道门槛：预取也是取播放地址，同样按注册设备来。
            if(!library.deviceRegistered()) { registerDeviceOnce(); source.inheritDevice(repository) }
            val prepared=scope.run {
                // 索引键用**实际档位**（见 `StreamInfo.mediaKey`）：与起播那条路的键
                // 必须逐字一致，预热结果才会被真正复用；用请求上限做键时，
                // 「CDN 只给兼容档」的那一集预热与起播会各用一份索引。
                val queue=target.episodes.drop(target.episodes.indexOf(id)+1).take(5)
                val info=sourceCoordinator.stream(id,quality,target.playback,queue)
                sourceCoordinator.warm(id,info).also { remote=it }
            }
            if(preparedNext.complete(ticket,key,prepared)) {
                android.util.Log.i(
                    "HGTV",
                    "${if(seasonBoundary) "Next season" else "Next episode"} prepared near end"
                )
                // 预热一落地就挂进队列，而不是等片尾那一下再挂：挂得越早，
                // ExoPlayer 越有时间在后台把下一集的 period prepare 好（片尾推进时就不用等）。
                main.post { if(!isDestroyed) enqueuePreparedNext() }
            }
        } catch(_: Exception) { remote?.close() }
    }
}

/**
 * 提前查下一季：倒数第二集开始取数，最后一集才显示提示。
 *
 * 同季下一集只需要 vid，跨季还要先查相关作品、再查下一季详情。若等到最后一集
 * 才开始查，短剧可能刚查到就已经片尾；提前一集准备，片尾才能像同季一样直接换 period。
 * 换回更早的集或离开播放页时立即作废。
 */
fun TvMainActivity.syncNextSeasonForCurrentEpisode() {
    val data=detail ?: return
    val prepare=screen=="player" && library.autoNext && !sleepStopped &&
        episodeIndex>=data.episodes.lastIndex-1
    if(!prepare) { clearNextSeasonLookup(); return }
    ensureNextSeason(data.series)
}

/** 作废下一季查询与提示；[nextSeasonNotice] 的视图引用由播放层重建 / 退出时单独清空。 */
fun TvMainActivity.clearNextSeasonLookup() {
    nextSeasonToken.incrementAndGet()
    nextSeasonJob?.cancel(true); nextSeasonJob=null
    nextSeasonFor=""; nextSeason=null; nextSeasonLoading=false
    nextSeasonDetailToken.incrementAndGet()
    nextSeasonDetailJob?.cancel(true); nextSeasonDetailJob=null
    nextSeasonDetailFor=""; nextSeasonDetail=null; nextSeasonDetailLoading=false
    autoNextSeasonPending=false; nextSeasonOpening=false; nextSeasonFinishedFor=""
    hideNextSeasonNotice()
}

private fun TvMainActivity.ensureNextSeason(series: Series) {
    if(nextSeasonFor==series.id) {
        if(!nextSeasonLoading) showNextSeasonNotice()
        return
    }
    val cached=relatedWorks?.takeIf { relatedFor==series.id && !relatedFailed }
    val token=nextSeasonToken.incrementAndGet()
    nextSeasonJob?.cancel(true)
    nextSeasonFor=series.id; nextSeason=null; nextSeasonLoading=true; nextSeasonOpening=false
    hideNextSeasonNotice()
    if(cached!=null) {
        completeNextSeasonLookup(series,cached,token)
        return
    }
    nextSeasonJob=io.submit {
        val result=runCatching { repository.related(series.id,series.title) }
        main.post {
            if(isDestroyed || nextSeasonToken.get()!=token) return@post
            result.onSuccess { works ->
                relatedWorks=works; relatedFor=series.id; relatedFailed=false
                completeNextSeasonLookup(series,works,token)
            }.onFailure {
                relatedFailed=true
                completeNextSeasonLookup(series,null,token)
            }
        }
    }
}

private fun TvMainActivity.completeNextSeasonLookup(
    series: Series,
    works: RelatedWorks?,
    token: Long,
) {
    if(token!=nextSeasonToken.get()) return
    nextSeasonJob=null
    nextSeasonLoading=false
    nextSeason=works?.let { SeriesSeasons.nextSeason(series,it) }
    val target=nextSeason
    if(target!=null) {
        showNextSeasonNotice()
        ensureNextSeasonDetail(target)
    } else hideNextSeasonNotice()
    if(autoNextSeasonPending) {
        when {
            target==null -> {
                autoNextSeasonPending=false
                finishSeriesPlayback(series)
            }
            nextSeasonDetail!=null -> {
                autoNextSeasonPending=false
                openNextSeason(target)
            }
            !nextSeasonDetailLoading -> {
                autoNextSeasonPending=false
                openNextSeason(target)
            }
            // 详情还在路上：继续等，落地后再直接进入下一季。
        }
    }
}

/**
 * 查到下一季后把它的分集表也提前取回来。
 *
 * 只查剧名列表不够：片源预热需要第 1 集的 vid 与这一季自己的播放模型。
 * 请求失败不弹错，片尾仍会走 [openNextSeason] 的兜底路径。
 */
private fun TvMainActivity.ensureNextSeasonDetail(target: Series) {
    if(nextSeasonDetailFor==target.id) return
    nextSeasonDetailJob?.cancel(true)
    val token=nextSeasonDetailToken.incrementAndGet()
    nextSeasonDetailFor=target.id
    nextSeasonDetail=null
    nextSeasonDetailLoading=true
    nextSeasonDetailJob=io.submit {
        val result=runCatching { repository.detail(target.id,includeAuthor=false) }
        main.post {
            if(isDestroyed || nextSeasonDetailToken.get()!=token) return@post
            nextSeasonDetailLoading=false
            nextSeasonDetailJob=null
            val detail=result.getOrNull()?.takeIf { it.episodes.isNotEmpty() }
            if(detail!=null) {
                nextSeasonDetail=detail
                if(autoNextSeasonPending) {
                    autoNextSeasonPending=false
                    openNextSeason(detail.series)
                } else if(screen=="player" && episodeIndex==this.detail?.episodes?.lastIndex) {
                    maybeQueueNext()
                }
            } else if(autoNextSeasonPending) {
                // 提前取详情失败：片尾兜底再完整试一次，不能停在等待态。
                autoNextSeasonPending=false
                openNextSeason(target)
            }
        }
    }
}

private fun TvMainActivity.showNextSeasonNotice() {
    val data=detail ?: return
    val target=nextSeason ?: return
    val active=screen=="player" && library.autoNext && !sleepStopped &&
        episodeIndex==data.episodes.lastIndex && nextSeasonFor==data.series.id
    if(!active) { hideNextSeasonNotice(); return }
    nextSeasonNotice?.apply {
        text="将自动播放 ${SeriesSeasons.titleWithSeason(target)}"
        visibility=View.VISIBLE
    }
}

private fun TvMainActivity.hideNextSeasonNotice() {
    nextSeasonNotice?.visibility=View.GONE
}

/**
 * 最后一集播完：有下一季就播它第 1 集，否则停在「全剧已播完」。
 *
 * 查询可能还在路上；此时先挂起，结果落地后由 [completeNextSeasonLookup] 接着推进。
 */
fun TvMainActivity.advanceToNextSeasonOrFinish(series: Series) {
    if(nextSeasonFinishedFor==series.id) return
    if(!library.autoNext) { finishSeriesPlayback(series); return }
    if(nextSeasonFor==series.id) {
        if(nextSeasonLoading) {
            autoNextSeasonPending=true
            return
        }
        val target=nextSeason
        if(target!=null) openNextSeason(target) else finishSeriesPlayback(series)
        return
    }
    autoNextSeasonPending=true
    ensureNextSeason(series)
}

private fun TvMainActivity.openNextSeason(target: Series) {
    if(nextSeasonOpening) return
    nextSeasonDetail?.takeIf {
        nextSeasonDetailFor==target.id && it.episodes.isNotEmpty()
    }?.let {
        enterNextSeason(it)
        return
    }
    if(nextSeasonDetailLoading && nextSeasonDetailFor==target.id) {
        autoNextSeasonPending=true
        return
    }
    nextSeasonOpening=true
    autoNextSeasonPending=false
    hideNextSeasonNotice()
    val token=nextSeasonToken.incrementAndGet()
    nextSeasonJob?.cancel(true)
    nextSeasonJob=io.submit {
        val result=runCatching { repository.detail(target.id,includeAuthor=false) }
        main.post {
            if(isDestroyed || nextSeasonToken.get()!=token) return@post
            val next=result.getOrNull()
            if(next==null || next.episodes.isEmpty()) {
                nextSeasonOpening=false
                finishSeriesPlayback(detail?.series ?: target)
                return@post
            }
            nextSeasonOpening=false
            nextSeasonJob=null
            enterNextSeason(next)
        }
    }
}

fun TvMainActivity.enterNextSeason(next: Detail) {
    nextSeasonDetailToken.incrementAndGet()
    nextSeasonDetailJob?.cancel(true); nextSeasonDetailJob=null
    nextSeasonOpening=false
    autoNextSeasonPending=false
    hideNextSeasonNotice()
    nextSeasonFor=""; nextSeason=null; nextSeasonLoading=false
    nextSeasonDetailFor=""; nextSeasonDetail=null; nextSeasonDetailLoading=false
    nextSeasonFinishedFor=""
    detail=next
    requestedAutoplay=true
    playEpisode(0,0,autoplay=true,direct=false)
}

private fun TvMainActivity.finishSeriesPlayback(series: Series) {
    nextSeasonFinishedFor=series.id
    nextSeasonOpening=false
    autoNextSeasonPending=false
    hideNextSeasonNotice()
    requestedAutoplay=false
    player?.pause()
    runCatching { playbackText.text="全剧已播完" }
    showHud()
}
