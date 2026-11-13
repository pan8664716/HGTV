// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.exoplayer2.Player

internal fun PhoneFeedView.isPlayingNowImpl(): Boolean {
    val exo = feedPlayer ?: return false
    return !feedHidden && !feedPaused && exo.playWhenReady &&
        exo.playbackState != Player.STATE_ENDED
}

internal fun PhoneFeedView.notifyPlayStateChangedImpl() {
    onPlayStateChanged?.invoke()
}

internal fun PhoneFeedView.scrollToTopImpl() {
    if (feedItems.isEmpty()) return
    feedList.stopScroll()
    (feedList.layoutManager as? LinearLayoutManager)
        ?.scrollToPositionWithOffset(0, 0)
        ?: feedList.scrollToPosition(0)
    feedList.post { if (feedPlayingIndex != 0) playIndexForSettings(0, 0L) }
}

internal fun PhoneFeedView.stopOrphanPlaybackImpl() {
    if (PhoneSession.owner != PhoneSession.Owner.PLAYER) return
    runCatching { PhoneServices.peekPlayer()?.pause() }
    PhoneSession.takeOver(PhoneSession.Owner.FEED)
    PhoneSession.endHandoff()
}
