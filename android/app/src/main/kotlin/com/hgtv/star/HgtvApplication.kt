// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.multidex.MultiDex
import com.hgtv.star.shared.debug.Diagnostics
import com.umeng.commonsdk.UMConfigure
import com.umeng.umcrash.UMCrash

/**
 * 进程入口。
 *
 * 这里只保留进程级、必须早于 Activity 的初始化：
 *
 * 1. **先把第二个 dex 装进来**。21+ 是 ART 原生多 dex，这层什么也不做；19/20 上 Dalvik
 *    只认一个 classes.dex，`MultiDex.install` 必须在任何应用类被用到之前跑，因此只能挂在
 *    Application 上。
 * 2. **再固定 OkHttp 的 Android 平台实现**（见 [com.hgtv.star.core.OkHttpPlatformCompat]）。
 *    云 OS 会裁掉 OkHttp 探测的 Conscrypt 组件，必须赶在友盟和业务网络线程之前完成。
 * 3. **初始化友盟统计与 APM**（见 [initUmeng]）。启动阶段的崩溃和性能数据要尽早开始采集。
 * 缓存、TLS / DNS / 签名预热、会话保活与书库加载由启动页统一发起
 *（见 [HgtvStartup]），因此首屏能先稳定画出来，再完成其余初始化。
 */
class HgtvApplication: Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if(android.os.Build.VERSION.SDK_INT<21) MultiDex.install(this)
        // Dalvik 会在类校验阶段解析 Platform 引用的 JVM 平台实现，而 BouncyCastle JSSE /
        // OpenJSSE 已被 R8 裁掉，强行切到 JVM 分支会把 Platform 初始化成永久失败状态。
        // Android 4.4 使用 OkHttp 原生的 Android 平台分支即可，兼容层只在 ART 上启用。
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            runCatching { com.hgtv.star.core.OkHttpPlatformCompat.installAndroidFallback() }
                .onFailure { problem ->
                    Diagnostics.warn("HGTV-OKHTTP","OkHttp 平台兼容初始化失败",problem)
                }
        }
    }

    override fun onCreate() {
        super.onCreate()
        initUmeng()
        // 调试包才记录每个请求的 DNS / TCP / TLS 分解（见 `NetTiming`）以及
        // API 请求 / 重试 / 失败现场（见 `RequestScope`）。正式包里这些开关恒为 false。
        com.hgtv.star.core.NetLog.timing=BuildConfig.DEBUG
        com.hgtv.star.core.NetLog.apiLogging=BuildConfig.DEBUG
        if(BuildConfig.DEBUG) {
            // Application 阶段也会发 TNC / 设备注册；先装一个最早的 logcat 出口，
            // 页面启动后 TV / Phone 会用自己的面板出口覆盖它。
            com.hgtv.star.core.NetLog.log={ line -> Diagnostics.trace("HGTV-NET",line) }
            com.hgtv.star.core.NetLog.event={ level,line ->
                when(level) {
                    com.hgtv.star.core.NetLogLevel.ERROR -> Diagnostics.error("HGTV-NET",line)
                    com.hgtv.star.core.NetLogLevel.WARN -> Diagnostics.warn("HGTV-NET",line)
                    com.hgtv.star.core.NetLogLevel.DEBUG -> Diagnostics.trace("HGTV-NET",line)
                }
            }
        }
    }

    /**
     * 初始化 U-App 统计与 U-APM。
     *
     * `preInit` 不采集设备信息；APM 配置必须在正式初始化之前写入。
     * 第三方 SDK 初始化失败不能影响应用启动，因此这里统一兜底并只记本地诊断。
     */
    private fun initUmeng() {
        runCatching {
            UMConfigure.setLogEnabled(BuildConfig.DEBUG)
            UMConfigure.preInit(this,UMENG_APP_KEY,UMENG_CHANNEL)
            UMCrash.initConfig(Bundle().apply {
                putBoolean(UMCrash.KEY_ENABLE_CRASH_JAVA,true)
                putBoolean(UMCrash.KEY_ENABLE_CRASH_NATIVE,true)
                putBoolean(UMCrash.KEY_ENABLE_ANR,true)
                putBoolean(UMCrash.KEY_ENABLE_PA,true)
                putBoolean(UMCrash.KEY_ENABLE_LAUNCH,true)
                putBoolean(UMCrash.KEY_ENABLE_MEM,true)
                putBoolean(UMCrash.KEY_ENABLE_NET,true)
            })
            UMConfigure.init(
                this,
                UMENG_APP_KEY,
                UMENG_CHANNEL,
                UMConfigure.DEVICE_TYPE_PHONE,
                ""
            )
        }.onFailure { problem ->
            Diagnostics.warn("HGTV-UMENG","友盟初始化失败",problem)
        }
    }

    private companion object {
        const val UMENG_APP_KEY="6aeaba5b863270068cb58786"
        const val UMENG_CHANNEL="HGTV"
    }
}
