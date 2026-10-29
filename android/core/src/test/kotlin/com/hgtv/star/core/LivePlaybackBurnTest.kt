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

class LivePlaybackBurnTest {
    private val host = "https://api5-normal-sinfonlineb.fqnovel.com"
    private val sdkBiz = """{"video_id":"7667513056330517529","biz_param":{"video_id_type":0,"need_all_video_definition":true,"video_platform":3}}"""

    private fun send(label: String, did: String, iid: String, cookie: String, branch2: Boolean) {
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
        val url: String; val headers: Map<String, String>
        if (branch2) {
            val s = PassportSigner(device = device).sign(host + "/novel/player/video_model/v1/", query, sdkBiz.toByteArray())
            url = s.url; headers = s.headers
        } else {
            val s = Signer(device = device).sign(host + "/novel/player/video_model/v1/", query, sdkBiz.toByteArray())
            url = s.url; headers = s.headers
        }
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        b.header("User-Agent", "com.phoenix.read/73932 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)")
        b.header("Accept", "application/json")
        b.header("Cookie", cookie)
        b.post(sdkBiz.toRequestBody("application/json; charset=UTF-8".toMediaType()))
        OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build().newCall(b.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val code = if (text.isNotBlank()) JSONObject(text).optInt("code", -999) else -1
            println("[burn] $label HTTP ${r.code} ${text.length}B code=$code")
        }
    }

    @Test fun burn() {
        assumeTrue("HONGGUO_LIVE", System.getenv("HONGGUO_LIVE") == "1")
        val did1 = System.getenv("D1"); val iid1 = System.getenv("I1"); val ck1 = System.getenv("C1")
        val did2 = System.getenv("D2"); val iid2 = System.getenv("I2"); val ck2 = System.getenv("C2")
        send("dev1 req1", did1, iid1, ck1, true)
        send("dev1 req2", did1, iid1, ck1, true)
        send("dev1 req3", did1, iid1, ck1, true)
        send("dev2 req1", did2, iid2, ck2, true)
        send("dev2 req2 b0", did2, iid2, ck2, false)
    }
}
