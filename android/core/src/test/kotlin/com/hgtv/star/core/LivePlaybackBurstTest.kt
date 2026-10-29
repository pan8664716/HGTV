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

class LivePlaybackBurstTest {
    private val host = "https://api5-normal-sinfonlineb.fqnovel.com"
    private val did = "4491977313750571"; private val iid = "4491977313754667"
    private val cookie = "store-region=cn-gd; store-region-src=did; install_id=4491977313754667; ttreq=1\$3b1ac72b297a9db8daf7ec4945b9c9877afe931a"
    private val body = """{"video_id":"7667513056330517529","biz_param":{"video_id_type":0,"need_all_video_definition":true,"video_platform":3}}"""
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    @Test fun burst() {
        assumeTrue("HONGGUO_LIVE", System.getenv("HONGGUO_LIVE") == "1")
        for (i in 1..8) {
            val device = linkedMapOf("device_id" to did, "iid" to iid, "install_id" to iid, "device_brand" to "Redmi", "device_type" to "25053RT47C", "os_version" to "16", "os_api" to "36")
            val query = linkedMapOf(
                "iid" to iid, "device_id" to did, "ac" to "wifi", "channel" to "update_64", "aid" to "8662",
                "app_name" to "novelread", "version_code" to "73532", "version_name" to "7.3.5.32",
                "device_platform" to "android", "os" to "android", "ssmix" to "a", "device_type" to "25053RT47C",
                "device_brand" to "Redmi", "language" to "zh", "os_api" to "36", "os_version" to "16",
                "manifest_version_code" to "73532", "resolution" to "1280*2772", "dpi" to "520",
                "update_version_code" to "73532", "host_abi" to "arm64-v8a", "dragon_device_type" to "phone",
                "pv_player" to "73532", "compliance_status" to "0", "need_personal_recommend" to "1",
                "player_so_load" to "1", "is_android_pad_screen" to "0"
            )
            val signed = Signer(device = device).sign(host + "/novel/player/video_model/v1/", query, body.toByteArray())
            val b = Request.Builder().url(signed.url)
            signed.headers.forEach { (k, v) -> b.header(k, v) }
            b.header("User-Agent", "com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)")
            b.header("Accept", "application/json")
            b.header("Cookie", cookie)
            b.post(body.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            client.newCall(b.build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                val code = if (text.isNotBlank()) JSONObject(text).optInt("code", -999) else -1
                println("[burst] #$i HTTP ${r.code} ${text.length}B code=$code")
            }
        }
    }
}
