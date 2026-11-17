// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 一张海报卡要显示的全部文案与角标状态。
 *
 * 这里只做纯逻辑，不碰 Android View：电视与手机虽然字号、焦点和材质不同，
 * 但「榜单页只显示名次」「前三名使用金银铜渐变」「集数优先于兜底角标」
 * 「热度优先用服务端原串」「第二行标签最多三个」这些规则必须完全一致。
 *
 * 标题与封面仍取 [Series]；这个对象只描述**卡片上各处该怎么写**。
 */
data class PosterCardContent(
    val title: String,
    val tags: String,
    val status: String,
    val episode: String,
    val season: String,
    val heat: String,
    val corner: String,
    val cornerColors: List<Int>,
    val cornerTop: Boolean,
    val preferStatus: Boolean,
    val hideTags: Boolean,
    val contentDescription: String,
) {
    companion object {
        /**
         * 从剧集与页面上下文生成卡片内容。
         *
         * @param statusText 第二行状态（历史进度 / 收藏更新 / 上线时间）；空白表示没有。
         * @param badgeFallback 没有集数时右下角显示的兜底文案（`预告` 这类）。
         * @param rankText 榜单名次（`第 3 名`）；非榜单页传空串。
         * @param rank 榜单名次数字；非榜单页传 null。
         * @param rankTop 兼容旧调用；榜单颜色现在按 [rank] 判断。
         * @param heatServerText 服务端给的热度原串（榜单接口）；没有时用 [Series.heat]。
         * @param preferStatus 第二行是否状态优先（历史栏）。
         */
        fun of(
            series: Series,
            statusText: String = "",
            badgeFallback: String = "",
            rankText: String = "",
            rank: Int? = null,
            rankTop: Boolean = false,
            seasonBadge: Boolean = true,
            heatServerText: String? = null,
            preferStatus: Boolean = false,
            hideTags: Boolean = false,
        ): PosterCardContent {
            val tags = if (hideTags) "" else tags(series.tags)
            val status = EpisodeLabel.cardStatus(statusText)
            val episode = EpisodeLabel.total(series.episodeCount).ifEmpty { badgeFallback }
            val season = if (seasonBadge) SeriesSeasons.badge(series) else ""
            val heat = HeatLabel.badge(heatServerText, series.heat)
            val parsedRank = rank ?: rankText.filter(Char::isDigit).toIntOrNull()
            val ranking = parsedRank != null || rankText.isNotBlank()
            val corner = if (ranking) rankText.ifBlank { "第 ${parsedRank ?: 1} 名" } else series.label
            val cornerColors = if (parsedRank != null) RankBadgeStyle.colors(parsedRank)
                else if (ranking) emptyList()
                else LabelStyle.colors(series.label, series.labelBg)
            val description = listOf(
                corner, episode, season, series.title, tags, status,
                if (heat.isBlank()) "" else "$heat 热度",
            ).filter { it.isNotBlank() }.joinToString("，")
            return PosterCardContent(
                title = series.title,
                tags = tags,
                status = status,
                episode = episode,
                season = season,
                heat = heat,
                corner = corner,
                cornerColors = cornerColors,
                cornerTop = false,
                preferStatus = preferStatus,
                hideTags = hideTags,
                contentDescription = description,
            )
        }

        /**
         * 第二行左侧的类型标签：最多三个、` · ` 连接。
         *
         * 服务端把整串用逗号 / 间隔号混着给；两端原先各拆一次，手机取三个、电视取整串。
         * 这里只保留一条规则，避免同一部剧在两种卡片上显示不同的标签数量。
         */
        fun tags(raw: String): String = raw.split(',', '·', '、', '|')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.contains("集") }
            .take(3)
            .joinToString(" · ")
    }
}

/** 榜单前三名的角标渐变：金 / 银 / 铜。 */
object RankBadgeStyle {
    private val GOLD = listOf(0xFFD6A21A.toInt(), 0xFF7A4D00.toInt())
    private val SILVER = listOf(0xFFBFC8D4.toInt(), 0xFF5F6B7A.toInt())
    private val BRONZE = listOf(0xFFC47A42.toInt(), 0xFF6A351B.toInt())

    fun colors(rank: Int): List<Int> = when(rank) {
        1 -> GOLD
        2 -> SILVER
        3 -> BRONZE
        else -> emptyList()
    }
}
