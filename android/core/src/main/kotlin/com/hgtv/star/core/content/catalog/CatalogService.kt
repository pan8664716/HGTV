// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

    /**
     * 榜单页：四个榜单（[RankBoard]）共用这一套取数，只有分类与排序不同。
     *
     * 首页的四个「热播…」剧架与排行榜页的四个榜单读的是**同一份数据**，
     * 因此剧架行末的「查看全部」直接进对应的榜单页，不再另做一套「热门集合页」。
     *
     * 数据取自 **App 分类接口按 [RankBoard.sort] 排**（参数与 [categoryApi] 同一套）：
     * 四个榜都按最高热度（`hot_score`）取数；推荐榜不筛分类，取全站综合热度。
     * 名次与热度都由服务端给——
     * 名次取 `recommend_info.rank`，热度取 `recommend_reason_list.sub_title_list` 里 `data_type==27`
     * 的那一段（形如「9014万」）。分类见 [RankBoard.genre]，`null` 表示不筛分类（全站综合）。
     */
    internal fun ContentRepository.rankApiPage(
        page: Int,
        board: RankBoard,
        size: Int,
        offset: Int = (page-1)*size,
    ): CatalogPage {
        val body=JSONObject()
            .put("filter_ids","")
            .put("req_scene","default")
            .put("offset",offset)
            .put("need_selector_panel",false)
            .put("limit",size)
            .put("select_items",JSONObject()
                // 不筛分类时给一个空数组（与不传这一项等价，实测返回全站综合榜）。
                .put("genre",JSONArray().apply { board.genre?.let { put(it) } })
                .put("sort",JSONArray().put(board.sort)))
            .put("session_id","")
            .put("req_type","only_content")
            .put("client_req_type",3)
        val data=JSONObject(categoryApiRaw(body,if(page<=1) FIRST_PAGE_ATTEMPTS else CATEGORY_ATTEMPTS))
        if(data.optInt("code",-1)!=0) throw IOException("${board.label}榜接口暂不可用")
        val payload=data.optJSONObject("data") ?: throw IOException("${board.label}榜接口结构已变化")
        val positions=linkedMapOf<String,RankPosition>()
        // 卡片字段与分类页是同一套（`apiCard`：title / cover / video_desc / copyright / episode_cnt…），
        // 因此这里直接复用，只有名次与热度要多读两处。
        val rows=payload.optJSONArray("video_data")?.objects().orEmpty()
        // 名次要换算：这一页的「页内序号」加上前面已经排过的条数，才是榜单上的名次。
        val base=offset
        val items=ContentCatalogParser.valid(rows.mapIndexed { index, row ->
            ContentCatalogParser.apiCard(row).also { series ->
                ContentCatalogParser.apiRank(row,base,index)?.let { positions[series.id]=it }
            }
        })
        if(items.isEmpty()) throw IOException("${board.label}榜暂无数据")
        // 接口只给 `has_more`，**没有总数**：因此不编造总量，只报「已看到第几页」。
        // 界面「N / M 部」据此显示（见 `moreCountText`），加载到底自然变成「已显示全部 N 部」。
        // 更新时间这一条接口没有，留空即由界面把那一行整行收起来（不显示任何来源文案）。
        return CatalogPage(items,payload.optBoolean("has_more",false),ComicRanking(positions,page,""))
    }

    /**
     * 分类接口：`POST /reading/distribution/category/landpage/v/`。
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
            .put("req_scene","default")
            .put("offset",categoryOffset(page))
            .put("need_selector_panel",needPanel)
            .put("limit",limit)
            .put("select_items",select)
            .put("session_id","")
            .put("req_type",if(needPanel) "default" else "only_content")
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
            ContentCatalogParser.parseCategoryPanel(payload.optJSONArray("selector_rows"))
        } else null
        val ranking=positions.takeIf { it.isNotEmpty() }?.let {
            ComicRanking(it,page,"")
        }
        return CategoryFeed(CatalogPage(items,more,ranking),panel)
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
                val signed=signer.sign(categoryApiUrl(),values,payload,method="POST")
                    .withRecommendationSession(identity)
                if(NetLog.timing) {
                    NetLog.log("sign ${signed.url.substringAfter("//").substringBefore("/")} body=${payload.size}B took=${(System.nanoTime()-signAt)/1_000_000}ms")
                }
                fun send(one: Signer.Request,profile: Map<String,String>): String {
                    val request=Request.Builder().url(one.url)
                    one.headers.forEach { (key,value) -> if(key!="User-Agent" && key!="Accept") request.header(key,value) }
                    // UA / Accept 保持与改动前一致：UA 用 App 那条（签名默认按「有 body」选视频 UA）。
                    request.header("User-Agent",VendorConstants.userAgent(
                        profile,
                        video = false,
                    ))
                    request.header("Accept","application/json; charset=utf-8,application/x-protobuf")
                    request.post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
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
        "https://${videoHost()}/reading/distribution/category/landpage/v/"
