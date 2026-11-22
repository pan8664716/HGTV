// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import com.hgtv.star.shared.ui.UiText
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.view.*
import android.widget.*
import android.text.TextUtils
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Renderer
import com.google.android.exoplayer2.analytics.AnalyticsListener
import com.google.android.exoplayer2.audio.AudioCapabilities
import com.google.android.exoplayer2.audio.AudioSink
import com.google.android.exoplayer2.audio.DefaultAudioSink
import com.google.android.exoplayer2.audio.DefaultAudioTrackBufferSizeProvider
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.util.MimeTypes
import com.google.android.exoplayer2.video.MediaCodecVideoRenderer
import com.google.android.exoplayer2.video.VideoRendererEventListener
import com.google.android.exoplayer2.ui.PlayerView
import com.hgtv.star.core.*
import com.hgtv.star.shared.compat.setVectorIcon
import com.hgtv.star.shared.compat.lowEndTv
import com.hgtv.star.shared.media.DanmakuView
import com.hgtv.star.tv.SeekIndicator
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.shared.media.VideoDataSource
import com.hgtv.star.shared.data.PlaybackCapability
import com.hgtv.star.shared.architecture.danmaku
import com.hgtv.star.shared.architecture.player
import com.hgtv.star.shared.debug.Diagnostics
import com.hgtv.star.shared.debug.DebugFaults
import com.hgtv.star.shared.debug.PlaybackStartupTrace
import com.hgtv.star.shared.debug.StartupBudgets
import com.hgtv.star.shared.debug.StartupDeferredTasks
import com.hgtv.star.tv.R

// 本文件是从 TvMainActivity.kt 拆出来的「播放内核：起播、切集、弹幕加载」这一块。
// 这些成员仍然属于 TvMainActivity，只是以扩展成员（TvMainActivity.xxx）的形式放在这里，
// 逻辑与拆之前逐行一致；改这里等于改 Activity 本体。

fun TvMainActivity.loadDanmaku(episodeId: String,seriesId: String,clearOnSwitch: Boolean=false) {
    // 加载内核在 `:shared`（`DanmakuLoader`）：手机播放页与手机信息流用的是同一份，
    // 三处「同一集不重拉 / 旧回包丢弃 / 隐藏时不发请求」的规则只写一次。
    // 主动换集要立刻停住旧弹幕：下面的 load 是延迟调度的，等它落地时缓冲期里
    // 旧弹幕时钟还在走，用户会看到"上一集弹幕又跑一下"。这里先同步清一次。
    if(clearOnSwitch) danmakuLoader.clear()
    StartupDeferredTasks.scheduleLatestOnMain("danmaku.tv.current",30_000L) {
        danmakuLoader.load(episodeId,seriesId,clearOnSwitch=clearOnSwitch)
    }
}

/**
 * 推荐页的相邻详情、批量模型和补页都等真正的首帧。
 *
 * READY 只表示轨道已经解出来；在低配电视上它可能比画面早几百毫秒。
 * [StartupDeferredTasks.scheduleLatestOnMain] 同时提供首帧前等待、首帧后执行和
 * 无首帧时的兜底；页面用本轮标记保证 READY 与真正首帧回调只执行一次，换剧后重置。
 */
internal fun TvMainActivity.scheduleRecommendAfterFirstFrame() {
    if(recommendAfterFirstFrameScheduled) return
    recommendAfterFirstFrameScheduled=true
    StartupDeferredTasks.scheduleLatestOnMain(
        "recommend.afterFirstFrame-${System.identityHashCode(this)}",
        30_000L,
    ) {
        main.post {
            if(isDestroyed || screen!="recommend") return@post
            scheduleRecommendPreload(slideCache.firstFrameDelay(),"first-frame")
            scheduleRecommendDwell()
            main.postDelayed({
                if(!isDestroyed && screen=="recommend") prefetchRecommendBatchModels()
            },if(runCatching { lowEndTv() }.getOrDefault(true)) 8_000L else RECOMMEND_BATCH_MODEL_DELAY_MS)
            fireRecommendFeed()
        }
    }
}

fun TvMainActivity.playEpisode(
    index: Int,
    position: Long=0,
    autoplay: Boolean=true,
    recovering: Boolean=false,
    prepared: RemoteVideo?=null,
    keepMedia: Boolean=false,
    clearDanmakuOnSwitch: Boolean=false,
    direct: Boolean=true,
) {
    val data=detail ?: return
    val directRequest=direct && !recovering
    // 用户主动换集 / 手动重来会开启新一轮起播；上一轮排着的自动重试必须先作废。
    // 已经进入取地址阶段的旧请求由下面的 work() 统一 cancelWork() 取消。
    if(!recovering) cancelRecovery()
    nextSeasonFinishedFor=""
    val playbackStarted=android.os.SystemClock.elapsedRealtime()
    // 换了一部剧：上一次的降级结论（有效上限 / 预算 / 手动建议）与提示去重全部作废——
    // 那是「上一部片在这台机器上放不了」的结论，与这一部无关。
    //
    // 判据按**剧**而不是按「有没有重建播放层」：同一部剧换集、换清晰度、重试、换解码方式
    // 都要沿用已经降下来的上限（否则每次重来都先撞一次 1080，白付一次失败）。
    if(sessionSeriesId!=data.series.id) {
        sessionSeriesId=data.series.id
        playbackSessionState.onUserManualChange()
        playbackToasts.clear()
    }
    // 推荐页也用这套播放内核：进来时记下「当前是不是推荐」，下面写 `screen` 时原样还回去，
    // 否则一换剧就变成正式播放（返回键会去详情页、上下键会变成选集）。
    val recommendEntry=screen=="recommend"
    // 从详情页 / 首页 / 搜索结果进的正片：返回键回原来的页面，不是「推荐那条路」。
    // 只有「推荐页确定 / 自动连播」进来的那一次才置位（见 [enterFormalPlayback]）；
    // 在正片内部换集 / 重试 / 换清晰度时 `screen` 已经是 `player`，标记原样保留。
    if(!recommendEntry && screen!="player") playbackFromRecommend=false
    val selectedIndex=index.coerceIn(0,data.episodes.lastIndex)
    val targetId=data.episodes[selectedIndex]
    // 设置页改档不会经过 [applyDecoderMode]；进播放前发现实例档位不一致就先放掉。
    releasePlayerForDecoderMismatch()
    // 首帧 EWMA 更新后，下一次起播按新的缓冲档位重建；当前这一集不打断。
    // 推荐流内冻结缓冲档位：这里换作频繁，不能因为 EWMA 跨档而重建整个播放器。
    if(!recommendEntry) releasePlayerForBufferMismatch()
    // `keepMedia`（同档重建）也要保住播放层：这一趟只为把解码器放掉重开，画面 / 弹幕 /
    // 控制层一个都不用换（见 [replayWithPlan]）。它和 `recovering` 是两件事——
    // 后者还包含降档、网络自愈、手动重试，那些真的需要重来一遍。
    val reuse=(screen=="player" || recommendEntry) && player!=null && playerView!=null &&
        (!recovering || keepMedia) && !playError
    // 「这一集的数据也一并留着」：只有**还能用**的那一份才算数（已经 prepare 过、且没被
    // close）。预热被换出 / 已经关掉时读不出数据，必须落回「重新取地址 + 本地准备」那条路。
    // 此时视图仍然复用（`reuse` 看的是 `keepMedia`）——那一步不需要重建整棵树。
    val keep=keepMedia && video?.isReusable==true
    // 必须先冻结，再 stop/clearMediaItems：停旧媒体会让 PlayerView 收到一次 ratio=0，
    // 如果这时还没 hold，AspectRatioFrameLayout 会立刻撑满整屏，用户看到的就是拉伸首帧。
    if((screen=="player" || recommendEntry) && reuse && !keep) {
        playerView?.holdFirstFrameAspectRatio()
    }
    // 「已看完」标记只在**正式播放**里清掉。推荐页还没进正片（需求 3：进正片才算开始看），
    // 这时改本机记录就等于「只看了一眼推荐，历史里那部剧的已看完标记就没了」。
    if(!recommendEntry) library.setWatched(data.series.id,false)
    // 推荐流不使用封面：相邻首块已经预热，切换只保留黑场保护直到新视频首帧。
    // 正式播放页仍保留分集封面过渡。
    artwork.cancelPage(clearMemory=false)
    if(!recommendEntry) {
        artwork.prefetchWindow(
            (selectedIndex-2..selectedIndex+10).mapNotNull { at ->
                data.episodes.getOrNull(at)?.let(data::episodeCover)
            }.filter { it.isNotBlank() },
            1920,
            1080,
        )
        showRecommendCover(data.episodeCover(targetId).ifBlank { data.series.cover })
    } else {
        showRecommendCover()
    }
    // 正式播放页先用 player 场景取当前集 + 后 5 集；更远的集在首帧稳定后 preload。
    // 推荐页的 20 个模型在起播 READY 后单独预取，
    // 窗口是「当前剧下一集 + 后 19 部不同剧」，消耗 10 个后补充，不能混进这里。
    val modelQueue=if(recommendEntry || directRequest) emptyList() else data.episodes.drop(selectedIndex+1).take(5)
    // 队列里正好挂着这一集（用户自己按了「下一集」、或点选集切到下一集）：
    // 把它接过来当预热结果，省掉一次取地址。注意队列里那一项是「播完自动推进」用的，
    // 这里要从头重放，所以仍然要重挂一次源，只是不再走网络。
    //
    val fromQueue=queuedEpisodeId==targetId && position==0L && !recovering
    // [prepared] 是调用方**已经预热好**的一份（推荐页上下切换：详情与首集数据都在本地），
    // 所有权当场转移过来，走的是与「预取下一集」同一条无感切集路径。
    val prefetched=when {
        // 同档重建：还是这一集的**同一份**（索引 / 文件头 / 首块都在它里面），
        // 原地接着用。`playbackSource.stream()` 与 `remote.prepare()` 两步因此全部省掉。
        keep -> video
        position!=0L || recovering -> null
        fromQueue -> queuedVideo
        else -> prepared ?: preparedNext.take(preparedKeyFor(targetId))
    }
    val claimedHttp=when {
        // 数据没换，传输也不用换：这条 client 还是这一集的分片读用着的。
        keep -> playbackHttp
        prefetched==null -> null
        fromQueue -> queuedHttp.also { queuedHttp=null }
        prepared!=null -> null
        else -> prefetchHttp.also { prefetchHttp=null }
    }
    // 传进来的预热结果这一下没用上（例如从后台回来按原位置续播）：当场收掉，
    // 否则那 1MB 与它的连接会一直挂着，没人再来取。
    if(prepared!=null && prefetched!==prepared) runCatching { prepared.close() }
    if(fromQueue) { queuedVideo=null; queuedEpisodeId=null; queuedQuality=""; queuedDetail=null; queuedAt=0 }
    // 正式播放的同一部剧分集切换不释放解码器：片源格式一致，直接替换 MediaSource，
    // 让 MediaCodecRenderer 复用现有解码器。目标已经预热时旧一集继续播到新源挂上；
    // 目标未预热时先暂停旧画面并盖过渡层，等取源完成后同样不清播放器。
    //
    // **推荐页换剧不能走这条路**（`!recommendEntry`）：无感切集不会 `stop()`，
    // 也就不会释放 MediaCodec——同一颗解码器要在原地跨剧重配。换的是**另一部片**，
    // 分辨率 / SPS / 码率都与上一部不同，部分电视 SoC 在这种原地重配上只出声音不出画
    //（ExoPlayer 状态照旧是 READY、进度条照走），表现出来就是「刷几部之后黑屏有声，
    // 快进快退也没反应」——拖进度同样要解码器吐帧。
    // 硬切（`stop()` + `clearMediaItems()`）会把解码器放掉再重建，画面必定重出；
    // 数据仍然来自预热缓存，因此不重新取地址、也不会变慢。过渡黑场本来就盖着这一下。
    // 同档重建**必须**走硬切（`!keep`）：这一趟的全部意义就是 `stop()` 把 MediaCodec 放掉。
    // 走无感那条路等于什么都不做，4001 / 4003 的现场原样留着。
    val softSwitch=reuse && !recommendEntry && !keep
    if(softSwitch && prefetched==null) player?.pause()
    // 队列里还挂着别的下一集（换清晰度、跳集）：先摘掉，别让它跟着新源一起进播放器。
    if(!fromQueue) dropQueuedEpisode()
    if(softSwitch || keep) suppressBufferingUi() else restoreBufferingUi()
    // 上一次切集留下的尾巴（正常情况为空：新源出画面时就收掉了，见 [retirePreviousEpisode]）。
    // 这里用强制版：下面马上要整条替换播放列表（setMediaSource / clearMediaItems），
    // 旧项不可能再被读；不强制的话，连续切集会把它顶掉、漏掉一次关闭。
    forceRetirePrevious()
    saveProgress(); library.flush(); invalidatePage(); favoriteMonitor.stop(); releaseCatalogViews()
    dismissPlaybackMenu()
    if(reuse) {
        cancelRecovery(); cancelPrefetch(); tvTools.close()
        episodePanel?.dismiss(); episodePanel=null; speedDialog?.dismiss(); speedDialog=null
        // 错误恢复 / 同档重建才 `stop()`：它卸载媒体、释放 MediaCodec，正是 4001 / 4003 要的。
        //
        // 列表**不清**（同档重建时）：下面 `setMediaSource` 内部第一件事就是
        // `removeMediaSourceHolders(0, size)`（ExoPlayer 2.19 `setMediaSourcesInternal`），
        // 显式 `clearMediaItems()` 只是把同一件事做两遍，中间还多一次空列表的 BUFFERING。
        // 其它路径照旧清：那条路上旧项与 `video` 都要一起放掉。
        if(!softSwitch) {
            // 推荐换作在进入这里之前已经 stop + clear，此时播放器为空且已 IDLE。
            // 不再重复走同一条释放链，避免无意义的渲染器状态切换。
            val p=player
            if(p!=null && (p.mediaItemCount>0 || p.playbackState!=Player.STATE_IDLE)) {
                p.stop()
                if(!keep && p.mediaItemCount>0) p.clearMediaItems()
            }
        }
        // 旧一集的视频与传输先记下来：无感切集不当场关（旧源可能还在收尾，当场关会让它读到
        // 一个已经关闭的视频而报错），等新源出画面再收；非无感路径下面紧接着就收掉。
        //
        // 同档重建例外（`keep`）：`video` / `playbackHttp` **还是这一集的**，
        // 记成「旧一集」会被下面那句 `forceRetirePrevious()` 当场 `close()`——
        // 索引与首块白读一遍，改法也就白改了。
        if(!keep) { retiringVideo=video; retiringHttp=playbackHttp; video=null; playbackHttp=null }
        // 非无感路径刚刚 stop + clearMediaItems，旧项已不可能再被读，直接收。
        if(!softSwitch) forceRetirePrevious()
        else if(prefetched!=null) scheduleRetire()
        cancelOverlayAutoHide(); main.removeCallbacks(seekRunnable); pendingSeek=null
    // `keepSession=true`：这里**不是**离开播放页，而是**重新起播这一集/这一部**。
    // 会话层（有效上限 / 预算 / 手动建议）必须活着穿过这次重建——`replayWithPlan` 刚写进去的
    // 降档上限就在里面，`applyDecoderMode` 写进去的软解档位也在里面。
    // 早先这里清一次，等于把「降档」和「切软解时同时降档」都做成了空操作：
    // 下面读到的 `playbackMaxQuality` 又回到用户设置的那一档，下一发必然再撞同一个失败。
    // 真正离开播放页（`returnToDetail` / 进推荐页 / 换剧）走的是不带这个参数的默认值。
    } else {
        detachPlayerSurface(keepSession=true)
        // 重建播放层会清掉旧的过渡黑场；推荐换作必须在新 Surface 挂上之前重新盖黑，
        // 否则新 Surface 在视频尺寸回调前会先按整屏显示一帧。
        if(recommendEntry) showRecommendCover()
    }
    // 会话号必须在 `detachPlayerSurface()` **之后**取：那条路自己也会自增（作废旧回调），
    // 先取再调会让本次监听器的 `active()` 永远不成立——表现就是首次进播放页
    // 一直「正在缓冲」、进度条拖不动，切一集才恢复。
    //
    // 这一下同时把**状态机**推进到 `PREPARING`（见 `PlaybackMachine`）：会话号、剧集、
    // 起播位置、是否无感切集一次性落地，之后播放器回调只带会话号回来即可。
    val session=playbackCoordinator.open(
        seriesId = detail?.series?.id.orEmpty(),
        episodeId = targetId,
        episodeIndex = episodeIndex,
        resumeAtMs = position.coerceAtLeast(0),
        autoplay = autoplay,
        seamless = softSwitch,
        resetEpisode = !recovering,
    ).toInt()
    playbackHttp=claimedHttp; video=prefetched
    autoRecovery=false; readySince=0; lastEpisodeDuration=0; advancedAt=0
    startPosition=position.coerceAtLeast(0); requestedAutoplay=autoplay
    if(autoplay) sleepStopped=false
    screen=if(recommendEntry) "recommend" else "player"; episodeIndex=selectedIndex
    // 诊断现场：换集就重置。之后解码器名 / 视频格式由 [playbackDiagnostics] 填，
    // 失败时 [reportPlaybackFailure] 原样带走——正式包里 `Log.i` 已被 R8 删掉，这里是唯一证据。
    // 调试注入开关只有一处：出帧的三条证据链（Player 监听 / Analytics 监听 / 逐帧心跳）
    // 全都看它（见 [PlaybackDiag.injectedNoFrame]），注入「有声音无画面」才测得准。
    PlaybackDiag.injectedNoFrame=BuildConfig.DEBUG && DebugFaults.mode(this)==DebugFaults.Mode.STALL_FRAME
    // 起播前就确定「本机没有 HEVC 硬解」：**只提示，不改设置**。
    // 需求口径是「优先硬解，不支持硬解时提示用户换成软解」——替用户静默改档，
    // 用户会以为硬解在跑，而软解也放不动时界面上没有任何线索。
    if(playbackDecoderMode==VideoDecoderMode.HARDWARE && !hardwareHevcAvailable) {
        if(playbackToasts.shown("$targetId:hevc-hw")) {
            runCatching { tvTools.toast(UiText.get(com.hgtv.star.shared.R.string.ui_0140)) }
        }
    }
    // 面板「解码」一栏记**实际生效**的那一档（[playerDecoderMode]）：本机没有视频硬解时
    // 用户设置里的「硬件解码」并没有生效，面板照抄设置会让人以为硬解真的在跑。
    PlaybackDiag.begin(session,data.series.title,targetId,selectedIndex,data.episodes.size,playerDecoderMode().label,"${playbackMaxQuality}P")
    panel=false; playError=false; playbackReady=false; quality=""; pausedForLifecycle=!autoplay
    // 片源尺寸属于「上一集」：新一集要知道自己的尺寸之后才能决定要不要自动全屏，
    // 不清掉的话第一帧出来之前会沿用上一集的比例（竖屏剧之后接横屏剧会先闪一下裁切）。
    videoWidth=0; videoHeight=0
    player?.playWhenReady=requestedAutoplay
    prefetchAttempts=0; prefetchAttemptAt=0
    // 常亮由状态推导（`autoplay=false` 的续播不该钉住屏幕），见 [syncKeepScreenOn]。
    syncKeepScreenOn()
    if(!reuse) {
        buildPlaybackSurface()
        // 首次进入播放页会重建根层，前面挂上的封面会被 removeAllViews 摘掉，这里补挂一次。
        showRecommendCover(
            if(recommendEntry) "" else data.episodeCover(targetId).ifBlank { data.series.cover }
        )
    }
    // 触屏设备：`reuse` 那一路不重建视图，因此这三步不能只放在 `!reuse` 里面——
    // 从推荐页进正片、换清晰度之后的回到播放页都走 `reuse`，漏了就整页点不动。
    // 三处调用都自带「已经有了就直接返回」，重复进播放页是空操作。
    if(touchArmed) { attachTouchLayer(); attachTouchBar(); wireSeekTouch() }
    // 调试面板：整棵树在上面刚被重建过，打开调试模式时在这里重新挂到最上层。
    // 没打开时是空操作（见 `DebugOverlay.kt`）。
    reattachDebugOverlay()
    // 推荐页：底部那块剧名 / 集数 / 操作说明的浮层跟着这一层一起重挂。
    // 换剧、换清晰度、换解码方式都会走到这里，浮层因此始终压在最上层（HUD 在推荐页是收着的）。
    if(recommendEntry) {
        attachRecommendOverlay(data.series)
        // 过渡黑场要压在**新画面之上**（它插在浮层下面），否则重建之后黑场会消失，
        // 露出正在按新比例拉伸的旧画面。
        reattachRecommendCover()
    }
    playbackTitle.text=data.series.title
    // 集数与无感切集共用同一处写法（见 [syncPlaybackEpisodeLabel]）：
    // 两条路各写一份格式，迟早会有一处忘了跟着改。
    syncPlaybackEpisodeLabel(data)
    syncPlaybackInfo()
    // 复用播放器的那条路不会重建播放层：这一集如果换过缩放比例，在这里重新落一次。
    applyVideoScale()
    // 画面模式同理：从设置页回来之后「竖屏短剧全屏」可能刚被改过。
    applyResizeMode()
    loadDanmaku(data.episodes[episodeIndex],data.series.id,clearOnSwitch=clearDanmakuOnSwitch)
    playbackText.text=""
    progressBar.progress=0f; progressBar.bufferedProgress=0f; progressBar.visibility=View.VISIBLE; hidePlaybackOverlay()
    syncBufferingDots()
    controls.removeAllViews(); controls.visibility=View.GONE
    syncMediaSession()
    syncNextSeasonForCurrentEpisode()
    val requestedId=data.episodes[episodeIndex]; val maxQuality=playbackMaxQuality; val ticket=generation
    // 起播分阶段计时：`Playback ready ... elapsedMs` 只给出总时长，看不出钱花在哪儿
    //（取地址 / 拉视频头 / 建播放器 / 等首帧）。调试包里逐个阶段记一行，正式包被 R8 删掉。
    val stageAt=android.os.SystemClock.elapsedRealtime()
    fun stage(name: String) {
        if(BuildConfig.DEBUG) android.util.Log.i("HGTV","Stage $name +${android.os.SystemClock.elapsedRealtime()-stageAt}ms")
    }
    // 新的一次起播：允许（重新）建播放器。上一次 releasePlayer 可能刚把它拆掉（见 [playerDisposed]）。
    playerDisposed=false
    // 解码器清单预热**与网络并行**：它要枚举本机全部解码器（550~600ms），
    // 排进下面这次取地址之前就是白等半秒。放在独占线程上，正好用掉「等网络」的那段时间。
    appScope.player { runCatching { primePlaybackClasses() } }
    // 建播放器也**立刻**派出去，不等 `work` 在 io 池里拿到线程（见 [buildPlayerAsync]）。
    // 它与上面的类预热、下面的取地址三条并行；`work` 里只等它的结果，不重复建。
    val playerBuild=runCatching { buildPlayerAsync() }.getOrNull()
    work({
        // 这一段在跑 = 正在起播（建播放器 / 取播放地址 / 本地准备）。下一集预热
        //（[maybePrefetch]）看到这个标志就让路：两条取地址请求叠在一起会把这一轮两边都
        // 打成空响应（见 ContentRepository.videoModel）。
        //
        // 置位必须在**最外层**、并且用下面那个 `try` 兜住：它是「有起播在跑」的唯一判据，
        // 漏掉任何一条退出路径（提前抛异常、被网络回调取消后重来）都会让它永远挂着，
        // 之后所有下一集预热都被悄悄挡掉。
        val fetchSeq=playbackFetchSeq.incrementAndGet()
        playbackFetching=true
        try {
            // 播放器与「取地址 + 本地准备」并行建：两件互不依赖的活，串行时白等 0.5 秒
            //（见 [warmPlayer]）。它已经不在这条线程上（上面 [buildPlayerAsync] 先派出去了），
            // 这里只等它——等的这段时间如果超过几十毫秒，说明是**主线程**在建视图，
            // 日志与 `Stage playerBuild` 一比就能看出来。
            runCatching { playerBuild?.get(3,java.util.concurrent.TimeUnit.SECONDS) }
            stage("playerBuild")
            // 幂等兜底：3 秒还没建完（或提交失败）就在这条线程上补一次。
            warmPlayer()
            // 这两件都在 `Stage register` 之前，早先把它们算进 register 那一栏，
            // 读日志时看起来像「注册接口慢」。分开记，免得再被误导。
            stage("warmPlayer")
            // 解码器清单 / 提取器 / AES 的一次性初始化（[primePlaybackClasses]）**不在这里做**：
            // 它要枚举本机全部解码器，实测 550~600ms，排在这里等于把取地址推后半秒
            // （日志：`Stage warmPlayer +371ms` → `Stage primeClasses +965ms`）。
            // 现在由 [playbackPrime] 那条线程与网络并行跑，见 `playEpisode` 开头的提交。
            // 播放用的是**平台注册过的**设备身份（本机随机值已经不再参与签名）：
            // 注册还没落地时先补一次，免得取地址落到内置那份被拉黑的档案上。
            if(!library.deviceRegistered()) registerDeviceOnce()
            stage("register")
            // 播放固定按 4 档请求，不再因空响应动态升档。
            stage("deviceLevel ${repository.deviceLevel}")
            // 预热命中这条路不取地址，域名只能从预热结果里读（见 [lastStreamHost]）。
            if(prefetched!=null) lastStreamHost=prefetched.host
            // 同档重建命中：下面整段取地址 + 本地准备都跳过。调试包打一行，
            // 与前面几条 `Stage ...` 一比就能看出这次重建只花了多少。
            if(keep) stage("keepMedia")
            // 取地址走仓库自己的 client（内容接口），**读视频走播放专用传输层**：
            // 后者钉死 HTTP/1.1、进程级共享，切集不再关连接池（见 PlaybackTransport）。
            prefetched ?: run {
                // 只有 HEVC 一种编码，取流只剩「上限之内取最高」这一条规则。
                val streamStarted=PlaybackStartupTrace.begin(
                    "stream",
                    "episode=$requestedId recommend=$recommendEntry",
                )
                val streamInfo=StageDeadline("playback.stream",StartupBudgets.STREAM_MS).use { deadline ->
                    deadline.run {
                        playbackSource.stream(
                            requestedId,
                            maxQuality,
                            detail?.playback ?: com.hgtv.star.core.PlaybackProfile(),
                            queue=modelQueue,
                            direct=directRequest,
                        )
                    }
                }
                PlaybackStartupTrace.done(
                    "stream",
                    streamStarted,
                    "episode=$requestedId quality=${streamInfo.quality}",
                )
                // 记下这一集的视频域名：`prepare()` 里建连失败时 [video] 还是上一集的，
                // 自愈清 DNS 只能按这一条来（见 [TvMainActivity.lastStreamHost]）。
                lastStreamHost=playbackSource.host(streamInfo)
                stage("stream ${streamInfo.quality}")
                // CDN 缺档：实际选中的档位与请求上限不一致时提示一次。
                // 去重键带集与档位：连播一百集时同一部剧的同一个缺档只弹一次，
                // 换一集真的降到别的档位才会再弹（见 [com.hgtv.star.core.ToastDeduper]）。
                if(streamInfo.actualQuality>0 && streamInfo.actualQuality!=maxQuality) {
                    val toastKey="$requestedId:select:${streamInfo.actualQuality}"
                    val text=if(streamInfo.actualQuality<maxQuality)
                        "CDN 没有 ${maxQuality}P，已用 ${streamInfo.actualQuality}P"
                    else "CDN 没有 ≤${maxQuality}P 的片源，已用 ${streamInfo.actualQuality}P 兼容资源"
                    main.post {
                        if(isDestroyed || screen!="player") return@post
                        if(playbackToasts.shown(toastKey)) runCatching { tvTools.toast(text) }
                    }
                }
                // 索引缓存键：**只跟「哪一集 + 实际档位」有关**，与播放地址无关。
                // 地址不按时间主动失效；索引只要文件没换就一直有效（见 [PlaybackCache]）。
                //
                // 用**实际档位**而不是请求上限：CDN 在「只有兼容 1080」与「有 720」之间变动时，
                // 请求上限没变、文件却换了。按上限做键的话，旧索引会撞上新文件，
                // 只能靠 `VideoChangedException` 兜一次（白等一轮）才自愈。
                // 「取地址」与「本地准备」分开包：前者失败是没有资源 / 没有可用清晰度，
                // 后者是容器不认、索引不在文件头、加密索引读不出来——后者与设备无关，
                // 但界面上同样说「解码失败」。换成 [PlaybackPrepareException]，上报里就能分开。
                val prepareStarted=PlaybackStartupTrace.begin(
                    "prepare",
                    "episode=$requestedId recommend=$recommendEntry",
                )
                val remote=try {
                    StageDeadline("playback.prepare",StartupBudgets.PREPARE_MS).use { deadline ->
                        deadline.run {
                            playbackSource.prepare(requestedId,streamInfo) {
                                // 调试包的故障注入：本地准备阶段直接失败（见 [DebugFaults]）。
                                if(BuildConfig.DEBUG && DebugFaults.mode(this@playEpisode)==DebugFaults.Mode.PREPARE_FAIL) {
                                    throw PlaybackPrepareException("调试注入：视频索引不可用")
                                }
                            }
                        }
                    }
                } catch(e: PlaybackPrepareException) {
                    throw e
                } catch(e: StreamExpiredException) {
                    // 服务端明确拒绝这条地址：原样抛出去，让外面换成新地址重来一次
                    // （见 [playerFailure] 里的 `streamExpiredOf`）。
                    throw e
                } catch(e: VideoChangedException) {
                    // 这一集的文件换过了（旧索引 / 旧首块对不上新文件）：同样要原样抛出去，
                    // 由 [playerFailure] 丢掉索引重来一次——包进 [PlaybackPrepareException] 之后
                    // 只会在界面上变成一句「本地准备失败」，救不回来。
                    throw e
                } catch(e: java.io.IOException) {
                    // 网络层没通（DNS 解析不出来 / 连接超时 / 连不上）：这不是「本地准备失败」，
                    // 包成 [PlaybackPrepareException] 会把上报阶段记成 `playback_prepare`，
                    // 下一步的排查方向就全错了（现场那次真因是视频 CDN 域名解析不出来）。
                    // 原样抛出，让它落到 `playback_io`，并由 [playerFailure] 换一条地址重来。
                    if(networkFailureOf(e)!=null) throw e
                    throw PlaybackPrepareException(e.message ?: "播放准备失败",e)
                } finally {
                    PlaybackStartupTrace.done(
                        "prepare",
                        prepareStarted,
                        "episode=$requestedId",
                    )
                }
                stage("prepare ${remote.total/1024}KB")
                remote
            }
        } finally {
            // 只由**这一次**起播来放下这个标志。
            //
            // 网络刚恢复时 [refetchAddressOnNetwork] 会把在途那一发作废（`generation++`）并
            // 立刻重来一次；旧那一发被取消后，`finally` 紧跟着就会跑到这里。若无条件清零，
            // 它会把**新**那一发的「正在取地址」一并抹掉：`maybePrefetch` 于是以为没人取地址、
            // 叠上第二条并发请求（这条接口对并发极敏感），下一次网络回调也认不出「正卡在取地址」。
            // 判据用 [playbackFetchSeq] 而不是 `generation`：后者在「退到后台 / 返回详情页」
            // 那种单纯取消时也会变，而那时并没有新的起播接手，标志必须照常放下。
            if(playbackFetchSeq.get()==fetchSeq) playbackFetching=false
        }
    }, { remote ->
        stage("workDone")
        // 播放器已被彻底拆除（Activity 销毁 / 清空数据）：这一集不再起播。
        // 否则下面那次补建会造出一个没有页面、也没人释放的播放器（解码器与音频输出都留着）。
        if(playerDisposed) { runCatching { remote.close() }; return@work }
        video=remote; quality=remote.info.quality; PlaybackDiag.quality=quality
        // 正常情况这一步在 io 线程的 [warmPlayer] 已经建好（并行那半秒）；
        // 没建起来（例如播放器被解码方式切换清掉）就当场补一次。
        val p=player ?: synchronized(playerBuildLock) {
            player ?: resolveTvBufferProfile().let { profile ->
                buildPlayer(playbackDecoderMode,profile).also {
                    player=it
                    builtPlayerDecoderMode=playbackDecoderMode
                    builtPlayerBufferProfile=profile
                }
            }
        }
        // 构造后的配置（音频属性 / 诊断监听 / 逐帧心跳）只能在主线程做，且是幂等的：
        // 预热线程那边 post 过来的那次可能还没跑到（见 [configurePlayer]）。
        configurePlayer(p)
        // 复用那条路不重建播放器，也不重建 PlayerView。推荐换作期间绑定关系保持不动，
        // 只让黑色快门遮住旧内容，等这一部真实首帧后再恢复 keep-content。
        // 这里的 attach 只服务首次进入、重建播放层或解码器换档，正常换作不会走到。
        playerView?.setKeepContentOnPlayerReset(!recommendEntry)
        playerView?.visibility=View.VISIBLE
        if(playerView?.player!==p) attachPlayerView(p)
        p.setPlaybackSpeed(library.playbackSpeed)
        stage("player")
        ensureStablePlayerListener(p)
        val bound=PlaybackBinding(
            player=p,
            generation=ticket,
            session=session,
            mediaId=requestedId,
            recommendEntry=recommendEntry,
            playbackStarted=playbackStarted,
            prefetched=prefetched!=null,
            reuse=reuse,
        )
        playbackBinding=bound
        playerView?.setFirstFrameAspectRatioAppliedListener {
            if(screen=="recommend") playbackBinding?.let(::revealRecommendWhenReady)
        }
        val source=ProgressiveMediaSource.Factory { VideoDataSource(remote) }.createMediaSource(MediaItem.Builder().setMediaId(requestedId).setUri("hgtv://episode/$requestedId").setMimeType(MimeTypes.VIDEO_MP4).build())
        // 正式播放换集：PlayerView 先冻结旧比例，等新首帧真的上屏后再应用新比例。
        if(screen=="player" || screen=="recommend") {
            if(BuildConfig.DEBUG) android.util.Log.i(
                "HGTV-RATIO",
                "sourceSet screen=$screen episode=${episodeIndex+1} reuse=$reuse soft=$softSwitch " +
                    "view=${playerView?.javaClass?.simpleName} " +
                    "source=${remote.info.videoWidth}x${remote.info.videoHeight}"
            )
            playerView?.holdFirstFrameAspectRatio()
        }
        // 起点一次设定：`setMediaSource(source, startPositionMs)` 内部先摘空旧项、
        // 再按给定位置掩码时间线，等价于「`setMediaSource` + `seekTo`」，少一次
        // `onPositionDiscontinuity`（那条回调会连带刷新媒体会话与弹幕指针）。
        // 旧帧心跳清到 0，并以这一刻作为新片源首帧的下限；否则 READY 兜底可能提前揭黑。
        // 尺寸也在这里重新归零：监听器已经挂上，旧片源的迟到尺寸回调不能算到新片源。
        videoWidth=0; videoHeight=0
        bound.videoSizeReady=false; bound.firstFrameRendered=false
        PlaybackDiag.lastFrameAt=0L
        bound.sourceSetAt=android.os.SystemClock.elapsedRealtime()
        if(softSwitch && prefetched==null) scheduleRetire()
        p.setMediaSource(source,position.coerceAtLeast(0))
        p.prepare()
        p.playWhenReady=foreground && requestedAutoplay && !pausedForLifecycle
        stage("sourceSet")
        if(BuildConfig.DEBUG && recommendSwitchStartedAt>0L) {
            android.util.Log.i(
                "HGTV-SWITCH",
                "source-set +${android.os.SystemClock.elapsedRealtime()-recommendSwitchStartedAt}ms "+
                    "episode=${episodeIndex+1}",
            )
        }
        // 起播看门狗：兜住「READY 了但一帧都没出」的黑屏——那条路不抛异常，进不了 [playerFailure]。
        if(autoplay) armPlaybackWatchdog(session)
        // 停帧看门狗：起播正常、播着播着不再出帧（自动连播几集后最常见的那种黑屏）。
        armFrameStallWatchdog(session)
        syncDanmakuClock()
    }, { problem ->
        playerFailure(problem)
    })
}
