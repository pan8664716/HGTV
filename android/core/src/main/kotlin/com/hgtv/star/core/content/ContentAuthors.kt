// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.json.JSONObject

internal fun ContentRepository.authorOfImpl(id: String): String =
    authorsOfImpl(listOf(id))[id].orEmpty()

/**
 * 批量取作者：book_id 支持逗号分隔，一次最多 MULTI_DETAIL_LIMIT 个。
 *
 * 首页 / 榜单 / 分类的列表接口都不带作者，只有详情页才有；为了让卡片也能显示，
 * 界面在列表落地之后调一次这个方法（见 TvMainActivity.loadAuthors），
 * 一次请求补齐一整屏，而不是每张卡各发一次。
 *
 * 结果按 id 缓存（包括空结果）：详情页反复进出时不重复请求；
 * 作者 / 版权方基本不会变，缓存 300 条足够覆盖一次浏览。
 */
internal fun ContentRepository.authorsOfImpl(ids: List<String>): Map<String,String> {
    val wanted=ids.filter { it.matches(Regex("[0-9]{1,30}")) }.distinct()
    if(wanted.isEmpty()) return emptyMap()
    val result=linkedMapOf<String,String>()
    val missing=mutableListOf<String>()
    synchronized(authorCacheStore) {
        wanted.forEach { id ->
            val cached=authorCacheStore[id]
            if(cached==null) missing.add(id)
            else if(cached.isNotBlank()) result[id]=cached
        }
    }
    if(missing.isEmpty()) return result
    missing.chunked(MULTI_DETAIL_LIMIT).forEach { chunk ->
        runCatching {
            val values=appValues()
            values.putAll(mapOf("book_id" to chunk.joinToString(","),"book_type" to "2"))
            val signed=signer.sign("https://${accountHost()}/reading/bookapi/multi-detail/v",values)
            val raw=text(signed.url,signed.withSession())
            if(raw.isBlank()) return@runCatching
            val rows=JSONObject(raw).optJSONArray("data") ?: return@runCatching
            for(index in 0 until rows.length()) {
                val row=rows.optJSONObject(index) ?: continue
                val id=row.str("book_id").ifEmpty { row.str("series_id") }
                val name=row.str("author").trim().ifEmpty { row.str("source").trim() }
                if(id.isNotBlank()) {
                    if(name.isNotBlank()) result[id]=name
                    synchronized(authorCacheStore) {
                        if(authorCacheStore.size>=authorCacheLimit) {
                            authorCacheStore.keys.firstOrNull()?.let(authorCacheStore::remove)
                        }
                        authorCacheStore[id]=name
                    }
                }
            }
        }
    }
    return result
}
