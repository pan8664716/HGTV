// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import okhttp3.Request
import java.io.IOException

    /**
     * 官网的联想词：`GET /api/search/suggestion?query={关键词}&count=N`。
     *
     * 免签名、免登录；`video_data` 与卡片同源，有 `series_id` 的那几条本身就是一部剧。
     * 搜索源选「官网」时搜索页那一行用它。
     *
     * 老地址 `/incent_resource/suggestion?app_id=8662` 现在仍然能回，但字段是 `suggest_list`、
     * 与页面那一套绑得更紧；新地址跟搜索结果是同一个接口组，一起换过来更稳（两个都认）。
     */
    internal fun ContentRepository.webSuggestionsImpl(keyword: String, count: Int): List<SearchSuggestion> {
        val clean=keyword.trim().take(WEB_SEARCH_QUERY_MAX)
        if(clean.isEmpty()) return emptyList()
        val wanted=count.coerceIn(1,20)
        val url="$SITE_HOST/api/search/suggestion?query=${Signer.encode(clean)}&count=$wanted"
        val raw=runCatching { siteText(url) }.getOrNull().orEmpty()
        if(raw.isBlank()) return emptyList()
        return ContentSearchParser.parseWebSuggestions(raw,wanted)
    }

    internal fun ContentRepository.appSuggestions(keyword: String, count: Int): List<SearchSuggestion> {
        val clean=keyword.trim()
        if(clean.isEmpty()) return emptyList()
        val wanted=count.coerceIn(1,20)
        val values=searchValues()
        values["q"]=clean
        val signed=signer.sign("https://${accountHost()}/reading/bookapi/search/suggest/v:version/",values,deviceOverride=values)
        val raw=text(signed.url,signed.withSession())
        if(raw.isBlank()) throw IOException("提示词暂不可用")
        return ContentSearchParser.parseAppSuggestions(raw,wanted)
    }

    /**
     * 热搜词：搜索页空输入时铺在下面那一排。
     *
     * 平台**没有**热搜榜接口（`hot_word` / `suggest_word` 这类路径实测全是 404），
     * 但 [suggestions] 这个联想词接口本身就是「按热度排的候选」：拿几个题材词当种子各问一次，
     * 把返回里**不是某一部剧**的那些（`title` / `common_query` / `short_play_category` / `category` /
     * `album`——`video_data` 是空对象）收集起来去重，就是一份现成的热词表。
     *
     * 只取种子词里靠前的若干条：联想词是按相关度排的，越靠前越像「大家在搜的」。
     * 全部种子词都失败时退回 [FALLBACK_HOT]：搜索页不能因为一个提示接口而空着。
     */
    internal fun ContentRepository.hotSearchesImpl(limit: Int, source: SearchSource): List<String> {
        val wanted=limit.coerceIn(1,30)
        // 种子词**并发**问（串行五个要 1.8s，并发后是单次请求的时间）。
        // API 19 没有 CompletableFuture（API 24），用普通的 Future 提交到同一个池子里等齐。
        val tasks=SUGGEST_SEEDS.map { seed -> SUGGEST_POOL.submit(java.util.concurrent.Callable { suggestionsPool(seed,source) }) }
        val pools=tasks.map { task -> runCatching { task.get() }.getOrDefault(emptyList()) }
        // 分层 + 层内轮询：
        // - 先按词类型分层（题材 / 分类 > 平台搜索建议 > 书名）；
        // - 同一层里**按种子轮询**交替取——一个种子单独就能填满整张表，
        //   顺着一个种子取的话整排热搜词都是同一个题材的变体（实测全是「…短剧」）。
        val seen=linkedSetOf<String>()
        HOT_WORD_ORDER.forEach { type ->
            if(seen.size>=wanted) return@forEach
            val lists=pools.map { pool -> pool.filter { it.second==type }.map { it.first } }
            var round=0
            while(seen.size<wanted && lists.any { it.size>round }) {
                lists.forEach { list -> list.getOrNull(round)?.let { if(seen.size<wanted) seen += it } }
                round++
            }
        }
        return if(seen.isEmpty()) FALLBACK_HOT.take(wanted) else seen.toList()
    }

    /** 一个种子词的候选：`(词, word_type)`，过滤掉剧名、过长过短的与重复的。 */
    private fun ContentRepository.suggestionsPool(seed: String,source: SearchSource): List<Pair<String,String>> =
        runCatching { suggestions(seed,20,source) }.getOrDefault(emptyList())
            .filter { it.series==null && it.name.length in 2..12 }
            .map { it.name to it.wordType }
            .distinctBy { it.first }

    /**
     * 界面上的搜索入口。
     *
     * [source] 决定走哪一套：App 接口（默认，官方综合请求、游标翻页）或官网搜索
     * （免签名免登录，把 10 个物理页并成 1 个逻辑页）。界面侧只认 [CatalogPage]。
     */
    internal fun ContentRepository.searchImpl(
        keyword: String,
        page: Int,
        source: SearchSource,
        fallbackPages: Int,
    ): CatalogPage {
        if(source==SearchSource.APP && isWebFallback(keyword,page)) {
            val result=runCatching { webFallbackResult(keyword,page,fallbackPages) }.getOrNull()
            if(result!=null) {
                SearchDiag.record(keyword,page,SearchSource.WEB,result.items.size,
                    note="App 搜索接口没有数据，已自动改用官网搜索",
                    registered=!deviceIdentity["device_id"].isNullOrBlank(),alive=appSearchAlive,degraded=true)
                return result
            }
        }
        // 每次搜索都在这里留一份现场（见 [SearchDiag]）：失败时面板给的是**判读结论**，
        // 不是页面上那句「没有找到相关剧集」。成功也要记——「0 条」只有配上
        // 「接口真的回答了」才等于「确实没有匹配」。
        //
        // ## 失败时先自愈、再降级
        //
        // 顺序不能反。App 搜索空响应最常见的原因就是「这台设备在服务端没注册过」，
        // 那时正确的动作是**换一套身份重注册再搜一次**；直接退到官网搜索只是绕开了故障，
        // 设备仍然是坏的（播放照样回 `101000`），而且用户再也看不到「搜索能用」以外的修复。
        // 早先这里是先降级官网、成功后直接 return，`DeviceNotRegisteredException` 根本
        // 传不到界面层，电视端的自愈逻辑成了死代码。
        //
        // 自愈只做一次（[healed]），失败之后才走官网兜底；两条都不成，异常照旧抛给界面。
        var healed = false
        while (true) {
            val registered = !deviceIdentity["device_id"].isNullOrBlank()
            try {
                val result = if(source==SearchSource.WEB) webSearch(keyword,page)
                    else appSearchPage(keyword,page,SEARCH_PAGE_SIZE).also { clearWebFallback(keyword) }
                // 官网这条「部分物理页失败」不算整页失败（其余页照常出内容），但要写进判读结论：
                // 否则面板上是「有结果 37 条」加一行 502，用户不知道少掉的那几页去哪了。
                val note = if(source==SearchSource.WEB) webSearchNote.get().orEmpty() else ""
                SearchDiag.record(keyword,page,source,result.items.size,note=note,registered=registered,alive=appSearchAlive)
                return result
            } catch(e: Exception) {
                // ① 设备被拒：换一套身份重注册，然后原地重搜一次。
                if(!healed && source==SearchSource.APP && e is DeviceNotRegisteredException) {
                    healed = true
                    val ok = runCatching { ContentRepository.deviceHeal?.invoke(this,"搜索被拒") }.getOrNull()==true
                    if(ok) {
                        NetLog.retry("search_device_heal",2,2,"device rejected; identity rebuilt")
                        NetLog.emit("device healed after search rejection; retrying app search")
                        continue
                    }
                }
                // ② **App 接口「答了但没数据」时自动改走官网**（issue #44）。
                //
                // 现场：`/reading/bookapi/search/tab/v` 回 HTTP 200 + 0 字节，或者回一份没有
                // `search_tabs` 的 JSON。这两种都不是「网络不通」，而是这条接口这一刻不给数据；
                // 官网那条（`/api/search/result`）同一时刻是 200 + 完整 JSON。
                // 早先这里如实抛错，界面就只能整页挡住「接口没有返回任何 tab 数据」，
                // 用户的第一反应是「电视坏了 / 网络坏了」。
                //
                // 只在「接口答了、只是没内容」时降级：真正断网时官网也一样不通，白等一个超时。
                if(source==SearchSource.APP && worthWebFallback(e)) {
                    val fallback=runCatching { webFallback(keyword,page,fallbackPages) }.getOrNull()
                    if(fallback!=null) {
                        SearchDiag.record(
                            keyword,page,SearchSource.WEB,fallback.items.size,
                            note="App 搜索接口没有数据，已自动改用官网搜索",
                            registered=registered,alive=appSearchAlive,degraded=true
                        )
                        return fallback
                    }
                }
                val (reason,detail)=SearchDiag.classify(e)
                SearchDiag.record(keyword,page,source,0,reason,detail,registered,appSearchAlive)
                throw e
            }
        }
    }

    /**
     * 这次失败值不值得换官网再试一次。
     *
     * 判据是「接口**答了**，只是没给内容」：设备没注册（空响应体）、响应里没有
     * `search_tabs`。游标过期不算——那是回到第 1 页重搜的事，换源只会把页码搞乱；
     * 网络类失败也不算——官网走的是同一根网线，换过去只会再等一次超时。
     */
    private fun worthWebFallback(e: Exception): Boolean = when(e) {
        is DeviceNotRegisteredException -> true
        is SearchApiException ->
            e.reason==SearchDiag.FAILURE_DEVICE || e.reason==SearchDiag.FAILURE_FORMAT
        else -> false
    }

    /**
     * 官网搜索：`GET https://hongguoduanju.com/api/search/result?query={关键词}&page=N`。
     *
     * 2026-10 之前走的是搜索页 HTML 里的 `_ROUTER_DATA`；`www` 改成 301 到裸域名、
     * 页面也不再内联 SSR 数据之后，那条路整条失效。现在直连官网自己用的 JSON 接口，
     * 字段与卡片同源（`searchList[].video_data`），`totalPages` / `hasMore` 由服务端给。
     *
     * **一个逻辑页给界面 100 条**：服务端一页只有 10 条，而且 `count` / `page_size` / `limit`
     * 这类参数一律被忽略（见 [WEB_SEARCH_PAGE_SIZE]），所以这里把 [WEB_SEARCH_BATCH] 个
     * 物理页并发拉回来拼成一份，界面侧「展示更多」一次就能多出一屏十行。
     *
     * 查不到东西时接口回的是 `{"searchList":[],"totalPages":1}`（HTTP 200），
     * 按「没有结果」处理，让界面显示「换个关键词试试」。
     */
    internal fun ContentRepository.webSearchImpl(keyword: String,page: Int): CatalogPage {
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..ContentRepository.MAX_CATALOG_PAGE)
        // 服务端只收 30 个字符（更长的回 400）：这里先截断，宁可少两个字的查询，
        // 也不要让用户看到「官网搜索没有结果」。
        val clean=keyword.trim().take(WEB_SEARCH_QUERY_MAX)
        // 上一次搜索留下的摘要不能带到这一次（同一个线程会被复用）。
        webSearchNote.set("")
        val first=(page-1)*WEB_SEARCH_BATCH+1
        return webSearchPages(clean,page,first,WEB_SEARCH_BATCH)
    }
    /** App 搜索自动降级：首屏少拉几页，后续逻辑页按实际物理页游标连续取。 */
    private fun ContentRepository.webFallback(keyword: String,page: Int,batch: Int): CatalogPage {
        val clean=keyword.trim().take(WEB_SEARCH_QUERY_MAX)
        val wantedPages=batch.coerceIn(1,WEB_SEARCH_BATCH)
        val first=synchronized(webFallbackCursors) {
            if(page==1 || !webFallbackCursors.containsKey(clean)) 1 else webFallbackCursors.getValue(clean)
        }
        val result=webSearchPages(clean,page,first,wantedPages)
        synchronized(webFallbackCursors) {
            if(webFallbackCursors.size>=8) webFallbackCursors.keys.firstOrNull()?.let(webFallbackCursors::remove)
            webFallbackCursors[clean]=first+wantedPages
        }
        return result
    }
    private fun ContentRepository.clearWebFallback(keyword: String) {
        val clean=keyword.trim().take(WEB_SEARCH_QUERY_MAX)
        synchronized(webFallbackCursors) { webFallbackCursors.remove(clean) }
    }
    private fun ContentRepository.isWebFallback(keyword: String,page: Int): Boolean = page>1 &&
        synchronized(webFallbackCursors) { webFallbackCursors.containsKey(keyword.trim().take(WEB_SEARCH_QUERY_MAX)) }
    private fun ContentRepository.webFallbackResult(keyword: String,page: Int,batch: Int): CatalogPage = webFallback(keyword,page,batch)
    private fun ContentRepository.webSearchPages(clean: String,page: Int,first: Int,wantedPages: Int): CatalogPage {
        // 上一次搜索留下的摘要不能带到这一次（同一个线程会被复用）。
        webSearchNote.set("")
        val scope=RequestScope.current()
        // 与 [homeFeed] 同理：共享 `fanout` 车道，不再每次搜索新建一个 10 线程池
        // （那等于「每敲一次键盘就创建再销毁十条线程」）。
        val pool=WorkLanes.fanout.executor
        val tasks=(first until first+wantedPages).map { physical ->
            pool.submit(java.util.concurrent.Callable {
                // 取消（切页 / 重新搜索）沿着 [RequestScope] 传进这十条请求，一起收手。
                RequestScope.withScope(scope) { runCatching { webSearchPageRetry(clean,physical) }.getOrNull() }
            })
        }
        val parts=tasks.map { task -> runCatching { task.get() }.getOrNull() }
        // 十个物理页全部失败才算这一页失败：部分失败时把拿到的那些照常给界面。
        // 「查不到」不走这里：接口对没有结果的关键词回的是 200 + 空 `searchList`，
        // 因此走到这一句就是真的连不上（或后端整体不可用），该让界面给「重试」。
        if(parts.all { it==null }) {
            webSearchNote.set("官网 ${wantedPages} 个物理页全部失败")
            NetLog.log("hongguoduanju.com/api/search/result 第${first}~${first+wantedPages-1}页全部失败")
            throw SearchApiException(SearchDiag.FAILURE_NETWORK,"官网 ${wantedPages} 个物理页全部失败")
        }
        // 部分页失败：**只汇总一条**，不把每个 502 都写进面板（那会把 40 条近况刷满，
        // 看起来像「整个官网搜索坏了」）。逐条的 HTTP 状态已在 [webSearchPageRetry] 里静默。
        val failed=parts.count { it==null }
        if(failed>0) {
            val note="官网搜索 ${failed}/${wantedPages} 页重试后仍失败"
            webSearchNote.set(note)
            NetLog.log("hongguoduanju.com/api/search/result 第${first}~${first+wantedPages-1}页 · $note")
        } else {
            webSearchNote.set("")
        }
        val items=mutableListOf<Series>()
        parts.forEach { part -> if(part!=null) items+=part.items }
        // 服务端分页偶尔重复（同一部剧出现在相邻两页）：按关键词记一份已发过的 id，只回新的。
        val seen=synchronized(webSearchSeen) {
            if(page==1) {
                if(webSearchSeen.size>=8) webSearchSeen.keys.firstOrNull()?.let(webSearchSeen::remove)
                webSearchSeen[clean]=mutableSetOf()
            }
            webSearchSeen.getOrPut(clean) { mutableSetOf() }
        }
        val fresh=synchronized(webSearchSeen) { items.filter { seen.add(it.id) } }
        // 「还有更多」看这一批里**最后一个成功的物理页**：它说没有了，后面几页也就没了。
        val more=parts.filterNotNull().lastOrNull()?.hasMore==true && first+wantedPages-1<ContentRepository.MAX_CATALOG_PAGE
        return CatalogPage(fresh,more)
    }
    /**
     * 官网搜索一个物理页，带重试与**静默上报**。
     *
     * 两层保护：
     * * 后端抖动（`502 upstream_unavailable`）隔 200~400ms 重发通常就过，因此重试 [WEB_SEARCH_RETRY] 次；
     * * 每次尝试都在 [NetLog.quiet] 里跑：失败由 [webSearch] 汇总成一条，
     *   不让 10 个物理页的 502 把调试面板的「近况」刷满。
     *
     * 取消（切页 / 重新搜索）不重试：线程被打断或取消域已取消时立刻抛出，
     * 否则用户切走之后线程还在为上一次搜索空转。
     */
    private fun ContentRepository.webSearchPageRetry(keyword: String,page: Int): CatalogPage {
        var last: Throwable?=null
        repeat(WEB_SEARCH_RETRY+1) { attempt ->
            if(attempt>0) {
                val delay=WEB_SEARCH_RETRY_DELAY*attempt
                NetLog.retry("web_search",attempt+1,WEB_SEARCH_RETRY+1,last?.message.orEmpty(),delay)
                try {
                    Thread.sleep(delay)
                } catch(cancelled: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("请求已取消",cancelled)
                }
            }
            try {
                return NetLog.quiet { webSearchPage(keyword,page) }
            } catch(problem: Throwable) {
                // 取消 / 打断不是「这一页坏了」：不重试，直接交回上层（上层按失败丢弃）。
                if(Thread.currentThread().isInterrupted || isInterruption(problem)) throw problem
                last=problem
            }
        }
        val failure=last ?: IOException("官网搜索暂不可用")
        NetLog.error("[API] error scope=web_search attempts=${WEB_SEARCH_RETRY+1} reason=${failure.message.orEmpty().take(220)}")
        throw failure
    }
    /**
     * 官网搜索的一个**物理页**（服务端一页 10 条）：只解析、不去重、不拼页。
     *
     * 去重放在 [webSearch] 里做：跨物理页的重复只有拼起来之后才看得出来。
     */
    private fun ContentRepository.webSearchPage(keyword: String,page: Int): CatalogPage {
        val url="$SITE_HOST/api/search/result?query=${Signer.encode(keyword)}&page=$page"
        // 重试放在 [webSearchPageRetry]：502 带响应体时 [siteText] 不抛异常，
        // 这里的 `code` 分支才会判失败，因此重试必须在**解析之后**做。
        val raw=siteText(url)
        return ContentSearchParser.parseWebSearchPage(raw,page,WEB_SEARCH_PAGE_SIZE,ContentRepository.MAX_CATALOG_PAGE)
    }
    /**
     * 官网页面的一次 GET：不走 App 的签名与设备参数（那套头对官网没有意义），
     * 只带一个普通浏览器的 UA 与语言。
     */
    private fun ContentRepository.siteText(url: String,retries: Int = 0): String {
        var last: Throwable? = null
        repeat(retries+1) { attempt ->
            if(attempt>0) NetLog.retry("site_search",attempt+1,retries+1,last?.message.orEmpty())
            val request=Request.Builder().url(url)
                .header("User-Agent","Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
                .header("Accept-Language","zh-CN,zh;q=0.9")
                // 官网的 JSON 接口：不要 HTML，也不要缓存中间层给的那一份。
                .header("Accept","application/json")
                .get().build()
            try {
                return RequestScope.execute(http.newCall(request)) { response ->
                    val body=response.body ?: throw IOException("内容响应为空")
                    if(body.contentLength()>16*1024*1024) throw IOException("内容响应过大")
                    // 502 时响应体里仍然带着 `{"code":"upstream_unavailable"}`：读出来交给
                    // 调用方判读，比只丢一句「HTTP 502」有用得多。
                    val raw=body.string()
                    if(!response.isSuccessful && raw.isBlank()) throw IOException("内容请求失败（HTTP ${response.code}）")
                    raw
                }
            } catch(problem: Throwable) {
                last=problem
            }
        }
        val failure=last ?: IOException("内容请求失败")
        NetLog.error("[API] error scope=site_search attempts=${retries+1} reason=${failure.message.orEmpty().take(220)}")
        throw failure
    }
    /**
     * 搜索：只走 App 搜索接口（`GET /reading/bookapi/search/tab/v`，参数全在 query 里）。
     *
     * 官方 7.4.3.32 用一次不带 `tab_type` 的综合请求返回全部 tab，取首个有数据的 tab；
     * 第 2 页起带上一页的游标（`search_id` / `passback` / `next_offset`），见 [appSearch]。
     *
     * 失败语义：接口回空响应体 = 这台设备没在服务端注册过（见 [registerDevice]），
     * 如实抛错让界面提示，**不再**用别的数据源顶替；游标过期抛 [SearchSessionExpiredException]，
     * 由界面回到第 1 页重搜。
     */
    internal fun ContentRepository.search(keyword: String, page: Int, types: List<ContentType>, count: Int): CatalogPage {
        require(types.isNotEmpty())
        return appSearchPage(keyword,page,count)
    }

    /** 官方形态的一次综合搜索。 */
    internal fun ContentRepository.appSearchPage(keyword: String,page: Int,count: Int): CatalogPage =
        appSearch(keyword,page,SEARCH_FEED_TAB,count)
    /**
     * 搜索接口空响应时的原地补发（见 [appSearch]）。
     *
     * 最多补 [SEARCH_BLANK_RETRIES] 次、每次隔 [SEARCH_BLANK_RETRY_DELAY_MS]。
     * 间隔不能省：实测同一条请求连发（0 间隔）几乎全空，短暂停顿后才有命中。
     */
    internal fun ContentRepository.retryBlankSearch(raw: String, signed: Signer.Request): String {
        var current=raw
        repeat(SEARCH_BLANK_RETRIES) { attempt ->
            if(current.isNotBlank()) return current
            NetLog.retry(
                "search_blank",
                attempt+1,
                SEARCH_BLANK_RETRIES,
                "HTTP 200 empty body",
                SEARCH_BLANK_RETRY_DELAY_MS,
            )
            try {
                Thread.sleep(SEARCH_BLANK_RETRY_DELAY_MS)
            } catch(e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("请求已取消",e)
            }
            current=text(signed.url,signed.withSession())
        }
        return current
    }

    internal fun ContentRepository.appSearch(keyword: String, page: Int, tabType: Int, count: Int): CatalogPage {
        val scope=if(tabType==SEARCH_FEED_TAB) SEARCH_FEED_SCOPE else tabType
        val signed=searchRequest(searchQuery(keyword,page,tabType,count))
        val tab=ContentSearchParser.parseTab(searchResponse(signed),tabType,SEARCH_FEED_TAB)
        return CatalogPage(ContentSearchParser.cards(tab),tab.hasMore && page<100).also {
            synchronized(searchCursors) {
                searchCursors[ContentRepository.SearchCursorKey(scope,keyword,page,count)]=
                    ContentRepository.SearchCursor(tab.searchId,tab.passback,tab.nextOffset,clock())
                while(searchCursors.size>100) searchCursors.remove(searchCursors.keys.first())
            }
        }
    }
    /** 官方综合搜索不带 `tab_type`；用户 / 类型专用查询继续显式指定。 */
    private fun ContentRepository.searchQuery(keyword: String,page: Int,tabType: Int,count: Int): LinkedHashMap<String,String> {
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..100)
        val scope=if(tabType==SEARCH_FEED_TAB) SEARCH_FEED_SCOPE else tabType
        val values=searchValues()
        values.putAll(linkedMapOf("query" to keyword,"count" to count.toString(),"offset" to ((page-1)*count).toString(),"bookshelf_search_plan" to "4","use_correct" to "true","user_is_login" to "0"))
        if(tabType!=SEARCH_FEED_TAB) values["tab_type"]=tabType.toString()
        val cursor=synchronized(searchCursors) {
            val now=clock()
            searchCursors.entries.removeAll { now-it.value.updatedAt>300000 ||
                (page==1 && it.key.scope==scope && it.key.keyword==keyword) }
            searchCursors[ContentRepository.SearchCursorKey(scope,keyword,page-1,count)]
        }
        if(page>1 && cursor==null && appSearchAlive!=false) throw SearchSessionExpiredException()
        cursor?.let {
            if(it.searchId.isNotBlank()) values["search_id"]=it.searchId
            if(it.passback.isNotBlank()) values["passback"]=it.passback
            it.offset?.takeIf { offset -> offset>=0 }?.let { offset -> values["offset"]=offset.toString() }
        }
        return values
    }
    /**
     * 发送搜索 GET，并处理空响应 / `100103`。空响应补发一次仍空才判设备未注册；
     * 这样保留设备自愈路径，又不会在高峰期原地退避很多次。
     */
    private fun ContentRepository.searchResponse(signed: Signer.Request): String {
        var raw=retryBlankSearch(text(signed.url,signed.withSession()),signed)
        if(raw.isBlank()) { appSearchAlive=false; noteDeviceRejected(); throw DeviceNotRegisteredException() }
        var attempt=0
        while(attempt<3 && raw.startsWith("""{"code":100103""")) {
            attempt++
            NetLog.retry("search_100103",attempt,3,"platform returned code=100103",120L)
            Thread.sleep(120L)
            raw=retryBlankSearch(text(signed.url,signed.withSession()),signed)
            if(raw.isBlank()) { appSearchAlive=false; noteDeviceRejected(); throw DeviceNotRegisteredException() }
        }
        appSearchAlive=true
        return raw
    }
