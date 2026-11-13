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
    internal var playerLandscapeContainer: FrameLayout
        get()=landscape
        set(value) { landscape=value }
    internal var playerLandscapeVideoSlot: PlayerView
        get()=landscapeVideo
        set(value) { landscapeVideo=value }
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
    /**
     * 进度条下面那一行「已经设置好的值」。
     *
     * 只读、常驻：全屏时用户想确认「现在是多少 P / 多少倍速 / 什么画面模式」，
     * 不该先打开播放设置再从左边找。设置面板因此只留右侧可改的一栏。
     */
    private lateinit var landSettingsInfo: TextView
    internal val playerLandSettingsInfo get()=landSettingsInfo
    internal var playerLandSettingsInfoSlot: TextView
        get()=landSettingsInfo
        set(value) { landSettingsInfo=value }
    internal val playerLandSettingsInfoInitialized get()=::landSettingsInfo.isInitialized
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
    internal var playerSheetSlot: PhoneSheet
        get()=sheet
        set(value) { sheet=value }

    /**
     * 播放信息条（设置 → 播放显示）：标题 / 集数 / 时间 / 倍速，四项各自开关。
     *
     * 只在**竖屏**显示：横屏的顶部标题栏与底部控制条已经把这四样摆开了，
     * 再压一条上去就是同一件事写两遍。
     */
    private lateinit var infoRow: LinearLayout
    internal val playerInfoRow get()=infoRow
    internal var playerInfoRowSlot: LinearLayout
        get()=infoRow
        set(value) { infoRow=value }
    internal val playerInfoRowInitialized get()=::infoRow.isInitialized
    /** 「播放显示」左半：剧集标题 + 当前集数（靠左、超长省略）。 */
    private lateinit var infoLeft: TextView
    internal val playerInfoLeft get()=infoLeft
    internal var playerInfoLeftSlot: TextView
        get()=infoLeft
        set(value) { infoLeft=value }
    /** 「播放显示」右半：网速 + 当前时间（贴右）。 */
    private lateinit var infoRight: TextView
    internal val playerInfoRight get()=infoRight
    internal var playerInfoRightSlot: TextView
        get()=infoRight
        set(value) { infoRight=value }
    /** 横屏那一份「播放显示」：与竖屏同内容，常驻在屏幕顶部。 */
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
    internal var playerDragging: Boolean
        get()=dragging
        set(value) { dragging=value }
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
        initializePlayerActivity(tEnter)
    }

    // ---------------------------------------------------------------- 界面

    /**
     * 横屏底栏那四颗入口（倍速 / 清晰度 / 弹幕 / 选集）。
     *
     * 尺寸比早先收了一档（78×高 → 64 宽、字号 12 → 11、内边距 10/5 → 8/4）：
     * 四颗并排时原来要吃掉横屏一半的宽度，把左边的常驻摘要挤得只剩几个字。
     * 它们都在这一行的最右侧（左边摘要 `weight=1` 顶着），因此「靠右」是布局本身就保证的。
     */
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
    internal val playerDanmakuTick get()=danmakuTick

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

    internal fun applyVideoScaleForSwipe() = applyVideoScaleImpl()

    internal fun applyChromeForSwipe() = applyChrome()

    // ---------------------------------------------------------------- 数据

    internal fun playForLoad(index: Int, startAt: Long) =
        play(index, startAt, clearDanmakuOnSwitch = true)

    /**
     * 横屏全屏入口只给竖版片源。
     *
     * 位置取视频 FIT 后的真实下边缘，再受底部控制层上沿限制。竖版视频在横屏里
     * 通常占满高度，若只看画面下边缘，按钮会直接落到控制层后面。
     */
    /**
     * 画面缩放（设置 → 播放偏好，1–100%）。
     *
     * 只缩**播放画面本身**：`PlayerView` 按中心等比缩放，页面底板仍是整屏黑，
     * 因此缩小后看到的是「画面变小、四周留黑」，与 TV 的 `applyVideoScale` 同一效果。
     */

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
    internal fun syncPlayInfoForLifecycle() = syncPlayInfo()
    internal fun syncDebugOverlayForLifecycle() = syncDebugOverlay()
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
    internal fun seekToForPage(fraction: Float) = seekTo(fraction)
    internal fun beginSwipeForPage() = beginSwipe()
    internal fun dragSwipeForPage(delta: Float) = dragSwipe(delta)
    internal fun endSwipeForPage(direction: Int) = endSwipe(direction)

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
