// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Phone / TV 共用的外部存储权限入口。
 *
 * 会话、设备 ID、收藏、历史和设置都由同一份恢复快照承载，因此权限请求也必须由
 * 启动页统一完成，不能再由某一端的设置页或账号流程各问一次。
 */
object RecoveryPermissions {
    const val LAUNCH_REQUEST_CODE = 0x71
    const val RESET_REQUEST_CODE = 0x72

    val REQUIRED = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    )

    fun hasPublicAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
    }

    fun isGranted(results: IntArray): Boolean =
        results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }

    fun isStorageMounted(): Boolean =
        Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED

    /** 返回 true 表示正在等系统权限回调；false 表示当前已经具备权限或系统不支持运行时申请。 */
    fun request(activity: Activity, requestCode: Int): Boolean {
        if (Build.VERSION.SDK_INT < 23 || hasPublicAccess(activity)) return false
        ActivityCompat.requestPermissions(activity, REQUIRED, requestCode)
        return true
    }
}
