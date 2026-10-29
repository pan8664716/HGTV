package com.hgtv.star.core

import java.util.concurrent.TimeUnit
import kotlin.random.Random
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveCooldownTest {
    private val host = "https://api5-normal-sinfonlineb.fqnovel.com"
    private val body = """{"video_id":"7667513056330517529","biz_param":{"video_id_type":0,"need_all_video_definition":true,"video_platform":3}}"""
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    private fun call(label: String, did: String, iid: String, cookie: String?) {
        val device = linkedMapOf("device_id" to did, "iid" to iid, "install_id" to iid, "device_brand" to "Xiaomi", "device_type" to "23127PN0CC", "os_version" to "14", "os_api" to "34")
        val query = linkedMapOf(
            "iid" to iid, "device_id" to did, "ac" to "wifi", "channel" to "xiaomi_8662_64", "aid" to "8662",
            "app_name" to "novelread", "version_code" to "73932", "version_name" to "7.3.9.32",
            "device_platform" to "android", "os" to "android", "ssmix" to "a", "device_type" to "23127PN0CC",
            "device_brand" to "Xiaomi", "language" to "zh", "os_api" to "34", "os_version" to "14",
            "manifest_version_code" to "73932", "resolution" to "1200*2670", "dpi" to "460",
            "update_version_code" to "73932", "host_abi" to "arm64-v8a", "dragon_device_type" to "phone",
            "pv_player" to "73932", "compliance_status" to "0", "need_personal_recommend" to "1",
            "player_so_load" to "1", "is_android_pad_screen" to "0"
        )
        val signed = Signer(device = device).sign(host + "/novel/player/video_model/v1/", query, body.toByteArray())
        val b = Request.Builder().url(signed.url)
        signed.headers.forEach { (k, v) -> b.header(k, v) }
        b.header("User-Agent", "com.phoenix.read/73932 (Linux; U; Android 14; zh_CN; 23127PN0CC; Build/UKQ1.230804.001; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)")
        b.header("Accept", "application/json")
        cookie?.let { b.header("Cookie", it) }
        b.post(body.toRequestBody("application/json; charset=UTF-8".toMediaType()))
        client.newCall(b.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val code = if (text.isNotBlank()) JSONObject(text).optInt("code", -999) else -1
            println("[cooldown] $label HTTP ${r.code} ${text.length}B code=$code")
        }
    }

    @Test fun cooldown() {
        assumeTrue("HONGGUO_LIVE", System.getenv("HONGGUO_LIVE") == "1")
        val r1 = Random.nextLong(1_000_000_000_000_000L, 9_999_999_999_999_999L).toString()
        val r2 = Random.nextLong(1_000_000_000_000_000L, 9_999_999_999_999_999L).toString()
        call("random", r1, r2, null)
        call("registered", "3911435176164651", "3911435175382331", "store-region=cn-gd; store-region-src=did; install_id=3911435175382331; ttreq=1\$913bb5a187044353e5818a1f7856bee63f006226")
    }
}
