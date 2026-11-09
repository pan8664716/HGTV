// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.architecture

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 重新进入应用。
 *
 * 设备形态切换只重建任务，不在这里立刻杀进程：`startActivity` 返回时只代表
 * 启动请求已交给 ActivityManager，部分 ROM 还没有真正拉起新页面。此时
 * `killProcess` 会把启动事务一起掐掉，用户看到的就是闪退或“点了没反应”。
 * 清空任务后由新的启动入口重新分流即可；旧 Activity 会在任务清理时正常销毁。
 *
 * 其他确实需要完整重启的流程仍可调用 [relaunchAndExit]。
 */
object AppRelaunch {

    /** 按落盘的设备形态重新进入；不主动杀进程。 */
    fun switchDeviceForm(context: Context) {
        val started=startLaunchActivity(context)
        if(!started) {
            Log.w("HGTV", "Cannot switch device form: launcher activity not found")
            return
        }
    }

    /** 完整重启：只有启动入口确实拉起后才结束进程。 */
    fun relaunchAndExit(context: Context) {
        if(!startLaunchActivity(context)) {
            Log.w("HGTV", "Cannot relaunch app: launcher activity not found")
            return
        }
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun startLaunchActivity(context: Context): Boolean {
        val app=context.applicationContext
        val launch=runCatching {
            app.packageManager.getLaunchIntentForPackage(app.packageName)
                ?: Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setPackage(app.packageName)
                }
        }.onFailure { Log.w("HGTV", "Cannot resolve launcher intent", it) }
            .getOrNull()
            ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return runCatching { app.startActivity(launch) }
            .onFailure { Log.w("HGTV", "Cannot relaunch app", it) }
            .isSuccess
    }
}
