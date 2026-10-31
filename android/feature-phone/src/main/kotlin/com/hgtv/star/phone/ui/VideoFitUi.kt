// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone.ui

import androidx.media3.ui.AspectRatioFrameLayout
import com.hgtv.star.core.VideoFit

/**
 * 画面模式 → media3 的 `RESIZE_MODE_*`，**手机端唯一的一处绑定**。
 *
 * 判定（竖屏片源一律铺满 / 横屏片源按用户选择）在 `:core` 的
 * [com.hgtv.star.core.VideoFrameMode.toFit]，与电视端同源；这里只把结果换成平台常量。
 *
 * 为什么要独立成文件：这条映射原先在推荐流、播放页、横竖屏切换里各写了一份
 * `when (VideoFrameMode) { … }`，改一档要改四处，漏一处就是「设置里选了等比铺满、
 * 某个入口还是完整画面」。
 */
fun VideoFit.resizeMode(): Int = when (this) {
    VideoFit.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    VideoFit.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    VideoFit.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
}
