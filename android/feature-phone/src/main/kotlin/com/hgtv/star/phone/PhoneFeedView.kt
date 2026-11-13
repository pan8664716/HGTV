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
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.video.VideoSize
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hgtv.star.core.CategorySelection
import com.hgtv.star.core.Detail
import com.hgtv.star.core.PlaybackQuality
import com.hgtv.star.core.PlaybackSpeed
import com.hgtv.star.core.RemoteVideo
import com.hgtv.star.core.StreamInfo
import com.hgtv.star.core.Series
import com.hgtv.star.core.RecommendTab
import com.hgtv.star.phone.ui.PhoneColor
import com.hgtv.star.phone.ui.PhonePagerSnapHelper
import com.hgtv.star.phone.ui.PhoneSheet
import com.hgtv.star.phone.ui.PhoneTouch
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.phoneSize
import com.hgtv.star.phone.ui.primeAspectRatio
import com.hgtv.star.phone.ui.resizeMode
import com.hgtv.star.phone.ui.roundedBackground
import com.hgtv.star.phone.ui.runAfterAspectRatioLayout
import com.hgtv.star.shared.architecture.PlaybackSourceCoordinator
import com.hgtv.star.shared.data.Library
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
    internal val feedScope get()=scope
    private val playbackSource by lazy { PlaybackSourceCoordinator(PhoneServices.repository(context)) }
    internal val feedPlaybackSource get()=playbackSource
    private val items get()=recommendStore.items
    internal val feedItems get()=items
    private val adapter = PhoneFeedAdapter(this)
    private val list = VerticalPagerList(context)
    internal val feedList get()=list

    private var offset: Int
        get()=recommendStore.nextOffset
        set(value) { recommendStore.nextOffset=value }
    private var sessionId: String
        get()=recommendStore.sessionId
        set(value) { recommendStore.sessionId=value }
    private var loading: Boolean
        get()=recommendStore.loading
        set(value) { recommendStore.loading=value }
    internal val feedLoading get()=loading
    private var ended: Boolean
        get()=!recommendStore.hasMore
        set(value) { recommendStore.hasMore=!value }
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
    internal val feedHidden get()=hidden
    internal var feedHiddenSlot: Boolean
        get()=hidden
        set(value) { hidden=value }

    /** 当前这一屏播放信息的自动收起截止时刻与代次。 */
    private var chromeHideAt = 0L
    internal var feedChromeHideAt: Long
        get()=chromeHideAt
        set(value) { chromeHideAt=value }
    private var chromeEpoch = 0
    internal var feedChromeEpoch: Int
        get()=chromeEpoch
        set(value) { chromeEpoch=value }

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
    internal val feedChromeHideRunnable get()=chromeHideRunnable

    private var player: ExoPlayer? = null
    internal val feedPlayer get()=player
    internal var feedPlayerSlot: ExoPlayer?
        get()=player
        set(value) { player=value }
    private var remote: RemoteVideo? = null
    internal var feedRemoteSlot: RemoteVideo?
        get()=remote
        set(value) { remote=value }
    private var playingIndex = -1
    internal var feedPlayingIndex: Int
        get()=playingIndex
        set(value) { playingIndex=value }
    /** 当前这一屏的推荐观看计时；与电视端共用 [RecommendBehavior] 的阈值。 */
    private var recommendWatchingId = ""
    private var recommendWatchStart = 0L
    internal var feedRecommendWatchingId: String
        get()=recommendWatchingId
        set(value) { recommendWatchingId=value }
    internal var feedRecommendWatchStart: Long
        get()=recommendWatchStart
        set(value) { recommendWatchStart=value }
    private var playToken = 0
    internal val feedPlayToken get()=playToken
    internal var feedPlayTokenSlot: Int
        get()=playToken
        set(value) { playToken=value }
    private var boundPage: PhoneVideoPage? = null
    internal val feedBoundPage get()=boundPage
    internal var feedBoundPageSlot: PhoneVideoPage?
        get()=boundPage
        set(value) { boundPage=value }
    private var paused = false
    internal var feedPaused: Boolean
        get()=paused
        set(value) { paused=value }
    /** 播放意图变化（起播 / 暂停 / 收起）：宿主据此同步屏幕常亮。 */
    var onPlayStateChanged: (() -> Unit)? = null
    /** 正在播那一屏的详情：片尾自动进正片要用它取「还有没有下一集」。 */
    private var playingDetail: Detail? = null
    internal val feedPlayingDetail get()=playingDetail
    internal var feedPlayingDetailSlot: Detail?
        get()=playingDetail
        set(value) { playingDetail=value }
    /** 已经因为片尾切去正片：防止 STATE_ENDED 重入时再开一个 Activity。 */
    private var advancing = false
    /** 上一次进入「交接 / 换下一部」的时刻：`advancing` 超时后自动失效（见 `onEpisodeEnded`）。 */
    private var advancingAt = 0L
    internal var feedAdvancing: Boolean
        get()=advancing
        set(value) { advancing=value }
    internal var feedAdvancingAt: Long
        get()=advancingAt
        set(value) { advancingAt=value }
    /** 推荐流的体裁筛选（`RankBoard.genre`）；null = 全部混排。 */
    private var genre: String? = initialGenre
    internal var feedGenre: String?
        get()=genre
        set(value) { genre=value }
    /**
     * 落盘快照 / 续流游标的命名空间：三条体裁流各存各的（见 `RecommendCache.fileOf`）。
     *
     * `heat-v1-` 是当前缓存格式版本：切回分类接口热度推荐后，
     * 官方书包流留下的快照与游标不能继续铺到首屏，换前缀后自然失效。
     */
    private fun cacheKey(): String = "heat-v1-" + genre.orEmpty().ifEmpty { "all" }
    internal fun feedCacheKey(): String = cacheKey()

    private var recommendStore=newRecommendStore()
    private var recommendPlayback=newRecommendPlayback()
    internal val feedRecommendStore get()=recommendStore
    internal val feedRecommendPlayback get()=recommendPlayback

    private fun newRecommendPlayback()=com.hgtv.star.shared.feature.recommend.RecommendPlaybackController(
        repository=PhoneServices.repository(context),
        library=PhoneServices.library(context),
        source=playbackSource,
        store=recommendStore,
    )

    private fun newRecommendStore()=com.hgtv.star.shared.feed.RecommendStore(
        repository=PhoneServices.repository(context),
        library=PhoneServices.library(context),
        policy=com.hgtv.star.shared.feed.RecommendFeedPolicy.standard(
            cacheNamespace=cacheKey(),
        ),
        debug={ line ->
            if(com.hgtv.star.shared.BuildConfig.DEBUG) {
                android.util.Log.i("HGTV-PHONE","feed $line")
            }
        },
    )

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
    internal fun feedSessionBelongsToThisFeed(): Boolean = sessionBelongsToThisFeed()
    internal fun feedNotifyDataSetChanged() = adapter.notifyDataSetChanged()
    internal fun feedNotifyItemRangeInserted(start: Int, count: Int) =
        adapter.notifyItemRangeInserted(start, count)
    internal fun feedScrollToPosition(position: Int) = list.scrollToPosition(position)
    internal fun feedPostScrollToPosition(position: Int) = list.post { list.scrollToPosition(position) }
    internal fun feedPostDelayed(runnable: Runnable, delayMs: Long) =
        list.postDelayed(runnable, delayMs)
    internal fun feedRemoveCallbacks(runnable: Runnable) = list.removeCallbacks(runnable)
    internal fun feedSmoothScrollToPosition(position: Int) =
        list.smoothScrollToPosition(position)
    internal fun revealChromeForAdapter(page: PhoneVideoPage) = revealChrome(page)
    internal fun togglePlayForAdapter() = togglePlay()
    internal fun openSettingsForAdapter() = openSettings()
    internal fun beginTempSpeedForAdapter() = beginTempSpeed()
    internal fun endTempSpeedForAdapter() = endTempSpeed()
    internal fun launchPlayerForAdapter(
        series: Series,
        episode: Int,
        fullscreen: Boolean,
        play: Boolean,
    ) = launchPlayer(series, episode, fullscreen, play)
    internal fun openPlayerForAdapter(page: PhoneVideoPage) =
        openPlayer(page, fullscreen = false)
    internal fun liveEpisodeOfForAdapter(series: Series, index: Int) =
        liveEpisodeOf(series, index)
    internal fun clockForAdapter(ms: Long) = clock(ms)
    internal fun rebindCurrentPageForAdapter() = rebindCurrentPage()
    internal fun revealChromeForSettings() = revealChrome()
    internal fun playIndexForSettings(index: Int, startAt: Long) =
        playIndex(index, startAt)
    internal fun applyDanmakuSettingsForSettings(page: PhoneVideoPage) =
        applyDanmakuSettingsImpl(page)
    internal fun loadDanmakuForSettings(
        page: PhoneVideoPage,
        index: Int,
        episodeId: String,
        seriesId: String,
    ) = loadDanmakuImpl(page, index, episodeId, seriesId)

    /**
     * 服务端已经确认不吃 `filter_ids`（回空页）。
     *
     * 与电视端 `Library.recommendFilterIdsOff` 是**同一个落盘值**：在电视上确认过不行的设备，
     * 换到手机端也不必再撞两次。
     */
    private var filterIdsOff: Boolean
        get()=recommendStore.filterIdsOff
        set(value) { recommendStore.filterIdsOff=value }
    /** 连续「带了 `filter_ids` 却回空页」的次数：到 2 就永久关掉这一层。 */
    private var filterMiss: Int
        get()=recommendStore.filterMiss
        set(value) { recommendStore.filterMiss=value }
    /**
     * 「推荐受众」（男频 / 女频）这一层的降级状态（`:core` 的 `OptionalFilter`）。
     *
     * 实测服务端对某些受众会整页回空（连流头都空）。带它拿到空页就这一次不带重取，
     * 连续两次仍空则本进程内不再带——与电视端同一套语义，用户至少还能刷到内容。
     */
    private val genderFilter get()=recommendStore.gender
    /** 「下一次请求先别带受众」：只是这一次退让，不等于永久关闭。 */
    private var genderProbe: Boolean
        get()=recommendStore.genderProbe
        set(value) { recommendStore.genderProbe=value }
    /** 这一次进页面是否已经从落盘恢复过续流游标（每个体裁各一份）。 */
    private var cursorRestored = false
    internal var feedCursorRestored: Boolean
        get()=cursorRestored
        set(value) { cursorRestored=value }
    /** 首屏不等注册时，注册完成前发出的空请求要在结束后补拉一次。 */
    private var identityRefreshPending = false
    internal var feedIdentityRefreshPending: Boolean
        get()=identityRefreshPending
        set(value) { identityRefreshPending=value }
    private var progressTick: Runnable? = null
    internal var feedProgressTick: Runnable?
        get()=progressTick
        set(value) { progressTick=value }
    private var tempSpeed = false
    private var userSpeed = 1f
    internal var feedTempSpeed: Boolean
        get()=tempSpeed
        set(value) { tempSpeed=value }
    internal var feedUserSpeed: Float
        get()=userSpeed
        set(value) { userSpeed=value }
    private var library: Library? = null
    internal val feedLibrary get()=library
    internal var feedLibrarySlot: Library?
        get()=library
        set(value) { library=value }
    /** 进程单例播放器上的监听器：换屏/换集前先摘掉，否则会越积越多。 */
    private var listener: Player.Listener? = null
    internal var feedListener: Player.Listener?
        get()=listener
        set(value) { listener=value }

    /**
     * 推荐流里连续几部放不了（与电视端 `recommendPlaybackFailures` 同一语义、同一上限）。
     *
     * 推荐页没有选集、也没有详情页可回，所以**不摆播放页那套错误界面**：
     * 放不了就换下一部。连换几部都不行才停在一句提示上，由用户自己上滑。
     * 计数只在「真的出画面」时归零（取地址成功不算）——见 [notePlaybackHealthy]。
     */
    private var recommendFailures = 0
    internal var feedRecommendFailures: Int
        get()=recommendFailures
        set(value) { recommendFailures=value }

    /** 每一屏当前在播的集（续播时可能不是第 1 集）。 */
    private val currentEpisodeIds = HashMap<Int, String>()
    private val currentEpisodeIndex = HashMap<Int, Int>()
    internal val feedCurrentEpisodeIds get()=currentEpisodeIds
    internal val feedCurrentEpisodeIndex get()=currentEpisodeIndex
    /** 推荐页起播后的 6 个模型批量预取（当前剧下一集 + 后 5 部不同剧）。 */
    private var recommendBatchKey = ""
    private var recommendBatchJob: Job? = null
    internal var feedRecommendBatchKey: String
        get()=recommendBatchKey
        set(value) { recommendBatchKey=value }
    internal var feedRecommendBatchJob: Job?
        get()=recommendBatchJob
        set(value) { recommendBatchJob=value }

    private val hint = TextView(context).apply {
        setTextColor(Color.WHITE)
        phoneSize(13)
        gravity = Gravity.CENTER
        setPadding(context.dp(14), context.dp(7), context.dp(14), context.dp(7))
        background = roundedBackground(0x88000000.toInt(), context.dp(14).toFloat())
        visibility = View.GONE
    }
    internal val feedHint get()=hint

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
    internal val feedSheet get()=sheet

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
                if (index >= 0 && index != playingIndex) {
                    playIndex(index, clearDanmakuOnSwitch = true)
                }
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
     * 换类等于换一条分类推荐流：`null` 是全部体裁混排，漫剧 / 真人剧 / AI剧
     * 分别对应分类接口的 `comic_series` / `short_play` / `ai_series`。
     * 换类必须从头取，游标与快照也按类分开。
     */
    fun setGenre(value: String?) {
        if (value == genre) return
        genre = value
        recommendStore=newRecommendStore()
        recommendPlayback=newRecommendPlayback()
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
        if (!resumeOnShown()) return
        // 设置页可能刚改过弹幕（显示方式 / 速度 / 字号 / 透明度）：回到这一页时重刷一次，
        // 否则要等下一次换集才看得到，用户读到的就是「弹幕设置不生效」。
        pageAt(playingIndex)?.let { applyDanmakuSettings(it) }
        // 这一路接完（集号 / 进度条都已经对齐）**之后**才把控制层还回来。
        // 顺序反了就是用户读到的「退回来内容断了一下」：控制层先亮，标题、集号、
        // 进度条还是离开前的那一份，过一拍才跳到正确的位置。
        PhoneSession.ensureFeedChrome()
        revealChrome()
    }

    /** 主设备注册完成：有缓存就不动，只有空列表才用新身份重取。 */
    fun onIdentityReady() {
        identityRefreshPending = true
        if(!loading) refreshAfterIdentity()
    }

    /** 当前这一条流是否真的在播：宿主用它决定窗口要不要保持常亮。 */
    fun isPlayingNow(): Boolean {
        return isPlayingNowImpl()
    }

    private fun notifyPlayStateChanged() {
        notifyPlayStateChangedImpl()
    }
    internal fun feedNotifyPlayStateChanged() = notifyPlayStateChanged()

    private fun currentChromePage(): PhoneVideoPage? = currentChromePageImpl()

    /**
     * 当前这一屏的播放信息按设置里的秒数自动收起。
     *
     * 推荐页与正式播放页共用同一个 `playerPanelTimeout`。到点时先收设置面板，
     * 再收起标题、进度条和底部操作栏；下一次触摸只会把信息叫回来，不会顺手暂停。
     */
    private fun scheduleChromeHide(page: PhoneVideoPage? = currentChromePage()) {
        scheduleChromeHideImpl(page)
    }

    private fun chromeTimeoutMs(): Long = chromeTimeoutMsImpl()

    private fun revealChrome(page: PhoneVideoPage? = currentChromePage()) {
        revealChromeImpl(page)
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
        scrollToTopImpl()
    }

    private fun resumeOnShown(): Boolean {
        return resumeOnShownImpl()
    }

    /**
     * 播放页已经不在了（此刻前台是本页），它留下的那一路却接不上（地址过期、
     * 或者它放的那一部根本不在当前这条流里）。
     *
     * 这时必须**先按停**：不按的话，画面会切到本页这一条，声音还是播放页那一条，
     * 用户听到的就是「退回推荐页后声音和画面对不上」。接得上时不会走到这里。
     */
    private fun stopOrphanPlayback() {
        stopOrphanPlaybackImpl()
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
    private fun adoptPlaying(
        forceRebind: Boolean = false,
        clearDanmakuOnSwitch: Boolean = false,
    ): Boolean = adoptPlayingImpl(forceRebind, clearDanmakuOnSwitch)
    internal fun adoptPlayingForResume(forceRebind: Boolean): Boolean =
        adoptPlaying(forceRebind = forceRebind)

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
        return reopenAtCurrentPositionImpl(index)
    }
    internal fun reopenAtCurrentPositionForResume(index: Int): Boolean =
        reopenAtCurrentPosition(index)

    /**
     * 接上之后兜一次「真的在走」。
     *
     * 只处理一种情况：播放器 READY、`playWhenReady` 为 true、却没有在播——
     * 这是 Surface 换宿主后厂商 ROM 上的已知表现（解码器已经就绪，播放状态没被推起来）。
     * 这里补一次 `play()`；如果本页的 Surface 已经不是输出端了，再挂回去。
     * **不重设媒体源、不 seek**：补的是播放状态，不是内容。
     */
    private fun keepLivePlayback() {
        keepLivePlaybackImpl()
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
        clearDanmakuOnSwitch: Boolean = false,
        videoInfo: StreamInfo? = null,
    ) {
        attachPageImpl(
            exo,
            page,
            series,
            episodeIndex,
            forceRebind,
            clearDanmakuOnSwitch,
            videoInfo,
        )
    }
    internal fun attachPageForSurface(
        exo: ExoPlayer,
        page: PhoneVideoPage,
        series: Series,
        episodeIndex: Int,
        forceRebind: Boolean,
        clearDanmakuOnSwitch: Boolean = false,
    ) = attachPage(exo, page, series, episodeIndex, forceRebind, clearDanmakuOnSwitch)

    /**
     * 把「画面模式」「画面缩放」按设置落到这一页上。
     *
     * 与播放页同一条规则：按用户选的 FIT / ZOOM / FILL 走，不再因为片源是竖屏
     * 就强制铺满。`bind` 之后必须补一次，否则会回到默认的完整画面。
     */
    private fun applyPageVideoSettings(page: PhoneVideoPage) {
        applyPageVideoSettingsImpl(page)
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
        onHiddenImpl(handoff)
    }

    /**
     * 把当前这一页的视频 Surface 借给正式播放页（竖屏），或者收回来。
     *
     * 借出去时本页只留画面：信息栏、弹幕、渐变遮罩全部收起——播放页会画一份
     * 一模一样（但集号更准）的控制层，两层叠着会看到两套标题与两条进度条。
     */
    fun setUnderlayMode(on: Boolean) {
        setUnderlayModeImpl(on)
    }

    /**
     * 登记「把播放器重新挂回本页 Surface」的入口。
     *
     * 播放页竖屏不挂自己的视频层（见 `PhoneVideoPage` 的 `underlay`），从横屏退回
     * 竖屏时它把横屏那一层摘掉，然后调这里把输出交回来——不交就是一块黑。
     */
    private fun bindFeedSurfaceForHandoff() {
        bindFeedSurfaceForHandoffImpl()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        handleFeedSizeChanged(w, h)
        // **不重建整屏**。一页就是一个屏幕高，换成 `MATCH_PARENT` 之后尺寸变化由
        // RecyclerView 自己重新测量，ViewHolder 与它上面那块 Surface 都不动——
        // 这一条正是「进 / 退播放页画面不断」的前提：
        // 播放页横屏时本页会跟着转一次屏（onSizeChanged），早先这里 `notifyDataSetChanged()`
        // 会把整屏（连同正借给播放页出画面的那块 Surface）全部回收重建，
        // 播放页手里的 Surface 就此没了，用户看到的是「一切横屏就黑一下」。
        //
        // 尺寸变了之后只要**重新对齐**当前这一页：像素滚动位置在换高度后不再是一页的整数倍，
        // 不对齐就会停在半页上。
    }

    /**
     * 把进程级播放器重新挂到「现在应该显示这一集」的那张页上。
     *
     * 只在会话仍属于信息流时做：属于播放页时播放器归播放页，抢过来会把那边的画面弄黑。
     * 布局变化（转屏、键盘、分屏）与列表重新绑定之后都要调一次。
     */
    private fun rebindCurrentPage() {
        rebindCurrentPageImpl()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        onFeedDetachedImpl()
        // 这一路归播放页时更不能停：窗口过期后转横屏，本页视图会被摘下，
        // 那时按一下就把用户正在看的画面停住了。
        if (!PhoneSession.inHandoff() && PhoneSession.owner == PhoneSession.Owner.FEED &&
            sessionBelongsToThisFeed()
        ) {
            player?.pause()
        }
    }

    // ------------------------------------------------------------------ 数据
    internal fun feedPlayIndex(index: Int, startAt: Long = -1L, clearDanmakuOnSwitch: Boolean = false) =
        playIndex(index, startAt, clearDanmakuOnSwitch)

    // ------------------------------------------------------------------ 播放

    /** [startAt] >= 0 时从该毫秒继续播（换清晰度重取地址时用），否则按本机进度续播。 */
    private fun playIndex(
        index: Int,
        startAt: Long = -1L,
        clearDanmakuOnSwitch: Boolean = false,
    ) {
        val series = items.getOrNull(index) ?: return
        val previousIndex = playingIndex
        PhoneSession.rememberRecommendSeries(series,cacheKey())
        if (playingIndex >= 0 && playingIndex != index) settleRecommendWatch()
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
        if (clearDanmakuOnSwitch && previousIndex >= 0 && previousIndex != index) {
            danmakuLoaderOfImpl(previousIndex).clear()
        }
        // 记下落盘的「停在哪一部」：下次冷启动从它的**下一部**接着播（同电视端 `rememberPlayed`）。
        runCatching { com.hgtv.star.core.RecommendCache.rememberPlayed(series.id, cacheKey()) }
        if (recommendWatchingId != series.id) {
            recommendWatchingId = series.id
            recommendWatchStart = SystemClock.elapsedRealtime()
        }
        // 「真的上屏了」才记进曝光窗口（与电视端 `playRecommendAt → markRecommended` 同一条规则）：
        // 一页 20 条里用户可能只看第一条，把整页都记成已推等于白白烧掉 19 部内容。
        recommendPlayback.rememberShown(series)
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
        feedPendingPlan = feedPreloadState.slideCache.onItemChanged(
            index,
            direction,
            android.os.SystemClock.elapsedRealtime(),
        )
        scheduleDwell()
        // 进程里如果还挂着**这部剧**的这一路（从正片退回来、视图刚被重建），先试着接。
        // 接上就什么都不做：不取详情、不取地址、不 prepare、不 seek——画面就是原来那一秒。
        if (startAt < 0 && adoptPlaying(clearDanmakuOnSwitch = clearDanmakuOnSwitch)) {
            PhoneSession.endHandoff()
            schedulePreload(feedFirstFrameDelay())
            return
        }
        val token = ++playToken
        scope.launch {
            val lib = library ?: PhoneServices.awaitLibrary(context).also { library = it }
            val prepared = withContext(Dispatchers.IO) {
                runCatching {
                    recommendPlayback.prepare(
                        series = series,
                        maxQuality = lib.maxQuality,
                        startAt = startAt,
                        resumeSaved = true,
                        takeWarm = { episodeId, quality ->
                            PhonePreload.take(series.id, episodeId, quality)
                        },
                    )
                }.getOrNull()
            }
            if (token != playToken) {
                prepared?.remote?.close()
                return@launch
            }
            if (prepared == null) {
                recommendPlayback.noteFailure()
                recommendPlayback.reportEvent(
                    series,
                    com.hgtv.star.core.RecommendBehavior.Event.ERROR,
                    episodeId=currentEpisodeIds[index].orEmpty(),
                    episodeIndex=currentEpisodeIndex[index] ?: -1,
                    reason="prepare_failed",
                )
                return@launch
            }
            playingDetail = prepared.detail
            currentEpisodeIds[index] = prepared.episodeId
            currentEpisodeIndex[index] = prepared.episodeIndex
            attach(index, prepared.remote, prepared.position, clearDanmakuOnSwitch)
        }
        // 首帧之后再排预热（与电视端「首帧 + 2 秒」同一条：此刻解码器还在出帧、buffer 还在填）。
        schedulePreload(feedFirstFrameDelay())
    }

    internal fun settleRecommendWatchForNavigation() = settleRecommendWatch()
    internal fun feedChildAdapterPosition(page: PhoneVideoPage): Int =
        list.getChildAdapterPosition(page)

    // ------------------------------------------------------------------ 相邻预热

    /** 与电视端 `SlideCacheManager` 同一份窗口 / 快滑 / 回看规则。 */
    internal val feedPreloadState=PhoneFeedPreloadState(this)
    internal val feedPreloadScheduler get()=feedPreloadState.scheduler
    internal var feedPendingPlan: com.hgtv.star.core.SlideCacheManager.Plan?
        get()=feedPreloadState.pendingPlan
        set(value) { feedPreloadState.pendingPlan=value }
    internal fun feedFirstFrameDelay(): Long = feedPreloadState.slideCache.firstFrameDelay()

    /** 每 500ms 把播放进度回传给页面，画在底部进度条上。 */
    /** 片尾预热窗口：与 TV 的 `PrefetchPolicy.nearEnd` 同一档（30 秒）。 */
    private val prefetchWindowMs = 30_000L
    internal val feedPrefetchWindowMs get()=prefetchWindowMs

    /** 推荐流里连续几部放不了就不再自动翻（与电视端 `RECOMMEND_SKIP_LIMIT` 同值）。 */
    private val RECOMMEND_SKIP_LIMIT = 4
    internal val feedRecommendSkipLimit get()=RECOMMEND_SKIP_LIMIT

    /**
     * `advancing` 的有效期（毫秒）。
     *
     * 它只是「这一集的片尾已经处理过」的防重入标记。交接一旦没走完（播放页没起来、
     * 用户中途返回、跳转被系统拦掉），标志会一直卡着，之后每一集都不再连播。
     * 超过这个时间就当作失效，允许重新推进。
     */
    private val ADVANCE_GUARD_MS = 5_000L
    internal val feedAdvanceGuardMs get()=ADVANCE_GUARD_MS
    /** 正在预热的那一集（防重入）。 */
    private var prefetchingFor = ""
    internal var feedPrefetchingFor: String
        get()=prefetchingFor
        set(value) { prefetchingFor=value }
    /** 上一次允许发起预热的时间（避免每 500ms 重试一次）。 */
    private var prefetchAt = 0L
    internal var feedPrefetchAt: Long
        get()=prefetchAt
        set(value) { prefetchAt=value }

    // —— 弹幕（与正式播放页、电视端共用同一份加载内核与同一个 DanmakuView）——
    /**
     * 每一屏一份加载器（`:shared` 的 [com.hgtv.star.shared.media.DanmakuLoader]）。
     *
     * 为什么按屏分：信息流的每一屏是**独立的一集**，各拉各的弹幕；共用一份的话，
     * 滑走再滑回来会重新拉一遍（那一份记忆属于上一屏）。
     */
    private val danmakuLoaders = HashMap<Int, com.hgtv.star.shared.media.DanmakuLoader>()
    internal val feedDanmakuLoaders get()=danmakuLoaders

    /** 取（或建）某一屏的加载器：目标层与集号都按那一屏现取。 */
    internal fun feedDanmakuLoaderOf(index: Int) = danmakuLoaderOfImpl(index)

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
        maybePrefetchNextImpl(position, duration)
    }
    internal fun maybePrefetchNextForClock(position: Long, duration: Long) =
        maybePrefetchNext(position, duration)

    private fun applyDanmakuSettings(page: PhoneVideoPage) =
        applyDanmakuSettingsImpl(page)

    private fun loadDanmaku(
        page: PhoneVideoPage,
        index: Int,
        episodeId: String,
        seriesId: String,
        clearOnSwitch: Boolean = false,
    ) = loadDanmakuImpl(page, index, episodeId, seriesId, clearOnSwitch)

    private fun attach(
        index: Int,
        video: RemoteVideo,
        startAt: Long = 0L,
        clearDanmakuOnSwitch: Boolean = false,
    ) {
        attachImpl(index, video, startAt, clearDanmakuOnSwitch)
    }

    private fun pageAt(index: Int): PhoneVideoPage? {
        return pageAtImpl(index)
    }
    internal fun feedPageAt(index: Int): PhoneVideoPage? = pageAt(index)

    /**
     * 一集播完：落 completed 进度 → 进正式播放页播下一集（最后一集则换下一部）。
     *
     * 判据与 TV 一致：
     * * 只在信息流是**前台且在播**时触发（`paused` / 已经切走过就不算）；
     * * 只在**真的播到结尾**时推进（`STATE_ENDED` 本身就是这个含义，但排队故障也可能把它
     *   提前抛出来，所以再核一次播放位置）；
     * * 同一集只推进一次（`advancing` 防重入）。
     */
    /** 位置读数：与电视端 `formatTime` 同一份（`:core` 的 `PlaybackInfoLine.time`）。 */
    private fun clock(ms: Long): String = clockImpl(ms)

    // ------------------------------------------------------------------ 播放设置

    /**
     * 长按中间 1/3：播放设置。
     *
     * **与正式播放页完全同一份面板**（[buildPlaybackSettingsRows] 的各行），
     * 早先这里只做了倍速 / 清晰度 / 画面模式三项，用户想调字号、透明度时找不到那一行。
     */
    private fun openSettings() {
        openSettingsImpl()
    }

    /** 离开页面时释放协程；播放器留给进程（下次进来重建，省一次解码器初始化）。 */
    fun release() {
        releaseImpl()
    }
}
