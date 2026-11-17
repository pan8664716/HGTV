// 联网实测：默认跳过，只有 HONGGUO_LIVE=1 时才真的发请求。
// 用途：量各个列表接口**一页最多能给多少条**——这直接决定「按住下键时图跟不跟得上」
// （一页太小，焦点两三秒就跑穿了，每次都得停下来等新的一页）。
// 运行：HONGGUO_LIVE=1 ./gradlew :core:test --tests '*LivePageSizeProbeTest*' --info
package com.hgtv.star.core

import java.util.concurrent.TimeUnit
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

class LivePageSizeProbeTest {
    private val live = System.getenv("HONGGUO_LIVE") == "1"

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()

    private fun post(url: String, values: Map<String, String>, body: ByteArray? = null): String {
        val signer = Signer()
        val signed = signer.sign(url, values, body, deviceOverride = values)
        val builder = Request.Builder().url(signed.url)
        signed.headers.forEach { (k, v) -> builder.header(k, v) }
        signed.body?.let { builder.post(it.toRequestBody("application/json; charset=UTF-8".toMediaType())) }
        http.newCall(builder.build()).execute().use { response ->
            return response.body?.string().orEmpty()
        }
    }

    private fun get(url: String, values: Map<String, String>): String {
        val signed = Signer().sign(url, values, null, method = "GET", deviceOverride = values)
        val builder = Request.Builder().url(signed.url).get()
        signed.headers.forEach { (k, v) -> builder.header(k, v) }
        http.newCall(builder.build()).execute().use { response ->
            return response.body?.string().orEmpty()
        }
    }

    /** 搜索接口（`GET /reading/bookapi/search/tab/v`）的 `count` 上限。 */
    @Test fun searchCountCeiling() {
        assumeTrue("需要 HONGGUO_LIVE=1 才联网实测", live)
        val values = LinkedHashMap(VendorConstants.device).apply {
            this["version_code"] = "74332"; this["manifest_version_code"] = "74332"
            this["update_version_code"] = "74332"; this["version_name"] = "7.4.3.32"
            this["pv_player"] = "74332"
        }
        for (count in listOf(20, 60, 100, 200)) {
            val q = LinkedHashMap(values).apply {
                putAll(linkedMapOf("query" to "短剧", "count" to count.toString(), "offset" to "0",
                    "bookshelf_search_plan" to "4", "use_correct" to "true", "user_is_login" to "0",
                    "search_source" to "1", "request_mode" to "0", "is_first_enter_search" to "true",
                    "bookstore_tab" to "16", "tab_name" to "feed"))
            }
            val raw = runCatching {
                get("https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/search/tab/v", q)
            }.getOrElse { "（失败 ${it.message}）" }
            val rows = runCatching {
                JSONObject(raw).optJSONArray("search_tabs")?.let { tabs ->
                    (0 until tabs.length()).sumOf { tabs.optJSONObject(it)?.optJSONArray("data")?.length() ?: 0 }
                }
            }.getOrNull()
            println("[page-size] 搜索 count=$count → 响应 ${raw.length}B，search_tabs.data=$rows")
        }
    }

    /** 分类接口的 `limit` 上限——已经改成 100，这里复核一次。 */
    @Test fun categoryLimitCeiling() {
        assumeTrue("需要 HONGGUO_LIVE=1 才联网实测", live)
        val repo = ContentRepository()
        for (page in listOf(1, 2)) {
            val result = runCatching { repo.category("comic-drama", page) }
            println("[page-size] 分类 page=$page → ${result.getOrNull()?.items?.size} 条，" +
                "hasMore=${result.getOrNull()?.hasMore} 失败=${result.exceptionOrNull()?.message}")
        }
    }

    /** 榜单逻辑页固定 100 条；这里量连续取 5 页的耗时与返回量。 */
    @Test fun rankFanOutCost() {
        assumeTrue("需要 HONGGUO_LIVE=1 才联网实测", live)
        val repo = ContentRepository()
        // 串行：5 页依次取
        val serial = System.currentTimeMillis()
        var count = 0
        for (page in 1..5) count += runCatching { repo.rank(page, RankBoard.COMIC) }.getOrNull()?.items?.size ?: 0
        val serialMs = System.currentTimeMillis() - serial
        println("[page-size] 榜单串行 5 页 → $count 条，耗时 ${serialMs}ms")

        // 并发：5 页同时取（模拟 ContentRepository 里做聚合时的形状）
        val pool = java.util.concurrent.Executors.newFixedThreadPool(5)
        val started = System.currentTimeMillis()
        val futures = (1..5).map { page -> pool.submit(java.util.concurrent.Callable { repo.rank(page, RankBoard.COMIC).items.size }) }
        val total = futures.sumOf { runCatching { it.get() }.getOrDefault(0) }
        val parallelMs = System.currentTimeMillis() - started
        pool.shutdownNow()
        println("[page-size] 榜单并发 5 页 → $total 条，耗时 ${parallelMs}ms（串行 ${serialMs}ms）")
    }

    /** 榜单每页多少条、能翻多少页。 */
    @Test fun rankPageSize() {
        assumeTrue("需要 HONGGUO_LIVE=1 才联网实测", live)
        val repo = ContentRepository()
        for (page in listOf(1, 2, 3)) {
            val result = runCatching { repo.rank(page, RankBoard.COMIC) }
            println("[page-size] 榜单 page=$page → ${result.getOrNull()?.items?.size} 条，" +
                "hasMore=${result.getOrNull()?.hasMore} 失败=${result.exceptionOrNull()?.message}")
        }
    }

    /**
     * 官方推荐 tab/v 单次能返回多少张卡。
     *
     * 这里刻意只传空 cell 游标，确保请求不会走到 cell/change/v；只观察 tab/v 的
     * `limit` 对首包卡片数和横屏卡片比例的影响。
     */
    @Test fun recommendTabLimitAndHorizontal() {
        assumeTrue("需要 HONGGUO_LIVE=1 才联网实测", live)
        val deviceId=System.getenv("HONGGUO_DEVICE_ID").orEmpty()
        val installId=System.getenv("HONGGUO_INSTALL_ID").orEmpty()
        val profileJson=System.getenv("HONGGUO_DEVICE_PROFILE").orEmpty()
        val cookiesJson=System.getenv("HONGGUO_COOKIES").orEmpty()
        assumeTrue(
            "需要真实设备的 DEVICE_ID / INSTALL_ID / PROFILE / COOKIES",
            deviceId.isNotBlank() && installId.isNotBlank() &&
                profileJson.isNotBlank() && cookiesJson.isNotBlank(),
        )
        val repo=ContentRepository()
        repo.tvDeviceProfile=JSONObject(profileJson).stringMap()
        repo.deviceIdentity=linkedMapOf(
            "device_id" to deviceId,
            "iid" to installId,
            "install_id" to installId,
            "device_token" to System.getenv("HONGGUO_DEVICE_TOKEN").orEmpty(),
            "klink_egdi" to System.getenv("HONGGUO_KLINK_EGDI").orEmpty(),
        )
        repo.registrationCookies=JSONObject(cookiesJson).stringMap()
        for(tab in listOf(RecommendTab.RECOMMEND,RecommendTab.COMIC)) {
            for(limit in listOf(20,60,100)) {
                val result=runCatching {
                    repo.recommendFeed(
                        selection=CategorySelection(),
                        sessionId="",
                        offset=0,
                        limit=limit,
                        tab=tab,
                        horizontalOnly=true,
                    )
                }
                val feed=result.getOrNull()
                println(
                    "[page-size] tab/v tab=${tab.tabType} limit=$limit → " +
                        "items=${feed?.page?.items?.size ?: -1} " +
                        "vertical=${feed?.page?.items?.count { it.vertical } ?: -1} " +
                        "hasMore=${feed?.page?.hasMore} next=${feed?.nextOffset} " +
                        "cell=${feed?.cell?.id?.takeLast(8)} " +
                        "failure=${result.exceptionOrNull()?.message}",
                )
            }
        }
    }

    private fun JSONObject.stringMap(): LinkedHashMap<String,String> {
        val out=LinkedHashMap<String,String>()
        keys().forEach { key ->
            val value=optString(key).trim()
            if(value.isNotEmpty()) out[key]=value
        }
        return out
    }
}
