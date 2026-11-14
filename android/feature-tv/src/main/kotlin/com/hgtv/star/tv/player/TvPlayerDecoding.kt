// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import com.google.android.exoplayer2.mediacodec.MediaCodecSelector
import com.hgtv.star.core.VideoDecoderMode
import com.hgtv.star.shared.debug.DebugFaults

/**
 * 按「硬件解码 / 软件解码」筛**视频**解码器。
 *
 * ## 音频为什么不过滤
 *
 * `DefaultRenderersFactory.buildAudioRenderers` 用的是**同一个** `MediaCodecSelector`
 * （视频 id 0、音频 id 1）。早先这里对任何 mimeType 一视同仁地过滤，于是硬件档下
 * AAC 只剩厂商那颗 `OMX.amlogic.aac.decoder`：它一旦初始化失败，候选表当场耗尽，
 * `setEnableDecoderFallback(true)` 也无处回落到 `c2.android.aac.decoder`，
 * 最终把一次**音频**故障报成 4001「解码失败」（2026-10-28 现场：电视盒子 Android 11）。
 *
 * 「硬件解码 / 软件解码」这个用户开关本来只管视频：软解 AAC 的开销可以忽略，
 * 没有任何理由为了让 S905 少跑一个 AAC 而牺牲兼容性。因此音频一律交回全表。
 *
 * ## 视频按 `hardwareAccelerated` 分档
 *
 * 不能只按 `softwareOnly` 筛软解：部分电视的软件解码器（例如 TCL 上的
 * `OMX.google.hevc.decoder`）会把 `isHardwareAccelerated()` 报成 false、
 * `isSoftwareOnly()` 也报成 false。只认 `softwareOnly` 时软解档会筛成空列表，
 * ExoPlayer 连视频解码器都不会创建，最终直接报 4005。
 *
 * 因此硬解档只收 `hardwareAccelerated`，软解档收其余所有实现；两档互补，不会漏项。
 *
 * ## secure / tunneled 组件
 *
 * 部分老电视只把 HEVC 放进 `MediaCodecList.ALL_CODECS`。普通硬解为空时会补查
 * tunneled 组件，但只接受不强制 tunneled、也不是 secure-required 的实现；
 * 强制 tunneled 必须显式开启隧道播放，不能混进普通 Surface 路径。
 *
 * ## 视频为什么不再 `ifEmpty { all }`
 *
 * 那一行是「静默降软解」：硬件档筛空就偷偷用软解跑，用户以为自己开着硬解。
 * 现在筛空就返回空列表 → ExoPlayer 抛 `DecoderQueryException`（4002）→
 * [TvMainActivity.playerFailure] 把用户带到错误页，并提示**自己**切换软件解码 / 换清晰度。
 * 起播前还有一道预检（[TvMainActivity.probeHardwareHevcDecoder]），结论只用于提示，
 * **不再自动改用户的解码方式**（需求：优先硬解，不支持时由用户手动换软解）。
 *
 * 只在创建播放器时生效；切换档位由 [TvMainActivity.applyDecoderMode] 重建播放器。
 */
internal fun decoderSelector(mode: VideoDecoderMode,app: android.content.Context)=MediaCodecSelector { mimeType,requiresSecureDecoder,requiresTunnelingDecoder ->
    val all=MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType,requiresSecureDecoder,requiresTunnelingDecoder)
    // 调试包的故障注入（见 [DebugFaults]）：正式包里 BuildConfig.DEBUG 是常量 false，
    // 整段会被 R8 删掉，因此线上不存在这条分支。
    if(BuildConfig.DEBUG) {
        when(DebugFaults.mode(app)) {
            // 一颗都不给：ExoPlayer 抛 DecoderQueryException → 4002 / 4005。
            DebugFaults.Mode.DECODER_NONE -> return@MediaCodecSelector emptyList()
            // 只给一颗**不存在的**解码器：初始化阶段必失败 → 4001，且 cause 里带着这个名字，
            // 正好用来验证上报里的 `failedDecoder=` 与 `decoders=` 两栏。
            DebugFaults.Mode.DECODER_BOGUS -> {
                if(!mimeType.startsWith("audio/")) return@MediaCodecSelector listOf(
                    com.google.android.exoplayer2.mediacodec.MediaCodecInfo.newInstance(
                        "hgtv.bogus.decoder","video/hevc","video/hevc",null,false,false,false,true,false
                    )
                )
            }
            else -> Unit
        }
    }
    // 音频不受「视频解码」档位影响（见上面的类注释）。
    if(!mimeType.startsWith("video/")) return@MediaCodecSelector all
    when(mode) {
        VideoDecoderMode.HARDWARE -> {
            val hardware=all.filter { it.hardwareAccelerated }
            if(hardware.isNotEmpty()) return@MediaCodecSelector hardware
            // 部分电视把 HEVC 硬解只放进 ALL_CODECS：组件声明支持 tunneled，但并未要求
            // 必须隧道播放。ExoPlayer 默认的 REGULAR_CODECS 会漏掉它；这类组件可以安全地
            // 走普通 Surface，强制 tunneled 的组件则由 relaxedTunneledHevcDecoders 排除。
            if(!requiresSecureDecoder && !requiresTunnelingDecoder &&
                mimeType.equals("video/hevc",ignoreCase=true)) {
                val relaxed=com.hgtv.star.shared.data.PlaybackCapability
                    .relaxedTunneledHevcDecoders()
                if(relaxed.isNotEmpty()) {
                    android.util.Log.w(
                        "HGTV",
                        "HEVC hardware hidden from REGULAR_CODECS; using non-required tunneled components="+
                            relaxed.joinToString(",") { it.name },
                    )
                    return@MediaCodecSelector relaxed
                }
            }
            hardware
        }
        VideoDecoderMode.SOFTWARE -> all.filter { !it.hardwareAccelerated }
    }
}
