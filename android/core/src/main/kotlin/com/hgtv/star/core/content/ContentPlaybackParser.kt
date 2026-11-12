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
            .put("device_level",deviceLevel)
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
     * 一次播放模型请求的 body。
     *
     * `biz_param` 必须是**对象**，不是序列化后的字符串。
     * 新路由对齐官方抓包：`content_type` 在顶层，`video_platform=3`，并带 `source=4`。
     * 老路由仍使用 `mixed_video_id_map` 与 Mixed=1024；preload 额外带 `dr_scene=preload`。
     */
    fun modelBody(
        id: String,
        path: String,
        playback: PlaybackProfile,
        deviceLevel: Int,
    ): String {
        val newRoute=!path.contains("multi_video_model")
        val biz=JSONObject()
            .put("detail_page_version",0).put("device_level",deviceLevel).put("disable_digg_stat",false)
            .put("need_all_video_definition",true).put("need_mp4_align",false)
            .put("use_os_player",false).put("use_server_dns",false)
        return if(newRoute) {
            biz.put("disable_video_relate_book",false).put("from_video_id","")
                .put("source",4).put("video_platform",playback.videoPlatform)
            JSONObject()
                .put("video_id",id)
                .put("content_type",playback.contentType)
                .put("biz_param",biz)
                .toString()
        } else {
            biz.put("disable_video_relate_book",false).put("video_platform",1024)
            JSONObject()
                .put("biz_param",biz)
                .put("mixed_video_id_map",JSONObject().put(playback.contentType.toString(),JSONArray().put(id)))
                .apply { if(path.contains("/preload/")) put("dr_scene","preload") }
                .toString()
        }
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

    fun parseStreamInfo(model: JSONObject,info: JSONObject,maxQuality: Int): StreamInfo {
        val videoList=info.optJSONObject("video_list") ?: throw IOException("没有可用清晰度")
        // 只留 **HEVC** 的档位（全站只有这一种编码）。
        val candidates=videoList.keys().asSequence().mapNotNull { k -> videoList.optJSONObject(k)?.let { k to it } }
            .filter { (k,o) ->
                o.str("main_url").isNotEmpty() && ContentRepository.isHevcCodec(o.str("codec_type"),k)
            }
            .map { (k,o) -> ContentRepository.qualityOf(o,k) to o }
            .filter { it.first>0 }
            .sortedByDescending { it.first }
            .toList()
        if(candidates.isEmpty()) throw IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")
        // 选档：上限内取最高的一档；一档都不满足上限时取**最低**的一档（向上兼容），
        // 并在标签上写清「兼容资源」，界面据此提示。
        val affordable=candidates.filter { it.first<=maxQuality }
        val selected=affordable.firstOrNull() ?: candidates.last()
        val quality=selected.first; val item=selected.second
        val seed=info.str("key_seed").ifEmpty { model.str("key_seed") }
        val seedBytes=seed.takeIf { it.isNotEmpty() }?.let(::decode64)
        val urls=linkedSetOf<String>()
        listOf(
            item.opt("main_url"),
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
        val url=urls.firstOrNull() ?: throw IOException("播放地址无效")
        val key=item.str("spade_a").takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey)
        val label="${quality}P"+(if(quality>maxQuality) "（兼容资源）" else "")
        val videoWidth=item.str("vwidth").toIntOrNull()?.takeIf { it>0 } ?: 0
        val videoHeight=item.str("vheight").toIntOrNull()?.takeIf { it>0 } ?: 0
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
}
