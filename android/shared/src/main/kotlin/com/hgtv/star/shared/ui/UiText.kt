// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.ui

import android.content.Context
import android.content.res.Resources

/**
 * 统一读取 `shared` 模块的界面文案资源。
 *
 * 语言选择完全交给 Android 资源系统（`values` / `values-zh-rTW` 等），
 * 这里只提供不依赖页面上下文的资源读取入口。
 */
object UiText {
    private var resources: Resources? = null

    fun init(context: Context) {
        resources = context.applicationContext.resources
    }

    fun get(id: Int, vararg args: Any): String {
        val resources = resources ?: error("UiText is not initialized")
        return if (args.isEmpty()) {
            resources.getString(id)
        } else {
            resources.getString(id, *args)
        }
    }
}
