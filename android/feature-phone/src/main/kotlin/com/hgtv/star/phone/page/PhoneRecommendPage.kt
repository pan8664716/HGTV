// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone.page

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.hgtv.star.core.RecommendGenre
import com.hgtv.star.phone.PhoneFeedView
import com.hgtv.star.phone.phoneNav
import com.hgtv.star.shared.nav.Route
import com.hgtv.star.phone.ui.PhoneColor
import com.hgtv.star.phone.ui.PhoneTabSwipeHost
import com.hgtv.star.phone.ui.PhoneTopBar
import com.hgtv.star.phone.ui.navigationBarPx

/**
 * 推荐页：四条件裁横向滑动，每页一条独立的竖屏视频流。
 *
 * ```
 * 推荐  ←→  漫剧  ←→  AI剧  ←→  真人  （顶部左对齐，与分类 / 榜单一致）
 *        [全屏视频流，一屏一集]        （右上角：搜索）
 * ```
 *
 * ## 为什么用横向 pager 而不是点 tab 换流
 *
 * 推荐与三个体裁是并列的四条流，用户对它们的预期是「左右翻」而不是「点一下重载」。
 * 换 tab 时旧流的状态（滚到第几集）会被丢掉；pager 保留每一页，
 * 翻回来还在原来那一屏。
 *
 * ## 播放器只有当前页在跑
 *
 * 每个体裁页各持一个 `PhoneFeedView`，但只有当前页在播：翻页时旧页 `onHidden()`
 *（暂停解码），新页 `onShown()`。相邻页提前创建，但不会同时起播。
 */
class PhoneRecommendPage(
    context: Context,
    initialGenre: String? = null,
    initialSeriesId: String? = null,
    initialPositionMs: Long = -1L,
) : FrameLayout(context) {

    /**
     * 这一屏要铺哪几条体裁流。
     *
     * 由设置页的「推荐范围」（`Library.recommendGenreOff`）决定：关掉的体裁不再建页，
     * 顶部那枚入口也不出现——与 TV 的 `recommendFeed(genres=…)` 同一份落盘值。
     * 至少留一条：`Library` 那边保证不会三类全关，这里再兜一次底（空表就全开）。
     */
    private var genres: List<RecommendGenre> = listOf(RecommendGenre.RECOMMEND) + RecommendGenre.typed
    /** 宿主重建页面时带回来的体裁；首次铺完后清空。 */
    private var restoreGenre: String? = initialGenre
    /** 宿主重建页面时带回来的当前剧；只交给当前体裁流，避免误恢复相邻流。 */
    private var restoreSeriesId: String? = initialSeriesId
    /** 宿主重建页面时带回来的播放位置；只用于需要重新 prepare 的恢复路径。 */
    private var restorePositionMs: Long = initialPositionMs
    /** 已经铺出来的那一次筛选结果：设置变了就整屏重建一次。 */
    private var appliedGenres: List<RecommendGenre> = emptyList()
    private var feeds = arrayOfNulls<PhoneFeedView>(genres.size)
    private val adapter = PagerAdapter()
    private val pager = RecommendGenrePager(context)
    /** 与分类 / 榜单共用同一个顶栏：tab 起点、字号和搜索按钮位置因此完全一致。 */
    private val topBar = PhoneTopBar(context).apply {
        makeOverlay()
        // 推荐页是满屏视频，状态栏由宿主藏起来（`PhoneMainActivity.applyStatusBarFor`），
        // 顶栏因此不再为状态栏留白。
        setImmersive(true)
    }
    private var feedDanmakuTopInset = 0
    private val topBarLayoutListener =
        View.OnLayoutChangeListener { _, _, _, _, bottom, _, _, _, _ ->
            syncFeedDanmakuTopInset(bottom)
        }
    private var current = 0
    /**
     * 设置页「页面左右滑动」是否开启（`Library.recommendSwipe`）。
     *
     * 关掉时整条流的横向手势都交还给视频层，只能点顶部 tab 换流。与
     * [sheetOpen] 是两个独立来源：面板开着只是**临时**锁住，设置关掉是**一直**锁住。
     */
    private var swipeEnabled = true
    /** 播放设置面板是否开着（开着时临时锁住横向翻页，见 `PhoneFeedView.onSheetVisibilityChanged`）。 */
    private var sheetOpen = false
    /** 当前体裁流的播放意图变化：宿主据此同步窗口常亮。 */
    var onPlayStateChanged: (() -> Unit)? = null
    private val pageCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageScrollStateChanged(state: Int) {
            if (state != ViewPager2.SCROLL_STATE_IDLE) return
            if (!pager.userScroll) return
            pager.userScroll = false
            val index = pager.currentItem
            if (index != current) switchTo(index)
        }
    }

    init {
        setBackgroundColor(PhoneColor.BG)
        genres = enabledGenres()
        swipeEnabled = swipeEnabledFromSettings()
        topBar.addOnLayoutChangeListener(topBarLayoutListener)
        pager.registerOnPageChangeCallback(pageCallback)
        buildUi()
        // 冷启动时书库还没读完，上面那一次读到的是兜底值（true）。
        // 读完必须再对一次，否则「关掉推荐页左右滑动」重启后不生效——
        // 用户反馈过这一条：设置里关了，重开 app 照样能滑。
        runCatching {
            com.hgtv.star.phone.PhoneServices.library(context)
                .loadAsync(onReady = { refreshSwipeSetting() })
        }
    }

    /**
     * 书库读完之后按设置重算一次横向翻页的锁（`loadAsync` 的 onReady 里调用）。
     *
     * 只改锁，不动页面内容：冷启动这一刻用户可能已经在滑了，整屏重铺会把位置与解码器一起打断。
     */
    private fun refreshSwipeSetting() {
        swipeEnabled = swipeEnabledFromSettings()
        applySwipeState()
    }

    /** 读设置页那一项；读不到（书库还没加载完）按开启处理，与出厂默认一致。 */
    private fun swipeEnabledFromSettings(): Boolean = runCatching {
        com.hgtv.star.phone.PhoneServices.library(context)
            .takeIf { it.isLoaded }?.recommendSwipe ?: true
    }.getOrDefault(true)

    /** 两个来源合成一个锁：面板开着（临时）或设置关掉（一直）都不许横向翻页。 */
    private fun applySwipeState() {
        pager.gesturesEnabled = !(sheetOpen || !swipeEnabled)
    }

    /**
     * 顶部固定先放「推荐」（三类混排），后面再按设置铺 漫剧 / AI剧 / 真人。
     *
     * 推荐页本身不能关掉；三类全关时仍保留推荐这一条混排流。
     */
    private fun enabledGenres(): List<RecommendGenre> {
        val typed = RecommendGenre.typed
        val lib = runCatching { com.hgtv.star.phone.PhoneServices.library(context) }.getOrNull()
        if (lib == null || !lib.isLoaded) return listOf(RecommendGenre.RECOMMEND) + typed
        val on = typed.filter { lib.recommendGenreEnabled(it.genre.orEmpty()) }
        return listOf(RecommendGenre.RECOMMEND) + on.ifEmpty { typed }
    }

    /**
     * 铺界面。
     *
     * 抽成函数是因为「推荐范围」改了之后要**整屏重铺**（体裁数变了，pager 的页数、
     * 顶部入口、每一页绑定的流都要跟着变），而不是就地打补丁。
     */
    private fun buildUi() {
        // 播放设置浮层挂在 Activity 的 decor 上，不随本页 `removeAllViews` 消失：
        // 重铺体裁之前先把它收掉，否则会留在新的一屏上。
        feeds.forEach {
            it?.closeSheet()
            it?.release()
        }
        removeAllViews()
        appliedGenres = genres
        feeds = arrayOfNulls(genres.size)
        current = genres.indexOfFirst { it.genre == restoreGenre }.takeIf { it >= 0 } ?: 0
        restoreGenre = null

        pager.adapter = null
        pager.userScroll = false
        // 重铺之后面板状态从「关」开始（旧的浮层已在上面统一收掉）。
        // 设置项那一份要重新读：用户可能刚在设置页关掉左右滑动。
        sheetOpen = false
        swipeEnabled = swipeEnabledFromSettings()
        applySwipeState()
        pager.adapter = adapter
        addView(pager, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 顶部渐变、体裁 tab 与右上角搜索都由同一个顶栏负责；推荐页是沉浸式视频，
        // 因此用 overlay 背板，位置和分类 / 榜单仍然落在同一条基线上。
        topBar.visibility = View.VISIBLE
        topBar.setTabs(genres.map { it.label }, current) { index -> goTo(index) }
        topBar.onSearch = {
            context.phoneNav().open(Route.Search)
        }
        addView(topBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = android.view.Gravity.TOP
        })
        topBar.bringToFront()
        topBar.post { syncFeedDanmakuTopInset(topBar.bottom) }
        pager.post { runCatching { pager.setCurrentItem(current, false) } }
    }

    /** 顶栏是覆盖层；弹幕轨道必须从它实际占用的下沿之后再开始。 */
    private fun syncFeedDanmakuTopInset(bottom: Int) {
        if (bottom <= 0) return
        feedDanmakuTopInset = bottom
        feeds.forEach { it?.setDanmakuTopInset(bottom) }
    }

    /** 当前体裁的稳定标识，供宿主在转屏重建前保存。 */
    fun currentGenreKey(): String? = genres.getOrNull(current)?.genre
    fun currentSeriesId(): String? = feeds.getOrNull(current)?.currentSeriesId()
    fun currentPositionMs(): Long = feeds.getOrNull(current)?.currentPositionMs() ?: -1L

    fun scrollToTop() {
        feeds.getOrNull(current)?.scrollToTop()
    }

    internal fun applySystemBottomInset(bottomInsetPx: Int) {
        feeds.forEach { it?.refreshBottomInsets(bottomInsetPx) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        // **不重铺**：一页的高度交给 `MATCH_PARENT` 自己跟，整屏重建会把三条流
        // （连同正在借给播放页出画面的那块 Surface）全部回收。
        // 播放页切横屏时本页会跟着转一次屏，早先那一次重建正是「一转屏就黑一下」的来源。
        // 尺寸变了只重新对齐当前这一页（换高度后旧的像素偏移不再是一页的整数倍）。
        pager.post { runCatching { pager.setCurrentItem(current, false) } }
        topBar.post { syncFeedDanmakuTopInset(topBar.bottom) }
        val bottomInsetPx = context.navigationBarPx()
        feeds.forEach { it?.refreshBottomInsets(bottomInsetPx) }
    }

    private fun goTo(index: Int) {
        if (index == current) return
        if (com.hgtv.star.shared.BuildConfig.DEBUG) {
            android.util.Log.i(
                "HGTV-PHONE",
                "tab click index=$index current=$current locked=${!pager.gesturesEnabled}",
            )
        }
        // 「减少动画优化性能」（设置 → 设备与性能）：打开时直接跳页，不做滑动动画。
        // 与 TV 的 `reduceAnim` 同一份落盘值——低配机省掉一次整屏合成。
        val reduce = runCatching {
            com.hgtv.star.phone.PhoneServices.library(context)
                .takeIf { it.isLoaded }?.reduceAnim == true
        }.getOrDefault(false)
        // 点 tab 是明确的程序性切页：先切业务流，再让 ViewPager2 跟上。
        // 用户手势的 tab 归属只在 IDLE 且 userScroll=true 时提交。
        pager.userScroll = false
        switchTo(index)
        pager.setCurrentItem(index, !reduce)
    }

    private fun switchTo(index: Int) {
        if (index == current) return
        if (com.hgtv.star.shared.BuildConfig.DEBUG) {
            android.util.Log.i("HGTV-PHONE", "tab switch from=$current to=$index")
        }
        feeds.getOrNull(current)?.onHidden()
        current = index
        applyTabSelection(index)
        feeds.getOrNull(index)?.onShown()
    }

    private fun applyTabSelection(selected: Int) {
        topBar.applyTabSelection(selected)
    }

    /**
     * 借 Surface 给正式播放页时收起 / 恢复本页自己的控制层。
     *
     * 只收**控制层**（顶部体裁、搜索、渐变、当前页的信息栏与弹幕），视频那一层不动：
     * 播放页竖屏就是靠它出画面的（见 `PhoneVideoPage` 的 `underlay`）。
     */
    fun setUnderlayMode(on: Boolean) {
        topBar.visibility = if (on) View.GONE else View.VISIBLE
        feeds.getOrNull(current)?.setUnderlayMode(on)
    }

    /** 由宿主在切走时调用：停掉解码器。 */
    fun onHidden() = feeds.getOrNull(current)?.onHidden()

    /** 当前这一条体裁流是否真的在播。 */
    fun isPlayingNow(): Boolean = feeds.getOrNull(current)?.isPlayingNow() == true

    /**
     * 由宿主在切回时调用：继续播。
     *
     * 顺带对一次「推荐范围」：设置页改完回来，体裁表可能已经变了（见 [buildUi]）。
     */
    fun onShown() {
        val next = enabledGenres()
        if (next != appliedGenres) {
            genres = next
            buildUi()
        } else {
            // 「左右滑动切换」可能刚在设置页改过：不重铺整屏，只把锁重算一次。
            val swipe = swipeEnabledFromSettings()
            if (swipe != swipeEnabled) {
                swipeEnabled = swipe
                applySwipeState()
            }
        }
        feeds.getOrNull(current)?.onShown()
    }

    /** 主设备注册完成：只让当前已创建、仍为空的信息流补拉一次。 */
    fun onIdentityReady() {
        feeds.forEach { it?.onIdentityReady() }
    }

    private inner class PagerAdapter : RecyclerView.Adapter<PagerHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PagerHolder {
            // 体裁在构造时就定下来：`PhoneFeedView.init` 会立刻按它铺快照、取第一页。
            // 建完再 `setGenre` 的话，构造期那一次全站混排的请求会晚回来、污染这一类的列表。
            val restoreSeries = if (viewType == current) restoreSeriesId else null
            val restorePosition = if (viewType == current) restorePositionMs else -1L
            val feed = PhoneFeedView(
                context,
                genres[viewType].genre,
                restoreSeries,
                restorePosition,
            ).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setDanmakuTopInset(feedDanmakuTopInset)
                onPlayStateChanged = { this@PhoneRecommendPage.onPlayStateChanged?.invoke() }
                // 播放设置面板开着时锁住横向翻页（见 `PhoneFeedView.onSheetVisibilityChanged`）。
                onSheetVisibilityChanged = { open -> sheetOpen = open; applySwipeState() }
            }
            if (viewType == current) {
                restoreSeriesId = null
                restorePositionMs = -1L
            }
            feeds[viewType] = feed
            // 建出来就**只让当前这一条上屏起播**（`PhoneFeedView` 默认是「收起来」的）。
            // pager 会预建左右相邻的体裁页，不区分的话三条流会同时起播、争抢活动播放器。
            if (viewType == current) feed.onShown()
            return PagerHolder(feed)
        }

        override fun getItemCount(): Int = genres.size

        override fun getItemViewType(position: Int): Int = position

        override fun onBindViewHolder(holder: PagerHolder, position: Int) {
            // 每一页在创建时就定了体裁，这里不需要重新绑定。
        }
    }

    private class PagerHolder(view: View) : RecyclerView.ViewHolder(view)

    /**
     * 横向体裁 ViewPager2。
     *
     * 外层只接明显横向的手势；纵向手势关闭本层输入，交给内部竖向信息流 ViewPager2。
     * 设置关闭或播放设置面板打开时整层锁定，不响应左右拖拽。
     */
    private class RecommendGenrePager(context: Context) : FrameLayout(context) {
        private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startItem = 0
        /** 0 = 未定性，1 = 纵向，2 = 横向。 */
        private var axis = 0

        var userScroll = false
        var gesturesEnabled = true
            set(value) {
                field = value
                if (!value) userScroll = false
                pager.isUserInputEnabled = value
            }

        private val pager = ViewPager2(context).apply {
            orientation = ViewPager2.ORIENTATION_HORIZONTAL
            offscreenPageLimit = 1
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        private val pageRecycler: RecyclerView
            get() = pager.getChildAt(0) as RecyclerView

        var adapter: RecyclerView.Adapter<*>?
            get() = pager.adapter
            set(value) {
                pager.adapter = value
            }

        val currentItem: Int
            get() = pager.currentItem

        init {
            setBackgroundColor(PhoneColor.BG)
            addView(
                pager,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
            pageRecycler.isNestedScrollingEnabled = false
            pageRecycler.itemAnimator = null
        }

        fun registerOnPageChangeCallback(callback: ViewPager2.OnPageChangeCallback) {
            pager.registerOnPageChangeCallback(callback)
        }

        fun setCurrentItem(position: Int, smoothScroll: Boolean) {
            pager.setCurrentItem(position, smoothScroll)
        }

        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    startItem = pager.currentItem
                    axis = 0
                    userScroll = false
                    pager.isUserInputEnabled = gesturesEnabled
                }
                MotionEvent.ACTION_MOVE -> {
                    if (axis == 0) {
                        val dx = Math.abs(e.x - downX)
                        val dy = Math.abs(e.y - downY)
                        if (dx > slop || dy > slop) {
                            axis = if (dx > dy) 2 else 1
                            if (axis == 2) {
                                userScroll = gesturesEnabled
                            } else {
                                pager.isUserInputEnabled = false
                                parent?.requestDisallowInterceptTouchEvent(false)
                            }
                        }
                    }
                    if (axis == 1) pager.isUserInputEnabled = false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val horizontalSwipe = axis == 2 && gesturesEnabled
                    val width = pager.width.coerceAtLeast(1)
                    val distance = Math.abs(e.x - downX)
                    axis = 0
                    pager.isUserInputEnabled = gesturesEnabled
                    if (e.actionMasked == MotionEvent.ACTION_UP && horizontalSwipe &&
                        distance < width * PhoneTabSwipeHost.COMMIT_DISTANCE_RATIO
                    ) {
                        pager.post {
                            pager.setCurrentItem(startItem, true)
                        }
                    }
                }
            }
            return super.dispatchTouchEvent(e)
        }
    }
}
