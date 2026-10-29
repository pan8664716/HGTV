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

class LivePlaybackVariantTest {
    private val host = "https://api5-normal-sinfonlineb.fqnovel.com"
    private val did = "4474385128492764"; private val iid = "4474385128496860"
    private val cdid = "427b7bd5-02a6-48d3-be13-9af736071521"; private val openudid = "f9ea37f42d180231"
    private val cookie = "store-region=cn-gd; store-region-src=did; install_id=4474385128496860; ttreq=1\$399d5347042c09a52ff0841add8adbc8ffd07264"
    private val sdkBiz = """{"video_id":"7667513056330517529","biz_param":{"video_id_type":0,"need_all_video_definition":true,"video_platform":3}}"""
    private val appBiz = """{"video_id":"7667513056330517529","biz_param":{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024}}"""

    private fun send(label: String, vc: String, vn: String, body: String, includeCdid: Boolean = true, includeOpen: Boolean = true, cookieIn: String? = cookie) {
        val device = linkedMapOf("device_id" to did, "iid" to iid, "install_id" to iid, "cdid" to cdid, "openudid" to openudid, "device_brand" to "Xiaomi", "device_type" to "23127PN0CC", "os_version" to "14", "os_api" to "34", "version_name" to vn)
        val query = linkedMapOf(
            "iid" to iid, "device_id" to did, "ac" to "wifi", "channel" to "xiaomi_8662_64", "aid" to "8662",
            "app_name" to "novelread", "version_code" to vc, "version_name" to vn,
            "device_platform" to "android", "os" to "android", "ssmix" to "a", "device_type" to "23127PN0CC",
            "device_brand" to "Xiaomi", "language" to "zh", "os_api" to "34", "os_version" to "14",
            "manifest_version_code" to vc, "resolution" to "1200*2670", "dpi" to "460",
            "update_version_code" to vc, "host_abi" to "arm64-v8a", "dragon_device_type" to "phone",
            "pv_player" to vc, "compliance_status" to "0", "need_personal_recommend" to "1",
            "player_so_load" to "1", "is_android_pad_screen" to "0"
        )
        if (includeCdid) query["cdid"] = cdid
        if (includeOpen) query["openudid"] = openudid
        val signed = Signer(device = device).sign(host + "/novel/player/video_model/v1/", query, body.toByteArray())
        val b = Request.Builder().url(signed.url)
        signed.headers.forEach { (k, v) -> b.header(k, v) }
        b.header("User-Agent", "com.phoenix.read/" + (if (vc == "72232") "71332" else vc) + " (Linux; U; Android 14; zh_CN; 23127PN0CC; Build/UKQ1.230804.001; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)")
        b.header("Accept", "application/json")
        cookieIn?.let { b.header("Cookie", it) }
        b.post(body.toRequestBody("application/json; charset=UTF-8".toMediaType()))
        OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build().newCall(b.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val code = if (text.isNotBlank()) JSONObject(text).optInt("code", -999) else -1
            println("[variant] $label HTTP ${r.code} ${text.length}B code=$code")
        }
    }

    @Test fun variant() {
        assumeTrue("HONGGUO_LIVE", System.getenv("HONGGUO_LIVE") == "1")
        send("base 73932 sdkbiz", "73932", "7.3.9.32", sdkBiz)
        send("no openudid", "73932", "7.3.9.32", sdkBiz, includeOpen = false)
        send("no cdid", "73932", "7.3.9.32", sdkBiz, includeCdid = false)
        send("appbiz", "73932", "7.3.9.32", appBiz)
        send("old 72232 appbiz", "72232", "7.2.2.32", appBiz)
        send("no cookie", "73932", "7.3.9.32", sdkBiz, cookieIn = null)
    }
}
