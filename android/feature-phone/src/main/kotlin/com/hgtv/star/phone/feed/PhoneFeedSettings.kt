// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.hgtv.star.shared.ui.UiText
import android.view.View
import com.hgtv.star.core.DanmakuMode
import com.hgtv.star.core.toFit
import com.hgtv.star.phone.ui.navigationBarPx
import com.hgtv.star.phone.ui.resizeMode
import kotlinx.coroutines.launch

/** 信息流长按中间打开的播放设置面板。 */
internal fun PhoneFeedView.openSettingsImpl() {
    val lib = feedLibrary
    if (lib == null) {
        feedScope.launch {
            feedLibrarySlot = PhoneServices.awaitLibrary(context)
            feedUserSpeed = feedLibrary?.playbackSpeed ?: feedUserSpeed
            openSettingsImpl()
        }
        return
    }
    feedSheet.bottomInset = context.navigationBarPx()
    feedSheet.show(UiText.get(com.hgtv.star.shared.R.string.ui_0116), landscape = false, heightFraction = .5f) { panel ->
        buildPlaybackSettingsRows(
            host = this,
            panel = panel,
            lib = lib,
            perLine = 0,
            speed = feedUserSpeed,
            onSpeed = { value ->
                feedUserSpeed = value
                lib.playbackSpeed = value
                feedPlayer?.setPlaybackSpeed(feedUserSpeed)
            },
            onQuality = {
                PhoneSession.resetRecoveryOverrides()
                val position = feedPlayer?.currentPosition ?: 0L
                feedSheet.dismiss()
                playIndexForSettings(feedPlayingIndex, position)
            },
            onFrame = { mode ->
                feedBoundPage?.setResizeMode(mode.toFit().resizeMode())
            },
            onDecoder = {
                PhoneSession.resetRecoveryOverrides()
            },
            onScale = { value -> feedBoundPage?.setVideoScale(value / 100f) },
            onDanmaku = {
                val page = feedPageAt(feedPlayingIndex) ?: feedBoundPage
                if (page != null) {
                    val wasHidden = page.danmakuView.mode == DanmakuMode.HIDDEN
                    applyDanmakuSettingsForSettings(page)
                    if (wasHidden && lib.danmakuMode != DanmakuMode.HIDDEN) {
                        val loader = feedDanmakuLoaderOf(feedPlayingIndex)
                        val episode = feedCurrentEpisodeIds[feedPlayingIndex]
                        val series = feedItems.getOrNull(feedPlayingIndex)
                        if (!loader.loaded && episode != null && series != null) {
                            loadDanmakuForSettings(
                                page,
                                feedPlayingIndex,
                                episode,
                                series.id,
                            )
                        }
                    }
                }
            },
            onDebug = {
                (context as? PhoneMainActivity)?.syncDebugOverlay()
            },
        )
    }
    onSheetVisibilityChanged?.invoke(true)
    revealChromeForSettings()
}
