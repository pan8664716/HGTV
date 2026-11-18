// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

internal data class RelatedEntry(val series: Series, val tag: String)

internal object ContentDetailParser {
    fun parseDetail(raw: String,id: String,author: () -> String): Detail {
        val root=JSONObject(raw)
        if(root.optInt("code",-1)!=0) throw IOException("剧集详情暂不可用（${root.optInt("code")}）")
        val o=root.optJSONObject("data")?.optJSONObject("video_data") ?: throw IOException("剧集详情结构已变化")
        // 未上架的剧（预告 / 预约中）**也是正常响应**：`code 0`、封面简介齐全，
        // 只是 `episode_cnt=0`、`video_list=[]`。早先这里把空分集当异常抛，
        // 用户点开「谁让这个文盲修仙的第六季」就只看到一页错误——现在按预告详情返回。
        val rows=o.optJSONArray("video_list")?.objects()
            ?.sortedBy { it.optInt("vid_index",0) }
            .orEmpty()
        val episodes=rows
            .map { it.str("vid") }
            .filter { it.matches(Regex("[0-9]{1,30}")) }
            .distinct()
        val episodeCovers=rows.mapNotNull { row ->
            val vid=row.str("vid")
            val cover=cleanUrl(row.str("episode_cover").ifBlank { row.str("cover") })
            if(vid in episodes && cover.isNotBlank()) vid to cover else null
        }.toMap()
        // 这两个字段决定 `video_model` 的请求语义。旧缓存没有它们时用红果当前默认值；
        // 新详情必须保留接口值，不能只留下分集 vid。
        val firstVideo=rows.firstOrNull()
        val playback=PlaybackProfile(
            contentType=o.optInt("content_type",firstVideo?.optInt("content_type",1004) ?: 1004),
            videoPlatform=o.optInt("video_platform",3),
        )
        val count=o.optInt("episode_cnt",episodes.size)
        // 有分集却解析不出合法 vid：那是真的数据异常，仍然报错（不能静默变成「预告」）。
        if(episodes.isEmpty() && count>0) throw IOException("分集数据异常")
        val upcoming=episodes.isEmpty()
        // 题材与列表接口同源（`category_schema` 里的 `name`），最多 5 个、 ` · ` 连接。
        val tags=ContentCatalogParser.tagsOf(o)
        // 预告文案：`series_sub_title_list` 里就是「预告 / 全0集 / 287.8万人已预约」这几条，
        // 详情接口本身不带「预计N月N日上线」——那一句只有搜索 / 预约列表有，界面自己补。
        val notice=runCatching { JSONArray(o.str("series_sub_title_list")) }.getOrNull()
            ?.objects()?.mapNotNull { it.str("content") }?.firstOrNull { it.contains("预告") }
            .orEmpty().ifEmpty { if(upcoming) "尚未上线" else "" }
        val series=Series(id,o.str("series_title"),cleanUrl(o.str("series_cover")),o.str("series_intro"),
            EpisodeLabel.normalize(o.str("episode_right_text")).ifEmpty { EpisodeLabel.of(count) },
            tags,author=author(),
            episodeCount=count,seasonIndex=o.optInt("season_index",0),
            upcoming=upcoming,notice=notice)
        return Detail(
            series,
            episodes,
            upcoming,
            reserved=o.flag("online_subscribed"),
            playback=playback,
            episodeCovers=episodeCovers,
        )
    }

    /** 从「相关作品」响应里取出那一栏的卡片（`title` / `cover` / `video_desc` / `episode_cnt`）。 */
    fun parseRelatedRows(data: JSONObject, showType: Int, cellName: String): List<RelatedEntry> {
        val cells=data.optJSONArray("data") ?: return emptyList()
        val rows=linkedMapOf<String,RelatedEntry>()
        for(cell in cells.objects()) {
            if(cell.optInt("show_type")!=showType && cell.str("cell_name")!=cellName) continue
            for(v in (cell.optJSONArray("video_data") ?: JSONArray()).objects()) {
                val seriesId=v.str("series_id")
                val title=v.str("title")
                if(!seriesId.matches(Regex("[0-9]{1,30}")) || title.isBlank()) continue
                val count=v.optInt("episode_cnt")
                // 同一栏里也会出现未上架的那一季：`episode_cnt=0`，`sub_title_list` 里写「预告」。
                // 标成预告态，卡片能正常显示、点进去是预约页而不是错误页。
                val notice=v.optJSONArray("sub_title_list")?.objects()
                    ?.mapNotNull { it.str("content") }?.firstOrNull { it.contains("预告") }.orEmpty()
                if(!rows.containsKey(seriesId)) rows[seriesId]=RelatedEntry(
                    // 相关作品的 `video_data` 也带 `category_schema`：卡片第二行同样显示类型标签。
                    Series(seriesId,title,cleanUrl(v.str("cover")),v.str("video_desc"),
                        EpisodeLabel.of(count),ContentCatalogParser.tagsOf(v),episodeCount=count,
                        seasonIndex=v.optInt("season_index",0),upcoming=count<=0,notice=notice),
                    v.optJSONObject("tag_info")?.str("text").orEmpty()
                )
            }
        }
        return rows.values.toList()
    }

    /** 服务端下发的 tab 表里挑一个 `total>0` 的，优先级由调用方提供。 */
    fun publisherTab(tabs: List<JSONObject>, priority: IntArray): Int? {
        val usable=tabs.filter { it.optInt("total",0)>0 }.map { it.optInt("tab_type",-1) }
        priority.firstOrNull { it in usable }?.let { return it }
        // 服务端加了新命名时按它自己的顺序取第一个有数据的，别再硬编码。
        return tabs.firstOrNull { it.optInt("total",0)>0 }?.optInt("tab_type",-1)?.takeIf { it>0 }
    }

    /** 把 `data_list[].video` 映射成 [Series]；`video_id` 就是 `series_id`。 */
    fun publisherSeries(video: JSONObject): Series? {
        val id=video.str("video_id").ifEmpty { video.str("series_id") }.trim()
        if(!id.matches(Regex("[0-9]{1,30}"))) return null
        val common=video.optJSONObject("common")
        val title=(common?.str("title") ?: video.str("title")).trim()
        if(title.isEmpty()) return null
        val cover=cleanUrl(common?.str("cover") ?: video.str("cover"))
        val intro=common?.str("intro") ?: video.str("intro")
        val stat=video.optJSONObject("stat")
        // 作者留空：这里只知道 uid，署名由调用方按名字统一填，免得「同名不同号」写错。
        return Series(id,title,cover,intro,"","",author="",episodeCount=video.optInt("episode_cnt",
            common?.optInt("episode_cnt",0) ?: 0),heat=stat?.optLong("digg_cnt",0L) ?: 0L,
            playCount=stat?.optInt("play_cnt",0) ?: 0)
    }
}
