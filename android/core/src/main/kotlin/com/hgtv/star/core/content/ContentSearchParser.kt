// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.json.JSONArray
import org.json.JSONObject

internal object ContentSearchParser {
    data class ParsedTab(
        val cells: List<JSONObject>,
        val searchId: String,
        val passback: String,
        val nextOffset: Int?,
        val hasMore: Boolean,
    )

    fun parseWebSuggestions(raw: String, wanted: Int): List<SearchSuggestion> {
        // 新接口的字段叫 `suggestionList`；老接口（`/incent_resource/suggestion`）叫 `suggest_list`。
        // 两个都认：接口再改名时不至于整行联想词直接消失。
        val rows=runCatching {
            val data=JSONObject(raw)
            (data.optJSONArray("suggestionList") ?: data.optJSONArray("suggest_list"))?.objects().orEmpty()
        }.getOrDefault(emptyList())
        return rows.mapNotNull { row ->
            val name=row.str("name").ifEmpty { row.optJSONArray("display_words")?.optString(0).orEmpty() }.trim()
            if(name.isEmpty()) return@mapNotNull null
            val video=row.optJSONObject("video_data")?.takeIf { it.length()>0 }
            val series=video?.let { webCard(it) }?.takeIf {
                it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank()
            }
            SearchSuggestion(name,series,row.str("word_type"))
        }.distinctBy { it.name }.take(wanted)
    }

    fun parseAppSuggestions(raw: String, wanted: Int): List<SearchSuggestion> {
        val body=JSONObject(raw)
        val data=body.optJSONObject("data") ?: body
        val v2=data.optJSONArray("query_result_v2")?.objects().orEmpty()
        val legacy=data.optJSONArray("query_result")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val item=array.opt(index)
                when(item) {
                    is String -> JSONObject().put("name",item)
                    is JSONObject -> item
                    else -> null
                }
            }
        }.orEmpty()
        val rows=v2.ifEmpty { legacy }
        return rows.mapNotNull { row ->
            val name=row.str("name").ifEmpty { row.optJSONArray("display_words")?.optString(0).orEmpty() }.trim()
            if(name.isEmpty()) return@mapNotNull null
            // `video_data` 里没有 `series_id` 的那些（title / common_query 这类）不算剧集，只能当查询词。
            val video=row.optJSONObject("video_data")?.takeIf { it.length()>0 }
            val series=video?.let(ContentCatalogParser::apiCard)
                ?.takeIf { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() }
            SearchSuggestion(name,series,row.str("word_type_str"))
        }.distinctBy { it.name }.take(wanted)
    }

    fun parseWebSearchPage(
        raw: String,
        page: Int,
        pageSize: Int,
        maxCatalogPage: Int,
    ): CatalogPage {
        val data=JSONObject(raw)
        // 失败时接口回的是 `{"code":...,"message":...}`（HTTP 可能是 200 / 400 / 502）：
        // - `invalid_query`：关键词为空或超过 30 字，按「没有结果」处理；
        // - `upstream_unavailable`：官网搜索后端瞬时不可用，重试已经用过了，这一页算失败
        //  （[ContentRepository.webSearch] 里十个物理页全失败才算整页失败，部分失败照常给结果）。
        if(data.has("code")) {
            if(data.optString("code")=="invalid_query") return CatalogPage(emptyList(),false)
            throw java.io.IOException("官网搜索暂不可用（${data.optString("code")}）")
        }
        val rows=data.optJSONArray("searchList") ?: return CatalogPage(emptyList(),false)
        // 超出末页时服务端不报错，而是**把整份结果一次性塞回来**（实测 page=99 时 216 条、
        // 且 totalPages 被改写成 99）：那已经不是「第 N 页」，照收会让列表里多出几百条
        // 与前面重复的内容。一页正常最多 10 条，明显超量就按「没有更多」处理。
        if(rows.length()>pageSize*3) return CatalogPage(emptyList(),false)
        val items=ContentCatalogParser.valid(rows.objects().map(::webCard))
        val totalPages=data.optInt("totalPages",1).coerceIn(1,maxCatalogPage)
        val more=data.optBoolean("hasMore",page<totalPages) && page<totalPages
        return CatalogPage(items,more)
    }

    /** 官网搜索的卡片：`video_data` 与详情页同源（`series_title` / `series_cover` / `series_intro`）。 */
    fun webCard(item: JSONObject): Series {
        val o=item.optJSONObject("video_data")?.takeIf { it.length()>0 } ?: item
        val count=o.optInt("episode_cnt",0)
        val tags=ContentCatalogParser.tagsOf(o,"category_list")
        return Series(
            id=o.str("series_id").ifEmpty { item.str("keyword") },
            title=o.str("series_title").ifEmpty { item.str("name") },
            cover=cleanUrl(o.str("series_cover")),
            description=o.str("series_intro"),
            badge=EpisodeLabel.normalize(o.str("episode_right_text")).ifEmpty { EpisodeLabel.of(count) },
            tags=tags,episodeCount=count,seasonIndex=ContentCatalogParser.seasonOf(o))
    }

    /**
     * 搜索卡片标题。
     *
     * `search_high_light` 只用于展示命中词，个别设备返回的文本会丢掉剧名尾部的季号。
     * 原始 `video_data.title` 才是完整剧名，因此必须优先用它；高亮文本仅作兜底。
     */
    fun searchTitle(row: JSONObject,video: JSONObject): String {
        val canonical=video.str("title")
        if(canonical.isNotBlank()) return canonical
        val highlighted=row.optJSONObject("search_high_light")
            ?.optJSONObject("title")?.str("text").orEmpty()
            .replace(SEARCH_HIGHLIGHT,"").trim()
        return highlighted.ifEmpty { row.str("cell_name") }
    }

    /** 从一次响应里挑出要展示的 tab，并把游标与卡片结构一起带出来。 */
    fun parseTab(raw: String,tabType: Int,feedTab: Int): ParsedTab {
        val data=JSONObject(raw)
        val code=data.optInt("code",-1)
        if(code!=0) throw SearchApiException(SearchDiag.FAILURE_CODE,"code=$code")
        val tabs=data.optJSONArray("search_tabs") ?: data.optJSONObject("data")?.optJSONArray("search_tabs")
            ?: throw SearchApiException(SearchDiag.FAILURE_FORMAT,"没有 search_tabs")
        val rows=tabs.objects()
        val picked=(if(tabType==feedTab) rows.firstOrNull { (it.optJSONArray("data")?.length() ?: 0)>0 } else null)
            ?: rows.firstOrNull { it.optInt("tab_type")==tabType }
            ?: if(tabType==feedTab) rows.firstOrNull() else null
        val tab=picked ?: throw SearchApiException(SearchDiag.FAILURE_TAB,"缺少可用 search tab")
        return ParsedTab(
            (tab.optJSONArray("data") ?: JSONArray()).objects(),tab.str("search_id"),tab.str("passback"),
            tab.str("next_offset").toIntOrNull(),tab.optBoolean("has_more",false)
        )
    }

    /** 搜索 tab 里的卡片 cell（含 lynx 内嵌 cell）统一转成列表卡片。 */
    fun cards(tab: ParsedTab): List<Series> {
        val list=mutableListOf<Series>()
        for(cell in tab.cells) {
            val cells=mutableListOf(cell)
            runCatching { JSONObject(cell.str("lynx_data")).optJSONArray("cell_data")?.objects() }.getOrNull()?.let { cells.addAll(it) }
            for(row in cells) {
                val video=row.optJSONArray("video_data")?.objects()?.firstOrNull()
                if(video!=null) {
                    val id=video.str("series_id").ifEmpty { row.str("book_id").ifEmpty { row.str("search_result_id") } }
                    val title=searchTitle(row,video)
                    val count=video.optInt("episode_cnt")
                    val seasonIndex=ContentCatalogParser.seasonOf(video).takeIf { it>0 }
                        ?: ContentCatalogParser.seasonOf(row)
                    // 出品方：搜索接口的 `video_data` 也带 `copyright`（与分类 / 详情同源），
                    // 直接取，卡片第二行就不用再为这一屏发一次 `multi-detail`。
                    // 类型标签：搜索的 `video_data` 与列表接口同源（`category_schema`）。
                    // 拿不到就留空，卡片第二行退回状态行（热度 / 进度），不拿季号顶替。
                    val tags=ContentCatalogParser.tagsOf(video)
                    list+=Series(
                        id,
                        title,
                        cleanUrl(video.str("cover").ifEmpty { video.str("cover_url") }),
                        video.str("video_desc"),
                        EpisodeLabel.of(count).ifEmpty { video.str("rec_text") },
                        tags,
                        author=ContentCatalogParser.publisher(video.str("copyright")),
                        episodeCount=count,
                        seasonIndex=seasonIndex,
                        // 热度 / 播放量：卡片可展示，也是链路里唯一能拿到的量级信号（不再用于本地排序）。
                        heat=ContentCatalogParser.heatValue(video.str("rec_text")),
                        playCount=video.optInt("play_cnt"),
                    )
                    continue
                }
                // 未上架的剧在搜索里是另一种 cell：`show_type=462`，没有 `video_data`，
                // 数据在 `subscribe_items[]` 里（`item_id` / `name` / `cover` / `sub_info`）。
                // 早先这里直接 `continue`，于是「谁让这个文盲修仙的第六季」这种卡片根本不出现，
                // 用户只能从别处点进来、再撞上详情页的 404。
                val sub=row.optJSONArray("subscribe_items")?.objects()?.firstOrNull() ?: continue
                val id=sub.str("item_id").ifEmpty { sub.str("item_id_str") }
                val title=sub.str("name")
                val tags=ContentCatalogParser.tagsOf(sub)
                // `sub_info` 是「预计11月6日上线」这种最有用的一句；没有就退到「预告」角标。
                val notice=sub.str("sub_info").ifEmpty { sub.optJSONObject("tag_info")?.str("text").orEmpty() }
                list+=Series(
                    id,
                    title,
                    cleanUrl(sub.str("cover")),
                    sub.str("item_desc"),
                    notice,
                    tags,
                    episodeCount=0,
                    upcoming=true,
                    notice=notice,
                )
            }
        }
        return ContentCatalogParser.valid(list)
    }

    private val SEARCH_HIGHLIGHT = Regex("</?em>",RegexOption.IGNORE_CASE)
}
