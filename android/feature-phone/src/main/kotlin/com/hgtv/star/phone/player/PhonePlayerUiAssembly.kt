// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.graphics.Color
import android.widget.FrameLayout
import com.hgtv.star.phone.ui.bindPhoneInsets
import com.hgtv.star.phone.ui.PhoneUiScale

internal fun PhonePlayerActivity.buildUi() {
    // 播放页窗口和根 View 都保持不透明。下层推荐页 / 简介页可能有独立 SurfaceView，
    // 普通透明底板无法可靠遮住系统图层。
    root = FrameLayout(this).apply {
        setBackgroundColor(Color.BLACK)
    }
    playerSharedDanmaku = createPhoneDanmakuView(this)

    PhoneUiScale.withOrientation(this, landscape = false) { buildPortrait() }
    PhoneUiScale.withOrientation(this, landscape = true) { buildLandscape() }
    installPlayerDanmakuSync()

    buildPlayerCommonUi()

    setContentView(root)
    root.bindPhoneInsets()
    syncPlaybackLockUi()
    applyMode()
}

/** 竖屏共享弹幕只属于当前集页；拖动开始先摘掉，IDLE 后由现有 attach 流程挂回。 */
private fun PhonePlayerActivity.installPlayerDanmakuSync() {
    playerEpisodePager.swipeStarted = {
        playerEpisodePager.pages().forEach { it.unmountSharedDanmaku(playerSharedDanmaku) }
    }
}

private fun PhonePlayerActivity.buildLandscape() {
    // 横屏 ViewPager2 集页：每页独立视频、封面、弹幕与黑边层。
    buildLandscapeBase()

    buildLandscapeTopControls()

    buildLandscapeBottomControls()
}
