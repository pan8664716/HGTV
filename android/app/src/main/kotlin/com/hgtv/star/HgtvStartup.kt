// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import android.content.Context
import android.os.Bundle
import com.hgtv.star.core.refreshRecommendStatConfigAfterFirstFrame
import com.hgtv.star.core.refreshTncAsync
import com.hgtv.star.shared.data.AppRuntime
import com.hgtv.star.shared.data.IdentityBinder
import com.hgtv.star.shared.data.SessionBootstrap
import com.hgtv.star.shared.compat.DeviceForm
import com.hgtv.star.shared.debug.Diagnostics
import com.hgtv.star.shared.debug.StartupDeferredTasks
import com.hgtv.star.shared.debug.StartupTrace
import com.umeng.commonsdk.UMConfigure
import com.umeng.umcrash.UMCrash
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 启动页负责的本地初始化。
 *
 * 启动页不再等待本机书库：窗口首帧之后只注册缓存目录，随后立即路由；书库与会话保活
 * 在后台继续。TLS 直接使用内置根证书，DNS 与 TNC 直接使用已落盘配置，签名不做启动
 * 预热。主页面自己先铺缓存 / 骨架，不把启动路由变成网络或 IO 门禁。
 */
internal object HgtvStartup {
    private enum class State { IDLE, RUNNING, READY, FAILED }

    private val lock=Any()
    private val runtimeStarted=AtomicBoolean(false)
    private val cacheStarted=AtomicBoolean(false)
    private val preloadStarted=AtomicBoolean(false)
    private val deferredStarted=AtomicBoolean(false)
    private val deviceFormSelected=AtomicBoolean(false)
    private var state=State.IDLE
    private var failure: Throwable?=null
    private val waiters=mutableListOf<Waiter>()

    private class Waiter(
        val onReady: () -> Unit,
        val onFailure: (Throwable) -> Unit,
    )

    /**
     * 首帧已经可见后再启动进程级后台初始化。
     *
     * 这些工作原来在 `Application.onCreate` 里执行，会推迟第一个窗口出现。
     * 现在由启动页在首帧回调中触发：日志出口、缓存目录和会话存储先装好，
     * 书库与运行时预热放到后台；心跳、TNC 和友盟延后到播放首帧或各自兜底超时。
     */
    fun afterFirstFrame(context: Context) {
        val app=context.applicationContext
        if(!deferredStarted.compareAndSet(false,true)) return
        StartupTrace.mark("startup.afterFirstFrame")
        configureNetLogging()
        // 只注册缓存路径，不做读盘：目标页紧接着就要用这份路径铺缓存 / 骨架。
        startCaches(app)
        // 会话存储必须先装配，设备注册 Cookie 才有地方落盘；心跳客户端延后创建。
        initSessionStore(app)
        preload(app)
        StartupDeferredTasks.schedule("startup.sessionKeeper",20_000L) {
            startSessionKeeper()
        }
        StartupDeferredTasks.schedule("startup.recommendStat",25_000L) {
            runCatching {
                AppRuntime.repository(app).refreshRecommendStatConfigAfterFirstFrame()
            }
        }
        StartupDeferredTasks.schedule("startup.umeng",25_000L) {
            initUmeng(app)
        }
    }

    /**
     * 进程启动时在后台预热书库和运行时。
     *
     * 不等待结果，也不把任何回调绑到启动页：Activity 可以立即创建，书库读盘
     * 在后台完成。TvMainActivity 自己再调
     * [com.hgtv.star.shared.data.Library.loadAsync] 时，已加载就直接命中，
     * 加载中则自然加入同一条后台任务。
     */
    fun preload(context: Context) {
        val app=context.applicationContext
        if(!preloadStarted.compareAndSet(false,true)) return
        Thread({
            val startedAt=StartupTrace.now()
            StartupTrace.mark("startup.library.preload.start")
            val library=AppRuntime.library(app)
            library.loadAsync(
                onFailure={ problem ->
                    StartupTrace.done(
                        "startup.library.preload.failed",
                        startedAt,
                        "error=${problem.javaClass.simpleName}:${problem.message.orEmpty().take(120)}",
                    )
                },
                onReady={
                    StartupTrace.done("startup.library.preload",startedAt)
                    StartupTrace.section("startup.identityBind") {
                        IdentityBinder.bind(library,AppRuntime.repository(app))
                    }
                },
            )
        },"hgtv-library-preload").apply { isDaemon=true }.start()
    }

    /**
     * 用户已经明确选择设备形态。
     *
     * 首次弹窗出现时缓存 / 书库 / 运行时已经在后台预热；这里只把依赖形态的设备
     * 身份补绑上。整个过程不阻塞路由。
     */
    fun onDeviceFormSelected(context: Context) {
        val app=context.applicationContext
        if(!deviceFormSelected.compareAndSet(false,true)) return
        Thread({
            val library=AppRuntime.library(app)
            library.loadAsync(
                onFailure={ deviceFormSelected.set(false) },
                onReady={
                    IdentityBinder.bind(library,AppRuntime.repository(app))
                },
            )
        },"hgtv-device-form-ready").apply { isDaemon=true }.start()
    }

    fun initialize(
        context: Context,
        onReady: () -> Unit,
        onFailure: (Throwable) -> Unit,
    ): () -> Unit = start(context,onReady,onFailure,retry=false)

    fun retry(
        context: Context,
        onReady: () -> Unit,
        onFailure: (Throwable) -> Unit,
    ): () -> Unit = start(context,onReady,onFailure,retry=true)

    private fun start(
        context: Context,
        onReady: () -> Unit,
        onFailure: (Throwable) -> Unit,
        retry: Boolean,
    ): () -> Unit {
        val app=context.applicationContext
        val waiter=Waiter(onReady,onFailure)
        var startNow=false
        var readyNow=false
        var failedNow: Throwable?=null
        synchronized(lock) {
            if(retry && state==State.FAILED) {
                state=State.IDLE
                failure=null
            }
            when(state) {
                State.IDLE -> {
                    state=State.RUNNING
                    waiters += waiter
                    startNow=true
                }
                State.RUNNING -> waiters += waiter
                State.READY -> readyNow=true
                State.FAILED -> failedNow=failure ?: IllegalStateException("应用初始化失败")
            }
        }
        if(readyNow) runCatching { onReady() }
        if(failedNow!=null) runCatching { onFailure(failedNow) }
        if(startNow) {
            runCatching { prepare(app) }.onFailure { problem -> fail(problem) }
        }
        return {
            synchronized(lock) { waiters -= waiter }
        }
    }

    private fun prepare(context: Context) {
        val startedAt=StartupTrace.now()
        StartupTrace.mark("startup.prepare.start")
        StartupTrace.section("startup.runtime") { startRuntime(context) }
        val libraryStarted=StartupTrace.now()
        StartupTrace.mark("startup.library.load.start")
        AppRuntime.library(context).loadAsync(
            onFailure={ problem ->
                StartupTrace.done(
                    "startup.library.load.failed",
                    libraryStarted,
                    "error=${problem.javaClass.simpleName}:${problem.message.orEmpty().take(120)}",
                )
                fail(problem)
            },
            onReady={
                StartupTrace.done("startup.library.load",libraryStarted)
                val library=AppRuntime.library(context)
                StartupTrace.section("startup.identityBind") {
                    IdentityBinder.bind(library,AppRuntime.repository(context))
                }
                if(DeviceForm.ofStored(library.deviceForm)!=null) {
                    onDeviceFormSelected(context)
                }
                StartupTrace.done("startup.prepare",startedAt)
                complete()
            },
        )
    }

    private fun startRuntime(context: Context) {
        if(!runtimeStarted.compareAndSet(false,true)) return
        startCaches(context)
        initSessionStore(context)
        StartupDeferredTasks.schedule("startup.sessionKeeper",20_000L) {
            startSessionKeeper()
        }
        StartupDeferredTasks.schedule("startup.recommendStat",25_000L) {
            runCatching {
                AppRuntime.repository(context).refreshRecommendStatConfigAfterFirstFrame()
            }
        }
    }

    private fun complete() {
        val ready=synchronized(lock) {
            if(state!=State.RUNNING) return
            state=State.READY
            failure=null
            waiters.toList().also { waiters.clear() }
        }
        ready.forEach { waiter -> runCatching { waiter.onReady() } }
    }

    private fun fail(problem: Throwable) {
        val failed=synchronized(lock) {
            if(state!=State.RUNNING) return
            state=State.FAILED
            failure=problem
            waiters.toList().also { waiters.clear() }
        }
        failed.forEach { waiter -> runCatching { waiter.onFailure(problem) } }
    }

    private fun startCaches(context: Context) {
        if(!cacheStarted.compareAndSet(false,true)) return
        StartupTrace.section("startup.cacheInit") {
            runCatching {
            val cache=context.cacheDir
            com.hgtv.star.core.TncConfigStore.init(File(cache,"tnc-v1.json"))
            com.hgtv.star.core.HgtvDns.init(File(cache,"dns-v1.txt"))
            com.hgtv.star.core.PlaybackCache.init(File(cache,"playback-v1"))
            com.hgtv.star.core.RecommendCache.init(File(cache,"feed-v1.json"))
            com.hgtv.star.core.DetailCache.init(File(cache,"detail-v1.json"))
            com.hgtv.star.core.CategoryPanelCache.init(File(cache,"category-panels-v1.json"))
            com.hgtv.star.core.CatalogPageCache.init(File(cache,"catalog-pages-v1.json"))
            }.onFailure { problem -> Diagnostics.warn("HGTV-CACHE","缓存目录初始化失败",problem) }
        }
        // TNC 使用落盘快照即可完成首屏；条件刷新放到首帧之后。
        StartupDeferredTasks.schedule("startup.tncRefresh",20_000L) {
            runCatching { AppRuntime.repository(context).refreshTncAsync(force=true) }
        }
    }

    private fun initSessionStore(context: Context) {
        StartupTrace.section("startup.sessionStore") {
            runCatching {
                SessionBootstrap.initStore(
                    context=context,
                    store=com.hgtv.star.shared.data.AppSessionStore(context),
                    log={ line -> Diagnostics.trace("HGTV-SESSION",line) },
                    onExpired={ reason ->
                        com.hgtv.star.shared.data.SessionEvents.sendExpired(context,reason)
                    },
                )
            }.onFailure { problem -> Diagnostics.warn("HGTV-SESSION","会话保活初始化失败",problem) }
        }
    }

    private fun startSessionKeeper() {
        StartupTrace.section("startup.sessionKeeper") {
            runCatching { SessionBootstrap.startKeeper() }
                .onFailure { problem -> Diagnostics.warn("HGTV-SESSION","会话心跳初始化失败",problem) }
        }
    }

    private fun configureNetLogging() {
        // 调试包才记录每个请求的 DNS / TCP / TLS 分解与 API 现场。
        com.hgtv.star.core.NetLog.timing=BuildConfig.DEBUG
        com.hgtv.star.core.NetLog.apiLogging=BuildConfig.DEBUG
        if(!BuildConfig.DEBUG) return
        com.hgtv.star.core.NetLog.log={ line -> Diagnostics.trace("HGTV-NET",line) }
        com.hgtv.star.core.NetLog.event={ level,line ->
            when(level) {
                com.hgtv.star.core.NetLogLevel.ERROR -> Diagnostics.error("HGTV-NET",line)
                com.hgtv.star.core.NetLogLevel.WARN -> Diagnostics.warn("HGTV-NET",line)
                com.hgtv.star.core.NetLogLevel.DEBUG -> Diagnostics.trace("HGTV-NET",line)
            }
        }
    }

    /** 初始化 U-App 统计与 U-APM；失败不能影响应用启动。 */
    private fun initUmeng(context: Context) {
        runCatching {
            UMConfigure.setLogEnabled(BuildConfig.DEBUG)
            UMConfigure.preInit(context,UMENG_APP_KEY,UMENG_CHANNEL)
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
                context,
                UMENG_APP_KEY,
                UMENG_CHANNEL,
                UMConfigure.DEVICE_TYPE_PHONE,
                "",
            )
        }.onFailure { problem ->
            Diagnostics.warn("HGTV-UMENG","友盟初始化失败",problem)
        }
    }

    private const val UMENG_APP_KEY="6aeaba5b863270068cb58786"
    private const val UMENG_CHANNEL="HGTV"
}
