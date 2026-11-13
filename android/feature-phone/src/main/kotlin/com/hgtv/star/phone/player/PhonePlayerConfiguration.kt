// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.content.res.Configuration

internal fun PhonePlayerActivity.handlePlayerConfigurationChanged(newConfig: Configuration) {
    val nowLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
    if (nowLandscape != playerLandscapeMode) {
        playerLandscapeModeSlot = nowLandscape
        applyMode()
    }
    if (nowLandscape) {
        playerPortraitFullscreenFromLandscape = false
        if (playerFinishAfterLandscapeRestore) {
            playerFinishAfterLandscapeRestore = false
            root.post { if (!isFinishing && !isDestroyed) finish() }
            return
        }
    }
    root.requestLayout()
}
