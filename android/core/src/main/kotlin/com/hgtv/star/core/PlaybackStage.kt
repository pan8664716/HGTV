// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 播放失败的**阶段常量与标题表**：电视与手机共用这一份。
 *
 * ## 为什么阶段必须是常量
 *
 * 阶段是调试面板的归并键，也是错误页的标题。一旦掺进集号、解码器名、错误码，
 * 面板上同一类故障会碎成一堆各不相同的记录，「这台机器到底是解码器不行还是网络不行」
 * 就再也读不出来。
 *
 * ## 为什么标题也放在这里
 *
 * 同一个故障在两端必须是**同一句话**：用户在电视上看到「解码失败：有声音无画面」，
 * 换到手机上看到的却是「播放失败」，他描述问题的方式就跟着变了。
 * 早先两端各写一份字面量，手机上还只有一句笼统的「播放失败」。
 *
 * 判定「这次失败属于哪个阶段」要用 `PlaybackException.errorCode`，那一步在
 * `:shared` 的 `PlaybackFailures.stageOf` 里（`:core` 不依赖 media3）。
 */
object PlaybackStage {
    const val SOURCE = "playback_source"
    const val IO = "playback_io"
    const val PARSE = "playback_parse"
    const val PREPARE = "playback_prepare"
    const val DECODER_INIT = "playback_decoder_init"
    const val DECODER_QUERY = "playback_decoder_query"
    const val DECODING = "playback_decoding"
    const val DECODER_LIMITS = "playback_decoder_limits"
    const val DECODER_UNSUPPORTED = "playback_decoder_unsupported"
    const val CODEC_ERROR = "playback_codec_error"
    const val STALL_FRAME = "playback_stall_frame"
    const val STALL_BUFFER = "playback_stall_buffer"
    const val OOM = "playback_oom"
    const val OTHER = "playback_other"

    /** 全部阶段：单测按它校验「每一档都有标题、且互不重复」。 */
    val ALL = listOf(
        SOURCE, IO, PARSE, PREPARE, DECODER_INIT, DECODER_QUERY, DECODING,
        DECODER_LIMITS, DECODER_UNSUPPORTED, CODEC_ERROR, STALL_FRAME, STALL_BUFFER, OOM, OTHER,
    )

    /** 阶段 → 一句稳定的话（错误页 / 调试面板标题）。未知阶段退回「播放失败」。 */
    fun titleOf(stage: String): String = when (stage) {
        SOURCE -> "播放失败：资源不可用"
        IO -> "播放失败：读取视频出错"
        PARSE -> "播放失败：视频封装无法解析"
        PREPARE -> "播放失败：播放地址准备失败"
        DECODER_INIT -> "解码失败：解码器初始化失败"
        DECODER_QUERY -> "解码失败：找不到可用解码器"
        DECODING -> "解码失败：解码过程中出错"
        DECODER_LIMITS -> "解码失败：视频超出设备解码能力"
        DECODER_UNSUPPORTED -> "解码失败：设备不支持该编码"
        CODEC_ERROR -> "解码失败：解码器报错"
        STALL_FRAME -> "解码失败：有声音无画面"
        STALL_BUFFER -> "播放失败：长时间无法起播"
        OOM -> "播放失败：内存不足"
        else -> "播放失败"
    }
}
