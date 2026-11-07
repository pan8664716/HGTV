// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data
import com.hgtv.star.shared.debug.Diagnostics

import com.hgtv.star.core.HOME_KEYS
import com.hgtv.star.core.CategoryGenres

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hgtv.star.core.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// The backup and native library use the same immutable progress shape.
typealias WatchProgress = BackupProgress

/** Memory-only UI access; disk reads, JSON serialization and commits run on workers. */
class Library(context: Context) {
    private companion object {
        // A replacement Activity must not load before its predecessor's final checkpoint commits.
        val retiredWrites = mutableListOf<LatestWriteQueue<String, () -> Any?>>()
        /**
         * 静态列表只保留最近几次关闭的队列。
         * 它存在的意义只是「下一个 Activity 启动时等上一批提交落盘」，
         * 不设上限就会随 Activity 重建次数一直增长（每条队列还带着自己的线程与待写快照）。
         *
         * 每个 [Library] 关掉时交出 [QUEUES_PER_LIBRARY] 条队列，因此这个上限按
         * 「最近两个 Activity」来定；改队列数量时这里要跟着改，否则被挤掉的队列就没人等了。
         */
        const val QUEUES_PER_LIBRARY = 3
        const val RETIRED_LIMIT = 2 * QUEUES_PER_LIBRARY
        /**
         * 推荐页的曝光窗口容量 / 存活时长见 [com.hgtv.star.core.RecommendFlow]。
         *
         * 早先这里是一个 800 条的纯 LRU（官方客户端是 200 条，文档 §4.4 指出它就是
         * 「刷一会儿就开始重复」的头号原因）。现在换成「条数上限 + 时间窗」，
         * 上限 1200 条、7 天过期，并且**剧名也进窗口**（挡同剧不同切片 / 同 IP 多版本）。
         */
        /**
         * 平台侧的 `device_id` / `install_id` 形状：**不限位数**的十进制数字。
         *
         * 早先这里写的是 `[0-9]{15,19}`，而平台下发的是**分片计数器**：水位还没到 `1e14`
         * 的分片会给 **14 位**号（实测全新注册里约 6–8%，例如 `41154078326090`）。
         * 14 位被这条正则判死之后，`deviceIdentity()` 返回空表、面板显示「设备 未注册」、
         * 播放回落到内置那份被拉黑的身份（`110001`）；而重注册又被服务端按 `cdid` 去重、
         * 原样返回同一对 14 位号——于是**永久卡住**，只有清空缓存换一套 `cdid` 才能出来。
         * 位数本身不是任何判据，因此这里不再限制：只要是一串十进制数字就算合法。
         *
         * 全 `0` 必须排除：`device_id=0` 是服务端**拒绝**这次注册的标记（见 `DeviceRegister.parse`），
         * 把它当身份用等于拿一个必然失败的设备号去签名。
         */
        fun isDeviceNumber(value: String): Boolean =
            value.isNotEmpty() && value.all { it in '0'..'9' } && value.any { it != '0' }
        /**
         * 会镜像到**外部存储**的那几项：设备身份、设备档案、账号会话与搜索令牌。
         *
         * 它们合起来就是「重装后要快速恢复」的全部内容——少了任何一项，平台就会把这次的
         * 请求看成另一台设备 / 另一次会话（见 [SessionMirror] 与 [restoreFromMirror]）。
         */
        val MIRROR_KEYS = listOf(
            "registered-device-id","registered-install-id","registered-device-token",
            "registered-klink-egdi","registered-profile-stamp","registered-activation-stamp",
            "manual-device-id",
            "device-profile","device-seed","device-profile-seed",
            // 待换新的候选档案（见 [commitPendingProfile]）：重装后仍要记得「这套身份是旧的」，
            // 否则会一直拿着老版本的随机档案继续跑。
            "device-profile-pending","device-profile-pending-seed","device-profile-pending-stamp",
            "device-profile-nonce","account-cookie","account-device","account-expires","x-tt-token",
            // 账号会话的 token / TicketGuard 签名 / 首刷标记 / 注册下发的 cookie：
            // 少了这几项，重装后恢复出来的就是「半份会话」——cookie 在、token 不在，
            // 心跳与账号接口都活不下来。
            "session-token","ts-sign","first-beat","registered-cookies","last-beat-at"
        )
        /** 账号那三项：退出登录时要**一并从镜像里抹掉**，否则下次启动又把登出的会话救回来。 */
        val MIRROR_ACCOUNT_KEYS = listOf(
            "account-cookie","account-device","account-expires","session-token","ts-sign","last-beat-at"
        )
    }
    private val app = context.applicationContext
    /**
     * 设备身份与账号会话的**外部镜像**：卸载重装后从这里捞回来，
     * 省掉「重新注册设备 → 重新登录 → 再发一次上行短信」这一整轮。
     */
    private val mirror = SessionMirror(app)
    /**
     * 把外部镜像接到会话落盘上（`AppSessionStore` 每次写 token / cookie 都同步写一份）。
     *
     * 为什么在这里挂：`SessionMirror` 是本类的私有成员，会话模块拿不到它；而
     * `AppSessionStore` 必须能写镜像，否则「重装后免登录」与「token 轮换后镜像仍是新的」
     * 这两件事都做不到。挂的位置是构造期，早于任何一次心跳。
     */
    init {
        com.hgtv.star.shared.data.SessionBootstrap.attachMirror { key,value ->
            if(value.isNullOrBlank()) mirror.remove(key) else mirror.put(key,value)
        }
    }
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor { task -> Thread(task, "hgtv-library-load") }
    private val prefs by lazy { app.getSharedPreferences("native-library-v1", Context.MODE_PRIVATE) }
    private val homePrefs by lazy { app.getSharedPreferences("native-home-cache-v1", Context.MODE_PRIVATE) }
    /** 搜索登录态单独存一个文件：它不是播放偏好，也不进备份文件、不随记录一起迁移。 */
    private val sessionPrefs by lazy { app.getSharedPreferences("native-session-v1", Context.MODE_PRIVATE) }
    private val writes = LatestWriteQueue<String, () -> Any?>("hgtv-library-write", write = { batch -> commit(prefs, batch) },
        failed = { Log.w("HGTV", "Local library commit failed; pending changes retained for retry") })
    /**
     * 账号侧数据（资料 + 收藏 / 历史 / 点赞 / 预约）的快照。
     *
     * 单独一个文件：它是**平台侧数据**，与 [prefs] 里的本机记录、[homePrefs] 里的榜单缓存
     * 不是一回事——不随备份走、账号一退就清。条目与体积上限见 [AccountSnapshot]。
     */
    private val accountPrefs by lazy { app.getSharedPreferences("native-account-cache-v1", Context.MODE_PRIVATE) }
    private val accountWrites = LatestWriteQueue<String, () -> Any?>("hgtv-account-write", write = { batch -> commit(accountPrefs, batch) },
        failed = { Log.w("HGTV", "Account cache commit failed") })
    private val homeWrites = LatestWriteQueue<String, () -> Any?>("hgtv-home-write", write = { batch -> commit(homePrefs, batch) },
        failed = { Log.w("HGTV", "Home cache commit failed") })
    private var index: LibraryIndex? = null
    /**
     * 观看进度的落盘（一剧一文件，见 [ProgressStore]）。
     *
     * 为什么从 SharedPreferences 里搬出来：`commit()` 会重写整份 XML，
     * 而播放中每 5 秒就要保存一次进度——改造前那是「200 条 JSON 序列化 + 400KB 文件重写」
     * 换「这一部播到第几秒」。现在一次保存只写一个 0.5KB 的文件。
     */
    private val progressStore = ProgressStore(java.io.File(app.filesDir, "library/progress"))
    /**
     * 首页四个「热播…」剧架的缓存，按榜单分别存一份（[RankBoard]）。
     * 首页首屏先铺缓存、再后台更新，用户不必等四个榜单都回来才看到东西。
     */
    private val homes = mutableMapOf<RankBoard, CatalogPage>()
    private val homeSavedAt = mutableMapOf<RankBoard, Long>()
    /** 内存里的账号快照；[accountStamp] 是它对应的账号指纹（换账号即作废）。 */
    private var accountCached: AccountCacheEntry? = null
    private var accountStamp = ""
    private var loading = false
    private var closed = false
    /**
     * 正在清除本机数据（见 [discardPendingWrites]）。
     *
     * 置位之后所有落盘入口都直接返回：清完到进程被杀之间还有几百毫秒，
     * 这期间在途的网络回调（设备注册、账号刷新）如果照常写盘，就会在刚清空的沙盒里
     * 重新留下 `registered-device-id` / 会话这些文件，等于没清干净。
     */
    @Volatile private var discarding = false
    private val readyCallbacks = mutableListOf<() -> Unit>()
    private val errorCallbacks = mutableListOf<(Throwable) -> Unit>()
    val isLoaded get() = index != null

    /** Call before showing the first page. Successful/error callbacks run on the main thread. */
    fun loadAsync(onFailure: (Throwable) -> Unit = { problem ->
        Log.e("HGTV", "Local library failed to load", problem)
        // 「打开就是空列表」的另一条根因：SharedPreferences 损坏、上次写入未落盘超时。
        // 默认回调也要留痕——调用方不传 onFailure 时，这里就是唯一出口。
        Diagnostics.warn("HGTV-LIB", "library load failed", problem)
    }, onReady: () -> Unit) {
        check(!closed)
        if (isLoaded) { onReady(); return }
        readyCallbacks += onReady
        errorCallbacks += onFailure
        if (loading) return
        loading = true
        loader.execute {
            // 会话镜像先读：它是后面 [restoreFromMirror] 的唯一数据源，而且只在这里读一次盘。
            // 放在 retired-writes 检查之前：那一步失败（上一批提交超时）也不该让镜像永远不读。
            runCatching { mirror.preload() }
            val result = runCatching {
                val retired = synchronized(retiredWrites) { retiredWrites.toList() }
                retired.forEach { check(it.awaitClosed(30, TimeUnit.SECONDS)) { "上次观看记录仍在保存，请稍后重试" } }
                synchronized(retiredWrites) { retiredWrites.removeAll(retired.toSet()) }
                val values = prefs.all
                // 观看进度改为「一剧一文件」（见 [ProgressStore]），不再从 SP 里读。
                //
                // 这里做一次**一次性迁移**：旧版把 200 条进度塞在 `progress` 这一个键里。
                // 文件目录为空而旧键有内容时，把旧键拆成文件，然后把 `values` 里的
                // `progress` 换成文件读出来的那一份——`LibraryDisk.decode` 不用改。
                //
                // 先看目录里有没有文件：迁移完之后这个键已经被删掉，但 `values["progress"]`
                // 在某些旧包上可能还残留，每次启动都去解一遍 100KB 的 JSON 是白花的。
                val legacyProgress = if (progressStore.count() == 0 && values["progress"] != null) {
                    runCatching { LibraryDisk.decode(mapOf("progress" to values["progress"])).history() }
                        .getOrDefault(emptyList())
                } else emptyList()
                if (progressStore.migrateFrom(legacyProgress)) {
                    // 迁移成功：旧键由下面的 `persist` 移除（写文件的同时清掉 SP 里那一份）。
                    prefs.edit().remove("progress").apply()
                }
                val fromDisk = progressStore.loadAll()
                val merged = (if (fromDisk.isNotEmpty()) fromDisk else legacyProgress)
                val effectiveValues: Map<String, Any?> =
                    if (merged.isEmpty()) values
                    else values.toMutableMap().apply { put("progress", LibraryDisk.history(merged)) }
                val loaded = LibraryDisk.decode(effectiveValues)
                // 推荐流游标：与其余本机设置一起在这一次读盘里抄进内存（见 [recommendCursor]）。
                recommendCursorCached = (values["recommendCursor"] as? String).orEmpty()
                // 手机端按体裁分开的推荐游标：同理（见 [phoneFeedCursor]）。
                phoneFeedCursorCached = (values["phoneFeedCursor"] as? String).orEmpty()
                // 推荐页的曝光窗口：同理，一次读盘抄进内存（见 [recommendSeen]）。
                // 旧版本落的是「纯 id 列表」，[RecommendMarks.restore] 会按新曝光补上 7 天。
                recommendMarks.restore((values["recommendSeen"] as? String).orEmpty())
                // 「回传 filter_ids」被服务端拒过一次就不再带（见 [recommendFilterIdsOff]）。
                recommendFilterIdsOffCached = values["recommendFilterIdsOff"] as? Boolean ?: false
                // 推荐范围（关掉的那些大类）：同样一次读盘抄进内存（见 [recommendGenreOff]）。
                recommendGenreOffCached = (values["recommendGenreOff"] as? String).orEmpty()
                // 推荐范围里的受众（男频 / 女频）：同理（见 [recommendGender]）。
                recommendGenderCached = (values["recommendGender"] as? String).orEmpty()
                // 「使用设备」：同理，读一次盘就抄进内存（见 [deviceForm]）。
                deviceFormCached = (values["deviceForm"] as? String).orEmpty().ifEmpty { "auto" }
                val now = System.currentTimeMillis()
                val cached = mutableMapOf<RankBoard, CatalogPage>()
                val stamps = mutableMapOf<RankBoard, Long>()
                val migration = homePrefs.edit()
                val oldKeys = mutableListOf<String>()
                RankBoard.entries.forEach { board ->
                    val key = "home-${board.storedValue}"
                    val legacy = values[key] as? String
                    val raw = homePrefs.getString(key, null) ?: legacy.orEmpty()
                    CatalogSnapshot.decode(raw, now)?.let { page ->
                        cached[board] = page
                        stamps[board] = org.json.JSONObject(raw).getLong("savedAt")
                        if (!homePrefs.contains(key)) migration.putString(key, raw)
                    }
                    if (legacy != null) oldKeys += key
                }
                // 首页缓存按榜单分开之后，旧版留下的键（`home-short` 这种）永远不会再被读到：
                // 一起清掉，别让一份不再更新的快照一直躺在磁盘上。
                val liveKeys = RankBoard.entries.map { "home-${it.storedValue}" }.toSet()
                homePrefs.all.keys.filter { it.startsWith("home-") && it !in liveKeys }.forEach(migration::remove)
                // Only remove the legacy copies after the separate cache has been committed.
                val committed = migration.commit()
                if (committed && oldKeys.isNotEmpty()) {
                    prefs.edit().also { edit -> oldKeys.forEach(edit::remove) }.commit()
                }
                // 账号快照在这里一起读：它只在**启动时**读一次（之后由内存里的那一份负责），
                // 因此放在这个已经有工作线程的地方，主线程不会为它多等一毫秒。
                // **未登录时不认磁盘上那份快照**：退出登录 / 掉线 / 清凭证之后，磁盘上的
                // `account` 还留着上一个账号的昵称、收藏、历史。不判登录态就把它读回来，
                // 界面会「一打开就是登录状态」——正是用户看到的那个现象（缓存里的资料，
                // 不是真会话）。这里与 `accountFingerprint()` 同一条判据：没有 cookie 就没有账号。
                val signed = sessionPrefs.getString("account-cookie", "").orEmpty().contains("sessionid=")
                val account = if(!signed) null else accountPrefs.getString("account", null)?.let { raw ->
                    AccountSnapshot.decode(raw, now)?.takeIf { it.data.usable }
                }
                Triple(loaded, cached, stamps) to account
            }
            main.post {
                loading = false
                if (closed) { readyCallbacks.clear(); errorCallbacks.clear(); return@post }
                val ready = readyCallbacks.toList(); readyCallbacks.clear()
                val errors = errorCallbacks.toList(); errorCallbacks.clear()
                result.onSuccess { (local, account) ->
                    val (loaded, cached, stamps) = local
                    index = loaded; homes.putAll(cached); homeSavedAt.putAll(stamps)
                    accountCached = account
                    accountStamp = accountFingerprint()
                    ready.forEach { it() }
                }.onFailure { failure -> errors.forEach { it(failure) } }
            }
        }
    }
    private fun state(): LibraryIndex = checkNotNull(index) { "Load the local library before accessing it" }
    private fun put(key: String, value: Any?) {
        // 清除数据期间队列已经关掉：这时再提交会抛异常，而数据马上就要被删掉，直接丢弃。
        if(discarding) return
        writes.submit(mapOf(key to { value }))
    }
    private fun persist(vararg keys: String) {
        if(discarding) return
        val state = state()
        val batch = linkedMapOf<String, () -> Any?>()
        keys.forEach { key ->
            // Each lambda captures an immutable snapshot; JSON is built by the serial writer.
            batch[key] = when (key) {
                "favorites" -> state.favorites().let { rows -> { LibraryDisk.series(rows) } }
                "later" -> state.later().let { rows -> { LibraryDisk.series(rows) } }
                // 进度不再进 SharedPreferences：整批写进一剧一文件，然后把这个键从 SP 里删掉
                //（返回 null 就是 `edit.remove(key)`）。这条路径只在「导入备份 / 恢复出厂后重建」
                // 这种一次性场合走，200 个文件写一次是可以接受的。
                "progress" -> state.history().let { rows -> { rows.forEach(progressStore::save); null } }
                "favoriteUpdates" -> state.updates().let { rows -> { LibraryDisk.updates(rows) } }
                "searches" -> state.searches().let { rows -> { SearchHistory.encode(rows) } }
                "watched" -> state.watched().let { ids -> { ids } }
                "hidden" -> state.hidden().let { ids -> { ids } }
                "reserves" -> state.reserves().let { rows -> { LibraryDisk.reserves(rows) } }
                // 删过的观看记录：本机墓碑，用来挡住账号侧那半份把删掉的条目又合并回来。
                "historyDeleted" -> state.historyDeleted().let { ids -> { ids } }
                else -> error("Unknown library partition")
            }
        }
        writes.submit(batch)
    }
    fun favorites() = state().favorites()
    fun favorite(id: String) = state().favorite(id)
    fun later() = state().later()
    fun queued(id: String) = state().queued(id)
    fun history(): List<WatchProgress> = state().history()
    fun progress(id: String): WatchProgress? = state().progress(id)
    fun searches() = state().searches()
    fun watched(id: String) = state().watched(id)
    fun hidden() = state().hidden()
    /** 本机记下的预约状态（平台只在详情接口给，见 `LibraryIndex.reserveRows`）。 */
    fun reserved(id: String) = state().reserved(id)
    fun reservedKnown(id: String) = state().reservedKnown(id)
    fun setReserved(id: String, value: Boolean) { state().setReserved(id, value); persist("reserves") }
    fun favoriteUpdate(id: String) = state().favoriteUpdate(id)
    fun updatedFavorites() = state().updatedFavorites()
    fun favoriteLabel(id: String): String = favoriteUpdate(id)?.let {
        if (it.added > 0) "新增 ${it.added} 集 · 更新至 ${it.total} 集" else "更新至 ${it.total} 集"
    } ?: "尚未检查更新"
    fun toggle(series: Series) = state().toggle(series).also { persist("favorites", "favoriteUpdates") }
    fun toggleLater(series: Series) = state().toggleLater(series).also { persist("later") }
    // 重新看了这部剧：墓碑要跟着一起落盘（[LibraryIndex.save] 会把它摘掉），
    // 否则下次启动又从磁盘把墓碑读回来，账号侧那一条会继续被挡着。
    //
    // **只写这一条**（见 [ProgressStore]）：改造前这里会把 200 条历史全量序列化并让
    // SharedPreferences 重写整份 XML，而播放中每 5 秒就会调一次。
    // 墓碑只在「这部剧之前被删过」时才需要重写——绝大多数保存不碰它。
    fun save(progress: WatchProgress) {
        // 正在清空本机数据：写入一律丢弃。
        //
        // 进度现在走的是 `disk` 车道而不是 `writes` 队列，不受 `discardPendingWrites()`
        // 管；少了这一句，清空之后到进程被杀之间的一次心跳 / 播放回调就能把
        // `library/progress/` 重新建出来，用户看到的还是「记录没清干净」。
        if (discarding) return
        val hadTombstone = state().historyDeleted(progress.series.id)
        if (!state().save(progress)) return
        com.hgtv.star.core.WorkLanes.disk.executor.execute { progressStore.save(progress) }
        if (hadTombstone) persist("historyDeleted")
    }
    fun setWatched(id: String, value: Boolean) { if (state().setWatched(id, value)) persist("watched") }
    fun hide(id: String) { state().hide(id); persist("hidden") }
    fun unhideAll() { state().unhideAll(); persist("hidden") }
    fun rememberSearch(query: String) { state().rememberSearch(query); persist("searches") }
    fun clearSearches() { state().clearSearches(); persist("searches") }
    // 删记录：进度文件要一起删，否则下次启动又把它读回来（内存里删了、盘上还在）。
    fun removeHistory(ids: Collection<String>) {
        if (ids.isEmpty()) return
        state().removeHistory(ids)
        com.hgtv.star.core.WorkLanes.disk.executor.execute {
            ids.forEach(progressStore::remove)
        }
        persist("watched", "historyDeleted")
    }
    fun removeHistory(id: String) = removeHistory(listOf(id))
    /** 把一批剧集记为「本机已删除的观看记录」（只留墓碑，不动其它数据）。 */
    fun markHistoryDeleted(ids: Collection<String>) {
        if(ids.isEmpty()) return
        state().markHistoryDeleted(ids); persist("historyDeleted")
    }
    /** 清空全部观看记录，返回被清掉的剧集 ID（调用方拿它去同步删云端）。 */
    fun clearHistory(): List<String> = state().clearHistory().also { ids ->
        if(ids.isNotEmpty()) {
            com.hgtv.star.core.WorkLanes.disk.executor.execute { progressStore.clear() }
            persist("watched", "historyDeleted")
        }
    }
    /** 本机删掉过的观看记录（见 [LibraryIndex.historyDeleted]）。 */
    fun historyDeleted(): Set<String> = state().historyDeleted()
    fun historyDeleted(id: String): Boolean = state().historyDeleted(id)
    fun observeFavorite(id: String, count: Int, now: Long, acknowledge: Boolean = false) {
        // 集数必须 > 0（见 `FavoriteUpdate.observe`）。未上架的剧详情返回 0 集，
        // 传进来会抛 `IllegalArgumentException`——收藏更新检查就曾因此在预告页闪退。
        if (count <= 0) return
        if (state().observeFavorite(id, count, now, acknowledge)) persist("favoriteUpdates")
    }
    fun cachedHome(board: RankBoard): CatalogPage? {
        state()
        val age = System.currentTimeMillis() - (homeSavedAt[board] ?: return null)
        return homes[board].takeIf { age in 0..7 * 24 * 60 * 60 * 1000L }
    }
    fun cacheHome(board: RankBoard, items: List<Series>, hasMore: Boolean) {
        if(discarding) return
        state()
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        val retained = items.take(30).map { it.copy(description = it.description.take(1500)) }
        homes[board] = CatalogPage(retained, hasMore)
        homeSavedAt[board] = now
        homeWrites.submit(mapOf("home-${board.storedValue}" to { CatalogSnapshot.encode(retained, hasMore, now) }))
    }
    /**
     * 账号侧数据的缓存（内存那一份，启动时从磁盘读回）。
     *
     * 返回 null 表示**这一台设备还没有这个账号的缓存**（第一次登录 / 换了账号 / 缓存过期或损坏）；
     * 调用方据此决定要不要显示「正在读取」。返回的那一份可能已经旧了，由调用方按
     * [AccountSnapshot.MAX_AGE] 之类的策略决定「先铺上、再去后台刷」。
     */
    /**
     * 本机现在有没有可用的登录态。
     *
     * 判据只有一条：`account-cookie` 里有没有 `sessionid`。与 `AccountCredentials.configured`
     * 一致（那边判 cookie 非空，而这里更严一点——只有 `sessionid` 才说明真登录过，
     * 出码 / 注册阶段下发的 `passport_csrf_token` 不算）。
     */
    private fun signedIn(): Boolean =
        sessionPrefs.getString("account-cookie", "").orEmpty().contains("sessionid=")

    fun accountCache(): AccountCacheEntry? {
        state()
        // 指纹对不上就是换了账号（或退出了）：那份缓存不属于当前登录态，直接丢掉。
        if (accountStamp != accountFingerprint()) { accountCached = null; accountStamp = accountFingerprint() }
        // 没有登录态时**一律返回 null**。
        //
        // 只比指纹是不够的：退出登录后 `account-cookie` 被清空，指纹是空串，而 `accountStamp`
        // 在 [load] 里也是按空串算的——两边相等，于是「上一个账号的快照」被当成当前账号的缓存
        // 返回，界面又铺出昵称与列表。用户点过退出、重开却还是登录状态，就是这么来的。
        if (accountCached != null && !signedIn()) { accountCached = null; return null }
        return accountCached
    }
    /**
     * 落盘一份账号快照。
     *
     * 只写内存里那份的**替换**（先换内存、再排队落盘）：界面下一次读到的就是这一份，
     * 不必等磁盘。写入走 [LatestWriteQueue]（同一 key 只保留最后一次），
     * 因此播放中反复刷新也不会把磁盘写爆。
     */
    fun cacheAccount(data: AccountData, now: Long = System.currentTimeMillis()) {
        if(discarding) return
        // 没有登录态就不该落快照：否则退出登录之后，磁盘上还留着一份「当前账号」，
        // 下次启动会被当成已登录的内容铺出来（见 [accountCache]）。
        if(!signedIn()) return
        state()
        val entry = AccountCacheEntry(AccountSnapshot.cacheable(data), now)
        accountCached = entry
        accountStamp = accountFingerprint()
        // 序列化交给落盘队列的工作线程（它本来就在后台）：主线程只换内存里那一份引用，
        // 不为了写缓存而在 UI 线程上拼一份上百 KB 的 JSON。
        // 关掉之后再进来的那次（Activity 已经销毁、网络回调才落地）不该抛异常：
        // 磁盘上那一份旧缓存还能用，丢掉这一次写入没有代价。
        runCatching { accountWrites.submit(mapOf("account" to { AccountSnapshot.encode(entry.data, entry.savedAt) })) }
    }
    /** 清掉账号快照（退出登录、换账号、导入新凭据时调用）。 */
    fun clearAccountCache() {
        if(discarding) return
        accountCached = null
        accountStamp = accountFingerprint()
        runCatching { accountWrites.submit(mapOf("account" to { null })) }
    }
    /**
     * 当前账号的指纹：拿 Cookie 串算一个稳定的短哈希。
     *
     * 只用它判「缓存是不是这个账号的」——不落盘 Cookie 本身（凭据已经在 [sessionPrefs] 里），
     * 也不参与任何请求签名。
     */
    private fun accountFingerprint(): String {
        val cookie = sessionPrefs.getString("account-cookie", "").orEmpty()
        if (cookie.isBlank()) return ""
        var hash = 1125899906842597L
        cookie.forEach { char -> hash = 31 * hash + char.code }
        return "%016x".format(hash)
    }
    var maxQuality: Int
        get() = state().settings.quality
        set(value) { val next = PlaybackQuality.normalize(value); if (next != maxQuality) { state().settings = state().settings.copy(quality = next); put("quality", next) } }
    /**
     * 用户有没有确认过「第三方客户端」的使用须知。
     *
     * 存在会话偏好里（不进备份、不进镜像）：换一台设备、或者清除数据之后要重新确认一次，
     * 这是这一条声明应有的语义——它是「这台设备上的这位用户」的知情同意，不是可以搬走的偏好。
     */
    var thirdPartyAccepted: Boolean
        get() = sessionPrefs.getBoolean("third-party-accepted",false)
        set(value) {
            if(discarding) return
            if(value==thirdPartyAccepted) return
            sessionPrefs.edit().putBoolean("third-party-accepted",value).apply()
        }
    /**
     * 用户有没有自己选过清晰度。
     *
     * 判据就是磁盘上有没有 `quality` 这个键：`LibraryDisk.decode` 在缺键时按 1080 兜底，
     * 但**不写回磁盘**，所以「没有这个键」恰好等于「还从没选过」。
     * 首次启动按设备解码能力给默认值（见 `PlaybackCapability.defaultQuality`）时用它做闸门，
     * 用户点过任意一档之后就不再覆盖他的选择。
     */
    fun qualityChosenByUser(): Boolean = prefs.contains("quality")
    /**
     * 搜索登录态（可选，App 抓到的 `x-tt-token`，整段 Cookie 也可以）。
     *
     * 不填也能搜索（本机会先匿名注册一台设备，见 `DeviceRegister`）；填上后按该账号请求。
     * 它只用于搜索请求，不进备份文件，也不参与「手机备份与恢复」。
     */
    var searchToken: String
        get() {
            restoreFromMirror()
            val token=sessionPrefs.getString("x-tt-token", "").orEmpty()
            mirrorSync("x-tt-token",token)
            return token
        }
        set(value) {
            if(discarding) return
            sessionPrefs.edit().putString("x-tt-token", value.trim()).apply()
            // 清空令牌是明确的删除意图（用户在设置里清掉的），走 remove 而不是 put(null)：
            // 后者在读盘之前只会被当成「本机还没有」，盘上那份不会被抹掉。
            if(value.isBlank()) mirror.remove("x-tt-token") else mirror.put("x-tt-token",value.trim())
        }
    /**
     * 把外部镜像里**本机缺的那几项**补回 [sessionPrefs]。
     *
     * 只有「重装之后第一次读」才真的会补东西：正常运行的那台机器每一项都在，这里一个键都不碰。
     * 逐项判断（不是整份覆盖）是有意的——镜像可能比本机旧，本机**已经有的值必须优先**，
     * 否则一个刚注册的新 `device_id` 会被外部那份旧的顶掉。
     */
    @Volatile private var mirrorChecked = false
    private fun restoreFromMirror(): Boolean {
        // 清除数据期间不再从镜像补写：补进去的任何一项都会让「恢复出厂」前功尽弃。
        if(discarding) return false
        if(mirrorChecked) return false
        // 后台还没读完盘：这一次不算数，等 [preload] / [reload] 完成后再补。
        // 否则主线程第一次调用就会把「空镜像」记成已检查，重装恢复从此失效。
        if(!mirror.isLoaded()) return false
        mirrorChecked = true
        val edit = sessionPrefs.edit()
        var changed = false
        MIRROR_KEYS.forEach { key ->
            if(sessionPrefs.contains(key)) return@forEach
            val text = mirror.string(key) ?: return@forEach
            // 过期时间是数值，其余都是字符串：这个类型差不能含糊，否则 getLong 会拿到 0。
            // 这两项是数值：类型差不能含糊，否则 `getLong` 会拿到 0（见各自的注释）。
            if(key=="account-expires" || key=="last-beat-at") edit.putLong(key,text.toLongOrNull() ?: 0L)
            else edit.putString(key,text)
            changed = true
        }
        if(changed) {
            edit.apply()
            Log.i("HGTV","Restored device identity / session from the external mirror")
            // 补回来的可能是**会话**，而这一步通常在后台线程、比 `MainActivity` 那次
            // `onAppStart` 晚：那一次判成「未登录」直接返回，`onResume` 也早已过去，
            // 于是本轮进程再也不会有心跳（重装后第一晚的「掉登录」就是这么来的）。
            SessionBootstrap.onSessionRestored()
        }
        return changed
    }
    /**
     * 权限刚拿到之后**再试一次**恢复（见 `MainActivity.onRequestPermissionsResult`）。
     *
     * 为什么必须再试：新装的应用在用户点「允许」之前读不了 `/sdcard/.hgtv`，而启动时那次恢复
     * 已经跑过（[mirrorChecked] 已置位）——不重试，镜像里那份设备身份与会话就一直认不回来，
     * 「点一下允许就能免掉重新登录与短信验证」这件事根本不会发生。
     *
     * 读盘与恢复都在 [loader] 线程上做，结果回主线程交给 [onDone]；调用方（权限回调）
     * 本身就在主线程，不能在这里碰 `/sdcard`。
     */
    fun retryMirrorRestore(onDone: (Boolean) -> Unit) {
        // 读盘放在 loader 线程：这条路的调用方是 onRequestPermissionsResult（主线程），
        // 在它里面读 `/sdcard/.hgtv` 正是线上那条 ANR 的直接原因。
        runCatching {
            loader.execute {
                val restored=runCatching {
                    mirror.reload()
                    mirrorChecked=false
                    val changed=restoreFromMirror()
                    // 权限刚拿到：把这份镜像**立刻补写**到刚变得可写的另外两处
                    //（`.hgtv` / `Android/data`）。不补的话，要等到下一次身份变化才会在
                    // 外部存储里留下副本，用户中途卸载重装就又丢了。
                    mirror.persistNow()
                    changed
                }.getOrDefault(false)
                main.post { onDone(restored) }
            }
        }.onFailure { main.post { onDone(false) } }
    }
    /**
     * 把本机已有的值补进镜像——**只在镜像缺它 / 与它不同**时写一次。
     *
     * 这是让「已经装着这个版本之前的用户」也受益的那一步：他们的 `device_id` / 会话早就在
     * [sessionPrefs] 里了（不是这次才写进去的），镜像里却还是空的；不补上，卸载重装就仍旧是
     * 一台新设备。读的时候顺手补一次，代价只是一次字符串比较。
     */
    private fun mirrorSync(key: String,value: String) {
        // 本机是空值时**不写镜像**：那不是「删掉」，只是「这台机器上还没有」，
        // 而镜像里那份正是重装后要恢复的会话（见 SessionMirror.put 的说明）。
        if(value.isBlank()) return
        if(mirror.string(key).orEmpty()==value) return
        mirror.put(key,value)
    }
    /** 同上，数值那一项（`account-expires`）；0 表示「本机还不知道」，不写镜像。 */
    private fun mirrorSync(key: String,value: Long) {
        if(value<=0) return
        if(mirror.string(key)==value.toString()) return
        mirror.put(key,value)
    }
    /**
     * 本机设备身份：`device_id` 与 `iid`。
     *
     * 只有一个来源：**平台注册结果**（`registered-device-id` / `registered-install-id`）。
     *
     * 内容接口（搜索、播放、弹幕）认的是「服务端注册过的 `device_id` + `install_id` 这一对」，
     * 随手编的数字拿不到数据，因此本机不再自己生成随机身份——播放也用注册下发的那一对。
     * 返回空表示这台设备还没注册过：调用方先注册（`MainActivity.ensureDeviceRegistered`，
     * 播放前另有一次同步补注册），注册成功后 [saveDeviceIdentity] 落盘，这里就能给出来。
     */
    fun deviceIdentity(): Map<String,String> {
        restoreFromMirror()
        val registered=sessionPrefs.getString("registered-device-id","").orEmpty()
        val registeredInstall=sessionPrefs.getString("registered-install-id","").orEmpty()
        if(isDeviceNumber(registered) && isDeviceNumber(registeredInstall)) {
            // 老版本装过、镜像里还没有这一对的：读的时候补进去，卸载重装才认得回这台设备。
            mirrorSync("registered-device-id",registered); mirrorSync("registered-install-id",registeredInstall)
            val identity=linkedMapOf("device_id" to registered,"iid" to registeredInstall)
            sessionPrefs.getString("registered-device-token","").orEmpty()
                .takeIf { it.isNotBlank() }?.let {
                    mirrorSync("registered-device-token",it)
                    identity["device_token"]=it
                }
            sessionPrefs.getString("registered-klink-egdi","").orEmpty()
                .takeIf { it.isNotBlank() }?.let {
                    mirrorSync("registered-klink-egdi",it)
                    identity["klink_egdi"]=it
                }
            return identity
        }
        return emptyMap()
    }
    /**
     * 用户在系统高级里手动覆盖的官方 App `device_id`。
     *
     * 空字符串表示使用自动注册 / 一次性设备池。这里只保存覆盖值，不改写注册下发的
     * `registered-device-id`，因此随时可以恢复自动身份。
     */
    fun manualDeviceId(): String {
        restoreFromMirror()
        return DeviceRegister.normalizeManualDeviceId(
            sessionPrefs.getString("manual-device-id","").orEmpty()
        ).orEmpty()
    }
    fun saveManualDeviceId(value: String): Boolean {
        if(discarding) return false
        val clean=DeviceRegister.normalizeManualDeviceId(value) ?: return false
        sessionPrefs.edit().putString("manual-device-id",clean).commit()
        mirror.put("manual-device-id",clean)
        mirror.flush()
        return true
    }
    fun clearManualDeviceId() {
        if(discarding) return
        sessionPrefs.edit().remove("manual-device-id").commit()
        mirror.remove("manual-device-id")
        mirror.flush()
    }
    /**
     * 是否**真**在平台注册过（只有注册过搜索才可能出数据）。
     *
     * 除了「有没有那一对数字」，还要看**这份注册结果是不是按当前档案注册出来的**：
     * 平台按 `cdid` / `openudid` 认设备，档案换了而 `device_id` 没换，两者对不上号，
     * 请求会被当成陌生设备（搜索 `200 + 0 字节`、播放 `101000`）。
     * 这一条是「升级到 1.2.3 后搜索 / 播放都不能用」的正面判据——
     * 没有它，`deviceRegistered()` 只看到数字形状对就答 true，永远不会重注册。
     */
    fun deviceRegistered(): Boolean {
        restoreFromMirror()
        // **不判 `registrationPending`**：那份「待换新」只是「档案想升级」，旧身份在平台上
        // 仍然是好的（搜索 / 播放都还能用）。判成「没注册」会让每次搜索都同步发一次注册请求，
        // 换新失败时用户每搜一次就多等一个 RTT。换新只由启动那一次（[MainActivity.ensureDeviceRegistered]）
        // 与播放前那一次兜底负责。
        // **先取档案指纹**：这一步可能把老版本的随机档案重建成派生值，
        // 那时 [invalidateRegistration] 会当场抹掉注册结果。因此读身份必须放在它之后，
        // 否则读到的是「刚被抹掉之前」那一对，判成已注册。
        val stamp=profileStamp()
        val registered=sessionPrefs.getString("registered-device-id","").orEmpty()
        val install=sessionPrefs.getString("registered-install-id","").orEmpty()
        if(!isDeviceNumber(registered) || !isDeviceNumber(install)) return false
        // 注册下发的 Cookie 里的 `install_id` 必须与这一对身份一致：它们是同一次注册的产物。
        // 从外部镜像救回来的旧 Cookie（重装 / 升级时那一份）与新的 `registered-install-id`
        // 对不上时，请求里会同时带「新 device_id」与「旧 install_id」，服务端按陌生设备处理——
        // 现象与「没注册过」一样（搜索空响应、播放 101000），只有清缓存能好。
        val cookieInstall=registrationCookies()["install_id"].orEmpty()
        if(cookieInstall.isNotBlank() && cookieInstall!=install) {
            // 顺手把这份对不上的 Cookie 抹掉：留着它，重新注册时 `putAll` 只会覆盖新下发的键，
            // 旧的 `install_id` 仍在，下一次判断还是 false——那就成了每次请求都重注册一次。
            clearRegistrationCookies()
            return false
        }
        val known=sessionPrefs.getString("registered-profile-stamp","").orEmpty()
        // 老版本没有这一项：不因为缺项就判死（那一版注册出来的身份在服务端是好的），
        // 档案真的换过的那条路由 [invalidateRegistration] 当场作废，不靠这里兜。
        // **不在读的时候补写**：缺项本身是「这份身份比当前版本旧」的判据，
        // 由 [registrationNeedsRefresh] 用一次后台换新把它消掉。
        return known.isBlank() || known==stamp
    }
    /**
     * 这份注册结果是不是**当前版本注册出来的**（缺 `registered-profile-stamp` = 不是）。
     *
     * 用来做一次升级迁移：1.2.3 换了注册线路与上报参数，旧版本注册下来的 `device_id` / `iid`
     * 在内容接口上会被当成陌生设备——搜索回 `200 + 0 字节`、播放回 `101000`，
     * 而「设置 → 清空缓存」之所以能修好，正是因为它把这些数字删了重新注册。
     *
     * 换新是**后台、可失败**的（见 `MainActivity.refreshRegistrationIdentity`）：
     * 换新成功才替换身份，失败就继续用旧的那一对，用户不会因此连首页都看不了。
     */
    fun registrationNeedsRefresh(): Boolean {
        restoreFromMirror()
        val registered=sessionPrefs.getString("registered-device-id","").orEmpty()
        val install=sessionPrefs.getString("registered-install-id","").orEmpty()
        if(!isDeviceNumber(registered) || !isDeviceNumber(install)) return false
        return sessionPrefs.getString("registered-profile-stamp","").orEmpty().isBlank()
    }
    /** 当前档案的指纹（`cdid|openudid`）：只用来判断「注册结果与档案是不是同一套」。 */
    @Volatile private var cachedProfileStamp: String?=null
    private fun profileStamp(): String {
        cachedProfileStamp?.let { return it }
        val profile=deviceProfile()
        val stamp=profile["cdid"].orEmpty()+"|"+profile["openudid"].orEmpty()
        // 清除数据期间 `deviceProfile()` 返回空表：这一份不能缓存，否则清完缓存
        // （同一个进程里还会读几次）会一直拿着 `"|"` 去比对。
        if(profile.isEmpty()) return stamp
        cachedProfileStamp=stamp
        return stamp
    }
    /**
     * 作废本机的注册结果（设备身份 + 下发 Cookie + 档案指纹）。
     *
     * 与 [saveDeviceIdentity] 传空同义，但多清一项指纹——三者必须一起走，
     * 少清一项都会让下一次 [deviceRegistered] 判成「还是好的」。
     */
    fun invalidateRegistration() {
        if(discarding) return
        sessionPrefs.edit().remove("registered-device-id").remove("registered-install-id")
            .remove("registered-device-token")
            .remove("registered-klink-egdi")
            .remove("registered-profile-stamp").remove("registered-activation-stamp")
            .remove("registered-cookies")
            .remove("registration-pending").apply()
        listOf("registered-device-id","registered-install-id","registered-device-token",
            "registered-klink-egdi","registered-profile-stamp","registered-activation-stamp",
            "registered-cookies")
            .forEach(mirror::remove)
        cachedProfileStamp=null
    }
    /**
     * 这一次「换新身份」的注册失败了，下次启动 / 下次取播放地址前再试。
     *
     * 为什么要落盘：注册失败（网络、风控、服务端抖动）时**不能把旧身份清掉**——
     * 清了就变成「设备未注册」，搜索回空响应、播放回 `110001`，用户看到的就是
     * 「升级之后什么都放不了」。因此失败只记一个标记，旧身份继续用，下一次再换。
     */
    fun markRegistrationPending() {
        if(discarding) return
        sessionPrefs.edit().putBoolean("registration-pending",true).apply()
    }
    fun clearRegistrationPending() {
        if(discarding) return
        sessionPrefs.edit().remove("registration-pending").apply()
    }
    fun registrationPending(): Boolean =
        sessionPrefs.getBoolean("registration-pending",false) || pendingProfile()!=null
    /**
     * 这台已注册设备是否完成过 App 日志 SDK 激活。
     *
     * 标记与 `device_id|iid` 绑定；重新注册换号后会自动失效。
     */
    fun deviceActivated(): Boolean {
        restoreFromMirror()
        val id=deviceIdentity()
        val deviceId=id["device_id"].orEmpty()
        val installId=id["iid"].orEmpty()
        if(deviceId.isBlank() || installId.isBlank()) return false
        return sessionPrefs.getString("registered-activation-stamp","").orEmpty()==
            "$deviceId|$installId"
    }
    fun markDeviceActivated() {
        if(discarding) return
        val id=deviceIdentity()
        val deviceId=id["device_id"].orEmpty()
        val installId=id["iid"].orEmpty()
        if(deviceId.isBlank() || installId.isBlank()) return
        val stamp="$deviceId|$installId"
        sessionPrefs.edit().putString("registered-activation-stamp",stamp).commit()
        mirror.put("registered-activation-stamp",stamp)
        mirror.flush()
    }
    /** 落盘注册结果；传空表示清除（下次启动重新注册）。 */
    fun saveDeviceIdentity(
        deviceId: String,
        installId: String,
        klinkEgdi: String = "",
        deviceToken: String = "",
    ) {
        if(discarding) return
        if(deviceId.isBlank() || installId.isBlank()) { invalidateRegistration(); return }
        // 连同「这份身份是按哪套档案注册出来的」一起落盘：档案一变就能立刻判出来
        // （见 [deviceRegistered]）。
        val stamp=profileStamp()
        // **同步 commit**（不是 apply）：`apply()` 的落盘由系统在后台排一次，
        // 进程被强杀（电视盒子拔电 / 用户划掉任务）时那一次可能还没写下去。
        // 身份丢了就等于「这台设备从没注册过」——搜索空响应、播放 110001。
        // 这几项只在注册成功时写一次，同步写一次磁盘的代价可以忽略。
        sessionPrefs.edit().putString("registered-device-id",deviceId).putString("registered-install-id",installId)
            .putString("registered-device-token",deviceToken)
            .putString("registered-klink-egdi",klinkEgdi)
            .putString("registered-profile-stamp",stamp)
            .remove("registered-activation-stamp").commit()
        // 身份一变（首次注册 / 重新注册）镜像立刻跟上：卸载重装后要先认回这台设备，再谈别的。
        mirror.put("registered-device-id",deviceId); mirror.put("registered-install-id",installId)
        if(deviceToken.isNotBlank()) mirror.put("registered-device-token",deviceToken)
        else mirror.remove("registered-device-token")
        if(klinkEgdi.isNotBlank()) mirror.put("registered-klink-egdi",klinkEgdi) else mirror.remove("registered-klink-egdi")
        mirror.put("registered-profile-stamp",stamp)
        mirror.remove("registered-activation-stamp")
        // 不等 250 ms 的合并窗口：注册成功后用户可能马上就退出 / 强杀，
        // 那一次写没落盘，下一次进来就又成了「未注册设备」——正是用户报的那个现象。
        mirror.flush()
    }
    /**
     * 设备注册下发的 Cookie（`install_id` / `ttreq` / `store-region` …）。
     *
     * 这几个值原本只活在 `ContentRepository` 的内存里，进程一重启就没了，于是每个新进程的
     * 第一轮请求都是「裸设备」——平台按陌生设备处理，风控更严。存下来之后，
     * 下次启动由 `ContentRepository.restoreDeviceCookies` 灌回去。
     */
    fun registrationCookies(): Map<String,String> {
        restoreFromMirror()
        val raw = sessionPrefs.getString("registered-cookies","").orEmpty()
        if(raw.isBlank()) return emptyMap()
        return runCatching {
            val json = org.json.JSONObject(raw)
            buildMap {
                for(key in json.keys()) json.optString(key).takeIf { it.isNotBlank() }?.let { put(key,it) }
            }
        }.getOrDefault(emptyMap())
    }
    /**
     * 抹掉注册下发的 Cookie。
     *
     * 与 [saveDeviceIdentity] 传空同一个语义：设备身份换了一套，旧 Cookie 里的 `install_id`
     * 指向的是**上一台**设备，留着会让新身份的请求带着两份互相矛盾的设备号。
     */
    fun clearRegistrationCookies() {
        if(discarding) return
        sessionPrefs.edit().remove("registered-cookies").commit()
        mirror.remove("registered-cookies")
    }
    fun saveRegistrationCookies(cookies: Map<String,String>) {
        if(discarding) return
        val clean = cookies.filterValues { it.isNotBlank() }
        if(clean.isEmpty()) return
        val json = org.json.JSONObject().apply { clean.forEach { (key,value) -> put(key,value) } }.toString()
        sessionPrefs.edit().putString("registered-cookies",json).commit()
        mirror.put("registered-cookies",json)
        // 与身份同一次注册的产物：分开落盘就可能出现「device_id 在、install_id 不在」，
        // 那半份身份在服务端等于陌生设备（搜索空响应 / 播放 101000）。
        mirror.flush()
    }
    /**
     * 本机的**真机设备档案**：机型 / 品牌 / 系统 / 分辨率 / 密度 / `cdid` / `openudid` …
     * （**不含** `device_id` / `iid`，那两个由注册下发）。
     *
     * 这一份**同时**喂给注册请求与请求签名（见 `ContentRepository.deviceProfile` 的合并层），
     * 因此注册时上报的机型、签名里 `x-medusa` 上报的机型、以及 query 里的 `device_type`
     * 是同一个值——平台的风控会拿这几处做一致性判定，同一次会话里出现两台设备是不正常的。
     *
     * ## 标识按官方 App 规则生成一次
     *
     * `cdid` / `clientudid` 各自是独立 UUID；`openudid` 优先使用有效的 Android ID，
     * 没有 Android ID 时才使用 SecureRandom 兜底。三项都随档案一起落盘，后续只读不重算。
     *
     * ## 读路径只有一条
     *
     * 档案与种子都在盘上，**每次只读盘**：盘上有就原样返回，没有才生成一次并落盘。
     * 机型 / 系统 / 分辨率这些字段因此只采集一次——系统升级之后档案不跟着变，
     * 这正是「同一台设备」该有的样子。
     */
    fun deviceProfile(): Map<String,String> {
        restoreFromMirror()
        // 清除数据期间不再采集 / 落盘：此时写进去的任何一项都会在沙盒里留下痕迹。
        if(discarding) return emptyMap()
        val stored=sessionPrefs.getString("device-profile","").orEmpty()
        if(stored.isNotBlank()) {
            var map=runCatching {
                val json=org.json.JSONObject(stored)
                buildMap { for(key in json.keys()) json.optString(key).takeIf { it.isNotBlank() }?.let { put(key,it) } }
            }.getOrNull()
            if(map!=null &&
                map["profile-format"]=="official-v1" &&
                map["cdid"].orEmpty().isNotBlank() &&
                map["openudid"].orEmpty().isNotBlank() &&
                map["clientudid"].orEmpty().isNotBlank()
            ) {
                // 早期版本可能在 Android ID 尚不可读时先落了一个随机兜底值。
                // 不要把它永久当成设备身份：后续每次读档案都再试一次 Android ID，
                // 一旦可读就把 openudid 升级为官方语义，并让注册指纹自然失效、重新注册。
                val source=map["openudid-source"].orEmpty()
                // 官方 App 用真实 Android ID 作为 openudid。即使旧版本曾因换设备
                // 写过 forced_random，只要现在能读到 Android ID，也要升级回来；
                // 否则设备会永久以 new_user=1 的陌生身份请求，第一批模型后就被限流。
                if(source!="android_id") {
                    val androidId=readAndroidId()
                    if(androidId!=null && androidId!=map["openudid"]) {
                        map=LinkedHashMap(map).apply {
                            put("openudid",androidId)
                            put("openudid-source","android_id")
                        }
                        val upgraded=org.json.JSONObject()
                        map.forEach { (key,value) -> upgraded.put(key,value) }
                        val json=upgraded.toString()
                        sessionPrefs.edit().putString("device-profile",json).commit()
                        mirror.put("device-profile",json)
                        mirror.flush()
                        cachedProfileStamp=null
                        Log.i("HGTV","Device openudid upgraded to Android ID")
                    }
                }
                // 老版本只把档案写在本机私有目录里：读一次就补进镜像，卸载重装才认得回这台设备。
                val current=org.json.JSONObject()
                map.forEach { (key,value) -> current.put(key,value) }
                mirrorSync("device-profile",current.toString())
                // **升级不动身份**：老版本那份档案（种子由系统标识符派生）原样继续用。
                // 早先这里会「升级时强制换一台设备」，实测结论是**不要**这么做：
                // 换设备只能换来一次重新绑定，账号在平台上被当成陌生机器，而收益为零
                //（平台认设备靠的是 `install_id` / `sessionid` 这串 Cookie，见
                // `ContentRepository.registerDevice`）。要换只有一条路——服务端明确不认这份身份
                //（见 `MainActivity.healDeviceIdentity`）。
                return map
            }
        }
        // 旧版档案没有官方格式标记：重建一次，让注册身份与官方字段重新对齐。
        // 盘上没有（首次使用 / 刚重新注册）也走这里。
        val fresh=buildOfficialProfile()
        val json=org.json.JSONObject(); fresh.forEach { (key,value) -> json.put(key,value) }
        // **同步 commit**：档案丢了就等于换了台设备，不能让它在强杀时还没写下去。
        sessionPrefs.edit().putString("device-profile",json.toString()).commit()
        mirror.put("device-profile",json.toString())
        mirror.flush()
        cachedProfileStamp=null
        return fresh
    }
    /**
     * 生成一条只用于一次性播放注册的设备档案。
     *
     * 机型、系统、分辨率等物理档案沿用本机当前值，三个设备标识全部换成新的随机值。
     * 这里不写 `device-profile`，也不改动当前注册身份；返回值只交给后台设备池使用。
     */
    fun freshPlaybackDeviceProfile(): Map<String,String> {
        val base=deviceProfile()
        if(base.isEmpty()) return emptyMap()
        return LinkedHashMap(base).apply {
            put("cdid",OfficialDeviceIds.cdid())
            put("openudid",OfficialDeviceIds.openudid(null))
            put("openudid-source","playback_pool")
            put("clientudid",OfficialDeviceIds.clientudid())
        }
    }
    /**
     * 生成一套**新的**设备身份候选（新种子 → 新档案），等注册成功再转正。
     *
     * 为什么是「候选」而不是直接换：注册可能失败（网络 / 风控 / 服务端抖动），
     * 直接换掉档案就等于把用户变成「未注册设备」——搜索空响应、播放 110001，
     * 而旧的档案 + 旧身份本来还能用。候选档案只在注册成功那一刻才转正
     *（见 [commitPendingProfile]），失败则一切照旧，下次再试。
     *
     * ## 唯一的触发者是「服务端明确不认这份身份」
     *
     * 早先还有一条「升级到随机种子方案时强制换一台设备」的迁移，**已删除**（2026-11）：
     * 实测平台认设备靠的是注册下发的 `install_id` / `sessionid` Cookie，不是 `cdid`；
     * 换设备只能换来一次重新绑定，账号在平台上被当成陌生机器，收益为零。
     *
     * 现在只有 `MainActivity.healDeviceIdentity` 会走到这里（搜索 / 播放被服务端判成
     * 「这台设备没注册过」之后的自愈），且一次进程最多两次、间隔至少 20 秒。
     */
    fun queueNewDeviceIdentity() {
        if(discarding) return
        // 换设备只换 cdid/clientudid；openudid 继续使用真实 Android ID。
        // 把它换成随机值会让服务端把用户当成全新设备，播放模型额度只剩第一批。
        storePendingProfile(buildOfficialProfile())
        markRegistrationPending()
    }
    /** 按官方默认链路生成一整套设备档案（机型 / 系统 / 分辨率 / 密度 / 三个标识）。 */
    private fun buildOfficialProfile(forceRandomOpenudid: Boolean = false): Map<String,String> {
        val model=android.os.Build.MODEL.orEmpty().trim()
        val brand=android.os.Build.BRAND.orEmpty().trim()
        // 机型 / 品牌认不出来时**整体**回落到内置档案那一台（Redmi + 25053RT47C），
        // 而不是各自填一个默认值——那样会拼出「Intel 平台的三星机」这种自相矛盾的档案。
        val known=model.isNotBlank() && brand.isNotBlank()
        val metrics=app.resources.displayMetrics
        val configuration=app.resources.configuration
        val width=metrics.widthPixels
        val height=metrics.heightPixels
        val largeScreen=(configuration.screenLayout and android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK) >=
            android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE
        val installTime=runCatching {
            app.packageManager.getPackageInfo(app.packageName,0).firstInstallTime
        }.getOrDefault(System.currentTimeMillis())
        val telephony=runCatching {
            app.getSystemService(android.content.Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        }.getOrNull()
        val androidId=readAndroidId()
        val carrier=runCatching { telephony?.networkOperatorName.orEmpty() }.getOrDefault("")
        val mccMnc=runCatching { telephony?.networkOperator.orEmpty() }.getOrDefault("")
        return linkedMapOf(
            // 官方默认链路：cdid/clientudid 各自随机，openudid 优先 Android ID。
            // 生成一次后随档案落盘，后续不能重算。
            "profile-format" to "official-v1",
            "cdid" to OfficialDeviceIds.cdid(),
            "openudid" to OfficialDeviceIds.openudid(if(forceRandomOpenudid) null else androidId),
            "openudid-source" to when {
                forceRandomOpenudid -> "forced_random"
                androidId!=null -> "android_id"
                else -> "fallback"
            },
            "clientudid" to OfficialDeviceIds.clientudid(),
            "device_type" to (if(known) model else "25053RT47C"),
            "device_brand" to (if(known) brand else "Redmi"),
            "channel" to "69310258a",
            "dragon_device_type" to "phone",
            "os_version" to android.os.Build.VERSION.RELEASE.orEmpty().ifBlank { "16" },
            "os_api" to android.os.Build.VERSION.SDK_INT.toString(),
            // `Build.DISPLAY` 是这台设备实际的固件版本串（官方 App 也读它当 `rom_version`）。
            "rom_version" to android.os.Build.DISPLAY.orEmpty().ifBlank { "W528JS release-keys" },
            "rom" to android.os.Build.VERSION.INCREMENTAL.orEmpty().ifBlank { "1135" },
            "carrier" to carrier.ifBlank { "CHINA MOBILE" },
            "mcc_mnc" to mccMnc.ifBlank { "46000" },
            // 分辨率与密度：与机型同源（都来自本机）。官方 query 用「长边*短边」，
            // 注册 body 再由 DeviceRegister 换成「短边x长边」。
            // 采集不到时回落到内置值（手机竖屏形态）。
            "resolution" to (if(width>0 && height>0) "${maxOf(width,height)}*${minOf(width,height)}" else "2772*1280"),
            "dpi" to (if(metrics.densityDpi>0) metrics.densityDpi.toString() else "520"),
            "is_android_pad_screen" to (if(largeScreen) "1" else "0"),
            // 重复注册时仍上报这台设备的真实首次安装时间，不能每次都写成“刚刚安装”。
            "apk_first_install_time" to installTime.toString()
        )
    }
    /** App 进程实际可见的 Android ID；官方 App 也是用这一个，而不是 shell 的全局值。 */
    private fun readAndroidId(): String? = runCatching {
        android.provider.Settings.Secure.getString(
            app.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID,
        )?.trim()?.takeIf(OfficialDeviceIds::validAndroidId)
    }.getOrNull()
    /**
     * 「换新身份」用的**候选档案**：派生出来但还没被平台接受的那一份。
     *
     * 只在 [deviceProfile] 发现老版本随机档案时生成；注册成功之后由 [commitPendingProfile]
     * 变成正式档案。返回 null 表示当前没有待换的档案（绝大多数情况下都是 null）。
     */
    private fun pendingProfile(): Map<String,String>? {
        val raw=sessionPrefs.getString("device-profile-pending","").orEmpty()
        if(raw.isBlank()) return null
        return runCatching {
            val json=org.json.JSONObject(raw)
            var map=buildMap { for(key in json.keys()) json.optString(key).takeIf { it.isNotBlank() }?.let { put(key,it) } }
                .takeIf { it["cdid"].orEmpty().isNotBlank() && it["openudid"].orEmpty().isNotBlank() }
            if(map!=null && map["openudid-source"]=="forced_random") {
                val androidId=readAndroidId()
                if(androidId!=null && androidId!=map["openudid"]) {
                    map=LinkedHashMap(map).apply {
                        put("openudid",androidId)
                        put("openudid-source","android_id")
                    }
                    storePendingProfile(
                        map,
                        sessionPrefs.getString("device-profile-pending-seed","").orEmpty(),
                    )
                }
            }
            map
        }.getOrNull()
    }
    /** 把候选档案连同**它的种子**一起落盘（只在注册成功之后才会变成正式档案）。 */
    private fun storePendingProfile(profile: Map<String,String>,seed: String = "") {
        if(discarding) return
        val json=org.json.JSONObject(); profile.forEach { (key,value) -> json.put(key,value) }
        sessionPrefs.edit().putString("device-profile-pending",json.toString())
            .putString("device-profile-pending-seed",seed).apply()
    }
    /**
     * 注册成功之后把候选档案转正（档案与种指纹一起换）。
     *
     * **必须与 [saveDeviceIdentity] 一起调用**（先这一条、再那一条）：身份是按候选档案注册出来的，
     * 只换身份不换档案，下一次启动 [deviceRegistered] 就会发现「身份与档案对不上」。
     */
    fun commitPendingProfile() {
        if(discarding) return
        val pending=pendingProfile() ?: return
        val seed=sessionPrefs.getString("device-profile-pending-seed","").orEmpty()
        val json=org.json.JSONObject(); pending.forEach { (key,value) -> json.put(key,value) }
        // `-pending-stamp` 是老版本的键（那时用指纹而不是种子），一并清掉，别留孤儿。
        val edit=sessionPrefs.edit().putString("device-profile",json.toString())
            .remove("device-profile-pending").remove("device-profile-pending-seed")
            .remove("device-profile-pending-stamp")
        // 种子与档案必须**一起**换：只换档案不换种子，下一次要派生时用的还是旧那一套。
        if(seed.isNotBlank()) edit.putString("device-seed",seed)
        edit.commit()
        mirror.put("device-profile",json.toString())
        if(seed.isNotBlank()) mirror.put("device-seed",seed)
        mirror.flush()
        cachedProfileStamp=null
        Log.i("HGTV","Pending device profile promoted after successful registration")
    }
    /**
     * 注册请求该用哪一套档案：有待换的候选就用候选，否则用当前档案。
     *
     * 老版本随机档案升级时，请求必须**按候选档案**发出去，平台才会把新的 `device_id` 绑在
     * 新档案上；而其它请求（搜索 / 播放）此刻仍用旧档案，旧身份因此继续可用。
     */
    fun registrationProfile(): Map<String,String> {
        pendingProfile()?.let { return it }
        val official=accountDevice()
        if(official["cdid"].orEmpty().isNotBlank() && official["openudid"].orEmpty().isNotBlank()) {
            return LinkedHashMap(deviceProfile()).apply { putAll(official) }
        }
        return deviceProfile()
    }
    /** 有没有待转正的候选档案（见 [registrationProfile]）。 */
    fun hasPendingProfile(): Boolean = pendingProfile()!=null
    /**
     * 账号参数（个人信息页导入的整套登录态）。
     *
     * 与 [searchToken] 同一个文件、同一套约定：**不进备份、不随记录迁移**，
     * 因为它是平台凭据而不是本机偏好。这里存的是 Cookie 串 + 设备参数 + 过期时间。
     */
    fun account(): AccountCredentials {
        // 重装后这一步就是把 `sessionid` 那一串捞回来的地方：拿到之后
        // [applyAccount] 会直接把它交给仓库，用户不必再登录一次。
        restoreFromMirror()
        // 反过来也要补：老版本装过、镜像里还没有这次会话的，读一次就把它写进镜像，
        // 否则卸载重装丢的就正是这串 `sessionid`。
        mirrorSync("account-cookie",sessionPrefs.getString("account-cookie","").orEmpty())
        mirrorSync("account-device",sessionPrefs.getString("account-device","").orEmpty())
        mirrorSync("account-expires",sessionPrefs.getLong("account-expires",0))
        mirrorSync("session-token",sessionPrefs.getString("session-token","").orEmpty())
        mirrorSync("last-beat-at",sessionPrefs.getLong("last-beat-at",0))
        val cookie = sessionPrefs.getString("account-cookie", "").orEmpty()
        // 读的时候也校一次：老版本可能已经把一份「不同源」的 token 落盘了（换号 / 重扫码之后
        // 服务端仍在回上一份会话的 token）。这种 token 会让平台把有效会话降级成匿名设备，
        // 因此**读出来就丢掉**，由心跳重新换一份回来。判据与 `SessionBridge.decorate` 同一条。
        val sid = com.hgtv.star.core.session.CookieRecord.value(cookie,"sessionid")
        val storedToken = sessionPrefs.getString("session-token", "").orEmpty()
        val token = storedToken.takeIf {
            it.isBlank() || sid.isBlank() || it.length<com.hgtv.star.core.session.SessionBridge.TOKEN_CHECK_LENGTH ||
                it.contains(sid)
        }.orEmpty()
        if(storedToken.isNotBlank() && token.isBlank()) {
            sessionPrefs.edit().remove("session-token").remove("ts-sign").apply()
            mirror.remove("session-token")
            mirror.remove("ts-sign")
        }
        return AccountCredentials(
        cookie = cookie,
        token = token,
        tsSign = sessionPrefs.getString("ts-sign", "").orEmpty(),
        deviceId = accountDevice()["device_id"].orEmpty(),
        installId = accountDevice()["iid"].orEmpty(),
        cdid = accountDevice()["cdid"].orEmpty(),
        openudid = accountDevice()["openudid"].orEmpty(),
        expiresAt = sessionPrefs.getLong("account-expires", 0),
        device = accountDevice()
        )
    }
    fun saveAccount(credentials: AccountCredentials) {
        if(discarding) return
        // 换会话（换号 / 重新扫码）时先把**上一份** token 抹掉。
        //
        // 不抹的后果是实测过的：token 与 cookie 不同源时，平台不是忽略 token，而是把整份
        // 有效会话降级成匿名设备（「用户20114780」）——「登录成功但立刻报状态无效」。
        // 这里按 `sessionid` 判是不是同一份会话；同一份会话下 token 为空则保留（空值不该
        // 抹掉好 token，见 `AppSessionStore.saveToken` 的说明）。
        val previousSid = com.hgtv.star.core.session.CookieRecord
            .value(sessionPrefs.getString("account-cookie", "").orEmpty(), "sessionid")
        val nextSid = com.hgtv.star.core.session.CookieRecord.value(credentials.cookie.trim(), "sessionid")
        if(previousSid.isNotBlank() && nextSid.isNotBlank() && previousSid!=nextSid) {
            sessionPrefs.edit().remove("session-token").remove("ts-sign").apply()
            mirror.remove("session-token")
            mirror.remove("ts-sign")
        }
        sessionPrefs.edit()
            .putString("account-cookie", credentials.cookie.trim())
            .putLong("account-expires", credentials.expiresAt)
            .apply()
        // token 与它自己的签名一起落盘：心跳、拦截器与 `AppSessionStore` 读的是这两个键。
        // 空值不覆盖——一次异常响应不该把好会话的 token 抹掉（见 `AppSessionStore.saveToken`）。
        credentials.token.takeIf { it.isNotBlank() }?.let { token ->
            sessionPrefs.edit().putString("session-token",token).apply()
            mirror.put("session-token",token)
        }
        credentials.tsSign.takeIf { it.isNotBlank() }?.let { sign ->
            sessionPrefs.edit().putString("ts-sign",sign).apply()
            mirror.put("ts-sign",sign)
        }
        // 档案单独存一份 JSON：字段比 [AccountCredentials] 那几个多（机型 / 系统 / klink_egdi …），
        // 展开成一条条 pref 不划算，签名要的是整份。
        saveAccountDevice(credentials.device)
        // 会话一有新值就覆盖外部镜像（登录成功 / 导入凭据 / 换号都走这里）：
        // 外部那份**永远是最新的**，重装后捞回来就能直接用，不会拿着过期的会话去请求。
        if(credentials.cookie.isNotBlank()) mirror.put("account-cookie",credentials.cookie.trim())
        if(credentials.expiresAt>0) mirror.put("account-expires",credentials.expiresAt)
    }
    fun clearAccount() {
        if(discarding) return
        sessionPrefs.edit().remove("account-cookie").remove("account-device")
            .remove("account-expires").remove("session-token").remove("ts-sign")
            .remove("last-beat-at").apply()
        // 外部镜像也要一起抹掉这三项：只清本机的话，**下次启动会把刚退掉的账号又救回来**
        // （用户点了退出登录，重开却还是登录状态）。设备身份不受影响，继续留着。
        MIRROR_ACCOUNT_KEYS.forEach { key -> mirror.remove(key) }
        // 账号数据快照跟着一起清：留着它下次登录（甚至是**另一个**账号）就会先把别人的
        // 收藏 / 历史铺出来，再等刷新纠正——那几秒钟界面上是错的内容。
        clearAccountCache()
    }
    /**
     * 设备档案：**只能从设备上读**的那批参数（`device_id` / 机型 / 系统版本 / cdid …）。
     *
     * 存成一条 JSON，字段名与 `tools/login-lab/dump_device.py` 产出的
     * `device_profile.json` 一致，导入那个文件即可整份灌进来。
     */
    fun accountDevice(): Map<String,String> {
        val raw = sessionPrefs.getString("account-device", "").orEmpty()
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val json = org.json.JSONObject(raw)
            buildMap { for (key in json.keys()) json.optString(key).takeIf { it.isNotBlank() }?.let { put(key, it) } }
        }.getOrDefault(emptyMap())
    }
    fun saveAccountDevice(device: Map<String,String>) {
        if(discarding) return
        val clean = device.filterValues { it.isNotBlank() }
        if (clean.isEmpty()) return
        val json = org.json.JSONObject()
        clean.forEach { (key, value) -> json.put(key, value) }
        sessionPrefs.edit().putString("account-device", json.toString()).apply()
        // 这份档案是**写接口一致性判定**用的（平台拿它比对请求头里的机型 / cdid 那一套），
        // 重装后会话能不能真的用起来就看它有没有跟着回来，所以一并镜像。
        mirror.put("account-device",json.toString())
    }
    var autoNext: Boolean
        get() = state().settings.autoNext
        set(value) { if (value != autoNext) { state().settings = state().settings.copy(autoNext = value); put("autoNext", value) } }
    var playbackSpeed: Float
        get() = state().settings.speed
        set(value) { val next = PlaybackSpeed.normalize(value); if (next != playbackSpeed) { state().settings = state().settings.copy(speed = next); put("playbackSpeed", next) } }
    var frameMode: VideoFrameMode
        get() = state().settings.frame
        set(value) { if (value != frameMode) { state().settings = state().settings.copy(frame = value); put("frameMode", value.name) } }
    /**
     * 视频解码方式：硬件解码（默认）/ 软件解码。
     *
     * 只在**新建播放器**时读取；改档之后由 `applyDecoderMode` 立刻重建一次播放器，
     * 因此正在看的这一集也会马上换过去，不需要退出播放界面。
     */
    var decoderMode: VideoDecoderMode
        get() = state().settings.decoder
        set(value) { if (value != decoderMode) { state().settings = state().settings.copy(decoder = value); put("decoderMode", value.name) } }
    /**
     * 搜索页的数据源（App 接口 / 官网）。
     *
     * 只影响搜索（结果、联想词、热搜词），改完下一次搜索立刻生效；
     * 已经显示着的那一页结果不会被就地替换——用户按「搜索」才换。
     */
    var searchSource: SearchSource
        get() = state().settings.searchSource
        set(value) { if (value != searchSource) { state().settings = state().settings.copy(searchSource = value); put("searchSource", value.storedValue) } }
    /**
     * 每一行放几张海报卡：4 / 5 / 6 / 7 / 8（默认 6，与旧版一致）。
     *
     * **全局生效**：首页剧架、榜单 / 分类 / 搜索 / 收藏 / 历史 / 我的、详情页相关剧集
     * 共用这一份设置；越界值一律夹回 4..6。
     */
    var homeColumns: Int
        get() = state().settings.homeColumns.coerceIn(4, 8)
        set(value) {
            val next = value.coerceIn(4, 8)
            if (next != homeColumns) { state().settings = state().settings.copy(homeColumns = next); put("homeColumns", next) }
        }
    /**
     * 手机 / 平板的海报列表基准列数（1–3，默认 2）。
     *
     * 与电视的 [homeColumns]（4..8）是两个键：手机上 2 列是「封面够大、标题还能读全」
     * 的默认档；横屏实际显示时映射为 4–6 列。
     * 坏值夹进 1..3，设置页不会出现一个选不中的状态。
     */
    var gridColumns: Int
        get() = state().settings.gridColumns.coerceIn(1, 3)
        set(value) {
            val next = value.coerceIn(1, 3)
            val current = state().settings.gridColumns
            if (next != current) { state().settings = state().settings.copy(gridColumns = next); put("gridColumns", next) }
        }
    /**
     * 列表页「回到顶部」浮层的显隐方式（[BACK_TO_TOP_MODES] 四档，默认始终显示）。
     *
     * 只管**列表页**那枚浮层：首页剧架、详情页、播放页本来就不挂它（见 `updateBackToTop`）。
     * 坏值一律回落到「始终显示」，设置页因此不会出现一个选不中任何一项的状态。
     */
    var backToTop: String
        get() = state().settings.backToTop.takeIf { it in BACK_TO_TOP_MODES } ?: BACK_TO_TOP_DEFAULT
        set(value) {
            val next = value.takeIf { it in BACK_TO_TOP_MODES } ?: BACK_TO_TOP_DEFAULT
            if (next != backToTop) { state().settings = state().settings.copy(backToTop = next); put("backToTop", next) }
        }
    /**
     * 播放画面左上角常显的四项信息：剧集标题 / 当前集数 / 时间 / 网速。
     *
     * 四项各自独立开关（设置页「界面设置 → 播放显示」）。前两项与时间默认开启，
     * 网速默认关闭——它是排障用的读数，平时不该占着画面。
     */
    var playerTitle: Boolean
        get() = state().settings.playerTitle
        set(value) { if (value != playerTitle) { state().settings = state().settings.copy(playerTitle = value); put("playerTitle", value) } }
    var playerEpisode: Boolean
        get() = state().settings.playerEpisode
        set(value) { if (value != playerEpisode) { state().settings = state().settings.copy(playerEpisode = value); put("playerEpisode", value) } }
    var playerClock: Boolean
        get() = state().settings.playerClock
        set(value) { if (value != playerClock) { state().settings = state().settings.copy(playerClock = value); put("playerClock", value) } }
    var playerSpeed: Boolean
        get() = state().settings.playerSpeed
        set(value) { if (value != playerSpeed) { state().settings = state().settings.copy(playerSpeed = value); put("playerSpeed", value) } }
    /**
     * 播放设置 / 选集面板「无操作几秒自动消失」（[PLAYER_PANEL_TIMEOUT_RANGE]，默认 6 秒）。
     *
     * 到点之后的行为与按返回键一致：播放设置收起、选集面板收起并把焦点还回控制条。
     */
    var playerPanelTimeout: Int
        get() = state().settings.playerPanelTimeout
        set(value) {
            val next=value.coerceIn(PLAYER_PANEL_TIMEOUT_RANGE)
            if (next != playerPanelTimeout) { state().settings = state().settings.copy(playerPanelTimeout = next); put("playerPanelTimeout", next) }
        }
    /**
     * 快进 / 快退单次跳过的秒数（2..20，默认 10）。
     *
     * 播放界面按左右键时的跳跃步长（见 `com.hgtv.star.core.KeyMap.SEEK_FORWARD`）。
     * 长按连按的加速（5 次之后每次 30 秒）仍然按固定档走，这一项只决定**单次**的步长，
     * 因此调到 20 秒时连按依然是先 20 秒、之后按连按规则加快。
     */
    var seekStep: Int
        get() = state().settings.seekStep
        set(value) {
            val next=value.coerceIn(2,20)
            if (next != seekStep) { state().settings = state().settings.copy(seekStep = next); put("seekStep", next) }
        }
    /**
     * 画面缩放百分比（1..100，默认 100 = 铺满）。
     *
     * 只缩播放画面本身（`playerView` 的框），弹幕层仍然是整屏坐标，
     * 因此缩小画面时弹幕照旧铺满屏幕（见 `applyVideoScale`）。
     */
    var videoScale: Float
        get() = state().settings.videoScale.coerceIn(1f,100f)
        set(value) {
            val next = value.coerceIn(1f,100f).let { Math.round(it).toFloat() }
            if (next != videoScale) { state().settings = state().settings.copy(videoScale = next); put("videoScale", next) }
        }
    /**
     * 竖屏短剧全屏（默认关闭）。
     *
     * 打开后，**竖屏片源**（短剧）在横屏电视上会被**转 90°**：9:16 旋转后就是 16:9，
     * 正好铺满屏幕、不裁掉任何内容（见 `autoFullscreenRotated`）。横屏片源不受影响，
     * 仍然用用户在「画面模式」里选的那一档。
     */
    var autoFullscreen: Boolean
        get() = state().settings.autoFullscreen
        set(value) { if (value != autoFullscreen) { state().settings = state().settings.copy(autoFullscreen = value); put("autoFullscreen", value) } }
    /**
     * 选集一页放多少集（10 / 20 / 30 / 40 / 50，默认 20）。
     *
     * 简介页与播放界面的选集面板共用这一份：它决定分页行怎么切段
     *（`1-20 / 21-40 …`）、网格铺几行（固定 10 列 × 一页集数 / 10 行）。
     * 落盘即生效——下次打开选集就是新的分段，不需要重启。
     */
    var episodePageSize: Int
        get() = state().settings.episodePageSize
        set(value) {
            val next = com.hgtv.star.core.EpisodePageSize.normalize(value)
            if (next != episodePageSize) { state().settings = state().settings.copy(episodePageSize = next); put("episodePageSize", next) }
        }
    /**
     * 分页边缘翻页（默认开启）。
     *
     * 开启：选集网格左右到本行两端、下到最后一行时**翻页**（跳到相邻那一页）。
     * 关闭：边缘不再翻页，右端按右键选下一集、左端按左键选上一集（跨页自动跟过去），
     * 换页改由分页行自己选（见 `EpisodeGrid.pageKey`）。
     *
     * 落盘即生效：下一次进简介页 / 打开选集面板就按新值走，已经摆好的那一屏不重建。
     */
    var episodeEdgeTurn: Boolean
        get() = state().settings.episodeEdgeTurn
        set(value) { if (value != episodeEdgeTurn) { state().settings = state().settings.copy(episodeEdgeTurn = value); put("episodeEdgeTurn", value) } }
    /**
     * 用户主动要求「减少动画、优先流畅」（默认关闭）。
     *
     * 与设备判据是**或**的关系：低配机一律按低配档跑，与这一项无关；
     * 非低配机打开它之后，焦点缩放动画、柔光位图、海报井暗角与跑马灯全部关掉，
     * 效果与低配机完全一致（见 `MainActivity.applyPerformanceProfile`）。
     *
     * 改完要整页重建一次才彻底生效：卡片在构造时就把「是否跑马灯」捉了一份
     * （见 `PosterCard.marqueeOn`）。
     */
    var reduceAnim: Boolean
        get() = state().settings.reduceAnim
        set(value) { if (value != reduceAnim) { state().settings = state().settings.copy(reduceAnim = value); put("reduceAnim", value) } }
    /**
     * 调试模式：打开后各页面右上角常驻一块现场面板（见 `DebugOverlay.kt`）。
     *
     * 默认关闭，**不进备份、不随记录迁移**：它是排障开关，不是使用偏好。
     * 关掉时那块面板立刻摘掉，刷新定时器也一起停（见 `syncDebugOverlay`）。
     */
    var debugMode: Boolean
        get() = state().settings.debugMode
        set(value) { if (value != debugMode) { state().settings = state().settings.copy(debugMode = value); put("debugMode", value) } }
    /**
     * 顶部导航栏里**被隐藏**的圆形入口（`recommend` / `search` / `category` / `rank` /
     * `profile`）。
     *
     * 每一项单独开关；空集合 = 全部显示（与旧版一致）。落盘是一个字符串集合。
     */
    var hiddenNav: Set<String>
        // 只保留白名单里的几枚：设置**始终显示**，
        // 旧版本磁盘上残留的这几个 key 读回来直接滤掉。
        get() = state().settings.hiddenNav.split(',').filter { it in NAV_KEYS }.toSet()
        private set(value) {
            val clean = value.filter { it in NAV_KEYS && it != "settings" }.toSet()
            if (clean == hiddenNav) return
            state().settings = state().settings.copy(hiddenNav = clean.joinToString(","))
            put("hiddenNav", clean)
        }
    /** 某一枚圆形入口是否隐藏。 */
    fun navHidden(key: String): Boolean = key in hiddenNav
    /**
     * 单独显示 / 隐藏某一枚圆形入口。
     *
     * 「推荐」这一枚与「打开应用是否进入推荐」**联动**：关掉入口就等于关掉启动进入推荐
     * （否则导航栏里找不到它、打开应用却仍然跳进推荐，两处说法自相矛盾）。
     * 反向不联动：启动项关掉时入口仍然留着，用户随时可以手动点进去刷。
     */
    fun setNavHidden(key: String, hidden: Boolean) {
        if (key !in NAV_KEYS) return
        hiddenNav = if (hidden) hiddenNav + key else hiddenNav - key
        if (key == NAV_KEY_RECOMMEND && hidden && state().settings.launchRecommend) {
            // 直接落盘，不走 setter：getter 已经因为「入口隐藏」返回 false，
            // 走 setter 会被那句 `value == launchRecommend` 提前挡掉，磁盘上仍是 true。
            state().settings = state().settings.copy(launchRecommend = false)
            put("launchRecommend", false)
        }
    }
    /**
     * 打开应用后是否直接进入推荐页。
     *
     * 与导航栏「推荐」入口联动：入口被隐藏时这里恒为 false（[setNavHidden] 会把开关关掉，
     * 这里再兜一次，避免磁盘上残留「入口隐藏 + 启动进推荐」这种自相矛盾的组合）。
     */
    var launchRecommend: Boolean
        get() = state().settings.launchRecommend && NAV_KEY_RECOMMEND !in hiddenNav
        set(value) {
            if (value == launchRecommend) return
            // 反向联动：打开「启动进推荐」时，导航栏那枚入口也一并恢复显示，
            // 否则用户打开开关、冷启动进了推荐，退出后却在导航栏里找不到回去的入口。
            if (value && NAV_KEY_RECOMMEND in hiddenNav) hiddenNav = hiddenNav - NAV_KEY_RECOMMEND
            state().settings = state().settings.copy(launchRecommend = value)
            put("launchRecommend", value)
        }
    /**
     * 推荐页是否允许左右滑动切换体裁（漫剧 / 真人 / AI 剧，默认开启）。
     *
     * 关掉之后只能点顶部那三个体裁 tab 换流：pager 的横向手势整体关掉，
     * 手指在视频上横向划一下不再整页翻走（竖屏短剧一屏一集，误触很常见）。
     * 播放设置面板开着时本来就会临时锁住横向翻页，两件事互不影响（见 `PhoneRecommendPage`）。
     */
    var recommendSwipe: Boolean
        get() = state().settings.recommendSwipe
        set(value) { if (value != recommendSwipe) { state().settings = state().settings.copy(recommendSwipe = value); put("recommendSwipe", value) } }
    /**
     * 首页七栏里**被隐藏**的那些：本机记录三栏（`history` / `favorites` / `later`）+
     * 热播四栏（`rank-comic` / `rank-real` / `rank-ai` / `rank-new`）。
     *
     * 与 [hiddenNav] 不是一组：那些管的是顶部导航的页面入口，这些管的是首页铺不铺那一栏。
     * 空集合 = 七栏全显示（与旧版一致）。关掉只让首页不建那一栏（行末「查看全部」也一起没有）；
     * 本机记录本身一份不删，热播栏关掉也不影响榜单页与推荐页。
     */
    var hiddenHome: Set<String>
        get() = state().settings.hiddenHome.split(',').filter { it in HOME_KEYS }.toSet()
        private set(value) {
            val clean = value.filter { it in HOME_KEYS }.toSet()
            if (clean == hiddenHome) return
            state().settings = state().settings.copy(hiddenHome = clean.joinToString(","))
            put("hiddenHome", clean)
        }
    /** 首页某一栏是否隐藏。 */
    fun homeHidden(key: String): Boolean = key in hiddenHome
    /** 单独显示 / 隐藏首页某一栏（只改首页，不动记录本身）。 */
    fun setHomeHidden(key: String, hidden: Boolean) {
        if (key !in HOME_KEYS) return
        hiddenHome = if (hidden) hiddenHome + key else hiddenHome - key
    }
    /**
     * 推荐流的续流游标：`session_id` + `next_offset`（见 `ContentRepository.recommendFeed`）。
     *
     * 为什么要落盘：接口是**有状态**的，`session_id` 传空、`offset` 每次归零，服务端
     * 永远回同一套默认列表（实测连续 8 次 `offset=0` 返回的 20 条完全一致）。
     * 把游标记在本机，下次打开应用进推荐就接着上次的位置往下取，才是「新的一批」。
     *
     * 不进备份：它描述的是「这台设备刷到哪儿了」，换一台电视应当从头开始。
     */
    var recommendCursor: String
        // 读的是 [loadAsync] 那一次读盘时抄下来的值：主线程上不碰 SharedPreferences。
        get() = recommendCursorCached
        set(value) {
            val clean=value.take(120).filter { !it.isISOControl() }
            if(clean==recommendCursorCached) return
            recommendCursorCached=clean
            put("recommendCursor", clean)
        }
    @Volatile private var recommendCursorCached: String=""
    /**
     * 手机端**按体裁分开**的推荐续流游标（`体裁|session|offset`，多条用 `;` 连接）。
     *
     * 电视只有一条推荐流，`recommendCursor` 一份就够；手机是「漫剧 / 真人 / AI剧」三条并列，
     * 共用一份会互相覆盖（翻到另一类会带着上一类的 offset 去取）。
     * 解析 / 合并的规则在 `:core` 的 [com.hgtv.star.core.FeedCursor]（纯逻辑、有单测）。
     *
     * 与 [recommendCursor] 同一类本机现场：不进备份、不做迁移，换一台设备从头开始刷。
     */
    var phoneFeedCursor: String
        get() = phoneFeedCursorCached
        set(value) {
            val clean = value.take(600).filter { !it.isISOControl() }
            if (clean == phoneFeedCursorCached) return
            phoneFeedCursorCached = clean
            put("phoneFeedCursor", clean)
        }
    @Volatile private var phoneFeedCursorCached: String=""
    /**
     * 推荐页**已经推给用户看过**的剧 id（按推出的先后排队）。
     *
     * 为什么需要它：推荐流本身不记「这台设备刷过什么」——`saveProgress` 里有一句
     * `if(screen=="recommend") return`，推荐页刻意不写观看历史；于是上下刷过十几部之后
     * 再进推荐，服务端回的同一批里有一大半是刚看过的。这里补一份「推荐页看过」的记录，
     * 由 `MainActivity.loadRecommendPage` 用它过滤 / 判断「这一页是不是又转回老地方」。
     *
     * 它只是**去重的提示**，不是记录：不参与首页任何一栏，不进备份（换一台设备应该从头推荐），
     * 清缓存时随 `shared_prefs` 整个目录一起删（见 `LocalReset.wipe`），不需要单独处理。
     * 只在主线程读写（取推荐页的回调都在主线程）。
     */
    private val recommendMarks = RecommendMarks()
    /** 推荐页推过的剧 id 快照（调用方只读，不要改）。 */
    fun recommendSeen(): Set<String> = recommendMarks.ids()
    /**
     * 这个剧名（归一化后，剥季号 / 去标点）是不是在窗口里。
     *
     * 挡的是文档 §5「原因 3 / 原因 9」那两种重复：同一部剧换了个切片、同一 IP 换了个版本，
     * `series_id` 不同，只按 id 去重拦不住。
     */
    fun recommendSeenTitle(title: String): Boolean {
        val key = RecommendFlow.titleKey(title)
        return key.isNotEmpty() && recommendMarks.contains(key)
    }
    /**
     * 记下这一批已经推给用户的剧（id + 剧名，先进先出 + 7 天过期，见 [RecommendFlow]）。
     *
     * 只落一行逗号分隔的 `键@失效时刻`：它是本机现场，不是用户数据，因此不进备份、不做迁移。
     */
    fun markRecommended(ids: Collection<String>, titles: Collection<String> = emptyList()) {
        if(ids.isEmpty() && titles.isEmpty()) return
        // 没有真正改动（同一条已经记过、且失效时刻不更晚）就不落盘：来回划同一部时不该反复写 30KB。
        var changed=recommendMarks.mark(ids, RecommendFlow.TTL_SEEN)
        if(titles.isNotEmpty()) {
            val keys=titles.mapNotNull { RecommendFlow.titleKey(it).takeIf(String::isNotEmpty) }
            if(recommendMarks.mark(keys, RecommendFlow.TTL_SEEN)) changed=true
        }
        if(changed) put("recommendSeen", recommendMarks.encode())
    }
    /** 一进来就划走：冷却 [RecommendFlow.TTL_SKIP]（只延长、不缩短，见 [RecommendMarks.mark]）。 */
    fun markRecommendSkipped(id: String) {
        if(id.isEmpty()) return
        if(recommendMarks.mark(listOf(id), RecommendFlow.TTL_SKIP)) put("recommendSeen", recommendMarks.encode())
    }
    /** 真的看了（停留够久 / 进了正片）：冷却 [RecommendFlow.TTL_DONE]。 */
    fun markRecommendWatched(id: String) {
        if(id.isEmpty()) return
        if(recommendMarks.mark(listOf(id), RecommendFlow.TTL_DONE)) put("recommendSeen", recommendMarks.encode())
    }
    /**
     * **已经追完**的剧（看到最后一集，且那一集播完）。
     *
     * 文档 §4.2 的第一层硬过滤：「已完播的剧」不该再以「新内容」的身份出现在推荐流里——
     * 用户刷到一部自己刚看完的剧，读到的就是「推荐不准」。
     * 判据只用本机观看记录：`episodeIndex` 已经是最后一集、并且那一集标记为播完。
     * 只看了一部分的剧**不**进这个集合（那是「接着看」，归正式播放的续播，不该被推荐页挡掉）。
     */
    fun recommendFinished(): Set<String> {
        val rows=state().history()
        if(rows.isEmpty()) return emptySet()
        val out=HashSet<String>(4)
        rows.forEach { row ->
            val total=row.series.episodeCount
            if(row.completed && total>0 && row.episodeIndex>=total-1) out+=row.series.id
        }
        return out
    }
    /**
     * 组装请求里的 `filter_ids`（本机已推过的剧 id，最新的在前面的那一批）。
     *
     * 空串 = 不带（服务端按它自己的曝光窗口去重），与旧版请求逐字节一致。
     */
    fun recommendFilterIds(limit: Int = RecommendFlow.FILTER_IDS_LIMIT): String =
        if(recommendFilterIdsOff) "" else recommendMarks.filterIds(limit)
    /**
     * 这台设备上「回传 `filter_ids`」是否已经确认不行（服务端照旧回空页 / 回错误码）。
     *
     * 一旦确认就**一直不带**：宁可少这一层去重，也不能让推荐页整屏「推荐暂时取不到」。
     * 与 `recommendSeen` 同属本机现场，不进备份。
     */
    var recommendFilterIdsOff: Boolean
        get() = recommendFilterIdsOffCached
        set(value) {
            if(value==recommendFilterIdsOffCached) return
            recommendFilterIdsOffCached=value
            put("recommendFilterIdsOff", value)
        }
    @Volatile private var recommendFilterIdsOffCached=false
    /**
     * 推荐范围：**被关掉的大类**（`comic_series` / `ai_series` / `short_play`）。
     *
     * 空集合 = 三类全开，也就是推荐流不带 `genre`（与旧版请求完全一致）。
     * 设置页「推荐范围」那三枚开关改的就是这里：关掉某一类之后，
     * 推荐页取流只带剩下的大类，不再往回取那一类的内容（见 [recommendGenres]）。
     *
     * 存「关掉的那些」而不是「打开的那些」：默认全开时磁盘上就是一个空串，
     * 老版本升级上来不需要迁移，也不用区分「磁盘上没有这个键」和「用户把三类全关了」。
     *
     * **至少保留一类**（[setRecommendGenre] 会拦住把最后一类也关掉的操作）：
     * 三类全关等于没有推荐范围，与「全部体裁」的语义正好相反，容易读成同一个东西。
     *
     * 不进备份：它描述的是「这台机器想刷什么」，与 [recommendCursor] 属同一类本机现场。
     */
    var recommendGenreOff: Set<String>
        // 磁盘上是一行逗号分隔的标识；读的时候按白名单过滤，坏值直接当没关。
        get() = recommendGenreOffCached.split(',').filter { it in CategoryGenres.recommendableIds }.toSet()
        private set(value) {
            val clean=value.filter { it in CategoryGenres.recommendableIds }.toSet()
            // 三类全关没有任何意义（等于不筛体裁），一律按「没改」处理。
            if(clean.size>=CategoryGenres.recommendableIds.size) return
            if(clean==recommendGenreOff) return
            val joined=CategoryGenres.recommendableIds.filter { it in clean }.joinToString(",")
            recommendGenreOffCached=joined
            put("recommendGenreOff", joined)
        }
    @Volatile private var recommendGenreOffCached: String=""
    /** 某一枚大类是否还在推荐范围里。 */
    fun recommendGenreEnabled(id: String): Boolean = id !in recommendGenreOff
    /**
     * 打开 / 关掉某一枚大类。
     *
     * 关掉最后一类会被 [recommendGenreOff] 的 setter 挡回去（它要求至少留一类），
     * 界面据此把开关按回「开」并给一句提示。
     */
    fun setRecommendGenre(id: String, enabled: Boolean) {
        if(id !in CategoryGenres.recommendableIds) return
        recommendGenreOff = if(enabled) recommendGenreOff - id else recommendGenreOff + id
    }
    /**
     * 推荐流要带的体裁：**三类全开时返回空**（= 不筛 `genre`，与旧版请求逐字节一致）。
     *
     * 返回的顺序固定按 [CategoryGenres.recommendableIds]：请求体是 JSON，
     * 顺序稳定下来，日志里比对「这次带了哪几类」时不必再算一遍集合差异。
     */
    fun recommendGenres(): List<String> {
        val off=recommendGenreOff
        return if(off.isEmpty()) emptyList() else CategoryGenres.recommendableIds.filter { it !in off }
    }
    /**
     * 推荐范围的**受众**：`""` = 不限（男女都推），否则是分类接口 `gender` 行里选中的
     * `selector_item_id`（男频 / 女频各一个，由接口给）。
     *
     * 为什么不写死标识：受众行的可选值来自接口的 `selector_rows`，标识是平台自己那一套
     * （各体裁还可能不同）。写死一个猜出来的串，猜错就是「一条都推不出来」，
     * 比没有这一项更糟。因此这里只存**用户选中的那个 id**，候选值由设置页
     * 现取一次面板（见 `MainActivity.loadGenderOptions`）再建芯片。
     *
     * 与 [recommendGenreOff] 一样是本机现场，不进备份。
     */
    var recommendGender: String
        get() = recommendGenderCached
        set(value) {
            val clean=value.trim().take(64).filter { !it.isISOControl() }
            if(clean==recommendGenderCached) return
            recommendGenderCached=clean
            put("recommendGender", clean)
        }
    @Volatile private var recommendGenderCached: String=""
    /**
     * 设置页「使用设备」：`auto` / `phone` / `tablet` / `tv`。
     *
     * 自动模式按打分判定（见 `com.hgtv.star.shared.compat.detectDeviceForm`）；用户手动选了一档
     * 就以他选的为准——触屏手势层建不建、「竖屏短剧全屏」往哪边转，都由它决定。
     *
     * 不进备份：它描述的是「这台机器是什么」，换一台机器应该重新自动判定，
     * 与 [recommendCursor]、`debugMode` 同一类（都是本机现场，不是用户偏好）。
     */
    var deviceForm: String
        get() = deviceFormCached
        set(value) {
            val clean=when(value) { "phone","tablet","tv" -> value; else -> "auto" }
            if(clean==deviceFormCached) return
            deviceFormCached=clean
            put("deviceForm", clean)
        }
    /**
     * 设备类型要立刻重启应用，必须先同步落盘，不能等 [writes] 的 250ms 延迟。
     *
     * 返回 false 表示写盘失败，此时调用方不应重启。
     */
    fun setDeviceFormNow(value: String): Boolean {
        val clean=when(value) { "phone","tablet","tv" -> value; else -> "auto" }
        val previous=deviceFormCached
        deviceFormCached=clean
        val saved=runCatching {
            commit(prefs, mapOf("deviceForm" to { clean }))
        }.getOrDefault(false)
        if(!saved) deviceFormCached=previous
        return saved
    }
    @Volatile private var deviceFormCached: String="auto"
    var danmakuMode: DanmakuMode
        get() = state().settings.danmaku
        set(value) { if (value != danmakuMode) { state().settings = state().settings.copy(danmaku = value); put("danmakuMode", value.storedValue) } }
    /** 弹幕字号缩放：1f = 原始大小，滑动条按 [DanmakuStyle.SCALE_STEP] 增减。 */
    var danmakuScale: Float
        get() = state().settings.danmakuScale
        set(value) { val next = DanmakuStyle.scale(value); if (next != danmakuScale) { state().settings = state().settings.copy(danmakuScale = next); put("danmakuScale", next) } }
    /** 弹幕文字不透明度：1f = 完全不透明，最低 [DanmakuStyle.ALPHA_MIN]。 */
    var danmakuAlpha: Float
        get() = state().settings.danmakuAlpha
        set(value) { val next = DanmakuStyle.alpha(value); if (next != danmakuAlpha) { state().settings = state().settings.copy(danmakuAlpha = next); put("danmakuAlpha", next) } }
    /** 弹幕移动速度档位（慢速 / 标准 / 快速）：影响整体通行速度，不影响弹幕之间的先后关系。 */
    var danmakuSpeed: DanmakuSpeed
        get() = state().settings.danmakuSpeed
        set(value) { if (value != danmakuSpeed) { state().settings = state().settings.copy(danmakuSpeed = value); put("danmakuSpeed", value.storedValue) } }
    /**
     * 相同弹幕是否聚合（默认开启）。
     *
     * 开启时同一句弹幕在时间窗口内合并成一条、带 `×N` 数量标记；关闭时逐条原样滚动。
     */
    var danmakuMerge: Boolean
        get() = state().settings.danmakuMerge
        set(value) { if (value != danmakuMerge) { state().settings = state().settings.copy(danmakuMerge = value); put("danmakuMerge", value) } }
    /**
     * 相同弹幕达到几条才聚合（[DANMAKU_MERGE_MIN_RANGE]，默认 2）。
     *
     * 不到这个条数的同一句弹幕照常逐条滚动、不带 `×N`；达到才合并成一条并可能上中央。
     */
    var danmakuMergeMin: Int
        get() = state().settings.danmakuMergeMin
        set(value) {
            val next=value.coerceIn(DANMAKU_MERGE_MIN_RANGE)
            if (next != danmakuMergeMin) { state().settings = state().settings.copy(danmakuMergeMin = next); put("danmakuMergeMin", next) }
        }
    /**
     * 聚合弹幕特效总开关（默认开启）。
     *
     * 控制计数放大、渐热颜色和 `×20` 后的冲击波；聚合规则、展示时长与槽位替换不受影响。
     */
    var danmakuEffects: Boolean
        get() = state().settings.danmakuEffects
        set(value) { if (value != danmakuEffects) { state().settings = state().settings.copy(danmakuEffects = value); put("danmakuEffects", value) } }
    /**
     * 播放界面的按键绑定（上 / 下 / 左 / 右 / 菜单 / 确定）。
     *
     * 只存改过的键（见 [KeyMap.encode]），因此默认状态下磁盘上就是一个空串；
     * 改一次就落一次盘，下一部剧、下一次开应用都还是这一套手感。
     */
    var keyMap: KeyMap
        get() = KeyMap.decode(state().settings.keys)
        set(value) {
            val next = value.encode()
            if (next == keyMap.encode()) return
            state().settings = state().settings.copy(keys = next)
            put("keymap", next)
        }
    fun snapshot() = state().snapshot()
    fun restore(incoming: BackupData, restoreSettings: Boolean) {
        val merged = LibraryBackup.merge(snapshot(), incoming, restoreSettings)
        index = LibraryIndex(merged, state().updates())
        persist("favorites", "later", "progress", "watched", "hidden", "searches", "favoriteUpdates", "historyDeleted")
        writes.submit(mapOf("quality" to { merged.settings.quality }, "playbackSpeed" to { merged.settings.speed },
            "autoNext" to { merged.settings.autoNext },
            "frameMode" to { merged.settings.frame.name }, "decoderMode" to { merged.settings.decoder.name },
            "danmakuMode" to { merged.settings.danmaku.storedValue },
            "danmakuScale" to { merged.settings.danmakuScale }, "danmakuAlpha" to { merged.settings.danmakuAlpha },
            "danmakuSpeed" to { merged.settings.danmakuSpeed.storedValue },
            "danmakuMerge" to { merged.settings.danmakuMerge },
            "danmakuMergeMin" to { merged.settings.danmakuMergeMin.coerceIn(DANMAKU_MERGE_MIN_RANGE) },
            "danmakuEffects" to { merged.settings.danmakuEffects },
            "searchSource" to { merged.settings.searchSource.storedValue },
            "homeColumns" to { merged.settings.homeColumns.coerceIn(4, 8) },
            "gridColumns" to { merged.settings.gridColumns.coerceIn(1, 3) },
            "playerTitle" to { merged.settings.playerTitle },
            "playerEpisode" to { merged.settings.playerEpisode },
            "playerClock" to { merged.settings.playerClock },
            "playerSpeed" to { merged.settings.playerSpeed },
            "playerPanelTimeout" to { merged.settings.playerPanelTimeout.coerceIn(PLAYER_PANEL_TIMEOUT_RANGE) },
            "seekStep" to { merged.settings.seekStep.coerceIn(2, 20) },
            "videoScale" to { merged.settings.videoScale.coerceIn(1f, 100f) },
            "autoFullscreen" to { merged.settings.autoFullscreen },
            "episodePageSize" to { merged.settings.episodePageSize },
            "episodeEdgeTurn" to { merged.settings.episodeEdgeTurn },
            "backToTop" to { merged.settings.backToTop },
            "hiddenNav" to { merged.settings.hiddenNav.split(',').filter { it.isNotBlank() }.toSet() },
            "debugMode" to { merged.settings.debugMode },
            "launchRecommend" to { merged.settings.launchRecommend },
            "recommendSwipe" to { merged.settings.recommendSwipe },
            "hiddenHome" to { merged.settings.hiddenHome.split(',').filter { it in HOME_KEYS }.toSet() },
            "reduceAnim" to { merged.settings.reduceAnim },
            "keymap" to { merged.settings.keys }))
        flush()
    }
    /** Requests prompt background commit, without lifecycle-thread disk waits. */
    fun flush() { writes.flush(); homeWrites.flush(); accountWrites.flush(); mirror.flush() }
    /**
     * 清除本机数据专用：丢掉所有待写快照、停掉写线程，**不再落盘**。
     *
     * 不能走 [close]：那一条会先把待写内容冲盘，而紧接着整份数据就要被删掉，
     * 冲盘等于把刚删的文件又建回来。调用之后本对象不可再用（进程随即结束）。
     */
    fun discardPendingWrites() {
        if (closed) return
        discarding = true
        closed = true
        readyCallbacks.clear(); errorCallbacks.clear()
        loader.shutdownNow()
        writes.discard(); homeWrites.discard(); accountWrites.discard()
        mirror.discard()
    }
    /** Drain pending commits after Activity destruction; abrupt process death is still a boundary. */
    fun close() {
        if (closed) return
        closed = true
        readyCallbacks.clear(); errorCallbacks.clear()
        loader.shutdown()
        synchronized(retiredWrites) {
            writes.close(); homeWrites.close(); accountWrites.close()
            retiredWrites += writes; retiredWrites += homeWrites; retiredWrites += accountWrites
            while (retiredWrites.size > RETIRED_LIMIT) retiredWrites.removeAt(0)
        }
        // 镜像自己带线程，关掉时先落盘再收：设备身份 / 会话刚变过的那一次不能丢
        // （它正是「下次重装能不能快速登录」的全部依据）。
        mirror.close()
    }
    private fun commit(target: SharedPreferences, values: Map<String, () -> Any?>): Boolean {
        val edit = target.edit()
        values.forEach { (key, encode) ->
            when (val value = encode()) {
                null -> edit.remove(key)
                is String -> edit.putString(key, value)
                is Int -> edit.putInt(key, value)
                is Float -> edit.putFloat(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> error("Unsupported library value")
            }
        }
        return edit.commit()
    }
}
