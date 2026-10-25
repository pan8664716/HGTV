// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.multidex.MultiDex
import com.umeng.commonsdk.UMConfigure
import com.umeng.umcrash.UMCrash

/**
 * 进程入口。
 *
 * 两件事，顺序不能换：
 *
 * 1. **先把第二个 dex 装进来**。21+ 是 ART 原生多 dex，这层什么也不做；19/20 上 Dalvik
 *    只认一个 classes.dex，`MultiDex.install` 必须在任何应用类被用到之前跑，因此只能挂在
 *    Application 上。
 * 2. **再初始化友盟**。崩溃 / ANR / 性能上报要在进程最早期挂上，晚一步就会漏掉启动阶段的崩溃；
 *    但它必须排在 MultiDex 之后——SDK 自己的类可能被分到第二个 dex。
 */
class HgtvApplication: Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if(android.os.Build.VERSION.SDK_INT<21) MultiDex.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        initUmeng()
        initSession()
    }

    /**
     * 会话保活。放在友盟之后：友盟自己 `runCatching` 包好了，不会挡住这里。
     *
     * 只做三件事：建落盘实现 → 交给全局出口 → 建心跳。**不发请求**：
     * 冷启动那一次 `boot` 心跳由 `MainActivity` 在凭据就绪后触发（见 `SessionBootstrap.onAppStart`），
     * 这里启动时 SP 里的凭据还没经过 `Library` 的镜像恢复，早打等于带一份半成品身份去问。
     */
    private fun initSession() {
        runCatching {
            val store = com.hgtv.star.data.AppSessionStore(this)
            com.hgtv.star.data.SessionBootstrap.init(
                context = this,
                store = store,
                log = { line ->
                    // 只留一条出口：`Diagnostics.trace` 本身就是 `Log.i`，再补一次 `Log.i`
                    // 会让每条心跳在 logcat 里出现两遍。
                    Diagnostics.trace("HGTV-SESSION",line)
                },
                onExpired = { reason ->
                    // 凭证已由心跳清掉；广播到界面收尾（进程刚起、界面还没起时发出去也没关系，
                    // 因为凭证已经没有了，界面重建时读到的就是未登录态）。
                    com.hgtv.star.data.SessionEvents.sendExpired(this,reason)
                }
            )
        }.onFailure { problem ->
            Diagnostics.warn("HGTV-SESSION","会话保活初始化失败",problem)
        }
    }

    private fun initUmeng() {
        runCatching {
            // APM 性能监控开关：必须在 UMConfigure.init 之前设置。
            //
            // 这里每一项都显式写出来（而不是依赖默认值）：`initConfig` 是**整包覆盖**，
            // 只要调用一次，没写到的键就按默认值走，以后升级 SDK 时默认值一变，
            // 采集范围就会悄悄跟着变。
            UMCrash.initConfig(Bundle().apply {
                putBoolean(UMCrash.KEY_ENABLE_CRASH_JAVA,true)
                putBoolean(UMCrash.KEY_ENABLE_CRASH_NATIVE,true)
                putBoolean(UMCrash.KEY_ENABLE_ANR,true)
                putBoolean(UMCrash.KEY_ENABLE_PA,true)
                putBoolean(UMCrash.KEY_ENABLE_LAUNCH,true)
                putBoolean(UMCrash.KEY_ENABLE_MEM,true)
                putBoolean(UMCrash.KEY_ENABLE_NET,true)
                putBoolean(UMCrash.KEY_ENABLE_PAGE,true)
                putBoolean(UMCrash.KEY_ENABLE_POWER,true)
                putBoolean(UMCrash.KEY_ENABLE_CODE_LOG,true)
                putLong(UMCrash.KEY_PA_TIMEOUT_TIME,2000L)
            })
            // 调试包输出 SDK 日志、每条崩溃立刻上报；正式包关掉（关掉之后下次启动补报，不丢数据）。
            UMConfigure.setLogEnabled(BuildConfig.DEBUG)
            UMConfigure.preInit(this,UMENG_APP_KEY,UMENG_CHANNEL)
            UMConfigure.init(
                this,
                UMENG_APP_KEY,
                UMENG_CHANNEL,
                UMConfigure.DEVICE_TYPE_PHONE,
                null
            )
        }
    }

    private companion object {
        /** 友盟 AppKey（`设置 → 应用信息`）。会打进 APK，属于公开信息，不是密钥。 */
        const val UMENG_APP_KEY="6ad3d4236545637cd615f056"
        const val UMENG_CHANNEL="HGTV"
    }
}
