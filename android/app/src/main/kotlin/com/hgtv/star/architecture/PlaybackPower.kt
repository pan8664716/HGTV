// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.architecture

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

/**
 * 播放期间的**电源与网络保活**。
 *
 * 改造前这两件事都没做，现场表现是两条完全不同的报障：
 *
 * 1. **「暂停一会儿再回来就放不了了」**——`syncKeepScreenOn()` 只保证屏幕不灭
 *    （`FLAG_KEEP_SCREEN_ON`），不保证 **CPU** 不休眠。电视盒子在暂停 / 后台时进入
 *    Doze，回来时连接已断，要重走 DNS + TLS + 取地址。播放中让 `ExoPlayer` 持一个
 *    `PARTIAL_WAKE_LOCK`（`setWakeMode(C.WAKE_MODE_NETWORK)`）就能避免。
 *
 * 2. **「播一会儿卡一下」**——Wi-Fi 省电模式（PS-Poll）下，接入点会把包攒着等
 *    信标周期再发。高码率视频对此极其敏感：每隔几秒一次 100~300ms 的停顿，
 *    表现就是周期性卡顿，而测速、ping 都正常。`WIFI_MODE_FULL_HIGH_PERF` 是标准解法。
 *
 * ## 为什么要自己管 WifiLock
 *
 * `WifiLock` 不会自动释放：忘记 release 会持续耗电并阻止系统省电，是明确的负面行为。
 * 因此这里只暴露「在播」与「不在播」两个动作，由 `syncKeepScreenOn()` 这个**唯一**的
 * 状态同步点驱动——它本来就已经按「前台 + 播放页 + 没出错 + 未定时停止 + 正在播」
 * 算出过一次布尔量，两件事用同一个判据，不会出现「屏幕常亮但 Wi-Fi 锁已放」这种错位。
 *
 * ## Android 4.4 兼容
 *
 * `WIFI_MODE_FULL_HIGH_PERF` 是 API 12 引入的，4.4 可用。
 * 从 Android 10 起这个模式需要 `ACCESS_FINE_LOCATION` 才能完全生效，
 * 但**不申请也不会崩**，只是退化成普通 `WIFI_MODE_FULL`——电视盒子大多没有定位权限，
 * 这个降级是可接受的（且盒子通常插网线，走不到这条路）。
 */
internal class PlaybackPower(context: Context) {

    private val app = context.applicationContext
    private var wifiLock: WifiManager.WifiLock? = null

    /** 开始播放：拿住 Wi-Fi 高性能锁。重复调用是幂等的。 */
    fun acquire() {
        if (wifiLock?.isHeld == true) return
        runCatching {
            val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            @Suppress("DEPRECATION")
            val lock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "hgtv-playback")
            lock.setReferenceCounted(false)
            lock.acquire()
            wifiLock = lock
        }.onFailure {
            // 部分盒子把 WifiManager 阉掉了（纯网口机型）：拿不到就算了，
            // 这是优化不是功能，不能因此影响播放。
            Log.w("HGTV-POWER", "wifi lock unavailable: ${it.javaClass.simpleName}")
        }
    }

    /** 停止播放 / 离开播放页 / 退到后台：立刻放掉。 */
    fun release() {
        val lock = wifiLock ?: return
        wifiLock = null
        runCatching { if (lock.isHeld) lock.release() }
    }
}
