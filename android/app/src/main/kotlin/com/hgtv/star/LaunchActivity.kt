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
 * 它**不建视图**（主题是 `Theme.NoDisplay`），只做一次形态判定然后转发：
 *
 * * 电视 / 盒子 → [MainActivity]（横屏、遥控器焦点导航、1280×720 设计画布）
 * * 手机 / 平板 → [PhoneMainActivity]（竖屏、触摸、手势播放）
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
        startActivity(Intent(this, targetFor(this)))
        finish()
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
        android.util.Log.i(
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
