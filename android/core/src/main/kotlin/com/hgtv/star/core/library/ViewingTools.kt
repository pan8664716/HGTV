// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.json.JSONArray
import org.json.JSONObject

/** 一条搜索历史：只有关键词。顶部那两个「漫剧 / 短剧」开关去掉之后，搜索本身不再分类型。 */
data class RecentSearch(val query: String)

object SearchHistory {
    /**
     * 搜索历史只记关键词：顶部那两个「漫剧 / 短剧」开关已经去掉，同一个词不再按类型各记一条。
     *
     * 解码**兼容旧记录**——老版本写的是 `{"query":…,"type":"comic"}`，多出来的 `type` 直接忽略；
     * 编码只写 `query`，旧版本读新记录时缺 `type` 会丢掉那一条，这是可接受的单向降级。
     */
    fun remember(existing: List<RecentSearch>, query: String): List<RecentSearch> {
        val clean=query.trim()
        if(clean.isEmpty() || clean.length>80) return existing
        val entry=RecentSearch(clean)
        return (listOf(entry)+existing.filterNot { it==entry }).take(20)
    }
    fun decode(value: String): List<RecentSearch> = runCatching {
        val rows=JSONArray(value)
        (0 until rows.length()).mapNotNull { i ->
            val row=rows.optJSONObject(i) ?: return@mapNotNull null
            val query=row.optString("query").trim()
            if(query.isEmpty() || query.length>80) null else RecentSearch(query)
        }.distinct().take(20)
    }.getOrDefault(emptyList())
    fun encode(rows: List<RecentSearch>) = JSONArray(rows.map { JSONObject().put("query",it.query) }).toString()
}

object EpisodeTarget {
    /** One-based user input to a zero-based episode index; never silently clamp it. */
    fun parse(input: String, count: Int): Int? {
        val clean=input.trim()
        if(!clean.matches(Regex("[0-9]{1,6}"))) return null
        return clean.toIntOrNull()?.takeIf { it in 1..count }?.minus(1)
    }
}
