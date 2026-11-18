// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

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
private fun TvMainActivity.scheduleRecommendAfterFirstFrame() {
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
    // 设置页改档不会经过 [applyDecoderMode]；进播放前发现实例档位不一致就先放掉。
    releasePlayerForDecoderMismatch()
    // 首帧 EWMA 更新后，下一次起播按新的缓冲档位重建；当前这一集不打断。
    releasePlayerForBufferMismatch()
    // `keepMedia`（同档重建）也要保住播放层：这一趟只为把解码器放掉重开，画面 / 弹幕 /
    // 控制层一个都不用换（见 [replayWithPlan]）。它和 `recovering` 是两件事——
    // 后者还包含降档、网络自愈、手动重试，那些真的需要重来一遍。
    val reuse=(screen=="player" || recommendEntry) && player!=null && playerView!=null && (!recovering || keepMedia) && !playError
    // 「这一集的数据也一并留着」：只有**还能用**的那一份才算数（已经 prepare 过、且没被
    // close）。预热被换出 / 已经关掉时读不出数据，必须落回「重新取地址 + 本地准备」那条路。
    // 此时视图仍然复用（`reuse` 看的是 `keepMedia`）——那一步不需要重建整棵树。
    val keep=keepMedia && video?.isReusable==true
    // 必须先冻结，再 stop/clearMediaItems：停旧媒体会让 PlayerView 收到一次 ratio=0，
    // 如果这时还没 hold，AspectRatioFrameLayout 会立刻撑满整屏，用户看到的就是拉伸首帧。
    if((screen=="player" || recommendEntry) && reuse && !keep) {
        // 上一集首帧的静帧先盖住 PlayerView；Surface 被 stop 清掉后不会露出黑快门。
        playerView?.showPlaybackStillFrame()
        playerView?.holdFirstFrameAspectRatio()
    }
    // 「已看完」标记只在**正式播放**里清掉。推荐页还没进正片（需求 3：进正片才算开始看），
    // 这时改本机记录就等于「只看了一眼推荐，历史里那部剧的已看完标记就没了」。
    if(!recommendEntry) library.setWatched(data.series.id,false)
    val selectedIndex=index.coerceIn(0,data.episodes.lastIndex)
    val targetId=data.episodes[selectedIndex]
    // 正式播放页先用 player 场景取当前集 + 后 5 集；更远的集在首帧稳定后 preload。
    // 推荐页的 20 个模型在起播 READY 后单独预取，
    // 窗口是「当前剧下一集 + 后 19 部不同剧」，消耗 10 个后补充，不能混进这里。
    val modelQueue=if(recommendEntry || directRequest) emptyList() else data.episodes.drop(selectedIndex+1).take(5)
    // 队列里正好挂着这一集（用户自己按了「下一集」、或点选集切到下一集）：
    // 把它接过来当预热结果，省掉一次取地址。注意队列里那一项是「播完自动推进」用的，
    // 这里要从头重放，所以仍然要重挂一次源，只是不再走网络。
    //
    // **地址有年龄上限**（见 [com.hgtv.star.core.PlaybackCache.PREPARED_FRESH_MS]）：
    // 队列项里装的是带时效的播放地址，而它可能在播放列表里躺了很久——用户在片尾暂停、
    // 去倒了杯水回来再按「下一集」，或者干脆停在那一集上没动。超过 10 分钟就当作没有预热，
    // 丢掉队列走「重新取地址」那条路（多花半秒，好过拿一条已作废的地址撞 403）。
    //
    // 不这么判的现场表现就是：播几集后按「下一集」，界面报
    // 「地址过期：播放地址已失效（HTTP 403）」；退出播放页再进又好了（队列被清掉）。
    val queueFresh=com.hgtv.star.core.PlaybackCache.isPreparedFresh(
        queuedAt,android.os.SystemClock.elapsedRealtime())
    val fromQueue=queuedEpisodeId==targetId && position==0L && !recovering && queueFresh
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
    // 无感切集：同一个播放器复用、目标是下一集的开头、而且下一集**已经预热好**
    //（播放地址 / moov / 首批数据都在本地，见 [maybePrefetch]）。
    // 这时切集不再走「停播放器 → 清空 → 重新取地址 → 重建画面」那条路：
    // 不清播放器（`stop()` / `clearMediaItems()` 会把画面掉到黑屏，编解码器还要重建一次），
    // 不显示缓冲转圈，旧一集的视频等新源出画面之后再收。
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
    val seamless=reuse && prefetched!=null && !recommendEntry && !keep
    // 队列里还挂着别的下一集（换清晰度、跳集）：先摘掉，别让它跟着新源一起进播放器。
    if(!fromQueue) dropQueuedEpisode()
    if(seamless || keep) suppressBufferingUi() else restoreBufferingUi()
    // 上一次切集留下的尾巴（正常情况为空：新源出画面时就收掉了，见 [retirePreviousEpisode]）。
    // 这里用强制版：下面马上要整条替换播放列表（setMediaSource / clearMediaItems），
    // 旧项不可能再被读；不强制的话，连续切集会把它顶掉、漏掉一次关闭。
    forceRetirePrevious()
    saveProgress(); library.flush(); invalidatePage(); favoriteMonitor.stop(); releaseCatalogViews()
    dismissPlaybackMenu()
    if(reuse) {
        cancelRecovery(); cancelPrefetch(); tvTools.close()
        episodePanel?.dismiss(); episodePanel=null; speedDialog?.dismiss(); speedDialog=null
        playerListener?.let { player?.removeListener(it) }; playerListener=null
        // `stop()` 是这一步唯一必须的：它卸载媒体、释放 MediaCodec，正是 4001 / 4003 要的。
        //
        // 列表**不清**（同档重建时）：下面 `setMediaSource` 内部第一件事就是
        // `removeMediaSourceHolders(0, size)`（ExoPlayer 2.19 `setMediaSourcesInternal`），
        // 显式 `clearMediaItems()` 只是把同一件事做两遍，中间还多一次空列表的 BUFFERING。
        // 其它路径照旧清：那条路上旧项与 `video` 都要一起放掉。
        if(!seamless) { player?.stop(); if(!keep) player?.clearMediaItems() }
        // 旧一集的视频与传输先记下来：无感切集不当场关（旧源可能还在收尾，当场关会让它读到
        // 一个已经关闭的视频而报错），等新源出画面再收；非无感路径下面紧接着就收掉。
        //
        // 同档重建例外（`keep`）：`video` / `playbackHttp` **还是这一集的**，
        // 记成「旧一集」会被下面那句 `forceRetirePrevious()` 当场 `close()`——
        // 索引与首块白读一遍，改法也就白改了。
        if(!keep) { retiringVideo=video; retiringHttp=playbackHttp; video=null; playbackHttp=null }
        // 非无感路径刚刚 stop + clearMediaItems，旧项已不可能再被读，直接收。
        if(!seamless) forceRetirePrevious() else scheduleRetire()
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
        seamless = seamless,
        resetEpisode = !recovering,
    ).toInt()
    artwork.cancelPage(clearMemory=true)
    playbackHttp=claimedHttp; video=prefetched
    var firstReady=true; var firstFrame=true
    // 新片源真正 setMediaSource 的时刻：READY 后的兜底只能认这之后的新帧，
    // 不能把上一部残留的逐帧心跳当成当前作品已经出画面。
    var sourceSetAt=0L
    var videoSizeReady=false
    var firstFrameRendered=false
    var recommendRevealed=false
    fun revealRecommendWhenReady() {
        if(screen!="recommend" || recommendRevealed || !videoSizeReady) return
        val frameReady=firstFrameRendered ||
            (sourceSetAt>0L && PlaybackDiag.lastFrameAt>sourceSetAt)
        if(!frameReady) return
        recommendRevealed=true
        // 尺寸回调只代表播放器知道了比例，PlayerView 的 AspectRatioFrameLayout
        // 还要等下一次布局才会把画面框到正确大小。延后一帧揭黑，避免用户先看到
        // “整屏拉伸”再恢复成正确比例。
        val coverToken=recommendCoverToken
        main.post {
            if(!isDestroyed && screen=="recommend" && recommendCoverToken==coverToken) {
                revealRecommendCover { if(!isDestroyed) hideCenterLoading() }
            }
        }
    }
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
            runCatching { tvTools.toast("本机没有 HEVC 硬解，可在播放设置里切换为软件解码") }
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
            // 播放默认按 4 档请求；取地址层遇到空响应会升到 5 再试一次。
            // 故障重来也保持同一档，避免缓存键和请求参数无意义地变化。
            if(!recovering) repository.beginEpisode()
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
                // 地址 20 分钟换一次，索引只要文件没换就一直有效（见 [PlaybackCache]）。
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
                    // 落盘地址过期：这条要原样抛出去，让外面换成新地址重来一次
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
        // 复用那条路不重建播放器，也不重建 PlayerView：这里补一次绑定是幂等的。
        // 推荐换作期间 PlayerView 会暂时 detach / INVISIBLE，等新源准备好后再恢复。
        playerView?.setKeepContentOnPlayerReset(true)
        playerView?.visibility=View.VISIBLE
        if(playerView?.player!==p) attachPlayerView(p)
        p.setPlaybackSpeed(library.playbackSpeed)
        stage("player")
        val listener=object: Player.Listener {
            // 片尾自动推进之后 mediaId 会变成下一集，因此这里不再拿它做判据；
            // 但也不能什么判据都不留：用播放会话号，挡掉上一次 `playEpisode` 遗留的回调。
            fun active()=player===p && generation==ticket && session==playbackSession
            override fun onPlaybackStateChanged(state: Int) {
                if(!active()) return
                syncBufferingDots()
                // 状态机是唯一事实来源：所有阶段迁移都从这里进（`playbackReady` 只是它的投影）。
                playback.onPlayerState(state, p.playWhenReady)
                if(state==Player.STATE_READY) {
                    PlaybackDiag.tracksSelected=p.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
                    if(!PlaybackDiag.tracksSelected) {
                        playerFailure(PlaybackException("资源没有可解码的视频轨道",null,PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
                        return
                    }
                    playbackReady=true
                    syncBufferingDots()
                    // 从推荐页进正片时这一集还没 READY，历史由这里补写（见 [enterFormalPlayback]）。
                    if(recommendHistoryPending) saveProgress()
                    // 起播已经稳了：高端电视趁用户还没按菜单键，把播放设置面板先建好、画一遍
                    //（见 [warmPlaybackMenu]）。低配电视跳过这段主线程预热，首开菜单再按需构建。
                    if(!runCatching { lowEndTv() }.getOrDefault(true)) warmPlaybackMenu()
                    // READY 只代表解出了轨道，不能保证画面已经上屏；相邻预热、
                    // 批量模型和推荐补页统一由 `scheduleRecommendAfterFirstFrame` 在
                    // 真正出帧后启动。这里只保留曝光兜底。
                    if(screen=="recommend") {
                        // READY 是首帧回调缺失时的兜底；真正的首次曝光在 onRenderedFirstFrame
                        // 里再提交一次，由共用上报器按短时间去重。
                        reportRecommendImpression()
                        scheduleRecommendAfterFirstFrame()
                    }
                    // 正式播放页：首帧 READY 后还要再等缓冲填起来，才让 preload 去拉后 6 集。
                    // 这样 player 场景已经保住当前集 + 后 2 集，后台补货不会和首帧抢闸门。
                    if(screen=="player") {
                        main.postDelayed({
                            val current=player
                            if(!isDestroyed && screen=="player" && current===p &&
                                p.isPlaying && p.bufferedPosition-p.currentPosition>=3_000L) {
                                prefetchFormalBatchModels()
                            }
                        },10_000L)
                    }
                    // 推荐页真的出画面了：连续失败计数归零（取地址成功不算，能放才算）。
                    if(screen=="recommend") {
                        recommendPlaybackFailures=0
                        // 过渡黑场的兜底出口：复用播放器时个别设备不会再报「首帧」，
                        // 只等那一个回调会让黑场一直挂到 2.5 秒的上限。必须等逐帧心跳
                        // 确认真的出过画面，不能只凭 READY 放掉黑场。
                        val coverToken=recommendCoverToken
                        main.postDelayed({
                            if(!isDestroyed && screen=="recommend" && recommendCoverToken==coverToken) {
                                if(!firstFrameRendered && sourceSetAt>0L &&
                                    PlaybackDiag.lastFrameAt>sourceSetAt) {
                                    firstFrameRendered=true
                                }
                                revealRecommendWhenReady()
                            }
                        },400)
                    }
                    // 不自动播的起播（用户进来先按了暂停、或从后台回来）也要有人盯着黑屏：
                    // 上面那次 arm 只在 autoplay 时发生，这里补一次，已有就不动。
                    // 判据是「有没有画面」而不是只认 `onRenderedFirstFrame`：
                    // 隧道模式与个别老系统不回调那个方法，只认它会把正常播放误判成黑屏。
                    if(playbackWatchdog==null && !pictureUpNow()) armPlaybackWatchdog(session)
                    lastEpisodeDuration=p.duration.coerceAtLeast(0)
                    // 新源已经出画面：这时候再收上一集的视频与传输最稳，顺手把缓冲指示恢复。
                    markAutoTransitionReady(p.currentMediaItem?.mediaId.orEmpty())
                    confirmPlayedItemRemoved()
                    retirePreviousEpisode(); restoreBufferingUi()
                    if(firstReady) { firstReady=false; readySince=android.os.SystemClock.elapsedRealtime(); android.util.Log.i("HGTV","Playback ready episode=${episodeIndex+1} preloaded=${prefetched!=null} reused=$reuse elapsedMs=${readySince-playbackStarted}") }
                    if(hud.visibility==View.VISIBLE) updatePlaybackText()
                }
                syncMediaSession()
                if(state==Player.STATE_ENDED) {
                    // 推荐页：无干预播完一集 → 自动进入正式播放界面，并从下一集接着播（需求 4）。
                    // 这里不写历史（`saveProgress` 在推荐页是空操作），进正片那一步才写。
                    if(screen=="recommend") {
                        main.post { if(!isDestroyed && screen=="recommend") enterFormalPlayback(advanceNext=true) }
                        return
                    }
                    // 队列里还挂着下一集却没自动推进 = 下一集没接上。这里**不自己再切一次**：
                    // 同一次故障的 `onPlayerError` 可能已经（或即将）走到 [playerFailure]，
                    // 两处都切就是重复切换。统一交给 [playerFailure]，它带互斥窗与次数上限。
                    val hasQueuedItem=runCatching {
                        val current=p.currentMediaItemIndex.coerceAtLeast(0)
                        p.mediaItemCount>current+1
                    }.getOrDefault(false)
                    val queuedSeason=queuedDetail
                    if(queuedSeason!=null && queuedEpisodeId!=null && hasQueuedItem) {
                        android.util.Log.w("HGTV","Queued next season did not advance; reopening it")
                        dropQueuedEpisode()
                        main.post { if(!isDestroyed && screen=="player") enterNextSeason(queuedSeason) }
                        return
                    }
                    if(queuedEpisodeId!=null && hasQueuedItem) {
                        playerFailure(PlaybackException("队列中的下一集没有接上",null,PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
                        return
                    }
                    // 标志残留但播放器实际已经没有下一项：清掉元数据后继续走
                    // 下一季 / 全剧结束，不能把最后一集卡在 STATE_ENDED。
                    if(queuedEpisodeId!=null && !hasQueuedItem) dropQueuedEpisode()
                    saveProgress(true)
                    // 「连着看」：还在前台、没有因为息屏 / 切后台被暂停、用户也没按过暂停。
                    val continuous=foreground && requestedAutoplay && !pausedForLifecycle
                    if(sleepTimer.episodeEnded()) stopForSleep()
                    else if(continuous && library.autoNext &&
                        detail?.let { episodeIndex<it.episodes.lastIndex }==true) {
                        playEpisode(
                            episodeIndex+1,
                            autoplay=requestedAutoplay,
                            direct=false,
                        )
                    }
                    else if(continuous && detail?.let { episodeIndex>=it.episodes.lastIndex }==true) {
                        detail?.series?.let { advanceToNextSeasonOrFinish(it) }
                    }
                    else { requestedAutoplay=false; p.pause(); playbackText.text="本集已结束"; showHud() }
                }
            }
            /**
             * 片尾自动推进：ExoPlayer 把 playing period 换成队列里的下一集。
             *
             * 这条回调里**不能**调 `playEpisode`：那会把刚换上的源又 reset 一遍，
             * 等于把无感切换重新做成了有感的。这里只交接状态。
             */
            override fun onMediaItemTransition(mediaItem: MediaItem?,reason: Int) {
                if(!active()) return
                if(reason!=Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) return
                val mediaId=mediaItem?.mediaId.orEmpty()
                val currentDetail=detail ?: return
                val seasonTarget=queuedDetail?.takeIf {
                    episodeIndex==currentDetail.episodes.lastIndex &&
                        it.episodes.firstOrNull()==mediaId
                }
                if(seasonTarget!=null) {
                    if(queuedEpisodeId!=mediaId || queuedVideo==null) {
                        android.util.Log.w(
                            "HGTV",
                            "Next season transition mismatch: media=$mediaId queued=$queuedEpisodeId " +
                                "video=${queuedVideo!=null}; rebuilding"
                        )
                        cancelAutoTransitionCleanup()
                        main.post {
                            if(!active()) return@post
                            runCatching { p.stop(); p.clearMediaItems() }
                            cancelPrefetch()
                            dropQueuedEpisode()
                            forceRetirePrevious()
                            enterNextSeason(seasonTarget)
                        }
                        return
                    }
                    // 跨季会更换播放模型，部分设备仍会原地重配解码器并短暂进入 BUFFERING。
                    // 用比同季更长的首帧窗口压住缓冲圈；READY / 出错都会提前恢复。
                    suppressBufferingUi(4000L)
                    onSeasonAdvanced(seasonTarget,mediaId)
                    main.postDelayed({ if(!isDestroyed) maybeQueueNext() },3000)
                    return
                }
                val next=currentDetail.episodes.indexOf(mediaId)
                if(next<0 || next==episodeIndex) return
                // 预取队列与播放器实际切换项不一致：不能再相信 `queuedVideo`，
                // 先停掉当前错误项，再走一次完整的 `setMediaSource` 重建。
                if(queuedEpisodeId!=mediaId || queuedVideo==null) {
                    android.util.Log.w(
                        "HGTV",
                        "Auto transition mismatch: media=$mediaId queued=$queuedEpisodeId " +
                            "video=${queuedVideo!=null}; rebuilding"
                    )
                    cancelAutoTransitionCleanup()
                    main.post {
                        if(!active()) return@post
                        runCatching { p.stop(); p.clearMediaItems() }
                        cancelPrefetch()
                        dropQueuedEpisode()
                        forceRetirePrevious()
                        playEpisode(next,0,requestedAutoplay,direct=false)
                    }
                    return
                }
                onEpisodeAdvanced(next,mediaId)
                // 队列空了：为新的这一集再准备下一集。等这一集 READY 再挂，
                // 免得两个 period 同时抢加载带宽。
                main.postDelayed({ if(!isDestroyed) maybeQueueNext() },3000)
            }
            /**
             * 片源尺寸：**竖屏短剧全屏的判据之一**（另一个是屏幕尺寸，见 [autoFullscreenRotated]）。
             *
             * 换集、换清晰度、软解 / 硬解切换都会重新回调；尺寸没变时也同样会回调，
             * 因此这里在尺寸没变时也重算一次（屏幕可能刚转过来），但不重建任何视图。
             */
            override fun onVideoSizeChanged(videoSize: com.google.android.exoplayer2.video.VideoSize) {
                if(!active()) return
                // 像素比不是 1 的片源（老盒子上很常见）：按显示尺寸算比例才是用户看到的形状。
                val ratio=if(videoSize.pixelWidthHeightRatio>0f) videoSize.pixelWidthHeightRatio else 1f
                // 带旋转元数据的片源（手机竖拍的常见形态）：解码器还没把那个角度转过来，
                // 因此这里按「转过来之后」的宽高记，判据才不会反过来。
                val swapped=videoSize.unappliedRotationDegrees==90 || videoSize.unappliedRotationDegrees==270
                val rawW=if(swapped) videoSize.height else videoSize.width
                val rawH=if(swapped) videoSize.width else videoSize.height
                videoWidth=Math.round(rawW*ratio)
                videoHeight=rawH
                videoSizeReady=videoWidth>0 && videoHeight>0
                if(BuildConfig.DEBUG) android.util.Log.i(
                    "HGTV-RATIO",
                    "videoSize=${videoSize.width}x${videoSize.height} applied=${videoWidth}x$videoHeight " +
                        "firstFrame=$firstFrameRendered view=${playerView?.javaClass?.simpleName}"
                )
                applyResizeMode()
                if(videoSizeReady && !firstFrameRendered && sourceSetAt>0L &&
                    PlaybackDiag.lastFrameAt>sourceSetAt) {
                    firstFrameRendered=true
                    playerView?.completeFirstFrameAspectRatio()
                }
                revealRecommendWhenReady()
            }
            override fun onRenderedFirstFrame() {
                if(!active()) return
                firstFrameRendered=true
                if(BuildConfig.DEBUG) android.util.Log.i(
                    "HGTV-RATIO",
                    "renderedFirstFrame view=${playerView?.javaClass?.simpleName} video=${videoWidth}x$videoHeight"
                )
                val waitingForAspectLayout=playerView?.completeFirstFrameAspectRatio()==true
                if(!waitingForAspectLayout) playerView?.clearPlaybackFreezeFrame()
                // 首帧回调当下 Surface 可能还没真正合成出像素；稍后再取静帧，
                // 否则会把一张全黑图缓存成下一次换源的过渡画面。
                val captureView=playerView
                main.postDelayed({
                    if(active() && firstFrameRendered && captureView===playerView) {
                        captureView?.capturePlaybackStillFrame()
                    }
                },600L)
                PlaybackStartupTrace.firstFrame(
                    "tv.player",
                    "recommend=$recommendEntry preloaded=${prefetched!=null} reused=$reuse",
                )
                markAutoTransitionFirstFrame(p.currentMediaItem?.mediaId.orEmpty())
                // 首帧的尺寸已经确定：竖屏短剧全屏在这之后再落一次
                //（`onVideoSizeChanged` 在个别软解 / 隧道模式下不一定先于首帧回调，这里兜一次底）。
                applyResizeMode()
                // 推荐页：新一集的第一帧真的上屏了，过渡黑场这时候才淡出（见 [revealRecommendCover]）。
                // 用「出帧」而不是 READY：READY 只代表解出了轨道，画面可能还要等几百毫秒。
                if(screen=="recommend") {
                    reportRecommendImpression()
                    // 黑场淡出完成后再撤中心 loading，避免用户先看到一段黑场。
                    revealRecommendWhenReady()
                    // 真正的首帧已经上屏：现在才允许相邻预热和推荐补页。
                    scheduleRecommendAfterFirstFrame()
                }
                // 出帧即撤看门狗：这是「画面真的上屏了」的硬证据（READY 只代表解出了轨道）。
                // 同时把面板上「没有画面」那条现场清掉，否则用户会看到「有画面却说我黑屏」
                //（见 [PlaybackDiag.pictureUp]）。调试注入 STALL_FRAME 时它返回 false，
                // 看门狗照旧挂着——那条上报才测得了。
                if(PlaybackDiag.pictureUp()) disarmPlaybackWatchdog()
                // 换集之后起播看门狗会在 `onEpisodeAdvanced` 重新挂；停帧看门狗同理，
                // 这里顺手把「这一集已经出过帧」的基线交回给它自己（lastFrameAt 由心跳刷新）。
                if(playbackStallWatchdog==null) armFrameStallWatchdog(session)
                // 这一集先前报过失败、现在真的出画面了：降级 / 重试救回来了，记一笔正样本。
                // 它不是错误，**不发异常**（否则会把「救回来」也算成故障）；只写 logcat，
                // 排查时与失败那条的 stage / 时间点对得上就够了。
                // `cap` / `plan` 是「降级救回率」的另一半：与 `degrade reason=… target=…`
                // 那一行成对出现，就能数出每个档位救回了几次、`QUALITY_DOWN_LIMIT` 够不够。
                if(PlaybackDiag.takeRecovered()) android.util.Log.i("HGTV-PLAY","recovered mode="+
                    (if(playbackDecoderMode==VideoDecoderMode.SOFTWARE) "sw" else "hw")+
                    " cap=${playbackMaxQuality}P plan=${playbackSessionState.lastReason?.name ?: "-"}"+
                    " codec=${PlaybackDiag.codecFamily} res=${PlaybackDiag.resolution}"+
                    " dev=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                syncDanmakuClock()
                // 推荐页：时长一确定就把进度条右侧那段时间填上，不等下一次每秒 tick——
                // 否则刚打开的那一两秒是「--:-- / --:--」，与点击唤出来的那一版不一致。
                if(screen=="recommend") updateRecommendOverlay()
                if(firstFrame) {
                    firstFrame=false
                    val elapsed=android.os.SystemClock.elapsedRealtime()-playbackStarted
                    recordFirstFrameForBuffer(elapsed)
                    android.util.Log.i("HGTV","Playback first frame episode=${episodeIndex+1} preloaded=${prefetched!=null} reused=$reuse elapsedMs=$elapsed")
                }
                // 片尾自动推进之后的第一帧：这是「无感」真正的验收数字。
                // 它包含换 period + 新 period 起播的时间，不含重新取地址（那是老路才有的事）。
                if(advancedAt>0) {
                    val gap=android.os.SystemClock.elapsedRealtime()-advancedAt
                    advancedAt=0
                    android.util.Log.i("HGTV","Seamless first frame episode=${episodeIndex+1} afterMs=$gap")
                }
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                if(!active()) return
                // 播放 / 暂停本身就是一次「操作」：暂停之后控制条同样要按设置里的秒数收起
                //（这里走 `showHud()`，它与唤出进度条走的是同一个计时器，见
                // `TvMainActivity.scheduleOverlayAutoHide`）。控制条收着时这一支是空操作。
                if(hud.visibility==View.VISIBLE) showHud()
                // 播放 / 暂停是「屏幕该不该常亮」的直接判据（见 [syncKeepScreenOn]）。
                syncKeepScreenOn()
                syncMediaSession()
                // 弹幕时钟跟着播放状态起停：暂停时停在当前帧，恢复播放时重新校准。
                syncDanmakuClock()
            }
            override fun onPlaybackParametersChanged(parameters: PlaybackParameters) {
                if(!active()) return
                if(com.hgtv.star.tv.BuildConfig.DEBUG) android.util.Log.i(
                    "HGTV",
                    "Speed changed to ${parameters.speed} media=${p.currentMediaItem?.mediaId} at=${android.os.SystemClock.elapsedRealtime()}"
                )
                syncMediaSession()
                // 倍速变了：外推的斜率要换，立刻按新倍速重新校准。
                syncDanmakuClock()
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,newPosition: Player.PositionInfo,reason: Int) {
                if(!active()) return
                syncMediaSession()
                // 跳转之后弹幕要跟着画面走：指针从头重找一次，再画一帧。
                danmakuView?.resetCursor(); syncDanmakuClock()
            }
            override fun onPlayerError(error: PlaybackException) { if(active()) playerFailure(error) }
            override fun onTimelineChanged(
                timeline: com.google.android.exoplayer2.Timeline,
                reason: Int
            ) {
                if(active()) confirmPlayedItemRemoved()
            }
        }
        playerListener=listener; p.addListener(listener)
        val source=ProgressiveMediaSource.Factory { VideoDataSource(remote) }.createMediaSource(MediaItem.Builder().setMediaId(requestedId).setUri("hgtv://episode/$requestedId").setMimeType(MimeTypes.VIDEO_MP4).build())
        // 正式播放换集：PlayerView 先冻结旧比例，等新首帧真的上屏后再应用新比例。
        if(screen=="player" || screen=="recommend") {
            if(BuildConfig.DEBUG) android.util.Log.i(
                "HGTV-RATIO",
                "sourceSet screen=$screen episode=${episodeIndex+1} reuse=$reuse seamless=$seamless " +
                    "view=${playerView?.javaClass?.simpleName} " +
                    "source=${remote.info.videoWidth}x${remote.info.videoHeight}"
            )
            if(reuse) playerView?.showPlaybackStillFrame()
            playerView?.holdFirstFrameAspectRatio()
        }
        // 起点一次设定：`setMediaSource(source, startPositionMs)` 内部先摘空旧项、
        // 再按给定位置掩码时间线，等价于「`setMediaSource` + `seekTo`」，少一次
        // `onPositionDiscontinuity`（那条回调会连带刷新媒体会话与弹幕指针）。
        // 旧帧心跳清到 0，并以这一刻作为新片源首帧的下限；否则 READY 兜底可能提前揭黑。
        // 尺寸也在这里重新归零：监听器已经挂上，旧片源的迟到尺寸回调不能算到新片源。
        videoWidth=0; videoHeight=0
        videoSizeReady=false; firstFrameRendered=false
        PlaybackDiag.lastFrameAt=0L
        sourceSetAt=android.os.SystemClock.elapsedRealtime()
        p.setMediaSource(source,position.coerceAtLeast(0))
        p.prepare()
        p.playWhenReady=foreground && requestedAutoplay && !pausedForLifecycle
        stage("sourceSet")
        // 起播看门狗：兜住「READY 了但一帧都没出」的黑屏——那条路不抛异常，进不了 [playerFailure]。
        if(autoplay) armPlaybackWatchdog(session)
        // 停帧看门狗：起播正常、播着播着不再出帧（自动连播几集后最常见的那种黑屏）。
        armFrameStallWatchdog(session)
        syncDanmakuClock()
    }, { problem ->
        // 取地址被平台当成「没注册过的设备」（`101000` / 空响应）时先自愈一次：
        // 丢掉旧身份重注册，成功之后按原来的位置重新起播。这条正是「升级后所有剧都放不了、
        // 清缓存才好」的根因，用户不该为了看一集去清一次缓存。
        if(deviceRejectionOf(problem) && healDeviceIdentity("播放被拒") {
                // 用**这一次**请求的起点重来（不是上一轮的 `retryPosition`）：
                // 自愈发生在取地址阶段，播放器还没起来，位置只能从入参拿。
                playEpisode(episodeIndex,position.coerceAtLeast(0),autoplay,direct=directRequest)
            }) return@work
        playerFailure(problem)
    })
}
