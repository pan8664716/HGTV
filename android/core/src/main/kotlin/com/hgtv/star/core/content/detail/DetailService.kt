// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import com.hgtv.star.core.session.CookieRecord
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

    internal fun ContentRepository.detailImpl(id: String,cached: Boolean=false,includeAuthor: Boolean=true): Detail {
        require(id.matches(Regex("[0-9]{1,30}")))
        if(cached) DetailCache.load(id)?.let { return it }
        val values=appValues()
        values["series_id"]=id
        val signed=signer.sign("https://${videoHost()}/novel/player/video_detail/v1/",values,JSONObject().toString().toByteArray())
        val raw=text(signed.url,signed.withSession())
        // 网关在业务处理前挡掉请求时是 200 + 0 字节（设备没注册也会这样）：先判空再解析。
        //
        // **空响应体先按 GET 再问一次**（2026-10-30 实测）：同一条路径 POST 回 `200 + 0 字节` 时，
        // 改 GET（参数全在 query 里、不带 body）能正常拿到 106 KB 的完整详情；签名只覆盖
        // query + body、不覆盖方法，因此同一个签名器直接换方法就行（搜索接口同一条经验）。
        // 这一步把「详情偶发取不到 → 用户看到错误页」变成一次内部重试，界面上完全无感。
        val body=if(raw.isBlank()) {
            val retry=signer.sign("https://${videoHost()}/novel/player/video_detail/v1/",values,method="GET")
            text(retry.url,retry.withSession())
        } else raw
        if(body.isBlank()) throw IOException("剧集详情暂不可用（稍后再试）")
        val detail=ContentDetailParser.parseDetail(body,id) {
            if(includeAuthor) authorOf(id) else ""
        }
        DetailCache.save(detail)
        return detail
    }
    /**
     * 相关作品：App 详情页「相关作品」那一栏的内容，**一次请求拿全**（替代原先那套
     * 早期那套「搜索 + 沿季号逐季搜 + 扫 6 页目录」的召回：实测 2~4s、还要二十多次请求）。
     *
     * ```
     * GET https://{线路}/reading/bookapi/plan/v:version/
     *     ?scene=10&from=detail_page_more_related&book_id={剧集 ID}（见 [relatedRows]）
     * ```
     *
     * 这一栏来自 App 详情页自己的 cell：
     * `data[]` 里 `show_type=547` / `cell_name="相关作品"` 的 `video_data[]`，
     * 每条带 `season_index`（季号）、`tag_info.text`（`第N季` / `同IP`）与完整的卡片字段。
     * 实测一次给全：万妖图录传 26 条（13 季 + 13 部同 IP）、聚宝仙盆 45 条。
     *
     * [title] 是正在看的那一部的剧名（界面手里就有，不用再取一次详情）。
     * 用它按 [SeriesSeasons.normalize] 把同一部剧的各季挑出来——**不能只看 `tag_info`**：
     * 万妖图录传第一季那一页的 `第N季` 组里混着「月落镇魔第二季」这种同 IP 的另一个系列。
     *
     * 同系列各季（按季号升序）排在前面，其余相关作品接在后面、按剧集 ID 去重；
     * 正在看的那一部本身不出现（它自己就是当前页）。接口整轮失败时返回空表，
     * 界面上那一栏就不出现（相关作品只是附加内容，不能影响详情本身）。
     */
    internal fun ContentRepository.relatedImpl(id: String, title: String = ""): RelatedWorks {
        require(id.matches(Regex("[0-9]{1,30}")))
        val current=title.trim()
        if(current.isNotEmpty()) synchronized(relatedCache) { relatedCache[id]?.let { return it } }
        val rows=runCatching { relatedRows(id) }.getOrNull().orEmpty()
        if(rows.isNotEmpty()) {
            val seasons=SeriesSeasons.siblings(current,rows.map { it.series })
            val seasonIds=seasons.map { it.id }.toSet()
            val others=rows.map { it.series }.filter { it.id!=id && it.id !in seasonIds }
            val works=RelatedWorks(seasons+others,seasons)
            if(current.isNotEmpty()) synchronized(relatedCache) {
                if(relatedCache.size>=RELATED_CACHE_LIMIT) relatedCache.keys.firstOrNull()?.let(relatedCache::remove)
                relatedCache[id]=works
            }
            return works
        }
        // 拿不到就空着：相关作品只是详情页的附加内容，不能影响详情本身。
        return RelatedWorks(emptyList(),emptyList())
    }

    /**
     * 作者作品：把同一作者名下的作品凑成一栏（详情页的「作者作品」用它）。
     *
     * ## 为什么是按作者名搜索，而不是一条「作者」接口
     *
     * 平台没有对外的作者主页 / 作者作品接口：`/reading/bookapi/author/...` 全部 404，
     * 详情（`video_detail`）与批量详情（`multi-detail`）都只给一个 `author` 字符串，
     * 既没有 `author_id` 也没有头像。能用的只有全站搜索 `search/tab/v`：
     *
     * * 搜索结果的每一条卡片自带 `copyright`（与 [Series.author] 同源），
     *   因此**不必为了判断归属再打一次 `multi-detail`**；
     * * 用作者名本身当关键词，服务端会把「大概率是这一家」的作品排前面，
     *   后面逐渐跑偏成题材相近的别的作品——所以要按 [Series.author] **精确比对**再收下。
     *
     * 实测（2026-10-29）：`延津县种梦互联网信息服务有限公司` 一次 50 条的页里能筛出 30~43 部
     * （每次调用服务端给的池子顺序都不一样，条数因此有浮动）；
     * 个人昵称类作者（`小橘长`）命中少且排序会漂，只能拿到一部分——
     * 这就是「按名字搜」的固有上限，不是页数不够。
     *
     * ## 一页 50 条，而不是 20 条翻四次
     *
     * `count` 参数是**真的**（见 [AUTHOR_WORKS_PAGE_SIZE] 的实测表：20/50/100 分别回 20/50/100 个 cell，
     * `next_offset` 也跟着走），所以高产作者**一次请求就能拿满一页**，不必再用 4 个 20 条的小页凑。
     * 界面侧的普通搜索仍然按 20 一页（[SEARCH_PAGE_SIZE]），两边各用各的档位。
     *
     * 翻页仍然要带上一页的游标（`search_id` / `passback` / `next_offset`，由 [appSearch] 自己记），
     * 因此这里**按页顺序**调用，不能并发；换线的重试也在 [appSearch] 里。
     * 翻到服务端说 `has_more=false` 为止（上限 [AUTHOR_WORKS_PAGES] 页），
     * 不再「凑够 20 条就收手」——高产作者会因此永远少掉一截。
     *
     * ## 两步取数：按名字搜 + 按家族补季
     *
     * 「按作者名搜」拿不全**同一部剧的其它季**：实测 `枯骨成仙` 的第 5 / 8 / 9 季
     * 根本不在这个查询的结果池里（翻满 6 页也没有），因此再怎么加页数都补不上。
     * 详情页的「相关作品」接口（[related]，一次请求约 1.1s）反而一次给全 9 季。
     *
     * 所以第二步只对**已经出现 2 部以上的家族**调一次 [related]（单季家族补不出东西，
     * 纯浪费一个 RTT），把返回的同名各季并进来；并发限 [AUTHOR_WORKS_FAMILY_FANOUT]，
     * 实测 3 路并发下 7 个家族约 4s，串行要 27s。
     *
     * 某一页失败就停在那一页（已经收下的条目照常返回）：作者作品同样只是附加内容，
     * 不能因为第 2 页抽风就整栏报错。整轮一条都没筛到才返回空表，界面上如实说「没有其他作品」。
     * 补季失败同样不算失败：搜到的那几部照常显示。
     *
     * ## 顺序：**服务端顺序**，客户端不重排
     *
     * 两条路径都按服务端给的顺序原样返回：
     * * 直连（[publisherWorks]）是 `sort=smart_hot_desc` 的「最热」序；
     * * 搜索兜底是服务端对「作者名」这个查询自己的相关度序。
     *
     * 早先这里有一套本地重排（同系列提权 → 题材重合度 → 平台相关度 → 热度），
     * 结果是「列表和人预期的不一样」：明明按热度排、却把冷门的同系列季提到最前。
     * 现在一律照平台顺序摆，[reference] 只用于判断缓存键与补季的家族基名，不再影响顺序。
     */
    internal fun ContentRepository.authorWorksImpl(
        author: String,
        reference: Series? = null,
        pages: Int = AUTHOR_WORKS_PAGES,
        onAvatar: ((String) -> Unit)? = null,
        onPartial: ((List<Series>) -> Unit)? = null,
    ): List<Series> {
        val name=author.trim()
        if(name.isEmpty()) return emptyList()
        // 缓存的是**平台原始顺序**（客户端不重排，见上）：键里必须带上「当前这一部的家族名」，
        // 因为补季那一步只对**当前家族**无条件做
        //（哪怕搜索结果里它只出现 1 部），因此《A》页与《B》页拿到的表本来就不同。
        // 只按作者名做键的话，先进《A》再进《B》会拿到《A》那一份，B 的其它季就永远补不上。
        val focus=reference?.let { SeriesSeasons.normalize(it.title) }.orEmpty()
        val key=if(focus.isEmpty()) name else "$name|$focus"
        val cached=synchronized(authorWorksCache) { authorWorksCache[key] }
        // 发行方直连（[publisherWorks]）**与搜索并行**，不再挡在关键路径前面。
        //
        // 早先是「先串行问一次直连，没命中再搜索」：命中时确实快，但没命中就白等 1.7s
        //（用户搜索 1.3~3.0s + 作品页 0.3s）才开始搜，而实测大量出品方账号的作品 Tab
        // 是 `total=0`，于是「慢」全落在这一下白等上。现在两路同时发：
        // * 直连先回（约 2~3s、一次取全）→ 搜索立刻停在页边界，直接换成直连那份；
        // * 直连没戏 → 搜索照旧（首屏仍是第 1 页到达就铺，见 `onPartial`），**一点没被拖慢**。
        val probe=submitPublisherProbe(name,onAvatar)
        val result=cached ?: collectAuthorWorks(name,pages,reference,probe) { partial ->
            // 搜索阶段一结束就先把这批交给界面（[onPartial]），补季在后台继续。
            // 补季要再花 3 秒上下（见 [AUTHOR_WORKS_FAMILY_MAX]），干等着看「正在查找」不划算。
            onPartial?.invoke(partial)
        }.also { fresh ->
            // **只缓存完整的那一份**：翻页中途失败（网关空响应 / 线路超时）时手上这份是残的，
            // 缓存下来就会变成「这一次进来看得少，之后每次进来都少」——用户读到的正是
            // 「有的时候可以显示出来，有的时候显示不出来」。残的那份照常返回给这一次的界面，
            // 下一次进来重新取，这才有补全的机会。
            if(fresh.complete) synchronized(authorWorksCache) {
                if(authorWorksCache.size>=AUTHOR_WORKS_CACHE_LIMIT) authorWorksCache.keys.firstOrNull()?.let(authorWorksCache::remove)
                authorWorksCache[key]=fresh
            }
        }
        // **平台顺序就是最终顺序**：服务端按 `sort=smart_hot_desc` / 搜索相关度自己排，
        // 客户端不再做「同系列提权 → 题材重合度 → 热度」那套本地重排。
        return result.works
    }
    /**
     * 发行方作品列表：`POST /novel/commentapi/user/profile/{uid}/v1/`
     *（文档：`/Users/star/Downloads/hongguo/docs/红果发行方作品列表-简版.md`）。
     *
     * ## 它解决的是什么
     *
     * [collectAuthorWorks] 那套「按作者名搜 6 页 + 按家族补季」是被迫的替代方案：平台没有对外的
     * 作者主页接口，只能拿作者名当关键词去搜，再按 `copyright` 精确比对。代价是 5~10 秒，
     * 而且**召回天然不全**——搜索池按相关度漂移，同一部剧的某些季根本不在里面。
     *
     * 这一条是平台自己的「作品」Tab：给一个 UGC `user_id`，一次就把这个号名下的短剧列出来，
     * `data_list[].video.video_id` **就是 `series_id`**（不是单集 `vid`），与详情 / 播放同一套 ID。
     * 命中时「作者作品」就从「搜 + 补」变成「一次请求 + 翻页」。
     *
     * ## 三个必须按原样遵守的细节
     *
     * 1. **`aid=8662` 必带**，缺了回 `103008 无社区功能`；
     * 2. **不要签名**：这条走社区域 `reading.snssdk.com`，带上 [Signer] 那套头会被风控挡成
     *    `10001010A 当前环境存在风险`（2026-10-30 实测：签名 3/3 被挡，不签名 3/3 放行），
     *    因此这里用普通 POST（见 [publisherPost]）；
     * 3. **路径段与 body 里的 `target_user_id` 必须一致**，否则只会拿到 `total=0`。
     *
     * ## 为什么不能硬编码 `tab_type=11`
     *
     * 服务端按账号类型下发 `data.tabs[]`（`11` 作品 / `8` 演员 / `21` 漫剧 / `23` 通用 …）。
     * 首页按 `11` 问一次，读它下发的 tab 表，再按 [PUBLISHER_TABS] 的优先级挑一个 `total>0` 的翻页。
     * 账号不是版权方时 `tabs` 是 `[{}]`、`total=0`——这种情况返回 null，交给搜索兜底。
     *
     * ## 返回值
     *
     * 拿不到（没有 uid / 不是版权方 / 风控 / 空响应）一律返回 **null**，调用方照旧走搜索：
     * 这只是一条更快更全的路，不能因为平台收紧就把整栏变成空的。
     * 返回的表保持平台自己的顺序（`smart_hot_desc`），调用方不再重排。
     */
    internal fun ContentRepository.publisherWorks(name: String,onAvatar: ((String) -> Unit)? = null): List<Series>? {
        val key=name.trim()
        val cachedAvatar=synchronized(publisherIdentities) { publisherIdentities[key] }
            ?.avatar.orEmpty()
        cachedAvatar.takeIf { it.isNotBlank() }?.let { onAvatar?.invoke(it) }
        synchronized(publisherMisses) {
            val at=publisherMisses[key]
            if(at!=null && clock()-at<PUBLISHER_MISS_TTL_MS) return null
        }
        val identity=publisherIdentity(key)
        if(identity==null) { notePublisherMiss(key); return null }
        identity.avatar.takeIf { it.isNotBlank() && it!=cachedAvatar }
            ?.let { onAvatar?.invoke(it) }
        val first=publisherPage(identity.uid,null) ?: return null
        val tab=ContentDetailParser.publisherTab(first.tabs,PUBLISHER_TABS) ?: return null
        // 首页问的就是这个 tab 且已有数据：直接用，省一个 RTT。
        val head=if(first.tab==tab) first else publisherPage(identity.uid,tab) ?: return null
        val works=LinkedHashMap<String,Series>()
        head.works.forEach { works[it.id]=it }
        var cursor=head.cursor
        var more=head.hasMore
        var page=1
        while(more && page<PUBLISHER_PAGES) {
            if(cursor.isBlank()) break
            val next=runCatching { publisherPage(identity.uid,tab,cursor) }.getOrNull() ?: break
            val before=works.size
            next.works.forEach { works[it.id]=it }
            // 游标没前进（服务端偶尔回同一个）就停，再问只是重复同一页。
            if(next.cursor==cursor || (works.size==before && next.works.isEmpty())) break
            cursor=next.cursor; more=next.hasMore; page++
        }
        val result=works.values.filter { it.title.isNotBlank() }
        if(result.isEmpty()) { notePublisherMiss(key); return null }
        return result
    }
    /** 记一次「没有作品页」，见 [publisherMisses]。 */
    internal fun ContentRepository.notePublisherMiss(name: String) {
        if(name.isEmpty()) return
        synchronized(publisherMisses) {
            if(publisherMisses.size>=PUBLISHER_UIDS_LIMIT) publisherMisses.keys.firstOrNull()?.let(publisherMisses::remove)
            publisherMisses[name]=clock()
        }
    }
    /**
     * 按作者名找 UGC `user_id`：全站搜索的**用户** tab（`tab_type=27`）。
     *
     * 为什么不用详情接口的 `main_creator_users`：本工程当前客户端版本下发的详情里**没有这个字段**
     * （2026-10-30 实测：`GET` / `POST /novel/player/video_detail/v1/` 在 72232 / 73932 两档、
     * 六条线路上都不含 `creator` 字样），而用户搜索一次就能拿到 `user_name` + `user_id`
     * 以及 `base_info.user_avatar`，顺手还能校验「这个号真的叫这个名字」——
     * 同名账号只认完全相等的那一个。
     *
     * 只缓存**找到了**的结果：一次失败不能被记成「这个作者没有主页」。
     */
    internal fun ContentRepository.publisherIdentity(name: String): ContentRepository.PublisherIdentity? {
        val key=name.trim()
        if(key.isEmpty()) return null
        synchronized(publisherIdentities) { publisherIdentities[key] }?.let { return it }
        val values=searchValues()
        values.putAll(linkedMapOf("query" to key,"count" to "20","offset" to "0","tab_type" to "27",
            "bookshelf_search_plan" to "4","use_correct" to "true","user_is_login" to "0"))
        val signed=runCatching { searchRequest(values) }.getOrNull() ?: return null
        val raw=runCatching { retryBlankSearch(text(signed.url,signed.withSession()),signed) }.getOrNull().orEmpty()
        if(raw.isBlank()) return null
        val rows=runCatching {
            ContentSearchParser.parseTab(raw,27,SEARCH_FEED_TAB).cells
        }.getOrDefault(emptyList())
        val identity=rows.firstNotNullOfOrNull { row ->
            val user=row.optJSONArray("celebrity_user_list")?.objects()?.firstOrNull()
            val info=user?.optJSONObject("base_info")
            val userName=(info?.str("user_name") ?: user?.str("user_name").orEmpty()).trim()
            val id=(info?.str("user_id") ?: user?.str("user_id").orEmpty()).trim()
            if(userName==key && id.matches(Regex("[0-9]{1,30}"))) {
                val avatar=cleanUrl(
                    info?.str("user_avatar").orEmpty()
                        .ifEmpty { info?.str("expand_user_avatar").orEmpty() }
                ).let { if(it.startsWith("http://")) "https://"+it.removePrefix("http://") else it }
                ContentRepository.PublisherIdentity(id,avatar)
            } else null
        } ?: rows.firstNotNullOfOrNull { row ->
            // 兜底：老版本 cell 只给 `cell_url`（`sslocal://guest_profile?uid=…`）。
            val id=row.str("cell_url").substringAfter("uid=","").substringBefore('&').trim()
            val userName=row.optJSONObject("search_high_light")?.optJSONObject("user_name")?.str("text").orEmpty()
            if(userName==key && id.matches(Regex("[0-9]{1,30}"))) ContentRepository.PublisherIdentity(id,"") else null
        } ?: return null
        synchronized(publisherIdentities) {
            if(publisherIdentities.size>=PUBLISHER_UIDS_LIMIT) {
                publisherIdentities.keys.firstOrNull()?.let(publisherIdentities::remove)
            }
            publisherIdentities[key]=identity
        }
        return identity
    }
    /**
     * 把发行方直连丢到后台跑，**不挡在关键路径前面**（见 [authorWorks]）。
     *
     * 返回 null 表示线程池拒绝（极少）：那就当作没有这条捷径，搜索照常。
     * 任务内部自己会失败 / 超时（[publisherPage] 三次重试后返回 null），这里不额外等待。
     */
    internal fun ContentRepository.submitPublisherProbe(
        name: String,
        onAvatar: ((String) -> Unit)? = null,
    ): java.util.concurrent.Future<List<Series>?>? = runCatching {
        WorkLanes.fanout.executor.submit(java.util.concurrent.Callable { publisherWorks(name,onAvatar) })
    }.getOrNull()
    /** 一页「发行方作品」：`tabs` 是这次响应下发的 tab 表，`works` 是本页的剧集。 */
    internal class PublisherPage(
        val tabs: List<JSONObject>,
        val tab: Int,
        val works: List<Series>,
        val cursor: String,
        val hasMore: Boolean
    )
    /**
     * 发一页请求并解析。
     *
     * [tab] 为 null 时按 `11` 问（只为读回 `data.tabs[]`）；[cursor] 为空表示第一页。
     * 空响应 / 风控码 / 结构变化一律返回 null——调用方据此退回搜索，不在这里抛。
     */
    internal fun ContentRepository.publisherPage(uid: String,tab: Int?,cursor: String=""): PublisherPage? {
        val body=JSONObject()
            .put("target_user_id",uid)
            .put("tab_type",tab ?: 11)
            .put("count",PUBLISHER_PAGE_SIZE)
            .put("cursor",cursor)
            .put("sort","smart_hot_desc")
            .put("comment_source",1506)
            .put("server_channel",3007)
            .toString().toByteArray(Charsets.UTF_8)
        val profile=primaryProfile()
        val values=linkedMapOf("aid" to "8662","app_name" to "novelread","version_code" to "72232")
        profile["device_id"]?.takeIf { it.isNotBlank() }?.let { values["device_id"]=it }
        profile["iid"]?.takeIf { it.isNotBlank() }?.let { values["iid"]=it }
        val host=TncConfigStore.localHost("reading.snssdk.com","reading.snssdk.com")
        val url="https://$host/novel/commentapi/user/profile/$uid/v1/?"+
            values.entries.joinToString("&") { (k,v) -> "$k=$v" }
        var raw=""
        var attempt=0
        while(attempt<3) {
            raw=runCatching { publisherPost(url,body) }.getOrNull().orEmpty()
            if(raw.isNotBlank()) break
            attempt++
            if(attempt<3) {
                NetLog.retry("publisher",attempt+1,3,"HTTP 200 empty body",SEARCH_BLANK_RETRY_DELAY_MS)
                runCatching { Thread.sleep(SEARCH_BLANK_RETRY_DELAY_MS) }
            }
        }
        if(raw.isBlank()) return null
        val root=runCatching { JSONObject(raw) }.getOrNull() ?: return null
        // `code` 有时是数字、有时是字符串（风控那条是 `"10001010A"`）：两种都按「非 0 即失败」判。
        if(root.opt("code")?.toString().orEmpty()!="0") return null
        val data=root.optJSONObject("data") ?: return null
        val tabs=data.optJSONArray("tabs")?.objects().orEmpty()
        val rows=data.optJSONArray("data_list")?.objects().orEmpty()
        val info=data.optJSONObject("common_list_info")
        val works=rows.mapNotNull { row ->
            row.optJSONObject("video")?.let(ContentDetailParser::publisherSeries)
        }
        return PublisherPage(tabs,tab ?: 11,works,info?.str("cursor").orEmpty(),info?.optBoolean("has_more",false) ?: false)
    }
    /** 社区域（`reading.snssdk.com`）的普通 POST：**不加签名头**，理由见 [publisherWorks]。 */
    internal fun ContentRepository.publisherPost(url: String,body: ByteArray): String {
        fun send(
            target: String,
            cookies: Map<String,String>,
            profile: Map<String,String>,
            anonymous: Boolean,
        ): String {
            val request=Request.Builder().url(target)
                .header("User-Agent",VendorConstants.userAgent(profile,video = false))
                .header("Content-Type","application/json; charset=utf-8")
                .header("Accept","application/json; charset=utf-8")
                .header("X-SS-DP","8662")
            cookies["store-region"]?.takeIf { it.isNotBlank() }?.let { request.header("X-Tt-Store-Region",it) }
            cookies["store-region-src"]?.takeIf { it.isNotBlank() }?.let { request.header("X-Tt-Store-Region-Src",it) }
            if(anonymous) request.header("X-HGTV-Anonymous","1")
            if(cookies.isNotEmpty()) request.header("Cookie",cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            request.post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            return RequestScope.execute(http.newCall(request.build())) { response ->
                if(!response.isSuccessful) throw IOException("出品方作品接口失败（HTTP ${response.code}）")
                String(response.body?.bytes() ?: ByteArray(0),Charsets.UTF_8)
            }
        }
        val mainCookies=CookieRecord.headerToMap(primaryCookieHeader())
        val currentHost=runCatching { okhttp3.HttpUrl.parse(url)?.host }.getOrNull().orEmpty()
        var raw=""
        for(host in listOf(currentHost)+alternateApiHosts(currentHost)) {
            raw=send(urlWithHost(url,host),mainCookies,primaryProfile(),anonymous=false)
            if(raw.isNotBlank()) {
                TncConfigStore.markHostSuccess(host)
                return raw
            }
        }
        val backup=runCatching { ContentRepository.playbackBackupDevices?.invoke()?.firstOrNull() }.getOrNull() ?: return raw
        val retryUrl=backupQueryUrl(url,backup) ?: return raw
        NetLog.retry("publisher_backup",1,1,"primary identity returned HTTP 200 empty body")
        NetLog.emit("device backup retry publisher id=${backup.id.takeLast(6)}")
        return send(retryUrl,backup.cookies,backup.profile,anonymous=true)
    }
    /**
     * 一次「作者作品」取数的结果。
     *
     * [complete] 只在**翻页一轮全都没出错**时为真：中途某一页三次都没回答，这份表就是残的。
     * 残表不进缓存（见 [authorWorks]），否则一次网络抖动会被永久记住成「这位作者只有这些作品」。
     */
    internal class AuthorWorksResult(val works: List<Series>,val complete: Boolean)
    /**
     * 取数 + 精筛：把「按作者名搜索」得到的候选里属于该作者的作品收下来，**保持平台返回顺序**。
     *
     * 顺序即「平台对『作者名』这个查询自己的排序」，客户端不做任何重排——
     * 用户要的就是「服务端说什么顺序，界面就什么顺序」。
     *
     * [probe] 是并行的发行方直连（见 [submitPublisherProbe]）：每翻一页前看一眼，
     * 先回来且非空就用它换掉整轮搜索（一次取全，含搜索池里没有的季），搜索立即停在页边界。
     */
    /**
     * 作者作品的一页搜索：与界面搜索一致，走官方一次综合请求。
     *
     * 分开留一个入口，是因为这一栏用 50 条一页并要连取多页；游标与界面搜索的 20 条档位
     * 按 `count` 隔离，不会互相覆盖。
     */
    internal fun ContentRepository.authorSearchPage(name: String,page: Int): CatalogPage {
        return appSearchPage(name,page,AUTHOR_WORKS_PAGE_SIZE)
    }
    internal fun ContentRepository.collectAuthorWorks(
        name: String,
        pages: Int,
        reference: Series? = null,
        probe: java.util.concurrent.Future<List<Series>?>? = null,
        onPartial: ((List<Series>) -> Unit)? = null
    ): AuthorWorksResult {
        val found=linkedMapOf<String,Series>()
        val total=pages.coerceIn(1,AUTHOR_WORKS_PAGES_MAX)
        var page=1
        // 至少有一页真的得到回答（哪怕一条都没筛到）才落缓存：设备还没注册时搜索会直接抛，
        // 那种「失败」不能被记成「这个作者没有作品」。
        var answered=false
        var painted=false
        // 这一轮是不是**完整**走完了（见 [AuthorWorksResult.complete]）：中途有一页重试到底也没回答就是残的。
        var complete=true
        // 连续「一页下来一条新的都没筛到」的次数：连空两页才停（见下面的早停说明）。
        var emptyPages=0
        while(page<=total) {
            // 直连先回来了：它一次就是全部作品（含搜索池里没有的季），比搜索更全，直接换掉。
            // 只在**页边界**看一次，不额外花时间（`isDone` 不阻塞）。
            if(probe!=null && probe.isDone) {
                val direct=runCatching { probe.get() }.getOrNull()
                if(!direct.isNullOrEmpty()) return AuthorWorksResult(direct,true)
            }
            // 一页最多试三次：这个平台的网关会**成片地**回空响应体（实测同一条请求连发里约一半是空的），
            // 翻到第 3 页时恰好被挡一次就停，用户看到的就是「少了一截」——而重新进来一次又能全出来，
            // 这正是「有的时候可以显示出来，有的时候显示不出来」。间隔 300ms 起，与 [retryBlankSearch] 同档。
            var result: CatalogPage?=null
            var attempt=0
            while(attempt<AUTHOR_WORKS_PAGE_ATTEMPTS && result==null) {
                result=runCatching {
                    authorSearchPage(name,page)
                }.getOrNull()
                if(result==null) {
                    attempt++
                    if(attempt<AUTHOR_WORKS_PAGE_ATTEMPTS) {
                        NetLog.retry(
                            "author_works",
                            attempt+1,
                            AUTHOR_WORKS_PAGE_ATTEMPTS,
                            "page=$page returned no usable data",
                            AUTHOR_WORKS_PAGE_RETRY_MS,
                        )
                        runCatching { Thread.sleep(AUTHOR_WORKS_PAGE_RETRY_MS) }
                    }
                }
            }
            val pageResult=result
            if(pageResult==null) { complete=false; break }
            answered=true
            val before=found.size
            pageResult.items.forEach { item -> if(sameAuthor(item.author,name)) found[item.id]=item }
            // 第 1 页就先铺一批：单页实测 1.3~3.0s，而翻满 6 页要 8~9 秒。
            // 只在这一页、以及搜索全部结束时各交付一次——每页都刷会把详情页下方那块重建 6 次。
            if(!painted && found.isNotEmpty()) {
                painted=true
                val early=found.values.toList()
                runCatching { onPartial?.invoke(early) }
            }
            if(!pageResult.hasMore || pageResult.items.isEmpty()) break
            // 连续两页一条新的都没筛到才停：相关度确实已经漂到别的作者身上了，后面的页只会更偏
            //（个人昵称类作者常见：第 1 页 5 条，第 2 页 0 条）。
            // **不能一页空就停**：实测 `大同禾乐文化传媒有限公司` 的第 2 页 3 条、第 3 页 0 条、
            // 第 4 页 0 条；而 `星芒时代` 这类第 3 页偶尔还有 1~2 条。一页空就停会把那一两条也丢掉。
            // 这里仍然刻意**不按「凑够多少条」早停**：那会让高产作者永远少掉一截（见 [AUTHOR_WORKS_PAGES]）。
            if(found.size==before) {
                emptyPages++
                if(emptyPages>=AUTHOR_WORKS_EMPTY_PAGES) break
            } else emptyPages=0
            page++
        }
        // 一页都没被回答（网关空响应 / 设备未注册 / 全部线路超时）：这是「没取到」，不是「没有作品」。
        // 抛出去让界面显示「加载失败 + 重试」，而不是把一句「这一位只有这一部作品」当成结论。
        if(!answered) throw IOException("作者作品接口暂无响应")
        val searched=found.values.toList()
        // 搜索阶段收尾再交付一次（第 1 页那次之后可能又补进十几部），然后才开始补季。
        if(searched.isNotEmpty()) runCatching { onPartial?.invoke(searched) }
        // 补季失败**不算**这一轮不完整：搜索那一轮已经完整，补季本来就是「能补多少补多少」。
        return AuthorWorksResult(fillFamilySeasons(searched,name,reference),complete)
    }

    /**
     * 补季：按剧名家族把「同一个查询拿不到的那几季」从 [related] 里捞回来。
     *
     * 为什么必须补：实测 `枯骨成仙` 的第 5 / 8 / 9 季**不在**「按作者名搜索」的结果池里，
     * 翻满 6 页也一条都没有；而详情页的「相关作品」接口一次就给全 9 季。
     * 这是「作者作品显示不全」在单部剧内部最刺眼的一种（同一部剧只显示 3 季）。
     *
     * 触发条件（2026-10-30 修正：**所有家族都补**，只按「当前家族 → 多季家族 → 单季家族」排序，
     * 取前 [AUTHOR_WORKS_FAMILY_MAX] 个）：
     * * **当前这一部的家族**永远排第一个：用户点进「作者作品」时最关心的就是「这一部还有哪些季」，
     *   而搜索结果里它可能只出现 1 部（甚至 0 部）。
     * * 其余家族按条数从多到少。早先只补「已经出现 2 部以上」的家族，理由是「单季家族补不出东西」——
     *   **那是错的**：搜索池按相关度排，恰好漏掉某几季的家族在结果里往往只剩 1 部
     *   （实测 `持械入宋` 第 1/2/3/6/8/9/10 季在池子里，第 4/5/7 季一条都没有），
     *   于是「只补 ≥2 部的家族」恰好把最需要补的那几个跳过了。
     *   代价是一个 `related` 请求（约 1.1s）；3 路并发、最多 12 个家族，收尾约 4~5s，
     *   而搜索结果那一段早就铺上屏了（见 `onPartial`），用户看不到等待。
     *
     * 补进来的条目**不带作者**（`related` 的卡片没有 `copyright`，实测全为空串），
     * 因此这里只按「家族名相同」判定归属，并**跳过作者明确是别家**的条目
     *（`枯骨成仙第一季` 是另一家翻拍，`related` 里它也是空的，只能靠 [sameAuthor] 的
     *「空作者放行」规则收下——界面上它的作者行留空，不误导）。
     *
     * 失败一律吞掉：补季只是附加内容，不能因为某一条 `related` 抽风就让整栏报错。
     */
    internal fun ContentRepository.fillFamilySeasons(works: List<Series>,name: String,reference: Series?): List<Series> {
        if(works.isEmpty()) return works
        val merged=LinkedHashMap<String,Series>()
        works.forEach { merged[it.id]=it }
        val focus=reference?.let { SeriesSeasons.normalize(it.title) }.orEmpty()
        // 按家族归组：**每个家族**都问一次（见上面的触发条件说明）。
        // `groupBy` 保留首次出现顺序，后面那次排序是稳定的，因此同档家族仍按「平台相关度」先后。
        val groups=works.groupBy { SeriesSeasons.normalize(it.title).ifEmpty { it.id } }
        val targets=groups.filterKeys { it.isNotBlank() }.toMutableMap()
        // **当前这一部自己就是一个种子**：搜索结果池里连它都没有时（实测常见，
        // `枯骨成仙` 那一条有时整页都不出现），按家族分组就找不到它，它的其它季也就补不上。
        // 手里已经有这一部，直接拿它去问 `related` 即可，不必依赖搜索结果。
        if(reference!=null && focus.isNotBlank()) targets.getOrPut(focus) { listOf(reference) }
        if(targets.isEmpty()) return works
        // 排序 = 当前家族 → 搜索里出现得多的家族 → 少的；同档按平台相关度（`targets` 的插入顺序）。
        val ranged=targets.entries
            .sortedWith(
                compareByDescending<Map.Entry<String,List<Series>>> { it.key==focus }
                    .thenByDescending { it.value.size }
            )
            .take(AUTHOR_WORKS_FAMILY_MAX)
        val pool=WorkLanes.fanout.executor
        // 第一步：**按家族基名再搜一次**。
        //
        // 这一步才是「作者作品显示不全」最主要的补法：按**出品方名**搜时，平台只会把
        // 「和这个公司名最相关」的一批塞进池子，同一部剧的某些季根本不会出现
        //（实测 `持械入宋` 按 `瑾叶剧场` 搜只出 7 季，按基名 `持械入宋` 搜一次 50 条就出满 10 季）。
        // 基名是剧名本身，搜索池对它的召回最全，而且每条都带 `copyright`，可以精确判归属。
        //
        // 代价：一个家族一次搜索（1.3~3s），最多 [AUTHOR_WORKS_FAMILY_MAX] 个家族，
        // 走同一个 `fanout` 池并发，收尾约 3~5s；搜索结果那一段早已上屏（见 `onPartial`）。
        val baseTasks=ranged.map { (family,_) ->
            family to pool.submit(java.util.concurrent.Callable {
                val page=runCatching {
                    search(family,1,ContentType.searchable,AUTHOR_WORKS_PAGE_SIZE)
                }.getOrNull() ?: return@Callable emptyList<Series>()
                page.items.filter { item ->
                    sameAuthor(item.author,name) && SeriesSeasons.normalize(item.title)==family
                }
            })
        }
        baseTasks.forEach { (_,task) ->
            val extra=runCatching { task.get(AUTHOR_WORKS_FAMILY_TIMEOUT_MS,TimeUnit.MILLISECONDS) }.getOrNull() ?: return@forEach
            extra.forEach { item -> merged.getOrPut(item.id) { item } }
        }
        // 第二步：`related` 补那些**搜索池里根本没有**的季（未上线的季在搜索里是空，只有 related 有）。
        //
        // 只问两类，不再把所有家族都问一遍（那是 `related` 最贵的一段：一个家族 1.1s）：
        // * **当前这一部的家族**：用户就是冲着它点进来的，无条件问；
        // * 基名搜索**一条新的都没补到**的家族：说明这一家在这一侧确实被挡，只能靠 related。
        // 基名搜索之后，每个家族手上现在有几部（用来判断「补到了没有」）。
        val have=merged.values.groupingBy { SeriesSeasons.normalize(it.title).ifEmpty { it.id } }.eachCount()
        val missing=ranged.filter { (family,rows) -> family==focus || (have[family] ?: 0)<=rows.size }
        val picks=missing.take(AUTHOR_WORKS_FAMILY_MAX).map { (family,rows) ->
            family to (rows.firstOrNull { it.id==reference?.id } ?: rows.first())
        }
        val relatedTasks=picks.map { (family,seed) ->
            pool.submit(java.util.concurrent.Callable {
                val related=runCatching { relatedImpl(seed.id,seed.title) }.getOrNull() ?: return@Callable family to emptyList<Series>()
                // `related` 的 `items` 已经把同系列各季排在前面；这里仍按家族名再筛一遍，
                // 免得把「同 IP 的另一个系列」（万妖图录传那类）混进来。
                val extra=related.items.filter { SeriesSeasons.normalize(it.title)==family && authorUnknownOrSame(it.author,name) }
                family to extra
            })
        }
        // 补进来的条目没有平台的排序位（`related` 用的是它自己那一套），
        // 因此先按收集顺序并进 [merged]，最后由 [mergeByFamily] 贴回各自家族。
        relatedTasks.forEach { task ->
            val extra=runCatching { task.get(AUTHOR_WORKS_FAMILY_TIMEOUT_MS,TimeUnit.MILLISECONDS) }.getOrNull()?.second ?: return@forEach
            extra.forEach { item -> merged.getOrPut(item.id) { item } }
        }
        return mergeByFamily(works,dropUnknownDuplicates(fillAuthors(merged.values.toList()),name,reference))
    }
    /**
     * 补季条目**贴回自己家族**，而不是一律堆到末尾。
     *
     * 平台顺序是唯一顺序（见 [authorWorks]），但补进来的那几季原本不在搜索结果池里，
     * 直接追加会读成「第 5 季跑到最后」。这里只做「同家族条目相邻」这一件事：
     * 按平台顺序走一遍，遇到某个家族的第一条时，把它补进来的其它季紧跟其后（按季号升序），
     * 其余位置一个都不动；家族名在原始结果里一条都没有的，才追加在末尾。
     *
     * 作者已在 [fillAuthors] 里补过，这里用 [filled] 的同一 id 版本换回带署名的条目。
     */
    internal fun ContentRepository.mergeByFamily(base: List<Series>,filled: List<Series>): List<Series> {
        if(filled.size<=base.size) return filled
        val baseIds=base.mapTo(HashSet()) { it.id }
        val extras=filled.filter { it.id !in baseIds }
        if(extras.isEmpty()) return filled
        val byFamily=extras.groupBy { SeriesSeasons.normalize(it.title).ifEmpty { it.id } }
        val byId=filled.associateBy { it.id }
        val out=mutableListOf<Series>()
        val emitted=mutableSetOf<String>()
        val usedFamilies=mutableSetOf<String>()
        base.forEach { item ->
            val row=byId[item.id] ?: item
            if(emitted.add(row.id)) out+=row
            val family=SeriesSeasons.normalize(item.title).ifEmpty { item.id }
            if(!usedFamilies.add(family)) return@forEach
            byFamily[family]
                ?.sortedWith(compareBy({ SeriesSeasons.season(it) },{ it.title }))
                ?.forEach { extra -> if(emitted.add(extra.id)) out+=byId[extra.id] ?: extra }
        }
        extras.forEach { extra -> if(emitted.add(extra.id)) out+=byId[extra.id] ?: extra }
        return out
    }

    /**
     * 给**作者为空**的条目补上作者（一次 `multi-detail`，最多 50 个 id）。
     *
     * 为什么需要：补季用的 [related] 卡片不带 `copyright`（实测全为空串），
     * 未上线的季在搜索里也是空。作者栏空着时用户读到的是「这些作品不知道是谁的」，
     * 卡片第二行还会退回状态行；补一次就能让「共 N 部作品」与每张卡的署名都成立。
     *
     * 只补空的那几条，已有作者的**不覆盖**（搜索接口给的 `copyright` 与详情同源，更可信）。
     * 失败原样返回：补作者只是让界面更好读，不能让整栏失败。
     */
    internal fun ContentRepository.fillAuthors(works: List<Series>): List<Series> {
        val blanks=works.filter { it.author.isBlank() }.map { it.id }
        if(blanks.isEmpty()) return works
        val names=runCatching { authorsOf(blanks) }.getOrNull().orEmpty()
        if(names.isEmpty()) return works
        return works.map { item ->
            val name=names[item.id].orEmpty()
            if(item.author.isBlank() && name.isNotBlank()) item.copy(author=name) else item
        }
    }

    /**
     * 丢掉「同一家族同一季、但作者未知」的重复条目。
     *
     * 由来：`枯骨成仙`（星芒，151 集，剧名没有季号＝第 1 季）与 `枯骨成仙第一季`
     *（西安凌九，143 集）在平台眼里是同一系列的两条——`related` 把它们并排放，
     * 但后者是别家翻拍，作者字段在 `related` 里又是空的，没法按作者区分。
     * 两条都摆上去会变成「第 1 季」出现两次，比少一条更让人困惑。
     *
     * 判定只做一件事：**同家族同季**时，作者为空的那条让位给作者已知的那条。
     * 两边作者都已知（真的是两家各拍一部同名剧）就都保留——那是事实，不该被合并。
     */
    internal fun ContentRepository.dropUnknownDuplicates(works: List<Series>,name: String,reference: Series?): List<Series> {
        if(works.size<2) return works
        val known=buildSet {
            works.filter { sameAuthor(it.author,name) }.forEach { add(SeriesSeasons.normalize(it.title) to SeriesSeasons.season(it.title)) }
            // 当前这一部也要算进「已知季」：界面会把它单独摆在第一张（[MainActivityDetail] 的
            // `items=listOf(current)+works`），因此它不在这张表里也照样占着「第 N 季」那个位置。
            // 少了这一行，`枯骨成仙` 页会把别家的 `枯骨成仙第一季` 也摆出来，读成「第 1 季出现两次」。
            if(reference!=null && sameAuthor(reference.author,name)) {
                add(SeriesSeasons.normalize(reference.title) to SeriesSeasons.season(reference.title))
            }
        }
        return works.filter { item ->
            if(sameAuthor(item.author,name)) return@filter true
            // 作者为空 / 明确是别家的：只有当「同一家族同一季已经有一条作者正确的」才丢。
            val family=SeriesSeasons.normalize(item.title)
            val season=SeriesSeasons.season(item.title)
            if(family.isBlank()) return@filter true
            (family to season) !in known
        }
    }
    /** 卡片的 `rec_text`（`1204万热度` / `1.2亿热度`）→ 数字；认不出单位一律 0（见 [detail]）。 */
