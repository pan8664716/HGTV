package com.hgtv.star.core

import java.util.concurrent.TimeUnit
import kotlin.random.Random
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 隔离变量：签名分支 / query 参数集 / 身份（随机 vs 已注册）/ Cookie。
 * 每次运行最多打 4 个请求，注意别把身份烧穿。
 */
class LiveIsolateTest {
    private val base = "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/video_model/v1/"
    private val vid = "7667513056330517529"
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val ua = "com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/UKQ1.230804.001; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"

    // 参考实现（现在仍可用）的最小参数集
    private fun minimal(did: String, iid: String) = linkedMapOf(
        "ac" to "wifi", "aid" to "8662", "app_name" to "novelread", "channel" to "update_64",
        "device_brand" to "Redmi", "device_id" to did, "device_platform" to "android",
        "device_type" to "25053RT47C", "dpi" to "520", "iid" to iid, "language" to "zh",
        "manifest_version_code" to "73532", "os" to "android", "os_api" to "36", "os_version" to "16",
        "resolution" to "1280*2772", "ssmix" to "a", "update_version_code" to "73532",
        "version_code" to "73532", "version_name" to "7.3.5.32"
    )

    private fun freshDevice() = linkedMapOf(
        "device_id" to rand16(), "iid" to rand16(), "install_id" to "x",
        "device_brand" to "Redmi", "device_type" to "25053RT47C", "os_version" to "16", "os_api" to "36"
    )

    private fun rand16() = Random.nextLong(1_000_000_000_000_000L, 9_999_999_999_999_999L).toString()

    private val minimalBody = """{"video_id":"$vid","content_type":1,"biz_param":{"need_all_video_definition":true,"video_platform":3}}"""

    private fun send(label: String, url: String, headers: Map<String, String>, body: String, extra: Map<String, String> = emptyMap()) {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        b.header("User-Agent", ua)
        b.header("Accept", "application/json")
        b.header("Content-Type", "application/json; charset=utf-8")
        b.header("x-xs-from-web", "0")
        b.header("sdk-version", "2")
        b.header("x-ss-dp", "8662")
        b.header("x-tt-store-region", "cn")
        b.header("x-tt-store-region-src", "did")
        extra.forEach { (k, v) -> b.header(k, v) }
        b.post(body.toRequestBody("application/json; charset=UTF-8".toMediaType()))
        client.newCall(b.build()).execute().use { r ->
            val t = r.body?.string().orEmpty()
            val logid = r.header("X-Tt-Logid") ?: ""
            println("[iso] $label HTTP ${r.code} ${t.length}B logid=$logid :: ${t.take(90)}")
        }
    }

    @Test fun isolate() {
        assumeTrue("HONGGUO_LIVE", System.getenv("HONGGUO_LIVE") == "1")
        val only = System.getenv("HONGGUO_ONE") ?: ""
        if (only == "A" || only == "ALL") {
        val d1 = freshDevice()
        val signed1 = PassportSigner(device = d1).sign(base, minimal(d1.getValue("device_id"), d1.getValue("iid")), minimalBody.toByteArray())
        send("A-passport-min-random", signed1.url, signed1.headers, minimalBody)
        }

        if (only == "B" || only == "ALL") {
        val d2 = freshDevice()
        val signed2 = Signer(device = d2).sign(base, minimal(d2.getValue("device_id"), d2.getValue("iid")), minimalBody.toByteArray())
        send("B-signer0-min-random", signed2.url, signed2.headers, minimalBody)
        }

        if (only == "D" || only == "ALL") {
        // 项目现状：直接用 VendorConstants 那份档案（version_code=71332），只把身份换成随机。
        val d4 = freshDevice()
        val q4 = linkedMapOf(
            "iid" to d4.getValue("iid"), "device_id" to d4.getValue("device_id"), "ac" to "wifi",
            "channel" to "update_64", "aid" to "8662", "app_name" to "novelread",
            "version_code" to "71332", "version_name" to "7.1.3.32", "device_platform" to "android",
            "os" to "android", "ssmix" to "a", "device_type" to "25053RT47C", "device_brand" to "Redmi",
            "language" to "zh", "os_api" to "36", "os_version" to "16",
            "manifest_version_code" to "71332", "resolution" to "1280*2772", "dpi" to "520",
            "update_version_code" to "71332", "host_abi" to "arm64-v8a", "dragon_device_type" to "phone",
            "pv_player" to "71332", "compliance_status" to "0", "need_personal_recommend" to "1",
            "player_so_load" to "1", "is_android_pad_screen" to "0"
        )
        val signed4 = Signer(device = d4).sign(base, q4, minimalBody.toByteArray())
        send("D-signer0-app-query-random", signed4.url, signed4.headers, minimalBody)
        }

        if (only == "E" || only == "ALL") {
        // 官方风格档案（73532/Redmi/16）但沿用项目那套完整 query。
        val d5 = freshDevice()
        val q5 = linkedMapOf(
            "iid" to d5.getValue("iid"), "device_id" to d5.getValue("device_id"), "ac" to "wifi",
            "channel" to "update_64", "aid" to "8662", "app_name" to "novelread",
            "version_code" to "73532", "version_name" to "7.3.5.32", "device_platform" to "android",
            "os" to "android", "ssmix" to "a", "device_type" to "25053RT47C", "device_brand" to "Redmi",
            "language" to "zh", "os_api" to "36", "os_version" to "16",
            "manifest_version_code" to "73532", "resolution" to "1280*2772", "dpi" to "520",
            "update_version_code" to "73532", "host_abi" to "arm64-v8a", "dragon_device_type" to "phone",
            "pv_player" to "73532", "compliance_status" to "0", "need_personal_recommend" to "1",
            "player_so_load" to "1", "is_android_pad_screen" to "0"
        )
        val signed5 = Signer(device = d5).sign(base, q5, minimalBody.toByteArray())
        send("E-signer0-official-code-random", signed5.url, signed5.headers, minimalBody)
        }

        if (only == "C" || only == "ALL") {
        val reg = "3911435176164651"
        val regIid = "3911435175382331"
        val d3 = linkedMapOf("device_id" to reg, "iid" to regIid, "install_id" to regIid, "device_brand" to "Redmi", "device_type" to "25053RT47C", "os_version" to "16", "os_api" to "36")
        val signed3 = PassportSigner(device = d3).sign(base, minimal(reg, regIid), minimalBody.toByteArray())
        send("C-passport-min-registered", signed3.url, signed3.headers, minimalBody,
            mapOf("Cookie" to "install_id=$regIid; ttreq=1\$913bb5a187044353e5818a1f7856bee63f006226"))
        }
    }
}
