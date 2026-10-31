// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 推荐流的**续流游标**：`session_id` + `next_offset`，按体裁分开存。
 *
 * ## 为什么需要它
 *
 * 推荐接口是有状态的：`session_id` 传空、`offset` 从 0 开始，服务端永远回同一套默认列表
 * （实测连续 8 次 `offset=0` 拿到的 20 条完全一致）。电视端为此把游标落盘（`Library.recommendCursor`），
 * 下次进推荐接着上次的位置往下取；手机端早先没有这一步，于是每次冷启动都是同一批剧——
 * 用户读到的就是「推荐怎么老是这几部」。
 *
 * ## 为什么要按体裁分开
 *
 * 手机推荐页是「漫剧 / 真人 / AI剧」三条并列的流（`PhoneRecommendPage` 的横向 pager），
 * 三条各有各的游标。共用一份的话，翻到另一类会带着上一类的 offset 去取，
 * 位置对不上、还会把另一类的进度覆盖掉。
 *
 * ## 存储格式
 *
 * 一条 `体裁|session|offset`，多条用 `;` 连接；体裁为空表示「三类混排」那条流。
 * 纯字符串处理、不碰 Android，因此可以直接单测（见 `FeedCursorTest`）。
 */
object FeedCursor {

    /** 一条流的续流位置。 */
    data class Entry(val genre: String, val session: String, val offset: Int)

    /** 最多留几条：手机只有三条流，留 6 条足够，也避免落盘字段无限长。 */
    private const val MAX_ENTRIES = 6

    /** 解析：坏值一律丢掉（宁可从头取，也不要拿着半条游标去请求）。 */
    fun decode(raw: String): List<Entry> {
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<Entry>(4)
        raw.split(';').forEach { part ->
            if (part.isBlank()) return@forEach
            val fields = part.split('|')
            if (fields.size != 3) return@forEach
            val offset = fields[2].toIntOrNull()?.takeIf { it >= 0 } ?: return@forEach
            val genre = fields[0].filter { !it.isISOControl() }
            val session = fields[1].filter { !it.isISOControl() }
            out += Entry(genre, session, offset)
        }
        return out
    }

    /** 取某一体裁的那一条；没有就返回 null。 */
    fun of(raw: String, genre: String): Entry? =
        decode(raw).firstOrNull { it.genre == genre }

    /**
     * 写入 / 覆盖某一体裁的游标，返回新的整串。
     *
     * 同一体裁只保留一条（后来的覆盖先前的），并把它挪到末尾：末尾那条是「最近刷的」，
     * 超出 [MAX_ENTRIES] 时从头部淘汰。
     */
    fun upsert(raw: String, entry: Entry): String {
        val clean = entry.copy(
            genre = entry.genre.filter { !it.isISOControl() && it != '|' && it != ';' },
            session = entry.session.filter { !it.isISOControl() && it != '|' && it != ';' },
            offset = entry.offset.coerceAtLeast(0),
        )
        val kept = decode(raw).filterNot { it.genre == clean.genre }
        val all = (kept + clean).takeLast(MAX_ENTRIES)
        return all.joinToString(";") { "${it.genre}|${it.session}|${it.offset}" }
    }

    /** 清掉某一体裁的游标（换范围 / 撞到流尾时调）。 */
    fun remove(raw: String, genre: String): String =
        decode(raw).filterNot { it.genre == genre }
            .joinToString(";") { "${it.genre}|${it.session}|${it.offset}" }
}
