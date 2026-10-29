package com.hgtv.star.core

import java.util.concurrent.TimeUnit
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveRegComboTest {
    private val host = "https://api5-normal-sinfonlineb.fqnovel.com"
    private val body = """{"video_id":"7667513056330517529","biz_param":{"video_id_type":0,"need_all_video_definition":true,"video_platform":3}}"""

    private fun send(label: String, did: String, iid: String, cdid: String, openudid: String, cookie: String, brand: String, model: String, osv: String, osapi: Int, res: String, dpi: Int, channel: String, vc: String, vn: String) {
        val device = linkedMapOf("device_id" to did, "iid" to iid, "install_id" to iid, "cdid" to cdid, "openudid" to openudid, "device_brand" to brand, "device_type" to model, "os_version" to osv, "os_api" to osapi.toString(), "version_name" to vn)
        val query = linkedMapOf(
            "iid" to iid, "device_id" to did, "ac" to "wifi", "channel" to channel, "aid" to "8662",
            "app_name" to "novelread", "cdid" to cdid, "version_code" to vc, "version_name" to vn,
            "device_platform" to "android", "os" to "android", "ssmix" to "a", "device_type" to model,
            "device_brand" to brand, "language" to "zh", "os_api" to osapi.toString(), "os_version" to osv,
            "manifest_version_code" to vc, "resolution" to res, "dpi" to dpi.toString(),
            "update_version_code" to vc, "host_abi" to "arm64-v8a", "dragon_device_type" to "phone",
            "pv_player" to vc, "compliance_status" to "0", "need_personal_recommend" to "1",
            "player_so_load" to "1", "is_android_pad_screen" to "0", "openudid" to openudid
        )
        val signed = Signer(device = device).sign(host + "/novel/player/video_model/v1/", query, body.toByteArray())
        val b = Request.Builder().url(signed.url)
        signed.headers.forEach { (k, v) -> b.header(k, v) }
        b.header("User-Agent", "com.phoenix.read/$vc (Linux; U; Android $osv; zh_CN; $model; Build/UKQ1.230804.001; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)")
        b.header("Accept", "application/json")
        b.header("Cookie", cookie)
        b.post(RequestBody.create(MediaType.parse("application/json; charset=UTF-8"), body))
        OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build().newCall(b.build()).execute().use { r ->
            val text = r.body()?.string().orEmpty()
            val code = if (text.isNotBlank()) JSONObject(text).optInt("code", -999) else -1
            println("[combo] $label HTTP ${r.code()} ${text.length}B code=$code")
        }
    }

    @Test fun combo() {
        assumeTrue("HONGGUO_LIVE", System.getenv("HONGGUO_LIVE") == "1")
        send("https://log.snssdk.com 73932", "199483918948923", "199483918953019", "1029be91-9b6c-4421-a015-6e7a5f0a5418", "188cdabf909342e7", "store-region=cn-gd; store-region-src=did; install_id=199483918953019; ttreq=1\$f222d38f39fe6a983ae9ec46ce5c0753833886de", "Xiaomi", "23127PN0CC", "14", 34, "1200*2670", 460, "xiaomi_8662_64", "73932", "7.3.9.32")
        send("https://log5-applog-lq.fqnovel.com 73532", "3893842988773232", "3893842988777328", "61f54530-f0bc-48d4-9b1f-8fe228d39adf", "f90e6a18c4ef443f", "store-region=cn-gd; store-region-src=did; install_id=3893842988777328; ttreq=1\$e1d5b4f5c38931e47a6747f138b05d787d9dac97", "Xiaomi", "23127PN0CC", "14", 34, "1200*2670", 460, "xiaomi_8662_64", "73532", "7.3.5.32")
        send("https://log5-applog-lq.fqnovel.com 73932", "2556836849431452", "516143268498220", "754e24db-56bd-46dd-8344-55f5720810c5", "7a810339d7994fc7", "store-region=cn-gd; store-region-src=did; install_id=516143268498220; ttreq=1\$03dfa37930815c2447e8efe31b89600e2e80e5cd", "Xiaomi", "23127PN0CC", "14", 34, "1200*2670", 460, "xiaomi_8662_64", "73932", "7.3.9.32")
    }
}
