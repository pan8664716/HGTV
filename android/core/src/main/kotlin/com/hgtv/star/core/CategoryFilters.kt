// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 分类页的一枚筛选项：[id] 是接口的 `selector_item_id`，[label] 是 `show_name`。
 */
data class CategoryFilterOption(val id: String, val label: String)

/**
 * 分类页的一行筛选条件：
 *
 * * [type] 同时是 `select_items` 的键（`genre` / `category_dim_theme` / `sort` …）；
 * * [title] 是接口给的整行名称（`全部体裁` / `全部主题` …）；
 * * [options] 按接口顺序排列，界面不再自己维护中文名到标识的映射。
 */
data class CategoryFilterRow(
    val type: String,
    val title: String,
    val options: List<CategoryFilterOption>
) {
    fun labelOf(id: String?): String? = options.firstOrNull { it.id==id }?.label
}

/** 分类页顶部体裁的一个按钮。[id] 为空 = 「全部体裁」（不筛 genre）。 */
data class CategoryGenreOption(val id: String?, val label: String)

/**
 * 顶部体裁的固定顺序（需求钉死，不跟接口的 `selector_rows` 顺序走）：
 * 全部 / 漫剧 / AI剧 / 真人。
 *
 * 这四枚按钮**常驻**导航栏：换体裁时只有下方的筛选行与内容重新加载，
 * 按钮本身不会因为面板刷新而消失。
 */
object CategoryGenres {
    val options: List<CategoryGenreOption> = listOf(
        CategoryGenreOption(null,"全部"),
        CategoryGenreOption("comic_series","漫剧"),
        CategoryGenreOption("ai_series","AI剧"),
        CategoryGenreOption("short_play","真人")
    )
    /**
     * 设置页「推荐范围」里能开关的那几枚：就是上面带标识的三类（「全部」不是一个类，不能开关）。
     *
     * 推荐流本来就是按 `genre` 数组取的（不传 = 全部体裁），因此这三枚开关直接对应
     * 请求里的体裁列表，不需要另建一套名字到标识的映射。
     */
    val recommendable: List<CategoryGenreOption> = options.filter { it.id != null }
    /** [recommendable] 的标识，顺序一致；用作本机设置的合法值白名单。 */
    val recommendableIds: List<String> = recommendable.mapNotNull { it.id }
}

/**
 * 分类页的筛选面板。一次 `need_selector_panel=true` 的请求就把所有行一起带回来：
 * 体裁 / 主题 / 设定 / 背景 / 推荐 / 受众 / 时间 / 状态（状态只有部分体裁有）。
 *
 * **主题 / 设定 / 背景 / 状态随体裁变化**：接口在 `genre` 行上给了
 * `refresh_other_selector_row=true`，换体裁后要重新取一次面板。
 */
data class CategoryPanel(val rows: List<CategoryFilterRow>) {
    fun row(type: String): CategoryFilterRow? = rows.firstOrNull { it.type==type }
    fun labelOf(type: String, id: String?): String? = row(type)?.labelOf(id)

    companion object {
        val EMPTY = CategoryPanel(emptyList())
    }
}

/**
 * 分类页当前选中的筛选条件。
 *
 * [genre] 与其余维度一样：为空 = 「全部体裁」（不筛 genre）。
 *
 * 默认值从「真人剧」改成「全部」：分类页第一次打开时用户还没表达任何偏好，
 * 一进来就替他筛掉漫剧与 AI 剧，看到的就不是「全部内容」而是「真人剧」——
 * 用户读到的正是「为什么只有这些」。顶部四枚芯片里「全部」排在第一位，默认停在那里。
 */
data class CategorySelection(
    val genre: String? = null,
    val theme: String? = null,
    val role: String? = null,
    val epoch: String? = null,
    /** 排序为空 = 「全部推荐」（接口不传 `sort`），这也是分类页的默认值。 */
    val sort: String? = null,
    val gender: String? = null,
    val onlineTime: String? = null,
    val creationStatus: String? = null
) {
    fun valueOf(type: String): String? = when(type) {
        TYPE_GENRE -> genre
        TYPE_THEME -> theme
        TYPE_ROLE -> role
        TYPE_EPOCH -> epoch
        TYPE_SORT -> sort
        TYPE_GENDER -> gender
        TYPE_ONLINE_TIME -> onlineTime
        TYPE_CREATION_STATUS -> creationStatus
        else -> null
    }

    fun with(type: String, id: String?): CategorySelection = when(type) {
        TYPE_GENRE -> copy(genre=id)
        TYPE_THEME -> copy(theme=id)
        TYPE_ROLE -> copy(role=id)
        TYPE_EPOCH -> copy(epoch=id)
        TYPE_SORT -> copy(sort=id)
        TYPE_GENDER -> copy(gender=id)
        TYPE_ONLINE_TIME -> copy(onlineTime=id)
        TYPE_CREATION_STATUS -> copy(creationStatus=id)
        else -> this
    }

    /**
     * 换体裁时清掉随体裁变化的维度（主题 / 设定 / 背景 / 状态）；
     * 推荐 / 受众 / 时间三条每个体裁都有，保留用户的选择。
     */
    fun newGenre(id: String?): CategorySelection =
        copy(genre=id, theme=null, role=null, epoch=null, creationStatus=null)

    /** 除体裁 / 排序外没有别的筛选：这种组合才走服务端的「全部推荐」流。 */
    fun hasNoDetailFilters(): Boolean =
        theme==null && role==null && epoch==null && gender==null && onlineTime==null && creationStatus==null

    /**
     * 缓存键 / 焦点键用的稳定串。顺序固定，不依赖 Map 的遍历顺序。
     */
    fun key(): String = listOf(
        genre.orEmpty(), theme.orEmpty(), role.orEmpty(), epoch.orEmpty(),
        sort.orEmpty(), gender.orEmpty(), onlineTime.orEmpty(), creationStatus.orEmpty()
    ).joinToString("|")

    companion object {
        /** 旧默认值（真人剧）：只留给旧缓存键的迁移，不再作为界面的默认选择。 */
        const val DEFAULT_GENRE = "short_play"
        const val TYPE_GENRE = "genre"
        const val TYPE_THEME = "category_dim_theme"
        const val TYPE_ROLE = "category_dim_role"
        const val TYPE_EPOCH = "category_dim_epoch"
        const val TYPE_SORT = "sort"
        const val TYPE_GENDER = "gender"
        const val TYPE_ONLINE_TIME = "online_time"
        const val TYPE_CREATION_STATUS = "creation_status"
    }
}

/** 分类页一次请求的落地结果：内容一页 + （可选）整份筛选面板。 */
data class CategoryFeed(val page: CatalogPage, val panel: CategoryPanel?)
