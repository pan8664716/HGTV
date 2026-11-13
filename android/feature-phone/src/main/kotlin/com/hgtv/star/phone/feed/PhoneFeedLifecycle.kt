// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import kotlinx.coroutines.cancel

internal fun PhoneFeedView.onFeedDetachedImpl() {
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
    feedListener?.let { feedPlayer?.removeListener(it) }
    feedListener = null
    feedPreloadScheduler.cancel()
    PhonePreload.clear()
    feedScope.cancel()
    runCatching { feedPlayer?.release() }
    feedPlayerSlot = null
    runCatching { feedRemoteSlot?.close() }
    feedRemoteSlot = null
}
