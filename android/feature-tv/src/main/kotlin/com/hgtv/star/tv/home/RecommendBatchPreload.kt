// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import com.hgtv.star.core.lruPut
import com.hgtv.star.core.detail
import com.hgtv.star.core.prefetchModels

/** 相邻一部的详情 / 地址 / 首块优先；播放模型批量预取晚一点再跑。 */
internal const val RECOMMEND_BATCH_MODEL_DELAY_MS=com.hgtv.star.core.AppConfig.Recommend.BATCH_MODEL_DELAY_MS

/**
 * 把推荐列表里当前这一部**前后相邻的几部**预热起来。
 *
 * 分两步，都是为了「一划就换台」：
 * 1. 详情（分集表）：一部几百毫秒，先取回来放进 [recommendDetails]；
 * 2. 首集数据：详情到手后取播放地址并把索引 / 前 1MB 拉下来（[RemoteVideo.warm]），
 *    放进 [recommendPrepared]，起播时直接交给播放器。
 *
 * ## 为什么按方向、而且不止一部
 *
 * 遥控器的节奏是「按住 → 一秒一下」，预热 ±1 就够：每换一部，新邻居刚好被上一次预热覆盖。
 * 手机 / 平板是**一划一部、一秒能划两三部**：只预热 ±1 时，第二下就落在没有预热的条目上，
 * 于是「每一次切换都在等网络」——观感就是「手机上特别卡」。
 * 因此顺序是「正在走的方向两步 → 反方向一步」，在途上限见 [warmRecommendDetail]。
 */
fun TvMainActivity.prefetchRecommendNeighbors(index: Int,direction: Int=recommendPrefetchDir) {
    if(screen!="recommend") return
    // 窗口与级别由 [SlideCacheManager] 决定（非对称前后窗口 + fling 降级 + 回看提升），
    // 这里只按它给的清单发起。原先写死的「前两步 + 反方向一步」在双向滑动下有两个洞：
    // 回看方向没有货、连划时四条 1MB 互相抢带宽。
    applyRecommendPlan(slideCache.plan(index),"window")
}

/** 换剧 / 进正片时作废上一部还没发完的推荐批量预取。 */
fun TvMainActivity.cancelRecommendBatchModels(resetWindow: Boolean = true) {
    recommendBatchJob?.cancel(true)
    recommendBatchJob=null
    recommendBatchKey=""
    if(resetWindow) recommendPlaybackBatchState.reset()
}

/**
 * 推荐页起播后预取 20 个播放模型：
 * 当前剧的下一集 + 后面 19 部不同推荐剧的首集；消耗 10 条后再补下一批。
 */
fun TvMainActivity.prefetchRecommendBatchModels() {
    if(screen!="recommend") return
    val data=detail ?: return
    if(!recommendPlaybackBatchState.shouldSchedule(recommendIndex)) return
    val currentNext=data.episodes.getOrNull(episodeIndex+1).orEmpty()
    val candidates=(recommendIndex+1 until recommendItems.size)
        .mapNotNull { recommendItems.getOrNull(it) }
        .filter { it.id!=data.series.id }
        .distinctBy { it.id }
        .take(com.hgtv.star.core.AppConfig.Recommend.BATCH_MODEL_SIZE)
    val key="${data.series.id}:$currentNext:${candidates.joinToString(",") { it.id }}"
    if(recommendBatchKey==key && recommendBatchJob?.isDone==false) return
    recommendBatchJob?.cancel(true)
    recommendBatchKey=key
    // 主线程先取一份详情快照，后台线程不再直接读写这个 LRU。
    val known=buildMap {
        for(series in candidates) recommendDetails[series.id]?.let { put(series.id,it) }
    }
    recommendBatchJob=recommendWarmWorker.submit {
        val ids=linkedSetOf<String>()
        currentNext.takeIf { it.isNotBlank() }?.let(ids::add)
        try {
            if(!library.deviceRegistered()) registerDeviceOnce()
            val target=com.hgtv.star.core.AppConfig.Recommend.BATCH_MODEL_SIZE
            for(series in candidates) {
                if(ids.size>=target) break
                val item=known[series.id]
                    ?: runCatching { repository.detail(series.id,includeAuthor=false) }.getOrNull()
                    ?: continue
                val first=item.episodes.firstOrNull() ?: continue
                if(ids.add(first) && known[series.id]==null) {
                    main.post { if(!isDestroyed) recommendDetails.lruPut(RECOMMEND_DETAIL_LIMIT,series.id,item) }
                }
            }
            if(ids.isNotEmpty()) repository.prefetchModels(ids.toList(),data.playback)
            if(BuildConfig.DEBUG) android.util.Log.i("HGTV-PERF","recommend batch models=${ids.size} currentNext=${currentNext.isNotEmpty()}")
        } catch(_: Exception) {
            // 批量模型是后台加速，不影响当前推荐起播。
        } finally {
            main.post {
                if(recommendBatchKey==key) {
                    recommendBatchJob=null
                    recommendBatchKey=""
                }
            }
        }
    }
}

/** 正式播放首帧稳定后，用 preload 在后台补当前剧后 6 集。 */
fun TvMainActivity.prefetchFormalBatchModels() {
    if(screen!="player") return
    val data=detail ?: return
    // 给 preload 一个更宽窗口；仓库会跳过 player 已缓存的当前 6 集，只补后面的 6 集。
    val ids=data.episodes.drop(episodeIndex+1).take(12)
    if(ids.isEmpty()) return
    val key="${data.series.id}:$episodeIndex:${ids.joinToString(",")}"
    if(formalBatchKey==key && formalBatchJob?.isDone==false) return
    formalBatchJob?.cancel(true)
    formalBatchKey=key
    formalBatchJob=recommendWarmWorker.submit {
        try {
            if(!library.deviceRegistered()) registerDeviceOnce()
            repository.prefetchModels(ids,data.playback)
            if(BuildConfig.DEBUG) android.util.Log.i("HGTV-PERF","formal batch models=${ids.size}")
        } catch(_: Exception) {
            // 批量预取失败时，起播仍可走单集兼容兜底。
        } finally {
            main.post {
                if(formalBatchKey==key) {
                    formalBatchJob=null
                    formalBatchKey=""
                }
            }
        }
    }
}
