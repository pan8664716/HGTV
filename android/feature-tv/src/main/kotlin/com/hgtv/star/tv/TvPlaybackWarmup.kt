// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

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
internal fun TvMainActivity.primePlaybackClasses() {
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
internal fun TvMainActivity.probeHardwareHevcDecoder() {
    // 判据只有一份，见 `PlaybackCapability.hasHardwareHevcDecoder`（同一套 ExoPlayer 解码器表）。
    // 顺手灌进 `:shared` 的内存缓存：手机端在错误页那一刻读的是它（不能在主线程扫 Binder）。
    com.hgtv.star.shared.data.PlaybackCapability.warmHardwareHevc(this)
    val available=com.hgtv.star.shared.data.PlaybackCapability.cachedHardwareHevc() ?: false
    hardwareHevcAvailable=available
    if(!available) android.util.Log.w("HGTV","No HEVC hardware decoder detected; user must switch to software decoding")
}
