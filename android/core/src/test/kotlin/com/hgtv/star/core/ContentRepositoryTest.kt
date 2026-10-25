package com.hgtv.star.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ContentRepositoryTest {
    /**
     * 新路由 `/novel/player/video_model/v1/` 的响应：`video_model` 直接挂在 `data` 下
     * （带场景段与旧路径挂在 `data[<vid>]` 下，见 [legacyVideoModel]）。
     */
    private fun videoModel(fallback: String) =
        """{"BaseResp":{"StatusCode":0},"code":0,"data":{"video_model":"{\"fallback_api\":\"$fallback\"}"}}"""
    /** 带场景段 / 旧路径的响应形态：`data[<vid>].video_model`。 */
    private fun legacyVideoModel(fallback: String) =
        """{"BaseResp":{"StatusCode":0},"code":0,"data":{"123":{"video_model":"{\"fallback_api\":\"$fallback\"}"}}}"""
    /** `fallback_api` 地址上的清晰度列表（`video_list` 为空时 stream 会报「没有电视可播放的清晰度」）。 */
    private fun fallbackList(vararg rows: Pair<String,String>) = JSONObject()
        .put("video_info",JSONObject().put("data",JSONObject().put("video_list",
            JSONObject().also { list -> rows.forEachIndexed { i,(codec,url) ->
                list.put("video_$i",JSONObject().put("vheight",1080).put("codec_type",codec).put("main_url",url)) } })))
        .toString()

    /**
     * 平台 2026-10-14 又把播放接口换了一条：原先带场景段的
     * `/novel/player/multi_video_model/:dr_scene/v1/` 现在一律返回 **HTTP 200 + 空响应体**，
     * 旧路径返回 `Code 110001 未知异常`；能用的只有 `/novel/player/video_model/v1/`，
     * 且要 `video_id` 单值。这里钉住四件事：新路由排在最前、空响应体必须当失败、
     * 新路由不通时依次退到带场景段与旧路径、全失败时如实报错。
     */
    @Test fun playbackPrefersTheVideoIdRouteAndIgnoresBusinessErrors() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            if(request.url().host()!="example.com") paths+=request.url().encodedPath()
            when {
                request.url().host()=="example.com" -> fallbackList("h264" to "https://example.com/stream")
                request.url().encodedPath()=="/novel/player/video_model/v1/" -> videoModel("https://example.com/stream")
                else -> """{"Code":110001,"Message":"未知异常"}"""
            }
        }
        val stream=repo.stream("123")
        assertEquals("https://example.com/stream",stream.url)
        assertEquals(listOf("/novel/player/video_model/v1/"),paths)
    }

    /**
     * 新路由的请求体必须是 `video_id` 单值：给它 `mixed_video_id_map` 会返回
     * `100001 video_id invalid`，所以两条路的 body 形态不能混用。
     */
    @Test fun videoIdRouteSendsTheSingleVideoIdBodyInsteadOfTheLegacyMap() {
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            if(request.url().host()!="example.com") requests+=request
            when {
                request.url().host()=="example.com" -> fallbackList("h264" to "https://example.com/stream")
                request.url().encodedPath()=="/novel/player/video_model/v1/" -> videoModel("https://example.com/stream")
                else -> ""
            }
        }
        assertEquals("https://example.com/stream",repo.stream("123").url)
        val sent=requests.single()
        assertEquals("/novel/player/video_model/v1/",sent.url().encodedPath())
        val body=sent.body()?.let { payload -> okio.Buffer().also { buffer -> payload.writeTo(buffer) }.readUtf8() } ?: ""
        assertTrue(body,body.contains("\"video_id\":\"123\""))
        assertFalse(body,body.contains("mixed_video_id_map"))
    }

    /**
     * 网关在业务处理前挡掉请求时是 `HTTP 200 + 0 字节`：这时业务码取不到、默认成 0，
     * 不判空就会被当成成功往下走，用户看到的是「播放接口暂不可用」但错误信息是 `（0）`。
     */
    @Test fun blankResponseBodyCountsAsFailureAndMovesToTheNextRoute() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            if(request.url().host()!="example.com") paths+=request.url().encodedPath()
            when {
                request.url().host()=="example.com" -> fallbackList("h264" to "https://example.com/stream")
                request.url().encodedPath()=="/novel/player/video_model/v1/" -> ""
                request.url().encodedPath()=="/novel/player/multi_video_model/preload/v1/" -> legacyVideoModel("https://example.com/stream")
                else -> """{"Code":110001,"Message":"未知异常"}"""
            }
        }
        assertEquals("https://example.com/stream",repo.stream("123").url)
        // 空响应会**原地重发几次**再换下一路（网关偶发挡掉时重发即好，见 `PLAYBACK_RETRIES`），
        // 因此这里只钉住顺序：同一路可以重复，但必须**依次**走过、最终落到下一条。
        assertEquals(
            listOf("/novel/player/video_model/v1/","/novel/player/multi_video_model/preload/v1/"),
            paths.distinct()
        )
        assertTrue(paths.first()=="/novel/player/video_model/v1/")
        assertTrue(paths.last()=="/novel/player/multi_video_model/preload/v1/")
    }

    /** 新路由通了但没给 `video_model`（例如只有 `data.disable_vsr` 这类字段）时也要继续换路。 */
    @Test fun responseWithoutVideoModelMovesToTheNextRoute() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            if(request.url().host()!="example.com") paths+=request.url().encodedPath()
            when {
                request.url().host()=="example.com" -> fallbackList("h264" to "https://example.com/stream")
                request.url().encodedPath()=="/novel/player/video_model/v1/" -> """{"code":0,"data":{"disable_vsr":true}}"""
                request.url().encodedPath()=="/novel/player/multi_video_model/detail/v1/" -> legacyVideoModel("https://example.com/stream")
                else -> ""
            }
        }
        assertEquals("https://example.com/stream",repo.stream("123").url)
        // 同前：每一条都可能重发几次，只钉住「依次走过、最终落到给得出模型的那条」。
        assertEquals(listOf(
            "/novel/player/video_model/v1/",
            "/novel/player/multi_video_model/preload/v1/",
            "/novel/player/multi_video_model/default/v1/",
            "/novel/player/multi_video_model/detail/v1/"
        ),paths.distinct())
    }

    /** 新路由不可用时退回旧路径：老账号 / 灰度环境上仍然要能播。 */
    @Test fun playbackFallsBackToTheLegacyRouteWhenTheNewRouteFails() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            if(request.url().host()!="example.com") paths+=request.url().encodedPath()
            when {
                request.url().host()=="example.com" -> fallbackList("h264" to "https://example.com/legacy")
                request.url().encodedPath()=="/novel/player/multi_video_model/v1/" -> legacyVideoModel("https://example.com/legacy")
                else -> """{"Code":110001,"Message":"未知异常"}"""
            }
        }
        assertEquals("https://example.com/legacy",repo.stream("123").url)
        assertEquals("/novel/player/multi_video_model/v1/",paths.last())
    }

    /** 五条路都只有业务错误码或空响应时必须如实报错，不能把空 data 当成成功往下走。 */
    @Test fun playbackReportsFailureWhenEveryRouteAnswersWithABusinessError() {
        val repo=repository { """{"Code":110001,"Message":"未知异常","BaseResp":{"StatusCode":110001}}""" }
        assertThrows(IOException::class.java) { repo.stream("123") }
    }

    /** 五条路全是空响应体（当前平台对带场景段那几条的实际行为）时也要报错，而不是往下走。 */
    @Test fun playbackReportsFailureWhenEveryRouteAnswersWithAnEmptyBody() {
        val repo=repository { "" }
        assertThrows(IOException::class.java) { repo.stream("123") }
    }

    private fun streamRepository(vararg variants: Triple<Int,String,String>) = repository { request ->
        if(request.url().host()=="example.com") {
            val rows=JSONObject()
            variants.forEachIndexed { index,(height,codec,url) -> rows.put("video_$index",JSONObject().put("vheight",height).put("codec_type",codec).put("main_url",url)) }
            JSONObject().put("video_info",JSONObject().put("data",JSONObject().put("video_list",rows))).toString()
        } else videoModel("https://example.com/stream")
    }

    @Test fun incompatibleByteVc2DoesNotBecomeAudioOnlyPlayback() {
        val repo=streamRepository(Triple(720,"bytevc2","https://example.com/unsupported"),Triple(1080,"bytevc1","https://example.com/hevc"))
        val result=repo.stream("123",720)
        assertEquals("https://example.com/hevc",result.url)
        assertEquals("1080P（兼容资源）",result.quality)
    }

    @Test fun compatibleLowerQualityWinsBeforeHigherResolutionFallback() {
        val repo=streamRepository(Triple(720,"bytevc2","https://example.com/unsupported"),Triple(480,"h264","https://example.com/avc"),Triple(1080,"bytevc1","https://example.com/hevc"))
        assertEquals("480P",repo.stream("123",720).quality)
    }

    @Test fun entirelyIncompatibleSourceFailsBeforeStartingAudio() {
        val repo=streamRepository(Triple(720,"ByteVC2","https://example.com/unsupported"),Triple(480,"bvc2","https://example.com/unsupported2"))
        assertThrows(IOException::class.java) { repo.stream("123",720) }
    }

    /**
     * 竖屏短剧的 `vheight` 是**长边**（854 / 962 / 1282），短边在 `vwidth`。
     * 只按 `vheight` 判清晰度的话，每一档都 >480，「默认 480P」会一路落到最低档并标成
     * 「兼容资源」——用户看到的就是设置没生效。这里钉住「按短边判定」。
     */
    @Test fun portraitResourcesUseTheShortEdgeAsTheirQuality() {
        val rows=JSONObject()
        listOf(360 to 640,480 to 854,540 to 962,720 to 1282,1080 to 1922).forEachIndexed { index,(width,height) ->
            rows.put("video_$index",JSONObject().put("vwidth",width).put("vheight",height)
                .put("codec_type","bytevc1").put("main_url","https://example.com/h$width"))
        }
        val repo=repository { request ->
            if(request.url().host()=="example.com")
                JSONObject().put("video_info",JSONObject().put("data",JSONObject().put("video_list",rows))).toString()
            else videoModel("https://example.com/stream")
        }
        assertEquals("480P",repo.stream("123",480).quality)
        assertEquals("https://example.com/h480",repo.stream("123",480).url)
        assertEquals("720P",repo.stream("123",720).quality)
    }

    /** `quality_desc` 是官方档位文案，优先级最高：有它就照它算，不再猜宽高。 */
    @Test fun qualityDescriptionWinsOverRawDimensions() {
        val rows=JSONObject()
            .put("video_1",JSONObject().put("quality_desc","480P").put("vwidth",360).put("vheight",640)
                .put("codec_type","bytevc1").put("main_url","https://example.com/desc480"))
            .put("video_2",JSONObject().put("quality_desc","1080P").put("vwidth",1080).put("vheight",1920)
                .put("codec_type","bytevc1").put("main_url","https://example.com/desc1080"))
        val repo=repository { request ->
            if(request.url().host()=="example.com")
                JSONObject().put("video_info",JSONObject().put("data",JSONObject().put("video_list",rows))).toString()
            else videoModel("https://example.com/stream")
        }
        assertEquals("https://example.com/desc480",repo.stream("123",480).url)
    }

    /**
     * 播放模型请求必须报 `device_level=4`。
     *
     * 平台按这个值决定下发哪一套编码：官方值 3 时 360/480/576/720 全是 `bytevc2`
     * （Android 的 MP4 解析器里往往只有音轨），被 [ContentRepository.stream] 过滤后
     * 只剩 1080P —— 于是「默认 480P」形同虚设。这里把请求体钉住，防止再被改回去。
     */
    @Test fun playbackModelRequestsTheDeviceLevelThatKeepsLowQualitiesDecodable() {
        val bodies=mutableListOf<String>()
        val repo=repository { request ->
            if(request.url().host()!="example.com" && request.url().encodedPath()=="/novel/player/video_model/v1/")
                bodies+=request.body()?.let { payload -> okio.Buffer().also { payload.writeTo(it) }.readUtf8() }.orEmpty()
            when {
                request.url().host()=="example.com" -> fallbackList("h264" to "https://example.com/stream")
                request.url().encodedPath()=="/novel/player/video_model/v1/" -> videoModel("https://example.com/stream")
                else -> ""
            }
        }
        assertEquals("https://example.com/stream",repo.stream("123").url)
        val body=bodies.single()
        assertTrue(body,body.contains("\"device_level\":4"))
    }

    /**
     * `device_level` 必须能按设备能力覆盖。
     *
     * 解不了 HEVC 的盒子要报 3（与官方 App 一致），能解的要报 4（五档全 HEVC，
     * 「默认 480P」才真的生效）。这里钉住「字段被读进请求体」这件事——
     * 它是 [PlaybackCapability.deviceLevel] 生效的唯一通道。
     */
    @Test fun deviceLevelIsInjectedIntoThePlaybackModelRequest() {
        val bodies=mutableListOf<String>()
        val repo=repository { request ->
            if(request.url().host()!="example.com" && request.url().encodedPath()=="/novel/player/video_model/v1/")
                bodies+=request.body()?.let { payload -> okio.Buffer().also { payload.writeTo(it) }.readUtf8() }.orEmpty()
            when {
                request.url().host()=="example.com" -> fallbackList("h264" to "https://example.com/stream")
                request.url().encodedPath()=="/novel/player/video_model/v1/" -> videoModel("https://example.com/stream")
                else -> ""
            }
        }
        repo.deviceLevel=3
        assertEquals("https://example.com/stream",repo.stream("123").url)
        assertTrue(bodies.single(),bodies.single().contains("\"device_level\":3"))
    }

    @Test fun legacyMissingCodecLabelKeepsQualityLimit() {
        val repo=streamRepository(Triple(720,"","https://example.com/legacy"),Triple(1080,"bytevc1","https://example.com/hevc"))
        assertEquals("720P",repo.stream("123",720).quality)
    }

    private fun repository(clock: () -> Long = System::currentTimeMillis, respond: (Request) -> String) = ContentRepository(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
.code(200).message("OK").body(ResponseBody.create(null, respond(chain.request()))).build()
        }.build(),clock
    )

    /**
     * 榜单来自 App 分类接口按「最高热度」排（该接口 2026-10-21 起要签名）：`sort=["hot_score"]`，
     * 分类用 `select_items.genre`（见 [RankBoard.genre]），名次与热度都在同一条响应里。
     *
     * 接口的 `recommend_info.rank` 是**页内序号**（`offset=20` 时第一名又是 1），
     * 客户端要加上这一页之前的条数才是榜单名次——这里把 `offset` 与名次一起钉住。
     */
    private fun rankBody(request: Request): JSONObject {
        val body=JSONObject(request.body()?.let { payload -> okio.Buffer().also { payload.writeTo(it) }.readUtf8() }.orEmpty())
        val select=body.getJSONObject("select_items")
        val offset=body.getInt("offset")
        val genre=select.getJSONArray("genre").takeIf { it.length()>0 }?.getString(0).orEmpty()
        val title=if(genre.isEmpty()) "综合榜" else genre
        return JSONObject()
            .put("code",0)
            .put("data",JSONObject()
                .put("has_more",true)
                .put("video_data",JSONArray().put(JSONObject()
                    .put("series_id","123")
                    .put("title",title)
                    .put("cover","https://example.com/cover.jpg")
                    .put("episode_cnt",12)
                    // 列表接口每条都带 `sub_title_list`，其中 `data_type == 0` 的那条就是「第N季」。
                    .put("sub_title_list",JSONArray()
                        .put(JSONObject().put("content","第3季").put("data_type",0))
                        .put(JSONObject().put("content","第1季").put("data_type",0)))
                    .put("recommend_info",JSONObject().put("rank","1"))
                    .put("recommend_reason_list",JSONObject()
                        .put("sub_title_list",JSONArray()
                            .put(JSONObject().put("content","第1季"))
                            .put(JSONObject().put("content","5746万").put("data_type",27)))
                        .toString()))))
    }

    /** 请求体（榜单 / 分类共用同一个接口，只能从 body 区分）。 */
    private fun requestBody(request: Request): JSONObject =
        JSONObject(request.body()?.let { payload -> okio.Buffer().also { payload.writeTo(it) }.readUtf8() }.orEmpty())

    /** 每个榜都带上自己的分类与排序：漫剧 / 真人 / AI剧 按热度，「上新」不筛分类、按上架时间。 */
    @Test fun `every board asks the api for its own genre and sort`() {
        val bodies=mutableListOf<JSONObject>()
        val repo=repository { request ->
            assertEquals("/reading/distribution/category/landpage/v/",request.url().encodedPath())
            bodies+=requestBody(request)
            rankBody(request).toString()
        }
        RankBoard.entries.forEach { board -> repo.rankRaw(1,board) }
        val selects=bodies.map { it.getJSONObject("select_items") }
        val genres=selects.map { it.getJSONArray("genre").let { g -> if(g.length()==0) "" else g.getString(0) } }
        val sorts=selects.map { it.getJSONArray("sort").getString(0) }
        assertEquals(listOf("comic_series","short_play","ai_series",""),genres)
        assertEquals(listOf("hot_score","hot_score","hot_score","online_time"),sorts)
        bodies.forEach { body -> assertEquals("only_content",body.getString("req_type")) }
    }

    /** 名次是累计的：第 2 个逻辑页要从 101 起，不能把接口给的页内序号直接显示出来。 */
    @Test fun `rank numbers continue across pages instead of restarting at one`() {
        val repo=repository { request -> rankBody(request).toString() }
        assertEquals(RankPosition(1,"5746万"),repo.rank(1,RankBoard.COMIC).ranking!!.positions["123"])
        assertEquals(RankPosition(101,"5746万"),repo.rank(2,RankBoard.COMIC).ranking!!.positions["123"])
        assertEquals(RankPosition(21,"5746万"),repo.rankRaw(2,RankBoard.COMIC).ranking!!.positions["123"])
    }

    /** 卡片字段与分类页同一套：封面反转义、集数进角标、出品方取 `copyright`。 */
    @Test fun `rank cards reuse the category card mapping`() {
        val repo=repository { request -> rankBody(request).toString() }
        val page=repo.rankRaw(1,RankBoard.REAL)
        // 这份伪响应把请求里的 genre 当片名回给客户端：用来确认「拿到的就是自己那个榜」。
        assertEquals("short_play",page.items.single().title)
        assertEquals("https://example.com/cover.jpg",page.items.single().cover)
        assertEquals("12 集",page.items.single().badge)
        assertEquals(12,page.items.single().episodeCount)
        // 季号来自接口：取第一条「第N季」（`data_type == 0`），不从剧名里猜。
        assertEquals(3,page.items.single().seasonIndex)
        assertTrue(page.hasMore)
    }

    /** 一页要几条由调用方定：首页剧架 20 条，榜单页一整页 100 条。 */
    @Test fun `rankRaw asks for a small page while rank asks for a full one`() {
        val limits=mutableListOf<Int>()
        val repo=repository { request ->
            limits+=requestBody(request).getInt("limit")
            rankBody(request).toString()
        }
        repo.rankRaw(1,RankBoard.COMIC); repo.rank(1,RankBoard.COMIC)
        assertEquals(listOf(20,100),limits)
    }

    /** 页号越界直接拒绝，不发请求；接口结构变了要如实报错。 */
    @Test fun `rank rejects invalid pages and unusable payloads`() {
        var requests=0
        val repo=repository { requests++; """{"code":0,"data":{"has_more":false,"video_data":[]}}""" }
        assertThrows(IllegalArgumentException::class.java) { repo.rank(0,RankBoard.COMIC) }
        assertThrows(IllegalArgumentException::class.java) { repo.rank(101,RankBoard.COMIC) }
        assertThrows(IOException::class.java) { repo.rank(1,RankBoard.COMIC) }
        assertEquals(1,requests)
    }

    /** 首页四栏一次并发取四个榜；单个榜失败只让那一栏不出现，四个都失败才抛错。 */
    @Test fun homeFeedFetchesAllFourBoardsAndToleratesOneFailure() {
        val genres=java.util.Collections.synchronizedList(mutableListOf<String>())
        val repo=repository { request ->
            val select=requestBody(request).getJSONObject("select_items")
            val genre=select.getJSONArray("genre").let { g -> if(g.length()==0) "" else g.getString(0) }
            genres+=genre
            if(genre=="ai_series") throw IOException("offline")
            rankBody(request).toString()
        }
        val feed=repo.homeFeed()
        assertEquals(3,feed.size)
        assertFalse(feed.containsKey(RankBoard.AI))
        assertEquals(RankBoard.entries.size,genres.size)
    }

    @Test fun homeFeedFailsOnlyWhenEveryBoardFails() {
        val repo=repository { throw IOException("offline") }
        assertThrows(IOException::class.java) { repo.homeFeed() }
    }
    @Test fun comicSearchCarriesCursorAndKeepsTypesAndQueriesSeparate() {
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            requests+=request
            JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(19,"999")
                .put("search_id","comic-session").put("passback","comic-cursor").put("next_offset",25))).toString()
        }
        repo.search("修仙",1,ContentType.COMIC)
        repo.search("修仙",2,ContentType.COMIC)
        assertEquals("comic-session",requests[1].url().queryParameter("search_id"))
        assertEquals("comic-cursor",requests[1].url().queryParameter("passback"))
        assertEquals("25",requests[1].url().queryParameter("offset"))
        assertThrows(SearchSessionExpiredException::class.java) { repo.search("重生",2,ContentType.COMIC) }
        assertEquals(2,requests.size)
    }

    /**
     * 搜索走 **GET**，分类 / 榜单走 **POST**——两条接口对方法的要求是相反的：
     *
     * * `GET /reading/bookapi/search/tab/v`：参数全在 query 里，同一路径 POST 是 404；
     * * `POST /reading/distribution/category/landpage/v/`：JSON body，GET 该路径是 404。
     *
     * 签名不覆盖 HTTP 方法，所以方法必须由调用方显式钉住（[Signer.Request.method]），
     * 不能靠「有没有 body」去猜。
     */
    @Test fun searchIsGetWhileCategoryAndRankArePost() {
        val methods=mutableListOf<Pair<String,String>>()
        val repo=repository { request ->
            methods+=request.url().encodedPath() to request.method()
            when {
                request.url().encodedPath()=="/reading/bookapi/search/tab/v" ->
                    JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(19,"999"))).toString()
                else -> rankBody(request).toString()
            }
        }
        repo.search("修仙",1,ContentType.COMIC)
        repo.rank(1,RankBoard.COMIC)
        assertEquals("GET",methods[0].second)
        assertEquals("POST",methods[1].second)
    }

    @Test fun expiredCursorRequestsFreshSearchInsteadOfDuplicateOffsetPage() {
        var now=0L
        var requests=0
        val repo=repository(clock={ now }) {
            requests++
            JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(19,"999")
                .put("search_id","session").put("passback","cursor"))).toString()
        }
        repo.search("修仙",1,ContentType.COMIC)
        now=300001L
        assertThrows(SearchSessionExpiredException::class.java) { repo.search("修仙",2,ContentType.COMIC) }
        assertEquals(1,requests)
        repo.search("修仙",1,ContentType.COMIC)
        repo.search("修仙",2,ContentType.COMIC)
        assertEquals(3,requests)
    }

    /**
     * 观看历史一次取全，不靠翻页。
     *
     * 这条接口的 `offset` 是假的（给 50/100/150 回来的都是同一批前 50 条），只有 `limit` 有用。
     * 早先写死 `limit=50`，账号里有 139 条时电视上只出现 50 条——用户读到的就是「同步来的不完全」。
     */
    @Test fun `history asks for every record in one request`() {
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            requests+=request
            when(request.url().encodedPath()) {
                "/reading/bookapi/read_progress/list/v" -> {
                    val limit=request.url().queryParameter("limit").orEmpty()
                    val n=limit.toIntOrNull() ?: 0
                    // 模拟平台：limit 大于真实条数时照实际数量返回，且不接受 offset。
                    val count=minOf(n,139)
                    JSONObject().put("code",0).put("video_data",JSONArray().also { array ->
                        repeat(count) { index -> array.put(JSONObject().put("series_id","${1000+index}")
                            .put("vid","${9000+index}").put("vid_index",1).put("last_watch_time",index.toLong())) }
                    }).toString()
                }
                else -> {
                    val ids=request.url().queryParameter("book_id").orEmpty().split(',')
                    JSONObject().put("code",0).put("data",JSONArray().also { array ->
                        ids.forEach { id -> array.put(JSONObject().put("book_id",id).put("book_name","剧 $id")) }
                    }).toString()
                }
            }
        }
        repo.sessionToken="sessionid=abc"
        val rows=repo.accountHistory()
        assertEquals(139,rows.size)
        val list=requests.first { it.url().encodedPath()=="/reading/bookapi/read_progress/list/v" }
        assertTrue("limit 要足够大",(list.url().queryParameter("limit")?.toIntOrNull() ?: 0)>=139)
        assertNull("不要靠 offset 翻页",list.url().queryParameter("offset"))
        // 元数据按 50 一片分三次要（139 → 50+50+39），每片都不超过上限。
        val detailCalls=requests.filter { it.url().encodedPath()=="/reading/bookapi/multi-detail/v" }
        assertEquals(3,detailCalls.size)
        detailCalls.forEach { call ->
            assertTrue((call.url().queryParameter("book_id")?.split(',')?.size ?: 0)<=50)
        }
    }

    /**
     * 分类目录走 App 分类接口（`/reading/distribution/category/landpage/v/`，要签名）：
     * 翻页靠 body 里的 `offset`，分类与小类靠 `select_items` 的 `genre` + `category_dim_theme`，
     * `has_more` 决定还有没有下一页，且没有名次与热度。
     */
    @Test fun `category reads the unsigned api and stops when has_more is false`() {
        val requests=mutableListOf<Request>()
        val bodies=mutableListOf<JSONObject>()
        val repo=repository { request ->
            requests+=request
            val body=requestBody(request)
            bodies+=body
            val offset=body.optInt("offset",0)
            val more=offset==0
            """{"code":0,"data":{"has_more":$more,"next_offset":${offset+100},"video_data":[
                {"series_id":"${if(offset==0) "1" else "2"}","title":"玄幻漫剧","cover":"https://e/c.jpg",
                 "video_desc":"简介","episode_cnt":9,
                 "category_schema":"[{\"name\":\"玄幻\"}]"}]}}"""
        }
        val comic=CategoryCatalog.groups.first()
        val tag=comic.tags[1]
        val first=repo.category(tag.path)
        assertEquals("/reading/distribution/category/landpage/v/",requests[0].url().encodedPath())
        assertEquals("玄幻漫剧",first.items.single().title)
        assertEquals("9 集",first.items.single().badge)
        assertTrue("has_more=true 还有下一页",first.hasMore)
        assertNull("分类页没有名次",first.ranking)
        // 大类段给 `genre`，小类段给接口的主题标识——两者都来自同一张 [CategoryCatalog]。
        assertEquals(comic.genre,bodies[0].getJSONObject("select_items").getJSONArray("genre").getString(0))
        assertEquals(tag.themeId,bodies[0].getJSONObject("select_items").getJSONArray("category_dim_theme").getString(0))
        // 「全部」不带主题筛选。
        repo.category(comic.path)
        assertFalse(bodies[1].getJSONObject("select_items").has("category_dim_theme"))

        val last=repo.category(tag.path,2)
        assertFalse("has_more=false 不再有下一页",last.hasMore)
    }

    /** 认不出的分类路径直接拒绝，不发请求。 */
    @Test fun `category rejects unknown paths`() {
        var requests=0
        val repo=repository { requests++; """{"code":0,"data":{"has_more":false,"video_data":[]}}""" }
        assertThrows(IOException::class.java) { repo.category("comic-drama/不存在") }
        assertThrows(IllegalArgumentException::class.java) { repo.category("comic-drama",0) }
        assertThrows(IllegalArgumentException::class.java) { repo.category("comic-drama",101) }
        assertEquals(0,requests)
    }

    /** 分类路径与页码都要校验：空路径、超长路径、越界页码直接拒绝，不发请求。 */
    @Test fun `category rejects invalid path and page`() {
        var requests=0
        val repo=repository { requests++; """{"code":0,"data":{"has_more":false,"video_data":[]}}""" }
        assertThrows(IllegalArgumentException::class.java) { repo.category("") }
        assertThrows(IllegalArgumentException::class.java) { repo.category("x".repeat(81)) }
        assertThrows(IllegalArgumentException::class.java) { repo.category("comic-drama",0) }
        assertThrows(IllegalArgumentException::class.java) { repo.category("comic-drama",101) }
        assertEquals(0,requests)
    }

    private fun searchTab(type: Int,id: String) = JSONObject().put("tab_type",type).put("has_more",true)
        .put("data",JSONArray().put(JSONObject().put("video_data",JSONArray().put(JSONObject()
            .put("series_id",id).put("title","测试剧集").put("episode_cnt",8)))))

    /**
     * 出品方直接取接口字段，不再为这一屏发 `multi-detail`。
     *
     * 搜索接口的 `video_data.copyright` 与分类 / 详情同源；占位串（`短剧版权3493645060`）
     * 一律当空值，卡片第二行收起而不是显示一串编号。
     */
    @Test fun publisherComesFromTheListPayloadAndDropsPlaceholders() {
        val repo=repository { request ->
            JSONObject().put("code",0).put("search_tabs",JSONArray().put(JSONObject()
                .put("tab_type",19).put("has_more",false)
                .put("data",JSONArray()
                    .put(JSONObject().put("video_data",JSONArray().put(JSONObject()
                        .put("series_id","901").put("title","有出品方").put("episode_cnt",12)
                        .put("copyright","原来如是"))))
                    .put(JSONObject().put("video_data",JSONArray().put(JSONObject()
                        .put("series_id","902").put("title","占位出品方").put("episode_cnt",30)
                        .put("copyright","短剧版权3493645060"))))))).toString()
        }
        val items=repo.search("测试",1,ContentType.COMIC).items.associateBy { it.id }
        assertEquals("原来如是",items.getValue("901").author)
        assertEquals("",items.getValue("902").author)
        assertEquals("",repo.publisher("   "))
        assertEquals("短剧版权",repo.publisher("短剧版权"))   // 不是「短剧版权 + 长编号」就不是占位串
    }

    @Test fun comicSearchRequestsAndSelectsOnlyComicTab() {
        var requests=0
        val repo=repository { request ->
            requests++
            assertEquals("19",request.url().queryParameter("tab_type"))
            assertEquals(if(requests==1) "0" else "20",request.url().queryParameter("offset"))
            assertEquals("修仙",request.url().queryParameter("query"))
            JSONObject().put("code",0).put("search_tabs",JSONArray()
                .put(searchTab(11,"111")).put(searchTab(19,"999"))).toString()
        }
        repo.search("修仙",1,ContentType.COMIC)
        assertEquals(listOf("999"),repo.search("修仙",2,ContentType.COMIC).items.map { it.id })
    }

    @Test fun emptyComicSearchDoesNotFallBackToShortResults() {
        var requests=0
        val repo=repository { requests++; """{"code":0,"search_tabs":[{"tab_type":19,"data":[],"has_more":false}]}""" }
        assertTrue(repo.search("无结果",1,ContentType.COMIC).items.isEmpty())
        assertEquals(1,requests)
    }

    /**
     * 相关作品（`plan/v:version/` 的 `show_type=547` cell）：每行的 `season_index` 要进 [Series]，
     * 「同系列各季」按它排序，**不靠剧名**——接口给的季号在剧名中间（`聚宝仙盆第二季：…`）时，
     * 剧名解析读不出来，但接口给得出。
     */
    @Test fun `related rows carry the api season index and group by it`() {
        val repo=repository { request ->
            val cell=JSONObject()
                .put("show_type",547).put("cell_name","相关作品")
                .put("video_data",JSONArray()
                    .put(JSONObject().put("series_id","201").put("title","聚宝仙盆第二季：灵根亿点点废，战力亿点强")
                        .put("season_index",2).put("episode_cnt",80))
                    .put(JSONObject().put("series_id","202").put("title","聚宝仙盆：灵根亿点点废，战力亿点强")
                        .put("season_index",1).put("episode_cnt",70))
                    .put(JSONObject().put("series_id","203").put("title","别的剧第一季").put("season_index",1)))
            JSONObject().put("code",0).put("data",JSONArray().put(cell)).toString()
        }
        val works=repo.related("123","聚宝仙盆：灵根亿点点废，战力亿点强")
        assertEquals(listOf("202","201"),works.seasons.map { it.id })
        assertEquals(listOf(1,2),works.seasons.map { it.seasonIndex })
        // 同系列在前（按季号升序），其它相关作品在后。
        assertEquals(listOf("202","201","203"),works.items.map { it.id })
        assertEquals(2,works.items[1].seasonIndex)
    }

    /**
     * 详情（简介 + 分集）走播放侧的 `video_detail`：参数是**单值 `series_id`**，
     * 分集从 `video_list[].vid` 取、按 `vid_index` 排序；简介 / 封面 / 集数 / 题材都在 `video_data` 里。
     */
    @Test fun `detail reads the app video detail and orders episodes by vid index`() {
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            requests+=request
            """{"code":0,"data":{"video_data":{"series_id":"123","series_title":"测试漫剧","series_cover":"https://e/c.jpg",
                "series_intro":"简介","episode_cnt":3,"episode_right_text":"全 3 集","season_index":7,
                "category_schema":"[{\"name\":\"玄幻\"},{\"name\":\"逆袭\"}]",
                "video_list":[{"vid":"300","vid_index":3},{"vid":"100","vid_index":1},{"vid":"200","vid_index":2}]}}}"""
        }
        val detail=repo.detail("123")
        assertEquals(listOf("100","200","300"),detail.episodes)
        assertEquals("测试漫剧",detail.series.title)
        assertEquals("https://e/c.jpg",detail.series.cover)
        assertEquals("简介",detail.series.description)
        assertEquals("3 集",detail.series.badge)   // 「全 N 集」归一成「N 集」
        assertEquals("玄幻 · 逆袭",detail.series.tags)
        assertEquals(3,detail.series.episodeCount)
        assertEquals(7,detail.series.seasonIndex)
        // 参数在 query 里（`series_id` 单值），路径是播放侧那条。
        // （详情还会顺带问一次 `multi-detail` 补出品方，因此这里按路径挑那一条。）
        val detailRequest=requests.single { it.url().encodedPath()=="/novel/player/video_detail/v1/" }
        assertEquals("123",detailRequest.url().queryParameter("series_id"))
    }

    /** 详情接口回空响应体（设备没注册）/ 业务码非 0 时都要如实报错，不能拿半份数据糊过去。 */
    @Test fun `detail fails loudly on blank or rejected responses`() {
        assertThrows(IOException::class.java) { repository { "" }.detail("123") }
        assertThrows(IOException::class.java) { repository { """{"code":100001,"message":"参数错误"}""" }.detail("123") }
        assertThrows(IllegalArgumentException::class.java) { repository { "" }.detail("不是数字") }
    }

    /**
     * 提示词走 App 搜索的 suggest 接口：`query_result_v2[].name` 是词，
     * 带 `series_id` 的 `video_data` 才是「这条本身就是一部剧」，可以直接进详情。
     */
    @Test fun `suggestions read the app suggest endpoint`() {
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            requests+=request
            """{"code":0,"data":{"query_result_v2":[
                {"name":"仙缘劫起","word_type_str":"short_play_name",
                 "video_data":{"series_id":"7688329494444133438","title":"仙缘劫起：十年归尘","copyright":"某文化"}},
                {"name":"仙逆","word_type_str":"album"},
                {"name":"仙逆","word_type_str":"album"}]}}"""
        }
        val rows=repo.suggestions("仙")
        assertEquals(listOf("仙缘劫起","仙逆"),rows.map { it.name })
        assertEquals("7688329494444133438",rows[0].series!!.id)
        assertEquals("仙缘劫起：十年归尘",rows[0].series!!.title)
        assertEquals("某文化",rows[0].series!!.author)
        assertEquals("short_play_name",rows[0].wordType)
        assertNull("没有 series_id 的只能当查询词",rows[1].series)
        assertEquals("/reading/bookapi/search/suggest/v",requests.single().url().encodedPath())
        assertEquals("仙",requests.single().url().queryParameter("query"))
        // 空关键词不发请求。
        assertTrue(repo.suggestions("  ").isEmpty())
        assertEquals(1,requests.size)
    }

    /**
     * 搜索接口对没注册过的设备回 HTTP 200 + 空响应体。空 body 必须先被识别成「接口不可用」，
     * 而不是直接丢给 JSONObject 抛出 `A JSONObject text must begin with '{'`；
     * 判死之后同一进程里不再空跑第二个 tab。
     */
    @Test fun blankSearchResponseFailsOnceWithoutHammeringTheOtherTab() {
        var requests=0
        val repo=repository { requests++; "" }
        assertThrows(IOException::class.java) { repo.search("修仙",1,ContentType.COMIC) }
        assertEquals(1,requests)
        assertThrows(IOException::class.java) { repo.search("修仙",1,ContentType.SHORT) }
        assertEquals(1,requests)
    }

    /** 游标过期要如实抛出（界面据此回到第 1 页重搜），不能拿别的页顶替。 */
    @Test fun expiredCursorSurfacesAsSearchSessionExpired() {
        val repo=repository { request ->
            if(request.url().queryParameter("offset")=="0")
                """{"code":0,"search_tabs":[{"tab_type":19,"has_more":true,"data":[],"search_id":"s","passback":"p"}]}"""
            else """{"code":0,"search_tabs":[]}"""
        }
        repo.search("修仙",1,ContentType.COMIC)
        assertThrows(SearchSessionExpiredException::class.java) { repo.search("修仙",3,ContentType.COMIC) }
    }

    /** 可选登录态：配置后按 `x-tt-token` 发出（整段 Cookie 再补一个 `Cookie` 头），留空就保持匿名请求。 */
    @Test fun sessionTokenIsSentAsLoginHeaderOnlyWhenConfigured() {
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            requests+=request
            JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(19,"999"))).toString()
        }
        repo.search("修仙",1,ContentType.COMIC)
        assertNull(requests.last().header("x-tt-token"))
        assertNull(requests.last().header("Cookie"))
        repo.sessionToken=" token-value "
        repo.search("修仙",1,ContentType.COMIC)
        assertEquals("token-value",requests.last().header("x-tt-token"))
        assertNull(requests.last().header("Cookie"))
        repo.sessionToken="sessionid=abc;uid=1"
        repo.search("修仙",1,ContentType.COMIC)
        assertEquals("sessionid=abc;uid=1",requests.last().header("x-tt-token"))
        assertEquals("sessionid=abc;uid=1",requests.last().header("Cookie"))
    }
}
