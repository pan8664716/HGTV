// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 四个榜单。首页的四个「热播…」剧架与排行榜页的四个榜单**是同一份数据**：
 * 剧架的行末「查看全部」直接进对应的榜单页，不再另做一套「热门集合页」。
 *
 * 数据来自 **App 分类接口按 [sort] 排序**：前三个榜按最高热度（`hot_score`），
 * 「上新」按最新上架（`online_time`）取全站综合，名次与热度都是服务端给的
 *（见 `ContentRepository.rankApiPage`）。每个榜对应的分类：
 *
 * | 榜单 | 按钮 / 来源行 | App 分类 [genre] | [sort] | `rankKey` | 首页栏目 |
 * | --- | --- | --- | --- | --- | --- |
 * | [COMIC] | 漫剧 | `comic_series` | `hot_score` | `comic` | 热播漫剧 |
 * | [REAL] | 真人 | `short_play` | `hot_score` | `real` | 热播真人 |
 * | [AI] | AI剧 | `ai_series` | `hot_score` | `ai` | 热播AI |
 * | [NEW] | 上新 | 全站综合（不筛分类） | `online_time` | `new` | 热播上新 |
 *
 * [label] 是榜单页四个切换按钮的文案（很短：漫剧 / 真人 / AI剧 / 上新），页面主标题固定写「榜单」；
 * [homeTitle] 是首页那一栏的栏目名；[source] 是来源行（榜单页头那一行文案）。
 * 三处都从这一个枚举取，改名字只改这里。
 *
 * [ranked] 为假时这一页是「上新」这种时间序列表，不画「第 N 名」：
 * 服务端给的 `rank` 只是页内序号，对时间序没有意义。
 *
 * [storedValue] 同时是 `rankKey`、本机首页缓存的键后缀，以及 [HomeLayout] 里剧架 key 的后缀；
 * 枚举顺序就是界面顺序（首页四栏、榜单页四个切换芯片）。
 */
enum class RankBoard(
    val storedValue: String,
    /** 榜单按钮的文案（短）：漫剧 / 真人 / AI剧 / 上新。榜单页主标题固定是「榜单」。 */
    val label: String,
    /** 首页栏目名。 */
    val homeTitle: String,
    val source: String,
    /**
     * App 分类接口里的分类标识（`select_items.genre`）。
     *
     * `null` = 不筛分类：接口按「全站综合热度」排，也就是「红果热播榜」那个全站榜。
     * 三个非空值取自接口自己的 `selector_rows`（`真人剧` / `漫剧` / `AI剧`）。
     */
    val genre: String?,
    /** App 分类接口的 `select_items.sort`：热度榜用 `hot_score`，上新用 `online_time`。 */
    val sort: String,
    /** 这一页要不要画名次。时间序的「上新」不画。 */
    val ranked: Boolean = true
) {
    COMIC("comic", "漫剧", "热播漫剧", "来源：红果漫剧热播榜", "comic_series", "hot_score"),
    REAL("real", "真人", "热播真人", "来源：红果真人剧热播榜", "short_play", "hot_score"),
    // 「AI剧」与分类页那个大类的写法一致（分类页是 漫剧 / 真人 / AI剧）。
    AI("ai", "AI剧", "热播AI", "来源：红果 AI 剧热播榜", "ai_series", "hot_score"),
    // 最新上架：不筛分类，全站按 `online_time` 倒序；不是热度榜，因此不画名次。
    NEW("new", "上新", "热播上新", "来源：红果最新上架", null, "online_time", ranked = false);

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.storedValue == value } ?: COMIC
    }
}
