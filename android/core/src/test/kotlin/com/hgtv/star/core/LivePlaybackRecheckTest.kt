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

class LivePlaybackRecheckTest {
    private val host = "https://api5-normal-sinfonlineb.fqnovel.com"
    private val body = """{"video_id":"7667513056330517529","biz_param":{"video_id_type":0,"need_all_video_definition":true,"video_platform":3}}"""

    private fun send(label: String, did: String, iid: String, cookie: String?) {
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
        val signed = Signer(device = device).sign(host + "/novel/player/video_model/v1/", query, body.toByteArray())
        val b = Request.Builder().url(signed.url)
        signed.headers.forEach { (k, v) -> b.header(k, v) }
        b.header("User-Agent", "com.phoenix.read/73932 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)")
        b.header("Accept", "application/json")
        cookie?.let { b.header("Cookie", it) }
        b.post(body.toRequestBody("application/json; charset=UTF-8".toMediaType()))
        OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build().newCall(b.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val code = if (text.isNotBlank()) JSONObject(text).optInt("code", -999) else -1
            println("[recheck] $label HTTP ${r.code} ${text.length}B code=$code")
        }
    }

    @Test fun recheck() {
        assumeTrue("HONGGUO_LIVE", System.getenv("HONGGUO_LIVE") == "1")
        send("burn dev1 (252260477076816)", "252260477076816", "252260477080912", "store-region=cn-gd; store-region-src=did; install_id=252260477080912; ttreq=1\$51326b230bf019b67ed9232810eebaa93bbce905")
        send("freshdev (4140133590241003)", "4140133590241003", "4140133590245099", "store-region=cn-gd; store-region-src=did; install_id=4140133590245099; ttreq=1\$7e6a36a305802a1da644af3e69a99af240f0692b")
        send("sdk dev (3154971173098252)", "3154971173098252", "3154971173102348", "store-region=cn-gd; store-region-src=did; install_id=3154971173102348; ttreq=1\$804c087461f3bfb97c16b232f9502d254ba9871c")
    }
}
