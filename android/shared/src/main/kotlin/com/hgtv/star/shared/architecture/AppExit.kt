// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.architecture

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log

/**
 * 用户主动退出：先做同步能做的收尾，再移除任务并结束当前进程。
 *
 * `finish()` 只销毁 Activity，进程级仓库、线程池、播放器和缓存仍会留在内存里。
 * 退出应用必须结束进程；这里留一小段时间给 [beforeExit] 里提交的最后一次落盘完成。
 */
object AppExit {
    private val main=Handler(Looper.getMainLooper())
    @Volatile private var exiting=false

    fun exit(activity: Activity, beforeExit: () -> Unit = {}) {
        if(exiting) return
        exiting=true
        runCatching(beforeExit).onFailure {
            Log.w("HGTV","Exit cleanup failed",it)
        }
        if(Build.VERSION.SDK_INT>=21) activity.finishAndRemoveTask()
        else activity.finishAffinity()
        // 页面已经从任务栈移除；延迟半秒只用于让最后一次 flush 落到磁盘。
        main.postDelayed({
            Process.killProcess(Process.myPid())
            // 部分定制 ROM 会拦截 killProcess；System.exit 是最后的兜底。
            kotlin.system.exitProcess(0)
        },500)
    }
}
