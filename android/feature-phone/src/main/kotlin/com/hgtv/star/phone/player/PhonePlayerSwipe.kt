// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

/** 滑动一开始就停当前声音；只有手势弹回时才恢复。 */
internal fun PhonePlayerActivity.pauseCurrentForSwipeImpl() {
    if (swipeCurrentPaused) return
    val exo = player ?: return
    swipeCurrentPaused = true
    swipeShouldResumeCurrent = exo.playWhenReady || exo.isPlaying
    exo.playWhenReady = false
    runCatching { exo.pause() }
    PhoneServices.enableAudioFocus(exo, false)
    syncPlayGlyph()
}

internal fun PhonePlayerActivity.resumeCurrentAfterSwipeImpl() {
    if (!swipeCurrentPaused) return
    val resume = swipeShouldResumeCurrent
    swipeCurrentPaused = false
    swipeShouldResumeCurrent = false
    if (!resume) return
    val exo = player ?: return
    PhoneServices.enableAudioFocus(exo, true)
    exo.playWhenReady = true
    syncPlayGlyph()
    syncKeepScreenOn()
}

internal fun PhonePlayerActivity.clearSwipePauseForSwitchImpl() {
    swipeCurrentPaused = false
    swipeShouldResumeCurrent = false
}

internal fun PhonePlayerActivity.prepareSwipePreviewForCommit(target: Int) {
    val preview = swipePlaybackPreviewIndex
    swipePlaybackPreviewIndex = -1
    if (preview >= 0 && preview != target) stopAdjacentSwipePreview(preview)
}
