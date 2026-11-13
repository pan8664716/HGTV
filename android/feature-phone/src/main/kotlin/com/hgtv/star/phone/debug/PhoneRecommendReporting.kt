// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.content.Context
import com.hgtv.star.core.RecommendBehavior

/**
 * 手机端推荐来源行为的统一出口。
 *
 * 只有当前播放器会话确实来自推荐流、且剧集 id 与推荐卡片一致时才发送；普通详情、
 * 搜索和首页进入的同一部剧不会被误记成推荐行为。
 */
internal fun reportRecommendBehavior(
    context: Context,
    event: RecommendBehavior.Event,
    seriesId: String,
    episodeId: String = "",
    episodeIndex: Int = -1,
    positionMs: Long = -1,
    durationMs: Long = -1,
    reason: String = "",
): Boolean {
    val series=PhoneSession.recommendSeries ?: return false
    if(PhoneSession.feedKey.isEmpty() || series.id!=seriesId) return false
    val data=PhoneSession.detail?.takeIf { it.series.id==series.id }
    val resolvedIndex=episodeIndex.takeIf { it>=0 }
        ?: PhoneSession.index.takeIf { it>=0 }
        ?: -1
    val resolvedEpisodeId=episodeId.ifBlank {
        data?.episodes?.getOrNull(resolvedIndex).orEmpty()
    }
    runCatching {
        RecommendBehavior.record(
            repository=PhoneServices.repository(context),
            series=series,
            event=event,
            episodeId=resolvedEpisodeId,
            episodeIndex=resolvedIndex,
            positionMs=positionMs,
            durationMs=durationMs,
            reason=reason,
        )
    }.getOrNull()
    return true
}
