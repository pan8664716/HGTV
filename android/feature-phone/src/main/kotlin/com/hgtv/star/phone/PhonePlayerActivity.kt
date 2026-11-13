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
import com.hgtv.star.phone.ui.primeAspectRatio
import com.hgtv.star.phone.ui.roundedBackground
import com.hgtv.star.phone.ui.runAfterAspectRatioLayout
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

/**
 * 播放页。**竖屏与横屏是两套版面，行为按用户给的规范实现**：
 *
 * ## 竖屏（与信息流完全一致）
 *
 * | 手势 | 行为 |
 * | --- | --- |
 * | 点击 | 播放 / 暂停，屏幕中心出现播放 / 暂停按钮 |
 * | 长按左 1/3 或右 1/3 | 2 倍速，松手恢复；上方提示「2 倍速播放中」 |
 * | 长按中间 1/3 | 打开播放设置 |
 *
 * 底部信息：标题 → 标签 → 第x集｜简介 → 观看完整短剧 → 进度条。
 * 按设置里「自动消失」的秒数隐藏整块信息，点一下屏幕先恢复信息（不会顺手把片子暂停）。
 *
 * ## 横屏（全屏）
 *
 * | 手势 | 行为 |
 * | --- | --- |
 * | 点击 | 显示 / 隐藏全部信息 |
 * | 双击 | 播放 / 暂停，屏幕中心出现播放 / 暂停按钮 |
 * | 长按 | 2 倍速，松手恢复；上方提示「2 倍速播放中」 |
 * | 横滑 | 拖动进度（规范之外的补充，不占用上面三种手势） |
 *
 * 左上角「标题 第x集」，右上角三个点（播放设置），
 * 底部两行：`播放/暂停 · 当前时长 · 进度条 · 总时长` 与 `倍速 · 清晰度 · 选集`。
 *
 * ## 竖屏单击为什么不走双击判定
 *
 * 竖屏没有双击语义，等 260ms 双击窗口会让人觉得「点了没反应」，所以竖屏单击立刻生效；
 * 横屏单击是显隐界面，必须等窗口结束才能区分双击（见 [PhoneTouch]）。
 */
class PhonePlayerActivity : PhoneActivity() {

    companion object {
        const val EXTRA_SERIES_ID = "series_id"
        const val EXTRA_TITLE = "title"

        /** 从信息流的「全屏」按钮进来：画面准备好后直接进横屏。 */
        const val EXTRA_FULLSCREEN = "fullscreen"

        /** 起始集下标（0 基）。不传就按本机进度续播。 */
        const val EXTRA_EPISODE = "episode"

        /**
         * 是否从推荐信息流进来。
         *
         * **不再影响界面**：早期按它把横屏底部的「选集」藏起来，结果从推荐流进全屏
         * （最常见的一条路）永远没有选集按钮。现在按钮一律显示，这个 extra 只留作
         * 日志 / 后续埋点的来源标识。
         */
        const val EXTRA_RECOMMEND = "recommend"

        /** 起播后先让当前这一集稳定一会儿，再在后台准备下一集。 */
        private const val PREFETCH_START_MS = 2_000L

        /** 给签名地址留出的安全余量，避免下一集真正播放时已经过期。 */
        private const val PREFETCH_URL_RESERVE_MS = 30_000L

        /** `player` 批量接口一次取当前集 + 后 14 集，覆盖短剧常见的直接跳集。 */
        private const val PLAYER_MODEL_QUEUE_SIZE = 14
        internal val prefetchStartMs get()=PREFETCH_START_MS
        internal val prefetchUrlReserveMs get()=PREFETCH_URL_RESERVE_MS
        internal val playerModelQueueSize get()=PLAYER_MODEL_QUEUE_SIZE

        /**
         * 竖屏 ↔ 全屏的过渡时长。
         *
         * 播放页声明了 `configChanges=orientation`，转屏**不重建 Activity**，
         * 系统也就不给任何窗口动画——内容直接换一套布局，读起来就是「啪」一下。
         * 这一层淡入 / 放大就是补上那一段过渡。
         */
        internal const val MODE_TRANSITION_MS = 220L
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

    private lateinit var landscape: FrameLayout
    private lateinit var landscapeVideo: PlayerView
    internal val playerLandscape get()=landscape
    internal val playerLandscapeVideo get()=landscapeVideo
    internal val playerLandscapeVideoInitialized get()=::landscapeVideo.isInitialized
    /**
     * 横屏（全屏）那一层的弹幕。
     *
     * 竖屏的弹幕在 `page.danmakuView` 里，而全屏时整个 `portrait` 被 GONE 掉——
     * 没有这一层的话，用户竖屏看着有弹幕、一点全屏就「没了」。
     * 两层各持一个 `DanmakuView`，数据由 [danmakuLoader] 的 `replay` 在切形态时接上。
     */
    private lateinit var landDanmaku: com.hgtv.star.shared.media.DanmakuView
    internal val playerLandDanmaku get()=landDanmaku
    internal val playerLandDanmakuInitialized get()=::landDanmaku.isInitialized
    private lateinit var landTop: LinearLayout
    private lateinit var landBottom: LinearLayout
    internal val playerLandTop get()=landTop
    private lateinit var landFullscreen: LinearLayout
    internal val playerLandFullscreen get()=landFullscreen
    internal val playerLandFullscreenInitialized get()=::landFullscreen.isInitialized
    internal val playerLandBottom get()=landBottom
    internal val playerLandBottomInitialized get()=::landBottom.isInitialized
    private lateinit var landPlay: PhoneGlyph
    internal val playerLandPlay get()=landPlay
    private lateinit var landCurrent: TextView
    private lateinit var landSeek: SeekBar
    private lateinit var landTotal: TextView
    internal val progressLandCurrent get()=landCurrent
    internal val progressLandSeek get()=landSeek
    internal val progressLandTotal get()=landTotal
    /**
     * 进度条下面那一行「已经设置好的值」。
     *
     * 只读、常驻：全屏时用户想确认「现在是多少 P / 多少倍速 / 什么画面模式」，
     * 不该先打开播放设置再从左边找。设置面板因此只留右侧可改的一栏。
     */
    private lateinit var landSettingsInfo: TextView
    internal val playerLandSettingsInfo get()=landSettingsInfo
    internal val playerLandSettingsInfoInitialized get()=::landSettingsInfo.isInitialized
    private lateinit var speedChip: TextView
    private lateinit var qualityChip: TextView
    private lateinit var danmakuChip: TextView
    private lateinit var episodeChip: TextView
    internal val chipSpeed get()=speedChip
    internal val chipQuality get()=qualityChip
    internal val chipDanmaku get()=danmakuChip
    internal val chipEpisode get()=episodeChip

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

    /** 缓冲指示：全站统一的三点动画，状态文案直接挂在动画下方。 */
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
    /** 缓冲指示被压住的截止时刻（无感切集期间）。 */
    private var bufferingMutedUntil = 0L
    internal var playerBufferingMutedUntil: Long
        get()=bufferingMutedUntil
        set(value) { bufferingMutedUntil=value }

    private lateinit var sheet: PhoneSheet
    internal val playerSheet get()=sheet

    /**
     * 播放信息条（设置 → 播放显示）：标题 / 集数 / 时间 / 倍速，四项各自开关。
     *
     * 只在**竖屏**显示：横屏的顶部标题栏与底部控制条已经把这四样摆开了，
     * 再压一条上去就是同一件事写两遍。
     */
    private lateinit var infoRow: LinearLayout
    internal val playerInfoRow get()=infoRow
    internal val playerInfoRowInitialized get()=::infoRow.isInitialized
    /** 「播放显示」左半：剧集标题 + 当前集数（靠左、超长省略）。 */
    private lateinit var infoLeft: TextView
    internal val playerInfoLeft get()=infoLeft
    /** 「播放显示」右半：网速 + 当前时间（贴右）。 */
    private lateinit var infoRight: TextView
    internal val playerInfoRight get()=infoRight
    /** 横屏那一份「播放显示」：与竖屏同内容，常驻在屏幕顶部。 */
    private lateinit var landInfoRow: LinearLayout
    internal val playerLandInfoRow get()=landInfoRow
    internal val playerLandInfoRowInitialized get()=::landInfoRow.isInitialized
    private lateinit var landInfoLeft: TextView
    internal val playerLandInfoLeft get()=landInfoLeft
    private lateinit var landInfoRight: TextView
    internal val playerLandInfoRight get()=landInfoRight

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal var player: ExoPlayer? = null
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

    /** 退出全屏后「钉住竖屏」用的方向尺子；非空 = 还没等到设备立起来（见 [pinPortraitUntilUpright]）。 */
    private var portraitPin: android.view.OrientationEventListener? = null
    internal var playerPortraitPin: android.view.OrientationEventListener?
        get()=portraitPin
        set(value) { portraitPin=value }
    /** 当前竖屏是否由横屏播放页的竖版短剧主动进入全屏。 */
    private var portraitFullscreenFromLandscape = false
    internal var playerPortraitFullscreenFromLandscape: Boolean
        get()=portraitFullscreenFromLandscape
        set(value) { portraitFullscreenFromLandscape=value }
    /** 返回时正在先恢复横屏，恢复完成后再结束播放页。 */
    private var finishAfterLandscapeRestore = false
    internal var playerFinishAfterLandscapeRestore: Boolean
        get()=finishAfterLandscapeRestore
        set(value) { finishAfterLandscapeRestore=value }

    /**
     * 竖屏顶部标题栏在 `page` 坐标系里的下沿（像素）；0 = 还没量到。
     *
     * 弹幕层要贴着它往下铺（见 [syncDanmakuTopInset]），而不是用 `DanmakuView`
     * 那套照电视横屏定的 104dp。
     */
    private var portraitTopBottom = 0
    internal var playerPortraitTopBottom: Int
        get()=portraitTopBottom
        set(value) { portraitTopBottom=value }
    /**
     * 横屏顶部那条「播放显示」在 `landscape` 坐标系里的下沿（像素）；0 = 还没量到。
     *
     * 横屏弹幕要从它下面开始铺，否则第一行弹幕会压在剧名 / 时间上。
     */
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
    /** 本页是否在前台：屏幕常亮只看前台、真实播放意图与错误 / 定时停止状态。 */
    private var foreground = false
    internal var playerForeground: Boolean
        get()=foreground
        set(value) { foreground=value }
    /**
     * 竖屏时**不挂自己的视频层**，直接把画面交给下层推荐页的 Surface。
     *
     * 这是「推荐页 ↔ 播放页内容不断」最关键的一环：两个 Activity 用的是同一台
     * 进程级播放器，推荐页的 `SurfaceView` 一直在出帧（窗口只是被盖住，没被销毁），
     * 播放页竖屏就**不要再挂一块新的 Surface**——换宿主那一下必然空一帧，用户看到的
     * 就是闪黑。借下层出画面，播放器连 Surface 都没换过，画面是同一路。
     *
     * 三个前置条件（见 `PhoneSession.feedUnderlayUsable`）：Android 9+、从推荐页进来、
     * 下层那一块还活着。不满足时本页自己出画面（详情页、搜索结果、长按菜单等入口）。
     */
    private var feedUnderlay = false
    internal val playerFeedUnderlay get()=feedUnderlay
    internal var playerFeedUnderlaySlot: Boolean
        get()=feedUnderlay
        set(value) { feedUnderlay=value }
    /** 下层那一块 Surface 当前是不是**已经在出本页的画面**（转横屏后会被拿走）。 */
    private var underlayBound = false
    internal var playerUnderlayBound: Boolean
        get()=underlayBound
        set(value) { underlayBound=value }
    /**
     * 下一次 [attachUnderlay] 是否需要强制重挂。
     *
     * 进场时输出本来就在推荐页那一块上（连 Surface 都没换过），强制重挂反而会
     * 先清一次屏；只有从横屏退回来、或本页自己抢过输出之后才需要。
     */
    private var underlayNeedsForce = false
    internal var playerUnderlayNeedsForce: Boolean
        get()=underlayNeedsForce
        set(value) { underlayNeedsForce=value }
    /** 本页是否收着下层推荐页的控制层（见 `PhoneSession.hideFeedChrome` 的计数）。 */
    private var feedChromeHeld = false
    internal var playerFeedChromeHeld: Boolean
        get()=feedChromeHeld
        set(value) { feedChromeHeld=value }
    private var chromeVisible = true
    internal val playerChromeVisible get()=chromeVisible
    internal var playerChromeVisibleSlot: Boolean
        get()=chromeVisible
        set(value) { chromeVisible=value }
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
    internal var progressDragging: Boolean
        get()=dragging
        set(value) { dragging=value }
    /**
     * 这次 `finish()` 是「退回推荐页」而不是别的收尾。
     *
     * 退回推荐页时这一路播放要**原样交出去**：不暂停、不关地址、不换媒体源。
     * 早先 `onPause` 无条件 `pause()`，两个 Activity 之间那几十毫秒里画面会停一下、
     * 推荐页接手后再 `play()` 又要重新缓冲一次——用户读到的就是「退回去内容断了一下」。
     */
    private var returningToFeed = false
    internal var playerReturningToFeed: Boolean
        get()=returningToFeed
        set(value) { returningToFeed=value }
    /** 最后一集查到的下一季、查询代次与底部提示。 */
    internal val nextSeasonState = PhoneNextSeasonState()
    /** 相关剧集与作者作品浮层的请求代次、缓存和打开状态。 */
    internal val relatedState = PhonePlayerRelatedState()
    /**
     * 现在这一次离开是不是「正在把这一路交回推荐页」。
     *
     * 三个条件缺一不可：
     * * 是推荐页把这一路交过来的（[EXTRA_RECOMMEND]）——从详情页进来时不能这么做，
     *   否则退回详情页后声音还在响；
     * * 这次是主动 `finish()`（`isFinishing`）——按 Home 键退到后台仍然必须暂停；
     * * 会话还属于播放页（信息流还没接手）——已经接手了就更不该动它。
     */
    private fun handingBackToFeed(): Boolean =
        returningToFeed && isFinishing && PhoneSession.owner == PhoneSession.Owner.PLAYER
    internal fun handingBackToFeedForLifecycle(): Boolean = handingBackToFeed()
    /** 挂在进程单例播放器上的监听器：每次换集都要先摘掉旧的，否则会越积越多。 */
    private var listener: Player.Listener? = null
    internal var playerListener: Player.Listener?
        get()=listener
        set(value) { listener=value }
    /**
     * 前台媒体会话：接耳机线控 / 蓝牙耳机 / 锁屏与通知栏的媒体面板。
     *
     * 与 TV 端共用 `:shared` 的 [com.hgtv.star.shared.media.MediaSessionHost]，不是另一套实现。
     * 手机上这条链路比电视更常用——用户锁屏后按耳机暂停，走的就是这里。
     * 建不出来（虚拟容器把 binder 代理成 null）时静默降级，不影响播放。
     */
    /** 媒体会话、建会话失败标记与网络恢复回调。 */
    internal val runtimeState = PhonePlayerRuntimeState()
    /**
     * 「定时关闭」：`SleepTimer` 在 `:core`，与 TV 端是同一个类，只是这里的计时源同样是
     * `elapsedRealtime`（不受系统时间被改影响，也不受倍速影响）。
     *
     * **不落盘**：它是本次观看的会话状态。用户设了「30 分钟」之后关掉应用，
     * 明天再看不该莫名其妙在半小时后停住——TV 端也是这个语义。
     */
    private val sleepTimer = com.hgtv.star.core.SleepTimer { android.os.SystemClock.elapsedRealtime() }
    internal val nextSeasonSleepTimer get()=sleepTimer
    /** 「定时关闭」当前选中的档位（[com.hgtv.star.core.SleepTimer.LABELS] 下标）。 */
    private var sleepChoice = 0
    internal var playerSleepChoice: Int
        get()=sleepChoice
        set(value) { sleepChoice=value }
    /** 定时已经停过一次：hint 与状态按它走，避免 tick 每秒钟重复报同一句。 */
    internal var sleepStopped = false
    /** 调试面板（设置 → 调试模式）。只在打开时挂，关掉立刻摘掉。 */
    internal var debugOverlay: com.hgtv.star.phone.ui.PhoneDebugOverlay? = null
    /** 最近一次视频尺寸 / 播放错误：调试面板要读，但只有监听器里才有。 */
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
    /** 上一集读到过的时长：无感换集后 `duration` 已经是新一集的，写 completed 要用旧值。 */
    private var lastDuration = 0L
    internal var listenerLastDuration: Long
        get()=lastDuration
        set(value) { lastDuration=value }
    /** 下一次允许发起预热的时间（避免每秒重试）。 */
    private var prefetchAt = 0L
    internal var listenerPrefetchAt: Long
        get()=prefetchAt
        set(value) { prefetchAt=value }
    /** 正在预热的那一集（防重入）。 */
    private var prefetchingFor = ""
    internal var progressPrefetchingFor: String
        get()=prefetchingFor
        set(value) { prefetchingFor=value }
    /** 已经挂进播放列表、等着无感推进的那一集（它的 [RemoteVideo] 要留给会话）。 */
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
    /** 提示的代次：换集后那句「第N集」延迟收起时，只收自己那一句。 */
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

    // —— 上下滑换集：与推荐页同一套「整页跟手」的翻页 ——
    //
    // 推荐页是竖向 RecyclerView + PagerSnapHelper：拖动时**两页一起跟手**，上一页滑出、
    // 下一页（它自己的画面与信息栏）同时滑进来，松手后吸附到整页。
    //
    // 播放页没有列表，但可以摆出同样的两页：当前页 [page] 与相邻页 [ghostPage]
    //（同一个 `PhoneVideoPage`，绑定目标集，视频区是黑的、信息栏是真的）。
    // 拖动时两页按同一个位移量平移，观感与推荐页逐帧一致；松手吸附到位后交换引用，
    // 播放器接到新的当前页上。**不是**「滑出去再淡入一张封面」。
    private lateinit var ghostPage: PhoneVideoPage
    private lateinit var landscapeGhost: FrameLayout
    private lateinit var landscapeGhostTitle: TextView
    internal var swipeGhostPage: PhoneVideoPage
        get()=ghostPage
        set(value) { ghostPage=value }
    internal var swipeLandscapeGhost: FrameLayout
        get()=landscapeGhost
        set(value) { landscapeGhost=value }
    internal var swipeLandscapeGhostTitle: TextView
        get()=landscapeGhostTitle
        set(value) { landscapeGhostTitle=value }
    internal val swipeLandscape get()=landscape
    internal val swipeLandscapeHeight get()=landscape.height
    internal val swipePortraitHeight get()=portrait.height
    /** 这次拖动的目标方向：+1 = 下一集，-1 = 上一集，0 = 还没定 / 到头了。 */
    private var swipeDir = 0
    private var swipePrepared = false
    internal var swipeDirection: Int
        get()=swipeDir
        set(value) { swipeDir=value }
    internal var swipePreparedState: Boolean
        get()=swipePrepared
        set(value) { swipePrepared=value }
    /** 当前这一轮 pager 吸附动画：一个帧时钟同时驱动两页与底层视频。 */
    private var swipeAnimation: Runnable? = null
    internal var swipeAnimationRunnable: Runnable?
        get()=swipeAnimation
        set(value) { swipeAnimation=value }
    /** 手动上滑已经推进到播放列表下一项，等 transition 回调或 post 兜底刷新界面。 */
    private var manualNextTarget = -1
    /** 手动切换前记下的当前进度：中途换集只能保存续播点，不能写成 completed。 */
    private var manualNextPosition = 0L
    internal var listenerManualNextTarget: Int
        get()=manualNextTarget
        set(value) { manualNextTarget=value }
    internal var listenerManualNextPosition: Long
        get()=manualNextPosition
        set(value) { manualNextPosition=value }
    /** 借来的那块视频现在被平移了多少像素（翻页动画的起点）。 */
    private var feedVideoDy = 0f
    internal var swipeFeedVideoDy: Float
        get()=feedVideoDy
        set(value) { feedVideoDy=value }

    /** 播放控制信息的自动隐藏截止时刻（见 `playerPanelTimeout`）。 */
    private var chromeHideAt = 0L
    internal var playerChromeHideAt: Long
        get()=chromeHideAt
        set(value) { chromeHideAt=value }
    /**
     * 自动收起的**代次**：每排一次队就 +1，旧的定时器看到代次变了就什么都不做。
     *
     * 没有它的时候有一个很具体的毛病：设置面板开着时点「相关剧集」，面板一关一开，
     * 但旧那一轮的定时器还在路上，到点就把**刚打开的**相关剧集浮层收走——
     * 用户读到的是「点了一下相关剧集，面板闪一下就没了」。
     */
    private var chromeEpoch = 0
    internal var relatedChromeEpoch: Int
        get()=chromeEpoch
        set(value) { chromeEpoch=value }
    /** 相关剧集是浏览层：它开着时不排、也不执行自动收起。 */

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

    // —— 播放失败的自愈（与电视端同一套内核）——

    /**
     * 会话级的降级状态（`:core`）：同档重建一次 / 最多降两档 / 手动处置建议。
     *
     * **不写 `library.maxQuality`**：降档是「这台机器这一刻放不了」，不是用户想要更低的清晰度。
     * 与电视端是同一个类、同一份规则，因此两端在「1080 解不了」这件事上的处置不会分叉。
     */
    /** 播放状态机、按集预算和降级策略的共享门面。 */
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

    /**
     * 正在「同一集的同一次起播重来」。
     *
     * 它只有一个含义：重试期间播放器报出来的错误不再触发第二轮处置，
     * 否则一次失败会在几毫秒里连撞三次降级，把预算烧光。
     */
    internal var recovering = false

    /** 排着的自动重试（3/6/12 秒）；换集 / 手动重试 / 销毁时取消。 */
    internal var retryJob: Job? = null
    /** 当前正在取地址 / 准备媒体源的起播请求；换集时连网络一起取消。 */
    internal var playRequestJob: Job? = null
    internal var playRequestScope: com.hgtv.star.core.RequestScope? = null
    /** 取地址失败后的重试次数、目标集与延迟任务。 */
    internal val sourceRetryState = PhoneSourceRetryState()

    /**
     * 解码现场（`:shared` 的 [com.hgtv.star.shared.media.PlaybackMetrics]）：
     * 解码器名、初始化耗时、编码格式、丢帧、音频欠载。
     *
     * 与电视端 `PlaybackDiag` 里那几项同源，只是抽到了 `:shared`——手机上也必须能看到
     * 「到底哪一颗解码器、解的是什么编码」，否则「有声音没画面」这类问题在手机上无现场可查。
     */
    /** 解码现场：进程级那一份（见 `PhoneServices.metrics`，在那里挂到播放器上）。 */
    private val metrics get() = PhoneServices.metrics

    /** 看门狗：有声音没画面 / 一直卡在缓冲（判据在 `:core`，与电视端同一套阈值）。 */
    internal val watchdog = com.hgtv.star.core.PictureWatchdog({ android.os.SystemClock.elapsedRealtime() })
    internal var watchdogTick: Runnable? = null

    /** 最近一帧上屏的时刻（`elapsedRealtime`）；0 = 这一集还没出过画面。 */
    @Volatile internal var lastFrameAt: Long = 0L

    /** 提示去重：同一次播放里同一个 (集, 理由, 目标档位) 只弹一条。 */
    internal val playbackToasts = com.hgtv.star.core.ToastDeduper()

    /** 错误浮层（失败时挂在 [root] 上）；非空即表示当前停在错误页。 */
    internal var errorCard: View? = null

    /** 这一次失败落在哪条轨 / 哪一档：错误页的按钮按它决定。 */
    internal var errorAttr: com.hgtv.star.core.FailureAttribution? = null

    /**
     * 错误页上那行「N 秒后自动重试」的读数。
     *
     * 与电视端 `scheduleRecovery` 写进状态行的倒计时是同一件事：错误页照常显示，
     * 同时排一次自动重试，用户能看见还要等多久、也能点「停止自动重试」。
     */
    internal var autoRetryLabel: android.widget.TextView? = null

    /**
     * 这一次失败的稳定阶段（`playback_decoder_init` 之类）。
     *
     * 错误页标题按它取（`PlaybackFailures.titleOf`），与电视端同一张表：
     * 同一个故障在两端必须是同一句话，否则用户照着电视上的说法描述问题，
     * 手机上看到的却是另一句。
     */
    internal var failureStage: String = ""

    // —— 弹幕 ——
    /**
     * 弹幕加载内核（`:shared`）：与电视端同一份实现。
     *
     * 目标层每次现取（[page]）：竖屏 / 横屏、翻页时的相邻页都会换，捕获一份会画到旧 View 上。
     */
    private val danmakuLoader by lazy {
        com.hgtv.star.shared.media.DanmakuLoader(
            scope = scope,
            repository = { PhoneServices.repository(this) },
            mode = { library?.danmakuMode ?: DanmakuMode.DEFAULT },
            target = { danmakuTarget() },
            post = { block -> runOnUiThread(block) },
            alive = { !isFinishing && !isDestroyed },
        )
    }
    internal val playerDanmakuLoader get()=danmakuLoader

    // ---------------------------------------------------------------- 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        val tEnter = android.os.SystemClock.elapsedRealtime()
        super.onCreate(savedInstanceState)
        // 主题：播放页可能被系统单独拉起（最近任务 / 深链），不保证经过主页面。
        PhoneStyle.ensure(this)
        phoneEdgeToEdge()
        val seriesId = intent.getStringExtra(EXTRA_SERIES_ID).orEmpty()
        if (seriesId.isBlank()) { finish(); return }
        PhoneSession.playerForeground = true

        pendingFullscreen = intent.getBooleanExtra(EXTRA_FULLSCREEN, false)
        landscapeMode = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        // **播放页不跟随手机姿态**：进来就锁竖屏，只有用户点「全屏」按钮才转横屏
        // （那个入口带 `EXTRA_FULLSCREEN`，下面自己会锁横屏）。
        //
        // 方向交给系统策略（`USER`）：**跟着系统的「自动旋转屏幕」开关走**。
        // 开着 → 竖着拿就是竖屏、横着拿就是全屏；关着 → 停在进入时的方向，
        // 只有用户点「全屏」才转（见 `enterFullscreen`）。
        // `pendingFullscreen` 是用户从推荐页点全屏进来的，拿到实际片源尺寸后再决定全屏方向。
        if (!pendingFullscreen) {
            requestOrientation(ActivityInfo.SCREEN_ORIENTATION_USER)
        }
        // 全屏是明确的「把画面独立出来」：不借下层推荐页的 Surface。
        // 下层可能已经停在下一页，或因为转屏换了布局；继续借会透出另一条剧，
        // 甚至让两个界面抢同一个视频输出。全屏路径始终使用本页自己的 PlayerView。
        feedUnderlay = !pendingFullscreen && canBorrowFeedSurface(seriesId)

        buildUi()
        val tUi = android.os.SystemClock.elapsedRealtime()
        // 借画面时把下层的标题、进度条、导航栏收起来：本页自己画一份。
        if (feedUnderlay) {
            feedChromeHeld = true
            PhoneSession.hideFeedChrome()
        }
        load(seriesId)
        if (com.hgtv.star.shared.BuildConfig.DEBUG) {
            // 进场耗时分段。用户在「推荐 → 正式」这一步读到的那一下卡顿就是这里的时间：
            // `ui` 是建整页（四页视频 + 面板），`chrome` 是收起下层控制层，
            // 两个都跑在主线程上，期间下层那块 Surface 的合成会被一起拖住。
            android.util.Log.i(
                "HGTV-PHONE",
                "enter timing ui=${tUi - tEnter}ms chrome=${android.os.SystemClock.elapsedRealtime() - tUi}ms " +
                    "underlay=$feedUnderlay at=$tEnter shown=${android.os.SystemClock.elapsedRealtime()}"
            )
        }
    }

    /**
     * 只有播放页要放的集与会话里的集一致时，才借推荐页的 Surface。
     *
     * 集号不一致时下层画面已经不代表本页内容。部分手机 / 平板上，透明播放页
     * 无法稳定合成下层 `SurfaceView`，表现就是声音正常、画面全黑；这种情况下
     * 必须从建页开始就使用本页自己的 `PlayerView`。
     */
    private fun canBorrowFeedSurface(seriesId: String): Boolean {
        if (Build.VERSION.SDK_INT < 28) return false
        if (!intent.getBooleanExtra(EXTRA_RECOMMEND, false)) return false
        if (!PhoneSession.feedUnderlayUsable(seriesId)) return false
        val wanted = intent.getIntExtra(EXTRA_EPISODE, -1)
        return wanted < 0 || wanted == PhoneSession.index
    }

    // ---------------------------------------------------------------- 界面

    private fun buildUi() {
        // 窗口是半透明的（见 `@style/PlayerTheme`）：目的是让下层推荐页**不被 onStop**，
        // 它的窗口与视频 Surface 全程活着——这才是「进 / 退都不重新加载」的前提。
        // 窗口透明不等于画面透明：本页自己铺一层不透明黑，letterbox 与转场那一下
        // 看到的是黑，而不是下面那一页。
        // 借下层 Surface 时整页透明：只画遮罩与控制层，画面从下层透上来。
        root = FrameLayout(this).apply {
            setBackgroundColor(if (feedUnderlay) Color.TRANSPARENT else Color.BLACK)
        }

        PhoneUiScale.withOrientation(this, landscape = false) { buildPortrait() }
        PhoneUiScale.withOrientation(this, landscape = true) { buildLandscape() }

        // 中心播放 / 暂停与上方提示挂在 root 上：竖屏、横屏共用同一份，
        // 切换形态时不用各建一套，也不会出现「转屏后图标留在旧的层里」。
        centerGlyph = PhoneGlyph(this, PhoneGlyph.Kind.PAUSE).apply { tint = Color.WHITE }
        centerBox = FrameLayout(this).apply {
            // 只留图标本身（用户要求：调小、不要外面那圈全圆底板）。
            // 34 → 24dp；容器 76 → 48dp 仅用于命中/定位，本身透明。
            addView(centerGlyph, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            visibility = View.GONE
        }
        root.addView(centerBox, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))

        bufferingDots = LoadingDotsView(this)
        bufferingBox = FrameLayout(this).apply {
            addView(bufferingDots, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            visibility = View.GONE
        }
        root.addView(bufferingBox, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        hint = TextView(this).apply {
            setTextColor(Color.WHITE)
            phoneSize(13)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = roundedBackground(0x88000000.toInt(), dp(14).toFloat())
            visibility = View.GONE
        }
        root.addView(hint, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = dp(24) })

        nextSeasonState.nextSeasonNotice = TextView(this).apply {
            setTextColor(PhoneStyle.playerText)
            phoneSize(13)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = roundedBackground(0xCC000000.toInt(), dp(10).toFloat())
            visibility = View.GONE
        }
        root.addView(nextSeasonState.nextSeasonNotice, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START
        ).apply {
            leftMargin = dp(16)
            rightMargin = dp(24)
            bottomMargin = dp(24)
        })

        // 「播放显示」那一行拆成左右两半：左边是剧名 + 集数（靠左、过长省略），
        // 右边是网速 + 当前时间（贴右）。早先四项拼成一条左对齐的读数，
        // 开了时间和网速之后整条往右挤，剧名反而被省略号吃掉。
        infoLeft = TextView(this).apply {
            setTextColor(Color.WHITE)
            phoneSize(14)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(4), 0, 0, 0)
            // 这一行直接压在画面上（没有底板），亮画面里白字会糊：补一层细阴影。
            setShadowLayer(2f, 0f, 1f, 0x99000000.toInt())
        }
        infoRight = TextView(this).apply {
            setTextColor(Color.WHITE)
            phoneSize(13)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(10), 0, 0, 0)
            setShadowLayer(2f, 0f, 1f, 0x99000000.toInt())
        }
        infoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            addView(infoLeft, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ))
            addView(infoRight, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        portraitTop.addView(infoRow, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))

        sheet = PhoneSheet(root).apply {
            // **不再给系统导航条留白**（用户规范）：面板贴到屏幕最底边。
            // 点空白处 / 点「关闭」= 「让开，我要看画面」：面板与控制层一起收掉
            // （与电视端菜单打开时收起 HUD、关闭后画面干净同一条规则）。
            // 换集、换清晰度那种程序性收起不走这里，标题与进度条仍然留着。
            onUserDismiss = { setChromeVisible(false) }
            onDismiss = {
                relatedState.relatedSheetOpen = false
                // 关掉相关剧集 / 设置后，从这一刻重新计时；相关剧集作为浏览层
                // 本身不会被自动收起，但“看完这一层”之后仍应回到自动收起。
                if (chromeVisible && ::root.isInitialized) scheduleChromeHide()
            }
        }

        setContentView(root)
        applyMode()
    }

    /** 建一页播放界面（当前页与相邻页同一份配置，否则翻页时两侧观感会不一样）。 */
    internal fun makePage(): PhoneVideoPage = PhoneVideoPage(
        this,
        withTabBar = false,
        fullscreenEntry = true,
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
        // 上下滑换集（用户要求）：信息流里的同名手势是翻下一条视频，因此只在这里开。
        verticalSwipe = true,
        underlay = feedUnderlay,
    )

    /**
     * 给一页接上手势回调。
     *
     * 两页都接同一份：只有**当前页**在屏幕正中、能收到触摸（翻页过程中触摸由当前页的
     * 状态机一直持有），因此回调里一律按 [page] 取当前页，不会串到相邻页上。
     */
    internal fun wirePage(p: PhoneVideoPage) {
        p.onTap = {
            // 清屏状态下，第一下先把信息叫回来，不顺手暂停。
            if (!page.isChromeVisible()) setChromeVisible(true) else togglePlay()
        }
        p.onLongPress = { zone ->
            when (zone) {
                PhoneTouch.Zone.CENTER -> openSettings()
                else -> beginTempSpeed()
            }
        }
        p.onLongPressEnd = { endTempSpeed() }
        p.onWatch = { openEpisodes() }
        p.onTitle = { detail?.series?.let { openSeries(it) } }
        p.onFullscreen = { enterFullscreen() }
        p.onSeekStart = { dragging = true }
        p.onSeek = { fraction -> showSeekHint(fraction) }
        p.onSeekEnd = { fraction ->
            dragging = false
            seekTo(fraction)
            clearSeekHint()
        }
        // 竖屏横滑也拖进度（起点离左右边缘 24dp 以外才接管，见 PhoneTouch.edgeSafe）。
        p.onDragStart = {
            dragging = true
            seekFrom = player?.currentPosition ?: 0L
        }
        p.onDragDelta = { delta -> dragTo(delta) }
        p.onDragEnd = {
            dragging = false
            val duration = player?.duration ?: 0L
            if (duration > 0) player?.seekTo(seekTarget)
            clearSeekHint()
        }
        // 上下滑换集：向上 = 下一集，向下 = 上一集（与推荐页上滑看下一条同一手感）。
        p.onVerticalStart = { beginSwipe() }
        p.onVerticalDelta = { delta -> dragSwipe(delta) }
        p.onVerticalEnd = { direction, _ -> endSwipe(direction) }
    }

    private fun buildLandscape() {
        // 相邻集那一页（横屏版）：黑底 + 左上角「标题 第N集」，与竖屏的 ghost 页同一个角色。
        // 加在 landscape 之前，因此它永远在下面，整页滑开才露出来。
        landscapeGhost = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        landscapeGhostTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            phoneSize(15)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        landscapeGhost.addView(landscapeGhostTitle, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ).apply {
            topMargin = dp(14)
            leftMargin = dp(16)
        })
        root.addView(landscapeGhost, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        landscape = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        landscapeVideo = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
            setKeepContentOnPlayerReset(true)
        }
        landscape.addView(landscapeVideo, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // 弹幕压在画面之上、手势层之下（与竖屏 `PhoneVideoPage` 里的叠放顺序一致）。
        landDanmaku = com.hgtv.star.shared.media.DanmakuView(this).apply {
            mode = DanmakuMode.DEFAULT
            minTextSizeDp = 4f
            // 与竖屏那一层同一个基准字号（见 `applyDanmakuSettings`）。
            sizeFollowsLongEdge = true
            setClock(0, false)
        }
        landscape.addView(landDanmaku, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // 手势挂在一块**透明覆盖层**上：横屏没有「信息栏里的按钮」需要让路，
        // 覆盖层可以整屏接管，点击一律先到状态机。
        val touchLayer = View(this)
        touchLayer.setOnTouchListener(
            PhoneTouch(
                host = touchLayer,
                isLandscape = { true },
                onTap = { setChromeVisible(!chromeVisible) },
                onDoubleTap = { togglePlay() },
                // 与竖屏同一套分区（用户要求）：中间 1/3 长按 = 播放设置，
                // 左右 1/3 长按 = 2 倍速。早先横屏不分区，长按哪里都只加速，
                // 用户在全屏里找不到播放设置（只能去点右上角那三个点）。
                onLongPress = { zone ->
                    when (zone) {
                        PhoneTouch.Zone.CENTER -> { endTempSpeed(); openSettings() }
                        else -> beginTempSpeed()
                    }
                },
                onLongPressEnd = { endTempSpeed() },
                onDragStart = {
                    dragging = true
                    seekFrom = player?.currentPosition ?: 0L
                },
                onDrag = { fraction -> dragTo(fraction) },
                onDragEnd = {
                    dragging = false
                    val duration = player?.duration ?: 0L
                    if (duration > 0) player?.seekTo(seekTarget)
                    clearSeekHint()
                },
                // 横屏同样支持上下滑换集：竖屏有、横屏没有，用户会以为功能坏了。
                verticalEnabled = true,
                onVerticalStart = { beginSwipe() },
                onVertical = { delta -> dragSwipe(delta) },
                onVerticalEnd = { direction, _ -> endSwipe(direction) },
            )
        )
        landscape.addView(touchLayer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        /*
         * 横屏的「播放显示」：与竖屏同一套左右两段（剧名·集数 / 网速·时间）。
         *
         * 它**独立于控制层**：勾了任意一项就常驻（用户要求「全屏也要显示」），
         * 因此不能挂在 `landTop` 里——那一层会跟着控制层一起收起来。
         * 位置贴屏幕顶（`dp(10)`），比竖屏那条更靠上；`landTop` 让出它的高度。
         */
        landInfoLeft = TextView(this).apply {
            setTextColor(Color.WHITE)
            phoneSize(13)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setShadowLayer(2f, 0f, 1f, 0x99000000.toInt())
        }
        landInfoRight = TextView(this).apply {
            setTextColor(Color.WHITE)
            phoneSize(12)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(10), 0, 0, 0)
            setShadowLayer(2f, 0f, 1f, 0x99000000.toInt())
        }
        landInfoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            addView(landInfoLeft, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ))
            addView(landInfoRight, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        landscape.addView(landInfoRow, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ).apply {
            topMargin = dp(10)
            leftMargin = dp(16)
            rightMargin = dp(16)
        })
        // 量出它的下沿交给弹幕层：横屏弹幕从这一行之下才开始铺（见 `syncDanmakuTopInset`）。
        // 收起（GONE）时高度为 0，那一帧不采信，否则弹幕会窜回屏幕顶部。
        landInfoRow.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            if (bottom - top > 0) {
                landInfoRowBottom = bottom
                syncDanmakuTopInset()
            }
        }

        /*
         * 顶部第二行：**只剩播放设置按钮**（⋮），靠右。
         *
         * 剧名与集数已经在上面那条信息里了（`landInfoRow` 左半），这里再放一遍是重复。
         * 这一行仍属控制层：清屏时跟 `landBottom` 一起收起；信息行则常驻。
         */
        landTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(0, 0, dp(12), 0)
        }

        val more = FrameLayout(this).apply {
            // 尺寸收一档（用户要求）：36 → 28dp、图标 22 → 18dp。
            // 它贴在信息行下方的右侧，是辅助入口，不该比画面元素更显眼。
            addView(PhoneGlyph(this@PhonePlayerActivity, PhoneGlyph.Kind.MORE).apply {
                tint = Color.WHITE
            }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
            setOnClickListener { openSettings() }
        }
        landTop.addView(more, LinearLayout.LayoutParams(dp(28), dp(28)))

        landscape.addView(landTop, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
        ).apply {
            // 紧接在常驻的 `landInfoRow`（贴顶 dp(10) + 一行字）下面。
            topMargin = dp(32)
        })

        /*
         * 横屏里的竖版短剧仍然有左右黑边。这里补一颗「全屏」入口，
         * 点击后切回竖屏播放，让竖版画面真正铺满屏幕。
         *
         * 它压在画面与手势层之上，但在底部控制层之下；位置按视频实际显示范围
         * 和底栏上沿计算，避免像推荐页那样被底栏遮住。
         */
        landFullscreen = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(1), dp(5), dp(1))
            background = roundedBackground(0x33FFFFFF, dp(8).toFloat())
            addView(PhoneGlyph(this@PhonePlayerActivity, PhoneGlyph.Kind.FULLSCREEN).apply {
                tint = Color.WHITE
            }, LinearLayout.LayoutParams(dp(10), dp(10)))
            addView(TextView(this@PhonePlayerActivity).apply {
                text = "全屏"
                setTextColor(Color.WHITE)
                phoneSize(9)
                setPadding(dp(3), 0, 0, 0)
            })
            setOnClickListener { enterPortraitFullscreen() }
            visibility = View.GONE
        }
        landscape.addView(landFullscreen, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply {
            topMargin = dp(200)
        })

        // 底部两行
        landBottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
            background = bottomScrim(215)
        }

        val line1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        landPlay = PhoneGlyph(this, PhoneGlyph.Kind.PAUSE).apply { tint = Color.WHITE }
        val playBox = FrameLayout(this).apply {
            addView(landPlay, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            setOnClickListener { togglePlay() }
        }
        line1.addView(playBox, LinearLayout.LayoutParams(dp(34), dp(34)))

        landCurrent = TextView(this).apply {
            setTextColor(PhoneStyle.playerText)
            phoneSize(12)
            text = "00:00"
            setPadding(0, 0, dp(8), 0)
        }
        line1.addView(landCurrent)

        landSeek = SeekBar(this).apply {
            max = 1000
            tintSeekBar(this)
            setPadding(0, 0, 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val duration = player?.duration ?: 0L
                    if (duration > 0) landCurrent.text = clock(duration * value / 1000)
                }

                override fun onStartTrackingTouch(sb: SeekBar) { dragging = true }

                override fun onStopTrackingTouch(sb: SeekBar) {
                    dragging = false
                    val duration = player?.duration ?: 0L
                    if (duration > 0) player?.seekTo(duration * sb.progress / 1000)
                }
            })
        }
        line1.addView(landSeek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        landTotal = TextView(this).apply {
            setTextColor(PhoneStyle.playerDim)
            phoneSize(12)
            text = "00:00"
            setPadding(dp(8), 0, 0, 0)
        }
        line1.addView(landTotal)
        landBottom.addView(line1, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // 进度条下面**只有一行**：左边是常驻摘要，右边是四颗入口。
        //
        // 早先是两行（摘要独占一行 + 四颗按钮另起一行），在 1080 高的横屏上，
        // 底栏加起来吃掉 150px 画面，而且两行之间没有视觉联系——用户看到的是
        // 「一堆字」和「一排按钮」两块互不相干的东西。并成一行之后：
        //
        // ```
        // ─────────────●──────────────────────────   ← 进度条
        // 完整画面 · 缩放100% · 硬件解码   [1.0x][1080P][弹幕][选集 1]
        // ```
        //
        // 摘要只写**四颗按钮上没有的东西**（画面 / 缩放 / 解码 / 定时），
        // 清晰度、倍速、选集、弹幕已经由右侧按钮自己显示当前值，
        // 再在左边重复一遍既是冗余、也会把这一行挤到省略号。
        val line2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        landSettingsInfo = TextView(this).apply {
            setTextColor(PhoneStyle.playerDim)
            phoneSize(11)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(2), 0, dp(10), 0)
            text = ""
        }
        line2.addView(landSettingsInfo, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))

        // 倍速与弹幕一样，点一次就换下一档；要细调仍可长按画面中间打开播放设置。
        speedChip = chip(PlaybackSpeed.label(userSpeed)) { cycleSpeed() }
        qualityChip = chip("1080P") { openSettings() }
        // 弹幕快捷开关（与电视播放页底部那颗「弹幕 全屏」同一职责）：
        // 电视上是遥控器一按就开菜单；手机上一次点击直接切下一档更省事，
        // 想细调（速度 / 字号 / 透明度）仍然长按中间开播放设置。
        danmakuChip = chip("弹幕") { cycleDanmaku() }
        episodeChip = chip("选集 1") { openEpisodes() }
        line2.addView(speedChip, chipLp(0))
        line2.addView(qualityChip, chipLp(6))
        line2.addView(danmakuChip, chipLp(6))
        line2.addView(episodeChip, chipLp(6))
        landBottom.addView(line2, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        landscape.addView(landBottom, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        ))

        landscape.visibility = View.GONE
        root.addView(landscape, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    /**
     * 横屏底栏那四颗入口（倍速 / 清晰度 / 弹幕 / 选集）。
     *
     * 尺寸比早先收了一档（78×高 → 64 宽、字号 12 → 11、内边距 10/5 → 8/4）：
     * 四颗并排时原来要吃掉横屏一半的宽度，把左边的常驻摘要挤得只剩几个字。
     * 它们都在这一行的最右侧（左边摘要 `weight=1` 顶着），因此「靠右」是布局本身就保证的。
     */
    private fun chip(text: String, onClick: () -> Unit): TextView =
        PhonePlayerViewSupport.chip(this,text,onClick)

    private fun chipLp(left: Int): LinearLayout.LayoutParams =
        PhonePlayerViewSupport.chipLp(this,left)

    /** 进度条染色是 API 21 才有的，19/20 上保持系统默认（不影响功能）。 */
    private fun tintSeekBar(bar: SeekBar) = PhonePlayerViewSupport.tintSeekBar(bar)

    // ---------------------------------------------------------------- 下层画面

    // ---------------------------------------------------------------- 形态切换

    internal fun scheduleChromeHideForMode() = scheduleChromeHide()
    internal fun applyModeForFullscreen() = applyMode()

    /**
     * 播放页永远不因尺寸回调自我重建。
     *
     * 播放器、Surface 与播放会话都绑定在当前实例上；转屏时系统可能连续派发多个
     * `orientation` / `screenSize` 回调，后续回调会把“新方向”当成已建立方向并触发
     * `recreate()`，结果正在看的这一路被交接回推荐页。横竖屏由 [applyMode] 原地切换，
     * 尺寸变化只重新布局即可。
     */
    override fun rebuildOnSizeChange(newConfig: Configuration): Boolean = false

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (screenRebuildPending || isFinishing || isDestroyed) return
        handlePlayerConfigurationChanged(newConfig)
    }

    // ---------------------------------------------------------------- 播放状态

    // ---------------------------------------------------------------- 弹幕

    /**
     * 当前形态该往哪一层画弹幕。
     *
     * 竖屏在 `page` 里，全屏在 `landscape` 里——两层是各自独立的 `DanmakuView`。
     * [danmakuLoader] 每次都现取，切形态后新请求自然落到新那一层（旧数据靠 `replay` 接）。
     */
    /**
     * 每秒把弹幕时钟对齐一次（与电视端同一个节奏）。
     *
     * 弹幕层自己会按最后一次 `setClock` 外推，因此事件驱动通常够用；但换集过渡里
     * 首批弹幕要等**新一集的时钟**到达才铺上屏（见 `DanmakuView.beginTransition`），
     * 而 [DanmakuLoader.syncClock] 对「位置 / 状态 / 倍速都没变」的调用是去重的。
     * 这一下兜底保证每秒至少落地一次，过渡不会永远收不了尾。
     */
    private val danmakuTick=object: Runnable {
        override fun run() {
            if(isFinishing || isDestroyed) return
            syncDanmakuClockNow()
            root.postDelayed(this,1000L)
        }
    }

    /** 上一次把弹幕重放到哪一层（见 [replayDanmaku]）。 */
    private var danmakuReplayTarget: com.hgtv.star.shared.media.DanmakuView? = null
    internal var playerDanmakuReplayTarget: com.hgtv.star.shared.media.DanmakuView?
        get()=danmakuReplayTarget
        set(value) { danmakuReplayTarget=value }

    // ---------------------------------------------------------------- 上下滑翻页

    /** 手指按下：停掉上一轮的动画，等第一次有方向的位移再决定翻向哪一集。 */
    private fun beginSwipe() = beginSwipeImpl()

    /**
     * 拖动中：**两页一起跟手**（与推荐页的整页 pager 同一个观感）。
     *
     * 当前页跟着手指走，相邻页从另一侧同步进入——它是真正的一页 `PhoneVideoPage`
     *（视频区黑着、标题 / 标签 / 集数 / 进度条都在），不是一张封面图。
     *
     * 到头（第一集再往下 / 最后一集再往上）时位移只走 0.3 倍，做出「拉不动」的手感。
     */
    private fun dragSwipe(delta: Float) = dragSwipeImpl(delta)

    /**
     * 松手：够了就两页一起吸到整页（当前页滑出、相邻页滑到正中），不够就弹回。
     *
     * 吸附完成后**交换引用**：刚滑进来的那一页成为 [page]，播放器接到它身上；
     * 刚滑出去的那一页清空、放回另一侧备用。整个过程中用户看到的始终是两页连续运动，
     * 与推荐页 `PagerSnapHelper` 的吸附是同一个动作。
     */
    private fun endSwipe(direction: Int) = endSwipeImpl(direction)

    /** 复位两页的位置与显隐；[keepCurrent] = 刚完成交换，当前页要留在正中。 */
    private fun resetSwipeState(keepCurrent: Boolean = false) =
        resetSwipeStateImpl(keepCurrent)

    internal fun playSwipeTargetForSwipe(target: Int) = playSwipeTarget(target)

    internal fun applyVideoScaleForSwipe() = applyVideoScale()

    internal fun applyChromeForSwipe() = applyChrome()

    private fun seekTo(fraction: Float) {
        val duration = player?.duration ?: 0L
        if (duration <= 0) return
        player?.seekTo((duration * fraction).toLong())
    }

    // ---------------------------------------------------------------- 数据

    internal fun playForLoad(index: Int, startAt: Long) =
        play(index, startAt, clearDanmakuOnSwitch = true)

    /** 把播放器当前的画面尺寸补到页面上（`bind` 之后调用）。 */
    private fun syncVideoFrame() = syncVideoFrameImpl()

    private fun updateLandscapeVideoFrame(width: Int, height: Int) =
        updateLandscapeVideoFrameImpl(width, height)

    /**
     * 横屏全屏入口只给竖版片源。
     *
     * 位置取视频 FIT 后的真实下边缘，再受底部控制层上沿限制。竖版视频在横屏里
     * 通常占满高度，若只看画面下边缘，按钮会直接落到控制层后面。
     */
    private fun placeLandscapeFullscreen() = placeLandscapeFullscreenImpl()

    /**
     * 画面缩放（设置 → 播放偏好，1–100%）。
     *
     * 只缩**播放画面本身**：`PlayerView` 按中心等比缩放，页面底板仍是整屏黑，
     * 因此缩小后看到的是「画面变小、四周留黑」，与 TV 的 `applyVideoScale` 同一效果。
     */
    private fun applyVideoScale() = applyVideoScaleImpl()

    /**
     * 把「播放显示」那四项铺到这一行上：**剧名 / 集数靠左，网速 / 时间靠右**。
     *
     * 显隐规则（与电视端同一套口径，用户指定）：
     *
     * | 四项开关 | 显示 | 是否跟随控制层 |
     * | --- | --- | --- |
     * | 全都没勾 | **四项全显示**（默认形态） | 是：清屏时一起收起 |
     * | 勾了任意一项 | 只显示勾选的那几项 | 否：**一直显示**，清屏也不收 |
     *
     * 横屏与竖屏各有一份（`landInfoRow` / `infoRow`），规则完全相同。
     */
    private fun syncPlayInfo() = syncPlayInfoImpl()

    internal fun play(
        episodeIndex: Int,
        startAt: Long = 0L,
        clearDanmakuOnSwitch: Boolean = false,
    ) {
        val d = detail ?: return
        if (episodeIndex !in d.episodes.indices) return
        nextSeasonState.nextSeasonFinishedFor = ""
        val sameEpisode = episodeIndex == index
        if (!sameEpisode) {
            cancelPlaybackRequest()
            cancelSourceRetry()
            // 上一集的帧时间不能算到这一集头上；看门狗要靠它判断新画面是否真的上屏。
            lastFrameAt = 0L
        }
        index = episodeIndex
        val episodeId = d.episodes[episodeIndex]
        playbackCoordinator.open(
            seriesId = d.series.id,
            episodeId = episodeId,
            episodeIndex = episodeIndex,
            resumeAtMs = startAt,
            autoplay = true,
            resetEpisode = !sameEpisode,
        )
        // 换了一集：按集计的重试 / 降级额度全部归零（与电视端 `onEpisodeStart` 同一入口）。
        // 同一集重来（换档 / 手动重试）不在这里清：那会把「每集只重建一次」变成无限次。
        if (!sameEpisode) {
            playbackToasts.clear()
        }
        recovering = false
        cancelAutoRetry()
        clearPlaybackError()
        bindEpisodeUi(d, episodeIndex, clearDanmakuOnSwitch)
        prepareNextSeasonIfNeeded(d)

        // 预热命中：这一集的首块与地址都已经在手上了，只差挂媒体源（几十毫秒）。
        // 上限也要对得上：预热那一份比现在生效的档位高时不能接（见 `PhoneSession.takeNext`）。
        val warm = PhoneSession.takeNext(episodeId, playbackMaxQuality)
        if (warm != null) {
            // 旧媒体源先解绑（不 release 播放器），再挂预热好的这一集。
            listener?.let { player?.removeListener(it) }
            listener = null
            // 借下层 Surface 时**不要动推荐页那一块**：它是本页现在的输出端。
            if (!feedUnderlay) PhoneServices.detach(page.playerView)
            PhoneServices.detach(landscapeVideo)
            attach(warm, startAt)
            return
        }

        releasePlayer()
        setBufferingLabel("正在获取播放地址…")
        val requestScope = com.hgtv.star.core.RequestScope()
        playRequestScope = requestScope
        playRequestJob = scope.launch {
            try {
            // 上限读**实际生效**的那一档（自动降档结果优先），否则降档只在当前这一集生效。
            val maxQuality = playbackMaxQuality
            // 播放器打开先用 player 场景取当前集 + 后 14 集，避免快速跳集时二次请求。
            val modelQueue = d.episodes.drop(episodeIndex + 1).take(PLAYER_MODEL_QUEUE_SIZE)
            val source = withContext(Dispatchers.IO) {
                requestScope.run {
                    runCatching {
                    playbackSource.stream(episodeId, maxQuality, d.playback, queue = modelQueue)
                    }
                }
            }
            if (!isActive || playRequestScope !== requestScope) return@launch
            val info = source.getOrNull()
            if (info == null) {
                val problem = source.exceptionOrNull()
                invalidateEpisodeCacheForRetry(episodeId, null)
                onEpisodeSourceMissing(d, episodeIndex, startAt, problem)
                return@launch
            }

            val preparedSource = withContext(Dispatchers.IO) {
                requestScope.run { runCatching { prepareSource(episodeId, info) } }
            }
            if (!isActive || playRequestScope !== requestScope) return@launch
            val prepared = preparedSource.getOrNull()
            if (prepared == null) {
                val problem = preparedSource.exceptionOrNull()
                // 本地准备会读落盘索引 / 首块；缓存对应的文件或地址不认了，
                // 继续重试同一份缓存只会重复失败。先整体作废并换请求 device_id。
                if (problem == null || problem is java.io.IOException) {
                    invalidateEpisodeCacheForRetry(episodeId, info)
                }
                onEpisodeSourceMissing(d, episodeIndex, startAt, problem)
                return@launch
            }

            if (!isActive || playRequestScope !== requestScope) {
                prepared.close()
                return@launch
            }
            attach(prepared, startAt)
            } finally {
                if (playRequestScope === requestScope) {
                    playRequestScope = null
                    playRequestJob = null
                }
            }
        }
    }

    /**
     * 上下滑换集优先推进 ExoPlayer 已经挂好的下一项。
     *
     * 自动连播之所以无感，是因为它没有重新设置媒体源；手动上滑如果仍走 [play]，
     * 即使地址已经预热，也会 stop / setMediaSource / prepare 一遍，用户看到的
     * 就是「先加载再播放」。这里直接 seek 到队列里的下一项，复用同一条播放链路。
     *
     * 只有下一项确实已在播放列表里才走这条快路；否则仍由 [play] 走常规兜底。
     */
    private fun playSwipeTarget(target: Int) {
        cancelAutoRetry()
        cancelPlaybackRequest()
        cancelSourceRetry()
        if (target == index + 1 && seekToQueuedNext(target)) return
        play(target, clearDanmakuOnSwitch = true)
    }

    private fun seekToQueuedNext(target: Int): Boolean {
        if (!PhoneSession.nextQueued) return false
        val video = queuedVideo ?: return false
        if (queuedEpisodeId.isEmpty()) return false
        val exo = player ?: return false
        val current = exo.currentMediaItemIndex
        if (current < 0 || exo.mediaItemCount <= current + 1) return false
        val verticalSource = if (video.info.videoWidth > 0 && video.info.videoHeight > 0) {
            video.info.videoHeight > video.info.videoWidth
        } else {
            detail?.series?.vertical == true
        }
        page.playerView.primeAspectRatio(
            video.info.videoWidth,
            video.info.videoHeight,
            verticalSource,
        )
        landscapeVideo.primeAspectRatio(
            video.info.videoWidth,
            video.info.videoHeight,
            verticalSource,
        )
        manualNextTarget = target
        manualNextPosition = exo.currentPosition.coerceAtLeast(0L)
        exo.playWhenReady = true
        if (runCatching { exo.seekToNextMediaItem() }.isFailure) {
            manualNextTarget = -1
            manualNextPosition = 0L
            return false
        }
        // `onMediaItemTransition` 正常会先到；这里只做一次兜底，避免个别设备不回调时
        // 画面已经切过去、标题与集数却还停在上一集。
        root.post {
            if (manualNextTarget == target) {
                manualNextTarget = -1
                onAdvanced(
                    target,
                    completedPrevious = false,
                    previousPosition = manualNextPosition,
                    clearDanmakuOnSwitch = true,
                )
                manualNextPosition = 0L
            }
        }
        return true
    }

    /**
     * 换集时**取不到播放地址 / 准备失败**。
     *
     * 这条路径原先只往 `landInfoLeft` 写一句「第 N 集 · 准备失败」就返回，而 `play()`
     * 在此之前已经 [releasePlayer] 了——页面于是停在一块黑屏上：既放不了，也看不出在等什么；
     * 竖屏连那行字都看不到（`landInfoLeft` 只在横屏存在）。
     * 用户报的「第三集播完进不了第四集，提示没准备好，然后就退不回去了」就是这条路径。
     *
     * 现在按「网络瞬态」处理：先自动重试两次（超时立即重试一次，之后退避 6 秒；
     * 播放器留着、上一集的最后一帧
     * 留在屏幕上，不黑屏）；仍不行才摆错误页——那里的「立即重试」与「下一集」
     * 才是用户真正需要的出口，而不是一句没人看得见的字。
     */
    /** 本地准备失败必须保留异常：调用方据此决定是否作废缓存后重取。 */
    private fun prepareSource(episodeId: String, info: StreamInfo): RemoteVideo =
        playbackSource.prepare(episodeId, info)

    /** 预热路径只需要结果，失败就丢掉；播放起播路径走 [prepareSource] 保留异常。 */
    private fun prepare(episodeId: String, info: StreamInfo): RemoteVideo? =
        runCatching { prepareSource(episodeId, info) }.getOrNull()

    private fun attach(video: RemoteVideo, startAt: Long) {
        remote = video
        val d = detail
        val episodeIndex = index
        val session = playbackSessionState.sessionId
        val exo = PhoneServices.player(this, library?.decoderMode)
        val factory = DataSource.Factory { VideoDataSource(video) }
        val episodeId = d?.episodes?.getOrNull(index).orEmpty()
        // 先停再挂（见 `swapMediaSource`）：预热命中时播放器可能正在出帧，
        // 直接换源会在 flush 编解码器时抛 0x80000000，随即报 4003。
        exo.swapMediaSource(episodeId, video.info.url, factory)
        // 新源已经替换整条播放列表：旧的“下一集已排队”标志不能跨到这一集，
        // 否则最后一集片尾会被误判成还有队列项而卡住。
        queuedVideo?.let { runCatching { it.close() } }
        queuedVideo = null
        queuedEpisodeId = ""
        PhoneSession.clearNext()
        exo.setPlaybackSpeed(userSpeed)
        exo.playWhenReady = true
        attachPlayerListener(exo)
        if (d != null) {
            PhoneSession.publish(d, index, video, video.info.quality, PhoneSession.Owner.PLAYER)
        }

        player = exo
        if (landscapeMode) {
            page.playerView.player = null
            landscapeVideo.player = exo
        } else {
            landscapeVideo.player = null
            if (!(feedUnderlay && attachUnderlay())) {
                dropUnderlay()
                PhoneSession.underlayActive = false
                page.playerView.player = exo
            }
        }
        // `PlayerView.setPlayer()` 会先按播放器上一集的 videoSize 覆盖宽高比；
        // 必须在挂好之后、prepare 之前再写入本集的真实尺寸，首帧才不会先按旧比例铺开。
        val verticalSource = if (video.info.videoWidth > 0 && video.info.videoHeight > 0) {
            video.info.videoHeight > video.info.videoWidth
        } else {
            d?.series?.vertical == true
        }
        page.playerView.primeAspectRatio(
            video.info.videoWidth,
            video.info.videoHeight,
            verticalSource,
        )
        landscapeVideo.primeAspectRatio(
            video.info.videoWidth,
            video.info.videoHeight,
            verticalSource,
        )
        setBufferingLabel("正在缓冲…")
        syncVideoFrame()
        val prepare = {
            if (
                player === exo &&
                index == episodeIndex &&
                playbackSessionState.sessionId == session
            ) {
                exo.prepare()
                if (startAt > 0) exo.seekTo(startAt)
            }
        }
        if (!landscapeMode && feedUnderlay && underlayBound) {
            prepare()
        } else {
            val target = if (landscapeMode) landscapeVideo else page.playerView
            target.runAfterAspectRatioLayout(prepare)
        }
        // 媒体源已经挂好，交接结束：之后退到后台就按正常规则暂停。
        PhoneSession.endHandoff()
        syncPlayGlyph()
        syncChips()
        startTicker()
        armPictureWatchdog()
        // 这一集真的挂上了：取地址失败的重试计数归零（见 `onEpisodeSourceMissing`）。
        // 不清的话，网络恢复回调会拿一个过期的计数把正常播放的这一集重来一遍。
        sourceRetryState.episode = ""
        sourceRetryState.count = 0

        if (pendingFullscreen) {
            // 画面已经挂上解码器，这时切换全屏不会黑屏。
            root.post { enterPendingFullscreen() }
        }
    }

    /**
     * 完成从推荐页带进来的「全屏」意图。
     *
     * 不能一律转横屏：横屏推荐页里的竖版短剧点全屏时，应直接进入竖屏全屏；
     * 否则会先停在横屏播放页，用户还得再点一次全屏。
     */
    private fun enterPendingFullscreen() {
        if (!pendingFullscreen) return
        if (landVideoWidth <= 0 || landVideoHeight <= 0) return
        pendingFullscreen = false
        if (landscapeMode && landVideoHeight > landVideoWidth) {
            enterPortraitFullscreen()
        } else {
            enterFullscreen()
        }
    }

    internal fun syncKeepScreenOnForListener() = syncKeepScreenOn()
    internal fun syncDanmakuClockNowForListener() = syncDanmakuClockNow()
    internal fun syncProgressBarsNowForListener() = syncProgressBarsNow()
    internal fun syncBufferingUiForListener(state: Int) = syncBufferingUi(state)
    internal fun syncPlayGlyphForListener() = syncPlayGlyph()
    internal fun danmakuTargetForListener() = danmakuTarget()
    internal fun updateLandscapeVideoFrameForListener(width: Int, height: Int) =
        updateLandscapeVideoFrame(width, height)
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

    /**
     * 调试模式那一块面板：打开就挂、关掉就摘。
     *
     * 与电视端的 `syncDebugOverlay` 同一条规则：把 `:shared` 的「近况」出口接到
     * [com.hgtv.star.shared.debug.DebugLog]，然后每秒把播放现场刷一遍。
     * 面板**不吃触摸**（见 `PhoneDebugOverlay`），开着它照样能点按钮。
     */
    private fun syncDebugOverlay() {
        syncDebugOverlayImpl()
    }

    /**
     * 相关剧集浮层：播放设置里的「相关剧集」使用这一份。
     *
     * 摆法与电视端播放设置里的相关剧集（`buildRelatedRow`）一一对应：
     *
     * ```
     * 《剧名》已经看完 · 换一部接着看
     * ┌──────┐ ┌──────┐ ┌──────┐ …      ← 海报卡片行，横向滚动看全
     * │ 第2季 │ │      │ │      │
     * └──────┘ └──────┘ └──────┘
     *   剧名     剧名     剧名
     *   正在看   预计上线
     * ```
     *
     * 三处与电视端对齐，也是「孤零零几个文字」那版的问题所在：
     * * **卡片**而不是剧名文字：海报 + 季号角标 + 状态行，和详情页那一栏长得一样；
     * * **不截断**：早先 `take(12)` 之后就只能看到前十二部，现在是全部横向滚；
     * * **不压暗画面**：电视端的 `RelatedPanel` 把 `dimAmount` 归零，片尾那一帧仍然亮着，
     *   用户看得见「刚刚放完的是什么」。
     *
     * 数据与详情页共用 [PhoneSession.relatedWorks]（电视端同样共用 `relatedWorks`）：
     * 从详情页点进来的这一路已经取好了，打开浮层时是零等待。
     */
    private fun releasePlayer() {
        releasePlayerImpl()
    }

    // ---------------------------------------------------------------- 网络恢复

    // ---------------------------------------------------------------- 面板自动收起

    /**
     * 播放控制信息「无操作几秒后自动隐藏」（设置 → 播放界面，2–15 秒）。
     *
     * 与 TV 的 `playerPanelTimeout` 同一份落盘值：计时从**最后一次操作**算起，
     * 到点后收起播放设置 / 选集面板和整层标题、进度条；任何触摸都会重新显示并重新计时
     * （见 [dispatchTouchEvent]）。相关剧集浮层是浏览层，不排自动隐藏。
     */
    /**
     * 面板打开期间，**每一次触摸**都把自动收起的计时往后推。
     *
     * 按下时重新排队；拖动（MOVE）与抬手（UP）只改截止时刻——否则拖一条滑块拖了七八秒，
     * 面板会在手指还按着的时候被收走。MOVE 不重新排队，避免一帧一个 post。
     */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        handleChromeTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    // ---------------------------------------------------------------- 播放设置

    /**
     * 播放设置。**竖屏从底部出、横屏只有右侧 1/3 一栏**。
     *
     * 横屏版式（用户规范）：
     *
     * ```
     * ┌──────────────────────────┬─────────────┐
     * │                          │ 播放设置  关闭│
     * │  画面（不遮）             │ 清晰度      │
     * │                          │  360 … 1080 │
     * │                          │ 倍速        │
     * │                          │  1.0 1.25 … │
     * │                          │ 画面 / 解码 │
     * │                          │ 弹幕四项    │
     * └──────────────────────────┴─────────────┘
     *   左侧 2/3（点一下收起）        右侧 1/3
     * ```
     *
     * 「我现在是什么设置」不在这块面板里：它常驻在**底部进度条下面**那一行
     * （见 [landSettingsInfo]），面板只负责改。左侧 2/3 留出画面，点一下收起。
     */
    // ---------------------------------------------------------------- 横屏播放设置

    /** 横屏面板的外壳（左 2/3 点击收起 + 右 1/3 设置）；为 null = 没打开。 */
    private var landPanel: FrameLayout? = null
    internal var playerLandPanel: FrameLayout?
        get()=landPanel
        set(value) { landPanel=value }

    /**
     * 播放设置的各行（竖屏 / 横屏共用，[perLine] 只在横屏的 1/3 抽屉里折行）。
     *
     * 顺序与电视端一致：先「怎么看」（清晰度 / 倍速 / 画面 / 解码 / 缩放），
     * 再「弹幕怎么滚」（显示方式 / 速度 / 字号 / 透明度）。
     */
    internal fun stopForSleepForNextSeason() = stopForSleep()
    internal fun stopForSleepForProgress() = stopForSleep()
    internal fun syncPlayGlyphForSleep() = syncPlayGlyph()
    internal fun syncKeepScreenOnForSleep() = syncKeepScreenOn()
    internal fun bindEpisodeUiForAdoption(d: Detail, episodeIndex: Int) =
        bindEpisodeUi(d, episodeIndex)
    internal fun syncVideoFrameForAdoption() = syncVideoFrame()
    internal fun syncPlayGlyphForAdoption() = syncPlayGlyph()
    internal fun enterPendingFullscreenForAdoption() = enterPendingFullscreen()
    internal fun reopenCurrentEpisodeInPlaceForAdoption(at: Long) =
        reopenCurrentEpisodeInPlace(at)
    internal fun playForReopen(index: Int, position: Long) =
        play(index, position)
    internal fun clockForControls(ms: Long): String = clock(ms)

    /**
     * 横屏播放设置：只保留右侧 40% 的可改栏。
     *
     * 已经设置好的值常驻在**进度条下方**（见 [landSettingsInfo]），打开面板时不再在左边
     * 重复一遍：左边 2/3 留出画面，点一下即可收起。这样「我现在的设置是什么」与
     * 「我要改哪一项」分别在底部摘要和右侧抽屉里，不用打开面板才能确认。
     *
     * **选集不在这里**：它是底部右下角那颗独立的按钮，点开的是自己那条抽屉。
     * 右边宽 40% 屏，选项按每行 3 个折行，整列通常会超过屏高——因此整列放进
     * `ScrollView`（`height=0 + weight=1`），滑到底能看到最后一行，不会像早先那样被裁掉。
     */
    private fun openLandscapeSettings(lib: Library) =
        openLandscapeSettingsImpl(lib)

    /**
     * 把「已经设置好的值」刷到进度条下面那一行。
     *
     * 这一行是**常驻摘要**，不依赖设置面板是否打开；换集、换倍速、换清晰度、
     * 换画面模式 / 缩放 / 解码 / 弹幕 / 定时之后都会经 [syncChips] 走到这里。
     */
    private fun syncLandscapeSettingsInfo() = syncLandscapeSettingsInfoImpl()

    private fun dismissLandscapeSettings() = dismissLandscapeSettingsImpl()
    internal fun dismissLandscapeSettingsForRelated() = dismissLandscapeSettings()
    internal fun buildSettingsRowsForLandscape(panel: LinearLayout, lib: Library) =
        buildSettingsRowsImpl(panel, lib, perLine = 3)
    internal fun setChromeVisibleForLandscape(visible: Boolean) = setChromeVisible(visible)
    internal fun scheduleChromeHideForLandscape() = scheduleChromeHide()

    /**
     * 按用户的「画面模式」把画面落到位。
     *
     * 早先这里对竖屏片源强制 ZOOM：首集可能按卡片字段走了 FIT，切到下一集时
     * 实际尺寸已知、突然改成 ZOOM，画面就会「等比放大、左右丢失」。
     */
    private fun syncFrameMode() = syncFrameModeImpl()

    private fun applyFrameMode(mode: VideoFrameMode) = applyFrameModeImpl(mode)

    // ---------------------------------------------------------------- 选集

    /**
     * 选集。**与播放设置分开**：横竖屏都用底部限高浮层，
     * 都不与「播放设置」共用一个面板。
     *
     * 为什么分开：选集是高频、连续的动作（看两集就想换），播放设置是低频、改一次就走的；
     * 混在一个面板里，用户想换集得先找到那一段，面板还更长。
     */
    internal fun playForEpisodes(i: Int) = play(i, clearDanmakuOnSwitch = true)
    internal fun scheduleChromeHideForEpisodes() = scheduleChromeHide()

    // ---------------------------------------------------------------- 生命周期

    /**
     * 退出播放页：不要系统转场动画。
     *
     * 退回去时目标页（信息流）自己就是亮的画面，但转场那 200ms 里系统会把本页淡出、
     * 中间露出窗口底色——读起来就是「退出去也闪一下」。直接切过去更连贯。
     */
    override fun finish() {
        preparePlayerFinish()
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    /**
     * 换一部接着看时的安全网。
     *
     * 本页是 `singleTop`：同栈顶再 `startActivity` 会复用当前实例而不是新建。
     * 正常情况下 [openSeries] 先 `finish()` 再开新的（旧实例已在收尾，不会被复用），
     * 但只要有一次被复用，`intent` 换了、`detail` 却还是上一部，页面就会「点了没反应」。
     * 这里按新 intent 就地重建：详情、集号、媒体源全部属于上一部，重建比原地改状态安全。
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleNewPlayerIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        PhoneSession.playerForeground = true
        foreground = true
        // 网络恢复时立刻重取地址（与电视端 `refetchAddressOnNetwork` 同一条）：
        // 手机切 Wi-Fi / 5G 比电视频繁，断网那一下正在取地址的话不该干等退避计时。
        watchNetwork()
        // 上面又压了一层播放页（相关剧集）时，那一层会把自己的视频层挂到同一台播放器上，
        // 借来的这块 Surface 就不是输出端了。回到本页必须把输出抢回来，否则是一片黑。
        restoreUnderlayIfNeeded()
        // 设置页可能刚改过弹幕：回到本页重刷一次，不等下一次换集。
        applyDanmakuSettings()
        // 每秒对齐一次弹幕时钟（见 [danmakuTick]）。
        root.removeCallbacks(danmakuTick)
        root.post(danmakuTick)
        // 播放显示四项也可能刚在设置页改过：回到本页立刻按新开关重排顶部信息。
        syncPlayInfo()
        // 回到前台：重新装看门狗（暂停过的那一段不该计入「等画面」的时间）。
        if (!isFinishing && !isDestroyed) armPictureWatchdog()
        // 回到前台：把会话重新激活并立刻对一次状态，锁屏上那一条不会停在「已暂停」。
        syncMediaSession()
        syncDebugOverlay()
        syncKeepScreenOn()
        if (chromeVisible) scheduleChromeHide()
    }

    override fun onPause() {
        super.onPause()
        PhoneSession.playerForeground = false
        foreground = false
        unwatchNetwork()
        syncKeepScreenOn()
        chromeEpoch++
        root.removeCallbacks(chromeHideRunnable)
        // 退到后台就不再每秒对弹幕时钟（回来时由 onResume 重新排）。
        root.removeCallbacks(danmakuTick)
        // 退到前台之外：暂停与后台都不是卡死，看门狗一律撤掉（回来时由 onResume 重装）。
        disarmPictureWatchdog()
        // 退了前台就不再占着系统媒体面板：那时面板上的按键应该交给真正在前台的应用。
        runtimeState.mediaSession?.deactivate()
        val handoff = handingBackToFeed()
        // 退回推荐页时**不在这里**还控制层：这一刻推荐页还没接完（`A.onResume` 还没跑），
        // 它手上的集号与进度条都还是离开前那一份，先亮出来就是「退回去标题 / 集号 /
        // 进度条先跳一下再对上」。改由推荐页在自己 `onShown()` 的最后调
        // `PhoneSession.ensureFeedChrome()`——那一步数据已经对齐，两边同时在位也就不会露空。
        // 交接没走成（推荐页没接上）时由 `onDestroy` 兜底还回去。
        player?.let { exo ->
            // **先记状态再暂停**：退回推荐页时信息流会按这个值决定要不要续播。
            // 顺序反了（先 pause）记到的永远是 false，用户看到的就成了「退回去就不动了」。
            PhoneSession.resumeOnAdopt = exo.playWhenReady
            if (com.hgtv.star.shared.BuildConfig.DEBUG) {
                android.util.Log.i(
                    "HGTV-PHONE",
                    "player pause handoff=$handoff pos=${exo.currentPosition} " +
                        "state=${exo.playbackState} pwr=${exo.playWhenReady} " +
                        "items=${exo.mediaItemCount} cur=${exo.currentMediaItemIndex}"
                )
            }
            saveProgress(exo.currentPosition, exo.duration)
            // 退回推荐页时**不暂停**：这一路马上要在信息流里继续放。
            // 暂停会让退回的那一屏先停一拍再恢复（还要重新缓冲），读起来就是「内容断了」。
            if (!handoff) exo.pause()
        }
    }

    override fun onDestroy() {
        PhoneSession.playerForeground = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        PhoneDebugReport.resetSamples()
        if (::root.isInitialized) root.removeCallbacks(chromeHideRunnable)
        // 方向尺子跟着页面一起收：留着会在页面已经没了之后还去改方向。
        releasePortraitPin()
        disarmPictureWatchdog()
        cancelAutoRetry()
        clearPlaybackError()
        player?.let { runCatching { saveProgress(it.currentPosition, it.duration) } }
        // **交给推荐页**：不 release 播放器、不关 remote、不清媒体源。
        // 退回信息流时它会把同一个 `PlayerView` 重新绑上去接着播——
        // 早先这里 `releasePlayer()` 把媒体源和地址一起丢了，于是每次返回都重新取地址、
        // 重新 prepare、从头起播（用户看到的「每退一次就重新加载」就是这个）。
        detachForHandoff()
        // 借过下层画面就把它自己的控制层还回去；不然退回推荐页会少一套标题与导航栏。
        // 推荐页已经在自己 `onShown()` 里要回去了的话，这一句是空操作（见 `ensureFeedChrome`）。
        if (feedChromeHeld) {
            feedChromeHeld = false
            PhoneSession.restoreFeedChrome()
        }
        // 借出去的那块 Surface 一直在出帧：**不要**在这里清标志。
        // 信息流的 `onResume` 早于本页 `onDestroy`，它接受手时就是靠这个标志
        // 判断「不用重挂」——清早了它会把同一台播放器摘下来再挂回去，白闪一帧。
        // 由信息流接手后自己清零（见 `PhoneFeedView.adoptPlaying`）。
        if (feedUnderlay && !underlayBound) PhoneSession.underlayActive = false
        runtimeState.mediaSession?.release()
        runtimeState.mediaSession = null
        scope.cancel()
        super.onDestroy()
    }

    /**
     * 离开播放页但不销毁这一路播放：只解绑 Surface、摘监听、暂停。
     *
     * 与 [releasePlayer] 的区别就是「留给谁」：换集时旧的确实没用了，可以关；
     * 退出播放页时这一路要交回信息流，关掉就等于让它重来一遍。
     */
    private fun detachForHandoff() {
        detachForHandoffImpl()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (handlePlayerBack()) return
        super.onBackPressed()
    }

    /** 轻提示：按钮暂时不可用时告诉用户「在等什么」，而不是毫无反应。 */
    internal fun toast(text: String) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 位置读数：与电视端 `formatTime` 同一份（`:core` 的 `PlaybackInfoLine.time`）。 */
    private fun clock(ms: Long): String = com.hgtv.star.core.PlaybackInfoLine.time(ms)
}
