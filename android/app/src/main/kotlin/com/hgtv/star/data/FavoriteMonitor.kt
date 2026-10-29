// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.data

import android.os.Handler
import android.os.Looper
import com.hgtv.star.core.ContentRepository
import com.hgtv.star.core.RequestScope
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Serial foreground checks; cancellation owns only calls made by this monitor. */
class FavoriteMonitor(private val library: Library, source: ContentRepository, private val changed: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var epoch = 0
    private var job: Future<*>? = null
    private var scope: RequestScope? = null
    private var destroyed = false
    /**
     * 派生 client 与仓库都**用到时才建**。
     *
     * 本类在 `MainActivity.onCreate` 里构造（主线程），而 `source.http` 第一次取值会建
     * 业务 client——那要解析系统根证书表（首次 2~3 秒，见 `HgtvTls.ACCEPTED_ISSUERS`）。
     * 在构造期取它，等于把冷启动最贵的那一刀又搬回主线程。
     */
    private val http by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        source.http.newBuilder().callTimeout(12, TimeUnit.SECONDS).build()
    }
    private val repository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { ContentRepository(http) }
    var running = false; private set
    var status = "进入收藏可手动检查更新"; private set
    var lastChangedId: String? = null; private set

    fun check(force: Boolean = false) {
        if (running || destroyed || !library.isLoaded) return
        val now = System.currentTimeMillis()
        val favorites = library.favorites()
        val items = favorites.filter { force || library.favoriteUpdate(it.id)?.let { now - it.checkedAt !in 0 until 3_600_000L } != false }
        lastChangedId = null
        if (items.isEmpty()) {
            status = if (favorites.isEmpty()) "收藏后可检查新集" else "最近一小时已检查，可手动刷新"
            changed(); return
        }
        val ticket = ++epoch
        val owned = RequestScope()
        scope = owned
        running = true
        var completed = 0; var failures = 0; var consecutiveFailures = 0
        fun next() {
            if (ticket != epoch) return
            if (completed == items.size || consecutiveFailures >= 3) {
                running = false
                status = "已检查 $completed/${items.size} 部" + (if (failures > 0) " · $failures 部失败，稍后可重试" else " · 检查完成")
                changed(); return
            }
            val item = items[completed]
            val started = System.currentTimeMillis()
            status = "检查更新 ${completed + 1}/${items.size}…"
            changed()
            lastChangedId = null
            job = worker.submit {
                if (ticket != epoch) return@submit
                // 未上架的剧没有分集：把它当成「暂无更新」，不要用 0 覆盖掉已知的集数。
                val result = runCatching { owned.run { repository.detail(item.id).episodes.size } }
                    .map { count -> if (count <= 0) error("尚未上线") else count }
                main.post {
                    if (ticket != epoch) return@post
                    job = null
                    result.onSuccess { count ->
                        library.observeFavorite(item.id, count, started)
                        lastChangedId = item.id
                        consecutiveFailures = 0
                    }.onFailure { failures++; consecutiveFailures++ }
                    completed++
                    next()
                }
            }
        }
        next()
    }
    fun stop() {
        epoch++
        if (running) status = "更新检查已暂停，返回目录后继续"
        running = false
        scope?.cancel(); scope = null
        job?.cancel(true); job = null
    }
    fun destroy() { destroyed = true; stop(); worker.shutdownNow() }
}
