// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.audio.AudioAttributes
import com.hgtv.star.core.AppConfig
import com.hgtv.star.shared.compat.isMeteredConnection
import com.hgtv.star.shared.compat.lowEndTv
import com.hgtv.star.shared.debug.PlaybackStartupTrace

/**
 * 电视端缓冲档位。
 *
 * 数值只用于构造 [DefaultLoadControl]，不替换 ExoPlayer 的缓冲算法。
 */
internal data class TvBufferProfile(
    val name: String,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int = AppConfig.Playback.TvBuffer.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
    val bufferForPlaybackAfterRebufferMs: Int =
        AppConfig.Playback.TvBuffer.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
    val targetBufferBytes: Int,
)

/**
 * 首帧耗时用于下一次播放器构建，避免在当前播放中更换 LoadControl。
 *
 * 首帧耗时同时包含网络、索引准备和解码器初始化，因此只作为设备 + 网络综合等级的代理指标；
 * 真正的缓冲策略仍由 [DefaultLoadControl] 执行。
 */
private object TvBufferTuning {
    private const val PREFS="hgtv-playback-buffer"
    private const val KEY_FIRST_FRAME_EWMA="first-frame-ewma-ms"
    private const val EWMA_OLD_WEIGHT=AppConfig.Playback.TvBuffer.EWMA_OLD_WEIGHT
    private const val EWMA_NEW_WEIGHT=AppConfig.Playback.TvBuffer.EWMA_NEW_WEIGHT

    fun firstFrameEwmaMs(context: android.content.Context): Long =
        runCatching {
            context.getSharedPreferences(PREFS,android.content.Context.MODE_PRIVATE)
                .getFloat(KEY_FIRST_FRAME_EWMA,0f).toLong()
        }.getOrDefault(0L)

    @Synchronized fun recordFirstFrame(context: android.content.Context,elapsedMs: Long) {
        if(elapsedMs !in 1L..AppConfig.Playback.TvBuffer.EWMA_SAMPLE_MAX_MS) return
        val prefs=context.getSharedPreferences(PREFS,android.content.Context.MODE_PRIVATE)
        val old=prefs.getFloat(KEY_FIRST_FRAME_EWMA,0f)
        val next=if(old<=0f) elapsedMs.toFloat() else old*EWMA_OLD_WEIGHT+elapsedMs*EWMA_NEW_WEIGHT
        prefs.edit().putFloat(KEY_FIRST_FRAME_EWMA,next).apply()
    }
}

internal fun TvMainActivity.resolveTvBufferProfile(): TvBufferProfile {
    val lowEnd=runCatching { lowEndTv() }.getOrDefault(true)
    val metered=runCatching { connectivity.isMeteredConnection() }.getOrDefault(true)
    val firstFrameMs=TvBufferTuning.firstFrameEwmaMs(this)
    return when {
        lowEnd -> TvBufferProfile(
            name="low-end",
            minBufferMs=AppConfig.Playback.TvBuffer.LOW_END_MIN_BUFFER_MS,
            maxBufferMs=AppConfig.Playback.TvBuffer.LOW_END_MAX_BUFFER_MS,
            targetBufferBytes=AppConfig.Playback.TvBuffer.LOW_END_TARGET_BYTES,
        )
        metered || firstFrameMs>=AppConfig.Playback.TvBuffer.SLOW_FIRST_FRAME_MS -> TvBufferProfile(
            name="slow-network",
            minBufferMs=AppConfig.Playback.TvBuffer.SLOW_MIN_BUFFER_MS,
            maxBufferMs=AppConfig.Playback.TvBuffer.SLOW_MAX_BUFFER_MS,
            targetBufferBytes=AppConfig.Playback.TvBuffer.SLOW_TARGET_BYTES,
        )
        firstFrameMs in 1L..AppConfig.Playback.TvBuffer.FAST_FIRST_FRAME_MAX_MS -> TvBufferProfile(
            name="fast",
            minBufferMs=AppConfig.Playback.TvBuffer.FAST_MIN_BUFFER_MS,
            maxBufferMs=AppConfig.Playback.TvBuffer.FAST_MAX_BUFFER_MS,
            targetBufferBytes=AppConfig.Playback.TvBuffer.FAST_TARGET_BYTES,
        )
        else -> TvBufferProfile(
            name="balanced",
            minBufferMs=AppConfig.Playback.TvBuffer.BALANCED_MIN_BUFFER_MS,
            maxBufferMs=AppConfig.Playback.TvBuffer.BALANCED_MAX_BUFFER_MS,
            targetBufferBytes=AppConfig.Playback.TvBuffer.BALANCED_TARGET_BYTES,
        )
    }
}

internal fun TvMainActivity.recordFirstFrameForBuffer(elapsedMs: Long) {
    TvBufferTuning.recordFirstFrame(this,elapsedMs)
}

internal fun TvMainActivity.setTvPlayerAudioFocus(player: ExoPlayer, enabled: Boolean) {
    runCatching {
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            enabled,
        )
    }
}

/**
 * 首帧指标更新后，旧播放器仍持有旧的 LoadControl。下一次真正起播时在这里释放，
 * 让新播放器按最新档位重建；当前正在播放的这一集不会被打断。
 */
internal fun TvMainActivity.releasePlayerForBufferMismatch() {
    val old=player ?: return
    val built=builtPlayerBufferProfile ?: return
    val wanted=resolveTvBufferProfile()
    if(built==wanted) return
    releaseTvPlaybackPool()
    android.util.Log.i(
        "HGTV-BUFFER",
        "Rebuilding player for buffer profile ${built.name} -> ${wanted.name}",
    )
    playerListener?.let { old.removeListener(it) }; playerListener=null
    playerView?.player=null
    playerBuildEpoch++
    player=null
    configuredPlayer=null
    builtPlayerDecoderMode=null
    builtPlayerBufferProfile=null
    playbackReady=false
    runCatching { old.stop(); old.clearMediaItems(); old.release() }
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
internal fun TvMainActivity.buildPlayer(
    mode: com.hgtv.star.core.VideoDecoderMode = playerDecoderMode(),
    profile: TvBufferProfile = resolveTvBufferProfile(),
    handleAudioFocus: Boolean = true,
): ExoPlayer {
    // 缓冲按时间算、字节上限只做保护。
    //
    // 实测片库（40 部 1080P 短剧）整文件码率 273~1727 kbps、视频 198~1650 kbps，
    // 全是 HEVC 8bit、24~30fps。按最高码率算，60 秒缓冲只占 13MB，
    // 因此这里留 24MB（约等于最高码率下 110 秒）就够，既不会提前截断，
    // 又把堆内存上限压在安全区。
    //
    // 时间仍是真正生效的那一维；档位只调整时间目标与字节保护上限。
    // `setPrioritizeTimeOverSizeThresholds(true)` 保证字节上限不会先于时间到顶。
    val load=DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            profile.minBufferMs,
            profile.maxBufferMs,
            profile.bufferForPlaybackMs,
            profile.bufferForPlaybackAfterRebufferMs,
        )
        .setTargetBufferBytes(profile.targetBufferBytes)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()
    android.util.Log.i(
        "HGTV-BUFFER",
        "profile=${profile.name} min=${profile.minBufferMs}ms max=${profile.maxBufferMs}ms"+
            " bytes=${profile.targetBufferBytes} firstFrameEwma=${TvBufferTuning.firstFrameEwmaMs(this)}ms",
    )
    val renderers=TvRenderersFactory(this)
        .setEnableDecoderFallback(true)
        .setMediaCodecSelector(decoderSelector(mode,this))
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
            handleAudioFocus,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()
        .also { PlaybackStartupTrace.point("player.built","tv") }
}
