package com.hgtv.star.core

import java.util.concurrent.TimeUnit
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 诊断用：区分「IP 级风控」与「播放路径/参数问题」。
 * HONGGUO_LIVE=1 才跑。
 */
class LiveDiagTest {
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val did = "3911435176164651"
    private val iid = "3911435175382331"
    private val cookie = "install_id=3911435175382331; ttreq=1\$913bb5a187044353e5818a1f7856bee63f006226"

    private fun device() = linkedMapOf(
        "device_id" to did, "iid" to iid, "install_id" to iid,
        "device_brand" to "Xiaomi", "device_type" to "23127PN0CC",
        "os_version" to "14", "os_api" to "34"
    )

    private fun baseQuery() = linkedMapOf(
        "iid" to iid, "device_id" to did, "ac" to "wifi", "channel" to "xiaomi_8662_64",
        "aid" to "8662", "app_name" to "novelread", "version_code" to "73932",
        "version_name" to "7.3.9.32", "device_platform" to "android", "os" to "android",
        "ssmix" to "a", "device_type" to "23127PN0CC", "device_brand" to "Xiaomi",
        "language" to "zh", "os_api" to "34", "os_version" to "14",
        "manifest_version_code" to "73932", "resolution" to "1200*2670", "dpi" to "460",
        "update_version_code" to "73932", "host_abi" to "arm64-v8a",
        "dragon_device_type" to "phone", "pv_player" to "73932"
    )

    private val ua = "com.phoenix.read/73932 (Linux; U; Android 14; zh_CN; 23127PN0CC; Build/UKQ1.230804.001; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"

    private fun post(label: String, url: String, query: Map<String, String>, body: String) {
        val signed = Signer(device = device()).sign(url, query, body.toByteArray())
        val b = Request.Builder().url(signed.url)
        signed.headers.forEach { (k, v) -> b.header(k, v) }
        b.header("User-Agent", ua)
        b.header("Accept", "application/json")
        b.header("Cookie", cookie)
        b.post(RequestBody.create(MediaType.parse("application/json; charset=UTF-8"), body))
        client.newCall(b.build()).execute().use { r ->
            val t = r.body()?.string().orEmpty()
            println("[diag] $label HTTP ${r.code()} ${t.length}B :: ${t.take(160)}")
        }
    }

    private fun get(label: String, url: String, query: Map<String, String>) {
        val signed = Signer(device = device()).sign(url, query, null, method = "GET")
        val b = Request.Builder().url(signed.url)
        signed.headers.forEach { (k, v) -> b.header(k, v) }
        b.header("User-Agent", ua)
        b.header("Cookie", cookie)
        client.newCall(b.build()).execute().use { r ->
            val t = r.body()?.string().orEmpty()
            println("[diag] $label HTTP ${r.code()} ${t.length}B :: ${t.take(160)}")
        }
    }

    @Test fun diag() {
        assumeTrue("HONGGUO_LIVE", System.getenv("HONGGUO_LIVE") == "1")
        // 1) 搜索：同 IP、同身份，不同业务路径
        get(
            "search-tab", "https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/search/tab/v",
            baseQuery() + linkedMapOf(
                "query" to "赘婿", "count" to "20", "offset" to "0",
                "tab_type" to "11", "bookshelf_search_plan" to "4", "use_correct" to "true",
                "user_is_login" to "0"
            )
        )
        // 2) 同一个 video_id，走账号域名的播放路径
        post(
            "video-model-a", "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/video_model/v1/",
            baseQuery() + linkedMapOf("compliance_status" to "0", "need_personal_recommend" to "1"),
            """{"video_id":"7667513056330517529","biz_param":{"video_id_type":0,"need_all_video_definition":true,"video_platform":3}}"""
        )
        // 3) 换一个 video_id，看是否只对某条片子为空
        post(
            "video-model-b", "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/video_model/v1/",
            baseQuery() + linkedMapOf("compliance_status" to "0", "need_personal_recommend" to "1"),
            """{"video_id":"7667513056330517528","biz_param":{"video_id_type":0,"need_all_video_definition":true,"video_platform":3}}"""
        )
        // 4) 换域名（同 aid 的另一个 shard）
        post(
            "video-model-c", "https://api5-normal-sinfonlinea.fqnovel.com/novel/player/video_model/v1/",
            baseQuery() + linkedMapOf("compliance_status" to "0", "need_personal_recommend" to "1"),
            """{"video_id":"7667513056330517529","biz_param":{"video_id_type":0,"need_all_video_definition":true,"video_platform":3}}"""
        )
    }
}
