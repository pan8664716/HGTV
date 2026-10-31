// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.content.Context
import android.os.Build

/**
 * 应用版本号。
 *
 * TV 端从「唯一模块」拆出来之后，`feature-tv` 的 `BuildConfig` 是**库模块**的
 * BuildConfig——它没有 `VERSION_NAME` / `VERSION_CODE` 这两个字段（只有 application
 * 模块才由 AGP 注入）。因此版本号改为运行时从 PackageManager 读一次，
 * 由 `MainActivity.onCreate` 调 [init]。
 *
 * 读不到就留 `?`：面板上显示一个问号，比整个调试面板崩掉好。
 */
object AppVersion {
    @Volatile var name: String = "?"
    @Volatile var code: Long = 0L

    /** 面板上那一行：`1.2.9.1(67)`。 */
    val label: String get() = "$name($code)"

    fun init(context: Context) {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            name = info.versionName ?: "?"
            code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        }
    }
}
