// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone.ui

import android.view.ViewTreeObserver
import android.view.View
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.PlayerView
import com.hgtv.star.core.VideoFit

/**
 * 画面模式 → ExoPlayer 的 `RESIZE_MODE_*`，**手机端唯一的一处绑定**。
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

/**
 * 换片前先写入一个方向正确的宽高比。
 *
 * `PlayerView` 会保留上一条视频的 AspectRatioFrameLayout 比例，直到新视频的
 * `onVideoSizeChanged` 到达。快速翻到方向相反的片源时，首帧会先按旧比例铺开；
 * 这里优先使用接口下发的实际宽高，缺失时才按卡片方向兜底。
 */
internal fun PlayerView.primeAspectRatio(
    width: Int,
    height: Int,
    verticalSource: Boolean,
) {
    val aspectRatio = if (width > 0 && height > 0) {
        width.toFloat() / height
    } else {
        if (verticalSource) 9f / 16f else 16f / 9f
    }
    (videoSurfaceView?.parent as? AspectRatioFrameLayout)?.setAspectRatio(aspectRatio)
}

/** 页面已经有正确比例但还没出帧时，先收起快门，别把上一页旧帧按新比例拉伸出来。 */
internal fun PlayerView.hideShutteruntilFirstFrame() {
    runCatching {
        val shutter = findViewById<View>(com.google.android.exoplayer2.ui.R.id.exo_shutter)
        shutter?.visibility = View.VISIBLE
    }
}

/** 等预置的宽高比完成一次布局后再开始解码，避免首帧先落到旧尺寸的 Surface。 */
internal fun PlayerView.runAfterAspectRatioLayout(action: () -> Unit) {
    val frame = videoSurfaceView?.parent as? AspectRatioFrameLayout
    if (frame == null) {
        post(action)
        return
    }
    val observer = frame.viewTreeObserver
    if (!observer.isAlive) {
        post(action)
        return
    }
    observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            if (observer.isAlive) observer.removeOnPreDrawListener(this)
            action()
            return true
        }
    })
    frame.requestLayout()
}
