// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.hgtv.star.core.CacheLevel
import com.hgtv.star.core.SlideCacheManager
import com.hgtv.star.shared.feed.PreloadScheduler
import com.hgtv.star.shared.feed.RecommendWarmCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

internal class PhoneFeedPreloadState(private val host: PhoneFeedView) {
    val slideCache = SlideCacheManager()
    var preloadAt = 0L
    var pendingPlan: SlideCacheManager.Plan? = null
    val priming = mutableSetOf<Int>()
    val scheduler by lazy {
        PreloadScheduler(
            slideCache = slideCache,
            anchor = { host.feedPlayingIndex },
            active = { !host.feedHidden && host.feedPlayingIndex >= 0 },
            gateOpen = { host.preloadGateOpen() },
            // 优先用换剧那一刻算出来的计划：它带着 `REVISIT`（滑回来）/ `FLING`（快滑）
            // 这些判定，重算一遍就只剩 `WINDOW`，回看与快滑的提升全丢了。
            plan = {
                val planned = pendingPlan ?: slideCache.plan(host.feedPlayingIndex)
                pendingPlan = null
                planned
            },
            apply = { planned, _ -> host.applyPreload(planned) },
        )
    }
}

/** 带宽能不能让出来：正在放的那一部 buffer 不足 1.5 秒时，预加载不该抢同一条下行。 */
internal fun PhoneFeedView.preloadGateOpen(): Boolean {
    val exo = feedPlayer ?: return true
    if (!exo.isPlaying) return true
    return exo.bufferedPosition - exo.currentPosition >= 1_500L
}

/**
 * 停留确认：连划之后停下来，把主方向那一步重新升回地址预热。
 *
 * 只在**刚才是在连划**时有意义：快滑期间窗口被降级成「仅地址」，用户停下来之后
 * 不补这一步，下一步滑过去仍然是冷启动。规则在 [PreloadScheduler]。
 */
internal fun PhoneFeedView.scheduleDwell() {
    feedPreloadScheduler.scheduleDwell()
}

/**
 * 排定一次相邻预热（延迟到首帧之后、带宽让路、用户已经滑走就取消）。
 *
 * 节流判断必须**在排定之前**：早先先取消再判，被节流的那一次会把已经排好的预热
 * 一起取消掉——连着滑几屏之后一段时间内一条预热都不发，用户停在某一屏上时
 * 下一步永远是冷启动（正是「滑到下一部要等」的来源）。
 * 已经排着的那一次会在执行时读 `feedPendingPlan`，因此它拿到的仍是最新的一屏。
 */
internal fun PhoneFeedView.schedulePreload(delayMs: Long) {
    val now = android.os.SystemClock.elapsedRealtime()
    if (now < feedPreloadState.preloadAt) return
    feedPreloadState.preloadAt = now + delayMs
    feedPreloadScheduler.scheduleWindow(delayMs, "switch")
}

/**
 * 按计划真正发起预热。
 *
 * 只派前 5 条（电视端 `PRELOAD_MAX_TARGETS` 同一条理由：同时发太多只会互相抢带宽）。
 * 主方向最近的一条升级为完整预热，并提前创建相邻页独立播放器；其余目标只取地址或索引。
 */
internal fun PhoneFeedView.applyPreload(plan: SlideCacheManager.Plan) {
    val anchor = feedPlayingIndex
    if (plan.anchor != anchor || feedHidden || !isAttachedToWindow) return
    val lib = feedLibrary ?: return
    val repo = PhoneServices.repository(context)
    val warmCoordinator=RecommendWarmCoordinator(repo,lib,feedPlaybackSource)
    val mainNeighbor = anchor + feedPreloadState.slideCache.direction
    var dispatched = 0
    for (target in plan.targets) {
        if (dispatched >= 5) break
        val series = feedItems.getOrNull(target.index) ?: continue
        // 正在放的那一部不预热。
        if (target.index == anchor) continue
        val level = when {
            target.level == CacheLevel.NONE -> CacheLevel.NONE
            target.index == mainNeighbor -> CacheLevel.FULL
            else -> target.level
        }
        if (level == CacheLevel.NONE) continue
        // 相邻页已经有独立播放器并挂好源时，不再重复取地址。
        if (pooledPlayerSlot(target.index)?.sourcePrepared == true) continue
        val prime = level == CacheLevel.FULL && abs(target.index - anchor) <= 1
        if (prime && !feedPreloadState.priming.add(target.index)) continue
        dispatched++
        feedScope.launch {
            try {
                val detail = withContext(Dispatchers.IO) {
                    runCatching { warmCoordinator.detail(series.id) }.getOrNull()
                } ?: return@launch
                if (detail.episodes.isEmpty()) return@launch
                val saved = withContext(Dispatchers.IO) {
                    runCatching { lib.progress(series.id) }.getOrNull()
                }
                val epIndex = saved?.let { s ->
                    detail.episodes.indexOf(s.episodeId).takeIf { it >= 0 }
                } ?: 0
                val episodeId = detail.episodes.getOrNull(epIndex) ?: return@launch
                val prepared = withContext(Dispatchers.IO) {
                    runCatching {
                        warmCoordinator.warm(
                            detail=detail,
                            episodeId=episodeId,
                            quality=lib.maxQuality,
                            level=level,
                        )
                    }.getOrNull()
                }
                if (level == CacheLevel.FULL) {
                    if (prepared == null) return@launch
                    if (feedHidden || !isAttachedToWindow ||
                        feedItems.getOrNull(target.index)?.id != series.id ||
                        abs(target.index - feedPlayingIndex) > 1
                    ) {
                        runCatching { prepared.close() }
                        return@launch
                    }
                    primePooledPlayerImpl(
                        index=target.index,
                        detail=detail,
                        episodeIndex=epIndex,
                        episodeId=episodeId,
                        video=prepared,
                    )
                }
                if (com.hgtv.star.shared.BuildConfig.DEBUG) {
                    android.util.Log.i(
                        "HGTV-PHONE",
                        "preload done index=${target.index} level=$level " +
                            "anchor=$feedPlayingIndex",
                    )
                }
            } finally {
                if (prime) feedPreloadState.priming.remove(target.index)
            }
        }
    }
}
