// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import java.security.Provider
import java.security.Security

/**
 * 让 OkHttp 在 Android 上使用通用平台实现。
 *
 * OkHttp 4 在 `Dalvik` 上会初始化 `AndroidPlatform`，并依赖系统 Conscrypt、
 * `NetworkSecurityPolicy` 等组件。部分云 OS / 定制电视系统会替换或裁掉这些组件，
 * 类校验阶段直接抛 `NoClassDefFoundError: okhttp3.internal.platform.Platform`；
 * 该错误发生在 `Platform` 静态初始化中，之后整个进程都无法再使用 OkHttp。
 *
 * 本工程所有业务请求都固定 HTTP/1.1，TLS 也由 [HgtvTls] 自行配置，不需要
 * AndroidPlatform 的 ALPN / Conscrypt 探测。因此在任何网络线程启动前，临时隐藏
 * `Dalvik` 标记并放一个空 Provider 到首位，让 `Platform` 只初始化一次通用实现，
 * 随后立即恢复系统状态。必须在 `Application.attachBaseContext` 中调用。
 */
object OkHttpPlatformCompat {
    private const val VM_NAME = "java.vm.name"
    private const val PROVIDER_NAME = "HGTV-OkHttp-Platform"
    private const val PLATFORM_CLASS = "okhttp3.internal.platform.Platform"

    @Volatile
    private var installed = false

    @Suppress("DEPRECATION")
    @Synchronized
    fun installAndroidFallback() {
        if (installed) return
        val originalVmName = System.getProperty(VM_NAME)
        if (!originalVmName.equals("Dalvik", ignoreCase = true)) {
            installed = true
            return
        }

        val probe = object : Provider(PROVIDER_NAME, 1.0, "Temporary OkHttp platform probe") {}
        try {
            System.setProperty(VM_NAME, "HGTV-Android")
            check(Security.insertProviderAt(probe, 1) == 1) {
                "无法安装 OkHttp 平台探测 Provider"
            }
            Class.forName(PLATFORM_CLASS, true, OkHttpPlatformCompat::class.java.classLoader)
            installed = true
        } finally {
            runCatching { Security.removeProvider(PROVIDER_NAME) }
            if (originalVmName == null) {
                runCatching { System.clearProperty(VM_NAME) }
            } else {
                runCatching { System.setProperty(VM_NAME, originalVmName) }
            }
        }
    }
}
