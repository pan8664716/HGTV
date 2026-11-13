// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.hgtv.star.core.VideoFrameMode
import com.hgtv.star.shared.data.Library

/** 横屏播放设置外壳：左侧点击收起，右侧一栏承载同一套设置行。 */
internal fun PhonePlayerActivity.openLandscapeSettingsImpl(lib: Library) {
    if (playerLandPanel != null) return
    val container = PhonePlayerSettingsPanel.showLandscape(
        this,
        root,
        { inner -> buildSettingsRowsForLandscape(inner, lib) },
        {
            dismissLandscapeSettingsImpl()
            setChromeVisibleForLandscape(false)
        },
    )
    playerLandPanel = container
    syncLandscapeSettingsInfoImpl()
    scheduleChromeHideForLandscape()
}

internal fun PhonePlayerActivity.dismissLandscapeSettingsImpl() {
    val panel = playerLandPanel ?: return
    root.removeView(panel)
    playerLandPanel = null
}

internal fun PhonePlayerActivity.syncFrameModeImpl() {
    applyFrameModeImpl(library?.frameMode ?: VideoFrameMode.FIT)
}

internal fun PhonePlayerActivity.applyFrameModeImpl(mode: VideoFrameMode) {
    PhonePlayerViewSupport.applyFrameMode(
        playerPage,
        playerLandscapeVideo,
        mode,
        playerFeedUnderlay,
    )
}
