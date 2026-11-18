// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.hgtv.star.core.SlideCacheManager
import com.hgtv.star.shared.feed.PreloadScheduler
import com.hgtv.star.shared.feed.RecommendWarmCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class PhoneFeedPreloadState(private val host: PhoneFeedView) {
    val slideCache = SlideCacheManager()
    var preloadAt = 0L
    var pendingPlan: SlideCacheManager.Plan? = null
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

/** 带宽能不能让出来：正在放的那一部 buffer 不足 3 秒时，预加载不该抢同一条下行。 */
internal fun PhoneFeedView.preloadGateOpen(): Boolean {
    val exo = feedPlayer ?: return true
    if (!exo.isPlaying) return true
    return exo.bufferedPosition - exo.currentPosition >= 3_000L
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
 * 每个目标只调一次 `stream()` 把播放地址落进缓存，不读取视频字节。
 */
internal fun PhoneFeedView.applyPreload(plan: SlideCacheManager.Plan) {
    val lib = feedLibrary ?: return
    val repo = PhoneServices.repository(context)
    val warmCoordinator=RecommendWarmCoordinator(repo,lib,feedPlaybackSource)
    var dispatched = 0
    for (target in plan.targets) {
        if (dispatched >= 5) break
        val series = feedItems.getOrNull(target.index) ?: continue
        // 正在放的那一部不预热。
        if (target.index == feedPlayingIndex) continue
        if (target.level == com.hgtv.star.core.CacheLevel.NONE) continue
        // 相邻页已经有独立播放器并挂好源时，不再重复取地址。
        if (pooledPlayerSlot(target.index)?.sourcePrepared == true) continue
        dispatched++
        feedScope.launch {
            val detail = withContext(Dispatchers.IO) {
                runCatching { warmCoordinator.detail(series.id) }.getOrNull()
            } ?: return@launch
            if (detail.episodes.isEmpty()) return@launch
            val saved = withContext(Dispatchers.IO) {
                runCatching { lib.progress(series.id) }.getOrNull()
            }
            val epIndex = saved?.let { s -> detail.episodes.indexOf(s.episodeId).takeIf { it >= 0 } } ?: 0
            val episodeId = detail.episodes.getOrNull(epIndex) ?: return@launch
            withContext(Dispatchers.IO) {
                runCatching {
                    warmCoordinator.prefetch(
                        detail=detail,
                        episodeId=episodeId,
                        quality=lib.maxQuality,
                    )
                }
            }
            if (com.hgtv.star.shared.BuildConfig.DEBUG) {
                android.util.Log.i(
                    "HGTV-PHONE",
                    "address preload done index=${target.index} anchor=$feedPlayingIndex"
                )
            }
        }
    }
}
