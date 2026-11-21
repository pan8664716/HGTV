// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.app.Activity
import android.content.Context
import com.hgtv.star.phone.ui.PhoneDialog
import com.hgtv.star.shared.data.RecoverySnapshotManager

/**
 * 首次进入时的「先调一下显示」提示。
 *
 * 手机端的 UI 大小与每行卡片数都收在「设置 → 外观导航 → 外观与布局」里，新用户不会主动去找：
 * 字太小、卡片太挤时，他们更可能直接卸掉，而不是翻设置。这里只弹**一次**，
 * 给出「取消 / 去设置」两条路，不做任何强制。
 *
 * 标记落在 [PhoneUiScale] 用的那份 SharedPreferences 里（`phone-display-v1`）：
 * 与字号设置同一份文件，清数据会一起清掉，下次重进仍然会提示一次。
 */
object PhoneFirstRunTip {

    private const val PREFS = "phone-display-v1"
    private const val KEY = "firstRunDisplayTipShown"

    private const val TITLE = "先调一下显示"
    private const val MESSAGE =
        "UI大小、每行显示几张卡片都能在设置里改。" +
            "觉得字小、或者卡片挤在一起，现在就可以调。"
    private const val CANCEL = "取消"
    private const val OPEN = "去设置"

    /** 第一次进主界面时弹一次；已经弹过就直接返回。 */
    fun ensure(activity: Activity) {
        val prefs = activity.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY, false)) return
        // 先落盘再弹：用户在弹窗还没出来时切走 / 被杀进程，也不该在下次又弹一遍。
        prefs.edit().putBoolean(KEY, true).apply()
        RecoverySnapshotManager.schedule(activity.applicationContext)
        PhoneDialog(activity, TITLE)
            .message(MESSAGE)
            .button(CANCEL)
            .button(OPEN, danger = true) {
                PhoneSettingsActivity.open(activity, PhoneSettingsPage.APPEARANCE)
            }
            .show()
    }
}
