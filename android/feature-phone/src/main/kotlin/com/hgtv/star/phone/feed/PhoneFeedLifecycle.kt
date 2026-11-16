// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import kotlinx.coroutines.cancel

internal fun PhoneFeedView.onFeedDetachedImpl() {
    // 旧页面被转屏 / 重建摘掉后，不能再让它的延迟回收器去释放新页面刚接手的播放器。
    cancelPoolIdleRelease()
    val stillOwnsSession = PhoneSession.owner == PhoneSession.Owner.PLAYER ||
        PhoneSession.inHandoff() ||
        (PhoneSession.owner == PhoneSession.Owner.FEED && feedSessionBelongsToThisFeed())
    if (!stillOwnsSession) {
        // Pager 回收的是已经切走的体裁页：这里没有新页面会接手它的池实例，立即释放。
        releaseAllPooledPlayers(resetSession = false)
    }
    feedChromeEpoch++
    feedList.removeCallbacks(feedChromeHideRunnable)
    closeSheet()
    PhoneServices.artwork(context).cancelPage()
    val ownedByPlayer = PhoneSession.owner == PhoneSession.Owner.PLAYER ||
        PhoneSession.inHandoff()
    if (!ownedByPlayer && feedSessionBelongsToThisFeed()) {
        PhoneSession.bindFeedSurface(null)
        PhoneSession.feedVideoOffset = null
        PhoneSession.feedVideoResize = null
        PhoneSession.feedVideoScale = null
        PhoneSession.underlayActive = false
    }
}

internal fun PhoneFeedView.releaseImpl() {
    feedChromeEpoch++
    feedList.removeCallbacks(feedChromeHideRunnable)
    detachAllFeedListeners()
    feedPreloadScheduler.cancel()
    PhonePreload.clear()
    feedScope.cancel()
    val playerOwnsCurrent = PhoneSession.owner == PhoneSession.Owner.PLAYER ||
        PhoneSession.inHandoff()
    if (playerOwnsCurrent) {
        releasePooledPlayersExcept(PhoneServices.peekPlayer() ?: feedPlayer)
    } else {
        val ownsSession = PhoneSession.owner == PhoneSession.Owner.FEED &&
            feedSessionBelongsToThisFeed()
        releaseAllPooledPlayers(resetSession = ownsSession)
        runCatching { feedRemoteSlot?.close() }
    }
    feedRemoteSlot = null
}
