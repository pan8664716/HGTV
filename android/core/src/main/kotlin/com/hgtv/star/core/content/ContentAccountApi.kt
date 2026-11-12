// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

internal class ContentAccountTransport(
    private val accountValues: () -> LinkedHashMap<String,String>,
    private val writeValues: () -> LinkedHashMap<String,String>,
    private val primaryProfile: () -> Map<String,String>,
    private val accountHost: () -> String,
    private val sign: (
        url: String,
        values: LinkedHashMap<String,String>,
        body: ByteArray?,
        profile: Map<String,String>,
    ) -> Signer.Request,
    private val withSession: (Signer.Request) -> Signer.Request,
    private val text: (String, Signer.Request) -> String,
    private val retries: Int,
) {
    fun json(path: String, params: Map<String,String> = emptyMap(), body: ByteArray? = null): JSONObject {
        val values=if(body!=null) writeValues() else accountValues()
        params.forEach { (key,value) -> values[key]=value }
        val signed=sign(
            "https://${accountHost()}$path",
            values,
            body,
            primaryProfile(),
        )
        val raw=text(signed.url,withSession(signed))
        if(raw.isBlank()) throw IOException("账号接口暂无响应（空响应体）")
        return JSONObject(raw)
    }

    fun retryBlank(attempts: Int = retries, action: () -> JSONObject): JSONObject {
        var last: Exception?=null
        repeat(attempts) { attempt ->
            try { return action() } catch(e: Exception) {
                last=e
                if(!e.message.orEmpty().contains("空响应体")) throw e
                val delay=300L+attempt*250L
                if(attempt<attempts-1) {
                    NetLog.retry("account_blank",attempt+2,attempts,e.message.orEmpty(),delay)
                } else {
                    NetLog.error("[API] error scope=account_blank attempts=$attempts reason=${e.message.orEmpty().take(220)}")
                }
                Thread.sleep(delay)
            }
        }
        throw last ?: IOException("账号接口暂无响应")
    }
}

internal object ContentAccountRequests {
    fun reserve(seriesId: String, subscribe: Boolean): Pair<String,ByteArray> {
        val payload = if(subscribe) JSONObject()
            .put("item_id",seriesId.toLong())
            .put("item_type",14)
            .put("op_type",1)
            .put("item_id_str",seriesId)
            .put("wish_list_all_del",0)
            .put("shark_param",JSONObject()
                .put("enter_from","ShortSeriesActivity")
                .put("previous_page","BulletActivity")
                .put("page_list","MainFragmentActivity,BulletActivity,ShortSeriesActivity"))
            .toString().toByteArray()
        else JSONObject()
            .put("item_id",JSONArray().put(seriesId))
            .put("all_select",false)
            .put("not_del_item_id",JSONArray())
            .put("is_online",false)
            .put("tab_type",13)
            .toString().toByteArray()
        val path = if(subscribe) {
            "/reading/bookapi/search/uncover_subscribe/v1/"
        } else {
            "/reading/user/subscribe/delete/v1/"
        }
        return path to payload
    }

    fun favorite(
        seriesId: String,
        operate: Int,
        now: Long,
        bookTypeWatch: Int,
        addOperate: Int,
        deleteOperate: Int,
    ): ByteArray {
        val entry=JSONObject()
            .put("book_id",seriesId).put("book_type",bookTypeWatch)
            .put("modify_time",now)
            .put("video_shelf_operate_type",operate)
        // 「取消」在条目里带一个空的 `group_name`；「加入」不带。
        if(operate==deleteOperate) entry.put("group_name","")
        // `shark_extra`（进入来源）是**body 的顶层字段**，不在条目里。
        val body=JSONObject()
            .put("is_cancelled",false)
        if(operate==addOperate) {
            body.put("shark_extra",JSONObject()
                .put("enter_from","MainFragmentActivity")
                .put("previous_page","")
                .put("page_list","MainFragmentActivity")
                .put("is_active_behavior","true")
                .put("inactive_type","0"))
        }
        body.put("update_bookshelf_video_list",JSONArray().put(entry))
        return body.toString().toByteArray()
    }

    fun uploadProgress(
        seriesId: String,
        episodeId: String,
        episodeIndex: Int,
        position: Long,
        duration: Long,
        episodeCount: Int,
        now: Long,
        bookTypeWatch: Int,
    ): ByteArray {
        val rate=if(duration>0) (position.toDouble()/duration).coerceIn(0.0,1.0) else 0.0
        val entry=JSONObject()
            .put("book_id",seriesId)
            .put("item_id",episodeId)
            .put("vid_index",episodeIndex+1)
            .put("current_play_time",position.coerceAtLeast(0))
            .put("current_total_time",duration.coerceAtLeast(0))
            .put("episode_count",episodeCount.coerceAtLeast(0))
            .put("read_timestamp_ms",now)
            .put("book_type",bookTypeWatch)
            .put("progress_rate","%.4f".format(java.util.Locale.US,rate))
            .put("player_cumulative_total_duration",position.coerceAtLeast(0))
        return JSONObject().put("books",JSONArray().put(entry)).put("write_mode",1).toString().toByteArray()
    }

    fun deleteHistory(
        ids: List<String>,
        known: Map<String,JSONObject>,
        bookTypeWatch: Int,
        now: () -> Long,
    ): ByteArray {
        val rows=JSONArray()
        ids.forEach { id ->
            val bookType=known[id]?.optInt("book_type",bookTypeWatch) ?: bookTypeWatch
            val row=JSONObject()
                .put("book_id",id.toLong())
                .put("book_type",bookType)
                .put("chapter_index",0)
                .put("current_play_position",0)
                .put("digged_count",0)
                .put("duration",0)
                .put("episode_cnt",0)
                .put("is_delete",true)
                .put("is_interactive_game",false)
                .put("is_listen_mode",false)
                .put("is_multi_season",0)
                .put("player_accumulate_total_time",0)
                .put("read_timestamp_ms",0)
                .put("recent_reads",0)
                .put("retain_video_play_time",0)
                .put("season_index",0)
                .put("series_play_cnt",0)
                .put("tone_id",0)
                .put("update_timestamp_ms",now())
                .put("user_digg",false)
                .put("vid",0)
                .put("vid_index",1)
            rows.put(row)
        }
        return JSONObject().put("update_datas",rows).toString().toByteArray()
    }
}
