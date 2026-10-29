package com.hgtv.star.core

import java.util.concurrent.TimeUnit
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

class LivePlaybackBurn2Test {
    private val host = "https://api5-normal-sinfonlineb.fqnovel.com"
    private val sdkBiz = """{"video_id":"7667513056330517529","biz_param":{"video_id_type":0,"need_all_video_definition":true,"video_platform":3}}"""

    private fun send(label: String, did: String, iid: String, cdid: String, openudid: String, cookie: String, branch2: Boolean) {
        val device = linkedMapOf("device_id" to did, "iid" to iid, "install_id" to iid, "cdid" to cdid, "openudid" to openudid, "device_brand" to "Xiaomi", "device_type" to "23127PN0CC", "os_version" to "14", "os_api" to "34", "version_name" to "7.3.9.32")
        val query = linkedMapOf(
            "iid" to iid, "device_id" to did, "ac" to "wifi", "channel" to "xiaomi_8662_64", "aid" to "8662",
            "app_name" to "novelread", "cdid" to cdid, "version_code" to "73932", "version_name" to "7.3.9.32",
            "device_platform" to "android", "os" to "android", "ssmix" to "a", "device_type" to "23127PN0CC",
            "device_brand" to "Xiaomi", "language" to "zh", "os_api" to "34", "os_version" to "14",
            "manifest_version_code" to "73932", "resolution" to "1200*2670", "dpi" to "460",
            "update_version_code" to "73932", "host_abi" to "arm64-v8a", "dragon_device_type" to "phone",
            "pv_player" to "73932", "compliance_status" to "0", "need_personal_recommend" to "1",
            "player_so_load" to "1", "is_android_pad_screen" to "0", "openudid" to openudid
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
        b.header("User-Agent", "com.phoenix.read/73932 (Linux; U; Android 14; zh_CN; 23127PN0CC; Build/UKQ1.230804.001; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)")
        b.header("Accept", "application/json")
        b.header("Cookie", cookie)
        b.post(RequestBody.create(MediaType.parse("application/json; charset=UTF-8"), sdkBiz))
        OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build().newCall(b.build()).execute().use { r ->
            val text = r.body()?.string().orEmpty()
            val code = if (text.isNotBlank()) JSONObject(text).optInt("code", -999) else -1
            println("[burn2] $label HTTP ${r.code()} ${text.length}B code=$code")
        }
    }

    @Test fun burn2() {
        assumeTrue("HONGGUO_LIVE", System.getenv("HONGGUO_LIVE") == "1")
        send("dev1 req1 b2", "1518897872551404", "1518897872768492", "80445479-0dfa-44d8-85a7-b37c3921e649", "b9a49bd6c1a55d28", "store-region=cn-gd; store-region-src=did; install_id=1518897872768492; ttreq=1\$51ed02f53de5a1aee15095a9b14563d9dd1f7a38", true)
        send("dev1 req2 b2", "1518897872551404", "1518897872768492", "80445479-0dfa-44d8-85a7-b37c3921e649", "b9a49bd6c1a55d28", "store-region=cn-gd; store-region-src=did; install_id=1518897872768492; ttreq=1\$51ed02f53de5a1aee15095a9b14563d9dd1f7a38", true)
        send("dev2 req1 b2", "4474385128492764", "4474385128496860", "427b7bd5-02a6-48d3-be13-9af736071521", "f9ea37f42d180231", "store-region=cn-gd; store-region-src=did; install_id=4474385128496860; ttreq=1\$399d5347042c09a52ff0841add8adbc8ffd07264", true)
        send("dev2 req2 b0", "4474385128492764", "4474385128496860", "427b7bd5-02a6-48d3-be13-9af736071521", "f9ea37f42d180231", "store-region=cn-gd; store-region-src=did; install_id=4474385128496860; ttreq=1\$399d5347042c09a52ff0841add8adbc8ffd07264", false)
    }
}
