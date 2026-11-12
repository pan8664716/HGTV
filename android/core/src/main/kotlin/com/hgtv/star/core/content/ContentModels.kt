// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.json.JSONObject

data class Series(
    val id: String,
    val title: String,
    val cover: String = "",
    val description: String = "",
    val badge: String = "",
    val tags: String = "",
    /**
     * 作者 / 版权方（出品方）。
     *
     * 它来自 App 的批量详情接口
     * `GET /reading/bookapi/multi-detail/v?book_id=<id>&book_type=2` 的 `author` 字段
     *（同一条里还有 `publisher` / `source` / `copyright_info`，实测 `author` 最稳定）。
     * 列表接口不带它，因此只有从详情页进来的卡片才显示（见 [ContentRepository.detail]）。
     */
    val author: String = "",
    /**
     * 总集数（0 = 未知）。
     *
     * 卡片的右下角角标统一显示它（见 `PosterCard`）；列表接口大多带 `episode_cnt`，
     * 详情页带 `vid_list.size`，两处都写进这个字段，界面就不用再猜 `badge` 里那串文案。
     */
    val episodeCount: Int = 0,
    /**
     * 第几季（0 = 接口没给）。
     *
     * 分类 / 榜单给 `sub_title_list`，详情 / 相关作品给 `season_index`。
     * 搜索接口实测不返回显式季号字段，季号写在 `video_data.title` 里；
     * 因此界面优先用接口字段，搜索等缺失场景再由剧名解析兜底（见 [SeriesSeasons]）。
     */
    val seasonIndex: Int = 0,
    /**
     * 还没上架（预告 / 预约中）。
     *
     * 平台对这类剧**照常给卡片**（搜索、相关作品里都有），但详情接口的 `episode_cnt=0`、
     * `video_list` 为空。旧实现把「没有分集」当异常，点进去就是错误页；
     * 现在按这个标记进「预告详情」：正常显示简介与封面，只是不摆选集、按钮换成「预约」。
     */
    val upcoming: Boolean = false,
    /** 预告文案（`预计11月6日上线` / `即将上线，火速预约` / `预告`）；没有就是空串。 */
    val notice: String = "",
    /**
     * 这一部是不是**竖屏**（接口卡片里的 `vertical` 字段，分类 / 榜单 / 搜索都带）。
     *
     * 接口**没有**「只要横屏」这样的筛选参数：`select_items.vertical` 会被忽略（实测
     * `["0"]` / `["1"]` 回同一批数据），`filter_ids` 传什么都不认。所以只能在客户端按这个
     * 字段筛。默认 false（老数据 / 备份里没有这个字段时按横屏算）。
     */
    val vertical: Boolean = false,
    /**
     * 热度（`0` = 接口没给）。
     *
     * 分类 / 榜单接口取 `sub_title_list` 里 `data_type == 27` 的文案（`9014万`），
     * 搜索接口取卡片自带的 `rec_text`（实测形如 `1204万热度`），这里统一换算成数字。
     * 榜单页另有服务端原串可优先使用；分类 / 搜索卡片由 [HeatLabel] 把它格式化成角标。
     */
    val heat: Long = 0,
    /**
     * 播放次数（`0` = 接口没给）。
     *
     * 搜索卡片的 `play_cnt`。与 [heat] 一样只当排序信号：热度缺失时用它兜底，
     * 两者都没有就退回接口返回的顺序。
     */
    val playCount: Int = 0,
    /**
     * 系统给的角标文案（`新剧` / `红果首发` / `爆剧`…）；没有就是空串。
     *
     * 来自卡片数据里的 `tag_info.text`，由服务端决定这一部该打什么标。
     * 空串时卡片左上角退回显示名次（见 `PosterCard.bind`）。
     */
    val label: String = "",
    /**
     * [label] 那个角标的背景色：`#00AE83`，**多个用逗号分隔表示渐变**（`#E8402A,#9C1B10`）。
     *
     * 服务端在 `tag_info.bg_color` 里给的是一个字符串数组（单色时只有一个元素，
     * 渐变色时给两个）。这里原样拼成一个串存着，由 `LabelStyle` 负责解析与兜底——
     * 不解析成 Int 是因为它要跟着 [toJson] 落盘，字符串最省事。
     */
    val labelBg: String = "",
    /**
     * 推荐流卡片上的原始 `recommend_info`。
     *
     * 上报推荐曝光时服务端需要原样拿回这一段，不能只拿页面展示字段。
     * 普通详情 / 搜索卡片没有这个字段，保持空串。
     */
    val recommendInfo: String = "",
    /** 推荐系统为本次候选生成的推荐分组 ID；分类卡片通常可从 `recommend_info.gid` 取到。 */
    val recommendGroupId: String = "",
    /** 内容类型，来自服务端内容模型；未知时为 0，不能伪造默认值。 */
    val contentType: Int = 0,
    /**
     * 推荐卡片自带的排行榜提示，例如 `真人剧·必看月榜 No.10` / `AI剧热播榜 No.10`。
     *
     * 它来自 `recommend_reason_list.rec_tags[]`，与普通分类标签分开展示。
     */
    val recommendRanking: String = "",
    /** 排行榜提示的官方背景色，多个颜色用逗号分隔；空串表示没有自定义样式。 */
    val recommendRankingBg: String = "",
) {
    fun toJson() = JSONObject().put("id",id).put("title",title).put("cover",cover)
        .put("description",description).put("badge",badge).put("tags",tags).put("author",author)
        .put("episodeCount",episodeCount).put("seasonIndex",seasonIndex)
        .put("upcoming",upcoming).put("notice",notice).put("vertical",vertical)
        .put("heat",heat).put("playCount",playCount)
        .put("label",label).put("labelBg",labelBg)
        .put("recommendInfo",recommendInfo).put("recommendGroupId",recommendGroupId)
        .put("contentType",contentType)
        .put("recommendRanking",recommendRanking)
        .put("recommendRankingBg",recommendRankingBg)
    companion object {
        fun fromJson(o: JSONObject) = Series(o.str("id"),o.str("title"),o.str("cover"),
            o.str("description"),o.str("badge"),o.str("tags"),o.str("author"),o.optInt("episodeCount"),
            o.optInt("seasonIndex"),o.optBoolean("upcoming",false),o.str("notice"),
            o.optBoolean("vertical",false),o.optLong("heat",0L),o.optInt("playCount",0),
            o.str("label"),o.str("labelBg"),o.str("recommendInfo"),o.str("recommendGroupId"),
            o.optInt("contentType",0),o.str("recommendRanking"),o.str("recommendRankingBg"))
    }
}

/**
 * 播放模型要求的视频类型与平台。
 *
 * 这两个值来自 `video_detail.data.video_data`，必须原样带给 `video_model`。
 * 红果短剧当前是 `MotionComic(1004) + Outer(3)`；默认值只用于旧缓存和探测工具，
 * 正常详情链路会使用接口返回值。
 */
data class PlaybackProfile(
    val contentType: Int = 1004,
    val videoPlatform: Int = 3,
) {
    fun toJson() = JSONObject().put("contentType",contentType).put("videoPlatform",videoPlatform)
    companion object {
        fun fromJson(o: JSONObject?) = PlaybackProfile(
            contentType=o?.optInt("contentType",1004) ?: 1004,
            videoPlatform=o?.optInt("videoPlatform",3) ?: 3,
        )
    }
}

/**
 * 详情页的数据；[upcoming] 为真时 [episodes] 一定是空的（还没上架，没有分集）。
 *
 * [reserved] 是「我预约过这一部没有」，来自详情接口的 `online_subscribed`
 *（真机抓包 2026-10-19：预约前后这个字段确实会翻）。界面据此画「预约 / 已预约」。
 */
data class Detail(
    val series: Series,
    val episodes: List<String>,
    val upcoming: Boolean = false,
    val reserved: Boolean = false,
    val playback: PlaybackProfile = PlaybackProfile(),
)

/**
 * 简介页下方那一栏的内容：同系列各季在前（按季号升序），其余相关作品在后，已去重。
 *
 * **不随详情一起返回**：那一栏要额外发一次 App 请求，而详情页每次进都要开一次，
 * 把它挂在详情上会让「进简介页」变慢。界面在详情上屏之后后台预取（见 `prefetchRelated`）。
 */
data class RelatedWorks(
    val items: List<Series>,
    /** 其中属于**同一部剧**的那些季（按季号升序，含正在看的这一季）：界面据此标注「第 N 季」。 */
    val seasons: List<Series>
)

/**
 * 一条搜索提示词（联想词）。
 *
 * [series] 非空表示这条提示词**本身就是一部剧**（服务端给了 `video_data.series_id`）：
 * 点它可以跳过搜索直接进详情；为空时它只是一个查询词（`common_query` / `title` / `album` /
 * 分类这类），点它就拿 [name] 去搜。
 */
data class SearchSuggestion(
    val name: String,
    val series: Series? = null,
    /** 服务端的 `word_type`：`short_play_name` / `title` / `common_query` / `album` / `category` 等。 */
    val wordType: String = ""
)

data class RankPosition(val rank: Int?, val heat: String)
data class ComicRanking(val positions: Map<String,RankPosition>, val totalPages: Int, val updatedText: String)
data class CatalogPage(val items: List<Series>, val hasMore: Boolean, val ranking: ComicRanking? = null)

/**
 * 推荐流的一页 + 续流游标。
 *
 * 推荐流是**有状态**的：同一个 `session_id` 配上一页返回的 `next_offset` 继续请求，
 * 才会拿到下一批；`session_id` 传空、`offset` 每次归零，服务端就永远回同一套默认列表
 * （实测：连续 8 次 `offset=0&session_id=""`，返回的 20 条完全一致）。
 * 因此客户端必须把这两个值记下来，下次进推荐接着往下取，而不是从头再来。
 */
data class RecommendFeed(val page: CatalogPage, val sessionId: String, val nextOffset: Int)

/**
 * 一次取流的结果。
 *
 * [quality] 仍然是给人看的那串（`720P` / `1080P（兼容资源）`），界面与缓存格式都用它；
 * 后面几个字段是**结构化**的同一份事实，供降级决策使用（见 [PlaybackDegradation]）：
 *
 * * [requestedQuality]：用户设的上限；
 * * [actualQuality]：真正选中那一档（认不出时为 0）；
 * * [available]：这一次响应里**所有可播档位**（已剔除 `bytevc2` / 非 HEVC / 空地址 / 认不出的），
 *   降档只能从它里面挑，不能硬编码 480。
 *
 * **全站只有一种视频编码（HEVC）**，因此这里不再记录「这一档是什么编码」：
 * 早期那一维（HEVC ↔ AVC 换编码族）已经整维删除，见 `PlaybackDegradation` 的类注释。
 */
data class StreamInfo(
    val url: String,
    val key: ByteArray?,
    val quality: String,
    val requestedQuality: Int = 0,
    val actualQuality: Int = 0,
    val available: List<Int> = emptyList(),
    /** 同一档位的备用 CDN 地址；主地址网络失败或 403 时按顺序切换。 */
    val backupUrls: List<String> = emptyList(),
    /** 接口声明的实际画面宽高；0 表示旧缓存或接口未提供。 */
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
) {
    /**
     * 索引 / 首块的缓存键：集 id + **实际档位**。
     *
     * 为什么不用请求上限：CDN 在「只有兼容 1080」与「有 720」之间变动时，请求上限没变、
     * 文件却换了；按上限做键的话旧索引会撞上新文件，只能靠 `VideoChangedException`
     * 兜一轮才自愈。按实际档位做键，换档就是换键，天然不会串。
     */
    fun mediaKey(id: String): String =
        "$id:${if(actualQuality>0) actualQuality else requestedQuality}"
}
