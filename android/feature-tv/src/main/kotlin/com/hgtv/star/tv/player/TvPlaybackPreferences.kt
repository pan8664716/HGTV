// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import com.hgtv.star.core.VideoDecoderMode

/**
 * 本次播放**实际生效**的清晰度上限。
 *
 * 优先级：会话层降档结果 > 用户设置。所有「取地址 / 预热 / 面板显示 / 索引缓存」的
 * **读取**都必须走这里，否则自动降档只在当前这一集生效，下一集立刻回到用户设置、
 * 再撞一次同样的失败（这正是要修的循环）。
 *
 * 写 `library.maxQuality` 的地方只允许是用户手动选择（见 `MainActivityPlaybackMenu`）。
 */
val TvMainActivity.playbackMaxQuality: Int
    get()=playbackSessionState.effectiveMaxQuality ?: library.maxQuality

/**
 * 本次播放实际生效的解码方式。
 *
 * 自动恢复选择的软解只存在当前会话；用户手动改设置时清掉会话覆盖并回到用户设置。
 */
val TvMainActivity.playbackDecoderMode: VideoDecoderMode
    get()=playbackSessionState.effectiveDecoderMode ?: library.decoderMode

/**
 * 下一集预热槽的键：集 + 上限。
 *
 * 三处（预取写入 / 挂队列取出 / tick 兜底）必须用**同一个**字符串。
 * 全站只有 HEVC 一种编码，所以键里不再需要编码族那一段。
 */
fun TvMainActivity.preparedKeyFor(id: String): String =
    "$id:${playbackMaxQuality}"

/**
 * 建播放器时该用的解码方式。
 *
 * 读取会话覆盖后的实际值；自动恢复写会话，设置页写用户偏好。
 */
fun TvMainActivity.playerDecoderMode(): VideoDecoderMode = playbackDecoderMode
