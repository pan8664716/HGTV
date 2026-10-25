// SPDX-License-Identifier: GPL-3.0-only.
package com.hgtv.star.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一次性探针：把播放接口返回的 `video_list` 原样摊开，看每个清晰度到底提供哪些编码。
 *
 * 目的只有一个：确认「能不能不用 HEVC」——同一清晰度是否有 h264 变体、
 * 换清晰度是否能换来 h264、以及接口是否本来就只给 HEVC。
 */

private fun reflect(repository: ContentRepository,name: String,count: Int): java.lang.reflect.Method =
    ContentRepository::class.java.declaredMethods.first { it.name==name && it.parameterCount==count }
        .apply { isAccessible=true }

@Suppress("UNCHECKED_CAST")
fun main(args: Array<String>) {
    val repository=ContentRepository()
    if(repository.registerDevice()==null) { println("设备注册失败"); return }
    val wanted=args.firstOrNull()?.toIntOrNull() ?: 12
    val modelMethod=reflect(repository,"videoModel",1)
    val textMethod=reflect(repository,"text",2)
    val feed=runCatching { repository.homeFeed() }.getOrElse { println("首页失败：${it.message}"); return }
    val series=feed.values.flatMap { it.items }.distinctBy { it.id }
    val codecCount=mutableMapOf<String,Int>()
    var shown=0
    for(item in series) {
        if(shown>=wanted) break
        val detail=runCatching { repository.detail(item.id) }.getOrNull() ?: continue
        val vid=detail.episodes.firstOrNull() ?: continue
        val model=runCatching { modelMethod.invoke(repository,vid) as JSONObject }.getOrNull() ?: continue
        val fallbackRaw=model.opt("fallback_api") ?: continue
        val fallbackUrl=when(fallbackRaw) {
            is JSONObject -> fallbackRaw.str("fallback_api")
            is JSONArray -> fallbackRaw.optString(0)
            else -> runCatching { JSONObject(fallbackRaw.toString()).str("fallback_api") }.getOrDefault(fallbackRaw.toString())
        }
        if(!fallbackUrl.startsWith("https://")) continue
        val raw=runCatching { textMethod.invoke(repository,fallbackUrl,null) as String }.getOrNull() ?: continue
        val data=runCatching { JSONObject(raw).optJSONObject("video_info")?.optJSONObject("data") }.getOrNull() ?: continue
        val list=data.opt("video_list") ?: continue
        val rows=mutableListOf<JSONObject>()
        when(list) {
            is JSONObject -> list.keys().forEach { key -> list.optJSONObject(key)?.let { rows+=JSONObject(it.toString()).put("_key",key) } }
            is JSONArray -> (0 until list.length()).mapNotNull { list.optJSONObject(it) }.forEach { rows+=it }
        }
        if(rows.isEmpty()) continue
        shown++
        println("${detail.series.title}  (vid=$vid)")
        rows.forEach { row ->
            val codec=row.str("codec_type").ifEmpty { "?" }
            codecCount[codec]=(codecCount[codec] ?: 0)+1
            val quality=row.str("quality_desc").ifEmpty { row.str("quality") }
            val meta=row.optJSONObject("video_meta")
            val w=row.optInt("vwidth",meta?.optInt("vwidth") ?: 0)
            val h=row.optInt("vheight",meta?.optInt("vheight") ?: 0)
            val bitrate=row.optInt("bitrate",meta?.optInt("bitrate") ?: 0)
            val size=row.optLong("size",meta?.optLong("size") ?: 0)
            val url=row.str("main_url")
            val seed=data.str("key_seed")
            val actual=runCatching {
                var real=if(seed.isNotEmpty()) MediaCrypto.decryptUrl(url,decode64(seed)) else url
                if(!real.startsWith("http")) real=url
                val key=row.str("spade_a").takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey)
                RemoteVideo(repository.http,StreamInfo(real,key,quality)).use { remote ->
                    remote.prepare()
                    val head=remote.read(0,minOf(remote.total,1024L*1024).toInt())
                    val stsd=runCatching {
                        val at=(0..head.size-4).firstOrNull { String(head,it,4,Charsets.US_ASCII)=="stsd" }
                        if(at==null) "无stsd" else String(head,at+16,4,Charsets.US_ASCII)
                    }.getOrDefault("?")
                    val vide=(0..head.size-4).count { String(head,it,4,Charsets.US_ASCII)=="vide" }
                    "stsd=$stsd vide轨=$vide"
                }
            }.getOrElse { "err:${it.javaClass.simpleName}" }
            println(String.format("     %-8s %-6s %-8s %4dx%-4d %6d kbps %7.1f MB url=%s",
                row.optString("_key",""),quality,codec,w,h,bitrate,size/1024.0/1024.0,
                if(url.isEmpty()) "无" else "有")+"  实际=$actual")
        }
        println()
    }
    println("编码统计：$codecCount")
}
