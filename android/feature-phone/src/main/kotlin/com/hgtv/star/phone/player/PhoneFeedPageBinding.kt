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
    val targetMediaId = feedCurrentEpisodeIds[feedPlayingIndex].orEmpty()
    val playerMediaId = runCatching { exo.currentMediaItem?.mediaId }.getOrNull().orEmpty()
    val sameSource = targetMediaId.isNotEmpty() && targetMediaId == playerMediaId
    page.bind(series, "第${episodeIndex + 1}集")
    applyPageVideoSettingsImpl(page)
    val exoSize = exo.videoSize
    // 换源时 exo 仍是上一路；不能拿它预置新页面的比例或黑边。
    val width = videoInfo?.videoWidth?.takeIf { it > 0 }
        ?: exoSize.width.takeIf { sameSource && it > 0 }
        ?: 0
    val height = videoInfo?.videoHeight?.takeIf { it > 0 }
        ?: exoSize.height.takeIf { sameSource && it > 0 }
        ?: 0
    if (width > 0 && height > 0) page.updateVideoFrame(width, height)
    // 池里每一页都有自己的播放器与 Surface。切到下一页时不能摘掉上一页的
    // player：保留那块 Surface，回看时可以直接恢复，不会重新交接一块空画面。
    if (forceRebind) {
        if (PhoneSession.underlayActive && page.playerView.player === exo) {
            // Surface is already live; do not detach it for a no-op rebind.
        } else {
            if (page.playerView.player === exo) page.playerView.detachPooledOutput()
            page.playerView.player = exo
        }
    } else if (page.playerView.player !== exo) {
        page.playerView.detachPooledOutput()
        page.playerView.player = exo
    }
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
