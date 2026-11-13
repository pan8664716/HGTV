// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 取一集的弹幕，也就是「原 App」播放时看到的同一份数据。
 *
 * 接口是播放器自己的评论接口：`POST /novel/commentapi/comment/list/{vid}/v1/`，
 * 请求体带 `comment_source=601`（短剧）、`server_channel=1000`、`group_type=30`、`comment_type=20`，
 * `business_param` 里再给剧集 ID 与本集时长；响应按 30 秒一个窗口返回，
 * `extra.next_query_danmaku_list_time` 是下一个窗口的起点，`common_list_info.has_more` 表示还没到片尾。
 *
 * [durationSeconds] 是这一集的时长（秒），用来判断何时停：为 0 时按窗口数量上限兜底。
 * [onWindow] 每拉到一个窗口就被回调一次（传当前已收集的全部弹幕，按时间排好），
 * 让播放界面边拉边画：整集拉完要十几秒，等全量再上屏等于前十几秒一条弹幕都没有。
 * 回调跑在调用线程上，调用方自己负责切回主线程。
 * 网络失败或平台不再返回弹幕时返回空列表，播放界面不显示任何弹幕，也不弹错误。
 */
fun ContentRepository.danmaku(
    vid: String,
    seriesId: String,
    durationSeconds: Long = 0,
    shouldStop: () -> Boolean = { false },
    onWindow: (List<Danmaku>) -> Unit = {},
): List<Danmaku> =
    danmakuImpl(vid,seriesId,durationSeconds,shouldStop,onWindow)

/**
 * 调试用：一个窗口的原始 `data_list`。
 *
 * 只看接口到底发了什么字段（表情是不是只有文本一种形式），联调表情表时用，界面不调用。
 */
fun ContentRepository.danmakuRawWindow(
    vid: String,
    seriesId: String,
    startMs: Long=0,
): List<org.json.JSONObject> =
    danmakuRawWindowImpl(vid,seriesId,startMs)
