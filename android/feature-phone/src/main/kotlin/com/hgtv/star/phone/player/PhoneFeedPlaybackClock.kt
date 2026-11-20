// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Player

internal fun PhoneFeedView.syncDanmakuClock(
    page: PhoneVideoPage,
    index: Int,
    positionMs: Long,
    running: Boolean,
    speed: Float,
) {
    if (index < 0) return
    feedDanmakuLoaderOf(index).syncClock(positionMs, running, speed)
}

internal fun PhoneFeedView.syncDanmakuClockNow(exo: ExoPlayer, index: Int) {
    val page = feedBoundPage ?: return
    syncDanmakuClock(
        page,
        index,
        exo.currentPosition,
        exo.isPlaying && exo.playbackState == Player.STATE_READY,
        exo.playbackParameters.speed,
    )
}

internal fun PhoneFeedView.syncProgressBarsNow(exo: ExoPlayer, index: Int) {
    val page = feedPageAt(index) ?: feedBoundPage ?: return
    val duration = exo.duration
    if (duration <= 0) return
    val position = exo.currentPosition.coerceIn(0, duration)
    page.setProgress(position.toFloat() / duration)
    page.setBufferedProgress(
        exo.bufferedPosition.coerceIn(position, duration).toFloat() / duration
    )
}

internal fun PhoneFeedView.startProgressTicker() {
    feedProgressTick?.let { feedRemoveCallbacks(it) }
    val tick = object : Runnable {
        override fun run() {
            val exo = feedPlayer
            val page = feedBoundPage
            // 播放器池接管时 `playWhenReady` 可能已经是 true，ExoPlayer 不会再发变化回调。
            // 进度 ticker 顺手对一次窗口常亮，避免自动连播后漏掉「开始播放」那一下。
            notifyPlayStateChangedImpl()
            if (exo != null && !exo.isFeedPlayerAlive()) {
                recoverReleasedFeedPlayer(exo)
                return
            }
            if (exo != null && page != null) {
                val duration = exo.duration
                if (duration > 0) {
                    val position = exo.currentPosition
                    PhoneSession.markPosition(position)
                    page.setProgress(position.toFloat() / duration)
                    page.setBufferedProgress(exo.bufferedPosition.toFloat() / duration)
                    if (exo.isPlaying) {
                        feedItems.getOrNull(feedPlayingIndex)?.let { series ->
                            feedRecommendPlayback.reportProgress(
                                series,
                                feedCurrentEpisodeIds[feedPlayingIndex].orEmpty(),
                                feedCurrentEpisodeIndex[feedPlayingIndex] ?: -1,
                                position.coerceAtLeast(0),
                                duration,
                            )
                        }
                    }
                    // 还在播就刷新会话时间戳：连看十几分钟后切页，地址仍然算「新鲜」。
                    if (exo.isPlaying) PhoneSession.touch()
                    if (!feedPaused) maybePrefetchNextForClock(position, duration)
                }
                // 片尾兜底：`STATE_ENDED` 只在状态**变化**时回调一次。如果那一下刚好
                // 被暂停 / 切页 / 交接吞掉，播放器会永远停在末帧——用户读到的就是
                // 「卡在这一集末尾，不往下走」。每 500ms 补一次，`onEpisodeEnded` 自己
                // 有 `paused` / `advancing` / 位置复核三道闸，重复调用是安全的。
                if (exo.playbackState == Player.STATE_ENDED && feedPlayingIndex >= 0) {
                    onEpisodeEnded(feedPlayingIndex)
                }
            }
            feedPostDelayed(this, 500)
        }
    }
    feedProgressTick = tick
    feedPostDelayed(tick, 500)
}
