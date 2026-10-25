// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.data

import android.content.Context
import android.media.MediaCodecList
import android.os.Build

/**
 * 这台设备能解什么。
 *
 * ## 为什么只关心 HEVC
 *
 * 播放接口只下发两种编码（实测 `gear_des_key` 的第 2 段）：
 *
 * * `bytevc1` / `h265_hvc1` —— 就是 HEVC（H.265），系统 `MediaCodec` 普遍能硬解；
 * * `bytevc2` —— 字节自家的 BVC2，容器里 `frma=bvc2`，Android 上没有解码器。
 *
 * 因此「这台设备能不能看」完全等价于「能不能解 HEVC」，`device_level` 也就只有两档有意义
 * （见 [deviceLevel]）。BVC2 那条路无论怎么调档位都救不回来。
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
     * 档位按「能不能真的解」给，不按屏幕分辨率给：
     *
     * * 硬解 1080 → 1080：这类设备播 1080 是常态，没必要替用户降档；
     * * 硬解只到 720 → 720；
     * * 硬解只到 480 / 576，或只有软解 → 480（软解 720 在多数盒子上会掉帧）；
     * * 解不了 HEVC → 480，交给 `stream()` 的「兼容资源」兜底。
     */
    fun defaultQuality(context: Context): Int {
        val (height,hardware)=probe(context)
        if(height<=0 || !hardware) return 480
        return when {
            height>=1080 -> 1080
            height>=720 -> 720
            else -> 480
        }
    }

    /**
     * 播放模型请求里该报的 `device_level`。
     *
     * 只有两档有意义，因为服务端只按它挑「编码族」：
     *
     * | `device_level` | 360 / 480 / 540(576) / 720 | 1080 |
     * | --- | --- | --- |
     * | 2 / 3（官方值） | `bytevc2`（解不了） | `bytevc1`（HEVC） |
     * | 4 / 5 | `bytevc1`（HEVC） | `bytevc1`（HEVC） |
     *
     * * 能解 HEVC → 报 **4**：五档全是可解码的 HEVC，「默认 480P」才真的能生效；
     * * 解不了 HEVC → 报官方的 **3**：报 4 只会拿到一套同样解不了的 HEVC，
     *   报 3 至少与官方 App 行为一致（低档 BVC2 会被 `stream()` 过滤掉）。
     */
    fun deviceLevel(context: Context): Int = if(probe(context).first>0) 4 else 3

    /** 解不了 HEVC 的设备：界面据此把「设置清晰度」解释成「这台电视可能放不了」。 */
    fun canDecodeHevc(context: Context): Boolean = probe(context).first>0

    /**
     * 本机解码能力：**硬解一行、软解一行**（调试模式面板用）。
     *
     * 与 [probe] 的区别：那一份只回答「HEVC 能不能解、到多高」，用来决定清晰度；
     * 这一份把几种常见编码分开按硬解 / 软解列出来，回答「这台机器到底支持什么」——
     * 用户报「某部剧黑屏」时，第一件事就是看这台机器有没有对应的那颗解码器，
     * 以及它走的是硬解还是软解（软解 1080 在盒子上必掉帧）。
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
        val types=listOf(
            "video/hevc" to "HEVC",
            "video/avc" to "AVC",
            "video/x-vnd.on2.vp9" to "VP9",
            "video/av01" to "AV1",
            "video/mp4v-es" to "MP4V"
        )
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
                val height=runCatching {
                    info.getCapabilitiesForType(mime).videoCapabilities.supportedHeights.upper
                }.getOrDefault(0)
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
