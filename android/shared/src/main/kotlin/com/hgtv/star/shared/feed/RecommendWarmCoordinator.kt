// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.feed

import com.hgtv.star.core.CacheLevel
import com.hgtv.star.core.ContentRepository
import com.hgtv.star.core.Detail
import com.hgtv.star.core.DetailCache
import com.hgtv.star.core.RemoteVideo
import com.hgtv.star.core.detail
import com.hgtv.star.shared.architecture.PlaybackSourceCoordinator
import com.hgtv.star.shared.data.Library

/**
 * 推荐预热的数据链：详情 → 播放地址 → 索引 / 首块。
 *
 * 页面只决定窗口、并发槽位和结果存放在哪里；预热本身的顺序与档位语义只写在这里。
 */
class RecommendWarmCoordinator(
    private val repository: ContentRepository,
    private val library: Library,
    private val source: PlaybackSourceCoordinator,
) {
    /** 详情优先读落盘；接口拿到后回写缓存。 */
    fun detail(seriesId: String): Detail? {
        DetailCache.load(seriesId)?.let { return it }
        val detail=runCatching { repository.detail(seriesId) }.getOrNull() ?: return null
        if(detail.episodes.isNotEmpty()) DetailCache.save(detail)
        return detail
    }

    /**
     * 按缓存级别预热一集。
     *
     * ADDRESS / META 返回 null（地址或索引已经写盘）；FULL 返回可交给播放器的
     * [RemoteVideo]，所有权归调用方。
     */
    fun warm(
        detail: Detail,
        episodeId: String = detail.episodes.firstOrNull().orEmpty(),
        quality: Int,
        level: CacheLevel,
    ): RemoteVideo? {
        if(level==CacheLevel.NONE || episodeId.isEmpty()) return null
        val info=source.stream(episodeId,quality,detail.playback)
        return when(level) {
            CacheLevel.NONE, CacheLevel.ADDRESS -> null
            CacheLevel.META -> source.warm(episodeId,info) { it.warmMeta() }
            CacheLevel.FULL -> source.warm(episodeId,info) { it.warm(512L*1024) }
        }
    }
}
