// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data

import android.content.Context
import com.hgtv.star.core.ContentRepository
import com.hgtv.star.shared.compat.Cancellable
import com.hgtv.star.shared.compat.NetworkWatcher
import com.hgtv.star.shared.debug.Diagnostics
import java.util.concurrent.TimeUnit

/**
 * TV 与手机共用的进程级运行时。
 *
 * 这里只放「整个进程应该只有一份」的数据对象：
 *
 * * [ContentRepository] 带连接池、设备身份与接口内存缓存；
 * * [Library] 带本机数据写队列；
 * * [ArtworkLoader] 带封面内存缓存与下载队列。
 *
 * Activity / Fragment 可以反复创建销毁，但不能关闭这些实例。页面离开时只保存进度、
 * 冲刷待写数据和取消属于页面的任务；真正的收尾交给进程退出。
 */
object AppRuntime {
    private val lock = Any()

    @Volatile private var repositoryInstance: ContentRepository? = null
    @Volatile private var libraryInstance: Library? = null
    @Volatile private var artworkInstance: ArtworkLoader? = null
    @Volatile private var playbackRepositoryInstance: ContentRepository? = null
    @Volatile private var favoriteRepositoryInstance: ContentRepository? = null
    @Volatile private var tncNetworkWatch: Cancellable? = null
    @Volatile private var lastTncNetworkRefreshAt: Long = 0L

    /**
     * 业务请求共用的仓库。
     *
     * client 仍然是懒建的；这里只创建对象，不会在调用线程解析证书表或建立连接。
     */
    fun repository(context: Context): ContentRepository =
        repositoryInstance ?: synchronized(lock) {
            repositoryInstance ?: ContentRepository().apply {
                writeLog = { line -> Diagnostics.trace("HGTV-WRITE", line) }
                installDeviceHeal(context)
            }.also {
                repositoryInstance = it
                installTncNetworkWatch(context.applicationContext)
            }
        }

    /**
     * 把 `:core` 留的那个「设备身份自愈」钩子接到 [DeviceHealer]。
     *
     * 为什么在这里接：`ContentRepository` 建在 `core`（纯 Kotlin，没有 `Library`），
     * 而自愈要读写本机身份、走 `:shared` 的注册流程。**两端共用**这一处接线，
     * 手机端因此不需要另写一套——这正是它原先缺失的那一环。
     *
     * 只接一次（[ContentRepository.deviceHeal] 是进程级静态字段），预取 / 收藏那两个
     * 仓库实例调用时传的是它们自己，自愈之后各自的内存身份由 [DeviceHealer] 收尾。
     */
    private fun installDeviceHeal(context: Context) {
        val app = context.applicationContext
        ContentRepository.deviceHeal = { repository, reason ->
            DeviceHealer.heal(library(app), repository, reason)
        }
        // 一行启动读数：两端任何一端「搜索被拒却没人管」时，先看这行在不在——
        // 不在就是钩子没接上（`core` 侧会当成「修不了」直接降级官网）。
        android.util.Log.i("HGTV", "Device heal hook installed")
    }

    /**
     * 网络切换后后台刷新一次 TNC。
     *
     * 网络回调会连续收到 onAvailable / onCapabilitiesChanged，因此这里做 15 秒去抖；
     * TNC 配置本身未过期时，[ContentRepository.refreshTncAsync] 也不会重复请求。
     */
    private fun installTncNetworkWatch(context: Context) {
        if(tncNetworkWatch!=null) return
        synchronized(lock) {
            if(tncNetworkWatch!=null) return
            tncNetworkWatch=runCatching {
                NetworkWatcher.watch(context) {
                    val now=android.os.SystemClock.elapsedRealtime()
                    if(now-lastTncNetworkRefreshAt<15_000L) return@watch
                    lastTncNetworkRefreshAt=now
                    repositoryInstance?.refreshTncAsync(force=true)
                }
            }.getOrNull()
        }
    }

    fun library(context: Context): Library =
        libraryInstance ?: synchronized(lock) {
            libraryInstance ?: Library(context.applicationContext).also { libraryInstance = it }
        }

    fun artwork(context: Context): ArtworkLoader =
        artworkInstance ?: synchronized(lock) {
            artworkInstance ?: ArtworkLoader(context.applicationContext).also { artworkInstance = it }
        }

    /**
     * 播放地址预取 / 推荐预热共用的仓库。
     *
     * 它使用 [PlaybackTransport.client]：这些请求与随后真正的播放共用 HTTP/1.1 传输层，
     * 但仓库对象本身仍与业务仓库分开，避免把业务接口的内存缓存与播放模型混在一起。
     *
     * 仓库只建一次；每次取用时同步主仓库的账号、会话与设备身份，保证登录或设备注册
     * 发生变化后，预热不会继续带旧身份。
     */
    fun playbackRepository(context: Context): ContentRepository {
        val identity = repository(context)
        val target = playbackRepositoryInstance ?: synchronized(lock) {
            playbackRepositoryInstance ?: ContentRepository(PlaybackTransport.client).apply {
                writeLog = { line -> Diagnostics.trace("HGTV-WRITE", line) }
            }.also { playbackRepositoryInstance = it }
        }
        target.credentials = identity.credentials
        target.sessionToken = identity.sessionToken
        target.inheritDevice(identity)
        return target
    }

    /**
     * 收藏更新检查共用的短超时仓库。
     *
     * 检查是一条一条串行发的，某部剧卡住不该按普通请求的 45 秒一直占着；因此它保留
     * 12 秒整调用超时。client 仍只建一份，且**第一次取用会初始化业务 client 与证书表**，
     * 所以只能在后台线程调用（见 [FavoriteMonitor]）。
     */
    fun favoriteRepository(source: ContentRepository): ContentRepository {
        val target = favoriteRepositoryInstance ?: synchronized(lock) {
            favoriteRepositoryInstance ?: ContentRepository(
                source.http.newBuilder().callTimeout(12, TimeUnit.SECONDS).build()
            ).apply {
                writeLog = { line -> Diagnostics.trace("HGTV-WRITE", line) }
            }.also { favoriteRepositoryInstance = it }
        }
        target.credentials = source.credentials
        target.sessionToken = source.sessionToken
        target.inheritDevice(source)
        return target
    }

    /** 冷启动预热；由调用方在后台线程触发。 */
    fun warm(context: Context) {
        repository(context).preconnect()
    }

    /** 只做本地签名预热，不建 client、不发请求；由调用方在后台线程触发。 */
    fun warmSignature(context: Context) {
        repository(context).warmSignature()
    }
}
