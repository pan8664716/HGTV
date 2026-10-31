// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone.page

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.hgtv.star.core.RankBoard
import com.hgtv.star.phone.PhoneFeedView
import com.hgtv.star.phone.phoneNav
import com.hgtv.star.shared.nav.Route
import com.hgtv.star.phone.ui.PhoneColor
import com.hgtv.star.phone.ui.PhoneTopBar

/**
 * 推荐页：三条件裁横向滑动，每页一条独立的竖屏视频流。
 *
 * ```
 * 漫剧  ←→  真人  ←→  AI剧          （顶部左对齐，与分类 / 榜单一致）
 *        [全屏视频流，一屏一集]        （右上角：搜索）
 * ```
 *
 * ## 为什么用横向 pager 而不是点 tab 换流
 *
 * 三个体裁是并列的三条流，用户对它们的预期是「左右翻」而不是「点一下重载」。
 * 换 tab 时旧流的状态（滚到第几集）会被丢掉；pager 保留每一页，
 * 翻回来还在原来那一屏。
 *
 * ## 播放器只有当前页在跑
 *
 * 三页各持一个 `PhoneFeedView`，但只有当前页在播：翻页时旧页 `onHidden()`（暂停解码），
 * 新页 `onShown()`。三个解码器同时跑在 2GB 的机器上会被系统直接杀掉。
 */
class PhoneRecommendPage(context: Context) : FrameLayout(context) {

    /**
     * 这一屏要铺哪几条体裁流。
     *
     * 由设置页的「推荐范围」（`Library.recommendGenreOff`）决定：关掉的体裁不再建页，
     * 顶部那枚入口也不出现——与 TV 的 `recommendFeed(genres=…)` 同一份落盘值。
     * 至少留一条：`Library` 那边保证不会三类全关，这里再兜一次底（空表就全开）。
     */
    private var genres: List<RankBoard> = listOf(RankBoard.COMIC, RankBoard.REAL, RankBoard.AI)
    /** 已经铺出来的那一次筛选结果：设置变了就整屏重建一次。 */
    private var appliedGenres: List<RankBoard> = emptyList()
    private var feeds = arrayOfNulls<PhoneFeedView>(3)
    private val adapter = PagerAdapter()
    private val pager = LockablePager(context)
    /** 与分类 / 榜单共用同一个顶栏：tab 起点、字号和搜索按钮位置因此完全一致。 */
    private val topBar = PhoneTopBar(context).apply { makeOverlay() }
    private var current = 0
    /** 当前体裁流的播放意图变化：宿主据此同步窗口常亮。 */
    var onPlayStateChanged: (() -> Unit)? = null
    /** 程序性切页期间，滚动的中间态不参与 tab 归属判定。 */
    private var programmatic = false
    private var pendingTarget = -1
    /** 吸附与翻页监听：重铺时要先摘掉旧的，否则每重铺一次就多一份回调。 */
    private val snap = PagerSnapHelper()
    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
            if (state != RecyclerView.SCROLL_STATE_IDLE) return
            val index = (rv.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
            // 程序性切页：只认“目标页真的到位”的那一次 IDLE。
            // 中间补发的 IDLE（first 仍是旧页 / 已经滑过头）一律忽略。
            if (programmatic) {
                if (index == pendingTarget) {
                    programmatic = false
                    pendingTarget = -1
                }
                return
            }
            // **只有手指真的拖过才换 tab**。`PagerSnapHelper` 会在无障碍遍历
            //（`uiautomator dump` 就是一次）与重新布局之后补发 IDLE，甚至往回跳一页；
            // 早先把这些也算成“用户翻页”，于是点一次 tab 会被回切、或自己跳到下一类。
            // 程序性滚动走 SETTLING，不经过 DRAGGING，因此这个判据不会误伤 goTo。
            if (!pager.userScroll) return
            pager.userScroll = false
            if (index >= 0 && index != current) switchTo(index)
        }
    }

    init {
        setBackgroundColor(PhoneColor.BG)
        genres = enabledGenres()
        buildUi()
    }

    /** 按设置筛出要铺的体裁；一条都不剩时退回全开（正常情况不会发生）。 */
    private fun enabledGenres(): List<RankBoard> {
        val all = listOf(RankBoard.COMIC, RankBoard.REAL, RankBoard.AI)
        val lib = runCatching { com.hgtv.star.phone.PhoneServices.library(context) }.getOrNull()
        if (lib == null || !lib.isLoaded) return all
        val on = all.filter { lib.recommendGenreEnabled(it.genre.orEmpty()) }
        return on.ifEmpty { all }
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
        feeds.forEach { it?.closeSheet() }
        removeAllViews()
        appliedGenres = genres
        feeds = arrayOfNulls(genres.size)
        current = 0

        pager.adapter = null
        pager.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        // 重铺之后面板状态从「关」开始（旧的浮层已在上面统一收掉）。
        pager.swipeLocked = false
        // 整屏重铺时旧的切页目标已经失效：不清掉的话，下一次左右滑会被
        // `programmatic` 当成“动画中间态”吞掉（直到 900ms 兜底才恢复）。
        programmatic = false
        pendingTarget = -1
        // 用户一按下就交还控制权：程序性切页动画没跑完时也能立刻用手势接管，
        // 不会因为 `programmatic` 一直挂着而让左右滑“失灵”。
        pager.onTouchDown = {
            if (programmatic) {
                programmatic = false
                pendingTarget = -1
            }
        }
        pager.adapter = adapter
        pager.overScrollMode = View.OVER_SCROLL_NEVER
        pager.itemAnimator = null
        snap.attachToRecyclerView(pager)
        pager.removeOnScrollListener(scrollListener)
        pager.addOnScrollListener(scrollListener)
        addView(pager, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 顶部渐变、体裁 tab 与右上角搜索都由同一个顶栏负责；推荐页是沉浸式视频，
        // 因此用 overlay 背板，位置和分类 / 榜单仍然落在同一条基线上。
        topBar.visibility = View.VISIBLE
        topBar.setTabs(genres.map { it.label }, 0) { index -> goTo(index) }
        topBar.onSearch = {
            context.phoneNav().open(Route.Search)
        }
        addView(topBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = android.view.Gravity.TOP
        })
        topBar.bringToFront()
    }

    fun scrollToTop() {
        feeds.getOrNull(current)?.scrollToTop()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        // **不重铺**：一页的高度交给 `MATCH_PARENT` 自己跟，整屏重建会把三条流
        // （连同正在借给播放页出画面的那块 Surface）全部回收。
        // 播放页切横屏时本页会跟着转一次屏，早先那一次重建正是「一转屏就黑一下」的来源。
        // 尺寸变了只重新对齐当前这一页（换高度后旧的像素偏移不再是一页的整数倍）。
        pager.post { runCatching { pager.scrollToPosition(current) } }
    }

    private fun goTo(index: Int) {
        if (index == current) return
        if (com.hgtv.star.shared.BuildConfig.DEBUG) {
            android.util.Log.i("HGTV-PHONE", "tab click index=$index current=$current locked=${pager.swipeLocked}")
        }
        // 「减少动画优化性能」（设置 → 设备与性能）：打开时直接跳页，不做滑动动画。
        // 与 TV 的 `reduceAnim` 同一份落盘值——低配机省掉一次整屏合成。
        val reduce = runCatching {
            com.hgtv.star.phone.PhoneServices.library(context)
                .takeIf { it.isLoaded }?.reduceAnim == true
        }.getOrDefault(false)
        // 程序性切页**自己落地**：先把状态切到目标页，再让 pager 跟上。
        // 不能只依赖 `onScrollStateChanged(IDLE)`：PagerSnapHelper 在一次点击后会
        // 补发多次 IDLE（1→0→1→0），监听器按“第一个可见项”回切就会来回跳。
        programmatic = true
        pendingTarget = index
        pager.userScroll = false
        switchTo(index)
        if (reduce) pager.scrollToPosition(index) else pager.smoothScrollToPosition(index)
        // 兜底：极端情况下（目标页还没布局、动画被系统打断）收不到那一次 IDLE，
        // 程序性标记不能一直挂着——否则下一次左右滑会被当成“动画中的中间态”吞掉。
        pager.postDelayed({
            if (programmatic && pendingTarget == index) {
                programmatic = false
                pendingTarget = -1
            }
        }, 900L)
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
        }
        feeds.getOrNull(current)?.onShown()
    }

    private inner class PagerAdapter : RecyclerView.Adapter<PagerHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PagerHolder {
            // 体裁在构造时就定下来：`PhoneFeedView.init` 会立刻按它铺快照、取第一页。
            // 建完再 `setGenre` 的话，构造期那一次全站混排的请求会晚回来、污染这一类的列表。
            val feed = PhoneFeedView(context, genres[viewType].genre).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                onPlayStateChanged = { this@PhoneRecommendPage.onPlayStateChanged?.invoke() }
                // 播放设置面板开着时锁住横向翻页（见 `PhoneFeedView.onSheetVisibilityChanged`）。
                onSheetVisibilityChanged = { open -> pager.swipeLocked = open }
            }
            feeds[viewType] = feed
            // 建出来就**只让当前这一条上屏起播**（`PhoneFeedView` 默认是「收起来」的）。
            // pager 会预建左右相邻的体裁页，不区分的话三条流会同时起播、抢同一台播放器。
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
     * 可锁横向翻页的 pager。
     *
     * `onInterceptTouchEvent` 返回 false 只是「不抢子 View 的触摸」；真正让横向滑动
     * 不生效还要 `onTouchEvent` 也返回 false，否则手指落在 pager 空白处仍然会滚。
     * 两处一起拦，面板开着时左右滑既不会换页、也不会把面板下面的那一页拖动。
     */
    private class LockablePager(context: Context) : RecyclerView(context) {
        var swipeLocked = false
        /** 这一次滚动是不是手指拖出来的（先收到 DOWN，再进 `DRAGGING`）。 */
        var userScroll = false
        /** 本次手势是否真的从 pager 上按下过：过滤掉布局 / 无障碍触发的假 DRAGGING。 */
        private var touchDown = false
        /** 手指按下：宿主借此放弃还在跑的程序性切页。 */
        var onTouchDown: (() -> Unit)? = null

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!swipeLocked) {
                        touchDown = true
                        userScroll = false
                        onTouchDown?.invoke()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // `touchDown` 只表示“这一下是从 pager 上按下去的”，抬手就可以清；
                    // `userScroll` 不能清：UP 之后还有一段 SETTLING，IDLE 才结算。
                    touchDown = false
                }
            }
            return !swipeLocked && super.onInterceptTouchEvent(e)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                touchDown = true
                userScroll = false
            }
            return !swipeLocked && super.onTouchEvent(e)
        }

        override fun onScrollStateChanged(state: Int) {
            if (state == RecyclerView.SCROLL_STATE_DRAGGING && touchDown) userScroll = true
            if (state == RecyclerView.SCROLL_STATE_IDLE) touchDown = false
            super.onScrollStateChanged(state)
        }

    }
}
