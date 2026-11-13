// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import com.hgtv.star.tv.list.showCatalog

internal fun TvMainActivity.reloadAfterConfigurationChange() {
    if(!mainInitialized || !library.isLoaded) return
    // 屏幕参数变了（分辨率 / dpi / 语言）：面板与列表里的尺寸都是建那一刻的像素，
    // 缓存的那一份必须丢掉，否则会按旧的像素间距摆在新屏幕上（见 `dropPlaybackMenuCache`）。
    dropPlaybackMenuCache()
    when(screen) {
        "player" -> {
            val position=currentPosition()
            val autoplay=player?.playWhenReady==true && !pausedForLifecycle
            playEpisode(episodeIndex,position,autoplay)
        }
        "recommend" -> {
            val position=currentPosition()
            val autoplay=player?.playWhenReady==true && !pausedForLifecycle
            playEpisode(episodeIndex,position,autoplay)
        }
        "detail" -> if(detail!=null) showDetail(false)
        else -> showCatalog()
    }
}
