// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.hgtv.star.phone.PhoneMainActivity
import com.hgtv.star.shared.compat.DeviceForm
import com.hgtv.star.shared.compat.detectDeviceForm
import com.hgtv.star.shared.debug.StartupTrace
import com.hgtv.star.shared.ui.LoadingDotsView
import com.hgtv.star.tv.MainActivity

/**
 * 启动页与形态路由：唯一带 `LAUNCHER` / `LEANBACK_LAUNCHER` 的组件。
 *
 * Logo 由系统启动画面显示；这一页只显示三点加载动画，完成本地初始化后再转发：
 *
 * * 电视 / 盒子 → [MainActivity]（横屏、遥控器焦点导航、1280×720 设计画布）
 * * 手机 / 平板 → [PhoneMainActivity]（竖屏、触摸、手势播放）
 *
 * ## 为什么必须是正常窗口
 *
 * NoDisplay 的活动**不建窗口**。`startActivity` 之后立刻 `finish()`，部分国产 TV 固件
 * 会先把任务清掉、再丢掉刚起来的目标页——用户读到的是「点图标没反应」，而且没有任何
 * 提示可循。透明窗口也不参与系统启动画面，进程初始化稍慢时用户会以为点击没有生效。
 * 因此这里使用不透明的正常 Activity，让点击后的第一帧就有明确反馈。
 *
 * ## 为什么必须在这一层分叉
 *
 * TV 与手机是两套完全独立的页面实现，任何一端都不该为了「能启动」去依赖另一端。
 * 放在 `:app` 里，两个 feature 模块彼此不可见，依赖方向保持单向。
 *
 * ## 用户设置优先于自动判定
 *
 * 设置页里的「使用设备」可以强制成某一档（`Library.deviceForm` 落盘的
 * `phone` / `tablet` / `tv`）。这里**先读设置**，只有 `auto` 才走自动探测——
 * 否则在电视上手动切成「手机」会被下次启动重新纠正回来，那个开关等于没用。
 *
 * 初始化成功后才读取这个设置并转发；初始化失败则留在本页显示重试，不把错误状态
 * 带进主界面。
 */
class LaunchActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var dots: LoadingDotsView
    private lateinit var errorText: TextView
    private lateinit var retryButton: TextView
    private var startupCleanup: (() -> Unit)?=null
    private var routed=false
    private var traceStartedAt=0L

    override fun onCreate(savedInstanceState: Bundle?) {
        traceStartedAt=StartupTrace.now()
        StartupTrace.mark("launch.onCreate.start")
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(BACKGROUND))
        applyFullscreen()
        buildStartupView()
        setContentView(root)
        StartupTrace.done("launch.onCreate",traceStartedAt)
        // 首帧可见后再启动书库 / 运行时 / 友盟等后台初始化，然后立即路由。
        root.postOnAnimation {
            StartupTrace.done("launch.firstFrame",traceStartedAt)
            if(!isFinishing && !isDestroyed) {
                HgtvStartup.afterFirstFrame(this)
                routeToTarget()
            }
        }
    }

    private fun buildStartupView() {
        root=FrameLayout(this).apply { setBackgroundColor(BACKGROUND) }
        val content=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            gravity=Gravity.CENTER_HORIZONTAL
        }
        dots=LoadingDotsView(this).apply { label="正在准备应用…" }
        content.addView(dots,LinearLayout.LayoutParams(dp(360),ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin=dp(26)
        })
        errorText=TextView(this).apply {
            text="初始化失败，请重试"
            setTextColor(0xFFF2F5F8.toInt())
            textSize=15f
            gravity=Gravity.CENTER
            visibility=View.GONE
        }
        content.addView(errorText,LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin=dp(24) })
        retryButton=TextView(this).apply {
            text="重试"
            setTextColor(0xFF4DA3FF.toInt())
            textSize=15f
            gravity=Gravity.CENTER
            isClickable=true
            isFocusable=true
            setPadding(dp(24),dp(10),dp(24),dp(10))
            background=GradientDrawable().apply {
                shape=GradientDrawable.RECTANGLE
                cornerRadius=dp(24).toFloat()
                setColor(0xFF2A3038.toInt())
            }
            visibility=View.GONE
            setOnClickListener {
                showLoading()
                beginInitialization(retry=true)
            }
        }
        content.addView(retryButton,LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin=dp(14) })
        root.addView(content,FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))
    }

    private fun beginInitialization(retry: Boolean = false) {
        val startedAt=StartupTrace.now()
        StartupTrace.mark("launch.initialization.start", "retry=$retry")
        startupCleanup?.invoke()
        val ready: () -> Unit = {
            StartupTrace.done("launch.initialization.ready",startedAt)
            if(!isFinishing && !isDestroyed) routeToTarget()
        }
        val failed: (Throwable) -> Unit = { problem ->
            StartupTrace.done(
                "launch.initialization.failed",
                startedAt,
                "error=${problem.javaClass.simpleName}:${problem.message.orEmpty().take(120)}",
            )
            android.util.Log.w("HGTV-STARTUP","初始化失败",problem)
            if(!isFinishing && !isDestroyed) showFailure()
        }
        startupCleanup=if(retry) {
            HgtvStartup.retry(this,ready,failed)
        } else {
            HgtvStartup.initialize(this,ready,failed)
        }
    }

    private fun routeToTarget() {
        if(routed) return
        routed=true
        val routeStarted=StartupTrace.now()
        StartupTrace.mark("launch.route.start")
        val target = runCatching { targetFor(this) }
            .onFailure { android.util.Log.w("HGTV-FORM", "形态判定失败，退回电视界面", it) }
            .getOrElse { MainActivity::class.java }
        // 用 `Log.w` 而不是 `Log.i`：R8 的 `-assumenosideeffects` 会把 `Log.i` 整条删掉，
        // 正式包里就再也看不到这一行——而「点图标没反应」恰恰只在正式包上出现过一次
        // （小明 Q1 Pro，2026-11）。这两条是现场唯一能把
        // 「路由没跑 / 路由跑了但目标没起来 / 目标起来了但画面没上屏」分开的证据，必须留住。
        android.util.Log.w("HGTV-FORM", "route -> ${target.simpleName}")
        var started = runCatching { startActivity(Intent(this, target)) }.isSuccess
        if (!started) {
            // 目标页起不来（理论上不会发生，但不能赌）：换成另一套界面再试一次。
            // 进错界面用户至少能看见东西、能从设置里切回来；什么都不显示就彻底没救。
            val fallback = if (target == MainActivity::class.java) {
                PhoneMainActivity::class.java
            } else {
                MainActivity::class.java
            }
            android.util.Log.w("HGTV-FORM", "route failed, falling back to ${fallback.simpleName}")
            started = runCatching { startActivity(Intent(this, fallback)) }
                .onFailure { android.util.Log.e("HGTV-FORM", "两套界面都起不来", it) }
                .isSuccess
        }
        if(started) {
            StartupTrace.done("launch.route",routeStarted, "target=${target.simpleName}")
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        } else {
            StartupTrace.done("launch.route.failed",routeStarted)
            routed=false
            showFailure()
        }
    }

    private fun targetFor(context: Context): Class<*> {
        val stored = runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_DEVICE_FORM, "auto")
        }.getOrNull().orEmpty()
        val profile = detectDeviceForm()
        val form = DeviceForm.ofStored(stored) ?: profile.form
        // 形态判定只有这一次机会，判错就进错界面，而且用户很难自己走出来。
        // 把判据原样打进 logcat：出问题时一行就能看出是「没识别到触屏」还是「品牌词误伤」。
        android.util.Log.w(
            "HGTV-FORM",
            "stored=$stored form=${profile.form} touch=${profile.touch} sw=${profile.swDp}dp " +
                "diag=${(profile.diagonalIn * 10).toInt() / 10f}in score=${profile.score} hits=${profile.hits}"
        )
        return if (form == DeviceForm.TV) MainActivity::class.java else PhoneMainActivity::class.java
    }

    private fun showLoading() {
        dots.visibility=View.VISIBLE
        errorText.visibility=View.GONE
        retryButton.visibility=View.GONE
    }

    private fun showFailure() {
        dots.visibility=View.GONE
        errorText.visibility=View.VISIBLE
        retryButton.visibility=View.VISIBLE
        retryButton.requestFocus()
    }

    @Suppress("DEPRECATION")
    private fun applyFullscreen() {
        window.decorView.systemUiVisibility=(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            )
        if(android.os.Build.VERSION.SDK_INT>=21) {
            window.statusBarColor=Color.TRANSPARENT
            window.navigationBarColor=Color.TRANSPARENT
        }
    }

    override fun onDestroy() {
        StartupTrace.mark("launch.onDestroy", "routed=$routed")
        startupCleanup?.invoke()
        startupCleanup=null
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value*resources.displayMetrics.density+0.5f).toInt()

    private companion object {
        const val BACKGROUND=0xFF1E2228.toInt()
        /** 与 `Library` 用的是同一个偏好文件（见 `Library.prefs`）。 */
        const val PREFS = "native-library-v1"
        const val KEY_DEVICE_FORM = "deviceForm"
    }
}
