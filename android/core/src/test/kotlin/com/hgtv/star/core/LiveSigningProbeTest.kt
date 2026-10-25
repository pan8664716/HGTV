// 联网实测：默认跳过，只有 HONGGUO_LIVE=1 时才真的发请求。
// 运行：HONGGUO_LIVE=1 ./gradlew :core:test --tests 'com.hgtv.star.core.LiveSigningProbeTest' --info
package com.hgtv.star.core

import java.util.concurrent.TimeUnit
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveSigningProbeTest {
    private val videoApi = "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/"
    private val vid = System.getenv("HONGGUO_LIVE_VID")?.takeIf { it.matches(Regex("[0-9]{1,30}")) } ?: "7691249364097829913"
    private val seriesId = System.getenv("HONGGUO_LIVE_SERIES")?.takeIf { it.matches(Regex("[0-9]{1,30}")) } ?: "7691228619774905368"

    private fun payload(id: String) = """{"biz_param":{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$id"]}}""".toByteArray()

    /** 搜索 / 弹幕这类 App 接口共用的设备参数（App 版本 7.2.2.32）。 */
    private fun appValues() = LinkedHashMap(VendorConstants.device).apply {
        for (key in listOf("version_code", "manifest_version_code", "update_version_code", "pv_player")) this[key] = "72232"
        this["version_name"] = "7.2.2.32"
    }

    private fun post(signed: Signer.Request): Pair<Int, String> {
        val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()
        val builder = Request.Builder().url(signed.url)
        signed.headers.forEach { (k, v) -> builder.header(k, v) }
        signed.body?.let { builder.post(RequestBody.create(MediaType.parse("application/json; charset=UTF-8"), it)) }
        client.newCall(builder.build()).execute().use { response ->
            return response.code() to (response.body()?.string() ?: "")
        }
    }

    @Test fun liveSignedVideoModelIsAccepted() {
        assumeTrue("需要 HONGGUO_LIVE=1 才联网实测", System.getenv("HONGGUO_LIVE") == "1")
        val signer = Signer()
        val body = payload(vid)
        val signed = signer.sign(videoApi, VendorConstants.device, body)

        val query = signed.url.substringAfter('?')
        val ts = signed.headers.getValue("x-khronos").toInt()
        val branch = signer.branch(query, body, ts)
        println("[live] vid=$vid branch=$branch ts=$ts ticket=${signed.headers.getValue("x-ss-req-ticket")}")
        println("[live] gorgon=${signed.headers.getValue("x-gorgon")}")
        println("[live] helios=${signed.headers.getValue("x-helios")}")
        println("[live] medusa=${signed.headers.getValue("x-medusa")}")

        assertEquals("只发 branch 0", 0, branch)
        val (status, text) = post(signed)
        println("[live] HTTP $status ${text.length}B ${text.take(120)}")
        assertEquals(200, status)
        assertTrue("响应为空", text.isNotBlank())
        val json = JSONObject(text)
        assertEquals("签名被平台拒绝", 0, json.optInt("Code", json.optInt("code", -1)))
        val entry = json.optJSONObject("data")?.optJSONObject(vid) ?: json.optJSONObject("data")?.keys()?.asSequence()?.mapNotNull { json.optJSONObject("data")?.optJSONObject(it) }?.firstOrNull()
        assertTrue("video_model 缺失", entry != null)
    }

    @Test fun liveSignedDanmakuIsAccepted() {
        assumeTrue("需要 HONGGUO_LIVE=1 才联网实测", System.getenv("HONGGUO_LIVE") == "1")
        val signer = Signer()
        val body = """{"comment_source":601,"server_channel":1000,"group_id":"$vid","group_type":30,"comment_type":20,"sort":1,"business_param":{"book_id":"$seriesId","start_offset_time":0,"playlet_item_duration":90000,"need_danmaku_guide_type":[1,3,4,2]},"count":50,"cursor":"","aid":8662,"compliance_status":0}""".toByteArray()
        val signed = signer.sign("https://api5-normal-sinfonlineb.fqnovel.com/novel/commentapi/comment/list/$vid/v1/", appValues(), body)
        val headers = signed.headers.toMutableMap().apply {
            this["comment-source"] = "601"
            this["server-channel"] = "1000"
            this["x-ss-stub"] = ""
        }
        val query = signed.url.substringAfter('?')
        val ts = signed.headers.getValue("x-khronos").toInt()
        println("[live] danmaku branch=${signer.branch(query, body, ts)} ts=$ts")

        val (status, text) = post(Signer.Request(signed.url, headers, body))
        println("[live] danmaku HTTP $status ${text.length}B ${text.take(160)}")
        assertEquals(200, status)
        assertTrue("弹幕响应为空", text.isNotBlank())
        val json = JSONObject(text)
        assertEquals("签名被平台拒绝", 0, json.optInt("code", -1))
    }
}
