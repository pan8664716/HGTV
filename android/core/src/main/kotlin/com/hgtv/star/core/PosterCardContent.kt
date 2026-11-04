// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 一张海报卡要显示的全部文案与角标状态。
 *
 * 这里只做纯逻辑，不碰 Android View：电视与手机虽然字号、焦点和材质不同，
 * 但「季号缺省第 1 季」「系统标签优先于名次」「集数优先于兜底角标」
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
    val contentDescription: String,
) {
    companion object {
        /**
         * 从剧集与页面上下文生成卡片内容。
         *
         * @param statusText 第二行状态（历史进度 / 收藏更新 / 上线时间）；空白表示没有。
         * @param badgeFallback 没有集数时右下角显示的兜底文案（`预告` 这类）。
         * @param rankText 榜单名次（`第 3 名`）；非榜单页传空串。
         * @param rankTop 名次是否在前三（决定提示色）。
         * @param heatServerText 服务端给的热度原串（榜单接口）；没有时用 [Series.heat]。
         * @param preferStatus 第二行是否状态优先（历史栏）。
         */
        fun of(
            series: Series,
            statusText: String = "",
            badgeFallback: String = "",
            rankText: String = "",
            rankTop: Boolean = false,
            seasonBadge: Boolean = true,
            heatServerText: String? = null,
            preferStatus: Boolean = false,
        ): PosterCardContent {
            val tags = tags(series.tags)
            val status = EpisodeLabel.cardStatus(statusText)
            val episode = EpisodeLabel.total(series.episodeCount).ifEmpty { badgeFallback }
            val season = if (seasonBadge) SeriesSeasons.badge(series) else ""
            val heat = HeatLabel.badge(heatServerText, series.heat)
            val corner = series.label.ifBlank { rankText }
            val cornerColors = if (series.label.isBlank()) emptyList()
                else LabelStyle.colors(series.label, series.labelBg)
            val description = listOf(
                series.label, episode, season, series.title, tags, status,
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
                cornerTop = series.label.isBlank() && rankTop,
                preferStatus = preferStatus,
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
