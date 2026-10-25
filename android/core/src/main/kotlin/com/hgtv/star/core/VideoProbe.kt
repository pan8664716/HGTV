package com.hgtv.star.core

import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 一次性探针：对每条候选路径，强制生成 branch 0 / 1 / 2 三种签名各打一次，
 * 用来定位「哪一种签名变体被服务端接受」。不参与应用构建。
 */
fun main() {
    val host = "https://api5-normal-sinfonlineb.fqnovel.com"
    val paths = listOf(
        "/novel/player/multi_video_model/preload/v1/",
        "/novel/player/multi_video_model/default/v1/",
        "/novel/player/multi_video_model/v1/",
    )
    val client = HgtvTls.configure(OkHttpClient.Builder())
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS).build()
    val signer = Signer()

    val repository = ContentRepository()
    val feed = repository.homeFeed()
    val first = feed.values.firstOrNull()?.items?.firstOrNull() ?: run { println("拿不到列表"); return }
    val detail = repository.detail(first.id)
    val vid = detail.episodes.firstOrNull() ?: run { println("拿不到分集"); return }
    println("测试剧集：${detail.series.title} vid=$vid\n")

    val payload = """{"biz_param":{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$vid"]}}""".toByteArray()

    /** 找一个 query 使 branch 等于 wanted（抖动 _rticket）。 */
    fun signForBranch(path: String, wanted: Int): Pair<Signer.Request, Int>? {
        val now = System.currentTimeMillis()
        val ts = (now / 1000).toInt()
        for (offset in 0..3000) {
            val ticket = now + offset
            val query = Signer.encode(VendorConstants.device + mapOf("ts" to ts.toString(), "_rticket" to ticket.toString()))
            if (signer.branch(query, payload, ts) == wanted) {
                // 用同一份 query 组装完整签名请求
                val url = "$host$path?$query"
                val random = (Math.random() * 65536).toInt()
                return signer.sign(url, VendorConstants.device, payload) to offset
            }
        }
        return null
    }

    for (path in paths) {
        println("=== $path ===")
        for (wanted in 0..2) {
            val found = signForBranch(path, wanted)
            if (found == null) { println("  branch=$wanted 找不到（跳过）"); continue }
            val (signed, offset) = found
            val real = signer.branch(signed.url.substringAfter('?'), payload, signed.headers.getValue("x-khronos").toInt())
            val builder = Request.Builder().url(signed.url)
            signed.headers.forEach { (k, v) -> builder.header(k, v) }
            builder.post(RequestBody.create(MediaType.parse("application/json; charset=UTF-8"),payload))
            val line = try {
                client.newCall(builder.build()).execute().use { response ->
                    val text = response.body()?.string() ?: ""
                    val code = runCatching { JSONObject(text).optInt("Code", JSONObject(text).optInt("code", -1)) }.getOrDefault(-1)
                    val msg = runCatching { JSONObject(text).optString("Message", JSONObject(text).optString("message", "")) }.getOrDefault("")
                    "HTTP ${response.code()}  code=$code  ${text.length}B  $msg"
                }
            } catch (e: Exception) { "异常：${e.message}" }
            println("  branch=$wanted(实际 $real, offset=$offset)  $line")
        }
        println()
    }
    repository.http.connectionPool().evictAll()
}
