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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
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
import com.hgtv.star.phone.ui.PhonePagerMotion
import com.hgtv.star.phone.ui.PhoneSheet
import com.hgtv.star.phone.ui.PhoneStyle
import com.hgtv.star.phone.ui.PhoneTouch
import com.hgtv.star.phone.ui.bottomScrim
import com.hgtv.star.phone.ui.resizeMode
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.navigationBarPx
import com.hgtv.star.phone.ui.phoneEdgeToEdge
import com.hgtv.star.phone.ui.phoneSize
import com.hgtv.star.phone.ui.roundedBackground
import com.hgtv.star.phone.ui.topScrim
import com.hgtv.star.shared.data.Library
import com.hgtv.star.shared.data.PlaybackTransport
import com.hgtv.star.shared.data.WatchProgress
import com.hgtv.star.shared.media.VideoDataSource
import com.hgtv.star.shared.media.swapMediaSource
import com.hgtv.star.shared.nav.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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

        /**
         * 竖屏 ↔ 全屏的过渡时长。
         *
         * 播放页声明了 `configChanges=orientation`，转屏**不重建 Activity**，
         * 系统也就不给任何窗口动画——内容直接换一套布局，读起来就是「啪」一下。
         * 这一层淡入 / 放大就是补上那一段过渡。
         */
        private const val MODE_TRANSITION_MS = 220L
    }

    internal lateinit var root: FrameLayout
    private lateinit var portrait: FrameLayout
    private lateinit var page: PhoneVideoPage

    private lateinit var landscape: FrameLayout
    private lateinit var landscapeVideo: PlayerView
    /**
     * 横屏（全屏）那一层的弹幕。
     *
     * 竖屏的弹幕在 `page.danmakuView` 里，而全屏时整个 `portrait` 被 GONE 掉——
     * 没有这一层的话，用户竖屏看着有弹幕、一点全屏就「没了」。
     * 两层各持一个 `DanmakuView`，数据由 [danmakuLoader] 的 `replay` 在切形态时接上。
     */
    private lateinit var landDanmaku: com.hgtv.star.shared.media.DanmakuView
    private lateinit var landTop: LinearLayout
    private lateinit var landBottom: LinearLayout
    private lateinit var landFullscreen: LinearLayout
    private lateinit var landPlay: PhoneGlyph
    private lateinit var landCurrent: TextView
    private lateinit var landSeek: SeekBar
    private lateinit var landTotal: TextView
    /**
     * 进度条下面那一行「已经设置好的值」。
     *
     * 只读、常驻：全屏时用户想确认「现在是多少 P / 多少倍速 / 什么画面模式」，
     * 不该先打开播放设置再从左边找。设置面板因此只留右侧可改的一栏。
     */
    private lateinit var landSettingsInfo: TextView
    private lateinit var speedChip: TextView
    private lateinit var qualityChip: TextView
    private lateinit var danmakuChip: TextView
    private lateinit var episodeChip: TextView

    private lateinit var portraitTop: LinearLayout
    private lateinit var centerBox: FrameLayout
    private lateinit var centerGlyph: PhoneGlyph
    internal lateinit var hint: TextView

    /**
     * 缓冲指示：一个居中的小转圈。
     *
     * 手机上没有它就等于「点了之后画面定住」——短剧的每集只有几十秒，
     * 用户分不清「在缓冲」与「卡死了」。与电视端的 `suppressBufferingUi` 同一条规则：
     * 无感切集那一两百毫秒不转圈（画面停在上一集最后一帧就够了），最多压 1.2 秒。
     */
    private lateinit var bufferingBox: FrameLayout
    /** 缓冲指示被压住的截止时刻（无感切集期间）。 */
    private var bufferingMutedUntil = 0L

    private lateinit var sheet: PhoneSheet

    /**
     * 播放信息条（设置 → 播放显示）：标题 / 集数 / 时间 / 倍速，四项各自开关。
     *
     * 只在**竖屏**显示：横屏的顶部标题栏与底部控制条已经把这四样摆开了，
     * 再压一条上去就是同一件事写两遍。
     */
    private lateinit var infoRow: LinearLayout
    /** 「播放显示」左半：剧集标题 + 当前集数（靠左、超长省略）。 */
    private lateinit var infoLeft: TextView
    /** 「播放显示」右半：网速 + 当前时间（贴右）。 */
    private lateinit var infoRight: TextView
    /** 横屏那一份「播放显示」：与竖屏同内容，常驻在屏幕顶部。 */
    private lateinit var landInfoRow: LinearLayout
    private lateinit var landInfoLeft: TextView
    private lateinit var landInfoRight: TextView

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal var player: ExoPlayer? = null
    internal var remote: RemoteVideo? = null
    internal var detail: Detail? = null
    internal var library: Library? = null
    internal var index = 0
    private var ticker: Job? = null

    private var landscapeMode = false

    /** 退出全屏后「钉住竖屏」用的方向尺子；非空 = 还没等到设备立起来（见 [pinPortraitUntilUpright]）。 */
    private var portraitPin: android.view.OrientationEventListener? = null
    /** 当前竖屏是否由横屏播放页的竖版短剧主动进入全屏。 */
    private var portraitFullscreenFromLandscape = false
    /** 返回时正在先恢复横屏，恢复完成后再结束播放页。 */
    private var finishAfterLandscapeRestore = false

    /**
     * 竖屏顶部标题栏在 `page` 坐标系里的下沿（像素）；0 = 还没量到。
     *
     * 弹幕层要贴着它往下铺（见 [syncDanmakuTopInset]），而不是用 `DanmakuView`
     * 那套照电视横屏定的 104dp。
     */
    private var portraitTopBottom = 0
    /**
     * 横屏顶部那条「播放显示」在 `landscape` 坐标系里的下沿（像素）；0 = 还没量到。
     *
     * 横屏弹幕要从它下面开始铺，否则第一行弹幕会压在剧名 / 时间上。
     */
    private var landInfoRowBottom = 0
    private var landVideoWidth = 0
    private var landVideoHeight = 0
    /** 本页是否在前台：屏幕常亮只看前台、真实播放意图与错误 / 定时停止状态。 */
    private var foreground = false
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
    /** 下层那一块 Surface 当前是不是**已经在出本页的画面**（转横屏后会被拿走）。 */
    private var underlayBound = false
    /**
     * 下一次 [attachUnderlay] 是否需要强制重挂。
     *
     * 进场时输出本来就在推荐页那一块上（连 Surface 都没换过），强制重挂反而会
     * 先清一次屏；只有从横屏退回来、或本页自己抢过输出之后才需要。
     */
    private var underlayNeedsForce = false
    /** 本页是否收着下层推荐页的控制层（见 `PhoneSession.hideFeedChrome` 的计数）。 */
    private var feedChromeHeld = false
    private var chromeVisible = true
    private var userSpeed = 1f
    private var tempSpeed = false
    private var pendingFullscreen = false
    private var dragging = false
    /**
     * 这次 `finish()` 是「退回推荐页」而不是别的收尾。
     *
     * 退回推荐页时这一路播放要**原样交出去**：不暂停、不关地址、不换媒体源。
     * 早先 `onPause` 无条件 `pause()`，两个 Activity 之间那几十毫秒里画面会停一下、
     * 推荐页接手后再 `play()` 又要重新缓冲一次——用户读到的就是「退回去内容断了一下」。
     */
    private var returningToFeed = false
    /** 最后一集查到的下一季与底部提示。 */
    private var nextSeason: Series? = null
    private var nextSeasonFor = ""
    private var nextSeasonLoading = false
    private var nextSeasonToken = 0
    private var nextSeasonJob: Job? = null
    private var autoNextSeasonPending = false
    private var nextSeasonOpening = false
    private var nextSeasonFinishedFor = ""
    private lateinit var nextSeasonNotice: TextView
    /** 「相关剧集」请求的代次：浮层被换掉 / 关掉之后，在途结果不再往旧视图上写。 */
    private var relatedToken = 0
    /** 「作者作品」当前请求与缓存，同一部剧打开多次不重复搜索。 */
    private var authorToken = 0
    private var authorWorksFor = ""
    private var authorWorks: List<Series>? = null
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
    /** 挂在进程单例播放器上的监听器：每次换集都要先摘掉旧的，否则会越积越多。 */
    private var listener: Player.Listener? = null
    /**
     * 前台媒体会话：接耳机线控 / 蓝牙耳机 / 锁屏与通知栏的媒体面板。
     *
     * 与 TV 端共用 `:shared` 的 [com.hgtv.star.shared.media.MediaSessionHost]，不是另一套实现。
     * 手机上这条链路比电视更常用——用户锁屏后按耳机暂停，走的就是这里。
     * 建不出来（虚拟容器把 binder 代理成 null）时静默降级，不影响播放。
     */
    private var mediaSession: com.hgtv.star.shared.media.MediaSessionHost? = null
    /** 建会话失败过一次就不再重试（与 TV 的 `mediaSessionFailed` 同一条规则）。 */
    private var mediaSessionFailed = false
    /**
     * 「定时关闭」：`SleepTimer` 在 `:core`，与 TV 端是同一个类，只是这里的计时源同样是
     * `elapsedRealtime`（不受系统时间被改影响，也不受倍速影响）。
     *
     * **不落盘**：它是本次观看的会话状态。用户设了「30 分钟」之后关掉应用，
     * 明天再看不该莫名其妙在半小时后停住——TV 端也是这个语义。
     */
    private val sleepTimer = com.hgtv.star.core.SleepTimer { android.os.SystemClock.elapsedRealtime() }
    /** 「定时关闭」当前选中的档位（[com.hgtv.star.core.SleepTimer.LABELS] 下标）。 */
    private var sleepChoice = 0
    /** 定时已经停过一次：hint 与状态按它走，避免 tick 每秒钟重复报同一句。 */
    internal var sleepStopped = false
    /** 调试面板（设置 → 调试模式）。只在打开时挂，关掉立刻摘掉。 */
    private var debugOverlay: com.hgtv.star.phone.ui.PhoneDebugOverlay? = null
    /** 最近一次视频尺寸 / 播放错误：调试面板要读，但只有监听器里才有。 */
    private var debugVideo = "?"
    private var debugError = ""
    private var seekFrom = 0L
    /** 上一集读到过的时长：无感换集后 `duration` 已经是新一集的，写 completed 要用旧值。 */
    private var lastDuration = 0L
    /** 下一次允许发起预热的时间（避免每秒重试）。 */
    private var prefetchAt = 0L
    /** 正在预热的那一集（防重入）。 */
    private var prefetchingFor = ""
    /** 已经挂进播放列表、等着无感推进的那一集（它的 [RemoteVideo] 要留给会话）。 */
    internal var queuedVideo: RemoteVideo? = null
    internal var queuedEpisodeId = ""
    private var hintSticky = false
    /** 提示的代次：换集后那句「第N集」延迟收起时，只收自己那一句。 */
    private var hintToken = 0

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
    /** 这次拖动的目标方向：+1 = 下一集，-1 = 上一集，0 = 还没定 / 到头了。 */
    private var swipeDir = 0
    private var swipePrepared = false
    /** 当前这一轮 pager 吸附动画：一个动画同时驱动两页与底层视频。 */
    private var swipeAnimator: android.animation.ValueAnimator? = null
    /** 手动上滑已经推进到播放列表下一项，等 transition 回调或 post 兜底刷新界面。 */
    private var manualNextTarget = -1
    /** 手动切换前记下的当前进度：中途换集只能保存续播点，不能写成 completed。 */
    private var manualNextPosition = 0L
    /** 借来的那块视频现在被平移了多少像素（翻页动画的起点）。 */
    private var feedVideoDy = 0f

    /** 播放控制信息的自动隐藏截止时刻（见 `playerPanelTimeout`）。 */
    private var chromeHideAt = 0L
    /**
     * 自动收起的**代次**：每排一次队就 +1，旧的定时器看到代次变了就什么都不做。
     *
     * 没有它的时候有一个很具体的毛病：设置面板开着时点「相关剧集」，面板一关一开，
     * 但旧那一轮的定时器还在路上，到点就把**刚打开的**相关剧集浮层收走——
     * 用户读到的是「点了一下相关剧集，面板闪一下就没了」。
     */
    private var chromeEpoch = 0
    /** 相关剧集是浏览层：它开着时不排、也不执行自动收起。 */
    private var relatedSheetOpen = false

    private val chromeHideRunnable = object : Runnable {
        override fun run() {
            if (!::root.isInitialized || !chromeVisible || relatedSheetOpen) return
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

    // —— 播放失败的自愈（与电视端同一套内核）——

    /**
     * 会话级的降级状态（`:core`）：同档重建一次 / 最多降两档 / 手动处置建议。
     *
     * **不写 `library.maxQuality`**：降档是「这台机器这一刻放不了」，不是用户想要更低的清晰度。
     * 与电视端是同一个类、同一份规则，因此两端在「1080 解不了」这件事上的处置不会分叉。
     */
    internal val sessionPlayback = com.hgtv.star.core.SessionPlaybackPolicy()

    /** 按集计的重试额度（`:core`）：自动重试 3 次（3/6/12 秒）、画面重建、连播链失败次数。 */
    internal val ledger = com.hgtv.star.core.PlaybackAttemptLedger()

    /**
     * 正在「同一集的同一次起播重来」。
     *
     * 它只有一个含义：重试期间播放器报出来的错误不再触发第二轮处置，
     * 否则一次失败会在几毫秒里连撞三次降级，把预算烧光。
     */
    internal var recovering = false

    /** 排着的自动重试（3/6/12 秒）；换集 / 手动重试 / 销毁时取消。 */
    internal var retryJob: Job? = null

    /**
     * 起播代次与「这一代已经处置过错误」。
     *
     * 一次失败会在几毫秒里连报两三条；而「重建又失败」必须能被处置第二次。
     * 布尔量做不到这件事（它会一直停在 true），代次可以：每次新起播 +1。
     */
    internal var attemptSeq = 0
    internal var handledAttempt = -1

    // —— 换集取地址失败的兜底（见 [onEpisodeSourceMissing]）——

    /** 换集取地址 / 准备最多自动重试几次。 */
    private val sourceRetryLimit = 2

    /** 每次重试的间隔基数：第 1 次等 3 秒，第 2 次等 6 秒。 */
    private val sourceRetryStepMs = 3_000L

    /**
     * 取地址失败的合成错误码。
     *
     * 落在「网络 / 源」那一段（2000–2999），错误页据此说「网络或音频输出暂时不可用」，
     * 而不是把 0 当成错误码念给用户听。
     */
    private val sourceMissingCode = 2000

    /** 上一次重试是针对哪一集：换了另一集就归零，不会把别的集的重试次数带过来。 */
    private var sourceRetryEpisode = ""
    private var sourceRetryCount = 0

    /** 用户 / 自动重试发起的新一轮尝试：地址重试次数也要重新给满。 */
    internal fun resetSourceRetryForFreshAttempt() {
        sourceRetryEpisode = ""
        sourceRetryCount = 0
    }

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
            mode = { library?.danmakuMode ?: DanmakuMode.FULL },
            target = { danmakuTarget() },
            post = { block -> runOnUiThread(block) },
            alive = { !isFinishing && !isDestroyed },
        )
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        val tEnter = android.os.SystemClock.elapsedRealtime()
        super.onCreate(savedInstanceState)
        // 主题：播放页可能被系统单独拉起（最近任务 / 深链），不保证经过主页面。
        PhoneStyle.ensure(this)
        phoneEdgeToEdge()
        val seriesId = intent.getStringExtra(EXTRA_SERIES_ID).orEmpty()
        if (seriesId.isBlank()) { finish(); return }

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
        // 借下层推荐页的 Surface：判定必须在 `buildUi` 之前，页面底板建出来就要是透明的。
        feedUnderlay = Build.VERSION.SDK_INT >= 28 &&
            intent.getBooleanExtra(EXTRA_RECOMMEND, false) &&
            PhoneSession.feedUnderlayUsable(seriesId)

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

        buildPortrait()
        buildLandscape()

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

        bufferingBox = FrameLayout(this).apply {
            addView(android.widget.ProgressBar(this@PhonePlayerActivity).apply {
                indeterminateDrawable?.setTint(Color.WHITE)
            }, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
            visibility = View.GONE
        }
        root.addView(bufferingBox, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER))

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

        nextSeasonNotice = TextView(this).apply {
            setTextColor(PhoneStyle.playerText)
            phoneSize(13)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = roundedBackground(0xCC000000.toInt(), dp(10).toFloat())
            visibility = View.GONE
        }
        root.addView(nextSeasonNotice, FrameLayout.LayoutParams(
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
                relatedSheetOpen = false
                // 关掉相关剧集 / 设置后，从这一刻重新计时；相关剧集作为浏览层
                // 本身不会被自动收起，但“看完这一层”之后仍应回到自动收起。
                if (chromeVisible && ::root.isInitialized) scheduleChromeHide()
            }
        }

        setContentView(root)
        applyMode()
    }

    /** 建一页播放界面（当前页与相邻页同一份配置，否则翻页时两侧观感会不一样）。 */
    private fun makePage(): PhoneVideoPage = PhoneVideoPage(
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
    private fun wirePage(p: PhoneVideoPage) {
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
        p.onVerticalEnd = { direction, velocity -> endSwipe(direction, velocity) }
    }

    private fun buildPortrait() {
        portrait = FrameLayout(this)
        // 两页：ghost 在下、当前页在上。拖动时两页一起平移，滑到位后交换引用。
        ghostPage = makePage()
        page = makePage()
        portrait.addView(ghostPage, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        portrait.addView(page, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        ghostPage.visibility = View.GONE
        wirePage(page)
        wirePage(ghostPage)

        portraitTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        portrait.addView(portraitTop, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 沉浸式：状态栏不占位，只留一小段呼吸位（早先是 `statusBar() + dp(6)`）。
            topMargin = dp(12)
        })
        // 标题栏一量出高度就把弹幕的顶部起点对齐到它下沿。收起来（GONE）时高度是 0，
        // 不能拿那一帧当新下沿——否则控制层一隐藏，弹幕就窜回屏幕顶部。
        portraitTop.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            if (bottom - top > 0) {
                portraitTopBottom = bottom
                syncDanmakuTopInset()
            }
        }

        root.addView(portrait, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
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
            mode = DanmakuMode.FULL
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
                onVerticalEnd = { direction, velocity -> endSwipe(direction, velocity) },
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
    private fun chip(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        phoneSize(11)
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(4), dp(8), dp(4))
        // 常态半透明白、按下更亮：点下去要有反馈，否则用户会以为没点中而连点。
        background = PhoneSheet.pillBackground(
            0x33FFFFFF, 0x73FFFFFF, dp(11).toFloat()
        )
        setOnClickListener { onClick() }
    }

    private fun chipLp(left: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        dp(64), ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { leftMargin = dp(left) }

    /** 进度条染色是 API 21 才有的，19/20 上保持系统默认（不影响功能）。 */
    private fun tintSeekBar(bar: SeekBar) {
        if (Build.VERSION.SDK_INT < 21) return
        runCatching {
            bar.progressTintList = android.content.res.ColorStateList.valueOf(PhoneStyle.accent)
            bar.secondaryProgressTintList = android.content.res.ColorStateList.valueOf(
                (PhoneStyle.accent and 0x00FFFFFF) or 0x66000000
            )
            bar.thumbTintList = android.content.res.ColorStateList.valueOf(PhoneStyle.accent)
            bar.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x4DFFFFFF)
        }
    }

    // ---------------------------------------------------------------- 下层画面

    /**
     * 把播放器的输出交回下层推荐页的 Surface。
     *
     * 成功返回 true。失败说明推荐页那一块已经不在了（被系统回收 / 换了体裁），
     * 调用方必须退回「本页自己出画面」，否则就是一块透明。
     */
    private fun attachUnderlay(): Boolean {
        if (!feedUnderlay) return false
        // 第一次进场时那块 Surface 本来就在出画面，不需要重挂；从横屏退回来时
        // 输出已经被横屏那一层拿走，必须强制重挂。
        val force = underlayNeedsForce
        val ok = runCatching { PhoneSession.attachFeedSurface(force) }.getOrDefault(false)
        if (ok) {
            underlayNeedsForce = false
            underlayBound = true
            page.setVideoSurfaceVisible(false)
            root.setBackgroundColor(Color.TRANSPARENT)
            PhoneSession.underlayActive = true
            return true
        }
        underlayBound = false
        android.util.Log.i("HGTV-PHONE", "借下层失败 force=$force 入口=${PhoneSession.feedSurface != null}")
        return false
    }

    /**
     * 放弃借下层，改由本页自己出画面。
     *
     * 发生在两类场景：下层推荐页被系统收走；或者会话里的地址过期、需要重新取源
     * （那时下层画面已经不是这一集了，继续借会放错内容）。
     */
    private fun dropUnderlay() {
        if (!feedUnderlay) return
        android.util.Log.i("HGTV-PHONE", "放弃借下层，改由本页出画面")
        feedUnderlay = false
        feedVideoDy = 0f
        PhoneSession.setFeedVideoOffset(0f)
        underlayBound = false
        PhoneSession.underlayActive = false
        root.setBackgroundColor(Color.BLACK)
        page.setVideoSurfaceVisible(true)
        if (feedChromeHeld) {
            feedChromeHeld = false
            PhoneSession.restoreFeedChrome()
        }
    }

    // ---------------------------------------------------------------- 形态切换

    private fun applyMode() {
        // 形态一变，面板的宽度与位置就都不对了（横屏是右侧 1/3 抽屉，竖屏是底部整宽），
        // 直接收起，避免留一块尺寸错误的浮层。
        sheet.dismiss()
        dismissLandscapeSettings()
        // 形态一变，上一轮的平移量就没有意义了：清零并收掉相邻页。
        resetSwipeState()
        feedVideoDy = 0f
        PhoneSession.setFeedVideoOffset(0f)
        if (landscapeMode) {
            portrait.visibility = View.GONE
            landscape.visibility = View.VISIBLE
            // 横屏是一整块不透明黑：下层推荐页被完全盖住，控制层收不收都一样。
            root.setBackgroundColor(Color.BLACK)
            // 上一次退出全屏时把这块 Surface 从树上摘掉了（见下面竖屏分支），先挂回去。
            // 插到 index 0：弹幕层、手势层、控制层都必须在它上面。
            if (landscapeVideo.parent == null) {
                landscape.addView(landscapeVideo, 0, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                ))
            }
            // 顺序：**先把输出挂到横屏这一层，再放开竖屏那一块**。
            // 反过来的话，两句之间播放器没有输出端，那一帧就是黑的（转全屏时闪一下）。
            player?.let { landscapeVideo.player = it }
            // 横屏这一层此后要长期出画面：换集时保留上一帧，避免闪黑。
            landscapeVideo.setKeepContentOnPlayerReset(true)
            PhoneServices.detach(page.playerView)
            // 竖屏这一层在横屏期间不出画面，而且尺寸马上会从竖屏变横屏。
            // 保留最后一帧的话，回竖屏时那帧旧 buffer 会残留一块在新尺寸的 Surface 上——
            // 用户截图里「一块画面卡在屏幕上」就是这个。先关掉再解绑，它才会清屏。
            page.playerView.setKeepContentOnPlayerReset(false)
            page.playerView.player = null
            // 输出被横屏这一层拿走了：推荐页那边不能再认为「Surface 一直在」。
            PhoneSession.underlayActive = false
            underlayBound = false
            underlayNeedsForce = true
            applyLandscapeResize()
            enterImmersive()
        } else {
            landscape.visibility = View.GONE
            portrait.visibility = View.VISIBLE
            // 同上：先挂上竖屏要用的那一块，再放开横屏那一块。借下层时挂的是
            // 推荐页那块 Surface（`attachUnderlay`），这里一样不能先清。
            val attached = feedUnderlay && attachUnderlay()
            // 横屏那一层即将隐藏：先关掉「保留最后一帧」，再解绑。
            // 否则它的 Surface 会带着横屏尺寸的旧帧一起留在窗口里（退出全屏后那块残留）。
            landscapeVideo.setKeepContentOnPlayerReset(false)
            landscapeVideo.player = null
            // 光解绑不够：`SurfaceView` 在 `GONE` 之后仍可能把最后一帧合成到窗口上，
            // 表现就是「退出全屏后有一块画面卡着，怎么点都去不掉」。从视图树里摘掉，
            // Surface 才会真正销毁；下次进全屏再挂回 index 0（见上面横屏分支）。
            landscape.removeView(landscapeVideo)
            if (attached) {
                // 借下层出画面：本页底板与视频层都透明 / 隐藏。
                root.setBackgroundColor(Color.TRANSPARENT)
            } else {
                // 下层接不上（推荐页被收走、地址过期）：退回到本页自己出画面。
                dropUnderlay()
                player?.let { page.playerView.player = it }
                // Surface 的尺寸刚从横屏变回竖屏：重挂一次，让系统重建 buffer 并立刻出新帧。
                // 不重建的话，旧 buffer 会有一块残留在屏幕上（见上面横屏分支的注释）。
                rebuildPortraitSurface()
            }
            // 竖屏同样进沉浸式：**状态栏不占位、不显示**。
            // 早先这里走 `exitImmersive()`，顶部留着一条状态栏，竖屏短剧的画面被压下去一截，
            // 读到的就是「竖屏播放还挂着状态栏」。导航栏一起藏掉，底部控制层才能贴满屏底。
            enterImmersive()
        }
        chromeVisible = true
        applyVideoScale()
        // 刚切形态时两个容器可能还没量出尺寸（宽高为 0），按 0 算会让画面变成 1px。
        // 等这一帧布局完再按真实尺寸算一次。
        root.post { applyVideoScale() }
        // 退出全屏时竖屏容器可能到这一帧才量出新尺寸：再补一次，避免缩放按横屏尺寸算。
        root.postDelayed({ if (!landscapeMode) applyVideoScale() }, 150L)
        applyChrome()
        placeLandscapeFullscreen()
        scheduleChromeHide()
        syncChips()
        // 竖屏弹幕从标题栏下沿开始铺；刚切形态时标题栏可能还没量出高度，
        // 这一帧布局完再对一次（见 `syncDanmakuTopInset`）。
        syncDanmakuTopInset()
        // 竖屏 / 全屏是两个弹幕层：把这一集已经拉到的数据接到新那一层上，
        // 否则一点全屏就「弹幕没了」（见 `replayDanmaku`）。
        replayDanmaku()
        // 形态切换的过渡：系统在同一个窗口里换布局是没有动画的（`configChanges` 让
        // Activity 不重建），不加这一层就是「啪」一下换掉。放在所有布局设置之后。
        animateModeEnter(if (landscapeMode) landscape else portrait)
        root.post {
            syncDanmakuTopInset()
            replayDanmaku()
            placeLandscapeFullscreen()
        }
    }

    /**
     * 退出全屏后重建一次竖屏的视频 Surface。
     *
     * 从横屏退回竖屏时，`PlayerView` 里的 `SurfaceView` 尺寸刚由横屏变回竖屏，
     * 系统分配的旧 buffer 可能只被新帧盖住一部分——屏幕上就留下一条「卡住的画面」。
     * 解绑再挂上会强制重建 Surface 并立刻出一帧；`keepContentOnPlayerReset` 先关掉，
     * 否则它会把旧帧又贴回来。
     *
     * 只在「本页自己出画面」时执行：借下层 Surface 的那条路画面在推荐页那一层，本层不参与。
     */
    private fun rebuildPortraitSurface() {
        val p = player ?: return
        if (feedUnderlay && underlayBound) return
        page.playerView.setKeepContentOnPlayerReset(false)
        page.playerView.player = null
        page.playerView.player = p
        // 换清晰度 / 片尾连播仍然要「换源保留上一帧」，重建完再打开。
        page.playerView.postDelayed({
            runCatching { page.playerView.setKeepContentOnPlayerReset(true) }
        }, 600L)
    }

    /**
     * 新形态那一层的入场过渡：淡入 + 从 97% 放到原尺寸。
     *
     * 为什么需要它：播放页声明了 `configChanges=orientation`，转屏**不重建 Activity**，
     * 系统因此也不给任何窗口转场——`applyMode()` 一执行，竖屏那一整套就换成横屏那一整套，
     * 中间一帧过渡都没有，读起来就是「啪」一下。
     *
     * 为什么缩放只给 3%：横屏那一层里是 `PlayerView`（SurfaceView），放大倍数一大，
     * 边缘会露出黑边、部分机型上还会闪一下。这里靠淡入撑过渡，缩放只做一点点「推进感」。
     */
    private fun animateModeEnter(view: View) {
        view.animate().cancel()
        view.alpha = 0f
        view.scaleX = 0.97f
        view.scaleY = 0.97f
        view.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(MODE_TRANSITION_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    /**
     * 把竖屏弹幕的顶部起点对到**顶部标题栏下沿**。
     *
     * `DanmakuView` 默认让开 104dp，那是照电视横屏的标题条（96dp）定的；竖屏播放页的
     * 标题栏按 `phoneScale` 算，和这个数对不上，滚动弹幕就会贴着屏幕顶边、压在剧名上。
     * 这里用实测的标题栏下沿，再留 8dp 呼吸位；横屏照旧交回默认值（那边标题条本来
     * 就是 96dp 那一档）。
     */
    private fun syncDanmakuTopInset() {
        if (!::page.isInitialized) return
        if (landscapeMode) {
            // 横屏：让开顶部那条「播放显示」（`landInfoRow`）。它的下沿要实测——
            // 字号跟着 `PhoneUiScale` 走，写死一个 dp 值在放大档上会被弹幕压住。
            if (!::landInfoRow.isInitialized || !::landDanmaku.isInitialized) return
            // 只留一点点缝（用户要求）：8dp 在 1080P 上是 24px，加上弹幕轨道自身的
            // 行内留白，第一行弹幕离信息行看着像隔了一整行。2dp 就够。
            if (landInfoRowBottom > 0) landDanmaku.topInsetPx = landInfoRowBottom + dp(2)
            return
        }
        if (portraitTopBottom <= 0) return
        page.danmakuView.topInsetPx = portraitTopBottom + dp(8)
    }

    /** 横屏也按用户的「画面模式」落画面，不按片源方向强制放大。 */
    private fun applyLandscapeResize() {
        // 竖屏片源不再因为方向被强制 ZOOM：那会把 9:16 的画面裁成中间一条，
        // 换集时尤其明显。用户想铺满可以自己选「等比铺满」。
        landscapeVideo.resizeMode = (library?.frameMode ?: VideoFrameMode.FIT).toFit().resizeMode()
    }

    private fun enterImmersive() {
        // **四个 LAYOUT 标志缺一不可**。
        //
        // 早先只有 `FULLSCREEN | HIDE_NAVIGATION | IMMERSIVE_STICKY | LAYOUT_STABLE`：
        // 状态栏确实藏起来了，但窗口仍然按「状态栏存在」来排版，于是屏幕最上面空出一条
        // 与状态栏等高的黑边——用户读到的就是「状态栏没显示，却空出了它的位置」。
        //
        // `LAYOUT_FULLSCREEN` / `LAYOUT_HIDE_NAVIGATION` 让内容真正铺到两条系统栏底下，
        // `LAYOUT_STABLE` 保证隐藏 / 显示过程中布局不跳。三条一起才等价于「全屏」。
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            // 30+ 的窗口默认会「避让」系统栏，旧的 systemUiVisibility 有一部分被忽略。
            // 显式关掉 fit，再把两条栏一起藏掉，新版系统上才是真正的一整屏。
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(
                    android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars()
                )
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        // 横屏顶部那条标题栏本来就贴 0：全屏时不要任何额外留白。
        root.setPadding(0, 0, 0, 0)
    }

    /**
     * 进全屏（用户点「全屏」/ 从推荐页带 `EXTRA_FULLSCREEN` 进来）。
     *
     * 用户主动点全屏后固定横屏，不再把方向交还传感器；否则自动旋转会在全屏里
     * 根据手机姿态反复切回竖屏。退出全屏或离开播放页时再恢复系统方向策略。
     */
    private fun enterFullscreen() {
        if (landscapeMode) return
        // 主动进全屏：退出全屏时那把「钉住竖屏」的尺子要先收掉，
        // 否则它会在设备立起来时把方向交回传感器，把人从全屏里踢出去。
        releasePortraitPin()
        if (!requestOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) return
        landscapeMode = true
        applyMode()
    }

    /**
     * 申请固定方向，**在拿不到时安静地退让**。
     *
     * 播放页的窗口在 Android 9.0+ 是半透明的（`values-v28/styles.xml`，见那里的注释：
     * 这是「推荐页 ↔ 播放页内容不断」的前提）。而 8.0 / 8.1 的系统对「半透明 Activity
     * 申请固定方向」会直接抛
     * `IllegalStateException: Only fullscreen opaque activities can request orientation`，
     * 9.0 起才放开——因此那两个版本用的是不透明主题，走不到这里。
     *
     * 兜底仍然要留着：厂商 ROM 把窗口判成 translucent 的情况出现过（主题叠加、悬浮窗），
     * 真抛出来时不能让播放页崩掉。转不过去就留在当前形态，播放本身不受影响。
     *
     * 返回 false = 这次没切成。
     */
    private fun requestOrientation(value: Int): Boolean = runCatching {
        requestedOrientation = value
    }.onFailure {
        android.util.Log.w("HGTV-PHONE", "orientation $value rejected: ${it.message}")
    }.isSuccess

    /**
     * 系统「自动旋转屏幕」是否打开。
     *
     * 播放页的方向策略完全按它分岔（见 [enterFullscreen] / [exitFullscreen]）：
     * 开着就跟随传感器，关着就只认用户的「全屏」点击。
     */
    private fun autoRotateEnabled(): Boolean = runCatching {
        android.provider.Settings.System.getInt(
            contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION, 0
        ) == 1
    }.getOrDefault(false)

    /**
     * 退出横屏：先锁竖屏让窗口立刻转过来，再把方向交还系统策略。
     *
     * 交还（`USER`）是这一项需求的关键：
     * * 系统**开着自动旋转** → 用户横着拿手机会自动再进全屏，竖着拿就停在竖屏；
     * * 系统**关着自动旋转** → `USER` 等价于「保持当前方向」，停在竖屏不会自己转。
     *
     * 1.2 秒的延迟是为了等这一次转屏走完：立刻设 `USER` 时窗口还在竖屏动画里，
     * 系统可能按旧方向结算，表现成「退出后闪一下又横过去」。
     */
    private fun exitFullscreen() {
        if (!landscapeMode) { finish(); return }
        requestOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        landscapeMode = false
        applyMode()
        // 退出后**先钉住竖屏**，等设备真的立起来再放开自动旋转（见 [pinPortraitUntilUpright]）。
        //
        // 早先这里是无条件 `postDelayed(1200) { requestOrientation(USER) }`：
        // 看全屏时手机是横着拿的，1.2 秒后方向交回传感器，页面立刻又转回横屏——
        // 用户按返回看到的是「没反应」，连按几次都出不去（用户反馈的
        // 「第三集播完进不了第四集，退出全屏后连续返回都回不到推荐页」）。
        pinPortraitUntilUpright()
    }

    /**
     * 横屏里的竖版短剧点「全屏」后进入竖屏全屏。
     *
     * 这里**不能复用 [exitFullscreen]**：那个入口会在方向传感器满足条件时把方向
     * 立刻交还系统，横屏设备 / 强制横屏环境会马上又转回横屏。用户主动选择全屏时
     * 应稳定停在竖屏，直到返回或离开播放页。
     */
    private fun enterPortraitFullscreen() {
        if (!landscapeMode) return
        releasePortraitPin()
        if (!requestOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)) return
        landscapeMode = false
        portraitFullscreenFromLandscape = true
        applyMode()
    }

    /**
     * 退出全屏后**钉住竖屏**，直到设备真的立起来。
     *
     * 为什么需要这一步：看全屏时人是横着拿手机的，这时把方向交回传感器
     *（`SCREEN_ORIENTATION_USER`）等于让页面自己转回全屏。等设备立起来再放开，
     * 既不会把人拽回全屏，也不影响之后「横过来自动进全屏」这条规则。
     *
     * 系统自动旋转关着时不必钉：那时 `USER` 本来就是竖屏。
     * 没有方向传感器（部分模拟器 / 平板）时 [android.view.OrientationEventListener.enable]
     * 会失败，这时保持竖屏不动——比反复横竖切换强。
     */
    private fun pinPortraitUntilUpright() {
        releasePortraitPin()
        if (!autoRotateEnabled()) return
        val listener = object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                // -1 = 放不平（平放在桌上）：这时不知道朝向，继续等。
                if (orientation == ORIENTATION_UNKNOWN) return
                // 0 / 360 是正立、180 是倒立，±45 度以内都算「立着」。
                val upright = orientation <= 45 || orientation >= 315 || orientation in 135..225
                if (!upright) return
                releasePortraitPin()
                requestOrientation(ActivityInfo.SCREEN_ORIENTATION_USER)
            }
        }
        // 没有方向传感器（部分模拟器 / 平板）：`canDetectOrientation` 为 false，
        // 这时保持竖屏不动，比反复横竖切换强。
        if (!runCatching { listener.canDetectOrientation() }.getOrDefault(false)) return
        runCatching { listener.enable() }
        portraitPin = listener
    }

    private fun releasePortraitPin() {
        portraitPin?.let { runCatching { it.disable() } }
        portraitPin = null
    }

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
        val nowLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (nowLandscape != landscapeMode) {
            landscapeMode = nowLandscape
            applyMode()
        }
        if (nowLandscape) {
            portraitFullscreenFromLandscape = false
            if (finishAfterLandscapeRestore) {
                finishAfterLandscapeRestore = false
                root.post { if (!isFinishing && !isDestroyed) finish() }
                return
            }
        }
        root.requestLayout()
    }

    private fun applyChrome() {
        if (landscapeMode) {
            landTop.visibility = if (chromeVisible) View.VISIBLE else View.GONE
            landBottom.visibility = if (chromeVisible) View.VISIBLE else View.GONE
            placeLandscapeFullscreen()
        } else {
            page.setChromeVisible(chromeVisible)
            // `portraitTop` 只装「播放显示」那一行，它的显隐由 `syncPlayInfo()` 按设置决定：
            // 勾了任意一项就常驻，四项全没勾才跟着控制层收起来。
        }
        if (!chromeVisible) hideCenter()
        syncPlayInfo()
    }

    private fun setChromeVisible(visible: Boolean) {
        if (chromeVisible == visible) {
            applyChrome()
            if (visible) scheduleChromeHide()
            return
        }
        chromeVisible = visible
        applyChrome()
        if (visible) {
            scheduleChromeHide()
        } else {
            chromeEpoch++
            if (::root.isInitialized) root.removeCallbacks(chromeHideRunnable)
        }
    }

    // ---------------------------------------------------------------- 播放状态

    private fun togglePlay() {
        val exo = player ?: return
        if (exo.isPlaying) {
            exo.pause()
            // 用户自己暂停：不是卡死，撤掉看门狗（恢复播放时重新装）。
            disarmPictureWatchdog()
        } else {
            exo.play()
            armPictureWatchdog()
        }
        showCenter(!exo.isPlaying)
        syncPlayGlyph()
        syncBufferingUi(exo.playbackState)
        syncKeepScreenOn()
    }

    /**
     * 缓冲指示的显隐（与电视端的 `suppressBufferingUi` / `restoreBufferingUi` 同一套规则）。
     *
     * 三个条件缺一不可：正在缓冲、没有处于「无感切集」的压制窗口、用户没有主动暂停。
     * 最后一条尤其重要：暂停后播放器也会落进 BUFFERING，那时转圈会让人以为「暂停没生效」。
     */
    private fun syncBufferingUi(state: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        val show = state == Player.STATE_BUFFERING &&
            now >= bufferingMutedUntil &&
            player?.playWhenReady == true &&
            errorCard == null
        bufferingBox.visibility = if (show) View.VISIBLE else View.GONE
        if (show) bufferingBox.bringToFront()
    }

    /**
     * 无感切集：压住缓冲指示 1.2 秒。
     *
     * 不压的话，ExoPlayer 从队列推进到下一集时会先报 BUFFERING，屏幕上闪一个转圈——
     * 而实际上画面一直有（停在上一集最后一帧），那个转圈纯属噪音。
     */
    private fun muteBufferingUi() {
        bufferingMutedUntil = android.os.SystemClock.elapsedRealtime() + 1_200L
        bufferingBox.visibility = View.GONE
        root.postDelayed({
            if (!isFinishing && !isDestroyed) syncBufferingUi(player?.playbackState ?: Player.STATE_IDLE)
        }, 1_200L)
    }

    internal fun syncPlayGlyph() {
        val playing = player?.isPlaying == true
        landPlay.kind = if (playing) PhoneGlyph.Kind.PAUSE else PhoneGlyph.Kind.PLAY
    }

    /** 屏幕中心的播放 / 暂停：暂停时一直亮着，播放中闪一下。 */
    private fun showCenter(paused: Boolean) {
        centerGlyph.kind = if (paused) PhoneGlyph.Kind.PLAY else PhoneGlyph.Kind.PAUSE
        centerBox.visibility = View.VISIBLE
        centerBox.bringToFront()
        if (!paused) {
            centerBox.postDelayed({
                if (player?.isPlaying == true) centerBox.visibility = View.GONE
            }, 700)
        }
    }

    private fun hideCenter() {
        centerBox.visibility = View.GONE
    }

    private fun beginTempSpeed() {
        if (tempSpeed) return
        tempSpeed = true
        player?.setPlaybackSpeed(2f)
        hintSticky = true
        hint.text = "2 倍速播放中"
        hint.visibility = View.VISIBLE
        hint.bringToFront()
    }

    private fun endTempSpeed() {
        if (!tempSpeed) return
        tempSpeed = false
        player?.setPlaybackSpeed(userSpeed)
        hintSticky = false
        hint.visibility = View.GONE
    }

    private var seekTarget = 0L

    /**
     * 横滑拖进度：**进度条与时间跟着手指实时走**，松手才真正 seek。
     *
     * 只更新显示、不 seek：拖动过程中每一帧都 seek 会让解码器反复重开，
     * 画面卡成一片。松手一次性跳过去，体验与 TV 端的「拖进度条」一致。
     */
    private fun dragTo(deltaFraction: Float) {
        val duration = player?.duration ?: 0L
        if (duration <= 0) return
        seekTarget = (seekFrom + deltaFraction * duration * 1.2f).toLong().coerceIn(0, duration)
        val permille = (seekTarget * 1000 / duration).toInt().coerceIn(0, 1000)
        // 横屏：底部的进度条 + 左侧当前时间
        landSeek.progress = permille
        landCurrent.text = clock(seekTarget)
        landTotal.text = clock(duration)
        // 竖屏：底部信息栏里那条进度条
        page.setProgress(permille / 1000f)
        // 两种形态都有的上方读数
        hint.text = "${clock(seekTarget)} / ${clock(duration)}"
        hint.visibility = View.VISIBLE
        hint.bringToFront()
    }

    private fun showSeekHint(fraction: Float) {
        val duration = player?.duration ?: 0L
        if (duration <= 0) return
        hint.text = "${clock((duration * fraction).toLong())} / ${clock(duration)}"
        hint.visibility = View.VISIBLE
    }

    private fun clearSeekHint() {
        if (!hintSticky) hint.visibility = View.GONE
    }

    // ---------------------------------------------------------------- 弹幕

    /**
     * 当前形态该往哪一层画弹幕。
     *
     * 竖屏在 `page` 里，全屏在 `landscape` 里——两层是各自独立的 `DanmakuView`。
     * [danmakuLoader] 每次都现取，切形态后新请求自然落到新那一层（旧数据靠 `replay` 接）。
     */
    private fun danmakuTarget(): com.hgtv.star.shared.media.DanmakuView? = when {
        landscapeMode && ::landDanmaku.isInitialized -> landDanmaku
        ::page.isInitialized -> page.danmakuView
        else -> null
    }

    /**
     * 把本机偏好落到弹幕层上（显示方式 / 字号 / 透明度 / 速度 / 聚合）。
     *
     * 与 TV 的 `MainActivityPlayer` 建弹幕层那一段**逐项一致**：同一份落盘值、
     * 同一个 [com.hgtv.star.shared.media.DanmakuView]，只是手机上字号按 density 走。
     */
    private fun applyDanmakuSettings() {
        val lib = library ?: return
        val views = buildList {
            add(page.danmakuView)
            add(ghostPage.danmakuView)
            // 横屏那一层也要跟着设置走：它平时不可见，但一切全屏就要立刻是对的。
            if (::landDanmaku.isInitialized) add(landDanmaku)
        }
        for (view in views) {
            // 横竖屏两层按**同一个基准**取字号（屏幕长边）：按各自视图高度算的话，
            // 竖屏 1920 与横屏 1080 会差 1.6 倍，切全屏字号明显跳一档。
            view.sizeFollowsLongEdge = true
            view.mode = lib.danmakuMode
            view.textScale = PhoneDanmakuSize.renderScale(this)
            view.textAlpha = lib.danmakuAlpha
            view.speed = lib.danmakuSpeed
            view.mergeRepeats = lib.danmakuMerge
            view.mergeMinCount = lib.danmakuMergeMin
            view.effectsEnabled = lib.danmakuEffects
            // 隐藏弹幕时整层不参与绘制，也就不必让它的请求跑下去。
            view.visibility = if (lib.danmakuMode == DanmakuMode.HIDDEN) View.GONE else View.VISIBLE
        }
    }

    /**
     * 拉这一集的弹幕。
     *
     * 实现在 `:shared` 的 [com.hgtv.star.shared.media.DanmakuLoader]：与电视端、手机信息流
     * **同一份代码**（同一集不重拉、旧回包丢弃、隐藏时不发请求、边拉边画）。
     */
    private fun loadDanmaku(episodeId: String, seriesId: String) {
        danmakuLoader.load(episodeId, seriesId)
    }

    /** 把播放位置 / 播放状态 / 倍速推给弹幕层（每帧只读，不回调播放器）。 */
    private fun syncDanmakuClock(positionMs: Long, running: Boolean, speed: Float) {
        danmakuLoader.syncClock(positionMs, running, speed)
    }

    /**
     * 切形态后把这一集的弹幕接到新那一层上。
     *
     * 竖屏与全屏是两个独立的 `DanmakuView`，数据在视图内部；不重放的话，
     * 竖屏看着有弹幕、一点全屏就空了。位置 / 状态 / 倍速一并带过去，
     * 新那一层从当前进度接着滚，不是从头再放一遍。
     */
    private fun replayDanmaku() {
        if (!danmakuLoader.loaded) return
        val target = danmakuTarget() ?: return
        // 同一层不重复重放：`applyMode` 同步一次、布局完再补一次，两层都重排一遍没有意义。
        if (target === danmakuReplayTarget) return
        val p = player ?: return
        danmakuLoader.replay(p.currentPosition, p.isPlaying, p.playbackParameters.speed)
        danmakuReplayTarget = target
    }

    /** 上一次把弹幕重放到哪一层（见 [replayDanmaku]）。 */
    private var danmakuReplayTarget: com.hgtv.star.shared.media.DanmakuView? = null

    // ---------------------------------------------------------------- 上下滑翻页

    /** 一屏的高度：竖屏 / 横屏各自的那一层。 */
    private fun slideHeight(): Int {
        val h = if (landscapeMode) landscape.height else portrait.height
        return h.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
    }

    /** 手指按下：停掉上一轮的动画，等第一次有方向的位移再决定翻向哪一集。 */
    private fun beginSwipe() {
        swipeDir = 0
        swipePrepared = false
        page.animate().cancel()
        ghostPage.animate().cancel()
        landscape.animate().cancel()
        landscapeGhost.animate().cancel()
        swipeAnimator?.cancel()
        swipeAnimator = null
        showEpisodeHint(0f)
    }

    /**
     * 拖动中：**两页一起跟手**（与推荐页的整页 pager 同一个观感）。
     *
     * 当前页跟着手指走，相邻页从另一侧同步进入——它是真正的一页 `PhoneVideoPage`
     *（视频区黑着、标题 / 标签 / 集数 / 进度条都在），不是一张封面图。
     *
     * 到头（第一集再往下 / 最后一集再往上）时位移只走 0.3 倍，做出「拉不动」的手感。
     */
    private fun dragSwipe(delta: Float) {
        val h = slideHeight()
        val direction = if (delta < 0) 1 else -1
        if (!swipePrepared && kotlin.math.abs(delta) > .008f) {
            swipePrepared = true
            swipeDir = direction
            prepareGhost(direction)
            // 到头时没有相邻页：保持可再次尝试，手指反向时还能接上另一侧。
            if (swipeDir == 0) swipePrepared = false
        } else if (swipePrepared && swipeDir != 0 && direction != swipeDir) {
            // 手指在拖动中反方向：相邻页要换到另一侧，否则松手会吸错页。
            swipeDir = direction
            prepareGhost(direction)
            if (swipeDir == 0) swipePrepared = false
        }
        val factor = if (swipeDir != 0) 1f else .3f
        val dy = delta * h * factor
        if (landscapeMode) {
            landscape.translationY = dy
            if (swipeDir != 0) {
                landscapeGhost.translationY = dy + if (swipeDir > 0) h else -h
            }
        } else {
            page.translationY = dy
            // 借下层出画面时视频不在本页的视图树里：把那一块按同一个位移量平移，
            // 观感才与推荐页的整页翻动一致（见 `PhoneSession.feedVideoOffset`）。
            if (feedUnderlay) {
                feedVideoDy = dy
                PhoneSession.setFeedVideoOffset(dy)
            }
            if (swipeDir != 0) {
                ghostPage.translationY = dy + if (swipeDir > 0) h else -h
            }
        }
        showEpisodeHint(delta)
    }

    /**
     * 松手：够了就两页一起吸到整页（当前页滑出、相邻页滑到正中），不够就弹回。
     *
     * 吸附完成后**交换引用**：刚滑进来的那一页成为 [page]，播放器接到它身上；
     * 刚滑出去的那一页清空、放回另一侧备用。整个过程中用户看到的始终是两页连续运动，
     * 与推荐页 `PagerSnapHelper` 的吸附是同一个动作。
     */
    private fun endSwipe(direction: Int, velocityY: Float = 0f) {
        clearEpisodeHint()
        val target = when (direction) {
            PhoneTouch.DIRECTION_NEXT -> 1
            PhoneTouch.DIRECTION_PREV -> -1
            else -> 0
        }
        val d = detail
        val playable = target != 0 && d != null && (index + target) in d.episodes.indices
        if (!playable) {
            // 到头 / 位移不够：弹回原位（到头时 [stepEpisode] 会补一句「已经是第一集」）。
            if (target != 0) stepEpisode(target)
            settleBack()
            return
        }
        val h = slideHeight().toFloat()
        val out = -target * h
        val start = if (landscapeMode) landscape.translationY else page.translationY
        val duration = PhonePagerMotion.duration(out - start, slideHeight(), velocityY)
        if (landscapeMode) {
            animateSwipe(
                pageTarget = page.translationY,
                ghostTarget = ghostPage.translationY,
                landscapeTarget = out,
                landscapeGhostTarget = 0f,
                feedTarget = null,
                duration = duration,
            ) {
                playSwipeTarget(index + target)
                resetSwipeState()
            }
        } else {
            animateSwipe(
                pageTarget = out,
                ghostTarget = 0f,
                landscapeTarget = landscape.translationY,
                landscapeGhostTarget = landscapeGhost.translationY,
                feedTarget = if (feedUnderlay) out else null,
                duration = duration,
            ) {
                val outgoing = page
                // 先交换：`play()` 里所有绑定（媒体源、进度、信息栏）都落在新的 [page] 上。
                page = ghostPage
                ghostPage = outgoing
                page.translationY = 0f
                // 旧的这一页已经没有播放器了，解绑并放回另一侧藏着。
                PhoneServices.detach(ghostPage.playerView)
                ghostPage.translationY = 0f
                ghostPage.visibility = View.GONE
                resetSwipeState(keepCurrent = true)
                playSwipeTarget(index + target)
                applyVideoScale()
                applyChrome()
                syncChips()
            }
        }
    }

    /** 弹回原位（位移不够 / 到头）。 */
    private fun settleBack() {
        val h = slideHeight().toFloat()
        val pageStart = if (landscapeMode) landscape.translationY else page.translationY
        val ghostTarget = when {
            swipeDir > 0 -> h
            swipeDir < 0 -> -h
            else -> 0f
        }
        val duration = PhonePagerMotion.duration(pageStart, slideHeight())
        animateSwipe(
            pageTarget = 0f,
            ghostTarget = ghostTarget,
            landscapeTarget = 0f,
            landscapeGhostTarget = ghostTarget,
            feedTarget = if (feedUnderlay && !landscapeMode) 0f else null,
            duration = duration,
        ) {
            resetSwipeState()
        }
    }

    /**
     * 一次动画同时驱动当前页、相邻页和借用的底层视频。
     *
     * 推荐页只有一个 RecyclerView 滚动值，所以所有子内容天然在同一帧更新。
     * 正式播放页原来用 2~3 个独立 Animator，启动与结束会差一帧，最后一跳看起来
     * 就比推荐页更急；这里统一成一个进度值。
     */
    private fun animateSwipe(
        pageTarget: Float,
        ghostTarget: Float,
        landscapeTarget: Float,
        landscapeGhostTarget: Float,
        feedTarget: Float?,
        duration: Long,
        onEnd: () -> Unit,
    ) {
        swipeAnimator?.cancel()
        val pageStart = page.translationY
        val ghostStart = ghostPage.translationY
        val landscapeStart = landscape.translationY
        val landscapeGhostStart = landscapeGhost.translationY
        val feedStart = feedVideoDy
        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f)
        anim.duration = duration
        anim.interpolator = PhonePagerMotion.interpolator
        anim.addUpdateListener { value ->
            val fraction = value.animatedFraction
            page.translationY = pageStart + (pageTarget - pageStart) * fraction
            ghostPage.translationY = ghostStart + (ghostTarget - ghostStart) * fraction
            landscape.translationY = landscapeStart + (landscapeTarget - landscapeStart) * fraction
            landscapeGhost.translationY =
                landscapeGhostStart + (landscapeGhostTarget - landscapeGhostStart) * fraction
            if (feedTarget != null) {
                feedVideoDy = feedStart + (feedTarget - feedStart) * fraction
                PhoneSession.setFeedVideoOffset(feedVideoDy)
            }
        }
        var canceled = false
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationCancel(animation: android.animation.Animator) {
                canceled = true
            }

            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (swipeAnimator === animation) swipeAnimator = null
                if (!canceled) onEnd()
            }
        })
        swipeAnimator = anim
        anim.start()
    }

    /**
     * 铺相邻页：绑定目标集（标题 / 标签 / 集数 / 进度都按那一集来）。
     *
     * 视频区是黑的——与推荐页翻到下一页时一样：媒体源还没挂上去，先出信息栏。
     */
    private fun prepareGhost(direction: Int) {
        val d = detail ?: run { swipeDir = 0; return }
        val target = index + direction
        if (target !in d.episodes.indices) {
            swipeDir = 0
            return
        }
        val label = "第${target + 1}集"
        val h = slideHeight().toFloat()
        if (landscapeMode) {
            landscapeGhostTitle.text = "${d.series.title} $label"
            landscapeGhost.translationY = if (direction > 0) h else -h
            landscapeGhost.visibility = View.VISIBLE
        } else {
            ghostPage.bind(d.series, label, watchText = "选集 ${target + 1}/${d.episodes.size}")
            ghostPage.setProgress(0f)
            ghostPage.setChromeVisible(chromeVisible)
            ghostPage.translationY = if (direction > 0) h else -h
            ghostPage.visibility = View.VISIBLE
        }
    }

    /** 复位两页的位置与显隐；[keepCurrent] = 刚完成交换，当前页要留在正中。 */
    private fun resetSwipeState(keepCurrent: Boolean = false) {
        if (keepCurrent) page.translationY = 0f
        // 视频那一块回到原位（新的一集已经接上，位置必须是正的）。
        if (feedUnderlay) PhoneSession.setFeedVideoOffset(0f)
        ghostPage.translationY = 0f
        ghostPage.visibility = View.GONE
        landscape.translationY = 0f
        landscapeGhost.translationY = 0f
        landscapeGhost.visibility = View.GONE
        swipePrepared = false
        swipeDir = 0
    }

    /**
     * 上下滑时的提示：「下一集 第N集」/「上一集 第N集」，位移不够时写「继续滑动」。
     *
     * 复用画面顶部那一条 [hint]（与拖动进度的读数同一个位置、同一套样式），
     * 因此两种手势的反馈读起来是同一种东西。手指一抬起就收起（见 [clearEpisodeHint]）。
     */
    private fun showEpisodeHint(delta: Float) {
        val d = detail ?: return
        // 向上是下一集（delta < 0），向下是上一集。
        val target = if (delta <= 0) index + 1 else index - 1
        val enough = kotlin.math.abs(delta) >= PhoneTouch.VERTICAL_COMMIT_RATIO
        hint.text = when {
            target !in d.episodes.indices && delta <= 0 -> "已经是最后一集"
            target !in d.episodes.indices -> "已经是第一集"
            enough -> "${if (delta <= 0) "下一集" else "上一集"} · 第${target + 1}集"
            else -> "${if (delta <= 0) "上滑" else "下滑"}切换${if (delta <= 0) "下一集" else "上一集"}"
        }
        hintToken++
        hintSticky = true
        hint.visibility = View.VISIBLE
    }

    private fun clearEpisodeHint() {
        hintSticky = false
        hint.visibility = View.GONE
    }

    /**
     * 换到相邻的一集（±1）。到头时给一句提示，不做越界播放。
     *
     * 优先走 [playSwipeTarget]：下一集已经排进播放列表时直接推进，避免重建播放器；
     * 尚未准备好时才回落到 [play] 的常规取源路径。
     */
    private fun stepEpisode(direction: Int) {
        val d = detail ?: return
        val target = index + direction
        if (target !in d.episodes.indices) {
            hint.text = if (direction > 0) "已经是最后一集" else "已经是第一集"
            hintToken++
            hintSticky = true
            hint.visibility = View.VISIBLE
            val token = hintToken
            root.postDelayed({ if (hintToken == token) clearEpisodeHint() }, 1200)
            return
        }
        playSwipeTarget(target)
        hint.text = "第${target + 1}集"
        hintToken++
        hintSticky = true
        hint.visibility = View.VISIBLE
        val token = hintToken
        root.postDelayed({ if (hintToken == token) clearEpisodeHint() }, 900)
    }

    private fun seekTo(fraction: Float) {
        val duration = player?.duration ?: 0L
        if (duration <= 0) return
        player?.seekTo((duration * fraction).toLong())
    }

    // ---------------------------------------------------------------- 数据

    private fun load(seriesId: String) {
        scope.launch {
            val lib = PhoneServices.awaitLibrary(this@PhonePlayerActivity)
            library = lib
            if (chromeVisible) scheduleChromeHide()
            // 倍速是**落盘设置**：用户刚在推荐页调到 2 倍速（那一侧写进了书库），进来接着用。
            userSpeed = lib.playbackSpeed

            // **接手**：推荐页刚把这一集挂上进程级播放器（[PhoneSession]），
            // 这里就不重新取详情、不重新取地址、不重新 prepare ——
            // 那三步加起来是 1~2 秒的黑屏，正是用户说的「每次进播放页都重新加载」。
            if (adoptFromSession(seriesId)) return@launch

            // 会话里已经有**同一部剧**的详情（片尾自动进正片就是这条路：要放的是下一集，
            // 集号对不上 `adoptFromSession` 的条件，但详情与下一集的预热结果都在手上）。
            // 不再请求一次详情：那是这条链路上最贵的一步（一次 App 接口往返）。
            val cached = PhoneSession.detail?.takeIf {
                it.series.id == seriesId && PhoneSession.remote != null && it.episodes.isNotEmpty()
            }
            // 详情：内存会话 → 落盘缓存 → 接口（与电视端 `DetailCache` 同一条顺序）。
            // 落盘那一份正是推荐页预热时写的，因此「进正片」这一段常常一次请求都不发。
            val fromDisk = if (cached == null) withContext(Dispatchers.IO) {
                runCatching { com.hgtv.star.core.DetailCache.load(seriesId) }.getOrNull()
            } else null
            val d = cached ?: fromDisk ?: withContext(Dispatchers.IO) {
                runCatching { PhoneServices.repository(this@PhonePlayerActivity).detail(seriesId) }.getOrNull()
            }
            if (d == null) { landInfoLeft.text = "详情加载失败"; return@launch }
            if (cached == null && fromDisk == null) runCatching { com.hgtv.star.core.DetailCache.save(d) }
            detail = d
            if (d.episodes.isEmpty()) {
                landInfoLeft.text = if (d.upcoming) "尚未上线" else "暂无剧集"
                return@launch
            }

            val saved = withContext(Dispatchers.IO) {
                runCatching { lib.progress(seriesId) }.getOrNull()
            }
            val resumeIndex = saved?.let { s -> d.episodes.indexOf(s.episodeId).takeIf { it >= 0 } } ?: 0
            val resumeAt = if (saved != null && resumeIndex == d.episodes.indexOf(saved.episodeId)) {
                saved.position
            } else 0L
            val start = intent.getIntExtra(EXTRA_EPISODE, -1).takeIf { it in d.episodes.indices }
                ?: resumeIndex
            val startAt = if (start == resumeIndex) resumeAt else 0L
            play(start, startAt)
        }
    }

    /** [adoptFromSession] 的前置检查；返回 null = 可以接，否则返回不能接的原因（只用于日志）。 */
    private fun adoptReject(seriesId: String): String? {
        // 只看「这一路还活着」：地址过期不等于接不上。切页只换 Surface，媒体源与解码器
        // 一直在跑，接上去立刻出帧；地址由 [reopenCurrentEpisodeInPlace] 原地换一条，
        // 位置不动。早先这里按「地址新鲜」判，于是暂停久了再进来就被判成接不上，
        // 走的是重取详情 → 重取地址 → 重新 prepare，用户读到的就是「进播放页重新加载」。
        if (!PhoneSession.live(seriesId)) {
            return when {
                PhoneSession.remote == null -> "no-remote"
                PhoneSession.seriesId != seriesId -> "other-series"
                else -> "no-session"
            }
        }
        val d = PhoneSession.detail ?: return "no-detail"
        if (PhoneSession.remote == null) return "no-remote"
        val sessionIndex = PhoneSession.index
        if (sessionIndex !in d.episodes.indices) return "bad-index"
        val wanted = intent.getIntExtra(EXTRA_EPISODE, -1)
        if (wanted >= 0 && wanted != sessionIndex) return "episode-mismatch"
        val exo = PhoneServices.peekPlayer() ?: return "no-player"
        // IDLE **不等于**接不上。`swapMediaSource` 是「先 stop 再挂源」（见 `PlayerSourceSwap`），
        // 从挂源到 prepare 之间播放器就是 IDLE；推荐页片尾交接、刚起播一屏时都正好落在
        // 这个窗口里。早先这里按 IDLE 直接拒绝，于是每一次都退化成
        // 「重新取详情 → 重新取地址 → 重新 prepare → 从 0 起播」——用户读到的就是
        // 「从推荐页进正片内容断了一下、还从头开始」。
        //
        // 真正的判据是「播放器上挂着的那一项是不是这一集」：是，就接手并替它 prepare。
        if (exo.playbackState == Player.STATE_IDLE) {
            val wantedId = d.episodes.getOrNull(sessionIndex).orEmpty()
            val onPlayer = runCatching { exo.currentMediaItem?.mediaId }.getOrNull().orEmpty()
            if (wantedId.isEmpty() || onPlayer != wantedId) return "idle-no-source"
        }
        return null
    }

    /**
     * 接手进程级播放器上已经挂好的这一集。
     *
     * 条件（缺一不可）：
     * * 会话里是**同一部剧**，且播放器上挂着的是这一集（IDLE 也行，这里补 prepare）；
     * * 用户没有指定别的集（`EXTRA_EPISODE` 与会话里那一集一致或没传）。
     *
     * 接上之后**不重新取源**：画面接着播，位置就是用户刚才看到的那一秒。
     */
    private fun adoptFromSession(seriesId: String): Boolean {
        val why = adoptReject(seriesId)
        if (why != null) {
            android.util.Log.i("HGTV-PHONE", "adopt skip $why episode=${intent.getIntExtra(EXTRA_EPISODE, -1)}")
            return false
        }
        val d = PhoneSession.detail ?: return false
        val video = PhoneSession.remote ?: return false
        val sessionIndex = PhoneSession.index
        // 发布前先取：`publish` 会把会话里的位置清零（那一份是给「下一次接手」用的）。
        val resumeAt = PhoneSession.positionMs
        val wasIdle = runCatching { PhoneServices.peekPlayer()?.playbackState }
            .getOrNull() == Player.STATE_IDLE
        val exo = PhoneServices.player(this, library?.decoderMode)

        detail = d
        index = sessionIndex
        remote = video
        bindEpisodeUi(d, sessionIndex)

        player = exo
        // `bindEpisodeUi` 在 `player` 赋值之前跑：这里补一次画面尺寸，
        // 否则接手时「全屏」按钮会一直不出现（`bind` 把已知尺寸清掉了）。
        syncVideoFrame()
        if (landscapeMode) {
            page.playerView.player = null
            landscapeVideo.player = exo
        } else {
            landscapeVideo.player = null
            // 借下层 Surface：不挂本页的视频层，播放器连输出都没换过。
            if (!(feedUnderlay && attachUnderlay())) {
                dropUnderlay()
                PhoneSession.underlayActive = false
                page.playerView.player = exo
            }
        }
        PhoneSession.publish(d, sessionIndex, video, video.info.quality, PhoneSession.Owner.PLAYER)
        attachListener(exo)
        // 播放列表里可能还挂着上一轮排好的「下一集」：`swapMediaSource` 换源时已经把列表清空，
        // 但 `nextQueued` 这个标志还停在 true。不对一次的话，片尾判定会以为「已经排好了」，
        // 一集放完就停在最后一帧（用户读到的就是「没有自动下一集」）。
        PhoneSession.nextQueued = runCatching {
            // `currentMediaItemIndex` 在「源刚挂上、还没 prepare」时可能是 -1（无 timeline）：
            // 直接相加会把「只有一项」算成「后面还有一项」，片尾就不再往下走了。
            exo.mediaItemCount > exo.currentMediaItemIndex.coerceAtLeast(0) + 1
        }.getOrDefault(false)
        // 预热好的下一集**留着**：推荐页在片尾前备好的那一份正是这一集要用的，
        // 丢掉等于让本页重新取一次地址（片尾那一下又要黑一下）。
        // 预热节流也跟着归零：上一轮那个「8 秒后再试」的时间戳留着，会让这一集的预热白等一截，
        // 短剧集只有几十秒，等过去就已经到片尾了。
        prefetchAt = 0
        prefetchingFor = ""
        exo.setPlaybackSpeed(userSpeed)
        // 媒体源是刚挂上、还没 prepare 的那一种（推荐页片尾交接 / 刚起播一屏）：
        // 这里替它 prepare，并把位置对回会话记下的那一秒。**不重新取地址**。
        if (wasIdle) {
            exo.prepare()
            if (resumeAt > 0) exo.seekTo(resumeAt)
        }
        // 按发起方的意图决定要不要立刻播：
        // * 「观看完整短剧」/ 片尾进正片 → true（信息流那边已经写好）；
        // * 「全屏」按钮 → 沿用信息流里当时是播还是暂停。
        // 这里**不要**硬写 true：用户明明在信息流里暂停了，点全屏进来看见它自己动起来，
        // 就是「暂停没记住」。
        exo.playWhenReady = PhoneSession.resumeOnAdopt
        // 接手完成：交接窗口到此为止，之后再退到后台就正常暂停。
        PhoneSession.endHandoff()
        syncPlayGlyph()
        syncChips()
        startTicker()
        armPictureWatchdog()
        if (pendingFullscreen) {
            root.post { enterPendingFullscreen() }
        }
        // 地址已过窗口期：这一路还在出帧，但下一个 Range 请求会 403。
        // 原地换一条地址，位置 / Surface / 播放状态都不动（见 [reopenCurrentEpisodeInPlace]）。
        if (PhoneSession.urlExpired()) {
            reopenCurrentEpisodeInPlace(runCatching { exo.currentPosition }.getOrDefault(0L))
        }
        android.util.Log.i("HGTV-PHONE", "adopt session series=$seriesId episode=${sessionIndex + 1}")
        return true
    }

    /**
     * 原地换一条地址：**不换集、不换 Surface、不回片头**。
     *
     * 用在「会话里这一路还活着、只是签名地址过期」这一种情况（见 [PhoneSession.urlExpired]）。
     * 早先它走的是 `play(index, at)`：那一条会 `releasePlayer()` 把播放器从 `PlayerView` 上摘下来、
     * 关掉旧地址、重新 prepare——摘 Surface 那一下就是用户看到的闪黑，
     * 而这一路的内容其实一秒都没断过。
     */
    private fun reopenCurrentEpisodeInPlace(at: Long) {
        val d = detail ?: return
        val episodeId = d.episodes.getOrNull(index) ?: return
        val exo = player ?: return
        val position = at.coerceAtLeast(0L).takeIf { it > 0 } ?: PhoneSession.positionMs
        val maxQuality = playbackMaxQuality
        // 用户此刻是播还是暂停，换地址不能把它改掉（暂停着换完还是暂停）。
        val wantPlay = exo.playWhenReady
        scope.launch {
            val prepared = withContext(Dispatchers.IO) {
                runCatching {
                    val info = PhoneServices.repository(this@PhonePlayerActivity)
                        .stream(episodeId, maxQuality)
                    RemoteVideo(PlaybackTransport.client, info, info.mediaKey(episodeId)).also { it.prepare() }
                }.getOrNull()
            }
            if (prepared == null) {
                // 换地址失败：退回常规起播（会重新取详情 / 地址），别停在死画面上。
                if (!isFinishing && !isDestroyed) play(index, position)
                return@launch
            }
            if (isFinishing || isDestroyed) { prepared.close(); return@launch }
            val factory = DataSource.Factory { VideoDataSource(prepared) }
            exo.swapMediaSource(episodeId, prepared.info.url, factory)
            queuedVideo?.let { runCatching { it.close() } }
            queuedVideo = null
            queuedEpisodeId = ""
            PhoneSession.clearNext()
            remote = prepared
            PhoneSession.publish(d, index, prepared, prepared.info.quality, PhoneSession.Owner.PLAYER)
            exo.setPlaybackSpeed(userSpeed)
            exo.prepare()
            if (position > 0) exo.seekTo(position)
            exo.playWhenReady = wantPlay
            android.util.Log.i(
                "HGTV-PHONE",
                "reopen in place episode=${index + 1} at=$position"
            )
        }
    }

    /** 把「第几集」这件事刷到所有界面上（竖屏信息栏 / 横屏标题 / 选集按钮）。 */
    private fun bindEpisodeUi(d: Detail, episodeIndex: Int) {
        val label = "第${episodeIndex + 1}集"
        page.bind(d.series, label, watchText = "选集 ${episodeIndex + 1}/${d.episodes.size}")
        // 横屏不再单独写一行「剧名 第x集」：这一份由 `syncPlayInfo()` 铺在顶部的信息行里。
        applyLandscapeResize()
        // `bind` 会把画面模式重置成默认值：换集 / 从横屏退回来都要按用户设置补一次，
        // 否则「选了等比铺满，一换集就变回原样」。
        syncFrameMode()
        applyVideoScale()
        syncPlayInfo()
        applyDanmakuSettings()
        // `bind` 会把已知画面尺寸清掉（换集后可能拿不到新的 `onVideoSizeChanged`），
        // 这里用播放器现有的尺寸补一次：否则「全屏」按钮会一直不出现。
        syncVideoFrame()
        loadDanmaku(d.episodes[episodeIndex], d.series.id)
        // 换集不转屏：手机端播放页的方向只由用户的「全屏」点击决定，
        // 上滑看下一集时画面不会自己变成横屏（早先那条「竖屏短剧自动全屏」已经删掉）。
    }

    /** 把播放器当前的画面尺寸补到页面上（`bind` 之后调用）。 */
    private fun syncVideoFrame() {
        val size = player?.videoSize ?: return
        if (size.width <= 0 || size.height <= 0) return
        // 调试面板的「画面=」也按这一份填：接手 / 换集时不会再触发 `onVideoSizeChanged`，
        // 只靠监听会让这一格一直停在「?」（用户报问题时最需要它）。
        debugVideo = "${size.width}x${size.height}"
        page.updateVideoFrame(size.width, size.height)
        updateLandscapeVideoFrame(size.width, size.height)
    }

    private fun updateLandscapeVideoFrame(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        landVideoWidth = width
        landVideoHeight = height
        placeLandscapeFullscreen()
    }

    /**
     * 横屏全屏入口只给竖版片源。
     *
     * 位置取视频 FIT 后的真实下边缘，再受底部控制层上沿限制。竖版视频在横屏里
     * 通常占满高度，若只看画面下边缘，按钮会直接落到控制层后面。
     */
    private fun placeLandscapeFullscreen() {
        if (!::landFullscreen.isInitialized || !::landscapeVideo.isInitialized) return
        val portraitVideo = landVideoHeight > landVideoWidth
        if (!landscapeMode || !chromeVisible || !portraitVideo) {
            landFullscreen.visibility = View.GONE
            return
        }
        if (landscape.width <= 0 || landscape.height <= 0) return

        landFullscreen.visibility = View.VISIBLE
        val videoWidth = landscapeVideo.width.takeIf { it > 0 } ?: landscape.width
        val videoHeight = landscapeVideo.height.takeIf { it > 0 } ?: landscape.height
        val ratio = landVideoWidth.toFloat() / landVideoHeight
        val videoRatio = videoWidth.toFloat() / videoHeight
        val pictureHeight = if (ratio >= videoRatio) videoWidth / ratio else videoHeight.toFloat()
        val videoTop = landscapeVideo.top.toFloat()
        val pictureBottom = videoTop + (videoHeight + pictureHeight) / 2f

        val buttonHeight = landFullscreen.measuredHeight.takeIf { it > 0 } ?: dp(18)
        val gap = dp(10)
        val bottomLimit = if (::landBottom.isInitialized && landBottom.height > 0) {
            landBottom.top
        } else {
            landscape.height
        }
        val maxTop = bottomLimit - buttonHeight - gap
        val top = (pictureBottom - buttonHeight - gap)
            .coerceAtMost(maxTop.toFloat())
            .coerceAtLeast(0f)
            .toInt()
        val lp = landFullscreen.layoutParams as FrameLayout.LayoutParams
        if (lp.topMargin != top) {
            lp.topMargin = top
            landFullscreen.layoutParams = lp
        }
        if (landFullscreen.measuredHeight <= 0) {
            landFullscreen.post {
                if (landscapeMode && landFullscreen.isAttachedToWindow) placeLandscapeFullscreen()
            }
        }
    }

    /**
     * 画面缩放（设置 → 播放偏好，1–100%）。
     *
     * 只缩**播放画面本身**：`PlayerView` 按中心等比缩放，页面底板仍是整屏黑，
     * 因此缩小后看到的是「画面变小、四周留黑」，与 TV 的 `applyVideoScale` 同一效果。
     */
    private fun applyVideoScale() {
        val ratio = ((library?.videoScale ?: 100f) / 100f).coerceIn(.1f, 1f)
        // 竖屏：改的是布局宽高（SurfaceView 不跟 scaleX 走，见 `PhoneVideoPage.setVideoScale`）。
        page.setVideoScale(ratio)
        // 横屏用的是另一块 PlayerView，同样按布局宽高缩。
        applyLandscapeVideoScale(ratio)
        if (feedUnderlay) PhoneSession.setFeedVideoScale(ratio)
    }

    /** 横屏画面缩放：改布局宽高，缩小时四周露出黑底。 */
    private fun applyLandscapeVideoScale(ratio: Float) {
        landscapeVideo.scaleX = 1f
        landscapeVideo.scaleY = 1f
        val lp = landscapeVideo.layoutParams as? FrameLayout.LayoutParams ?: return
        if (ratio >= .999f) {
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT
        } else {
            val w = landscape.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val h = landscape.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
            lp.width = (w * ratio).toInt().coerceAtLeast(1)
            lp.height = (h * ratio).toInt().coerceAtLeast(1)
        }
        lp.gravity = Gravity.CENTER
        landscapeVideo.layoutParams = lp
        landscapeVideo.requestLayout()
    }

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
    private fun syncPlayInfo() {
        val lib = library
        val d = detail
        if (lib == null || d == null || !::infoRow.isInitialized) return
        val anyChecked = lib.playerTitle || lib.playerEpisode || lib.playerClock || lib.playerSpeed
        // 全都没勾时按「四项全开」渲染——这是用户要的默认形态，但它跟着控制层收放。
        val showAll = !anyChecked
        val followChrome = showAll
        // 左半：剧集标题 + 当前集数。
        val left = ArrayList<String>(2)
        if (showAll || lib.playerTitle) left += d.series.title
        // 集数带总数（「第 3 / 171 集」）：与电视端信息条同一段格式（`PlaybackInfoLine`）。
        if (showAll || lib.playerEpisode) {
            left += com.hgtv.star.core.PlaybackInfoLine.episode(index, d.episodes.size)
        }
        // 右半：网速 + 当前时间。两项与电视端**同一份落盘值、同一套含义**
        //（见 TV 的 `updatePlaybackInfoReadings`）：`playerSpeed` 是网速、`playerClock` 是当前时间。
        // 早先手机上把它们画成了「播放位置 / 倍速」——同一个开关在两端显示不同的东西。
        // 播放位置在底部进度条上本来就有（`landCurrent` / `landTotal`），不占这一行。
        val right = ArrayList<String>(2)
        if (showAll || lib.playerSpeed) right += com.hgtv.star.shared.debug.NetSpeed.line()
        if (showAll || lib.playerClock) {
            val calendar = java.util.Calendar.getInstance()
            right += com.hgtv.star.core.PlaybackInfoLine.clock(
                calendar.get(java.util.Calendar.HOUR_OF_DAY),
                calendar.get(java.util.Calendar.MINUTE),
            )
        }
        val leftText = left.joinToString("  ·  ")
        val rightText = right.joinToString("  ·  ")
        infoLeft.text = leftText
        infoRight.text = rightText
        if (::landInfoRow.isInitialized) {
            landInfoLeft.text = leftText
            landInfoRight.text = rightText
        }
        val empty = left.isEmpty() && right.isEmpty()
        val visible = !empty && (!followChrome || chromeVisible)
        // 竖屏：`portraitTop` 只装这一行，显隐完全由这里决定（见 `applyChrome`）。
        infoRow.visibility = if (visible) View.VISIBLE else View.GONE
        portraitTop.visibility = if (visible) View.VISIBLE else View.GONE
        // 顶部那条压暗只给这一行垫底：这一行不显示时它就是一条平白无故的暗带
        //（用户反馈「全屏顶部有一些黑雾，需要仔细对比才能发现」——渐变很缓，
        // 亮场里才看得出）。早先它跟控制层一起开关，跟「有没有字」无关。
        if (::page.isInitialized) page.setTopScrimVisible(visible)
        // 横屏：贴顶那一行与 `landTop` 各管各的，清屏时它照旧常驻。
        if (::landInfoRow.isInitialized) {
            landInfoRow.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    internal fun play(episodeIndex: Int, startAt: Long = 0L) {
        val d = detail ?: return
        if (episodeIndex !in d.episodes.indices) return
        nextSeasonFinishedFor = ""
        val sameEpisode = episodeIndex == index
        index = episodeIndex
        val episodeId = d.episodes[episodeIndex]
        // 换了一集：按集计的重试 / 降级额度全部归零（与电视端 `onEpisodeStart` 同一入口）。
        // 同一集重来（换档 / 手动重试）不在这里清：那会把「每集只重建一次」变成无限次。
        if (!sameEpisode) {
            sessionPlayback.onEpisodeStart(episodeId)
            ledger.onEpisodeStart()
            playbackToasts.clear()
        }
        recovering = false
        attemptSeq++
        cancelAutoRetry()
        clearPlaybackError()
        bindEpisodeUi(d, episodeIndex)
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
        scope.launch {
            // 上限读**实际生效**的那一档（自动降档结果优先），否则降档只在当前这一集生效。
            val maxQuality = playbackMaxQuality
            val source = withContext(Dispatchers.IO) {
                runCatching {
                    PhoneServices.repository(this@PhonePlayerActivity).stream(episodeId, maxQuality)
                }
            }
            val info = source.getOrNull()
            if (info == null) {
                val problem = source.exceptionOrNull()
                invalidateEpisodeCacheForRetry(episodeId, null)
                onEpisodeSourceMissing(d, episodeIndex, startAt, problem)
                return@launch
            }

            val preparedSource = withContext(Dispatchers.IO) {
                runCatching { prepareSource(episodeId, info) }
            }
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

            attach(prepared, startAt)
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
        if (target == index + 1 && seekToQueuedNext(target)) return
        play(target)
    }

    private fun seekToQueuedNext(target: Int): Boolean {
        if (!PhoneSession.nextQueued) return false
        if (queuedVideo == null || queuedEpisodeId.isEmpty()) return false
        val exo = player ?: return false
        val current = exo.currentMediaItemIndex
        if (current < 0 || exo.mediaItemCount <= current + 1) return false
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
                onAdvanced(target, completedPrevious = false, previousPosition = manualNextPosition)
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
     * 现在按「网络瞬态」处理：先自动重试两次（3 秒 / 6 秒，播放器留着、上一集的最后一帧
     * 留在屏幕上，不黑屏）；仍不行才摆错误页——那里的「立即重试」与「下一集」
     * 才是用户真正需要的出口，而不是一句没人看得见的字。
     */
    private fun onEpisodeSourceMissing(
        d: Detail,
        episodeIndex: Int,
        at: Long,
        problem: Throwable? = null,
    ) {
        val episodeId = d.episodes.getOrNull(episodeIndex) ?: return
        if (problem != null && com.hgtv.star.core.isInterruption(problem)) return
        if (problem != null) {
            android.util.Log.w(
                "HGTV-RECOVER",
                "source missing episode=${episodeIndex + 1} cache invalidated=${problem is java.io.IOException}",
                problem,
            )
        }
        if (sourceRetryEpisode != episodeId) {
            sourceRetryEpisode = episodeId
            sourceRetryCount = 0
        }
        if (sourceRetryCount < sourceRetryLimit) {
            sourceRetryCount++
            val wait = sourceRetryStepMs * sourceRetryCount
            // 与「第 N 集」那类提示走同一套 token：后到的提示会把这一条顶掉。
            hint.text = "第 ${episodeIndex + 1} 集还没取到播放地址，${wait / 1000} 秒后重试…"
            hintToken++
            hintSticky = true
            hint.visibility = View.VISIBLE
            hint.bringToFront()
            val token = hintToken
            root.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    // 重试前先把提示收掉：重试成功时不该还挂着「重试中」。
                    if (hintToken == token) {
                        hintSticky = false
                        hint.visibility = View.GONE
                    }
                    play(episodeIndex, at)
                }
            }, wait)
            return
        }
        // 额度用完：交给错误页（带「立即重试 / 下一集」），绝不留一块黑屏给用户。
        hintSticky = false
        hint.visibility = View.GONE
        failureStage = com.hgtv.star.core.PlaybackStage.PREPARE
        showPlaybackError(
            playbackAttribution(sourceMissingCode),
            com.hgtv.star.core.GiveUpReason.BUDGET_EXHAUSTED,
        )
    }

    /**
     * 清掉这一集当前所有缓存，并让下一次取流使用新的请求 device_id。
     *
     * 与电视端的 `prepareFreshPlaybackRetry` 保持同一条语义：
     * 地址、索引、首块、预取槽和连接层一起作废，不能只重放同一条失败流。
     */
    private fun invalidateEpisodeCacheForRetry(episodeId: String, info: StreamInfo?) {
        queuedVideo?.let { runCatching { it.close() } }
        queuedVideo = null
        queuedEpisodeId = ""
        PhoneSession.clearNext()
        runCatching {
            PhoneServices.repository(this).forgetStreamAllQualities(episodeId)
        }
        info?.let {
            runCatching { PlaybackCache.invalidateIndex(it.mediaKey(episodeId)) }
            val host = runCatching { java.net.URI(it.url).host }.getOrNull().orEmpty()
            if (host.isNotEmpty()) {
                com.hgtv.star.shared.media.PlaybackFailures.dropTransportCaches(listOf(host))
            }
        }
    }

    /** 本地准备失败必须保留异常：调用方据此决定是否作废缓存后重取。 */
    private fun prepareSource(episodeId: String, info: StreamInfo): RemoteVideo {
        val remote = RemoteVideo(PlaybackTransport.client, info, info.mediaKey(episodeId))
        return try {
            remote.prepare()
            remote
        } catch (problem: Throwable) {
            runCatching { remote.close() }
            throw problem
        }
    }

    /** 预热路径只需要结果，失败就丢掉；播放起播路径走 [prepareSource] 保留异常。 */
    private fun prepare(episodeId: String, info: StreamInfo): RemoteVideo? =
        runCatching { prepareSource(episodeId, info) }.getOrNull()

    private fun attach(video: RemoteVideo, startAt: Long) {
        remote = video
        val d = detail
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
        attachListener(exo)
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
        syncVideoFrame()
        exo.prepare()
        if (startAt > 0) exo.seekTo(startAt)
        // 媒体源已经挂好，交接结束：之后退到后台就按正常规则暂停。
        PhoneSession.endHandoff()
        syncPlayGlyph()
        syncChips()
        startTicker()
        armPictureWatchdog()
        // 这一集真的挂上了：取地址失败的重试计数归零（见 `onEpisodeSourceMissing`）。
        // 不清的话，网络恢复回调会拿一个过期的计数把正常播放的这一集重来一遍。
        sourceRetryEpisode = ""
        sourceRetryCount = 0

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

    /** 挂播放器监听（换集时先摘旧的：进程单例上不能越积越多）。 */
    private fun attachListener(exo: ExoPlayer) {
        listener?.let { exo.removeListener(it) }
        val hook = object : Player.Listener {
            /**
             * 片尾。
             *
             * * 播放列表里还挂着下一集 → ExoPlayer 自己会无感推进，这里什么都不做
             *   （界面由 [onAdvanced] 接手）；
             * * 没有下一集 → 最后一集播完，走老路。
             */
            override fun onPlaybackStateChanged(state: Int) {
                syncKeepScreenOn()
                // 真的能放了：重试窗口结束、错误页收起（与电视端「出第一帧」同一职责）。
                syncBufferingUi(state)
                if (state == Player.STATE_READY) {
                    watchdog.onReady()
                    notePlaybackHealthy()
                }
                if (state == Player.STATE_ENDED) {
                    // 进程级 `nextQueued` 可能比播放器列表晚一拍清掉。片尾只信
                    // 播放器当前真实是否还有下一项；没有就立刻走下一季 / 全剧结束。
                    val hasNextItem = runCatching {
                        val exo = player ?: return@runCatching false
                        val current = exo.currentMediaItemIndex.coerceAtLeast(0)
                        exo.mediaItemCount > current + 1
                    }.getOrDefault(false)
                    if (hasNextItem) return
                    PhoneSession.nextQueued = false
                    next()
                }
            }

            /** 无感换集：自动推进或手动 seek 到队列下一项时回调。 */
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val nextIndex = detail?.episodes?.indexOf(mediaItem?.mediaId).takeIf { it != null && it >= 0 }
                    ?: (index + 1)
                val manual = reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK &&
                    manualNextTarget == nextIndex
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && !manual) return
                if (manual) manualNextTarget = -1
                if (manual) {
                    onAdvanced(nextIndex, completedPrevious = false, previousPosition = manualNextPosition)
                    manualNextPosition = 0L
                } else {
                    onAdvanced(nextIndex)
                }
            }

            // 只同步底部那颗按钮的图标。中心图标**不跟着 isPlaying 走**：
            // 缓冲卡一下、片尾、切集都会让 isPlaying 变 false，那时冒出一个暂停图标
            // 会让人以为「自己按了暂停」。中心图标只在用户明确操作时出现。
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                syncPlayGlyph()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                syncKeepScreenOn()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                debugVideo = "${videoSize.width}x${videoSize.height}"
                // 画面模式按用户选择走，**不按片源方向强制 ZOOM**：
                // 竖屏短剧在竖屏里等比铺满会裁掉左右，换集后突然放大就是从这里来的。
                val target = (library?.frameMode ?: VideoFrameMode.FIT).toFit().resizeMode()
                page.setResizeMode(target)
                if (landscapeMode) landscapeVideo.resizeMode = target
                if (feedUnderlay) PhoneSession.setFeedVideoResize(target)
                page.updateVideoFrame(videoSize.width, videoSize.height)
                updateLandscapeVideoFrame(videoSize.width, videoSize.height)
                if (pendingFullscreen) root.post { enterPendingFullscreen() }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // 调试面板要把错误码留下：用户报「花屏 / 有声无画」时，这一行就是结论。
                debugError = "${error.errorCodeName}(${error.errorCode})"
                syncDebugOverlay()
                // 自愈：同档重建 / 降档 / 退避重试 / 错误页，决策全在 `:core`（见 PhonePlaybackRecovery）。
                handlePlayerFailure(error)
            }
        }
        listener = hook
        exo.addListener(hook)
        // 「画面还在走」的唯一硬证据是逐帧回调：播放位置在走、声音在响，都可能只是音频在动。
        // 回调在播放线程上、每帧一次，因此这里只写一个 volatile 字段，不碰任何 View。
        exo.setVideoFrameMetadataListener { _, _, _, _ ->
            // 调试包注入「有声音无画面」（`DebugFaults.STALL_FRAME`）：故意不记账，
            // 让看门狗那条链路能在开发机上按需复现。正式包里 BuildConfig.DEBUG 是常量 false。
            if (!com.hgtv.star.shared.BuildConfig.DEBUG ||
                com.hgtv.star.shared.debug.DebugFaults.mode(this) !=
                com.hgtv.star.shared.debug.DebugFaults.Mode.STALL_FRAME
            ) {
                lastFrameAt = android.os.SystemClock.elapsedRealtime()
                watchdog.onFrame()
            }
        }
    }

    /**
     * 无感换集：ExoPlayer 已经把下一集的 period 推上屏了，这里只把**界面与记录**跟上。
     *
     * 与 TV 的 `onEpisodeAdvanced` 同一套职责划分：
     *
     * * 播完那一集按 `completed` 落盘（此时 `duration` 已经是新一集的了，必须用旧值）；
     * * 集号、标题、选集按钮、会话全部换到新一集；
     * * **不重设媒体源**——重设就会黑屏重来，那正是要避免的。
     */
    private fun onAdvanced(
        nextIndex: Int,
        completedPrevious: Boolean = true,
        previousPosition: Long = 0L,
    ) {
        val d = detail ?: return
        if (nextIndex !in d.episodes.indices) return
        val previous = index
        val previousDuration = lastDuration

        // 播完那一集：写 completed（用切走之前记下的时长）。
        val lib = library
        if (lib != null && previous in d.episodes.indices && previousDuration > 0) {
            // 自动推进才是「整集看完」；手动上滑是中途换集，只保存当前续播点。
            val done = if (completedPrevious) {
                WatchProgress.completed(
                    series = d.series,
                    episodeId = d.episodes[previous],
                    episodeIndex = previous,
                    duration = previousDuration,
                )
            } else {
                WatchProgress.of(
                    series = d.series,
                    episodeId = d.episodes[previous],
                    episodeIndex = previous,
                    position = previousPosition,
                    duration = previousDuration,
                )
            }
            runCatching { lib.save(done) }
            // 账号侧也要跟着走（与 TV 的 `syncProgress` 同一条路）：不推的话，
            // 在手机上看完的这几集换到电视上还是「没看过」。
            runCatching { PhoneAccountSync.progress(this, done, d.episodes.size) }
        }

        index = nextIndex
        PhoneSession.nextQueued = false
        PhoneSession.clearNext()
        bindEpisodeUi(d, nextIndex)
        syncChips()
        lastDuration = 0
        prefetchAt = 0
        // 会话换成**这一集**的 RemoteVideo：它在挂队列时就被 `queuedVideo` 记下了。
        // 用 `PhoneSession.remote` 会把上一集的引用写回去——那一份马上要关，
        // 信息流接手时会拿到一个已关闭的传输。
        val video = queuedVideo
        queuedVideo = null
        queuedEpisodeId = ""
        if (video != null) {
            remote = video
            PhoneSession.publish(d, nextIndex, video, video.info.quality, PhoneSession.Owner.PLAYER)
        }
        prepareNextSeasonIfNeeded(d)
        muteBufferingUi()
        android.util.Log.i("HGTV-PHONE", "advanced seamlessly to ${nextIndex + 1}")
    }

    private fun syncChips() {
        syncLandscapeSettingsInfo()
        speedChip.text = PlaybackSpeed.label(userSpeed)
        qualityChip.text = "${library?.maxQuality ?: 1080}P"
        // 弹幕那颗：显示当前档位（「弹幕 全屏」），隐藏档显示「弹幕 关」。
        danmakuChip.text = when (library?.danmakuMode ?: com.hgtv.star.core.DanmakuMode.FULL) {
            com.hgtv.star.core.DanmakuMode.HIDDEN -> "弹幕 关"
            com.hgtv.star.core.DanmakuMode.FULL -> "弹幕 全屏"
            com.hgtv.star.core.DanmakuMode.TOP -> "弹幕 上方"
            com.hgtv.star.core.DanmakuMode.SINGLE -> "弹幕 一行"
        }
        // **选集按钮永远显示**：之前按 `recommend` 把它藏了，而从推荐流进全屏正是最常见
        // 的路径（`PhoneFeedView` 一律带 `EXTRA_RECOMMEND=true`），结果就是「全屏里没有选集按钮」。
        // 文案带总数（用户要求）：`选集 3/171`，一眼知道现在在第几集、全剧多少集。
        val total = detail?.episodes?.size ?: 0
        episodeChip.text = if (total > 0) "选集 ${index + 1}/$total" else "选集 ${index + 1}"
        episodeChip.visibility = View.VISIBLE
    }

    /**
     * 弹幕快捷开关：点一次切下一档（全屏 → 上方 → 一行 → 关 → 全屏）。
     *
     * 与电视播放页那颗「弹幕」按钮同一职责，只是把「弹菜单再选」压成一次点击：
     * 手机上最常见的动作就是「挡画面了，先关掉」，不该为此打开一整个设置面板。
     * 四档循环的次序按 [com.hgtv.star.core.DanmakuMode] 的枚举顺序，落盘值与设置页共用同一份。
     */
    private fun cycleDanmaku() {
        val lib = library ?: return
        // 循环顺序在 `:core`（`DanmakuMode.next`，带单测）：两端共用同一套「越来越不挡画面」的次序。
        val next = lib.danmakuMode.next()
        val previous = lib.danmakuMode
        if (com.hgtv.star.shared.BuildConfig.DEBUG) {
            android.util.Log.i(
                "HGTV-PHONE",
                "danmaku chip ${previous.storedValue} -> ${next.storedValue}"
            )
        }
        lib.danmakuMode = next
        // 立刻作用在画面上：弹幕层的显示方式、以及「隐藏档不再发请求」那条规则
        // 都由 `DanmakuView.mode` 与 `DanmakuLoader` 负责（与电视端同一份实现）。
        page.danmakuView.mode = next
        applyDanmakuSettings()
        syncChips()
        hint.text = "弹幕 ${next.label}"
        hint.visibility = View.VISIBLE
        hint.bringToFront()
        val token = hintToken
        root.postDelayed({ if (hintToken == token) hint.visibility = View.GONE }, 900)
        // 从隐藏切回可见、而这一集还没取过弹幕：补一次请求（电视端同一判据）。
        if (previous == com.hgtv.star.core.DanmakuMode.HIDDEN && next.showsStyle) {
            detail?.let { d ->
                d.episodes.getOrNull(index)?.let { loadDanmaku(it, d.series.id) }
            }
        }
    }

    /**
     * 倍速快捷开关：点一次切到下一档，与弹幕按钮同一种操作。
     *
     * 0.75 → 1 → 1.25 → 1.5 → 1.75 → 2 → 3 → 0.75。写入书库，
     * 推荐页接手时读到的也是刚切好的这一档。
     *
     * 用 [PlaybackSpeed.phoneOptions] 而不是 [PlaybackSpeed.options]：3× 只在手机 / 平板上提供
     * （电视端遥控器来回调这么快的档位没有意义），但**校验**两边都认，见那边的说明。
     */
    private fun cycleSpeed() {
        val lib = library ?: return
        val options = PlaybackSpeed.phoneOptions
        val current = options.indexOfFirst { kotlin.math.abs(it - userSpeed) < .001f }
            .takeIf { it >= 0 } ?: options.indexOf(1f).coerceAtLeast(0)
        val next = options[(current + 1) % options.size]
        endTempSpeed()
        userSpeed = next
        lib.playbackSpeed = next
        player?.setPlaybackSpeed(next)
        syncChips()
        hint.text = "倍速 ${PlaybackSpeed.label(next)}"
        hint.visibility = View.VISIBLE
        hint.bringToFront()
        val token = hintToken
        root.postDelayed({ if (hintToken == token) hint.visibility = View.GONE }, 900)
    }

    /**
     * 建（或复用）媒体会话。
     *
     * 回调只做「跟播放有关的那几件事」：播放 / 暂停、拖到某处、上一集 / 下一集、停止。
     * 每个回调都先看 `player != null`：会话可能在播放页收尾的最后一帧被系统调一下，
     * 那时播放器已经交回推荐页，不能再被它拽回来。
     */
    private fun ensureMediaSession(): com.hgtv.star.shared.media.MediaSessionHost? {
        mediaSession?.let { return it }
        if (mediaSessionFailed) return null
        val created = com.hgtv.star.shared.media.MediaSessionHost.create(
            this,
            { player?.play() },
            { player?.pause() },
            { position -> player?.seekTo(position) },
            { direction ->
                val d = detail ?: return@create
                val target = index + direction
                if (target in d.episodes.indices) play(target)
            },
            { if (!isFinishing) finish() },
        )
        if (created == null) mediaSessionFailed = true else mediaSession = created
        return created
    }

    /**
     * 调试模式那一块面板：打开就挂、关掉就摘。
     *
     * 与电视端的 `syncDebugOverlay` 同一条规则：把 `:shared` 的「近况」出口接到
     * [com.hgtv.star.shared.debug.DebugLog]，然后每秒把播放现场刷一遍。
     * 面板**不吃触摸**（见 `PhoneDebugOverlay`），开着它照样能点按钮。
     */
    private fun syncDebugOverlay() {
        val lib = library ?: return
        val on = runCatching { lib.debugMode }.getOrDefault(false)
        com.hgtv.star.shared.debug.DebugLog.enabled = on
        if (!on) {
            if (debugOverlay?.visibility == View.VISIBLE) PhoneDebugReport.resetSamples()
            debugOverlay?.visibility = View.GONE
            return
        }
        com.hgtv.star.shared.debug.debugSink = { tag, line ->
            com.hgtv.star.shared.debug.DebugLog.record(tag, line)
        }
        val overlay = debugOverlay ?: com.hgtv.star.phone.ui.PhoneDebugOverlay(this).also {
            debugOverlay = it
            com.hgtv.star.phone.ui.PhoneDebugOverlay.place(it, root, dp(8))
        }
        overlay.update(debugLines())
        PhoneDebugReport.sampleAsync(this) {
            if (!isFinishing && !isDestroyed) syncDebugOverlay()
        }
    }

    /** 播放现场那几行（与电视端面板的播放页内容同源：状态 / 位置 / 解码 / 尺寸 / 错误）。 */
    private fun debugLines(): List<String> {
        val extras = ArrayList<Pair<String, String>>(12)
        val d = detail
        val p = player
        val quality = if (::qualityChip.isInitialized) qualityChip.text.toString() else "-"
        if (d != null) extras += "剧集" to
            "${d.series.title} · ${index + 1}/${d.episodes.size}集 · $quality"
        val state = when {
            p == null -> "无播放器"
            p.playbackState == Player.STATE_BUFFERING -> "缓冲"
            p.playbackState == Player.STATE_READY -> if (p.isPlaying) "播放中" else "暂停"
            p.playbackState == Player.STATE_ENDED -> "已结束"
            else -> "空闲"
        }
        if (p != null) {
            extras += "状态" to "$state · ${clock(p.currentPosition)}/${clock(p.duration.coerceAtLeast(0))}" +
                " · 缓冲 ${clock(p.bufferedPosition.coerceAtLeast(0))}（${p.bufferedPercentage}%）" +
                " · ${p.playbackParameters.speed}×"
        } else {
            extras += "状态" to state
        }
        val init = if (metrics.initMs >= 0) " ${metrics.initMs}ms" else ""
        extras += "解码" to "${PhoneDecoder.mode.label} ${metrics.decoderName.ifBlank { "-" }}$init"
        extras += "编码" to metrics.mime.ifBlank { "-" } +
            metrics.codecs.takeIf { it.isNotBlank() }?.let { " · ${it.take(70)}" }.orEmpty()
        val size = if (metrics.width > 0 && metrics.height > 0)
            "${metrics.width}x${metrics.height}" else debugVideo
        val fps = if (metrics.frameRate > 0f) "@${Math.round(metrics.frameRate)}" else ""
        val bitrate = if (metrics.bitrate > 0) " ${metrics.bitrate / 1000}k" else ""
        val frameAge = if (lastFrameAt > 0L) {
            "${(android.os.SystemClock.elapsedRealtime() - lastFrameAt).coerceAtLeast(0) / 1000}s 前"
        } else "无逐帧回调"
        extras += "画面" to "$size$fps$bitrate · 帧龄 $frameAge · 竖屏=${d?.series?.vertical ?: false}" +
            " · ${if (landscapeMode) "横屏" else "竖屏"}"
        val firstFrame = lastFrameAt > 0L || metrics.firstFrameAt > 0L
        extras += "帧数" to "首帧=${if (firstFrame) "是" else "否"}" +
            " · 丢帧=${metrics.droppedFrames} · 欠载=${metrics.underruns}" +
            " · 解码错=${metrics.lastCodecError.isNotBlank()}"
        // 实际生效的档位与用户设置可能不同：自动降档只写会话层（与电视端同一条规则）。
        val cap = playbackMaxQuality
        val requested = library?.maxQuality ?: 0
        extras += "默认上限" to "${if (cap > 0) "${cap}P" else "-"}" +
            (if (cap < requested) "（本次降档）" else "") + " · 实际 $quality"
        if (sessionPlayback.touched()) {
            val reason = when (sessionPlayback.lastReason) {
                com.hgtv.star.core.DegradationKind.SAME_QUALITY_REBUILD -> "原档重建"
                com.hgtv.star.core.DegradationKind.QUALITY_DOWN -> "降清晰度"
                null -> "待手动处理"
            }
            extras += "降级" to "$reason · ${sessionPlayback.budgetSummary()} · 重试=${ledger.attempts}"
        }
        val errors = listOf(debugError, metrics.lastCodecError).filter { it.isNotBlank() }
            .joinToString(" · ")
        return PhoneDebugReport.lines(
            this, "播放", library, extras,
            problem = if (errors.isBlank()) "无" else "播放失败",
            error = errors,
            recent = 5,
        )
    }

    /** 把「正在放什么」同步给系统（每秒一次，与进度条同一个节拍）。 */
    private fun syncMediaSession() {
        val d = detail ?: return
        val session = ensureMediaSession() ?: return
        val p = player
        val state = when {
            p == null -> android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED
            p.playbackState == Player.STATE_BUFFERING ->
                android.support.v4.media.session.PlaybackStateCompat.STATE_BUFFERING
            p.playbackState == Player.STATE_ENDED ->
                android.support.v4.media.session.PlaybackStateCompat.STATE_STOPPED
            p.isPlaying -> android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING
            else -> android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED
        }
        session.update(
            title = d.series.title,
            episode = index,
            duration = p?.duration?.coerceAtLeast(0) ?: 0,
            position = p?.currentPosition ?: 0,
            speed = userSpeed,
            state = state,
            previous = index > 0,
            next = index < d.episodes.lastIndex,
        )
    }

    internal fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            var lastSave = 0L
            while (true) {
                delay(1000)
                val p = player ?: continue
                val duration = p.duration
                if (duration > 0) {
                    val position = p.currentPosition
                    lastDuration = duration
                    PhoneSession.markPosition(position)
                    // 还在播就刷新会话时间戳（见 PhoneSession.touch）：退回推荐页时才敢接手。
                    if (p.isPlaying) PhoneSession.touch()
                    // 弹幕时钟：位置 + 是否在播 + 倍速。暂停 / 跳转 / 换倍速都不会脱节。
                    syncDanmakuClock(position, p.isPlaying, p.playbackParameters.speed)
                    if (!dragging) updateProgress(position, duration)
                    // 媒体面板的进度 / 状态跟着走：锁屏上那条进度不能停在进入播放页那一刻。
                    syncMediaSession()
                    // 定时关闭：按分钟计的那几档到点在这里停（按集计的在片尾那一下停）。
                    if (!sleepStopped && sleepTimer.poll()) stopForSleep()
                    // 调试面板：与进度同一节拍刷新（关着时这一步只是一次布尔判断）。
                    syncDebugOverlay()
                    // 起播稳定后尽早准备下一集，手动上滑与片尾自动连播共用这一份。
                    maybePrefetchNext(position, duration)
                    enqueueNextIfReady()
                    val now = System.currentTimeMillis()
                    if (now - lastSave >= 5000) {
                        lastSave = now
                        saveProgress(position, duration)
                    }
                }
            }
        }
    }

    private fun updateProgress(position: Long, duration: Long) {
        val permille = (position * 1000 / duration).toInt().coerceIn(0, 1000)
        page.setProgress(permille / 1000f)
        val bufferedPermille=((player?.bufferedPosition ?: position).coerceIn(0,duration)*1000/duration)
            .toInt().coerceIn(0,1000)
        page.setBufferedProgress(bufferedPermille/1000f)
        if (!dragging) {
            landSeek.progress = permille
            landSeek.secondaryProgress = bufferedPermille
        }
        landCurrent.text = clock(position)
        landTotal.text = clock(duration)
        // 时间每分钟才变一次，但网速每秒都在变：两项任意一项开着就按秒刷。
        if (library?.playerClock == true || library?.playerSpeed == true) syncPlayInfo()
    }

    private fun saveProgress(position: Long, duration: Long) {
        val d = detail ?: return
        val lib = library ?: return
        if (duration <= 0) return
        val episodeId = d.episodes.getOrNull(index) ?: return
        // 位置夹取与「离片尾 3 秒算看完」的判据都在 `:core`（`WatchProgress.of`），
        // 电视端写进度走的是同一份，两端的「看完」不会差一秒。
        val progress = WatchProgress.of(
            series = d.series,
            episodeId = episodeId,
            episodeIndex = index,
            position = position,
            duration = duration,
        )
        runCatching { lib.save(progress) }
        // 本机先落盘、账号侧随后推（未登录时那一半是空操作）。
        runCatching { PhoneAccountSync.progress(this, progress, d.episodes.size) }
    }

    /**
     * 播放稳定后尽早预热下一集。
     *
     * 手动上下滑可能发生在任意时刻，不能像以前一样等到片尾 30 秒才准备。这里在当前集
     * 稳定播放 2 秒后开始取地址与首块，并把它挂进播放列表；片尾自动推进与手动上滑
     * 都复用这一项，因此不会重新 release 播放器。
     *
     * 地址只有 10 分钟有效期（见 [PlaybackCache.URL_FRESH_MS]），所以长视频只会在
     * 剩余时长小于安全窗口时开始预热，避免下一集真正播放时地址已经过期。
     */
    private fun maybePrefetchNext(position: Long, duration: Long) {
        val d = detail ?: return
        val lib = library ?: return
        if (!lib.autoNext) return
        val nextIndex = index + 1
        if (nextIndex > d.episodes.lastIndex) return
        if (PhoneSession.nextQueued) return
        if (PhoneSession.nextRemote != null) return
        if (player?.isPlaying != true) return
        if (position < PREFETCH_START_MS) return
        val remaining = duration - position
        if (remaining <= 0 || remaining > PlaybackCache.URL_FRESH_MS - PREFETCH_URL_RESERVE_MS) return
        val now = System.currentTimeMillis()
        if (now < prefetchAt) return
        val nextId = d.episodes[nextIndex]
        if (prefetchingFor == nextId) return
        prefetchingFor = nextId
        // 失败就 8 秒后再试一次：片尾之前还有机会，但不至于每秒撞接口。
        prefetchAt = now + 8_000

        val maxQuality = playbackMaxQuality
        scope.launch {
            val info = withContext(Dispatchers.IO) {
                runCatching { PhoneServices.repository(this@PhonePlayerActivity).stream(nextId, maxQuality) }
                    .getOrNull()
            }
            val prepared = if (info == null) null else withContext(Dispatchers.IO) {
                prepare(nextId, info)
            }
            prefetchingFor = ""
            if (prepared == null) return@launch
            // 期间用户可能已经换集 / 换剧：对不上就丢掉，别把别的集挂上去。
            if (detail?.episodes?.getOrNull(index + 1) != nextId) {
                prepared.close()
                return@launch
            }
            PhoneSession.putNext(nextId, prepared)
            enqueueNextIfReady()
        }
    }

    /**
     * 把预热好的下一集**挂进播放列表**。
     *
     * 为什么不是片尾再 `setMediaSource`：那会 `resetInternal → releaseCodec → prepare`，
     * 黑屏、转圈、解码器重建全来一遍。挂进列表之后 ExoPlayer 自己把 period 前移一格
     * （`DISCONTINUITY_REASON_AUTO_TRANSITION`），画面不断。
     *
     * `shouldLoadNextMediaPeriod()` 要求当前 period 已完全缓冲，所以早挂不会抢当前这一集的带宽。
     */
    private fun enqueueNextIfReady() {
        if (PhoneSession.nextQueued) return
        // 按集计的定时：不把下一集挂进播放列表——挂进去 ExoPlayer 会自己推进，
        // 片尾那一下拦不住（与 TV 的 `enqueuePreparedNext` 同一条规则）。
        if (!sleepStopped && sleepTimer.episodesLeft > 0) return
        val d = detail ?: return
        val exo = player ?: return
        val nextIndex = index + 1
        if (nextIndex > d.episodes.lastIndex) return
        val nextId = d.episodes[nextIndex]
        val video = PhoneSession.takeNext(nextId, playbackMaxQuality) ?: return

        val factory = DataSource.Factory { VideoDataSource(video) }
        val source = ProgressiveMediaSource.Factory(factory).createMediaSource(
            MediaItem.Builder().setMediaId(nextId).setUri(video.info.url).build()
        )
        if (runCatching { exo.addMediaSource(source) }.isFailure) {
            // 挂失败就当没预热过：片尾仍走 `STATE_ENDED → play()` 兜底。
            runCatching { video.close() }
            return
        }
        queuedVideo = video
        queuedEpisodeId = nextId
        PhoneSession.nextQueued = true
        android.util.Log.i("HGTV-PHONE", "next queued id=${nextId.takeLast(6)} episode=${nextIndex + 1}")
    }

    /**
     * 兜底换集：预热没成功、或列表里没挂上时，片尾仍然要能往下走。
     *
     * 与 TV 的 `STATE_ENDED → playEpisode` 同一条路，只是没有「无感」可言（会重新取地址）。
     */
    private fun next() {
        val d = detail ?: return
        // 定时关闭（按集计）：片尾那一下先问计时器，到点就停在这里，不进下一集。
        // 与 TV 的 `sleepTimer.episodeEnded()` 同一条判据，且**必须在换集之前**问——
        // 换完再问就只能停在新一集的第一帧，用户读到的是「它已经跳过去了」。
        if (!sleepStopped && sleepTimer.episodesLeft > 0 && sleepTimer.episodeEnded()) {
            stopForSleep()
            return
        }
        if (index + 1 < d.episodes.size) play(index + 1) else advanceToNextSeasonOrFinish(d)
    }

    /**
     * 进入最后一集时提前查下一季，查到就在画面底部提示。
     */
    private fun prepareNextSeasonIfNeeded(d: Detail) {
        val active = d.series.id == detail?.series?.id &&
            index == d.episodes.lastIndex &&
            library?.autoNext == true &&
            !sleepStopped
        if (!active) {
            clearNextSeasonLookup()
            return
        }
        ensureNextSeason(d.series)
    }

    private fun clearNextSeasonLookup() {
        nextSeasonToken++
        nextSeasonJob?.cancel()
        nextSeasonJob = null
        nextSeasonFor = ""
        nextSeason = null
        nextSeasonLoading = false
        autoNextSeasonPending = false
        nextSeasonOpening = false
        nextSeasonFinishedFor = ""
        hideNextSeasonNotice()
    }

    private fun ensureNextSeason(series: Series) {
        if (nextSeasonFor == series.id) {
            if (!nextSeasonLoading) showNextSeasonNotice()
            return
        }
        val token = ++nextSeasonToken
        nextSeasonJob?.cancel()
        nextSeasonFor = series.id
        nextSeason = null
        nextSeasonLoading = true
        nextSeasonOpening = false
        hideNextSeasonNotice()
        val cached = PhoneSession.relatedWorks(series.id)
        if (cached != null) {
            completeNextSeasonLookup(series, cached, token)
            return
        }
        nextSeasonJob = scope.launch {
            val works = withContext(Dispatchers.IO) {
                runCatching {
                    PhoneServices.repository(this@PhonePlayerActivity).related(series.id, series.title)
                }.getOrNull()
            }
            if (token != nextSeasonToken || isFinishing || isDestroyed) return@launch
            if (works != null) PhoneSession.rememberRelated(series.id, works)
            completeNextSeasonLookup(series, works, token)
        }
    }

    private fun completeNextSeasonLookup(
        series: Series,
        works: com.hgtv.star.core.RelatedWorks?,
        token: Int,
    ) {
        if (token != nextSeasonToken || isFinishing || isDestroyed) return
        nextSeasonJob = null
        nextSeasonLoading = false
        nextSeason = works?.let { com.hgtv.star.core.SeriesSeasons.nextSeason(series, it) }
        if (nextSeason != null) showNextSeasonNotice() else hideNextSeasonNotice()
        if (autoNextSeasonPending) {
            autoNextSeasonPending = false
            val target = nextSeason
            if (target != null) openNextSeason(target) else finishSeriesPlayback(series)
        }
    }

    private fun showNextSeasonNotice() {
        val d = detail ?: return
        val target = nextSeason ?: return
        val active = d.series.id == nextSeasonFor &&
            index == d.episodes.lastIndex &&
            library?.autoNext == true &&
            !sleepStopped
        if (!active) {
            hideNextSeasonNotice()
            return
        }
        nextSeasonNotice.text =
            "将自动播放 ${com.hgtv.star.core.SeriesSeasons.titleWithSeason(target)}"
        nextSeasonNotice.visibility = View.VISIBLE
    }

    private fun hideNextSeasonNotice() {
        if (::nextSeasonNotice.isInitialized) nextSeasonNotice.visibility = View.GONE
    }

    /** 最后一集播完：有下一季就播第 1 集，否则停在「全剧已播完」。 */
    private fun advanceToNextSeasonOrFinish(d: Detail) {
        val series = d.series
        if (nextSeasonFinishedFor == series.id) return
        if (library?.autoNext != true) {
            finishSeriesPlayback(series)
            return
        }
        if (nextSeasonFor == series.id) {
            if (nextSeasonLoading) {
                autoNextSeasonPending = true
                return
            }
            val target = nextSeason
            if (target != null) openNextSeason(target) else finishSeriesPlayback(series)
            return
        }
        autoNextSeasonPending = true
        ensureNextSeason(series)
    }

    private fun openNextSeason(target: Series) {
        if (nextSeasonOpening) return
        nextSeasonOpening = true
        autoNextSeasonPending = false
        hideNextSeasonNotice()
        val token = ++nextSeasonToken
        nextSeasonJob?.cancel()
        nextSeasonJob = scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    PhoneServices.repository(this@PhonePlayerActivity).detail(target.id)
                }.getOrNull()
            }
            if (token != nextSeasonToken || isFinishing || isDestroyed) return@launch
            if (loaded == null || loaded.episodes.isEmpty()) {
                nextSeasonOpening = false
                finishSeriesPlayback(detail?.series ?: target)
                return@launch
            }
            nextSeasonOpening = false
            nextSeasonJob = null
            nextSeasonFor = ""
            nextSeason = null
            nextSeasonLoading = false
            nextSeasonFinishedFor = ""
            detail = loaded
            play(0)
        }
    }

    private fun finishSeriesPlayback(series: Series) {
        nextSeasonFinishedFor = series.id
        nextSeasonOpening = false
        autoNextSeasonPending = false
        hideNextSeasonNotice()
        player?.pause()
        hint.text = "全剧已播完"
        hintSticky = true
        hint.visibility = View.VISIBLE
        hint.bringToFront()
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
    private fun showRelated(d: Detail, title: String, heading: String) {
        val series = d.series
        val token = ++relatedToken
        // 作废上一轮（设置面板）排下的自动收起：这一层不自动收，但那个定时器还在路上，
        // 不换 epoch 它到点就会把刚打开的卡片条收走。
        chromeEpoch++
        if (::root.isInitialized) root.removeCallbacks(chromeHideRunnable)
        val cached = PhoneSession.relatedWorks(series.id)
        // 横竖屏都用**贴底整宽**的条：电视端的 RelatedPanel 就是这样，且横屏时
        // 右侧 1/3 抽屉只放得下两张卡，浏览「换一部」反而更费劲。
        sheet.bottomInset = if (landscapeMode) navigationBarPx() else 0
        sheet.show(
            title,
            landscape = false,
            dim = false,
            heightFraction = if (landscapeMode) .95f else .78f,
        ) { panel ->
            // 状态行：加载中 / 共 N 部（同系列多季时点明季数，与 TV 的标题说法一致）。
            val state = TextView(this).apply {
                text = if (cached != null) relatedHeadline(cached, heading) else "正在找相关剧集…"
                setTextColor(PhoneStyle.textDim)
                phoneSize(12)
                setPadding(dp(2), 0, dp(2), dp(10))
                tag = "related-state"
            }
            panel.addView(state, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            panel.addView(host, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            if (cached != null) {
                fillRelated(host, cached, series, state)
                return@show
            }
            // 没取过（从历史 / 搜索直接开播）：这一栏自己取一次，取到就地铺。
            // 走的是与详情页同一个接口、同一份进程缓存，因此第二次进来必然是零等待。
            scope.launch {
                val works = withContext(Dispatchers.IO) {
                    runCatching { PhoneServices.repository(this@PhonePlayerActivity).related(series.id, series.title) }
                        .getOrNull()
                }
                if (token != relatedToken || isFinishing || isDestroyed) return@launch
                if (works == null) {
                    state.text = "相关剧集没能取到，稍后再试。"
                    return@launch
                }
                PhoneSession.rememberRelated(series.id, works)
                state.text = relatedHeadline(works, heading)
                fillRelated(host, works, series, state)
            }
        }
        relatedSheetOpen = true
        // 这里**不**排自动收起：它是一层「挑下一部」的浏览浮层，用户正在横向翻卡片，
        // 到点收走等于把正在看的那一排抽掉（电视端的 `RelatedPanel` 也只在用户操作时收起）。
        // 上一轮排下的截止时刻已经作废——`chromeEpoch` 每次重排都会换。
    }

    /** 状态行文案：与电视端 `buildRelatedRow` 的标题同一套说法。 */
    private fun relatedHeadline(works: com.hgtv.star.core.RelatedWorks, heading: String): String {
        val seasons = works.seasons.size
        return if (seasons > 1) "$heading · 同系列 $seasons 季 · 共 ${works.items.size} 部" else "$heading · 共 ${works.items.size} 部"
    }

    /**
     * 把相关剧集铺成一排卡片。
     *
     * 卡片是 [PhoneCards.row]，**详情页「相关剧集」那一栏用的是同一份**——
     * 两处因此不可能再分叉（电视端也是 `buildRelatedRow` 一处实现）。
     */
    private fun fillRelated(
        host: LinearLayout,
        works: com.hgtv.star.core.RelatedWorks,
        current: com.hgtv.star.core.Series,
        state: TextView,
    ) {
        if (works.items.isEmpty()) {
            state.text = "暂时没有找到相关剧集，可以先返回看看别的。"
            return
        }
        host.removeAllViews()
        host.addView(
            PhoneCards.row(
                this,
                PhoneServices.artwork(this),
                works.items,
                current.id,
                widthDp = if (landscapeMode) PhoneCards.COMPACT_WIDTH_DP else PhoneCards.WIDTH_DP,
            ) { item ->
                // 点卡片进详情页（与电视端的「换一部接着看」同一落点）。
                openSeries(item)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    /**
     * 播放页作者作品：与详情页使用同一个 [ContentRepository.authorWorks] 数据源，
     * 以底部横排卡片展示；同一部剧重复打开时复用本次播放页的缓存。
     */
    private fun showAuthorWorks(d: Detail) {
        val series = d.series
        val name = series.author.trim()
        if (name.isEmpty()) {
            toast("这一部没有作者信息")
            return
        }
        chromeEpoch++
        if (::root.isInitialized) root.removeCallbacks(chromeHideRunnable)
        val cached = authorWorks.takeIf { authorWorksFor == series.id }
        sheet.bottomInset = if (landscapeMode) navigationBarPx() else 0
        sheet.show(
            "作者作品",
            landscape = false,
            dim = false,
            heightFraction = if (landscapeMode) .95f else .78f,
        ) { panel ->
            val state = TextView(this).apply {
                text = if (cached != null) "正在读取作者作品…" else "正在查找 $name 的作品…"
                setTextColor(PhoneStyle.textDim)
                phoneSize(12)
                setPadding(dp(2), 0, dp(2), dp(10))
            }
            panel.addView(state, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            panel.addView(host, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            fun fill(works: List<Series>) {
                val items = (listOf(series) + works.filterNot { it.id == series.id })
                    .distinctBy { it.id }
                state.text = "$name · 共 ${items.size} 部作品"
                host.removeAllViews()
                if (items.size <= 1) {
                    state.text = "$name · 暂时只有这一部作品"
                    return
                }
                host.addView(
                    PhoneCards.row(
                        this,
                        PhoneServices.artwork(this),
                        items,
                        series.id,
                        widthDp = if (landscapeMode) PhoneCards.COMPACT_WIDTH_DP else PhoneCards.WIDTH_DP,
                    ) { item ->
                        openSeries(item)
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ),
                )
            }
            if (cached != null) {
                fill(cached)
                return@show
            }
            val token = ++authorToken
            scope.launch {
                val works = withContext(Dispatchers.IO) {
                    runCatching {
                        PhoneServices.repository(this@PhonePlayerActivity)
                            .authorWorks(name, series)
                    }.getOrNull()
                }
                if (token != authorToken || isFinishing || isDestroyed) return@launch
                authorWorksFor = series.id
                authorWorks = works
                if (works == null) {
                    state.text = "作者作品没能取到，稍后再试。"
                    return@launch
                }
                fill(works)
            }
        }
        relatedSheetOpen = true
    }

    /** 播放设置动作区里的收藏切换，状态同时写入本机与账号缓存。 */
    private fun toggleFavorite(series: Series): Boolean {
        val lib = library ?: return false
        val accountFavorite = PhoneAccountSync.data?.favorites?.any { it.series.id == series.id } == true
        val current = runCatching { lib.favorite(series.id) }.getOrDefault(false) || accountFavorite
        if (current) {
            if (runCatching { lib.favorite(series.id) }.getOrDefault(false)) lib.toggle(series)
            PhoneAccountSync.favorite(this, series, false) { toast("登录后可同步收藏") }
            toast("已取消收藏")
            return false
        }
        if (!runCatching { lib.favorite(series.id) }.getOrDefault(false)) lib.toggle(series)
        PhoneAccountSync.favorite(this, series, true) { toast("登录后可同步收藏") }
        toast("已加入收藏")
        return true
    }

    /**
     * 点一张相关卡片：**进详情页**，与电视端 `showFinishedStrip` 里 `openDetail(target)` 同一语义。
     *
     * 为什么不直接起播：电视端那一栏点的就是详情页——用户要的是「这部是什么、有几集、
     * 从第几集接着看」，直接起播等于替他做了这个决定。
     *
     * 为什么**不** `finish()` 本页：电视端只有一个 Activity，从播放页进详情、按返回会回到
     * 播放界面同一位置。手机上如果先收掉播放页，返回就落到推荐流上，用户丢掉「刚才看到哪」。
     * 因此本页留在栈里（`onPause` 会存进度并暂停），详情页盖在上面，返回即可接着看。
     */
    private fun openSeries(item: com.hgtv.star.core.Series) {
        sheet.dismiss()
        dismissLandscapeSettings()
        phoneNav().open(Route.Detail(item.id, item.title))
    }

    private fun releasePlayer() {
        ticker?.cancel(); ticker = null
        cancelAutoRetry()
        clearPlaybackError()
        listener?.let { player?.removeListener(it) }
        listener = null
        player?.pause()
        if (::page.isInitialized) PhoneServices.detach(page.playerView)
        if (::landscapeVideo.isInitialized) PhoneServices.detach(landscapeVideo)
        player = null
        runCatching { remote?.close() }
        remote = null
    }

    // ---------------------------------------------------------------- 网络恢复

    /** 网络回调；非空 = 正在监听（见 [watchNetwork]）。 */
    private var networkWatch: android.net.ConnectivityManager.NetworkCallback? = null

    /**
     * 网络恢复时**立刻重取地址**，不等退避计时（与电视端 `refetchAddressOnNetwork` 同一条）。
     *
     * 手机切 Wi-Fi / 5G 比电视频繁：断网那一下正好在取地址，退避还要等 3~12 秒，
     * 而网络其实已经好了。电视端早就有这条，手机端原先连网络状态都不看。
     *
     * 只在**正在等地址**（[sourceRetryCount] > 0）或**停在错误页**时动手，
     * 正常播放期间网络抖动不该把这一集重来一遍。
     */
    private fun watchNetwork() {
        if (networkWatch != null) return
        // `registerDefaultNetworkCallback` 是 API 24 起才有的；更低版本交给退避重试兜底。
        if (android.os.Build.VERSION.SDK_INT < 24) return
        val manager = getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (errorCard == null && sourceRetryCount == 0) return@runOnUiThread
                    cancelAutoRetry()
                    val at = runCatching { player?.currentPosition ?: 0L }.getOrDefault(0L).coerceAtLeast(0)
                    play(index, at)
                }
            }
        }
        if (runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess) {
            networkWatch = callback
        }
    }

    private fun unwatchNetwork() {
        val callback = networkWatch ?: return
        networkWatch = null
        val manager = getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager
        runCatching { manager?.unregisterNetworkCallback(callback) }
    }

    // ---------------------------------------------------------------- 面板自动收起

    /**
     * 播放控制信息「无操作几秒后自动隐藏」（设置 → 播放界面，2–15 秒）。
     *
     * 与 TV 的 `playerPanelTimeout` 同一份落盘值：计时从**最后一次操作**算起，
     * 到点后收起播放设置 / 选集面板和整层标题、进度条；任何触摸都会重新显示并重新计时
     * （见 [dispatchTouchEvent]）。相关剧集浮层是浏览层，不排自动隐藏。
     */
    private fun scheduleChromeHide() {
        if (!chromeVisible || relatedSheetOpen) {
            chromeEpoch++
            if (::root.isInitialized) root.removeCallbacks(chromeHideRunnable)
            return
        }
        val seconds = library?.playerPanelTimeout ?: 6
        chromeEpoch++
        chromeHideAt = System.currentTimeMillis() + seconds * 1000L
        root.removeCallbacks(chromeHideRunnable)
        root.postDelayed(chromeHideRunnable, seconds * 1000L)
    }

    /** 自动收起的时长（毫秒）；设置没读到时按 TV 的出厂值 6 秒。 */
    private fun chromeTimeoutMs(): Long = ((library?.playerPanelTimeout ?: 6) * 1000L)

    /**
     * 面板打开期间，**每一次触摸**都把自动收起的计时往后推。
     *
     * 按下时重新排队；拖动（MOVE）与抬手（UP）只改截止时刻——否则拖一条滑块拖了七八秒，
     * 面板会在手指还按着的时候被收走。MOVE 不重新排队，避免一帧一个 post。
     */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (chromeVisible && !relatedSheetOpen) {
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> scheduleChromeHide()
                android.view.MotionEvent.ACTION_MOVE,
                android.view.MotionEvent.ACTION_UP -> {
                    if (::root.isInitialized) {
                        chromeHideAt = System.currentTimeMillis() + chromeTimeoutMs()
                        root.removeCallbacks(chromeHideRunnable)
                        root.postDelayed(chromeHideRunnable, chromeTimeoutMs())
                    }
                }
            }
        }
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
    private fun openSettings() {
        // 自动收起之后长按中间仍然能打开设置：先把控制层叫回来，
        // 否则面板会出现在一层已经隐藏的界面上，用户看不到自己改的是哪一页。
        if (!chromeVisible) setChromeVisible(true)
        // 书库还没读完时**不能直接 return**：那正是「点了没反应」的一种。
        // 等它读完再弹，用户的这一下点击不会白点。
        val lib = library
        if (lib == null) {
            scope.launch {
                library = PhoneServices.awaitLibrary(this@PhonePlayerActivity)
                openSettings()
            }
            return
        }
        if (landscapeMode) openLandscapeSettings(lib) else openPortraitSettings(lib)
    }

    /**
     * 竖屏：底部整宽浮层，**只占屏高 1/3**（用户规范）。
     *
     * 各行与电视端的播放设置面板对应（清晰度 / 倍速 / 画面模式 / 解码 / 画面缩放 /
     * 弹幕显示 / 弹幕速度 / 弹幕大小 / 弹幕透明度），写的是同一份落盘值。
     *
     * 为什么限定三分之一：竖屏播放页上半屏是画面，设置铺到九成高就等于把画面关掉了。
     * 三分之一高（1080×1920 上约 640px）+ 内部滚动，一次能看到三到四项，
     * 手指一滑就到底，既知道「面板就这么高」又不会找不到最后一项。
     */
    private fun openPortraitSettings(lib: Library) {
        sheet.show("播放设置", landscape = false, heightFraction = 1f / 3f) { panel ->
            buildSettingsRows(panel, lib, perLine = 0)
        }
        scheduleChromeHide()
    }

    // ---------------------------------------------------------------- 横屏播放设置

    /** 横屏面板的外壳（左 2/3 点击收起 + 右 1/3 设置）；为 null = 没打开。 */
    private var landPanel: FrameLayout? = null

    /**
     * 播放设置的各行（竖屏 / 横屏共用，[perLine] 只在横屏的 1/3 抽屉里折行）。
     *
     * 顺序与电视端一致：先「怎么看」（清晰度 / 倍速 / 画面 / 解码 / 缩放），
     * 再「弹幕怎么滚」（显示方式 / 速度 / 字号 / 透明度）。
     */
    private fun buildSettingsRows(panel: LinearLayout, lib: Library, perLine: Int) {
        val series = detail?.series
        buildPlaybackSettingsRows(
            host = root,
            panel = panel,
            lib = lib,
            perLine = perLine,
            speed = userSpeed,
            onSpeed = { value ->
                userSpeed = value
                // 落盘：退回推荐页时那一侧读同一份，不会突然变回旧倍速。
                lib.playbackSpeed = value
                player?.setPlaybackSpeed(userSpeed)
                syncChips()
            },
            onQuality = {
                // 用户自己选了档位：自动降档的结论与预算全部让位（与电视端同一条规则）。
                sessionPlayback.onManualQualityChange()
                syncChips()
                // 换档 = 换一套地址表，必须重新取流；从当前位置接着播。
                play(index, player?.currentPosition ?: 0L)
            },
            onFrame = { mode ->
                applyFrameMode(mode)
                syncChips()
            },
            // 缩放 / 解码都只改画面或下一次起播，但底部摘要要立刻反映当前选择。
            onScale = { applyVideoScale(); syncChips() },
            onDecoder = { syncChips() },
            sleepChoice = if (sleepTimer.active) sleepChoice else 0,
            onSleep = { choice -> applySleepChoice(choice) },
            onRelated = {
                val d = detail
                if (d == null) {
                    toast("还在加载剧集")
                } else {
                    sheet.dismiss()
                    dismissLandscapeSettings()
                    showRelated(d, "相关剧集", "换一部接着看")
                }
            },
            onAuthor = {
                val d = detail
                if (d == null) {
                    toast("还在加载剧集")
                } else {
                    sheet.dismiss()
                    dismissLandscapeSettings()
                    showAuthorWorks(d)
                }
            },
            favorite = series?.let { item ->
                runCatching { lib.favorite(item.id) }.getOrDefault(false) ||
                    PhoneAccountSync.data?.favorites?.any { it.series.id == item.id } == true
            } ?: false,
            onFavorite = {
                val d = detail
                if (d == null) {
                    toast("还在加载剧集")
                    false
                } else {
                    toggleFavorite(d.series)
                }
            },
            // 弹幕四项：立刻落到正在画的这一层（字号 / 透明度 / 速度 / 显示方式都是内存字段，
            // 只写书库要等下一次换集才生效）。隐藏切回可见时补拉一次这一集的弹幕。
            onDanmaku = {
                val wasHidden = page.danmakuView.visibility == View.GONE
                applyDanmakuSettings()
                if (wasHidden && lib.danmakuMode != DanmakuMode.HIDDEN && !danmakuLoader.loaded) {
                    detail?.let { d -> d.episodes.getOrNull(index)?.let { loadDanmaku(it, d.series.id) } }
                }
                syncChips()
            },
        )
    }

    /**
     * 应用「定时关闭」的选择。
     *
     * 与 TV 的 `sleepLabels` 逐项对应：
     * * 关闭 → `cancel()`，并清掉「已停」状态（用户可能刚刚被停过一次又继续看）；
     * * 15 / 30 / 60 / 90 分钟 → 按分钟计，由 [startTicker] 里的 `poll()` 负责停；
     * * 本集播完 / 再 3 集 / 再 5 集 → 按集计，片尾那一下由 `episodeEnded()` 拦住。
     *
     * 按集计时时**不把下一集挂进播放列表**（见 [maybePrefetchNext] / [enqueueNextIfReady]）：
     * 挂进去 ExoPlayer 会自己推进，界面来不及在片尾停住，用户看到的就是「说好一集，结果又播了」。
     */
    private fun applySleepChoice(choice: Int) {
        sleepChoice = choice.coerceIn(0, com.hgtv.star.core.SleepTimer.LABELS.lastIndex)
        sleepStopped = false
        // 档位与分钟的对应关系只有一份（`core/SleepTimer.applyChoice`）：与电视端同源，
        // 用户在电视上选的「再 3 集」到手机上仍是同一档。
        val armed = com.hgtv.star.core.SleepTimer.applyChoice(sleepTimer, sleepChoice)
        if (armed) {
            hint.text = "定时已设置 · ${sleepTimer.label()}"
            hint.visibility = View.VISIBLE
        }
        if (!armed) hint.visibility = View.GONE
        syncChips()
    }

    /** 定时到点：暂停、落盘进度、给一句提示。与 TV 的 `stopForSleep` 同一套动作。 */
    private fun stopForSleep() {
        sleepStopped = true
        sleepChoice = 0
        sleepTimer.cancel()
        player?.pause()
        player?.let { runCatching { saveProgress(it.currentPosition, it.duration) } }
        hint.text = "定时停止已生效 · 观看位置已保存"
        hint.visibility = View.VISIBLE
        syncPlayGlyph()
        syncChips()
        syncMediaSession()
        syncKeepScreenOn()
    }

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
    private fun openLandscapeSettings(lib: Library) {
        if (landPanel != null) return

        val container = FrameLayout(this)
        val screenW = resources.displayMetrics.widthPixels
        // 抽屉宽度从 1/3 放到 40%：3 列选项里「完整画面 / 硬件解码 / 播完本集」
        // 都是 4 个汉字，1/3 宽（1920 屏上每格 177px）装不下 13.5 号字，
        // 会被 `TruncateAt.END` 截成「完整画…」。40% 下每格 220px，四个字 + 内边距
        // 共 198px，留 22px 余量；左侧仍有 60% 画面可看、可点收起。
        val rightW = (screenW * .4f).toInt().coerceAtLeast(dp(220))
        val leftW = screenW - rightW

        // ---- 左侧：只留「点一下收起」的透明区域，不再画已设置摘要 ----
        val leftDismiss = View(this).apply {
            setOnClickListener { dismissLandscapeSettings(); setChromeVisible(false) }
        }

        // ---- 右侧：各行可改，整列可滚 ----
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor((PhoneStyle.surface and 0x00FFFFFF) or (0xF2 shl 24))
                cornerRadius = 0f
                setStroke(1, 0x1AFFFFFF)
            }
            // 面板本身吃掉点击：否则点在两行之间会穿到左侧的「收起」上。
            isClickable = true
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = "播放设置"
            setTextColor(PhoneStyle.text)
            phoneSize(14)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(TextView(this).apply {
            text = "关闭"
            setTextColor(PhoneStyle.textDim)
            phoneSize(12)
            setPadding(dp(6), dp(4), 0, dp(4))
            setOnClickListener { dismissLandscapeSettings(); setChromeVisible(false) }
        })
        panel.addView(head)

        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        buildSettingsRows(inner, lib, perLine = 3)
        panel.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(inner, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        container.addView(leftDismiss, FrameLayout.LayoutParams(
            leftW, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START
        ))
        container.addView(panel, FrameLayout.LayoutParams(
            rightW, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END
        ))
        root.addView(container, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        landPanel = container
        syncLandscapeSettingsInfo()
        scheduleChromeHide()
    }

    /**
     * 把「已经设置好的值」刷到进度条下面那一行。
     *
     * 这一行是**常驻摘要**，不依赖设置面板是否打开；换集、换倍速、换清晰度、
     * 换画面模式 / 缩放 / 解码 / 弹幕 / 定时之后都会经 [syncChips] 走到这里。
     */
    private fun syncLandscapeSettingsInfo() {
        if (!::landSettingsInfo.isInitialized) return
        val lib = library ?: run {
            landSettingsInfo.text = ""
            return
        }
        // 只写四颗按钮**没有**承载的三项（+ 定时）：清晰度 / 倍速 / 选集 / 弹幕
        // 的当前值就在右侧按钮上，左边再列一遍会把这一行挤成省略号。
        val parts = ArrayList<String>(4)
        parts += lib.frameMode.label
        parts += "缩放 ${Math.round(lib.videoScale)}%"
        parts += lib.decoderMode.label
        if (sleepTimer.active) parts += "定时 ${sleepTimer.label()}"
        landSettingsInfo.text = parts.joinToString("  ·  ")
    }

    private fun dismissLandscapeSettings() {
        val panel = landPanel ?: return
        root.removeView(panel)
        landPanel = null
    }

    /**
     * 按用户的「画面模式」把画面落到位。
     *
     * 早先这里对竖屏片源强制 ZOOM：首集可能按卡片字段走了 FIT，切到下一集时
     * 实际尺寸已知、突然改成 ZOOM，画面就会「等比放大、左右丢失」。
     */
    private fun syncFrameMode() {
        applyFrameMode(library?.frameMode ?: VideoFrameMode.FIT)
    }

    private fun applyFrameMode(mode: VideoFrameMode) {
        val target = mode.toFit().resizeMode()
        page.setResizeMode(target)
        landscapeVideo.resizeMode = target
        if (feedUnderlay) PhoneSession.setFeedVideoResize(target)
    }

    // ---------------------------------------------------------------- 选集

    /**
     * 选集。**与播放设置分开**：横竖屏都用底部限高浮层，
     * 都不与「播放设置」共用一个面板。
     *
     * 为什么分开：选集是高频、连续的动作（看两集就想换），播放设置是低频、改一次就走的；
     * 混在一个面板里，用户想换集得先找到那一段，面板还更长。
     */
    private fun openEpisodes() {
        val d = detail
        if (d == null) {
            // 详情还没到：给一句提示，别让按钮看起来是坏的。
            toast("剧集还在加载…")
            return
        }
        if (landscapeMode) openLandscapeEpisodes(d) else openPortraitEpisodes(d)
    }

    /** 竖屏选集：底部整宽浮层。每行 5 个，一页行数跟着设置走。 */
    private fun openPortraitEpisodes(d: Detail) {
        sheet.show(
            "选集 · 正在看第 ${index + 1} 集",
            landscape = false,
            notifyHostOnUserDismiss = false,
        ) { panel ->
            panel.addView(
                PhoneEpisodeGrid(
                    this, d.episodes.size, index,
                    pageSize = library?.episodePageSize ?: 20,
                    compact = false,
                    columns = 5,
                    // 外层 PhoneSheet 已经是唯一的纵向滚动容器；网格若再限高会形成嵌套滚动，
                    // 横屏时最后一页按钮因此够不到。这里铺完整高度，交给浮层统一滚动。
                    maxGridHeight = Int.MAX_VALUE,
                    edgeTurn = library?.episodeEdgeTurn ?: true,
                    swipePages = true,
                ) { i ->
                    sheet.dismiss()
                    if (i != index) play(i)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        scheduleChromeHide()
    }

    /**
     * 横屏选集：TV 式底部限高浮层。
     *
     * **每行 10 个**；一页几行由设置页的「选集每页」决定
     * （20 / 30 / 50 / 80 / 100 → 2 / 3 / 5 / 8 / 10 行），放不下时网格内部滚动。
     */
    private fun openLandscapeEpisodes(d: Detail) {
        sheet.show(
            "选集 · 正在看第 ${index + 1} 集",
            landscape = true,
            heightFraction = .58f,
            notifyHostOnUserDismiss = false,
        ) { panel ->
            panel.addView(
                PhoneEpisodeGrid(
                    this, d.episodes.size, index,
                    pageSize = library?.episodePageSize ?: 20,
                    compact = true,
                    columns = 10,
                    maxGridHeight = Int.MAX_VALUE,
                    edgeTurn = library?.episodeEdgeTurn ?: true,
                    swipePages = true,
                ) { i ->
                    sheet.dismiss()
                    if (i != index) play(i)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        scheduleChromeHide()
    }

    // ---------------------------------------------------------------- 生命周期

    /**
     * 退出播放页：不要系统转场动画。
     *
     * 退回去时目标页（信息流）自己就是亮的画面，但转场那 200ms 里系统会把本页淡出、
     * 中间露出窗口底色——读起来就是「退出去也闪一下」。直接切过去更连贯。
     */
    override fun finish() {
        // 交给推荐页：交接窗口内两边都不暂停，播放器一直在跑，Surface 只是换了宿主。
        if (intent.getBooleanExtra(EXTRA_RECOMMEND, false)) {
            returningToFeed = true
            PhoneSession.beginHandoff()
        }
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
        intent ?: return
        setIntent(intent)
        val id = intent.getStringExtra(EXTRA_SERIES_ID).orEmpty()
        if (id.isBlank()) return
        if (id == detail?.series?.id) return
        sheet.dismiss()
        dismissLandscapeSettings()
        recreate()
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        // 网络恢复时立刻重取地址（与电视端 `refetchAddressOnNetwork` 同一条）：
        // 手机切 Wi-Fi / 5G 比电视频繁，断网那一下正在取地址的话不该干等退避计时。
        watchNetwork()
        // 上面又压了一层播放页（相关剧集）时，那一层会把自己的视频层挂到同一台播放器上，
        // 借来的这块 Surface 就不是输出端了。回到本页必须把输出抢回来，否则是一片黑。
        restoreUnderlayIfNeeded()
        // 设置页可能刚改过弹幕：回到本页重刷一次，不等下一次换集。
        applyDanmakuSettings()
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

    /**
     * 播放时保持屏幕常亮。
     *
     * `setWakeMode` 只保 CPU 与网络，**不会阻止屏幕熄灭**；这里按「前台 + 正在播 +
     * 没有停在错误页 / 定时停止」驱动窗口标志，暂停或退到后台立即放开。
     */
    private fun syncKeepScreenOn() {
        val exo = player
        val on = foreground && !isFinishing && !isDestroyed && !sleepStopped &&
            errorCard == null && exo?.playWhenReady == true &&
            exo.playbackState != Player.STATE_ENDED
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * 借下层出画面时，确认输出仍然在推荐页那一块上。
     *
     * 只在竖屏、且 `PhoneSession.underlayActive` 已经变 false（被别的播放页抢走）时重挂：
     * 正常进出（推荐页 ↔ 本页）不会走到这里，因此不会多一次闪屏。
     */
    private fun restoreUnderlayIfNeeded() {
        if (!feedUnderlay || landscapeMode) return
        if (PhoneSession.underlayActive) return
        val exo = player ?: return
        underlayBound = false
        underlayNeedsForce = true
        if (attachUnderlay()) return
        dropUnderlay()
        PhoneSession.underlayActive = false
        page.playerView.player = exo
    }

    override fun onPause() {
        super.onPause()
        foreground = false
        unwatchNetwork()
        syncKeepScreenOn()
        chromeEpoch++
        root.removeCallbacks(chromeHideRunnable)
        // 退到前台之外：暂停与后台都不是卡死，看门狗一律撤掉（回来时由 onResume 重装）。
        disarmPictureWatchdog()
        // 退了前台就不再占着系统媒体面板：那时面板上的按键应该交给真正在前台的应用。
        mediaSession?.deactivate()
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
        mediaSession?.release()
        mediaSession = null
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
        ticker?.cancel(); ticker = null
        listener?.let { player?.removeListener(it) }
        listener = null
        val handoff = handingBackToFeed()
        player?.let { exo ->
            PhoneSession.markPosition(exo.currentPosition)
            // **只在会话仍属于播放页时暂停**：退回推荐页时信息流已经先一步接手
            //（A.onResume 早于 B.onDestroy），这里再 pause 一次会把刚恢复的播放按停，
            // 用户看到的就是「退回去之后内容断了」。
            PhoneSession.resumeOnAdopt = exo.playWhenReady
            // 交接窗口内（或会话已被别人接手）一律不暂停：这一路还在被用。
            if (!handoff && !PhoneSession.inHandoff() &&
                PhoneSession.owner == PhoneSession.Owner.PLAYER
            ) {
                exo.pause()
            }
        }
        if (::page.isInitialized) PhoneServices.detach(page.playerView)
        if (::landscapeVideo.isInitialized) PhoneServices.detach(landscapeVideo)
        // player 字段留在 Activity 里没有意义（Activity 要销毁了），但**进程级实例**必须保住：
        // `PhoneServices.player` 是单例，这里只是不再引用它。
        player = null
        remote = null
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // 顺序：先收面板；横屏播放页直接返回上一页，不再先转竖屏。
        if (landPanel != null) { dismissLandscapeSettings(); return }
        if (landscapeMode) {
            finish()
            return
        }
        if (portraitFullscreenFromLandscape) {
            finishAfterLandscapeRestore = true
            portraitFullscreenFromLandscape = false
            if (!requestOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                finishAfterLandscapeRestore = false
                finish()
                return
            }
            // 个别 ROM 不回调方向变化：留一个兜底，避免返回键看起来没反应。
            root.postDelayed({
                if (finishAfterLandscapeRestore && !isFinishing && !isDestroyed) {
                    finishAfterLandscapeRestore = false
                    finish()
                }
            }, 800L)
            return
        }
        super.onBackPressed()
    }

    /** 轻提示：按钮暂时不可用时告诉用户「在等什么」，而不是毫无反应。 */
    internal fun toast(text: String) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 位置读数：与电视端 `formatTime` 同一份（`:core` 的 `PlaybackInfoLine.time`）。 */
    private fun clock(ms: Long): String = com.hgtv.star.core.PlaybackInfoLine.time(ms)
}
