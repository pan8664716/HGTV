// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.media

import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.analytics.AnalyticsListener
import com.hgtv.star.shared.debug.PlaybackStartupTrace

/**
 * 播放现场的「解码事实」：解码器名、初始化耗时、编码格式、丢帧、音频欠载。
 *
 * ## 为什么需要它
 *
 * 「有声音没画面」「花屏」「卡住」这三类问题，光看播放状态分不出来：位置在走、声音在响，
 * 解码器却可能根本没起来、或者选错了（软解硬解混用）、或者片源的编码超出这台机器的能力。
 * 电视端早就在收集这几项（见 `feature-tv` 的 `PlaybackDiag` + `playbackDiagnostics()`），
 * 手机端一直只有「硬件 / 软件」这一格设置值——用户报问题时手上没有任何现场。
 *
 * ## 为什么放在 `:shared`
 *
 * 它只依赖 ExoPlayer 的 [AnalyticsListener] 与 [Format]，与版面无关；两端将来都能挂同一份。
 * 电视端的 `PlaybackDiag` 还混着「失败阶段 / 上报去重 / 首帧看门狗」，那些留在各端；
 * 这里只留**可观测的播放事实**，是抽离的第一层。
 *
 * ## 开销
 *
 * 所有回调都在播放线程上，只写 volatile 字段或做几次整数累加；[line] 只在调试面板
 * 每秒刷新时调用一次。没有 IO、没有分配（[line] 拼一个短字符串）。
 */
class PlaybackMetrics {

    @Volatile var decoderName: String = ""
        private set
    @Volatile var initMs: Long = -1
        private set
    @Volatile var mime: String = ""
        private set
    @Volatile var codecs: String = ""
        private set
    @Volatile var container: String = ""
        private set
    @Volatile var width: Int = 0
        private set
    @Volatile var height: Int = 0
        private set
    @Volatile var frameRate: Float = 0f
        private set
    @Volatile var bitrate: Int = 0
        private set
    @Volatile var rotation: Int = 0
        private set
    @Volatile var droppedFrames: Int = 0
        private set
    @Volatile var underruns: Int = 0
        private set
    /** 最近一次解码器报错（原文截断，面板上只放一行）。 */
    @Volatile var lastCodecError: String = ""
        private set
    /** 第一次出帧的时刻（`elapsedRealtime`）；0 = 这一集还没出过画面。 */
    @Volatile var firstFrameAt: Long = 0L
        private set

    /** 换集 / 换源：上一集的解码器与格式不再成立。 */
    fun reset() {
        decoderName = ""
        initMs = -1
        mime = ""
        codecs = ""
        container = ""
        width = 0
        height = 0
        frameRate = 0f
        bitrate = 0
        rotation = 0
        droppedFrames = 0
        underruns = 0
        lastCodecError = ""
        firstFrameAt = 0L
    }

    fun format(value: Format?) {
        if (value == null) return
        // 换了一条流（换集 / 换清晰度）：上一集的丢帧与欠载不能算到这一集头上，
        // 「出过帧没有」也要重新计——否则看门狗会把这一集的黑屏当成已经出过画面而放过。
        val changed = mime != value.sampleMimeType.orEmpty() || width != value.width ||
            height != value.height || codecs != value.codecs.orEmpty()
        if (changed) {
            droppedFrames = 0
            underruns = 0
            firstFrameAt = 0L
            lastCodecError = ""
        }
        mime = value.sampleMimeType.orEmpty()
        codecs = value.codecs.orEmpty()
        container = value.containerMimeType.orEmpty()
        width = value.width
        height = value.height
        frameRate = value.frameRate
        bitrate = value.bitrate
        rotation = value.rotationDegrees
    }

    fun decoder(name: String, durationMs: Long) {
        decoderName = name
        initMs = durationMs
    }

    fun dropped(frames: Int) {
        droppedFrames += frames
    }

    fun underrun() {
        underruns++
    }

    fun codecError(message: String) {
        lastCodecError = message.take(160)
    }

    fun firstFrame(now: Long) {
        if (firstFrameAt == 0L) firstFrameAt = now
    }

    /** 编码族短标签：`hevc` / `avc` / `vp9` / `av1`，取不到时给 mime 的后半段。 */
    val codecFamily: String
        get() = when {
            mime.contains("hevc", true) || codecs.startsWith("hvc", true) ||
                codecs.startsWith("hev", true) -> "hevc"
            mime.contains("avc", true) || codecs.startsWith("avc", true) -> "avc"
            mime.contains("vp9", true) -> "vp9"
            mime.contains("av01", true) -> "av1"
            mime.isEmpty() -> "-"
            else -> mime.substringAfter('/').take(8)
        }

    /**
     * 面板上那一行：`解码器=c2.qti.hevc.decoder init=42ms codec=hvc1 1920x1080@30 rot=0 br=2400000 丢帧=3 欠载=0`。
     *
     * 只放**排查必需**的项：解码器名（判断软硬解）、初始化耗时（判断能力边界）、
     * 编码与分辨率（判断片源）、丢帧与欠载（判断卡顿来源）。
     */
    fun line(): String = buildString {
        append("解码器=").append(decoderName.ifEmpty { "-" })
        if (initMs >= 0) append(" init=").append(initMs).append("ms")
        append(" codec=").append(codecs.ifEmpty { codecFamily })
        if (mime.isNotEmpty()) append(" mime=").append(mime)
        if (width > 0) append(' ').append(width).append('x').append(height)
        // 帧率只留一位小数：`29.999594` 那种原始值是解码器报的浮点，面板上又长又假精确。
        if (frameRate > 0f) append('@').append(String.format("%.1f", frameRate))
        if (rotation != 0) append(" rot=").append(rotation)
        if (bitrate > 0) append(" br=").append(bitrate)
        append(" 丢帧=").append(droppedFrames).append(" 欠载=").append(underruns)
        if (lastCodecError.isNotEmpty()) append(" 解码错误=").append(lastCodecError)
    }

    /**
     * 挂到播放器上（幂等：同一个播放器重复挂同一份监听没有副作用，ExoPlayer 内部按实例去重）。
     *
     * 只取「解码与出帧」相关的回调，不碰状态机——播放状态由各端自己的 `Player.Listener` 管。
     */
    fun attach(player: ExoPlayer) {
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: com.google.android.exoplayer2.decoder.DecoderReuseEvaluation?,
            ) {
                format(format)
            }

            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializationDurationMs: Long,
            ) {
                decoder(decoderName, initializationDurationMs)
            }

            override fun onVideoCodecError(
                eventTime: AnalyticsListener.EventTime,
                videoCodecError: Exception,
            ) {
                codecError(videoCodecError.message.orEmpty().ifEmpty { videoCodecError.javaClass.simpleName })
            }

            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long,
            ) {
                dropped(droppedFrames)
            }

            override fun onAudioUnderrun(
                eventTime: AnalyticsListener.EventTime,
                bufferSize: Int,
                bufferSizeMs: Long,
                elapsedSinceLastFeedMs: Long,
            ) {
                underrun()
            }

            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long,
            ) {
                firstFrame(android.os.SystemClock.elapsedRealtime())
                PlaybackStartupTrace.firstFrame("phone.metrics")
            }
        })
    }
}
