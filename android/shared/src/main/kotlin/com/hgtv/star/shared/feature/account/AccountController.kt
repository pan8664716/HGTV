// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.feature.account

import com.hgtv.star.core.AccountData
import com.hgtv.star.core.AccountFavorite
import com.hgtv.star.core.AccountReserve
import com.hgtv.star.core.AccountSnapshot
import com.hgtv.star.core.BackupProgress
import com.hgtv.star.core.ContentRepository
import com.hgtv.star.core.Series
import com.hgtv.star.core.WatchSync
import com.hgtv.star.core.accountAddFavorite
import com.hgtv.star.core.accountClearHistory
import com.hgtv.star.core.accountData
import com.hgtv.star.core.accountDeleteHistories
import com.hgtv.star.core.accountHistory
import com.hgtv.star.core.accountRemoveFavorite
import com.hgtv.star.core.accountReserve
import com.hgtv.star.core.accountUploadProgress
import com.hgtv.star.core.isFinished
import com.hgtv.star.shared.data.Library
import com.hgtv.star.shared.data.accountCache
import com.hgtv.star.shared.data.cacheAccount
import com.hgtv.star.shared.data.clearAccountCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 账号侧的唯一状态与写入入口。
 *
 * 页面不再各自维护 `accountData`，也不各自拼「本机先改、云端后推、失败回滚」。
 * 这里只处理账号数据与网络副作用，不持有 Activity / View，也不直接做页面刷新。
 */
class AccountController(
    private val repository: ContentRepository,
    private val library: Library,
    private val worker: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val _state = MutableStateFlow<AccountData?>(null)
    val state: StateFlow<AccountData?> = _state.asStateFlow()

    val data: AccountData? get() = _state.value

    data class RefreshResult(
        val data: AccountData?,
        val changed: Set<String>,
        val localWrites: Int,
        val failedColumns: Set<String>,
        val error: Throwable? = null,
    )

    data class ClearHistoryResult(
        val localIds: List<String>,
        val knownIds: List<String>,
        val remoteRequested: Boolean,
    )

    data class HistoryPageResult(
        val page: com.hgtv.star.core.AccountHistoryPage?,
        val data: AccountData?,
        val error: Throwable? = null,
    )

    /**
     * 身份变化时调用：登录账号先用账号缓存顶上，并把它镜像成本机缓存。
     *
     * 登录后账号是唯一真源，缓存也是上一次远端同步的产物；没有缓存时清空本机
     * 收藏与历史，避免把登录前的本地记录当成账号记录显示或补传。
     */
    fun bindCredentials(): AccountData? {
        val cached = library.accountCache()?.data
        val next = cached?.let { value ->
            if (library.isLoaded && repository.signedIn) {
                mirrorAccountData(value, AccountSnapshot.KEYS, emptySet())
            }
            value
        }
        _state.value = next
        if (library.isLoaded && repository.signedIn && cached == null) {
            library.replaceFavorites(emptyList())
            library.replaceHistory(emptyList())
        }
        if (repository.credentials == null && !library.deviceRegistered()) {
            library.clearAccountCache()
        }
        return next
    }

    /**
     * 页面回到前台时使用：已有内存账号数据就保留，只有控制器还没初始化时才读缓存。
     *
     * 电视模式与手机模式共用同一个控制器。手机页若每次都走 [bindCredentials]，会用磁盘
     * 快照覆盖电视模式刚拉到的最新历史；快照尚未落盘时更会被直接清空，最后只剩本机记录。
     */
    fun ensureBoundCredentials(): AccountData? = data ?: bindCredentials()

    /**
     * 拉一次账号数据，并以远端结果覆盖本地账号缓存。
     *
     * 只有请求成功的栏目才覆盖本机缓存；失败栏目保留上一份，避免网络抖动清空界面。
     * 页面只需根据
     * [RefreshResult.changed] / [RefreshResult.localWrites] 重画对应区域。
     */
    suspend fun refresh(
        columns: Set<String> = AccountSnapshot.KEYS,
    ): RefreshResult {
        val fresh = withContext(Dispatchers.IO) {
            runCatching { repository.accountData(columns) }
        }.getOrElse { problem ->
            return RefreshResult(data, emptySet(), 0, emptySet(), problem)
        }
        val before = data
        val partial = AccountSnapshot.merge(before, fresh).let { snapshot ->
            if(before == null || columns == AccountSnapshot.KEYS) snapshot
            else snapshot.copy(
                profile = if("资料" in columns) snapshot.profile else before.profile,
                favorites = if("收藏" in columns) snapshot.favorites else before.favorites,
                history = if("历史" in columns) snapshot.history else before.history,
                likes = if("点赞" in columns) snapshot.likes else before.likes,
                reserves = if("预约" in columns) snapshot.reserves else before.reserves,
            )
        }
        val localWrites = mirrorAccountData(partial, columns, fresh.errors.keys)
        val changed = AccountSnapshot.changedKeys(before, partial)
        storeRaw(partial)
        return RefreshResult(
            data = partial,
            changed = changed,
            localWrites = localWrites,
            failedColumns = fresh.errors.keys,
        )
    }

    /**
     * 刷新账号历史。首屏一次取远端全量，后续页从已同步的本机缓存切片。
     *
     * 账号历史接口本身支持 `limit=0` 全量读取；首屏必须走全量，才能把远端已经删除、
     * 但本机还留着的旧记录一起清掉。分页只是界面展示，不再决定本地缓存保留哪些条目。
     */
    suspend fun refreshHistory(offset: Int, limit: Int): HistoryPageResult {
        if (!repository.signedIn) {
            return HistoryPageResult(null, data, com.hgtv.star.core.SignInRequiredException())
        }
        val page = withContext(Dispatchers.IO) {
            runCatching {
                if (offset == 0) {
                    val rows = repository.accountHistory()
                    com.hgtv.star.core.AccountHistoryPage(
                        rows = rows,
                        hasMore = false,
                        nextOffset = rows.size,
                    )
                } else {
                    val rows = data?.history.orEmpty()
                    com.hgtv.star.core.AccountHistoryPage(
                        rows = rows.drop(offset).take(limit),
                        hasMore = offset + limit < rows.size,
                        nextOffset = minOf(rows.size, offset + limit),
                    )
                }
            }
        }.getOrElse { problem ->
            return HistoryPageResult(null, data, problem)
        }
        val before = data
        if (offset != 0) {
            return HistoryPageResult(page, before, null)
        }
        val next = (before ?: AccountData(null, emptyList(), emptyList(), emptyList(), emptyList()))
            .copy(history = page.rows)
        mirrorAccountData(next, setOf("历史"), emptySet())
        storeRaw(next)
        return HistoryPageResult(page, next, null)
    }

    /** 收藏 / 取消收藏：本机账号缓存先改，云端随后推。 */
    fun favorite(
        series: Series,
        added: Boolean,
        onNeedSignIn: () -> Unit = {},
        onSyncError: () -> Unit = {},
    ) {
        applyFavorite(series, added)
        if (!repository.signedIn) {
            onNeedSignIn()
            return
        }
        worker.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    if (added) repository.accountAddFavorite(series.id)
                    else repository.accountRemoveFavorite(series.id)
                }.getOrDefault(false)
            }
            if (!ok) onSyncError()
        }
    }

    /** 批量取消收藏。 */
    fun favoritesDeleted(series: List<Series>) {
        val rows = series.distinctBy { it.id }
        if (rows.isEmpty()) return
        applyFavoritesDeleted(rows.mapTo(HashSet()) { it.id })
        if (!repository.signedIn) return
        worker.launch {
            withContext(Dispatchers.IO) {
                rows.forEach { runCatching { repository.accountRemoveFavorite(it.id) } }
            }
        }
    }

    /** 上传一条观看进度。 */
    fun progress(progress: BackupProgress, episodeCount: Int = 0) {
        applyHistory(progress, episodeCount)
        worker.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    repository.accountUploadProgress(
                        progress.series.id,
                        progress.episodeId,
                        progress.episodeIndex,
                        progress.position,
                        progress.duration,
                        episodeCount,
                    )
                }
            }
        }
    }

    /** 删除一条或多条云端观看历史。 */
    fun historyDeleted(seriesIds: Collection<String>) {
        val ids = seriesIds.distinct()
        if (ids.isEmpty()) return
        applyHistoryDeleted(ids.toSet())
        worker.launch {
            withContext(Dispatchers.IO) {
                runCatching { repository.accountDeleteHistories(ids) }
            }
        }
    }

    fun historyDeleted(series: Series) = historyDeleted(listOf(series.id))

    /** 预约 / 取消预约。 */
    fun reserve(
        series: Series,
        reserved: Boolean,
        onNeedSignIn: () -> Unit = {},
        onDone: (Boolean) -> Unit = {},
    ) {
        // **先落本机**：预约是一份「开播提醒我」的清单，没登录也该记得住。
        // 未登录时只写本机、不发那条注定失败的写请求（`onNeedSignIn` 只用来提示一句）；
        // 登录之后刷新账号数据时以远端为准（见 [refresh] 的整栏覆盖）。
        applyReserve(series, reserved)
        if (!repository.signedIn) {
            onNeedSignIn()
            onDone(true)
            return
        }
        worker.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { repository.accountReserve(series.id, reserved) }.getOrDefault(false)
            }
            if (!ok) applyReserve(series, !reserved)
            onDone(ok)
        }
    }

    /** 批量取消预约。 */
    fun reservationsDeleted(seriesIds: Collection<String>) {
        val ids = seriesIds.distinct()
        if (ids.isEmpty()) return
        applyReservationsDeleted(ids.toSet())
        if (!repository.signedIn) return
        worker.launch {
            withContext(Dispatchers.IO) {
                ids.forEach { runCatching { repository.accountReserve(it, false) } }
            }
        }
    }

    /** 批量移除点赞记录。平台没有公开取消点赞写接口，本次缓存先移除，刷新后以远端为准。 */
    fun likesDeleted(seriesIds: Collection<String>) {
        val ids = seriesIds.distinct()
        if (ids.isEmpty()) return
        applyLikesDeleted(ids.toSet())
    }

    /** 是否还有可清空的观看记录（内存、本机、磁盘快照三处都算）。 */
    fun hasHistoryRecords(): Boolean {
        if (library.history().isNotEmpty()) return true
        val memory = data?.history.orEmpty()
        val cached = library.accountCache()?.data?.history.orEmpty()
        return memory.isNotEmpty() || cached.isNotEmpty()
    }

    /**
     * 清空本机、账号缓存与云端观看记录。
     *
     * 返回值供页面提示与刷新；云端请求仍在后台尽力完成。
     */
    fun clearAllHistory(onRemoteResult: (Boolean) -> Unit = {}): ClearHistoryResult {
        val accountIds = data?.history?.map { it.series.id }.orEmpty()
        val cachedIds = library.accountCache()?.data?.history?.map { it.series.id }.orEmpty()
        val knownIds = (accountIds + cachedIds).distinct()
        val localIds = library.clearHistory()
        if (knownIds.isNotEmpty()) library.markHistoryDeleted(knownIds)

        val current = data
        if (current != null && current.history.isNotEmpty()) {
            storeRaw(current.copy(history = emptyList()))
        } else {
            library.accountCache()?.data?.let { cached ->
                if (cached.history.isNotEmpty()) library.cacheAccount(cached.copy(history = emptyList()))
            }
        }
        val ids = (localIds + knownIds).distinct()
        val networkEligible = repository.signedIn || library.deviceRegistered()
        if (networkEligible) {
            worker.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { repository.accountClearHistory(ids) }
                }.getOrDefault(false)
                onRemoteResult(ok)
            }
        }
        return ClearHistoryResult(localIds, knownIds, networkEligible)
    }

    /** 本机改动就地写回账号缓存。 */
    fun store(value: AccountData?) {
        value ?: return
        storeRaw(value)
    }

    /** 页面从缓存恢复时使用：只更新内存状态，不重复写缓存。 */
    fun replace(value: AccountData?) {
        if (value == null) {
            clear()
            return
        }
        _state.value = value
    }

    /** 退出登录 / 会话失效：内存账号数据清掉。 */
    fun clear() {
        _state.value = null
    }

    private fun storeRaw(value: AccountData) {
        _state.value = value
        runCatching { library.cacheAccount(value) }
    }

    /**
     * 把账号侧成功刷新的栏目镜像到本机书库缓存。
     *
     * 收藏与历史都是**整表覆盖**：远端没有的条目本地也删除，登录前的本地记录不会再
     * 被补传或留在列表中。只有请求成功的栏目才动，失败栏目继续保留上一份缓存。
     */
    private fun mirrorAccountData(
        value: AccountData,
        columns: Set<String>,
        failedColumns: Set<String>,
    ): Int {
        if (!repository.signedIn || !library.isLoaded) return 0
        var writes = 0
        if ("收藏" in columns && "收藏" !in failedColumns) {
            if (library.replaceFavorites(value.favorites.map { it.series })) writes++
        }
        if ("历史" in columns && "历史" !in failedColumns) {
            val existing = library.history().associateBy { it.series.id }
            val now = System.currentTimeMillis()
            val local = value.history.map { row ->
                val previous = existing[row.series.id]
                val sameEpisode = previous != null &&
                    previous.episodeId == row.episodeId &&
                    previous.episodeIndex == row.episodeIndex
                BackupProgress(
                    series = row.series,
                    episodeId = row.episodeId,
                    episodeIndex = row.episodeIndex,
                    position = row.position.coerceAtLeast(0),
                    duration = if (sameEpisode) previous?.duration?.coerceAtLeast(0) ?: 0 else 0,
                    completed = row.isFinished(),
                    updatedAt = WatchSync.at(row.updatedAt, now),
                )
            }
            if (library.replaceHistory(local)) writes++
        }
        return writes
    }

    private fun applyFavorite(series: Series, added: Boolean) {
        val current = data ?: return
        val rows = current.favorites
        val next = if (added) {
            if (rows.any { it.series.id == series.id }) rows
            else listOf(AccountFavorite(series, System.currentTimeMillis(), 0)) + rows
        } else {
            rows.filterNot { it.series.id == series.id }
        }
        if (next === rows) return
        storeRaw(current.copy(favorites = next))
    }

    private fun applyFavoritesDeleted(ids: Set<String>) {
        val current = data ?: return
        val next = current.favorites.filterNot { it.series.id in ids }
        if (next.size == current.favorites.size) return
        storeRaw(current.copy(favorites = next))
    }

    private fun applyHistory(progress: BackupProgress, episodeCount: Int) {
        val current = data ?: return
        val row = WatchSync.toAccount(
            progress,
            current.history.firstOrNull { it.series.id == progress.series.id },
        ).let { if (it.episodes > 0) it else it.copy(episodes = episodeCount) }
        val rows = (listOf(row) + current.history.filterNot { it.series.id == progress.series.id })
            .sortedByDescending { WatchSync.millis(it.updatedAt) }
        storeRaw(current.copy(history = rows))
    }

    private fun applyHistoryDeleted(ids: Set<String>) {
        val current = data ?: return
        val rows = current.history.filterNot { it.series.id in ids }
        if (rows.size == current.history.size) return
        storeRaw(current.copy(history = rows))
    }

    private fun applyReserve(series: Series, reserved: Boolean) {
        val current = data ?: return
        val rows = current.reserves
        val next = if (reserved) {
            if (rows.any { it.series.id == series.id }) rows
            else listOf(AccountReserve(series.copy(upcoming = true), System.currentTimeMillis(), false)) + rows
        } else {
            rows.filterNot { it.series.id == series.id }
        }
        if (next === rows) return
        storeRaw(current.copy(reserves = next))
    }

    private fun applyReservationsDeleted(ids: Set<String>) {
        val current = data ?: return
        val rows = current.reserves.filterNot { it.series.id in ids }
        if (rows.size == current.reserves.size) return
        storeRaw(current.copy(reserves = rows))
    }

    private fun applyLikesDeleted(ids: Set<String>) {
        val current = data ?: return
        val rows = current.likes.filterNot { it.series.id in ids }
        if (rows.size == current.likes.size) return
        storeRaw(current.copy(likes = rows))
    }

}
