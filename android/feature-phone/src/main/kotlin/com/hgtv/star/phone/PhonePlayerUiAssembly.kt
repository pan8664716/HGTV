// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.graphics.Color
import android.widget.FrameLayout
import com.hgtv.star.phone.ui.PhoneUiScale

internal fun PhonePlayerActivity.buildUi() {
    // 窗口是半透明的（见 `@style/PlayerTheme`）：目的是让下层推荐页**不被 onStop**，
    // 它的窗口与视频 Surface 全程活着——这才是「进 / 退都不重新加载」的前提。
    // 窗口透明不等于画面透明：本页自己铺一层不透明黑，letterbox 与转场那一下
    // 看到的是黑，而不是下面那一页。
    // 借下层 Surface 时整页透明：只画遮罩与控制层，画面从下层透上来。
    root = FrameLayout(this).apply {
        setBackgroundColor(if (playerFeedUnderlay) Color.TRANSPARENT else Color.BLACK)
    }

    PhoneUiScale.withOrientation(this, landscape = false) { buildPortrait() }
    PhoneUiScale.withOrientation(this, landscape = true) { buildLandscape() }

    buildPlayerCommonUi()

    setContentView(root)
    applyMode()
}

private fun PhonePlayerActivity.buildLandscape() {
    // 相邻集那一页（横屏版）：黑底 + 左上角「标题 第N集」，与竖屏的 ghost 页同一个角色。
    // 加在 landscape 之前，因此它永远在下面，整页滑开才露出来。
    buildLandscapeBase()

    buildLandscapeTopControls()

    buildLandscapeBottomControls()
}
