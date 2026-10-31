// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.architecture

import android.content.Context
import android.content.Intent
import android.util.Log

/** 重新进入应用：先交给系统启动入口，再结束当前进程，避免两套界面共用旧进程状态。 */
object AppRelaunch {

    fun relaunchAndExit(context: Context) {
        val app = context.applicationContext
        runCatching {
            // 启动入口负责按最新的设备类型重新分流到手机或 TV 界面。
            val intent = app.packageManager.getLaunchIntentForPackage(app.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            if (intent != null) app.startActivity(intent)
        }.onFailure { Log.w("HGTV", "Cannot relaunch app", it) }
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
