// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.audio.AudioAttributes

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
internal fun TvMainActivity.buildPlayer(mode: com.hgtv.star.core.VideoDecoderMode = playerDecoderMode()): ExoPlayer {
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
            true
        )
        .setHandleAudioBecomingNoisy(true)
        .build()
}
