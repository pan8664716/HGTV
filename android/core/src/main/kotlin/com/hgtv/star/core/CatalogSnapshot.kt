// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * First-page metadata only; signed playback URLs and account data never go to disk.
 *
 * 首页有四个「热播…」剧架（对应四个热播榜），因此这份快照按**榜单**分别存一份，
 * 键是 `RankBoard.storedValue`（见 `Library.cacheHome` / `cachedHome`）。
 */
object CatalogSnapshot {
    private const val MAX_AGE=7*24*60*60*1000L
    fun encode(items: List<Series>,hasMore: Boolean,now: Long): String = JSONObject()
        .put("savedAt",now).put("hasMore",hasMore)
        .put("items",JSONArray(items.take(30).map { it.copy(description=it.description.take(1500)).toJson() })).toString()
    fun decode(value: String,now: Long): CatalogPage? = runCatching {
        require(value.length<=200_000)
        val o=JSONObject(value); require(now-o.getLong("savedAt") in 0..MAX_AGE)
        val rows=o.getJSONArray("items"); require(rows.length() in 1..30)
        val items=(0 until rows.length()).map { Series.fromJson(rows.getJSONObject(it)) }
        require(items.all { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() })
        CatalogPage(items.distinctBy { it.id },o.getBoolean("hasMore"))
    }.getOrNull()
}
