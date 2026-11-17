// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 推荐页的四个入口。
 *
 * 综合、漫剧、真人推荐页使用官方 `bookmall/tab/v`；这里保留 Tab 与分类 `genre` 的映射：
 * * `16` 推荐
 * * `36` 漫剧
 * * `39` 真人剧
 *
 * AI 剧没有独立官方 Tab，取流时仍使用分类接口的 `ai_series`。
 */
enum class RecommendTab(val tabType: Int?, val genre: String?) {
    RECOMMEND(16, null),
    COMIC(36, CategoryGenres.COMIC),
    REAL(39, CategoryGenres.REAL),
    AI(null, CategoryGenres.AI);

    companion object {
        fun fromGenre(genre: String?): RecommendTab = when(genre) {
            CategoryGenres.COMIC -> COMIC
            CategoryGenres.REAL -> REAL
            CategoryGenres.AI -> AI
            else -> RECOMMEND
        }
    }
}

/**
 * 推荐 Store 依赖的窄接口。
 *
 * Store 只认识取流与上报这两件事，不再直接依赖完整的 `ContentRepository`。
 */
interface RecommendFeedSource {
    fun recommendFeed(
        selection: CategorySelection,
        sessionId: String,
        offset: Int,
        limit: Int = 20,
        genres: List<String> = emptyList(),
        filterIds: String = "",
        tab: RecommendTab = RecommendTab.RECOMMEND,
        horizontalOnly: Boolean = false,
        cell: RecommendCellCursor = RecommendCellCursor(),
    ): RecommendFeed
}

interface RecommendReportSink {
    fun reportRecommendStats(events: List<RecommendStatData>)
}

interface RecommendDataSource : RecommendFeedSource,RecommendReportSink
