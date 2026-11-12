// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.view.View
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.video.VideoSize
import com.hgtv.star.core.VideoFrameMode
import com.hgtv.star.core.toFit
import com.hgtv.star.phone.ui.resizeMode

/**
 * 挂监听（隐藏时摘掉、回来时重挂：摘掉是为了不让隐藏期间的回调改界面）。
 *
 * 回调里一律用 `feedBoundPage` 现取目标页，不捕获构造时的那个：交接、重建、重新绑定之后
 * 播放器可能已经挂到另一张页上，捕获旧页会把画面尺寸写到已经回收的 View 上。
 */
internal fun PhoneFeedView.attachFeedListener(exo: ExoPlayer, index: Int) {
    feedListener?.let { exo.removeListener(it) }
    val hook = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            val page = feedBoundPage ?: return
            // 不再按片源方向强制 ZOOM：竖屏短剧在竖屏里铺满会裁掉左右，
            // 从播放页退回来时也会突然放大。画面模式只认用户选择。
            val target = (feedLibrary?.frameMode ?: VideoFrameMode.FIT).toFit().resizeMode()
            page.setResizeMode(target)
            // 画面尺寸到手才把「全屏」按钮贴到画面下边缘（之前按钮位置是写死的）。
            page.updateVideoFrame(videoSize.width, videoSize.height)
        }

        /**
         * 片尾：**自动进入正式播放界面**，并从下一集接着播。
         *
         * 与 TV 端一致（`MainActivityPlayer` 里 `STATE_ENDED` 的 recommend 分支 →
         * `enterFormalPlayback(advanceNext = true)`）：推荐流只负责「把人勾住」，
         * 真要看完整部，交给正式播放页按它自己的连播规则走。
         * 最后一集播完则「换一部接着看」——TV 是弹一份相关剧集，手机就是往下滑一屏。
         */
        override fun onPlaybackStateChanged(state: Int) {
            feedNotifyPlayStateChanged()
            syncDanmakuClockNow(exo, index)
            syncProgressBarsNow(exo, index)
            // 真的出画面了：连续失败计数归零（取地址成功不算，能放才算）。
            if (state == Player.STATE_READY) {
                feedRecommendFailures = 0
                // READY 是首帧回调缺失时的兜底；同一曝光由共用上报器短时去重。
                reportRecommendImpression(index)
                scheduleRecommendBatchModels(index)
            }
            if (state == Player.STATE_ENDED) onEpisodeEnded(index)
        }

        override fun onRenderedFirstFrame() {
            reportRecommendImpression(index)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            feedNotifyPlayStateChanged()
            syncDanmakuClockNow(exo, index)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            feedNotifyPlayStateChanged()
            syncDanmakuClockNow(exo, index)
            syncProgressBarsNow(exo, index)
        }

        override fun onPlaybackParametersChanged(parameters: com.google.android.exoplayer2.PlaybackParameters) {
            syncDanmakuClockNow(exo, index)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            feedPageAt(index)?.danmakuView?.resetCursor()
            syncDanmakuClockNow(exo, index)
            syncProgressBarsNow(exo, index)
        }

        /**
         * 这一部放不了：**换下一部**（与电视端 `screen=="recommend"` 那条分支一致）。
         *
         * 早先这里什么都没有：解码失败之后画面就停住、声音也没有，用户只看到一屏静止的
         * 封面与一条进度条，不知道发生了什么，只能退出重进。
         */
        override fun onPlayerError(error: com.google.android.exoplayer2.PlaybackException) {
            android.util.Log.i(
                "HGTV-PHONE",
                "feed error ${error.errorCodeName}(${error.errorCode}) index=$index failures=$feedRecommendFailures"
            )
            if (feedPaused || feedHidden) return
            feedItems.getOrNull(index)?.let { series ->
                feedRecommendPlayback.reportEvent(
                    series,
                    com.hgtv.star.core.RecommendBehavior.Event.ERROR,
                    episodeId=feedCurrentEpisodeIds[index].orEmpty(),
                    episodeIndex=feedCurrentEpisodeIndex[index] ?: -1,
                    positionMs=runCatching { feedPlayer?.currentPosition ?: -1L }.getOrDefault(-1L),
                    durationMs=runCatching { feedPlayer?.duration ?: -1L }.getOrDefault(-1L),
                    reason=error.errorCodeName,
                )
            }
            runCatching { feedPlayer?.pause() }
            feedRecommendFailures++
            if (feedRecommendFailures > feedRecommendSkipLimit) {
                // 连续几部都放不了：停在一句提示上，不再自动翻（否则会一路刷到底）。
                feedHint.text = "这一部暂时无法播放，上滑换一部"
                feedHint.visibility = View.VISIBLE
                feedHint.bringToFront()
                return
            }
            val next = index + 1
            if (next < feedItems.size) {
                feedHint.text = "这一部放不了，换下一部"
                feedHint.visibility = View.VISIBLE
                feedHint.bringToFront()
                feedSmoothScrollToPosition(next)
            } else {
                loadMore()
                feedHint.text = "这一部放不了，正在换下一部…"
                feedHint.visibility = View.VISIBLE
                feedHint.bringToFront()
            }
        }
    }
    feedListener = hook
    exo.addListener(hook)
}
