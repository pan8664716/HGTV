// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone.ui

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.WindowManager

/**
 * 手机端统一的沉浸式窗口设置。
 *
 * **每个 Activity 都必须调**：状态栏一旦不透明，它就会用自己的底色（主题的
 * `colorPrimaryDark` 或系统默认）盖在页面顶部，与页面的石墨底拼成两条颜色，
 * 从主页跳进搜索 / 播放 / 设置时最明显——「两种颜色不和谐」说的就是这个。
 *
 * 4.4 只有 `FLAG_TRANSLUCENT_STATUS`；5.0+ 才有 `statusBarColor`。
 * 两条路都只影响观感，拿不到就退回普通模式，不影响功能。
 */
fun Activity.phoneEdgeToEdge() {
    runCatching {
        if (Build.VERSION.SDK_INT >= 21) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.statusBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        } else if (Build.VERSION.SDK_INT >= 19) {
            window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        }
    }
}

/** 状态栏高度；取不到时按 24dp 兜底。 */
fun Activity.phoneStatusBarHeight(): Int {
    val id = resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
}
