// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.hgtv.star.core.detail
import com.hgtv.star.core.prefetchModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 与电视端同一套「快速划过 / 划走 / 看过」判定。 */
internal fun PhoneFeedView.settleRecommendWatch() {
    val played = runCatching { feedPlayer?.currentPosition ?: 0L }.getOrDefault(0L)
    runCatching { feedRecommendPlayback.settleWatch(played) }
}

internal fun PhoneFeedView.reportRecommendImpression(index: Int) {
    if (index != feedPlayingIndex) return
    val series = feedItems.getOrNull(index) ?: return
    runCatching {
        val exposureSeries=series.copy(
            contentType=series.contentType.takeIf { it>0 }
                ?: feedPlayingDetail?.playback?.contentType
                ?: 0,
        )
        feedRecommendPlayback.reportImpression(exposureSeries)
        feedRecommendPlayback.reportPlayStart(
            series,
            episodeId=feedCurrentEpisodeIds[index].orEmpty(),
            episodeIndex=feedCurrentEpisodeIndex[index] ?: -1,
            positionMs=runCatching { feedPlayer?.currentPosition ?: 0L }.getOrDefault(0L),
            durationMs=runCatching { feedPlayer?.duration ?: 0L }.getOrDefault(0L),
        )
    }
}

/**
 * 推荐页出画面后，用一次批量接口预取 20 个模型：
 * 当前剧下一集 + 后面 19 部不同推荐剧的首集；消耗 10 条后再补下一批。
 */
internal fun PhoneFeedView.scheduleRecommendBatchModels(anchor: Int) {
    if (feedHidden || anchor != feedPlayingIndex) return
    val data = feedPlayingDetail ?: return
    val current = feedCurrentEpisodeIndex[anchor] ?: return
    if (!feedPlaybackBatchState.shouldSchedule(anchor)) return
    val nextId = data.episodes.getOrNull(current + 1).orEmpty()
    val candidates = (anchor + 1 until feedItems.size)
        .mapNotNull(feedItems::getOrNull)
        .filter { it.id != data.series.id }
        .distinctBy { it.id }
        .take(com.hgtv.star.core.AppConfig.Recommend.BATCH_MODEL_SIZE)
    val key = "${data.series.id}:$nextId:${candidates.joinToString(",") { it.id }}"
    if (feedRecommendBatchKey == key && feedRecommendBatchJob?.isActive == true) return
    feedRecommendBatchJob?.cancel()
    feedRecommendBatchKey = key
    val token = feedPlayToken
    val view=this
    feedRecommendBatchJob = feedScope.launch {
        delay(feedFirstFrameDelay())
        if (feedHidden || anchor != feedPlayingIndex || token != feedPlayToken) return@launch
        val repo = PhoneServices.repository(view.context)
        val ids = linkedSetOf<String>()
        if (nextId.isNotBlank()) ids += nextId
        val target = com.hgtv.star.core.AppConfig.Recommend.BATCH_MODEL_SIZE
        withContext(Dispatchers.IO) {
            for (series in candidates) {
                if (ids.size >= target) break
                val item = runCatching { com.hgtv.star.core.DetailCache.load(series.id) }.getOrNull()
                    ?: runCatching { repo.detail(series.id) }.getOrNull()
                    ?: continue
                val first = item.episodes.firstOrNull() ?: continue
                ids += first
            }
            if (ids.isNotEmpty()) {
                runCatching { repo.prefetchModels(ids.toList(), data.playback) }
            }
        }
        if (com.hgtv.star.shared.BuildConfig.DEBUG) {
            android.util.Log.i(
                "HGTV-PHONE",
                "recommend batch models=${ids.size} anchor=$anchor",
            )
        }
        if (feedRecommendBatchKey == key) {
            feedRecommendBatchKey = ""
            feedRecommendBatchJob = null
        }
    }
}
