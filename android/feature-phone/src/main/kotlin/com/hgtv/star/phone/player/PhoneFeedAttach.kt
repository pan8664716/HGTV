// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.google.android.exoplayer2.upstream.DataSource
import com.hgtv.star.core.RemoteVideo
import com.hgtv.star.phone.ui.runAfterAspectRatioLayout
import com.hgtv.star.shared.media.VideoDataSource
import com.hgtv.star.shared.media.swapMediaSource

internal fun PhoneFeedView.attachImpl(
    index: Int,
    video: RemoteVideo,
    startAt: Long = 0L,
    clearDanmakuOnSwitch: Boolean = false,
) {
    feedAdvancing = false
    val token = feedPlayToken
    val series = feedItems.getOrNull(index) ?: run {
        runCatching { video.close() }
        return
    }
    val episodeIndex = feedCurrentEpisodeIndex[index] ?: 0
    val episodeId = feedCurrentEpisodeIds[index].orEmpty()
    val slot = ensurePooledPlayerSlot(index, series.id, episodeId)
    slot.detail = feedPlayingDetail
    slot.episodeIndex = episodeIndex
    slot.remote = video
    slot.sourcePrepared = false
    slot.firstFrameRendered = false
    val exo = slot.player
    pausePooledPlayersExcept(exo)
    feedPlayerSlot = exo
    runCatching {
        if (exo.mediaItemCount > 1) exo.removeMediaItems(1, exo.mediaItemCount)
    }
    PhoneSession.nextQueued = false
    PhoneSession.clearNext()

    val page = feedPageAt(index)
    if (page != null) {
        attachPageForSurface(
            exo,
            page,
            series,
            episodeIndex,
            forceRebind = false,
            clearDanmakuOnSwitch = clearDanmakuOnSwitch,
            videoInfo = video.info,
        )
    }

    val factory = DataSource.Factory { VideoDataSource(video) }
    exo.swapMediaSource(episodeId, video.info.url, factory)
    slot.sourcePrepared = false
    feedRemoteSlot = video
    val d = feedPlayingDetail
    if (d != null) {
        publishPooledSession(d, episodeIndex, video, video.info.quality)
    }
    exo.setPlaybackSpeed(feedUserSpeed)
    attachFeedListener(exo, index)
    exo.playWhenReady = !feedPaused
    val prepare = {
        if (
            feedPlayer === exo &&
            feedPlayingIndex == index &&
            token == feedPlayToken
        ) {
            exo.prepare()
            if (startAt > 0) exo.seekTo(startAt)
            slot.sourcePrepared = true
            slot.preparedAt = android.os.SystemClock.elapsedRealtime()
        }
    }
    if (page != null) page.playerView.runAfterAspectRatioLayout(prepare) else prepare()
    startProgressTicker()
    trimPooledPlayers(index)
}
