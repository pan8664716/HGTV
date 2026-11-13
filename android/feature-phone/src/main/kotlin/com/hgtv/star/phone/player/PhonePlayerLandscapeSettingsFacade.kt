// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.widget.LinearLayout
import com.hgtv.star.core.VideoFrameMode
import com.hgtv.star.shared.data.Library

/**
 * 横屏播放设置：只保留右侧 40% 的可改栏。
 *
 * 已经设置好的值常驻在进度条下方，打开面板时不再在左边重复一遍。右边宽 40% 屏，
 * 选项按每行 3 个折行；整列放进 `ScrollView`，滑到底能看到最后一行。
 */
internal fun PhonePlayerActivity.openLandscapeSettings(lib: Library) =
    openLandscapeSettingsImpl(lib)

/**
 * 把已经设置好的值刷到进度条下面那一行。
 *
 * 这一行是常驻摘要，不依赖设置面板是否打开。
 */
internal fun PhonePlayerActivity.syncLandscapeSettingsInfo() =
    syncLandscapeSettingsInfoImpl()

internal fun PhonePlayerActivity.dismissLandscapeSettings() =
    dismissLandscapeSettingsImpl()

internal fun PhonePlayerActivity.dismissLandscapeSettingsForRelated() =
    dismissLandscapeSettings()

internal fun PhonePlayerActivity.buildSettingsRowsForLandscape(
    panel: LinearLayout,
    lib: Library,
) = buildSettingsRowsImpl(panel, lib, perLine = 3)

internal fun PhonePlayerActivity.setChromeVisibleForLandscape(visible: Boolean) =
    setChromeVisible(visible)

internal fun PhonePlayerActivity.scheduleChromeHideForLandscape() =
    scheduleChromeHide()

/**
 * 按用户的画面模式把画面落到位，不再对竖屏片源强制 ZOOM。
 */
internal fun PhonePlayerActivity.syncFrameMode() = syncFrameModeImpl()

internal fun PhonePlayerActivity.applyFrameMode(mode: VideoFrameMode) =
    applyFrameModeImpl(mode)
