// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.hgtv.star.phone.ui.primeAspectRatio

/**
 * 上下滑换集优先推进 ExoPlayer 已经挂好的下一项。
 *
 * 自动连播之所以无感，是因为它没有重新设置媒体源；手动上滑如果仍走 [play]，
 * 即使地址已经预热，也会 stop / setMediaSource / prepare 一遍，用户看到的
 * 就是「先加载再播放」。这里直接 seek 到队列里的下一项，复用同一条播放链路。
 *
 * 只有下一项确实已在播放列表里才走这条快路；否则仍由 [play] 走常规兜底。
 */
internal fun PhonePlayerActivity.playSwipeTarget(target: Int) {
    cancelAutoRetry()
    cancelPlaybackRequest()
    cancelSourceRetry()
    if (target == index + 1 && seekToQueuedNext(target)) return
    play(target, clearDanmakuOnSwitch = true)
}

private fun PhonePlayerActivity.seekToQueuedNext(target: Int): Boolean {
    if (!PhoneSession.nextQueued) return false
    val video = queuedVideo ?: return false
    if (queuedEpisodeId.isEmpty()) return false
    val exo = player ?: return false
    val current = exo.currentMediaItemIndex
    if (current < 0 || exo.mediaItemCount <= current + 1) return false
    val verticalSource = if (video.info.videoWidth > 0 && video.info.videoHeight > 0) {
        video.info.videoHeight > video.info.videoWidth
    } else {
        detail?.series?.vertical == true
    }
    playerPage.playerView.primeAspectRatio(
        video.info.videoWidth,
        video.info.videoHeight,
        verticalSource,
    )
    playerLandscapeVideo.primeAspectRatio(
        video.info.videoWidth,
        video.info.videoHeight,
        verticalSource,
    )
    listenerManualNextTarget = target
    listenerManualNextPosition = exo.currentPosition.coerceAtLeast(0L)
    exo.playWhenReady = true
    if (runCatching { exo.seekToNextMediaItem() }.isFailure) {
        listenerManualNextTarget = -1
        listenerManualNextPosition = 0L
        return false
    }
    // `onMediaItemTransition` 正常会先到；这里只做一次兜底，避免个别设备不回调时
    // 画面已经切过去、标题与集数却还停在上一集。
    root.post {
        if (listenerManualNextTarget == target) {
            listenerManualNextTarget = -1
            onAdvanced(
                target,
                completedPrevious = false,
                previousPosition = listenerManualNextPosition,
                clearDanmakuOnSwitch = true,
            )
            listenerManualNextPosition = 0L
        }
    }
    return true
}
