// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

    /**
     * 榜单页与首页榜单剧架共用的 `cell/change` 取数。
     *
     * HAR 中每个榜都使用 `selected_items=all` + [RankBoard.apiValue]，
     * 单页 10 条。后续页需要把上一页返回的 `next_offset`、`rank_version`、
     * `session_id` 和 `filter_ids` 原样带回；这些游标保存在 [ComicRanking]。
     */
    internal fun ContentRepository.rankApiPage(
        page: Int,
        board: RankBoard,
        size: Int = RANK_API_PAGE_SIZE,
        cursor: ComicRanking? = null,
    ): CatalogPage {
        val offset=when {
            cursor!=null -> cursor.nextOffset.coerceAtLeast(0)
            page<=1 -> 0
            else -> (page-1)*size
        }
        val identity=primaryIdentity()
        val values=recommendationValues(identity).apply {
            for(key in listOf("version_code","manifest_version_code","update_version_code","pv_player")) {
                this[key]="74332"
            }
            this["version_name"]="7.4.3.32"
            this["session_uuid"]=rankSessionUuid
            this["cell_id"]=RANK_CELL_ID
            this["cell_sub_id"]="0"
            this["client_req_type"]="2"
            this["client_template"]="2"
            this["tab_type"]="26"
            this["source_tab_type"]="0"
            this["selected_items"]="all"
            this["sub_selected_items"]=board.apiValue
            this["offset"]=offset.toString()
            this["limit"]="0"
            this["rank_sub_info_id"]="0"
            this["rank_version"]=cursor?.rankVersion.orEmpty()
            this["unlimited_selector_change_type"]=if(cursor==null) "2" else "1"
            this["screen_width_px"]=(identity.profile["resolution"]
                ?.substringBefore('*')
                ?.toIntOrNull()
                ?.times(2)
                ?: 1080).toString()
            putRankDefaults(this)
            cursor?.sessionId?.takeIf { it.isNotBlank() }?.let { this["session_id"]=it }
            cursor?.filterIds?.takeIf { it.isNotBlank() }?.let { this["filter_ids"]=it }
        }
        val signed=signer.sign(rankApiUrl(),values,method="GET")
            .withRecommendationSession(identity)
        val raw=text(
            signed.url,
            signed,
            allowDomainFailover=true,
            storeRegionHeaders=true,
            userAgentProfile=identity.profile,
        )
        if(raw.isBlank()) throw IOException("${board.label}接口暂无响应")
        val root=JSONObject(raw)
        if(root.optString("message").isNotBlank() &&
            !root.optString("message").equals("SUCCESS",ignoreCase=true)) {
            throw IOException("${board.label}接口暂不可用")
        }
        val payload=root.optJSONObject("data") ?: throw IOException("${board.label}接口结构已变化")
        val cellView=payload.optJSONObject("cell_view")
        val rows=cellView?.optJSONArray("cell_data")?.objects().orEmpty()
            .flatMap { cell -> cell.optJSONArray("video_data")?.objects().orEmpty() }
        val positions=linkedMapOf<String,RankPosition>()
        val items=ContentCatalogParser.valid(rows.mapIndexed { index,row ->
            ContentCatalogParser.apiCard(row).also { series ->
                positions[series.id]=RankPosition(
                    rank=offset+index+1,
                    heat=ContentCatalogParser.rankHeat(row),
                )
            }
        })
        if(items.isEmpty()) throw IOException("${board.label}暂无数据")
        val nextOffset=payload.optInt("next_offset",-1)
            .takeIf { it>=0 }
            ?: cellView?.optInt("next_offset",-1)?.takeIf { it>=0 }
            ?: (offset+rows.size)
        val hasMore=payload.optBoolean("has_more",cellView?.optBoolean("has_more",false)==true)
        val rankVersion=payload.optString("rank_version")
            .ifBlank { cellView?.optString("rank_version").orEmpty() }
        val sessionId=payload.optString("session_id")
            .ifBlank { cellView?.optString("session_id").orEmpty() }
        val filterIds=rows.mapNotNull { row ->
            row.optString("series_id").takeIf { it.isNotBlank() }
        }.joinToString(",")
        val updatedText=ContentCatalogParser.rankSubtitle(cellView?.optJSONObject("cell_selector"),board)
        return CatalogPage(
            items=items,
            hasMore=hasMore,
            ranking=ComicRanking(
                positions=positions,
                totalPages=page,
                updatedText=updatedText,
                nextOffset=nextOffset,
                rankVersion=rankVersion,
                sessionId=sessionId,
                filterIds=filterIds,
            ),
        )
    }

    private fun putRankDefaults(values: LinkedHashMap<String,String>) {
        val defaults=linkedMapOf(
            "app_launch_times" to "0",
            "author_id" to "0",
            "background_selected_items" to "",
            "book_comment_id" to "0",
            "category_id" to "0",
            "celebrity_user_id" to "",
            "cold_start_session" to "0",
            "current_chapter_num" to "0",
            "disable_digg_stat" to "false",
            "ecom_category_id" to "0",
            "ecom_impression_start_time" to "0",
            "ecom_refresh_type" to "0",
            "ecom_sort_by" to "0",
            "genre" to "0",
            "genre_type" to "0",
            "idol_tag_id" to "0",
            "inner_algo" to "0",
            "inner_category_id" to "0",
            "is_horizontal_screen" to "false",
            "item_id" to "0",
            "pad_column_cover" to "0",
            "pad_column_detail" to "0",
            "page" to "0",
            "page_entry_time" to "0",
            "panel_selected_items" to "",
            "plan_id" to "0",
            "post_id" to "0",
            "reader_single_col_from_ip_strategy" to "false",
            "related_book_id" to "0",
            "search_tab_type" to "0",
            "sub_genre" to "0",
            "sub_tag_id" to "0",
            "support_gender_list" to "false",
            "tag_id" to "0",
            "total_chapter_num" to "0",
            "video_tab_cold_start" to "0",
            "web_page_version_code" to "0",
        )
        // Android 5/6 的 java.util.Map 没有 Java 8 的 putIfAbsent；这里在发请求前就会
        // 抛 NoSuchMethodError，导致八大榜单连网络请求都发不出去。
        defaults.forEach { (key,value) ->
            if (key !in values) values[key]=value
        }
    }

    /**
     * 分类接口：`POST /reading/distribution/category/landpage/v:version/`。
     *
     * **2026-10-21 起要签名**（早先只带 User-Agent / Accept / Content-Type 三个头就能用，
     * 现在缺签名头一律回空响应，见 [categoryApiRaw]）。不需要登录态。参数要点：
     *
     * * `select_items` 的每个值都必须是**数组**：给字符串会回
     *   `HTTP 400 req.SelectItems[genre]: expected array, actual value: short_play (string)`；
     * * `filter_ids` 必须是**字符串**（给 `[]` 会回 `expected string, actual value: []`）；
     * * 翻页靠 `offset`（0 / 60 / 120…）与 `limit`（见 [CATEGORY_PAGE_SIZE]，
     *   实测 `limit` 可以放大到 200，服务端会照数返回）；
     * * `req_type` 用 `only_content`（要筛选面板时才用 `default` + `need_selector_panel=true`）。
     *
     * [path] 来自 [CategoryCatalog]：大类段映射成接口的 `genre`
     *（`comic_series` / `short_play` / `ai_series`），小类段就是接口的主题标识（`cate_*`）。
     */
    internal fun ContentRepository.categoryApi(selection: CategorySelection, page: Int, needPanel: Boolean): CategoryFeed {
        // 「全部推荐」是一条最多约 100 条的服务端推荐流，正好作为首屏。
        // 后续页统一接 hot_score，从第 101 条继续；带了主题 / 设定等条件时首屏也直接走 hot_score。
        val recommendation=page==1 && selection.sort==null && selection.hasNoDetailFilters()
        val fallback=selection.sort==null && !recommendation
        val select=ContentCatalogParser.categorySelectItems(
            selection,
            if(fallback) "hot_score" else selection.sort,
        )
        val limit=CATEGORY_PAGE_SIZE
        val body=JSONObject()
            .put("filter_ids","")
            .put("req_scene",categoryRequestScene(selection.genre))
            .put("offset",categoryOffset(page))
            .put("need_selector_panel",false)
            .put("limit",limit)
            .put("select_items",select)
            .put("session_id","")
            .put("req_type","only_content")
            .put("client_req_type",3)
        val data=JSONObject(categoryApiRaw(body,if(page<=1) FIRST_PAGE_ATTEMPTS else CATEGORY_ATTEMPTS))
        if(data.optInt("code",-1)!=0) throw IOException("分类接口暂不可用")
        val payload=data.optJSONObject("data") ?: throw IOException("分类接口结构已变化")
        val rows=(payload.optJSONArray("video_data") ?: JSONArray()).objects()
        val offset=categoryOffset(page)
        val positions=linkedMapOf<String,RankPosition>()
        val items=ContentCatalogParser.valid(rows.mapIndexed { index,row ->
            ContentCatalogParser.apiCard(row).also { series ->
                ContentCatalogParser.apiRank(row,offset,index)?.let { positions[series.id]=it }
            }
        })
        val serverMore=payload.optBoolean("has_more",false)
        // 服务端偶尔在“刚好装满一页”时把 has_more 错报成 false；这种满页不能提前终止，
        // 否则分类页会在同一位置停住。多请求一页，真到底时下一页为空自然结束。
        val fullPage=rows.size>=limit
        val more=(serverMore || fullPage) && page<ContentRepository.MAX_CATALOG_PAGE
        val panel=if(needPanel) {
            runCatching { categoryPanel(selection) }.getOrNull()
                ?: ContentCatalogParser.parseCategoryPanel(payload.optJSONArray("selector_rows"))
                    ?.withCatalogFilters(selection.genre)
        } else null
        val ranking=positions.takeIf { it.isNotEmpty() }?.let {
            ComicRanking(it,page,"")
        }
        return CategoryFeed(CatalogPage(items,more,ranking),panel)
    }

    /**
     * 官方分类面板是独立 `only_panel` 请求，内容响应不再同时携带 `selector_rows`。
     */
    private fun ContentRepository.categoryPanel(selection: CategorySelection): CategoryPanel? {
        val body=JSONObject()
            .put("req_scene",categoryRequestScene(selection.genre))
            .put("need_selector_panel",false)
            .put("req_type","only_panel")
        val data=JSONObject(categoryApiRaw(body,FIRST_PAGE_ATTEMPTS))
        if(data.optInt("code",-1)!=0) throw IOException("分类面板暂不可用")
        val payload=data.optJSONObject("data") ?: return null
        return ContentCatalogParser.parseCategoryPanel(payload.optJSONArray("selector_rows"))
            ?.withCatalogFilters(selection.genre)
    }

    /** 分类页 offset：每页 100 条，第 2 页从 100 开始。 */
    private fun ContentRepository.categoryOffset(page: Int): Int =
        if(page<=1) 0 else CATEGORY_FIRST_PAGE_SIZE+(page-2)*CATEGORY_PAGE_SIZE
    /**
     * 分类 / 榜单 / 推荐共用的那条 POST。
     *
     * 后续分页使用 [CATEGORY_ATTEMPTS]（8 次、退避合计 3.8s），失败发生在用户已经看到
     * 上一页之后，可以多等一会儿。分类 / 榜单首屏与推荐页只给 3 次，避免整页长时间停在
     * 「正在加载」上。
     */
    internal fun ContentRepository.categoryApiRaw(
        body: JSONObject,
        attempts: Int = CATEGORY_ATTEMPTS,
    ): String {
        val payload=body.toString().toByteArray()
        val identity=primaryIdentity()
        var problem: IOException=IOException("分类接口暂无响应")
        for(attempt in 0 until attempts) {
            if(attempt>0) {
                val delay=CATEGORY_RETRY_DELAYS[(attempt-1).coerceAtMost(CATEGORY_RETRY_DELAYS.lastIndex)]
                NetLog.retry("category",attempt+1,attempts,problem.message.orEmpty(),delay)
                try {
                    Thread.sleep(delay)
                } catch(cancelled: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("请求已取消",cancelled)
                }
            }
            try {
                // 每次重试都重签：`ts` / `_rticket` 是签名的一部分，复用旧签名会被判过期。
                // 分类 / 榜单这条只认 POST + JSON body（GET 该路径回 404）。
                val signAt=System.nanoTime()
                val values=recommendationValues(identity)
                // 官方分类请求是未压缩 JSON，x-ss-stub 与 v5 六头都按这份原始 body 计算。
                val signed=signer.sign(
                    categoryApiUrl(),
                    values,
                    payload,
                    method="POST",
                    gzipBody=false,
                )
                    .withRecommendationSession(identity)
                if(NetLog.timing) {
                    NetLog.log("sign ${signed.url.substringAfter("//").substringBefore("/")} body=${payload.size}B took=${(System.nanoTime()-signAt)/1_000_000}ms")
                }
                fun send(one: Signer.Request,profile: Map<String,String>): String {
                    // v5 签名器会把 JSON 压成 gzip，x-ss-stub 也按压缩后的字节计算。
                    // 这里必须发送签名器返回的 body，不能重新发送未压缩的 payload。
                    val encodedPayload=one.body ?: payload
                    val request=Request.Builder().url(one.url)
                    one.headers.forEach { (key,value) -> if(key!="User-Agent" && key!="Accept") request.header(key,value) }
                    // UA / Accept 保持与改动前一致：UA 用 App 那条（签名默认按「有 body」选视频 UA）。
                    request.header("User-Agent",VendorConstants.userAgent(
                        profile,
                        video = false,
                    ))
                    request.header("Accept","application/json; charset=utf-8,application/x-protobuf")
                    request.post(encodedPayload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    val callAt=System.nanoTime()
                    return RequestScope.execute(http.newCall(request.build())) { response ->
                        if(!response.isSuccessful) {
                            throw CatalogHttpException(response.code,"分类接口失败（HTTP ${response.code}）")
                        }
                        val bodyAt=System.nanoTime()
                        val text=response.body?.string().orEmpty()
                        if(NetLog.timing) {
                            NetLog.log(
                                "category call headers=${(bodyAt-callAt)/1_000_000}ms"+
                                " read=${(System.nanoTime()-bodyAt)/1_000_000}ms chars=${text.length}"
                            )
                        }
                        text
                    }
                }
                var raw=send(signed,identity.profile)
                if(raw.isBlank()) {
                    val currentHost=runCatching { okhttp3.HttpUrl.parse(signed.url)?.host }.getOrNull().orEmpty()
                    val hosts=alternateApiHosts(currentHost)
                    for((index,host) in hosts.withIndex()) {
                        NetLog.retry("category_domain",index+1,hosts.size,"HTTP 200 empty body from $currentHost")
                        val retry=resignForHost(signed,host,identity.profile) ?: continue
                        raw=send(retry,identity.profile)
                        if(raw.isNotBlank()) {
                            TncConfigStore.markHostSuccess(host)
                            NetLog.emit("category domain failover accepted host=$host")
                            break
                        }
                    }
                }
                if(raw.isNotBlank()) return raw
                problem=IOException("分类接口暂无响应")
            } catch(failure: IOException) {
                // 只重发「可能自己好」的那两类：空响应体（上面那个分支）与 5xx / 429。
                // 取消、明确的 4xx、以及连接层的硬错误（超时 / 断网）立刻抛出——
                // 重发只是把同一次失败重复 8 遍，界面白等 4 秒还是一样的结果。
                if(Thread.currentThread().isInterrupted || failure.message=="请求已取消") throw failure
                if(failure !is CatalogHttpException) throw failure
                if(failure.status in 400..499 && failure.status!=429) throw failure
                problem=failure
            }
        }
        NetLog.error("[API] error scope=category attempts=$attempts reason=${problem.message.orEmpty().take(220)}")
        throw problem
    }

    internal fun ContentRepository.categoryApiUrl(): String =
        "https://${videoHost()}/reading/distribution/category/landpage/v:version/"

    internal fun ContentRepository.rankApiUrl(): String =
        "https://${videoHost()}/reading/bookapi/bookmall/cell/change/v:version/"
