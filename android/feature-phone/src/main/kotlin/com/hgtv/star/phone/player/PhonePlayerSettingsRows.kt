// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.hgtv.star.shared.ui.UiText
import android.view.View
import android.widget.LinearLayout
import com.hgtv.star.core.DanmakuMode
import com.hgtv.star.shared.data.Library

/**
 * 播放设置的各行：清晰度、倍速、画面、解码、缩放、定时、相关剧集、收藏与弹幕。
 * 竖屏和横屏共用同一份控件顺序与回写逻辑。
 */
internal fun PhonePlayerActivity.buildSettingsRowsImpl(
    panel: LinearLayout,
    lib: Library,
    perLine: Int,
) {
    val series = detail?.series
    buildPlaybackSettingsRows(
        host = root,
        panel = panel,
        lib = lib,
        perLine = perLine,
        speed = sessionUserSpeed,
        onSpeed = { value ->
            chipUserSpeed = value
            lib.playbackSpeed = value
            player?.setPlaybackSpeed(chipUserSpeed)
            syncChips()
        },
        onQuality = {
            playbackSessionState.onManualQualityChange()
            syncChips()
            play(index, player?.currentPosition ?: 0L)
        },
        onFrame = { mode ->
            applyFrameModeForSettings(mode)
            syncChips()
        },
        onScale = {
            applyVideoScaleForSettings()
            syncChips()
        },
        onDecoder = {
            playbackSessionState.onManualDecoderChange()
            syncChips()
        },
        sleepChoice = if (nextSeasonSleepTimer.active) playerSleepChoice else 0,
        onSleep = { choice -> applySleepChoiceForSettings(choice) },
        onRelated = {
            val d = detail
            if (d == null) {
                toast(UiText.get(com.hgtv.star.shared.R.string.ui_0237))
            } else {
                playerSheet.dismiss()
                dismissLandscapeSettingsForRelated()
                showRelated(d, UiText.get(com.hgtv.star.shared.R.string.ui_0191), UiText.get(com.hgtv.star.shared.R.string.ui_0109))
            }
        },
        onAuthor = {
            val d = detail
            if (d == null) {
                toast(UiText.get(com.hgtv.star.shared.R.string.ui_0237))
            } else {
                playerSheet.dismiss()
                dismissLandscapeSettingsForRelated()
                showAuthorWorks(d)
            }
        },
        favorite = series?.let { item ->
            runCatching { lib.favorite(item.id) }.getOrDefault(false) ||
                PhoneAccountSync.data?.favorites?.any { it.series.id == item.id } == true
        } ?: false,
        onFavorite = {
            val d = detail
            if (d == null) {
                toast(UiText.get(com.hgtv.star.shared.R.string.ui_0237))
                false
            } else {
                toggleFavorite(d.series)
            }
        },
        onDanmaku = {
            val wasHidden = playerPage.danmakuView.mode == DanmakuMode.HIDDEN
            applyDanmakuSettingsForChips()
            if (
                wasHidden &&
                lib.danmakuMode != DanmakuMode.HIDDEN &&
                !playerDanmakuLoaded
            ) {
                detail?.let { d ->
                    d.episodes.getOrNull(index)?.let { loadDanmakuForSettings(it, d.series.id) }
                }
            }
            syncChips()
        },
        onDebug = { syncDebugOverlayForListener() },
    )
}
