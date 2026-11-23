// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.hgtv.star.shared.ui.UiText
import android.view.View
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.video.VideoSize
import com.hgtv.star.core.VideoFrameMode
import com.hgtv.star.core.toFit
import com.hgtv.star.shared.debug.PlaybackStartupTrace
import com.hgtv.star.shared.compat.connectivityManager
import com.hgtv.star.shared.compat.hasInternet
import com.hgtv.star.phone.ui.resizeMode

/**
 * 摘掉某台池内播放器的监听。
 *
 * 池里同时可能有两三台播放器，不能再用一个全局 listener 字段互相覆盖。
 */
internal fun PhoneFeedView.detachFeedListener(exo: ExoPlayer) {
    feedPlayerListeners.remove(exo)?.let(exo::removeListener)
}

internal fun PhoneFeedView.detachAllFeedListeners() {
    feedPlayerListeners.entries.toList().forEach { (exo, listener) ->
        exo.removeListener(listener)
    }
    feedPlayerListeners.clear()
}

/**
 * 挂监听（隐藏时摘掉、回来时重挂：摘掉是为了不让隐藏期间的回调改界面）。
 *
 * 回调里一律用 `feedBoundPage` 现取目标页，不捕获构造时的那个：交接、重建、重新绑定之后
 * 播放器可能已经挂到另一张页上，捕获旧页会把画面尺寸写到已经回收的 View 上。
 */
internal fun PhoneFeedView.attachFeedListener(exo: ExoPlayer, index: Int) {
    detachFeedListener(exo)
    val hook = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (feedPlayer !== exo && feedPooledPlayers[index]?.player !== exo) return
            val page = feedPageAt(index) ?: feedBoundPage?.takeIf {
                index == feedPlayingIndex
            } ?: return
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
            val active = feedPlayer === exo && index == feedPlayingIndex
            if (active) {
                feedNotifyPlayStateChanged()
                syncDanmakuClockNow(exo, index)
                syncProgressBarsNow(exo, index)
            }
            // 真的出画面了：连续失败计数归零（取地址成功不算，能放才算）。
            if (state == Player.STATE_READY) {
                feedSourceRecoveryIndex = -1
                if (feedPooledPlayers[index]?.player === exo) {
                    markPooledPlayerSourceReady(index)
                }
                if (!active) return
                feedRecommendFailures = 0
                // READY 是首帧回调缺失时的兜底；同一曝光由共用上报器短时去重。
                reportRecommendImpression(index)
                scheduleFeedFillAfterFirstFrame()
                scheduleRecommendBatchModels(index)
                postDelayed({
                    if (feedPlayer === exo && index == feedPlayingIndex) {
                        feedPageAt(index)?.hideEpisodeCover()
                    }
                }, 600L)
                postDelayed({
                    if (feedPlayer === exo && index == feedPlayingIndex && exo.isPlaying) {
                        feedRecovery.markStablePlayback()
                    }
                }, com.hgtv.star.core.AppConfig.Playback.RECOVERY_STABLE_MS)
            }
            if (state == Player.STATE_ENDED && index == feedPlayingIndex) onEpisodeEnded(index)
        }

        override fun onRenderedFirstFrame() {
            feedSourceRecoveryIndex = -1
            if (feedPooledPlayers[index]?.player === exo) {
                markPooledPlayerFirstFrame(index)
            }
            val active = feedPlayer === exo && index == feedPlayingIndex
            if (!active) {
                finishPooledPreRenderImpl(index, exo)
                return
            }
            feedPageAt(index)?.hideEpisodeCover()
            // 画面真的上屏才放行弹幕；STATE_READY 后还可能回 buffering，
            // 那时放行会让弹幕先跑、出帧后再清空重排（与 TV 端同一闸门）。
            feedDanmakuLoaderOf(index).onFrameRendered(feedPlayer?.currentPosition ?: 0L)
            reportRecommendImpression(index)
            PlaybackStartupTrace.firstFrame("phone.feed")
            scheduleFeedFillAfterFirstFrame()
            if (com.hgtv.star.shared.BuildConfig.DEBUG) {
                android.util.Log.i(
                    "HGTV-PHONE",
                    "feed first frame index=$index media=" +
                        runCatching { exo.currentMediaItem?.mediaId }.getOrNull().orEmpty(),
                )
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (feedPlayer !== exo || index != feedPlayingIndex) return
            feedNotifyPlayStateChanged()
            syncDanmakuClockNow(exo, index)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (feedPlayer !== exo || index != feedPlayingIndex) return
            feedNotifyPlayStateChanged()
            syncDanmakuClockNow(exo, index)
            syncProgressBarsNow(exo, index)
        }

        override fun onPlaybackParametersChanged(parameters: com.google.android.exoplayer2.PlaybackParameters) {
            if (feedPlayer !== exo || index != feedPlayingIndex) return
            syncDanmakuClockNow(exo, index)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (feedPlayer !== exo || index != feedPlayingIndex) return
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
            if (feedPlayer !== exo || index != feedPlayingIndex) {
                if (feedPooledPlayers[index]?.player === exo) releasePooledPlayerSlot(index)
                return
            }
            if (feedPaused || feedHidden) return
            val at = runCatching { feedPlayer?.currentPosition ?: -1L }.getOrDefault(-1L)
                .takeIf { it > 0L } ?: PhoneSession.positionMs
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
            val request = com.hgtv.star.shared.media.PlaybackFailures.recoveryRequest(
                problem=error,
                layer=com.hgtv.star.core.RecoveryLayer.PLAYER,
                surface=com.hgtv.star.core.RecoverySurface.RECOMMEND,
                info=PhoneSession.remote?.info,
                fallbackQuality=PhoneSession.effectiveMaxQuality
                    ?: feedLibrary?.maxQuality ?: 1080,
                mode=PhoneSession.effectiveDecoderMode
                    ?: feedLibrary?.decoderMode
                    ?: com.hgtv.star.core.VideoDecoderMode.HARDWARE,
                networkAvailable=context.connectivityManager()?.hasInternet() != false,
                stageOverride=com.hgtv.star.shared.media.PlaybackFailures.stageOf(error),
            )
            val decision=feedRecovery.decide(request)
            when(val action=decision.action) {
                com.hgtv.star.core.RecoveryAction.RefetchAddress,
                com.hgtv.star.core.RecoveryAction.RetryPrepare,
                com.hgtv.star.core.RecoveryAction.RebuildPlayer,
                com.hgtv.star.core.RecoveryAction.SwitchHostAndRefetch,
                com.hgtv.star.core.RecoveryAction.RecoverQueuedCurrent -> {
                    playIndexForSettings(index, at, clearDanmakuOnSwitch = false)
                }
                is com.hgtv.star.core.RecoveryAction.SwitchSoftware -> {
                    PhoneSession.effectiveDecoderMode =
                        com.hgtv.star.core.VideoDecoderMode.SOFTWARE
                    action.maxQuality?.let { PhoneSession.effectiveMaxQuality = it }
                    playIndexForSettings(index, at, clearDanmakuOnSwitch = false)
                }
                is com.hgtv.star.core.RecoveryAction.LowerQuality -> {
                    PhoneSession.effectiveMaxQuality = action.targetQuality
                    playIndexForSettings(index, at, clearDanmakuOnSwitch = false)
                }
                com.hgtv.star.core.RecoveryAction.WaitForNetwork,
                com.hgtv.star.core.RecoveryAction.ShowPassiveHint -> {
                    runCatching { feedPlayer?.pause() }
                    feedHint.text = "播放失败，点此重试"
                    feedHint.isClickable = true
                    feedHint.setOnClickListener {
                        feedHint.setOnClickListener(null)
                        feedHint.isClickable = false
                        feedHint.visibility = View.GONE
                        feedRecovery.onManualRetry()
                        playIndexForSettings(index, at, clearDanmakuOnSwitch = false)
                    }
                    feedHint.visibility = View.VISIBLE
                    feedHint.bringToFront()
                }
            }
        }
    }
    feedPlayerListeners[exo] = hook
    exo.addListener(hook)
}

private fun com.google.android.exoplayer2.PlaybackException.isRecoverableSourceError(): Boolean =
    when (errorCode) {
        com.google.android.exoplayer2.PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        com.google.android.exoplayer2.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        com.google.android.exoplayer2.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        com.google.android.exoplayer2.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        com.google.android.exoplayer2.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        -> true
        else -> false
    }

private fun PhoneFeedView.shouldRetrySource(index: Int): Boolean =
    feedSourceRecoveryIndex != index ||
        android.os.SystemClock.elapsedRealtime() - feedSourceRecoveryAt > 10_000L
