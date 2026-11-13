// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 剧集详情（简介 + 分集列表）：走 App 播放侧的详情接口。
 *
 * ```
 * POST https://api5-normal-sinfonlineb.fqnovel.com/novel/player/video_detail/v1/?<设备 + 签名>
 * body: {"series_id":"<剧集 ID>"}      ← 参数是单值 `series_id`，给 `series_ids` / `book_id` 一律 100001
 * ```
 *
 * `data.video_data` 就是这一部的详情：`series_title` / `series_cover` / `series_intro` /
 * `episode_cnt` / `episode_right_text` / `category_schema`（题材）；**分集在 `video_list[]`**，
 * 每条的 `vid` 就是播放与弹幕要的那个分集 ID，`vid_index` 是集号（1 起，按它排序）。
 *
 * 实测：222 集的那一部一次给全 222 条（一个请求约 145 KB），顺序与集号一致。
 *
 * 简介页下方那一栏**不在这里取**：它是「相关作品」接口（见 [related]），要额外发一次请求，
 * 挂在详情上会让每次进简介页都多等一个 RTT。界面在详情上屏之后后台预取。
 *
 * [cached] 为真时先看落盘缓存（[DetailCache]，30 分钟）：推荐页起播走这一条——
 * 那一页要的只是「第 1 集是哪个 vid」，分集表旧半小时完全不影响起播；
 * 详情页保持默认（现取），用户在那里读的是集数与更新。
 *
 * [includeAuthor] 为 false 时跳过作者补查：详情页先拿到主体内容上屏，
 * 作者由界面在后台补，不再让一个附加字段挡住整页。
 */
fun ContentRepository.detail(
    id: String,
    cached: Boolean=false,
    includeAuthor: Boolean=true,
): Detail =
    detailImpl(id,cached,includeAuthor)

fun ContentRepository.related(id: String, title: String = ""): RelatedWorks =
    relatedImpl(id,title)
