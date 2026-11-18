// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.feed

import com.hgtv.star.core.ContentRepository
import com.hgtv.star.core.Detail
import com.hgtv.star.core.DetailCache
import com.hgtv.star.core.StageDeadline
import com.hgtv.star.core.detail
import com.hgtv.star.shared.architecture.PlaybackSourceCoordinator
import com.hgtv.star.shared.data.Library
import com.hgtv.star.shared.debug.StartupBudgets

/**
 * 推荐预热的数据链：详情 → 单集直连播放地址。
 *
 * 页面只决定窗口与并发槽位；这里不读取视频字节，也不保留 RemoteVideo。
 */
class RecommendWarmCoordinator(
    private val repository: ContentRepository,
    private val library: Library,
    private val source: PlaybackSourceCoordinator,
) {
    /** 详情优先读落盘；接口拿到后回写缓存。 */
    fun detail(seriesId: String): Detail? {
        DetailCache.load(seriesId)?.let { return it }
        val detail=runCatching {
            StageDeadline("recommend.warm.detail",StartupBudgets.DETAIL_MS).use { deadline ->
                deadline.run { repository.detail(seriesId,includeAuthor=false) }
            }
        }.getOrNull() ?: return null
        if(detail.episodes.isNotEmpty()) DetailCache.save(detail)
        return detail
    }

    /** 只把单集直连地址写入缓存；不解析索引，不读取首块。 */
    fun prefetch(
        detail: Detail,
        episodeId: String = detail.episodes.firstOrNull().orEmpty(),
        quality: Int,
    ) {
        if(episodeId.isEmpty()) return
        source.stream(episodeId,quality,detail.playback,direct=true)
    }
}
