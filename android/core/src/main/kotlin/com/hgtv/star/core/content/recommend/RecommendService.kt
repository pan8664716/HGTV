// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

    /**
     * 官方曝光上报使用的查询参数。
     *
     * 上报接口和官方书包推荐流共用 `74332 / 7.4.3.32`；分类接口仍保留原来的
     * `73532 / 7.3.5.32`，避免把已稳定的分类链路一起改掉。
     */
    private fun ContentRepository.recommendReportValues(identity: ContentPrimaryIdentity): LinkedHashMap<String,String> =
        recommendationValues(identity).apply {
            for(key in listOf("version_code","manifest_version_code","update_version_code","pv_player")) {
                this[key]="74332"
            }
            this["version_name"]="7.4.3.32"
            this["channel"]="update_64"
        }

    internal fun Signer.Request.withRecommendationSession(identity: ContentPrimaryIdentity): Signer.Request {
        val extra=linkedMapOf<String,String>()
        if(identity.token.isNotBlank()) extra["x-tt-token"]=identity.token
        if(identity.cookie.isNotBlank()) extra["Cookie"]=identity.cookie
        if(identity.account) extra["X-HGTV-Identity"]="account"
        return if(extra.isEmpty()) this else copy(headers=headers+extra)
    }

    internal fun ContentRepository.recommendFeedImpl(
        selection: CategorySelection,
        sessionId: String,
        offset: Int,
        limit: Int,
        genres: List<String>,
        filterIds: String,
        tab: RecommendTab,
    ): RecommendFeed {
        refreshRecommendStatConfigAsync()
        require(offset >= 0) { "offset 不能为负" }
        // 统一回到旧分类接口；`hot_score` 由 categoryRecommendFeed 明确传入。
        return categoryRecommendFeed(
            selection=selection,
            sessionId=sessionId,
            offset=offset,
            limit=limit,
            genres=genres.ifEmpty { listOfNotNull(tab.genre) },
            filterIds=filterIds,
        )
    }

    /** 按热度取推荐流：分类接口 + `hot_score`，支持续流和推荐范围筛选。 */
    private fun ContentRepository.categoryRecommendFeed(
        selection: CategorySelection,
        sessionId: String,
        offset: Int,
        limit: Int,
        genres: List<String>,
        filterIds: String,
    ): RecommendFeed {
        val body=JSONObject()
            // 服务端字段是**字符串**（给数组会回 `expected string`，见 `categoryApi` 的说明）。
            .put("filter_ids",filterIds)
            .put("req_scene","default")
            .put("offset",offset)
            .put("need_selector_panel",false)
            .put("limit",limit)
            // 推荐页也固定按最高热度取候选：保留续流游标、去重和曝光过滤，
            // 但不再使用服务端那套容易混入冷门内容的默认推荐序。
            .put("select_items",ContentCatalogParser.categorySelectItems(
                selection,
                CategorySelection.DEFAULT_SORT,
                genres,
            ))
            .put("session_id",sessionId)
            .put("req_type","only_content")
            .put("client_req_type",3)
        // 只重试 3 次（≈1.4s 最坏）：推荐页的等待全压在这条请求上，见 [categoryApiRaw]。
        val data=JSONObject(categoryApiRaw(
            body,
            RECOMMEND_ATTEMPTS,
        ))
        if(data.optInt("code",-1)!=0) throw IOException("推荐接口暂不可用")
        val payload=data.optJSONObject("data") ?: throw IOException("推荐接口结构已变化")
        val items=ContentCatalogParser.valid(
            (payload.optJSONArray("video_data") ?: JSONArray()).objects()
                .map(ContentCatalogParser::apiCard),
        )
        // `next_offset` 偶尔缺失 / 不往前走：用「本页实际条数」兜底，保证游标一定前进，
        // 否则下一次还是同一个位置，用户看到的就是「又一套一模一样的列表」。
        val reported=payload.optInt("next_offset",-1)
        val next=when {
            reported>offset -> reported
            items.isNotEmpty() -> offset+items.size
            else -> offset+limit
        }
        val more=payload.optBoolean("has_more",items.isNotEmpty())
        return RecommendFeed(CatalogPage(items,more),payload.optString("session_id").ifBlank { sessionId },next)
    }

    /**
     * 官方书包 Tab 推荐流：`GET /reading/bookapi/bookmall/tab/v`。
     *
     * 响应不是分类接口那种平铺 `video_data`，而是：
     *
     * ```
     * data.tab_item[tab_type=请求值]
     *   .cell_data[]                 // 「猜你喜欢」外层
     *     .cell_data[]               // 真正的推荐卡片
     *       .video_data[0]           // 系列卡片
     *       .video_detail             // UGC / 合集卡片
     * ```
     *
     * 官方成功响应用 `bottom_unlimited=true` 表示无限流；首包常回
     * `next_offset=0`，因此客户端必须用本页实际卡片数推进游标。
     */
    private fun ContentRepository.officialRecommendFeed(
        tab: RecommendTab,
        selection: CategorySelection,
        sessionId: String,
        offset: Int,
        filterIds: String,
    ): RecommendFeed {
        val tabType=requireNotNull(tab.tabType)
        // 旧分类接口的 session_id 不是官方 bookmall 的 UUID。换接口后若继续带着旧
        // offset 请求，官方会按新 session 解释这个位置，首屏就可能反复拿同一批 3 条。
        val validSession=sessionId.takeIf {
            runCatching { java.util.UUID.fromString(it) }.isSuccess
        }
        val sessionUuid=validSession ?: java.util.UUID.randomUUID().toString()
        val requestOffset=if(validSession==null) 0 else offset
        val raw=officialRecommendRaw(tabType,sessionUuid,requestOffset,filterIds,selection)
        val root=JSONObject(raw)
        if(root.optInt("code",-1)!=0) throw IOException("推荐接口暂不可用")
        val payload=root.optJSONObject("data") ?: throw IOException("推荐接口结构已变化")
        val tabs=payload.optJSONArray("tab_item")?.objects().orEmpty()
        fun hasCards(row: JSONObject): Boolean =
            (row.optJSONArray("cell_data")?.length() ?: 0)>0
        val selected=tabs.firstOrNull {
            it.optInt("tab_type",-1)==tabType && hasCards(it)
        } ?: tabs.firstOrNull { hasCards(it) }
            ?: throw IOException("推荐接口没有返回 tab_type=$tabType")
        val items=ContentCatalogParser.officialFeedCards(selected)
        // 官方首包常回 next_offset=0，且偶尔夹带无法解析的空卡片。游标必须按
        // 原始卡片槽位推进，不能按解析成功的条数推进，否则会跳过或重复一页。
        val rawSlots=ContentCatalogParser.officialFeedRawCardCount(selected)
        val reported=selected.optInt("next_offset",-1)
        val next=when {
            reported>requestOffset -> reported
            rawSlots>0 -> requestOffset+rawSlots
            else -> requestOffset+3
        }
        val more=selected.optBoolean("bottom_unlimited",false) ||
            selected.optBoolean("has_more",items.isNotEmpty())
        return RecommendFeed(
            page=CatalogPage(ContentCatalogParser.valid(items),more),
            sessionId=sessionUuid,
            nextOffset=next,
        )
    }

    /**
     * 官方书包 Tab 的 GET 请求。每次重试重新签名；成功后返回原始 JSON。
     */
    private fun ContentRepository.officialRecommendRaw(
        tabType: Int,
        sessionUuid: String,
        offset: Int,
        filterIds: String,
        selection: CategorySelection,
    ): String {
        var problem: IOException=IOException("推荐接口暂无响应")
        repeat(RECOMMEND_ATTEMPTS) { attempt ->
            if(attempt>0) {
                val delay=CATEGORY_RETRY_DELAYS[(attempt-1).coerceAtMost(CATEGORY_RETRY_DELAYS.lastIndex)]
                NetLog.retry("recommend",attempt+1,RECOMMEND_ATTEMPTS,problem.message.orEmpty(),delay)
                try {
                    Thread.sleep(delay)
                } catch(cancelled: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("请求已取消",cancelled)
                }
            }
            try {
                val identity=primaryIdentity()
                val values=officialRecommendationValues(
                    identity=identity,
                    tabType=tabType,
                    sessionUuid=sessionUuid,
                    offset=offset,
                    filterIds=filterIds,
                    selection=selection,
                )
                val signed=signer.sign(
                    "https://${videoHost()}/reading/bookapi/bookmall/tab/v",
                    values,
                    method="GET",
                ).withRecommendationSession(identity)
                val raw=text(
                    signed.url,
                    signed,
                    allowBackup=false,
                    allowDomainFailover=true,
                    storeRegionHeaders=true,
                    userAgentProfile=identity.profile,
                )
                if(raw.isNotBlank()) return raw
                problem=IOException("推荐接口暂无响应")
            } catch(failure: IOException) {
                if(Thread.currentThread().isInterrupted) throw failure
                problem=failure
            }
        }
        throw problem
    }

    private fun ContentRepository.officialRecommendationValues(
        identity: ContentPrimaryIdentity,
        tabType: Int,
        sessionUuid: String,
        offset: Int,
        filterIds: String,
        selection: CategorySelection,
    ): LinkedHashMap<String,String> {
        val values=recommendationValues(identity)
        for(key in listOf("version_code","manifest_version_code","update_version_code","pv_player")) {
            values[key]="74332"
        }
        values["version_name"]="7.4.3.32"
        values["session_uuid"]=sessionUuid
        values["normal_session_id"]=officialNormalSessionId
        values["cold_start_session_id"]=officialColdStartSessionId
        values["normal_session_cnt_in_day"]="1"
        values["cold_start_session_cnt_in_day"]="1"
        values["normal_session_cnt_in_life"]="1"
        values["cold_start_session_cnt_in_life"]="1"
        values["tab_type"]=tabType.toString()
        values["tab_index"]="0"
        values["offset"]=offset.toString()
        values["last_tab_index"]="0"
        values["last_tab_type"]="0"
        values["last_session_video_tab_type"]=tabType.toString()
        values["landing_bottom_tab_type"]="0"
        values["bottom_tab_type"]="7"
        values["bottom_tab_type_list"]=
            if(tabType==16) "7,0,1,2,3,4" else "7,0,1,5,2,4"
        values["client_req_type"]=if(tabType==16) "1028" else "4"
        values["is_horizontal_screen"]="false"
        values["screen_width_px"]=identity.profile["resolution"]
            ?.substringBefore('*')
            ?.toIntOrNull()
            ?.toString()
            ?: "1080"
        values["device_level"]="3"
        values["stream_count"]=OFFICIAL_RECOMMEND_STREAM_COUNT
        values["recommend_extra"]=OFFICIAL_RECOMMEND_EXTRA
        values["is_video_feed_tab_first_request_cold_start"]=
            if(offset==0) "true" else "false"
        values["cold_start_session"]=if(offset==0) "1" else "0"
        values["video_tab_cold_start"]="0"
        values["first_use_category_select"]="false"
        values["page_entry_time"]="0"
        values["has_video_cache"]="false"
        values["disable_digg_stat"]="false"
        values["last_search_query_from_rec"]="false"
        values["ranklist_in_1col_show_times"]="0"
        values["ranklist_in_1col_no_consume_show_times"]="0"
        values["req_rank_category_id"]="0"
        values["req_rank_algo"]="0"
        values["unlimited_short_series_change_type"]="0"
        values["unlimited_short_series_next_offset"]="0"
        values["book_id"]="0"
        values["item_id"]="0"
        values["auth_aweme"]="false"
        values["auth_backward"]="true"
        values["after_genre_preference_popup"]="0"
        if(tabType==16) {
            values["filter_ids"]=filterIds
            values["rec_filter_info_list"]=""
            val gender=when(val selected=selection.gender) {
                null -> "2"
                "male" -> "1"
                "female" -> "0"
                else -> selected.takeIf { it.matches(Regex("[0-9]+")) }
            }
            gender?.let { values["gender"]=it }
        }
        return values
    }

    /**
     * 上报推荐曝光（官方 `FilterImpression`）。
     *
     * 拉推荐流和上报必须使用同一套身份：登录时用账号 Cookie / token 与账号设备档案，
     * 未登录时才回落到主设备。批量、节流和失败重试由 [RecommendBehavior] 统一处理。
     */
    internal fun ContentRepository.reportRecommendStatsImpl(events: List<RecommendStatData>) {
        if(events.isEmpty()) return
        val identity=primaryIdentity()
        val rows=JSONArray()
        events.forEach { event ->
            val row=JSONObject()
                .put("recommend_info",event.recommendInfo)
                .put("group_id",event.groupId)
                .put("content_type",event.contentType)
                .put("stat_item_type",event.statItemType)
                .put("client_time",event.clientTime)
            rows.put(row)
        }
        val payload=JSONObject()
            .put("scene",1)
            .put("stat_data",rows)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val signed=signer.sign(
            recommendStatUrl(),
            recommendReportValues(identity),
            payload,
            method="POST",
        ).withRecommendationSession(identity)
        // 官方上报请求带设备区域头；身份、签名和 UA 档案必须与推荐取流保持一致。
        val raw=text(
            signed.url,
            signed,
            allowBackup=false,
            storeRegionHeaders=true,
            userAgentProfile=identity.profile,
        )
        if(raw.isBlank()) throw IOException("推荐上报暂无响应")
        val reply=JSONObject(raw)
        if(reply.optInt("code",-1)!=0) {
            throw IOException("推荐上报失败（code=${reply.optInt("code",-1)}）")
        }
    }

    private fun ContentRepository.recommendStatUrl(): String =
        "https://${videoHost()}/reading/distribution/recommend_stat/report/v1/"

    /**
     * 读取官方推荐曝光上报频率。
     *
     * 配置项：`short_video_recommend_stat_report_duration`。网络失败时保留 10 秒默认值，
     * 不影响推荐流；成功时缓存一小时，失败五分钟后重试。
     */
    private fun ContentRepository.refreshRecommendStatConfigAsync() {
        val now=System.currentTimeMillis()
        synchronized(recommendStatConfigLock) {
            if(recommendStatConfigLoading || now<recommendStatConfigNextAt) return
            recommendStatConfigLoading=true
        }
        WorkLanes.network.executor.execute {
            var ok=false
            try {
                val identity=primaryIdentity()
                val values=recommendationValues(identity).toMutableMap()
                values["app"]="1"
                values["settings_time"]="0"
                values["_rticket"]=System.currentTimeMillis().toString()
                val host=TncConfigStore.localHost("is.snssdk.com","is.snssdk.com")
                val url="https://$host/service/settings/v3/?"+Signer.encode(values)
                val headers=linkedMapOf<String,String>()
                if(identity.cookie.isNotBlank()) headers["Cookie"]=identity.cookie
                if(identity.token.isNotBlank()) headers["x-tt-token"]=identity.token
                val request=Signer.Request(url,headers,null,"GET")
                val raw=text(
                    url,
                    request,
                    allowBackup=false,
                    allowDomainFailover=false,
                    storeRegionHeaders=true,
                    userAgentProfile=identity.profile,
                )
                if(raw.isNotBlank()) {
                    if(RecommendStatConfigStore.applySettings(raw)) {
                        RecommendBehavior.onConfigChanged()
                        NetLog.log(
                            "recommend stat config enable=${RecommendStatConfigStore.isEnabled()} "+
                                "duration=${RecommendStatConfigStore.durationMs()}ms",
                        )
                    }
                    ok=true
                }
            } catch(_: Exception) {
                ok=false
            } finally {
                synchronized(recommendStatConfigLock) {
                    recommendStatConfigLoading=false
                    recommendStatConfigNextAt=System.currentTimeMillis()+
                        if(ok) 60*60*1000L else 5*60*1000L
                }
            }
        }
    }
