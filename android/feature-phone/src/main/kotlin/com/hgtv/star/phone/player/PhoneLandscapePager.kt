// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.hgtv.star.core.Detail
import com.hgtv.star.core.VideoFrameMode
import com.hgtv.star.core.toFit
import com.hgtv.star.phone.ui.PhoneTouch
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.isSystemBarGestureOrigin
import com.hgtv.star.phone.ui.resizeMode
import com.hgtv.star.shared.data.ArtworkLoader
import com.hgtv.star.shared.media.DanmakuView

internal class LandscapePlayerPage(
    private val host: PhonePlayerActivity,
) : FrameLayout(host) {
    val video = FixedFirstFramePlayerView(host).apply {
        useController = false
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        setShutterBackgroundColor(Color.BLACK)
        setKeepContentOnPlayerReset(true)
        PhonePlayerViewSupport.useOpaqueVideoSurface(this)
        setAspectRatioListener { _, _, _ ->
            if (host.playerLandscapeVideo === this) host.syncLandscapeLetterboxImpl()
        }
    }
    val cover = episodeCoverView(host)
    val danmaku = DanmakuView(host).apply {
        mode = com.hgtv.star.core.DanmakuMode.DEFAULT
        minTextSizeDp = 4f
        topGapDp = 0f
        sizeFollowsLongEdge = true
        setClock(0, false)
        // 每集页都有自己的视频 Surface；弹幕保持普通层级，避免透明覆盖层越过
        // 相邻页的视频并透出下一集预渲染画面。
        setZOrderMediaOverlay(false)
        onRenderStateChangedListener = {
            if (host.playerLandDanmaku === this) host.syncLandscapeLetterboxImpl()
        }
    }
    private val touch = View(host)

    init {
        setBackgroundColor(Color.BLACK)
        addView(video, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(cover, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(danmaku, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        touch.setOnTouchListener(
            PhoneTouch(
                host = touch,
                isLandscape = { true },
                onTap = { host.setChromeVisible(!host.playerChromeVisible) },
                onDoubleTap = { host.togglePlay() },
                onLongPress = { zone ->
                    if (!host.playerControlsLocked) {
                        when (zone) {
                            PhoneTouch.Zone.CENTER -> {
                                host.endTempSpeed()
                                host.openSettings()
                            }
                            else -> host.beginTempSpeed()
                        }
                    }
                },
                onLongPressEnd = { host.endTempSpeed() },
                onDragStart = {
                    if (!host.playerControlsLocked) {
                        host.playerDragging = true
                        host.playerSeekFrom = host.player?.currentPosition ?: 0L
                    }
                },
                onDrag = { fraction ->
                    if (!host.playerControlsLocked) host.dragTo(fraction)
                },
                onDragEnd = {
                    if (!host.playerControlsLocked) {
                        host.playerDragging = false
                        val duration = host.player?.duration ?: 0L
                        if (duration > 0) host.player?.seekTo(host.playerSeekTarget)
                        host.clearSeekHint()
                    }
                },
                verticalEnabled = false,
                doubleTapEnabled = true,
            )
        )
        addView(touch, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        video.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (host.playerLandscapeVideo === video &&
                host.playerLandDanmakuInitialized
            ) {
                host.syncLandscapeLetterboxImpl()
            }
        }
    }

    fun bind(detail: Detail, index: Int) {
        video.resizeMode =
            (host.library?.frameMode ?: VideoFrameMode.FIT).toFit().resizeMode()
    }

    fun showCover(url: String) {
        cover.animate().cancel()
        cover.alpha = 1f
        cover.visibility = View.VISIBLE
        if (url.isBlank()) {
            cover.setImageDrawable(null)
            return
        }
        val metrics = resources.displayMetrics
        PhoneServices.artwork(context).load(
            cover,
            url,
            ArtworkLoader.PRIORITY_FOCUSED,
            metrics.widthPixels,
            metrics.heightPixels,
        )
    }

    fun hideCover() {
        if (cover.visibility != View.VISIBLE) return
        cover.animate().cancel()
        cover.animate().alpha(0f).setDuration(120L).withEndAction {
            if (cover.alpha == 0f) {
                cover.visibility = View.GONE
                cover.setImageDrawable(null)
            }
        }.start()
    }
}

internal class LandscapePlayerPageHolder(
    val page: LandscapePlayerPage,
) : RecyclerView.ViewHolder(page)

internal class LandscapePlayerPagerAdapter(
    private val host: PhonePlayerActivity,
    private val initialPage: LandscapePlayerPage,
) : RecyclerView.Adapter<LandscapePlayerPageHolder>() {
    private var initialPageAvailable = true

    override fun getItemCount(): Int =
        host.detail?.episodes?.size?.coerceAtLeast(1) ?: 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LandscapePlayerPageHolder {
        val page = if (initialPageAvailable) {
            initialPageAvailable = false
            initialPage
        } else {
            LandscapePlayerPage(host)
        }
        page.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        return LandscapePlayerPageHolder(page)
    }

    override fun onBindViewHolder(holder: LandscapePlayerPageHolder, position: Int) {
        host.bindLandscapePagerPage(holder.page, position)
    }

    override fun onViewRecycled(holder: LandscapePlayerPageHolder) {
        host.detachLandscapePagerPage(holder.page)
    }
}

internal class VerticalLandscapePager(context: Context) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var axis = 0
    private var systemBarGesture = false

    var userScroll = false
    var gesturesEnabled = true
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

    val currentItem: Int
        get() = pager.currentItem

    private val pageRecycler: RecyclerView
        get() = pager.getChildAt(0) as RecyclerView

    var adapter: RecyclerView.Adapter<*>?
        get() = pager.adapter
        set(value) {
            pager.adapter = value
        }

    init {
        setBackgroundColor(Color.BLACK)
        addView(
            pager,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        pageRecycler.isNestedScrollingEnabled = false
        pageRecycler.itemAnimator = null
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

    fun pageAt(position: Int): LandscapePlayerPage? =
        (pageRecycler.findViewHolderForAdapterPosition(position)
            as? LandscapePlayerPageHolder)?.page

    fun pages(): List<LandscapePlayerPage> = buildList {
        for (childIndex in 0 until pageRecycler.childCount) {
            (pageRecycler.getChildAt(childIndex) as? LandscapePlayerPage)?.let(::add)
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

internal fun PhonePlayerActivity.installLandscapePlayerPagerCallbacks() {
    playerLandscapePager.registerOnPageChangeCallback(
        object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(
                position: Int,
                positionOffset: Float,
                positionOffsetPixels: Int,
            ) {
                if (!playerLandscapeMode || !playerLandscapePager.userScroll) return
                val target = if (positionOffset > 0.5f) position + 1 else position
                val size = detail?.episodes?.size ?: return
                if (target in 0 until size) previewLandscapePagerTarget(target)
            }

            override fun onPageSelected(position: Int) {
                if (!playerLandscapePager.userScroll) {
                    syncLandscapePlayerPagerPage(position, attachCurrent = position == index)
                }
            }

            override fun onPageScrollStateChanged(state: Int) {
                if (!playerLandscapeMode) return
                if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
                    if (!playerLandscapePager.userScroll) return
                    stopAdjacentSwipePreview()
                    swipePlaybackPreviewIndex = -1
                    pauseCurrentForSwipeImpl()
                    return
                }
                if (state != ViewPager2.SCROLL_STATE_IDLE) return
                val target = playerLandscapePager.currentItem
                val userScroll = playerLandscapePager.userScroll
                playerLandscapePager.userScroll = false
                if (!userScroll) {
                    syncLandscapePlayerPagerPage(target, attachCurrent = target == index)
                    return
                }
                if (target != index && detail?.episodes?.getOrNull(target) != null) {
                    syncLandscapePlayerPagerPage(target, attachCurrent = false)
                    playSwipeTarget(target)
                } else {
                    syncLandscapePlayerPagerPage(index, attachCurrent = true)
                    stopAdjacentSwipePreview()
                    swipePlaybackPreviewIndex = -1
                    resumeCurrentAfterSwipeImpl()
                }
            }
        },
    )
}

private fun PhonePlayerActivity.previewLandscapePagerTarget(target: Int) {
    if (target == index) {
        stopAdjacentSwipePreview()
        swipePlaybackPreviewIndex = -1
        return
    }
    if (swipePlaybackPreviewIndex == target) return
    stopAdjacentSwipePreview()
    swipePlaybackPreviewIndex = target
    pauseCurrentForSwipeImpl()
    attachAdjacentToLandscapePagerPage(target)
    startAdjacentSwipePreview(target)
}

internal fun PhonePlayerActivity.prepareLandscapePlayerPager(
    detail: Detail,
    start: Int,
    onReady: () -> Unit,
) {
    if (!playerLandscapePagerInitialized) {
        onReady()
        return
    }
    val position = start.coerceIn(detail.episodes.indices)
    playerLandscapePager.visibility = View.INVISIBLE
    playerLandscapePagerAdapter.notifyDataSetChanged()
    playerLandscapePager.setCurrentItem(position, false)
    awaitLandscapePlayerPagerPage(position, 3) {
        playerLandscapePager.visibility = View.VISIBLE
        onReady()
    }
}

private fun PhonePlayerActivity.awaitLandscapePlayerPagerPage(
    position: Int,
    attempts: Int,
    onReady: () -> Unit,
) {
    playerLandscapePager.post {
        if (isFinishing || isDestroyed) return@post
        if (syncLandscapePlayerPagerPage(position, attachCurrent = position == index) ||
            attempts <= 0
        ) {
            onReady()
        } else {
            awaitLandscapePlayerPagerPage(position, attempts - 1, onReady)
        }
    }
}

internal fun PhonePlayerActivity.selectLandscapePagerEpisode(position: Int) {
    if (!playerLandscapePagerInitialized) return
    if (playerLandscapePager.currentItem != position) {
        playerLandscapePager.setCurrentItem(position, false)
    }
    syncLandscapePlayerPagerPage(position, attachCurrent = position == index)
}

internal fun PhonePlayerActivity.syncLandscapePlayerPagerPage(
    position: Int,
    attachCurrent: Boolean,
): Boolean {
    if (!playerLandscapePagerInitialized) return false
    val page = playerLandscapePager.pageAt(position) ?: return false
    playerLandscapeVideoSlot = page.video
    playerLandscapeCover = page.cover
    playerLandDanmakuSlot = page.danmaku
    if (attachCurrent && position == index && playerLandscapeMode) {
        attachCurrentPlayerToLandscapePagerPage()
    }
    return true
}

internal fun PhonePlayerActivity.bindLandscapePagerPage(
    page: LandscapePlayerPage,
    position: Int,
) {
    val d = detail
    if (d == null || position !in d.episodes.indices) return
    page.bind(d, position)
    applyDanmakuSettingsToView(page.danmaku)
    if (playerLandInfoRowBottom > 0) {
        page.danmaku.topInsetPx = playerLandInfoRowBottom + dp(2)
    }
    if (position == index && playerLandscapeMode) {
        attachCurrentPlayerToLandscapePagerPage()
        if (player?.playbackState == Player.STATE_READY && lastFrameAt > 0L) {
            page.hideCover()
        } else {
            page.showCover(d.episodeCover(d.episodes[position]).ifBlank { d.series.cover })
        }
    } else {
        val slot = playerAdjacentState.slots[position]
        if (slot != null) {
            attachAdjacentToLandscapePagerPage(position)
        } else {
            detachLandscapePagerPage(page)
            page.showCover(d.episodeCover(d.episodes[position]).ifBlank { d.series.cover })
            ensureAdjacentEpisode(position)
        }
    }
}

/**
 * 等横屏当前集页真正建出来后，再把当前播放器挂到它自己的 Surface。
 *
 * 转屏时 `ViewPager2` 要先可见、再测量、再创建 ViewHolder；早一帧读取会拿不到当前页。
 * 这里只按集号取页，不再回退到 `playerLandscapeVideo`：后者可能还指向 pager 缓存里的
 * 旧页，挂错后就是“声音在走、当前页全黑”，切一次集又碰巧恢复。
 */
internal fun PhonePlayerActivity.scheduleCurrentLandscapeAttach(attempts: Int = 12) {
    if (!playerLandscapeMode || !playerLandscapePagerInitialized) return
    scheduleCurrentPlayerSurfaceRebind(attempts = attempts)
}

internal fun PhonePlayerActivity.attachCurrentPlayerToLandscapePagerPage(
    force: Boolean = false,
): Boolean {
    if (!playerLandscapeMode || !playerLandscapePagerInitialized) return false
    val exo = player ?: return false
    val current = playerLandscapePager.pageAt(index) ?: return false
    playerLandscapePager.pages().forEach { page ->
        if (page !== current && page.video.player === exo) {
            detachLandscapePagerPage(page)
        }
    }
    playerLandscapeVideoSlot = current.video
    playerLandscapeCover = current.cover
    playerLandDanmakuSlot = current.danmaku
    current.video.visibility = View.VISIBLE
    if (force) current.video.forceBindVideoSurface(exo)
    else if (current.video.player !== exo) current.video.player = exo
    return true
}

internal fun PhonePlayerActivity.detachLandscapePagerOutputsExcept(
    keep: LandscapePlayerPage?,
) {
    if (!playerLandscapePagerInitialized) return
    playerLandscapePager.pages().forEach { page ->
        if (page !== keep && page.video.player != null) detachLandscapePagerPage(page)
    }
}

internal fun PhonePlayerActivity.detachLandscapePagerPage(page: LandscapePlayerPage) {
    if (page.video === playerLandscapeVideo && playerLandscapeMode) return
    page.video.setKeepContentOnPlayerReset(false)
    page.video.player = null
    page.video.setKeepContentOnPlayerReset(true)
}
