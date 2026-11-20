// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.hgtv.star.shared.ui.UiText
import com.hgtv.star.core.Detail

internal fun PhonePlayerActivity.openEpisodes() {
    if (playerControlsLocked) return
    val d = detail
    if (d == null) {
        // 详情还没到：给一句提示，别让按钮看起来是坏的。
        toast(UiText.get(com.hgtv.star.shared.R.string.ui_0041))
        return
    }
    if (playerLandscapeMode) openLandscapeEpisodes(d) else openPortraitEpisodes(d)
}

/** 竖屏选集：底部整宽浮层。每行 5 个，一页行数跟着设置走。 */
private fun PhonePlayerActivity.openPortraitEpisodes(d: Detail) {
    PhonePlayerEpisodePanels.showPortrait(
        this,
        playerSheet,
        d,
        index,
        library,
        { i -> playForEpisodes(i) },
        { scheduleChromeHideForEpisodes() },
    )
}

/**
 * 横屏选集：TV 式底部限高浮层。
 *
 * **每行 10 个**；一页几行由设置页的「选集每页」决定
 * （20 / 30 / 50 / 80 / 100 → 2 / 3 / 5 / 8 / 10 行），放不下时网格内部滚动。
 */
private fun PhonePlayerActivity.openLandscapeEpisodes(d: Detail) {
    PhonePlayerEpisodePanels.showLandscape(
        this,
        playerSheet,
        d,
        index,
        library,
        { i -> playForEpisodes(i) },
        { scheduleChromeHideForEpisodes() },
    )
}
