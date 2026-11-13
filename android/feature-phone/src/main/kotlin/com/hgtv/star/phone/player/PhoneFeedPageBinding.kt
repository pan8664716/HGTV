// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.google.android.exoplayer2.ExoPlayer
import com.hgtv.star.core.Series
import com.hgtv.star.core.StreamInfo
import com.hgtv.star.phone.ui.primeAspectRatio

internal fun PhoneFeedView.attachPageImpl(
    exo: ExoPlayer,
    page: PhoneVideoPage,
    series: Series,
    episodeIndex: Int,
    forceRebind: Boolean,
    clearDanmakuOnSwitch: Boolean = false,
    videoInfo: StreamInfo? = null,
) {
    page.bind(series, "第${episodeIndex + 1}集")
    applyPageVideoSettingsImpl(page)
    page.updateVideoFrame(
        exo.videoSize.width.takeIf { it > 0 } ?: 1,
        exo.videoSize.height.takeIf { it > 0 } ?: 1,
    )
    feedBoundPage?.let { if (it !== page) it.playerView.player = null }
    if (forceRebind) {
        if (PhoneSession.underlayActive && page.playerView.player === exo) {
            // Surface is already live; do not detach it for a no-op rebind.
        } else {
            if (page.playerView.player === exo) page.playerView.player = null
            page.playerView.player = exo
        }
    } else if (page.playerView.player !== exo) {
        page.playerView.player = exo
    }
    val width = videoInfo?.videoWidth?.takeIf { it > 0 } ?: exo.videoSize.width
    val height = videoInfo?.videoHeight?.takeIf { it > 0 } ?: exo.videoSize.height
    val verticalSource = if (width > 0 && height > 0) height > width else series.vertical
    page.playerView.primeAspectRatio(width, height, verticalSource)
    feedBoundPageSlot = page
    applyDanmakuSettingsImpl(page)
    loadDanmakuImpl(
        page,
        feedPlayingIndex,
        feedCurrentEpisodeIds[feedPlayingIndex].orEmpty(),
        series.id,
        clearOnSwitch=clearDanmakuOnSwitch,
    )
    page.setChromeVisible(true)
    scheduleChromeHideImpl(page)
}
