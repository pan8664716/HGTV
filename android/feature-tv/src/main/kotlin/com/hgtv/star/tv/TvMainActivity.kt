// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv
import com.hgtv.star.shared.debug.DebugLog

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.net.ConnectivityManager
import android.widget.*
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.ui.PlayerView
import com.hgtv.star.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.hgtv.star.shared.data.ArtworkLoader
import com.hgtv.star.shared.data.AppRuntime
import com.hgtv.star.shared.media.DanmakuView
import com.hgtv.star.shared.lan.LanLibrarySyncSession
import com.hgtv.star.tv.player.EpisodePanel
import com.hgtv.star.shared.data.FavoriteMonitor
import com.hgtv.star.tv.list.HomeScreen
import com.hgtv.star.shared.data.Library
import com.hgtv.star.shared.compat.lowEndTv
import com.hgtv.star.tv.player.PlaybackMenu
import com.hgtv.star.tv.PosterCard
import com.hgtv.star.tv.SeekIndicator
import com.hgtv.star.tv.TvButton
import com.hgtv.star.tv.TvDialog
import com.hgtv.star.shared.media.MediaSessionHost
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.shared.ui.TvTheme
import com.hgtv.star.tv.TvTools
import com.hgtv.star.tv.list.showCatalog
import com.hgtv.star.shared.compat.Cancellable
import com.hgtv.star.shared.debug.Diagnostics
import com.hgtv.star.shared.debug.PlaybackStartupTrace
import com.hgtv.star.shared.debug.StartupTrace
import com.hgtv.star.shared.debug.compactTls
import com.hgtv.star.shared.nav.Navigator
import com.hgtv.star.shared.nav.Route
import com.hgtv.star.shared.update.AppUpdateController
import com.hgtv.star.shared.update.AppUpdateState


class TvMainActivity: Activity() {
    val bg get()=TvStyle.background
    val white get()=TvStyle.text
    val muted get()=TvStyle.muted
    val themeRows=linkedMapOf<TvTheme,TvButton>()
    val main=Handler(Looper.getMainLooper())
    val appScope=CoroutineScope(SupervisorJob() + com.hgtv.star.shared.architecture.AppExecutors.main)
    val io get()=com.hgtv.star.shared.architecture.AppExecutors.network.executor
    val playbackPrime get()=com.hgtv.star.shared.architecture.AppExecutors.player.executor
    val playerBuildWorker get()=com.hgtv.star.shared.architecture.AppExecutors.player.executor
    init { setupNetworkLogging() }
    val repository get()=AppRuntime.repository(this)
    val accountController get()=AppRuntime.accountController(this)
    val detailController by lazy {
        com.hgtv.star.shared.feature.detail.DetailController(
            repository = repository,
            library = AppRuntime.library(this),
            scope = appScope,
            account = accountController,
        )
    }
    val searchController by lazy {
        com.hgtv.star.shared.feature.search.SearchController(
            repository = repository,
            library = AppRuntime.library(this),
            scope = appScope,
        )
    }
    val lanSyncController by lazy {
        com.hgtv.star.shared.feature.lan.LanSyncController(
            context = this,
            library = AppRuntime.library(this),
            scope = appScope,
        )
    }
    val updateController by lazy { AppUpdateController(this) }
    var updateState=AppUpdateState()
    var updateIcon: View?=null
    var updateDot: View?=null
    val recommendPlaybackController by lazy {
        com.hgtv.star.shared.feature.recommend.RecommendPlaybackController(
            repository = repository,
            library = AppRuntime.library(this),
            source = playbackSource,
            store = recommendStore(),
        )
    }
    val playbackSource by lazy {
        com.hgtv.star.shared.architecture.PlaybackSourceCoordinator(repository)
    }

    val networkCleanup get()=com.hgtv.star.shared.architecture.AppExecutors.network.executor
    private val sessionExpiredReceiver=object: android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context,intent: android.content.Intent) {
            if(intent.action!=com.hgtv.star.shared.data.SessionEvents.ACTION_SESSION_EXPIRED) return
            val reason=intent.getStringExtra(com.hgtv.star.shared.data.SessionEvents.EXTRA_REASON).orEmpty()
            android.util.Log.w("HGTV-SESSION","session expired: $reason")
            if(isDestroyed) return
            repository.credentials=null
            repository.sessionToken=null
            applySessionToken(); applyAccount()
            showCatalog()
            runCatching { tvTools.toast("登录已失效，请重新登录") }
        }
    }
    internal val initializationSessionExpiredReceiver get()=sessionExpiredReceiver
    val prefetchWorker get()=com.hgtv.star.shared.architecture.AppExecutors.network.executor
    val danmakuWorker get()=com.hgtv.star.shared.architecture.AppExecutors.danmaku.executor
    var prefetchHttp: okhttp3.OkHttpClient?=null
    @Volatile var playbackFetching=false
    val playbackFetchSeq=java.util.concurrent.atomic.AtomicInteger(0)
    var playbackHttp: okhttp3.OkHttpClient?=null
    val preparedNext=PreparedSlot<RemoteVideo>({ android.os.SystemClock.elapsedRealtime() })
    var prefetchJob: java.util.concurrent.Future<*>?=null
    var prefetchScope: RequestScope?=null
    var prefetchedFor=""
    var readySince=0L
    val ledger=com.hgtv.star.core.PlaybackAttemptLedger()
    var recommendRestored: Boolean
        get()=recommendStore().restored
        set(value) { recommendStore().restored=value }
    var recommendStarted=false
    var recommendFeedPending=false
    var recommendFeedTimer: Runnable?=null
    var recommendAfterFirstFrameScheduled=false
    var bufferingSuppressed=false
    var bufferingDots: com.hgtv.star.shared.ui.LoadingDotsView?=null
    var retiringVideo: RemoteVideo?=null
    var retiringHttp: okhttp3.OkHttpClient?=null
    var queuedEpisodeId: String?=null
    var queuedVideo: RemoteVideo?=null
    var queuedHttp: okhttp3.OkHttpClient?=null
    var queuedQuality: String=""
    var queuedDetail: Detail?=null
    var queuedAt=0L
    var advancedAt=0L
    val playback=com.hgtv.star.shared.architecture.PlaybackController()
    val playbackPower by lazy { com.hgtv.star.shared.architecture.PlaybackPower(this) }
    val playbackSession: Int get()=playback.sessionId.toInt()
    var failureHandledSession=-1
    var failureHandledAt=0L
    var playedItemPending=false
    var playedItemId=""
    var transitionCleanupToken=0
    var transitionMediaId=""
    var transitionReady=false
    var transitionFirstFrame=false
    var transitionDropRequested=false
    var transitionConfirmAttempts=0
    var lastRenderedMediaId=""
    val orphanResources=mutableListOf<Pair<RemoteVideo?,okhttp3.OkHttpClient?>>()
    var lastEpisodeDuration=0L
    lateinit var artwork: ArtworkLoader
    val artworkReady: Boolean get() = ::artwork.isInitialized
    private val artworkReleaseCallback={ releaseOffscreenCovers() }
    internal val initializationArtworkReleaseCallback get()=artworkReleaseCallback
    data class DeferredUi(val apply: ()->Unit,val discard: ()->Unit={})
    class GridRow(val line: LinearLayout) {
        var index=0
        val cards=mutableListOf<PosterCard>()
    }
    var deferredWork: DeferredUi?=null
    var initialCatalogPending=true
    private var restoreBundle: Bundle?=null
    internal var resumeRestoreBundle: Bundle?
        get()=restoreBundle
        set(value) { restoreBundle=value }
    var restoreStoppedPage=false
    var pendingDetail: Series?=null
    var detailAutoplay=true
    var libraryLoadError: Throwable?=null
    var workScope: RequestScope?=null
    var workJob: java.util.concurrent.Future<*>?=null
    var playerListener: Player.Listener?=null
    var pendingCodecReport: Runnable?=null
    var playbackWatchdog: Runnable?=null
    var playbackWatchdogSession=-1
    val playbackWatchdogState=com.hgtv.star.core.PictureWatchdog({ android.os.SystemClock.elapsedRealtime() })
    internal val debugOverlayState=TvDebugOverlayState()
    var debugPanel: LinearLayout?
        get()=debugOverlayState.panel
        set(value) { debugOverlayState.panel=value }
    var debugBody: LinearLayout?
        get()=debugOverlayState.body
        set(value) { debugOverlayState.body=value }
    var debugRowLabels: List<String>
        get()=debugOverlayState.rowLabels
        set(value) { debugOverlayState.rowLabels=value }
    val debugRowValues get()=debugOverlayState.rowValues
    var debugTick: Runnable?
        get()=debugOverlayState.tick
        set(value) { debugOverlayState.tick=value }
    var debugIdleTicks: Int
        get()=debugOverlayState.idleTicks
        set(value) { debugOverlayState.idleTicks=value }
    var debugProblem: String
        get()=debugOverlayState.problem
        set(value) { debugOverlayState.problem=value }
    var debugProblemAt: Long
        get()=debugOverlayState.problemAt
        set(value) { debugOverlayState.problemAt=value }
    var debugProblemPage: String
        get()=debugOverlayState.problemPage
        set(value) { debugOverlayState.problemPage=value }
    var debugCapability: com.hgtv.star.shared.data.PlaybackCapability.DebugCapability?
        get()=debugOverlayState.capability
        set(value) { debugOverlayState.capability=value }
    var debugCapabilityLoading: Boolean
        get()=debugOverlayState.capabilityLoading
        set(value) { debugOverlayState.capabilityLoading=value }
    var playbackStallWatchdog: Runnable?=null
    lateinit var playbackTitle: TextView
    var playerClock: TextView?=null
    var playbackInfo: View?=null
    var playbackEpisode: TextView?=null
    var playbackSpeedText: TextView?=null
    var videoWidth=0
    var videoHeight=0
    var directPlayback=false
    var collection: String?=null
    var exitArmedAt=0L
    var accountTab="history"
    var accountHistoryFilter: Boolean?=null
    val historyFilterButtons=mutableMapOf<Boolean,View>()
    var accountReserveFilter: Boolean?=null
    val reserveFilterButtons=mutableMapOf<Boolean,View>()
    val pageSize=100
    var backToTopView: View?=null
    var backToTopShown=false
    var backToTopReturn=View.NO_ID
    var catalogScroll: ScrollView?=null
    var catalogBody: LinearLayout?=null
    var homePosition: HomeScreen.Position?=null
    var catalogPageKey=""
    var accountData: AccountData?
        get()=accountController.data
        set(value) {
            if(value==null) accountController.clear() else accountController.replace(value)
        }
    var pendingAccountUri: android.net.Uri?=null
    var accountError: Throwable?=null
    var accountLoading=false
    var accountRefreshing=false
    var accountToken=0
    var accountRetry=0
    /** 权限授予后最多补读一次旧镜像，避免每次 onResume 都触发 MediaStore 查询。 */
    var mirrorRestoreRetried=false
    val historyPushedAt=mutableMapOf<String,Long>()
    var deviceRegistering=false
    var playbackHevcSupported=true
    val sessionPlayback=com.hgtv.star.core.SessionPlaybackPolicy()
    val playbackSessionState=com.hgtv.star.shared.architecture.PlaybackSession(
        controller=playback,
        ledger=ledger,
        policy=sessionPlayback,
    )
    private val playerHost=object:com.hgtv.star.shared.feature.player.PlayerHost {
        override fun currentPositionMs(): Long =
            runCatching { player?.currentPosition ?: startPosition }.getOrDefault(startPosition)
        override fun durationMs(): Long =
            runCatching { player?.duration ?: 0L }.getOrDefault(0L).coerceAtLeast(0L)
        override fun playbackState(): Int = player?.playbackState ?: Player.STATE_IDLE
        override fun playWhenReady(): Boolean = player?.playWhenReady==true
        override fun isPlaying(): Boolean = player?.isPlaying==true
        override fun play() { player?.play() }
        override fun pause() { player?.pause() }
        override fun seekTo(positionMs: Long) { player?.seekTo(positionMs) }
        override fun rebuildSameQuality(positionMs: Long) {
            playEpisode(episodeIndex,positionMs,requestedAutoplay,recovering=true,keepMedia=true)
        }
    }
    val playbackCoordinator by lazy {
        com.hgtv.star.shared.feature.player.PlaybackCoordinator(
            session=playbackSessionState,
            source=playbackSource,
            host=playerHost,
            library=library,
            account=accountController,
        )
    }
    var sessionSeriesId=""
    val playbackToasts=com.hgtv.star.core.ToastDeduper()
    @Volatile internal var hardwareHevcAvailable=true
    var consentShowing=false
    var consentShownThisRun=false
    var prefetchChain=0
    var prefetchCenterRow=-1
    val deviceRegisterLock=Any()

    var centerLoading: com.hgtv.star.shared.ui.LoadingDotsView?=null
    var centerLoadingMessage: String?=null
    val scrollPositions=mutableMapOf<String,Int>()

    fun rememberScroll(key: String,value: Int) = rememberScrollImpl(key,value)

    val topArea=mutableListOf<View>()
    val topAreaHeights=mutableMapOf<View,Int>()
    val topAreaRestore=mutableMapOf<View,Int>()
    val topAreaAnimators=mutableMapOf<View,android.animation.ValueAnimator>()
    var topAreaHidden=false
    var keepTopAreaState=false
    var listScrollY=0
    var topAreaSettleUntil=0L

    fun registerTopAreaRow(row: View) = registerTopAreaRowImpl(row)

    fun onListScroll(y: Int,force: Boolean=false) = onListScrollImpl(y,force)

    fun syncHeaderState() = syncHeaderStateImpl()

    fun headerShouldHide(y: Int): Boolean = headerShouldHideImpl(y)

    fun gridAtBottom(slack: Int): Boolean = gridAtBottomImpl(slack)

    fun setTopAreaHidden(hide: Boolean,animate: Boolean=true) = setTopAreaHiddenImpl(hide,animate)
    var prefetchAttemptAt=0L
    var prefetchAttempts=0
    var homeScreen: HomeScreen?=null
    var cachedHomeScreen: HomeScreen?=null
    var homeFromCache=false
    /** 首页已经完成首次稳定落焦；后续异步刷新不再主动抢焦点。 */
    var homeFocusRequested=false

    var homeUpdateCount=0
    var launchStarted=android.os.SystemClock.elapsedRealtime()
    val activityStarted=launchStarted
    var homeContentLogged=false
    lateinit var library: Library
    val libraryReady: Boolean get() = ::library.isInitialized
    lateinit var tvTools: TvTools
    internal val tvToolsReady: Boolean get() = ::tvTools.isInitialized
    var mediaSession: MediaSessionHost?=null
    var mediaSessionFailed=false
    val sleepTimer=SleepTimer { android.os.SystemClock.elapsedRealtime() }
    var sleepStopped=false
    var playerView: PlayerView?=null
    var overlayLayer: FrameLayout?=null
    var danmakuView: DanmakuView?=null
    val danmakuLoader by lazy {
        com.hgtv.star.shared.media.DanmakuLoader(
            scope = appScope,
            repository = { repository },
            mode = { library.danmakuMode },
            target = { danmakuView },
            post = { block -> main.post(block) },
            alive = { !isDestroyed && (screen=="player" || screen=="recommend") },
            onLoaded = { episodeId, count ->
                android.util.Log.i("HGTV","Danmaku episode=$episodeId count=$count mode=${library.danmakuMode.storedValue}")
                updatePlaybackText()
            },
            onFailed = { episodeId, problem ->
                Diagnostics.warn("HGTV-DANMAKU","fetch failed episode=$episodeId",problem)
            },
        )
    }
    val danmakuFor: String get()=danmakuLoader.episode
    val danmakuLoaded: Boolean get()=danmakuLoader.loaded
    val danmakuCount: Int get()=danmakuLoader.count
    lateinit var favoriteMonitor: FavoriteMonitor
    var favoriteStatus: TextView?=null
    var historyClear: View?=null
    var historyClearInline=false
    var episodePanel: EpisodePanel?=null
    var retryPending=false
    var autoRecovery=false
    var startPosition=0L
    var retryPosition=0L
    var retryEpisodeId=""
    var requestedAutoplay=true
    var retryAutoplay=true
    var foreground=false
    lateinit var connectivity: ConnectivityManager
    private var networkWatch: Cancellable?=null
    internal var initializationNetworkWatch: Cancellable?
        get()=networkWatch
        set(value) { networkWatch=value }
    val retryRunnable=Runnable {
        retryPending=false
        val retryId=detail?.episodes?.getOrNull(episodeIndex).orEmpty()
        if(retryEpisodeId.isNotEmpty() && retryId!=retryEpisodeId) {
            cancelRecovery()
        } else if(foreground && screen=="player" && playError && autoRecovery) {
            if(!networkAvailable()) scheduleRecovery()
            else if(playbackCoordinator.consumeAutoRetry()) {
                android.util.Log.i("HGTV","Automatic playback recovery ${playbackCoordinator.autoRetryAttempts}/3 at ${retryPosition}ms")
                prepareFreshPlaybackRetry()
                playEpisode(episodeIndex,retryPosition,retryAutoplay,recovering=true)
            }
        }
    }
    var resetting=false
    lateinit var root: FrameLayout
    var generation=0
    var networkWasUp=false
    var screen="catalog"
    var launchRecommendPending=true
    var startupInitializing=true
    var startupCreateTraceAt=0L
    var mainInitialized=false
    var recommendStoreRef: com.hgtv.star.shared.feed.RecommendStore?=null
    var searchCollectorStarted=false
    var recommendItems: List<Series>
        get()=recommendStore().items
        set(value) { recommendStore().replaceItems(value) }
    var recommendIndex=0
    var recommendPage: Int
        get()=recommendStore().page
        set(value) { recommendStore().page=value }
    var recommendHasMore: Boolean
        get()=recommendStore().hasMore
        set(value) { recommendStore().hasMore=value }
    var recommendLoading: Boolean
        get()=recommendStore().loading
        set(value) { recommendStore().loading=value }
    var recommendTasteCache: com.hgtv.star.core.RecommendTaste?
        get()=recommendStore().tasteCache
        set(value) { recommendStore().tasteCache=value }
    var recommendFilterMiss: Int
        get()=recommendStore().filterMiss
        set(value) { recommendStore().filterMiss=value }
    var recommendFilterUnsupported: Boolean
        get()=recommendStore().filterIdsOff
        set(value) { recommendStore().filterIdsOff=value }
    var recommendGenderOff: Boolean
        get()=recommendStore().genderOff
        set(value) { recommendStore().genderOff=value }
    var recommendGenderProbe: Boolean
        get()=recommendStore().genderProbe
        set(value) { recommendStore().genderProbe=value }
    val recommendGenderFilter: com.hgtv.star.core.OptionalFilter
        get()=recommendStore().gender

    var recommendWatchingId=""
    var recommendWatchStart=0L
    var recommendAdvancePending=0
    var recommendAdvanceWaiterQueued=false
    var recommendLookaheadAt=0L
    val recommendWaiters=mutableListOf<() -> Unit>()
    var recommendSession: String
        get()=recommendStore().sessionId
        set(value) { recommendStore().sessionId=value }
    var recommendNext: Int
        get()=recommendStore().nextOffset
        set(value) { recommendStore().nextOffset=value }
    var recommendGenderOptions: List<com.hgtv.star.core.CategoryFilterOption>?=null
    var recommendGenderLoading=false
    val recommendDetails=LinkedHashMap<String,Detail>(8,0.75f,true)
    val recommendDetailLoading=mutableSetOf<String>()
    val recommendPrepared=LinkedHashMap<String,RemoteVideo>()
    val recommendWarmJobs=HashMap<String,RecommendWarm>()
    val recommendPlaybackBatchState=com.hgtv.star.shared.feed.RecommendPlaybackBatchState()
    var recommendBatchKey=""
    var recommendBatchJob: java.util.concurrent.Future<*>?=null
    var formalBatchKey=""
    var formalBatchJob: java.util.concurrent.Future<*>?=null
    val recommendWarmWorker get()=com.hgtv.star.shared.architecture.AppExecutors.network.executor
    var recommendLastSwitchAt=0L
    var recommendPrefetchDir=1
    // 低配电视首帧后稍让出一点时间，再优先准备**下一部**；其余相邻作品由完成后的
    // 预热链继续补。窗口总延迟仍由 preloadGateOpen 按当前缓冲决定是否让路。
    val slideCache=com.hgtv.star.core.SlideCacheManager(
        firstFrameDelayMs=if(runCatching { lowEndTv() }.getOrDefault(true)) 500L else 300L,
    )
    var preloadSchedulerRef: com.hgtv.star.shared.feed.PreloadScheduler?=null
    val recommendPreparedIndex=HashMap<String,Int>()
    val recommendPreparedAt=HashMap<String,Long>()
    val recommendToken=java.util.concurrent.atomic.AtomicLong(0)
    val recommendPageToken=java.util.concurrent.atomic.AtomicLong(0)
    var recommendHistoryPending=false
    var recommendShift=0
    var playbackMenuOpenPress=false
    var pictureRecoveries=0
    var pictureRecoveryAt=0L
    var recommendCover: View?=null
    var recommendCoverShownAt=0L
    var recommendCoverToken=0
    var recommendOverlay: View?=null
    var recommendTitle: TextView?=null
    var recommendEpisode: TextView?=null

    var recommendProgress: com.hgtv.star.tv.SeekIndicator?=null
    var recommendSpan: TextView?=null
    var recommendScrubbing=false
    var recommendNotice=""
    var recommendNoticeAt=0L
    var recommendPlaybackFailures=0
    var recommendIcon: View?=null
    var playbackFromRecommend=false
    var tab=0
    var page=1
    var query=""
    var catalog=emptyList<Series>()
    var hasMore=false
    /** 当前榜单的第 1 页是否已经由网络刷新过；缓存种子不算。 */
    var catalogPageOneFresh=false
    var catalogFocus=""
    data class CatalogState(val page: Int,val items: List<Series>,val hasMore: Boolean,val ranking: ComicRanking?)
    var ranking: ComicRanking?=null
    var rankBoard=RankBoard.RECOMMEND
    var homeFeed=mutableMapOf<RankBoard,CatalogPage>()
    var focusFirstCardOnLanding=false
    var chipFocusKey: String?=null
    var rankSubtitle: TextView?=null
    var moreButton: View?=null
    var moreFocus: String?=null

    var gridList: LinearLayout?=null
    var gridScroll: ScrollView?=null
    var gridPaging: LinearLayout?=null
    var moreCount: TextView?=null
    var pagingHasMore=false
    var gridWindow: LinearLayout?=null
    var gridTopSpacer: View?=null
    var gridBottomSpacer: View?=null
    var gridItems: List<Series> = emptyList()
    var gridRowStride=0
    var gridWindowStart=-1
    var gridSyncing=false
    val gridRows=mutableListOf<GridRow>()
    val gridRowPool=mutableListOf<GridRow>()
    var gridCardWidth=0
    var gridGap=0
    var gridPosterHeight=0
    var gridRestoring=true
    var moreInFlight=false
    var moreInFlightPage=0
    var moreVisible=false
    var moreFocusWanted=false
    var moreFailed=false
    var moreFailedAt=0L
    var gridPrefetchJob: java.util.concurrent.Future<*>?=null
    val tabState=mutableMapOf<Int,CatalogState>()
    val categoryStates=linkedMapOf<String,CatalogState>()
    val rankStates=linkedMapOf<RankBoard,CatalogState>()
    val tabFocus=mutableMapOf<Int,String>()
    val tabVisited=mutableSetOf<Int>()
    val nav=mutableListOf<View>()
    val navKeyed=linkedMapOf<String,View>()
    val boardButtons=mutableMapOf<RankBoard,View>()
    val accountButtons=mutableMapOf<String,View>()
    var profileHeadRow: View?=null
    var profileAvatar: android.widget.ImageView?=null
    var accountPanelHost: android.widget.LinearLayout?=null
    var accountPanelMode: String?=null
    var loginMode: String=ACCOUNT_PANEL_QR
    val loginModeChips=linkedMapOf<String,View>()
    var accountPanelTrigger: View?=null
    var accountPanelSession: java.io.Closeable?=null
    var accountPanelGeneration=0
    var categorySelection=CategorySelection()
    var categoryPanel: CategoryPanel?=null
    val categoryFilterButtons=mutableMapOf<String,MutableMap<String,View>>()
    var searchInput: View?=null
    var searchButton: View?=null
    var hongguoKeyboard: HongguoKeyboard?=null
    var hongguoHistoryColumn: LinearLayout?=null
    var hongguoResultsColumn: LinearLayout?=null
    var hongguoSwitch: View?=null
    var hongguoLandFirstCard=false
    var hongguoDraft: String=""
    var voiceButton: View?=null
    val searchActions=mutableListOf<View>()
    var searchImeToken=0
    var voiceLaunching=false
    var voiceReturned=false
    var voiceDraft: String?=null
    var pendingVoiceQuery: String?=null
    var suppressSearchFocusOnce=false
    var suggestRow: HorizontalScrollView?=null
    var suggestViewport: View?=null
    var suggestLine: LinearLayout?=null
    val suggestButtons=mutableListOf<View>()
    val suggestToken=java.util.concurrent.atomic.AtomicLong(0)
    var suggestJob: java.util.concurrent.Future<*>?=null
    val suggestRun=java.util.concurrent.atomic.AtomicReference<Runnable?>(null)
    var detail: Detail?=null
    val detailMemo=LinkedHashMap<String,Detail>()
    var episodeIndex=0
    var relatedOpen=false
    var detailBottomRefresh: (() -> Unit)?=null
    var detailAuthorRefresh: ((String) -> Unit)?=null
    var detailAuthorLoading=false
    var detailAuthorToken=0
    var relatedWorks: RelatedWorks?=null
    var relatedLoading=false
    var relatedFailed=false
    val relatedToken=java.util.concurrent.atomic.AtomicLong(0)
    var relatedJob: java.util.concurrent.Future<*>?=null
    var relatedFor=""
    var authorOpen=false
    var authorWorks: List<Series>?=null
    var authorLoading=false
    var authorFailed=false
    val authorToken=java.util.concurrent.atomic.AtomicLong(0)
    var authorJob: java.util.concurrent.Future<*>?=null
    var authorFor=""
    @Volatile internal var player: ExoPlayer?=null
    val playerBuildLock=Any()
    /** 解码方式 / 销毁会推进；异步建播放器完成后必须仍匹配，避免旧渲染器回装。 */
    @Volatile internal var playerBuildEpoch=0L
    /** 当前 [player] 实际创建时使用的解码档位；设置页改档后用它发现旧实例。 */
    @Volatile internal var builtPlayerDecoderMode: VideoDecoderMode?=null
    @Volatile internal var builtPlayerBufferProfile: TvBufferProfile?=null
    @Volatile internal var playerDisposed=false
    @Volatile internal var configuredPlayer: ExoPlayer?=null
    var video: RemoteVideo?=null
    @Volatile internal var lastStreamHost=""
    var playbackReady: Boolean
        get()=playback.prepared
        set(value) { playback.setPrepared(value) }
    var pausedForLifecycle=false
    lateinit var hud: LinearLayout
    internal val hudReady: Boolean get() = ::hud.isInitialized
    lateinit var playbackText: TextView
    lateinit var progressBar: SeekIndicator
    lateinit var controls: LinearLayout
    var transportIcon: ImageView?=null
    var qualityOption: TextView?=null
    var speedOption: TextView?=null
    var frameOption: TextView?=null
    var danmakuOption: TextView?=null
    var sleepOption: TextView?=null
    var touchArmed=false
    var touchLayer: FrameLayout?=null
    var touchBar: LinearLayout?=null
    var touchPlayLabel: com.hgtv.star.tv.TvButton?=null
    var touchEntryTitle: TextView?=null
    var touchEntryHint: TextView?=null
    var hudEpisodeArrow: ImageView?=null
    var touchSeekHint: TextView?=null
    var touchBoostHint: TextView?=null
    var touchScrubHint: TextView?=null
    var playbackBoost=false
    var scrubbing=false
    var panel=false
    var playbackMenu: PlaybackMenu?=null
    var playbackMenuShell: View?=null
    var playbackMenuCache: PlaybackMenu?=null
    var playbackMenuCacheShape: String?=null
    var warmPlaybackMenuTask: Runnable?=null
    var playerStripRefresh: (() -> Unit)?=null
    var sleepChoice=0
    var speedDialog: TvDialog?=null
    var homeColumnsDialog: TvDialog?=null
    var backToTopDialog: TvDialog?=null
    var settingsPendingFocus: String?=null
    var settingsTab=0
    val settingsTabChips=mutableListOf<View>()
    var settingsTopRows: (Int)->List<View> = { emptyList() }
    var settingsScroll: android.widget.ScrollView?=null

    var finishedPanel: com.hgtv.star.tv.player.RelatedPanel?=null
    var nextSeason: Series?=null
    var nextSeasonFor=""
    var nextSeasonLoading=false
    val nextSeasonToken=java.util.concurrent.atomic.AtomicLong(0)
    var nextSeasonJob: java.util.concurrent.Future<*>?=null
    var nextSeasonDetail: Detail?=null
    var nextSeasonDetailFor=""
    var nextSeasonDetailLoading=false
    val nextSeasonDetailToken=java.util.concurrent.atomic.AtomicLong(0)
    var nextSeasonDetailJob: java.util.concurrent.Future<*>?=null
    var lanSyncSession: LanLibrarySyncSession?=null
    val lanSyncToken=java.util.concurrent.atomic.AtomicLong(0)
    var autoNextSeasonPending=false
    var nextSeasonOpening=false
    var nextSeasonFinishedFor=""
    var nextSeasonNotice: TextView?=null
    var pendingSeek: Long?=null
    var playError: Boolean
        get()=playback.failed
        set(value) { playback.setErrorFlag(value) }
    var quality=""
    private val tick=object: Runnable {
        override fun run() = runTvMainTick(this)
    }
    internal val initializationTick get()=tick
    val overlayAutoHide=Runnable {
        runOverlayAutoHide()
    }
    val seekRunnable=Runnable { runPendingSeek() }
    val restoreBuffering=Runnable { restoreBufferingUi() }
    val retireForced=Runnable {
        runForcedRetire()
    }

    override fun onCreate(state: Bundle?) {
        startupCreateTraceAt=StartupTrace.now()
        StartupTrace.mark("main.onCreate.start")
        PlaybackStartupTrace.point("tvMain.onCreate")
        super.onCreate(state)
        initializeMainShell(state)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        saveMainState(outState)
    }

    override fun onActivityResult(requestCode: Int,resultCode: Int,data: android.content.Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        handleActivityResultImpl(requestCode,resultCode,data)
    }

    override fun onRequestPermissionsResult(requestCode: Int,permissions: Array<out String>,results: IntArray) {
        if(android.os.Build.VERSION.SDK_INT>=23) super.onRequestPermissionsResult(requestCode,permissions,results)
        handleRequestPermissionsResultImpl(requestCode,results)
    }


    val navigator: Navigator by lazy { TvNavigator(this) }

    @Deprecated("TV remote back is handled through the activity")
    override fun onBackPressed()=goBackImpl()
    override fun onSearchRequested(): Boolean = false
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        prepareDispatchTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return handleDispatchKeyEvent(event) ?: super.dispatchKeyEvent(event)
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        reloadAfterConfigurationChange()
    }
    override fun onResume() {
        val resumeStarted=StartupTrace.now()
        StartupTrace.mark("main.onResume.start")
        PlaybackStartupTrace.point("tvMain.onResume")
        super.onResume()
        resumeAfterSuper(resumeStarted)
    }
    override fun onPause() {
        onPauseImpl { super.onPause() }
    }
    override fun onStop() {
        onStopImpl { super.onStop() }
    }
    var suspendedCovers=0

    override fun onLowMemory() {
        super.onLowMemory()
        handleLowMemory()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        handleTrimMemory(level)
    }
    override fun onDestroy() {
        handleDestroy { super.onDestroy() }
    }
}
