// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import com.hgtv.star.core.CacheLevel
import com.hgtv.star.core.Detail
import com.hgtv.star.shared.compat.isMeteredConnection
import com.hgtv.star.shared.data.AppRuntime

/** 同时在途的相邻地址预热条数上限（WiFi）。 */
const val RECOMMEND_WARM_SLOTS=com.hgtv.star.core.AppConfig.Recommend.WARM_SLOTS
/** 移动网络下的相邻地址预热条数上限。 */
const val RECOMMEND_WARM_SLOTS_METERED=com.hgtv.star.core.AppConfig.Recommend.WARM_SLOTS_METERED
/** 单槽片源只保留首帧后约 1MB，避免相邻作品长期占用过多 Java 堆。 */
private const val RECOMMEND_PREPARED_BYTES=com.hgtv.star.core.AppConfig.Playback.HEAD_MAX_BYTES
/** 当前画面缓冲低于它就只做地址预热，把带宽留给正在播放的这一部。 */
private const val RECOMMEND_PREPARED_MIN_BUFFER_MS=1_500L

/**
 * 预热相邻作品的首集播放源。
 *
 * 非主方向目标只取播放地址；主方向最近的一部额外解析索引并准备约 1MB 首块，
 * 完成后进入 [recommendPrepared] 单槽，换剧时直接交给现有播放内核。
 */
fun TvMainActivity.warmRecommendDetail(
    data: Detail,
    index: Int=-1,
    prepareSource: Boolean=false,
): Boolean {
    if(screen!="recommend") return false
    // 当前这一部还没出画面时，任何相邻请求都不能抢首帧路径。
    if(!playbackReady) return false
    // 正在放的这一部不再重复取地址。
    if(data.series.id.isNotEmpty() && data.series.id==recommendItems.getOrNull(recommendIndex)?.id) return false
    val key=preparedKey(data)
    if(key.isEmpty()) return false
    val full=prepareSource && canPrepareRecommendSource()
    val preparedSlotKey=recommendPreparedKey(data.series.id,playbackMaxQuality)
    val targetLevel=if(full) CacheLevel.FULL else CacheLevel.ADDRESS
    // 已经达到本次目标级别就不再重复请求。
    val achieved=recommendStore().warmedLevel(key)
    if(achieved!=null && achieved>=targetLevel) return false
    if(recommendStore().isWarming(key)) return false
    if(!networkAvailable()) return false
    val metered=runCatching { connectivity.isMeteredConnection() }.getOrDefault(false)
    if(!hasWarmSlot(metered)) {
        if(BuildConfig.DEBUG) android.util.Log.i("HGTV-PERF","addressSkip reason=slot inFlight=${recommendStore().warmingCount()}")
        return false
    }
    recommendStore().beginWarm(key)
    val warm=RecommendWarm()
    warm.preparedKey=if(full) preparedSlotKey else ""
    recommendWarmJobs[key]=warm
    val quality=playbackMaxQuality
    val preparedTicket=if(full) recommendPrepared.begin(warm,preparedSlotKey) else null
    warm.future=recommendWarmWorker.submit {
        var level=CacheLevel.NONE
        try {
            // client 与仓库都在工作线程上拿：两者都是懒建 / 进程级共享的，
            // 第一次触碰要建一个 OkHttpClient（解析系统根证书表）。放在主线程上取，
            // 等于把冷启动那段成本搬到「划一下」的那一帧里。
            val http=com.hgtv.star.shared.data.PlaybackTransport.client
            val source=AppRuntime.playbackRepository(this)
            val sourceCoordinator=com.hgtv.star.shared.architecture.PlaybackSourceCoordinator(source,http)
            val warmCoordinator=com.hgtv.star.shared.feed.RecommendWarmCoordinator(source,library,sourceCoordinator)
            if(!library.deviceRegistered()) { registerDeviceOnce(); source.inheritDevice(repository) }
            // 整段都在这一次预热自己的取消域里：划过去之后 `cancelRecommendWarm`
            // 能立刻把在途的取地址请求掐掉。
            warm.scope.run {
                warmCoordinator.prefetch(data,data.episodes.first(),quality)
            }
            level=CacheLevel.ADDRESS
            if(full && preparedTicket!=null) {
                try {
                    val episode=data.episodes.first()
                    val info=sourceCoordinator.stream(episode,quality,data.playback,direct=true)
                    val prepared=sourceCoordinator.warm(episode,info) {
                        it.warm(RECOMMEND_PREPARED_BYTES)
                    }
                    if(recommendPrepared.complete(warm,preparedTicket,preparedSlotKey,prepared)) {
                        level=CacheLevel.FULL
                    }
                } catch(problem: Exception) {
                    recommendPrepared.fail(warm,preparedSlotKey)
                    if(problem is InterruptedException) Thread.currentThread().interrupt()
                    if(BuildConfig.DEBUG) {
                        android.util.Log.w(
                            "HGTV-PERF",
                            "preparedFailed index=$index key=$preparedSlotKey "+
                                "error=${problem.javaClass.simpleName}:${problem.message.orEmpty().take(160)}",
                        )
                    }
                }
            }
            main.post {
                // 只摘掉**自己**那一份：取消之后同一个键可能已经被新的一次预热占了
                // （划回去又划过来），无条件 remove 会把新的那一次从表里抹掉，
                // 它回来时就认不出自己、白拉一遍。
                val current=recommendWarmJobs[key]
                if(current===warm) {
                    recommendWarmJobs.remove(key)
                } else {
                    return@post
                }
                if(isDestroyed || screen!="recommend") {
                    recommendStore().cancelWarm(key)
                    recommendPrepared.cancel(preparedSlotKey)
                    return@post
                }
                recommendStore().finishWarm(key,level.takeIf { it!=CacheLevel.NONE })
                if(BuildConfig.DEBUG) {
                    android.util.Log.i(
                        "HGTV-PERF",
                        "${if(level>=CacheLevel.FULL) "preparedDone" else "addressDone"} "+
                            "index=$index key=$key "+
                            "took=${android.os.SystemClock.elapsedRealtime()-warm.startedAt}ms",
                    )
                }
                // 空出来的槽位马上补上。
                if(recommendStore().warmingCount()<RECOMMEND_WARM_SLOTS) prefetchRecommendNeighbors(recommendIndex)
            }
        } catch(problem: Exception) {
            recommendPrepared.fail(warm,preparedSlotKey)
            main.post {
                if(recommendWarmJobs.remove(key)===warm) recommendStore().finishWarm(key)
            }
            if(BuildConfig.DEBUG) {
                android.util.Log.w(
                    "HGTV-PERF",
                    "addressFailed index=$index key=$key "+
                        "error=${problem.javaClass.simpleName}:${problem.message.orEmpty().take(160)}",
                )
            }
        }
    }
    return true
}

/**
 * 只有非计费网络且当前播放器缓冲充足时，才把主方向最近一部提升到完整片源。
 */
private fun TvMainActivity.canPrepareRecommendSource(): Boolean {
    if(!networkAvailable()) return false
    if(runCatching { connectivity.isMeteredConnection() }.getOrDefault(true)) return false
    val p=player ?: return true
    if(!p.isPlaying) return true
    return p.bufferedPosition-p.currentPosition>=RECOMMEND_PREPARED_MIN_BUFFER_MS
}

/** 推荐单槽片源的键只认剧与清晰度；首集 id 由预热详情决定。 */
internal fun recommendPreparedKey(seriesId: String,quality: Int): String =
    "recommend:$seriesId:$quality"

/**
 * 地址预热还有没有并发额度。WiFi 最多两路，流量网络一路。
 */
private fun TvMainActivity.hasWarmSlot(metered: Boolean): Boolean {
    val inFlight=recommendStore().warmingCount()
    return inFlight<(if(metered) RECOMMEND_WARM_SLOTS_METERED else RECOMMEND_WARM_SLOTS)
}
