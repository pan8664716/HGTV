// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.google.android.exoplayer2.upstream.DataSource
import com.hgtv.star.shared.media.VideoDataSource
import com.hgtv.star.shared.media.swapMediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun PhoneFeedView.reopenAtCurrentPositionImpl(index: Int): Boolean {
    if (!feedSessionBelongsToThisFeed()) return false
    if (index !in feedItems.indices) return false
    val series = feedItems[index]
    if (!PhoneSession.live(series.id)) return false
    val d = PhoneSession.detail ?: return false
    val episodeIndex = PhoneSession.index.takeIf { it in d.episodes.indices } ?: return false
    val episodeId = d.episodes.getOrNull(episodeIndex) ?: return false
    val exo = feedPlayer ?: PhoneServices.peekPlayer() ?: return false
    // 位置优先问播放器（最准），拿不到才用会话里每秒刷新的那个读数。
    val at = runCatching { exo.currentPosition }.getOrDefault(0L)
        .takeIf { it > 0 } ?: PhoneSession.positionMs
    // 换地址不改播放 / 暂停状态：暂停着退回来，换完还是暂停。
    val wantPlay = if (PhoneSession.inHandoff()) {
        PhoneSession.resumeOnAdopt
    } else {
        exo.playWhenReady
    }
    feedPlayerSlot = exo
    feedPlayingIndex = index
    feedPlayingDetailSlot = d
    feedCurrentEpisodeIndex[index] = episodeIndex
    feedCurrentEpisodeIds[index] = episodeId
    val token = ++feedPlayTokenSlot
    feedScope.launch {
        val lib = feedLibrary ?: PhoneServices.awaitLibrary(context).also {
            feedLibrarySlot = it
        }
        val maxQuality = PhoneSession.effectiveMaxQuality ?: lib.maxQuality
        val prepared = withContext(Dispatchers.IO) {
            runCatching {
                val info = feedPlaybackSource.stream(episodeId, maxQuality, d.playback, direct=true)
                feedPlaybackSource.prepare(episodeId, info)
            }.getOrNull()
        }
        if (prepared == null) {
            // 换地址失败（断网 / 接口抖动）：退回常规起播，别把用户停在一张死画面上。
            if (token == feedPlayToken) playIndexForSettings(index, -1L)
            return@launch
        }
        if (token != feedPlayToken || !isAttachedToWindow) {
            prepared.close()
            return@launch
        }
        // **不碰 `PlayerView`**：Surface 还是那一块，画面不会黑一下。
        val factory = DataSource.Factory { VideoDataSource(prepared) }
        exo.swapMediaSource(episodeId, prepared.info.url, factory)
        adoptPooledPlayerSlot(
            index = index,
            player = exo,
            seriesId = series.id,
            episodeId = episodeId,
            detail = d,
            episodeIndex = episodeIndex,
            remote = prepared,
        )
        pooledPlayerSlot(index)?.sourcePrepared = false
        pausePooledPlayersExcept(exo)
        feedRemoteSlot = prepared
        publishPooledSession(d, episodeIndex, prepared, prepared.info.quality)
        exo.setPlaybackSpeed(feedUserSpeed)
        exo.prepare()
        pooledPlayerSlot(index)?.let {
            it.sourcePrepared = true
            it.preparedAt = android.os.SystemClock.elapsedRealtime()
        }
        if (at > 0) exo.seekTo(at)
        exo.playWhenReady = wantPlay
        notifyPlayStateChangedImpl()
        android.util.Log.i(
            "HGTV-PHONE",
            "feed reopen in place episode=${episodeIndex + 1} at=$at"
        )
    }
    return true
}
