// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.WeakHashMap

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
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= 29) {
                window.isNavigationBarContrastEnforced = false
            }
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        } else if (Build.VERSION.SDK_INT >= 19) {
            window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        }
    }
}

private val phoneBottomInsets = WeakHashMap<Context, Int>()

private fun Context.phoneRootContext(): Context {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        val base = current.baseContext
        if (base === current) break
        current = base
    }
    return current
}

/** 当前 Activity 最近一次由 WindowInsets 得到的底部系统安全区。 */
fun Context.phoneBottomInsetPx(): Int = phoneBottomInsets[phoneRootContext()] ?: 0

/**
 * 绑定真实 WindowInsets。
 *
 * 不使用 `navigation_bar_height` 资源：手势导航、三键导航、导航栏隐藏时资源值
 * 并不等于当前窗口的底部安全区。Insets 可能连续回调多次，最后一次才是稳定值。
 */
fun View.bindPhoneInsets(onBottomChanged: (Int) -> Unit = {}) {
    val rootContext = context.phoneRootContext()
    fun apply(insets: WindowInsetsCompat) {
        val bottom = insets.getInsets(
            WindowInsetsCompat.Type.navigationBars() or
                WindowInsetsCompat.Type.systemGestures() or
                WindowInsetsCompat.Type.mandatorySystemGestures(),
        ).bottom
        if (phoneBottomInsets[rootContext] == bottom) return
        phoneBottomInsets[rootContext] = bottom
        onBottomChanged(bottom)
    }
    ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
        apply(insets)
        insets
    }
    ViewCompat.getRootWindowInsets(this)?.let(::apply)
    ViewCompat.requestApplyInsets(this)
}

/** 当前页面是否应使用深色导航栏图标（浅色背景时）。 */
fun phoneNavigationBarUsesLightIcons(): Boolean {
    val color = PhoneStyle.background
    val luma = (Color.red(color) * .299f + Color.green(color) * .587f + Color.blue(color) * .114f) / 255f
    return luma > .58f
}

/**
 * 统一沉浸式导航栏外观：透明底色、关闭对比度蒙层、按页面背景切换图标亮暗。
 *
 * `lightIcons=true` 表示背景是浅色，需要深色图标。
 */
@Suppress("DEPRECATION")
fun Activity.applyPhoneNavigationBar(lightIcons: Boolean = false) {
    if (Build.VERSION.SDK_INT >= 21) {
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 28) window.navigationBarDividerColor = Color.TRANSPARENT
    }
    if (Build.VERSION.SDK_INT >= 29) {
        window.isNavigationBarContrastEnforced = false
    }
    var visibility = window.decorView.systemUiVisibility
    if (Build.VERSION.SDK_INT >= 26) {
        visibility = if (lightIcons) {
            visibility or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } else {
            visibility and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        }
        window.decorView.systemUiVisibility = visibility
    }
    if (Build.VERSION.SDK_INT >= 30) {
        window.insetsController?.setSystemBarsAppearance(
            if (lightIcons) android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0,
            android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )
    }
}

/** 状态栏高度；取不到时按 24dp 兜底。 */
fun Activity.phoneStatusBarHeight(): Int {
    val id = resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
}
