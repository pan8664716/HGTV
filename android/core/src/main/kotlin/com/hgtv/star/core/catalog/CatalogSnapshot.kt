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
    internal const val MAX_AGE_MS=7*24*60*60*1000L
    private const val DEFAULT_MAX_ITEMS=30
    private const val MAX_DECODE_ITEMS=100
    private const val DEFAULT_MAX_VALUE_CHARS=200_000
    fun encode(
        items: List<Series>,
        hasMore: Boolean,
        now: Long,
        ranking: ComicRanking? = null,
        maxItems: Int = DEFAULT_MAX_ITEMS,
    ): String = JSONObject()
        .put("savedAt",now).put("hasMore",hasMore)
        .put("items",JSONArray(items.take(maxItems.coerceIn(1,MAX_DECODE_ITEMS)).map {
            it.copy(description=it.description.take(1500)).toJson()
        }))
        .apply {
            ranking?.let { value ->
                put("ranking",JSONObject()
                    .put("totalPages",value.totalPages)
                    .put("updatedText",value.updatedText)
                    .put("positions",JSONObject().apply {
                        value.positions.forEach { (id,position) ->
                            put(id,JSONObject().put("heat",position.heat).apply {
                                position.rank?.let { put("rank",it) }
                            })
                        }
                    }))
            }
        }
        .toString()
    fun decode(
        value: String,
        now: Long,
        maxValueChars: Int = DEFAULT_MAX_VALUE_CHARS,
    ): CatalogPage? = runCatching {
        require(value.length<=maxValueChars)
        val o=JSONObject(value); require(now-o.getLong("savedAt") in 0..MAX_AGE_MS)
        val rows=o.getJSONArray("items"); require(rows.length() in 1..MAX_DECODE_ITEMS)
        val items=(0 until rows.length()).map { Series.fromJson(rows.getJSONObject(it)) }
        require(items.all { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() })
        val ranking=o.optJSONObject("ranking")?.let { value ->
            val positions=value.optJSONObject("positions")?.let { rowsById ->
                rowsById.keys().asSequence().mapNotNull { id ->
                    rowsById.optJSONObject(id)?.let { row ->
                        id to RankPosition(
                            row.optInt("rank",0).takeIf { it>0 },
                            row.optString("heat"),
                        )
                    }
                }.toMap()
            }.orEmpty()
            ComicRanking(
                positions=positions,
                totalPages=value.optInt("totalPages",0),
                updatedText=value.optString("updatedText"),
            )
        }
        CatalogPage(items.distinctBy { it.id },o.getBoolean("hasMore"),ranking)
    }.getOrNull()
}
