// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.google.android.exoplayer2.Player

/**
 * 播放时保持屏幕常亮。
 *
 * setWakeMode 只保 CPU 与网络，**不会阻止屏幕熄灭**；这里按「前台 + 正在播 +
 * 没有停在错误页 / 定时停止」驱动窗口标志，暂停或退到后台立即放开。
 */
internal fun PhonePlayerActivity.syncKeepScreenOn() {
    val exo = player
    val on = playerForeground && !isFinishing && !isDestroyed && !sleepStopped &&
        errorCard == null && exo?.playWhenReady == true &&
        exo.playbackState != Player.STATE_ENDED
    PhonePlayerViewSupport.keepScreenOn(window,on)
}

/**
 * 借下层出画面时，确认输出仍然在推荐页那一块上。
 *
 * 只在竖屏、且 PhoneSession.underlayActive 已经变 false（被别的播放页抢走）时重挂：
 * 正常进出（推荐页 ↔ 本页）不会走到这里，因此不会多一次闪屏。
 */
internal fun PhonePlayerActivity.restoreUnderlayIfNeeded() {
    if (!playerFeedUnderlay || playerLandscapeMode) return
    if (PhoneSession.underlayActive) return
    val exo = player ?: return
    playerUnderlayBound = false
    playerUnderlayNeedsForce = true
    if (attachUnderlay()) return
    dropUnderlay()
    PhoneSession.underlayActive = false
    playerPage.playerView.player = exo
}
