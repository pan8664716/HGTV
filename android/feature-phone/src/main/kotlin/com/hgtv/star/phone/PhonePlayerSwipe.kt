// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.view.View
import com.hgtv.star.phone.ui.PhonePagerMotion
import com.hgtv.star.phone.ui.PhoneTouch

/** 一屏的高度：竖屏 / 横屏各自的那一层。 */
internal fun PhonePlayerActivity.slideHeightImpl(): Int {
    val h = if (playerLandscapeMode) swipeLandscapeHeight else swipePortraitHeight
    return h.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
}

/** 手指按下：停掉上一轮的动画，等第一次有方向的位移再决定翻向哪一集。 */
internal fun PhonePlayerActivity.beginSwipeImpl() {
    swipeDirection = 0
    swipePreparedState = false
    playerPage.animate().cancel()
    swipeGhostPage.animate().cancel()
    swipeLandscape.animate().cancel()
    swipeLandscapeGhost.animate().cancel()
    cancelSwipeAnimationImpl()
    showEpisodeHintImpl(0f)
}

/**
 * 拖动中：两页一起跟手。当前页跟着手指走，相邻页从另一侧同步进入。
 * 到头时相邻页保持隐藏，当前页不产生位移。
 */
internal fun PhonePlayerActivity.dragSwipeImpl(delta: Float) {
    val h = slideHeightImpl()
    val direction = if (delta < 0) 1 else -1
    if (!swipePreparedState && kotlin.math.abs(delta) > .008f) {
        swipePreparedState = true
        swipeDirection = direction
        prepareGhostImpl(direction)
        if (swipeDirection == 0) swipePreparedState = false
    } else if (swipePreparedState && swipeDirection != 0 && direction != swipeDirection) {
        swipeDirection = direction
        prepareGhostImpl(direction)
        if (swipeDirection == 0) swipePreparedState = false
    }
    val factor = if (swipeDirection != 0) 1f else 0f
    val dy = delta * h * factor
    if (playerLandscapeMode) {
        swipeLandscape.translationY = dy
        if (swipeDirection != 0) {
            swipeLandscapeGhost.translationY =
                dy + if (swipeDirection > 0) h else -h
        }
    } else {
        playerPage.translationY = dy
        if (playerFeedUnderlay) {
            swipeFeedVideoDy = dy
            PhoneSession.setFeedVideoOffset(dy)
        }
        if (swipeDirection != 0) {
            swipeGhostPage.translationY =
                dy + if (swipeDirection > 0) h else -h
        }
    }
    showEpisodeHintImpl(delta)
}

/** 松手：够了就吸到整页，不够就弹回。 */
internal fun PhonePlayerActivity.endSwipeImpl(direction: Int) {
    clearEpisodeHintImpl()
    val target = when (direction) {
        PhoneTouch.DIRECTION_NEXT -> 1
        PhoneTouch.DIRECTION_PREV -> -1
        else -> 0
    }
    val d = detail
    val playable = target != 0 && d != null && (index + target) in d.episodes.indices
    if (!playable) {
        if (target != 0) stepEpisodeImpl(target)
        settleBackImpl()
        return
    }
    val h = slideHeightImpl().toFloat()
    val out = -target * h
    val start = if (playerLandscapeMode) {
        swipeLandscape.translationY
    } else {
        playerPage.translationY
    }
    val duration = PhonePagerMotion.duration(out - start, slideHeightImpl())
    if (playerLandscapeMode) {
        animateSwipeImpl(
            pageTarget = playerPage.translationY,
            ghostTarget = swipeGhostPage.translationY,
            landscapeTarget = out,
            landscapeGhostTarget = 0f,
            feedTarget = null,
            duration = duration,
        ) {
            playSwipeTargetForSwipe(index + target)
            resetSwipeStateImpl()
        }
    } else {
        animateSwipeImpl(
            pageTarget = out,
            ghostTarget = 0f,
            landscapeTarget = swipeLandscape.translationY,
            landscapeGhostTarget = swipeLandscapeGhost.translationY,
            feedTarget = if (playerFeedUnderlay) out else null,
            duration = duration,
        ) {
            val outgoing = playerPage
            swipePlayerPage = swipeGhostPage
            swipeGhostPage = outgoing
            playerPage.translationY = 0f
            PhoneServices.detach(swipeGhostPage.playerView)
            swipeGhostPage.translationY = 0f
            swipeGhostPage.visibility = View.GONE
            resetSwipeStateImpl(keepCurrent = true)
            playSwipeTargetForSwipe(index + target)
            applyVideoScaleForSwipe()
            applyChromeForSwipe()
            syncChips()
        }
    }
}

/** 弹回原位（位移不够 / 到头）。 */
private fun PhonePlayerActivity.settleBackImpl() {
    val h = slideHeightImpl().toFloat()
    val pageStart = if (playerLandscapeMode) {
        swipeLandscape.translationY
    } else {
        playerPage.translationY
    }
    val ghostTarget = when {
        swipeDirection > 0 -> h
        swipeDirection < 0 -> -h
        else -> 0f
    }
    val duration = PhonePagerMotion.duration(pageStart, slideHeightImpl())
    animateSwipeImpl(
        pageTarget = 0f,
        ghostTarget = ghostTarget,
        landscapeTarget = 0f,
        landscapeGhostTarget = ghostTarget,
        feedTarget = if (playerFeedUnderlay && !playerLandscapeMode) 0f else null,
        duration = duration,
    ) {
        resetSwipeStateImpl()
    }
}

/** 一次动画同时驱动当前页、相邻页和借用的底层视频。 */
private fun PhonePlayerActivity.animateSwipeImpl(
    pageTarget: Float,
    ghostTarget: Float,
    landscapeTarget: Float,
    landscapeGhostTarget: Float,
    feedTarget: Float?,
    duration: Long,
    onEnd: () -> Unit,
) {
    cancelSwipeAnimationImpl()
    val pageStart = playerPage.translationY
    val ghostStart = swipeGhostPage.translationY
    val landscapeStart = swipeLandscape.translationY
    val landscapeGhostStart = swipeLandscapeGhost.translationY
    val feedStart = swipeFeedVideoDy
    val startedAt = android.os.SystemClock.uptimeMillis()
    lateinit var tick: Runnable
    tick = object : Runnable {
        override fun run() {
            if (swipeAnimationRunnable !== tick) return
            val elapsed = (android.os.SystemClock.uptimeMillis() - startedAt)
                .coerceAtMost(duration)
            val linear = if (duration > 0L) {
                (elapsed.toFloat() / duration).coerceIn(0f, 1f)
            } else {
                1f
            }
            val fraction = PhonePagerMotion.interpolator.getInterpolation(linear)
            playerPage.translationY = pageStart + (pageTarget - pageStart) * fraction
            swipeGhostPage.translationY =
                ghostStart + (ghostTarget - ghostStart) * fraction
            swipeLandscape.translationY =
                landscapeStart + (landscapeTarget - landscapeStart) * fraction
            swipeLandscapeGhost.translationY =
                landscapeGhostStart + (landscapeGhostTarget - landscapeGhostStart) * fraction
            if (feedTarget != null) {
                swipeFeedVideoDy = feedStart + (feedTarget - feedStart) * fraction
                PhoneSession.setFeedVideoOffset(swipeFeedVideoDy)
            }
            if (elapsed < duration) {
                root.postOnAnimation(this)
            } else {
                swipeAnimationRunnable = null
                onEnd()
            }
        }
    }
    swipeAnimationRunnable = tick
    root.postOnAnimation(tick)
}

/** 取消帧时钟动画；不依赖系统 animator duration scale。 */
private fun PhonePlayerActivity.cancelSwipeAnimationImpl() {
    swipeAnimationRunnable?.let { root.removeCallbacks(it) }
    swipeAnimationRunnable = null
}

/** 铺相邻页：绑定目标集，视频区保持黑，先出真实信息栏。 */
private fun PhonePlayerActivity.prepareGhostImpl(direction: Int) {
    val d = detail ?: run { swipeDirection = 0; return }
    val target = index + direction
    if (target !in d.episodes.indices) {
        swipeDirection = 0
        return
    }
    val label = "第${target + 1}集"
    val h = slideHeightImpl().toFloat()
    if (playerLandscapeMode) {
        swipeLandscapeGhostTitle.text = "${d.series.title} $label"
        swipeLandscapeGhost.translationY = if (direction > 0) h else -h
        swipeLandscapeGhost.visibility = View.VISIBLE
    } else {
        swipeGhostPage.bind(
            d.series,
            label,
            watchText = "选集 ${target + 1}/${d.episodes.size}",
        )
        swipeGhostPage.setProgress(0f)
        swipeGhostPage.setChromeVisible(swipeChromeVisible)
        swipeGhostPage.translationY = if (direction > 0) h else -h
        swipeGhostPage.visibility = View.VISIBLE
    }
}

/** 复位两页的位置与显隐；[keepCurrent] = 刚完成交换，当前页要留在正中。 */
internal fun PhonePlayerActivity.resetSwipeStateImpl(keepCurrent: Boolean = false) {
    if (keepCurrent) playerPage.translationY = 0f
    if (playerFeedUnderlay) PhoneSession.setFeedVideoOffset(0f)
    swipeGhostPage.translationY = 0f
    swipeGhostPage.visibility = View.GONE
    swipeLandscape.translationY = 0f
    swipeLandscapeGhost.translationY = 0f
    swipeLandscapeGhost.visibility = View.GONE
    swipePreparedState = false
    swipeDirection = 0
}

/** 上下滑时的集数提示，复用画面顶部那一条 hint。 */
private fun PhonePlayerActivity.showEpisodeHintImpl(delta: Float) {
    val d = detail ?: return
    val target = if (delta <= 0) index + 1 else index - 1
    val enough = kotlin.math.abs(delta) >= PhoneTouch.VERTICAL_COMMIT_RATIO
    hint.text = when {
        target !in d.episodes.indices && delta <= 0 -> "已经是最后一集"
        target !in d.episodes.indices -> "已经是第一集"
        enough -> "${if (delta <= 0) "下一集" else "上一集"} · 第${target + 1}集"
        else -> "${if (delta <= 0) "上滑" else "下滑"}切换${if (delta <= 0) "下一集" else "上一集"}"
    }
    swipeHintToken++
    swipeHintSticky = true
    hint.visibility = View.VISIBLE
}

private fun PhonePlayerActivity.clearEpisodeHintImpl() {
    swipeHintSticky = false
    hint.visibility = View.GONE
}

/** 换到相邻的一集（±1）。到头时给一句提示，不做越界播放。 */
private fun PhonePlayerActivity.stepEpisodeImpl(direction: Int) {
    val d = detail ?: return
    val target = index + direction
    if (target !in d.episodes.indices) {
        hint.text = if (direction > 0) "已经是最后一集" else "已经是第一集"
        swipeHintToken++
        swipeHintSticky = true
        hint.visibility = View.VISIBLE
        val token = swipeHintToken
        root.postDelayed({ if (swipeHintToken == token) clearEpisodeHintImpl() }, 1200)
        return
    }
    playSwipeTargetForSwipe(target)
    hint.text = "第${target + 1}集"
    swipeHintToken++
    swipeHintSticky = true
    hint.visibility = View.VISIBLE
    val token = swipeHintToken
    root.postDelayed({ if (swipeHintToken == token) clearEpisodeHintImpl() }, 900)
}
