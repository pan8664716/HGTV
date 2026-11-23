// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 片尾 30 秒内准备下一集地址与首块。 */
internal fun PhoneFeedView.maybePrefetchNextImpl(position: Long, duration: Long) {
    val lib = feedLibrary ?: return
    val d = feedPlayingDetail ?: return
    val index = feedPlayingIndex
    if (index !in feedItems.indices) return
    val current = feedCurrentEpisodeIndex[index] ?: 0
    val next = current + 1
    if (next > d.episodes.lastIndex) return
    if (duration - position > feedPrefetchWindowMs) return
    val nextId = d.episodes[next]
    val now = System.currentTimeMillis()
    if (nextId == feedPrefetchingFor || now < feedPrefetchAt) return
    if (PhoneSession.nextEpisodeId == nextId) return
    feedPrefetchingFor = nextId
    feedPrefetchAt = now + 5_000L
    val maxQuality = PhoneSession.effectiveMaxQuality ?: lib.maxQuality
    feedScope.launch {
        val remote = withContext(Dispatchers.IO) {
            runCatching {
                val info = feedPlaybackSource.stream(nextId, maxQuality, d.playback, direct=true)
                feedPlaybackSource.prepare(nextId, info)
            }.getOrNull()
        }
        feedPrefetchingFor = ""
        if (remote != null) {
            PhoneSession.putNext(nextId, remote)
            android.util.Log.i(
                "HGTV-PHONE",
                "feed prefetch next id=$nextId episode=${next + 1}",
            )
        }
    }
}
