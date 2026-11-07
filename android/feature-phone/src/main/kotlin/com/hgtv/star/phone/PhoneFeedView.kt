// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hgtv.star.core.CategorySelection
import com.hgtv.star.core.DanmakuMode
import com.hgtv.star.core.Detail
import com.hgtv.star.core.PlaybackQuality
import com.hgtv.star.core.PlaybackSpeed
import com.hgtv.star.core.RemoteVideo
import com.hgtv.star.core.toFit
import com.hgtv.star.core.Series
import com.hgtv.star.core.VideoFrameMode
import com.hgtv.star.phone.ui.PhoneColor
import com.hgtv.star.phone.ui.PhonePagerSnapHelper
import com.hgtv.star.phone.ui.PhoneSheet
import com.hgtv.star.phone.ui.PhoneTouch
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.phoneSize
import com.hgtv.star.phone.ui.resizeMode
import com.hgtv.star.phone.ui.roundedBackground
import com.hgtv.star.shared.data.Library
import com.hgtv.star.shared.data.PlaybackTransport
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
 * 首页：竖屏全屏视频流，一屏一集、上下滑切换、进屏即播。
 *
 * **手势与播放页完全一致**（见 [PhoneTouch]）：
 *
 * | 手势 | 行为 |
 * | --- | --- |
 * | 点击 | 播放 / 暂停，中心出现播放 / 暂停按钮 |
 * | 长按左 / 右 1/3 | 2 倍速，松手恢复，上方提示 |
 * | 长按中间 1/3 | 播放设置（倍速 / 清晰度 / 画面模式） |
 *
 * ## 为什么只留一个播放器
 *
 * 每屏一个 `ExoPlayer` 在 2GB 的 4.4 机器上会被系统直接杀掉（一个解码器实例
 * 就要占掉几十 MB 的图形内存）。这里全程只有一个播放器，切页时只换数据源、
 * 把 `PlayerView` 重新绑过去——与抖音那类信息流的做法一致。
 *
 * ## 切页时的三件事
 *
 * 1. 旧页 `PlayerView.player = null`（Surface 不能同时挂在两个 View 上）；
 * 2. 取消上一页还在飞的取地址请求（`playToken`），避免「滑走了又跳回来」；
 * 3. 新页按本机进度续播（续播在播放页里也走同一份 `Library.progress`）。
 *
 * ## 信息流**不记进度、不进历史**
 *
 * 这里刷到的片子是「随便看看」，只在内存里的会话（`PhoneSession`）记位置：
 * 从这一屏点进正式播放页时能接着播，但**不写 `Library` 的观看记录**，
 * 「我的 → 历史」里也就不会堆满划过一眼的剧。历史只由正式播放页写。
 *
 * 代价是下次冷启动再刷到同一部剧会从头开始——这正是这一条需求要的取舍。
 */
class PhoneFeedView(
    context: Context,
    /**
     * 这一条流绑定的体裁。
     *
     * **必须在构造时给定**，不能建完再 `setGenre`：`init` 里就会铺落盘快照并取第一页，
     * 那两件事都读 `genre`。早先是 `PhoneFeedView(context).apply { setGenre(...) }`，
     * 构造期间的 `loadMore()` 已经带着 `genre=null`（全站混排）发了出去，晚回来的那一页
     * 会追加到刚清空的列表里——三个 tab 的快照互相污染，最后每个 tab 都放着同一批视频。
     */
    initialGenre: String? = null,
) : FrameLayout(context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val items = ArrayList<Series>()
    private val adapter = FeedAdapter()
    private val list = VerticalPagerList(context)

    private var offset = 0
    private var sessionId = ""
    private var loading = false
    private var ended = false
    /**
     * 这一条流现在是不是「被宿主收起来了」。
     *
     * 同一个切页动作会被通知两次：`PhoneMainActivity.onPause` 一次、`onEpisodeEnded`
     * 自己一次（片尾进正片）。重复执行会做两件坏事：把已经交给播放页的播放器再暂停一次、
     * 把 `resumeOnAdopt` 记成 false。因此按状态去重。
     *
     * **初始值是 true**：三条体裁流都在 pager 里，只有真正上屏的那一条才该起播
     * （见 `PhoneRecommendPage` 建页时按 `current` 调 `onShown()`）。早先默认 false，
     * 于是横滑换体裁时，三条流会同时 `playIndex(0)`、抢同一台播放器——
     * 用户看到的是「换个体裁，画面先是别的剧，再跳回来」。
     */
    private var hidden = true

    /** 当前这一屏播放信息的自动收起截止时刻与代次。 */
    private var chromeHideAt = 0L
    private var chromeEpoch = 0

    private val chromeHideRunnable = object : Runnable {
        override fun run() {
            if (hidden || PhoneSession.underlayActive) return
            val now = System.currentTimeMillis()
            if (now < chromeHideAt) {
                list.postDelayed(this, chromeHideAt - now)
                return
            }
            val target = currentChromePage() ?: return
            if (!target.isChromeVisible()) return
            if (sheetDelegate.isInitialized() && sheet.isOpen) sheet.dismiss()
            target.setChromeVisible(false)
        }
    }

    private var player: ExoPlayer? = null
    private var remote: RemoteVideo? = null
    private var playingIndex = -1
    private var playToken = 0
    private var boundPage: PhoneVideoPage? = null
    private var paused = false
    /** 播放意图变化（起播 / 暂停 / 收起）：宿主据此同步屏幕常亮。 */
    var onPlayStateChanged: (() -> Unit)? = null
    /** 正在播那一屏的详情：片尾自动进正片要用它取「还有没有下一集」。 */
    private var playingDetail: Detail? = null
    /** 已经因为片尾切去正片：防止 STATE_ENDED 重入时再开一个 Activity。 */
    private var advancing = false
    /** 上一次进入「交接 / 换下一部」的时刻：`advancing` 超时后自动失效（见 `onEpisodeEnded`）。 */
    private var advancingAt = 0L
    /** 推荐流的体裁筛选（`RankBoard.genre`）；null = 全部混排。 */
    private var genre: String? = initialGenre
    /**
     * 落盘快照 / 续流游标的命名空间：三条体裁流各存各的（见 `RecommendCache.fileOf`）。
     *
     * `v2-` 是**缓存格式版本**：上一版的 `setGenre` 晚于构造，三个命名空间里都混进了
     * 别类的条目（甚至同一部剧三份都有）。旧文件不能再用，否则升级后首屏仍是错的；
     * 换个前缀让它们自然过期即可。
     */
    private fun cacheKey(): String = "v2-" + genre.orEmpty().ifEmpty { "all" }

    /**
     * 进程级会话里那一路，是不是**本体裁流**起的。
     *
     * 三条流共用一台播放器，但各自是独立的列表：切 tab 时不能把另一条流正在播的
     * 那一部当成自己的当前项接过来（服务端三条流有重叠剧集，只看 `seriesId`
     * 会让三个 tab 永远停在同一个视频上）。会话的归属由 [PhoneSession.publish]
     * 的 `feed` 参数写入，播放页换集不会改写。
     */
    private fun sessionBelongsToThisFeed(): Boolean =
        PhoneSession.feedKey == cacheKey()

    /**
     * 服务端已经确认不吃 `filter_ids`（回空页）。
     *
     * 与电视端 `Library.recommendFilterIdsOff` 是**同一个落盘值**：在电视上确认过不行的设备，
     * 换到手机端也不必再撞两次。
     */
    private var filterIdsOff = false
    /** 连续「带了 `filter_ids` 却回空页」的次数：到 2 就永久关掉这一层。 */
    private var filterMiss = 0
    /**
     * 「推荐受众」（男频 / 女频）这一层的降级状态（`:core` 的 `OptionalFilter`）。
     *
     * 实测服务端对某些受众会整页回空（连流头都空）。带它拿到空页就这一次不带重取，
     * 连续两次仍空则本进程内不再带——与电视端同一套语义，用户至少还能刷到内容。
     */
    private val genderFilter = com.hgtv.star.core.OptionalFilter("recommendGender")
    /** 「下一次请求先别带受众」：只是这一次退让，不等于永久关闭。 */
    private var genderProbe = false
    /** 这一次进页面是否已经从落盘恢复过续流游标（每个体裁各一份）。 */
    private var cursorRestored = false
    private var progressTick: Runnable? = null
    private var tempSpeed = false
    private var userSpeed = 1f
    private var library: Library? = null
    /** 进程单例播放器上的监听器：换屏/换集前先摘掉，否则会越积越多。 */
    private var listener: Player.Listener? = null

    /**
     * 推荐流里连续几部放不了（与电视端 `recommendPlaybackFailures` 同一语义、同一上限）。
     *
     * 推荐页没有选集、也没有详情页可回，所以**不摆播放页那套错误界面**：
     * 放不了就换下一部。连换几部都不行才停在一句提示上，由用户自己上滑。
     * 计数只在「真的出画面」时归零（取地址成功不算）——见 [notePlaybackHealthy]。
     */
    private var recommendFailures = 0

    /** 每一屏当前在播的集（续播时可能不是第 1 集）。 */
    private val currentEpisodeIds = HashMap<Int, String>()
    private val currentEpisodeIndex = HashMap<Int, Int>()
    /** 推荐页起播后的 6 个模型批量预取（当前剧下一集 + 后 5 部不同剧）。 */
    private var recommendBatchKey = ""
    private var recommendBatchJob: Job? = null

    private val hint = TextView(context).apply {
        setTextColor(Color.WHITE)
        phoneSize(13)
        gravity = Gravity.CENTER
        setPadding(context.dp(14), context.dp(7), context.dp(14), context.dp(7))
        background = roundedBackground(0x88000000.toInt(), context.dp(14).toFloat())
        visibility = View.GONE
    }

    /**
     * 播放设置浮层。
     *
     * **挂在 Activity 的 decor 上**，不是挂在信息流自己身上：信息流外面套着横向翻页的
     * `RecyclerView`，底部还有固定 tab 栏。浮层挂在本页时，横滑会被 pager 抢走（用户读到的
     * 就是「设置面板滑不动」），tab 栏也压在面板最后一行上。decor 是整窗最上层，
     * 这两个问题一起消失——与 `PhoneCardMenu` 同一个做法。
     */
    private val sheetDelegate = lazy {
        val decor = (context as? Activity)?.window?.decorView as? FrameLayout
        PhoneSheet(decor ?: this).apply {
            // **不给系统导航条留白**（与正式播放页一致）：面板贴到屏幕最底边。
            // 收起（点空白 / 点关闭 / 程序性收起）都要把「面板开着」这件事告诉宿主，
            // 宿主据此锁住横向 pager：面板开着时左右滑不该换体裁。
            onDismiss = {
                onSheetVisibilityChanged?.invoke(false)
                scheduleChromeHide()
            }
            // 播放设置里的滑块 / 选项卡也是「操作」：每按一下都把自动收起计时
            // 重新算起，用户拨滑块时不能把面板在他手指底下收走。
            onInteraction = { scheduleChromeHide() }
        }
    }
    private val sheet: PhoneSheet by sheetDelegate

    /** 收起播放设置（切 tab、借画面给播放页、重铺体裁时调用）。 */
    fun closeSheet() {
        if (sheetDelegate.isInitialized()) sheet.dismiss()
    }

    /**
     * 播放设置面板的开关状态：宿主（`PhoneRecommendPage`）用它锁横向翻页。
     *
     * 浮层本身挂在 decor 上，触摸已经不会落到 pager；这一层是兜底——某些机型 /
     * 从非 Activity 上下文构造时 decor 拿不到，浮层会退回挂在本页，这时必须显式锁住。
     */
    var onSheetVisibilityChanged: ((Boolean) -> Unit)? = null

    init {
        setBackgroundColor(PhoneColor.BG)
        list.layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
        list.adapter = adapter
        list.overScrollMode = View.OVER_SCROLL_NEVER
        /*
         * 关掉这一层列表的**嵌套滚动**。
         *
         * 推荐页外面是横向 pager，本列表只吃纵向位移，横向那一份会被
         * `dispatchNestedPreScroll` 交给 pager——那条分发不经过 `onInterceptTouchEvent`，
         * 于是 pager 的「锁住横滑」形同虚设：设置里关掉「推荐页左右滑动」后，
         * 手指在视频上横着一划照样整页翻走，用户读到的就是「关了没生效」。
         *
         * 关掉它不影响任何纵向行为：上下滑、翻页吸附、惯性都是列表自己算的；
         * 横向手势此后只走标准的触摸分发（父 pager 的 `onInterceptTouchEvent`），
         * 那条路上才有锁。
         */
        list.isNestedScrollingEnabled = false
        PhonePagerSnapHelper { list.height }.attachToRecyclerView(list)
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
                if (state != RecyclerView.SCROLL_STATE_IDLE) return
                val index = (rv.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
                if (index >= 0 && index != playingIndex) playIndex(index)
                if (index >= items.size - 3) loadMore()
            }
        })
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(hint, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = context.dp(64)
        })

        scope.launch {
            library = PhoneServices.awaitLibrary(context)
            userSpeed = library?.playbackSpeed ?: 1f
            scheduleChromeHide()
        }
        // 冷启动先铺落盘快照（电视端 `RecommendCache` 的同一条路）：
        // 列表与「上一次停在哪」都在盘上，首屏不必等取流那 1 秒，也不会每次都从同一部开始。
        restoreSnapshot()
        loadMore()
    }

    /**
     * 换体裁（漫剧 / 真人 / AI剧）。
     *
     * 与 TV 端 `recommendFeed(genres=…)` 是同一条路：`genres` 为空 = 三类混排，
     * 只留一类时服务端就只回这一类，换类等于换一条流，必须从头取（`session_id` 也要重置）。
     */
    fun setGenre(value: String?) {
        if (value == genre) return
        genre = value
        // 换体裁 = 换一条流：把上一类还在飞的 `playIndex` 作废（详情 / 取地址
        // 晚回来时不能再把媒体源挂上去，否则新一类的首屏会被旧内容顶掉）。
        playToken++
        chromeEpoch++
        list.removeCallbacks(chromeHideRunnable)
        recommendFailures = 0
        playingIndex = -1
        items.clear()
        offset = 0
        sessionId = ""
        ended = false
        loading = false
        // 换体裁 = 换一条流：游标与落盘快照都要换成那一类自己的
        //（三条流各存各的，见 `FeedCursor` 与 `RecommendCache.fileOf`）。
        cursorRestored = false
        filterMiss = 0
        adapter.notifyDataSetChanged()
        // 新的一类先铺它自己的快照：首屏不必等取流，也不会铺出上一类的卡片。
        restoreSnapshot()
        loadMore()
    }

    /**
     * 由宿主在切回首页时调用。
     *
     * **能接就接**：进程级播放器上如果还挂着这一条流（从正片退回来就是这种情形），
     * 只把 `PlayerView` 重新绑上去、接着播，不重新取地址、不重新 prepare、也不跳回旧位置。
     * 早先这里一律 `playIndex()`，用户看到的就是「每退一次都重新加载、从头开始」。
     */
    fun onShown() {
        resumeOnShown()
        // 设置页可能刚改过弹幕（显示方式 / 速度 / 字号 / 透明度）：回到这一页时重刷一次，
        // 否则要等下一次换集才看得到，用户读到的就是「弹幕设置不生效」。
        pageAt(playingIndex)?.let { applyDanmakuSettings(it) }
        // 这一路接完（集号 / 进度条都已经对齐）**之后**才把控制层还回来。
        // 顺序反了就是用户读到的「退回来内容断了一下」：控制层先亮，标题、集号、
        // 进度条还是离开前的那一份，过一拍才跳到正确的位置。
        PhoneSession.ensureFeedChrome()
        revealChrome()
    }

    /** 当前这一条流是否真的在播：宿主用它决定窗口要不要保持常亮。 */
    fun isPlayingNow(): Boolean {
        val exo = player ?: return false
        return !hidden && !paused && exo.playWhenReady &&
            exo.playbackState != Player.STATE_ENDED
    }

    private fun notifyPlayStateChanged() {
        onPlayStateChanged?.invoke()
    }

    private fun currentChromePage(): PhoneVideoPage? = pageAt(playingIndex) ?: boundPage

    /**
     * 当前这一屏的播放信息按设置里的秒数自动收起。
     *
     * 推荐页与正式播放页共用同一个 `playerPanelTimeout`。到点时先收设置面板，
     * 再收起标题、进度条和底部操作栏；下一次触摸只会把信息叫回来，不会顺手暂停。
     */
    private fun scheduleChromeHide(page: PhoneVideoPage? = currentChromePage()) {
        if (hidden || PhoneSession.underlayActive || page == null || !page.isChromeVisible()) {
            chromeEpoch++
            list.removeCallbacks(chromeHideRunnable)
            return
        }
        val seconds = library?.playerPanelTimeout ?: 6
        chromeEpoch++
        chromeHideAt = System.currentTimeMillis() + seconds * 1000L
        list.removeCallbacks(chromeHideRunnable)
        list.postDelayed(chromeHideRunnable, seconds * 1000L)
    }

    private fun chromeTimeoutMs(): Long = ((library?.playerPanelTimeout ?: 6) * 1000L)

    private fun revealChrome(page: PhoneVideoPage? = currentChromePage()) {
        if (hidden || PhoneSession.underlayActive) return
        page?.setChromeVisible(true)
        scheduleChromeHide(page)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!hidden && !PhoneSession.underlayActive) {
            val page = currentChromePage()
            if (page?.isChromeVisible() == true) {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> scheduleChromeHide(page)
                    MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                        chromeHideAt = System.currentTimeMillis() + chromeTimeoutMs()
                        list.removeCallbacks(chromeHideRunnable)
                        list.postDelayed(chromeHideRunnable, chromeTimeoutMs())
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** 双击推荐导航：当前这条流回到第一屏，并让列表的吸附与起播逻辑跟上。 */
    fun scrollToTop() {
        if (items.isEmpty()) return
        list.stopScroll()
        (list.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(0, 0)
            ?: list.scrollToPosition(0)
        list.post { if (playingIndex != 0) playIndex(0) }
    }

    private fun resumeOnShown() {
        // 之前确实被收起来过（离开去播放页 / 退到后台）。重复通知时不要动 Surface：
        // 重挂一次 `player` 会让 `PlayerView` 先清掉再交出去，白闪一下。
        val resuming = hidden
        hidden = false
        paused = false
        notifyPlayStateChanged()
        // 回到本页：不再需要把 Surface 借给播放页，注销那个入口。
        // **不要在这里恢复本页的控制层**：此刻播放页只是 onPause，窗口还在上层，
        // 恢复出来的标题 / 进度条会透过它的透明底板double一次。等播放页 onDestroy
        // 通过 `PhoneSession.restoreFeedChrome()` 统一还回来。
        PhoneSession.bindFeedSurface(null)
        PhoneSession.feedVideoOffset = null
        PhoneSession.feedVideoResize = null
        PhoneSession.feedVideoScale = null
        // 上一次交接可能停在翻页动画中间：那块视频的位移要清掉，否则回来是歪的。
        boundPage?.playerView?.translationY = 0f
        // 从正片回来：允许这一屏再次因片尾切走。
        advancing = false

        // 会话里的位置（播放页那边每秒刷新）：接手时按它对齐进度读数。
        // **只有会话属于本体裁流时才算数**：切 tab 时另一条流的那一部不能拿来对齐，
        // 否则本页的 `playingIndex` 会被改写成“另一条流正在播的那一集”。
        val sessionSameFeed = sessionBelongsToThisFeed()
        if (com.hgtv.star.shared.BuildConfig.DEBUG && !sessionSameFeed &&
            PhoneSession.feedKey.isNotEmpty() && PhoneSession.seriesId.isNotEmpty()
        ) {
            android.util.Log.i(
                "HGTV-PHONE",
                "feed switch genre=${cacheKey()} sessionFeed=${PhoneSession.feedKey} " +
                    "sessionSeries=${PhoneSession.seriesId} keepIndex=$playingIndex"
            )
        }
        if (playingIndex < 0) {
            // 本视图是新建的（Activity 重建 / 体裁重铺），但进程里可能还在放这一部：
            // 先找它，找不到才从第一条起播。
            val resumed = adoptableIndexOf()
            if (resumed >= 0) {
                list.scrollToPosition(resumed)
                playIndex(resumed)
            } else {
                stopOrphanPlayback()
                if (items.isNotEmpty()) playIndex(0)
            }
            return
        }
        // 列表可能被重铺过（换体裁、快照恢复、Activity 重建）：本页记的下标这时指向的
        // 已经不是会话里那一部了。先按会话对齐下标，否则下面的「接手」会拿错卡片去比，
        // 判成接不上，接着重新取详情、从 0 起播——用户读到的就是「退回来内容断了」。
        // 切 tab 时 `sessionSameFeed` 为 false，这一段整个跳过：本页保持自己的下标。
        val sessionIndex = if (sessionSameFeed) adoptableIndexOf() else -1
        if (sessionIndex >= 0 && sessionIndex != playingIndex) {
            playingIndex = sessionIndex
            boundPage?.playerView?.translationY = 0f
            list.scrollToPosition(sessionIndex)
        }
        if (adoptPlaying(forceRebind = resuming)) {
            // 接上了：交接窗口到此为止，之后真的退到后台仍然要暂停。
            PhoneSession.endHandoff()
            return
        }
        // 接不上（播放器已经 IDLE / 视图被重建过）但**会话里还是这部剧的这一集**：
        // 原地换一条地址接着放，不要退回 `playIndex`——那一条会重新取详情、重新读本机进度，
        // 用户看到的是「退回来从头开始」。
        if (reopenAtCurrentPosition(playingIndex)) {
            PhoneSession.endHandoff()
            return
        }
        stopOrphanPlayback()
        playIndex(playingIndex)
    }

    /**
     * 播放页已经不在了（此刻前台是本页），它留下的那一路却接不上（地址过期、
     * 或者它放的那一部根本不在当前这条流里）。
     *
     * 这时必须**先按停**：不按的话，画面会切到本页这一条，声音还是播放页那一条，
     * 用户听到的就是「退回推荐页后声音和画面对不上」。接得上时不会走到这里。
     */
    private fun stopOrphanPlayback() {
        if (PhoneSession.owner != PhoneSession.Owner.PLAYER) return
        runCatching { PhoneServices.peekPlayer()?.pause() }
        PhoneSession.takeOver(PhoneSession.Owner.FEED)
        PhoneSession.endHandoff()
    }

    /**
     * 把进程级播放器**接回来**：媒体源没变，只是 Surface 换了。
     *
     * 返回 true 表示接上了（画面马上继续），false 表示需要重新取地址。
     *
     * 三种情况都走这一条：
     * * 从正片退回推荐（`owner == PLAYER`，放的是同一部剧）；
     * * 回到首页但播放器还在放这一屏（`owner == FEED`）；
     * * 播放页里换过集（集号以 [PhoneSession.index] 为准）。
     */
    private fun adoptPlaying(forceRebind: Boolean = false): Boolean {
        // 会话属于另一条体裁流：这一路不是本页的，不能接。
        if (!sessionBelongsToThisFeed()) return false
        val series = items.getOrNull(playingIndex) ?: return false
        // 只看「这一路还活着」，不看地址新不新鲜：切页只换了 Surface，媒体源与解码器一直在跑，
        // 接上去就能出帧。地址过期由 [reopenAtCurrentPosition] 在接上之后单独处理。
        if (!PhoneSession.live(series.id)) return false
        // 播放器可能不在本视图的字段上：本视图刚被重建（转屏、体裁重铺、Activity 重建），
        // 但进程里那一台还在放。这时照样能接——这正是「退回来内容不断」要覆盖的场景。
        val exo = player ?: PhoneServices.peekPlayer() ?: return false
        if (com.hgtv.star.shared.BuildConfig.DEBUG) {
            // 接手前后的读数：位置有没有被谁清零 / 播放列表有没有被谁改过，
            // 这行是最直接的证据（用户报「切一次就从头」时先看它）。
            android.util.Log.i(
                "HGTV-PHONE",
                "adopt begin pos=${runCatching { exo.currentPosition }.getOrDefault(-1)} " +
                    "state=${exo.playbackState} pwr=${exo.playWhenReady} " +
                    "items=${exo.mediaItemCount} cur=${exo.currentMediaItemIndex} " +
                    "underlay=${PhoneSession.underlayActive} sessionPos=${PhoneSession.positionMs}"
            )
        }
        val d = PhoneSession.detail ?: return false
        // IDLE 不等于接不上：`swapMediaSource` 是「先 stop 再挂源」，挂完到 prepare 之间
        // 播放器就是 IDLE（播放页原地换地址、片尾交接都落在这个窗口里）。这时播放器上
        // 挂着的**就是这一集**，接过来补一次 prepare 即可；判成接不上就会重新取地址、
        // 重新 prepare，用户读到的又是一次「退回来内容断了」。
        var idleNeedsPrepare = false
        if (exo.playbackState == Player.STATE_IDLE) {
            val wantedId = d.episodes.getOrNull(PhoneSession.index.takeIf { it >= 0 } ?: 0).orEmpty()
            val onPlayer = runCatching { exo.currentMediaItem?.mediaId }.getOrNull().orEmpty()
            if (wantedId.isEmpty() || onPlayer != wantedId) return false
            idleNeedsPrepare = true
        }
        player = exo

        val episodeIndex = PhoneSession.index.takeIf { it >= 0 } ?: 0
        // 集号 / 标题按会话里那一集重新刷一遍（播放页里可能已经换过集）。
        currentEpisodeIndex[playingIndex] = episodeIndex
        currentEpisodeIds[playingIndex] = d.episodes.getOrNull(episodeIndex).orEmpty()
        playingDetail = d

        // 播放列表里可能还挂着「下一集」（播放页预热好的那一项）。
        // 信息流的语义是「一集播完 → 进正式播放页」，留着它 ExoPlayer 会自己无感推进，
        // 于是信息流里悄悄换了集、集号却没变。这里只保留当前这一项。
        // 只摘「当前项之后」的那些：如果已经推进到第 2 项（片尾刚切过去），
        // 摘掉它等于把正在放的那一项删了，播放器会直接跳到结尾。
        runCatching {
            // `currentMediaItemIndex` 在「源刚挂上、还没 prepare」时可能是 -1：不夹一下，
            // `removeMediaItems(0, count)` 会把正在放的那一项也一起摘掉，播放器直接跳到结尾。
            val current = exo.currentMediaItemIndex.coerceAtLeast(0)
            if (exo.mediaItemCount > current + 1) {
                exo.removeMediaItems(current + 1, exo.mediaItemCount)
            }
        }
        // 列表里的下一项已经摘掉了，「排好了」这个标志必须跟着清：留着它的话，
        // 本页片尾那一下会被当成「播放器会自己推进」而什么都不做，用户看到的就是「放完停住」。
        PhoneSession.nextQueued = false
        // 那一项已经从播放列表里摘掉，它捕获的 `RemoteVideo` 也就没人用了：一起关掉。
        // **但片尾预热的那一份要留着**：它不属于播放列表，是给「片尾自动进正片」接手用的。
        if (PhoneSession.nextEpisodeId.isEmpty()) PhoneSession.clearNext()

        // 页面可能刚好不在可见区（退回来时列表还在恢复布局 / Activity 刚重建）：
        // 这时**照样接管**，Surface 交给 [rebindCurrentPage] 在页面绑上来之后挂——
        // 早先这里直接返回 false，于是又走一遍取详情、取地址、prepare，用户看到的就是「重新加载」。
        val page = pageAt(playingIndex)
            ?: boundPage?.takeIf { list.getChildAdapterPosition(it) == playingIndex }
        if (page != null) attachPage(exo, page, series, episodeIndex, forceRebind)

        // 重新挂监听：`onHidden` 里摘掉了，摘掉是为了不让隐藏期间的回调改界面。
        attachListener(exo, playingIndex)
        // 倍速以**书库设置**为准：用户可能刚在播放页里把 1.75 调成了 2（那一侧会落盘），
        // 本页字段还是进来时的旧值，直接写回去就是一次「速度突变」。
        userSpeed = library?.playbackSpeed ?: userSpeed
        exo.setPlaybackSpeed(userSpeed)
        // 会话已经被信息流接手：播放页的 onDestroy 因此在收尾时不再 pause（见 detachForHandoff）。
        PhoneSession.takeOver(PhoneSession.Owner.FEED)
        // 用户是在播放页里按的暂停就保持暂停，别替他恢复播放。
        exo.playWhenReady = PhoneSession.resumeOnAdopt
        // 暂停状态跟着走：不然片尾判定（`paused` 为 false）会在暂停的画面上误触发换集。
        paused = !exo.playWhenReady
        // 进度条立刻对齐到接手的那一秒，不要等 500ms 后的第一个 tick。
        val dur = exo.duration
        if (page != null && dur > 0) {
            page.setProgress(exo.currentPosition.toFloat() / dur)
            page.setBufferedProgress(exo.bufferedPosition.toFloat() / dur)
        }
        if (page != null) {
            syncDanmakuClock(page, playingIndex, exo.currentPosition, exo.isPlaying, exo.playbackParameters.speed)
        }
        // 画面已经回到本页（无论刚才是不是借出去的）：之后的重挂都按常规走。
        PhoneSession.underlayActive = false
        // 源挂上了但还没 prepare 的那一种：在这里补上，并把位置对回会话记下的那一秒。
        // 换源时 `PlayerView` 保留了上一帧（`setKeepContentOnPlayerReset`），画面不会黑一下。
        if (idleNeedsPrepare) {
            exo.prepare()
            val at = PhoneSession.positionMs
            if (at > 0) exo.seekTo(at)
        }
        startProgressTicker()
        // 地址已经过了窗口期：这一路还能继续出帧，但下一个 Range 请求会 403。
        // 原地换一条地址，位置与 Surface 都不动（见 [reopenAtCurrentPosition]）。
        if (PhoneSession.urlExpired()) reopenAtCurrentPosition(playingIndex)
        // 接上之后兜一次「在走」：Surface 换宿主之后偶尔会停在 READY + playWhenReady 却不推进。
        // 只补一次播放状态，不重设媒体源、不 seek。
        list.postDelayed({ keepLivePlayback() }, 1_500L)
        android.util.Log.i(
            "HGTV-PHONE",
            "feed adopt series=${series.id} episode=${episodeIndex + 1} pos=${exo.currentPosition} page=${page != null}"
        )
        return true
    }

    /**
     * 地址过期后**原地重开**这一集：不换页、不换 Surface、不回片头。
     *
     * `PhoneSession` 里那条签名地址只有 10 分钟窗口（与 TV 的 `PlaybackCache` 同值）。
     * 用户在播放页里停久了再退回来时，旧地址已经发不出新的 Range 请求，
     * 但画面、解码器、播放位置全都还在——这时唯一要换的是地址本身。
     * 早先这里走的是 `playIndex`：重新取详情、重新读本机进度、`releasePlayer()` 关掉地址，
     * 用户读到的就是「退回来又从头放了一遍」。
     *
     * 返回 true 表示已经在换了（异步），调用方不要再走重播那条路。
     */
    private fun reopenAtCurrentPosition(index: Int): Boolean {
        if (!sessionBelongsToThisFeed()) return false
        if (index !in items.indices) return false
        val series = items[index]
        if (!PhoneSession.live(series.id)) return false
        val d = PhoneSession.detail ?: return false
        val episodeIndex = PhoneSession.index.takeIf { it in d.episodes.indices } ?: return false
        val episodeId = d.episodes.getOrNull(episodeIndex) ?: return false
        val exo = player ?: PhoneServices.peekPlayer() ?: return false
        // 位置优先问播放器（最准），拿不到才用会话里每秒刷新的那个读数。
        val at = runCatching { exo.currentPosition }.getOrDefault(0L)
            .takeIf { it > 0 } ?: PhoneSession.positionMs
        // 换地址不改播放 / 暂停状态：暂停着退回来，换完还是暂停。
        val wantPlay = if (PhoneSession.inHandoff()) PhoneSession.resumeOnAdopt else exo.playWhenReady
        player = exo
        playingIndex = index
        playingDetail = d
        currentEpisodeIndex[index] = episodeIndex
        currentEpisodeIds[index] = episodeId
        val token = ++playToken
        val repo = PhoneServices.repository(context)
        scope.launch {
            val lib = library ?: PhoneServices.awaitLibrary(context).also { library = it }
            val maxQuality = lib.maxQuality
            val prepared = withContext(Dispatchers.IO) {
                runCatching {
                    val info = repo.stream(episodeId, maxQuality, d.playback)
                    RemoteVideo(PlaybackTransport.client, info, info.mediaKey(episodeId)).also { it.prepare() }
                }.getOrNull()
            }
            if (prepared == null) {
                // 换地址失败（断网 / 接口抖动）：退回常规起播，别把用户停在一张死画面上。
                if (token == playToken) playIndex(index)
                return@launch
            }
            if (token != playToken || !isAttachedToWindow) { prepared.close(); return@launch }
            // **不碰 `PlayerView`**：Surface 还是那一块，画面不会黑一下。
            val factory = DataSource.Factory { VideoDataSource(prepared) }
            exo.swapMediaSource(episodeId, prepared.info.url, factory)
            remote = prepared
            PhoneSession.publish(
                d, episodeIndex, prepared, prepared.info.quality,
                PhoneSession.Owner.FEED, cacheKey(),
            )
            exo.setPlaybackSpeed(userSpeed)
            exo.prepare()
            if (at > 0) exo.seekTo(at)
            exo.playWhenReady = wantPlay
            android.util.Log.i(
                "HGTV-PHONE",
                "feed reopen in place episode=${episodeIndex + 1} at=$at"
            )
        }
        return true
    }

    /**
     * 接上之后兜一次「真的在走」。
     *
     * 只处理一种情况：播放器 READY、`playWhenReady` 为 true、却没有在播——
     * 这是 Surface 换宿主后厂商 ROM 上的已知表现（解码器已经就绪，播放状态没被推起来）。
     * 这里补一次 `play()`；如果本页的 Surface 已经不是输出端了，再挂回去。
     * **不重设媒体源、不 seek**：补的是播放状态，不是内容。
     */
    private fun keepLivePlayback() {
        if (hidden) return
        if (PhoneSession.owner != PhoneSession.Owner.FEED) return
        if (!sessionBelongsToThisFeed()) return
        val exo = player ?: return
        if (exo.playbackState == Player.STATE_READY && exo.playWhenReady && !exo.isPlaying) {
            runCatching { exo.play() }
        }
        if (PhoneSession.underlayActive) return
        val page = pageAt(playingIndex) ?: return
        if (page.playerView.player !== exo) page.playerView.player = exo
    }

    /**
     * 把播放器挂到这一页上（Surface + 弹幕 + 集号）。
     *
     * @param forceRebind 从别的界面退回来时必须**强制重挂**：本页的 Surface 已经被系统
     *   销毁又重建过，而 `PlayerView` 里那个 `player` 字段还指着同一台播放器，
     *   它会认为「已经绑好了」、永远不再把新 Surface 交出去，画面就一直黑着。
     */
    private fun attachPage(
        exo: ExoPlayer,
        page: PhoneVideoPage,
        series: Series,
        episodeIndex: Int,
        forceRebind: Boolean,
    ) {
        page.bind(series, "第${episodeIndex + 1}集")
        // `bind` 会把画面模式重置成「按卡片 vertical 猜」：从播放页退回来时，
        // 用户在设置里选的等比铺满 / 缩放会被悄悄改回默认，读起来就是「回来画面变了一下」。
        applyPageVideoSettings(page)
        page.updateVideoFrame(
            exo.videoSize.width.takeIf { it > 0 } ?: 1,
            exo.videoSize.height.takeIf { it > 0 } ?: 1,
        )
        boundPage?.let { if (it !== page) it.playerView.player = null }
        if (forceRebind) {
            // 先置空再挂：置空只会清掉「本 View 持有的那一张」（播放页那张不受影响），
            // 紧接着挂回去，两次都在同一帧里完成，不会多一次闪黑。
            //
            // 但如果**这一块 Surface 从来就没被解绑过**（播放页竖屏是借它出画面的，
            // 见 `PhoneVideoPage.underlay`），就不要再置空：那一下会真的把它摘掉、
            // 再挂回来又是一次空帧，正好是「退回来闪一下」。
            // 只有「输出仍挂在推荐页 Surface 上」时才跳过：播放页竖屏借画面
            // （`underlayActive`）时这一块从来没被摘过，重挂会先清一次屏。
            // 其余情况（横屏播放页、详情页进来）输出已被别人拿走，必须重挂。
            if (PhoneSession.underlayActive && page.playerView.player === exo) {
                // 什么都不做：Surface 一直在，画面就是原来那一帧。
            } else {
                if (page.playerView.player === exo) page.playerView.player = null
                page.playerView.player = exo
            }
        } else if (page.playerView.player !== exo) {
            page.playerView.player = exo
        }
        boundPage = page
        applyDanmakuSettings(page)
        loadDanmaku(page, playingIndex, currentEpisodeIds[playingIndex].orEmpty(), series.id)
        page.setChromeVisible(true)
        scheduleChromeHide(page)
    }

    /**
     * 把「画面模式」「画面缩放」按设置落到这一页上。
     *
     * 与播放页同一条规则：按用户选的 FIT / ZOOM / FILL 走，不再因为片源是竖屏
     * 就强制铺满。`bind` 之后必须补一次，否则会回到默认的完整画面。
     */
    private fun applyPageVideoSettings(page: PhoneVideoPage) {
        val lib = library
        page.setResizeMode(
            (lib?.frameMode ?: VideoFrameMode.FIT).toFit().resizeMode()
        )
        page.setVideoScale(((lib?.videoScale ?: 100f) / 100f).coerceIn(.1f, 1f))
    }

    /**
     * 由宿主在切走时调用：解绑 Surface、摘监听、停掉本页的计时器。
     *
     * 关键：**不关 `remote`、不清媒体源**。播放器是进程单例，媒体源就是「这一集正在播」
     * 这个事实本身；关掉它，播放页接手时就只能重新取地址、重新 prepare——
     * 那正是「切页就重新加载」的根源。谁换了媒体源，谁负责关旧的
     * （见 [PhoneSession.publish]）。
     *
     * @param handoff true = 这一路要交给播放页（「观看完整短剧」「全屏」「片尾进正片」）。
     *
     * 交接时**不暂停**：两个 Activity 的 `onPause` / `onResume` 之间有几十毫秒，
     * 在这里暂停就等于让画面在窗口切换的那一瞬停住、新窗口再重新起播缓冲一次——
     * 用户读到的就是「切过去内容断了一下」。不暂停的话，播放器一直在跑，
     * 新窗口只是把 Surface 接过去，第一帧立刻有。
     */
    fun onHidden(handoff: Boolean = false) {
        // 浮层挂在 Activity 的 decor 上，不随本页 `removeAllViews` 消失：切 tab / 进播放页
        // 之前必须显式收掉，否则它会一直压在别的页面上。
        closeSheet()
        val already = hidden
        hidden = true
        paused = true
        chromeEpoch++
        list.removeCallbacks(chromeHideRunnable)
        // 切 tab / 退到后台时作废本页还在飞的 `playIndex`：详情或取地址晚回来时
        // 不能再 `attach`——那一手会把另一条流刚起播的媒体源顶掉，用户看到的就是
        // “切了 tab，画面又跳回上一个 tab 的那部剧”。
        // 交接给播放页（`handoff` / 交接窗口内）**不能**作废：那一路正是播放页要接的。
        if (!handoff && !PhoneSession.inHandoff()) playToken++
        if (handoff) PhoneSession.beginHandoff()
        // 记下「离开时是不是在播」：从后台回来 / 从正片退回来时按它决定要不要自动续播。
        // 用户在信息流里主动暂停过，回来时就不该自己动起来。
        // 交接时**不改**这个值：它已经是调用方（[openPlayer] 等）按意图写好的。
        if (!already && !handoff) PhoneSession.resumeOnAdopt = player?.playWhenReady == true
        listener?.let { player?.removeListener(it) }
        listener = null
        // 交接窗口内也不暂停：同一个切页动作会被通知两次（自己一次、宿主 `onPause` 一次），
        // 第二次进来时 `handoff` 是 false，但这一路已经在往播放页交了，不能按停。
        // 判据再加一条 `owner`：交接窗口只有 3 秒，用户在播放页里看久了再让本页收起来
        //（转横屏、系统回收视图）时窗口早已过期，只看窗口就会把播放页正在放的那一路按停。
        if (!handoff && !PhoneSession.inHandoff() &&
            PhoneSession.owner == PhoneSession.Owner.FEED &&
            sessionBelongsToThisFeed()
        ) {
            player?.pause()
        }
        // 把「借 Surface」的入口登记下来：正式播放页竖屏时就用这一块出画面，
        // 从横屏退回竖屏也靠它把输出交回来。只登记一次（重复通知会走到这里）。
        //
        // 反过来，**因为切 tab / 退到后台而收起来**时必须注销：那一页已经不在屏幕上了，
        // 再让播放页借它出画面就是一块黑（那一块 Surface 也只是被系统挂起）。
        // 交接窗口内不注销：宿主 `onPause` 会以 `handoff=false` 再通知一次。
        if (handoff && !already) bindFeedSurfaceForHandoff()
        else if (!handoff && !PhoneSession.inHandoff() &&
            PhoneSession.owner == PhoneSession.Owner.FEED &&
            sessionBelongsToThisFeed()
        ) {
            PhoneSession.bindFeedSurface(null)
            PhoneSession.feedVideoOffset = null
            PhoneSession.feedVideoResize = null
            PhoneSession.feedVideoScale = null
            PhoneSession.underlayActive = false
        }
        // ticker 要停：它读的是**进程级播放器**的位置，
        // 而这时播放器可能已经交给播放页在放另一集了——继续跑就会把别的集的位置
        // 记到这一屏的集号上。
        progressTick?.let { list.removeCallbacks(it) }
        progressTick = null
        // 预热与停留确认也停掉：隐藏期间发出去的预热会跟播放页抢带宽，
        // 而停留确认本来就是「这一屏还在前台停着」才成立的事。
        preloadScheduler.cancel()
        pendingPlan = null
        // 交接时**不解绑 Surface**：SurfaceView 的 surface 在窗口切走时会被系统销毁、
        // 切回来时重建，播放器自己会跟上；这里解绑反而要在回来时重新 `setPlayer`，
        // 那一下会先清屏再挂新的，用户看到的是一次闪黑。
        //
        // 判据不能只看方法参数：同一个交接动作会被通知两次——`launchPlayer` 一次
        //（handoff=true）、宿主 `onPause` 再一次（handoff=false）。第二次进来时
        // `handoff` 是 false，但这一路正在往播放页交，照旧不能解绑。早先漏了这一条，
        // 于是「交出 Surface → 播放页再挂一块新的」中间必然空一帧，就是闪黑。
        val handingOff = handoff || PhoneSession.inHandoff() ||
            PhoneSession.owner != PhoneSession.Owner.FEED
        if (!handingOff) PhoneServices.detach(boundPage?.playerView)
        // boundPage 保留：回来时要把它重新绑上去。
        hint.visibility = View.GONE
        notifyPlayStateChanged()
    }

    /**
     * 把当前这一页的视频 Surface 借给正式播放页（竖屏），或者收回来。
     *
     * 借出去时本页只留画面：信息栏、弹幕、渐变遮罩全部收起——播放页会画一份
     * 一模一样（但集号更准）的控制层，两层叠着会看到两套标题与两条进度条。
     */
    fun setUnderlayMode(on: Boolean) {
        if (on) {
            closeSheet()
            chromeEpoch++
            list.removeCallbacks(chromeHideRunnable)
        }
        // `pageAt` 在列表刚恢复布局 / 页面正在回收时可能取不到；此时用最后绑定的那一页。
        val page = pageAt(playingIndex) ?: boundPage
        page?.setUnderlayMode(on)
        if (!on) {
            page?.playerView?.translationY = 0f
            revealChrome(page)
        }
        // 收回画面时按用户的设置重刷弹幕显隐（关了弹幕的不要被顺手打开）。
        if (!on && page != null) applyDanmakuSettings(page)
        if (on) hint.visibility = View.GONE
    }

    /**
     * 登记「把播放器重新挂回本页 Surface」的入口。
     *
     * 播放页竖屏不挂自己的视频层（见 `PhoneVideoPage` 的 `underlay`），从横屏退回
     * 竖屏时它把横屏那一层摘掉，然后调这里把输出交回来——不交就是一块黑。
     */
    private fun bindFeedSurfaceForHandoff() {
        PhoneSession.bindFeedSurface { force ->
            val index = playingIndex
            val page = boundPage ?: pageAt(index) ?: return@bindFeedSurface false
            val series = items.getOrNull(index) ?: return@bindFeedSurface false
            val exo = player ?: PhoneServices.peekPlayer() ?: return@bindFeedSurface false
            player = exo
            // `force` 时才先置空：`PlayerView` 里那个 `player` 字段还指着同一台播放器，
            // 不置空它就不会把新的 Surface 交出去（与 `attachPage` 的 forceRebind 同理）。
            // 进场那一次输出本来就在这块 Surface 上，置空等于原地闪一下。
            if (force && page.playerView.player === exo) page.playerView.player = null
            if (page.playerView.player !== exo) page.playerView.player = exo
            page.bind(series, "第${(currentEpisodeIndex[index] ?: 0) + 1}集")
            boundPage = page
            true
        }
        PhoneSession.feedVideoOffset = { dy ->
            val page = boundPage ?: pageAt(playingIndex)
            page?.playerView?.translationY = dy
        }
        // 播放页里改「画面模式」「画面缩放」时，真正出画面的是这一块：
        // 不转过来就会变成「设置里选了等比铺满，画面还是原样」。
        PhoneSession.feedVideoResize = { mode ->
            (boundPage ?: pageAt(playingIndex))?.playerView?.resizeMode = mode
        }
        PhoneSession.feedVideoScale = { ratio ->
            (boundPage ?: pageAt(playingIndex))?.setVideoScale(ratio)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0 || items.isEmpty()) return
        // **不重建整屏**。一页就是一个屏幕高，换成 `MATCH_PARENT` 之后尺寸变化由
        // RecyclerView 自己重新测量，ViewHolder 与它上面那块 Surface 都不动——
        // 这一条正是「进 / 退播放页画面不断」的前提：
        // 播放页横屏时本页会跟着转一次屏（onSizeChanged），早先这里 `notifyDataSetChanged()`
        // 会把整屏（连同正借给播放页出画面的那块 Surface）全部回收重建，
        // 播放页手里的 Surface 就此没了，用户看到的是「一切横屏就黑一下」。
        //
        // 尺寸变了之后只要**重新对齐**当前这一页：像素滚动位置在换高度后不再是一页的整数倍，
        // 不对齐就会停在半页上。
        val index = playingIndex.coerceAtLeast(0)
        list.post { runCatching { list.scrollToPosition(index) } }
    }

    /**
     * 把进程级播放器重新挂到「现在应该显示这一集」的那张页上。
     *
     * 只在会话仍属于信息流时做：属于播放页时播放器归播放页，抢过来会把那边的画面弄黑。
     * 布局变化（转屏、键盘、分屏）与列表重新绑定之后都要调一次。
     */
    private fun rebindCurrentPage() {
        if (PhoneSession.owner != PhoneSession.Owner.FEED) return
        // 另一条体裁流正在播时，本页即使被重新布局也不能把播放器抢回来。
        if (hidden || !sessionBelongsToThisFeed()) return
        val exo = player ?: return
        val index = playingIndex
        if (index < 0 || index !in items.indices) return
        val page = pageAt(index) ?: return
        val series = items[index]
        if (page.playerView.player === exo && boundPage === page) return
        attachPage(exo, page, series, currentEpisodeIndex[index] ?: 0, forceRebind = false)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        chromeEpoch++
        list.removeCallbacks(chromeHideRunnable)
        // 浮层挂在 Activity 的 decor 上，不随本页摘下：这里兜底收一次，
        // 免得页面被回收时它留在窗口上（宿主切 tab 时已经由 `onHidden` 收过）。
        closeSheet()
        PhoneServices.artwork(context).cancelPage()
        // 播放页压在上面时**不要注销**这几个入口：转横屏会让本页的视图被摘下
        //（Activity 只是 stopped，没销毁），播放页从横屏退回竖屏正是靠这里把输出
        // 交回本页的 Surface。注销早了它会判「下层不可用」→ 自己挂一层视频 →
        // 换输出端那一帧就是用户看到的闪黑。
        // 真正离开（切 tab / 宿主销毁）由 `onHidden` 与 `PhoneMainActivity.onDestroy` 注销。
        val ownedByPlayer = PhoneSession.owner == PhoneSession.Owner.PLAYER ||
            PhoneSession.inHandoff()
        // 只有**会话归本页**时才注销 Surface 入口：pager 回收旧页时，
        // 全局 `feedSurface` 可能刚被新起播的那一条流登记上，注销它就是一块黑屏。
        if (!ownedByPlayer && sessionBelongsToThisFeed()) {
            PhoneSession.bindFeedSurface(null)
            PhoneSession.feedVideoOffset = null
            PhoneSession.feedVideoResize = null
            PhoneSession.feedVideoScale = null
            PhoneSession.underlayActive = false
        }
        // **不要在这里 pause**：这条流可能在「交给播放页」的交接窗口里被系统先摘下来
        //（Activity 停止时视图会 detach），此时暂停就等于把播放页要接的那一路按停。
        // 真正的「退到后台要停」由 `PhoneMainActivity.onPause → onHidden()` 负责。
        // 这一路归播放页时更不能停：窗口过期后转横屏，本页视图会被摘下，
        // 那时按一下就把用户正在看的画面停住了。
        if (!PhoneSession.inHandoff() && PhoneSession.owner == PhoneSession.Owner.FEED &&
            sessionBelongsToThisFeed()
        ) {
            player?.pause()
        }
    }

    // ------------------------------------------------------------------ 数据

    /**
     * 冷启动先铺落盘快照：列表 + 「上一次停在哪」。
     *
     * 与电视端 `MainActivityRecommend` 的恢复逻辑同一套语义，只是简化成手机上够用的那部分：
     * 快照里的条目直接上屏、从**上一次停的那一部的下一部**接着播（不是永远第 1 条），
     * 之后由 [loadMore] 在后台把新的一页补进来。快照过期 / 没有就什么都不做。
     */
    private fun restoreSnapshot() {
        if (items.isNotEmpty()) return
        val snapshot = runCatching { com.hgtv.star.core.RecommendCache.load(cacheKey()) }
            .getOrDefault(emptyList())
        if (snapshot.isEmpty()) return
        val hiddenIds = runCatching { PhoneServices.library(context).hidden() }.getOrDefault(emptySet())
        val usable = snapshot.filterNot { it.id in hiddenIds }
        if (usable.isEmpty()) return
        items += usable
        adapter.notifyDataSetChanged()
        // 起点 = 上一次停的那一部的下一条（到末尾就回到第一条）。
        val last = runCatching { com.hgtv.star.core.RecommendCache.lastPlayed(cacheKey()) }
            .getOrDefault("")
        val lastIndex = usable.indexOfFirst { it.id == last }
        val start = if (lastIndex >= 0) (lastIndex + 1) % usable.size else 0
        playingIndex = start
        // 还没上屏的那一条只摆好列表与下标，不出声音：真正起播由 `onShown()` 负责。
        if (!hidden) playIndex(start)
        // 滚到这一屏要等布局完成（此刻高度可能还是 0）。
        list.post { list.scrollToPosition(start) }
        if (com.hgtv.star.shared.BuildConfig.DEBUG) {
            android.util.Log.i("HGTV-PHONE", "feed restore items=${usable.size} start=$start last=$last")
        }
    }

    /**
     * 取下一页推荐。
     *
     * 规则与电视端 `loadRecommendPage` **同源**（那是踩出来的，手机端不该重新踩一遍）：
     *
     * * **续流游标落盘**（按体裁分开，见 [com.hgtv.star.core.FeedCursor]）：接口是有状态的，
     *   `session_id` 传空 + `offset=0` 永远回同一套默认列表，于是「每次打开都是这几部」；
     * * **回传 `filter_ids`**（本机已推过的剧）让服务端去重；服务端不吃这个字段（回空页）时
     *   立刻不带重取一次，连续两次就永久关掉——它是锦上添花的一层，不能因此让整屏空着；
     * * **已看过的、追完的、隐藏的、同名的**全部滤掉，一页全被滤光就接着取下一页（最多 3 页）；
     * * 游标不前进（服务端把 `next_offset` 钳住）或首屏空页：退回流头重取一次。
     */
    private fun loadMore() {
        if (loading || ended) return
        loading = true
        scope.launch {
            val lib = library ?: PhoneServices.awaitLibrary(context).also { library = it }
            // 续流：从落盘恢复这一类自己的游标（电视端 `recommendCursor` 的同一条思路）。
            if (!cursorRestored) {
                cursorRestored = true
                filterIdsOff = runCatching { lib.recommendFilterIdsOff }.getOrDefault(false)
                val saved = runCatching {
                    com.hgtv.star.core.FeedCursor.of(lib.phoneFeedCursor, cacheKey())
                }.getOrNull()
                if (saved != null && items.isEmpty()) {
                    sessionId = saved.session
                    offset = saved.offset
                }
            }
            val repo = PhoneServices.repository(context)
            val genres = genre?.let { listOf(it) } ?: emptyList()
            var added = 0
            var pages = 0
            var restarts = 0
            // 一页全被滤光时不能停：接着取下一页，否则首屏会停在「暂无内容」。
            while (pages < 3) {
                pages++
                val prevOffset = offset
                val filterIds = if (filterIdsOff) "" else lib.recommendFilterIds()
                // 受众（男频 / 女频）：与电视端同一个落盘值（设置页「推荐受众」）。
                // 早先这里一律 `CategorySelection()`，用户在手机上选过受众也不生效——
                // 与电视端「设置项真的会改变取到的内容」不一致。
                val gender = lib.recommendGender.ifEmpty { null }
                    .takeIf { !genderFilter.off && !genderProbe }
                genderProbe = false
                var feed = withContext(Dispatchers.IO) {
                    runCatching {
                        repo.recommendFeed(
                            CategorySelection(gender = gender),
                            sessionId, offset, genres = genres, filterIds = filterIds,
                        )
                    }.getOrNull()
                }
                // 带了 `filter_ids` 却一条都没回来：本机服务端不吃这个字段（或把整页滤光了）。
                if (feed != null && filterIds.isNotEmpty() && feed.page.items.isEmpty()) {
                    // 两次即关：阈值在 `:core`（`RecommendFlow.noteFilterMiss`），与电视端同一份。
                    val miss = com.hgtv.star.core.RecommendFlow.noteFilterMiss(filterMiss)
                    filterMiss = miss.first
                    if (miss.second) {
                        filterIdsOff = true
                        runCatching { lib.recommendFilterIdsOff = true }
                    }
                    feed = withContext(Dispatchers.IO) {
                        runCatching {
                            repo.recommendFeed(
                                CategorySelection(gender = gender),
                                sessionId, offset, genres = genres,
                            )
                        }.getOrNull()
                    }
                } else if (feed != null && filterIds.isNotEmpty()) {
                    filterMiss = 0
                }
                // 带了受众却回空页：这一次不带受众重取一次；连续两次仍空就永久关掉这一层
                // （与电视端同一套语义，`genderFilter` 是 `:core` 的 `OptionalFilter`）。
                if (feed != null && feed.page.items.isEmpty() && gender != null && !genderFilter.off) {
                    genderFilter.onEmpty()
                    genderProbe = true
                    if (genderFilter.off) genderProbe = false
                    if (com.hgtv.star.shared.BuildConfig.DEBUG) {
                        android.util.Log.i(
                            "HGTV-PHONE",
                            "feed empty with gender=$gender miss=${genderFilter.misses} off=${genderFilter.off}"
                        )
                    }
                    feed = withContext(Dispatchers.IO) {
                        runCatching {
                            repo.recommendFeed(
                                CategorySelection(),
                                sessionId, offset, genres = genres,
                                filterIds = if (filterIdsOff) "" else lib.recommendFilterIds(),
                            )
                        }.getOrNull()
                    }
                } else if (feed != null && gender != null && feed.page.items.isNotEmpty()) {
                    genderFilter.onFilled()
                }
                if (feed == null) break

                // 换了推荐范围之后落盘的位置可能已经越过池子尾：首屏空页就退回流头重取一次。
                if (pages == 1 && feed.page.items.isEmpty() && prevOffset > 0 && restarts < 2) {
                    restarts++
                    sessionId = ""
                    offset = 0
                    persistCursor(lib)
                    continue
                }
                // 游标没往前走（同一页上打转）：只有换一条新流才有新东西。
                // 判据在 `:core`（`RecommendFlow.stalled`），与电视端同一份。
                val stalled = com.hgtv.star.core.RecommendFlow.stalled(prevOffset, feed.nextOffset)
                if (stalled && restarts < 2) {
                    restarts++
                    sessionId = ""
                    offset = 0
                    persistCursor(lib)
                    continue
                }
                sessionId = feed.sessionId
                offset = feed.nextOffset
                persistCursor(lib)

                // 手动隐藏 / 已经追完 / 本机已经推过 / 同名（另一季、同 IP 另一版）全部滤掉。
                // 六条规则在 `:core`（`RecommendFlow.fresh`），电视端读的是同一份。
                val loadedIds = items.mapTo(HashSet(items.size)) { it.id }
                val loadedTitles = items.mapNotNull {
                    com.hgtv.star.core.RecommendFlow.titleKey(it.title).takeIf(String::isNotEmpty)
                }.toHashSet()
                val fresh = com.hgtv.star.core.RecommendFlow.fresh(
                    items = feed.page.items,
                    hidden = runCatching { lib.hidden() }.getOrDefault(emptySet()),
                    finished = runCatching { lib.recommendFinished() }.getOrDefault(emptySet()),
                    seen = runCatching { lib.recommendSeen() }.getOrDefault(emptySet()),
                    loadedIds = loadedIds,
                    loadedTitles = loadedTitles,
                    seenTitle = { lib.recommendSeenTitle(it) },
                )
                if (fresh.isNotEmpty()) {
                    val start = items.size
                    items += fresh
                    added += fresh.size
                    if (start == 0) adapter.notifyDataSetChanged()
                    else adapter.notifyItemRangeInserted(start, fresh.size)
                    // 落盘快照（后台线程写，40KB 左右）：冷启动的首屏靠它。
                    runCatching { com.hgtv.star.core.RecommendCache.save(items, cacheKey()) }
                }
                if (!feed.page.hasMore) {
                    ended = true
                    break
                }
                // 够一屏了：剩下的留给用户滑到底再取（电视端也是这个节奏）。
                if (added >= 6) break
            }
            loading = false
            if (added == 0 && items.isEmpty()) adapter.notifyDataSetChanged()
            if (com.hgtv.star.shared.BuildConfig.DEBUG) {
                android.util.Log.i(
                    "HGTV-PHONE",
                    "feed page genre=${genre ?: "all"} offset=$offset added=$added ended=$ended"
                )
            }
            // 数据回来了，但这一条流还没上屏（pager 里的另外两条体裁）：只把列表铺好，
            // **不起播**。起播由 `onShown()` 统一负责——否则三条流会抢同一台播放器。
            if (playingIndex < 0 && items.isNotEmpty() && !hidden) {
                // 进程里可能还挂着这一部（推荐页被系统重建过、或刚从别的页面回来）：
                // 直接定位到它接着放，别从第一条重新起播。
                val resumed = adoptableIndexOf()
                if (resumed >= 0) {
                    list.scrollToPosition(resumed)
                    playIndex(resumed)
                } else {
                    playIndex(0)
                }
            }
        }
    }

    /** 把这一条流的游标按体裁写进落盘（电视端只存一份，手机三条流各存各的）。 */
    private fun persistCursor(lib: com.hgtv.star.shared.data.Library) {
        runCatching {
            lib.phoneFeedCursor = com.hgtv.star.core.FeedCursor.upsert(
                lib.phoneFeedCursor,
                com.hgtv.star.core.FeedCursor.Entry(cacheKey(), sessionId, offset),
            )
        }
    }

    /**
     * 会话里那一部在当前列表中的位置；没有 / 不在列表里返回 -1。
     *
     * 判据与 [adoptPlaying] 一致（[PhoneSession.live]，不看地址新鲜度）：
     * 地址过期的那一路照样定位过去，由 [reopenAtCurrentPosition] 原地换地址，
     * 而不是从第一条重新起播。
     */
    private fun adoptableIndexOf(): Int {
        if (!sessionBelongsToThisFeed()) return -1
        val sid = PhoneSession.seriesId
        if (sid.isBlank() || !PhoneSession.live(sid)) return -1
        return items.indexOfFirst { it.id == sid }
    }

    // ------------------------------------------------------------------ 播放

    /** [startAt] >= 0 时从该毫秒继续播（换清晰度重取地址时用），否则按本机进度续播。 */
    private fun playIndex(index: Int, startAt: Long = -1L) {
        val series = items.getOrNull(index) ?: return
        recommendBatchJob?.cancel()
        recommendBatchJob = null
        recommendBatchKey = ""
        // 滑到新的一屏 = 明确的「我要看这一部」：上一屏被用户按停过，不该带到这一屏。
        // 两条状态都要清：
        // * `paused` —— `attach` 里 `playWhenReady = !paused` 直接读它，留着就是「滑过来先是暂停的」；
        // * `PhoneSession.resumeOnAdopt` —— 同一部剧再次出现在流里时会走 `adoptPlaying`，
        //   那一条用的是这个全局意图；它在用户暂停后离开页面时被写成 false，不清就会把
        //   暂停状态一路带到下一屏。
        paused = false
        PhoneSession.resumeOnAdopt = true
        playingDetail = null
        val direction = if (playingIndex >= 0 && index != playingIndex) {
            if (index > playingIndex) 1 else -1
        } else {
            0
        }
        playingIndex = index
        // 记下落盘的「停在哪一部」：下次冷启动从它的**下一部**接着播（同电视端 `rememberPlayed`）。
        runCatching { com.hgtv.star.core.RecommendCache.rememberPlayed(series.id, cacheKey()) }
        // 「真的上屏了」才记进曝光窗口（与电视端 `playRecommendAt → markRecommended` 同一条规则）：
        // 一页 20 条里用户可能只看第一条，把整页都记成已推等于白白烧掉 19 部内容。
        runCatching { library?.markRecommended(listOf(series.id), listOf(series.title)) }
        if (com.hgtv.star.shared.BuildConfig.DEBUG) {
            android.util.Log.i(
                "HGTV-PHONE",
                "feed play genre=${cacheKey()} index=$index dir=$direction series=${series.id}",
            )
        }
        // 窗口规则与电视端同一份（`core/SlideCache`）：换一部、方向、快滑、回看都在这里判。
        // 返回的计划**要留下**：它带着这一次的判定原因（`WINDOW` / `REVISIT` / `FLING`），
        // 回看那一部时主方向第二步会被升到完整预热（见 `SlideCacheManager.plan`）。
        // 早先这里把返回值丢掉、预热时重新 `plan(index)`，原因永远是 `WINDOW`，
        // 于是「滑走又滑回来」那一条享受不到提升，回来的第一下仍是冷启动。
        pendingPlan = slideCache.onItemChanged(index, direction, android.os.SystemClock.elapsedRealtime())
        scheduleDwell()
        // 进程里如果还挂着**这部剧**的这一路（从正片退回来、视图刚被重建），先试着接。
        // 接上就什么都不做：不取详情、不取地址、不 prepare、不 seek——画面就是原来那一秒。
        if (startAt < 0 && adoptPlaying()) {
            PhoneSession.endHandoff()
            schedulePreload(slideCache.firstFrameDelay())
            return
        }
        val token = ++playToken
        val repo = PhoneServices.repository(context)

        scope.launch {
            // 详情先看落盘缓存（电视端同一条路）：快照铺出来的那一部，
            // 它的详情正是上一轮预热时写下的，命中就不必再等一次接口往返。
            val cachedDetail = withContext(Dispatchers.IO) {
                runCatching { com.hgtv.star.core.DetailCache.load(series.id) }.getOrNull()
            }
            val detail = cachedDetail ?: withContext(Dispatchers.IO) {
                runCatching { repo.detail(series.id) }.getOrNull()
            } ?: return@launch
            if (token != playToken) return@launch
            playingDetail = detail
            // 写回缓存（`DetailCache` 自己限 8 条）：下一次冷启动就能直接用。
            if (cachedDetail == null) runCatching { com.hgtv.star.core.DetailCache.save(detail) }

            // **续播**：本机存过这部剧的进度就接着看，不是每次回到这一屏都从第 1 集第 0 秒开始。
            val lib = library ?: PhoneServices.awaitLibrary(context).also { library = it }
            val saved = withContext(Dispatchers.IO) {
                runCatching { lib.progress(series.id) }.getOrNull()
            }
            val episodeIndex = saved
                ?.let { s -> detail.episodes.indexOf(s.episodeId).takeIf { it >= 0 } }
                ?: 0
            val episodeId = detail.episodes.getOrNull(episodeIndex) ?: return@launch
            val resumeAt = if (saved != null && saved.episodeId == episodeId) saved.position else 0L
            val from = if (startAt >= 0) startAt else resumeAt

            // 相邻预热的命中：地址、索引、首块都已经在手，只差挂媒体源（几十毫秒）。
            // 与 `PhoneSession.takeNext` 同一条档位校验：降档之后不接更高的那一份。
            val warm = PhonePreload.take(series.id, episodeId, lib.maxQuality)
            if (warm != null) {
                if (com.hgtv.star.shared.BuildConfig.DEBUG) {
                    android.util.Log.i("HGTV-PHONE", "feed warm hit series=${series.id} episode=${episodeIndex + 1}")
                }
                if (token != playToken) { runCatching { warm.close() }; return@launch }
                currentEpisodeIds[index] = episodeId
                currentEpisodeIndex[index] = episodeIndex
                attach(index, warm, from)
                return@launch
            }

            val info = withContext(Dispatchers.IO) {
                runCatching { repo.stream(episodeId, lib.maxQuality, detail.playback) }.getOrNull()
            } ?: return@launch
            if (token != playToken) return@launch

            val prepared = withContext(Dispatchers.IO) {
                runCatching {
                    RemoteVideo(PlaybackTransport.client, info, info.mediaKey(episodeId)).also { it.prepare() }
                }.getOrNull()
            } ?: return@launch
            if (token != playToken) { prepared.close(); return@launch }

            currentEpisodeIds[index] = episodeId
            currentEpisodeIndex[index] = episodeIndex
            attach(index, prepared, from)
        }
        // 首帧之后再排预热（与电视端「首帧 + 2 秒」同一条：此刻解码器还在出帧、buffer 还在填）。
        schedulePreload(slideCache.firstFrameDelay())
    }

    /**
     * 推荐页出画面后，用一次批量接口预取 6 个模型：
     * 当前剧下一集 + 后面 5 部不同推荐剧的首集。
     */
    private fun scheduleRecommendBatchModels(anchor: Int) {
        if (hidden || anchor != playingIndex) return
        val data = playingDetail ?: return
        val current = currentEpisodeIndex[anchor] ?: return
        val nextId = data.episodes.getOrNull(current + 1).orEmpty()
        val candidates = (anchor + 1 until items.size)
            .mapNotNull(items::getOrNull)
            .filter { it.id != data.series.id }
            .distinctBy { it.id }
            .take(8)
        val key = "${data.series.id}:$nextId:${candidates.joinToString(",") { it.id }}"
        if (recommendBatchKey == key && recommendBatchJob?.isActive == true) return
        recommendBatchJob?.cancel()
        recommendBatchKey = key
        val token = playToken
        recommendBatchJob = scope.launch {
            delay(slideCache.firstFrameDelay())
            if (hidden || anchor != playingIndex || token != playToken) return@launch
            val repo = PhoneServices.repository(context)
            val ids = linkedSetOf<String>()
            if (nextId.isNotBlank()) ids += nextId
            val target = if (nextId.isBlank()) 5 else 6
            withContext(Dispatchers.IO) {
                for (series in candidates) {
                    if (ids.size >= target) break
                    val item = runCatching { com.hgtv.star.core.DetailCache.load(series.id) }.getOrNull()
                        ?: runCatching { repo.detail(series.id) }.getOrNull()
                        ?: continue
                    val first = item.episodes.firstOrNull() ?: continue
                    ids += first
                }
                if (ids.isNotEmpty()) {
                    runCatching { repo.prefetchModels(ids.toList(), data.playback) }
                }
            }
            if (com.hgtv.star.shared.BuildConfig.DEBUG) {
                android.util.Log.i(
                    "HGTV-PHONE",
                    "recommend batch models=${ids.size} anchor=$anchor",
                )
            }
            if (recommendBatchKey == key) {
                recommendBatchKey = ""
                recommendBatchJob = null
            }
        }
    }

    // ------------------------------------------------------------------ 相邻预热

    /** 与电视端 `SlideCacheManager` 同一份窗口 / 快滑 / 回看规则（纯逻辑，`:core` 里带单测）。 */
    private val slideCache = com.hgtv.star.core.SlideCacheManager()

    /** 上一次排定窗口预热的时间（节流用：连着滑几屏不必每一屏都排一次）。 */
    private var preloadAt = 0L
    /** [playIndex] 里 `onItemChanged` 给出的那一份计划（带判定原因），预热时优先用它。 */
    private var pendingPlan: com.hgtv.star.core.SlideCacheManager.Plan? = null

    /**
     * 预热排程（延迟 / 让路重试 / 停留确认 / 取消）。
     *
     * 与电视端**同一个类**（`:shared` 的 [com.hgtv.star.shared.feed.PreloadScheduler]）：
     * 这四条时序规则以前两端各写一遍，同一个问题常常只修在一端。
     * 这里只注入手机才有的东西：锚点是这一屏的下标、是否在前台、带宽是否让路、怎么派发。
     */
    private val preloadScheduler by lazy {
        com.hgtv.star.shared.feed.PreloadScheduler(
            slideCache = slideCache,
            anchor = { playingIndex },
            active = { !hidden && playingIndex >= 0 },
            gateOpen = { preloadGateOpen() },
            // 优先用换剧那一刻算出来的计划：它带着 `REVISIT`（滑回来）/ `FLING`（快滑）
            // 这些判定，重算一遍就只剩 `WINDOW`，回看与快滑的提升全丢了。
            plan = {
                val planned = pendingPlan ?: slideCache.plan(playingIndex)
                pendingPlan = null
                planned
            },
            apply = { planned, _ -> applyPreload(planned) },
        )
    }

    /** 带宽能不能让出来：正在放的那一部 buffer 不足 3 秒时，预加载不该抢同一条下行。 */
    private fun preloadGateOpen(): Boolean {
        val exo = player ?: return true
        if (!exo.isPlaying) return true
        return exo.bufferedPosition - exo.currentPosition >= 3_000L
    }

    /**
     * 停留确认：连划之后停下来，把主方向那一步重新升回完整预热。
     *
     * 只在**刚才是在连划**时有意义：快滑期间窗口被降级成「仅地址」，用户停下来之后
     * 不补这一步，下一步滑过去仍然是冷启动。规则在 [com.hgtv.star.shared.feed.PreloadScheduler]。
     */
    private fun scheduleDwell() {
        preloadScheduler.scheduleDwell()
    }

    /**
     * 排定一次相邻预热（延迟到首帧之后、带宽让路、用户已经滑走就取消）。
     *
     * 节流判断必须**在排定之前**：早先先取消再判，被节流的那一次会把已经排好的预热
     * 一起取消掉——连着滑几屏之后一段时间内一条预热都不发，用户停在某一屏上时
     * 下一步永远是冷启动（正是「滑到下一部要等」的来源）。
     * 已经排着的那一次会在执行时读 `pendingPlan`，因此它拿到的仍是最新的一屏。
     */
    private fun schedulePreload(delayMs: Long) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < preloadAt) return
        preloadAt = now + delayMs
        preloadScheduler.scheduleWindow(delayMs, "switch")
    }

    /**
     * 按计划真正发起预热。
     *
     * 只派前 3 条（电视端 `PRELOAD_MAX_TARGETS` 同一条理由：同时发太多只会互相抢带宽）。
     * 地址级只调一次 `stream()` 把地址落进缓存；完整级再 `prepare()` 出索引与首块。
     */
    private fun applyPreload(plan: com.hgtv.star.core.SlideCacheManager.Plan) {
        val lib = library ?: return
        val repo = PhoneServices.repository(context)
        var dispatched = 0
        for (target in plan.targets) {
            if (dispatched >= 3) break
            val series = items.getOrNull(target.index) ?: continue
            // 正在放的那一部不预热（它的地址与首块本来就是热的）。
            if (target.index == playingIndex) continue
            if (target.level == com.hgtv.star.core.CacheLevel.NONE) continue
            // 已经有了完整预热就别再发一遍。
            dispatched++
            scope.launch {
                val detail = withContext(Dispatchers.IO) {
                    runCatching { repo.detail(series.id) }.getOrNull()
                } ?: return@launch
                if (detail.episodes.isEmpty()) return@launch
                val saved = withContext(Dispatchers.IO) {
                    runCatching { lib.progress(series.id) }.getOrNull()
                }
                val epIndex = saved?.let { s -> detail.episodes.indexOf(s.episodeId).takeIf { it >= 0 } } ?: 0
                val episodeId = detail.episodes.getOrNull(epIndex) ?: return@launch
                val info = withContext(Dispatchers.IO) {
                    runCatching { repo.stream(episodeId, lib.maxQuality, detail.playback) }.getOrNull()
                } ?: return@launch
                if (target.level != com.hgtv.star.core.CacheLevel.FULL) return@launch
                val prepared = withContext(Dispatchers.IO) {
                    runCatching {
                        RemoteVideo(PlaybackTransport.client, info, info.mediaKey(episodeId)).also { it.prepare() }
                    }.getOrNull()
                } ?: return@launch
                if (!isAttachedToWindow) { runCatching { prepared.close() }; return@launch }
                PhonePreload.put(series.id, episodeId, prepared)
                if (com.hgtv.star.shared.BuildConfig.DEBUG) {
                    android.util.Log.i(
                        "HGTV-PHONE",
                        "preload done index=${target.index} level=${target.level} anchor=$playingIndex"
                    )
                }
            }
        }
    }

    /** 每 500ms 把播放进度回传给页面，画在底部进度条上。 */
    /** 片尾预热窗口：与 TV 的 `PrefetchPolicy.nearEnd` 同一档（30 秒）。 */
    private val prefetchWindowMs = 30_000L

    /** 推荐流里连续几部放不了就不再自动翻（与电视端 `RECOMMEND_SKIP_LIMIT` 同值）。 */
    private val RECOMMEND_SKIP_LIMIT = 4

    /**
     * `advancing` 的有效期（毫秒）。
     *
     * 它只是「这一集的片尾已经处理过」的防重入标记。交接一旦没走完（播放页没起来、
     * 用户中途返回、跳转被系统拦掉），标志会一直卡着，之后每一集都不再连播。
     * 超过这个时间就当作失效，允许重新推进。
     */
    private val ADVANCE_GUARD_MS = 5_000L
    /** 正在预热的那一集（防重入）。 */
    private var prefetchingFor = ""
    /** 上一次允许发起预热的时间（避免每 500ms 重试一次）。 */
    private var prefetchAt = 0L

    // —— 弹幕（与正式播放页、电视端共用同一份加载内核与同一个 DanmakuView）——
    /**
     * 每一屏一份加载器（`:shared` 的 [com.hgtv.star.shared.media.DanmakuLoader]）。
     *
     * 为什么按屏分：信息流的每一屏是**独立的一集**，各拉各的弹幕；共用一份的话，
     * 滑走再滑回来会重新拉一遍（那一份记忆属于上一屏）。
     */
    private val danmakuLoaders = HashMap<Int, com.hgtv.star.shared.media.DanmakuLoader>()

    /** 取（或建）某一屏的加载器：目标层与集号都按那一屏现取。 */
    private fun danmakuLoaderOf(index: Int): com.hgtv.star.shared.media.DanmakuLoader =
        danmakuLoaders.getOrPut(index) {
            com.hgtv.star.shared.media.DanmakuLoader(
                scope = scope,
                repository = { PhoneServices.repository(context) },
                mode = { library?.danmakuMode ?: DanmakuMode.FULL },
                target = { pageAt(index)?.danmakuView },
                post = { block -> list.post(block) },
                alive = { isAttachedToWindow },
            )
        }

    /**
     * 片尾 30 秒内把**下一集**的地址与首块准备好（[PhoneSession.putNext]）。
     *
     * 这是「片尾自动进正片不黑屏」的关键一步：正式播放页接手时直接
     * `takeNext` 拿到已经 prepare 好的 `RemoteVideo`，只差挂媒体源（几十毫秒），
     * 不必再走一次「取详情 → 取地址 → 解析首块」（那三步合计 1~2 秒，正是黑屏的大头）。
     *
     * 只在信息流前台、且在播当前这一屏时做；同一集只发起一次。
     */
    private fun maybePrefetchNext(position: Long, duration: Long) {
        val lib = library ?: return
        val d = playingDetail ?: return
        val index = playingIndex
        if (index !in items.indices) return
        val current = currentEpisodeIndex[index] ?: 0
        val next = current + 1
        if (next > d.episodes.lastIndex) return
        if (duration - position > prefetchWindowMs) return
        val nextId = d.episodes[next]
        val now = System.currentTimeMillis()
        if (nextId == prefetchingFor || now < prefetchAt) return
        if (PhoneSession.nextEpisodeId == nextId) return
        prefetchingFor = nextId
        prefetchAt = now + 5_000L
        val maxQuality = lib.maxQuality
        scope.launch {
            val remote = withContext(Dispatchers.IO) {
                runCatching {
                    val info = PhoneServices.repository(context).stream(nextId, maxQuality, d.playback)
                    RemoteVideo(PlaybackTransport.client, info, info.mediaKey(nextId)).also { it.prepare() }
                }.getOrNull()
            }
            prefetchingFor = ""
            if (remote != null) {
                PhoneSession.putNext(nextId, remote)
                android.util.Log.i("HGTV-PHONE", "feed prefetch next id=$nextId episode=${next + 1}")
            }
        }
    }

    /**
     * 把本机偏好落到这一页的弹幕层（与正式播放页同一份实现、同一批落盘值）。
     */
    private fun applyDanmakuSettings(page: PhoneVideoPage) {
        val lib = library ?: return
        val view = page.danmakuView
        view.mode = lib.danmakuMode
        // 横屏推荐页的视图高度只有 1080，按高度算会比正式播放页（按长边 1920）
        // 小一档；两端统一按屏幕长边取基准，切进正式播放页时字号不会突然变大。
        view.sizeFollowsLongEdge = true
        view.textScale = PhoneDanmakuSize.renderScale(context)
        view.textAlpha = lib.danmakuAlpha
        view.speed = lib.danmakuSpeed
        view.mergeRepeats = lib.danmakuMerge
        view.mergeMinCount = lib.danmakuMergeMin
        view.effectsEnabled = lib.danmakuEffects
        view.visibility = if (lib.danmakuMode == DanmakuMode.HIDDEN) View.GONE else View.VISIBLE
    }

    /**
     * 拉这一屏这一集的弹幕。
     *
     * 规则全在 `:shared` 的 `DanmakuLoader` 里（同一集不重拉、旧回包丢弃、隐藏时不发请求、边拉边画）。
     */
    private fun loadDanmaku(page: PhoneVideoPage, index: Int, episodeId: String, seriesId: String) {
        val lib = library ?: return
        val view = page.danmakuView
        view.mode = lib.danmakuMode
        if (index < 0) return
        danmakuLoaderOf(index).load(episodeId, seriesId)
    }

    private fun syncDanmakuClock(page: PhoneVideoPage, index: Int, positionMs: Long, running: Boolean, speed: Float) {
        if (index < 0) return
        danmakuLoaderOf(index).syncClock(positionMs, running, speed)
    }

    private fun startProgressTicker() {
        progressTick?.let { list.removeCallbacks(it) }
        val tick = object : Runnable {
            override fun run() {
                val exo = player
                val page = boundPage
                if (exo != null && page != null) {
                    val duration = exo.duration
                    if (duration > 0) {
                        val position = exo.currentPosition
                        page.setProgress(position.toFloat() / duration)
                        page.setBufferedProgress(exo.bufferedPosition.toFloat() / duration)
                        // 还在播就刷新会话时间戳：连看十几分钟后切页，地址仍然算「新鲜」。
                        if (exo.isPlaying) PhoneSession.touch()
                        syncDanmakuClock(page, playingIndex, position, exo.isPlaying, exo.playbackParameters.speed)
                        if (!paused) maybePrefetchNext(position, duration)
                    }
                    // 片尾兜底：`STATE_ENDED` 只在状态**变化**时回调一次。如果那一下刚好
                    // 被暂停 / 切页 / 交接吞掉，播放器会永远停在末帧——用户读到的就是
                    // 「卡在这一集末尾，不往下走」。每 500ms 补一次，`onEpisodeEnded` 自己
                    // 有 `paused` / `advancing` / 位置复核三道闸，重复调用是安全的。
                    if (exo.playbackState == Player.STATE_ENDED && playingIndex >= 0) {
                        onEpisodeEnded(playingIndex)
                    }
                }
                list.postDelayed(this, 500)
            }
        }
        progressTick = tick
        list.postDelayed(tick, 500)
    }

    private fun attach(index: Int, video: RemoteVideo, startAt: Long = 0L) {
        // 换了源就是新的一集：上一集「正在交接」的标记不再成立。
        advancing = false
        val series = items.getOrNull(index)
        val episodeIndex = currentEpisodeIndex[index] ?: 0
        val exo = PhoneServices.player(context, library?.decoderMode)
        player = exo
        // 播放列表里可能还挂着「下一集」（播放页预热好的那一项）。
        // 信息流的语义是「一集播完 → 进正式播放页」，留着它 ExoPlayer 会自己无感推进，
        // 于是信息流里悄悄换了集、集号却没变。这里只保留当前这一项。
        runCatching {
            if (exo.mediaItemCount > 1) exo.removeMediaItems(1, exo.mediaItemCount)
        }
        // 同上：列表已经只剩这一项，标志不能再停在 true。
        PhoneSession.nextQueued = false
        // 那一项已经从播放列表里摘掉，它捕获的 `RemoteVideo` 也就没人用了：一起关掉。
        PhoneSession.clearNext()

        // 页面还没绑上来时（刚重建 / 刚返回）不丢这一路：媒体源照挂，
        // Surface 交给 `onBindViewHolder → rebindCurrentPage` 补上。
        val page = pageAt(index)
        if (page != null && series != null) {
            attachPage(exo, page, series, episodeIndex, forceRebind = false)
        }

        // 媒体源：**带 mediaId**，播放页接手时据此判断「放的是不是同一集」。
        val factory = DataSource.Factory { VideoDataSource(video) }
        val episodeId = currentEpisodeIds[index].orEmpty()
        // 先停再挂：直接 `setMediaSource` 会在出帧中的解码器上 flush，
        // 厂商实现会抛 `CodecException 0x80000000`，整条链路随即报 4003（见 `swapMediaSource`）。
        exo.swapMediaSource(episodeId, video.info.url, factory)
        remote = video
        // 记进进程级会话：播放页接手时不用重新取地址（见 [PhoneSession]）。
        // 详情用 `playingDetail`（`playIndex` 里存的那一份，带完整集列表）。
        val d = playingDetail
        if (d != null) {
            PhoneSession.publish(
                d, episodeIndex, video, video.info.quality,
                PhoneSession.Owner.FEED, cacheKey(),
            )
        }
        exo.setPlaybackSpeed(userSpeed)
        attachListener(exo, index)
        exo.playWhenReady = !paused
        exo.prepare()
        if (startAt > 0) exo.seekTo(startAt)
        startProgressTicker()
    }

    /**
     * 挂监听（隐藏时摘掉、回来时重挂：摘掉是为了不让隐藏期间的回调改界面）。
     *
     * 回调里一律用 [boundPage] 现取目标页，不捕获构造时的那个：交接、重建、重新绑定之后
     * 播放器可能已经挂到另一张页上，捕获旧页会把画面尺寸写到已经回收的 View 上。
     */
    private fun attachListener(exo: ExoPlayer, index: Int) {
        listener?.let { exo.removeListener(it) }
        val hook = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val page = boundPage ?: return
                // 不再按片源方向强制 ZOOM：竖屏短剧在竖屏里铺满会裁掉左右，
                // 从播放页退回来时也会突然放大。画面模式只认用户选择。
                val target = (library?.frameMode ?: VideoFrameMode.FIT).toFit().resizeMode()
                page.setResizeMode(target)
                // 画面尺寸到手才把「全屏」按钮贴到画面下边缘（之前按钮位置是写死的）。
                page.updateVideoFrame(videoSize.width, videoSize.height)
            }

            /**
             * 片尾：**自动进入正式播放界面**，并从下一集接着播。
             *
             * 与 TV 端一致（`MainActivityPlayer` 里 `STATE_ENDED` 的 recommend 分支 →
             * `enterFormalPlayback(advanceNext = true)`）：推荐流只负责「把人勾住」，
             * 真要看完整部，交给正式播放页按它自己的连播规则走。
             * 最后一集播完则「换一部接着看」——TV 是弹一份相关剧集，手机就是往下滑一屏。
             */
            override fun onPlaybackStateChanged(state: Int) {
                notifyPlayStateChanged()
                // 真的出画面了：连续失败计数归零（取地址成功不算，能放才算）。
                if (state == Player.STATE_READY) {
                    recommendFailures = 0
                    scheduleRecommendBatchModels(index)
                }
                if (state == Player.STATE_ENDED) onEpisodeEnded(index)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                notifyPlayStateChanged()
            }

            /**
             * 这一部放不了：**换下一部**（与电视端 `screen=="recommend"` 那条分支一致）。
             *
             * 早先这里什么都没有：解码失败之后画面就停住、声音也没有，用户只看到一屏静止的
             * 封面与一条进度条，不知道发生了什么，只能退出重进。
             */
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.i(
                    "HGTV-PHONE",
                    "feed error ${error.errorCodeName}(${error.errorCode}) index=$index failures=$recommendFailures"
                )
                if (paused || hidden) return
                runCatching { player?.pause() }
                recommendFailures++
                if (recommendFailures > RECOMMEND_SKIP_LIMIT) {
                    // 连续几部都放不了：停在一句提示上，不再自动翻（否则会一路刷到底）。
                    hint.text = "这一部暂时无法播放，上滑换一部"
                    hint.visibility = View.VISIBLE
                    hint.bringToFront()
                    return
                }
                val next = index + 1
                if (next < items.size) {
                    hint.text = "这一部放不了，换下一部"
                    hint.visibility = View.VISIBLE
                    hint.bringToFront()
                    list.smoothScrollToPosition(next)
                } else {
                    loadMore()
                    hint.text = "这一部放不了，正在换下一部…"
                    hint.visibility = View.VISIBLE
                    hint.bringToFront()
                }
            }
        }
        listener = hook
        exo.addListener(hook)
    }

    private fun pageAt(index: Int): PhoneVideoPage? {
        val holder = list.findViewHolderForAdapterPosition(index) as? PageHolder ?: return null
        return holder.itemView as? PhoneVideoPage
    }

    private fun togglePlay() {
        val exo = player ?: return
        // 判据用 `playWhenReady`（意图）而不是 `isPlaying`（此刻有没有在出帧）：
        // 起播缓冲的那一两秒里 `isPlaying` 是 false，按 `isPlaying` 判会把「暂停」
        // 错当成「播放」，用户点一下反而什么都没发生。
        if (exo.playWhenReady) exo.pause() else exo.play()
        // 记下用户自己的意图：它决定切页 / 回来时要不要自动起播（见 [paused]）。
        paused = !exo.playWhenReady
        boundPage?.showCenterIcon(exo.playWhenReady)
        notifyPlayStateChanged()
    }

    /**
     * 一集播完：落 completed 进度 → 进正式播放页播下一集（最后一集则换下一部）。
     *
     * 判据与 TV 一致：
     * * 只在信息流是**前台且在播**时触发（`paused` / 已经切走过就不算）；
     * * 只在**真的播到结尾**时推进（`STATE_ENDED` 本身就是这个含义，但排队故障也可能把它
     *   提前抛出来，所以再核一次播放位置）；
     * * 同一集只推进一次（`advancing` 防重入）。
     */
    private fun onEpisodeEnded(index: Int) {
        if (paused || hidden) return
        // `advancing` 只用来防重入。交接一旦没走完（播放页没起来、用户中途返回、
        // 跳转被系统拦掉），这个标志会一直卡着，之后每一集都不再连播——
        // 用户读到的就是「卡在这一集末尾」。给它 5 秒的寿命。
        if (advancing && SystemClock.elapsedRealtime() - advancingAt < ADVANCE_GUARD_MS) return
        advancing = false
        val series = items.getOrNull(index) ?: return
        val d = playingDetail ?: return
        val exo = player ?: return
        // 复核：位置必须真的在片尾（留 2 秒余量，与 TV 的 atEnd 判据同值）。
        val duration = exo.duration.coerceAtLeast(0)
        if (duration <= 0 || exo.currentPosition < duration - 2_000) return

        val current = currentEpisodeIndex[index] ?: 0

        val next = current + 1
        if (next <= d.episodes.lastIndex) {
            advancing = true
            advancingAt = SystemClock.elapsedRealtime()
            // 交接给正片：**不暂停**。两边用的是同一个进程级播放器，暂停只会让
            // 切页那一下断一拍；播放页接手的是同一路解码，声音是连续的。
            PhoneSession.resumeOnAdopt = true
            PhoneSession.beginHandoff()
            PhoneSession.takeOver(PhoneSession.Owner.PLAYER)
            onHidden(handoff = true)
            // **在切页之前**把下一集挂上播放器：这样正片一进来接的就是已经在解码的这一路，
            // 声音不断、画面第一帧立刻有。不预热直接切页时，新窗口要等「取详情 → 取地址 →
            // 解析首块 → 出第一帧」，那一段就是用户看到的黑屏。
            val primed = primeHandoff(d, next)
            android.util.Log.i("HGTV-PHONE", "handoff to formal episode=${next + 1} primed=$primed")
            hideChromeForHandoff(series)
            // 下一集从 0 秒开始（不是续播：这一集用户还没看过）。
            // `noTransition` 关掉系统转场：滑动 / 淡入那 200ms 里新窗口还没画出视频，
            // 用户看到的就是一次黑屏。
            context.phoneNav().open(
                Route.Player(
                    series.id,
                    series.title,
                    episode = next,
                    fromRecommend = true,
                    noTransition = true,
                )
            )
            // 4.4–5.x 不认 FLAG_ACTIVITY_NO_ANIMATION 的入场：再显式关一次。
            (context as? android.app.Activity)?.overridePendingTransition(0, 0)
        } else {
            // 最后一集：与 TV 的「全剧已播完 → 换一部接着看」等价——往下滑一屏。
            // 同样上锁：否则每 500ms 的兜底检查会反复触发这一段（反复滚动 / 反复 loadMore）。
            advancing = true
            advancingAt = SystemClock.elapsedRealtime()
            val nextSeries = index + 1
            if (nextSeries < items.size) {
                list.smoothScrollToPosition(nextSeries)
            } else {
                loadMore()
                hint.text = "全剧已播完，正在换下一部…"
                hint.visibility = View.VISIBLE
                hint.bringToFront()
            }
        }
    }

    /**
     * 切页之前先把下一集挂到进程级播放器上（预热命中时才做）。
     *
     * 用的是 [maybePrefetchNext] 在片尾 30 秒内备好的那份 `RemoteVideo`：地址与首块都已在手，
     * 这里只差 `setMediaSource`（几十毫秒）。挂上之后立即 `playWhenReady = true`，
     * 因此切页那 200ms 里声音是连续的，新窗口拿到 Surface 时立刻就有帧。
     *
     * 返回 false 表示没预热到（例如刚起播就拖到片尾）：调用方照常切页，
     * 由正式播放页自己取地址——那条路会慢一点，但不会出错。
     */
    private fun primeHandoff(d: Detail, nextIndex: Int): Boolean {
        val nextId = d.episodes.getOrNull(nextIndex) ?: return false
        val warm = PhoneSession.takeNext(nextId) ?: return false
        val exo = player ?: run { runCatching { warm.close() }; return false }
        val ok = runCatching {
            val factory = DataSource.Factory { VideoDataSource(warm) }
            exo.swapMediaSource(nextId, warm.info.url, factory)
            exo.setPlaybackSpeed(userSpeed)
            exo.playWhenReady = true
            // 必须在这里就 prepare：`swapMediaSource` 先 `stop()` 再挂源，此刻播放器停在 IDLE。
            // 不 prepare 的话，紧跟着起来的正式播放页看到的是一台「IDLE、没有在准备」的播放器，
            // 会判成接不上 → 重新取详情、重新取地址、从 0 起播——用户看到的就是闪黑 + 从头开始。
            // prepare 之后这一路马上开始缓冲，播放页接手时可能已经有首帧（`keepContentOnPlayerReset`
            // 还会保留上一帧），画面与声音都不断。
            exo.prepare()
        }.isSuccess
        if (!ok) { runCatching { warm.close() }; return false }
        PhoneSession.publish(d, nextIndex, warm, warm.info.quality, PhoneSession.Owner.PLAYER)
        return true
    }

    /** 位置读数：与电视端 `formatTime` 同一份（`:core` 的 `PlaybackInfoLine.time`）。 */
    private fun clock(ms: Long): String = com.hgtv.star.core.PlaybackInfoLine.time(ms)

    private fun beginTempSpeed() {
        if (tempSpeed) return
        tempSpeed = true
        player?.setPlaybackSpeed(2f)
        hint.text = "2 倍速播放中"
        hint.visibility = View.VISIBLE
        hint.bringToFront()
    }

    private fun endTempSpeed() {
        if (!tempSpeed) return
        tempSpeed = false
        player?.setPlaybackSpeed(userSpeed)
        hint.visibility = View.GONE
    }

    // ------------------------------------------------------------------ 播放设置

    /**
     * 长按中间 1/3：播放设置。
     *
     * **与正式播放页完全同一份面板**（[buildPlaybackSettingsRows] 的各行），
     * 早先这里只做了倍速 / 清晰度 / 画面模式三项，用户想调字号、透明度时找不到那一行。
     */
    private fun openSettings() {
        // 书库没读完时先等它：直接 return 会让长按中间「没反应」。
        val lib = library
        if (lib == null) {
            scope.launch {
                library = PhoneServices.awaitLibrary(context)
                userSpeed = library?.playbackSpeed ?: userSpeed
                openSettings()
            }
            return
        }
        // 推荐页没有正式播放页的沉浸式底板，面板可用高度按半屏留足；
        // 内容仍然可以滚动，但不会一打开只看到前几项。
        sheet.show("播放设置", landscape = false, heightFraction = .5f) { panel ->
            buildPlaybackSettingsRows(
                host = this,
                panel = panel,
                lib = lib,
                perLine = 0,
                speed = userSpeed,
                onSpeed = { value ->
                    userSpeed = value
                    // 落盘：进播放页 / 退回来都读同一份，不会跳回旧值（与 TV 的倍速设置同一条路）。
                    lib.playbackSpeed = value
                    player?.setPlaybackSpeed(userSpeed)
                },
                onQuality = {
                    // 换档要换一套地址：从当前位置重开这一屏。
                    val position = player?.currentPosition ?: 0L
                    sheet.dismiss()
                    playIndex(playingIndex, position)
                },
                onFrame = { mode ->
                    boundPage?.setResizeMode(mode.toFit().resizeMode())
                },
                onScale = { value -> boundPage?.setVideoScale(value / 100f) },
                // 弹幕四项：只写书库不会动已经在画的弹幕层，必须立刻把当前页重刷一遍。
                // 从「隐藏」切回可见且这一集还没拉过弹幕时补一次请求（与播放页同一判据）。
                onDanmaku = {
                    val page = pageAt(playingIndex) ?: boundPage
                    if (page != null) {
                        val wasHidden = page.danmakuView.visibility == View.GONE
                        applyDanmakuSettings(page)
                        if (wasHidden && lib.danmakuMode != DanmakuMode.HIDDEN) {
                            val loader = danmakuLoaderOf(playingIndex)
                            val episode = currentEpisodeIds[playingIndex]
                            val series = items.getOrNull(playingIndex)
                            if (!loader.loaded && episode != null && series != null) {
                                loadDanmaku(page, playingIndex, episode, series.id)
                            }
                        }
                    }
                },
            )
        }
        onSheetVisibilityChanged?.invoke(true)
        revealChrome()
    }

    /**
     * 竖向信息流：**只有明显纵向的手势才滚**。
     *
     * RecyclerView 自己的判据只有 `|dy| > slop`，没有主轴偏向。横滑拖进度取消之后
     * （见 `PhoneVideoPage` 的 `dragSeek`），横着划的那一下不再有谁去
     * `requestDisallowInterceptTouchEvent`，于是「横滑带一点纵向分量」会被它认成上滑，
     * `PagerSnapHelper` 顺势吸附到下一页——用户读到的正是
     * 「关了左右滑动，横着一划反而上下翻页」。
     *
     * 这里加一把方向锁：整个手势在第一次越过 slop 时定性，判成横向就彻底不滚。
     * 判据取 `|dy| > |dx|`（纵向必须**比横向多**），横滑（含斜着横滑）一律不触发翻页；
     * 正常的上下滑 `|dy|` 通常是 `|dx|` 的两三倍以上，不受影响。
     */
    private class VerticalPagerList(context: Context) : RecyclerView(context) {

        private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f

        /** 0 = 未定性，1 = 纵向（这一下归列表），2 = 横向（这一下不归列表）。 */
        private var axis = 0

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    axis = 0
                }
                MotionEvent.ACTION_MOVE -> {
                    if (axis == 0) {
                        val dx = Math.abs(e.x - downX)
                        val dy = Math.abs(e.y - downY)
                        if (dx > slop || dy > slop) axis = if (dy > dx) 1 else 2
                    }
                    if (axis == 2) return false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> axis = 0
            }
            return super.onInterceptTouchEvent(e)
        }
    }

    // ------------------------------------------------------------------ 列表

    private inner class FeedAdapter : RecyclerView.Adapter<PageHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val page = PhoneVideoPage(
                context,
                withTabBar = true,
                fullscreenEntry = true,
                // 推荐页**不接管横滑**：这里的左右手势归外层 pager（换体裁），
                // 而且横滑一旦被这一层接住就会拦住父容器，上滑的横向抖动会被误判成拖进度。
                dragSeek = false,
            )
            // 高度跟着 RecyclerView 走（= 一个屏幕）：尺寸变化不再需要重建这一页，
            // 正在出画面的那块 Surface 因此能在转屏、进出播放页时一直活着。
            page.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )

            page.onTap = {
                // 自动收起后第一下先把信息叫回来，不顺手暂停。
                if (!page.isChromeVisible()) revealChrome(page) else togglePlay()
            }
            page.onLongPress = { zone ->
                when (zone) {
                    PhoneTouch.Zone.CENTER -> {
                        if (!page.isChromeVisible()) revealChrome(page)
                        openSettings()
                    }
                    else -> beginTempSpeed()
                }
            }
            page.onLongPressEnd = { endTempSpeed() }
            // 信息流的「全屏」按钮：直接进播放页并让它切横屏。
            page.onFullscreen = {
                val series = items.getOrNull(playingIndex) ?: page.currentSeries()
                if (series != null) {
                    // 集号以**正在播的那一屏**为准：`getChildAdapterPosition(page)` 在全屏按钮
                    // 刚显示、页还没完全挂上时可能返回 -1，那样传过去的就是第 1 集，
                    // 播放页判定「集号对不上」→ 拒绝接手 → 重新取地址从头播。
                    val episode = liveEpisodeOf(series, playingIndex)
                    launchPlayer(
                        series = series,
                        episode = episode,
                        fullscreen = true,
                        // 全屏是「同一路继续看」：进来时的播放/暂停状态原样带过去。
                        play = player?.playWhenReady == true,
                    )
                }
            }
            page.onWatch = { openPlayer(page, fullscreen = false) }
            // 标题是简介页入口；选集 / 观看完整短剧仍走正式播放页。
            page.onTitle = {
                val series = items.getOrNull(playingIndex) ?: page.currentSeries()
                if (series != null) {
                    context.phoneNav().open(Route.Detail(series.id, series.title))
                }
            }
            // 进度条拖动：与播放页同一套反馈（拖动中显示目标时间，松手才 seek）。
            page.onSeekStart = { }
            page.onSeek = { fraction ->
                val duration = player?.duration ?: 0L
                if (duration > 0) {
                    hint.text = "${clock((duration * fraction).toLong())} / ${clock(duration)}"
                    hint.visibility = View.VISIBLE
                }
            }
            page.onSeekEnd = { fraction ->
                player?.let { p ->
                    p.duration.takeIf { it > 0 }?.let { p.seekTo((it * fraction).toLong()) }
                }
                hint.visibility = View.GONE
            }
            return PageHolder(page)
        }

        override fun getItemCount(): Int = if (items.isEmpty()) 1 else items.size

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            val page = holder.itemView as PhoneVideoPage
            if (items.isEmpty()) {
                page.bind(Series(id = "", title = if (loading) "加载中…" else "暂无内容"), "")
                return
            }
            val series = items[position]
            val label = "第${(currentEpisodeIndex[position] ?: 0) + 1}集"
            page.bind(series, label)
            // 重新绑定之后，如果这一屏正是正在播的那一屏，把播放器接回来。
            // 不接的话：列表因尺寸变化重绑 → 新的 ViewHolder 上没有 Surface → 画面黑掉。
            if (position == playingIndex) list.post { rebindCurrentPage() }
        }
    }

    /** 进播放页。先停掉这一条流，否则两边声音叠在一起。 */
    private fun openPlayer(page: PhoneVideoPage, fullscreen: Boolean) {
        val series = page.currentSeries() ?: return
        val index = list.getChildAdapterPosition(page).takeIf { it >= 0 } ?: playingIndex
        launchPlayer(
            series = series,
            episode = liveEpisodeOf(series, index),
            fullscreen = fullscreen,
            // 「观看完整短剧」是明确的「我要接着看」：即使用户刚在信息流里暂停过也起播。
            play = true,
        )
    }

    /**
     * 起播放页之前先把本页控制层收起。
     *
     * 播放页的窗口是透明的（见 `PlayerTheme`）：晚收的那几十毫秒里，本页这套
     * 「选集 全N集｜第x集」会从它的透明底板下透出来，紧接着又被播放页自己那份
     * 「观看完整短剧」盖掉——用户读到的是按钮文字闪了一下。
     *
     * 只在播放页**真的会借这块 Surface** 时才收（9.0+ 且这一路还在）：更早的系统上
     * 播放页窗口不透明，下层根本看不见，收了只是多一次无谓的视图切换。
     */
    private fun hideChromeForHandoff(series: Series) {
        if (android.os.Build.VERSION.SDK_INT < 28) return
        if (!PhoneSession.feedUnderlayUsable(series.id)) return
        PhoneSession.hideFeedChrome()
    }

    /**
     * 打开正式播放页，并把这一路**原样交出去**。
     *
     * 三件事缺一不可（缺一件用户就会看到「内容断了一下」）：
     * 1. `beginHandoff()` + `onHidden(handoff = true)`：交接窗口内不暂停播放器，
     *    媒体源与解码器都在跑，播放页只把 Surface 接过去；
     * 2. 把「进来要不要播」写进会话（`resumeOnAdopt`），播放页接手时照它决定；
     * 3. 不要系统转场动画：那 200ms 里新窗口还没画出视频，读起来就是一次黑屏。
     */
    private fun launchPlayer(series: Series, episode: Int, fullscreen: Boolean, play: Boolean) {
        PhoneSession.resumeOnAdopt = play
        PhoneSession.beginHandoff()
        // 交接一开始就把这一路记在播放页名下：`PhoneMainActivity.onStop` 与
        // `onHidden` 都按 `owner` 判断「这一路是不是正被别人接手」，仍记成信息流
        // 的话它们会在播放页刚起来的几十毫秒里把它按停——用户读到一次断音。
        PhoneSession.takeOver(PhoneSession.Owner.PLAYER)
        onHidden(handoff = true)
        hideChromeForHandoff(series)
        context.phoneNav().open(
            Route.Player(
                series.id,
                series.title,
                episode = episode,
                fromRecommend = true,
                fullscreen = fullscreen,
                noTransition = true,
            )
        )
        (context as? android.app.Activity)?.overridePendingTransition(0, 0)
    }

    /**
     * 「这一屏现在放到第几集」。
     *
     * 优先用**进程级会话**里的集号：它才是播放器上真正挂着的那一集。
     * 本地 map 只是「这一屏显示过第几集」，在片尾交接、播放页里换过集、
     * 或这一屏还没起播时都可能落后。
     *
     * 传错的后果很具体：播放页会判「要的集号与会话里的不一致」→ 拒绝接手 →
     * 重新取详情、重新取地址、从头起播，用户看到的就是「切过去内容断了」。
     */
    private fun liveEpisodeOf(series: Series, index: Int): Int {
        // 判据用 `live`（不看地址新鲜度）：地址过期只影响后续 Range 请求，播放页接手时
        // 会原地换一条地址、位置不动。用 `adoptable` 的话，停久了再点进去传的集号会退回
        // 本页记的旧值 → 播放页判「集号对不上」→ 重新取详情、从 0 起播。
        if (PhoneSession.live(series.id) && PhoneSession.index >= 0) return PhoneSession.index
        return currentEpisodeIndex[index] ?: 0
    }

    private class PageHolder(view: View) : RecyclerView.ViewHolder(view)

    /** 离开页面时释放协程；播放器留给进程（下次进来重建，省一次解码器初始化）。 */
    fun release() {
        chromeEpoch++
        list.removeCallbacks(chromeHideRunnable)
        listener?.let { player?.removeListener(it) }
        listener = null
        preloadScheduler.cancel()
        PhonePreload.clear()
        scope.cancel()
        runCatching { player?.release() }
        player = null
        runCatching { remote?.close() }
        remote = null
    }
}
