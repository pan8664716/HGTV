// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data
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
    private const val KEY_SCHEMA="hevc-capability-schema"
    private const val SCHEMA=2
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
     * 档位表是五档（360 / 480 / 540 / 720 / 1080，见 `PlaybackQuality.options`）：
     *
     * * 硬解 HEVC 到 1080 → 1080：这类设备播 1080 是常态，没必要替用户降档；
     * * 硬解只到 720 → 720；
     * * 硬解只到 540 → 540；
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
            height>=540 -> 540
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
     * 这个用平台 `ALL_CODECS` 扫一次，能看到只在 secure / tunneled 路径注册的组件；
     * 选择器随后会排除 secure-required，并只接受不强制 tunneled 的普通硬解组件。
     *
     * 调用方必须在后台线程（`MediaCodecList` 是 Binder 调用，盒子上能卡 1~3 秒）。
     */
    fun hasHardwareHevcDecoder(context: Context): Boolean {
        val infos=runCatching { platformHevcInfos() }.getOrNull() ?: return true
        return infos.any { it.hardware && !it.secureRequired }
    }

    /**
     * [hasHardwareHevcDecoder] 的**内存缓存版**：给「随时可能被调用、但绝不能卡 UI」的地方用。
     *
     * 未探过时返回 `null`，调用方按「不知道」处理（宁可少给一颗按钮，也不要在主线程上
     * 做一次 1~3 秒的 Binder 扫描）。预热见 [warmHardwareHevc]。
     */
    @Volatile private var hardwareHevcKnown: Boolean?=null
    @Volatile private var relaxedTunneledHevcInfos: List<com.google.android.exoplayer2.mediacodec.MediaCodecInfo>?=null

    fun cachedHardwareHevc(): Boolean? = hardwareHevcKnown

    /**
     * 后台预热 [hasHardwareHevcDecoder] 的结果。
     *
     * 调用方**必须在后台线程**。它只是把结果放进内存，之后 UI 线程读
     * [cachedHardwareHevc] 就是一次纯内存读。
     */
    fun warmHardwareHevc(context: Context) {
        hardwareHevcKnown=hasHardwareHevcDecoder(context)
        relaxedTunneledHevcInfos=runCatching { scanRelaxedTunneledHevc() }.getOrNull()
    }

    /** 解码器表变了（解码失败之后）把内存缓存一起作废，下次预热重新探。 */
    fun invalidateHardwareHevc() {
        hardwareHevcKnown=null
        relaxedTunneledHevcInfos=null
        platformCodecs=null
        capabilitySummary=null
        clearExoDecoderCache()
    }

    /**
     * 只返回“支持 tunneled、但不要求必须 tunneled”的硬件解码器。
     *
     * 部分电视把这类组件只放进 `ALL_CODECS`，ExoPlayer 默认查 `REGULAR_CODECS` 时看不到；
     * 但它们仍可在普通 Surface 上工作，不能和 `tunneled-playback required=true` 混为一谈。
     * 强制 tunneled 的组件必须走显式隧道播放，否则 configure 会失败。
     */
    fun relaxedTunneledHevcDecoders(): List<com.google.android.exoplayer2.mediacodec.MediaCodecInfo> {
        relaxedTunneledHevcInfos?.let { return it }
        val value=runCatching { scanRelaxedTunneledHevc() }.getOrDefault(emptyList())
        relaxedTunneledHevcInfos=value
        return value
    }

    /**
     * 平台 `ALL_CODECS` 里的普通 HEVC 解码器，按硬解 / 软解筛选。
     *
     * ExoPlayer 的默认查询在部分车机上会返回空表，即使平台已经注册了
     * `c2.qti.hevc.decoder` 这类组件。这里直接把平台表转换成 ExoPlayer 可消费的
     * [com.google.android.exoplayer2.mediacodec.MediaCodecInfo]，作为选择器最后一层兜底。
     *
     * 只返回能在普通 Surface 上工作的组件：secure-required 只能处理 DRM，
     * tunnel-required 必须显式开启隧道播放。调用方仍须在后台线程调用。
     */
    fun platformHevcDecoders(hardware: Boolean): List<com.google.android.exoplayer2.mediacodec.MediaCodecInfo> {
        return platformHevcInfos()
            .asSequence()
            .filter { !it.secureRequired && !it.tunnelRequired }
            .filter { it.hardware==hardware }
            .mapNotNull { it.exoInfo }
            .distinctBy { it.name }
            .toList()
    }

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
    @Volatile private var platformCodecs: List<PlatformHevcCodec>?=null

    fun debugSummary(): DebugCapability {
        capabilitySummary?.let { return it }
        val value=runCatching { scanSummary() }.getOrElse { DebugCapability("探测失败","探测失败") }
        capabilitySummary=value
        return value
    }

    /** 调试面板重开时重新探一次（系统升级 / 换解码器之后读数会变）。 */
    fun invalidateSummary() {
        capabilitySummary=null
        platformCodecs=null
    }

    private data class PlatformHevcCodec(
        val name: String,
        val hardware: Boolean,
        val maxHeight: Int,
        val tunnelRequired: Boolean,
        val secureRequired: Boolean,
        val exoInfo: com.google.android.exoplayer2.mediacodec.MediaCodecInfo?,
    )

    private fun scanSummary(): DebugCapability {
        val found=platformHevcInfos()
        fun label(best: PlatformHevcCodec?): String {
            best ?: return "无"
            val flags=buildString {
                if(best.tunnelRequired) append(" tunnel")
                if(best.secureRequired) append(" secure")
            }
            return "HEVC ${if(best.maxHeight>0) best.maxHeight else "?"}$flags"
        }
        return DebugCapability(
            hardware=label(found.filter { it.hardware }.maxByOrNull { it.maxHeight }),
            software=label(found.filterNot { it.hardware }.maxByOrNull { it.maxHeight }),
        )
    }

    /** 解码失败之后清掉缓存，下次重新探测（见 `TvMainActivity.playerFailure` 的解码分支）。 */
    fun invalidate(context: Context) {
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
            .remove(KEY_HEIGHT).remove(KEY_HARDWARE).remove(KEY_SCHEMA).apply()
        platformCodecs=null
        capabilitySummary=null
        clearExoDecoderCache()
    }

    private fun probe(context: Context): Pair<Int,Boolean> {
        val prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        if(prefs.getInt(KEY_SCHEMA,0)!=SCHEMA) {
            prefs.edit().remove(KEY_HEIGHT).remove(KEY_HARDWARE).putInt(KEY_SCHEMA,SCHEMA).apply()
        }
        val cached=prefs.getInt(KEY_HEIGHT,UNKNOWN)
        if(cached!=UNKNOWN) return cached to prefs.getBoolean(KEY_HARDWARE,false)
        val scanned=scan() ?: return UNKNOWN to false
        val (height,hardware)=scanned
        prefs.edit()
            .putInt(KEY_SCHEMA,SCHEMA)
            .putInt(KEY_HEIGHT,height)
            .putBoolean(KEY_HARDWARE,hardware)
            .apply()
        return height to hardware
    }

    /**
     * 真正扫一遍解码器。
     *
     * 5.0 及以下没有 `MediaCodecList.REGULAR_CODECS`（API 21 才有），用
     * `createDecoderByType` 兜底：能创建出来就说明「至少有一个」，名字用来分软硬解。
     * 那一档读不到能力上限，按 1080 处理——真正的上限由运行期失败兜底（见 [invalidate]）。
     */
    private fun scan(): Pair<Int,Boolean>? {
        val infos=runCatching { platformHevcInfos() }.getOrNull() ?: return null
        if(infos.isEmpty()) return 0 to false
        var best=0
        var hardware=false
        for(codec in infos) {
            // secure-required 组件只能给 DRM 内容，不能拿来证明普通片源可解。
            if(codec.secureRequired) continue
            if(codec.hardware) {
                hardware=true
                if(codec.maxHeight>best) best=codec.maxHeight
            } else if(!hardware && codec.maxHeight>best) {
                best=codec.maxHeight
            }
        }
        if(hardware && best<=0) best=1080
        return best to hardware
    }

    private fun platformHevcInfos(): List<PlatformHevcCodec> {
        platformCodecs?.let { return it }
        val value=scanPlatformHevc()
        platformCodecs=value
        return value
    }

    private fun scanPlatformHevc(): List<PlatformHevcCodec> {
        if(Build.VERSION.SDK_INT<21) {
            // 4.4 / 5.0 的 `MediaCodec` 还没有 `AutoCloseable`（API 21 才加），不能用 `use`。
            val codec=runCatching { android.media.MediaCodec.createDecoderByType("video/hevc") }.getOrNull()
                ?: return emptyList()
            val name=runCatching { codec.name }.getOrDefault("")
            runCatching { codec.release() }
            if(name.isBlank()) return emptyList()
            val lower=name.lowercase()
            val hardware=!lower.startsWith("omx.google.") && !lower.startsWith("c2.android.")
            val exoInfo=runCatching {
                com.google.android.exoplayer2.mediacodec.MediaCodecInfo.newInstance(
                    name,"video/hevc","video/hevc",null,hardware,!hardware,hardware,false,false,
                )
            }.getOrNull()
            return listOf(PlatformHevcCodec(name,hardware,1080,false,false,exoInfo))
        }
        // `ALL_CODECS` 才能看到只注册给 secure / tunneled 路径的组件；
        // `REGULAR_CODECS` 会在一些电视固件上把唯一一颗 HEVC 硬解直接隐藏。
        val infos=runCatching { MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.toList() }
            .getOrElse { legacyCodecInfos() }
        return infos.mapNotNull { info ->
            if(info.isEncoder) return@mapNotNull null
            if(!info.supportedTypes.any { it.equals("video/hevc",ignoreCase=true) }) return@mapNotNull null
            val caps=runCatching { info.getCapabilitiesForType("video/hevc") }.getOrNull()
            val hardware=if(Build.VERSION.SDK_INT>=29) info.isHardwareAccelerated
                else !info.name.lowercase().let { it.startsWith("omx.google.") || it.startsWith("c2.android.") }
            val height=runCatching { caps?.videoCapabilities?.supportedHeights?.upper ?: 0 }
                .getOrDefault(0)
            val tunnelRequired=caps?.isFeatureRequired(
                android.media.MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback,
            )==true
            val secureRequired=caps?.isFeatureRequired(
                android.media.MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback,
            )==true
            val exoInfo=runCatching {
                com.google.android.exoplayer2.mediacodec.MediaCodecInfo.newInstance(
                    info.name,"video/hevc","video/hevc",caps,hardware,!hardware,hardware,false,false,
                )
            }.getOrNull()
            PlatformHevcCodec(info.name,hardware,height,tunnelRequired,secureRequired,exoInfo)
        }
    }

    /** 少数车机 / 电视的 `ALL_CODECS` 构造会失败，退回旧版静态枚举接口。 */
    private fun legacyCodecInfos(): List<android.media.MediaCodecInfo> {
        return (0 until MediaCodecList.getCodecCount()).mapNotNull {
            runCatching { MediaCodecList.getCodecInfoAt(it) }.getOrNull()
        }
    }

    private fun scanRelaxedTunneledHevc(): List<com.google.android.exoplayer2.mediacodec.MediaCodecInfo> {
        val platform=platformHevcInfos()
        val blocked=platform.asSequence()
            .filter { it.tunnelRequired || it.secureRequired }
            .mapTo(HashSet()) { it.name }
        return com.google.android.exoplayer2.mediacodec.MediaCodecUtil
            .getDecoderInfos("video/hevc",false,true)
            .filter { it.hardwareAccelerated && it.name !in blocked }
    }

    private fun clearExoDecoderCache() {
        runCatching {
            com.google.android.exoplayer2.mediacodec.MediaCodecUtil.clearDecoderInfoCache()
        }
    }
}
