// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

internal data class ContentRepositoryDanmakuPage(
    val items: List<Danmaku>,
    val cursor: String,
    val hasMore: Boolean,
    val total: Long,
    val raw: List<JSONObject> = emptyList(),
    val nextStart: Long = 0L,
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
        var hotLoaded=false
        for(index in 0 until ContentRepository.MAX_DANMAKU_PAGES) {
            if(shouldStop()) break
            val page=runCatching { commentWindow(vid,seriesId,0L,cursor) }.getOrNull() ?: break
            if(mergeDanmaku(collected,page.items)>0) {
                onWindow(collected.values.sortedBy { it.offsetMs })
            }
            if(!hotLoaded) {
                hotLoaded=true
                loadHotDanmaku(
                    vid,
                    seriesId,
                    durationSeconds,
                    shouldStop,
                    collected,
                    onWindow,
                )
            }
            if(collected.size>=ContentRepository.MAX_DANMAKU) break
            if(!page.hasMore || page.cursor.isBlank() || page.cursor==cursor) break
            cursor=page.cursor
            if(index<ContentRepository.MAX_DANMAKU_PAGES-1) {
                runCatching { Thread.sleep(ContentRepository.COMMENT_THROTTLE_MS) }
            }
        }
        if(!hotLoaded) {
            loadHotDanmaku(
                vid,
                seriesId,
                durationSeconds,
                shouldStop,
                collected,
                onWindow,
            )
        }
        return collected.values.sortedBy { it.offsetMs }
    }

    private fun mergeDanmaku(
        collected: MutableMap<String,Danmaku>,
        items: List<Danmaku>,
    ): Int {
        var added=0
        items.forEach { item ->
            val key="${item.offsetMs}:${item.text}"
            if(!collected.containsKey(key)) {
                collected[key]=item
                added++
            }
        }
        return added
    }

    /**
     * 旧热弹幕采样流单独返回一组高频短句，与当前完整评论流混合后，
     * 聚合才能重新拿到足够的重复样本；总量单独封顶，避免挤占完整流。
     */
    private fun ContentRepository.loadHotDanmaku(
        vid: String,
        seriesId: String,
        durationSeconds: Long,
        shouldStop: () -> Boolean,
        collected: MutableMap<String,Danmaku>,
        onWindow: (List<Danmaku>) -> Unit,
    ) {
        val durationMs=(durationSeconds.coerceIn(0,4*60*60))*1000
        val planned=if(durationMs>0) {
            ((durationMs+ContentRepository.HOT_COMMENT_WINDOW_MS-1)/
                ContentRepository.HOT_COMMENT_WINDOW_MS).toInt()
        } else {
            ContentRepository.MAX_HOT_COMMENT_WINDOWS
        }
        val windows=planned.coerceIn(1,ContentRepository.MAX_HOT_COMMENT_WINDOWS)
        var startMs=0L
        var cursor=""
        var hotAdded=0
        for(index in 0 until windows) {
            if(shouldStop() || hotAdded>=ContentRepository.MAX_HOT_DANMAKU ||
                collected.size>=ContentRepository.MAX_DANMAKU) break
            val page=runCatching { hotCommentWindow(vid,seriesId,durationMs,startMs,cursor) }
                .getOrNull() ?: break
            val added=mergeDanmaku(collected,page.items)
            if(added>0) {
                hotAdded+=added
                onWindow(collected.values.sortedBy { it.offsetMs })
            }
            if(page.nextStart<=startMs) break
            if(durationMs>0 && page.nextStart>=durationMs) break
            if(durationMs<=0 && !page.hasMore) break
            startMs=page.nextStart
            cursor=page.cursor
            if(index<windows-1) {
                runCatching { Thread.sleep(ContentRepository.HOT_COMMENT_THROTTLE_MS) }
            }
        }
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

    private fun ContentRepository.hotCommentWindow(
        vid: String,
        seriesId: String,
        durationMs: Long,
        startMs: Long,
        cursor: String,
    ): ContentRepositoryDanmakuPage {
        val body=JSONObject()
            .put("comment_source",ContentRepository.HOT_COMMENT_SOURCE)
            .put("server_channel",ContentRepository.HOT_SERVER_CHANNEL)
            .put("group_id",vid).put("group_type",30).put("comment_type",20).put("sort",1)
            .put("business_param",JSONObject()
                .put("book_id",seriesId)
                .put("start_offset_time",startMs)
                .put("playlet_item_duration",durationMs)
                .put("need_danmaku_guide_type",JSONArray(listOf(1,3,4,2))))
            .put("count",ContentRepository.HOT_COMMENT_COUNT)
            .put("cursor",cursor)
            .put("aid",8662)
            .put("compliance_status",0)
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
                headers["comment-source"]=ContentRepository.HOT_COMMENT_SOURCE.toString()
                headers["server-channel"]=ContentRepository.HOT_SERVER_CHANNEL.toString()
                headers["x-ss-stub"]=""
                val raw=text(signed.url,Signer.Request(signed.url,headers,payload))
                if(raw.isBlank()) throw IOException("热弹幕接口暂无响应")
                val data=JSONObject(raw)
                if(data.optInt("code",-1)!=0) throw IOException("热弹幕接口暂不可用（${data.optInt("code")}）")
                val payload=data.optJSONObject("data") ?: throw IOException("热弹幕数据为空")
                val rows=payload.optJSONArray("data_list")
                val items=parseDanmakuRows(rows)
                val info=payload.optJSONObject("common_list_info")
                val next=payload.optJSONObject("extra")
                    ?.optLong("next_query_danmaku_list_time",-1L)
                    ?.takeIf { it>startMs }
                    ?: (startMs+ContentRepository.HOT_COMMENT_WINDOW_MS)
                return ContentRepositoryDanmakuPage(
                    items=items,
                    cursor=info?.str("cursor").orEmpty(),
                    hasMore=info?.optBoolean("has_more",false) ?: false,
                    total=info?.optLong("total",0L) ?: 0L,
                    raw=rawDanmakuRows(rows),
                    nextStart=next,
                )
            } catch(e: Exception) {
                failure=e as? IOException ?: IOException(e.message ?: "热弹幕请求失败")
            }
        }
        throw failure ?: IOException("热弹幕接口暂不可用")
    }
