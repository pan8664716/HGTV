package com.hgtv.star.core

import java.util.concurrent.TimeUnit
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

class LivePlaybackBranch2Test {
    private val host = "https://api5-normal-sinfonlinea.fqnovel.com"
    private val device = linkedMapOf(
        "device_id" to "3154971173098252", "iid" to "3154971173102348", "install_id" to "3154971173102348",
        "cdid" to "70a1585e-b007-43af-bc0d-4f5c176ccbbf", "openudid" to "15fcb0784fcb6042",
        "device_brand" to "Xiaomi", "device_type" to "23127PN0CC", "os_version" to "14", "os_api" to "34",
        "version_name" to "7.3.9.32"
    )
    private val query = linkedMapOf(
        "iid" to "3154971173102348", "device_id" to "3154971173098252", "ac" to "wifi",
        "channel" to "xiaomi_8662_64", "aid" to "8662", "app_name" to "novelread",
        "cdid" to "70a1585e-b007-43af-bc0d-4f5c176ccbbf", "version_code" to "73932", "version_name" to "7.3.9.32",
        "device_platform" to "android", "os" to "android", "ssmix" to "a", "device_type" to "23127PN0CC",
        "device_brand" to "Xiaomi", "language" to "zh", "os_api" to "34", "os_version" to "14",
        "manifest_version_code" to "73932", "resolution" to "1200*2670", "dpi" to "460",
        "update_version_code" to "73932", "host_abi" to "arm64-v8a", "dragon_device_type" to "phone",
        "pv_player" to "73932", "compliance_status" to "0", "need_personal_recommend" to "1",
        "player_so_load" to "1", "is_android_pad_screen" to "0", "openudid" to "15fcb0784fcb6042"
    )
    private val cookie = "store-region=cn-gd; store-region-src=did; install_id=3154971173102348; ttreq=1\$804c087461f3bfb97c16b232f9502d254ba9871c"

    @Test fun playbackSignedWithBranch2() {
        assumeTrue("需要 HONGGUO_LIVE=1", System.getenv("HONGGUO_LIVE") == "1")
        val body = """{"video_id":"7667513056330517529","biz_param":{"video_id_type":0,"need_all_video_definition":true,"video_platform":3}}""".toByteArray()
        for (variant in listOf("passport", "content")) {
            val url: String
            val headers: Map<String, String>
            if (variant == "passport") {
                val signed = PassportSigner(device = device).sign(host + "/novel/player/video_model/v1/", query, body)
                url = signed.url; headers = signed.headers
            } else {
                val signed = Signer(device = device).sign(host + "/novel/player/video_model/v1/", query, body)
                url = signed.url; headers = signed.headers
            }
            val builder = Request.Builder().url(url)
            headers.forEach { (k, v) -> builder.header(k, v) }
            builder.header("User-Agent", "com.phoenix.read/73932 (Linux; U; Android 14; zh_CN; 23127PN0CC; Build/UKQ1.230804.001; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)")
            builder.header("Accept", "application/json")
            builder.header("Cookie", cookie)
            builder.post(RequestBody.create(MediaType.parse("application/json; charset=UTF-8"), body))
            OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build().newCall(builder.build()).execute().use { r ->
                val text = r.body()?.string().orEmpty()
                println("[live-playback:$variant] HTTP ${r.code()} ${text.length}B ${text.take(300)}")
                if (text.isNotBlank()) println("[live-playback:$variant] code=" + JSONObject(text).optInt("code", -1))
            }
        }
    }
}
