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
        val durationMs=(durationSeconds.coerceIn(0,4*60*60))*1000
        val collected=linkedMapOf<String,Danmaku>()
        var startMs=0L
        var cursor=""
        val planned=if(durationMs>0) {
            (durationMs+ContentRepository.HOT_COMMENT_WINDOW_MS-1)/
                ContentRepository.HOT_COMMENT_WINDOW_MS+4L
        } else {
            ContentRepository.MAX_HOT_COMMENT_WINDOWS.toLong()
        }
        val windows=planned.coerceIn(1L,ContentRepository.MAX_HOT_COMMENT_WINDOWS.toLong()).toInt()
        for(index in 0 until windows) {
            if(shouldStop()) break
            val page=runCatching { hotCommentWindow(vid,seriesId,durationMs,startMs,cursor) }
                .getOrNull() ?: break
            if(mergeDanmaku(collected,page.items)>0) {
                onWindow(collected.values.sortedBy { it.offsetMs })
            }
            if(collected.size>=ContentRepository.MAX_DANMAKU) break
            if(page.nextStart<=startMs) break
            if(durationMs>0 && page.nextStart>=durationMs) break
            if(durationMs<=0 && !page.hasMore) break
            startMs=page.nextStart
            cursor=page.cursor
            if(index<windows-1) {
                runCatching { Thread.sleep(ContentRepository.HOT_COMMENT_THROTTLE_MS) }
            }
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

    internal fun ContentRepository.danmakuRawWindowImpl(
        vid: String,
        seriesId: String,
        startMs: Long,
    ): List<JSONObject> {
        val page=hotCommentWindow(vid,seriesId,0L,startMs,"")
        return page.raw
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
        val values=appValues()
        var failure: IOException?=null
        for(host in commentHosts()) {
            try {
                val signed=signer.sign("https://$host/novel/commentapi/comment/list/$vid/v1/",values,payload)
                val headers=signed.headers.toMutableMap()
                headers["comment-source"]=ContentRepository.HOT_COMMENT_SOURCE.toString()
                headers["server-channel"]=ContentRepository.HOT_SERVER_CHANNEL.toString()
                headers["x-ss-stub"]=""
                val raw=text(signed.url,Signer.Request(signed.url,headers,payload))
                if(raw.isBlank()) throw IOException("弹幕接口暂无响应")
                val data=JSONObject(raw)
                if(data.optInt("code",-1)!=0) throw IOException("弹幕接口暂不可用（${data.optInt("code")}）")
                val payload=data.optJSONObject("data") ?: throw IOException("弹幕数据为空")
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
                failure=e as? IOException ?: IOException(e.message ?: "弹幕请求失败")
            }
        }
        throw failure ?: IOException("弹幕接口暂不可用")
    }
