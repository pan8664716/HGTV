// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 榜单页的八个榜单，参数与顺序来自 2026-11-18 的 `bangdan.har`。
 *
 * 接口是 `GET /reading/bookapi/bookmall/cell/change/v:version/`：
 * `selected_items=all`，[apiValue] 写入 `sub_selected_items`，单页 10 条。
 * [storedValue] 只用于本机缓存与设置 key，不能和旧版的漫剧 / 真人 / AI 分类混用。
 */
enum class RankBoard(
    val storedValue: String,
    /** 榜单页顶部两字导航。 */
    val label: String,
    /** 首页剧架与设置显隐使用的完整榜单名。 */
    val homeTitle: String,
    val apiValue: String,
) {
    RECOMMEND("recommend", "推荐", "推荐榜", "ranklist_hot_sc"),
    HOT("hot", "热播", "热播榜", "ranklist_hot_play_sc"),
    SUBSCRIBE("subscribe", "预约", "预约榜", "ranklist_subscribe"),
    NEW_RELEASE("new-release", "新剧", "新剧榜", "ranklist_new_rank_sc"),
    HOT_SEARCH("hot-search", "热搜", "热搜榜", "ranklist_hot_search_sc"),
    CHASE("chase", "追更", "追更榜", "ranklist_chase_update"),
    MUST_WATCH("must-watch", "必看", "必看榜", "ranklist_must_watch"),
    FOLLOWED("followed", "收藏", "收藏榜", "ranklist_followed");

    companion object {
        /** 顶部榜单导航顺序与 HAR 的展示顺序一致。 */
        val navEntries: List<RankBoard> = AppConfig.Navigation.RANK_NAV_ORDER.mapNotNull { value ->
            entries.firstOrNull { it.storedValue == value }
        }

        /**
         * 旧版本首页缓存里是 `comic / real / ai / new`，其中 `new` 是当时的推荐榜。
         * 这些旧键统一落到新推荐榜；其余未知值也使用默认项。
         */
        fun fromStored(value: String?) = when(value) {
            "comic", "real", "ai", "new" -> RECOMMEND
            else -> entries.firstOrNull { it.storedValue == value } ?: RECOMMEND
        }
    }
}

/**
 * 推荐流与分类页共用的体裁入口，和榜单无关。
 *
 * 推荐页的「推荐 / 漫剧 / AI剧 / 真人」是内容体裁；榜单页的八个榜是平台榜单。
 * 两者拆开后，后续榜单继续增加时不会再把推荐流的 pager 撑成八页。
 */
enum class RecommendGenre(
    val storedValue: String,
    val label: String,
    val genre: String?,
) {
    RECOMMEND("recommend", "推荐", null),
    COMIC("comic", "漫剧", "comic_series"),
    AI("ai", "AI剧", "ai_series"),
    REAL("real", "真人", "short_play");

    companion object {
        val typed: List<RecommendGenre> = listOf(COMIC, AI, REAL)
    }
}
