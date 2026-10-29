package com.hgtv.star.core

import java.util.concurrent.TimeUnit
import kotlin.random.Random
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

class LivePlaybackFreshTest {
    private val host = "https://api5-normal-sinfonlineb.fqnovel.com"
    private val appBiz = """{"video_id":"7667513056330517529","biz_param":{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,use_server_dns":false,"video_platform":1024}}"""
    private val sdkBiz = """{"video_id":"7667513056330517529","biz_param":{"video_id_type":0,"need_all_video_definition":true,"video_platform":3}}"""

    private fun rid() = Random.nextLong(1_000_000_000_000_000L, 9_999_999_999_999_999L).toString()

    private fun send(label: String, did: String, iid: String, branch2: Boolean, body: String, withCookie: String? = null) {
        val device = linkedMapOf("device_id" to did, "iid" to iid, "install_id" to iid, "device_brand" to "Redmi", "device_type" to "25053RT47C", "os_version" to "16", "os_api" to "36")
        val query = linkedMapOf(
            "iid" to iid, "device_id" to did, "ac" to "wifi", "channel" to "update_64", "aid" to "8662",
            "app_name" to "novelread", "version_code" to "73932", "version_name" to "7.3.9.32",
            "device_platform" to "android", "os" to "android", "ssmix" to "a", "device_type" to "25053RT47C",
            "device_brand" to "Redmi", "language" to "zh", "os_api" to "36", "os_version" to "16",
            "manifest_version_code" to "73932", "resolution" to "1280*2772", "dpi" to "520",
            "update_version_code" to "73932", "host_abi" to "arm64-v8a", "dragon_device_type" to "phone",
            "pv_player" to "73932", "compliance_status" to "0", "need_personal_recommend" to "1",
            "player_so_load" to "1", "is_android_pad_screen" to "0"
        )
        val url: String
        val headers: Map<String, String>
        if (branch2) {
            val signed = PassportSigner(device = device).sign(host + "/novel/player/video_model/v1/", query, body.toByteArray())
            url = signed.url; headers = signed.headers
        } else {
            val signed = Signer(device = device).sign(host + "/novel/player/video_model/v1/", query, body.toByteArray())
            url = signed.url; headers = signed.headers
        }
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        b.header("User-Agent", "com.phoenix.read/73932 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)")
        b.header("Accept", "application/json")
        if (withCookie != null) b.header("Cookie", withCookie)
        b.post(RequestBody.create(MediaType.parse("application/json; charset=UTF-8"), body))
        OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build().newCall(b.build()).execute().use { r ->
            val text = r.body()?.string().orEmpty()
            val code = if (text.isNotBlank()) JSONObject(text).optInt("code", -999) else -1
            println("[fresh] $label HTTP ${r.code()} ${text.length}B code=$code")
            if (text.length > 500) println("[fresh] $label body=" + text.take(160))
        }
    }

    @Test fun fresh() {
        assumeTrue("HONGGUO_LIVE", System.getenv("HONGGUO_LIVE") == "1")
        val a = rid() to rid()
        send("b2 fresh A", a.first, a.second, true, sdkBiz)
        send("b2 same A again", a.first, a.second, true, sdkBiz)
        val c = rid() to rid()
        send("b0 fresh C", c.first, c.second, false, sdkBiz)
        val d = rid() to rid()
        send("b2 fresh D appbiz", d.first, d.second, true, appBiz)
        val e = rid() to rid()
        send("b0 fresh E appbiz", e.first, e.second, false, appBiz)
    }
}
