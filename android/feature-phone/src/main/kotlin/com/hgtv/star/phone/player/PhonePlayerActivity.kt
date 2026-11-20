// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.video.VideoSize
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.PlayerView
import com.hgtv.star.core.DanmakuMode
import com.hgtv.star.core.DanmakuSpeed
import com.hgtv.star.core.Detail
import com.hgtv.star.core.PlaybackCache
import com.hgtv.star.core.PlaybackQuality
import com.hgtv.star.core.PlaybackSpeed
import com.hgtv.star.core.Series
import com.hgtv.star.core.toFit
import com.hgtv.star.core.RemoteVideo
import com.hgtv.star.core.StreamInfo
import com.hgtv.star.core.VideoDecoderMode
import com.hgtv.star.core.VideoFrameMode
import com.hgtv.star.phone.ui.PhoneCards
import com.hgtv.star.phone.ui.PhoneGlyph
import com.hgtv.star.phone.ui.PhoneEpisodeGrid
import com.hgtv.star.phone.ui.PhoneSheet
import com.hgtv.star.phone.ui.PhoneStyle
import com.hgtv.star.phone.ui.PhoneTouch
import com.hgtv.star.phone.ui.PhoneUiScale
import com.hgtv.star.phone.ui.bottomScrim
import com.hgtv.star.phone.ui.resizeMode
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.navigationBarPx
import com.hgtv.star.phone.ui.phoneEdgeToEdge
import com.hgtv.star.phone.ui.phoneSize
import com.hgtv.star.phone.ui.roundedBackground
import com.hgtv.star.phone.ui.topScrim
import com.hgtv.star.shared.data.Library
import com.hgtv.star.shared.data.DeviceHealer
import com.hgtv.star.shared.architecture.PlaybackSourceCoordinator
import com.hgtv.star.shared.data.AppRuntime
import com.hgtv.star.shared.feature.player.PlaybackCoordinator
import com.hgtv.star.shared.feature.player.PlayerHost
import com.hgtv.star.shared.media.PlaybackFailures
import com.hgtv.star.shared.media.VideoDataSource
import com.hgtv.star.shared.media.swapMediaSource
import com.hgtv.star.shared.nav.Route
import com.hgtv.star.shared.ui.LoadingDotsView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

open class PhonePlayerActivity : PhoneActivity() {

    companion object {
        const val EXTRA_SERIES_ID = "series_id"
        const val EXTRA_TITLE = "title"

        const val EXTRA_FULLSCREEN = "fullscreen"

        const val EXTRA_EPISODE = "episode"

        const val EXTRA_RECOMMEND = "recommend"

        private const val PREFETCH_START_MS = com.hgtv.star.core.AppConfig.Playback.PHONE_PREFETCH_START_MS

        private const val PREFETCH_URL_RESERVE_MS = com.hgtv.star.core.AppConfig.Playback.PHONE_PREFETCH_URL_RESERVE_MS

        private const val PLAYER_MODEL_QUEUE_SIZE = com.hgtv.star.core.AppConfig.Playback.PHONE_MODEL_QUEUE_SIZE
        internal val prefetchStartMs get()=PREFETCH_START_MS
        internal val prefetchUrlReserveMs get()=PREFETCH_URL_RESERVE_MS
        internal val playerModelQueueSize get()=PLAYER_MODEL_QUEUE_SIZE

        internal const val MODE_TRANSITION_MS = com.hgtv.star.core.AppConfig.Playback.MODE_TRANSITION_MS
    }

    internal lateinit var root: FrameLayout
    internal val rootReady: Boolean get()=::root.isInitialized
    internal val playerRootInitialized get()=::root.isInitialized
    private lateinit var portrait: FrameLayout
    internal var playerPortrait: FrameLayout
        get()=portrait
        set(value) { portrait=value }
    private lateinit var page: PhoneVideoPage
    internal val playerPage get()=page
    internal var swipePlayerPage: PhoneVideoPage
        get()=page
        set(value) { page=value }
    internal val playerPageInitialized get()=::page.isInitialized
    private lateinit var episodePager: VerticalEpisodePager
    internal var playerEpisodePager: VerticalEpisodePager
        get()=episodePager
        set(value) { episodePager=value }
    internal val playerEpisodePagerInitialized get()=::episodePager.isInitialized
    private lateinit var episodePagerAdapter: PhonePlayerPagerAdapter
    internal var playerEpisodePagerAdapter: PhonePlayerPagerAdapter
        get()=episodePagerAdapter
        set(value) { episodePagerAdapter=value }
    private lateinit var landscapePager: VerticalLandscapePager
    internal var playerLandscapePager: VerticalLandscapePager
        get()=landscapePager
        set(value) { landscapePager=value }
    internal val playerLandscapePagerInitialized get()=::landscapePager.isInitialized
    private lateinit var landscapePagerAdapter: LandscapePlayerPagerAdapter
    internal var playerLandscapePagerAdapter: LandscapePlayerPagerAdapter
        get()=landscapePagerAdapter
        set(value) { landscapePagerAdapter=value }

    private lateinit var landscape: FrameLayout
    private lateinit var landscapeVideo: PlayerView
    internal var playerLandscapeContainer: FrameLayout
        get()=landscape
        set(value) { landscape=value }
    internal var playerLandscapeVideoSlot: PlayerView
        get()=landscapeVideo
        set(value) { landscapeVideo=value }
    internal var playerLandscapeCover: ImageView?=null
    internal val playerLandscape get()=landscape
    internal val playerLandscapeVideo get()=landscapeVideo
    internal val playerLandscapeVideoInitialized get()=::landscapeVideo.isInitialized
    private lateinit var landDanmaku: com.hgtv.star.shared.media.DanmakuView
    internal val playerLandDanmaku get()=landDanmaku
    internal var playerLandDanmakuSlot: com.hgtv.star.shared.media.DanmakuView
        get()=landDanmaku
        set(value) { landDanmaku=value }
    internal val playerLandDanmakuInitialized get()=::landDanmaku.isInitialized
    private lateinit var landTop: LinearLayout
    private lateinit var landBottom: LinearLayout
    internal var playerLandTopSlot: LinearLayout
        get()=landTop
        set(value) { landTop=value }
    internal val playerLandTop get()=landTop
    private lateinit var landFullscreen: LinearLayout
    internal val playerLandFullscreen get()=landFullscreen
    internal var playerLandFullscreenSlot: LinearLayout
        get()=landFullscreen
        set(value) { landFullscreen=value }
    internal val playerLandFullscreenInitialized get()=::landFullscreen.isInitialized
    internal val playerLandBottom get()=landBottom
    internal var playerLandBottomSlot: LinearLayout
        get()=landBottom
        set(value) { landBottom=value }
    internal val playerLandBottomInitialized get()=::landBottom.isInitialized
    private lateinit var landPlay: PhoneGlyph
    internal val playerLandPlay get()=landPlay
    internal var playerLandPlaySlot: PhoneGlyph
        get()=landPlay
        set(value) { landPlay=value }
    private lateinit var landCurrent: TextView
    private lateinit var landSeek: SeekBar
    private lateinit var landTotal: TextView
    internal val progressLandCurrent get()=landCurrent
    internal val progressLandSeek get()=landSeek
    internal val progressLandTotal get()=landTotal
    internal var progressLandCurrentSlot: TextView
        get()=landCurrent
        set(value) { landCurrent=value }
    internal var progressLandSeekSlot: SeekBar
        get()=landSeek
        set(value) { landSeek=value }
    internal var progressLandTotalSlot: TextView
        get()=landTotal
        set(value) { landTotal=value }
    private lateinit var landSettingsInfo: TextView
    internal val playerLandSettingsInfo get()=landSettingsInfo
    internal var playerLandSettingsInfoSlot: TextView
        get()=landSettingsInfo
        set(value) { landSettingsInfo=value }
    internal val playerLandSettingsInfoInitialized get()=::landSettingsInfo.isInitialized
    private lateinit var landLock: FrameLayout
    private lateinit var landLockGlyph: PhoneGlyph
    internal val playerLandLockInitialized get()=::landLock.isInitialized
    internal var playerLandLockSlot: FrameLayout
        get()=landLock
        set(value) { landLock=value }
    internal var playerLandLockGlyphSlot: PhoneGlyph
        get()=landLockGlyph
        set(value) { landLockGlyph=value }
    private lateinit var speedChip: TextView
    private lateinit var qualityChip: TextView
    private lateinit var danmakuChip: TextView
    private lateinit var episodeChip: TextView
    internal val chipSpeed get()=speedChip
    internal val chipQuality get()=qualityChip
    internal val chipDanmaku get()=danmakuChip
    internal val chipEpisode get()=episodeChip
    internal var chipSpeedSlot: TextView
        get()=speedChip
        set(value) { speedChip=value }
    internal var chipQualitySlot: TextView
        get()=qualityChip
        set(value) { qualityChip=value }
    internal var chipDanmakuSlot: TextView
        get()=danmakuChip
        set(value) { danmakuChip=value }
    internal var chipEpisodeSlot: TextView
        get()=episodeChip
        set(value) { episodeChip=value }

    private lateinit var portraitTop: LinearLayout
    internal var playerPortraitTop: LinearLayout
        get()=portraitTop
        set(value) { portraitTop=value }
    private lateinit var centerBox: FrameLayout
    private lateinit var centerGlyph: PhoneGlyph
    internal var playerCenterBox: FrameLayout
        get()=centerBox
        set(value) { centerBox=value }
    internal var playerCenterGlyph: PhoneGlyph
        get()=centerGlyph
        set(value) { centerGlyph=value }
    internal lateinit var hint: TextView

    private lateinit var bufferingBox: FrameLayout
    private lateinit var bufferingDots: LoadingDotsView
    internal var playerBufferingBox: FrameLayout
        get()=bufferingBox
        set(value) { bufferingBox=value }
    internal var playerBufferingDots: LoadingDotsView
        get()=bufferingDots
        set(value) { bufferingDots=value }
    internal val playerBufferingInitialized get()=
        ::bufferingBox.isInitialized && ::bufferingDots.isInitialized
    private var bufferingMutedUntil = 0L
    internal var playerBufferingMutedUntil: Long
        get()=bufferingMutedUntil
        set(value) { bufferingMutedUntil=value }

    private lateinit var sheet: PhoneSheet
    internal val playerSheet get()=sheet
    internal var playerSheetSlot: PhoneSheet
        get()=sheet
        set(value) { sheet=value }

    private lateinit var infoRow: LinearLayout
    internal val playerInfoRow get()=infoRow
    internal var playerInfoRowSlot: LinearLayout
        get()=infoRow
        set(value) { infoRow=value }
    internal val playerInfoRowInitialized get()=::infoRow.isInitialized
    private lateinit var infoLeft: TextView
    internal val playerInfoLeft get()=infoLeft
    internal var playerInfoLeftSlot: TextView
        get()=infoLeft
        set(value) { infoLeft=value }
    private lateinit var infoRight: TextView
    internal val playerInfoRight get()=infoRight
    internal var playerInfoRightSlot: TextView
        get()=infoRight
        set(value) { infoRight=value }
    private lateinit var landInfoRow: LinearLayout
    internal val playerLandInfoRow get()=landInfoRow
    internal var playerLandInfoRowSlot: LinearLayout
        get()=landInfoRow
        set(value) { landInfoRow=value }
    internal val playerLandInfoRowInitialized get()=::landInfoRow.isInitialized
    private lateinit var landInfoLeft: TextView
    internal val playerLandInfoLeft get()=landInfoLeft
    internal var playerLandInfoLeftSlot: TextView
        get()=landInfoLeft
        set(value) { landInfoLeft=value }
    private lateinit var landInfoRight: TextView
    internal val playerLandInfoRight get()=landInfoRight
    internal var playerLandInfoRightSlot: TextView
        get()=landInfoRight
        set(value) { landInfoRight=value }

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal var player: ExoPlayer? = null
    /** 横竖屏切换时，作废旧一轮 Surface 重挂任务。 */
    internal var playerSurfaceRebindToken = 0
    internal var remote: RemoteVideo? = null
    internal var detail: Detail? = null
    internal var library: Library? = null
    internal var index = 0
    private var ticker: Job? = null
    internal var progressTicker: Job?
        get()=ticker
        set(value) { ticker=value }

    private var landscapeMode = false
    internal val playerLandscapeMode get()=landscapeMode
    internal var playerLandscapeModeSlot: Boolean
        get()=landscapeMode
        set(value) { landscapeMode=value }

    private var portraitPin: android.view.OrientationEventListener? = null
    internal var playerPortraitPin: android.view.OrientationEventListener?
        get()=portraitPin
        set(value) { portraitPin=value }
    private var portraitFullscreenFromLandscape = false
    internal var playerPortraitFullscreenFromLandscape: Boolean
        get()=portraitFullscreenFromLandscape
        set(value) { portraitFullscreenFromLandscape=value }
    private var finishAfterLandscapeRestore = false
    internal var playerFinishAfterLandscapeRestore: Boolean
        get()=finishAfterLandscapeRestore
        set(value) { finishAfterLandscapeRestore=value }

    private var portraitTopBottom = 0
    internal var playerPortraitTopBottom: Int
        get()=portraitTopBottom
        set(value) { portraitTopBottom=value }
    private var landInfoRowBottom = 0
    internal var playerLandInfoRowBottom: Int
        get()=landInfoRowBottom
        set(value) { landInfoRowBottom=value }
    private var landVideoWidth = 0
    private var landVideoHeight = 0
    internal var playerLandVideoWidth: Int
        get()=landVideoWidth
        set(value) { landVideoWidth=value }
    internal var playerLandVideoHeight: Int
        get()=landVideoHeight
        set(value) { landVideoHeight=value }
    private var foreground = false
    internal var playerForeground: Boolean
        get()=foreground
        set(value) { foreground=value }
    private var feedUnderlay = false
    internal val playerFeedUnderlay get()=feedUnderlay
    internal var playerFeedUnderlaySlot: Boolean
        get()=feedUnderlay
        set(value) { feedUnderlay=value }
    private var underlayBound = false
    internal var playerUnderlayBound: Boolean
        get()=underlayBound
        set(value) { underlayBound=value }
    private var underlayNeedsForce = false
    internal var playerUnderlayNeedsForce: Boolean
        get()=underlayNeedsForce
        set(value) { underlayNeedsForce=value }
    private var feedChromeHeld = false
    internal var playerFeedChromeHeld: Boolean
        get()=feedChromeHeld
        set(value) { feedChromeHeld=value }
    private var chromeVisible = true
    internal val playerChromeVisible get()=chromeVisible
    internal var playerChromeVisibleSlot: Boolean
        get()=chromeVisible
        set(value) { chromeVisible=value }
    private var controlsLocked = false
    internal var playerControlsLocked: Boolean
        get()=controlsLocked
        set(value) { controlsLocked=value }
    private var userSpeed = 1f
    internal val sessionUserSpeed get()=userSpeed
    internal var chipUserSpeed: Float
        get()=userSpeed
        set(value) { userSpeed=value }
    private var tempSpeed = false
    internal var playerTempSpeed: Boolean
        get()=tempSpeed
        set(value) { tempSpeed=value }
    private var pendingFullscreen = false
    internal var listenerPendingFullscreen: Boolean
        get()=pendingFullscreen
        set(value) { pendingFullscreen=value }
    private var dragging = false
    internal var playerDragging: Boolean
        get()=dragging
        set(value) { dragging=value }
    internal var progressDragging: Boolean
        get()=dragging
        set(value) { dragging=value }
    private var returningToFeed = false
    internal var playerReturningToFeed: Boolean
        get()=returningToFeed
        set(value) { returningToFeed=value }
    internal val nextSeasonState = PhoneNextSeasonState()
    internal val relatedState = PhonePlayerRelatedState()
    private fun handingBackToFeed(): Boolean =
        returningToFeed && isFinishing && PhoneSession.owner == PhoneSession.Owner.PLAYER
    internal fun handingBackToFeedForLifecycle(): Boolean = handingBackToFeed()
    private var listener: Player.Listener? = null
    internal var playerListener: Player.Listener?
        get()=listener
        set(value) { listener=value }
    internal val runtimeState = PhonePlayerRuntimeState()
    internal val playerAdjacentState = PhonePlayerAdjacentState()
    private val sleepTimer = com.hgtv.star.core.SleepTimer { android.os.SystemClock.elapsedRealtime() }
    internal val nextSeasonSleepTimer get()=sleepTimer
    private var sleepChoice = 0
    internal var playerSleepChoice: Int
        get()=sleepChoice
        set(value) { sleepChoice=value }
    internal var sleepStopped = false
    internal var debugOverlay: com.hgtv.star.phone.ui.PhoneDebugOverlay? = null
    private var debugVideo = "?"
    private var debugError = ""
    internal var listenerDebugVideo: String
        get()=debugVideo
        set(value) { debugVideo=value }
    internal var listenerDebugError: String
        get()=debugError
        set(value) { debugError=value }
    internal fun debugQualityLabel(): String =
        if (::qualityChip.isInitialized) qualityChip.text.toString() else "-"
    internal fun debugClock(ms: Long): String = clock(ms)
    private var seekFrom = 0L
    internal var playerSeekFrom: Long
        get()=seekFrom
        set(value) { seekFrom=value }
    private var lastDuration = 0L
    internal var listenerLastDuration: Long
        get()=lastDuration
        set(value) { lastDuration=value }
    private var prefetchAt = 0L
    internal var listenerPrefetchAt: Long
        get()=prefetchAt
        set(value) { prefetchAt=value }
    private var prefetchingFor = ""
    internal var progressPrefetchingFor: String
        get()=prefetchingFor
        set(value) { prefetchingFor=value }
    internal var queuedVideo: RemoteVideo? = null
    internal var queuedEpisodeId = ""
    private var hintSticky = false
    internal var playerHintSticky: Boolean
        get()=hintSticky
        set(value) { hintSticky=value }
    private var seekTarget = 0L
    internal var playerSeekTarget: Long
        get()=seekTarget
        set(value) { seekTarget=value }
    internal var swipeHintSticky: Boolean
        get()=hintSticky
        set(value) { hintSticky=value }
    internal var nextSeasonHintSticky: Boolean
        get()=hintSticky
        set(value) { hintSticky=value }
    internal var sourceRetryHintSticky: Boolean
        get()=hintSticky
        set(value) { hintSticky=value }
    private var hintToken = 0
    internal var swipeHintToken: Int
        get()=hintToken
        set(value) { hintToken=value }
    internal var sourceRetryHintToken: Int
        get()=hintToken
        set(value) { hintToken=value }
    internal var chipHintToken: Int
        get()=hintToken
        set(value) { hintToken=value }

    private var swipePausedCurrent = false
    private var swipeResumeCurrent = false
    private var swipePreviewIndex = -1
    internal var swipeCurrentPaused: Boolean
        get()=swipePausedCurrent
        set(value) { swipePausedCurrent=value }
    internal var swipeShouldResumeCurrent: Boolean
        get()=swipeResumeCurrent
        set(value) { swipeResumeCurrent=value }
    internal var swipePlaybackPreviewIndex: Int
        get()=swipePreviewIndex
        set(value) { swipePreviewIndex=value }
    private var manualNextTarget = -1
    private var manualNextPosition = 0L
    internal var listenerManualNextTarget: Int
        get()=manualNextTarget
        set(value) { manualNextTarget=value }
    internal var listenerManualNextPosition: Long
        get()=manualNextPosition
        set(value) { manualNextPosition=value }
    private var feedVideoDy = 0f
    internal var swipeFeedVideoDy: Float
        get()=feedVideoDy
        set(value) { feedVideoDy=value }

    private var chromeHideAt = 0L
    internal var playerChromeHideAt: Long
        get()=chromeHideAt
        set(value) { chromeHideAt=value }
    private var chromeEpoch = 0
    internal var relatedChromeEpoch: Int
        get()=chromeEpoch
        set(value) { chromeEpoch=value }

    private val chromeHideRunnable = object : Runnable {
        override fun run() {
            if (!::root.isInitialized || !chromeVisible || relatedState.relatedSheetOpen) return
            val now = System.currentTimeMillis()
            if (now < chromeHideAt) {
                root.postDelayed(this, chromeHideAt - now)
                return
            }
            if (sheet.isOpen) sheet.dismiss()
            if (landPanel != null) dismissLandscapeSettings()
            setChromeVisible(false)
        }
    }
    internal val relatedChromeHideRunnable get()=chromeHideRunnable


    internal val playbackSessionState = com.hgtv.star.shared.architecture.PlaybackSession()
    internal val playbackSource by lazy { PlaybackSourceCoordinator(PhoneServices.repository(this)) }
    internal val playerPlaybackSource get()=playbackSource
    private val playerHost = object : PlayerHost {
        override fun currentPositionMs(): Long =
            runCatching { player?.currentPosition ?: 0L }.getOrDefault(0L)

        override fun durationMs(): Long =
            runCatching { player?.duration ?: 0L }.getOrDefault(0L).coerceAtLeast(0L)

        override fun playbackState(): Int = player?.playbackState ?: Player.STATE_IDLE
        override fun playWhenReady(): Boolean = player?.playWhenReady == true
        override fun isPlaying(): Boolean = player?.isPlaying == true
        override fun play() { player?.play() }
        override fun pause() { player?.pause() }
        override fun seekTo(positionMs: Long) { player?.seekTo(positionMs) }
        override fun rebuildSameQuality(positionMs: Long) {
            this@PhonePlayerActivity.rebuildSameQuality(positionMs)
        }
    }
    internal val playbackCoordinator by lazy {
        PlaybackCoordinator(
            session = playbackSessionState,
            source = playbackSource,
            host = playerHost,
            library = PhoneServices.library(this),
            account = AppRuntime.accountController(this),
        )
    }

    internal var recovering = false

    internal var retryJob: Job? = null
    internal var playRequestJob: Job? = null
    internal var playRequestScope: com.hgtv.star.core.RequestScope? = null
    internal val sourceRetryState = PhoneSourceRetryState()

    private val metrics get() = PhoneServices.metrics

    internal val watchdog = com.hgtv.star.core.PictureWatchdog({ android.os.SystemClock.elapsedRealtime() })
    internal var watchdogTick: Runnable? = null

    @Volatile internal var lastFrameAt: Long = 0L
    @Volatile internal var lastSurfaceFirstFrameAt: Long = 0L

    internal val playbackToasts = com.hgtv.star.core.ToastDeduper()

    internal var errorCard: View? = null

    internal var errorAttr: com.hgtv.star.core.FailureAttribution? = null

    internal var autoRetryLabel: android.widget.TextView? = null

    internal var failureStage: String = ""

    private val danmakuLoader by lazy {
        com.hgtv.star.shared.media.DanmakuLoader(
            scope = scope,
            repository = { PhoneServices.repository(this) },
            mode = { library?.danmakuMode ?: DanmakuMode.DEFAULT },
            target = { danmakuTarget() },
            post = { block -> runOnUiThread(block) },
            alive = { !isFinishing && !isDestroyed },
            // 正式播放页换集必须等视频真正出帧；超时放行会在长缓冲时先滚弹幕。
            allowTransitionTimeout = false,
        )
    }
    internal val playerDanmakuLoader get()=danmakuLoader


    override fun onCreate(savedInstanceState: Bundle?) {
        val tEnter = android.os.SystemClock.elapsedRealtime()
        super.onCreate(savedInstanceState)
        initializePlayerActivity(tEnter)
    }




    internal fun scheduleChromeHideForMode() = scheduleChromeHide()
    internal fun applyModeForFullscreen() = applyMode()

    override fun rebuildOnSizeChange(newConfig: Configuration): Boolean = false

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (screenRebuildPending || isFinishing || isDestroyed) return
        handlePlayerConfigurationChanged(newConfig)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && playerRootInitialized && !isFinishing && !isDestroyed) {
            enterImmersive()
            syncKeepScreenOn()
        }
    }



    private val danmakuTick=object: Runnable {
        override fun run() {
            if(isFinishing || isDestroyed) return
            syncDanmakuClockNow()
            root.postDelayed(this,1000L)
        }
    }
    internal val playerDanmakuTick get()=danmakuTick

    private var danmakuReplayTarget: com.hgtv.star.shared.media.DanmakuView? = null
    internal var playerDanmakuReplayTarget: com.hgtv.star.shared.media.DanmakuView?
        get()=danmakuReplayTarget
        set(value) { danmakuReplayTarget=value }

    internal fun playSwipeTargetForSwipe(target: Int) = playSwipeTarget(target)

    internal fun applyChromeForSwipe() = applyChrome()


    internal fun playForLoad(index: Int, startAt: Long) =
        play(index, startAt, clearDanmakuOnSwitch = true)



    internal fun syncKeepScreenOnForListener() = syncKeepScreenOn()
    internal fun syncDanmakuClockNowForListener() = syncDanmakuClockNow()
    internal fun syncProgressBarsNowForListener() = syncProgressBarsNow()
    internal fun syncBufferingUiForListener(state: Int) = syncBufferingUi(state)
    internal fun syncPlayGlyphForListener() = syncPlayGlyph()
    internal fun danmakuTargetForListener() = danmakuTarget()
    internal fun updateLandscapeVideoFrameForListener(width: Int, height: Int) =
        updateLandscapeVideoFrameImpl(width, height)
    internal fun enterPendingFullscreenForListener() = enterPendingFullscreen()
    internal fun syncDebugOverlayForListener() = syncDebugOverlay()
    internal fun applyFrameModeForSettings(mode: VideoFrameMode) = applyFrameMode(mode)
    internal fun applyVideoScaleForSettings() = applyVideoScale()
    internal fun applySleepChoiceForSettings(choice: Int) = applySleepChoice(choice)
    internal val playerDanmakuLoaded get()=danmakuLoader.loaded
    internal fun loadDanmakuForSettings(episodeId: String, seriesId: String) =
        loadDanmaku(episodeId, seriesId)
    internal fun syncMediaSessionForProgress() = syncMediaSession()
    internal fun syncPlayInfoForProgress() = syncPlayInfo()
    internal fun prepareForProgress(episodeId: String, info: StreamInfo): RemoteVideo? =
        prepare(episodeId, info)
    internal fun syncLandscapeSettingsInfoForChips() = syncLandscapeSettingsInfo()
    internal fun applyDanmakuSettingsForChips() = applyDanmakuSettings()
    internal fun loadDanmakuForChips(episodeId: String, seriesId: String) =
        loadDanmaku(episodeId, seriesId)
    internal fun endTempSpeedForChips() = endTempSpeed()
    internal fun bindEpisodeUiForListener(d: Detail, episodeIndex: Int, clearDanmakuOnSwitch: Boolean) =
        bindEpisodeUi(d, episodeIndex, clearDanmakuOnSwitch)
    internal fun muteBufferingUiForListener() = muteBufferingUi()
    internal fun syncChipsForListener() = syncChips()

    private fun syncDebugOverlay() {
        syncDebugOverlayImpl()
    }



    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        handleChromeTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }



    private var landPanel: FrameLayout? = null
    internal var playerLandPanel: FrameLayout?
        get()=landPanel
        set(value) { landPanel=value }

    internal fun stopForSleepForNextSeason() = stopForSleep()
    internal fun stopForSleepForProgress() = stopForSleep()
    internal fun syncPlayGlyphForSleep() = syncPlayGlyph()
    internal fun syncKeepScreenOnForSleep() = syncKeepScreenOn()
    internal fun bindEpisodeUiForAdoption(d: Detail, episodeIndex: Int) =
        bindEpisodeUi(d, episodeIndex)
    internal fun syncVideoFrameForAdoption() = syncVideoFrame()
    internal fun syncPlayGlyphForAdoption() = syncPlayGlyph()
    internal fun syncPlayInfoForLifecycle() = syncPlayInfo()
    internal fun syncDebugOverlayForLifecycle() = syncDebugOverlay()
    internal fun enterPendingFullscreenForAdoption() = enterPendingFullscreen()
    internal fun reopenCurrentEpisodeInPlaceForAdoption(at: Long) =
        reopenCurrentEpisodeInPlace(at)
    internal fun playForReopen(index: Int, position: Long) =
        play(index, position)
    internal fun clockForControls(ms: Long): String = clock(ms)


    internal fun playForEpisodes(i: Int) {
        if (!playerControlsLocked) play(i, clearDanmakuOnSwitch = true)
    }
    internal fun scheduleChromeHideForEpisodes() = scheduleChromeHide()
    internal fun seekToForPage(fraction: Float) = seekTo(fraction)
    override fun finish() {
        preparePlayerFinish()
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleNewPlayerIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        handlePlayerResume()
    }

    override fun onPause() {
        super.onPause()
        handlePlayerPause()
    }

    override fun onDestroy() {
        handlePlayerDestroy()
        super.onDestroy()
    }

    private fun detachForHandoff() {
        detachForHandoffImpl()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (handlePlayerBack()) return
        super.onBackPressed()
    }

    internal fun toast(text: String) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun clock(ms: Long): String = com.hgtv.star.core.PlaybackInfoLine.time(ms)
}
