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
    val series = feedItems.getOrNull(index)
    val episodeIndex = feedCurrentEpisodeIndex[index] ?: 0
    val exo = PhoneServices.player(context, feedLibrary?.decoderMode)
    feedPlayerSlot = exo
    runCatching {
        if (exo.mediaItemCount > 1) exo.removeMediaItems(1, exo.mediaItemCount)
    }
    PhoneSession.nextQueued = false
    PhoneSession.clearNext()

    val page = feedPageAt(index)
    if (page != null && series != null) {
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
    val episodeId = feedCurrentEpisodeIds[index].orEmpty()
    exo.swapMediaSource(episodeId, video.info.url, factory)
    feedRemoteSlot = video
    val d = feedPlayingDetail
    if (d != null) {
        PhoneSession.publish(
            d, episodeIndex, video, video.info.quality,
            PhoneSession.Owner.FEED, feedCacheKey(),
        )
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
        }
    }
    if (page != null) page.playerView.runAfterAspectRatioLayout(prepare) else prepare()
    startProgressTicker()
}
