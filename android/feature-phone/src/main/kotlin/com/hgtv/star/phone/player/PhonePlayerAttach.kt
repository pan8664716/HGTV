// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.hgtv.star.core.RemoteVideo
import com.google.android.exoplayer2.upstream.DataSource
import com.hgtv.star.shared.media.VideoDataSource
import com.hgtv.star.shared.media.swapMediaSource
import com.hgtv.star.phone.ui.runAfterAspectRatioLayout

/** 把已准备好的视频源挂到当前形态对应的 PlayerView，并安排首帧前的 prepare。 */
internal fun PhonePlayerActivity.attach(
    video: RemoteVideo,
    startAt: Long,
    rebindSurface: Boolean = false,
) {
    remote = video
    val d = detail
    val episodeIndex = index
    val session = playbackSessionState.sessionId
    val exo = PhoneServices.player(this, library?.decoderMode)
    val factory = DataSource.Factory { VideoDataSource(video) }
    val episodeId = d?.episodes?.getOrNull(index).orEmpty()
    if (d != null) {
        showEpisodeCover(d.episodeCover(episodeId).ifBlank { d.series.cover })
    }
    holdFirstFrameAspectRatio()
    // 先停再挂（见 `swapMediaSource`）：预热命中时播放器可能正在出帧，
    // 直接换源会在 flush 编解码器时抛 0x80000000，随即报 4003。
    exo.swapMediaSource(episodeId, video.info.url, factory)
    // 新源已经替换整条播放列表：旧的“下一集已排队”标志不能跨到这一集，
    // 否则最后一集片尾会被误判成还有队列项而卡住。
    queuedVideo?.let { runCatching { it.close() } }
    queuedVideo = null
    queuedEpisodeId = ""
    PhoneSession.clearNext()
    exo.setPlaybackSpeed(sessionUserSpeed)
    exo.playWhenReady = true
    attachPlayerListener(exo)
    if (d != null) {
        PhoneSession.publish(d, index, video, video.info.quality, PhoneSession.Owner.PLAYER)
    }

    player = exo
    val landscape = playerLandscapeMode
    if (landscape) {
        playerPage.playerView.player = null
        scheduleCurrentLandscapeAttach()
    } else {
        playerLandscapeVideo.player = null
        if (!(playerFeedUnderlay && attachUnderlay())) {
            dropUnderlay()
            PhoneSession.underlayActive = false
            selectPlayerPagerEpisode(index)
            attachCurrentPlayerToPagerPage()
            playerEpisodePager.post {
                if (player === exo && !playerLandscapeMode) {
                    attachCurrentPlayerToPagerPage()
                }
            }
        }
    }
    setBufferingLabel("正在缓冲…")
    syncVideoFrameImpl()
    val prepare = {
        if (
            player === exo &&
            index == episodeIndex &&
            playbackSessionState.sessionId == session
        ) {
            exo.prepare()
            if (startAt > 0) exo.seekTo(startAt)
        }
    }
    if (!landscape && playerFeedUnderlay && playerUnderlayBound) {
        prepare()
    } else {
        val target = if (landscape) playerLandscapeVideo else playerPage.playerView
        target.runAfterAspectRatioLayout(prepare)
    }
    // 媒体源已经挂好，交接结束：之后退到后台就按正常规则暂停。
    PhoneSession.endHandoff()
    syncPlayGlyph()
    syncChips()
    startTicker()
    armPictureWatchdog()
    if (rebindSurface && !playerFeedUnderlay) {
        // 非相邻集切换时，目标页和旧页可能复用同一个进程级播放器。PlayerView 与
        // ExoPlayer 都会把“同一个 player / surface”当成无需重绑，实际输出面却还
        // 停在旧页，表现为一直缓冲。等目标页建好后强制重交一次当前集 Surface。
        android.util.Log.i(
            "HGTV-PHONE",
            "surface rebind queued episode=${episodeIndex + 1}",
        )
        scheduleCurrentPlayerSurfaceRebind(baselineFrameAt = 0L, attempts = 24)
    }
    // 这一集真的挂上了：取地址失败的重试计数归零（见 `onEpisodeSourceMissing`）。
    // 不清的话，网络恢复回调会拿一个过期的计数把正常播放的这一集重来一遍。
    sourceRetryState.episode = ""
    sourceRetryState.count = 0
    scheduleAdjacentEpisodeWarmup()

    if (listenerPendingFullscreen) {
        // 画面已经挂上解码器，这时切换全屏不会黑屏。
        root.post { enterPendingFullscreenForListener() }
    }
}
