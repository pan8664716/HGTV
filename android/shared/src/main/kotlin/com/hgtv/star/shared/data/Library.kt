// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data
import com.hgtv.star.shared.debug.Diagnostics
import com.hgtv.star.shared.debug.StartupTrace

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

typealias WatchProgress = BackupProgress

class Library(context: Context) {
    private companion object {
        val retiredWrites = mutableListOf<LatestWriteQueue<String, () -> Any?>>()
        const val QUEUES_PER_LIBRARY = 3
        const val RETIRED_LIMIT = 2 * QUEUES_PER_LIBRARY
        fun isDeviceNumber(value: String): Boolean =
            value.isNotEmpty() && value.all { it in '0'..'9' } && value.any { it != '0' }
        val MIRROR_KEYS = listOf(
            "registered-device-id","registered-install-id","registered-device-token",
            "registered-klink-egdi","registered-profile-stamp",
            "device-profile","device-seed","device-profile-seed",
            "device-profile-pending","device-profile-pending-seed","device-profile-pending-stamp",
            "device-profile-nonce","account-cookie","account-device","account-expires","x-tt-token",
            "session-token","ts-sign","first-beat","registered-cookies","last-beat-at",
            "identity-layout-v2",
            "legacy-registered-device-id","legacy-registered-install-id",
            "legacy-registered-device-token","legacy-registered-klink-egdi",
            "legacy-registered-profile-stamp","legacy-registered-cookies","legacy-device-profile",
        )
        const val IDENTITY_LAYOUT_KEY = "identity-layout-v2"
        internal val identityLayoutKey get()=IDENTITY_LAYOUT_KEY
        val LEGACY_IDENTITY_KEYS = listOf(
            "registered-device-id","registered-install-id","registered-device-token",
            "registered-klink-egdi","registered-profile-stamp","registered-cookies",
            "device-profile","device-seed","device-profile-seed",
            "device-profile-pending","device-profile-pending-seed","device-profile-pending-stamp",
            "registration-pending",
        )
        internal val legacyIdentityKeys get()=LEGACY_IDENTITY_KEYS
        val MIRROR_ACCOUNT_KEYS = listOf(
            "account-cookie","account-device","account-expires","session-token","ts-sign","last-beat-at"
        )
        internal val mirrorAccountKeys get()=MIRROR_ACCOUNT_KEYS
    }
    private val app = context.applicationContext
    internal val identityApp get()=app
    private val mirror = SessionMirror(app)
    init {
        com.hgtv.star.shared.data.SessionBootstrap.attachMirror { key,value ->
            if(value.isNullOrBlank()) mirror.remove(key) else mirror.put(key,value)
        }
    }
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor { task -> Thread(task, "hgtv-library-load") }
    private val prefs by lazy { app.getSharedPreferences("native-library-v1", Context.MODE_PRIVATE) }
    private val homePrefs by lazy { app.getSharedPreferences("native-home-cache-v1", Context.MODE_PRIVATE) }
    private val sessionPrefs by lazy { app.getSharedPreferences("native-session-v1", Context.MODE_PRIVATE) }
    internal val identitySessionPrefs get()=sessionPrefs
    internal val settingsPrefs get()=prefs
    internal val identityMirror get()=mirror
    internal val identityMirrorKeys get()=MIRROR_KEYS
    internal val identityLoader get()=loader
    internal val identityMainHandler get()=main
    internal val identityLayoutKeyValue get()=IDENTITY_LAYOUT_KEY
    internal val legacyIdentityKeysValue get()=LEGACY_IDENTITY_KEYS
    internal val mirrorAccountKeysValue get()=mirrorAccountKeys
    internal fun isDeviceNumberForIdentity(value: String): Boolean = isDeviceNumber(value)
    internal fun ensureIdentityLayoutForDeviceProfile() = ensureIdentityLayout()
    internal fun settingsState(): LibraryIndex = state()
    internal fun putSetting(key: String, value: Any?) = put(key, value)
    private val writes = LatestWriteQueue<String, () -> Any?>("hgtv-library-write", write = { batch -> commit(prefs, batch) },
        failed = { Log.w("HGTV", "Local library commit failed; pending changes retained for retry") })
    private val accountPrefs by lazy { app.getSharedPreferences("native-account-cache-v1", Context.MODE_PRIVATE) }
    private val accountWrites = LatestWriteQueue<String, () -> Any?>("hgtv-account-write", write = { batch -> commit(accountPrefs, batch) },
        failed = { Log.w("HGTV", "Account cache commit failed") })
    private val homeWrites = LatestWriteQueue<String, () -> Any?>("hgtv-home-write", write = { batch -> commit(homePrefs, batch) },
        failed = { Log.w("HGTV", "Home cache commit failed") })
    private val homeCache = LibraryHomeCacheStore(homeWrites)
    private val accountCacheStore = LibraryAccountCacheStore(
        accountWrites,
        ::cacheIdentityKey,
        ::cacheIdentityAvailable,
    )
    internal val identityAccountCacheStore get()=accountCacheStore
    @Volatile private var index: LibraryIndex? = null
    private val progressStore = ProgressStore(java.io.File(app.filesDir, "library/progress"))
    @Volatile private var loading = false
    @Volatile private var closed = false
    @Volatile private var discarding = false
    @Volatile private var identityLayoutChecked = false
    internal var identityLayoutInitialized: Boolean
        get()=identityLayoutChecked
        set(value) { identityLayoutChecked=value }
    internal val identityDiscarding get()=discarding
    private val callbackLock = Any()
    private val readyCallbacks = mutableListOf<() -> Unit>()
    private val errorCallbacks = mutableListOf<(Throwable) -> Unit>()
    val isLoaded get() = index != null

    fun loadAsync(onFailure: (Throwable) -> Unit = { problem ->
        Log.e("HGTV", "Local library failed to load", problem)
        Diagnostics.warn("HGTV-LIB", "library load failed", problem)
    }, onReady: () -> Unit) {
        val traceStarted=StartupTrace.now()
        StartupTrace.mark("library.load.request")
        var startLoader=false
        val loadedNow=synchronized(callbackLock) {
            check(!closed)
            if(index!=null) true else {
                readyCallbacks += onReady
                errorCallbacks += onFailure
                if(!loading) {
                    loading=true
                    startLoader=true
                }
                false
            }
        }
        if (loadedNow) {
            StartupTrace.done("library.load.already",traceStarted)
            onReady()
            return
        }
        if (!startLoader) {
            StartupTrace.mark("library.load.join")
            return
        }
        loader.execute {
            StartupTrace.mark("library.worker.start")
            val mirrorStarted=StartupTrace.now()
            runCatching { mirror.preload() }
            StartupTrace.done("library.mirror.preload",mirrorStarted)
            val identityStarted=StartupTrace.now()
            runCatching { ensureIdentityLayout() }
            StartupTrace.done("library.identityLayout",identityStarted)
            val result = runCatching {
                val retiredStarted=StartupTrace.now()
                val retired = synchronized(retiredWrites) { retiredWrites.toList() }
                retired.forEach { check(it.awaitClosed(30, TimeUnit.SECONDS)) { "上次观看记录仍在保存，请稍后重试" } }
                synchronized(retiredWrites) { retiredWrites.removeAll(retired.toSet()) }
                StartupTrace.done("library.retiredWrites",retiredStarted, "count=${retired.size}")
                val prefsStarted=StartupTrace.now()
                val values = prefs.all
                StartupTrace.done("library.preferences",prefsStarted, "keys=${values.size}")
                val progressStarted=StartupTrace.now()
                val legacyProgress = if (progressStore.count() == 0 && values["progress"] != null) {
                    runCatching { LibraryDisk.decode(mapOf("progress" to values["progress"])).history() }
                        .getOrDefault(emptyList())
                } else emptyList()
                if (progressStore.migrateFrom(legacyProgress)) {
                    prefs.edit().remove("progress").apply()
                }
                val fromDisk = progressStore.loadAll()
                val merged = (if (fromDisk.isNotEmpty()) fromDisk else legacyProgress)
                val effectiveValues: Map<String, Any?> =
                    if (merged.isEmpty()) values
                    else values.toMutableMap().apply { put("progress", LibraryDisk.history(merged)) }
                StartupTrace.done(
                    "library.progress",
                    progressStarted,
                    "files=${fromDisk.size} legacy=${legacyProgress.size}",
                )
                val decodeStarted=StartupTrace.now()
                val loaded = LibraryDisk.decode(effectiveValues)
                StartupTrace.done("library.decode",decodeStarted)
                recommendCursorCached = (values["recommendCursor"] as? String).orEmpty()
                phoneFeedCursorCached = (values["phoneFeedCursor"] as? String).orEmpty()
                recommendMarks.restore((values["recommendSeen"] as? String).orEmpty())
                recommendFilterIdsOffCached = values["recommendFilterIdsOff"] as? Boolean ?: false
                recommendGenreOffCached = (values["recommendGenreOff"] as? String).orEmpty()
                recommendGenderCached = (values["recommendGender"] as? String).orEmpty()
                recommendHorizontalOnlyCached = values["recommendHorizontalOnly"] as? Boolean ?: false
                deviceFormCached = (values["deviceForm"] as? String).orEmpty().ifEmpty { "auto" }
                val now = System.currentTimeMillis()
                val cached = mutableMapOf<RankBoard, CatalogPage>()
                val stamps = mutableMapOf<RankBoard, Long>()
                val migration = homePrefs.edit()
                val oldKeys = mutableListOf<String>()
                val homeStarted=StartupTrace.now()
                RankBoard.entries.forEach { board ->
                    val key = homeCache.cacheKey(board)
                    val legacy = values[key] as? String
                    val raw = homePrefs.getString(key, null) ?: legacy.orEmpty()
                    CatalogSnapshot.decode(raw, now)?.let { page ->
                        cached[board] = page
                        stamps[board] = org.json.JSONObject(raw).getLong("savedAt")
                        if (!homePrefs.contains(key)) migration.putString(key, raw)
                    }
                    if (legacy != null) oldKeys += key
                }
                StartupTrace.done("library.homeCache.decode",homeStarted, "boards=${cached.size}")
                val liveKeys = RankBoard.entries.map(homeCache::cacheKey).toSet()
                homePrefs.all.keys.filter { it.startsWith("home-") && it !in liveKeys }.forEach(migration::remove)
                val committed = migration.commit()
                if (committed && oldKeys.isNotEmpty()) {
                    prefs.edit().also { edit -> oldKeys.forEach(edit::remove) }.commit()
                }
                val signed = sessionPrefs.getString("account-cookie", "").orEmpty().contains("sessionid=")
                val accountStarted=StartupTrace.now()
                val account = if(!signed) null else accountPrefs.getString("account", null)?.let { raw ->
                    AccountSnapshot.decode(raw, now)?.takeIf { it.data.usable }
                }
                StartupTrace.done("library.accountSnapshot",accountStarted, "signed=$signed")
                Triple(loaded, cached, stamps) to account
            }
            val dispatchStarted=StartupTrace.now()
            main.post {
                StartupTrace.done("library.mainDispatch",dispatchStarted)
                val callbacks=synchronized(callbackLock) {
                    loading=false
                    if(closed) {
                        readyCallbacks.clear(); errorCallbacks.clear()
                        null
                    } else {
                        val ready=readyCallbacks.toList(); readyCallbacks.clear()
                        val errors=errorCallbacks.toList(); errorCallbacks.clear()
                        ready to errors
                    }
                }
                if (callbacks==null) {
                    StartupTrace.done("library.load.closed",traceStarted)
                    return@post
                }
                val (ready,errors)=callbacks
                result.onSuccess { (local, account) ->
                    val (loaded, cached, stamps) = local
                    index = loaded
                    homeCache.replace(cached, stamps)
                    accountCacheStore.replace(account)
                    ready.forEach { it() }
                    StartupTrace.done("library.load.total",traceStarted, "result=success")
                }.onFailure { failure ->
                    errors.forEach { it(failure) }
                    StartupTrace.done(
                        "library.load.total",
                        traceStarted,
                        "result=failure error=${failure.javaClass.simpleName}:${failure.message.orEmpty().take(120)}",
                    )
                }
            }
        }
    }
    private fun state(): LibraryIndex = checkNotNull(index) { "Load the local library before accessing it" }
    private fun ensureIdentityLayout() {
        ensureIdentityLayoutImpl()
    }
    private fun put(key: String, value: Any?) {
        if(discarding) return
        writes.submit(mapOf(key to { value }))
    }
    private fun persist(vararg keys: String) {
        if(discarding) return
        val state = state()
        val batch = linkedMapOf<String, () -> Any?>()
        keys.forEach { key ->
            batch[key] = when (key) {
                "favorites" -> state.favorites().let { rows -> { LibraryDisk.series(rows) } }
                "later" -> state.later().let { rows -> { LibraryDisk.series(rows) } }
                "progress" -> state.history().let { rows -> { rows.forEach(progressStore::save); null } }
                "favoriteUpdates" -> state.updates().let { rows -> { LibraryDisk.updates(rows) } }
                "searches" -> state.searches().let { rows -> { SearchHistory.encode(rows) } }
                "watched" -> state.watched().let { ids -> { ids } }
                "hidden" -> state.hidden().let { ids -> { ids } }
                "reserves" -> state.reserves().let { rows -> { LibraryDisk.reserves(rows) } }
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
    fun save(progress: WatchProgress) {
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
    fun removeHistory(ids: Collection<String>) {
        if (ids.isEmpty()) return
        state().removeHistory(ids)
        com.hgtv.star.core.WorkLanes.disk.executor.execute {
            ids.forEach(progressStore::remove)
        }
        persist("watched", "historyDeleted")
    }
    fun removeHistory(id: String) = removeHistory(listOf(id))
    fun markHistoryDeleted(ids: Collection<String>) {
        if(ids.isEmpty()) return
        state().markHistoryDeleted(ids); persist("historyDeleted")
    }
    fun clearHistory(): List<String> = state().clearHistory().also { ids ->
        if(ids.isNotEmpty()) {
            com.hgtv.star.core.WorkLanes.disk.executor.execute { progressStore.clear() }
            persist("watched", "historyDeleted")
        }
    }
    fun historyDeleted(): Set<String> = state().historyDeleted()
    fun historyDeleted(id: String): Boolean = state().historyDeleted(id)
    fun observeFavorite(id: String, count: Int, now: Long, acknowledge: Boolean = false) {
        if (count <= 0) return
        if (state().observeFavorite(id, count, now, acknowledge)) persist("favoriteUpdates")
    }
    fun cachedHome(board: RankBoard): CatalogPage? {
        state()
        return homeCache.cached(board)
    }
    fun cacheHome(
        board: RankBoard,
        items: List<Series>,
        hasMore: Boolean,
        ranking: ComicRanking? = null,
    ) {
        if(discarding) return
        state()
        homeCache.cache(board,items,hasMore,ranking)
    }
    private fun signedIn(): Boolean =
        sessionPrefs.getString("account-cookie", "").orEmpty().contains("sessionid=")

    private fun cacheIdentityKey(): String {
        return cacheIdentityKeyImpl()
    }

    private fun cacheIdentityAvailable(): Boolean = cacheIdentityAvailableImpl()

    var maxQuality: Int
        get() = state().settings.quality
        set(value) { val next = PlaybackQuality.normalize(value); if (next != maxQuality) { state().settings = state().settings.copy(quality = next); put("quality", next) } }
    @Volatile private var mirrorChecked = false
    internal var identityMirrorChecked: Boolean
        get()=mirrorChecked
        set(value) { mirrorChecked=value }
    private fun restoreFromMirror(): Boolean {
        return restoreFromMirrorImpl()
    }
    internal fun restoreFromMirrorForRegistrationCookies(): Boolean = restoreFromMirror()
    internal fun restoreFromMirrorForAccount(): Boolean = restoreFromMirror()
    fun retryMirrorRestore(onDone: (Boolean) -> Unit) {
        retryMirrorRestoreImpl(onDone)
    }
    private fun mirrorSync(key: String,value: String) {
        mirrorSyncImpl(key, value)
    }
    private fun mirrorSync(key: String,value: Long) {
        mirrorSyncImpl(key, value)
    }
    internal fun mirrorSyncForAccount(key: String, value: String) = mirrorSync(key, value)
    internal fun mirrorSyncForAccount(key: String, value: Long) = mirrorSync(key, value)
    fun deviceIdentity(): Map<String,String> {
        return deviceIdentityImpl()
    }
    fun deviceRegistered(): Boolean {
        return deviceRegisteredImpl()
    }
    @Volatile private var cachedProfileStamp: String?=null
    internal var identityCachedProfileStamp: String?
        get()=cachedProfileStamp
        set(value) { cachedProfileStamp=value }
    private fun profileStamp(): String {
        return profileStampImpl()
    }
    private fun freshDeviceProfile(source: String): Map<String,String> {
        return freshDeviceProfileImpl(source)
    }
    fun saveDeviceIdentity(
        deviceId: String,
        installId: String,
        klinkEgdi: String = "",
        deviceToken: String = "",
    ) {
        saveDeviceIdentityImpl(deviceId, installId, klinkEgdi, deviceToken)
    }
    fun deviceProfile(): Map<String,String> {
        return deviceProfileImpl()
    }
    fun queueNewDeviceIdentity() {
        if(discarding) return
        storePendingProfile(buildOfficialProfile())
        markRegistrationPending()
    }
    private fun buildOfficialProfile(forceRandomOpenudid: Boolean = false): Map<String,String> {
        return buildOfficialProfileImpl(forceRandomOpenudid)
    }
    internal fun buildOfficialProfileForIdentityLayout(forceRandomOpenudid: Boolean) =
        buildOfficialProfile(forceRandomOpenudid)
    private fun readAndroidId(): String? = readAndroidIdImpl()
    internal fun readAndroidIdForPendingProfile(): String? = readAndroidId()
    private fun pendingProfile(): Map<String,String>? {
        return pendingProfileImpl()
    }
    private fun storePendingProfile(profile: Map<String,String>,seed: String = "") {
        storePendingProfileImpl(profile, seed)
    }
    var autoNext: Boolean
        get() = autoNextSetting()
        set(value) = setAutoNextSetting(value)
    var playbackSpeed: Float
        get() = playbackSpeedSetting()
        set(value) = setPlaybackSpeedSetting(value)
    var frameMode: VideoFrameMode
        get() = frameModeSetting()
        set(value) = setFrameModeSetting(value)
    var decoderMode: VideoDecoderMode
        get() = decoderModeSetting()
        set(value) = setDecoderModeSetting(value)
    var searchSource: SearchSource
        get() = searchSourceSetting()
        set(value) = setSearchSourceSetting(value)
    var searchKeyboard: SearchKeyboard
        get() = searchKeyboardSetting()
        set(value) = setSearchKeyboardSetting(value)
    var homeColumns: Int
        get() = homeColumnsSetting()
        set(value) = setHomeColumnsSetting(value)
    var gridColumns: Int
        get() = gridColumnsSetting()
        set(value) = setGridColumnsSetting(value)
    var backToTop: String
        get() = backToTopSetting()
        set(value) = setBackToTopSetting(value)
    var playerTitle: Boolean
        get() = playerTitleSetting()
        set(value) = setPlayerTitleSetting(value)
    var playerEpisode: Boolean
        get() = playerEpisodeSetting()
        set(value) = setPlayerEpisodeSetting(value)
    var playerClock: Boolean
        get() = playerClockSetting()
        set(value) = setPlayerClockSetting(value)
    var playerSpeed: Boolean
        get() = playerSpeedSetting()
        set(value) = setPlayerSpeedSetting(value)
    var playerPanelTimeout: Int
        get() = playerPanelTimeoutSetting()
        set(value) = setPlayerPanelTimeoutSetting(value)
    var seekStep: Int
        get() = seekStepSetting()
        set(value) = setSeekStepSetting(value)
    var videoScale: Float
        get() = videoScaleSetting()
        set(value) = setVideoScaleSetting(value)
    var autoFullscreen: Boolean
        get() = autoFullscreenSetting()
        set(value) = setAutoFullscreenSetting(value)
    var episodePageSize: Int
        get() = episodePageSizeSetting()
        set(value) = setEpisodePageSizeSetting(value)
    var episodeEdgeTurn: Boolean
        get() = episodeEdgeTurnSetting()
        set(value) = setEpisodeEdgeTurnSetting(value)
    var reduceAnim: Boolean
        get() = reduceAnimSetting()
        set(value) = setReduceAnimSetting(value)
    var debugMode: Boolean
        get() = state().settings.debugMode
        set(value) { if (value != debugMode) { state().settings = state().settings.copy(debugMode = value); put("debugMode", value) } }
    var hiddenNav: Set<String>
        get() = LibraryPreferenceCodec.decodeCsv(state().settings.hiddenNav,NAV_KEYS)
        private set(value) {
            val clean = LibraryPreferenceCodec.encodeCsv(value,NAV_KEYS,setOf("settings"))
            if (clean == hiddenNav) return
            state().settings = state().settings.copy(hiddenNav = clean.joinToString(","))
            put("hiddenNav", clean)
        }
    fun navHidden(key: String): Boolean = key in hiddenNav
    fun setNavHidden(key: String, hidden: Boolean) {
        if (key !in NAV_KEYS) return
        hiddenNav = if (hidden) hiddenNav + key else hiddenNav - key
        if (key == NAV_KEY_RECOMMEND && hidden && state().settings.launchRecommend) {
            state().settings = state().settings.copy(launchRecommend = false)
            state().launchRecommendStored = true
            put("launchRecommend", false)
        }
    }
    var launchRecommend: Boolean
        get() = state().settings.launchRecommend && NAV_KEY_RECOMMEND !in hiddenNav
        set(value) {
            val current = launchRecommend
            state().launchRecommendStored = true
            if (value == current) return
            if (value && NAV_KEY_RECOMMEND in hiddenNav) hiddenNav = hiddenNav - NAV_KEY_RECOMMEND
            state().settings = state().settings.copy(launchRecommend = value)
            put("launchRecommend", value)
        }
    /** 磁盘上是否已经显式保存过「打开应用进入推荐」。 */
    val launchRecommendStored: Boolean
        get() = state().launchRecommendStored
    var historyPreferDetail: Boolean
        get() = state().settings.historyPreferDetail
        set(value) {
            if (value == historyPreferDetail) return
            state().settings = state().settings.copy(historyPreferDetail = value)
            put("historyPreferDetail", value)
        }
    var recommendSwipe: Boolean
        get() = state().settings.recommendSwipe
        set(value) { if (value != recommendSwipe) { state().settings = state().settings.copy(recommendSwipe = value); put("recommendSwipe", value) } }
    var hiddenHome: Set<String>
        get() = LibraryPreferenceCodec.decodeCsv(state().settings.hiddenHome,HOME_KEYS)
        private set(value) {
            val clean = LibraryPreferenceCodec.encodeCsv(value,HOME_KEYS)
            if (clean == hiddenHome) return
            state().settings = state().settings.copy(hiddenHome = clean.joinToString(","))
            put("hiddenHome", clean)
        }
    fun homeHidden(key: String): Boolean = key in hiddenHome
    fun setHomeHidden(key: String, hidden: Boolean) {
        if (key !in HOME_KEYS) return
        hiddenHome = if (hidden) hiddenHome + key else hiddenHome - key
    }
    var recommendCursor: String
        get() = recommendCursorCached
        set(value) {
            val clean=LibraryPreferenceCodec.sanitizeCursor(value,120)
            if(clean==recommendCursorCached) return
            recommendCursorCached=clean
            put("recommendCursor", clean)
        }
    @Volatile private var recommendCursorCached: String=""
    var phoneFeedCursor: String
        get() = phoneFeedCursorCached
        set(value) {
            val clean = LibraryPreferenceCodec.sanitizeCursor(value,600)
            if (clean == phoneFeedCursorCached) return
            phoneFeedCursorCached = clean
            put("phoneFeedCursor", clean)
        }
    @Volatile private var phoneFeedCursorCached: String=""
    private val recommendMarks = RecommendMarks()
    fun recommendSeen(): Set<String> = recommendMarks.ids()
    fun recommendSeenTitle(title: String): Boolean {
        val key = RecommendFlow.titleKey(title)
        return key.isNotEmpty() && recommendMarks.contains(key)
    }
    fun markRecommended(ids: Collection<String>, titles: Collection<String> = emptyList()) {
        if(ids.isEmpty() && titles.isEmpty()) return
        var changed=recommendMarks.mark(ids, RecommendFlow.TTL_SEEN)
        if(titles.isNotEmpty()) {
            val keys=titles.mapNotNull { RecommendFlow.titleKey(it).takeIf(String::isNotEmpty) }
            if(recommendMarks.mark(keys, RecommendFlow.TTL_SEEN)) changed=true
        }
        if(changed) put("recommendSeen", recommendMarks.encode())
    }
    fun markRecommendSkipped(id: String) {
        if(id.isEmpty()) return
        if(recommendMarks.mark(listOf(id), RecommendFlow.TTL_SKIP)) put("recommendSeen", recommendMarks.encode())
    }
    fun markRecommendWatched(id: String) {
        if(id.isEmpty()) return
        if(recommendMarks.mark(listOf(id), RecommendFlow.TTL_DONE)) put("recommendSeen", recommendMarks.encode())
    }
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
    fun recommendFilterIds(limit: Int = RecommendFlow.FILTER_IDS_LIMIT): String =
        if(recommendFilterIdsOff) "" else recommendMarks.filterIds(limit)
    var recommendFilterIdsOff: Boolean
        get() = recommendFilterIdsOffCached
        set(value) {
            if(value==recommendFilterIdsOffCached) return
            recommendFilterIdsOffCached=value
            put("recommendFilterIdsOff", value)
        }
    @Volatile private var recommendFilterIdsOffCached=false
    var recommendGenreOff: Set<String>
        get() = LibraryPreferenceCodec.decodeGenreOff(recommendGenreOffCached)
        private set(value) {
            val clean=value.filter { it in CategoryGenres.recommendableIds }.toSet()
            if(clean.size>=CategoryGenres.recommendableIds.size) return
            if(clean==recommendGenreOff) return
            val joined=LibraryPreferenceCodec.encodeGenreOff(clean)
            recommendGenreOffCached=joined
            put("recommendGenreOff", joined)
        }
    @Volatile private var recommendGenreOffCached: String=""
    fun recommendGenreEnabled(id: String): Boolean = id !in recommendGenreOff
    fun setRecommendGenre(id: String, enabled: Boolean) {
        if(id !in CategoryGenres.recommendableIds) return
        recommendGenreOff = if(enabled) recommendGenreOff - id else recommendGenreOff + id
    }
    fun recommendGenres(): List<String> {
        val off=recommendGenreOff
        return if(off.isEmpty()) emptyList() else CategoryGenres.recommendableIds.filter { it !in off }
    }
    var recommendGender: String
        get() = recommendGenderCached
        set(value) {
            val clean=LibraryPreferenceCodec.sanitizeCursor(value.trim(),64)
            if(clean==recommendGenderCached) return
            recommendGenderCached=clean
            put("recommendGender", clean)
        }
    @Volatile private var recommendGenderCached: String=""
    /**
     * TV 推荐请求是否使用官方横屏候选流。
     *
     * 开启后官方推荐请求传 `is_horizontal_screen=true`；服务端仍可能返回竖屏卡，
     * 因此客户端同时按卡片 `vertical` 字段做兜底过滤。
     */
    var recommendHorizontalOnly: Boolean
        get() = recommendHorizontalOnlyCached
        set(value) {
            if(value==recommendHorizontalOnlyCached) return
            recommendHorizontalOnlyCached=value
            put("recommendHorizontalOnly", value)
        }
    @Volatile private var recommendHorizontalOnlyCached=false
    var deviceForm: String
        get() = deviceFormCached
        set(value) {
            val clean=LibraryPreferenceCodec.normalizeDeviceForm(value)
            if(clean==deviceFormCached) return
            deviceFormCached=clean
            put("deviceForm", clean)
        }
    fun setDeviceFormNow(value: String): Boolean {
        val clean=LibraryPreferenceCodec.normalizeDeviceForm(value)
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
    var danmakuScale: Float
        get() = state().settings.danmakuScale
        set(value) { val next = DanmakuStyle.scale(value); if (next != danmakuScale) { state().settings = state().settings.copy(danmakuScale = next); put("danmakuScale", next) } }
    var danmakuAlpha: Float
        get() = state().settings.danmakuAlpha
        set(value) { val next = DanmakuStyle.alpha(value); if (next != danmakuAlpha) { state().settings = state().settings.copy(danmakuAlpha = next); put("danmakuAlpha", next) } }
    var danmakuSpeed: DanmakuSpeed
        get() = state().settings.danmakuSpeed
        set(value) { if (value != danmakuSpeed) { state().settings = state().settings.copy(danmakuSpeed = value); put("danmakuSpeed", value.storedValue) } }
    var danmakuMerge: Boolean
        get() = state().settings.danmakuMerge
        set(value) { if (value != danmakuMerge) { state().settings = state().settings.copy(danmakuMerge = value); put("danmakuMerge", value) } }
    var danmakuMergeMin: Int
        get() = state().settings.danmakuMergeMin
        set(value) {
            val next=value.coerceIn(DANMAKU_MERGE_MIN_RANGE)
            if (next != danmakuMergeMin) { state().settings = state().settings.copy(danmakuMergeMin = next); put("danmakuMergeMin", next) }
        }
    var danmakuEffects: Boolean
        get() = state().settings.danmakuEffects
        set(value) { if (value != danmakuEffects) { state().settings = state().settings.copy(danmakuEffects = value); put("danmakuEffects", value) } }
    var danmakuAvoidPlaybackInfo: Boolean
        get() = state().settings.danmakuAvoidPlaybackInfo
        set(value) {
            if(value==danmakuAvoidPlaybackInfo) return
            state().settings=state().settings.copy(danmakuAvoidPlaybackInfo=value)
            put("danmakuAvoidPlaybackInfo",value)
        }
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
            "danmakuAvoidPlaybackInfo" to { merged.settings.danmakuAvoidPlaybackInfo },
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
            "searchKeyboard" to { merged.settings.searchKeyboard.storedValue },
            "keymap" to { merged.settings.keys },
            "historyPreferDetail" to { merged.settings.historyPreferDetail }))
        flush()
    }
    fun flush() { writes.flush(); homeWrites.flush(); accountWrites.flush(); mirror.flush() }
    fun flushBlocking(timeoutMillis: Long = 5_000): Boolean {
        val deadline=System.nanoTime()+timeoutMillis.coerceAtLeast(1)*1_000_000
        fun remaining(): Long=((deadline-System.nanoTime())/1_000_000).coerceAtLeast(1)
        val library=writes.flushBlocking(remaining())
        val home=homeWrites.flushBlocking(remaining())
        val account=accountWrites.flushBlocking(remaining())
        val session=mirror.flushBlocking(remaining())
        return library && home && account && session
    }
    fun discardPendingWrites() {
        if (closed) return
        discarding = true
        closed = true
        synchronized(callbackLock) { readyCallbacks.clear(); errorCallbacks.clear() }
        loader.shutdownNow()
        writes.discard(); homeWrites.discard(); accountWrites.discard()
        mirror.discard()
    }
    fun close() {
        if (closed) return
        closed = true
        synchronized(callbackLock) { readyCallbacks.clear(); errorCallbacks.clear() }
        loader.shutdown()
        synchronized(retiredWrites) {
            writes.close(); homeWrites.close(); accountWrites.close()
            retiredWrites += writes; retiredWrites += homeWrites; retiredWrites += accountWrites
            while (retiredWrites.size > RETIRED_LIMIT) retiredWrites.removeAt(0)
        }
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
