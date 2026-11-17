// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.graphics.Color
import android.view.View
import com.hgtv.star.phone.ui.PhoneGlyph

fun PhoneVideoPage.setChromeVisible(visible: Boolean) {
    chromeVisible = visible
    infoColumn.visibility = if (visible) View.VISIBLE else View.GONE
    if (actionBarInitialized) actionBar.visibility = if (visible) View.VISIBLE else View.GONE
    bottomShade.visibility = if (visible) View.VISIBLE else View.GONE
    if (fullscreenEntry && fullscreenBoxInitialized) {
        fullscreenBox.visibility = if (visible && wantsFullscreen) View.VISIBLE else View.GONE
    }
    if (!visible) hideCenterIcon()
}

fun PhoneVideoPage.isChromeVisible(): Boolean = chromeVisible

fun PhoneVideoPage.setUnderlayMode(on: Boolean) {
    if (on) {
        setChromeVisible(false)
        danmakuView.visibility = View.GONE
        hideCenterIcon()
        if (fullscreenEntry && fullscreenBoxInitialized) fullscreenBox.visibility = View.GONE
    } else {
        setChromeVisible(true)
    }
}

fun PhoneVideoPage.showCenterIcon(playing: Boolean, autoHideMs: Long = 700L) {
    centerGlyph.kind = if (playing) PhoneGlyph.Kind.PAUSE else PhoneGlyph.Kind.PLAY
    centerBox.visibility = View.VISIBLE
    centerBox.bringToFront()
    centerHideAt = System.currentTimeMillis() + autoHideMs
    if (playing) {
        postDelayed({
            if (System.currentTimeMillis() >= centerHideAt) centerBox.visibility = View.GONE
        }, autoHideMs)
    }
}

fun PhoneVideoPage.hideCenterIcon() {
    centerBox.visibility = View.GONE
    centerHideAt = 0L
}

fun PhoneVideoPage.setResizeMode(mode: Int) {
    playerView.resizeMode = mode
    syncLetterboxMask()
}

fun PhoneVideoPage.setVideoSurfaceVisible(visible: Boolean) {
    playerView.visibility = if (visible) View.VISIBLE else View.GONE
    setBackgroundColor(if (visible) Color.BLACK else Color.TRANSPARENT)
    if (visible) {
        playerView.setShutterBackgroundColor(Color.BLACK)
        playerView.setBackgroundColor(Color.BLACK)
    }
    syncLetterboxMask()
}
