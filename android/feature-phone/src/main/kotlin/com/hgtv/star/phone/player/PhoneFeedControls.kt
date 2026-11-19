// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.view.View

internal fun PhoneFeedView.togglePlay() {
    val exo = feedPlayer ?: return
    if (!exo.isFeedPlayerAlive()) {
        recoverReleasedFeedPlayer(exo)
        return
    }
    // 判据用 `playWhenReady`（意图）而不是 `isPlaying`（此刻有没有在出帧）：
    // 起播缓冲的那一两秒里 `isPlaying` 是 false，按 `isPlaying` 判会把「暂停」
    // 错当成「播放」，用户点一下反而什么都没发生。
    if (exo.playWhenReady) exo.pause() else exo.play()
    // 记下用户自己的意图：它决定切页 / 回来时要不要自动起播（见 `paused`）。
    feedPaused = !exo.playWhenReady
    feedBoundPage?.showCenterIcon(exo.playWhenReady)
    feedNotifyPlayStateChanged()
}

internal fun PhoneFeedView.beginTempSpeed() {
    if (feedTempSpeed) return
    val exo = feedPlayer ?: return
    if (!exo.isFeedPlayerAlive()) {
        recoverReleasedFeedPlayer(exo)
        return
    }
    feedTempSpeed = true
    exo.setPlaybackSpeed(2f)
    feedHint.text = "2 倍速播放中"
    feedHint.visibility = View.VISIBLE
    feedHint.bringToFront()
}

internal fun PhoneFeedView.endTempSpeed() {
    if (!feedTempSpeed) return
    feedTempSpeed = false
    feedPlayer?.takeIf { it.isFeedPlayerAlive() }?.setPlaybackSpeed(feedUserSpeed)
    feedHint.visibility = View.GONE
}
