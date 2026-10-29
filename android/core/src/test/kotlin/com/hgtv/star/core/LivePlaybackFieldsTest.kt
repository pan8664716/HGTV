package com.hgtv.star.core

import java.util.concurrent.TimeUnit
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

class LivePlaybackFieldsTest {
    private val host = "https://api5-normal-sinfonlineb.fqnovel.com"
    private val did = "3154971173098252"; private val iid = "3154971173102348"
    private val cdid = "70a1585e-b007-43af-bc0d-4f5c176ccbbf"; private val openudid = "15fcb0784fcb6042"
    private val cookie = "store-region=cn-gd; store-region-src=did; install_id=$iid; ttreq=1\$804c087461f3bfb97c16b232f9502d254ba9871c"
    private val device = linkedMapOf("device_id" to did, "iid" to iid, "install_id" to iid, "cdid" to cdid, "openudid" to openudid, "device_brand" to "Xiaomi", "device_type" to "23127PN0CC", "os_version" to "14", "os_api" to "34")

    private val full = linkedMapOf(
        "iid" to iid, "device_id" to did, "ac" to "wifi", "channel" to "xiaomi_8662_64", "aid" to "8662",
        "app_name" to "novelread", "cdid" to cdid, "version_code" to "73932", "version_name" to "7.3.9.32",
        "device_platform" to "android", "os" to "android", "ssmix" to "a", "device_type" to "23127PN0CC",
        "device_brand" to "Xiaomi", "language" to "zh", "os_api" to "34", "os_version" to "14",
        "manifest_version_code" to "73932", "resolution" to "1200*2670", "dpi" to "460",
        "update_version_code" to "73932", "host_abi" to "arm64-v8a", "dragon_device_type" to "phone",
        "pv_player" to "73932", "compliance_status" to "0", "need_personal_recommend" to "1",
        "player_so_load" to "1", "is_android_pad_screen" to "0", "openudid" to openudid
    )
    private val body = """{"video_id":"7667513056330517529","biz_param":{"video_id_type":0,"need_all_video_definition":true,"video_platform":3}}"""

    private fun send(label: String, query: Map<String, String>, withCookie: Boolean = true) {
        val signed = Signer(device = device).sign(host + "/novel/player/video_model/v1/", query, body.toByteArray())
        val b = Request.Builder().url(signed.url)
        signed.headers.forEach { (k, v) -> b.header(k, v) }
        b.header("User-Agent", "com.phoenix.read/73932 (Linux; U; Android 14; zh_CN; 23127PN0CC; Build/UKQ1.230804.001; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)")
        b.header("Accept", "application/json")
        if (withCookie) b.header("Cookie", cookie)
        b.post(RequestBody.create(MediaType.parse("application/json; charset=UTF-8"), body))
        OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build().newCall(b.build()).execute().use { r ->
            val text = r.body()?.string().orEmpty()
            val code = if (text.isNotBlank()) JSONObject(text).optInt("code", -999) else -1
            println("[fields] $label HTTP ${r.code()} ${text.length}B code=$code")
        }
    }

    @Test fun fields() {
        assumeTrue("HONGGUO_LIVE", System.getenv("HONGGUO_LIVE") == "1")
        send("full", full)
        for (key in listOf("host_abi","dragon_device_type","pv_player","compliance_status","need_personal_recommend","player_so_load","is_android_pad_screen","openudid","cdid")) {
            send("minus $key", full.filterKeys { it != key })
        }
        send("full nocookie", full, withCookie = false)
    }
}
