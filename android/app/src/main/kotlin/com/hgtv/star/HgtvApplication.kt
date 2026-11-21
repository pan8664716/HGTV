// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star
import android.app.Application
import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.multidex.MultiDex
import com.hgtv.star.shared.data.RecoveryPermissions
import com.hgtv.star.shared.data.RecoverySnapshotManager
import com.hgtv.star.shared.ui.UiText

/**
 * 进程入口。
 *
 * `Application` 只保留 Android 4.4 / 5.0 的 MultiDex 安装。Dalvik 只认一个
 * `classes.dex`，这一步必须在任何应用类被使用前完成，不能移到 Activity。
 *
 * 书库预热、运行时预热、友盟与网络日志统一由 `LaunchActivity` 在首帧后启动，
 * 避免进程入口先做一轮初始化再显示第一个窗口。
 */
class HgtvApplication: Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if(android.os.Build.VERSION.SDK_INT<21) MultiDex.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        UiText.init(this)
        AppIntegrityGuard.install(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) {
                if (RecoveryPermissions.hasPublicAccess(activity)) {
                    RecoverySnapshotManager.onForeground(activity.applicationContext)
                }
            }
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) {
                RecoverySnapshotManager.schedule(activity.applicationContext, 0L)
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
