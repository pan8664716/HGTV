// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

internal object ContentPlaybackParser {
    /**
     * 官方批量播放模型请求体。
     *
     * 一集一个请求会在新匿名设备上消耗单集额度；这里把当前集起的多个 ID
     * 放进同一个 `mixed_video_id_map`，一次响应缓存整批模型。
     */
    fun batchModelBody(
        ids: List<String>,
        playback: PlaybackProfile,
        scene: String,
        deviceLevel: Int,
    ): String {
        val biz=JSONObject()
            .put("detail_page_version",0)
            .put("device_level",deviceLevel.coerceIn(4,5))
            .put("disable_digg_stat",false)
            .put("disable_video_relate_book",false)
            .put("need_all_video_definition",true)
            .put("need_mp4_align",false)
            .put("use_os_player",false)
            .put("use_server_dns",true)
            .put("use_server_dns_scene","cold_start_stage")
            .put("video_platform",1024)
        return JSONObject()
            .put("biz_param",biz)
            .put("dr_scene",scene)
            .put("mixed_video_id_map",JSONObject().put(
                playback.contentType.toString(),
                JSONArray(ids),
            ))
            .toString()
    }

    /**
     * 用户主动点集时使用的单集播放模型请求。
     *
     * 与后台批量预取分开：只带当前这一集，使用官方 `video_model` 的单集结构。
     */
    fun directModelBody(id: String, playback: PlaybackProfile): String {
        return modelBody(
            id,
            ContentRepository.PLAYER_MODEL_PATH,
            playback,
            ContentRepository.DIRECT_DEVICE_LEVEL,
        )
    }

    /**
     * 一次播放模型请求的 body。
     *
     * 官方前台 `video_model` 使用顶层 `video_id` / `content_type`；
     * `video_platform` 来自详情，不再使用旧 mixed map 结构。
     */
    fun modelBody(
        id: String,
        path: String,
        playback: PlaybackProfile,
        deviceLevel: Int,
    ): String {
        val biz=JSONObject()
            .put("detail_page_version",0)
            .put("device_level",deviceLevel.coerceIn(4,5))
            .put("disable_digg_stat",false)
            .put("disable_video_relate_book",false)
            .put("from_video_id","")
            .put("need_all_video_definition",true)
            .put("need_mp4_align",false)
            .put("use_os_player",false)
            .put("use_server_dns",true)
            .put("use_server_dns_scene","cold_start_stage")
            .put("source",4)
            .put("video_platform",playback.videoPlatform)
        return JSONObject()
            .put("video_id",id)
            .put("content_type",playback.contentType)
            .put("biz_param",biz)
            .toString()
    }

    fun parseBatchModels(data: JSONObject,ids: List<String>): Map<String,JSONObject> {
        val root=data.optJSONObject("data") ?: return emptyMap()
        return buildMap {
            for(id in ids) {
                val entry=root.optJSONObject(id) ?: continue
                model(entry.opt("video_model"))?.let { put(id,it) }
            }
        }
    }

    /**
     * 从播放接口响应里取出 `video_model`：新路由在 `data.video_model`，
     * 带场景段 / 旧路径在 `data[<vid>].video_model`。
     * `video_model` 可能是对象，也可能是**字符串化的 JSON**，两种都要处理。
     */
    fun videoModelOf(data: JSONObject): JSONObject? {
        val root=data.optJSONObject("data") ?: return null
        model(root.opt("video_model"))?.let { return it }
        val entry=root.keys().asSequence().mapNotNull { root.optJSONObject(it) }.firstOrNull() ?: return null
        return model(entry.opt("video_model"))
    }

    fun model(value: Any?): JSONObject? = when(value) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrNull()
        else -> null
    }

    fun stringValues(value: Any?): List<String> = when(value) {
        is String -> listOf(value).filter { it.isNotBlank() }
        is JSONArray -> (0 until value.length()).mapNotNull {
            value.optString(it).takeIf(String::isNotBlank)
        }
        else -> emptyList()
    }

    /** 播放模型自身已经带 `video_list` 时直接解析，避免再请求一次 `fallback_api`。 */
    fun parseInlineStreamInfo(model: JSONObject,maxQuality: Int): StreamInfo? =
        parseStreamInfoOrNull(model,model,model.opt("video_list"),maxQuality)

    fun parseStreamInfo(model: JSONObject,info: JSONObject,maxQuality: Int): StreamInfo =
        parseStreamInfoOrNull(model,info,info.opt("video_list"),maxQuality)
            ?: throw IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")

    private fun parseStreamInfoOrNull(
        model: JSONObject,
        info: JSONObject,
        rawVideoList: Any?,
        maxQuality: Int,
    ): StreamInfo? {
        // 只留 **HEVC** 的档位（全站只有这一种编码）。
        val candidates=videoEntries(rawVideoList).mapNotNull { (key,o) ->
            val meta=o.optJSONObject("video_meta")
            val codec=listOf(
                o.str("codec_type"),
                o.str("gear_des_key"),
                meta?.str("codec_type").orEmpty(),
            ).joinToString(" ").lowercase()
            if(!isHevcCodec(codec,key)) return@mapNotNull null
            val quality=qualityOf(o,meta,key)
            if(quality<=0) return@mapNotNull null
            quality to VideoCandidate(o,meta,quality)
        }.sortedByDescending { it.first }
        if(candidates.isEmpty()) return null
        // 选档：上限内取最高的一档；一档都不满足上限时取**最低**的一档（向上兼容），
        // 并在标签上写清「兼容资源」，界面据此提示。
        val affordable=candidates.filter { it.first<=maxQuality }
        val selected=affordable.firstOrNull() ?: candidates.last()
        val quality=selected.first; val candidate=selected.second; val item=candidate.row; val meta=candidate.meta
        val seed=info.str("key_seed").ifEmpty { model.str("key_seed") }
        val seedBytes=seed.takeIf { it.isNotEmpty() }?.let(::decode64)
        val urls=linkedSetOf<String>()
        listOf(
            item.opt("main_url"),
            item.opt("play_url"),
            item.opt("backup_url"),
            item.opt("backup_url_1"),
            item.opt("backup_url_2"),
            item.opt("backup_urls"),
            item.opt("url_list"),
        ).forEach { raw ->
            stringValues(raw).forEach { value ->
                val cleaned=cleanUrl(value)
                val normalized=if(seedBytes!=null) {
                    runCatching { MediaCrypto.decryptUrl(cleaned,seedBytes) }.getOrDefault(cleaned)
                } else cleaned
                if(normalized.startsWith("https://") || normalized.startsWith("http://")) {
                    urls+=normalized
                }
            }
        }
        val url=urls.firstOrNull() ?: return null
        val encryption=item.optJSONObject("encrypt_info")
        val key=encryption?.str("spade_a").orEmpty()
            .ifEmpty { item.str("spade_a") }
            .takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey)
        if(encryption!=null && key==null && encryption.opt("encrypt") !in listOf(false,0,"0","false")) {
            return null
        }
        val label="${quality}P"+(if(quality>maxQuality) "（兼容资源）" else "")
        val videoWidth=dimension(item,meta,"vwidth","width")
        val videoHeight=dimension(item,meta,"vheight","height")
        return StreamInfo(
            url=url,
            key=key,
            quality=label,
            requestedQuality=maxQuality,
            actualQuality=quality,
            available=candidates.map { it.first },
            backupUrls=urls.drop(1),
            videoWidth=videoWidth,
            videoHeight=videoHeight,
        )
    }

    private data class VideoCandidate(
        val row: JSONObject,
        val meta: JSONObject?,
        val quality: Int,
    )

    private fun videoEntries(raw: Any?): List<Pair<String,JSONObject>> = when(raw) {
        is JSONObject -> raw.keys().asSequence().mapNotNull { key ->
            raw.optJSONObject(key)?.let { key to it }
        }.toList()
        is JSONArray -> (0 until raw.length()).mapNotNull { index ->
            raw.optJSONObject(index)?.let { "" to it }
        }
        else -> emptyList()
    }

    private fun qualityOf(row: JSONObject,meta: JSONObject?,key: String): Int {
        ContentRepository.qualityNumber(
            row.str("quality_desc").ifEmpty { meta?.str("definition").orEmpty() },
        ).takeIf { it>0 }?.let { return it }
        val width=dimension(row,meta,"vwidth","width")
        val height=dimension(row,meta,"vheight","height")
        if(width>0 && height>0) {
            ContentRepository.qualityNumber(minOf(width,height).toString()).takeIf { it>0 }?.let { return it }
        }
        if(height>0) ContentRepository.qualityNumber(height.toString()).takeIf { it>0 }?.let { return it }
        ContentRepository.qualityNumber(row.str("height")).takeIf { it>0 }?.let { return it }
        ContentRepository.qualityNumber(row.str("quality")).takeIf { it>0 }?.let { return it }
        return ContentRepository.qualityNumber(key)
    }

    private fun dimension(row: JSONObject,meta: JSONObject?,vararg keys: String): Int {
        for(key in keys) {
            row.str(key).toIntOrNull()?.takeIf { it>0 }?.let { return it }
            meta?.str(key)?.toIntOrNull()?.takeIf { it>0 }?.let { return it }
        }
        return 0
    }

    private fun isHevcCodec(label: String,key: String): Boolean {
        val value=label.lowercase()
        if(listOf("bytevc2","bvc2","h264","avc","avc1","h263","vp9","av01","mp4v-es").any { value.contains(it) }) {
            return false
        }
        if(listOf("bytevc1","hevc","h265","hvc1","hev1").any { value.contains(it) }) return true
        return ContentRepository.isHevcCodec("",key)
    }
}
