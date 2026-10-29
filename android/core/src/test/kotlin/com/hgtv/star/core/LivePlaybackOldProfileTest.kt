package com.hgtv.star.core

import java.util.concurrent.TimeUnit
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

class LivePlaybackOldProfileTest {
    private val host = "https://api5-normal-sinfonlineb.fqnovel.com"
    private val did = "4491977313750571"; private val iid = "4491977313754667"
    private val cdid = "44f5a2da-4c28-4bce-9b2e-6092bca2a84b"; private val openudid = "72dc9e9570884eec"
    private val cookie = "store-region=cn-gd; store-region-src=did; install_id=4491977313754667; ttreq=1\$3b1ac72b297a9db8daf7ec4945b9c9877afe931a"
    private val sdkBiz = """{"video_id":"7667513056330517529","biz_param":{"video_id_type":0,"need_all_video_definition":true,"video_platform":3}}"""
    private val appBiz = """{"video_id":"7667513056330517529","biz_param":{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024}}"""

    private fun send(label: String, vc: String, vn: String, uaVc: String, body: String, includeCdid: Boolean, includeOpen: Boolean, cookieIn: String?) {
        val device = linkedMapOf("device_id" to did, "iid" to iid, "install_id" to iid, "cdid" to cdid, "openudid" to openudid, "device_brand" to "Redmi", "device_type" to "25053RT47C", "os_version" to "16", "os_api" to "36", "version_name" to vn)
        val query = linkedMapOf(
            "iid" to iid, "device_id" to did, "ac" to "wifi", "channel" to "update_64", "aid" to "8662",
            "app_name" to "novelread", "version_code" to vc, "version_name" to vn,
            "device_platform" to "android", "os" to "android", "ssmix" to "a", "device_type" to "25053RT47C",
            "device_brand" to "Redmi", "language" to "zh", "os_api" to "36", "os_version" to "16",
            "manifest_version_code" to vc, "resolution" to "1280*2772", "dpi" to "520",
            "update_version_code" to vc, "host_abi" to "arm64-v8a", "dragon_device_type" to "phone",
            "pv_player" to vc, "compliance_status" to "0", "need_personal_recommend" to "1",
            "player_so_load" to "1", "is_android_pad_screen" to "0"
        )
        if (includeCdid) query["cdid"] = cdid
        if (includeOpen) query["openudid"] = openudid
        val signed = Signer(device = device).sign(host + "/novel/player/video_model/v1/", query, body.toByteArray())
        val b = Request.Builder().url(signed.url)
        signed.headers.forEach { (k, v) -> b.header(k, v) }
        b.header("User-Agent", "com.phoenix.read/$uaVc (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)")
        b.header("Accept", "application/json")
        cookieIn?.let { b.header("Cookie", it) }
        b.post(RequestBody.create(MediaType.parse("application/json; charset=UTF-8"), body))
        OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build().newCall(b.build()).execute().use { r ->
            val text = r.body()?.string().orEmpty()
            val code = if (text.isNotBlank()) JSONObject(text).optInt("code", -999) else -1
            println("[old] $label HTTP ${r.code()} ${text.length}B code=$code")
        }
    }

    @Test fun old() {
        assumeTrue("HONGGUO_LIVE", System.getenv("HONGGUO_LIVE") == "1")
        send("73532 sdkbiz full", "73532", "7.3.5.32", "73532", sdkBiz, true, true, cookie)
        send("73532 sdkbiz nocdid", "73532", "7.3.5.32", "73532", sdkBiz, false, false, cookie)
        send("72232 appbiz full", "72232", "7.2.2.32", "71332", appBiz, true, true, cookie)
        send("72232 appbiz nocdid", "72232", "7.2.2.32", "71332", appBiz, false, false, cookie)
        send("73532 appbiz full", "73532", "7.3.5.32", "71332", appBiz, true, true, cookie)
        send("73532 appbiz nocookie", "73532", "7.3.5.32", "71332", appBiz, true, true, null)
    }
}
