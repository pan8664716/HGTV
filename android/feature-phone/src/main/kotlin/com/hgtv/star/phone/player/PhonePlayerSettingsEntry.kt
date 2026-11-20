// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.hgtv.star.shared.ui.UiText
import android.widget.LinearLayout
import com.hgtv.star.shared.data.Library
import kotlinx.coroutines.launch

internal fun PhonePlayerActivity.openSettings() {
    if (playerControlsLocked) return
    val activity = this
    // 自动收起之后长按中间仍然能打开设置：先把控制层叫回来，
    // 否则面板会出现在一层已经隐藏的界面上，用户看不到自己改的是哪一页。
    if (!playerChromeVisible) setChromeVisible(true)
    // 书库还没读完时**不能直接 return**：那正是「点了没反应」的一种。
    // 等它读完再弹，用户的这一下点击不会白点。
    val lib = library
    if (lib == null) {
        scope.launch {
            library = PhoneServices.awaitLibrary(activity)
            openSettings()
        }
        return
    }
    if (playerLandscapeMode) openLandscapeSettingsImpl(lib) else openPortraitSettings(lib)
}

/**
 * 竖屏：底部整宽浮层，**只占屏高 1/3**（用户规范）。
 *
 * 各行与电视端的播放设置面板对应（清晰度 / 倍速 / 画面模式 / 解码 / 画面缩放 /
 * 弹幕显示 / 弹幕速度 / 弹幕大小 / 弹幕透明度），写的是同一份落盘值。
 *
 * 为什么限定三分之一：竖屏播放页上半屏是画面，设置铺到九成高就等于把画面关掉了。
 * 三分之一高（1080×1920 上约 640px）+ 内部滚动，一次能看到三到四项，
 * 手指一滑就到底，既知道「面板就这么高」又不会找不到最后一项。
 */
private fun PhonePlayerActivity.openPortraitSettings(lib: Library) {
    playerSheet.show(UiText.get(com.hgtv.star.shared.R.string.ui_0116), landscape = false, heightFraction = 1f / 3f) { panel ->
        buildSettingsRowsImpl(panel, lib, perLine = 0)
    }
    scheduleChromeHide()
}
