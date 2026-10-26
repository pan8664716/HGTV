// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.view.*
import android.widget.*
import android.text.TextUtils
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.audio.AudioCapabilities
import com.google.android.exoplayer2.audio.AudioSink
import com.google.android.exoplayer2.audio.DefaultAudioSink
import com.google.android.exoplayer2.audio.DefaultAudioTrackBufferSizeProvider
import com.google.android.exoplayer2.mediacodec.MediaCodecAdapter
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector
import com.google.android.exoplayer2.video.MediaCodecVideoRenderer
import com.google.android.exoplayer2.video.VideoRendererEventListener
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.ui.PlayerView
import com.hgtv.star.core.*
import com.hgtv.star.compat.setVectorIcon
import com.hgtv.star.player.DanmakuView
import com.hgtv.star.tv.SeekIndicator
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.player.VideoDataSource
import com.hgtv.star.data.PlaybackCapability

// 本文件是从 MainActivity.kt 拆出来的「播放内核：起播、切集、弹幕加载」这一块。
// 这些成员仍然属于 MainActivity，只是以扩展成员（MainActivity.xxx）的形式放在这里，
// 逻辑与拆之前逐行一致；改这里等于改 Activity 本体。

internal fun MainActivity.loadDanmaku(episodeId: String,seriesId: String) {
    val view=danmakuView ?: return
    view.resetCursor(); view.mode=library.danmakuMode
    // 同一集重复进入（换清晰度、播放重试）不重复拉弹幕：已经拿到的这一份直接沿用，
    // 只把播放位置指针重置回开头。空结果也算「拿到」——这一集确实没有弹幕。
    if(danmakuFor==episodeId && danmakuLoaded) return
    val token=danmakuToken.incrementAndGet()
    danmakuFor=episodeId
    danmakuLoaded=false
    danmakuCount=-1
    view.clear()
    if(library.danmakuMode==DanmakuMode.HIDDEN) return
    danmakuWorker.execute {
        val items=runCatching {
            // 边拉边画：一个 30 秒窗口到手就先铺上屏。整集要拉几十个窗口、每个之间还要留频控间隔，
            // 等全量再画等于前十几秒一条弹幕都没有，看上去和「弹幕功能没生效」一样。
            repository.danmaku(episodeId,seriesId,0,{ danmakuToken.get()!=token }) { partial ->
                main.post {
                    if(isDestroyed || danmakuToken.get()!=token || (screen!="player" && screen!="recommend")) return@post
                    danmakuView?.setDanmaku(partial)
                }
            }
        }
            .onFailure {
                android.util.Log.w("HGTV","Danmaku fetch failed episode=$episodeId",it)
                // 弹幕取不到不弹错（平台上部分剧集对匿名设备就是不开放），
                // 但「这条剧一条弹幕都没有」是会被当成功能坏了的，得留个现场。
                Diagnostics.warn("HGTV-DANMAKU","fetch failed episode=$episodeId",it)
            }
            .getOrDefault(emptyList())
        main.post {
            if(isDestroyed || danmakuToken.get()!=token || (screen!="player" && screen!="recommend")) return@post
            danmakuLoaded=true; danmakuCount=items.size
            android.util.Log.i("HGTV","Danmaku episode=$episodeId count=${items.size} mode=${library.danmakuMode.storedValue}")
            danmakuView?.setDanmaku(items)
            // 一条都没取到才提示一次：平台上只有部分剧集对匿名设备放出弹幕，
            // 不说明的话「屏幕上没有弹幕」会被当成功能坏了。

            updatePlaybackText()
        }
    }
}
/**
 * 按「硬件解码 / 软件解码」筛解码器。
 *
 * ExoPlayer 默认选择器会把软解、硬解一起排好序交回来（硬解优先、失败再回落）。
 * 这里在它外面套一层过滤：硬件档只留非软解、软件档只留软解；
 * **筛完为空就原样退回默认列表**——某些老设备只注册了一种解码器，
 * 硬筛会让本来能播的片源直接变成「没有可解码的视频轨道」。
 *
 * 只在创建播放器时生效；切换档位由 [MainActivity.applyDecoderMode] 重建播放器。
 */
private fun decoderSelector(mode: VideoDecoderMode,app: android.content.Context)=MediaCodecSelector { mimeType,requiresSecureDecoder,requiresTunnelingDecoder ->
    val all=MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType,requiresSecureDecoder,requiresTunnelingDecoder)
    // 调试包的故障注入（见 [DebugFaults]）：正式包里 BuildConfig.DEBUG 是常量 false，
    // 整段会被 R8 删掉，因此线上不存在这条分支。
    if(BuildConfig.DEBUG) {
        when(DebugFaults.mode(app)) {
            // 一颗都不给：ExoPlayer 抛 DecoderQueryException → 4002 / 4005。
            DebugFaults.Mode.DECODER_NONE -> return@MediaCodecSelector emptyList()
            // 只给一颗**不存在的**解码器：初始化阶段必失败 → 4001，且 cause 里带着这个名字，
            // 正好用来验证上报里的 `failedDecoder=` 与 `decoders=` 两栏。
            DebugFaults.Mode.DECODER_BOGUS -> return@MediaCodecSelector listOf(
                com.google.android.exoplayer2.mediacodec.MediaCodecInfo.newInstance(
                    "hgtv.bogus.decoder","video/hevc","video/hevc",null,false,false,false,true,false
                )
            )
            else -> Unit
        }
    }
    val picked=when(mode) {
        VideoDecoderMode.HARDWARE -> all.filter { !it.softwareOnly }
        VideoDecoderMode.SOFTWARE -> all.filter { it.softwareOnly }
    }
    picked.ifEmpty { all }
}

/**
 * 解码器按「最高播放倍速」预留算力。
 *
 * ExoPlayer 2.19 的 `KEY_OPERATING_RATE` 只在**解码器初始化那一刻**算一次：
 * `MediaCodecVideoRenderer.setPlaybackSpeed()` 只更新帧释放器，不会重建解码器。
 * 用户先 1× 起播、再点 1.5×/2× 时，解码器仍然按 1× 的时钟跑——片源 1080P 30fps，
 * 30fps 的 operating-rate 在 1.5× 下要吃 45fps，部分电视 SoC 会直接开始丢帧，
 * 而音频照常输出，用户看到的就是「卡 + 音画不同步」。
 *
 * 这里把 operating-rate 固定按片源帧率 ×[MAX_SPEED] 上报（30fps → 60），
 * 与「起播时就是 2×」时 ExoPlayer 自己算出来的值一致，不改变任何播放语义，
 * 只是让解码器从第一帧起就按 2× 的负载准备时钟。
 */
private class SpeedAwareVideoRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    allowedVideoJoiningTimeMs: Long,
    enableDecoderFallback: Boolean,
    eventHandler: Handler?,
    eventListener: VideoRendererEventListener?,
    maxDroppedFramesToNotify: Int
): MediaCodecVideoRenderer(
    context,codecAdapterFactory,mediaCodecSelector,allowedVideoJoiningTimeMs,
    enableDecoderFallback,eventHandler,eventListener,maxDroppedFramesToNotify
) {
    override fun getCodecOperatingRateV23(playbackSpeed: Float,format: Format,streamFormats: Array<Format>): Float {
        val base=super.getCodecOperatingRateV23(playbackSpeed,format,streamFormats)
        if(base<=0f) return base
        // base 已经乘过初始化时的倍速，这里换算成「固定按 2× 上报」。
        return base*(MAX_SPEED/playbackSpeed.coerceAtLeast(0.1f))
    }
    private companion object { const val MAX_SPEED=2f }
}

/**
 * 电视端渲染器工厂：两处针对播放链路的加固。
 *
 * 1. 视频：用 [SpeedAwareVideoRenderer]，让解码器的 operating-rate 始终按 2× 预留
 *    （见类注释；倍速是在播放中切换的，ExoPlayer 不会为此重配解码器）。
 * 2. 音频：把 AudioTrack 的 PCM 缓冲从默认 250ms 抬到 350ms。
 *
 * 为什么需要：倍速播放时音频由 Sonic 重采样，每秒要喂 1.5~2 倍的 PCM 样本。
 * 电视上的音频通路（软件混音 / HDMI）抖动比手机大，默认 250ms 的缓冲在 1.5× 以上
 * 容易欠载——欠载一次音频时钟就落后一次，视频跟着音频时钟走，累积下来就是音画不同步。
 *
 * 其余参数与 [com.google.android.exoplayer2.DefaultRenderersFactory] 的默认实现保持一致，
 * 尤其是 `setAudioCapabilities`：透传能力少声明一项，HDMI 直通的片源就会退回 PCM。
 */
private class TvRenderersFactory(context: Context): com.google.android.exoplayer2.DefaultRenderersFactory(context) {
    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        out.add(
            SpeedAwareVideoRenderer(
                context,codecAdapterFactory,mediaCodecSelector,allowedVideoJoiningTimeMs,
                enableDecoderFallback,eventHandler,eventListener,
                com.google.android.exoplayer2.DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
            )
        )
    }
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
        enableOffload: Boolean
    ): AudioSink {
        val builder=DefaultAudioSink.Builder(context)
            .setAudioCapabilities(AudioCapabilities.getCapabilities(context))
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioTrackBufferSizeProvider(
                DefaultAudioTrackBufferSizeProvider.Builder()
                    .setMinPcmBufferDurationUs(350_000)
                    .build()
            )
        if(enableOffload) builder.setOffloadMode(DefaultAudioSink.OFFLOAD_MODE_ENABLED_GAPLESS_REQUIRED)
        return builder.build()
    }
}
/**
 * 换解码方式：重建播放器并从当前位置接着播。
 *
 * 解码器是在播放器创建时定下的，运行中改不了；因此先把播放器放掉（[playEpisode] 的
 * `reuse` 判据要求 `player!=null`，这里置空就是让它走「重建」那条路），
 * 再按新档位重新起播。画面会闪一下，但播放位置、倍速、连播状态都不变。
 */
internal fun MainActivity.applyDecoderMode() {
    if(screen!="player" && screen!="recommend") return
    val old=player ?: return
    val position=currentPosition(); val play=old.playWhenReady
    // 先把这一集的进度落盘：下面立刻把 player 置空，playEpisode 里那次 saveProgress 就取不到播放器了。
    saveProgress()
    playerListener?.let { old.removeListener(it) }; playerListener=null
    playerView?.player=null; player=null; playbackReady=false
    // `ExoPlayer.release()` 会阻塞调用线程，直到播放线程把所有 Renderer 收干净
    //（`ExoPlayerImplInternal.release()` 是 `synchronized` + `waitUninterruptibly`）：
    // 老盒子上实测 0.4–2 秒，`c2.*` 解码器上更久。放在主线程上，用户点一下「软件解码」，
    // 遥控器就有两秒没反应——而紧接着同一条路上还有一次整树重建（PlayerView + 十几枚 TextView）。
    //
    // 因此释放放到 io，回到主线程再起播。
    // 重入不需要额外标志位：`player` 上面已经置空，第二次点进来 `val old=player ?: return` 直接返回。
    val ticket=++generation
    runCatching {
        io.execute {
            val started=android.os.SystemClock.elapsedRealtime()
            runCatching { old.release() }
            android.util.Log.i("HGTV","Old player released in ${android.os.SystemClock.elapsedRealtime()-started}ms")
            main.post {
                // 这期间用户可能已经返回 / 换了别的剧：那一次页面重建会把 generation 推走。
                if(isDestroyed || generation!=ticket) return@post
                playEpisode(episodeIndex,position,play)
            }
        }
    }
}
/**
 * 把播放器的当前状态采样给弹幕层。
 *
 * **绘制线程不读播放器**：读位置只发生在这里（主线程，每秒一次，外加播放状态 / 跳转 /
 * 倍速变化），弹幕层自己按系统时钟外推。这样即使 ExoPlayer 内部状态异常，
 * 也最多让弹幕停在上一帧，不会在 `onDraw` 里抛异常把进程带走。
 *
 * 采样本身也包在 [runCatching] 里：线上那条 `PlaybackInfo.timeline` 为 null 的崩溃
 * 就是从 `currentPosition` 抛出来的；这里失败时按「暂停在当前位置」处理。
 */
internal fun MainActivity.syncDanmakuClock() {
    val view=danmakuView ?: return
    val p=player
    if(p==null) { view.stopClock(); return }
    val sample=runCatching { Triple(p.currentPosition,p.isPlaying,p.playbackParameters.speed) }.getOrNull()
    if(sample==null) {
        view.setClock(view.clockPositionMillis(),false)
        return
    }
    view.setClock(sample.first,sample.second && foreground && !pausedForLifecycle,sample.third)
}
/**
 * 播放链路的可观测点：只回答三个问题。
 *
 * 1. 换集时解码器有没有重建 —— `onVideoDecoderInitialized` 每次初始化记一行。
 *    同一集内只出现一次 = 复用；每次切集都出现 = 重建，那才是卡顿的根。
 * 2. 下一集第一帧什么时候上屏。
 * 3. 有没有真的丢帧 / 音频断流。
 *
 * 这些都是 `AnalyticsListener` 的既有事件，不改变播放行为；只在 debug 包挂。
 */
internal fun MainActivity.playbackAnalytics()=object: com.google.android.exoplayer2.analytics.AnalyticsListener {
    private fun tag()=player?.currentMediaItem?.mediaId ?: "-"
    override fun onVideoDecoderInitialized(eventTime: com.google.android.exoplayer2.analytics.AnalyticsListener.EventTime,decoderName: String,initializedTimestampMs: Long,initializationDurationMs: Long) {
        android.util.Log.i("HGTV","Decoder init media=${tag()} name=$decoderName durationMs=$initializationDurationMs")
    }
    override fun onRenderedFirstFrame(eventTime: com.google.android.exoplayer2.analytics.AnalyticsListener.EventTime,surface: Any,renderTimeMs: Long) {
        android.util.Log.i("HGTV","Rendered first frame media=${tag()} renderTimeMs=$renderTimeMs at=${android.os.SystemClock.elapsedRealtime()}")
    }
    override fun onDroppedVideoFrames(eventTime: com.google.android.exoplayer2.analytics.AnalyticsListener.EventTime,droppedFrames: Int,elapsedMs: Long) {
        if(droppedFrames>0) android.util.Log.w("HGTV","Dropped frames=$droppedFrames in ${elapsedMs}ms media=${tag()}")
    }
    override fun onAudioUnderrun(eventTime: com.google.android.exoplayer2.analytics.AnalyticsListener.EventTime,bufferSize: Int,bufferSizeMs: Long,elapsedSinceLastFeedMs: Long) {
        android.util.Log.w("HGTV","Audio underrun ${bufferSizeMs}ms media=${tag()}")
    }
}
internal fun MainActivity.playEpisode(index: Int,position: Long=0,autoplay: Boolean=true,recovering: Boolean=false,prepared: RemoteVideo?=null) {
    val data=detail ?: return
    val playbackStarted=android.os.SystemClock.elapsedRealtime()
    // 推荐页也用这套播放内核：进来时记下「当前是不是推荐」，下面写 `screen` 时原样还回去，
    // 否则一换剧就变成正式播放（返回键会去详情页、上下键会变成选集）。
    val recommendEntry=screen=="recommend"
    // 从详情页 / 首页 / 搜索结果进的正片：返回键回原来的页面，不是「推荐那条路」。
    // 只有「推荐页确定 / 自动连播」进来的那一次才置位（见 [enterFormalPlayback]）；
    // 在正片内部换集 / 重试 / 换清晰度时 `screen` 已经是 `player`，标记原样保留。
    if(!recommendEntry && screen!="player") playbackFromRecommend=false
    val reuse=(screen=="player" || recommendEntry) && player!=null && playerView!=null && !recovering && !playError
    // 「已看完」标记只在**正式播放**里清掉。推荐页还没进正片（需求 3：进正片才算开始看），
    // 这时改本机记录就等于「只看了一眼推荐，历史里那部剧的已看完标记就没了」。
    if(!recommendEntry) library.setWatched(data.series.id,false)
    val selectedIndex=index.coerceIn(0,data.episodes.lastIndex)
    val targetId=data.episodes[selectedIndex]
    // 队列里正好挂着这一集（用户自己按了「下一集」、或点选集切到下一集）：
    // 把它接过来当预热结果，省掉一次取地址。注意队列里那一项是「播完自动推进」用的，
    // 这里要从头重放，所以仍然要重挂一次源，只是不再走网络。
    val fromQueue=queuedEpisodeId==targetId && position==0L && !recovering
    // [prepared] 是调用方**已经预热好**的一份（推荐页上下切换：详情与首集数据都在本地），
    // 所有权当场转移过来，走的是与「预取下一集」同一条无感切集路径。
    val prefetched=if(position==0L && !recovering) {
        if(fromQueue) queuedVideo
        else prepared ?: preparedNext.take("$targetId:${library.maxQuality}")
    } else null
    val claimedHttp=when {
        prefetched==null -> null
        fromQueue -> queuedHttp.also { queuedHttp=null }
        prepared!=null -> null
        else -> prefetchHttp.also { prefetchHttp=null }
    }
    // 传进来的预热结果这一下没用上（例如从后台回来按原位置续播）：当场收掉，
    // 否则那 1MB 与它的连接会一直挂着，没人再来取。
    if(prepared!=null && prefetched!==prepared) runCatching { prepared.close() }
    if(fromQueue) { queuedVideo=null; queuedEpisodeId=null; queuedQuality=""; queuedAt=0 }
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
    val seamless=reuse && prefetched!=null && !recommendEntry
    // 队列里还挂着别的下一集（换清晰度、跳集）：先摘掉，别让它跟着新源一起进播放器。
    if(!fromQueue) dropQueuedEpisode()
    if(seamless) suppressBufferingUi() else restoreBufferingUi()
    // 上一次切集留下的尾巴（正常情况为空：新源出画面时就收掉了，见 [retirePreviousEpisode]）。
    // 这里用强制版：下面马上要整条替换播放列表（setMediaSource / clearMediaItems），
    // 旧项不可能再被读；不强制的话，800ms 内连着切集会把它顶掉、漏掉一次关闭。
    forceRetirePrevious()
    saveProgress(); library.flush(); invalidatePage(); favoriteMonitor.stop(); releaseCatalogViews()
    dismissPlaybackMenu()
    if(reuse) {
        cancelRecovery(); cancelPrefetch(); tvTools.close()
        episodePanel?.dismiss(); episodePanel=null; speedDialog?.dismiss(); speedDialog=null
        playerListener?.let { player?.removeListener(it) }; playerListener=null
        if(!seamless) { player?.stop(); player?.clearMediaItems() }
        // 旧一集的视频与传输先记下来：无感切集不当场关（旧源可能还在收尾，当场关会让它读到
        // 一个已经关闭的视频而报错），等新源出画面再收；非无感路径下面紧接着就收掉。
        retiringVideo=video; retiringHttp=playbackHttp; video=null; playbackHttp=null
        // 非无感路径刚刚 stop + clearMediaItems，旧项已不可能再被读，直接收。
        if(!seamless) forceRetirePrevious() else scheduleRetire()
        main.removeCallbacks(hideHud); main.removeCallbacks(seekRunnable); pendingSeek=null
    } else detachPlayerSurface()
    // 会话号必须在 `detachPlayerSurface()` **之后**取：那条路自己也会自增（作废旧回调），
    // 先取再调会让本次监听器的 `active()` 永远不成立——表现就是首次进播放页
    // 一直「正在缓冲」、进度条拖不动，切一集才恢复。
    val session=++playbackSession
    artwork.cancelPage(clearMemory=true)
    playbackHttp=claimedHttp; video=prefetched
    var firstReady=true; var firstFrame=true
    if(!recovering) recovery.reset()
    autoRecovery=false; readySince=0; lastEpisodeDuration=0; advancedAt=0
    startPosition=position.coerceAtLeast(0); requestedAutoplay=autoplay
    if(autoplay) sleepStopped=false
    screen=if(recommendEntry) "recommend" else "player"; episodeIndex=selectedIndex
    // 诊断现场：换集就重置。之后解码器名 / 视频格式由 [playbackDiagnostics] 填，
    // 失败时 [reportPlaybackFailure] 原样带走——正式包里 `Log.i` 已被 R8 删掉，这里是唯一证据。
    // 调试注入开关只有一处：出帧的三条证据链（Player 监听 / Analytics 监听 / 逐帧心跳）
    // 全都看它（见 [PlaybackDiag.injectedNoFrame]），注入「有声音无画面」才测得准。
    PlaybackDiag.injectedNoFrame=BuildConfig.DEBUG && DebugFaults.mode(this)==DebugFaults.Mode.STALL_FRAME
    PlaybackDiag.begin(session,data.series.title,targetId,selectedIndex,data.episodes.size,library.decoderMode.label,"${library.maxQuality}P")
    panel=false; playError=false; playbackReady=false; quality=""; pausedForLifecycle=!autoplay
    // 片源尺寸属于「上一集」：新一集要知道自己的尺寸之后才能决定要不要自动全屏，
    // 不清掉的话第一帧出来之前会沿用上一集的比例（竖屏剧之后接横屏剧会先闪一下裁切）。
    videoWidth=0; videoHeight=0
    player?.playWhenReady=requestedAutoplay
    prefetchAttempts=0; prefetchAttemptAt=0
    // 常亮由状态推导（`autoplay=false` 的续播不该钉住屏幕），见 [syncKeepScreenOn]。
    syncKeepScreenOn()
    if(!reuse) {
        root.removeAllViews(); root.setBackgroundColor(Color.BLACK)
        // 整棵树被清空了：触摸层的引用全部作废（控件已经不在树上），
        // 否则下面 `attachTouchLayer()` 会因为「引用非空」而直接返回，平板播放页就没有手势层了。
        touchLayer=null; touchBar=null; touchPlayLabel=null; touchSeekHint=null; touchBoostHint=null
        // 横滑调进度那块读数也挂在旧的手势层上，引用一并作废（见 `showScrubHint`）。
        touchScrubHint=null
        scrubbing=false; recommendScrubbing=false
        // 画面之外那一层同样随整棵树作废（见 `overlayLayer`）。
        overlayLayer=null
        // 推荐页那枚「剧名」入口同属这一块：整棵树已经换掉，引用必须一起作废。
        touchEntryTitle=null; touchEntryHint=null
        // 长按倍速是「手指按住时的临时状态」，换集时一定已经抬手：标志位不能带进新的一集。
        playbackBoost=false
        // 「短剧自动全屏」的实现是**把画面转 90°**（见 [autoFullscreenRotated]）。
        //
        // 这一步对承载视频的那一层有硬要求：SurfaceView 是独立于窗口的系统图层，
        // 父视图的旋转不会作用到它的画面上，转完之后看到的只是被拉扁 / 错位的画面
        // （表现就是「横向拉伸」）。TextureView 是普通视图，跟着父级一起变换，才能真的转过来。
        //
        // 因此开关打开时改用 `surface_type=texture_view` 这一版布局；关掉时仍然用默认的
        // SurfaceView（更省电、4K 更稳，与改动前完全一致）。
        val view=(if(library.autoFullscreen) layoutInflater.inflate(R.layout.player_surface_texture,root,false) as PlayerView else PlayerView(this)).apply {
            useController=false; resizeMode=frameResizeMode(); isFocusable=false
            setKeepContentOnPlayerReset(true); setShutterBackgroundColor(Color.BLACK); setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        }
        // 缓冲圈按**设计单位**定尺寸。
        //
        // PlayerView 里的 exo_buffering 是一个 wrap_content 的 ProgressBar：它的大小
        // 完全由平台的 indeterminateDrawable 决定，而那份 drawable 是按 density 换算的
        // （48dp × density）。540dpi 上 48dp = 162px，屏幕上就是一颗巨大的圈。
        // 这里把它钉成 46 个设计单位，与全站其它图标同一档；它仍然是系统那枚转圈动画，
        // 只是被约束在一个固定的方框里，换分辨率、换 dpi 都不会再变形。
        view.findViewById<ProgressBar>(com.google.android.exoplayer2.ui.R.id.exo_buffering)?.let { spinner ->
            spinner.layoutParams=FrameLayout.LayoutParams(dp(46),dp(46),Gravity.CENTER)
        }
        // 播放器实例是进程内复用的：从别的页面回到播放页时 [player] 已经存在，
        // 这里必须把新一层的 PlayerView 接上去，否则画面没有输出（黑屏、只有声音）。
        view.player=player
        playerView=view; root.addView(view,FrameLayout.LayoutParams(-1,-1))
        // 画面之上的一切装在同一层里：弹幕 / 手势层 / 顶部信息条 / 底部控制条。
        //
        // 只把视频转 90°（短剧自动全屏）时，这一层要跟着一起转，否则标题条、控制条、
        // 弹幕都还横在原方向上，和画面互相垂直。装成一层之后，「跟着转」只需要在
        // 这一层上做一次旋转 + 一次宽高对调（见 `applyOverlayGeometry`），
        // 里面所有控件（含后挂上来的手势层、触屏按键、快进提示）自动跟着走。
        val layer=FrameLayout(this).apply { clipChildren=false; clipToPadding=false }
        overlayLayer=layer
        root.addView(layer,FrameLayout.LayoutParams(-1,-1))
        // 画面缩放 / 短剧自动全屏按当前偏好落到这一层上
        //（100% 且不旋转时就是原来的铺满）。
        applyVideoGeometry()
        // 弹幕层压在画面之上、标题条与控制层之下：隐藏弹幕时整层不可见，也不参与绘制。
        // 播放位置与播放状态都从播放器现取，因此暂停 / 跳转 / 倍速都不会和画面脱节。
        val overlay=DanmakuView(this).apply {
            mode=library.danmakuMode
            // 字号、透明度与速度都是本机偏好（设置面板里调出来的），
            // 换集、换清晰度重建这一层时都要带上，否则会回退成默认档。
            textScale=library.danmakuScale
            textAlpha=library.danmakuAlpha
            speed=library.danmakuSpeed
            mergeRepeats=library.danmakuMerge
            // 时钟由宿主推进（见 [syncDanmakuClock]），绘制线程不再直接读播放器。
            setClock(0,false)
        }
        danmakuView=overlay
        layer.addView(overlay,FrameLayout.LayoutParams(-1,-1))
        // 播放画面顶部的一条常显信息：**左 = 剧名 → 当前集数；右 = 网速 → 时间**。
        //
        // 四项各自独立开关（设置页「界面设置 → 播放显示」）。左两组贴左、右两组贴右，
        // 中间由左组自己的权重吃掉空出来的宽度：某一项关掉（`GONE`）时，
        // 剩下那一项自然补到它该在的位置（左侧仍靠左、右侧仍靠右），不会留下空档。
        //
        // 这一条**常驻**（不跟底部控制条一起收起），因此在顶部压一条 50% 黑 → 透明的渐变，
        // 亮画面上白字也读得清；文字固定用暖白（TvStyle.playerText），不跟主题，
        // 否则浅色主题下会变成深色字、整条看不见。
        val info=row().apply {
            gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(78),0,dp(78),0)
            background=TvStyle.gradient(0x80000000.toInt(),Color.TRANSPARENT)
        }
        // 左组：剧名 + 当前集数，两项**都贴左**（剧名在前、集数紧跟其后）。
        // 整组在 info 里带权重，把右组顶到最右边；组内两项都是 wrap_content，
        // 因此剧名关掉时集数自动成为最左那一项，反之亦然。
        val left=row().apply { gravity=Gravity.CENTER_VERTICAL }
        // 剧名：只写剧名，**不再拼「· 第 N 集」**（集数由右边那一项单独负责）。
        playbackTitle=text("",24f,TvStyle.playerText).apply {
            maxLines=1; ellipsize=TextUtils.TruncateAt.END; setTypeface(null,Typeface.BOLD)
            setShadowLayer(5f,0f,2f,0x80000000.toInt())
            setPadding(0,0,dp(18),0)
        }
        left.addView(playbackTitle,lp(-2,-2))
        // 当前集数 / 总集数：`第 3 / 24 集`。
        val episode=text("",22f,TvStyle.playerText).apply {
            maxLines=1
            setShadowLayer(5f,0f,2f,0x80000000.toInt())
        }
        playbackEpisode=episode
        left.addView(episode,lp(-2,-2))
        // 剧名是 wrap_content（要贴着集数走），长剧名因此需要一条上界才能省略：
        // 每次布局按「左组可用宽度 − 集数宽度」算一次 maxWidth，超出就由 ellipsize 收成「…」。
        // 这样既不会把集数挤出画面，也不会在两者之间留一段空白。
        left.addOnLayoutChangeListener { view,_,_,viewRight,_,_,_,_,_ ->
            val available=(viewRight-view.left-view.paddingLeft-view.paddingRight).coerceAtLeast(0)
            val episodeWidth=if(episode.visibility==View.VISIBLE) episode.width else 0
            val limit=(available-episodeWidth).coerceAtLeast(0)
            if(playbackTitle.maxWidth!=limit) { playbackTitle.maxWidth=limit; playbackTitle.requestLayout() }
        }
        info.addView(left,lp(0,-2).apply { weight=1f })
        // 右组：网速 + 时间，整体贴右。
        val right=row().apply { gravity=Gravity.CENTER_VERTICAL }
        // 网速：本进程实时下行（`↓1.2M/s`），默认关闭。
        val speed=text("",22f,TvStyle.playerText).apply {
            maxLines=1
            setShadowLayer(5f,0f,2f,0x80000000.toInt())
            setPadding(0,0,dp(18),0)
        }
        playbackSpeedText=speed
        right.addView(speed,lp(-2,-2))
        // 时间在最右：24sp，与剧名同字号。
        val clock=text("",24f,TvStyle.playerText).apply {
            maxLines=1
            setShadowLayer(5f,0f,2f,0x80000000.toInt())
        }
        playerClock=clock
        right.addView(clock,lp(-2,-2))
        info.addView(right,lp(-2,-2))
        // 整宽、96dp 高，贴着顶边：与原来那条标题条同一块区域、左右同一条 78dp 安全线。
        layer.addView(info,FrameLayout.LayoutParams(-1,dp(96),Gravity.TOP))
        playbackInfo=info

        // 底部控制层保持原样：透明 → 60% 黑 → 90% 黑的三段渐变，压在画面上自然暗下去。
        // 播放界面（标题条 + 控制层 + 进度条）不套新拟态：任何柔光底板都会和画面抢注意力，
        // 这一层只负责让画面暗下来、让控制项读得清。
        // 第一行 = 42.5dp 播放键 + 进度条 + 右对齐时间；第二行 = 20sp 状态项。
        hud=column().apply {
            setPadding(dp(78),dp(70),dp(78),0)
            background=TvStyle.panel()
        }
        val transport=row()
        transportIcon=ImageView(this).apply {
            setVectorIcon(R.drawable.ic_player_play)
            scaleType=ImageView.ScaleType.CENTER_INSIDE
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnClickListener { togglePlayback() }
        }
        transport.addView(transportIcon,lp(dp(43),dp(43)))
        progressBar=SeekIndicator(this)
        transport.addView(progressBar,lp(0,dp(43)).apply { weight=1f; leftMargin=dp(13); rightMargin=dp(26) })
        playbackText=text("",18f,TvStyle.playerText).apply { setShadowLayer(4f,0f,1f,0x80000000.toInt()) }
        transport.addView(playbackText,lp(-2,-2))
        hud.addView(transport,lp(-1,dp(47)))
        // 状态行只显示当前值、只做点击入口（遥控器走 上=播放设置菜单、下=选集），因此不抢焦点。
        val options=row().apply { setPadding(0,dp(20),0,0) }
        fun option(label: String,action: ()->Unit): TextView {
            val item=text(label,20f,TvStyle.playerDim).apply {
                gravity=Gravity.CENTER; maxLines=1
                setOnClickListener { action() }
                // 状态项的焦点只做「更亮一档」，同样不引入颜色。
                setOnFocusChangeListener { view,focused -> (view as TextView).setTextColor(if(focused) TvStyle.playerText else TvStyle.playerDim) }
            }
            options.addView(item,lp(-2,-2).apply { rightMargin=dp(34) })
            return item
        }
        // 点击任一状态项直接打开竖向菜单的对应项目。
        qualityOption=option("清晰度 ${library.maxQuality}P") { showPlaybackMenu(MENU_ROW_QUALITY) }
        speedOption=option("倍速 ${PlaybackSpeed.label(library.playbackSpeed)}") { showPlaybackMenu(MENU_ROW_SPEED) }
        frameOption=option("画面 ${library.frameMode.label}") { showPlaybackMenu(MENU_ROW_FRAME) }
        danmakuOption=option("弹幕 ${library.danmakuMode.label}") { showPlaybackMenu(MENU_ROW_DANMAKU) }
        sleepOption=option("定时 ${sleepTimer.label()}") { showPlaybackMenu(sleepRow(library.danmakuMode.showsStyle)) }
        hud.addView(options,lp(-1,-2))
        // 底部居中的下拉箭头（爆米花 scroll_indicator）：点击直接打开选集。
        // 触屏的推荐页要把它藏起来（那一页不放选集入口，见 `attachRecommendEntry`），
        // 因此留一个引用，按页面切换显隐。
        hudEpisodeArrow=ImageView(this).apply {
            setVectorIcon(R.drawable.ic_chevron_down)
            scaleType=ImageView.ScaleType.CENTER_INSIDE
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnClickListener { showEpisodePanel(hud) }
        }
        hud.addView(
            hudEpisodeArrow,
            lp(dp(75),dp(38)).apply { gravity=Gravity.CENTER_HORIZONTAL; topMargin=dp(8) }
        )
        controls=row().apply { setPadding(0,dp(12),0,0); visibility=View.GONE }; hud.addView(controls)
        // 这个滚动容器高度是 WRAP_CONTENT，内容永远不超过自己，因此不需要外壳裁剪；
        // 容器自己也不裁剪，播放器按钮放大后的底色才能画出来。
        // 控制条也挂在这一层里（同样会跟着画面一起转）。ScrollView 的高度是 WRAP_CONTENT、
        // 贴在这一层的底边——旋转之后它就落在屏幕的一条竖边上，与画面同方向。
        layer.addView(ScrollView(this).apply { isVerticalScrollBarEnabled=false; clipChildren=false; clipToPadding=false; addView(hud) },FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM)) // focus-bleed-exempt: 边界就是整屏宽，播放器按钮离边缘 ≥78dp
        // 触屏设备（平板 / 手机 / 触屏一体机）：在画面之上补一层手势、给控制条补一排可点按钮、
        // 把进度条接成可拖。电视上 `touchArmed` 恒为 false（永远收不到 ACTION_DOWN），
        // 这三步一次都不会执行，播放页的结构与今天逐行一致。
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
    playbackEpisode?.text="第 ${episodeIndex+1} / ${data.episodes.size} 集"
    syncPlaybackInfo()
    // 复用播放器的那条路不会重建播放层：这一集如果换过缩放比例，在这里重新落一次。
    applyVideoScale()
    // 画面模式同理：从设置页回来之后「短剧自动全屏」可能刚被改过。
    applyResizeMode()
    loadDanmaku(data.episodes[episodeIndex],data.series.id)
    playbackText.text="正在获取播放地址…"; progressBar.progress=0f; progressBar.visibility=View.VISIBLE; hidePlaybackOverlay()
    controls.removeAllViews(); controls.visibility=View.GONE
    syncMediaSession()
    val requestedId=data.episodes[episodeIndex]; val maxQuality=library.maxQuality; val ticket=generation
    work({
        // 播放用的是**平台注册过的**设备身份（本机随机值已经不再参与签名）：
        // 注册还没落地时先补一次，免得取地址落到内置那份被拉黑的档案上。
        if(!library.deviceRegistered()) registerDeviceOnce()
        // 播放模型要按这台设备的解码能力报 `device_level`（见 PlaybackCapability）：
        // 报 3 时低清晰度全是 BVC2（Android 解不了），「默认 480P」会形同虚设。
        // 探测结果有缓存，这里在 io 线程读，几乎不花时间；放在取地址之前是为了
        // 保证「第一次播放」就已经用的是正确档位（启动时那次异步探测可能还没回来）。
        repository.deviceLevel=PlaybackCapability.deviceLevel(this@playEpisode)
        // 取地址走仓库自己的 client（内容接口），**读视频走播放专用传输层**：
        // 后者钉死 HTTP/1.1、进程级共享，切集不再关连接池（见 PlaybackTransport）。
        prefetched ?: run {
            val remote=RemoteVideo(
                com.hgtv.star.data.PlaybackTransport.client,
                repository.stream(requestedId,maxQuality)
            )
            // 调试包的故障注入：本地准备阶段直接失败（见 [DebugFaults]）。
            if(BuildConfig.DEBUG && DebugFaults.mode(this@playEpisode)==DebugFaults.Mode.PREPARE_FAIL) {
                remote.close()
                throw PlaybackPrepareException("调试注入：视频索引不可用")
            }
            // 「取地址」与「本地准备」分开包：前者失败是没有资源 / 没有可用清晰度，
            // 后者是容器不认、索引不在文件头、加密索引读不出来——后者与设备无关，
            // 但界面上同样说「解码失败」。换成 [PlaybackPrepareException]，上报里就能分开。
            try { remote.prepare() } catch(e: java.io.IOException) {
                remote.close()
                throw PlaybackPrepareException(e.message ?: "播放准备失败",e)
            }
        }
    }, { remote ->
        video=remote; quality=remote.info.quality; PlaybackDiag.quality=quality
        val p=player ?: run {
            // 缓冲按时间算、字节上限只做保护。
            //
            // 实测片库（40 部 1080P 短剧）整文件码率 273~1727 kbps、视频 198~1650 kbps，
            // 全是 HEVC 8bit、24~30fps。按最高码率算，60 秒缓冲只占 13MB，
            // 因此这里留 24MB（约等于最高码率下 110 秒）就够，既不会提前截断，
            // 又把堆内存上限压在安全区。
            //
            // 时间是真正生效的那一维：`minBufferUs` 会被 ExoPlayer 按倍速放大，
            // 1.5× 时最小缓冲是 30 秒媒体、2× 时是 40 秒，真实缓冲时间不再随倍速缩水。
            // `setPrioritizeTimeOverSizeThresholds(true)` 保证字节上限不会先于时间到顶。
            val load=DefaultLoadControl.Builder()
                .setBufferDurationsMs(20000,60000,1000,2000)
                .setTargetBufferBytes(24*1024*1024)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
            val renderers=TvRenderersFactory(this)
                .setEnableDecoderFallback(true)
                .setMediaCodecSelector(decoderSelector(library.decoderMode,this@playEpisode))
            ExoPlayer.Builder(this,renderers).setLoadControl(load).build().also {
                player=it; playerView?.player=it
                it.setAudioAttributes(com.google.android.exoplayer2.audio.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true)
                it.setHandleAudioBecomingNoisy(true)
                // 诊断监听**正式包也挂**：只记账、不写日志，开销是几个 volatile 赋值。
                it.addAnalyticsListener(playbackDiagnostics())
                // 逐帧心跳：「画面还在走」的唯一硬证据，停滞看门狗靠它判活（见 [playbackFrameClock]）。
                it.setVideoFrameMetadataListener(playbackFrameClock())
                if(com.hgtv.star.BuildConfig.DEBUG) it.addAnalyticsListener(playbackAnalytics())
            }
        }
        p.setPlaybackSpeed(library.playbackSpeed)
        val listener=object: Player.Listener {
            // 片尾自动推进之后 mediaId 会变成下一集，因此这里不再拿它做判据；
            // 但也不能什么判据都不留：用播放会话号，挡掉上一次 `playEpisode` 遗留的回调。
            internal fun active()=player===p && generation==ticket && session==playbackSession
            override fun onPlaybackStateChanged(state: Int) {
                if(!active()) return
                if(state==Player.STATE_READY) {
                    PlaybackDiag.tracksSelected=p.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
                    if(!PlaybackDiag.tracksSelected) {
                        playerFailure(PlaybackException("资源没有可解码的视频轨道",null,PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
                        return
                    }
                    playbackReady=true
                    // 从推荐页进正片时这一集还没 READY，历史由这里补写（见 [enterFormalPlayback]）。
                    if(recommendHistoryPending) saveProgress()
                    // 起播已经稳了：趁用户还没按菜单键，把播放设置面板先建好、画一遍
                    //（见 [warmPlaybackMenu]）。不预热的话第一次打开菜单仍要现建三十来枚按钮，
                    // 那一下弹幕会停半拍。
                    warmPlaybackMenu()
                    // 推荐页真的出画面了：连续失败计数归零（取地址成功不算，能放才算）。
                    if(screen=="recommend") {
                        recommendPlaybackFailures=0
                        // 过渡黑场的兜底出口：复用播放器时个别设备不会再报「首帧」，
                        // 只等那一个回调会让黑场一直挂到 2.5 秒的上限。READY 时视频尺寸已经知道，
                        // 这时候放掉不会再看到拉伸；令牌保证「这期间又换了一部」时不会提前放。
                        val coverToken=recommendCoverToken
                        main.postDelayed({
                            if(!isDestroyed && screen=="recommend" && recommendCoverToken==coverToken) revealRecommendCover()
                        },400)
                    }
                    // 不自动播的起播（用户进来先按了暂停、或从后台回来）也要有人盯着黑屏：
                    // 上面那次 arm 只在 autoplay 时发生，这里补一次，已有就不动。
                    // 判据是「有没有画面」而不是只认 `onRenderedFirstFrame`：
                    // 隧道模式与个别老系统不回调那个方法，只认它会把正常播放误判成黑屏。
                    if(playbackWatchdog==null && !pictureUpNow()) armPlaybackWatchdog(session)
                    lastEpisodeDuration=p.duration.coerceAtLeast(0)
                    // 新源已经出画面：这时候再收上一集的视频与传输最稳，顺手把缓冲指示恢复。
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
                    if(queuedEpisodeId!=null) {
                        playerFailure(PlaybackException("队列中的下一集没有接上",null,PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
                        return
                    }
                    saveProgress(true)
                    // 「连着看」：还在前台、没有因为息屏 / 切后台被暂停、用户也没按过暂停。
                    val continuous=foreground && requestedAutoplay && !pausedForLifecycle
                    if(sleepTimer.episodeEnded()) stopForSleep()
                    else if(continuous && library.autoNext && episodeIndex<data.episodes.lastIndex) playEpisode(episodeIndex+1)
                    else if(continuous && episodeIndex>=data.episodes.lastIndex) {
                        // 最后一集播完：不再停在最后一帧上等用户按返回。
                        // 按「相关剧集」那一份数据给一份下一部的推荐，让用户自己挑一部接着看
                        // （见 [showSeriesFinished]）。
                        requestedAutoplay=false; p.pause(); showHud()
                        showSeriesFinished(data.series)
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
                val next=data.episodes.indexOf(mediaItem?.mediaId)
                if(next<0 || next==episodeIndex) return
                onEpisodeAdvanced(next)
                // 队列空了：为新的这一集再准备下一集。等这一集 READY 再挂，
                // 免得两个 period 同时抢加载带宽。
                main.postDelayed({ if(!isDestroyed) maybeQueueNext() },3000)
            }
            /**
             * 片源尺寸：**短剧自动全屏的判据之一**（另一个是屏幕尺寸，见 [autoFullscreenRotated]）。
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
                applyResizeMode()
            }
            override fun onRenderedFirstFrame() {
                if(!active()) return
                // 首帧的尺寸已经确定：短剧自动全屏在这之后再落一次
                //（`onVideoSizeChanged` 在个别软解 / 隧道模式下不一定先于首帧回调，这里兜一次底）。
                applyResizeMode()
                // 推荐页：新一集的第一帧真的上屏了，过渡黑场这时候才淡出（见 [revealRecommendCover]）。
                // 用「出帧」而不是 READY：READY 只代表解出了轨道，画面可能还要等几百毫秒。
                if(screen=="recommend") revealRecommendCover()
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
                if(PlaybackDiag.takeRecovered()) android.util.Log.i("HGTV-PLAY","recovered mode="+
                    (if(library.decoderMode==VideoDecoderMode.SOFTWARE) "sw" else "hw")+
                    " codec=${PlaybackDiag.codecFamily} res=${PlaybackDiag.resolution}"+
                    " dev=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                syncDanmakuClock()
                // 推荐页：时长一确定就把进度条右侧那段时间填上，不等下一次每秒 tick——
                // 否则刚打开的那一两秒是「--:-- / --:--」，与点击唤出来的那一版不一致。
                if(screen=="recommend") updateRecommendOverlay()
                if(firstFrame) {
                    firstFrame=false
                    android.util.Log.i("HGTV","Playback first frame episode=${episodeIndex+1} preloaded=${prefetched!=null} reused=$reuse elapsedMs=${android.os.SystemClock.elapsedRealtime()-playbackStarted}")
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
                if(hud.visibility==View.VISIBLE) showHud()
                // 播放 / 暂停是「屏幕该不该常亮」的直接判据（见 [syncKeepScreenOn]）。
                syncKeepScreenOn()
                syncMediaSession()
                // 弹幕时钟跟着播放状态起停：暂停时停在当前帧，恢复播放时重新校准。
                syncDanmakuClock()
            }
            override fun onPlaybackParametersChanged(parameters: PlaybackParameters) {
                if(!active()) return
                if(com.hgtv.star.BuildConfig.DEBUG) android.util.Log.i(
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
        }
        playerListener=listener; p.addListener(listener)
        val source=ProgressiveMediaSource.Factory { VideoDataSource(remote) }.createMediaSource(MediaItem.Builder().setMediaId(requestedId).setUri("hgtv://episode/$requestedId").setMimeType(com.google.android.exoplayer2.util.MimeTypes.VIDEO_MP4).build())
        p.setMediaSource(source); p.seekTo(position.coerceAtLeast(0)); p.prepare(); p.playWhenReady=foreground && requestedAutoplay && !pausedForLifecycle
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
                playEpisode(episodeIndex,position.coerceAtLeast(0),autoplay)
            }) return@work
        playerFailure(problem)
    })
}
