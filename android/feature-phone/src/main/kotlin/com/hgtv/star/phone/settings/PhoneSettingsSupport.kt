// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.widget.TextView
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.phoneSize

internal fun PhoneSettingsActivity.appVersionNameImpl(): String = runCatching {
    packageManager.getPackageInfo(packageName, 0).versionName
}.getOrNull() ?: "?"

internal fun PhoneSettingsActivity.settingsDpImpl(value: Number): Int =
    dp(value.toFloat() * SETTINGS_DENSITY_SCALE)

internal fun TextView.settingsTextSizeImpl(value: Number): TextView =
    phoneSize(value.toFloat() * SETTINGS_TEXT_SCALE)

internal fun PhoneSettingsActivity.settingRowHeightImpl(): Int =
    settingsDpImpl(if (settingsLandscape) 36 else 42)

internal fun PhoneSettingsActivity.settingRowGapImpl(): Int =
    settingsDpImpl(if (settingsLandscape) 4 else 6)

internal fun anchorKeyImpl(kind: String, title: String): String = "$kind:$title"

internal fun genderLabelImpl(v: String) = when (v) {
    "male" -> "男频"
    "female" -> "女频"
    else -> "不限"
}

internal fun genderIndexImpl(v: String) = when (v) {
    "male" -> 1
    "female" -> 2
    else -> 0
}

internal fun PhoneSettingsActivity.statusBarHeightImpl(): Int {
    val id = resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
}
