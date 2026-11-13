// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 四个榜单。首页的四个剧架与排行榜页的四个榜单**是同一份数据**：
 * 剧架的行末「查看全部」直接进对应的榜单页，不再另做一套「热门集合页」。
 *
 * 数据全部来自 **App 分类接口按 [sort] 排序**，四个榜都按最高热度（`hot_score`）取数，
 * 名次与热度都是服务端给的（见 `ContentRepository.rankApiPage`）。每个榜对应的分类：
 *
 * | 榜单 | 推荐流 / 榜单 Tab [label] | App 分类 [genre] | [sort] | `rankKey` | 首页栏目 [homeTitle] |
 * | --- | --- | --- | --- | --- | --- |
 * | [COMIC] | 漫剧 | `comic_series` | `hot_score` | `comic` | 热播漫剧 |
 * | [REAL] | 真人 | `short_play` | `hot_score` | `real` | 热播真人 |
 * | [AI] | AI剧 | `ai_series` | `hot_score` | `ai` | 热播AI |
 * | [NEW] | 推荐 | 全站综合（不筛分类） | `hot_score` | `new` | 推荐榜 |
 *
 * [label] 同时供手机推荐流和榜单页使用，所以 [NEW] 两处都叫「推荐」；
 * [homeTitle] 只用于首页栏目，[NEW] 在首页叫「推荐榜」。
 *
 * [storedValue] 同时是 `rankKey`、本机首页缓存的键后缀，以及 [HomeLayout] 里剧架 key 的后缀；
 * 枚举顺序就是界面顺序（首页四栏、榜单页四个切换芯片）。
 */
enum class RankBoard(
    val storedValue: String,
    /** 推荐流与榜单页 Tab 的短名；[NEW] 在这里仍是「推荐」。 */
    val label: String,
    /** 首页栏目名。 */
    val homeTitle: String,
    /**
     * App 分类接口里的分类标识（`select_items.genre`）。
     *
     * `null` = 不筛分类：接口按「全站综合热度」排，也就是「HGTV热播榜」那个全站榜。
     * 三个非空值取自接口自己的 `selector_rows`（`真人剧` / `漫剧` / `AI剧`）。
     */
    val genre: String?,
    /** App 分类接口的 `select_items.sort`；四个榜都按热度取推荐榜。 */
    val sort: String,
) {
    COMIC("comic", "漫剧", "热播漫剧", "comic_series", "hot_score"),
    REAL("real", "真人", "热播真人", "short_play", "hot_score"),
    // 「AI剧」与分类页那个大类的写法一致（分类页是 漫剧 / 真人 / AI剧）。
    AI("ai", "AI剧", "热播AI", "ai_series", "hot_score"),
    // 推荐榜：不筛分类，取全站综合热度；推荐流与榜单页 Tab 都显示「推荐」。
    NEW("new", "推荐", "推荐榜", null, "hot_score");

    companion object {
        /** 顶部榜单导航顺序：推荐 / 漫剧 / AI剧 / 真人。 */
        val navEntries: List<RankBoard> = listOf(NEW, COMIC, AI, REAL)

        fun fromStored(value: String?) = entries.firstOrNull { it.storedValue == value } ?: COMIC
    }
}
