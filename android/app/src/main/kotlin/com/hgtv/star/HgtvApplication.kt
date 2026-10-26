// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import android.app.Application
import android.content.Context
import androidx.multidex.MultiDex

/**
 * 进程入口。
 *
 * 只有两件事：
 *
 * 1. **先把第二个 dex 装进来**。21+ 是 ART 原生多 dex，这层什么也不做；19/20 上 Dalvik
 *    只认一个 classes.dex，`MultiDex.install` 必须在任何应用类被用到之前跑，因此只能挂在
 *    Application 上。
 * 2. **再起会话保活**（见 [initSession]）。它要读本机的设备身份与会话镜像，
 *    因此必须排在 MultiDex 之后——那些类可能被分到第二个 dex。
 *
 * **友盟（统计与 APM）已整体移除**：这里不再有任何第三方 SDK 初始化，
 * 崩溃 / ANR / 性能 / 事件都不采集，也不往体外发任何东西。
 */
class HgtvApplication: Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if(android.os.Build.VERSION.SDK_INT<21) MultiDex.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        initSession()
    }

    /**
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

}
