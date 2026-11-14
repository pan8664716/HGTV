// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.app.Activity
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.SurfaceView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.exoplayer2.ui.PlayerView
import com.hgtv.star.core.VideoFrameMode
import com.hgtv.star.core.toFit
import com.hgtv.star.phone.ui.PhoneSheet
import com.hgtv.star.phone.ui.PhoneStyle
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.phoneSize
import com.hgtv.star.phone.ui.resizeMode

internal object PhonePlayerViewSupport {
    /**
     * SurfaceView 默认透明区域会挖穿 Activity 窗口。播放器 FIT 后的黑边一旦落在这块
     * 透明 Surface 上，下层详情页就会透出来。显式使用 OPAQUE Surface，让黑边由视频层
     * 自己输出黑色，而不是依赖 Activity 背景。
     */
    fun useOpaqueVideoSurface(view: PlayerView) {
        runCatching {
            (view.videoSurfaceView as? SurfaceView)?.holder?.setFormat(PixelFormat.OPAQUE)
        }
    }

    /**
     * 横屏底栏入口。常态半透明白、按下更亮：点下去要有反馈。
     */
    fun chip(activity: Activity, text: String, onClick: () -> Unit): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(Color.WHITE)
        phoneSize(11)
        gravity = Gravity.CENTER
        setPadding(activity.dp(8), activity.dp(4), activity.dp(8), activity.dp(4))
        background = PhoneSheet.pillBackground(
            0x33FFFFFF, 0x73FFFFFF, activity.dp(11).toFloat()
        )
        setOnClickListener { onClick() }
    }

    fun chipLp(activity: Activity, left: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        activity.dp(64), ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { leftMargin = activity.dp(left) }

    /** 进度条染色是 API 21 才有的，19/20 上保持系统默认（不影响功能）。 */
    fun tintSeekBar(bar: SeekBar) {
        if (Build.VERSION.SDK_INT < 21) return
        runCatching {
            bar.progressTintList = android.content.res.ColorStateList.valueOf(PhoneStyle.accent)
            bar.secondaryProgressTintList = android.content.res.ColorStateList.valueOf(
                0x66FFFFFF
            )
            bar.thumbTintList = android.content.res.ColorStateList.valueOf(PhoneStyle.accent)
            bar.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x4DFFFFFF)
        }
    }

    fun requestOrientation(activity: Activity, value: Int): Boolean = runCatching {
        activity.requestedOrientation = value
    }.onFailure {
        android.util.Log.w("HGTV-PHONE", "orientation $value rejected: ${it.message}")
    }.isSuccess

    fun autoRotateEnabled(activity: Activity): Boolean = runCatching {
        android.provider.Settings.System.getInt(
            activity.contentResolver,
            android.provider.Settings.System.ACCELEROMETER_ROTATION,
            0,
        ) == 1
    }.getOrDefault(false)

    fun applyFrameMode(
        page: PhoneVideoPage,
        landscapeVideo: PlayerView,
        mode: VideoFrameMode,
        feedUnderlay: Boolean,
    ) {
        val target = mode.toFit().resizeMode()
        page.setResizeMode(target)
        landscapeVideo.resizeMode = target
        if (feedUnderlay) PhoneSession.setFeedVideoResize(target)
    }

    fun keepScreenOn(window: Window, on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
