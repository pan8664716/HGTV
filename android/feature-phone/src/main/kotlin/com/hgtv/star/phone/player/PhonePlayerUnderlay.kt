// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.graphics.Color

/**
 * 把播放器的输出交回下层推荐页的 Surface。
 *
 * 成功返回 true。失败说明推荐页那一块已经不在了（被系统回收 / 换了体裁），
 * 调用方必须退回「本页自己出画面」，否则就是一块透明。
 */
internal fun PhonePlayerActivity.attachUnderlay(): Boolean {
    if (!playerFeedUnderlay) return false
    // 第一次进场时那块 Surface 本来就在出画面，不需要重挂；从横屏退回来时
    // 输出已经被横屏那一层拿走，必须强制重挂。
    val force = playerUnderlayNeedsForce
    val ok = runCatching { PhoneSession.attachFeedSurface(force) }.getOrDefault(false)
    if (ok) {
        playerUnderlayNeedsForce = false
        playerUnderlayBound = true
        playerPage.setVideoSurfaceVisible(false)
        root.setBackgroundColor(Color.TRANSPARENT)
        PhoneSession.underlayActive = true
        PhoneSession.reassertFeedChromeHidden()
        return true
    }
    playerUnderlayBound = false
    android.util.Log.i(
        "HGTV-PHONE",
        "借下层失败 force=$force 入口=${PhoneSession.feedSurface != null}",
    )
    return false
}

/**
 * 放弃借下层，改由本页自己出画面。
 *
 * 发生在两类场景：下层推荐页被系统收走；或者会话里的地址过期、需要重新取源
 * （那时下层画面已经不是这一集了，继续借会放错内容）。
 */
internal fun PhonePlayerActivity.dropUnderlay() {
    if (!playerFeedUnderlay) return
    android.util.Log.i("HGTV-PHONE", "放弃借下层，改由本页出画面")
    playerFeedUnderlaySlot = false
    swipeFeedVideoDy = 0f
    PhoneSession.setFeedVideoOffset(0f)
    playerUnderlayBound = false
    PhoneSession.underlayActive = false
    root.setBackgroundColor(Color.BLACK)
    playerPage.setVideoSurfaceVisible(true)
    if (playerFeedChromeHeld) {
        playerFeedChromeHeld = false
        PhoneSession.restoreFeedChrome()
    }
}
