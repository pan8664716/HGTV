// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.view.View
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.Player
import com.hgtv.star.core.VideoDecoderMode
import com.hgtv.star.shared.compat.lowEndTv
import com.hgtv.star.shared.debug.PlaybackStartupTrace
import com.hgtv.star.shared.ui.UiText

/**
 * 当前这一份片源的播放绑定。
 *
 * 监听器只认这个对象，不再在每次换作时创建新监听器。换源时整份替换绑定，
 * 旧片源排队中的回调会因为 generation / session / player 不匹配而直接丢弃。
 */
internal class PlaybackBinding(
    val player: ExoPlayer,
    val generation: Int,
    val session: Int,
    var mediaId: String,
    val recommendEntry: Boolean,
    val playbackStarted: Long,
    val prefetched: Boolean,
    val reuse: Boolean,
) {
    var sourceSetAt=0L
    var videoSizeReady=false
    var firstFrameRendered=false
    var firstReady=true
    var firstFrame=true
    var recommendRevealed=false
}

/** 每个 ExoPlayer 实例只挂一次；片源状态全部从 [PlaybackBinding] 读取。 */
internal fun TvMainActivity.ensureStablePlayerListener(p: ExoPlayer) {
    if(playerListenerPlayer===p && playerListener!=null) return
    detachStablePlayerListener()
    val listener=TvPlaybackListener(this)
    playerListener=listener
    playerListenerPlayer=p
    p.addListener(listener)
}

internal fun TvMainActivity.detachStablePlayerListener() {
    val p=playerListenerPlayer
    val listener=playerListener
    if(p!=null && listener!=null) runCatching { p.removeListener(listener) }
    playerListener=null
    playerListenerPlayer=null
    playbackBinding=null
}

private class TvPlaybackListener(
    private val host: TvMainActivity,
): Player.Listener {
    private fun binding(): PlaybackBinding? {
        val value=host.playbackBinding ?: return null
        if(host.player!==value.player) return null
        if(host.generation!=value.generation) return null
        if(value.session!=host.playbackSession) return null
        return value
    }

    /**
     * Player.Listener 的首帧 / 尺寸回调没有携带 mediaId；换源时旧渲染器排队的回调
     * 仍可能落到新绑定上。只接受播放器当前媒体项就是本绑定的那一条。
     */
    private fun currentBinding(): PlaybackBinding? {
        val bound=binding() ?: return null
        if(bound.player.currentMediaItem?.mediaId!=bound.mediaId) return null
        return bound
    }

    override fun onPlaybackStateChanged(state: Int) {
        val bound=currentBinding() ?: return
        val p=bound.player
        if(host.screen=="recommend" && host.playerView?.isFirstFrameAspectRatioReady()!=true) {
            host.playerView?.forceShutterVisible()
        }
        host.syncBufferingDots()
        host.playback.onPlayerState(state,p.playWhenReady)
        if(state==Player.STATE_READY) {
            PlaybackDiag.tracksSelected=p.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
            if(!PlaybackDiag.tracksSelected) {
                host.playerFailure(
                    PlaybackException(
                        "资源没有可解码的视频轨道",
                        null,
                        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    )
                )
                return
            }
            host.playbackReady=true
            host.syncBufferingDots()
            if(host.recommendHistoryPending) host.saveProgress()
            if(!runCatching { host.lowEndTv() }.getOrDefault(true)) host.warmPlaybackMenu()
            if(host.screen=="recommend") {
                host.reportRecommendImpression()
                host.scheduleRecommendAfterFirstFrame()
            }
            if(host.screen=="player") {
                val coverToken=host.recommendCoverToken
                host.main.postDelayed({
                    if(binding()!==bound) return@postDelayed
                    if(!host.isDestroyed && host.screen=="player" &&
                        host.recommendCoverToken==coverToken &&
                        bound.sourceSetAt>0L &&
                        PlaybackDiag.lastFrameAt>bound.sourceSetAt) {
                        host.revealRecommendCover()
                    }
                },600L)
                host.main.postDelayed({
                    if(binding()!==bound) return@postDelayed
                    if(!host.isDestroyed && host.screen=="player" && host.player===p &&
                        p.isPlaying && p.bufferedPosition-p.currentPosition>=3_000L) {
                        host.prefetchFormalBatchModels()
                    }
                },10_000L)
            }
            if(host.screen=="recommend") {
                host.recommendPlaybackFailures=0
                val coverToken=host.recommendCoverToken
                host.main.postDelayed({
                    if(binding()!==bound) return@postDelayed
                    if(!host.isDestroyed && host.screen=="recommend" &&
                        host.recommendCoverToken==coverToken) {
                        if(!bound.firstFrameRendered && bound.sourceSetAt>0L &&
                            PlaybackDiag.lastFrameAt>bound.sourceSetAt) {
                            bound.firstFrameRendered=true
                            host.recommendPlaybackHasFrame=true
                        }
                        host.revealRecommendWhenReady(bound)
                    }
                },400)
            }
            if(host.playbackWatchdog==null && !host.pictureUpNow()) {
                host.armPlaybackWatchdog(bound.session)
            }
            host.lastEpisodeDuration=p.duration.coerceAtLeast(0)
            host.markAutoTransitionReady(p.currentMediaItem?.mediaId.orEmpty())
            host.confirmPlayedItemRemoved()
            host.retirePreviousEpisode()
            host.restoreBufferingUi()
            if(bound.firstReady) {
                bound.firstReady=false
                host.readySince=android.os.SystemClock.elapsedRealtime()
                android.util.Log.i(
                    "HGTV",
                    "Playback ready episode=${host.episodeIndex+1} "+
                        "preloaded=${bound.prefetched} reused=${bound.reuse} "+
                        "elapsedMs=${host.readySince-bound.playbackStarted}",
                )
            }
            if(host.hud.visibility==View.VISIBLE) host.updatePlaybackText()
        }
        host.syncMediaSession()
        if(state==Player.STATE_ENDED) {
            if(host.screen=="recommend") {
                host.requestedAutoplay=false
                p.pause()
                host.playbackText.text=UiText.get(com.hgtv.star.shared.R.string.ui_0141)
                host.showHud()
                return
            }
            if(host.queuedEpisodeId!=null) host.dropQueuedEpisode()
            host.saveProgress(true)
            if(host.sleepTimer.episodeEnded()) host.stopForSleep()
            else {
                host.requestedAutoplay=false
                p.pause()
                host.playbackText.text=UiText.get(com.hgtv.star.shared.R.string.ui_0141)
                host.showHud()
            }
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?,reason: Int) {
        // 统一策略禁止自动切下一集：即使播放器队列里意外还有下一项，也不接手。
        if(reason==Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) host.dropQueuedEpisode()
    }

    override fun onVideoSizeChanged(videoSize: com.google.android.exoplayer2.video.VideoSize) {
        val bound=currentBinding() ?: run {
            if(host.screen=="recommend") host.playerView?.forceShutterVisible()
            return
        }
        if(host.screen=="recommend" && host.playerView?.isFirstFrameAspectRatioReady()!=true) {
            host.playerView?.forceShutterVisible()
        }
        val ratio=if(videoSize.pixelWidthHeightRatio>0f) videoSize.pixelWidthHeightRatio else 1f
        val swapped=videoSize.unappliedRotationDegrees==90 || videoSize.unappliedRotationDegrees==270
        val rawW=if(swapped) videoSize.height else videoSize.width
        val rawH=if(swapped) videoSize.width else videoSize.height
        host.videoWidth=Math.round(rawW*ratio)
        host.videoHeight=rawH
        bound.videoSizeReady=host.videoWidth>0 && host.videoHeight>0
        host.syncPlaybackLetterbox()
        if(BuildConfig.DEBUG) android.util.Log.i(
            "HGTV-RATIO",
            "videoSize=${videoSize.width}x${videoSize.height} "+
                "applied=${host.videoWidth}x${host.videoHeight} "+
                "firstFrame=${bound.firstFrameRendered} view=${host.playerView?.javaClass?.simpleName}",
        )
        host.applyResizeMode()
        if(!bound.recommendEntry && bound.videoSizeReady && !bound.firstFrameRendered &&
            bound.sourceSetAt>0L && PlaybackDiag.lastFrameAt>bound.sourceSetAt) {
            bound.firstFrameRendered=true
            host.playerView?.completeFirstFrameAspectRatio()
        }
        host.revealRecommendWhenReady(bound)
    }

    override fun onRenderedFirstFrame() {
        val bound=currentBinding() ?: run {
            // 旧片源的首帧可能晚于新绑定到达；PlayerView 自己的监听器会先隐藏快门，
            // 这里必须重新盖住，直到当前媒体项真的出帧。
            if(host.screen=="recommend") host.playerView?.forceShutterVisible()
            return
        }
        val p=bound.player
        bound.firstFrameRendered=true
        if(host.screen=="recommend") host.recommendPlaybackHasFrame=true
        if(BuildConfig.DEBUG) {
            android.util.Log.i(
                "HGTV-RATIO",
                "renderedFirstFrame media=${p.currentMediaItem?.mediaId} "+
                    "view=${host.playerView?.javaClass?.simpleName} "+
                    "video=${host.videoWidth}x${host.videoHeight}",
            )
            val switchAge=host.recommendSwitchStartedAt.takeIf { it>0L }?.let {
                android.os.SystemClock.elapsedRealtime()-it
            }
            if(switchAge!=null) {
                android.util.Log.i(
                    "HGTV-SWITCH",
                    "first-frame +${switchAge}ms episode=${host.episodeIndex+1}",
                )
                host.recommendSwitchStartedAt=0L
            }
        }
        host.playerView?.completeFirstFrameAspectRatio()
        host.syncPlaybackLetterbox()
        PlaybackStartupTrace.firstFrame(
            "tv.player",
            "recommend=${bound.recommendEntry} preloaded=${bound.prefetched} reused=${bound.reuse}",
        )
        host.markAutoTransitionFirstFrame(p.currentMediaItem?.mediaId.orEmpty())
        host.applyResizeMode()
        if(host.screen=="player") host.revealRecommendCover()
        if(host.screen=="recommend") {
            host.reportRecommendImpression()
            host.revealRecommendWhenReady(bound)
            host.scheduleRecommendAfterFirstFrame()
        }
        if(PlaybackDiag.pictureUp()) host.disarmPlaybackWatchdog()
        if(host.playbackStallWatchdog==null) host.armFrameStallWatchdog(bound.session)
        if(PlaybackDiag.takeRecovered()) android.util.Log.i(
            "HGTV-PLAY",
            "recovered mode=${if(host.playbackDecoderMode==VideoDecoderMode.SOFTWARE) "sw" else "hw"}"+
                " cap=${host.playbackMaxQuality}P "+
                "plan=${host.playbackSessionState.lastReason?.name ?: "-"}"+
                " codec=${PlaybackDiag.codecFamily} res=${PlaybackDiag.resolution}"+
                " dev=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
        )
        host.danmakuLoader.onFrameRendered(p.currentPosition)
        host.syncDanmakuClock()
        if(host.screen=="recommend") host.updateRecommendOverlay()
        if(bound.firstFrame) {
            bound.firstFrame=false
            val elapsed=android.os.SystemClock.elapsedRealtime()-bound.playbackStarted
            host.recordFirstFrameForBuffer(elapsed)
            android.util.Log.i(
                "HGTV",
                "Playback first frame episode=${host.episodeIndex+1} "+
                    "preloaded=${bound.prefetched} reused=${bound.reuse} elapsedMs=$elapsed",
            )
        }
        if(host.advancedAt>0) {
            val gap=android.os.SystemClock.elapsedRealtime()-host.advancedAt
            host.advancedAt=0
            android.util.Log.i(
                "HGTV",
                "Seamless first frame episode=${host.episodeIndex+1} afterMs=$gap",
            )
        }
        host.main.postDelayed({
            if(binding()===bound && host.player?.isPlaying==true &&
                (host.screen=="player" || host.screen=="recommend")
            ) {
                host.playbackCoordinator.markStablePlayback()
            }
        }, com.hgtv.star.core.AppConfig.Playback.RECOVERY_STABLE_MS)
    }

    override fun onIsPlayingChanged(playing: Boolean) {
        if(currentBinding()==null) return
        if(host.hud.visibility==View.VISIBLE) host.showHud()
        host.syncKeepScreenOn()
        host.syncMediaSession()
        host.syncDanmakuClock()
    }

    override fun onPlaybackParametersChanged(parameters: PlaybackParameters) {
        val bound=currentBinding() ?: return
        if(BuildConfig.DEBUG) android.util.Log.i(
            "HGTV",
            "Speed changed to ${parameters.speed} media=${bound.player.currentMediaItem?.mediaId} "+
                "at=${android.os.SystemClock.elapsedRealtime()}",
        )
        host.syncMediaSession()
        host.syncDanmakuClock()
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if(currentBinding()==null) return
        host.syncMediaSession()
        host.danmakuView?.resetCursor()
        host.syncDanmakuClock()
    }

    override fun onPlayerError(error: PlaybackException) {
        if(currentBinding()!=null) host.playerFailure(error)
    }

    override fun onTimelineChanged(
        timeline: com.google.android.exoplayer2.Timeline,
        reason: Int,
    ) {
        if(currentBinding()!=null) host.confirmPlayedItemRemoved()
    }
}

internal fun TvMainActivity.revealRecommendWhenReady(bound: PlaybackBinding) {
    if(playbackBinding!==bound || screen!="recommend" || bound.recommendRevealed || !bound.videoSizeReady) return
    if(playerView?.isFirstFrameAspectRatioReady()!=true) return
    val frameReady=bound.firstFrameRendered ||
        (bound.sourceSetAt>0L && PlaybackDiag.lastFrameAt>bound.sourceSetAt)
    if(!frameReady) return
    recommendPlaybackHasFrame=true
    bound.recommendRevealed=true
    playerView?.setKeepContentOnPlayerReset(true)
    val coverToken=recommendCoverToken
    main.post {
        if(!isDestroyed && screen=="recommend" && playbackBinding===bound &&
            recommendCoverToken==coverToken) {
            revealRecommendCover { if(!isDestroyed) hideCenterLoading() }
        }
    }
}
