// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import java.io.IOException

/**
 * 分类页里的一个小类。[path] 是 `{大类路径}/{接口题材标识}`（「全部」就是大类本身），
 * [themeId] 是 App 分类接口 `select_items.category_dim_theme` 要的那个 `cate_*` 标识（「全部」为 null）。
 *
 * 名字与标识都取自**接口自己**，不再用官网那套分类名（官网的分类名与接口的题材表不是一套，
 * 按中文名去对接口标识会筛出 0 条：真人那条筛选行的标识对漫剧 / AI剧根本不生效）。
 */
data class CategoryTag(val label: String, val path: String, val themeId: String?)

/** 分类页里的一个大类：大类名 + 接口的 `genre` + 它下面的小类（第一个固定是「全部」）。 */
data class CategoryGroup(val label: String, val path: String, val genre: String, val tags: List<CategoryTag>)

/**
 * 分类页的三本目录，小类来自 App 分类接口自己：
 *
 * | 大类 | 目录 | 接口 `genre` | 小类 |
 * | --- | --- | --- | --- |
 * | 漫剧 | `comic-drama` | `comic_series` | 全部 + 12 |
 * | 真人 | `real-drama` | `short_play` | 全部 + 29 |
 * | AI剧 | `ai-drama` | `ai_series` | 全部 + 12 |
 *
 * 来源分两处，都是**接口自己的数据**（2026-10-19 实测）：
 *
 * * 真人：接口的 `selector_rows` 里 `category_dim_theme` 那一行（`need_selector_panel=true` 才给）。
 *   这一行**只对 `genre=short_play` 有效**：换别的 `genre` 时接口回的是同一份默认行，
 *   按那份标识去筛漫剧 / AI 剧一律 0 条（实测 34 个标识里只有 11 个能筛出内容）。
 * * 漫剧 / AI剧：从**接口返回的内容自己的题材**里采集——把热门前 500 部每条的 `category_schema`
 *   里的 `(category_id, name)` 按出现次数排序，取前 12 个。这样每一个小类都保证筛得出内容。
 *
 * 具体做法可以这么复现（改这张表时照做一遍）：
 *
 * 1. 真人：`POST /reading/distribution/category/landpage/v/` 带
 *    `{"need_selector_panel":true,"select_items":{"genre":["short_play"]}}`，取 `selector_rows` 里
 *    `type=category_dim_theme` 的那一行；
 * 2. 漫剧 / AI剧：同一个接口、`genre=["comic_series"]` / `["ai_series"]`、`sort=["hot_score"]`、
 *    `offset` 取 0/100/200/300/400，统计返回条目 `category_schema` 里的 `(category_id,name)` 频次；
 * 3. `themeId` 一律写成 `cate_<category_id>`。
 */
object CategoryCatalog {
    private fun tags(path: String, vararg entries: Pair<String,String>) =
        listOf(CategoryTag("全部",path,null)) +
            entries.map { (label,themeId) -> CategoryTag(label,"$path/$themeId",themeId) }

    val groups: List<CategoryGroup> = listOf(
        CategoryGroup("漫剧","comic-drama","comic_series",tags("comic-drama",
            "玄幻" to "cate_7", "逆袭" to "cate_739", "修真" to "cate_21",
            "异界" to "cate_599", "穿越" to "cate_37", "奇幻" to "cate_6",
            "年代" to "cate_79", "剧情" to "cate_316", "搞笑" to "cate_425",
            "都市" to "cate_1", "豪门" to "cate_936", "恋爱" to "cate_527",
        )),
        CategoryGroup("真人","real-drama","short_play",tags("real-drama",
            "现言" to "cate_1021", "女性成长" to "cate_1048", "脑洞" to "cate_262",
            "奇幻" to "cate_1020", "玄幻" to "cate_1019", "古言" to "cate_439",
            "战神" to "cate_1038", "宫斗" to "cate_246", "仙侠" to "cate_1013",
            "权谋" to "cate_1047", "种田" to "cate_1180", "年代爱情" to "cate_1022",
            "喜剧" to "cate_303", "悬疑" to "cate_165", "青春" to "cate_297",
            "志怪" to "cate_1027", "民国爱情" to "cate_1025", "灵异" to "cate_751",
            "家国情怀" to "cate_1235", "法律" to "cate_1136", "刑侦" to "cate_1148",
            "抗战" to "cate_504", "武侠" to "cate_1172", "民国传奇" to "cate_1240",
            "动作" to "cate_302", "求生" to "cate_1168", "科幻" to "cate_1092",
            "恐怖" to "cate_1219", "商战" to "cate_1225",
        )),
        CategoryGroup("AI剧","ai-drama","ai_series",tags("ai-drama",
            "脑洞" to "cate_755", "穿越" to "cate_37", "古代" to "cate_758",
            "乡村" to "cate_11", "剧情" to "cate_316", "逆袭" to "cate_739",
            "年代" to "cate_79", "系统" to "cate_19", "重生" to "cate_36",
            "都市" to "cate_1", "校园" to "cate_4", "豪门" to "cate_936",
        )),
    )

    /** 默认选中：第一组的「全部」。 */
    val defaultTag: CategoryTag get() = groups.first().tags.first()

    /** 按路径反查小类；找不到返回 null。 */
    fun tagOf(path: String): CategoryTag? =
        groups.firstNotNullOfOrNull { group -> group.tags.firstOrNull { it.path==path } }

    /**
     * 按路径反查小类的**中文名**（「全部」返回 null）。
     */
    fun labelOf(path: String): String? = tagOf(path)?.takeIf { it.themeId!=null }?.label

    /** 按路径反查它属于哪一组（界面从缓存里恢复选择时用）。 */
    fun groupOf(path: String) = groups.firstOrNull { group -> group.tags.any { it.path==path } }

    /**
     * 旧路径 → [CategorySelection]。分类页现在走接口的 `selector_rows`（见 [CategoryPanel]），
     * 这里只给旧调用方 / 探针 / 单测保留路径入口。
     */
    fun selectionOf(path: String): CategorySelection {
        val group=groupOf(path) ?: throw IOException("分类暂不可用")
        return CategorySelection(genre=group.genre,theme=tagOf(path)?.themeId)
    }

    /** 当前选择的显示文案，例如「漫剧 · 玄幻」。认不出的路径原样返回。 */
    fun describe(path: String): String {
        val group = groupOf(path) ?: return path
        val tag = group.tags.firstOrNull { it.path==path } ?: return group.label
        return if (tag.themeId==null) "${group.label} · 全部" else "${group.label} · ${tag.label}"
    }
}
