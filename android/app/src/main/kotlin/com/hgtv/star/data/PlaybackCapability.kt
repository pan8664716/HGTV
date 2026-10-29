// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.data

import android.content.Context
import android.media.MediaCodecList
import android.os.Build

/**
 * 这台设备能解什么。
 *
 * ## 只有一种编码：HEVC
 *
 * 全站视频只有 HEVC（H.265，接口标签 `bytevc1` / `h265_hvc1`）。早期那些兼容维度
 * （`bytevc2`、AVC、VP9、AV1）已经全部撤掉：平台只下发 HEVC，而 `bytevc2` 在 Android
 * 上没有解码器。因此「这台设备能不能看」完全等价于「能不能硬解 HEVC」，
 * 最多再退一步「有没有一个能跑的 HEVC 软解」。
 *
 * ## 为什么要缓存
 *
 * [MediaCodecList] 在电视盒子上是 Binder 调用，实测能卡 1–3 秒（同一个坑见
 * `MainActivityPlaybackMenu` 里把 `AudioManager.getDevices()` 挪出主线程那段注释）。
 * 因此**每个安装只探一次**，结果落盘；之后都是纯内存读。
 *
 * 系统升级、换解码器、或者设备「声称支持但实际解不了」这些情况由 [invalidate] 处理：
 * 播放失败时清掉缓存，下次按真实结果重新探。
 */
object PlaybackCapability {
    private const val PREFS="hgtv-playback"
    private const val KEY_HEIGHT="hevc-max-height"
    private const val KEY_HARDWARE="hevc-hardware"
    /** 还没探过（0 不是合法的可解高度）。 */
    private const val UNKNOWN=0

    /**
     * 默认清晰度上限。
     *
     * **只在用户还没自己选过清晰度时用**（判据见 `Library.qualityChosenByUser`）：
     * 一旦用户在设置里点过任意一档，这个值就不再覆盖他的选择。
     *
     * 档位按「硬解能真的解到哪」给，不按屏幕分辨率给：
     *
     * 档位表是五档（360 / 480 / 576 / 720 / 1080，见 `PlaybackQuality.options`）：
     *
     * * 硬解 HEVC 到 1080 → 1080：这类设备播 1080 是常态，没必要替用户降档；
     * * 硬解只到 720 → 720；
     * * 硬解只到 576 → 576；
     * * 硬解只到 480 → 480；更低 → 360；
     * * 没有 HEVC 硬解 → 480：软解 1080 在盒子上必掉帧，720 也不稳，从中间偏低起。
     *
     * 注意这里只是**默认值**。本机没有硬解时不会自动切软解——那是用户在播放设置里
     * 自己点的（见 `PlaybackDegradation.manualHint`）。
     */
    fun defaultQuality(context: Context): Int {
        val (height,hardware)=probe(context)
        if(height<=0 || !hardware) return 480
        return when {
            height>=1080 -> 1080
            height>=720 -> 720
            height>=576 -> 576
            height>=480 -> 480
            else -> 360
        }
    }

    /** 解不了 HEVC 的设备：界面据此把「设置清晰度」解释成「这台电视可能放不了」。 */
    fun canDecodeHevc(context: Context): Boolean = probe(context).first>0

    /**
     * 本机有没有 **HEVC 硬件解码器**。
     *
     * 只回答 HEVC：全站只有这一种编码，AVC 硬解有没有已经与播放无关。
     *
     * 与 [canDecodeHevc] 的区别：那个读的是落盘的能力探测（「最多解到多高」），
     * 这个用 ExoPlayer 自己的解码器表枚举一次（含别名与厂商 workaround），
     * 判据与选择器**逐条一致**，因此适合用来决定「要不要在错误页上提示用户换软解」。
     *
     * 调用方必须在后台线程（`MediaCodecList` 是 Binder 调用，盒子上能卡 1~3 秒）。
     */
    fun hasHardwareHevcDecoder(context: Context): Boolean = runCatching {
        androidx.media3.exoplayer.mediacodec.MediaCodecUtil
            .getDecoderInfos("video/hevc",false,false)
            .any { !it.softwareOnly }
    }.getOrDefault(true)

    /**
     * 本机解码能力：**硬解一行、软解一行**（调试模式面板用）。
     *
     * 与 [probe] 的区别：那一份只回答「HEVC 能不能解、到多高」，用来决定清晰度；
     * 这一份按硬解 / 软解两行列出 HEVC 解码器，回答「这台机器到底能不能硬解」——
     * 用户报「某部剧黑屏」时，第一件事就是看这台机器有没有那颗硬解，
     * 以及它会不会走到软解（软解 1080 在盒子上必掉帧）。
     *
     * 结果**只缓存在内存**：它是排障读数，不值得落盘；探测本身在调用方的后台线程上跑
     * （`MediaCodecList` 在盒子上是 Binder 调用，能卡 1–3 秒）。
     */
    data class DebugCapability(val hardware: String,val software: String)

    @Volatile private var capabilitySummary: DebugCapability?=null

    fun debugSummary(): DebugCapability {
        capabilitySummary?.let { return it }
        val value=runCatching { scanSummary() }.getOrElse { DebugCapability("探测失败","探测失败") }
        capabilitySummary=value
        return value
    }

    /** 调试面板重开时重新探一次（系统升级 / 换解码器之后读数会变）。 */
    fun invalidateSummary() { capabilitySummary=null }

    private data class CodecBrief(val name: String,val hardware: Boolean,val maxHeight: Int)

    private fun scanSummary(): DebugCapability {
        // 只列 HEVC：全站只有这一种编码，面板上列一堆用不上的解码器只会让人误以为「还有别的路」。
        val types=listOf("video/hevc" to "HEVC")
        val infos: List<android.media.MediaCodecInfo> = if(Build.VERSION.SDK_INT>=21) {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList()
        } else {
            (0 until MediaCodecList.getCodecCount()).mapNotNull {
                runCatching { MediaCodecList.getCodecInfoAt(it) }.getOrNull()
            }
        }
        val hard=mutableListOf<String>()
        val soft=mutableListOf<String>()
        types.forEach { (mime,label) ->
            val found=infos.filter { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mime,true) }
            }.map { info ->
                val hardware=if(Build.VERSION.SDK_INT>=29) info.isHardwareAccelerated
                    else !info.name.lowercase().let { it.startsWith("omx.google.") || it.startsWith("c2.android.") }
                // `getVideoCapabilities()` / `supportedHeights` 是 API 21 才有的。4.4 上直接调用会
                // 抛 `NoSuchMethodError`——虽然外面套着 `runCatching` 不会崩，但**每颗解码器、每种
                // mime 各抛一次**（5 × N 次异常构造 + 栈回溯），在 2G 盒子上是几百毫秒的白费。
                // 先判版本，21 以下整段跳过（高度未知填 0，面板显示 `?`）。
                val height=if(Build.VERSION.SDK_INT>=21) runCatching {
                    info.getCapabilitiesForType(mime).videoCapabilities.supportedHeights.upper
                }.getOrDefault(0) else 0
                CodecBrief(info.name,hardware,height)
            }
            // 每类只报「最好的一颗」：面板一行放不下整串解码器名，用户要的是「能不能解、到多高」。
            found.filter { it.hardware }.maxByOrNull { it.maxHeight }?.let { best ->
                hard+="$label ${if(best.maxHeight>0) best.maxHeight else "?"}"
            }
            found.filterNot { it.hardware }.maxByOrNull { it.maxHeight }?.let { best ->
                soft+="$label ${if(best.maxHeight>0) best.maxHeight else "?"}"
            }
        }
        return DebugCapability(
            hardware=if(hard.isEmpty()) "无" else hard.joinToString(" "),
            software=if(soft.isEmpty()) "无" else soft.joinToString(" ")
        )
    }

    /** 解码失败之后清掉缓存，下次重新探测（见 `MainActivity.playerFailure` 的解码分支）。 */
    fun invalidate(context: Context) {
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
            .remove(KEY_HEIGHT).remove(KEY_HARDWARE).apply()
    }

    private fun probe(context: Context): Pair<Int,Boolean> {
        val prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        val cached=prefs.getInt(KEY_HEIGHT,UNKNOWN)
        if(cached!=UNKNOWN) return cached to prefs.getBoolean(KEY_HARDWARE,false)
        val (height,hardware)=scan()
        prefs.edit().putInt(KEY_HEIGHT,height).putBoolean(KEY_HARDWARE,hardware).apply()
        return height to hardware
    }

    /**
     * 真正扫一遍解码器。
     *
     * 5.0 及以下没有 `MediaCodecList.REGULAR_CODECS`（API 21 才有），用
     * `createDecoderByType` 兜底：能创建出来就说明「至少有一个」，名字用来分软硬解。
     * 那一档读不到能力上限，按 1080 处理——真正的上限由运行期失败兜底（见 [invalidate]）。
     */
    private fun scan(): Pair<Int,Boolean> {
        if(Build.VERSION.SDK_INT>=21) {
            val infos=runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }
                .getOrDefault(emptyArray())
            var best=0
            var hardware=false
            for(info in infos) {
                if(info.isEncoder) continue
                if(!info.supportedTypes.any { it.equals("video/hevc",ignoreCase=true) }) continue
                // `OMX.google.*` / `c2.android.*` 是软解，其余（qcom / exynos / mtk / amlogic …）按硬解算。
                // API 29 起有 `isHardwareAccelerated`，更准，优先用它。
                val isHardware=if(Build.VERSION.SDK_INT>=29) info.isHardwareAccelerated
                    else !info.name.lowercase().let { it.startsWith("omx.google.") || it.startsWith("c2.android.") }
                val height=runCatching {
                    info.getCapabilitiesForType("video/hevc").videoCapabilities.supportedHeights.upper
                }.getOrDefault(0)
                if(isHardware) {
                    if(height>best) best=height
                    hardware=true
                } else if(!hardware && height>best) {
                    // 只有软解时也要记下上限：`defaultQuality` 会因此落到 480。
                    best=height
                }
            }
            return best to hardware
        }
        // 4.4 / 5.0 的 `MediaCodec` 还没有 `AutoCloseable`（API 21 才加），不能用 `use`：
        // 这里手动 release，否则会漏一个解码器实例（部分 ROM 的实例数是有限的）。
        val codec=runCatching { android.media.MediaCodec.createDecoderByType("video/hevc") }.getOrNull()
            ?: return 0 to false
        val name=runCatching { codec.name }.getOrDefault("")
        runCatching { codec.release() }
        if(name.isBlank()) return 0 to false
        val lower=name.lowercase()
        val isHardware=!lower.startsWith("omx.google.") && !lower.startsWith("c2.android.")
        return 1080 to isHardware
    }
}
