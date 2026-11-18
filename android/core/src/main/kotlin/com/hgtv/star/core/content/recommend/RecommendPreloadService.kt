// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private const val RECOMMEND_DETAIL_PRELOAD_LIMIT=6
private const val RECOMMEND_DETAIL_IMAGE_SHRINK=
    "W3siaW1hZ2VfdHlwZSI6MywiaW1hZ2Vfd2lkdGgiOjEwODAsInNocmlua190eXBlIjozfSx7ImltYWdlX3R5cGUiOjQsImltYWdlX3dpZHRoIjo0Nywic2hyaW5rX3R5cGUiOjR9XQ=="

/**
 * 官方推荐详情预热：`POST /novel/player/multi_video_detail/preload/v1`。
 *
 * 只用于后台加速。调用方不得等待它完成后再展示推荐卡或开始当前播放；
 * 当前卡片仍按“详情缓存命中 -> 单条 video_detail”这条关键路径走。
 */
internal fun ContentRepository.prefetchRecommendDetailsImpl(ids: List<String>): Int {
    val pending=ids
        .filter { it.matches(Regex("[0-9]{1,30}")) }
        .distinct()
        .filter { DetailCache.load(it)==null }
    if(pending.isEmpty()) return 0

    val identity=primaryIdentity()
    val values=recommendationValues(identity).apply {
        for(key in listOf("version_code","manifest_version_code","update_version_code","pv_player")) {
            this[key]="74332"
        }
        this["version_name"]="7.4.3.32"
    }
    val width=identity.profile["resolution"]
        ?.substringBefore('*')
        ?.toIntOrNull()
        ?.coerceAtLeast(1)
        ?: 1080
    var stored=0
    var lastFailure: IOException?=null
    for(chunk in pending.chunked(RECOMMEND_DETAIL_PRELOAD_LIMIT)) {
        val body=JSONObject()
            .put("biz_param",JSONObject()
                .put("detail_page_version",0)
                .put("disable_digg_stat",false)
                .put("disable_video_relate_book",false)
                .put("image_shrink_datas_str",RECOMMEND_DETAIL_IMAGE_SHRINK)
                .put("need_all_video_definition",false)
                .put("need_mp4_align",false)
                .put("screen_width_px",width.toString())
                .put("source",7)
                .put("use_os_player",false)
                .put("use_server_dns",false))
            .put("dr_scene","preload")
            .put("series_id",chunk.joinToString(","))
            .toString()
            .toByteArray(Charsets.UTF_8)
        try {
            val signed=signer.sign(
                "https://${videoHost()}/novel/player/multi_video_detail/preload/v1",
                values,
                body,
                method="POST",
            ).withRecommendationSession(identity)
            val raw=text(
                signed.url,
                withVideoContentHeaders(signed),
                storeRegionHeaders=true,
                userAgentProfile=values,
            )
            if(raw.isBlank()) throw IOException("推荐详情预热暂无响应")
            val root=JSONObject(raw)
            if(root.optInt("code",-1)!=0) {
                throw IOException("推荐详情预热暂不可用（${root.optInt("code")}）")
            }
            val data=root.optJSONObject("data") ?: continue
            data.keys().asSequence().forEach { seriesId ->
                val value=data.optJSONObject(seriesId) ?: return@forEach
                val videoData=value.optJSONObject("video_data") ?: return@forEach
                val detail=runCatching {
                    ContentDetailParser.parseVideoData(videoData,seriesId) { "" }
                }.getOrNull() ?: return@forEach
                DetailCache.save(detail)
                stored++
            }
        } catch(failure: IOException) {
            lastFailure=failure
        }
    }
    if(stored==0 && lastFailure!=null) throw lastFailure!!
    return stored
}

/**
 * 把推荐首包 / 续页里已经下发的完整详情写入统一缓存。
 *
 * 这一步不额外发请求，只复用已经解析过的响应；调用发生在推荐工作线程。
 */
internal fun ContentRepository.cacheRecommendDetails(cell: JSONObject): Int {
    var stored=0
    fun save(value: JSONObject?,fallbackId: String="") {
        val videoData=value ?: return
        val detail=runCatching {
            ContentDetailParser.parseVideoData(videoData,fallbackId) { "" }
        }.getOrNull() ?: return
        if(DetailCache.load(detail.series.id)==null) stored++
        DetailCache.save(detail)
    }

    val outer=cell.optJSONArray("cell_data")?.objects().orEmpty()
    outer.forEach { row ->
        val inner=row.optJSONArray("cell_data")?.objects().orEmpty()
        if(inner.isEmpty()) {
            save(row.optJSONObject("video_detail"))
            row.optJSONArray("video_data")?.objects().orEmpty().forEach { video ->
                save(
                    video.optJSONObject("video_detail")
                        ?: video.takeIf { it.optJSONArray("video_list")!=null },
                    video.str("series_id"),
                )
            }
        } else {
            inner.forEach { child ->
                save(child.optJSONObject("video_detail"))
                child.optJSONArray("video_data")?.objects().orEmpty().forEach { video ->
                    save(
                        video.optJSONObject("video_detail")
                            ?: video.takeIf { it.optJSONArray("video_list")!=null },
                        video.str("series_id"),
                    )
                }
            }
        }
    }
    return stored
}

private fun ContentRepository.withVideoContentHeaders(request: Signer.Request): Signer.Request {
    val headers=LinkedHashMap(request.headers).apply {
        remove("x-tt-dt")
        remove("x-tt-request-tag")
        put("passport-sdk-version","5051452")
        put("lc","101")
        put("x-ss-dp","8662")
        put("x-vc-bdturing-sdk-version","4.0.3.cn")
        put("x-tt-trace-id",VendorConstants.traceId())
    }
    return request.copy(headers=headers)
}
