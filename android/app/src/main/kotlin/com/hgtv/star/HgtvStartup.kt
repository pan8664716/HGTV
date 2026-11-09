// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import android.content.Context
import com.hgtv.star.shared.data.AppRuntime
import com.hgtv.star.shared.data.PlaybackDeviceBackups
import com.hgtv.star.shared.data.SessionBootstrap
import com.hgtv.star.shared.debug.Diagnostics
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 启动页负责的本地初始化。
 *
 * 页面只把“可以开始首屏”的判据放在本机书库加载成功；TLS、DNS、TNC、签名与会话
 * 预热继续在后台并行，不把网络波动变成无法进入应用的阻塞条件。初始化失败时由
 * 启动页显示重试，不再先进入主界面再补一个全屏加载遮罩。
 */
internal object HgtvStartup {
    private enum class State { IDLE, RUNNING, READY, FAILED }

    private val lock=Any()
    private val runtimeStarted=AtomicBoolean(false)
    private var state=State.IDLE
    private var failure: Throwable?=null
    private val waiters=mutableListOf<Waiter>()

    private class Waiter(
        val onReady: () -> Unit,
        val onFailure: (Throwable) -> Unit,
    )

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
        startRuntime(context)
        AppRuntime.library(context).loadAsync(
            onFailure={ problem -> fail(problem) },
            onReady={
                // 书库已就绪后再启动备用设备线程，避免两条启动链路同时调用 loadAsync。
                PlaybackDeviceBackups.start(context)
                complete()
            },
        )
    }

    private fun startRuntime(context: Context) {
        if(!runtimeStarted.compareAndSet(false,true)) return
        startCaches(context)
        startTlsWarmup()
        startDnsWarmup()
        startTncBootstrap(context)
        startSignWarmup(context)
        initSession(context)
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
        runCatching {
            val cache=context.cacheDir
            com.hgtv.star.core.TncConfigStore.init(File(cache,"tnc-v1.json"))
            com.hgtv.star.core.HgtvDns.init(File(cache,"dns-v1.txt"))
            com.hgtv.star.core.PlaybackCache.init(File(cache,"playback-v1"))
            com.hgtv.star.core.RecommendCache.init(File(cache,"feed-v1.json"))
            com.hgtv.star.core.DetailCache.init(File(cache,"detail-v1.json"))
            com.hgtv.star.core.CategoryPanelCache.init(File(cache,"category-panels-v1.json"))
        }.onFailure { problem -> Diagnostics.warn("HGTV-CACHE","缓存目录初始化失败",problem) }
    }

    private fun startTlsWarmup() {
        Thread({
            runCatching { com.hgtv.star.core.HgtvTls.warm() }
                .onFailure { problem -> Diagnostics.warn("HGTV-TLS","TLS 预热失败",problem) }
        }, "hgtv-tls-warm").start()
    }

    private fun startDnsWarmup() {
        Thread({
            runCatching { com.hgtv.star.core.HgtvDns.warm(com.hgtv.star.core.HgtvDns.BOOT_HOSTS) }
        }, "hgtv-dns-warm").start()
    }

    private fun startTncBootstrap(context: Context) {
        Thread({
            runCatching { AppRuntime.repository(context).refreshTncAsync() }
        }, "hgtv-tnc-bootstrap").start()
    }

    private fun startSignWarmup(context: Context) {
        Thread({
            runCatching { AppRuntime.warmSignature(context) }
        }, "hgtv-sign-warm").start()
    }

    private fun initSession(context: Context) {
        runCatching {
            SessionBootstrap.init(
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
