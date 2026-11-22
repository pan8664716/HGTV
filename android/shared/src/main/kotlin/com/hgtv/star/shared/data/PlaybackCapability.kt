// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

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
 * 因此**每个安装只探一次**，合并 `REGULAR_CODECS`、`ALL_CODECS` 和 LEGACY 三张表，
 * 按 codec 名去重并保留来源，完整结果落盘；之后启动、选择器兜底和调试面板都只读缓存。
 *
 * 系统升级、换解码器、或者设备「声称支持但实际解不了」这些情况由 [invalidate] 处理：
 * 播放失败时清掉缓存，下次按真实结果重新探。
 */
object PlaybackCapability {
    private const val PREFS="hgtv-playback"
    private const val KEY_HEIGHT="hevc-max-height"
    private const val KEY_HARDWARE="hevc-hardware"
    private const val KEY_CODECS="hevc-codec-snapshot"
    private const val KEY_SCHEMA="hevc-capability-schema"
    private const val SCHEMA=3
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
     * 这个读取合并后的平台候选快照，能看到只在 secure / tunneled 路径注册的组件；
     * 选择器随后会排除 secure-required，并只接受不强制 tunneled 的普通硬解组件。
     *
     * 调用方必须在后台线程（`MediaCodecList` 是 Binder 调用，盒子上能卡 1~3 秒）。
     */
    fun hasHardwareHevcDecoder(context: Context): Boolean {
        val infos=platformHevcInfos(context) ?: return true
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
        relaxedTunneledHevcInfos=runCatching { scanRelaxedTunneledHevc(context) }.getOrNull()
    }

    /** 解码器表变了（解码失败之后）把内存缓存一起作废，下次预热重新探。 */
    fun invalidateHardwareHevc() {
        hardwareHevcKnown=null
        relaxedTunneledHevcInfos=null
        codecSnapshot=null
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
    fun relaxedTunneledHevcDecoders(context: Context): List<com.google.android.exoplayer2.mediacodec.MediaCodecInfo> {
        relaxedTunneledHevcInfos?.let { return it }
        val value=runCatching { scanRelaxedTunneledHevc(context) }.getOrDefault(emptyList())
        relaxedTunneledHevcInfos=value
        return value
    }

    /**
     * 合并候选快照里的普通 HEVC 解码器，按硬解 / 软解筛选。
     *
     * ExoPlayer 的默认查询在部分车机上会返回空表，即使平台已经注册了
     * `c2.qti.hevc.decoder` 这类组件。这里直接把平台表转换成 ExoPlayer 可消费的
     * [com.google.android.exoplayer2.mediacodec.MediaCodecInfo]，作为选择器最后一层兜底。
     *
     * 只返回能在普通 Surface 上工作的组件：secure-required 只能处理 DRM，
     * tunnel-required 必须显式开启隧道播放。调用方仍须在后台线程调用。
     */
    fun platformHevcDecoders(context: Context,hardware: Boolean): List<com.google.android.exoplayer2.mediacodec.MediaCodecInfo> {
        return platformHevcInfos(context).orEmpty()
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
     * 结果来自已落盘的完整候选快照；探测本身在调用方的后台线程上跑
     * （`MediaCodecList` 在盒子上是 Binder 调用，能卡 1–3 秒）。
     */
    data class DebugCapability(val hardware: String,val software: String)

    @Volatile private var capabilitySummary: DebugCapability?=null
    @Volatile private var codecSnapshot: CodecSnapshot?=null

    fun debugSummary(context: Context): DebugCapability {
        capabilitySummary?.let { return it }
        val value=runCatching { scanSummary(context) }.getOrElse { DebugCapability("探测失败","探测失败") }
        capabilitySummary=value
        return value
    }

    /** 调试面板重开时重新探一次（系统升级 / 换解码器之后读数会变）。 */
    fun invalidateSummary() {
        capabilitySummary=null
        codecSnapshot=null
    }

    private data class RawCodec(
        val info: MediaCodecInfo,
        val sources: MutableSet<String> = linkedSetOf(),
    )

    private data class PlatformHevcCodec(
        val name: String,
        val hardware: Boolean,
        val maxHeight: Int,
        val tunnelRequired: Boolean,
        val secureRequired: Boolean,
        val sources: Set<String>,
        val exoInfo: com.google.android.exoplayer2.mediacodec.MediaCodecInfo?,
    )

    private data class CodecSnapshot(
        val codecs: List<PlatformHevcCodec>,
        val errors: List<String>,
    )

    private fun scanSummary(context: Context): DebugCapability {
        val found=platformHevcInfos(context).orEmpty()
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
            .remove(KEY_HEIGHT).remove(KEY_HARDWARE).remove(KEY_CODECS).remove(KEY_SCHEMA).apply()
        hardwareHevcKnown=null
        relaxedTunneledHevcInfos=null
        codecSnapshot=null
        capabilitySummary=null
        clearExoDecoderCache()
    }

    private fun probe(context: Context): Pair<Int,Boolean> {
        val prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        val infos=platformHevcInfos(context) ?: return UNKNOWN to false
        val cached=prefs.getInt(KEY_HEIGHT,UNKNOWN)
        if(cached!=UNKNOWN) return cached to prefs.getBoolean(KEY_HARDWARE,false)
        val (height,hardware)=summarize(infos)
        prefs.edit()
            .putInt(KEY_HEIGHT,height)
            .putBoolean(KEY_HARDWARE,hardware)
            .apply()
        return height to hardware
    }

    /** 把完整候选快照归纳成「最高可解高度 + 是否有硬解」。 */
    private fun summarize(infos: List<PlatformHevcCodec>): Pair<Int,Boolean> {
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

    @Synchronized
    private fun platformHevcInfos(context: Context): List<PlatformHevcCodec>? {
        codecSnapshot?.let { return it.codecs }
        val prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        val cached=readSnapshot(prefs)
        if(cached!=null) {
            codecSnapshot=cached
            return cached.codecs
        }
        val scanned=scanPlatformHevc() ?: return null
        prefs.edit()
            .putInt(KEY_SCHEMA,SCHEMA)
            .putString(KEY_CODECS,encodeSnapshot(scanned))
            .apply()
        codecSnapshot=scanned
        return scanned.codecs
    }

    private fun scanPlatformHevc(): CodecSnapshot? {
        if(Build.VERSION.SDK_INT<21) {
            return scanLegacyPlatform()
        }
        val raw=linkedMapOf<String,RawCodec>()
        val errors=mutableListOf<String>()
        collectModern(MediaCodecList.REGULAR_CODECS,"REGULAR_CODECS",raw,errors)
        val allOk=collectModern(MediaCodecList.ALL_CODECS,"ALL_CODECS",raw,errors)
        val legacyOk=collectLegacy("LEGACY",raw,errors)
        // REGULAR 可能故意隐藏只注册在 secure / tunneled 路径的组件；
        // ALL 与 LEGACY 都失败时不能把空表当成“设备不支持”。
        if(!allOk && !legacyOk) return null
        return CodecSnapshot(
            codecs=raw.values.mapNotNull { inspectCodec(it,errors) },
            errors=errors.toList(),
        )
    }

    /**
     * 5.0 及以下没有 `MediaCodecList.REGULAR_CODECS`（API 21 才有），先扫 LEGACY；
     * 仍没有 HEVC 时用 `createDecoderByType` 兜底。那一档读不到能力上限，按 1080 处理，
     * 真正的上限由运行期失败兜底（见 [invalidate]）。
     */
    private fun scanLegacyPlatform(): CodecSnapshot? {
        val raw=linkedMapOf<String,RawCodec>()
        val errors=mutableListOf<String>()
        val legacyOk=collectLegacy("LEGACY",raw,errors)
        val codecs=raw.values.mapNotNull { inspectCodec(it,errors) }.toMutableList()
        if(codecs.isEmpty()) {
            // 4.4 / 5.0 的 `MediaCodec` 还没有 `AutoCloseable`（API 21 才加），不能用 `use`。
            val codec=runCatching { android.media.MediaCodec.createDecoderByType("video/hevc") }.getOrNull()
            if(codec!=null) {
                val name=runCatching { codec.name }.getOrDefault("")
                runCatching { codec.release() }
                if(name.isNotBlank()) {
                    val hardware=!looksSoftware(name)
                    codecs+=PlatformHevcCodec(
                        name=name,
                        hardware=hardware,
                        maxHeight=1080,
                        tunnelRequired=false,
                        secureRequired=false,
                        sources=setOf("CREATE_DECODER"),
                        exoInfo=exoInfo(name,null,hardware),
                    )
                }
            }
            if(!legacyOk && codecs.isEmpty()) return null
        }
        return CodecSnapshot(codecs,errors.toList())
    }

    @Suppress("DEPRECATION")
    private fun collectModern(
        kind: Int,
        source: String,
        output: MutableMap<String,RawCodec>,
        errors: MutableList<String>,
    ): Boolean {
        if(Build.VERSION.SDK_INT<21) return false
        return try {
            MediaCodecList(kind).codecInfos.forEach { addCodec(it,source,output) }
            true
        } catch(e: Exception) {
            errors+="$source failed: ${e.message}"
            false
        } catch(e: LinkageError) {
            errors+="$source linkage failed: ${e.message}"
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun collectLegacy(
        source: String,
        output: MutableMap<String,RawCodec>,
        errors: MutableList<String>,
    ): Boolean {
        return try {
            val count=MediaCodecList.getCodecCount()
            for(index in 0 until count) {
                try {
                    addCodec(MediaCodecList.getCodecInfoAt(index),source,output)
                } catch(e: Exception) {
                    errors+="$source[$index] failed: ${e.message}"
                }
            }
            true
        } catch(e: Exception) {
            errors+="$source enumeration failed: ${e.message}"
            false
        } catch(e: LinkageError) {
            errors+="$source linkage failed: ${e.message}"
            false
        }
    }

    private fun addCodec(info: MediaCodecInfo,source: String,output: MutableMap<String,RawCodec>) {
        val name=runCatching { info.name }.getOrNull() ?: return
        val key=name.lowercase()
        val existing=output[key]
        if(existing!=null) {
            existing.sources+source
        } else {
            output[key]=RawCodec(info,linkedSetOf(source))
        }
    }

    private fun inspectCodec(raw: RawCodec,errors: MutableList<String>): PlatformHevcCodec? {
        val info=raw.info
        val name=runCatching { info.name }.getOrNull() ?: return null
        val isEncoder=runCatching { info.isEncoder }.getOrElse {
            errors+="$name isEncoder failed: ${it.message}"
            return null
        }
        if(isEncoder) return null
        val supported=runCatching { info.supportedTypes }.getOrElse {
            errors+="$name supportedTypes failed: ${it.message}"
            return null
        }
        if(!supported.any { it.equals("video/hevc",ignoreCase=true) }) return null
        val caps=runCatching { info.getCapabilitiesForType("video/hevc") }.getOrElse {
            errors+="$name capabilities failed: ${it.message}"
            null
        }
        val hardware=if(Build.VERSION.SDK_INT>=29) {
            runCatching { info.isHardwareAccelerated }.getOrDefault(!looksSoftware(name))
        } else {
            !looksSoftware(name)
        }
        val height=runCatching { caps?.videoCapabilities?.supportedHeights?.upper ?: 0 }
            .getOrDefault(0)
        val tunnelRequired=caps?.isFeatureRequired(
            MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback,
        )==true
        val secureRequired=caps?.isFeatureRequired(
            MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback,
        )==true
        return PlatformHevcCodec(
            name=name,
            hardware=hardware,
            maxHeight=height,
            tunnelRequired=tunnelRequired,
            secureRequired=secureRequired,
            sources=raw.sources.toSet(),
            exoInfo=exoInfo(name,caps,hardware),
        )
    }

    private fun looksSoftware(name: String): Boolean {
        val lower=name.lowercase()
        return lower.startsWith("omx.google.") ||
            lower.startsWith("c2.android.") ||
            lower.contains(".sw.") ||
            lower.contains("software") ||
            lower.contains("ffmpeg") ||
            lower.contains("avcodec")
    }

    private fun exoInfo(
        name: String,
        caps: MediaCodecInfo.CodecCapabilities?,
        hardware: Boolean,
    ): com.google.android.exoplayer2.mediacodec.MediaCodecInfo? = runCatching {
        com.google.android.exoplayer2.mediacodec.MediaCodecInfo.newInstance(
            name,"video/hevc","video/hevc",caps,hardware,!hardware,hardware,false,false,
        )
    }.getOrNull()

    private fun readSnapshot(prefs: android.content.SharedPreferences): CodecSnapshot? {
        if(prefs.getInt(KEY_SCHEMA,0)!=SCHEMA) return null
        val raw=prefs.getString(KEY_CODECS,null) ?: return null
        val root=runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if(root.optInt("schema",0)!=SCHEMA) return null
        if(root.optString("fingerprint")!=Build.FINGERPRINT.orEmpty()) return null
        val rows=root.optJSONArray("codecs") ?: return null
        val codecs=(0 until rows.length()).mapNotNull { decodeCodec(rows.optJSONObject(it)) }
        if(rows.length()>0 && codecs.isEmpty()) return null
        val errorRows=root.optJSONArray("errors")
        val errors=(0 until (errorRows?.length() ?: 0)).mapNotNull { errorRows?.optString(it) }
        return CodecSnapshot(codecs,errors)
    }

    private fun decodeCodec(row: JSONObject?): PlatformHevcCodec? {
        row ?: return null
        val name=row.optString("name").takeIf { it.isNotBlank() } ?: return null
        val hardware=row.optBoolean("hardware",false)
        val sourceRows=row.optJSONArray("sources")
        val sources=(0 until (sourceRows?.length() ?: 0))
            .mapNotNull { sourceRows?.optString(it) }
            .toSet()
        return PlatformHevcCodec(
            name=name,
            hardware=hardware,
            maxHeight=row.optInt("maxHeight",0),
            tunnelRequired=row.optBoolean("tunnelRequired",false),
            secureRequired=row.optBoolean("secureRequired",false),
            sources=sources,
            exoInfo=exoInfo(name,null,hardware),
        )
    }

    private fun encodeSnapshot(snapshot: CodecSnapshot): String {
        val codecs=JSONArray()
        snapshot.codecs.forEach { codec ->
            codecs.put(JSONObject()
                .put("name",codec.name)
                .put("hardware",codec.hardware)
                .put("maxHeight",codec.maxHeight)
                .put("tunnelRequired",codec.tunnelRequired)
                .put("secureRequired",codec.secureRequired)
                .put("sources",JSONArray(codec.sources.sorted()))
            )
        }
        return JSONObject()
            .put("schema",SCHEMA)
            .put("fingerprint",Build.FINGERPRINT.orEmpty())
            .put("errors",JSONArray(snapshot.errors))
            .put("codecs",codecs)
            .toString()
    }

    private fun scanRelaxedTunneledHevc(context: Context): List<com.google.android.exoplayer2.mediacodec.MediaCodecInfo> {
        val platform=platformHevcInfos(context) ?: return emptyList()
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
