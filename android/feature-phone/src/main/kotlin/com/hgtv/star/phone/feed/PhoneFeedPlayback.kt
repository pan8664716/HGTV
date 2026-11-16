// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun PhoneFeedView.playIndexImpl(
    index: Int,
    startAt: Long = -1L,
    clearDanmakuOnSwitch: Boolean = false,
) {
    val series = feedItems.getOrNull(index) ?: return
    val previousIndex = feedPlayingIndex
    PhoneSession.rememberRecommendSeries(series, feedCacheKey())
    if (feedPlayingIndex >= 0 && feedPlayingIndex != index) settleRecommendWatch()
    feedRecommendBatchJob?.cancel()
    feedRecommendBatchJob = null
    feedRecommendBatchKey = ""
    // 滑到新的一屏 = 明确的「我要看这一部」：上一屏被用户按停过，不该带到这一屏。
    // 两条状态都要清：
    // * `paused` —— attach 里 `playWhenReady = !paused` 直接读它，留着就是「滑过来先是暂停的」；
    // * `PhoneSession.resumeOnAdopt` —— 同一部剧再次出现在流里时会走 adoptPlaying，
    //   那一条用的是这个全局意图；它在用户暂停后离开页面时被写成 false，不清就会把
    //   暂停状态一路带到下一屏。
    feedPaused = false
    PhoneSession.resumeOnAdopt = true
    feedPlayingDetailSlot = null
    val direction = if (feedPlayingIndex >= 0 && index != feedPlayingIndex) {
        if (index > feedPlayingIndex) 1 else -1
    } else {
        0
    }
    feedPlayingIndex = index
    // 每次明确的换作都推进代次。池命中也必须作废上一轮还在飞的详情 / 地址请求。
    val token = ++feedPlayTokenSlot
    if (clearDanmakuOnSwitch && previousIndex >= 0 && previousIndex != index) {
        danmakuLoaderOfImpl(previousIndex).clear()
    }
    // 记下落盘的「停在哪一部」：下次冷启动从它的**下一部**接着播（同电视端 rememberPlayed）。
    runCatching {
        com.hgtv.star.core.RecommendCache.rememberPlayed(series.id, feedCacheKey())
    }
    if (feedRecommendWatchingId != series.id) {
        feedRecommendWatchingId = series.id
        feedRecommendWatchStart = SystemClock.elapsedRealtime()
    }
    // 「真的上屏了」才记进曝光窗口（与电视端 playRecommendAt → markRecommended 同一条规则）：
    // 一页 20 条里用户可能只看第一条，把整页都记成已推等于白白烧掉 19 部内容。
    feedRecommendPlayback.rememberShown(series)
    if (com.hgtv.star.shared.BuildConfig.DEBUG) {
        android.util.Log.i(
            "HGTV-PHONE",
            "feed play genre=${feedCacheKey()} index=$index dir=$direction series=${series.id}",
        )
    }
    // 窗口规则与电视端同一份（core/SlideCache）：换一部、方向、快滑、回看都在这里判。
    // 返回的计划**要留下**：它带着这一次的判定原因（WINDOW / REVISIT / FLING），
    // 回看那一部时主方向第二步会被升到完整预热（见 SlideCacheManager.plan）。
    // 早先这里把返回值丢掉、预热时重新 plan(index)，原因永远是 WINDOW，
    // 于是「滑走又滑回来」那一条享受不到提升，回来的第一下仍是冷启动。
    feedPendingPlan = feedPreloadState.slideCache.onItemChanged(
        index,
        direction,
        SystemClock.elapsedRealtime(),
    )
    scheduleDwell()
    // 进程里如果还挂着**这部剧**的这一路（从正片退回来、视图刚被重建），先试着接。
    // 接上就什么都不做：不取详情、不取地址、不 prepare、不 seek——画面就是原来那一秒。
    if (startAt < 0 && adoptPlayingForPlayback(clearDanmakuOnSwitch)) {
        PhoneSession.endHandoff()
        schedulePreload(feedFirstFrameDelay())
        return
    }
    // 相邻页已经有一台独立播放器时直接激活它。这里不发生 Surface 交接，
    // 也不重新 setMediaSource / prepare，因此上一部和下一部不会共享输出。
    val pooled = pooledPlayerSlot(index)
    if (pooled?.sourcePrepared == true && !pooled.sourceFresh()) {
        releasePooledPlayerSlot(index)
    }
    if (
        startAt < 0 &&
        pooled?.sourcePrepared == true &&
        pooled.sourceFresh() &&
        activatePreparedPooledPlayerImpl(index, pooled, clearDanmakuOnSwitch)
    ) {
        schedulePreload(feedFirstFrameDelay())
        return
    }
    // 池里没有这一项，需要走详情 / 地址准备。旧页仍保留在池里供回看，
    // 但必须先停掉它的声音；否则新页还在取地址时，用户听到的仍是上一部。
    pausePooledPlayersExcept(null)
    feedScope.launch {
        val lib = feedLibrary ?: PhoneServices.awaitLibrary(context).also {
            feedLibrarySlot = it
        }
        val prepared = withContext(Dispatchers.IO) {
            runCatching {
                feedRecommendPlayback.prepare(
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
        if (token != feedPlayToken) {
            prepared?.remote?.close()
            return@launch
        }
        if (prepared == null) {
            feedRecommendPlayback.noteFailure()
            feedRecommendPlayback.reportEvent(
                series,
                com.hgtv.star.core.RecommendBehavior.Event.ERROR,
                episodeId=feedCurrentEpisodeIds[index].orEmpty(),
                episodeIndex=feedCurrentEpisodeIndex[index] ?: -1,
                reason="prepare_failed",
            )
            return@launch
        }
        feedPlayingDetailSlot = prepared.detail
        feedCurrentEpisodeIds[index] = prepared.episodeId
        feedCurrentEpisodeIndex[index] = prepared.episodeIndex
        attachImpl(index, prepared.remote, prepared.position, clearDanmakuOnSwitch)
    }
    // 首帧之后再排预热（与电视端「首帧 + 2 秒」同一条：此刻解码器还在出帧、buffer 还在填）。
    schedulePreload(feedFirstFrameDelay())
}
