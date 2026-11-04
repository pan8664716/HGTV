// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.hgtv.star.phone.PhoneMainActivity
import com.hgtv.star.shared.compat.DeviceForm
import com.hgtv.star.shared.compat.detectDeviceForm
import com.hgtv.star.tv.MainActivity

/**
 * 启动路由：唯一带 `LAUNCHER` / `LEANBACK_LAUNCHER` 的组件。
 *
 * 它**不画任何东西**（主题 `LaunchRouter` 是半透明、无标题、无动画），
 * 只做一次形态判定然后转发：
 *
 * * 电视 / 盒子 → [MainActivity]（横屏、遥控器焦点导航、1280×720 设计画布）
 * * 手机 / 平板 → [PhoneMainActivity]（竖屏、触摸、手势播放）
 *
 * ## 为什么是「半透明」而不是 `Theme.NoDisplay`
 *
 * NoDisplay 的活动**不建窗口**。`startActivity` 之后立刻 `finish()`，部分国产 TV 固件
 * 会先把任务清掉、再丢掉刚起来的目标页——用户读到的是「点图标没反应」，而且没有任何
 * 提示可循。2026-11 小明 Q1 Pro 投影仪上就是这样：1.2.9.1（没有这一层，桌面直接起
 * MainActivity）正常，1.3.0 点开毫无反应。
 *
 * 半透明主题让这一层**有一个窗口**，任务里始终有东西，转发不会落空；透明底色保证
 * 不会闪一块黑。代价只是多一个空窗口，它活不到一帧。
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
 * 直接读 SharedPreferences 而不构造 `Library`：后者要起写队列、读镜像、
 * 解历史记录，在启动链路上是几百毫秒的量级，而这里只需要一个字符串。
 */
class LaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // **转发必须万无一失**：这一层只要有一次什么都不做，用户读到的就是「点图标没反应」，
        // 而且没有任何提示可循（2026-11 现场：小明 Q1 Pro 投影仪上 1.3.0 点开毫无反应，
        // 1.2.9.1 正常——那一版没有这一层，桌面直接起 MainActivity）。
        // 因此这里的每一步都兜住异常，并且**永远**有一个目标页被拉起来。
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
        finish()
        // 这一层不画任何东西，转场动画只会让目标页晚出来一帧。
        @Suppress("DEPRECATION")
        if (started) overridePendingTransition(0, 0)
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

    private companion object {
        /** 与 `Library` 用的是同一个偏好文件（见 `Library.prefs`）。 */
        const val PREFS = "native-library-v1"
        const val KEY_DEVICE_FORM = "deviceForm"
    }
}
