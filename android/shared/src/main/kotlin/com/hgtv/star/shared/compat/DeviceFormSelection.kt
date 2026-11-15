// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.compat

import android.content.Context
import android.content.Intent

/**
 * 首页首次启动与设置页共用同一个设备类型选择 Activity。
 *
 * 这里只放跨模块稳定的 action / extra 键；具体弹窗、输入限制与落盘都在
 * `:app` 的 `DeviceFormActivity` 中维护一份。
 */
object DeviceFormSelection {
    const val ACTION="com.hgtv.star.action.SELECT_DEVICE_FORM"
    const val EXTRA_FIRST_RUN="first-run"
    const val EXTRA_CURRENT_FORM="current-form"
    const val EXTRA_TV_STYLE="tv-style"
    const val EXTRA_SELECTED_FORM="selected-form"
    const val EXTRA_CHANGED="changed"

    private const val ACTIVITY_CLASS="com.hgtv.star.DeviceFormActivity"

    fun intent(
        context: Context,
        firstRun: Boolean,
        currentForm: String?,
        tvStyle: Boolean,
    ): Intent =
        Intent(ACTION).setClassName(context.packageName,ACTIVITY_CLASS).apply {
            putExtra(EXTRA_FIRST_RUN,firstRun)
            putExtra(EXTRA_CURRENT_FORM,currentForm.orEmpty())
            putExtra(EXTRA_TV_STYLE,tvStyle)
        }
}
