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

class LivePlaybackMatrixTest {
    private val host = "https://api5-normal-sinfonlineb.fqnovel.com"
    private val did = "3154971173098252"
    private val iid = "3154971173102348"
    private val cdid = "70a1585e-b007-43af-bc0d-4f5c176ccbbf"
    private val openudid = "15fcb0784fcb6042"
    private val cookie = "store-region=cn-gd; store-region-src=did; install_id=$iid; ttreq=1\$804c087461f3bfb97c16b232f9502d254ba9871c"
    private val device = linkedMapOf(
        "device_id" to did, "iid" to iid, "install_id" to iid, "cdid" to cdid, "openudid" to openudid,
        "device_brand" to "Xiaomi", "device_type" to "23127PN0CC", "os_version" to "14", "os_api" to "34"
    )

    private fun baseQuery(vc: String, vn: String) = linkedMapOf(
        "ac" to "wifi", "aid" to "8662", "app_name" to "novelread", "channel" to "xiaomi_8662_64",
        "device_brand" to "Xiaomi", "device_id" to did, "device_platform" to "android",
        "device_type" to "23127PN0CC", "dpi" to "460", "iid" to iid, "language" to "zh",
        "manifest_version_code" to vc, "os" to "android", "os_api" to "34", "os_version" to "14",
        "resolution" to "1200*2670", "ssmix" to "a", "update_version_code" to vc,
        "version_code" to vc, "version_name" to vn, "cdid" to cdid, "openudid" to openudid
    )

    private fun ua(vc: String, os: String = "14") = "com.phoenix.read/$vc (Linux; U; Android $os; zh_CN; 23127PN0CC; Build/UKQ1.230804.001; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"

    private fun send(label: String, vc: String, vn: String, body: String, uaVc: String, withCookie: Boolean = true) {
        val signed = Signer(device = device).sign(host + "/novel/player/video_model/v1/", baseQuery(vc, vn), body.toByteArray())
        val b = Request.Builder().url(signed.url)
        signed.headers.forEach { (k, v) -> b.header(k, v) }
        b.header("User-Agent", ua(uaVc))
        b.header("Accept", "application/json")
        if (withCookie) b.header("Cookie", cookie)
        b.post(body.toRequestBody("application/json; charset=UTF-8".toMediaType()))
        OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build().newCall(b.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val code = if (text.isNotBlank()) JSONObject(text).optInt("code", -999) else -1
            println("[matrix] $label HTTP ${r.code} ${text.length}B code=$code ${text.take(120)}")
        }
    }

    @Test fun matrix() {
        assumeTrue("HONGGUO_LIVE", System.getenv("HONGGUO_LIVE") == "1")
        val appBiz = """{"video_id":"7667513056330517529","biz_param":{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024}}"""
        val sdkBiz = """{"video_id":"7667513056330517529","biz_param":{"video_id_type":0,"need_all_video_definition":true,"video_platform":3}}"""
        send("app 72232/UA71332", "72232", "7.2.2.32", appBiz, "71332")
        send("app 72232/UA72232", "72232", "7.2.2.32", appBiz, "72232")
        send("new 73932/UA73932 appbiz", "73932", "7.3.9.32", appBiz, "73932")
        send("new 73932/UA73932 sdkbiz", "73932", "7.3.9.32", sdkBiz, "73932")
        send("app 72232/UA71332 nocookie", "72232", "7.2.2.32", appBiz, "71332", withCookie = false)
        send("new 73932 nocookie", "73932", "7.3.9.32", appBiz, "73932", withCookie = false)
    }
}
