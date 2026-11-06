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
 * 四件事，顺序不能换：
 *
 * 1. **先把第二个 dex 装进来**。21+ 是 ART 原生多 dex，这层什么也不做；19/20 上 Dalvik
 *    只认一个 classes.dex，`MultiDex.install` 必须在任何应用类被用到之前跑，因此只能挂在
 *    Application 上。
 * 2. **再固定 OkHttp 的 Android 平台实现**（见 [com.hgtv.star.core.OkHttpPlatformCompat]）。
 *    云 OS 会裁掉 OkHttp 探测的 Conscrypt 组件，必须赶在友盟和业务网络线程之前完成。
 * 3. **初始化友盟统计与 APM**（见 [initUmeng]）。启动阶段的崩溃和性能数据要尽早开始采集。
 * 4. **最后起会话保活**（见 [initSession]）。它要读本机的设备身份与会话镜像，
 *    因此必须排在 MultiDex 之后——那些类可能被分到第二个 dex。
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
        // 调试包才记录每个请求的 DNS / TCP / TLS 分解（见 `NetTiming`）；
        // 正式包里这个开关恒为 false，连监听器都不会装到 client 上。
        com.hgtv.star.core.NetLog.timing=BuildConfig.DEBUG
        startCaches()
        startTlsWarmup()
        startDnsWarmup()
        startSignWarmup()
        initSession()
        startPlaybackDevicePool()
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

    /**
     * 两处落盘缓存（都只在后台用，主线程零成本）：
     *
     * * **DNS**：第二次启动直接命中，省掉 `vas-lf-x` 那 1.6 秒（见 `HgtvDns`）；
     * * **播放索引 + 播放地址**：第二次播同一集省掉取地址（0.5~1.1s）与 moov 解析
     *   （0.7~0.9s）（见 `PlaybackCache`）。
     *
     * 放在 `onCreate` 而不是第一次用到时：两者的读取都在后台线程里，这里只是把
     * 「读盘」这条任务排进去，顺便让它在界面还在建视图时就跑完。
     * 目录都在 `cacheDir` 下——系统存储紧张时可以整目录回收，不会伤到用户数据。
     */
    private fun startCaches() {
        runCatching {
            com.hgtv.star.core.HgtvDns.init(java.io.File(cacheDir,"dns-v1.txt"))
            com.hgtv.star.core.PlaybackCache.init(java.io.File(cacheDir,"playback-v1"))
            com.hgtv.star.core.RecommendCache.init(java.io.File(cacheDir,"feed-v1.json"))
            com.hgtv.star.core.DetailCache.init(java.io.File(cacheDir,"detail-v1.json"))
        }.onFailure { problem ->
            Diagnostics.warn("HGTV-CACHE","缓存目录初始化失败",problem)
        }
    }

    /**
     * 把「第一次签名」挪到进程刚起来的那半秒里。
     *
     * 冷启动实测：推荐首屏那 3.1 秒中，`sign ... took=564ms`，而同一进程里第二次签名
     * 只要 30~60ms——差的全是类加载 + 首轮 JIT（SM3 / MD5 / 那条 128 轮混淆循环），
     * 与网络无关。这里在 Application 阶段就用一份假 body 签一遍，等推荐页真要发请求时
     * 这 500ms 已经花完了（而且是在后台、与建界面并行）。
     *
     * 用的是两端共享的进程级仓库：这一步只做本地运算，不建 client、不发请求。
     * 真正的 `preconnect` 也走同一个实例（连接必须落在它自己的池子里才复用得上）。
     */
    private fun startSignWarmup() {
        Thread({
            runCatching { com.hgtv.star.shared.data.AppRuntime.warmSignature(this) }
        }, "hgtv-sign-warm").start()
    }

    /**
     * 先把冷启动会碰到的域名解析出来（见 [com.hgtv.star.core.HgtvDns]）。
     *
     * 与 [startTlsWarmup] **并行**起两条线程：DNS 是几百毫秒级的活，而 TLS 预热要 2~3 秒，
     * 串着跑会让「第一条请求」白等 TLS。两条都不碰主线程，也不发应用层请求。
     */
    private fun startDnsWarmup() {
        Thread({
            runCatching { com.hgtv.star.core.HgtvDns.warm(com.hgtv.star.core.HgtvDns.BOOT_HOSTS) }
        }, "hgtv-dns-warm").start()
    }

    /**
     * 把系统根证书表的第一次解析挪到后台线程。
     *
     * 这张表是 `/system/etc/security/cacerts` 下 150 张 PEM，第一次读要逐张
     * `CertificateFactory.generateCertificate` + 算 `subjectX500Principal`（含 NFKC 归一化）。
     * 实测这台 2G 盒子 **2.5~3.0 秒**（见 `HgtvTls.ACCEPTED_ISSUERS`）。原先第一个碰它的调用
     * 是 `initSession()` 里建心跳 client，等于整段冷启动都压在这一次解析上：
     * 2026-10-27 的线程栈里，main 连续五帧停在 `DerValue.toByteArray`，
     * 而 Activity 根本还没开始建。
     *
     * 起一条线程先解，主线程随后无论从哪条路碰到它都只是拷一份数组。线程结束时通常
     * 界面还在建视图，两条路完全并行。
     */
    private fun startTlsWarmup() {
        Thread({
            runCatching { com.hgtv.star.core.HgtvTls.warm() }
                .onFailure { problem -> Diagnostics.warn("HGTV-TLS","TLS 预热失败",problem) }
        }, "hgtv-tls-warm").start()
    }

    /**
     * 只做三件事：建落盘实现 → 交给全局出口 → 建心跳。**不发请求**：
     * 冷启动那一次 `boot` 心跳由 `MainActivity` 在凭据就绪后触发（见 `SessionBootstrap.onAppStart`），
     * 这里启动时 SP 里的凭据还没经过 `Library` 的镜像恢复，早打等于带一份半成品身份去问。
     */
    private fun initSession() {
        runCatching {
            val store = com.hgtv.star.shared.data.AppSessionStore(this)
            com.hgtv.star.shared.data.SessionBootstrap.init(
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
                    com.hgtv.star.shared.data.SessionEvents.sendExpired(this,reason)
                }
            )
        }.onFailure { problem ->
            Diagnostics.warn("HGTV-SESSION","会话保活初始化失败",problem)
        }
    }

    /**
     * 单线程持续注册一次性播放设备。注册结果落到应用私有目录，
     * 播放取地址时消费一条并从磁盘删除。
     */
    private fun startPlaybackDevicePool() {
        runCatching {
            com.hgtv.star.shared.data.PlaybackDevicePool.start(this)
        }.onFailure { problem ->
            Diagnostics.warn("HGTV-DEVICE-POOL","一次性播放设备池启动失败",problem)
        }
    }

    private companion object {
        const val UMENG_APP_KEY="6aeaba5b863270068cb58786"
        const val UMENG_CHANNEL="HGTV"
    }
}
