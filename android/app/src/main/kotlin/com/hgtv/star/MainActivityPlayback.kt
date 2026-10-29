// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import com.hgtv.star.compat.hasInternet
import com.hgtv.star.compat.isMeteredConnection

import android.app.Activity
import android.view.*
import android.widget.*
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.ui.PlayerView
import com.hgtv.star.core.*
import com.hgtv.star.data.WatchProgress
import com.hgtv.star.data.PlaybackCapability
import com.hgtv.star.player.VideoDataSource
import com.hgtv.star.compat.setVectorIcon
import com.hgtv.star.player.PlaybackMenu
import com.hgtv.star.player.TvMediaSession
import com.hgtv.star.tv.TvDialog
import com.hgtv.star.compat.attachedToWindow

// 本文件是从 MainActivity.kt 拆出来的「播放中控：缓冲抑制、下一集预取、重试恢复、HUD 与传输层回收」这一块。
// 这些成员仍然属于 MainActivity，只是以扩展成员（MainActivity.xxx）的形式放在这里，
// 逻辑与拆之前逐行一致；改这里等于改 Activity 本体。

/**
 * 收掉一集的传输层。
 *
 * 2026-10-19 起播放链路共用 [com.hgtv.star.data.PlaybackTransport.client]：
 * **不关连接池、不 shutdown dispatcher**，只由 `RemoteVideo.close()` 取消这一集自己的 call。
 *
 * 为什么改：线上崩溃是 h2 读线程在平台 TLS 里越界——
 * 每集新建 client、切集时 `cancelAll + evictAll + shutdown`，在途的 TLS 连接被关，
 * 而 h2 的读线程正阻塞在 `read()` 里，异常无人接住直接打死进程（见 PlaybackTransport 的类注释）。
 * 连接池留着复用之后，「在途连接被关闭」这件事本身就不再发生。
 */
internal fun MainActivity.closeTransport(http: okhttp3.OkHttpClient?) {
    if (http == null) return
    if (http === com.hgtv.star.data.PlaybackTransport.client) return
    // 兜底：万一以后有别的 client 走到这里（当前没有），仍按老办法收掉。
    networkCleanup.execute { http.dispatcher().cancelAll(); http.connectionPool().evictAll(); http.dispatcher().executorService().shutdown() }
}
/**
 * 无感切集时的缓冲指示：过渡那一两百毫秒不转圈，画面停在上一集最后一帧就够了。
 *
 * 只关到新源 READY 为止，而且**最多 1.2 秒**——万一预热的数据被换出、新源还得走网络，
 * 转圈该回来就得回来，不能让用户对着一帧静止画面干等。
 */
internal fun MainActivity.suppressBufferingUi() {
    if(bufferingSuppressed) return
    bufferingSuppressed=true
    playerView?.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
    main.removeCallbacks(restoreBuffering); main.postDelayed(restoreBuffering,1200)
}
internal fun MainActivity.restoreBufferingUi() {
    if(!bufferingSuppressed) return
    bufferingSuppressed=false
    main.removeCallbacks(restoreBuffering)
    playerView?.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
}

/**
 * 上一集的视频与传输：等新源出画面（READY）、这一集出错、或退出播放时再关。
 *
 * [scheduleRetire] 是兜底：万一新源一直没到 READY（切走、出错这些极端路径），
 * 3 秒后也把它收掉——那时候旧源早就被换下、不可能还在读，收掉不会打断任何东西。
 */
internal fun MainActivity.scheduleRetire() {
    main.removeCallbacks(retireForced); main.postDelayed(retireForced,3000)
}
/**
 * 强制收掉旧资源：调用方**刚刚整条替换 / 清空**了播放列表，那一项已经不可能再被读。
 *
 * 与 [retirePreviousEpisode] 的区别只有一条：不等 800ms 的摘除任务。
 */
internal fun MainActivity.forceRetirePrevious() {
    playedItemPending=false; playedItemId=""
    retirePreviousEpisode()
}
internal fun MainActivity.retirePreviousEpisode() {
    main.removeCallbacks(retireForced)
    // 播完的那一项可能还挂在播放列表里（要等 800ms 才摘）。这时关掉它持有的
    // `RemoteVideo`，一旦 ExoPlayer 因为列表变化重新 prepare 旧 period，就会读到
    // 一个已关闭的对象而抛 IOException。等摘掉之后再关，顺序才对。
    if(playedItemPending) return
    retiringVideo?.let { runCatching { it.close() } }
    retiringVideo=null
    closeTransport(retiringHttp); retiringHttp=null
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
internal fun MainActivity.enqueuePreparedNext() {
    val p=player ?: return
    val data=detail ?: return
    // 用户按过暂停 / 息屏 / 切后台：不要挂下一集，否则回来一按播放就自己跳到下一集了。
    if(!foreground || screen!="player" || playError || sleepStopped || !requestedAutoplay || pausedForLifecycle) return
    if(!library.autoNext || episodeIndex>=data.episodes.lastIndex) return
    // 按集数计的定时必须在片尾那一下拦住，因此这种定时下不挂队列（走 STATE_ENDED 老路）。
    // 按分钟计的定时由 tick 自己停，和片尾无关，仍然可以无感连播。
    if(sleepTimer.episodesLeft>0) return
    if(queuedEpisodeId!=null) return
    // 不在片尾窗口里也没关系：`MediaPeriodQueue.shouldLoadNextMediaPeriod()` 要求
    // 「当前 period 已完全缓冲」才会真的去 prepare 下一集，因此早挂不会抢当前这一集的带宽，
    // 只是把「预热结果 → 播放器知道有下一集」这一步提前做完，片尾推进时少一次等待。
    val id=data.episodes[episodeIndex+1]; val quality=playbackMaxQuality
    val remote=preparedNext.take(preparedKeyFor(id)) ?: return
    val http=prefetchHttp.also { prefetchHttp=null }
    if(http==null) { remote.close(); return }
    queuedEpisodeId=id; queuedVideo=remote; queuedHttp=http; queuedQuality="${quality}P"
    queuedAt=android.os.SystemClock.elapsedRealtime()
    val source=ProgressiveMediaSource.Factory { VideoDataSource(remote) }
        .createMediaSource(MediaItem.Builder().setMediaId(id).setUri("hgtv://episode/$id")
            .setMimeType(com.google.android.exoplayer2.util.MimeTypes.VIDEO_MP4).build())
    // 挂失败（列表被别处清空、播放器正好在重建）就当没预热过：把资源收回来，
    // 片尾仍然走 `STATE_ENDED → playEpisode` 那条兜底，不会留下半截状态。
    if(runCatching { p.addMediaSource(source) }.isFailure) {
        queuedEpisodeId=null; queuedVideo=null; queuedHttp=null; queuedQuality=""; queuedAt=0
        runCatching { remote.close() }; closeTransport(http)
        return
    }
    android.util.Log.i("HGTV","Next episode queued id=$id quality=$quality")
}
/** tick 里的兜底：预热结果落地时那一次 post 可能被别的重建吞掉，这里再补一次。 */
internal fun MainActivity.maybeQueueNext() {
    if(queuedEpisodeId!=null) return
    val nextId=detail?.episodes?.getOrNull(episodeIndex+1) ?: return
    if(!preparedNext.isFresh(preparedKeyFor(nextId))) return
    enqueuePreparedNext()
}
/**
 * 摘掉**刚播完的那一项**。
 *
 * 为什么必须摘：每一项 `MediaSource` 都捕获着自己那一集的 [RemoteVideo]（预热缓存、
 * 样本索引），列表不清就会随集数一直涨。摘掉之后旧的 `MediaSource` 失去引用，
 * 下一轮 GC 就把那一集的内存收回去。
 *
 * 为什么按 mediaId 而不是按下标：这个调用是延迟 800ms 执行的，这期间用户可能已经
 * 手动选集、换清晰度或退出播放器，下标早就不是当初那一个了；按下标删会误删当前项。
 * [session] 是排这个任务时的播放会话号，会话变了说明期间发生过切集，直接放弃。
 *
 * 为什么安全：删的是当前项**之前**的窗口，当前 period 的 uid 不变，
 * `resolvePositionForPlaylistChange` 判定「位置没变」，不会 seek、不会重建渲染器。
 */
internal fun MainActivity.dropPlayedItems(playedId: String,session: Int) {
    val p=player ?: return
    if(playedId.isEmpty() || session!=playbackSession) return
    val index=p.currentMediaItemIndex
    if(index<=0) return
    val target=(0 until index).firstOrNull { p.getMediaItemAt(it).mediaId==playedId } ?: return
    if(runCatching { p.removeMediaItem(target) }.isFailure) return
    // 那一项真的摘掉了：现在才可以关它持有的视频与传输。
    playedItemPending=false; playedItemId=""
    retirePreviousEpisode()
}
/**
 * 摘掉队列里的下一集。
 *
 * 只摘**当前项之后**的那一段：当前 period 被保留，ExoPlayer 不会为此 seek 或重建渲染器。
 * 用户手动切集 / 换清晰度 / 退出播放器时调用——那时候新源要顶上来，队列里的旧目标必须让位。
 */
internal fun MainActivity.dropQueuedEpisode() {
    val p=player
    var removed=true
    if(p!=null && p.mediaItemCount>1) {
        val index=p.currentMediaItemIndex
        removed=runCatching { p.removeMediaItems(minOf(index+1,p.mediaItemCount),p.mediaItemCount) }.isSuccess
    }
    queuedEpisodeId=null; queuedQuality=""; queuedAt=0
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
internal fun MainActivity.onEpisodeAdvanced(next: Int) {
    val data=detail ?: return
    val p=player ?: return
    if(next !in data.episodes.indices) return
    val previous=episodeIndex
    val queuedFor=queuedAt
    advancedAt=android.os.SystemClock.elapsedRealtime()
    // 播完那一集：用切走之前记下的时长写一条 completed 记录。
    // 这里**不能**读 `p.duration`：回调到达时播放器已经在报下一集的时长了。
    // 时长未知（这一集没走到 READY）就不写：写一条 0/0 的「已看完」比不写更糟，
    // 它会把这个剧的续播位置直接推到下一集开头。
    if(previous in data.episodes.indices && lastEpisodeDuration>0) {
        val duration=lastEpisodeDuration
        val progress=WatchProgress(data.series,data.episodes[previous],previous,duration,duration,true,System.currentTimeMillis())
        library.save(progress); syncProgress(progress,data.episodes.size)
    }
    // 新一集的时长还没读到：先清零，等 READY / tick 按新 mediaId 重新采样。
    // 不清的话，新一集若在 READY 之前就出错，写出去的是上一集的长度。
    lastEpisodeDuration=0
    // 旧一集的视频与传输交给兜底回收（旧 period 刚 release，晚一点关更稳）。
    retirePreviousEpisode()
    retiringVideo=video; retiringHttp=playbackHttp
    video=queuedVideo; playbackHttp=queuedHttp
    queuedVideo=null; queuedHttp=null; queuedEpisodeId=null
    quality=video?.info?.quality ?: queuedQuality
    queuedQuality=""; queuedAt=0
    playedItemPending=true
    playedItemId=data.episodes[previous]
    scheduleRetire()
    episodeIndex=next
    // 无感切集换的是 period，不走 [playEpisode]，诊断现场得在这里跟着换：
    // 换集之后报出来的必须是**这一集**的 id / 集号 / 清晰度，否则上报里张冠李戴。
    // 解码器与渲染器是复用的，`decoderName` 因此保留——那正是这一条最有用的信息。
    PlaybackDiag.nextEpisode(data.episodes[next],next,quality)
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
    ledger.onEpisodeStart()
    // **不**在这里设故障互斥窗——那会把新一集紧接着发生的真实错误也一起吞掉。
    // 故障互斥窗是「**这一次故障**」的，不是「这一段播放」的：换了集必须作废。
    //
    // 无感切集**不**增加 `playbackSession`（有意如此：监听器的 `active()` 要活过换集），
    // 因此 `playerFailure` 开头那条「同一会话 1.5 秒内只处理第一条」会把**新一集**开播
    // 1.5 秒内的第一个真实错误当成重复回调丢掉：画面全黑、`playError` 仍是 false、
    // 控制条还写着「播放中」，只能等 15 秒起播看门狗来救。上一集在片尾失败、300ms 后
    // 新一集立刻报 4001 或 IO，正是这条最常见的命中方式。
    failureHandledSession=-1; failureHandledAt=0L
    // 新一集的降级预算同样从零开始（档位上限照旧沿用）。这条「无感切集」不走 [playEpisode]，
    // 早先因此**不清**预算与手动建议：上一集留下的「请切换为软件解码」会被下一集的错误页
    // 原样顶出来，而那可能是一次毫不相关的网络失败（见 `SessionPlaybackPolicy.onEpisodeStart`）。
    sessionPlayback.onEpisodeStart(data.episodes[next])
    // 标题只放剧名：集数由顶部那一项单独显示（见 [syncPlaybackInfo]）。
    playbackTitle.text=data.series.title
    // 集号这一格必须在这一帧就换掉：新 period 的第一帧已经上屏，读数还写着上一集，
    // 用户看到的就是「明明播到第 5 集，顶部还写第 4 集」。只改一处文字，不触发整条重排。
    syncPlaybackEpisodeLabel(data)
    // 弹幕与界面状态的刷新放到下一帧之后：这一帧正在把新 period 的第一帧推上屏，
    // 主线程上任何一次重排都会把它顶后一帧，用户看到的就是「换集时闪一下」。
    main.post {
        if(isDestroyed || screen!="player" || episodeIndex!=next) return@post
        loadDanmaku(data.episodes[next],data.series.id)
        updatePlaybackText(); syncMediaSession()
    }
    // 播完的那一项摘掉，列表回到「只有当前一集」；延后一拍，别和这一帧的换源抢。
    // 带上播完那一集的 mediaId 与会话号：这 800ms 里发生的切集 / 选集会让任务自己作废。
    val playedId=data.episodes[previous]
    val session=playbackSession
    main.postDelayed({ if(!isDestroyed) dropPlayedItems(playedId,session) },800)
    val waited=if(queuedFor>0) android.os.SystemClock.elapsedRealtime()-queuedFor else -1
    android.util.Log.i("HGTV","Episode advanced seamlessly to ${next+1} quality=$quality queuedForMs=$waited")
}
internal fun MainActivity.cancelPrefetch() {
    prefetchScope?.cancel(); prefetchScope=null
    preparedNext.clear(); prefetchJob?.cancel(true); prefetchJob=null
    closeTransport(prefetchHttp); prefetchHttp=null; prefetchedFor=""
}
internal fun MainActivity.maybePrefetch() {
    val p=player ?: return; val data=detail ?: return
    // 推荐页不挂下一集：一集播完就进正片（见 STATE_ENDED 分支），由正片接着播，
    // 这里再预取一集只会白占带宽，还会让片尾被 ExoPlayer 自己推进到队列里那一集。
    if(screen=="recommend") return
    // 起播正在取地址：这会儿再发一次就是两个并发，而这条接口对并发极敏感
    //（20 并发 0 命中、单发 50~80% 命中）。让起播先拿到地址，下一帧再预热。
    if(playbackFetching) return
    if(!foreground || !p.isPlaying || !library.autoNext || playError || sleepStopped || episodeIndex>=data.episodes.lastIndex) return
    if(!PrefetchPolicy.nearEnd(p.currentPosition,p.duration,p.playbackParameters.speed)) return
    // 键 = 集 + 上限（见 [preparedKeyFor]）：全站只有 HEVC 一种编码，不需要再区分编码族。
    val id=data.episodes[episodeIndex+1]; val quality=playbackMaxQuality
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
    // 共用进程级传输层（HTTP/1.1）：切集不再关连接池，见 [com.hgtv.star.data.PlaybackTransport]。
    val http=com.hgtv.star.data.PlaybackTransport.client
    val source=ContentRepository(http); prefetchHttp=http
    // 预取走的是另一个仓库实例：登录态与**设备身份 / 注册 Cookie**都要带过去，
    // 少了后者它签名时只剩内置那份档案（`device_id` 被平台拉黑），
    // 取播放地址一律空响应（见 ContentRepository.videoModel），这一集永远预取不到。
    source.credentials=repository.credentials
    source.inheritDevice(repository)
    // 预取也要用同一档 `device_level`：换了档位就等于换了一套清晰度表，
    // 预取到的地址与真正起播时选中的那一档对不上，预热就白做了。
    source.deviceLevel=repository.deviceLevel
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
                val info=source.stream(id,quality)
                RemoteVideo(http,info,info.mediaKey(id)).also { remote=it; it.warm() }
            }
            if(preparedNext.complete(ticket,key,prepared)) {
                android.util.Log.i("HGTV","Next episode prepared near end")
                // 预热一落地就挂进队列，而不是等片尾那一下再挂：挂得越早，
                // ExoPlayer 越有时间在后台把下一集的 period prepare 好（片尾推进时就不用等）。
                main.post { if(!isDestroyed) enqueuePreparedNext() }
            }
        } catch(_: Exception) { remote?.close() }
    }
}
internal fun MainActivity.networkAvailable(): Boolean = connectivity.hasInternet()
internal fun MainActivity.currentPosition(): Long = pendingSeek ?: player?.currentPosition?.takeIf { playbackReady } ?: startPosition
internal fun MainActivity.cancelRecovery() { main.removeCallbacks(retryRunnable); retryPending=false; autoRecovery=false }
internal fun MainActivity.scheduleRecovery() {
    if(!foreground || !playError || !autoRecovery || retryPending) return
    if(!ledger.canAttempt) { autoRecovery=false; playbackText.text="已自动重试 3 次，仍无法播放。可手动重试或切换清晰度。"; return }
    if(!networkAvailable()) { playbackText.text="网络已断开，联网后自动续播 · 第 ${episodeIndex+1} 集 ${formatTime(retryPosition)}"; return }
    val delay=ledger.delayMillis ?: return
    playbackText.text="${if(retryAutoplay) "准备恢复播放" else "准备恢复暂停位置"} · ${delay/1000f} 秒后重试 ${ledger.attempts+1}/3 · ${formatTime(retryPosition)}"
    retryPending=true; main.postDelayed(retryRunnable,delay)
}
/**
 * 这次失败落在**哪条轨**上。
 *
 * `ExoPlaybackException` 自己带三样公开字段（`rendererName` / `rendererIndex` / `rendererFormat`），
 * 比顺 cause 链找 `DecoderInitializationException` 更稳，而且 4003 / 4004 / 4005 也覆盖得到。
 *
 * 这一项是「音频 4001 被当成视频能力不足、进而把清晰度压到 480」那个 bug 的正解：
 * 音频失败与视频能力**无关**，不该动档位、不该清 HEVC 探测结果。
 */
internal fun playbackMediaKind(problem: Throwable?): MediaKind {
    var current=problem; var guard=0
    while(current!=null && guard++<8) {
        if(current is com.google.android.exoplayer2.ExoPlaybackException) {
            val mime=current.rendererFormat?.sampleMimeType.orEmpty()
            if(mime.startsWith("audio/")) return MediaKind.AUDIO
            if(mime.startsWith("video/")) return MediaKind.VIDEO
            val name=current.rendererName.orEmpty()
            if(name.contains("Audio",true)) return MediaKind.AUDIO
            if(name.contains("Video",true)) return MediaKind.VIDEO
        }
        if(current is com.google.android.exoplayer2.mediacodec.MediaCodecRenderer.DecoderInitializationException) {
            val mime=current.mimeType.orEmpty()
            if(mime.startsWith("audio/")) return MediaKind.AUDIO
            if(mime.startsWith("video/")) return MediaKind.VIDEO
        }
        current=current.cause
    }
    return MediaKind.VIDEO
}

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
internal fun MainActivity.playbackAttribution(
    problem: Throwable?,
    code: Int,
    failedQueued: StreamInfo? = null
): FailureAttribution {
    val info=failedQueued ?: video?.info ?: queuedVideo?.info
    val quality=info?.actualQuality?.takeIf { it>0 } ?: playbackMaxQuality
    // 没有结构化档位（例如预热命中的旧对象）时退化成「只有当前这一档」：
    // 策略会因此降无可降，直接落到错误页——比瞎猜一个 480 安全。
    val available=info?.available?.takeIf { it.isNotEmpty() } ?: listOf(quality)
    return FailureAttribution(
        media=playbackMediaKind(problem),
        errorCode=code,
        currentQuality=quality,
        // 用**实际生效**的解码方式（见 [playerDecoderMode]）：起播前不会静默改档了，
        // 但用户可能自己点过「软件解码」，那时降档的判据、错误页文案都与硬解不同。
        currentMode=playerDecoderMode(),
        available=available
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
    if(!sessionPlayback.onManualRetry()) return
    ledger.onManualRetry()
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
    // 按**新一集**重来一遍预算：`onEpisodeStart` 只清这台机器上「这一集」的额度，不动档位上限。
    detail?.episodes?.getOrNull(index)?.let { sessionPlayback.onEpisodeStart(it) }
    // 连播链的失败计数**不**清：这条路本身就是「片尾推进失败，从下一集重来」，
    // 清掉计数就变成了无限自动推进（见 `PlaybackAttemptLedger.onEpisodeSwitch`）。
    ledger.onEpisodeSwitch()
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
internal fun MainActivity.replayWithPlan(plan: PlaybackRetryPlan) {
    // 断点位置直接问播放器：出错时 `playbackReady` 可能已被清掉，[currentPosition] 会退回
    // [startPosition]（本集开头），表现为「降级一次就从头播」。播放器在错误态下
    // `currentPosition` 仍然是有效的（它就是失败那一刻的位置），取它更准。
    val live=runCatching { player?.currentPosition ?: -1L }.getOrDefault(-1L)
    val at=(if(live>0) live else currentPosition()).coerceAtLeast(0)
    val play=requestedAutoplay && !sleepStopped
    // 上限先落进会话层，再记留痕：`noteDegrade` 会打出 `cap=`，那一刻读到的必须已经是**新的**上限。
    plan.maxQuality?.let { sessionPlayback.setMaxQuality(it) }
    // 记下降档起点：下一次同档失败时策略据此判断「降了等于没降」
    //（CDN 只回兼容资源），不再白烧一轮取地址与一次黑屏。
    plan.fromQuality?.let { sessionPlayback.noteQualityDown(it) }
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
    // 代价却是 `forgetStream` 尾部的换号把 `video_model` 缓存一起打掉——
    // 白付一次 500~1100ms 的 POST，而坏的东西明明是本机解码器，与身份无关。
    // 同档重建走窄路（`keepMedia`）：视图与这一集的 `RemoteVideo` 原地留着，
    // 只 `stop()` 放掉解码器。降档必须回到整条重建——上限变了就是另一条地址、
    // 另一份索引，留着的这一份用不上。
    playEpisode(episodeIndex,at,play,recovering=true,keepMedia=plan.maxQuality==null)
}

internal fun MainActivity.playerFailure(problem: Throwable) {
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
    // 落盘地址过期（403 / 404）：**换一条新地址原地重来一次**，不给用户看错误页。
    //
    // 缓存 20 分钟的地址有可能被服务端提前作废（签名时效、CDN 换线），而这是
    // 「回落到盘上以后才可能出现」的新失败点，必须自己救回来：丢掉内存与盘上那条地址，
    // 让 `stream()` 重新取一次，再按当前位置重新起播。每集只救一次，第二次才报错。
    //
    // 这一段排在 [reportPlaybackFailure] **之前**：它是自愈路径，救回来就不该记一条故障
    //（否则诊断面板里会出现「刚报错、其实画面正常」的记录）。
    val expiredEpisode=detail?.episodes?.getOrNull(episodeIndex)
    val expiredQuality=playbackMaxQuality
    // 第二种可自愈的失败：**这一集的文件换过了**（见 [VideoChangedException]）。
    // 落盘的索引与首块是上一版的，`Content-Range` 的总长对不上，`range` 当场作废索引并抛它。
    // 它跟地址过期是同一种处理——丢掉地址与索引重来一次，用户只多等半秒，
    // 而不是看到「本地准备失败」然后被告知「请换一部」。共用同一份「每集只救一次」的额度：
    // 两种失败都是「缓存里的东西过期了」，同一次起播里连救两次没意义。
    val videoChanged=videoChangedOf(problem)
    // 第三种可自愈的失败：**视频 CDN 的域名解析不出来**（见 [networkFailureOf]）。
    // 这条不靠「等 DNS 好起来」——`HgtvDns` 已经兜了一层公共 DNS（见 `PublicDns`），
    // 万一兜不住（域名真的从 CDN 下线了），换一条新地址重取往往就落到另一个 CDN 域名上，
    // 也就是换一次地址就能好。它同样共用「每集只救一次」的额度。
    val hostUnresolved=hasCauseOf<java.net.UnknownHostException>(problem)
    // 第四种：**连接层就没通**（超时 / 被拒 / 路由不可达 / TLS 握手失败）。
    //
    // 这是现场「某一集永远放不了、切上一集下一集都正常」最常见的那一条：坏的东西不在
    // 地址里，而在下面两层缓存里——
    //   * DNS 记住了那个 CDN 域名的一个已经下线的 IP（10 分钟内存 / 30 分钟落盘）；
    //   * 进程级连接池记住了那条半死连接（路由器 NAT 表项被回收，写得出、读不回）。
    // 只丢播放地址是不够的：重取回来的地址多半还是同一个 host，于是又命中同一份坏 IP、
    // 又复用同一条死连接，下一发照样卡满 `connectTimeout`。必须三处一起清（见 [dropDeadTransport]）。
    val transportDead=transportDeadOf(problem)
    // 失败的是**队列里那一集**（片尾自动推进没接上）时，要救的是它，不是当前这一集：
    // 按当前集重放会在片尾立刻再次 ENDED，白走一趟而且照样进不去下一集。
    //
    // 判据不能只看「离片尾多远」：预热在片尾 30 秒就发起，队列项的连接故障完全可能在
    // 片尾窗口之外就报出来。那时当前 period 已经缓冲满（`shouldLoadNextMediaPeriod`
    // 的前提就是「当前 period 完全缓冲」），IO 只可能来自队列项——按当前集处理会
    // 清掉当前集**好好的**地址与索引、把正在播的这一集重放一遍（用户看到的是「正播着
    // 突然黑一下重来」），而真正坏掉的下一集地址还留在缓存里，下一次预热照旧命中。
    //
    // `queuedNearEnd` 只用来决定**从哪儿重来**：还在中段就重开当前集，已经在片尾就
    // 从下一集开头重切一次。
    val queuedDuration=player?.duration?.coerceAtLeast(0) ?: 0
    val queuedNearEnd=queuedEpisodeId!=null && queuedDuration>0 &&
        (player?.currentPosition ?: 0)>=queuedDuration-2_000
    val failingEpisode=queuedEpisodeId ?: expiredEpisode
    if(failingEpisode!=null && (streamExpiredOf(problem) || videoChanged || hostUnresolved || transportDead)) {
        if(!ledger.streamRetried("$failingEpisode:$expiredQuality")) {
            ledger.noteStreamRetry("$failingEpisode:$expiredQuality")
            val why=when {
                hostUnresolved -> "Video host unresolved; refetching url"
                transportDead -> "Transport dead; refetching url, dns & pool"
                videoChanged -> "Video changed; refetching url & index"
                else -> "Stream expired; refetching url"
            }
            android.util.Log.w("HGTV","$why episode=${episodeIndex+1}")
            // 键里带 `device_level`（见 `ContentRepository.forgetStream`），删的必须是**当前那条**
            // 地址，否则真正那条还留在盘上，下次照旧命中。
            repository.forgetStream(failingEpisode,expiredQuality)
            // 索引与首块也是按「这一集 + 这一档」落盘的，文件换过之后它们一起作废。
            //
            // 键必须用**实际**那一份（`StreamInfo.mediaKey`：集 + 实际档位）。
            // 早先这里拼的是 `"$集:$上限"`，而索引键早就改成带实际档位了——
            // 拼错等于什么也没删，下一次起播仍然读那份对不上新文件的旧索引。
            val staleInfo=if(queuedEpisodeId!=null) queuedVideo?.info ?: video?.info else video?.info
            if(staleInfo!=null) PlaybackCache.invalidateIndex(staleInfo.mediaKey(failingEpisode))
            // 地址之外的那两层：视频域名的解析结果、连接池里的空闲连接。
            dropDeadTransport()
            val at=currentPosition().coerceAtLeast(0)
            disarmPlaybackWatchdog(); disarmFrameStallWatchdog()
            cancelRecovery(); cancelPrefetch(); dropQueuedEpisode()
            restoreBufferingUi(); retirePreviousEpisode()
            // 队首那一集失败：从它的开头重来，而不是重放当前这一集（见上面的 `queuedNearEnd`）。
            val retryIndex=if(queuedNearEnd) episodeIndex+1 else episodeIndex
            val retryAt=if(queuedNearEnd) 0L else at
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
    disarmPlaybackWatchdog(); disarmFrameStallWatchdog()
    reportPlaybackFailure(problem,playbackStage(problem))
    // 推荐页出错：不摆播放页那套「重试 / 返回选集」的错误界面（那里没有选集、也没有详情页可回），
    // 直接换下一部。连换几部都放不了才停在一句提示上，由用户按确定重试、按返回回主页面。
    if(screen=="recommend") {
        val failed=detail?.series?.title ?: recommendItems.getOrNull(recommendIndex)?.title
        recommendPlaybackFailures++
        if(recommendPlaybackFailures>RECOMMEND_SKIP_LIMIT) {
            // 连续几部都放不了：把这一层收干净（画面 / 传输 / 弹幕），只留一句提示。
            // 不写历史（推荐页本来就不写），也不改 `screen`——返回键仍然是回主页面。
            // 收画面由 [showRecommendMessage] 统一做（它只在「整屏一行字」时才动手）。
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
    // 队列在的时候出错，多半是**下一集**加载失败（当前这一集还在播，只是加载线程先撞上了）。
    // 判断依据用播放位置：已经走到片尾就是「下一集没接上」，从头重切一次；
    // 还在中段就是当前这一集自己坏了，按原逻辑原地重试。
    //
    // [failedQueuedInfo]：这一次失败的**归属**。队列那一集出错时要拿它的档位表去归因，
    // 见 [playbackAttribution] 的 `failedQueued`。必须在 `dropQueuedEpisode()` 之前抄走。
    var failedQueuedInfo: StreamInfo?=null
    if(queuedEpisodeId!=null) {
        val p=player
        val duration=p?.duration?.coerceAtLeast(0) ?: 0
        val atEnd=duration>0 && (p?.currentPosition ?: 0)>=duration-2_000
        val next=episodeIndex+1
        val data=detail
        failedQueuedInfo=queuedVideo?.info
        dropQueuedEpisode()
        // 自动重切只给一次：地址失效 / 鉴权失败 / 无法解码时，反复重切只会
        // 让用户看着画面一黑一亮地循环。第二次失败就落到下面的错误界面，交给用户决定。
        if(atEnd && data!=null && next in data.episodes.indices && ledger.autoNextFailures<1) {
            ledger.noteAutoNextFailure()
            android.util.Log.w("HGTV","Queued episode failed near end; retrying next=${next+1} attempt=${ledger.autoNextFailures}")
            retryPosition=0; retryAutoplay=requestedAutoplay && !sleepStopped
            // 目标换成了下一集：`recovering=true` 只表示「同一集重来」，这里必须先按**新一集**
            // 归零那几份不按集做键的额度（见 [restartAnotherEpisode]）。不归零的话，
            // 上一集烧掉的自动重试 / 画面重建次数会原样压到这一集头上；错误页还会顶着
            // 上一集的「请切换为软件解码」。
            restartAnotherEpisode(next,0,retryAutoplay)
            return
        }
        // 中段：这条致命错误属于队列里那一集，可 ExoPlayer 一报错就把**整台**播放器推到
        // `STATE_IDLE`（2.19 的 `ExoPlayerImpl` → `PlaybackInfo.copyWithPlaybackState(1)`），
        // 当前这一集的画面也跟着停了。因此不能「什么都不做」：必须原地把它接回来。
        if(!atEnd && recoverQueuedCurrent()) return
        android.util.Log.w("HGTV","Queued episode failed; giving up automatic advance")
    }
    // —— 降级决策：一次失败只变一个参数，有预算、可归因（见 `core/PlaybackDegradation.kt`）——
    //
    // 只有 4xxx（解码类）进这条路；1xxx/2xxx/3xxx（源 / 网络 / 封装）仍走下面的重试与错误页。
    // 推荐页在上面的分支已经 return，因此不会消费正片的预算——那一页的规则是「放不了就换下一部」。
    val decodeCode=(problem as? PlaybackException)?.errorCode ?: 0
    // 放弃自动处置时**为什么**放弃。错误页据此决定给哪几颗按钮（见下面的 `actions`）。
    //
    // 早先这个信息藏在 `sessionPlayback.manualHint` 里，页面只能靠「有建议 && 解码类」反推，
    // 于是音频失败也摆出了「切换为软解 / 换到 720P」这两颗**对音频完全无效**的按钮：
    // 音频走全表选择器，换解码方式与换档位都不作用在它身上。
    var giveUp: GiveUpReason?=null
    if(decodeCode in 4000..4999) {
        val attr=playbackAttribution(problem,decodeCode,failedQueuedInfo)
        // ① 本机**一颗 HEVC 硬解都没有**（硬件档的选择器筛出来就是空表，见 `decoderSelector`）：
        //    4002 与「这一档太高」无关——任何档位都是同一张空表。降档只会白烧两次起播
        //    （各一次取地址 + 本地准备），最后还是同一句话。直接把「切软解 + 具体档位」给出来。
        //
        //    判据用起播前就探过的 `hardwareHevcAvailable`（与选择器同源，见
        //    `PlaybackCapability.hasHardwareHevcDecoder`），不额外花一次 Binder 扫描。
        //
        //    这颗硬解**根本不存在**是起播前就定下的确定事实，与错误码无关：同一台机器上
        //    更常见的 4001（厂商解码器 init 失败）/ 4004 / 4005 走的也是同一张空表。
        //    早先只有 4002 命中这条短路，于是这类设备仍要先烧「重建一次 + 降档两次」
        //    = 三次黑屏 + 三次重取地址，最后才告诉用户「请换软解」。
        val queryFailed=decodeCode==PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED &&
            attr.media==MediaKind.VIDEO
        val noHardwareDecoder=attr.media==MediaKind.VIDEO &&
            attr.currentMode==VideoDecoderMode.HARDWARE && !hardwareHevcAvailable
        if(queryFailed || noHardwareDecoder) {
            sessionPlayback.noteManualHint(PlaybackDegradation.manualHint(attr))
            giveUp=GiveUpReason.NO_LOWER_TIER
        } else {
            when(val decision=PlaybackDegradation.decide(attr,sessionPlayback)) {
                is RetryDecision.Retry -> { replayWithPlan(decision.plan); return }
                is RetryDecision.GiveUp -> giveUp=decision.reason
            }
        }
    }
    retryPosition=currentPosition().coerceAtLeast(0); retryAutoplay=requestedAutoplay && !sleepStopped
    saveProgress(); cancelRecovery(); cancelPrefetch()
    // 无感切集留下的两个尾巴：缓冲指示要恢复，上一集的视频与传输要收掉（这一集已经出错，不会再出画面了）。
    restoreBufferingUi(); retirePreviousEpisode(); dropQueuedEpisode()
    playError=true; player?.pause(); progressBar.visibility=View.GONE; hud.visibility=View.VISIBLE; controls.removeAllViews(); controls.visibility=View.VISIBLE; panel=true
    // 停在错误页上不再需要守着屏幕：用户看完提示可能就走开了（见 [syncKeepScreenOn]）。
    syncKeepScreenOn()
    // 顶部那条信息是常驻的，出错时按当前页再刷一遍（集数不会因为出错而消失）。
    syncPlaybackInfo()
    dismissPlaybackMenu()
    // 这一次失败落在哪条轨上：下面**所有**文案与按钮都要按它分流。
    // 音频走全表选择器（见 `decoderSelector`），换解码方式与换档位都不作用在它身上，
    // 因此「切换为软解 / 换到 xP」这两颗按钮对音频必须**不出现**——点了只会再撞一次。
    val media=playbackMediaKind(problem)
    val videoProblem=media==MediaKind.VIDEO
    // 音频输出（AudioTrack 初始化 / 写入）失败：与解码器、清晰度都无关，
    // 但绝大多数是音频 HAL 被别的应用占着——等几秒再来一次通常就回来了（见下面的 `retryable`）。
    val audioOutput=problem is PlaybackException && problem.errorCode in 5000..5999
    val decoding=problem is PlaybackException && problem.errorCode in 3000..4999
    val retryable=if(problem is PlaybackException)
        problem.errorCode in 2000..2999 ||
            problem.errorCode==PlaybackException.ERROR_CODE_TIMEOUT ||
            // 5xxx：音频输出起不来。一次瞬态故障不该直接判死——自动重试那 3/6/12 秒
            // 正好覆盖「别的应用刚把音频 HAL 让出来」这段时间。
            problem.errorCode in 5000..5999
    else problem is java.io.IOException
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
        io.submit {
            val hevc=PlaybackCapability.canDecodeHevc(this)
            val hardware=PlaybackCapability.hasHardwareHevcDecoder(this)
            main.post {
                if(isDestroyed) return@post
                // 档位只有 4 / 5，与解码能力无关，这里不动它（见 `ContentRepository.deviceLevel`）。
                playbackHevcSupported=hevc
                hardwareHevcAvailable=hardware
            }
        }
    }
    // 降级决策已经走完也放不了：把「换软解 + 换到哪一档」摆到文案与按钮上，
    // **由用户点**（需求：优先硬解，不支持硬解时提示用户换成软解和支持的清晰度）。
    val manualHint=sessionPlayback.manualHint
    playbackText.text=when {
        // 音频输出起不来：这句话必须与「解码失败」分开——前者是设备被占，后者是片源。
        audioOutput -> "音频输出初始化失败，可重试或稍后再试。"
        // 音频：走到这里说明本机的音频解码器全军覆没（或音轨本身坏了），换哪一档都一样，如实说。
        decoding && !videoProblem -> "音频解码失败，这台设备无法播放该音轨。"
        // 解码类失败且降无可降：直接给出手动处置建议（换软解 / 换到具体哪一档）。
        // 必须带 `decoding` 门槛：这条建议是**上一次解码失败**留下的，若这一集随后因为
        // 网络超时 / 取流失败进来，把「请切换为软件解码」顶上去就是一句与故障无关的误导。
        manualHint!=null && decoding -> manualHint
        // 解不了 HEVC 的设备：这条路再怎么换清晰度都走不通。
        decoding && !playbackHevcSupported -> "这台电视没有 HEVC 硬解，请在播放设置里切换为软件解码。"
        decoding -> "电视无法解码当前视频，可在播放设置里换更低清晰度或软件解码。"
        else -> "播放失败，可重试或切换清晰度。"
    }
    // 说明：`giveUp` 只进日志（见本函数末尾那一行），不直接参与这里的文案——
    // 放弃时 `decide` 一定已经写过 `manualHint`，那句带着具体档位，比任何通用文案都准。
    // 按钮的可见性由三件事决定：媒体类型（音频不给视频按钮）、当前解码方式、还有没有更低档。
    val actions=row(); controls.addView(actions)
    // 手动重试同样换一个 `device_id`（见 `ContentRepository.rotateDeviceForRetry`）：
    // 用户按「立即重试」时最可能就是刚才那个身份被挡 / 被限速，不换号只是重放同一次失败。
    addButton(actions,"立即重试") {
        runCatching { repository.rotateDeviceForRetry() }
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
    val failedInfo=failedQueuedInfo ?: video?.info
    val available=failedInfo?.available.orEmpty()
    val actualQuality=failedInfo?.actualQuality?.takeIf { it>0 } ?: playbackMaxQuality
    // 硬解失败：给一个「切换为软解」的按钮，并**同时**把档位降到软解跑得动的那一档
    //（只换解码器不降档，1080 软解在盒子上照样是掉帧 / 卡死，等于白换一次）。
    //
    // 上限取**当前这一档**：换解码器允许停在原档（480 软解照放），但绝不能把已经降下来的
    // 档位抬回去——那是「这台机器这一刻放不了」的既成结论，与 `SessionPlaybackPolicy`
    // 里「不倒退」的口径一致。
    val softTarget=PlaybackDegradation.softwareQuality(available,actualQuality)
    // 只对**视频**给这两颗按钮：音频走全表选择器，换解码方式与换档位都不作用在它身上。
    if(videoProblem && playbackDecoderMode==VideoDecoderMode.HARDWARE && playbackHevcSupported) addButton(actions,"切换为软解") {
        library.decoderMode=VideoDecoderMode.SOFTWARE
        sessionPlayback.onManualDecoderChange()
        if(softTarget>0) sessionPlayback.setMaxQuality(softTarget)
        applyDecoderMode(retryPosition,retryAutoplay)
    }
    // 「换到 xP」：目标必须**严格低于刚刚失败的那一档**。
    //
    // 早先直接取 `softwareQuality(available)`：`available` 是 CDN 这一次发的全表，降档不会
    // 让它缩短，于是降到 480 失败后按钮仍写「换到 720P」——点下去把清晰度抬回去、解码方式
    // 仍是硬解、换号重取一条地址，必然复现同一类失败，只多一轮 500~1100ms 与一次黑屏。
    // 已经是最低档时这里得到 0，按钮直接不出现：没有更低的档可换，就不该给一个假的出口。
    val lower=PlaybackDegradation.lowerThan(available,actualQuality)
    val swapTarget=if(lower!=null) PlaybackDegradation.softwareQuality(available,lower) else 0
    if(videoProblem && swapTarget>0) addButton(actions,"换到 ${swapTarget}P") {
        runCatching { repository.rotateDeviceForRetry() }
        // 同「立即重试」：这是用户主动发起的新一轮尝试，自动重试 / 画面重建的额度一起还。
        // 档位在还款之后才落进会话层——`onManualRetry` 不动 `effectiveMaxQuality`，
        // 顺序反过来也能工作，但先还账再定档读起来才是「先清预算、再设目标」。
        resetBudgetsForManualRetry()
        sessionPlayback.setMaxQuality(swapTarget)
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
    val used=ledger.queuedRecoveries(key)
    if(used>=QUEUED_RECOVERY_LIMIT) return false
    // 位置直接问播放器：`STATE_IDLE` 下它仍然保留失败那一刻的读数（错误不会把位置清零），
    // 而 [currentPosition] 会看 `pendingSeek` / `playbackReady`，在出错态下不可靠。
    val live=runCatching { player?.currentPosition ?: -1L }.getOrDefault(-1L)
    val at=(if(live>0) live else startPosition).coerceAtLeast(0)
    ledger.noteQueuedRecovery(key,used+1)
    // 队列那一集已经确认坏过一次：片尾不再对它做「同档重切一次」的那次尝试
    //（见 `playerFailure` 里 `atEnd` 分支的 `ledger.autoNextFailures<1`）——重来只会再黑一次。
    ledger.noteAutoNextFailure()
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
internal fun playbackFailureTag(problem: Throwable?): String = when(problem) {
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
internal fun playbackFailureReason(problem: Throwable?): String? {
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
internal inline fun <reified T: Throwable> hasCauseOf(problem: Throwable?): Boolean {
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
internal fun networkFailureOf(problem: Throwable?): String? {
    var current=problem
    var depth=0
    while(current!=null && depth<8) {
        when(current) {
            // 顺序有意义：`SocketTimeoutException` / `ConnectException` 都是 `IOException`，
            // `UnknownHostException` 也是——所以这几个具体类型必须排在笼统判断前面。
            is java.net.UnknownHostException -> return "视频服务器域名无法解析"
            is java.net.SocketTimeoutException -> return "连接视频服务器超时"
            is java.net.ConnectException -> return "连不上视频服务器"
            is java.net.NoRouteToHostException -> return "网络不通，无法访问视频服务器"
            is javax.net.ssl.SSLException -> return "视频连接加密握手失败"
        }
        current=current.cause
        depth++
    }
    return null
}
/**
 * 这一串异常里有没有「这一集的文件换过了」。
 *
 * ExoPlayer 会把数据源抛出的异常层层包进 `PlaybackException`，因此只能顺着 cause 往下找，
 * 深度上限 8 与 [streamExpiredOf] 同因。
 */
internal fun videoChangedOf(problem: Throwable?): Boolean {
    var current=problem
    var depth=0
    while(current!=null && depth<8) {
        if(current is com.hgtv.star.core.VideoChangedException) return true
        current=current.cause
        depth++
    }
    return false
}
/**
 * 这一串异常里有没有「名字解析出来了，但连接层就没通」。
 *
 * 与 `hasCauseOf<UnknownHostException>` 分开：解析失败说明坏在 DNS 的**回答**上，
 * 这里收的是**回答没问题、路走不通**——超时（SYN 或 TLS 无应答）、被拒（RST）、
 * 路由不可达、握手失败。两者在自愈路径上处理完全一样（清地址 + 清 DNS + 清连接池），
 * 分开只为日志能说清是哪一半。
 *
 * 深度上限 8 与 [streamExpiredOf] 同因：ExoPlayer 会套两三层包装，而写死上限能防环形 cause。
 */
internal fun transportDeadOf(problem: Throwable?): Boolean {
    var current=problem
    var depth=0
    while(current!=null && depth<8) {
        when(current) {
            // 顺序有意义：`SocketTimeoutException` / `ConnectException` 都是 `IOException`，
            // 具体类型必须排在笼统判断前面（这里本来就没有笼统分支，保持与 [networkFailureOf] 一致）。
            is java.net.SocketTimeoutException,
            is java.net.ConnectException,
            is java.net.NoRouteToHostException,
            is javax.net.ssl.SSLException -> return true
        }
        current=current.cause; depth++
    }
    return false
}
/**
 * 自愈前把「连接层缓存」一起清掉：视频域名的解析结果 + 进程级连接池。
 *
 * 为什么不能只丢播放地址：地址是按集签发的，但**host 常常没变**（同一部剧的相邻几集
 * 大多落在同一个 CDN shard 上）。于是重取回来的地址还是那个域名，
 * 于是又命中 DNS 里那份 10 分钟 / 30 分钟前的坏 IP，又复用连接池里那条半死连接，
 * 下一发照样卡满超时——用户看到的就是「重试多少次都一样，换一集立刻好」。
 *
 * 域名来源按优先级取 [MainActivity.lastStreamHost]：`prepare()` 失败时 [MainActivity.video]
 * 还是上一集的（见该字段的注释），拿它清等于清错对象。
 *
 * **只清 idle 连接**（`evictAll` 就是这个语义）：在途的那条由 `retirePreviousEpisode()`
 * → `RemoteVideo.close()` 取消，两件事合起来才等于「下一条连接一定重新建」。
 */
internal fun MainActivity.dropDeadTransport() {
    val hosts=LinkedHashSet<String>(4)
    lastStreamHost.takeIf { it.isNotEmpty() }?.let(hosts::add)
    video?.host?.takeIf { it.isNotEmpty() }?.let(hosts::add)
    queuedVideo?.host?.takeIf { it.isNotEmpty() }?.let(hosts::add)
    for(host in hosts) HgtvDns.invalidate(host)
    runCatching { com.hgtv.star.data.PlaybackTransport.evictIdleConnections() }
    if(hosts.isNotEmpty()) android.util.Log.w("HGTV","Transport cache cleared hosts=$hosts pool=evicted")
}
/**
 * 这一串异常里有没有「播放地址已失效」。
 *
 * ExoPlayer 会把数据源抛出的 `IOException` 层层包进 `PlaybackException`（有时还套一层
 * `HttpDataSourceException` 形态的包装），因此只能顺着 cause 往下找。深度上限 8：
 * 真出问题时不会无限包，而写死上限能防住环形 cause 链。
 */
internal fun streamExpiredOf(problem: Throwable?): Boolean {
    var current=problem
    var depth=0
    while(current!=null && depth<8) {
        if(current is com.hgtv.star.core.StreamExpiredException) return true
        current=current.cause; depth++
    }
    return false
}
internal fun MainActivity.hidePlaybackOverlay() {
    panel=false; dismissPlaybackMenu(); controls.visibility=View.GONE; controls.clearFocus(); hud.visibility=View.GONE
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
internal fun MainActivity.scheduleOverlayAutoHide() {
    main.removeCallbacks(overlayAutoHide)
    val visible=playbackMenu!=null || episodePanel!=null || hudShown()
    if(!visible) return
    // 设置还没读出来时用出厂值：冷启动直接进播放页时 `library` 可能仍为空。
    val seconds=runCatching { library.playerPanelTimeout }.getOrDefault(PLAYER_PANEL_TIMEOUT_DEFAULT)
    main.postDelayed(overlayAutoHide,(seconds*1000L).coerceAtLeast(1000L))
}

/** 浮层收起（选择完成、返回键、自动消失）时把定时器一并停掉。 */
internal fun MainActivity.cancelOverlayAutoHide() = main.removeCallbacks(overlayAutoHide)

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
internal fun MainActivity.hudShown(): Boolean = runCatching {
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
internal fun MainActivity.onPlaybackPage(): Boolean = screen=="player" || screen=="recommend"

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
internal fun MainActivity.syncKeepScreenOn() {
    val on=foreground && onPlaybackPage() && !playError && !sleepStopped &&
        player?.playWhenReady==true
    if(on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}

internal fun MainActivity.showHud() {
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
internal fun MainActivity.updatePlaybackText() {
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
    if(playError) return
    if(sleepStopped) { playbackText.text="定时停止 · 按播放继续"; return }
    if(!playbackReady) { playbackText.text=if(video==null) "正在获取播放地址…" else "正在缓冲…"; return }
    val p=player ?: return
    val duration=p.duration.coerceAtLeast(0); val position=pendingSeek ?: p.currentPosition
    val span="${formatSpan(position)} / ${formatSpan(duration)}"
    playbackText.text=if(p.playbackState==Player.STATE_BUFFERING) "缓冲中 · $span" else if(p.playbackState==Player.STATE_ENDED) "本集已结束 · $span" else span
    progressBar.progress=if(duration>0) position.toFloat()/duration else 0f
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
internal fun MainActivity.syncPlaybackEpisodeLabel(data: Detail? = detail) {
    val view=playbackEpisode ?: return
    val total=data?.episodes?.size ?: 0
    if(total<=0) return
    val key="${episodeIndex}/${total}"
    if(view.tag==key) return
    view.tag=key
    view.text="第 ${episodeIndex+1} / $total 集"
}
internal fun MainActivity.requestPlayback(play: Boolean) {
    requestedAutoplay=play; pausedForLifecycle=!play
    if(play) sleepStopped=false
    if(!play) { player?.pause(); retryAutoplay=false; saveProgress() }
    // 错误页上按「播放」＝一次手动重试：与错误页那颗按钮走**同一条**还款路径
    //（`resetBudgetsForManualRetry`），否则两个入口的额度语义不一样：
    // 按钮还三份、这里只还 session 那一份，自动重试用尽之后按播放就只会闪回同一句提示。
    else if(playError) {
        resetBudgetsForManualRetry()
        playEpisode(episodeIndex,retryPosition,true,recovering=true)
        return
    }
    else { if(player?.playbackState==Player.STATE_ENDED) player?.seekTo(0); player?.play() }
    // 屏幕常亮由状态推导，不再在这里 add/clear（见 [syncKeepScreenOn]）：
    // `play` 与「真的在播」不是同一件事——出错页、定时停、后台回来这三条都会不一样。
    syncKeepScreenOn()
    showHud(); updatePlaybackText(); syncMediaSession()
}
internal fun MainActivity.togglePlayback()=requestPlayback(!(if(playbackReady) player?.playWhenReady ?: requestedAutoplay else requestedAutoplay))
internal fun MainActivity.seekTo(position: Long) {
    val p=player ?: return; if(!playbackReady || p.duration<=0) return
    main.removeCallbacks(seekRunnable); pendingSeek=null
    p.seekTo(position.coerceIn(0,(p.duration-500).coerceAtLeast(0))); updatePlaybackText(); syncMediaSession(); showHud()
}
internal fun MainActivity.skipEpisode(direction: Int) {
    if(!foreground || (screen!="player" && screen!="recommend")) return
    val count=detail?.episodes?.size ?: return
    val target=episodeIndex+direction
    if(target in 0 until count) playEpisode(target)
}
/**
 * 媒体会话懒创建：启动阶段不碰系统 `media_session`。
 *
 * 虚拟容器（SandVXposed 等）会把 `ISessionManager.createSession` 的返回值代理成 null，
 * 而平台 `MediaSession` 构造函数不判空，直接在 `mBinder.getController()` 抛 NPE，
 * 顺着 `onCreate` 变成「Unable to start activity」闪退。这里建不出来就记住失败，
 * 之后静默跳过——遥控器媒体键只是附加能力，不影响播放。
 */
internal fun MainActivity.ensureMediaSession(): TvMediaSession? {
    mediaSession?.let { return it }
    if(mediaSessionFailed) return null
    val created=TvMediaSession.create(this,
        { if(foreground && (screen=="player" || screen=="recommend")) requestPlayback(true) },
        { if(foreground && (screen=="player" || screen=="recommend")) requestPlayback(false) },
        { position -> if(foreground && (screen=="player" || screen=="recommend")) seekTo(position) },
        { direction -> if(screen=="recommend") switchRecommend(direction) else skipEpisode(direction) },
        { if(foreground && screen=="player") returnToDetail() })
    if(created==null) mediaSessionFailed=true else mediaSession=created
    return created
}
internal fun MainActivity.syncMediaSession() {
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
internal fun MainActivity.stopForSleep() {
    dropQueuedEpisode()
    sleepStopped=true; requestedAutoplay=false; retryAutoplay=false; pausedForLifecycle=true
    sleepChoice=0
    cancelRecovery(); player?.pause(); saveProgress()
    syncKeepScreenOn()
    playbackText.text="定时停止已生效 · 观看位置已保存 · 按播放可继续"
    showHud(); if(playbackMenu!=null) showPlaybackMenu(sleepRow(library.danmakuMode.showsStyle)); syncMediaSession()

}
/** 定时项目的 8 个选项：关闭排在最前，选中态跟随本次观看已生效的定时。 */
internal fun MainActivity.sleepChoices(menu: PlaybackMenu): List<PlaybackMenu.Choice> {
    val current=if(sleepTimer.active) sleepChoice else 0
    return sleepLabels.mapIndexed { index,label ->
        PlaybackMenu.Choice(label,current==index) {
            sleepChoice=index
            when(index) {
                0 -> sleepTimer.cancel()
                in 1..4 -> sleepTimer.afterMinutes(listOf(15,30,60,90)[index-1])
                else -> sleepTimer.afterEpisodes(listOf(1,3,5)[index-5])
            }
            // 定时生效期间不放下一集进队列（见 [enqueuePreparedNext]）：已经在队列里的先摘掉，
            // 否则片尾 ExoPlayer 会自己推进，界面来不及在片尾那一下停住。
            if(index!=0) dropQueuedEpisode()
            menu.select(sleepRow(library.danmakuMode.showsStyle),index); updatePlaybackText()
        }
    }
}
internal fun MainActivity.frameResizeMode()=when(library.frameMode) {
    VideoFrameMode.FIT -> com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
    VideoFrameMode.ZOOM -> com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    VideoFrameMode.FILL -> com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
}
/**
 * 「竖屏短剧全屏」**这台设备上能不能用**：只有手机 / 平板可用，电视不可用。
 *
 * 形态来自 [deviceMode]：用户手动选过（`library.deviceForm` = phone/tablet/tv）就以他选的为准，
 * 没选过（`auto`）就看启动时 [com.hgtv.star.compat.detectDeviceForm] 的打分结果。
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
internal fun MainActivity.autoFullscreenAvailable(): Boolean = when(deviceMode()) {
    com.hgtv.star.compat.DeviceForm.PHONE,
    com.hgtv.star.compat.DeviceForm.TABLET -> true
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
internal fun MainActivity.autoFullscreenRotated(): Boolean {
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
internal fun MainActivity.fullscreenSpinDirection(): Float {
    // 「使用设备」钉在电视上（或自动判定就是电视）：电视恒为横屏 `ROTATION_0`，直接用 `+90`。
    // 电视上这一项现在不可用（见 [autoFullscreenAvailable]），走不到这里；
    // 留着这一档是为了手动选「电视」之后再切回手机 / 平板时方向仍然有确定值。
    if(deviceMode()==com.hgtv.star.compat.DeviceForm.TV) return 90f
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
internal fun MainActivity.applyVideoGeometry() {
    val view=playerView ?: return
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
internal fun MainActivity.applyOverlayGeometry() {
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
internal fun MainActivity.applyResizeMode() {
    playerView?.resizeMode=frameResizeMode()
    // 旋转 / 缩放与画面模式是同一次「这一集怎么摆」的决策：片源尺寸一变，
    // 旋转与否可能跟着变（竖屏剧 ⟷ 横屏剧），这里一并落下去。
    applyVideoGeometry()
}
/**
 * 画面缩放 / 竖屏短剧全屏：两者的落点都是 [playerView] 这一层的几何，
 * 因此统一交给 [applyVideoGeometry]（旋转与缩放一起算，缺一个都会排错边）。
 *
 * 弹幕层（[danmakuView]）**不受影响**：它仍然占满整屏、按整屏坐标算轨道，
 * 所以把画面缩到 50%（或转 90°）时，弹幕照旧铺满屏幕横着滚。
 */
internal fun MainActivity.applyVideoScale() = applyVideoGeometry()
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
internal fun MainActivity.playbackOverlayShown(): Boolean {
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
internal fun MainActivity.syncPlaybackInfo() {
    val bar=playbackInfo
    if(bar!=null) {
        // 浮层露着 = 四项全显（见函数注释第 2 条）。
        val allOn=playbackOverlayShown()
        val shown=screen=="player" || (screen=="recommend" && touchArmed && allOn)
        bar.visibility=if(shown) View.VISIBLE else View.GONE
        playbackTitle.visibility=if(allOn || library.playerTitle) View.VISIBLE else View.GONE
        playbackEpisode?.visibility=if(allOn || library.playerEpisode) View.VISIBLE else View.GONE
        playerClock?.visibility=if(allOn || library.playerClock) View.VISIBLE else View.GONE
        playbackSpeedText?.visibility=if(allOn || library.playerSpeed) View.VISIBLE else View.GONE
        // 调试面板是**悬浮**在最上层的一块，不参与任何布局：
        // 这里不按它让位、不改内边距，否则顶部这条会被压窄（用户要求「不要挤压任何元素」）。
        // 面板盖住哪一格就盖住哪一格，关掉即恢复。
    }
    updatePlaybackInfoReadings()
}
/** 只换字、不动布局：时间每分钟一格，网速每一秒一格（见每秒一次的 tick）。 */
internal fun MainActivity.updatePlaybackInfoReadings() {
    playerClock?.takeIf { it.visibility==View.VISIBLE }?.let { clock ->
        val now=clockFormat.format(java.util.Date())
        if(clock.text.toString()!=now) clock.text=now
    }
    playbackSpeedText?.takeIf { it.visibility==View.VISIBLE }?.let { speed ->
        val text=NetSpeed.line()
        if(speed.text.toString()!=text) speed.text=text
    }
}
internal fun MainActivity.showFramePicker(anchor: TextView,inPlayer: Boolean) {
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
internal fun MainActivity.showDecoderPicker(anchor: TextView,inPlayer: Boolean) {
    val labels=listOf("硬件解码 · 省电，4K 更稳（默认）","软件解码 · 兼容性好，花屏 / 绿屏 / 音画不同步时用")
    tvTools.choose("视频解码",labels,anchor,{ index ->
        val selected=VideoDecoderMode.entries[index]
        val changed=playbackDecoderMode!=selected
        library.decoderMode=selected
        // 用户手动改解码方式：撤销自动切换、归还「切软解」的预算（不动清晰度上限）。
        sessionPlayback.onManualDecoderChange()
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
internal fun MainActivity.showHomeColumnsPicker(anchor: TextView) {
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
internal fun backToTopLabel(mode: String): String=when(mode) {
    BACK_TO_TOP_ROWS5 -> "下拉 5 行列表后显示"
    BACK_TO_TOP_ROWS8 -> "下拉 8 行列表后显示"
    BACK_TO_TOP_NEVER -> "始终不显示"
    else -> "始终显示"
}
/**
 * 设置格上显示的那一段读数（**短**）：一格只有半屏宽，值最多占一半，
 * 完整文案会被省略号吃掉尾巴，读出来就成了「下拉 8 行列表…」。
 * 弹窗里的选项仍用 [backToTopLabel] 的完整说法。
 */
internal fun backToTopShort(mode: String): String=when(mode) {
    BACK_TO_TOP_ROWS5 -> "下拉 5 行"
    BACK_TO_TOP_ROWS8 -> "下拉 8 行"
    BACK_TO_TOP_NEVER -> "始终不显示"
    else -> "始终显示"
}
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
internal fun MainActivity.showBackToTopPicker(anchor: TextView) {
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
internal fun MainActivity.showEpisodePageSizePicker(anchor: TextView) {
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
internal fun MainActivity.showDanmakuPicker(anchor: TextView,inPlayer: Boolean,onChanged: ()->Unit={}) {
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
 * 三档只改「整体多快」：弹幕层里所有弹幕同速（见 [com.hgtv.star.player.DanmakuView]），
 * 换档不会让某一条追上前面那一条——快的档位只是让同一屏通过得更多、同一条轨道排得更密。
 * [onChanged] 在档位真的变了之后回调一次（设置页用它刷新那一行文案）。
 */
internal fun MainActivity.showDanmakuSpeedPicker(anchor: TextView,inPlayer: Boolean,onChanged: ()->Unit={}) {
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
internal fun MainActivity.danmakuLabel(prefix: String)=prefix+library.danmakuMode.label+
    if(danmakuCount<0) "" else "（上一集 ${if(danmakuCount>0) "$danmakuCount 条" else "无弹幕"}）"
internal fun MainActivity.seek(direction: Int,repeat: Int) {
    val p=player ?: return; if(!playbackReady) return
    val duration=p.duration; if(duration<=0) return
    // 单次步长取自设置页「播放偏好 · 快进快退时长」（2..20 秒，默认 10）。
    // 连按加速仍按 3 倍：10 秒档下就是原来那句「连按 5 次后每次 30 秒」，
    // 调到 20 秒档就是 20/60，调到 2 秒档就是 2/6——比例始终一致，手感不会因为改档而错位。
    val base=library.seekStep*1000L
    val step=if(repeat>4) base*3 else base
    pendingSeek=((pendingSeek ?: p.currentPosition)+direction*step).coerceIn(0,(duration-500).coerceAtLeast(0))
    main.removeCallbacks(seekRunnable); main.postDelayed(seekRunnable,250); updatePlaybackText(); showHud()
}
internal fun MainActivity.dismissPlaybackMenu() {
    playbackMenu?.let { menu -> if(menu.hasFocus()) menu.clearFocus() }
    // 面板现在是「外壳 → 滚动容器 → 面板」三层，直接 removeView(menu) 摘不掉任何东西
    // （menu 的父级是滚动容器），浮层会留在画面上。这里按最外层摘。
    playbackMenuShell?.let { shell -> (shell.parent as? ViewGroup)?.removeView(shell) }
    playbackMenuShell=null
    playbackMenu=null
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
