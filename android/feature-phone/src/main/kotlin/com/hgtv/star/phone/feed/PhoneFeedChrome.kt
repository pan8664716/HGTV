// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

internal fun PhoneFeedView.currentChromePageImpl(): PhoneVideoPage? =
    feedPageAt(feedPlayingIndex) ?: feedBoundPage

internal fun PhoneFeedView.scheduleChromeHideImpl(
    page: PhoneVideoPage? = currentChromePageImpl(),
) {
    if (feedHidden || PhoneSession.underlayActive || page == null || !page.isChromeVisible()) {
        feedChromeEpoch++
        feedList.removeCallbacks(feedChromeHideRunnable)
        return
    }
    val seconds = feedLibrary?.playerPanelTimeout ?: 6
    feedChromeEpoch++
    feedChromeHideAt = System.currentTimeMillis() + seconds * 1000L
    feedList.removeCallbacks(feedChromeHideRunnable)
    feedList.postDelayed(feedChromeHideRunnable, seconds * 1000L)
}

internal fun PhoneFeedView.chromeTimeoutMsImpl(): Long =
    (feedLibrary?.playerPanelTimeout ?: 6) * 1000L

internal fun PhoneFeedView.revealChromeImpl(page: PhoneVideoPage?) {
    if (feedHidden || PhoneSession.underlayActive) return
    page?.setChromeVisible(true)
    scheduleChromeHideImpl(page)
}
