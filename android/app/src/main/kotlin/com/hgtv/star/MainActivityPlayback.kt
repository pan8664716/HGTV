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
    val id=data.episodes[episodeIndex+1]; val quality=library.maxQuality
    val remote=preparedNext.take("$id:$quality") ?: return
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
    if(!preparedNext.isFresh("${detail?.episodes?.getOrNull(episodeIndex+1)}:${library.maxQuality}")) return
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
    playError=false; retryPending=false; autoRecovery=false; recovery.reset()
    // 走到这里说明「下一集」真的接上了：自动重切失败的计数清零。
    // **不**在这里设互斥窗——那会把新一集紧接着发生的真实错误也一起吞掉。
    autoNextFailures=0
    playbackTitle.text="${data.series.title}  ·  第 ${next+1} 集"
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
    if(!foreground || !p.isPlaying || !library.autoNext || playError || sleepStopped || episodeIndex>=data.episodes.lastIndex) return
    if(!PrefetchPolicy.nearEnd(p.currentPosition,p.duration,p.playbackParameters.speed)) return
    val id=data.episodes[episodeIndex+1]; val quality=library.maxQuality; val key="$id:$quality"
    // 已经挂进播放器队列的那一集不用再取一次地址。
    if(queuedEpisodeId==id) return
    if(preparedNext.isFresh(key) || prefetchJob?.isDone==false) return
    val now=android.os.SystemClock.elapsedRealtime()
    if(readySince==0L || now-readySince<3000 || p.bufferedPosition-p.currentPosition<5000) return
    // 预取窗口只有 30 秒（见 [PrefetchPolicy.nearEnd]），而这条接口约一半概率回空响应：
    // 窗口内要敢重发——6 秒一次、最多 5 次。以前是 15 秒 / 3 次，窗口里只来得及试两次，
    // 两次都空就只能退回「切集时现取播放地址」那条慢路，用户看到的正是黑屏转圈。
    if(prefetchAttempts>=5 || (prefetchAttempts>0 && now-prefetchAttemptAt<6_000) || connectivity.isMeteredConnection() || !networkAvailable()) return
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
                RemoteVideo(http,source.stream(id,quality)).also { remote=it; it.warm() }
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
    if(!recovery.remaining) { autoRecovery=false; playbackText.text="已自动重试 3 次，仍无法播放。可手动重试或切换清晰度。"; return }
    if(!networkAvailable()) { playbackText.text="网络已断开，联网后自动续播 · 第 ${episodeIndex+1} 集 ${formatTime(retryPosition)}"; return }
    val delay=recovery.delayMillis ?: return
    playbackText.text="${if(retryAutoplay) "准备恢复播放" else "准备恢复暂停位置"} · ${delay/1000f} 秒后重试 ${recovery.attempts+1}/3 · ${formatTime(retryPosition)}"
    retryPending=true; main.postDelayed(retryRunnable,delay)
}
internal fun MainActivity.playerFailure(problem: Throwable) {
    // 同一次片尾故障可能先来 onPlayerError、再来 STATE_ENDED（或反过来）。
    // 两条路都去「切下一集」就是重复切换：同一会话 1.5 秒内只处理第一条。
    // 判据带会话号，所以新一集自己的错误不会被这条规则误吞。
    val now=android.os.SystemClock.elapsedRealtime()
    if(failureHandledSession==playbackSession && now-failureHandledAt in 0..1500) {
        android.util.Log.i("HGTV","Duplicate playback failure ignored session=$playbackSession")
        return
    }
    failureHandledSession=playbackSession; failureHandledAt=now
    // 同一件事的解码器报错可能还挂在 1.5 秒的等待里（见 [playbackDiagnostics] 的
    // `onVideoCodecError`）：这条已经带着完整 cause 链报了，那条不必再来一遍。
    pendingCodecReport?.let { main.removeCallbacks(it) }; pendingCodecReport=null
    // 上报放在互斥窗之后、任何重试分支之前：同一次故障只报一条，且「自动重试成功」
    // 也不会让这条记录消失——用户看到过的那次失败是真的发生过。
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
            // 末尾还有下一页：先补一页再从第一部试。补页请求已经在路上时不能干等（那样
            // 这一屏就停在失败的那一集上），直接环绕回第一部。
            recommendHasMore -> main.post {
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
            recommendItems.isNotEmpty() -> main.post { if(!isDestroyed && screen=="recommend") playRecommendAt(0,0L,1) }
            else -> showRecommendMessage("暂时没有可播放的推荐\n按确定键重试，按返回键回主页面")
        }
        return
    }
    // 队列在的时候出错，多半是**下一集**加载失败（当前这一集还在播，只是加载线程先撞上了）。
    // 判断依据用播放位置：已经走到片尾就是「下一集没接上」，从头重切一次；
    // 还在中段就是当前这一集自己坏了，按原逻辑原地重试。
    if(queuedEpisodeId!=null) {
        val p=player
        val duration=p?.duration?.coerceAtLeast(0) ?: 0
        val atEnd=duration>0 && (p?.currentPosition ?: 0)>=duration-2_000
        val next=episodeIndex+1
        val data=detail
        dropQueuedEpisode()
        // 自动重切只给一次：地址失效 / 鉴权失败 / 无法解码时，反复重切只会
        // 让用户看着画面一黑一亮地循环。第二次失败就落到下面的错误界面，交给用户决定。
        if(atEnd && data!=null && next in data.episodes.indices && autoNextFailures<1) {
            autoNextFailures++
            android.util.Log.w("HGTV","Queued episode failed near end; retrying next=${next+1} attempt=$autoNextFailures")
            retryPosition=0; retryAutoplay=requestedAutoplay && !sleepStopped
            playEpisode(next,0,retryAutoplay,recovering=true)
            return
        }
        android.util.Log.w("HGTV","Queued episode failed; giving up automatic advance")
    }
    retryPosition=currentPosition().coerceAtLeast(0); retryAutoplay=requestedAutoplay && !sleepStopped
    saveProgress(); cancelRecovery(); cancelPrefetch()
    // 无感切集留下的两个尾巴：缓冲指示要恢复，上一集的视频与传输要收掉（这一集已经出错，不会再出画面了）。
    restoreBufferingUi(); retirePreviousEpisode(); dropQueuedEpisode()
    playError=true; player?.pause(); progressBar.visibility=View.GONE; hud.visibility=View.VISIBLE; controls.removeAllViews(); controls.visibility=View.VISIBLE; panel=true
    // 停在错误页上不再需要守着屏幕：用户看完提示可能就走开了（见 [syncKeepScreenOn]）。
    syncKeepScreenOn()
    // 出错界面要让用户看清是哪一集，标题条这时必须露出来。
    playbackTopBar?.visibility=View.VISIBLE
    dismissPlaybackMenu()
    val decoding=problem is PlaybackException && problem.errorCode in 3000..4999
    val retryable=if(problem is PlaybackException) problem.errorCode in 2000..2999 || problem.errorCode==PlaybackException.ERROR_CODE_TIMEOUT else problem is java.io.IOException
    // 解码器压根没起来（4001 / 4002）：这台设备对「能解 HEVC」的声明可能不成立。
    // 探测结果就此作废、**重新探一次**，并把清晰度降到最低一档——按原档位重试只会重复同一个失败。
    //
    // 不能在这一条上直接把 `playbackHevcSupported` 判成 false：解码器初始化失败也可能只是
    // 这一刻实例被占满 / 系统忙，一次失败不足以给这台设备定性。重探的结果才是判据。
    val decoderMissing=problem is PlaybackException && problem.errorCode in listOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED)
    if(decoderMissing) {
        PlaybackCapability.invalidate(this)
        if(library.maxQuality>480) library.maxQuality=480
        io.submit {
            val level=PlaybackCapability.deviceLevel(this)
            val hevc=PlaybackCapability.canDecodeHevc(this)
            main.post {
                if(isDestroyed) return@post
                repository.deviceLevel=level
                playbackHevcSupported=hevc
            }
        }
    }
    playbackText.text=when {
        // 解不了 HEVC 的设备：这条路再怎么换清晰度都走不通，别让用户反复按「尝试 720P」。
        decoding && !playbackHevcSupported -> "这台电视不支持该视频编码（HEVC），无法播放。"
        decoding -> "电视无法解码当前视频，可尝试更低清晰度。"
        else -> "播放失败，可重试或切换清晰度。"
    }
    val actions=row(); controls.addView(actions)
    addButton(actions,"立即重试") { playEpisode(episodeIndex,retryPosition,retryAutoplay) }.requestFocus()
    // 解不了 HEVC 时「尝试 720P」也是同一种编码，点下去必然再失败一次：这一档不摆。
    if(playbackHevcSupported) addButton(actions,"尝试 720P") { library.maxQuality=720; playEpisode(episodeIndex,retryPosition,retryAutoplay) }
    addButton(actions,"返回选集") { returnToDetail(true) }
    if(retryable && !sleepStopped) {
        addButton(controls,"停止自动重试") { cancelRecovery(); playbackText.text="自动重试已停止，观看位置已保留。" }
        autoRecovery=true; scheduleRecovery()
    }
    // 错误界面挂一枚短码（同一行，不加行——这一行高度是钉死的 47dp）。
    // 用户反馈「解码失败」时报出它，就能直接对上后台那条上报。
    //
    // **没有 `PlaybackException` 的路（本地准备失败、解码器自己喊的错）没有数字码**：
    // 早先照样拼一个「错误码 0」出来，用户报「错误码 0」时谁也查不到东西。
    // 那种情况改为挂异常类名（`PlaybackPrepareException` / `CodecException`），同样能对上上报。
    val code=(problem as? PlaybackException)?.errorCode ?: 0
    if(code!=0) playbackText.text="${playbackText.text} · 错误码 $code"
    else problem.javaClass.simpleName.takeIf { it.isNotBlank() }?.let {
        playbackText.text="${playbackText.text} · $it"
    }
    android.util.Log.w("HGTV","Playback failure: ${problem.javaClass.simpleName}"+(if(problem is PlaybackException) " code=${problem.errorCodeName}" else ""))
}
internal fun MainActivity.hidePlaybackOverlay() {
    main.removeCallbacks(hideHud)
    panel=false; dismissPlaybackMenu(); controls.visibility=View.GONE; controls.clearFocus(); hud.visibility=View.GONE
    // 标题条跟底部控制条同一套显隐：播放时屏幕上不留任何文字。
    playbackTopBar?.visibility=View.GONE
    // 时间那一层例外：打开「始终显示时间」后它继续留在右上角（见 [syncClockVisibility]）。
    syncClockVisibility()
}
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
    if(screen!="player" || playbackMenu!=null) return
    hud.visibility=View.VISIBLE; updatePlaybackText(); main.removeCallbacks(hideHud)
    playbackTopBar?.visibility=View.VISIBLE
    syncClockVisibility()
    if(!panel && player?.isPlaying==true) main.postDelayed(hideHud,4500)
}
/**
 * 右上角时间的显隐：控制层 / 标题条 / 播放菜单任一可见时显示，
 * 否则只在「始终显示时间」打开时留在画面上。
 */
internal fun MainActivity.syncClockVisibility() {
    val clock=playerClock ?: return
    val overlay=hud.visibility==View.VISIBLE || playbackTopBar?.visibility==View.VISIBLE || playbackMenu!=null
    // 推荐页不显示右上角时间：那一页只有画面 + 底部浮层，时间会破坏「上下刷剧」的整屏感。
    clock.visibility=if(screen=="player" && (overlay || library.alwaysShowClock)) View.VISIBLE else View.GONE
}
internal fun MainActivity.updatePlaybackText() {
    playerClock?.let { clock ->
        val now=clockFormat.format(java.util.Date())
        if(clock.text.toString()!=now) clock.text=now
    }
    // 触屏设备上控制条多一排按钮，播放键的文案也要跟着播放状态改（电视上为空操作）。
    updateTouchPlayLabel()
    if(screen!="player") return
    // 手指正在拖进度条：这一段时间进度与时间由手指决定，每秒一次的刷新不能把它顶回去。
    if(scrubbing) return
    // 播放键图标与选项行的当前值：爆米花把状态放在图标和选项文字里，不额外占一行。
    transportIcon?.setVectorIcon(if(player?.playWhenReady==true) R.drawable.ic_player_pause else R.drawable.ic_player_play)
    qualityOption?.text="清晰度 "+if(quality.isBlank()) "${library.maxQuality}P" else quality
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
internal fun MainActivity.requestPlayback(play: Boolean) {
    requestedAutoplay=play; pausedForLifecycle=!play
    if(play) sleepStopped=false
    if(!play) { player?.pause(); retryAutoplay=false; saveProgress() }
    else if(playError) { playEpisode(episodeIndex,retryPosition,true); return }
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
internal fun MainActivity.showFramePicker(anchor: TextView,inPlayer: Boolean) {
    tvTools.choose("全局画面模式",listOf("完整画面 · 保留比例与全部内容","等比铺满 · 会裁掉部分画面与字幕","拉伸铺满 · 画面比例会改变"),anchor,{ index ->
        library.frameMode=VideoFrameMode.entries[index]; playerView?.resizeMode=frameResizeMode()
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
        val changed=library.decoderMode!=selected
        library.decoderMode=selected
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
    val step=if(repeat>4) 30000 else 10000
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
    // 菜单收起：推荐页那块信息浮层回到画面上（正式播放里它是 null，空操作）。
    // 收起状态一并复位：这一行把它重新露出来了，方向键就得跟着回到「选按钮」。
    recommendOverlay?.visibility=View.VISIBLE
    recommendBarHidden=false
    syncClockVisibility()
}
/**
 * 遥控器「上」（或菜单键）打开底部设置面板：
 * 清晰度 / 倍速 / 画面模式 / 弹幕 / 弹幕大小 / 弹幕透明度 / 定时。
 *
 * 「弹幕大小 / 弹幕透明度」只在弹幕可见时存在：隐藏档下整层不画、也不发请求，
 * 这两项改完看不到任何变化，因此那两行**直接不添加**（行号见 [sleepRow]）。
 *
 * 功能竖排、选项横排，上下换功能、左右换选项，确认立即生效，返回收起整个浮层。
 * 面板高度**固定为屏幕下方三分之一**，装不下的行留在滚动容器里，遥控器焦点走到哪一行
 * 就自动把哪一行带进可视区（ScrollView 自己的 `requestChildFocus` 行为），因此所有行都能按到。
 *
 * [focusRow] 与 [focusChoice] 用来在改清晰度重建菜单、或点 HUD 状态行时定位焦点；
 * [focusChoice] 为负数时落在该项目当前选中的选项上。
 */
