// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

internal fun PhonePlayerActivity.seekTo(fraction: Float) {
    if (playerControlsLocked) return
    val duration = player?.duration ?: 0L
    if (duration <= 0) return
    player?.seekTo((duration * fraction).toLong())
}

/** 把播放器当前的画面尺寸补到页面上（`bind` 之后调用）。 */
internal fun PhonePlayerActivity.syncVideoFrame() = syncVideoFrameImpl()

/**
 * 画面缩放（设置 → 播放偏好，1–100%）。
 *
 * 只缩**播放画面本身**：`PlayerView` 按中心等比缩放，页面底板仍是整屏黑，
 * 因此缩小后看到的是「画面变小、四周留黑」，与 TV 的 `applyVideoScale` 同一效果。
 */
internal fun PhonePlayerActivity.applyVideoScale() = applyVideoScaleImpl()

/**
 * 把「播放显示」那四项铺到这一行上：**剧名 / 集数靠左，网速 / 时间靠右**。
 *
 * 显隐规则（与电视端同一套口径，用户指定）：
 *
 * | 四项开关 | 显示 | 是否跟随控制层 |
 * | --- | --- | --- |
 * | 全都没勾 | **四项全显示**（默认形态） | 是：清屏时一起收起 |
 * | 勾了任意一项 | 只显示勾选的那几项 | 否：**一直显示**，清屏也不收 |
 *
 * 横屏与竖屏各有一份（`landInfoRow` / `infoRow`），规则完全相同。
 */
internal fun PhonePlayerActivity.syncPlayInfo() = syncPlayInfoImpl()
