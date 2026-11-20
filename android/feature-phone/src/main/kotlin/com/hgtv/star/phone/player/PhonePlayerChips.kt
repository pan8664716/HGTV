// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.hgtv.star.shared.ui.UiText
import android.view.View
import com.hgtv.star.core.PlaybackSpeed

internal fun PhonePlayerActivity.syncChips() {
    syncLandscapeSettingsInfoForChips()
    chipSpeed.text = PlaybackSpeed.label(chipUserSpeed)
    chipQuality.text = "${library?.maxQuality ?: 1080}P"
    // 弹幕那颗：显示当前 0..6 档位。
    val mode=library?.danmakuMode ?: com.hgtv.star.core.DanmakuMode.DEFAULT
    chipDanmaku.text=UiText.get(com.hgtv.star.shared.R.string.ui_0091, mode.optionLabel)
    // **选集按钮永远显示**：之前按 `recommend` 把它藏了，而从推荐流进全屏正是最常见
    // 的路径（`PhoneFeedView` 一律带 `EXTRA_RECOMMEND=true`），结果就是「全屏里没有选集按钮」。
    // 文案带总数（用户要求）：`选集 3/171`，一眼知道现在在第几集、全剧多少集。
    val total = detail?.episodes?.size ?: 0
    chipEpisode.text = if (total > 0) UiText.get(com.hgtv.star.shared.R.string.ui_0255, index + 1, total) else UiText.get(com.hgtv.star.shared.R.string.ui_0254, index + 1)
    chipEpisode.visibility = View.VISIBLE
}

/**
 * 弹幕快捷开关：点一次切下一档（0 行 → 1 行 → … → 6 行 → 0 行）。
 *
 * 与电视播放页那颗「弹幕」按钮同一职责，只是把「弹菜单再选」压成一次点击：
 * 手机上最常见的动作就是「挡画面了，先关掉」，不该为此打开一整个设置面板。
 * 七档循环的次序按 [com.hgtv.star.core.DanmakuMode] 的枚举顺序，落盘值与设置页共用同一份。
 */
internal fun PhonePlayerActivity.cycleDanmaku() {
    if (playerControlsLocked) return
    val lib = library ?: return
    // 循环顺序在 `:core`（`DanmakuMode.next`，带单测）：两端共用同一套 0..6 次序。
    val next = lib.danmakuMode.next()
    val previous = lib.danmakuMode
    if (com.hgtv.star.shared.BuildConfig.DEBUG) {
        android.util.Log.i(
            "HGTV-PHONE",
            "danmaku chip ${previous.storedValue} -> ${next.storedValue}"
        )
    }
    lib.danmakuMode = next
    // 立刻作用在画面上：弹幕层的显示方式、以及「隐藏档不再发请求」那条规则
    // 都由 `DanmakuView.mode` 与 `DanmakuLoader` 负责（与电视端同一份实现）。
    playerPage.danmakuView.mode = next
    applyDanmakuSettingsForChips()
    syncChips()
    hint.text = UiText.get(com.hgtv.star.shared.R.string.ui_0092, next.optionLabel)
    hint.visibility = View.VISIBLE
    hint.bringToFront()
    val token = chipHintToken
    root.postDelayed({ if (chipHintToken == token) hint.visibility = View.GONE }, 900)
    // 从隐藏切回可见、而这一集还没取过弹幕：补一次请求（电视端同一判据）。
    if (previous == com.hgtv.star.core.DanmakuMode.HIDDEN && next.showsStyle) {
        detail?.let { d ->
            d.episodes.getOrNull(index)?.let { loadDanmakuForChips(it, d.series.id) }
        }
    }
}

/**
 * 倍速快捷开关：点一次切到下一档，与弹幕按钮同一种操作。
 *
 * 快捷按钮只走 0.50 → 1.00 → 1.50 → 2.00 → 2.50 → 3.00 → 0.50 六个粗档位；写入书库，
 * 推荐页接手时读到的也是刚切好的这一档。
 *
 * 精确到 0.05× 的完整区间在播放设置滑杆里，快捷按钮不承担连续调节。
 */
internal fun PhonePlayerActivity.cycleSpeed() {
    if (playerControlsLocked) return
    val lib = library ?: return
    val options = PlaybackSpeed.quickOptions
    val current = options.indexOfFirst { kotlin.math.abs(it - chipUserSpeed) < .001f }
        .takeIf { it >= 0 } ?: options.indexOf(1f).coerceAtLeast(0)
    val next = options[(current + 1) % options.size]
    endTempSpeedForChips()
    chipUserSpeed = next
    lib.playbackSpeed = next
    player?.setPlaybackSpeed(next)
    syncChips()
    hint.text = UiText.get(com.hgtv.star.shared.R.string.ui_0026, PlaybackSpeed.label(next))
    hint.visibility = View.VISIBLE
    hint.bringToFront()
    val token = chipHintToken
    root.postDelayed({ if (chipHintToken == token) hint.visibility = View.GONE }, 900)
}
