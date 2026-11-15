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
 * 来源分两处，都是**接口自己的数据**（2026-11-16 复核）：
 *
 * * 真人：接口的 `selector_rows` 里的主题 / 设定 / 背景行（`need_selector_panel=true` 才给）。
 * * 漫剧 / AI剧：接口返回的主题 / 设定 / 背景默认行只对真人有效，按那份标识筛会得到 0 条。
 *   因此从内容自己的 `category_schema` 里采集 `(category_id, name)`：主题按出现频次取前 12 个，
 *   设定 / 背景按中文名与真人筛选项精确匹配，只保留该体裁内容里实际存在的选项。
 *
 * 具体做法可以这么复现（改这张表时照做一遍）：
 *
 * 1. 真人：`POST /reading/distribution/category/landpage/v/` 带
 *    `{"need_selector_panel":true,"select_items":{"genre":["short_play"]}}`，取 `selector_rows` 里
 *    `type=category_dim_theme / category_dim_role / category_dim_epoch` 的三行；
 * 2. 漫剧 / AI剧：同一个接口、`genre=["comic_series"]` / `["ai_series"]`、`sort=["hot_score"]`、
 *    `offset` 取 0/100/200/300/400，统计返回条目 `category_schema` 里的 `(category_id,name)`；
 * 3. `themeId` 一律写成 `cate_<category_id>`。
 */
object CategoryCatalog {
    private fun tags(path: String, vararg entries: Pair<String,String>) =
        listOf(CategoryTag("全部",path,null)) +
            entries.map { (label,themeId) -> CategoryTag(label,"$path/$themeId",themeId) }

    private fun options(vararg entries: Pair<String,String>) =
        entries.map { (label,id) -> CategoryFilterOption(id,label) }

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

    /**
     * 漫剧 / AI剧的 `category_dim_role` 与 `category_dim_epoch` 也不能使用接口默认表。
     * 下表按中文名从热门前 500 部的 `category_schema` 里映射到各自 `cate_<category_id>`；
     * 没有出现在该体裁内容里的选项不展示，避免用户点到 0 条。
     */
    private val comicRoles=options(
        "女强" to "cate_6172", "神女" to "cate_6173", "大男主" to "cate_6174",
        "男强" to "cate_6175", "忠诚男主" to "cate_6176", "群像" to "cate_6178",
        "团宠" to "cate_6179", "反派主角" to "cate_6182", "霸道男主" to "cate_6183",
        "商界女王" to "cate_6185", "直爽女主" to "cate_6186", "大女主" to "cate_6171",
        "战神" to "cate_6192", "总裁" to "cate_6197", "业界精英" to "cate_6198",
        "医生" to "cate_6199", "法医" to "cate_9323", "黑客" to "cate_6200",
        "厨师" to "cate_6201", "厨娘" to "cate_6202", "主播" to "cate_6204",
        "豪门千金" to "cate_6205", "真假千金" to "cate_6207", "剑客" to "cate_6212",
        "刺客杀手" to "cate_6213", "书生" to "cate_9318", "女帝" to "cate_6237",
        "将军" to "cate_9321", "世子" to "cate_9317", "学神" to "cate_6220",
        "天才" to "cate_6221", "天生灵骨" to "cate_6223", "锦鲤" to "cate_6224",
        "萌宠" to "cate_6227", "古灵精怪" to "cate_6250", "乖乖女" to "cate_6251",
        "腹黑" to "cate_6240", "毒舌" to "cate_6241", "病娇" to "cate_6242",
        "宠妻" to "cate_6261", "强强联合" to "cate_6267", "极品亲戚" to "cate_6271",
        "后妈" to "cate_6272",
    )

    private val comicEpochs=options(
        "未来" to "cate_6278", "架空" to "cate_6279", "现代" to "cate_6274",
        "古代" to "cate_6275", "乡村" to "cate_6282", "校园" to "cate_6283",
        "职场" to "cate_6290", "豪门" to "cate_6287", "世家" to "cate_6288",
        "娱乐圈" to "cate_6289", "星际" to "cate_6295", "游戏世界" to "cate_6297",
        "荒岛" to "cate_6298", "异界" to "cate_6299", "方言" to "cate_6310",
        "英语" to "cate_9461", "韩语" to "cate_9465",
    )

    private val aiRoles=options(
        "女强" to "cate_5754", "神女" to "cate_5755", "大男主" to "cate_5756",
        "男强" to "cate_5757", "忠诚男主" to "cate_5758", "热血硬汉" to "cate_5759",
        "群像" to "cate_5760", "团宠" to "cate_5761", "大叔" to "cate_5762",
        "小人物" to "cate_5763", "反派主角" to "cate_5764", "霸道男主" to "cate_5765",
        "市井英雄" to "cate_5766", "商界女王" to "cate_5767", "直爽女主" to "cate_5768",
        "大女主" to "cate_5753", "神豪" to "cate_5774", "龙王" to "cate_5775",
        "总裁" to "cate_5777", "业界精英" to "cate_5778", "医生" to "cate_5779",
        "律师" to "cate_9431", "厨师" to "cate_5781", "厨娘" to "cate_5782",
        "主播" to "cate_5784", "豪门千金" to "cate_5785", "落魄千金" to "cate_5786",
        "真假千金" to "cate_5787", "武林高手" to "cate_5795", "少帅" to "cate_5788",
        "书生" to "cate_9424", "将军" to "cate_9430", "世子" to "cate_9422",
        "学神" to "cate_5800", "天才" to "cate_5801", "神童" to "cate_5802",
        "锦鲤" to "cate_5804", "嘴硬心软" to "cate_5821", "甜妹" to "cate_9421",
        "古灵精怪" to "cate_5823", "乖乖女" to "cate_5824", "大小姐" to "cate_5825",
        "灰姑娘" to "cate_5826", "腹黑" to "cate_5813", "高冷" to "cate_5816",
        "戏精" to "cate_5817", "痞少" to "cate_5819", "宠妻" to "cate_5833",
        "欢喜冤家" to "cate_5838", "姐弟恋" to "cate_5829", "强强联合" to "cate_5839",
        "极品亲戚" to "cate_5843", "后妈" to "cate_5844",
    )

    private val aiEpochs=options(
        "架空" to "cate_5850", "现代" to "cate_5845", "古代" to "cate_5846",
        "民国" to "cate_5847", "乡村" to "cate_5852", "校园" to "cate_5853",
        "职场" to "cate_5859", "商界" to "cate_5860", "黑道" to "cate_5861",
        "豪门" to "cate_5856", "世家" to "cate_5857", "娱乐圈" to "cate_5858",
        "游戏世界" to "cate_5865", "异界" to "cate_5867", "方言" to "cate_5875",
        "韩语" to "cate_9471",
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
     * 漫剧 / AI剧的主题 / 设定 / 背景必须使用内容 `category_schema` 采集出的标识。
     * 真人剧继续使用接口 `selector_rows`，返回 null 表示不改写。
     */
    fun filterOptions(genre: String?, type: String): List<CategoryFilterOption>? {
        if(genre!=CategoryGenres.COMIC && genre!=CategoryGenres.AI) return null
        return when(type) {
            CategorySelection.TYPE_THEME -> groups
                .firstOrNull { it.genre==genre }
                ?.tags
                ?.mapNotNull { tag -> tag.themeId?.let { id -> CategoryFilterOption(id,tag.label) } }
            CategorySelection.TYPE_ROLE ->
                if(genre==CategoryGenres.COMIC) comicRoles else aiRoles
            CategorySelection.TYPE_EPOCH ->
                if(genre==CategoryGenres.COMIC) comicEpochs else aiEpochs
            else -> null
        }
    }

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
