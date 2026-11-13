// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 用一次官方批量接口预取一组播放模型。
 *
 * 推荐页与正式播放页的批量窗口由界面层决定；这里只负责把整组 ID 放进同一次请求，
 * 并把响应写进共享模型缓存，后续 [stream] 直接命中。
 */
fun ContentRepository.prefetchModels(
    ids: List<String>,
    playback: PlaybackProfile = PlaybackProfile(),
): Int = prefetchModelsImpl(ids,playback)
