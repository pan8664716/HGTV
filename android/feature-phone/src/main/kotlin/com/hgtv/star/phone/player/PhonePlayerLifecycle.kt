// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

internal fun PhonePlayerActivity.releasePlayerImpl() {
    releaseAllAdjacentPlayers()
    playbackSessionState.release()
    progressTicker?.cancel()
    progressTicker = null
    cancelAutoRetry()
    clearPlaybackError()
    playerListener?.let { player?.removeListener(it) }
    playerListener = null
    player?.pause()
    if (playerPageInitialized) PhoneServices.detach(playerPage.playerView)
    if (playerLandscapeVideoInitialized) PhoneServices.detach(playerLandscapeVideo)
    player = null
    runCatching { remote?.close() }
    remote = null
}

/**
 * 离开播放页但不销毁这一路播放：只解绑 Surface、摘监听、暂停。
 *
 * 与 [releasePlayerImpl] 的区别就是「留给谁」：换集时旧的确实没用了，可以关；
 * 退出播放页时这一路要交回信息流，关掉就等于让它重来一遍。
 */
internal fun PhonePlayerActivity.detachForHandoffImpl() {
    releaseAllAdjacentPlayers()
    progressTicker?.cancel()
    progressTicker = null
    playerListener?.let { player?.removeListener(it) }
    playerListener = null
    val handoff = handingBackToFeedForLifecycle()
    player?.let { exo ->
        PhoneSession.markPosition(exo.currentPosition)
        // **只在会话仍属于播放页时暂停**：退回推荐页时信息流已经先一步接手
        //（A.onResume 早于 B.onDestroy），这里再 pause 一次会把刚恢复的播放按停，
        // 用户看到的就是「退回去之后内容断了」。
        PhoneSession.resumeOnAdopt = exo.playWhenReady
        // 交接窗口内（或会话已被别人接手）一律不暂停：这一路还在被用。
        if (!handoff && !PhoneSession.inHandoff() &&
            PhoneSession.owner == PhoneSession.Owner.PLAYER
        ) {
            exo.pause()
        }
    }
    if (playerPageInitialized) PhoneServices.detach(playerPage.playerView)
    if (playerLandscapeVideoInitialized) PhoneServices.detach(playerLandscapeVideo)
    // player 字段留在 Activity 里没有意义（Activity 要销毁了），但**进程级实例**必须保住：
    // `PhoneServices.player` 是单例，这里只是不再引用它。
    player = null
    remote = null
}
