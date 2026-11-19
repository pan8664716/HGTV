// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

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
    if (queuedVideo == null) return false
    if (queuedEpisodeId.isEmpty()) return false
    val exo = player ?: return false
    val current = exo.currentMediaItemIndex
    if (current < 0 || exo.mediaItemCount <= current + 1) return false
    holdFirstFrameAspectRatio()
    if (!bindQueuedNextOutput()) return false
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

/**
 * 把进程播放器接到翻页后的当前画面层。
 *
 * 竖屏换集时两页会交换引用，旧页的 PlayerView 在动画结束时已经解绑。快路径只
 * `seekToNextMediaItem()`，不像 [play] 那样会重新 `attach()`；漏掉这一步时音频与
 * 进度会继续走，但新页没有视频 Surface，表现就是一直停在封面或上一帧。
 */
private fun PhonePlayerActivity.bindQueuedNextOutput(): Boolean {
    val exo = player ?: return false
    if (playerLandscapeMode) {
        if (playerLandscapeVideo.player !== exo) playerLandscapeVideo.player = exo
        return playerLandscapeVideo.player === exo
    }
    // 正式播放内部换集不再借推荐页 Surface，否则新页自己的 PlayerView 会保持隐藏。
    if (playerFeedUnderlay) dropUnderlay()
    // 从推荐页进入时两页最初都按 underlay 隐藏。第一次换集只恢复了其中一页；
    // 下一次换到另一页时必须再次显式恢复，否则音频和进度正常但视频 Surface 仍是 GONE。
    playerPage.setVideoSurfaceVisible(true)
    PhoneServices.detach(swipeGhostPage.playerView)
    if (playerPage.playerView.player !== exo) playerPage.playerView.player = exo
    return playerPage.playerView.player === exo
}
