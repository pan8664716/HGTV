// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.content.Context
import com.hgtv.star.core.AccountData
import com.hgtv.star.core.AccountFavorite
import com.hgtv.star.core.AccountHistory
import com.hgtv.star.core.HistoryReconcile
import com.hgtv.star.core.Series
import com.hgtv.star.core.WatchSync
import com.hgtv.star.core.BackupProgress
import com.hgtv.star.shared.data.Library
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 手机端的账号侧写入（收藏 / 观看进度 / 删除历史 / 历史对账）。
 *
 * ## 与电视端的关系
 *
 * **判断规则全部来自 `:core`**（[HistoryReconcile] / [WatchSync]），因此两端对「哪一份更超前」
 * 得出一致结论；这里只负责手机上的三件事：什么时候发请求、把结果写回本机缓存、
 * 失败时怎么提示。与 TV 的 `MainActivityAccountSync` 一一对应：
 *
 * | 动作 | TV | 手机 |
 * | --- | --- | --- |
 * | 收藏 / 取消收藏 | `syncFavorite` | [favorite] |
 * | 上传进度 | `syncProgress` | [progress] |
 * | 删除云端历史 | `syncHistoryDelete` | [historyDeleted] |
 * | 历史对账 | `reconcileHistory` | [reconcile] |
 *
 * ## 为什么要做
 *
 * 手机端此前只写本机：用户在手机上登录、看了十集、又收藏了两部，云端一条都没变。
 * 换到电视上接着看，看到的还是登录那一刻的旧进度——「两个历史各说各的」。
 *
 * ## 没登录时
 *
 * 一律**静默跳过网络**，本机记录照常写。收藏那一条会给一句提示（用户在界面上点了收藏、
 * 界面也变了，不说明的话他会以为已经同步到云端）；进度与删除历史不提示——
 * 进度每 5 秒一次，弹提示会把画面刷满。
 */
object PhoneAccountSync {

    /** 去重：同一部剧、同一个时间戳的进度只推一次（账号数据每次进「我的」都会刷新一遍）。 */
    private val pushed = HashMap<String, Long>()
    private const val PUSHED_LIMIT = 64

    /** 账号数据的进程内那一份（与电视的 `accountData` 同角色）。 */
    @Volatile
    var data: AccountData? = null
        private set

    /** 收藏 / 取消收藏。 */
    fun favorite(context: Context, series: Series, added: Boolean, onNeedSignIn: () -> Unit = {}) {
        val repo = PhoneServices.repository(context)
        applyFavorite(context, series, added)
        if (!repo.signedIn) { onNeedSignIn(); return }
        io(context).launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    if (added) repo.accountAddFavorite(series.id) else repo.accountRemoveFavorite(series.id)
                }.getOrDefault(false)
            }
            if (!ok) android.util.Log.i("HGTV-PHONE", "favorite sync skipped id=${series.id} added=$added")
        }
    }

    /**
     * 上传一条观看进度。
     *
     * [episodeCount] 只用于云端「全 N 集」的展示，取不到时传 0。
     */
    fun progress(context: Context, progress: BackupProgress, episodeCount: Int = 0) {
        val repo = PhoneServices.repository(context)
        applyHistory(context, progress, episodeCount)
        if (!repo.signedIn) return
        io(context).launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    repo.accountUploadProgress(
                        progress.series.id, progress.episodeId, progress.episodeIndex,
                        progress.position, progress.duration, episodeCount,
                    )
                }
            }
        }
    }

    /** 删除云端观看历史（平台的软删）。 */
    fun historyDeleted(context: Context, series: Series) {
        val repo = PhoneServices.repository(context)
        applyHistoryDeleted(context, series)
        if (!repo.signedIn) return
        io(context).launch {
            withContext(Dispatchers.IO) {
                runCatching { repo.accountDeleteHistory(series.id) }
            }
        }
    }

    /**
     * 拉一次账号数据并对账。
     *
     * 对账本身是 `:core` 的 [HistoryReconcile]：账号更超前的写回本机进度，
     * 本机更超前的改账号那一行并推回云端。两端结论一致。
     */
    suspend fun refresh(context: Context): AccountData? {
        val repo = PhoneServices.repository(context)
        val lib = PhoneServices.library(context)
        if (!repo.signedIn) return null
        val fresh = withContext(Dispatchers.IO) { runCatching { repo.accountData() }.getOrNull() } ?: return null
        val merged = com.hgtv.star.core.AccountSnapshot.merge(data, fresh).let { snapshot ->
            // 本机删过的那几条不再进缓存：平台对短剧进度列表的软删不一定生效。
            val kept = snapshot.history.filterNot { lib.historyDeleted(it.series.id) }
            if (kept.size == snapshot.history.size) snapshot else snapshot.copy(history = kept)
        }
        val outcome = HistoryReconcile.run(merged, lib.history(), System.currentTimeMillis())
        outcome.writes.forEach { runCatching { lib.save(it) } }
        store(context, outcome.data)
        pushAhead(context, outcome.uploads)
        return outcome.data
    }

    /** 账号数据换掉，同时落盘（本机改动也是「账号侧的真实状态」，重启后不该回滚）。 */
    fun store(context: Context, value: AccountData?) {
        value ?: return
        data = value
        runCatching { PhoneServices.library(context).cacheAccount(value) }
    }

    /** 退出登录：内存那一份跟着清掉，否则「我的」页还会铺出上一个账号的收藏。 */
    fun clear() {
        data = null
        pushed.clear()
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 把「本机更超前」的那几条推回云端。
     *
     * 只在**本机确实更超前**时调用（见 [HistoryReconcile.Result.uploads]）。按剧去重、
     * 同一时间戳只推一次：账号数据每次进「我的」页都会刷新一遍，不记一下就会反复上传同一份进度。
     */
    private fun pushAhead(context: Context, rows: List<BackupProgress>) {
        if (rows.isEmpty()) return
        val repo = PhoneServices.repository(context)
        if (!repo.signedIn) return
        io(context).launch {
            rows.forEach { progress ->
                val key = progress.series.id
                if (pushed[key] == progress.updatedAt) return@forEach
                if (pushed.size >= PUSHED_LIMIT) pushed.clear()
                pushed[key] = progress.updatedAt
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        repo.accountUploadProgress(
                            key, progress.episodeId, progress.episodeIndex,
                            progress.position, progress.duration, progress.series.episodeCount,
                        )
                    }.getOrDefault(false)
                }
                // 失败就忘掉这次去重标记：下次刷新还有机会补上。
                if (!ok && pushed[key] == progress.updatedAt) pushed.remove(key)
            }
        }
    }

    /** 收藏改到账号缓存上（加进来的那条用本机的 [Series]，封面简介都齐）。 */
    private fun applyFavorite(context: Context, series: Series, added: Boolean) {
        val current = data ?: return
        val rows = current.favorites
        val next = if (added) {
            if (rows.any { it.series.id == series.id }) rows
            else listOf(AccountFavorite(series, System.currentTimeMillis(), 0)) + rows
        } else {
            rows.filterNot { it.series.id == series.id }
        }
        if (next === rows) return
        store(context, current.copy(favorites = next))
    }

    /** 进度改到账号缓存上（本机刚看的那一集，云端那一行也要跟着走）。 */
    private fun applyHistory(context: Context, progress: BackupProgress, episodeCount: Int) {
        val current = data ?: return
        val row = WatchSync.toAccount(progress, current.history.firstOrNull { it.series.id == progress.series.id })
            .let { if (it.episodes > 0) it else it.copy(episodes = episodeCount) }
        val rows = (listOf(row) + current.history.filterNot { it.series.id == progress.series.id })
            .sortedByDescending { WatchSync.millis(it.updatedAt) }
        store(context, current.copy(history = rows))
    }

    private fun applyHistoryDeleted(context: Context, series: Series) {
        val current = data ?: return
        val rows = current.history.filterNot { it.series.id == series.id }
        if (rows.size == current.history.size) return
        store(context, current.copy(history = rows))
    }

    /**
     * 进程级工作作用域：这些请求都是「发出去就不管」，不该挂在某个页面的生命周期上
     * （用户点完收藏就退出页面，请求仍然要发出去）。作用域本身不占线程。
     */
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun io(@Suppress("UNUSED_PARAMETER") context: Context): CoroutineScope = worker
}
