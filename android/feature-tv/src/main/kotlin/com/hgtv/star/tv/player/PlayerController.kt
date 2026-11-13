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
import com.hgtv.star.shared.media.DanmakuView
import com.hgtv.star.tv.SeekIndicator
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.shared.media.VideoDataSource
import com.hgtv.star.shared.data.PlaybackCapability
import com.hgtv.star.shared.architecture.danmaku
import com.hgtv.star.shared.architecture.player
import com.hgtv.star.shared.debug.Diagnostics
import com.hgtv.star.shared.debug.DebugFaults
import com.hgtv.star.tv.R

// 本文件是从 TvMainActivity.kt 拆出来的「播放内核：起播、切集、弹幕加载」这一块。
// 这些成员仍然属于 TvMainActivity，只是以扩展成员（TvMainActivity.xxx）的形式放在这里，
// 逻辑与拆之前逐行一致；改这里等于改 Activity 本体。

fun TvMainActivity.loadDanmaku(episodeId: String,seriesId: String,clearOnSwitch: Boolean=false) {
    // 加载内核在 `:shared`（`DanmakuLoader`）：手机播放页与手机信息流用的是同一份，
    // 三处「同一集不重拉 / 旧回包丢弃 / 隐藏时不发请求」的规则只写一次。
    danmakuLoader.load(episodeId,seriesId,clearOnSwitch=clearOnSwitch)
}
/**
 * 本次播放**实际生效**的清晰度上限。
 *
 * 优先级：会话层降档结果 > 用户设置。所有「取地址 / 预热 / 面板显示 / 索引缓存」的
 * **读取**都必须走这里，否则自动降档只在当前这一集生效，下一集立刻回到用户设置、
 * 再撞一次同样的失败（这正是要修的循环）。
 *
 * 写 `library.maxQuality` 的地方只允许是用户手动选择（见 `MainActivityPlaybackMenu`）。
 */
val TvMainActivity.playbackMaxQuality: Int
    get()=playbackSessionState.effectiveMaxQuality ?: library.maxQuality

/**
 * 本次播放实际生效的解码方式：**就是用户设置**。
 *
 * 降级不再自动改这一档（全站只有 HEVC，软解是用户的知情选择）：
 * 切软解的唯一入口是错误页上的「切换为软解」按钮与播放设置里的「解码」一行，
 * 两者都写 `library.decoderMode`（见 [applyDecoderMode]）。
 */
val TvMainActivity.playbackDecoderMode: VideoDecoderMode
    get()=library.decoderMode

/**
 * 下一集预热槽的键：集 + 上限。
 *
 * 三处（预取写入 / 挂队列取出 / tick 兜底）必须用**同一个**字符串。
 * 全站只有 HEVC 一种编码，所以键里不再需要编码族那一段。
 */
fun TvMainActivity.preparedKeyFor(id: String): String =
    "$id:${playbackMaxQuality}"

/**
 * 建播放器时该用的解码方式。
 *
 * **就等于用户设置**（[playbackDecoderMode]）：本机有没有 HEVC 硬解**不再自动改这一档**。
 *
 * 需求口径是「优先硬解，不支持硬解时提示用户换成软解，由用户手动换」。
 * 早先这里会在探测到「本机没有视频硬解」时静默改成软解：用户以为开着硬解，实际在软解，
 * 而且一旦软解也放不动，界面上没有任何线索说明「是硬解根本没有」。
 * 现在硬解筛空 → 4002 → 错误页给出「切换软解 + 建议档位」的按钮，动作由用户发起。
 */
fun TvMainActivity.playerDecoderMode(): VideoDecoderMode = playbackDecoderMode

/**
 * 电视端渲染器工厂：两处针对播放链路的加固。
 *
 * 1. 视频：在 ExoPlayer 的 [MediaCodecVideoRenderer] 上补一层换流兼容。起播前先写入用户
 *    设置的倍速；新解码器启动后若设备漏掉配置值，会通过
 *    `MediaCodec.setParameters("operating-rate")` 强制重新下发，不需要重建播放器。
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
            SpeedSafeVideoRenderer(
                context,codecAdapterFactory,mediaCodecSelector,allowedVideoJoiningTimeMs,
                enableDecoderFallback,eventHandler,eventListener,
                com.google.android.exoplayer2.DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
            )
        )
    }
    /** 与 `DefaultRenderersFactory` 保持一致，只额外抬高 AudioTrack 的 PCM 缓冲。 */
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
        enableOffload: Boolean
    ): AudioSink {
        val builder = DefaultAudioSink.Builder(context)
            .setAudioCapabilities(AudioCapabilities.getCapabilities(context))
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioTrackBufferSizeProvider(
                DefaultAudioTrackBufferSizeProvider.Builder()
                    .setMinPcmBufferDurationUs(350_000)
                    .build()
            )
        if (enableOffload) {
            builder.setOffloadMode(DefaultAudioSink.OFFLOAD_MODE_ENABLED_GAPLESS_REQUIRED)
        }
        return builder.build()
    }
}

/**
 * 兼容部分电视 SoC 的倍速实现。
 *
 * 新一集可能复用同一个渲染器，但重建 MediaCodec。创维酷开等设备会忽略
 * `MediaFormat.operating-rate`；此时播放器仍是 1.5× / 2×，音频按新倍速走，
 * 视频解码器却停留在 1×。ExoPlayer 又会把“相同倍速”的再次设置当成空操作，
 * 因此必须由渲染器在真正启动 / 换流完成后，对底层解码器强制重下发一次。
 *
 * 这里只调整视频渲染器的速度，不经过播放器，也不会改变音频的 Sonic 参数。
 */
private class SpeedSafeVideoRenderer(
    context: Context,
    codecAdapterFactory: com.google.android.exoplayer2.mediacodec.MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    allowedJoiningTimeMs: Long,
    enableDecoderFallback: Boolean,
    eventHandler: Handler,
    eventListener: VideoRendererEventListener,
    maxDroppedFramesToNotify: Int,
): MediaCodecVideoRenderer(
    context,codecAdapterFactory,mediaCodecSelector,allowedJoiningTimeMs,
    enableDecoderFallback,eventHandler,eventListener,maxDroppedFramesToNotify
) {
    private var lastRateCheckAt=0L

    override fun onStarted() {
        super.onStarted()
        lastRateCheckAt=android.os.SystemClock.elapsedRealtime()
        reassertCodecOperatingRate()
    }

    override fun onCodecInitialized(
        name: String,
        configuration: com.google.android.exoplayer2.mediacodec.MediaCodecAdapter.Configuration,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        super.onCodecInitialized(name,configuration,initializedTimestampMs,initializationDurationMs)
        // 预取 / 队列 / 缓存切换可能让厂商解码器在播放中重新初始化。
        // 初始化完成后立即把当前倍速写回，不能只依赖 renderer 的启动回调。
        reassertCodecOperatingRate()
    }

    override fun onProcessedStreamChange() {
        super.onProcessedStreamChange()
        lastRateCheckAt=android.os.SystemClock.elapsedRealtime()
        reassertCodecOperatingRate()
    }

    override fun onProcessedOutputBuffer(presentationTimeUs: Long) {
        super.onProcessedOutputBuffer(presentationTimeUs)
        maybeReassertDuringPlayback()
    }

    override fun onProcessedTunneledBuffer(presentationTimeUs: Long) {
        super.onProcessedTunneledBuffer(presentationTimeUs)
        maybeReassertDuringPlayback()
    }

    /**
     * 部分酷开 / 创维解码器会在播放一段时间后自己丢掉 operating-rate。
     * 输出帧回调只做一次很轻的时间判断，最多每 2 秒重新下发一次。
     */
    private fun maybeReassertDuringPlayback() {
        val now=android.os.SystemClock.elapsedRealtime()
        if(now-lastRateCheckAt<2_000L) return
        lastRateCheckAt=now
        reassertCodecOperatingRate()
    }

    private fun reassertCodecOperatingRate() {
        val speed=playbackSpeed
        if(!speed.isFinite() || kotlin.math.abs(speed-1f)<0.001f) return
        // 先把速度轻微错开再恢复：MediaCodecRenderer 只在计算值变化时调用
        // `MediaCodec.setParameters("operating-rate")`。差值 0.001× 用户不可感知。
        val nudge=if(speed>1f) speed-0.001f else speed+0.001f
        setPlaybackSpeed(nudge,1f)
        setPlaybackSpeed(speed,1f)
    }
}
/**
 * 换解码方式：重建播放器并从当前位置接着播。
 *
 * 解码器是在播放器创建时定下的，运行中改不了；因此先把播放器放掉（[playEpisode] 的
 * `reuse` 判据要求 `player!=null`，这里置空就是让它走「重建」那条路），
 * 再按新档位重新起播。画面会闪一下，但播放位置、倍速、连播状态都不变。
 *
 * [position] / [play] 由**故障降级**那条路传进来（见 `replayWithPlan`）：出错时
 * `playbackReady` 可能已经被清掉，这里再读一次 `currentPosition()` 会退回本集开头，
 * 于是「切软解」永远从第 0 秒开始——用户看到的是画面倒退，而不是接着刚才那一下继续。
 */
fun TvMainActivity.applyDecoderMode(position: Long = -1L, play: Boolean? = null) {
    if(screen!="player" && screen!="recommend") return
    val old=player ?: return
    val resumeAt=if(position>=0) position else currentPosition()
    val resumePlay=play ?: old.playWhenReady
    // 先把这一集的进度落盘：下面立刻把 player 置空，playEpisode 里那次 saveProgress 就取不到播放器了。
    saveProgress()
    playerListener?.let { old.removeListener(it) }; playerListener=null
    playerView?.player=null; player=null; playbackReady=false
    configuredPlayer=null
    // 释放**不能**放到 io 线程：`ExoPlayerImpl.release()` 第一句就是 `verifyApplicationThread()`，
    // 线程不符直接抛 `Player is accessed on the wrong thread`——早先那版把它裹在 `runCatching`
    // 里，异常被吞掉，旧播放器连同播放线程、解码器一起泄漏，还会和新播放器抢同一颗解码器。
    //
    // 留在主线程又不卡的办法是分两步：先 `stop()` + `clearMediaItems()`（只走内部消息，
    // 把 Renderer 上的 MediaCodec / AudioTrack 卸掉），再 `release()`（这时只剩线程收尾）。
    // 实测 `release()` 那 0.4–2 秒的大头就是等 Renderer 收干净，先 stop 之后基本归零。
    // 重入不需要额外标志位：`player` 上面已经置空，第二次点进来 `val old=player ?: return` 直接返回。
    val ticket=++generation
    val started=android.os.SystemClock.elapsedRealtime()
    runCatching { old.stop(); old.clearMediaItems() }
    runCatching { old.release() }
    android.util.Log.i("HGTV","Old player released in ${android.os.SystemClock.elapsedRealtime()-started}ms")
    // 这期间用户可能已经返回 / 换了别的剧：那一次页面重建会把 generation 推走。
    if(isDestroyed || generation!=ticket) return
    playEpisode(episodeIndex,resumeAt,resumePlay)
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
fun TvMainActivity.syncDanmakuClock() {
    val view=danmakuView ?: return
    val p=player
    if(p==null) {
        view.stopClock()
        return
    }
    val sample=runCatching { Triple(p.currentPosition,p.isPlaying,p.playbackParameters.speed) }.getOrNull()
    if(sample==null) {
        view.setClock(view.clockPositionMillis(),false)
        return
    }
    val readyPlaying=sample.second && runCatching { p.playbackState==Player.STATE_READY }.getOrDefault(false)
    val running=readyPlaying && foreground && !pausedForLifecycle
    view.setClock(sample.first,running,sample.third)
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
fun TvMainActivity.playbackAnalytics()=object: com.google.android.exoplayer2.analytics.AnalyticsListener {
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
/**
 * 建一个播放器（**不含挂 Surface，也不做构造后的配置**）。
 *
 * 分成两步是因为它现在需要**在 io 线程上建**：构造一次要 0.5 秒
 * （实测 `Stage player +509ms`，其中大头是 `TvRenderersFactory` → `MediaCodecList` 全表能力扫描），
 * 而它原先排在「取地址 + 本地准备」之后串行执行。两件事互不依赖，并起来省下半秒。
 *
 * 这个方法只碰播放器自己的状态与 `library` 里的偏好，**不碰任何 View**；
 * 挂 Surface 与构造后的配置都在主线程做，见 [attachPlayerView] / [configurePlayer]。
 */
private fun TvMainActivity.buildPlayer(): ExoPlayer {
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
        .setMediaCodecSelector(decoderSelector(playerDecoderMode(),this))
    // `setLooper` 显式钉死主线程：`ExoPlayer.Builder` 默认取「当前线程的 Looper，没有才退主线程」，
    // 而这个构造现在跑在 io 线程池里（没有 Looper，侥幸落到主线程）。一旦哪天 `io` 换成
    // HandlerThread，整个播放器就会认那条线程，主线程碰它立刻崩——不要依赖这个巧合。
    return ExoPlayer.Builder(this,renderers)
        .setLooper(android.os.Looper.getMainLooper())
        .setLoadControl(load)
        // 这两项走 Builder：构造前的设置不校验线程，能顺手在 io 线程做完，
        // 少两次回到主线程的配置（见 [configurePlayer]）。
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true
        )
        .setHandleAudioBecomingNoisy(true)
        .build()
}

/**
 * 构造之后的配置，**只能在主线程调用**。
 *
 * ExoPlayer 2.19.1 里 `addAnalyticsListener` / `setVideoFrameMetadataListener` 开头都是
 * `verifyApplicationThread()`，线程不符就抛 `Player is accessed on the wrong thread`。
 *（`setAudioAttributes` / `setHandleAudioBecomingNoisy` 同样如此，只是它们已经挪到
 * [buildPlayer] 的 Builder 上——构造前的设置不校验线程。）
 * 因此构造留在 io（那半秒是 `MediaCodecList` 扫描），配置回主线程——两边都很短，
 * 谁先到都不影响：这里用 [TvMainActivity.configuredPlayer] 保证同一个实例只配一次
 *（`addAnalyticsListener` 重复调用会把监听挂两遍，诊断数据会翻倍）。
 */
private fun TvMainActivity.configurePlayer(p: ExoPlayer) {
    // `p!==player`：这个实例已经被 release / 换解码方式拆掉了，不要再往它上面挂监听。
    if(p!==player || p===configuredPlayer) return
    // 诊断监听**正式包也挂**：只记账、不写日志，开销是几个 volatile 赋值。
    p.addAnalyticsListener(playbackDiagnostics())
    // 逐帧心跳：「画面还在走」的唯一硬证据，停滞看门狗靠它判活（见 [playbackFrameClock]）。
    p.setVideoFrameMetadataListener(playbackFrameClock())
    // 播放期间持一个 PARTIAL_WAKE_LOCK：屏幕常亮只管屏幕，CPU 该睡还是会睡。
    //
    // 电视盒子在暂停 / 后台几秒后进入 Doze，回来时连接已断，要重走 DNS + TLS + 取地址
    // ——用户读到的是「暂停一下再回来就放不了了」。`setWakeMode` 必须在应用线程调
    // （`ExoPlayerImpl` 里第一句就是 `verifyApplicationThread()`），因此放在这里而不是
    // [buildPlayer]（那边跑在 io 线程）。需要 `WAKE_LOCK` 权限，见 AndroidManifest。
    runCatching { p.setWakeMode(C.WAKE_MODE_NETWORK) }
    if(com.hgtv.star.tv.BuildConfig.DEBUG) p.addAnalyticsListener(playbackAnalytics())
    configuredPlayer=p
}

/** 把播放器挂到 `PlayerView` 上（主线程）。 */
private fun TvMainActivity.attachPlayerView(p: ExoPlayer) { playerView?.player=p }

/**
 * 起播前的并行预热：`player` 还没有就先建出来，让「建播放器」与「取地址 + 本地准备」同时在跑。
 *
 * 只在 io 线程调（见 `playEpisode` 的 `work { ... }`）。已经有播放器时是空操作——
 * 复用那条路本来就不重建播放器。
 */
private fun TvMainActivity.warmPlayer() {
    if(player!=null || playerDisposed) return
    // 见 [playerBuildLock]：并发的第二条路只等一次，不会建出第二个播放器。
    val built=synchronized(playerBuildLock) {
        if(player!=null || playerDisposed) null else buildPlayer().also { player=it }
    } ?: return
    // 配置必须在主线程（见 [configurePlayer]）。这里只是扔回主线程，不等结果：
    // 起播回调本身在主线程，那边会再调一次（幂等），配置不会丢。
    main.post { runCatching { if(!playerDisposed) configurePlayer(built) } }
}

/**
 * 在**独立线程**上开始建播放器，返回一个可以等的句柄。
 *
 * 为什么不直接在 [io] 上建（原来的写法）：那个池子只有 3 条线程，冷启动那一刻
 * 「读详情缓存 / 取推荐流 / 心跳 / 账号」同时在里面排队，建播放器被推后 300~400ms——
 * 日志上读起来像「`buildPlayer` 变慢了」，实际是在等线程。建播放器与网络无关，
 * 单独一条线程才不会排在别人后面。
 *
 * 仍然走 [warmPlayer] 那条加锁路径：与「起播回调里补建」并发时只会建出一个实例
 * （见 [playerBuildLock]）。
 */
fun TvMainActivity.buildPlayerAsync(): java.util.concurrent.Future<*> =
    // 这一处**保留 `Future`**而不是协程：调用方要用 `get(3s)` 带超时地等它
    //（见 `playEpisode` 里 `playerBuild?.get(3, SECONDS)`）。`Job` 没有「等 3 秒就放弃」
    // 的等价写法，硬套 `runBlocking { withTimeout }` 反而多一层阻塞。
    // 线程仍然是 `player` 车道，与改造前那条专用线程同一个预算。
    playerBuildWorker.submit { runCatching { warmPlayer() } }

/**
 * 把「首帧之前才发现很贵」的几处初始化提前做掉。
 *
 * 实测（S905L3B / 2G，`Stage sourceSet` 之后还要 3.1 秒才出首帧，而内核从
 * `vdec_init` 到 `first frame decoded` 只有 157ms）——也就是说那 3 秒全花在**解码之前**：
 *
 * * `MediaCodecList` 全表能力扫描（日志里一连二十条 `VideoCapabilities: Unsupported mime`，
 *   约 230ms）。`MediaCodecUtil` 对每个 mime 有缓存，先在后台问一次即可；
 * * `Mp4Extractor` 的类初始化（栈里是 `SefReader.<clinit>` → Guava `Splitter` / `CharMatcher`）；
 * * 样本解密的 AES 引擎首次 `Cipher.getInstance`。
 *
 * 三者都是一次性的，在 io 线程上做与「取地址 + 拉首批数据」完全并行。任何一步失败都不影响
 * 后续流程：这些只是让第一次少等，不是功能依赖。
 */
private fun TvMainActivity.primePlaybackClasses() {
    runCatching {
        // 片源全是 HEVC 8bit + AAC：先把这两条的解码器清单灌进 `MediaCodecUtil` 的缓存。
        val selector=decoderSelector(playbackDecoderMode,this)
        selector.getDecoderInfos("video/hevc",false,false)
        selector.getDecoderInfos("audio/mp4a-latm",false,false)
        // 顺手回答「本机有没有 HEVC 硬解」：这一趟本来就要枚举解码器表，再问一次是纯内存命中。
        // 结果只用于错误页提示（见 [probeHardwareHevcDecoder]），不自动改解码方式。
        probeHardwareHevcDecoder()
        // 抽提取器构造一次（`Mp4Extractor` 的构造会带动 `SefReader` 的静态初始化）。
        com.google.android.exoplayer2.extractor.mp4.Mp4Extractor()
    }.onFailure { android.util.Log.w("HGTV","起播预热跳过：${it.javaClass.simpleName} ${it.message}") }
}

/**
 * 探一次「本机有没有 HEVC 硬解」。**只能在 io 线程调**（`MediaCodecList` 是 Binder 调用，
 * 盒子上实测 1~3 秒，见 `PlaybackCapability` 的类注释）。
 *
 * 用 `MediaCodecUtil.getDecoderInfos`（ExoPlayer 自己的表，含别名与厂商 workaround），
 * 而不是裸 `MediaCodecList`：判据必须与选择器一致，否则会出现「预检说没有、选择器却给出一颗」。
 *
 * 结论**只用于界面提示**（错误页上的「切换软解」按钮、面板读数），不会自动改解码方式。
 */
private fun TvMainActivity.probeHardwareHevcDecoder() {
    // 判据只有一份，见 `PlaybackCapability.hasHardwareHevcDecoder`（同一套 ExoPlayer 解码器表）。
    // 顺手灌进 `:shared` 的内存缓存：手机端在错误页那一刻读的是它（不能在主线程扫 Binder）。
    com.hgtv.star.shared.data.PlaybackCapability.warmHardwareHevc(this)
    val available=com.hgtv.star.shared.data.PlaybackCapability.cachedHardwareHevc() ?: false
    hardwareHevcAvailable=available
    if(!available) android.util.Log.w("HGTV","No HEVC hardware decoder detected; user must switch to software decoding")
}

fun TvMainActivity.playEpisode(
    index: Int,
    position: Long=0,
    autoplay: Boolean=true,
    recovering: Boolean=false,
    prepared: RemoteVideo?=null,
    keepMedia: Boolean=false,
    clearDanmakuOnSwitch: Boolean=false,
) {
    val data=detail ?: return
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
    // `keepMedia`（同档重建）也要保住播放层：这一趟只为把解码器放掉重开，画面 / 弹幕 /
    // 控制层一个都不用换（见 [replayWithPlan]）。它和 `recovering` 是两件事——
    // 后者还包含降档、网络自愈、手动重试，那些真的需要重来一遍。
    val reuse=(screen=="player" || recommendEntry) && player!=null && playerView!=null && (!recovering || keepMedia) && !playError
    // 「这一集的数据也一并留着」：只有**还能用**的那一份才算数（已经 prepare 过、且没被
    // close）。预热被换出 / 已经关掉时读不出数据，必须落回「重新取地址 + 本地准备」那条路。
    // 此时视图仍然复用（`reuse` 看的是 `keepMedia`）——那一步不需要重建整棵树。
    val keep=keepMedia && video?.isReusable==true
    // 「已看完」标记只在**正式播放**里清掉。推荐页还没进正片（需求 3：进正片才算开始看），
    // 这时改本机记录就等于「只看了一眼推荐，历史里那部剧的已看完标记就没了」。
    if(!recommendEntry) library.setWatched(data.series.id,false)
    val selectedIndex=index.coerceIn(0,data.episodes.lastIndex)
    val targetId=data.episodes[selectedIndex]
    // 正式播放页先用 player 场景取当前集 + 后 5 集；更远的集在首帧稳定后 preload。
    // 推荐页的 6 个模型在起播 READY 后单独预取，
    // 窗口是「当前剧下一集 + 后 5 部不同剧」，不能混进这里。
    val modelQueue=if(recommendEntry) emptyList() else data.episodes.drop(selectedIndex+1).take(5)
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
    } else detachPlayerSurface(keepSession=true)
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
        // 「竖屏短剧全屏」的实现是**把画面转 90°**（见 [autoFullscreenRotated]）。
        //
        // 这一步对承载视频的那一层有硬要求：SurfaceView 是独立于窗口的系统图层，
        // 父视图的旋转不会作用到它的画面上，转完之后看到的只是被拉扁 / 错位的画面
        // （表现就是「横向拉伸」）。TextureView 是普通视图，跟着父级一起变换，才能真的转过来。
        //
        // 因此开关打开时改用 `surface_type=texture_view` 这一版布局；关掉时仍然用默认的
        // SurfaceView（更省电、4K 更稳，与改动前完全一致）。
        //
        // 判据是 [autoFullscreenAvailable]：电视上这个开关存着也不生效（见那里的说明），
        // 就不必为了它把整块画面换成更贵的 TextureView。
        val rotatedSurface=library.autoFullscreen && autoFullscreenAvailable()
        val view=(if(rotatedSurface) layoutInflater.inflate(R.layout.player_surface_texture,root,false) as PlayerView else PlayerView(this)).apply {
            useController=false; resizeMode=frameResizeMode(); isFocusable=false
            setKeepContentOnPlayerReset(true); setShutterBackgroundColor(Color.BLACK); setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        }
        // 播放器实例是进程内复用的：从别的页面回到播放页时 [player] 已经存在，
        // 这里必须把新一层的 PlayerView 接上去，否则画面没有输出（黑屏、只有声音）。
        view.player=player
        playerView=view; root.addView(view,FrameLayout.LayoutParams(-1,-1))
        bufferingDots=com.hgtv.star.shared.ui.LoadingDotsView(this).apply {
            visibility=View.GONE
        }
        root.addView(
            bufferingDots,
            FrameLayout.LayoutParams(-2,-2,Gravity.CENTER),
        )
        // 画面之上的一切装在同一层里：弹幕 / 手势层 / 顶部信息条 / 底部控制条。
        //
        // 只把视频转 90°（竖屏短剧全屏）时，这一层要跟着一起转，否则标题条、控制条、
        // 弹幕都还横在原方向上，和画面互相垂直。装成一层之后，「跟着转」只需要在
        // 这一层上做一次旋转 + 一次宽高对调（见 `applyOverlayGeometry`），
        // 里面所有控件（含后挂上来的手势层、触屏按键、快进提示）自动跟着走。
        val layer=FrameLayout(this).apply { clipChildren=false; clipToPadding=false }
        overlayLayer=layer
        root.addView(layer,FrameLayout.LayoutParams(-1,-1))
        // 画面缩放 / 竖屏短剧全屏按当前偏好落到这一层上
        //（100% 且不旋转时就是原来的铺满）。
        applyVideoGeometry()
        // 弹幕层压在画面之上、标题条与控制层之下：隐藏弹幕时整层不可见，也不参与绘制。
        // 播放位置与播放状态都从播放器现取，因此暂停 / 跳转 / 倍速都不会和画面脱节。
        val overlay=DanmakuView(this).apply {
            // 弹幕层在公共模块里，画布比例由这里注入（与改造前的 `designSize(1f)` 同一个值）。
            unitScale=designSize(1f)
            mode=library.danmakuMode
            // 字号、透明度与速度都是本机偏好（设置面板里调出来的），
            // 换集、换清晰度重建这一层时都要带上，否则会回退成默认档。
            textScale=library.danmakuScale
            textAlpha=library.danmakuAlpha
            speed=library.danmakuSpeed
            mergeRepeats=library.danmakuMerge
            mergeMinCount=library.danmakuMergeMin
            effectsEnabled=library.danmakuEffects
            // 时钟由宿主推进（见 [syncDanmakuClock]），绘制线程不再直接读播放器。
            setClock(0,false)
        }
        danmakuView=overlay
        layer.addView(overlay,FrameLayout.LayoutParams(-1,-1))
        // 最后一集底部的下一季提示：只在查到下一季时出现，不抢焦点、不打断播放。
        val notice=text("",18f,TvStyle.playerText).apply {
            gravity=Gravity.CENTER_VERTICAL or Gravity.START
            maxLines=1
            maxWidth=dp(520)
            ellipsize=TextUtils.TruncateAt.END
            setPadding(dp(18),dp(10),dp(18),dp(10))
            background=TvStyle.gradient(0x99000000.toInt(),0xCC000000.toInt()).apply {
                cornerRadius=dp(8).toFloat()
            }
            visibility=View.GONE
        }
        nextSeasonNotice=notice
        layer.addView(
            notice,
            FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.START).apply {
                leftMargin=dp(24)
                bottomMargin=dp(24)
            }
        )
        // 播放画面顶部的一条常显信息：**左 = 剧名 → 当前集数；右 = 网速 → 时间**。
        //
        // 四项各自独立开关（设置页「界面设置 → 播放显示」）。左两组贴左、右两组贴右，
        // 中间由左组自己的权重吃掉空出来的宽度：某一项关掉（`GONE`）时，
        // 剩下那一项自然补到它该在的位置（左侧仍靠左、右侧仍靠右），不会留下空档。
        //
        // 这一条**常驻**（不跟底部控制条一起收起），但不铺整条背景，避免画面上出现黑雾；
        // 文字固定用暖白（TvStyle.playerText）并保留自身阴影，不跟主题，
        // 否则浅色主题下会变成深色字、整条看不见。
        val info=row().apply {
            gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(78),0,dp(78),0)
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
        qualityOption=option("清晰度 ${playbackMaxQuality}P") { showPlaybackMenu(MENU_ROW_QUALITY) }
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
                val streamInfo=playbackSource.stream(
                    requestedId,
                    maxQuality,
                    detail?.playback ?: com.hgtv.star.core.PlaybackProfile(),
                    queue=modelQueue,
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
                val remote=try {
                    playbackSource.prepare(requestedId,streamInfo) {
                        // 调试包的故障注入：本地准备阶段直接失败（见 [DebugFaults]）。
                        if(BuildConfig.DEBUG && DebugFaults.mode(this@playEpisode)==DebugFaults.Mode.PREPARE_FAIL) {
                            throw PlaybackPrepareException("调试注入：视频索引不可用")
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
        val p=player ?: synchronized(playerBuildLock) { player ?: buildPlayer().also { player=it } }
        // 构造后的配置（音频属性 / 诊断监听 / 逐帧心跳）只能在主线程做，且是幂等的：
        // 预热线程那边 post 过来的那次可能还没跑到（见 [configurePlayer]）。
        configurePlayer(p)
        // 复用那条路不重建播放器，也不重建 PlayerView：这里补一次绑定是幂等的。
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
                    // 起播已经稳了：趁用户还没按菜单键，把播放设置面板先建好、画一遍
                    //（见 [warmPlaybackMenu]）。不预热的话第一次打开菜单仍要现建三十来枚按钮，
                    // 那一下弹幕会停半拍。
                    warmPlaybackMenu()
                    // 推荐页：这一部已经出画面，现在才轮到给相邻两部拉首集数据。
                    //
                    // 起播之前不预热（见 `warmRecommendDetail` 开头的 `playbackReady` 判断）：
                    // 那一段里预热只会往取地址的串行闸门后面排队，把正在等的这一次推到更后面。
                    // 因此这里必须**补一次**——`startRecommendEpisode` 调 `prefetchRecommendNeighbors`
                    // 时 `playbackReady` 刚被置成 false，那一次会被挡回去。
                    //
                    // 现在改成「排定 + 短延迟 300ms」：READY 只代表解出了轨道，
                    // 解码器还在出帧、buffer 还在填，立刻拉相邻几条会把这一部自己拖慢。
                    // 延迟期间用户滑走的话，`playRecommendAt` 会把这一次取消掉。
                    if(screen=="recommend") {
                        // READY 是首帧回调缺失时的兜底；真正的首次曝光在 onRenderedFirstFrame
                        // 里再提交一次，由共用上报器按短时间去重。
                        reportRecommendImpression()
                        scheduleRecommendPreload(slideCache.firstFrameDelay(),"ready")
                        scheduleRecommendDwell()
                        // 批量播放模型是第二优先级：先让相邻一部的详情 / 地址 / 首块跑起来，
                        // 避免 6 个模型请求与用户下一次换剧抢同一条下行。
                        main.postDelayed({
                            if(!isDestroyed && screen=="recommend") prefetchRecommendBatchModels()
                        },RECOMMEND_BATCH_MODEL_DELAY_MS)
                        // 冷启动续播时暂缓的那一次取流：画面已经出来了，现在取不会抢首帧。
                        fireRecommendFeed()
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
                        playEpisode(next,0,requestedAutoplay)
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
                applyResizeMode()
            }
            override fun onRenderedFirstFrame() {
                if(!active()) return
                markAutoTransitionFirstFrame(p.currentMediaItem?.mediaId.orEmpty())
                // 首帧的尺寸已经确定：竖屏短剧全屏在这之后再落一次
                //（`onVideoSizeChanged` 在个别软解 / 隧道模式下不一定先于首帧回调，这里兜一次底）。
                applyResizeMode()
                // 推荐页：新一集的第一帧真的上屏了，过渡黑场这时候才淡出（见 [revealRecommendCover]）。
                // 用「出帧」而不是 READY：READY 只代表解出了轨道，画面可能还要等几百毫秒。
                if(screen=="recommend") {
                    reportRecommendImpression()
                    revealRecommendCover()
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
        // 起点一次设定：`setMediaSource(source, startPositionMs)` 内部先摘空旧项、
        // 再按给定位置掩码时间线，等价于「`setMediaSource` + `seekTo`」，少一次
        // `onPositionDiscontinuity`（那条回调会连带刷新媒体会话与弹幕指针）。
        p.setMediaSource(source,position.coerceAtLeast(0)); p.prepare(); p.playWhenReady=foreground && requestedAutoplay && !pausedForLifecycle
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
                playEpisode(episodeIndex,position.coerceAtLeast(0),autoplay)
            }) return@work
        playerFailure(problem)
    })
}
