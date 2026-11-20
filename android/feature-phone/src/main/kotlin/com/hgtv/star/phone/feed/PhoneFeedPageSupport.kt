// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.hgtv.star.core.VideoFrameMode
import com.hgtv.star.core.toFit
import com.hgtv.star.phone.ui.resizeMode

internal fun PhoneFeedView.pageAtImpl(index: Int): PhoneVideoPage? {
    val holder = feedList.findViewHolderForAdapterPosition(index) as? PageHolder ?: return null
    return holder.itemView as? PhoneVideoPage
}

internal fun PhoneFeedView.applyPageVideoSettingsImpl(page: PhoneVideoPage) {
    val lib = feedLibrary
    page.setResizeMode(
        (lib?.frameMode ?: VideoFrameMode.FIT).toFit().resizeMode()
    )
    page.setVideoScale(((lib?.videoScale ?: 100f) / 100f).coerceIn(.1f, 1f))
}

internal fun PhoneFeedView.handleFeedSizeChanged(w: Int, h: Int) {
    if (w <= 0 || h <= 0 || feedItems.isEmpty()) return
    val index = feedPlayingIndex.coerceAtLeast(0)
    feedList.post { runCatching { feedList.scrollToPosition(index) } }
    rebindCurrentSurfaceAfterSizeChangeImpl()
}

internal fun PhoneFeedView.clockImpl(ms: Long): String =
    com.hgtv.star.core.PlaybackInfoLine.time(ms)
