// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.view.View

/**
 * 把「播放显示」四项铺到竖屏与横屏的信息行：剧名 / 集数靠左，网速 / 时间靠右。
 *
 * 全都没勾时按四项全开显示并跟随控制层；勾了任意一项时只显示选中项且常驻。
 */
internal fun PhonePlayerActivity.syncPlayInfoImpl() {
    val lib = library
    val d = detail
    if (lib == null || d == null || !playerInfoRowInitialized) return
    val anyChecked =
        lib.playerTitle || lib.playerEpisode || lib.playerClock || lib.playerSpeed
    val showAll = !anyChecked
    val followChrome = showAll
    val left = ArrayList<String>(2)
    if (showAll || lib.playerTitle) left += d.series.title
    if (showAll || lib.playerEpisode) {
        left += com.hgtv.star.core.PlaybackInfoLine.episode(index, d.episodes.size)
    }
    val right = ArrayList<String>(2)
    if (showAll || lib.playerSpeed) right += com.hgtv.star.shared.debug.NetSpeed.line()
    if (showAll || lib.playerClock) {
        val calendar = java.util.Calendar.getInstance()
        right += com.hgtv.star.core.PlaybackInfoLine.clock(
            calendar.get(java.util.Calendar.HOUR_OF_DAY),
            calendar.get(java.util.Calendar.MINUTE),
        )
    }
    val leftText = left.joinToString("  ·  ")
    val rightText = right.joinToString("  ·  ")
    playerInfoLeft.text = leftText
    playerInfoRight.text = rightText
    if (playerLandInfoRowInitialized) {
        playerLandInfoLeft.text = leftText
        playerLandInfoRight.text = rightText
    }
    val empty = left.isEmpty() && right.isEmpty()
    val visible = !empty && (!followChrome || playerChromeVisible)
    playerInfoRow.visibility = if (visible) View.VISIBLE else View.GONE
    playerPortraitTop.visibility = if (visible) View.VISIBLE else View.GONE
    if (playerPageInitialized) playerPage.setTopScrimVisible(visible)
    if (playerLandInfoRowInitialized) {
        playerLandInfoRow.visibility = if (visible) View.VISIBLE else View.GONE
    }
}

/**
 * 把已经设置好的画面模式、缩放、解码和定时刷到横屏进度条下方。
 */
internal fun PhonePlayerActivity.syncLandscapeSettingsInfoImpl() {
    if (!playerLandSettingsInfoInitialized) return
    val lib = library ?: run {
        playerLandSettingsInfo.text = ""
        return
    }
    val parts = ArrayList<String>(4)
    parts += lib.frameMode.label
    parts += "缩放 ${Math.round(lib.videoScale)}%"
    parts += lib.decoderMode.label
    if (nextSeasonSleepTimer.active) parts += "定时 ${nextSeasonSleepTimer.label()}"
    playerLandSettingsInfo.text = parts.joinToString("  ·  ")
}
