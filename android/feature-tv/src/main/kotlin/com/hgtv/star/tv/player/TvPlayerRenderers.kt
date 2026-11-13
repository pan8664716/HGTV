// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.content.Context
import android.os.Handler
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.Renderer
import com.google.android.exoplayer2.audio.AudioCapabilities
import com.google.android.exoplayer2.audio.AudioSink
import com.google.android.exoplayer2.audio.DefaultAudioSink
import com.google.android.exoplayer2.audio.DefaultAudioTrackBufferSizeProvider
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector
import com.google.android.exoplayer2.video.MediaCodecVideoRenderer
import com.google.android.exoplayer2.video.VideoRendererEventListener

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
 * 其余参数与 [DefaultRenderersFactory] 的默认实现保持一致，
 * 尤其是 `setAudioCapabilities`：透传能力少声明一项，HDMI 直通的片源就会退回 PCM。
 */
internal class TvRenderersFactory(context: Context): DefaultRenderersFactory(context) {
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
                DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
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
