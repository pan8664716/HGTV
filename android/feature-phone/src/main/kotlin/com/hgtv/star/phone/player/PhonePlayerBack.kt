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
        // 用户在竖屏播放页点「全屏」进来的：返回先退回竖屏，再按一次才退出。
        // 从推荐页点「全屏」直接进来（`landscapeEnteredFromPortrait=false`）照旧直接退出，
        // 否则会先停在一个用户没见过的竖屏页上。
        if (landscapeEnteredFromPortrait) {
            landscapeEnteredFromPortrait = false
            exitFullscreen()
            return true
        }
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
