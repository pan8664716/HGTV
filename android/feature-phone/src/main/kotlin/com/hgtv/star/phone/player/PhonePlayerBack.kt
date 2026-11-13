// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.content.pm.ActivityInfo

/** 返回键决策；true = 已消费，false = 交给 Activity 默认返回。 */
internal fun PhonePlayerActivity.handlePlayerBack(): Boolean {
    // 顺序：先收面板；横屏播放页直接返回上一页，不再先转竖屏。
    if (playerLandPanel != null) {
        dismissLandscapeSettingsImpl()
        return true
    }
    if (playerLandscapeMode) {
        finish()
        return true
    }
    if (playerPortraitFullscreenFromLandscape) {
        playerFinishAfterLandscapeRestore = true
        playerPortraitFullscreenFromLandscape = false
        if (!requestOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
            playerFinishAfterLandscapeRestore = false
            finish()
            return true
        }
        // 个别 ROM 不回调方向变化：留一个兜底，避免返回键看起来没反应。
        root.postDelayed({
            if (playerFinishAfterLandscapeRestore && !isFinishing && !isDestroyed) {
                playerFinishAfterLandscapeRestore = false
                finish()
            }
        }, 800L)
        return true
    }
    return false
}
