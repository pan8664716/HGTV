// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.json.JSONArray
import org.json.JSONObject

internal object ContentCatalogParser {
    fun valid(rows: List<Series>) = rows.filter {
        it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank()
    }.distinctBy { it.id }

    /**
     * 榜单一条里的名次与热度。
     *
     * **名次要换算**：接口的 `recommend_info.rank` 是**这一页内**的 1-based 序号，不是全站名次
     * （实测 `offset=20` 时第一名的 `rank` 又是 1，而它确实是第 21 名）。
     * 榜单页的名次是累计的，因此这里用 `base + 页内序号` 换算成榜单上的名次
     *（`base` 是这一页之前已经排过的条数）；
     * 服务端没给 `rank` 时退回「第几条」（这一串本来就是按热度排的，序号即名次）。
     *
     * `recommend_info` 有时是对象、有时是 JSON 字符串（同一次响应里两种都能见到），
     * `recommend_reason_list` 一直是字符串，因此两者都统一「能当对象就用、否则当字符串解」。
     *
     * 热度是服务端自己那串「9014万」：榜单本来就是按它排的，界面照它显示，不重排、不换算。
     * 两处都读不到就返回 null（卡片第二行由界面退回榜单名）。
     */
    fun apiRank(row: JSONObject, base: Int, index: Int): RankPosition? {
        val info=row.optJSONObject("recommend_info")
            ?: runCatching { JSONObject(row.str("recommend_info")) }.getOrNull()
        val local=info?.optString("rank")?.toIntOrNull()?.takeIf { it>0 }
        val heat=runCatching { JSONObject(row.str("recommend_reason_list")) }.getOrNull()
            ?.optJSONArray("sub_title_list")?.objects()
            ?.firstOrNull { it.optInt("data_type",-1)==27 }?.str("content").orEmpty()
        if(local==null && heat.isEmpty()) return null
        return RankPosition(base+(local ?: (index+1)),heat)
    }

    /**
     * `select_items`：每个维度都是数组，未选中的维度不传（与接口的「全部」等价）。
     * `genre` 始终传一个值：分类页与官方 App 一样始终停在一个体裁上。
     */
    fun categorySelectItems(
        selection: CategorySelection,
        sort: String?,
        genres: List<String> = emptyList()
    ): JSONObject {
        // 「全部体裁」= 空数组（与不传等价）；其余体裁传它自己的标识。
        // [genres] 非空时用它替代 [CategorySelection.genre]：推荐页的「推荐范围」可以同时开好几类，
        // 接口的 `genre` 本来就是数组（分类页始终只传一个，走的是下面这条空列表分支）。
        val picked=genres.ifEmpty { listOfNotNull(selection.genre) }
        val select=JSONObject().put("genre",JSONArray().apply { picked.forEach { put(it) } })
        selection.theme?.let { select.put(CategorySelection.TYPE_THEME,JSONArray().put(it)) }
        selection.role?.let { select.put(CategorySelection.TYPE_ROLE,JSONArray().put(it)) }
        selection.epoch?.let { select.put(CategorySelection.TYPE_EPOCH,JSONArray().put(it)) }
        selection.gender?.let { select.put(CategorySelection.TYPE_GENDER,JSONArray().put(it)) }
        // 分类页已统一移除「时间」「状态」两栏；旧缓存里即使残留值也不再发送。
        // 排序为空 = 用户主动选择服务端推荐序；分类页默认与推荐流都传 hot_score。
        sort?.let { select.put(CategorySelection.TYPE_SORT,JSONArray().put(it)) }
        return select
    }

    /** 把 `selector_rows` 原样解析成界面用的面板；没有面板时返回 null（内容仍然可用）。 */
    fun parseCategoryPanel(rows: JSONArray?): CategoryPanel? {
        val parsed=rows?.objects().orEmpty().mapNotNull { row ->
            val type=row.str("type")
            val options=row.optJSONArray("items")?.objects().orEmpty().mapNotNull { item ->
                val id=item.str("selector_item_id")
                val label=item.str("show_name")
                if(id.isBlank()||label.isBlank()) null else CategoryFilterOption(id,label)
            }
            if(type.isBlank()||options.isEmpty()) null
            else CategoryFilterRow(type,row.str("row_name"),options)
        }
        return if(parsed.isEmpty()) null else CategoryPanel(parsed)
    }

    fun officialFeedCards(tab: JSONObject): List<Series> {
        val outer=tab.optJSONArray("cell_data")?.objects().orEmpty()
        return outer.flatMap { cell ->
            val inner=cell.optJSONArray("cell_data")?.objects().orEmpty()
            if(inner.isEmpty()) listOfNotNull(officialFeedCard(cell))
            else inner.mapNotNull(::officialFeedCard)
        }
    }

    /**
     * 官方推荐页的原始卡片槽位数。
     *
     * 外层 `cell_data` 可能是一个「猜你喜欢」容器，真正的卡片在它的内层
     * `cell_data` 里；也有接口直接在外层给卡片。空槽位也要计入游标，避免
     * 一个坏卡片让下一页从错误的位置开始。
     */
    fun officialFeedRawCardCount(tab: JSONObject): Int {
        val outer=tab.optJSONArray("cell_data")?.objects().orEmpty()
        return outer.fold(0) { count, cell ->
            val inner=cell.optJSONArray("cell_data")
            count + if(inner!=null && inner.length()>0) inner.length() else 1
        }
    }

    /**
     * 把官方书包卡片归一成页面使用的 [Series]。
     *
     * 系列卡片的正文在 `video_data[0].video_detail`，UGC / 合集卡片的正文
     * 直接在外层 `video_detail`；两条路径都保留原始 `recommend_info`，
     * 曝光上报才能原样回传服务端。
     */
    fun officialFeedCard(cell: JSONObject): Series? {
        val videoData=cell.optJSONArray("video_data")?.objects()?.firstOrNull()
        val direct=cell.optJSONObject("video_detail")
        val detail=videoData?.optJSONObject("video_detail") ?: direct ?: videoData
        val firstVideo=detail?.optJSONArray("video_list")?.objects()?.firstOrNull()
            ?: detail?.optJSONArray("video_detail_list")?.objects()?.firstOrNull()
        val id=detail?.str("series_id").orEmpty()
            .ifBlank { detail?.str("series_id_str").orEmpty() }
            .ifBlank { videoData?.str("series_id").orEmpty() }
            .ifBlank { firstVideo?.str("series_id").orEmpty() }
            .ifBlank { detail?.str("related_album_id").orEmpty() }
        val title=detail?.str("series_title").orEmpty()
            .ifBlank { videoData?.str("title").orEmpty() }
            .ifBlank { firstVideo?.str("title").orEmpty() }
            .ifBlank { detail?.str("title").orEmpty() }
        if(id.isBlank() || title.isBlank()) return null
        val count=detail?.optInt("episode_cnt",0)?.takeIf { it>0 }
            ?: firstVideo?.optInt("episode_cnt",0)
            ?: 0
        val info=videoData?.let { rawJsonValue(it,"recommend_info") }.orEmpty()
            .ifBlank { detail?.let { rawJsonValue(it,"recommend_info") }.orEmpty() }
        val groupId=videoData?.let { rawJsonValue(it,"recommend_group_id") }.orEmpty()
            .ifBlank { detail?.let { rawJsonValue(it,"recommend_group_id") }.orEmpty() }
            .ifBlank { runCatching { JSONObject(info).str("gid") }.getOrDefault("") }
        val tag=officialTagInfo(detail,videoData)
        val reasonSource=videoData ?: detail
        val heat=reasonSource?.let(::heatOf) ?: 0L
        val ranking=officialRankingInfo(videoData)
            .takeIf { it.first.isNotBlank() }
            ?: officialRankingInfo(detail)
        val playCount=(detail?.optLong("series_play_cnt",0)
            ?: videoData?.optLong("series_play_cnt",0)
            ?: 0L).coerceIn(0L,Int.MAX_VALUE.toLong()).toInt()
        val vertical=detail?.optBoolean("vertical",false)==true ||
            firstVideo?.optBoolean("vertical",false)==true ||
            videoData?.optBoolean("vertical",false)==true
        val contentType=detail?.optInt("content_type",0)
            ?: videoData?.optInt("content_type",0)
            ?: 0
        return Series(
            id=id,
            title=title,
            cover=cleanUrl(
                detail?.str("series_cover").orEmpty()
                    .ifBlank { videoData?.str("cover").orEmpty() }
                    .ifBlank { firstVideo?.str("cover").orEmpty() },
            ),
            description=detail?.str("series_intro").orEmpty()
                .ifBlank { firstVideo?.str("video_desc").orEmpty() },
            badge=EpisodeLabel.normalize(detail?.str("episode_right_text").orEmpty())
                .ifEmpty { EpisodeLabel.of(count) },
            tags=detail?.let { tagsOf(it) }.orEmpty(),
            author=publisher(detail?.str("copyright").orEmpty()),
            episodeCount=count,
            seasonIndex=detail?.let(::seasonOf) ?: videoData?.let(::seasonOf) ?: 0,
            vertical=vertical,
            heat=heat,
            playCount=playCount,
            label=tag?.str("text").orEmpty().trim(),
            labelBg=tagColors(tag),
            recommendInfo=info,
            recommendGroupId=groupId,
            contentType=contentType,
            recommendRanking=ranking.first,
            recommendRankingBg=ranking.second,
        )
    }

    fun officialTagInfo(
        detail: JSONObject?,
        videoData: JSONObject?,
    ): JSONObject? {
        detail?.let(::tagInfo)?.let { return it }
        videoData?.let(::tagInfo)?.let { return it }
        return detail?.optJSONArray("video_tag_info")?.objects()?.firstOrNull()
            ?: videoData?.optJSONArray("video_tag_info")?.objects()?.firstOrNull()
    }

    /**
     * 官方推荐原因里的排行榜提示。
     *
     * `rec_tags` 在系列卡片里既可能给 `recommend_reason`（如「AI剧热播榜 No.10」），
     * 也可能只给 `content`（如「真人剧·必看月榜 No.10」）。可见文案优先取前者，
     * 没有时再取后者；背景色原样从 `rec_reason_style` 读取。
     */
    fun officialRankingInfo(source: JSONObject?): Pair<String,String> {
        val reason=source?.let(::reasonPayload) ?: return "" to ""
        val tags=reason.optJSONArray("rec_tags")?.objects().orEmpty()
        val text=tags.mapNotNull { row ->
            row.str("recommend_reason").ifBlank { row.str("content") }
                .trim().takeIf { it.isNotEmpty() }
        }.distinct().joinToString("｜")
        val bg=tags.asSequence().mapNotNull { recTagColors(it).takeIf(String::isNotEmpty) }
            .firstOrNull().orEmpty()
        return text to bg
    }

    fun recTagColors(row: JSONObject): String {
        val style=row.optJSONObject("rec_reason_style")
            ?: jsonObject(row.str("rec_reason_style"))
            ?: return ""
        val colors=style.optJSONArray("rec_style_background_colors")
        if(colors!=null) {
            val values=(0 until colors.length()).mapNotNull { index ->
                colors.optString(index,"").trim().takeIf { it.startsWith("#") }
            }
            if(values.isNotEmpty()) return values.joinToString(",")
        }
        return style.str("rec_style_background_color")
            .trim()
            .takeIf { it.startsWith("#") }
            .orEmpty()
    }

    /**
     * 分类接口的卡片：字段名与 SSR 那套不同（`title` / `cover` / `video_desc`）。
     *
     * **出品方这一条接口自带**（`copyright`），与详情页 `multi-detail` 的 `author` 同源，
     * 因此分类页的卡片不必再为作者发一次请求（见 [publisher]）。
     */
    fun apiCard(item: JSONObject): Series {
        val count=item.optInt("episode_cnt",0)
        val tags=tagsOf(item)
        val tag=tagInfo(item)
        val ranking=officialRankingInfo(item)
        val recommendInfo=rawJsonValue(item,"recommend_info")
        val recommendGroupId=item.str("recommend_group_id").ifBlank {
            runCatching { JSONObject(recommendInfo).optString("gid") }.getOrDefault("")
        }
        return Series(item.str("series_id"),item.str("title"),cleanUrl(item.str("cover")),
            item.str("video_desc"),EpisodeLabel.of(count),tags,
            author=publisher(item.str("copyright")),episodeCount=count,seasonIndex=seasonOf(item),
            vertical=item.optBoolean("vertical",false),
            heat=heatOf(item),
            label=tag?.str("text").orEmpty().trim(),
            labelBg=tagColors(tag),
            recommendInfo=recommendInfo,
            recommendGroupId=recommendGroupId,
            contentType=item.optInt("content_type",0),
            recommendRanking=ranking.first,
            recommendRankingBg=ranking.second,
        )
    }

    /** 保留接口里的 JSON 原文；对象和字符串两种形态都统一成字符串。 */
    fun rawJsonValue(item: JSONObject,key: String): String =
        when(val value=item.opt(key)) {
            is JSONObject -> value.toString()
            is JSONArray -> value.toString()
            null -> ""
            else -> value.toString()
        }

    /**
     * 卡片左上角那个系统角标。
     *
     * 分类接口多数时候直接给 `tag_info`，另一些条目只把它塞在
     * `recommend_reason_list` 这份**字符串副本**里；两处都读，才不会因为服务端换了
     * 数据位置就整片卡片都不打标。两处都没有时返回 null，界面按「平台没给标签」留空。
     */
    fun tagInfo(item: JSONObject): JSONObject? {
        val direct = item.optJSONObject("tag_info") ?: jsonObject(item.str("tag_info"))
        if (direct != null) return direct
        val reason = reasonPayload(item) ?: return null
        return reason.optJSONObject("tag_info") ?: jsonObject(reason.str("tag_info"))
    }

    fun jsonObject(raw: String): JSONObject? =
        raw.trim().takeIf { it.startsWith("{") }
            ?.let { runCatching { JSONObject(it) }.getOrNull() }

    /**
     * 卡片左下角的热度。
     *
     * 分类 / 榜单接口把热度写在 `sub_title_list` 里 `data_type == 27` 的那一段
     *（`9014万`）。榜单页另有 [CatalogPage.ranking] 的原始串可优先使用；分类页没有 ranking，
     * 只能在这里解析进 [Series.heat]，否则左下角永远为空。
     */
    fun heatOf(item: JSONObject): Long {
        fun fromRows(rows: JSONArray?): Long =
            rows?.objects()?.firstOrNull { it.optInt("data_type",-1)==27 }
                ?.str("content")?.let(::heatValue) ?: 0L
        fromRows(item.optJSONArray("sub_title_list")).takeIf { it>0 }?.let { return it }
        fromRows(reasonPayload(item)?.optJSONArray("sub_title_list")).takeIf { it>0 }?.let { return it }
        return heatValue(item.str("rec_text"))
    }

    /** `recommend_reason_list` 可能是对象，也可能是一段 JSON 字符串。 */
    fun reasonPayload(item: JSONObject): JSONObject? =
        item.optJSONObject("recommend_reason_list")
            ?: runCatching { JSONObject(item.str("recommend_reason_list")) }.getOrNull()

    /**
     * 系统角标的背景色：`tag_info.bg_color` 是**字符串数组**。
     *
     * 单色时只有一个元素（`["#00AE83"]` 绿色「新剧」），渐变色时给两个
     * （「爆剧」那类红到深红）；也见过空数组——那时没有自定义色，卡片用默认的深色压暗层。
     * 这里拼成逗号分隔的串交给 `LabelStyle` 解析，保留原始顺序（渐变方向由它决定）。
     */
    fun tagColors(tag: JSONObject?): String {
        val rows=tag?.optJSONArray("bg_color") ?: return ""
        val colors=(0 until rows.length()).mapNotNull { index ->
            rows.optString(index,"").trim().takeIf { it.startsWith("#") }
        }
        return colors.joinToString(",")
    }

    /**
     * 接口给的季号（0 = 没给）。
     *
     * 分类 / 榜单优先取 `sub_title_list` 里 `data_type == 0` 的「第N季」；
     * 详情 / 相关作品取数字字段 `season_index`。搜索实测没有这些字段，只把完整剧名留给
     * [SeriesSeasons] 解析；如果服务端以后补 `sub_title` / `tag_info.text`，这里也会直接采用。
     */
    fun seasonOf(item: JSONObject): Int {
        fun fromRows(rows: JSONArray?): Int {
            rows?.objects()?.forEach { row ->
                val season=seasonFromText(row.str("content"))
                if(season>0) return season
            }
            return 0
        }
        fromRows(item.optJSONArray("sub_title_list")).takeIf { it>0 }?.let { return it }
        runCatching { JSONObject(item.str("recommend_reason_list")).optJSONArray("sub_title_list") }
            .getOrNull()?.let { rows -> fromRows(rows).takeIf { it>0 }?.let { return it } }
        item.optInt("season_index",0).takeIf { it>0 }?.let { return it }
        seasonFromText(item.str("sub_title")).takeIf { it>0 }?.let { return it }
        return seasonFromText(item.optJSONObject("tag_info")?.str("text").orEmpty())
    }

    /** 从 `第3季` / `第三季` / `第3季 · 全80集` 这类接口文案中取季号。 */
    fun seasonFromText(value: String): Int {
        if(value.isBlank()) return 0
        return value.split('·',',','，','|','/','\n')
            .asSequence()
            .map { it.trim() }
            .mapNotNull { token -> SeriesSeasons.parse(token).takeIf { it.matched }?.season }
            .firstOrNull() ?: 0
    }

    /**
     * 出品方：把接口给的 `copyright` 归一成能直接显示的名字。
     *
     * 短剧那一栏里有一部分是**占位串**（`短剧版权3493645060`、`短剧版权5146586586`），
     * 那是平台没填版权方时自动生成的编号，显示出来只会让卡片第二行变成一串数字。
     * 这类一律当空值处理：卡片那一行收起，由 [ContentRepository.authorsOf] 的详情兜底去补。
     */
    fun publisher(raw: String): String {
        val text=raw.trim()
        if(text.isEmpty()) return ""
        return if(Regex("^短剧版权[0-9]{6,}$").matches(text)) "" else text
    }

    /**
     * 题材 / 类型标签：卡片第二行显示的那一串（`玄幻 · 逆袭`）。
     *
     * 三处来源同一个意思，字段名不同：
     * * App 列表 / 搜索 / 详情：`category_schema`（JSON 字符串，`[{"name":"玄幻"}]`）；
     * * 官网搜索：`category_list`（真数组）。
     *
     * 最多取 5 个、` · ` 连接（与详情页信息行同一套写法）。都没有就返回空串，
     * 卡片第二行退回状态行（进度 / 热度），而不是留一条空白。
     */
    fun tagsOf(o: JSONObject, key: String = "category_schema"): String {
        val rows = runCatching { JSONArray(o.str(key)) }.getOrNull()
            ?: o.optJSONArray(key)
        val names = rows?.objects()?.mapNotNull { it.str("name").ifBlank { null } }.orEmpty()
        return names.take(5).joinToString(" · ")
            // 老接口 / 个别 cell 只给一个 `category` 字符串，也认。
            .ifEmpty { o.str("category") }
            // 官网搜索那一套字段。
            .ifEmpty { o.optJSONArray("category_list")?.objects()?.mapNotNull { it.str("name").ifBlank { null } }
                ?.take(5)?.joinToString(" · ").orEmpty() }
    }

    /** 卡片的 `rec_text`（`1204万热度` / `1.2亿热度`）→ 数字；认不出单位一律 0（见 [ContentRepository.detail]）。 */
    fun heatValue(raw: String): Long {
        val text=raw.trim()
        if(text.isEmpty()) return 0
        val hit=HEAT_TEXT.find(text) ?: return 0
        val number=hit.groupValues[1].toDoubleOrNull() ?: return 0
        val unit=when(hit.groupValues[2]) {
            "亿" -> 100_000_000.0
            "万" -> 10_000.0
            // 没有单位就不是热度（`全40集` / `更新至 12 集` 这类角标文案），不参与排序。
            else -> return 0
        }
        return (number*unit).toLong()
    }

    /** 热度文案里的「数字 + 万/亿」；见 [heatValue]。 */
    private val HEAT_TEXT=Regex("([0-9]+(?:\\.[0-9]+)?)\\s*(亿|万)")
}
