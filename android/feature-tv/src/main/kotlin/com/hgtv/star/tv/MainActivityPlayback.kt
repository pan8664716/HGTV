// SPDX-License-Identifier: GPL-3.0-only
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

// 本文件是从 MainActivity.kt 拆出来的「播放中控：缓冲抑制、下一集预取、重试恢复、HUD 与传输层回收」这一块。
// 这些成员仍然属于 MainActivity，只是以扩展成员（MainActivity.xxx）的形式放在这里，
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
fun MainActivity.closeTransport(http: okhttp3.OkHttpClient?) {
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
fun MainActivity.suppressBufferingUi(timeoutMs: Long=1200L) {
    bufferingSuppressed=true
    syncBufferingDots()
    main.removeCallbacks(restoreBuffering); main.postDelayed(restoreBuffering,timeoutMs)
}
fun MainActivity.restoreBufferingUi() {
    if(!bufferingSuppressed) return
    bufferingSuppressed=false
    main.removeCallbacks(restoreBuffering)
    syncBufferingDots()
}

/** 把 ExoPlayer 的缓冲状态映射到统一三点动画；过渡窗口内临时隐藏。 */
fun MainActivity.syncBufferingDots() {
    val label=when {
        playError || sleepStopped -> null
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
fun MainActivity.scheduleRetire() {
    main.removeCallbacks(retireForced); main.postDelayed(retireForced,3000)
}
/**
 * 强制收掉旧资源：调用方**刚刚整条替换 / 清空**了播放列表，那一项已经不可能再被读。
 *
 * 与 [retirePreviousEpisode] 的区别只有一条：不再等自动切集的稳定清理令牌。
 */
fun MainActivity.forceRetirePrevious() {
    cancelAutoTransitionCleanup()
    retirePreviousEpisode()
}
fun MainActivity.retirePreviousEpisode() {
    main.removeCallbacks(retireForced)
    // 播完的那一项还挂在播放列表里时不能关：播放线程可能仍在释放旧 period。
    // 必须等新集 READY + 首帧、旧项确实从列表移除后再关。
    if(playedItemPending) return
    retiringVideo?.let { runCatching { it.close() } }
    retiringVideo=null
    closeTransport(retiringHttp); retiringHttp=null
}
/** 作废当前自动切集的清理任务；下一次自动切集会生成新令牌。 */
fun MainActivity.cancelAutoTransitionCleanup() {
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
fun MainActivity.startAutoTransitionCleanup(mediaId: String,playedId: String) {
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
fun MainActivity.markAutoTransitionReady(mediaId: String) {
    if(mediaId.isEmpty() || mediaId!=transitionMediaId) return
    transitionReady=true
    maybeDropPlayedItem()
}
/** 新集首帧后补一次稳定条件。 */
fun MainActivity.markAutoTransitionFirstFrame(mediaId: String) {
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
private fun MainActivity.maybeDropPlayedItem() {
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
fun MainActivity.confirmPlayedItemRemoved() {
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
fun MainActivity.enqueuePreparedNext() {
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
fun MainActivity.maybeQueueNext() {
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
fun MainActivity.dropQueuedEpisode() {
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
fun MainActivity.onEpisodeAdvanced(next: Int,mediaId: String) {
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
fun MainActivity.onSeasonAdvanced(target: Detail,mediaId: String) {
    if(target.episodes.isEmpty()) return
    val previous=detail ?: return
    episodePanel?.dismiss(); episodePanel=null
    finishSeamlessAdvance(target,0,mediaId,previous)
}

private fun MainActivity.finishSeamlessAdvance(
    nextData: Detail,
    next: Int,
    mediaId: String,
    previousData: Detail,
) {
    val p=player ?: return
    if(next !in nextData.episodes.indices) return
    val previous=episodeIndex
    val queuedFor=queuedAt
    advancedAt=android.os.SystemClock.elapsedRealtime()
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
fun MainActivity.cancelPrefetch() {
    prefetchScope?.cancel(); prefetchScope=null
    preparedNext.clear(); prefetchJob?.cancel(true); prefetchJob=null
    closeTransport(prefetchHttp); prefetchHttp=null; prefetchedFor=""
}
fun MainActivity.maybePrefetch() {
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
fun MainActivity.syncNextSeasonForCurrentEpisode() {
    val data=detail ?: return
    val prepare=screen=="player" && library.autoNext && !sleepStopped &&
        episodeIndex>=data.episodes.lastIndex-1
    if(!prepare) { clearNextSeasonLookup(); return }
    ensureNextSeason(data.series)
}

/** 作废下一季查询与提示；[nextSeasonNotice] 的视图引用由播放层重建 / 退出时单独清空。 */
fun MainActivity.clearNextSeasonLookup() {
    nextSeasonToken.incrementAndGet()
    nextSeasonJob?.cancel(true); nextSeasonJob=null
    nextSeasonFor=""; nextSeason=null; nextSeasonLoading=false
    nextSeasonDetailToken.incrementAndGet()
    nextSeasonDetailJob?.cancel(true); nextSeasonDetailJob=null
    nextSeasonDetailFor=""; nextSeasonDetail=null; nextSeasonDetailLoading=false
    autoNextSeasonPending=false; nextSeasonOpening=false; nextSeasonFinishedFor=""
    hideNextSeasonNotice()
}

private fun MainActivity.ensureNextSeason(series: Series) {
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

private fun MainActivity.completeNextSeasonLookup(
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
private fun MainActivity.ensureNextSeasonDetail(target: Series) {
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

private fun MainActivity.showNextSeasonNotice() {
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

private fun MainActivity.hideNextSeasonNotice() {
    nextSeasonNotice?.visibility=View.GONE
}

/**
 * 最后一集播完：有下一季就播它第 1 集，否则停在「全剧已播完」。
 *
 * 查询可能还在路上；此时先挂起，结果落地后由 [completeNextSeasonLookup] 接着推进。
 */
fun MainActivity.advanceToNextSeasonOrFinish(series: Series) {
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

private fun MainActivity.openNextSeason(target: Series) {
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

fun MainActivity.enterNextSeason(next: Detail) {
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
    playEpisode(0,0,autoplay=true)
}

private fun MainActivity.finishSeriesPlayback(series: Series) {
    nextSeasonFinishedFor=series.id
    nextSeasonOpening=false
    autoNextSeasonPending=false
    hideNextSeasonNotice()
    requestedAutoplay=false
    player?.pause()
    runCatching { playbackText.text="全剧已播完" }
    showHud()
}

fun MainActivity.networkAvailable(): Boolean = connectivity.hasInternet()
/**
 * 网络刚恢复，而起播正卡在「正在获取播放地址…」：把在途那一发作废，按原位置立刻重取。
 *
 * ## 为什么必须由网络回调来推一把
 *
 * 取地址跑在 [work] 的 io 线程上，卡住时界面上一个字都不变，唯一的出口是那一发自己超时。
 * 现场（2026-10-30，小米 TV，1.2.9.1(67)）：**断网 → 按下一集 → 两秒后联网**，
 * 界面停在「正在获取播放地址…」整整 **49.6 秒**——
 * `net POST … failed after 45001ms / InterruptedIOException: timeout`，
 * 而网络其实在第 2 秒就回来了。用户的感受是「网络已经好了，它还在转圈」。
 *
 * 现在有两道闸：
 * 1. 取地址整段 6 秒硬上限（见 `ContentRepository.ADDRESS_DEADLINE_MS`）——**兜底**；
 * 2. 这一条：网络恢复的那一刻就把在途那一发作废重来——**不再等兜底**。
 *
 * ## 作废为什么用 `generation++` + [cancelWork]
 *
 * [work] 的两个回调都按提交那一刻的 `generation` 丢弃结果，因此旧那一发晚回来的
 * 成功 / 失败都不会再落到界面上。失败尤其不能落：那会把一次「用户什么都没做」的等待
 * 记成一次起播失败，白白烧掉一次自动重试额度（3 次用完就只剩错误页）。
 *
 * 旧那一发的 `finally` 也不会把 [playbackFetching] 抹掉（那里比的是
 * [playbackFetchSeq]，新一发已经把它推走了），所以这一发重取期间下一集预热仍然让路。
 */
fun MainActivity.refetchAddressOnNetwork() {
    if(isDestroyed || screen!="player" || !playbackFetching || playError) return
    if(detail==null || !networkAvailable()) return
    android.util.Log.i(
        "HGTV",
        "Network restored while fetching address; refetching episode=${episodeIndex+1} at=${startPosition}ms"
    )
    generation++; cancelWork()
    playEpisode(episodeIndex,startPosition.coerceAtLeast(0),requestedAutoplay)
}
fun MainActivity.currentPosition(): Long = pendingSeek ?: player?.currentPosition?.takeIf { playbackReady } ?: startPosition
fun MainActivity.cancelRecovery() {
    main.removeCallbacks(retryRunnable)
    retryPending=false
    autoRecovery=false
    retryEpisodeId=""
}
fun MainActivity.scheduleRecovery() {
    if(!foreground || !playError || !autoRecovery || retryPending) return
    if(!playbackCoordinator.autoRetryAvailable) { autoRecovery=false; playbackText.text="已自动重试 3 次，仍无法播放。可手动重试或切换清晰度。"; return }
    if(!networkAvailable()) { playbackText.text="网络已断开，联网后自动续播 · 第 ${episodeIndex+1} 集 ${formatTime(retryPosition)}"; return }
    val delay=playbackCoordinator.autoRetryDelayMs ?: return
    playbackText.text="${if(retryAutoplay) "准备恢复播放" else "准备恢复暂停位置"} · ${delay/1000f} 秒后重试 ${playbackCoordinator.autoRetryAttempts+1}/3 · ${formatTime(retryPosition)}"
    retryEpisodeId=detail?.episodes?.getOrNull(episodeIndex).orEmpty()
    retryPending=true; main.postDelayed(retryRunnable,delay)
}
/**
 * 这次失败落在**哪条轨**上。
 *
 * 判断本体已经收进 `:shared` 的 [com.hgtv.star.shared.media.PlaybackFailures]：
 * 手机端也要用同一份（音频失败不该被当成「视频能力不足」去降清晰度），
 * 两端各写一份迟早会分叉。这里保留同名函数只是为了不动调用点。
 */
fun playbackMediaKind(problem: Throwable?): MediaKind =
    com.hgtv.star.shared.media.PlaybackFailures.mediaKind(problem)


/**
 * 把这一次失败整理成策略能吃的归因。
 *
 * 档位与编码来自**这一次真正取到的流**（[com.hgtv.star.core.StreamInfo]），不是用户设置：
 * 用户设 1080、CDN 只给 480 时，失败的其实是 480 那条，降档目标也只能从 480 往下找。
 *
 * [failedQueued]：这一次失败属于**队列里那一集**（片尾预热挂进去的下一集）。那时
 * [video] 是当前这一集的流，用它会得出错误的 `currentQuality` 与 `available`，
 * 于是降档从错误的一档开始、还可能把「当前这一档其实没问题」的结论压进会话层。
 * 判据与 [playerFailure] 里那段队列分支同源，调用方在 `dropQueuedEpisode()` **之前**
 * 把那一份 `StreamInfo` 抄出来传进来。
 */
fun MainActivity.playbackAttribution(
    problem: Throwable?,
    code: Int,
    failedQueued: StreamInfo? = null
): FailureAttribution {
    val info=failedQueued ?: video?.info ?: queuedVideo?.info
    // 归因本体在 `:shared`（手机端同一份）：档位来自这一次真正取到的流，不是用户设置；
    // 解码方式用**实际生效**的那一档（用户可能自己点过「软件解码」）。
    return com.hgtv.star.shared.media.PlaybackFailures.attribution(
        problem=problem,
        code=code,
        info=info,
        fallbackQuality=playbackMaxQuality,
        mode=playerDecoderMode(),
    )
}

/**
 * 用户主动发起的一次**全新尝试**：把按集计的全部额度与账本一起还给这一集。
 *
 * 两半都要还，而且必须**同时**还：
 *
 * * `SessionPlaybackPolicy` 管自动降级（同档重建一次 / 降档两次），
 *   它自己带「归还次数」上限（`MANUAL_RETRY_LIMIT`）；
 * * `PlaybackAttemptLedger` 管自动重试 3 次 / 画面重建一次 / 队列项重开两次，
 *   这三份过去归零条件都挂在 `recovering=false` 上，而错误页那几颗按钮都带
 *   `recovering=true`，所以它们从来不会被还——「立即重试」点下去只是闪回同一句提示。
 *
 * 归还必须是**一次性的**：策略那边拒绝归还（连点第三次）时，这里整体停下。
 * 早先只挡了策略那一半，下面三份照样重置，于是连点按钮可以无限批次地重来。
 */
private fun MainActivity.resetBudgetsForManualRetry() {
    playbackCoordinator.onManualRetry()
}

/**
 * 重试前把这一集所有清晰度的地址与当前索引一起作废。
 *
 * 只换 `device_id` 不会绕过 `stream()` 的内存 / 落盘 URL 缓存，重试仍会拿到同一条
 * 失败地址；预取槽也要清掉，避免又接回失败前准备好的旧流。
 */
internal fun MainActivity.prepareFreshPlaybackRetry() {
    val episodeId=detail?.episodes?.getOrNull(episodeIndex)
    val staleInfo=video?.info
    cancelPrefetch()
    if(episodeId!=null) {
        playbackSource.invalidate(episodeId, staleInfo)
    } else {
        runCatching { repository.rotateDeviceForRetry() }
    }
}
/**
 * 从这一集切到**另一集**去重来（片尾没接上 / 队首那一集坏了）。
 *
 * `recovering=true` 的语义只有一条：**同一集的同一次起播重来**，因此它刻意不去清
 * 按集计的额度——那正是「自动降级不会无限循环」的前提。可一旦目标集换了，
 * 这几份不按集做键的账本（自动重试 / 画面重建 / 手动建议 / 队列重开）就全部张冠李戴：
 * 新一集开播时没有任何自动重试机会、画面一停就直接落错误页，错误页上还顶着
 * **上一集**的「请切换为软件解码」。
 *
 * 档位上限（[SessionPlaybackPolicy.effectiveMaxQuality]）**照旧沿用**：那是
 * 「这台机器放不了这一档」的结论，与具体哪一集无关。
 */
private fun MainActivity.restartAnotherEpisode(index: Int,at: Long,autoplay: Boolean) {
    // 按**新一集**重来一遍预算；连播链的失败计数不清，避免无限自动推进。
    detail?.episodes?.getOrNull(index)?.let { playbackCoordinator.onEpisodeSwitch(it) }
    playEpisode(index,at,autoplay,recovering=true)
}
/**
 * 执行一次降级重试：**按计划重新起播 + 断点续播**，参数由 [PlaybackRetryPlan] 决定。
 *
 * 两种计划，都只改一件事：
 *
 * * [DegradationKind.SAME_QUALITY_REBUILD]（`maxQuality==null`）：什么参数都不改，只是把
 *   解码器放掉重开。走的是 `keepMedia=true` 那条窄路——**播放层不重建、这一集的
 *   `RemoteVideo` 原地留着**，所以地址 / 索引 / 首块都不重取（一次 Range 都不发，
 *   也不重读盘），只剩下「放掉解码器 + 首帧」这一段；
 *   早先它也走 `recovering=true` 的整条重建：摘掉整个播放层重新 inflate、再走一遍取地址
 *   （命中缓存）与 `prepare()`（读盘上的索引 + 首块）——为「重开一颗解码器」付了整页的钱。
 * * [DegradationKind.QUALITY_DOWN]：换一档上限，自然换一条地址（新档位的缓存键本来就是空的）。
 *
 * 「切软解」不在这条路上：那是用户点按钮 → [applyDecoderMode]。
 *
 * 现场先收干净再重来：看门狗、自动重试、预取、队列项都作废——它们都是按**上一次起播**排的，
 * 留着只会在新一次起播时抢解码器或往旧目标上推进。
 */
fun MainActivity.replayWithPlan(plan: PlaybackRetryPlan) {
    // 断点位置直接问播放器：出错时 `playbackReady` 可能已被清掉，[currentPosition] 会退回
    // [startPosition]（本集开头），表现为「降级一次就从头播」。播放器在错误态下
    // `currentPosition` 仍然是有效的（它就是失败那一刻的位置），取它更准。
    val live=runCatching { player?.currentPosition ?: -1L }.getOrDefault(-1L)
    val at=(if(live>0) live else currentPosition()).coerceAtLeast(0)
    val play=requestedAutoplay && !sleepStopped
    // 上限先落进会话层，再记留痕：`noteDegrade` 会打出 `cap=`，那一刻读到的必须已经是**新的**上限。
    playbackCoordinator.applyRetryPlan(plan)
    // 提示一次就够：同一个 (集, 理由, 目标档位) 在一次播放里只弹一条（见 `ToastDeduper`）。
    //
    // 键里**必须**带目标档位：1080 → 720 与 720 → 480 是同一次播放里连着发生的两次降档，
    // 早先两处共用一个「集 + 理由」的键，第二次被去重吞掉——用户眼前是「已降为 720P」，
    // 实际放的是 480，于是「清晰度自己变低了」这条反馈永远查不出来。
    plan.toast?.let { text ->
        val key="${detail?.episodes?.getOrNull(episodeIndex) ?: episodeIndex}:${plan.reason}:${plan.maxQuality ?: 0}"
        if(playbackToasts.shown(key)) runCatching { tvTools.toast(text) }
    }
    // 降级本身必须留痕（见 [noteDegrade]）：本集被标成「这一次起播失败过」，于是真的出画面时
    // `PlaybackDiag.takeRecovered()` 能记下一条「救回来了」的正样本。
    // 早先这条路直接 return、什么标记都不留，于是「降级救回率」永远是 0 条样本——
    // `QUALITY_DOWN_LIMIT` 够不够、`softwareQuality` 的 720 是保守还是过头，全都无从校准。
    noteDegrade(plan)
    disarmPlaybackWatchdog(); disarmFrameStallWatchdog()
    cancelRecovery(); cancelPrefetch(); dropQueuedEpisode()
    restoreBufferingUi(); retirePreviousEpisode()
    // 降档**不**去 `forgetStream`：地址按「集 + 上限 + device_level」做键，上限一变就是
    // 另一个键、必然重取一条新地址，旧那条留着下次切回高档还能用。
    // 早先这里无条件清一次，清的是**新**上限那个（原本就空的）键，什么也没删掉，
    // 代价却是 `forgetStream` 尾部把 `video_model` 缓存一起打掉——
    // 白付一次 500~1100ms 的 POST，而坏的东西明明是本机解码器，与身份无关。
    // 同档重建走窄路（`keepMedia`）：视图与这一集的 `RemoteVideo` 原地留着，
    // 只 `stop()` 放掉解码器。降档必须回到整条重建——上限变了就是另一条地址、
    // 另一份索引，留着的这一份用不上。
    playEpisode(episodeIndex,at,play,recovering=true,keepMedia=plan.maxQuality==null)
}

fun MainActivity.playerFailure(problem: Throwable) {
    // 同一次片尾故障可能先来 onPlayerError、再来 STATE_ENDED（或反过来）。
    // 两条路都去「切下一集」就是重复切换：同一会话 1.5 秒内只处理第一条。
    // 判据带会话号，所以新一集自己的错误不会被这条规则误吞。
    //
    // 但「时间窗」本身不足以证明这是同一条：万一将来有一条路在原地重试而不换会话，
    // 第二条**真实**失败会被静默丢掉，而那时 `playbackReady` 仍是 true、两条看门狗都不动
    // ——就是永久冻结。因此再加一道：只有播放器确实已经停了（READY / BUFFERING 之外的
    // 任何状态）才允许吞；真的还在播就按新故障处理。
    val now=android.os.SystemClock.elapsedRealtime()
    val stillPlaying=player?.let {
        it.playbackState==Player.STATE_READY || it.playbackState==Player.STATE_BUFFERING
    }==true
    if(failureHandledSession==playbackSession && now-failureHandledAt in 0..1500 && !stillPlaying) {
        android.util.Log.i("HGTV","Duplicate playback failure ignored session=$playbackSession")
        return
    }
    failureHandledSession=playbackSession; failureHandledAt=now
    // 同一件事的解码器报错可能还挂在 1.5 秒的等待里（见 [playbackDiagnostics] 的
    // `onVideoCodecError`）：这条已经带着完整 cause 链报了，那条不必再来一遍。
    pendingCodecReport?.let { main.removeCallbacks(it) }; pendingCodecReport=null
    // 上报放在互斥窗之后、任何重试分支之前：同一次故障只报一条，且「自动重试成功」
    // 也不会让这条记录消失——用户看到过的那次失败是真的发生过。
    // —— 判定：**与手机端同一个函数**（`PlaybackFailurePolicy.decide`）——
    //
    // 这一段以前是电视端自己的一套 if/else（地址自愈 → 推荐页 → 队列项 → 解码降级 → 错误页），
    // 手机端另写了一套、少了前三段，于是同一个故障在电视上能自己救回来、在手机上直接死在
    // 黑屏上。现在「怎么办」只留在 `:core` 那一份规则里，两端都调它；
    // 各端只负责**读事实**（[FailureFacts]）与**执行**，界面各自画。
    //
    // 下面每一条分支的**执行**仍是电视端原来的写法，一个像素都没改——换的只是
    // 「什么时候走哪一条」这个判断。
    val decodeCode=(problem as? PlaybackException)?.errorCode ?: 0
    // 队列那一集出错时要拿**它的**档位表去归因（`video` 是当前这一集）。
    // 必须在 `dropQueuedEpisode()` 之前抄走，错误页的按钮也要用它。
    val failedQueuedInfo=queuedVideo?.info
    val queuedTargetDetail=queuedDetail
    val attr=playbackAttribution(problem,decodeCode,failedQueuedInfo)
    val failureMedia=playbackMediaKind(problem)
    val queuedDuration=player?.duration?.coerceAtLeast(0) ?: 0
    // 队列项是在**片尾窗口内**报的错（= 下一集真的没接上），还是窗口外
    //（= 当前这一集还在正常播，坏的只是后台预加载的那一条）。
    val queuedNearEnd=queuedEpisodeId!=null && queuedDuration>0 &&
        (player?.currentPosition ?: 0)>=queuedDuration-2_000
    val facts=FailureFacts(
        errorCode=decodeCode,
        stage=playbackStage(problem),
        media=failureMedia,
        retryable=com.hgtv.star.shared.media.PlaybackFailures.isRetryable(problem),
        streamExpired=streamExpiredOf(problem),
        videoChanged=videoChangedOf(problem),
        hostUnresolved=hasCauseOf<java.net.UnknownHostException>(problem),
        transportDead=transportDeadOf(problem),
        queued=queuedEpisodeId!=null,
        queuedNearEnd=queuedNearEnd,
        noHardwareDecoder=failureMedia==MediaKind.VIDEO &&
            playerDecoderMode()==VideoDecoderMode.HARDWARE && !hardwareHevcAvailable,
        autoRetryAllowed=!sleepStopped,
        onRecommend=screen=="recommend",
    )
    if(screen=="recommend" || playbackFromRecommend) {
        val data=detail
        if(data!=null) {
            val failedEpisode=if(facts.queued) queuedEpisodeId.orEmpty()
            else data.episodes.getOrNull(episodeIndex).orEmpty()
            recommendPlaybackController.reportEventById(
                data.series.id,
                com.hgtv.star.core.RecommendBehavior.Event.ERROR,
                episodeId=failedEpisode,
                episodeIndex=if(facts.queued) -1 else episodeIndex,
                positionMs=runCatching { player?.currentPosition ?: -1L }.getOrDefault(-1L),
                durationMs=runCatching { player?.duration ?: -1L }.getOrDefault(-1L),
                reason=facts.stage,
            )
        }
    }
    // 队列项接不回来时要按「当前这一集自己坏了」再判一次：决策函数是纯的，重入安全。
    fun decideFailure(queued: Boolean)=playbackCoordinator.decideFailure(
        facts.copy(queued=queued),attr,recommendPlaybackFailures
    )
    var failureAction=decideFailure(facts.queued)
    if(failureAction==FailureAction.RecoverQueuedCurrent) {
        // 队列里那一集坏了、当前这一集还在正常播：原地把当前集接回来。
        // ExoPlayer 对队列项的加载失败同样报致命错误，并且一报错就把**整台**播放器推到
        // `STATE_IDLE`——不接回来，用户看到的是「这一集播到一半黑掉」。
        dropQueuedEpisode(); restoreBufferingUi(); retirePreviousEpisode()
        if(recoverQueuedCurrent()) return
        android.util.Log.w("HGTV","Queued episode failed; giving up automatic advance")
        failureAction=decideFailure(false)
    }
    // 自愈路径（`RefetchStream`）救回来就不该记一条故障，否则诊断面板里会出现
    // 「刚报错、其实画面正常」的记录——因此上报放在它之后。
    if(failureAction !is FailureAction.RefetchStream) {
        disarmPlaybackWatchdog(); disarmFrameStallWatchdog()
        reportPlaybackFailure(problem,playbackStage(problem))
    }
    // 放弃自动处置时**为什么**放弃：错误页据此决定给哪几颗按钮。
    var giveUp: GiveUpReason?=null
    when(failureAction) {
        is FailureAction.RefetchStream -> {
            // 落盘地址过期（403 / 404）/ 文件换过 / 域名解析不出来 / 连接层假死：
            // **换一条新地址原地重来一次**，不给用户看错误页。
            //
            // 缓存 20 分钟的地址有可能被服务端提前作废（签名时效、CDN 换线），而这是
            // 「回落到盘上以后才可能出现」的新失败点，必须自己救回来。
            val failingEpisode=queuedEpisodeId ?: detail?.episodes?.getOrNull(episodeIndex)
            if(failingEpisode!=null) {
                val why=when {
                    facts.hostUnresolved -> "Video host unresolved; refetching url"
                    facts.transportDead -> "Transport dead; refetching url, dns & pool"
                    facts.videoChanged -> "Video changed; refetching url & index"
                    facts.preloadFailure -> "Queued preload failed; refetching url (budget kept)"
                    else -> "Stream expired; refetching url"
                }
                android.util.Log.w("HGTV","$why episode=${episodeIndex+1}")
                // 这一集的地址不认了。**按集整体作废，而不是按精确键**：
                //
                // 精确键是 `集:上限:device_level`，而这条地址当初是按**预取那一刻**的上限与
                // 档位取回来的。从取地址到失败之间，降档、重建、换解码都可能把上限改过，
                // `device_level` 也可能变过——键对不上时 [forgetStream] 等于什么都没删，
                // 重来时会原样命中同一条死地址，第二次必然还是 403。
                //
                // 多删几条的代价只是下一次多一次网络往返（500~1100ms），
                // 而删错对象的代价是用户看到错误页。
                // 索引与首块也是按「这一集 + 这一档」落盘的，文件换过之后它们一起作废。
                //
                // 键必须用**实际**那一份（`StreamInfo.mediaKey`：集 + 实际档位）。
                // 早先这里拼的是 `"$集:$上限"`，而索引键早就改成带实际档位了——
                // 拼错等于什么也没删，下一次起播仍然读那份对不上新文件的旧索引。
                val staleInfo=if(queuedEpisodeId!=null) queuedVideo?.info ?: video?.info else video?.info
                playbackSource.invalidate(failingEpisode, staleInfo)
                // 地址之外的那两层：视频域名的解析结果、连接池里的空闲连接。
                if(failureAction.dropTransport) dropDeadTransport()
                val at=currentPosition().coerceAtLeast(0)
                disarmPlaybackWatchdog(); disarmFrameStallWatchdog()
                cancelRecovery(); cancelPrefetch(); dropQueuedEpisode()
                restoreBufferingUi(); retirePreviousEpisode()
                if(queuedTargetDetail!=null && failureAction.fromStart) {
                    main.post { if(!isDestroyed) enterNextSeason(queuedTargetDetail) }
                    return
                }
                // 队首那一集失败：从它的开头重来，而不是重放当前这一集（见 `queuedNearEnd`）。
                val retryIndex=if(failureAction.fromStart) episodeIndex+failureAction.episodeOffset else episodeIndex
                val retryAt=if(failureAction.fromStart) 0L else at
                if(retryIndex in (detail?.episodes?.indices ?: IntRange.EMPTY)) {
                    // 换了集就走 [restartAnotherEpisode]：按集计的额度必须跟着换，否则新一集
                    // 会继承上一集的自动重试 / 画面重建次数，一个都不会再有。
                    main.post { if(!isDestroyed) restartAnotherEpisode(retryIndex,retryAt,requestedAutoplay) }
                    return
                }
                main.post { if(!isDestroyed) playEpisode(episodeIndex,at,requestedAutoplay,recovering=true) }
                return
            }
        }
        FailureAction.SkipRecommend, FailureAction.GiveUpRecommend -> {
            // 推荐页出错：不摆播放页那套「重试 / 返回选集」的错误界面（那里没有选集、
            // 也没有详情页可回），直接换下一部。连换几部都放不了才停在一句提示上。
            val failed=detail?.series?.title ?: recommendItems.getOrNull(recommendIndex)?.title
            recommendPlaybackFailures++
            if(failureAction==FailureAction.GiveUpRecommend) {
                // 连续几部都放不了：把这一层收干净（画面 / 传输 / 弹幕），只留一句提示。
                // 不写历史（推荐页本来就不写），也不改 `screen`——返回键仍然是回主页面。
                player?.pause()
                showRecommendMessage("${if(failed!=null) "《$failed》" else "这一部"}暂时无法播放\n按确定键重试，按返回键回主页面")
                return
            }
            val next=recommendIndex+1
            when {
                next in recommendItems.indices -> main.post { if(!isDestroyed && screen=="recommend") playRecommendAt(next,0L,1) }
                // 走到末尾：先补一页再从第一部试（这条流到底了，补页那一侧会换一条新流）。
                // 补页请求已经在路上时不能干等（那样这一屏就停在失败的那一集上），直接环绕回第一部。
                else -> main.post {
                    if(isDestroyed || screen!="recommend") return@post
                    if(recommendLoading) {
                        if(recommendItems.isNotEmpty()) playRecommendAt(0,0L,1)
                        else showRecommendMessage("暂时没有可播放的推荐\n按确定键重试，按返回键回主页面")
                    } else {
                        val guard=recommendToken.get()
                        loadRecommendPage(true) {
                            if(recommendToken.get()!=guard) return@loadRecommendPage
                            if(recommendItems.isNotEmpty()) playRecommendAt(0,0L,1)
                            else showRecommendMessage("暂时没有可播放的推荐\n按确定键重试，按返回键回主页面")
                        }
                    }
                }
            }
            return
        }
        FailureAction.RecoverQueuedCurrent -> {
            // 上面已经处理过（接回当前集）；走到这里说明状态已经变了，直接落错误页。
            android.util.Log.w("HGTV","Queued recovery requested but no longer applicable")
        }
        is FailureAction.Replay -> {
            // 解码类：同档重建 / 降一档。决策在 `:core` 的 `PlaybackDegradation` 里。
            replayWithPlan(failureAction.plan)
            return
        }
        is FailureAction.ShowError -> {
            giveUp=failureAction.reason
            // 错误页上要不要排自动重试：决策层已经按 `retryable` / 额度 / 前台状态算过，
            // 这里只负责执行（下面 `if(retryable && !sleepStopped)` 那段仍由本页决定按钮文案）。
        }
    }
    retryPosition=currentPosition().coerceAtLeast(0); retryAutoplay=requestedAutoplay && !sleepStopped
    saveProgress(); cancelRecovery(); cancelPrefetch()
    // 无感切集留下的两个尾巴：缓冲指示要恢复，上一集的视频与传输要收掉（这一集已经出错，不会再出画面了）。
    restoreBufferingUi(); retirePreviousEpisode(); dropQueuedEpisode()
    playError=true; player?.pause(); syncBufferingDots(); progressBar.visibility=View.GONE; hud.visibility=View.VISIBLE; controls.removeAllViews(); controls.visibility=View.VISIBLE; panel=true
    // 停在错误页上不再需要守着屏幕：用户看完提示可能就走开了（见 [syncKeepScreenOn]）。
    syncKeepScreenOn()
    // 顶部那条信息是常驻的，出错时按当前页再刷一遍。
    syncPlaybackInfo()
    dismissPlaybackMenu()
    // 这一次失败落在哪条轨上：下面**所有**文案与按钮都要按它分流。
    // 音频走全表选择器（见 `decoderSelector`），换解码方式与换档位都不作用在它身上，
    // 因此「切换为软解 / 换到 xP」这两颗按钮对音频必须**不出现**——点了只会再撞一次。
    val media=playbackMediaKind(problem)
    val videoProblem=media==MediaKind.VIDEO
    // 值不值得等一会儿再来一次：判据只有一份（`:shared` 的 `PlaybackFailures.isRetryable`），
    // 手机端用的是同一个函数。
    val retryable=com.hgtv.star.shared.media.PlaybackFailures.isRetryable(problem)
    // 解码器压根没起来：这台设备对「能解 HEVC」的声明可能不成立，**重新探一次**。
    //
    // 注意这里**不再**写 `library.maxQuality`（早先是「一失败就压到 480」）：
    // 那是把「这一刻放不了」记成用户的偏好，写进去 `qualityChosenByUser` 就恒为真，
    // 启动时那次「按能力给默认值」永远不再生效，而且 CDN 没有 ≤480 时还会形成
    // 「降档 → 兼容 1080 → 再 4001」的隐式循环（2026-10-28 现场）。
    // 降档由 [PlaybackDegradation] 决策、写进会话层，用户设置只有他自己能动。
    //
    // 也不能在这一条上直接把 `playbackHevcSupported` 判成 false：解码器初始化失败也可能只是
    // 这一刻实例被占满 / 系统忙，一次失败不足以给这台设备定性。重探的结果才是判据。
    if(problem is PlaybackException && problem.errorCode in listOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED) &&
        videoProblem) {
        PlaybackCapability.invalidate(this)
        // 内存里那份「有没有硬解」也要一起作废，否则界面（与手机端）读到的还是旧结论。
        PlaybackCapability.invalidateHardwareHevc()
        io.submit {
            val hevc=PlaybackCapability.canDecodeHevc(this)
            // 重新探一次并灌回内存缓存（判据只有一份）。
            PlaybackCapability.warmHardwareHevc(this)
            val hardware=PlaybackCapability.cachedHardwareHevc() ?: true
            main.post {
                if(isDestroyed) return@post
                // device_level 默认 4、空响应升 5，与解码能力无关，这里不动它。
                playbackHevcSupported=hevc
                hardwareHevcAvailable=hardware
            }
        }
    }
    // 降级决策已经走完也放不了：把「换软解 + 换到哪一档」摆到文案与按钮上，
    // **由用户点**（需求：优先硬解，不支持硬解时提示用户换成软解和支持的清晰度）。
    //
    // 文案**与手机端同一份**（`FailurePresentation.message`）：同一个故障在两端必须是
    // 同一句话，否则用户照着电视上的说法描述问题，手机上看到的却是另一句。
    // 只有排版归本页（控制条里的一行字 + 一排按钮）。
    val manualHint=playbackCoordinator.manualHint
    playbackText.text=FailurePresentation.message(facts,manualHint,playbackHevcSupported)
    // 说明：`giveUp` 只进日志（见本函数末尾那一行），不直接参与这里的文案——
    // 放弃时 `decide` 一定已经写过 `manualHint`，那句带着具体档位，比任何通用文案都准。
    // 按钮的可见性由三件事决定：媒体类型（音频不给视频按钮）、当前解码方式、还有没有更低档。
    val actions=row(); controls.addView(actions)
    // 手动重试会换身份并作废这一集的地址 / 索引缓存，确保真正重新取流。
    addButton(actions,"立即重试") {
        prepareFreshPlaybackRetry()
        // 用户手动重试：归还本集预算（否则决策立刻再返回 null，点一下闪回错误页），
        // 但**不**撤销已经降下来的档位——那是既成事实，倒退只会再撞一次。
        // 归还次数有上限（见 `MANUAL_RETRY_LIMIT`），因此下面必须带 `recovering=true`：
        // 那一位才保证 `onEpisodeStart` 不去重置预算与归还次数，否则连点就成了无限次降档。
        //
        // 还款必须**一并还自动重试那 3 次**（见 [resetBudgetsForManualRetry]）：那是另一套
        // 额度，归零条件同样挂在 `recovering=false` 上。少了这一半，自动重试一旦用尽，
        // 用户点这颗按钮之后 `scheduleRecovery()` 第一句就返回，从此再也不会有任何自动重试
        // ——手动重试反而把这一集的重试永久关掉了。
        resetBudgetsForManualRetry()
        playEpisode(episodeIndex,retryPosition,retryAutoplay,recovering=true)
    }.requestFocus()
    // 下面两个按钮的判据取**这一次真正失败的那条流**（`failedQueuedInfo` 优先）：
    // 队列那一集失败时 `video` 是当前这一集，用它会让按钮作用错一集——文案说「480P 放不了」，
    // 点下去换的却是当前集的档。与 [playbackAttribution] 同源。
    // 按钮的**能力判据**与手机端同一份（`FailurePresentation.capabilities`）：
    // 只对视频给「换软解 / 换档位」，音频走全表选择器，那两颗对音频完全无效。
    // 换档目标也必须**严格低于刚刚失败的那一档**（判据在 `FailurePresentation.swapTarget`），
    // 否则降到 480 失败后按钮仍写「换到 720P」，点下去把清晰度抬回去、必然复现同一类失败。
    val caps=FailurePresentation.capabilities(facts,attr,playbackHevcSupported)
    // 硬解失败：给一个「切换为软解」的按钮，并**同时**把档位降到软解跑得动的那一档
    //（只换解码器不降档，1080 软解在盒子上照样是掉帧 / 卡死，等于白换一次）。
    //
    // 上限取**当前这一档**：换解码器允许停在原档（480 软解照放），但绝不能把已经降下来的
    // 档位抬回去——那是「这台机器这一刻放不了」的既成结论，与 `SessionPlaybackPolicy`
    // 里「不倒退」的口径一致。
    val softTarget=FailurePresentation.softwareTarget(attr)
    if(FailureCapability.SWITCH_SOFTWARE in caps) addButton(actions,"切换为软解") {
        library.decoderMode=VideoDecoderMode.SOFTWARE
        playbackCoordinator.onManualDecoderChange(softTarget)
        applyDecoderMode(retryPosition,retryAutoplay)
    }
    val swapTarget=FailurePresentation.swapTarget(attr)
    if(FailureCapability.CHANGE_QUALITY in caps) addButton(actions,"换到 ${swapTarget}P") {
        prepareFreshPlaybackRetry()
        // 同「立即重试」：这是用户主动发起的新一轮尝试，自动重试 / 画面重建的额度一起还。
        // 档位在还款之后才落进会话层——`onManualRetry` 不动 `effectiveMaxQuality`，
        // 顺序反过来也能工作，但先还账再定档读起来才是「先清预算、再设目标」。
        resetBudgetsForManualRetry()
        playbackCoordinator.setMaxQuality(swapTarget)
        playEpisode(episodeIndex,retryPosition,retryAutoplay,recovering=true)
    }
    addButton(actions,"返回选集") { returnToDetail(true) }
    if(retryable && !sleepStopped) {
        addButton(controls,"停止自动重试") { cancelRecovery(); playbackText.text="自动重试已停止，观看位置已保留。" }
        autoRecovery=true; scheduleRecovery()
    }
    // 错误界面挂一枚短码（同一行，不加行——这一行高度是钉死的 47dp）。
    // 用户反馈「解码失败」时报出它，就能直接对上诊断里那一条。
    //
    // **没有 `PlaybackException` 的路（本地准备失败、取流失败）没有数字码**：
    // 早先照样拼一个「错误码 0」出来，用户报「错误码 0」时谁也查不到东西；
    // 后来改成挂异常类名，又踩了另一个坑——正式包经 R8 混淆之后类名变成 `y1` / `a1`
    // 这种两三字符的短码，现场就出现「播放失败 · Y1」，用户报上来谁也查不到（1.2.7 实测）。
    // 因此这里只输出 [playbackFailureTag] 里的稳定常量。
    val code=(problem as? PlaybackException)?.errorCode ?: 0
    if(code!=0) playbackText.text="${playbackText.text} · 错误码 $code"
    else playbackText.text="${playbackText.text} · ${playbackFailureTag(problem)}"+
        // 为什么还要把原因挂上去：`本地准备失败` 底下有十几种完全不同的原因
        //（容器不认 / 索引不在文件头 / 加密索引缺失 / 索引截断 / 资源换了……），
        // 只报一个大类等于没报——现场只能回一句「查不到」。这些 message 本来就是中文短句，
        // 直接给用户看，他报上来的那句话就能落到具体那一行判断上。
        playbackFailureReason(problem)?.let { "：$it" }.orEmpty()
    // 日志同样不带类名：正式包里那串是混淆之后的短码，读日志的人对不上。
    // 阶段（`playback_prepare` / `playback_io` …）是稳定聚合键，与调试面板同源。
    // `giveUp` 一并打出来：现场问「为什么它没自动降档」时，这一栏直接回答
    //（音频没有可换的维度 / 已经是最低档 / 预算用完）。
    android.util.Log.w("HGTV","Playback failure stage=${playbackStage(problem)} tag=${playbackFailureTag(problem)}"+
        (if(problem is PlaybackException) " code=${problem.errorCodeName}" else "")+
        " giveUp=${giveUp?.name ?: "-"}"+
        " msg=${playbackFailureReason(problem) ?: "-"}")
}
/**
 * 队列里那一集出错之后，把**当前这一集**原地接回来（同档、静默、不动预算）。
 *
 * ## 为什么必须做
 *
 * ExoPlayer 对**队列里那一集**（片尾预热挂进去的下一集）的加载 / 解码失败，同样报成致命
 * `PlayerError`，而 2.19 的 `ExoPlayerImpl` 收到错误的第一件事就是
 * `PlaybackInfo.copyWithPlaybackState(1)`——整台播放器落到 `STATE_IDLE`，**当前这一集的画面
 * 同时停住**（`isPlaying` 也是 false，没有任何「还在正常播」的状态可读）。
 * 于是「下一集那条流坏了」在用户眼里是「这一集播到一半黑掉了」。
 *
 * 早先这里直接往后落到降级决策：消费**当前集**的预算、把 `effectiveMaxQuality` 压下一档
 * （跨集沿用）、最后弹错误页。用户看到的后果是「第 7 集放不了，之后整部剧都变糊了」——
 * 而当前这一集根本没坏。
 *
 * ## 为什么代价可以接受
 *
 * 当前这一集的播放地址 / moov 索引 / 首块全部命中缓存（见 `PlaybackCache` 与
 * `RemoteVideo`），重开一次**一个 Range 都不发**，代价只是一次换源黑屏。
 * 相比「把用户扔到错误页 + 永久压一档」，这个交换明显划算。
 *
 * ## 三条硬边界
 *
 * 1. 不消费 `SessionPlaybackPolicy` 的预算、不写档位上限——这不是「这台机器放不了这一档」
 *    的结论，坏的是隔壁那一集；
 * 2. 不弹提示、不进错误页、不写观看历史（`saveProgress` 在 `playError` 之前不会被走到，
 *    而这里连 `playError` 都不置位）；
 * 3. **有界**：[QUEUED_RECOVERY_LIMIT] 次都救不回来，说明坏的是当前这一集自己或者本机
 *    解码器，这才让位给正常的降级 / 错误页路径（那里的归因已经指向队列那一集，
 *    见 `playerFailure` 里的 `failedQueuedInfo`）。
 *
 * 次数按「集 + 当前上限」计：换集（非 `recovering`）归零，见 [playEpisode]。
 */
private const val QUEUED_RECOVERY_LIMIT=2

private fun MainActivity.recoverQueuedCurrent(): Boolean {
    val key="${detail?.episodes?.getOrNull(episodeIndex).orEmpty()}:${playbackMaxQuality}"
    val used=playbackCoordinator.queuedRecoveryCount(key)
    if(used>=QUEUED_RECOVERY_LIMIT) return false
    // 位置直接问播放器：`STATE_IDLE` 下它仍然保留失败那一刻的读数（错误不会把位置清零），
    // 而 [currentPosition] 会看 `pendingSeek` / `playbackReady`，在出错态下不可靠。
    val live=runCatching { player?.currentPosition ?: -1L }.getOrDefault(-1L)
    val at=(if(live>0) live else startPosition).coerceAtLeast(0)
    playbackCoordinator.noteQueuedRecovery(key)
    // 队列那一集已经确认坏过一次：片尾不再对它做「同档重切一次」的那次尝试
    //（见 `playerFailure` 里 `atEnd` 分支的 `ledger.autoNextFailures<1`）——重来只会再黑一次。
    playbackCoordinator.noteAutoNextFailure()
    // 片尾预热窗口的计数一并归零：这一集要重新挂下一集，得从头数它自己的重发次数。
    prefetchAttempts=0; prefetchAttemptAt=0
    android.util.Log.w(
        "HGTV",
        "Queued episode failed mid-episode; reopening current=${episodeIndex+1} at=${at}ms"+
            " try=${used+1}/$QUEUED_RECOVERY_LIMIT quality=${playbackMaxQuality}P"
    )
    playEpisode(episodeIndex,at,requestedAutoplay && !sleepStopped,recovering=true)
    return true
}

/**
 * 错误页那枚短码：没有数字错误码时显示什么。
 *
 * **只返回稳定常量**，绝不返回 `javaClass.simpleName`：正式包里类名被 R8 混淆成
 * `y1` / `a1` 这种两三个字符的短码，页面就成了「播放失败 · Y1」——用户报这串字母，
 * 谁都查不出是哪条路径。这里按「用户能看懂、我们能对上阶段」来分：
 *
 * | 短码 | 含义 | 对应阶段 |
 * | --- | --- | --- |
 * | 取流失败 | 取播放地址 / 拉视频字节失败（网络、网关空响应） | `playback_io` |
 * | 本地准备失败 | 地址拿到了，但容器 / 索引 / 加密头读不出来 | `playback_prepare` |
 * | 地址过期 | 缓存下来的地址被服务端提前作废（重取一次仍失败） | `playback_io` |
 * | 资源已变化 | 这一集的文件换过了，旧索引对不上（重取一次仍失败） | `playback_io` |
 * | 内存不足 | `OutOfMemoryError` 被兜住 | `playback_oom` |
 */
fun playbackFailureTag(problem: Throwable?): String = when(problem) {
    null -> "未知错误"
    // 网络类排最前：它才是现场绝大多数「本地准备失败」的真身。
    // `remote.prepare()` 里那次 `range()` 也会抛 `IOException`（DNS / 超时 / 连不上），
    // 早先一律被包成 [PlaybackPrepareException]，于是**连不上服务器**也显示「本地准备失败」，
    // 把排查方向整个带偏（现场：真因是视频 CDN 域名解析不出来）。
    else -> when {
        networkFailureOf(problem)!=null -> "取流失败"
        hasCauseOf<PlaybackPrepareException>(problem) -> "本地准备失败"
        hasCauseOf<com.hgtv.star.core.StreamExpiredException>(problem) -> "地址过期"
        hasCauseOf<com.hgtv.star.core.VideoChangedException>(problem) -> "资源已变化"
        hasCauseOf<java.io.IOException>(problem) -> "取流失败"
        problem is OutOfMemoryError -> "内存不足"
        else -> "未知错误"
    }
}
/**
 * 这一串异常里那句**能落到具体判断**的原因，没有就返回 null。
 *
 * 只收「短、单行、消息本身是给人看的」那种：`视频索引不在文件开头`、`没有可用清晰度`。
 * 长度上限 20：再长就会把错误页那一行顶出屏幕，而看不到的那半句对定位没有帮助
 *（真要长消息，日志里有完整一条）。深度上限 8，与 [streamExpiredOf] 一致。
 */
fun playbackFailureReason(problem: Throwable?): String? {
    // 网络类优先：那一句「视频服务器域名无法解析」比任何英文系统错误都好用，
    // 而且它自带下一步（查 DNS / 换线路），不会被 20 字的长度上限截掉。
    networkFailureOf(problem)?.let { return it }
    var current=problem
    var depth=0
    while(current!=null && depth<8) {
        val message=current.message
        if(!message.isNullOrBlank() && message.length<=24 && '\n' !in message) return message
        current=current.cause
        depth++
    }
    return null
}
/** 顺着 cause 链找某个类型，深度上限 8（与 [streamExpiredOf] 同因：防环形 cause）。 */
inline fun <reified T: Throwable> hasCauseOf(problem: Throwable?): Boolean {
    var current=problem
    var depth=0
    while(current!=null && depth<8) {
        if(current is T) return true
        current=current.cause
        depth++
    }
    return false
}
/**
 * 这一串异常里有没有「网络层就没通」，有就返回一句给用户看的话。
 *
 * 与 [playbackFailureReason] 分开，是因为它要**优先于**类型判断：同一个
 * `UnknownHostException` 可能被 `PlaybackPrepareException`、`HttpDataSourceException`
 * 或 [PlaybackException] 包上一层两层，只看最外层的类型永远归错类。
 */
fun networkFailureOf(problem: Throwable?): String? {
    var current=problem
    var depth=0
    while(current!=null && depth<8) {
        when(current) {
            // 顺序有意义：`SocketTimeoutException` / `ConnectException` 都是 `IOException`，
            // `UnknownHostException` 也是——所以这几个具体类型必须排在笼统判断前面。
            is com.hgtv.star.core.AddressTimeoutException -> return "取播放地址超时"
            is java.net.UnknownHostException -> return "视频服务器域名无法解析"
            is java.net.SocketTimeoutException -> return "连接视频服务器超时"
            is java.net.ConnectException -> return "连不上视频服务器"
            is java.net.NoRouteToHostException -> return "网络不通，无法访问视频服务器"
            is javax.net.ssl.SSLException -> return "视频连接加密握手失败"
            // **整调用超时**（OkHttp 的 `callTimeout`，45 秒）：抛的是 `InterruptedIOException("timeout")`，
            // 不是 `SocketTimeoutException`（那个是单次读超时，30 秒）。
            //
            // 现场（2026-10-30，小米 TV，1.2.9.1(67)）：
            // `PlaybackPrepareException: timeout <- InterruptedIOException: timeout <- SocketException: Socket closed`
            // ——一次 Range 读整整 45 秒没读完，被 okhttp 掐掉并关了 socket。
            // 它**必须算网络失败**：否则会被外面包成「播放地址准备失败」（本地准备），
            // 既不换地址、也不清 DNS 与连接池，用户重试多少次都是同一条半死连接
            // （见 [transportDeadOf] 与 [dropDeadTransport] 的说明）。
            //
            // 只认消息里带 `timeout` 的那种：`InterruptedIOException` 也被 OkHttp 用来表达
            // 「线程被打断 / 取消」（消息是 `interrupted` / `interrupted by`），
            // 那是我们自己切页造成的，算成网络故障会触发无意义的自愈。
            is java.io.InterruptedIOException ->
                if(current.message.orEmpty().contains("timeout",ignoreCase=true)) return "视频服务器响应超时"
        }
        current=current.cause
        depth++
    }
    return null
}
/**
 * 这一串异常里有没有「这一集的文件换过了」。
 *
 * 判断本体已收进 `:shared` 的 `PlaybackFailures.videoChanged`（手机端同一份）：
 * 地址过期 / 文件换过 / 域名解析失败 / 连接层假死这四条判据两端必须完全一致，
 * 否则同一个故障在电视上自己救回来了、在手机上直接死掉。
 */
fun videoChangedOf(problem: Throwable?): Boolean =
    com.hgtv.star.shared.media.PlaybackFailures.videoChanged(problem)
/**
 * 这一串异常里有没有「名字解析出来了，但连接层就没通」。
 *
 * 判断本体见 `:shared` 的 `PlaybackFailures.transportDead`（与手机端同一份）。
 */
fun transportDeadOf(problem: Throwable?): Boolean =
    com.hgtv.star.shared.media.PlaybackFailures.transportDead(problem)
/**
 * 自愈前把「连接层缓存」一起清掉：视频域名的解析结果 + 进程级连接池。
 *
 * 清法收在 `:shared` 的 `PlaybackFailures.dropTransportCaches`（手机端同一份）；
 * 这里只负责按优先级收集「这一次可能涉及的 host」——`prepare()` 失败时 [MainActivity.video]
 * 还是上一集的（见该字段的注释），拿它清等于清错对象，因此要带上 [MainActivity.lastStreamHost]。
 */
fun MainActivity.dropDeadTransport() {
    val hosts=LinkedHashSet<String>(4)
    lastStreamHost.takeIf { it.isNotEmpty() }?.let(hosts::add)
    video?.host?.takeIf { it.isNotEmpty() }?.let(hosts::add)
    queuedVideo?.host?.takeIf { it.isNotEmpty() }?.let(hosts::add)
    com.hgtv.star.shared.media.PlaybackFailures.dropTransportCaches(hosts)
    if(hosts.isNotEmpty()) android.util.Log.w("HGTV","Transport cache cleared hosts=$hosts pool=evicted")
}
/**
 * 这一串异常里有没有「播放地址已失效」。
 *
 * 判断本体见 `:shared` 的 `PlaybackFailures.expired`（与手机端同一份）。
 */
fun streamExpiredOf(problem: Throwable?): Boolean =
    com.hgtv.star.shared.media.PlaybackFailures.expired(problem)
fun MainActivity.hidePlaybackOverlay() {
    dismissPlaybackMenu(); controls.visibility=View.GONE; controls.clearFocus(); hud.visibility=View.GONE
    // 方向键开关放在 [dismissPlaybackMenu] **之后**归零：那个函数会把 `panel` 还原成
    // [playError]（出错页的按钮要能被焦点选中），这里既然连控制条都收了，就一定是 false。
    panel=false
    // 顶部那条信息跟着控制条一起收：浮层收起之后，四项回到「播放显示」里用户选的值
    // （默认全关，见 [syncPlaybackInfo]）。两页都要同步——播放页收起控制条时
    // 那四项也不该继续常驻，否则「默认全不选」就落不实。
    syncPlaybackInfo()
    // 三块浮层都收了，自动消失的定时器也跟着作废（留着只会在几百毫秒后再跑一次空动作）。
    // 放在最后：此刻 `hud` 已经是 GONE，`scheduleOverlayAutoHide()` 里的判据也不会再把它排上。
    cancelOverlayAutoHide()
}
/**
 * 播放页三块浮层（播放设置 / 选集面板 / 底部控制条）的「无操作自动消失」计时：从**现在**开始重新数。
 *
 * 这几种时候调它：面板或控制条刚打开、用户按了一下（方向键 / 确定 / 触摸）、
 * 拖动了进度条或滑块。规则是「最后一次操作之后再过 N 秒」，因此每次都是先取消再重排——
 * 不能只排不管，那样第二次操作就白按了。
 *
 * 三块浮层都不在时什么都不做（顺手把上一次的定时器清掉）。播放设置开着时控制条本来就是收着的
 * （见 `showPlaybackMenu`），因此这里按「菜单 → 选集 → 控制条」的优先级只看一处就够。
 */
fun MainActivity.scheduleOverlayAutoHide() {
    main.removeCallbacks(overlayAutoHide)
    val visible=playbackMenu!=null || episodePanel!=null || hudShown()
    if(!visible) return
    // 设置还没读出来时用出厂值：冷启动直接进播放页时 `library` 可能仍为空。
    val seconds=runCatching { library.playerPanelTimeout }.getOrDefault(PLAYER_PANEL_TIMEOUT_DEFAULT)
    main.postDelayed(overlayAutoHide,(seconds*1000L).coerceAtLeast(1000L))
}

/** 浮层收起（选择完成、返回键、自动消失）时把定时器一并停掉。 */
fun MainActivity.cancelOverlayAutoHide() = main.removeCallbacks(overlayAutoHide)

/**
 * 底部控制条此刻是不是露着（推荐页也算：手机 / 平板那一页用的是同一条控制条）。
 *
 * 「算不算露着」按页面分两种口径：
 * * 正式播放：只要露着就算——暂停、出错文案之外的任何状态都要参与自动收起；
 * * 推荐页：**只有触屏**才算。电视上那一页根本没有这条控制条（信息在常显的底部浮层里，
 *   见 `attachRecommendOverlay`），`touchArmed` 为 false 时这里也就不会误判成「露着」。
 *
 * `hud` 是 lateinit：冷启动还没建播放层时读它会抛，因此用一次受捕的读取兜住
 *（与 [playbackOverlayShown] 同一套写法）。
 */
fun MainActivity.hudShown(): Boolean = runCatching {
    (screen=="player" || (screen=="recommend" && touchArmed)) &&
        !panel && hud.visibility==View.VISIBLE
}.getOrDefault(false)
/**
 * 当前这一屏是不是播放页：正式播放与推荐播放。
 *
 * 两条看门狗（起播不出帧 / 播着播着停帧）过去只认 `screen=="player"`，
 * 而推荐页跑的是同一套播放内核，同样会出现「有声音、没画面」——那一页上这个故障
 * 恰恰最显眼（整屏都是视频）。因此「要不要盯」与「算不算离开播放页」统一走这一个判据，
 * 免得以后再出现一处改了、另一处没跟上。
 */
fun MainActivity.onPlaybackPage(): Boolean = screen=="player" || screen=="recommend"

/**
 * 屏幕常亮的**唯一判据**。
 *
 * 早先是 `playEpisode` 里无条件 `addFlags`，只在 `requestPlayback(false)` / `stopForSleep` /
 * 离开播放页三处 `clearFlags`。漏掉的正是最要命的两种：
 *
 * 1. **暂停后走开**——包括从后台回来的那次 `playEpisode(..., autoplay=false)`：
 *    画面停在某一帧、`playWhenReady=false`，屏幕却一直亮到用户动手；
 * 2. **停在错误页上**——`playerFailure` 只 `player?.pause()`，从不碰这个 flag。
 *    用户看完「解码失败」放下遥控器，电视一整夜不黑屏。
 *
 * OLED / 等离子面板上，静态标题条 + 静态错误文案就是清晰的烧屏痕迹；LCD 上是背光寿命。
 *
 * 判据只看「此刻真的在推进」：前台 + 在播放页 + 没出错 + 没被定时停 + `playWhenReady`。
 * 缓冲中 `playWhenReady` 仍为 true，因此缓冲不会掉到息屏——那正是最不该黑屏的时候。
 *
 * **所有 add/clear 都必须走这里**：分散在两处各写一次，迟早又会漏掉一条新路径。
 */
fun MainActivity.syncKeepScreenOn() {
    val on=foreground && onPlaybackPage() && !playError && !sleepStopped &&
        player?.playWhenReady==true
    if(on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    // 屏幕常亮只管屏幕，不管 CPU，也不管 Wi-Fi 省电。这两件事与「是否在播」是同一个判据，
    // 因此在这里一起驱动（见 `PlaybackPower` 的类注释：分散在两处各写一次迟早会漏）。
    if(on) playbackPower.acquire() else playbackPower.release()
}

fun MainActivity.showHud() {
    // 设置菜单打开时底部控制条保持收起，避免和菜单抢同一块画面。
    if(playbackMenu!=null) return
    // 推荐页：只有触屏设备才露这条控制条（它上面的那排按钮本来也只在触屏上存在）。
    // 电视上推荐页从来没有控制条——那一页的信息在底部浮层里，结构保持不变。
    if(screen!="player" && !(screen=="recommend" && touchArmed)) return
    hud.visibility=View.VISIBLE; updatePlaybackText(); cancelOverlayAutoHide()
    syncPlaybackInfo()
    // 控制条：**不看是否在播**——暂停时同样按设置里的秒数收起
    //（原来只在 `isPlaying` 时排定时器，一暂停控制条就永久留在画面上，
    // 暂停久了一张静态的进度条一直压在画面底部）。
    //
    // 判据只写「在播放页就排」，不再看 `panel`：只要这一下是**唤出控制条 / 进度条**，
    // 就一律归到同一个计时器里数。出错那一版控制条（`playError`，上面压着错误文案）
    // 由定时器自己的判据挡掉——到点什么都不做，等于没排。这样「唤出 = 开始计时」
    // 只有一条规则，以后再加唤出路径也不会漏掉计时的分支。
    //
    // 推荐页同样走这一条：手机 / 平板上那一页的控制条与正式播放是同一条，
    // 到了上面那一行「触屏才算」的判据之外没有别的情形，因此这里不再按页面分支。
    // 上下换剧、重新进推荐页都会再唤一次（见 `startRecommendEpisode`），计时随之重排。
    scheduleOverlayAutoHide()
}
fun MainActivity.updatePlaybackText() {
    // 触屏设备上控制条多一排按钮，播放键的文案也要跟着播放状态改（电视上为空操作）。
    updateTouchPlayLabel()
    // 顶部那格「第 x / 共 N 集」跟着当前集号走：无感切集不走 `playEpisode`，
    // 只在这里对一次，任何换集路径（含片尾自动推进）都不会留下上一集的集号。
    syncPlaybackEpisodeLabel()
    // 推荐页（触屏）用的是**同一条控制条**，读数与状态项这一整段逻辑完全一样，
    // 因此这里不再只认 `player`——否则唤出来的控制条上进度与「清晰度 / 倍速 / 画面」都是空的。
    // 电视上的推荐页仍然走它自己的浮层刷新（`updateRecommendOverlay`），这一条不会被执行。
    if(screen!="player" && !(screen=="recommend" && touchArmed)) return
    // 手指正在拖进度条：这一段时间进度与时间由手指决定，每秒一次的刷新不能把它顶回去。
    if(scrubbing) return
    // 推荐页拖的是同一条进度条，它的「拖动中」标记在 `onRecommendScrub` 里（见那里）。
    if(screen=="recommend" && recommendScrubbing) return
    // 播放键图标与选项行的当前值：爆米花把状态放在图标和选项文字里，不额外占一行。
    transportIcon?.setVectorIcon(if(player?.playWhenReady==true) R.drawable.ic_player_pause else R.drawable.ic_player_play)
    qualityOption?.text="清晰度 "+if(quality.isBlank()) "${playbackMaxQuality}P" else quality
    speedOption?.text="倍速 "+PlaybackSpeed.label(if(playbackReady) player?.playbackParameters?.speed ?: library.playbackSpeed else library.playbackSpeed)
    frameOption?.text="画面模式 "+library.frameMode.label
    // 状态行只放显示方式：弹幕条数放在「设置 → 弹幕显示」那一行，
    // 播放界面的这一排已经够长，再加数字会被屏宽裁掉。
    danmakuOption?.text="弹幕 "+library.danmakuMode.label
    sleepOption?.text="定时 "+sleepTimer.label()
    syncBufferingDots()
    if(playError) return
    if(sleepStopped) { playbackText.text="定时停止 · 按播放继续"; return }
    if(!playbackReady) { playbackText.text=""; return }
    val p=player ?: return
    val duration=p.duration.coerceAtLeast(0); val position=pendingSeek ?: p.currentPosition
    val span="${formatSpan(position)} / ${formatSpan(duration)}"
    playbackText.text=if(p.playbackState==Player.STATE_ENDED) "本集已结束 · $span" else span
    progressBar.progress=if(duration>0) position.toFloat()/duration else 0f
    progressBar.bufferedProgress=if(duration>0) {
        p.bufferedPosition.coerceIn(0,duration).toFloat()/duration
    } else 0f
}
/**
 * 顶部信息条里的集数读数：`第 3 / 24 集`。
 *
 * 集号与总集数都从 `detail` + [episodeIndex] 现算，**不缓存**在调用点——
 * 片尾无感切集（[onEpisodeAdvanced]）换的是播放器的 period，不走 [playEpisode]，
 * 早先只有 `playEpisode` 写这一格，于是自动连播之后界面上还挂着上一集的集号。
 *
 * 内容没变就不 `setText`：这条每秒都会跟着 tick 调一次，重复设置会让整条重排。
 * [onEpisodeAdvanced] 与 [playEpisode] 里可以显式传 `data`，其余路径不传就用当前的 `detail`。
 */
fun MainActivity.syncPlaybackEpisodeLabel(data: Detail? = detail) {
    val view=playbackEpisode ?: return
    // 推荐页不显示「第 x / y 集」；正式播放页仍按「播放显示」开关正常显示。
    if(screen=="recommend") {
        view.visibility=View.GONE
        return
    }
    val total=data?.episodes?.size ?: 0
    if(total<=0) return
    val key="${episodeIndex}/${total}"
    if(view.tag==key) return
    view.tag=key
    // 集数文案只有一份（`:core` 的 `PlaybackInfoLine`）：手机端信息条读的是同一段格式。
    view.text=com.hgtv.star.core.PlaybackInfoLine.episode(episodeIndex,total)
}
fun MainActivity.requestPlayback(play: Boolean) {
    requestedAutoplay=play; pausedForLifecycle=!play
    if(play) sleepStopped=false
    if(!play) { player?.pause(); retryAutoplay=false; saveProgress() }
    // 错误页上按「播放」＝一次手动重试：与错误页那颗按钮走**同一条**还款路径
    //（`resetBudgetsForManualRetry`），否则两个入口的额度语义不一样：
    // 按钮还三份、这里只还 session 那一份，自动重试用尽之后按播放就只会闪回同一句提示。
    else if(playError) {
        resetBudgetsForManualRetry()
        // 与错误页「立即重试」走同一条：按播放键也必须先作废当前集的地址 /
        // 索引 / 首块并换请求 device_id，否则会原样命中刚才失败的缓存。
        prepareFreshPlaybackRetry()
        playEpisode(episodeIndex,retryPosition,true,recovering=true)
        return
    }
    else { if(player?.playbackState==Player.STATE_ENDED) player?.seekTo(0); player?.play() }
    // 屏幕常亮由状态推导，不再在这里 add/clear（见 [syncKeepScreenOn]）：
    // `play` 与「真的在播」不是同一件事——出错页、定时停、后台回来这三条都会不一样。
    syncKeepScreenOn()
    showHud(); updatePlaybackText(); syncMediaSession()
}
fun MainActivity.togglePlayback()=requestPlayback(!(if(playbackReady) player?.playWhenReady ?: requestedAutoplay else requestedAutoplay))
fun MainActivity.seekTo(position: Long) {
    val p=player ?: return; if(!playbackReady || p.duration<=0) return
    main.removeCallbacks(seekRunnable); pendingSeek=null
    p.seekTo(position.coerceIn(0,(p.duration-500).coerceAtLeast(0))); updatePlaybackText(); syncMediaSession(); showHud()
}
fun MainActivity.skipEpisode(direction: Int) {
    if(!foreground || (screen!="player" && screen!="recommend")) return
    val count=detail?.episodes?.size ?: return
    val target=episodeIndex+direction
    if(target in 0 until count) playEpisode(target,clearDanmakuOnSwitch=true)
}
/**
 * 媒体会话懒创建：启动阶段不碰系统 `media_session`。
 *
 * 虚拟容器（SandVXposed 等）会把 `ISessionManager.createSession` 的返回值代理成 null，
 * 而平台 `MediaSession` 构造函数不判空，直接在 `mBinder.getController()` 抛 NPE，
 * 顺着 `onCreate` 变成「Unable to start activity」闪退。这里建不出来就记住失败，
 * 之后静默跳过——遥控器媒体键只是附加能力，不影响播放。
 */
fun MainActivity.ensureMediaSession(): MediaSessionHost? {
    mediaSession?.let { return it }
    if(mediaSessionFailed) return null
    val created=MediaSessionHost.create(this,
        { if(foreground && (screen=="player" || screen=="recommend")) requestPlayback(true) },
        { if(foreground && (screen=="player" || screen=="recommend")) requestPlayback(false) },
        { position -> if(foreground && (screen=="player" || screen=="recommend")) seekTo(position) },
        { direction -> if(screen=="recommend") switchRecommend(direction) else skipEpisode(direction) },
        { if(foreground && screen=="player") returnToDetail() })
    if(created==null) mediaSessionFailed=true else mediaSession=created
    return created
}
fun MainActivity.syncMediaSession() {
    if(!foreground || (screen!="player" && screen!="recommend")) { mediaSession?.deactivate(); return }
    val data=detail ?: return; val p=player
    val session=ensureMediaSession() ?: return
    val state=when {
        playError -> android.support.v4.media.session.PlaybackStateCompat.STATE_ERROR
        sleepStopped || !requestedAutoplay || (p!=null && !p.playWhenReady) -> android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED
        p==null || !playbackReady || p.playbackState==Player.STATE_BUFFERING -> android.support.v4.media.session.PlaybackStateCompat.STATE_BUFFERING
        p.playbackState==Player.STATE_ENDED -> android.support.v4.media.session.PlaybackStateCompat.STATE_STOPPED
        p.isPlaying -> android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING
        else -> android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED
    }
    session.update(data.series.title,episodeIndex,p?.duration?.coerceAtLeast(0) ?: 0,currentPosition(),library.playbackSpeed,state,episodeIndex>0,episodeIndex<data.episodes.lastIndex)
}
fun MainActivity.stopForSleep() {
    dropQueuedEpisode()
    sleepStopped=true; requestedAutoplay=false; retryAutoplay=false; pausedForLifecycle=true
    sleepChoice=0
    cancelRecovery(); player?.pause(); saveProgress()
    syncKeepScreenOn()
    playbackText.text="定时停止已生效 · 观看位置已保存 · 按播放可继续"
    showHud(); if(playbackMenu!=null) showPlaybackMenu(sleepRow(library.danmakuMode.showsStyle)); syncMediaSession()

}
/** 定时项目的 8 个选项：关闭排在最前，选中态跟随本次观看已生效的定时。 */
fun MainActivity.sleepChoices(menu: PlaybackMenu): List<PlaybackMenu.Choice> {
    val current=if(sleepTimer.active) sleepChoice else 0
    return SleepTimer.LABELS.mapIndexed { index,label ->
        PlaybackMenu.Choice(label,current==index) {
            sleepChoice=index
            // 档位与分钟的对应关系只有一份（`core/SleepTimer.applyChoice`），两端同源。
            SleepTimer.applyChoice(sleepTimer,index)
            // 定时生效期间不放下一集进队列（见 [enqueuePreparedNext]）：已经在队列里的先摘掉，
            // 否则片尾 ExoPlayer 会自己推进，界面来不及在片尾那一下停住。
            if(index!=0) dropQueuedEpisode()
            menu.select(sleepRow(library.danmakuMode.showsStyle),index); updatePlaybackText()
        }
    }
}
/**
 * 画面模式 → ExoPlayer 的 `RESIZE_MODE_*`，**电视端唯一的一处绑定**。
 *
 * 「用户选了什么」到「最终怎么摆」的判定在 `:core`（[com.hgtv.star.core.VideoFrameMode.toFit]），
 * 与手机端同源；这里只负责把那个结果换成平台常量。电视是横屏设备，不存在竖屏片源，
 * 因此不传方向（默认 `false`）。
 */
fun com.hgtv.star.core.VideoFit.resizeMode()=when(this) {
    com.hgtv.star.core.VideoFit.FIT -> com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
    com.hgtv.star.core.VideoFit.ZOOM -> com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    com.hgtv.star.core.VideoFit.FILL -> com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
}
fun MainActivity.frameResizeMode()=library.frameMode.toFit().resizeMode()
/**
 * 「竖屏短剧全屏」**这台设备上能不能用**：只有手机 / 平板可用，电视不可用。
 *
 * 形态来自 [deviceMode]：用户手动选过（`library.deviceForm` = phone/tablet/tv）就以他选的为准，
 * 没选过（`auto`）就看启动时 [com.hgtv.star.shared.compat.detectDeviceForm] 的打分结果。
 *
 * 为什么电视上不给用：电视是**遥控器 + 固定 16:9 大屏**，播放页的画面几何由
 * `applyVideoGeometry()` 统一算，竖屏片已经按「等比铺满、不裁内容」摆放；
 * 再叠一层整屏旋转只会把标题条、控制条、弹幕一起转过去，在 3 米外看是斜的。
 * 这一项留给真的会竖着拿、或系统不转屏的手机 / 平板用。
 *
 * 因此设置页在电视上把它画成不可用（见 `MainActivitySettings` 里那一格），
 * 播放层也在这里挡住——同一份设置从手机同步到电视时，存下来的值不会在电视上偷偷生效。
 *
 * 未判定（`DeviceForm.UNKNOWN`，算法没跑起来）同样按「不可用」处理：这种形态既不能
 * 断言是手机也不能断言是平板。真要用，用户在设置里手动选「手机」或「平板」即可。
 */
fun MainActivity.autoFullscreenAvailable(): Boolean = when(deviceMode()) {
    com.hgtv.star.shared.compat.DeviceForm.PHONE,
    com.hgtv.star.shared.compat.DeviceForm.TABLET -> true
    else -> false
}

/**
 * 「竖屏短剧全屏」要不要把画面**转 90°**。
 *
 * 需求的原话是「屏幕是 16:9、短剧是 9:16，把短剧旋转一下就可以自动匹配全屏」——
 * 也就是**画面转向**，不是放大裁切：竖屏片转 90° 之后就是 16:9，
 * 正好铺满屏幕，一个像素都不用裁。
 *
 * 只在这三种情况同时成立时才转：
 * 1. 开关打开；
 * 2. 片源是竖屏（高 > 宽），也就是短剧；
 * 3. 屏幕是横屏（宽 > 高），也就是横放的平板 / 手机。
 *
 * 屏幕本身是竖的时候（平板竖着拿、分屏）不转：那时竖屏片本来就是满的，
 * 转过去反而变成两边黑边。
 */
fun MainActivity.autoFullscreenRotated(): Boolean {
    if(!library.autoFullscreen) return false
    // 只有手机 / 平板能用（见 [autoFullscreenAvailable]）：电视上这个开关存着也不生效。
    if(!autoFullscreenAvailable()) return false
    // 视频必须画在 TextureView 上才转得动：SurfaceView 是独立图层，父级旋转不会作用到它，
    // 硬转会得到一张拉扁的画面。这一条是兜底（开关打开时建的就是 TextureView 那一版）。
    if(playerView?.videoSurfaceView !is android.view.TextureView) return false
    val videoW=videoWidth; val videoH=videoHeight
    if(videoW<=0 || videoH<=0 || videoW>=videoH) return false
    val screenW=root.width; val screenH=root.height
    if(screenW<=0 || screenH<=0) return false
    return screenW>screenH
}

/**
 * 转**哪一边**：+90（顺时针）还是 −90（逆时针）。
 *
 * ## 为什么需要分方向
 *
 * 竖屏短剧转 90° 铺满横屏之后，「片子的上边」只剩两个可能：屏幕左边、屏幕右边。
 * 哪种才是「正的」，取决于用户此刻是**怎样把手机横过来**的——也就是系统报的
 * `Display.rotation`：
 *
 * | `Display.rotation` | 机身 | 机身的「上边」现在在哪 | 片子该往哪转 |
 * |---|---|---|---|
 * | `ROTATION_90`  | 逆时针转了 90°（最常见：手机横过来、上边朝左） | 屏幕左侧 | **−90°**（逆时针，片子的上边跟着朝左） |
 * | `ROTATION_270` | 顺时针转了 90°（上边朝右）               | 屏幕右侧 | **+90°**（顺时针） |
 * | `ROTATION_0` / `180` | 电视、原生横屏的平板、显示器 | 就是屏幕上边 | +90°（一直以来的行为，不动） |
 *
 * 施工时间顺序上踩过的坑：原来无条件 `+90f`，在手机上（`ROTATION_90`）得到的是
 * 片子的上边朝**右**——与用户手上那台手机的方向正好相反，读起来就是「上下颠倒」。
 * 电视是 `ROTATION_0`，`+90f` 一直是对的，因此这里只对 `ROTATION_90` 取负，
 * 电视与横屏平板的行为一个像素都不变。
 *
 * API 30 起 `Activity.display` 取代了 `windowManager.defaultDisplay`（后者已废弃）；
 * 两条路都包在 `runCatching` 里：拿不到就按 `ROTATION_0`（电视的默认行为）处理。
 */
fun MainActivity.fullscreenSpinDirection(): Float {
    // 「使用设备」钉在电视上（或自动判定就是电视）：电视恒为横屏 `ROTATION_0`，直接用 `+90`。
    // 电视上这一项现在不可用（见 [autoFullscreenAvailable]），走不到这里；
    // 留着这一档是为了手动选「电视」之后再切回手机 / 平板时方向仍然有确定值。
    if(deviceMode()==com.hgtv.star.shared.compat.DeviceForm.TV) return 90f
    val rotation=runCatching {
        if(android.os.Build.VERSION.SDK_INT>=30) display?.rotation
        else @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation
    }.getOrNull() ?: Surface.ROTATION_0
    return if(rotation==Surface.ROTATION_90) -90f else 90f
}
/**
 * 画面几何：**缩放比例 + 是否旋转**，一次算完一起落到 [playerView] 上。
 *
 * 两者必须一起算：旋转后画面的「宽」其实是屏幕的高，缩放百分比要作用在旋转之后的边上。
 *
 * - 不转：`宽 = 屏宽 × 比例`、`高 = 屏高 × 比例`，居中；
 * - 转 90°：`宽 = 屏高 × 比例`、`高 = 屏宽 × 比例`，同样居中。旋转是绕自身中心的，
 *   因此这个「横过来」的框转动 90° 之后，正好落在屏幕的 `屏宽 × 屏高` 上；
 * - 100% 时不做任何乘法，直接铺满（与改动前完全一致）。
 *
 * 转的方向交给 [fullscreenSpinDirection]（手机 `−90`、电视 / 横屏平板 `+90`）：
 * 框的尺寸对两个方向是对称的，因此换方向不需要动这里的任何算式。
 */
fun MainActivity.applyVideoGeometry(target: PlayerView?=playerView) {
    val view=target ?: return
    val params=view.layoutParams as? FrameLayout.LayoutParams ?: return
    val rootW=root.width; val rootH=root.height
    if(rootW<=0 || rootH<=0) return
    val percent=library.videoScale.coerceIn(1f,100f)
    val rotated=autoFullscreenRotated()
    // 旋转后要按「屏幕的高」当宽、「屏幕的宽」当高来排版。
    val baseW=if(rotated) rootH else rootW
    val baseH=if(rotated) rootW else rootH
    val width=if(percent>=100f) baseW else (baseW*percent/100f).toInt().coerceAtLeast(1)
    val height=if(percent>=100f) baseH else (baseH*percent/100f).toInt().coerceAtLeast(1)
    val rotation=if(rotated) fullscreenSpinDirection() else 0f
    // 界面那一层跟着画面一起转（见 [applyOverlayGeometry]）：两者必须同一次落下去，
    // 否则会出现「画面转了、控制条还横着」的那一两帧。
    //
    // **必须放在下面那条提前返回之前**：画面这一层没变、界面那一层可能变了
    //（例如刚从别的页面回来、`overlayLayer` 刚建好还没摆过），
    // 提前返回会把那一层漏掉，表现就是「转了画面、界面没转」。
    applyOverlayGeometry()
    if(params.width==width && params.height==height &&
        params.gravity==Gravity.CENTER && view.rotation==rotation
    ) return
    params.width=width; params.height=height; params.gravity=Gravity.CENTER
    view.rotation=rotation
    view.layoutParams=params
}
/**
 * 画面之外那一层（弹幕 / 手势层 / 顶部信息条 / 底部控制条）的几何。
 *
 * 与 [applyVideoGeometry] 同一套算式，**只有一处不同：不乘画面缩放的比例**。
 *
 * 缩放的语义是「只缩画面，弹幕照旧铺满整屏」（见 [applyVideoScale] 的说明）：
 * 把控制条也按 50% 摆到中间，用户就点不到、也看不清了。因此这里恒按 100% 排版，
 * 只在「竖屏短剧全屏」要求旋转时，才和画面取同一个角度、同一组对调过的宽高。
 *
 * 转动方向必须与画面**完全一致**（都走 [fullscreenSpinDirection]）：
 * 两个方向反了的话，界面会对着画面的反面，读起来是倒的。
 */
fun MainActivity.applyOverlayGeometry() {
    val view=overlayLayer ?: return
    val params=view.layoutParams as? FrameLayout.LayoutParams ?: return
    val rootW=root.width; val rootH=root.height
    if(rootW<=0 || rootH<=0) return
    val rotated=autoFullscreenRotated()
    val width=if(rotated) rootH else rootW
    val height=if(rotated) rootW else rootH
    val rotation=if(rotated) fullscreenSpinDirection() else 0f
    if(params.width==width && params.height==height &&
        params.gravity==Gravity.CENTER && view.rotation==rotation
    ) return
    params.width=width; params.height=height; params.gravity=Gravity.CENTER
    view.rotation=rotation
    view.layoutParams=params
}
/**
 * 画面模式落到播放器上。
 *
 * **竖屏短剧全屏只靠旋转实现，不改画面模式**：旋转之后那个框已经是 16:9，
 * 「完整画面」就能铺满，用户自己选的档位（完整 / 等比铺满 / 拉伸）语义保持原样。
 */
fun MainActivity.applyResizeMode(target: PlayerView?=playerView) {
    target?.resizeMode=frameResizeMode()
    // 旋转 / 缩放与画面模式是同一次「这一集怎么摆」的决策：片源尺寸一变，
    // 旋转与否可能跟着变（竖屏剧 ⟷ 横屏剧），这里一并落下去。
    applyVideoGeometry(target)
}
/**
 * 画面缩放 / 竖屏短剧全屏：两者的落点都是 [playerView] 这一层的几何，
 * 因此统一交给 [applyVideoGeometry]（旋转与缩放一起算，缺一个都会排错边）。
 *
 * 弹幕层（[danmakuView]）**不受影响**：它仍然占满整屏、按整屏坐标算轨道，
 * 所以把画面缩到 50%（或转 90°）时，弹幕照旧铺满屏幕横着滚。
 */
fun MainActivity.applyVideoScale() = applyVideoGeometry()
/**
 * 此刻屏幕上有没有一块**唤出来的浮层**（控制条 / 播放设置 / 选集 / 各种选择器）。
 *
 * 「唤出」的口径统一在这里：[hud] 露着（电视上按确定 / 菜单，触屏上轻点画面都走这条）、
 * 播放设置面板开着、选集面板开着、或者某个选择器弹窗开着。四项信息的临时全显
 * （见 [syncPlaybackInfo]）与「推荐页顶部这条要不要跟着控制条走」都以它为准，
 * 免得两处各判一半、出现「菜单开着但标题没出来」。
 *
 * `hud` 是 lateinit：扩展函数里读不到 `isInitialized`（那是背板字段），
 * 冷启动还没建播放层时读它会抛，因此用一次受捕的读取兜住。
 */
fun MainActivity.playbackOverlayShown(): Boolean {
    if(playbackMenu!=null || episodePanel!=null || speedDialog!=null || finishedPanel!=null) return true
    return runCatching { hud.visibility==View.VISIBLE }.getOrDefault(false)
}

/**
 * 播放画面顶部那一条信息（时间 / 剧名 / 集数 / 网速）的显隐与读数刷新。
 *
 * 两条规则叠在一起：
 *
 * 1. **平时**按设置页「播放显示」那四枚开关走（默认四项全关，画面上什么都不挂）；
 * 2. **唤出任何一块浮层时四项全显**——轻点画面出现控制条、按菜单开播放设置、开选集、
 *    开各种选择器，这一刻用户正在「读这一集是什么、播到哪儿」，四项就是这块上下文，
 *    因此**不受那四枚开关限制**；浮层收起后再回到开关的值。
 *
 * 推荐页：顶部这条跟着控制条一起显隐（轻点唤出、收起时一起收）。那一页的本体是整屏连播，
 * 常驻一条标题会让「一滑一部」的观感变成「在一个播放器里」；电视上的推荐页没有控制条，
 * 剧名在底部浮层里，因此整条不显示。
 */
fun MainActivity.syncPlaybackInfo() {
    val bar=playbackInfo
    if(bar!=null) {
        // 浮层露着 = 四项全显（见函数注释第 2 条）。
        val allOn=playbackOverlayShown()
        val shown=screen=="player" || (screen=="recommend" && touchArmed && allOn)
        playbackTitle.visibility=if(allOn || library.playerTitle) View.VISIBLE else View.GONE
        // 推荐页只显示剧名 / 时间 / 网速，不显示「第 x / y 集」。
        playbackEpisode?.visibility=if(screen!="recommend" && (allOn || library.playerEpisode)) {
            View.VISIBLE
        } else {
            View.GONE
        }
        playerClock?.visibility=if(allOn || library.playerClock) View.VISIBLE else View.GONE
        playbackSpeedText?.visibility=if(allOn || library.playerSpeed) View.VISIBLE else View.GONE
        // **这一行只在真的有一项要显示时才出现。**
        //
        // 它的背景是「50% 黑 → 透明」的竖向渐变（见 [buildPlaybackLayer]），那是给白字垫底用的。
        // 四项默认全关，早先这里只判 `screen=="player"`：播放页上这一行于是**常驻但空着**，
        // 底色却照画——画面顶部 96dp 平白多出一条越来越淡的暗带。
        // 用户反馈的「全屏顶部有一些黑雾，需要仔细对比才能发现」就是它：
        // 渐变很缓，暗场里完全看不出，亮场里才显形，所以只能靠对比发现。
        val anyItem=playbackTitle.visibility==View.VISIBLE ||
            playbackEpisode?.visibility==View.VISIBLE ||
            playerClock?.visibility==View.VISIBLE ||
            playbackSpeedText?.visibility==View.VISIBLE
        bar.visibility=if(shown && anyItem) View.VISIBLE else View.GONE
        // 调试面板是**悬浮**在最上层的一块，不参与任何布局：
        // 这里不按它让位、不改内边距，否则顶部这条会被压窄（用户要求「不要挤压任何元素」）。
        // 面板盖住哪一格就盖住哪一格，关掉即恢复。
    }
    updatePlaybackInfoReadings()
}
/** 只换字、不动布局：时间每分钟一格，网速每一秒一格（见每秒一次的 tick）。 */
fun MainActivity.updatePlaybackInfoReadings() {
    playerClock?.takeIf { it.visibility==View.VISIBLE }?.let { clock ->
        val calendar=java.util.Calendar.getInstance()
        // 时钟文案只有一份（`:core` 的 `PlaybackInfoLine`）：与手机端同一段格式。
        val now=com.hgtv.star.core.PlaybackInfoLine.clock(
            calendar.get(java.util.Calendar.HOUR_OF_DAY),calendar.get(java.util.Calendar.MINUTE)
        )
        if(clock.text.toString()!=now) clock.text=now
    }
    playbackSpeedText?.takeIf { it.visibility==View.VISIBLE }?.let { speed ->
        val text=NetSpeed.line()
        if(speed.text.toString()!=text) speed.text=text
    }
}
fun MainActivity.showFramePicker(anchor: TextView,inPlayer: Boolean) {
    tvTools.choose("全局画面模式",listOf("完整画面 · 保留比例与全部内容","等比铺满 · 会裁掉部分画面与字幕","拉伸铺满 · 画面比例会改变"),anchor,{ index ->
        library.frameMode=VideoFrameMode.entries[index]; applyResizeMode()
        if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "画面 " else "画面模式  ·  ")+library.frameMode.label
    })
}
/**
 * 视频解码方式：硬件解码 / 软件解码。
 *
 * 说明文字写清「什么时候该换」——这两档在正常情况下看不出区别，
 * 只有遇到花屏、绿屏、音画不同步、黑屏有声时才有意义。
 * [inPlayer] 为真时改完立刻重建播放器（[applyDecoderMode]），否则只落盘、下次起播生效。
 */
fun MainActivity.showDecoderPicker(anchor: TextView,inPlayer: Boolean) {
    val labels=listOf("硬件解码 · 省电，4K 更稳（默认）","软件解码 · 兼容性好，花屏 / 绿屏 / 音画不同步时用")
    tvTools.choose("视频解码",labels,anchor,{ index ->
        val selected=VideoDecoderMode.entries[index]
        val changed=playbackDecoderMode!=selected
        library.decoderMode=selected
        // 用户手动改解码方式：撤销自动切换、归还「切软解」的预算（不动清晰度上限）。
        playbackCoordinator.onManualDecoderChange()
        if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "解码 " else "视频解码  ·  ")+selected.label
        if(inPlayer && changed) applyDecoderMode() else if(inPlayer) updatePlaybackText()
    })
}
/**
 * 每行卡片数：4 / 5 / 6 / 7 / 8（全局）。
 *
 * 列数变少时卡片变宽，封面与卡片文字一起按比例放大（见 `HomeScreen.textScale` /
 * `SeriesGrid.gridTextScale`）。首页剧架与所有卡片栅格共用这一份设置。
 * 改完只落盘，回到列表时按新列数重建；正在看的页面不受影响。
 */
fun MainActivity.showHomeColumnsPicker(anchor: TextView) {
    val options=listOf(4,5,6,7,8)
    val labels=listOf("4 张 · 大卡片","5 张 · 中号卡片","6 张 · 紧凑（默认）","7 张 · 更紧凑","8 张 · 最紧凑")
    val selected=options.indexOf(library.homeColumns).coerceAtLeast(0)
    if(homeColumnsDialog!=null) return
    val picker=TvDialog(this,"每行卡片").choices(labels,selected) { index ->
        library.homeColumns=options[index]
        anchor.text="每行卡片  ·  ${library.homeColumns} 张"
    }
    picker.button("取消", icon=R.drawable.ic_menu_close)
    homeColumnsDialog=picker
    picker.show(anchor) {
        homeColumnsDialog=null
        if(anchor.attachedToWindow) anchor.requestFocus()
    }
}
/**
 * 「返回顶部」浮层的显隐档位文案（设置页那一格的读数与选择弹窗共用）。
 *
 * 未知值按「始终显示」读：磁盘 / 备份里的坏值在解码时就已归一，这里只是兜底，
 * 保证设置页永远显示一个真能选中的档位。
 */
fun backToTopLabel(mode: String): String=com.hgtv.star.core.BackToTopPolicy.label(mode)
/**
 * 设置格上显示的那一段读数（**短**）：一格只有半屏宽，值最多占一半，
 * 完整文案会被省略号吃掉尾巴，读出来就成了「下拉 8 行列表…」。
 * 弹窗里的选项仍用 [backToTopLabel] 的完整说法。
 */
fun backToTopShort(mode: String): String=com.hgtv.star.core.BackToTopPolicy.shortLabel(mode)
/**
 * 列表页「回到顶部」浮层的显隐方式：始终显示 / 下拉 5 行后显示 / 下拉 8 行后显示 / 始终不显示。
 *
 * 只影响**列表页**那枚浮层（榜单 / 分类 / 集合 / 搜索 / 我的）；首页剧架、详情页、
 * 播放页本来就不挂它（见 `updateBackToTop`）。
 *
 * 「下拉 N 行」按**列表滚过的行数**算：一行的高度取 `rowStride()`（含行间距），
 * 因此与设备分辨率、每行几张卡无关，用户读到的是「往下翻了几行」。
 * 改完只落盘，回到列表按新档位重算显隐，不需要退出应用。
 */
fun MainActivity.showBackToTopPicker(anchor: TextView) {
    val options=listOf(BACK_TO_TOP_ALWAYS,BACK_TO_TOP_ROWS5,BACK_TO_TOP_ROWS8,BACK_TO_TOP_NEVER)
    val labels=options.map(::backToTopLabel)
    val selected=options.indexOf(library.backToTop).coerceAtLeast(0)
    if(backToTopDialog!=null) return
    val picker=TvDialog(this,"返回顶部按钮").choices(labels,selected) { index ->
        library.backToTop=options[index]
        anchor.text="返回顶部  ·  "+backToTopShort(options[index])
    }
    picker.button("取消", icon=R.drawable.ic_menu_close)
    backToTopDialog=picker
    picker.show(anchor) {
        backToTopDialog=null
        if(anchor.attachedToWindow) anchor.requestFocus()
    }
}
/**
 * 选集分页数量：每页 10 / 20 / 30 / 40 / 50 集（默认 20）。
 *
 * 列数固定 10，因此一页就是 1..5 行；简介页与播放界面的选集面板共用同一份设置
 *（见 `EpisodeGrid` 的 `pageSize`），两处分段永远一致。
 * 改完只落盘：下一次进简介页、或下一次打开选集面板时按新页长摆格子。
 */
fun MainActivity.showEpisodePageSizePicker(anchor: TextView) {
    val options=listOf(10,20,30,40,50)
    val labels=listOf("每页 10 集 · 一屏一行","每页 20 集 · 一屏两行（默认）",
        "每页 30 集 · 一屏三行","每页 40 集 · 一屏四行","每页 50 集 · 一屏五行")
    tvTools.choose("选集分页数量",labels,anchor,{ index ->
        val size=options[index]
        library.episodePageSize=size
        anchor.text="选集分页  ·  $size 集"
    })
}
/**
 * 弹幕显示方式：只显一行 / 上方显示 / 全屏显示 / 隐藏弹幕。
 *
 * 排列顺序与每一档的说明都取自 [DanmakuMode.entries]（`hint`），设置页与播放设置面板不再各写一份文案——
 * 档位顺序是需求里定死的一条验收项，抄成三份迟早会走样。
 *
 * 弹幕数据本身就是原 App 播放时用的那一份（见 [ContentRepository.danmaku]）。
 *
 * [onChanged] 在档位真的变了之后回调一次——设置页用它就地收起 / 放出「弹幕速度」「弹幕大小」
 * 「弹幕透明度」三行（[DanmakuMode.showsStyle]）；播放设置面板走的是整块重建，不需要这个回调。
 */
fun MainActivity.showDanmakuPicker(anchor: TextView,inPlayer: Boolean,onChanged: ()->Unit={}) {
    tvTools.choose("弹幕显示方式",DanmakuMode.entries.map { it.hint },anchor,{ index ->
        val selected=DanmakuMode.entries[index]
        val changed=library.danmakuMode!=selected
        library.danmakuMode=selected; danmakuView?.mode=selected
        if(anchor.tag!="playback-settings") anchor.text=danmakuLabel(if(inPlayer) "弹幕 " else "弹幕显示  ·  ")
        if(changed) onChanged()
    })
}
/**
 * 弹幕移动速度：慢速 / 标准 / 快速。
 *
 * 三档只改「整体多快」：弹幕层里所有弹幕同速（见 [com.hgtv.star.shared.media.DanmakuView]），
 * 换档不会让某一条追上前面那一条——快的档位只是让同一屏通过得更多、同一条轨道排得更密。
 * [onChanged] 在档位真的变了之后回调一次（设置页用它刷新那一行文案）。
 */
fun MainActivity.showDanmakuSpeedPicker(anchor: TextView,inPlayer: Boolean,onChanged: ()->Unit={}) {
    tvTools.choose("弹幕移动速度",DanmakuSpeed.entries.map { it.hint },anchor,{ index ->
        val selected=DanmakuSpeed.entries[index]
        val changed=library.danmakuSpeed!=selected
        library.danmakuSpeed=selected; danmakuView?.speed=selected
        if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "弹幕速度 " else "弹幕速度  ·  ")+selected.label
        if(changed) onChanged()
    })
}
/**
 * 「弹幕显示」那一行的文案：显示方式 + 最近一集实际取到多少条。
 *
 * 平台只对部分剧集放开匿名弹幕，屏幕上「一条都没有」和「功能没生效」从画面上分不出来，
 * 这一行就是给这件事一个明确的读数（播放界面那排状态项太窄，放不下数字）。
 */
fun MainActivity.danmakuLabel(prefix: String)=prefix+library.danmakuMode.label+
    if(danmakuCount<0) "" else "（上一集 ${if(danmakuCount>0) "$danmakuCount 条" else "无弹幕"}）"
fun MainActivity.seek(direction: Int,repeat: Int) {
    val p=player ?: return; if(!playbackReady) return
    val duration=p.duration; if(duration<=0) return
    // 单次步长、连按加速与片尾保护都在 `:core`（[SeekStep]）：手机端读的是同一份，
    // 两端「连按几下、每次多少秒、停在哪」不会再各调各的。
    // 这里把按键的 repeat 计数（首次为 0）换算成「第几次操作」（首次为 1）。
    val step=SeekStep.stepMs(library.seekStep,repeat+1)
    pendingSeek=SeekStep.target(pendingSeek ?: p.currentPosition,direction,step,duration)
    main.removeCallbacks(seekRunnable); main.postDelayed(seekRunnable,250); updatePlaybackText(); showHud()
}
fun MainActivity.dismissPlaybackMenu() {
    playbackMenu?.let { menu -> if(menu.hasFocus()) menu.clearFocus() }
    // 面板现在是「外壳 → 滚动容器 → 面板」三层，直接 removeView(menu) 摘不掉任何东西
    // （menu 的父级是滚动容器），浮层会留在画面上。这里按最外层摘。
    playbackMenuShell?.let { shell -> (shell.parent as? ViewGroup)?.removeView(shell) }
    playbackMenuShell=null
    playbackMenu=null
    // **焦点交还播放界面**：`panel` 是「方向键交给浮层焦点链」的开关（见 `dispatchKeyEvent`），
    // 打开菜单时置真，收起时必须一起放开——否则方向键 / 确定键都被 `if(!panel)` 挡掉，
    // 表现就是「浮层关了、控制条还亮着、左右键全都不好使，按返回才恢复」。
    //
    // 出错页是例外：那一版的按钮本身要能按，焦点得留在它上面，因此按 [playError] 判定。
    panel=playError
    // 菜单已经不在屏幕上：那一位「是被哪一下菜单键按开的」也随之作废，
    // 否则下一次菜单键的抬起会被这条旧标记吞掉（用户会觉得「按了菜单没反应」）。
    playbackMenuOpenPress=false
    // 菜单收起：推荐页那块信息浮层回到画面上（正式播放里它是 null，空操作；
    // 触屏的推荐页不建这块浮层，同样是空操作——它上面盖着的是同一条控制条）。
    recommendOverlay?.visibility=View.VISIBLE
    // 菜单收起 = 浮层不再「唤出」：顶部四项回到「播放显示」里用户选的值
    // （菜单里的空间重新还给画面，见 [syncPlaybackInfo]）。
    syncPlaybackInfo()
    // 重排计时器必须放在**最后**：判据要按「菜单已经摘掉之后还剩哪些浮层」算。
    // 放在前面的话 `playbackMenu` 还没清空，等于给一块已经不存在的面板数秒；
    // 菜单关掉、控制条还在时该继续给控制条数着，因此这里是「重排」而不是「取消」。
    scheduleOverlayAutoHide()
}
/**
 * 遥控器「上」（或菜单键）打开底部设置面板：
 * 清晰度 / 倍速 / 画面模式 / 弹幕 / 弹幕大小 / 弹幕透明度 / 定时。
 *
 * 「弹幕大小 / 弹幕透明度」只在弹幕可见时存在：隐藏档下整层不画、也不发请求，
 * 这两项改完看不到任何变化，因此那两行**直接不添加**（行号见 [sleepRow]）。
 *
 * 功能竖排、选项横排，上下换功能、左右换选项，确认立即生效，返回收起整个浮层。
 * 面板高度固定为 [com.hgtv.star.tv.TvStyle.PLAYER_PANEL_HEIGHT]（与选集面板同高），
 * 装不下的行留在滚动容器里，遥控器焦点走到哪一行
 * 就自动把哪一行带进可视区（ScrollView 自己的 `requestChildFocus` 行为），因此所有行都能按到。
 *
 * [focusRow] 与 [focusChoice] 用来在改清晰度重建菜单、或点 HUD 状态行时定位焦点；
 * [focusChoice] 为负数时落在该项目当前选中的选项上。
 */
