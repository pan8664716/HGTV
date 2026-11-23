// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.hgtv.star.shared.ui.UiText
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.exoplayer2.Player
import com.hgtv.star.core.Detail
import com.hgtv.star.core.Series
import com.hgtv.star.core.VideoFrameMode
import com.hgtv.star.core.toFit
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.isSystemBarGestureOrigin
import com.hgtv.star.phone.ui.resizeMode

/**
 * 正式播放页竖屏容器。
 *
 * `offscreenPageLimit=1` 让当前集、上一集、下一集的 [PhoneVideoPage] 常驻，
 * 三个独立 `SurfaceView` 可以在滑动前分别完成当前输出与相邻集静音预渲染。
 * 横滑仍归页面自己的进度手势，只有明显纵向拖动才交给 ViewPager2。
 */
internal class VerticalEpisodePager(context: Context) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var axis = 0
    private var systemBarGesture = false

    var userScroll = false
    var gesturesEnabled: Boolean = true
        set(value) {
            field = value
            if (!value) userScroll = false
            pager.isUserInputEnabled = value
        }

    private val pager = ViewPager2(context).apply {
        orientation = ViewPager2.ORIENTATION_VERTICAL
        offscreenPageLimit = 1
        overScrollMode = View.OVER_SCROLL_NEVER
    }

    val pageRecycler: RecyclerView
        get() = pager.getChildAt(0) as RecyclerView

    var adapter: RecyclerView.Adapter<*>?
        get() = pager.adapter
        set(value) {
            pager.adapter = value
        }

    val currentItem: Int
        get() = pager.currentItem

    init {
        setBackgroundColor(android.graphics.Color.BLACK)
        addView(
            pager,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        pageRecycler.isNestedScrollingEnabled = false
        pageRecycler.itemAnimator = null
        // 当前页最后绘制：让标题、进度条、按钮这些普通 View 不被相邻页盖住。
        pageRecycler.keepCurrentPagerItemOnTop { pager.currentItem }
    }

    fun registerOnPageChangeCallback(callback: ViewPager2.OnPageChangeCallback) {
        pager.registerOnPageChangeCallback(callback)
    }

    fun setActive(active: Boolean) {
        pager.offscreenPageLimit = if (active) 1 else ViewPager2.OFFSCREEN_PAGE_LIMIT_DEFAULT
    }

    fun setCurrentItem(position: Int, smoothScroll: Boolean = false) {
        pager.setCurrentItem(position, smoothScroll)
    }

    fun pageAt(position: Int): PhoneVideoPage? =
        (pageRecycler.findViewHolderForAdapterPosition(position) as? PhonePlayerPageHolder)
            ?.page

    fun pages(): List<PhoneVideoPage> = buildList {
        for (childIndex in 0 until pageRecycler.childCount) {
            (pageRecycler.getChildAt(childIndex) as? PhoneVideoPage)?.let(::add)
        }
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                axis = 0
                userScroll = false
                systemBarGesture = isSystemBarGestureOrigin(e.rawY)
                pager.isUserInputEnabled = gesturesEnabled && !systemBarGesture
            }
            MotionEvent.ACTION_MOVE -> {
                if (axis == 0) {
                    val dx = Math.abs(e.x - downX)
                    val dy = Math.abs(e.y - downY)
                    if (dx > slop || dy > slop) {
                        axis = if (dy > dx) 1 else 2
                        if (axis == 1) {
                            userScroll = gesturesEnabled && !systemBarGesture
                        } else {
                            pager.isUserInputEnabled = false
                            parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                }
                if (axis == 2) pager.isUserInputEnabled = false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                axis = 0
                systemBarGesture = false
                pager.isUserInputEnabled = gesturesEnabled
            }
        }
        return super.dispatchTouchEvent(e)
    }
}

/** 让 ViewPager2 当前页最后绘制，页面内 UI 才能跟着画面一起翻。 */
internal fun RecyclerView.keepCurrentPagerItemOnTop(currentPosition: () -> Int) {
    setChildDrawingOrderCallback { childCount, drawingPosition ->
        val currentChild = (0 until childCount).firstOrNull { childIndex ->
            val child = getChildAt(childIndex)
            child != null && getChildAdapterPosition(child) == currentPosition()
        } ?: -1
        when {
            currentChild < 0 -> drawingPosition
            drawingPosition == childCount - 1 -> currentChild
            drawingPosition < currentChild -> drawingPosition
            else -> drawingPosition + 1
        }
    }
}

internal class PhonePlayerPageHolder(
    val page: PhoneVideoPage,
) : RecyclerView.ViewHolder(page)

internal class PhonePlayerPagerAdapter(
    private val host: PhonePlayerActivity,
    private val initialPage: PhoneVideoPage,
) : RecyclerView.Adapter<PhonePlayerPageHolder>() {
    private var initialPageAvailable = true

    override fun getItemCount(): Int =
        host.detail?.episodes?.size?.coerceAtLeast(1) ?: 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhonePlayerPageHolder {
        val page = if (initialPageAvailable) {
            initialPageAvailable = false
            initialPage
        } else {
            host.makePage().also(host::wirePage)
        }
        page.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        return PhonePlayerPageHolder(page)
    }

    override fun onBindViewHolder(holder: PhonePlayerPageHolder, position: Int) {
        host.bindPlayerPagerPage(holder.page, position)
    }

    override fun onViewRecycled(holder: PhonePlayerPageHolder) {
        host.detachPlayerPagerPage(holder.page)
    }
}

internal fun PhonePlayerActivity.installPlayerPagerCallbacks() {
    playerEpisodePager.registerOnPageChangeCallback(
        object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(
                position: Int,
                positionOffset: Float,
                positionOffsetPixels: Int,
            ) {
                // 顶部播放信息挂在 pager 外层，翻页时按当前页真实位置跟着走。
                // **必须在 `userScroll` 判断之前**：自动连播、选集这类程序性翻集
                // 不经过手势，放在后面就不会动，用户读作「信息停在上一集」。
                if (playerPageInitialized && !playerLandscapeMode) {
                    playerPortraitTop.translationY =
                        playerEpisodePager.pageAt(position)?.top?.toFloat() ?: 0f
                }
                if (playerLandscapeMode || !playerEpisodePager.userScroll) return
                val target = if (positionOffset > 0.5f) position + 1 else position
                val size = detail?.episodes?.size ?: return
                if (target in 0 until size) previewPlayerPagerTarget(target)
            }

            override fun onPageSelected(position: Int) {
                if (!playerEpisodePager.userScroll) {
                    if (playerPageInitialized) playerPortraitTop.translationY = 0f
                    syncPlayerPagerPage(position, attachCurrent = position == index)
                }
            }

            override fun onPageScrollStateChanged(state: Int) {
                if (playerLandscapeMode) return
                if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
                    if (!playerEpisodePager.userScroll) return
                    stopAdjacentSwipePreview()
                    swipePlaybackPreviewIndex = -1
                    pauseCurrentForSwipeImpl()
                    return
                }
                if (state != ViewPager2.SCROLL_STATE_IDLE) return
                if (playerPageInitialized) playerPortraitTop.translationY = 0f
                val target = playerEpisodePager.currentItem
                val userScroll = playerEpisodePager.userScroll
                playerEpisodePager.userScroll = false
                if (!userScroll) {
                    syncPlayerPagerPage(target, attachCurrent = target == index)
                    return
                }
                if (target != index && detail?.episodes?.getOrNull(target) != null) {
                    syncPlayerPagerPage(target, attachCurrent = false)
                    playSwipeTarget(target)
                } else {
                    syncPlayerPagerPage(index, attachCurrent = true)
                    stopAdjacentSwipePreview()
                    swipePlaybackPreviewIndex = -1
                    resumeCurrentAfterSwipeImpl()
                }
            }
        },
    )
}

private fun PhonePlayerActivity.previewPlayerPagerTarget(target: Int) {
    if (target == index) {
        stopAdjacentSwipePreview()
        swipePlaybackPreviewIndex = -1
        return
    }
    if (swipePlaybackPreviewIndex == target) return
    stopAdjacentSwipePreview()
    swipePlaybackPreviewIndex = target
    pauseCurrentForSwipeImpl()
    attachAdjacentToPagerPage(target)
    startAdjacentSwipePreview(target)
}

internal fun PhonePlayerActivity.preparePlayerPager(
    detail: Detail,
    start: Int,
    onReady: () -> Unit,
) {
    var remaining = 2
    val ready = {
        remaining--
        if (remaining == 0) onReady()
    }
    preparePortraitPlayerPager(detail, start, ready)
    prepareLandscapePlayerPager(detail, start, ready)
}

private fun PhonePlayerActivity.preparePortraitPlayerPager(
    detail: Detail,
    start: Int,
    onReady: () -> Unit,
) {
    if (!playerEpisodePagerInitialized) {
        onReady()
        return
    }
    val position = start.coerceIn(detail.episodes.indices)
    playerEpisodePager.visibility = View.INVISIBLE
    playerEpisodePagerAdapter.notifyDataSetChanged()
    playerEpisodePager.setCurrentItem(position, false)
    awaitPlayerPagerPage(position, 3) {
        playerEpisodePager.visibility = View.VISIBLE
        onReady()
    }
}

private fun PhonePlayerActivity.awaitPlayerPagerPage(
    position: Int,
    attempts: Int,
    onReady: () -> Unit,
) {
    playerEpisodePager.post {
        if (isFinishing || isDestroyed) return@post
        if (syncPlayerPagerPage(position, attachCurrent = position == index) || attempts <= 0) {
            onReady()
        } else {
            awaitPlayerPagerPage(position, attempts - 1, onReady)
        }
    }
}

internal fun PhonePlayerActivity.selectPlayerPagerEpisode(position: Int) {
    if (playerEpisodePagerInitialized) {
        if (playerEpisodePager.currentItem != position) {
            playerEpisodePager.setCurrentItem(position, false)
        }
        syncPlayerPagerPage(position, attachCurrent = position == index)
        playerEpisodePager.post {
            if (playerEpisodePager.currentItem == position) {
                syncPlayerPagerPage(position, attachCurrent = position == index)
            }
        }
    }
    selectLandscapePagerEpisode(position)
}

internal fun PhonePlayerActivity.syncPlayerPagerPage(
    position: Int,
    attachCurrent: Boolean,
): Boolean {
    if (!playerEpisodePagerInitialized) return false
    val page = playerEpisodePager.pageAt(position) ?: return false
    swipePlayerPage = page
    if (playerLandscapeMode) return true
    page.setPlaybackLocked(playerControlsLocked)
    page.setChromeVisible(playerChromeVisible)
    if (attachCurrent && position == index) attachCurrentPlayerToPagerPage()
    return true
}

internal fun PhonePlayerActivity.bindPlayerPagerPage(page: PhoneVideoPage, position: Int) {
    val d = detail
    if (d == null || position !in d.episodes.indices) {
        page.bind(
            Series(
                id = "",
                title = if (d == null) UiText.get(com.hgtv.star.shared.R.string.ui_0043) else UiText.get(com.hgtv.star.shared.R.string.ui_0128),
            ),
            "",
        )
        page.setPlaybackLocked(playerControlsLocked)
        page.setChromeVisible(playerChromeVisible)
        return
    }
    syncAdjacentDetail(d)
    val label = UiText.get(com.hgtv.star.shared.R.string.ui_0343, position + 1)
    page.bind(
        d.series,
        label,
        watchText = "选集 ${position + 1}/${d.episodes.size}",
    )
    page.setPlaybackLocked(playerControlsLocked)
    page.setChromeVisible(playerChromeVisible)
    page.setResizeMode(
        (library?.frameMode ?: VideoFrameMode.FIT).toFit().resizeMode()
    )
    page.setVideoScale(((library?.videoScale ?: 100f) / 100f).coerceIn(.1f, 1f))
    page.danmakuView.alignBelowPhonePortraitChrome(
        bottomPx = playerPortraitTopBottom,
        infoGapPx = dp(8),
    )
    applyDanmakuSettingsToView(page.danmakuView)
    if (position == index && !playerLandscapeMode) {
        attachCurrentPlayerToPagerPage()
    } else {
        releaseStaleAdjacentSlot(position, d)
        val slot = playerAdjacentState.slots[position]
        if (slot != null) {
            attachAdjacentToPagerPage(position)
        } else {
            detachPlayerPagerPage(page)
            ensureAdjacentEpisode(position)
        }
    }
}

internal fun PhonePlayerActivity.attachCurrentPlayerToPagerPage(
    force: Boolean = false,
): Boolean {
    if (playerLandscapeMode || !playerPageInitialized) return false
    val exo = player ?: return false
    val current = if (playerEpisodePagerInitialized) {
        playerEpisodePager.pageAt(index) ?: return false
    } else {
        playerPage
    }
    swipePlayerPage = current
    if (playerEpisodePagerInitialized) {
        playerEpisodePager.pages().forEach { page ->
            if (page !== current && page.playerView.player === exo) {
                detachPlayerPagerPage(page)
            }
        }
    }
    current.setVideoSurfaceVisible(true)
    if (force) current.playerView.forceBindVideoSurface(exo)
    else if (current.playerView.player !== exo) current.playerView.player = exo
    return true
}

/**
 * 重建正式播放页的三页输出窗口。
 *
 * 非相邻集切换时，ViewPager2 会同时回收旧页、创建目标页和它的前后页。只依赖各页
 * 创建回调去“碰巧”绑定，会留下旧页仍持主播放器、目标页尚未接稳的窗口。
 *
 * 这里显式固定映射：
 * * 中间当前页 → 主播放器；
 * * 前后各一页 → 各自相邻集池播放器；
 * * 窗口外页面 → 全部解绑。
 */
internal fun PhonePlayerActivity.reconcilePlayerPagerWindow() {
    if (playerLandscapeMode || !playerEpisodePagerInitialized) return
    val d = detail ?: return
    val keep = LinkedHashSet<PhoneVideoPage>(3)

    playerEpisodePager.pageAt(index)?.let { current ->
        keep += current
        attachCurrentPlayerToPagerPage()
    }

    for (target in intArrayOf(index - 1, index + 1)) {
        if (target !in d.episodes.indices) continue
        val page = playerEpisodePager.pageAt(target) ?: continue
        keep += page
        val episodeId = d.episodes[target]
        val slot = playerAdjacentState.slots[target]
        if (
            slot != null &&
            slot.episodeId == episodeId &&
            slot.detail?.series?.id == d.series.id
        ) {
            attachAdjacentToPagerPage(target)
        } else {
            if (slot != null) releaseAdjacentSlot(target)
            detachPlayerPagerPage(page)
            ensureAdjacentEpisode(target)
        }
    }

    playerEpisodePager.pages()
        .filterNot(keep::contains)
        .forEach(::detachPlayerPagerPage)
}

internal fun PhonePlayerActivity.detachPlayerPagerOutputsExcept(keep: PhoneVideoPage?) {
    if (!playerEpisodePagerInitialized) return
    playerEpisodePager.pages().forEach { page ->
        if (page !== keep && page.playerView.player != null) {
            detachPlayerPagerPage(page)
        }
    }
}

internal fun PhonePlayerActivity.detachPlayerPagerPage(page: PhoneVideoPage) {
    if (page === playerPage && !playerLandscapeMode) return
    // **不清帧**：先关 `keepContentOnPlayerReset` 再把 `player` 置空，`PlayerView`
    // 会立刻合上快门。换集时被摘的这一页往往还在屏幕上（刚滑走、或相邻仍可见），
    // 用户看到的就是一次闪黑——推荐页每页独立播放器、不会去摘别人的输出，所以不闪。
    // 只摘引用，`TextureView` 上最后一帧留着；页面被复用时由 `bind` + 首帧覆盖。
    page.playerView.player = null
}
