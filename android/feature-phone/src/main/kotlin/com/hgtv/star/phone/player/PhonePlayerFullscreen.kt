// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.view.WindowManager

@Suppress("DEPRECATION")
internal fun PhonePlayerActivity.enterImmersive() {
    // Android 17 上只隐藏系统栏仍可能在顶部保留一块系统栏高度的底板。
    // `LAYOUT_IN_SCREEN` 先让窗口本身铺到状态栏 / 刘海区域，再关闭 decor fit；
    // 旧的全屏标志必须放在 `setDecorFitsSystemWindows(false)` 之后，避免被新版窗口重置。
    window.addFlags(
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_FULLSCREEN
    )
    if (Build.VERSION.SDK_INT >= 21) {
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        // 系统栏被隐藏时黑色不可见；若新版系统短暂保留底板，也只会与视频黑边融合。
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
    }
    if (Build.VERSION.SDK_INT >= 28) {
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }
    if (Build.VERSION.SDK_INT >= 29) {
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
    }
    if (Build.VERSION.SDK_INT >= 30) {
        window.setDecorFitsSystemWindows(false)
    }

    // **四个 LAYOUT 标志缺一不可**。
    //
    // 早先只有 FULLSCREEN | HIDE_NAVIGATION | IMMERSIVE_STICKY | LAYOUT_STABLE：
    // 状态栏确实藏起来了，但窗口仍然按「状态栏存在」来排版，于是屏幕最上面空出一条
    // 与状态栏等高的黑边——用户读到的就是「状态栏没显示，却空出了它的位置」。
    //
    // LAYOUT_FULLSCREEN / LAYOUT_HIDE_NAVIGATION 让内容真正铺到两条系统栏底下，
    // LAYOUT_STABLE 保证隐藏 / 显示过程中布局不跳。三条一起才等价于「全屏」。
    window.decorView.systemUiVisibility = (
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    if (Build.VERSION.SDK_INT >= 30) {
        window.insetsController?.let { controller ->
            controller.hide(
                android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.navigationBars()
            )
            controller.systemBarsBehavior =
                android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
    // 不借下层 Surface 时给窗口本身也铺黑，避免任何未覆盖区域落回系统白色兜底。
    window.setBackgroundDrawable(
        ColorDrawable(if (playerFeedUnderlay) Color.TRANSPARENT else Color.BLACK)
    )
    // 横屏顶部那条标题栏本来就贴 0：全屏时不要任何额外留白。
    root.setPadding(0, 0, 0, 0)
    root.requestLayout()
    if (Build.VERSION.SDK_INT >= 20) window.decorView.requestApplyInsets()
}

/**
 * 进全屏（用户点「全屏」/ 从推荐页带 EXTRA_FULLSCREEN 进来）。
 *
 * 用户主动点全屏后固定横屏，不再把方向交还传感器；否则自动旋转会在全屏里
 * 根据手机姿态反复切回竖屏。退出全屏或离开播放页时再恢复系统方向策略。
 */
internal fun PhonePlayerActivity.enterFullscreen() {
    if (playerLandscapeMode) return
    // 全屏期间不再借用推荐页 Surface，避免转屏过程中透出下层的相邻页。
    dropUnderlay()
    // 主动进全屏：退出全屏时那把「钉住竖屏」的尺子要先收掉，
    // 否则它会在设备立起来时把方向交回传感器，把人从全屏里踢出去。
    releasePortraitPin()
    if (!requestOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) return
    playerLandscapeModeSlot = true
    applyModeForFullscreen()
}

/**
 * 申请固定方向，**在拿不到时安静地退让**。
 *
 * 播放页的窗口在 Android 9.0+ 是半透明的（values-v28/styles.xml，见那里的注释：
 * 这是「推荐页 ↔ 播放页内容不断」的前提）。而 8.0 / 8.1 的系统对「半透明 Activity
 * 申请固定方向」会直接抛
 * IllegalStateException: Only fullscreen opaque activities can request orientation，
 * 9.0 起才放开——因此那两个版本用的是不透明主题，走不到这里。
 *
 * 兜底仍然要留着：厂商 ROM 把窗口判成 translucent 的情况出现过（主题叠加、悬浮窗），
 * 真抛出来时不能让播放页崩掉。转不过去就留在当前形态，播放本身不受影响。
 *
 * 返回 false = 这次没切成。
 */
internal fun PhonePlayerActivity.requestOrientation(value: Int): Boolean =
    PhonePlayerViewSupport.requestOrientation(this,value)

/**
 * 系统「自动旋转屏幕」是否打开。
 *
 * 播放页的方向策略完全按它分岔（见 enterFullscreen / exitFullscreen）：
 * 开着就跟随传感器，关着就只认用户的「全屏」点击。
 */
private fun PhonePlayerActivity.autoRotateEnabled(): Boolean =
    PhonePlayerViewSupport.autoRotateEnabled(this)

/**
 * 退出横屏：先锁竖屏让窗口立刻转过来，再把方向交还系统策略。
 *
 * 交还（USER）是这一项需求的关键：
 * * 系统**开着自动旋转** → 用户横着拿手机会自动再进全屏，竖着拿就停在竖屏；
 * * 系统**关着自动旋转** → USER 等价于「保持当前方向」，停在竖屏不会自己转。
 *
 * 1.2 秒的延迟是为了等这一次转屏走完：立刻设 USER 时窗口还在竖屏动画里，
 * 系统可能按旧方向结算，表现成「退出后闪一下又横过去」。
 */
internal fun PhonePlayerActivity.exitFullscreen() {
    if (!playerLandscapeMode) {
        finish()
        return
    }
    requestOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
    playerLandscapeModeSlot = false
    applyModeForFullscreen()
    // 退出后**先钉住竖屏**，等设备真的立起来再放开自动旋转（见 pinPortraitUntilUpright）。
    //
    // 早先这里是无条件 postDelayed(1200) { requestOrientation(USER) }：
    // 看全屏时手机是横着拿的，1.2 秒后方向交回传感器，页面立刻又转回横屏——
    // 用户按返回看到的是「没反应」，连按几次都出不去（用户反馈的
    // 「第三集播完进不了第四集，退出全屏后连续返回都回不到推荐页」）。
    pinPortraitUntilUpright()
}

/**
 * 横屏里的竖版短剧点「全屏」后进入竖屏全屏。
 *
 * 这里**不能复用 exitFullscreen**：那个入口会在方向传感器满足条件时把方向
 * 立刻交还系统，横屏设备 / 强制横屏环境会马上又转回横屏。用户主动选择全屏时
 * 应稳定停在竖屏，直到返回或离开播放页。
 */
internal fun PhonePlayerActivity.enterPortraitFullscreen() {
    if (!playerLandscapeMode) return
    // 横屏里看竖版短剧时，下层推荐页可能已经滑到下一页。
    // 切回竖屏全屏必须使用本页自己的画面，不能继续借那一块 Surface。
    dropUnderlay()
    releasePortraitPin()
    if (!requestOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)) return
    playerLandscapeModeSlot = false
    playerPortraitFullscreenFromLandscape = true
    applyModeForFullscreen()
}

/**
 * 退出全屏后**钉住竖屏**，直到设备真的立起来。
 *
 * 为什么需要这一步：看全屏时人是横着拿手机的，这时把方向交回传感器
 *（SCREEN_ORIENTATION_USER）等于让页面自己转回全屏。等设备立起来再放开，
 * 既不会把人拽回全屏，也不影响之后「横过来自动进全屏」这条规则。
 *
 * 系统自动旋转关着时不必钉：那时 USER 本来就是竖屏。
 * 没有方向传感器（部分模拟器 / 平板）时 OrientationEventListener.enable
 * 会失败，这时保持竖屏不动——比反复横竖切换强。
 */
private fun PhonePlayerActivity.pinPortraitUntilUpright() {
    releasePortraitPin()
    if (!autoRotateEnabled()) return
    val listener = object : android.view.OrientationEventListener(this) {
        override fun onOrientationChanged(orientation: Int) {
            // -1 = 放不平（平放在桌上）：这时不知道朝向，继续等。
            if (orientation == ORIENTATION_UNKNOWN) return
            // 0 / 360 是正立、180 是倒立，±45 度以内都算「立着」。
            val upright = orientation <= 45 || orientation >= 315 || orientation in 135..225
            if (!upright) return
            releasePortraitPin()
            requestOrientation(ActivityInfo.SCREEN_ORIENTATION_USER)
        }
    }
    // 没有方向传感器（部分模拟器 / 平板）：canDetectOrientation 为 false，
    // 这时保持竖屏不动，比反复横竖切换强。
    if (!runCatching { listener.canDetectOrientation() }.getOrDefault(false)) return
    runCatching { listener.enable() }
    playerPortraitPin = listener
}

internal fun PhonePlayerActivity.releasePortraitPin() {
    playerPortraitPin?.let { runCatching { it.disable() } }
    playerPortraitPin = null
}
