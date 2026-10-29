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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

class LivePageSizeProbeTest {
    private val live = System.getenv("HONGGUO_LIVE") == "1"

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()

    private fun post(url: String, values: Map<String, String>, body: ByteArray? = null): String {
        val signer = Signer()
        val signed = signer.sign(url, values, body)
        val builder = Request.Builder().url(signed.url)
        signed.headers.forEach { (k, v) -> builder.header(k, v) }
        signed.body?.let { builder.post(it.toRequestBody("application/json; charset=UTF-8".toMediaType())) }
        http.newCall(builder.build()).execute().use { response ->
            return response.body?.string().orEmpty()
        }
    }

    /** 搜索接口（`GET /reading/bookapi/search/tab/v`）的 `count` 上限。 */
    @Test fun searchCountCeiling() {
        assumeTrue("需要 HONGGUO_LIVE=1 才联网实测", live)
        val values = LinkedHashMap(VendorConstants.device).apply {
            this["version_code"] = "73532"; this["manifest_version_code"] = "73532"
            this["update_version_code"] = "73532"; this["version_name"] = "7.3.5.32"
        }
        for (count in listOf(20, 60, 100, 200)) {
            val q = LinkedHashMap(values).apply {
                putAll(linkedMapOf("query" to "短剧", "count" to count.toString(), "offset" to "0",
                    "tab_type" to "1", "bookshelf_search_plan" to "4", "use_correct" to "true",
                    "user_is_login" to "0"))
            }
            val raw = runCatching {
                post("https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/search/tab/v", q)
            }.getOrElse { "（失败 ${it.message}）" }
            val rows = runCatching {
                JSONObject(raw).optJSONObject("data")?.optJSONArray("video_data")?.length()
            }.getOrNull()
            println("[page-size] 搜索 count=$count → 响应 ${raw.length}B，video_data=$rows")
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

    /**
     * 榜单是官网 SSR、服务端固定 20 条一页（改不了）。
     * 这里量「并发取 5 个物理页拼成 100 条」的耗时，看能不能当作一个逻辑页用。
     */
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

    /** 榜单（官网 SSR）一页多少条、能翻多少页。 */
    @Test fun rankPageSize() {
        assumeTrue("需要 HONGGUO_LIVE=1 才联网实测", live)
        val repo = ContentRepository()
        for (page in listOf(1, 2, 3)) {
            val result = runCatching { repo.rank(page, RankBoard.COMIC) }
            println("[page-size] 榜单 page=$page → ${result.getOrNull()?.items?.size} 条，" +
                "hasMore=${result.getOrNull()?.hasMore} 失败=${result.exceptionOrNull()?.message}")
        }
    }
}
