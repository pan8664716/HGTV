// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.json.JSONObject
import java.io.IOException

internal data class ContentRepositoryDanmakuPage(
    val items: List<Danmaku>,
    val cursor: String,
    val hasMore: Boolean,
    val total: Long,
    val raw: List<JSONObject> = emptyList(),
)

    internal fun ContentRepository.danmakuImpl(
        vid: String,
        seriesId: String,
        durationSeconds: Long,
        shouldStop: () -> Boolean,
        onWindow: (List<Danmaku>) -> Unit,
    ): List<Danmaku> {
        require(vid.matches(Regex("[0-9]{1,30}")) && seriesId.matches(Regex("[0-9]{1,30}")))
        val collected=linkedMapOf<String,Danmaku>()
        var cursor=""
        for(index in 0 until ContentRepository.MAX_DANMAKU_PAGES) {
            if(shouldStop()) break
            val page=runCatching { commentWindow(vid,seriesId,0L,cursor) }.getOrNull() ?: break
            var added=false
            page.items.forEach { item -> val key="${item.offsetMs}:${item.text}"; if(!collected.containsKey(key)) { collected[key]=item; added=true } }
            if(added) onWindow(collected.values.sortedBy { it.offsetMs })
            if(collected.size>=ContentRepository.MAX_DANMAKU) break
            if(!page.hasMore || page.cursor.isBlank() || page.cursor==cursor) break
            cursor=page.cursor
            if(index<ContentRepository.MAX_DANMAKU_PAGES-1) {
                runCatching { Thread.sleep(ContentRepository.COMMENT_THROTTLE_MS) }
            }
        }
        return collected.values.sortedBy { it.offsetMs }
    }

    internal fun ContentRepository.danmakuRawWindowImpl(
        vid: String,
        seriesId: String,
        startMs: Long,
    ): List<JSONObject> {
        val page=commentWindow(vid,seriesId,startMs,"")
        return page.raw
    }

    /** 一页弹幕；所有线路都失败时抛 IOException，由 [danmakuImpl] 决定是继续还是停下。 */
    private fun ContentRepository.commentWindow(
        vid: String,
        seriesId: String,
        startMs: Long,
        cursor: String,
    ): ContentRepositoryDanmakuPage {
        val body=JSONObject()
            .put("comment_source",ContentRepository.COMMENT_SOURCE).put("server_channel",ContentRepository.SERVER_CHANNEL)
            .put("group_id",vid).put("group_type",30).put("comment_type",4).put("sort",1)
            .put("business_param",JSONObject()
                .put("need_count",true)
                .put("book_id",seriesId)
                .put("req_type",0)
                .put("start_offset_time",startMs)
                .put("sort","video_innerflow")
                .put("playlet_consume_duration_ms",0)
                .put("playlet_item_consume_duration_ms",0)
                .put("outflow_comment_count",0)
                .put("is_last_episode",false))
            .put("count",ContentRepository.COMMENT_COUNT).put("aid",8662).put("compliance_status",0)
        if(cursor.isNotBlank()) body.put("cursor",cursor)
        val payload=body.toString().toByteArray(Charsets.UTF_8)
        val values=appValues().apply {
            put("channel","update_64")
            for(key in listOf("version_code","manifest_version_code","update_version_code","pv_player")) {
                put(key,"74332")
            }
            put("version_name","7.4.3.32")
        }
        var failure: IOException?=null
        for(host in commentHosts()) {
            try {
                val signed=signer.sign("https://$host/novel/commentapi/comment/list/$vid/v1/",values,payload)
                val headers=signed.headers.toMutableMap()
                // 这三个头是弹幕接口专用的，签名与普通接口共用同一套 App 参数。
                headers["comment-source"]=ContentRepository.COMMENT_SOURCE.toString()
                headers["server-channel"]=ContentRepository.SERVER_CHANNEL.toString()
                headers["x-ss-stub"]=""
                val raw=text(signed.url,Signer.Request(signed.url,headers,payload))
                if(raw.isBlank()) throw IOException("弹幕接口暂无响应")
                val data=JSONObject(raw)
                if(data.optInt("code",-1)!=0) throw IOException("弹幕接口暂不可用（${data.optInt("code")}）")
                val payload=data.optJSONObject("data") ?: throw IOException("弹幕数据为空")
                val rows=payload.optJSONArray("data_list")
                val items=parseDanmakuRows(rows)
                val info=payload.optJSONObject("common_list_info")
                return ContentRepositoryDanmakuPage(
                    items=items,
                    cursor=info?.str("cursor").orEmpty(),
                    hasMore=info?.optBoolean("has_more",false) ?: false,
                    total=info?.optLong("total",0L) ?: 0L,
                    raw=rawDanmakuRows(rows),
                )
            } catch(e: Exception) {
                failure=e as? IOException ?: IOException(e.message ?: "弹幕请求失败")
            }
        }
        throw failure ?: IOException("弹幕接口暂不可用")
    }
