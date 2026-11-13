// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

/** 播放设置、选集和控制条共用同一枚「无操作自动消失」计时器。 */
internal fun TvMainActivity.runOverlayAutoHide() {
    when {
        playbackMenu!=null -> hidePlaybackOverlay()
        episodePanel!=null -> episodePanel?.dismiss()
        onPlaybackPage() && !playError && !scrubbing && !recommendScrubbing && hudShown() ->
            hidePlaybackOverlay()
    }
}

/** 遥控器快进快退在 250ms 合并后真正落到播放器。 */
internal fun TvMainActivity.runPendingSeek() {
    pendingSeek?.let { player?.seekTo(it) }
    pendingSeek=null
}

/** 强制 retiring 兜底：只有确认旧项不在播放列表里才关，避免关掉正在读的数据源。 */
internal fun TvMainActivity.runForcedRetire() {
    val stillListed=playedItemId.isNotEmpty() &&
        (0 until (player?.mediaItemCount ?: 0)).any {
            player?.getMediaItemAt(it)?.mediaId==playedItemId
        }
    if(!stillListed) {
        playedItemPending=false
        playedItemId=""
        retirePreviousEpisode()
    }
}
