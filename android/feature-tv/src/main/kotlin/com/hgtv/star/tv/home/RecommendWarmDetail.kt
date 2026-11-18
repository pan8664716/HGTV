// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import com.hgtv.star.core.Detail
import com.hgtv.star.shared.compat.isMeteredConnection
import com.hgtv.star.shared.data.AppRuntime

/** 同时在途的相邻地址预热条数上限（WiFi）。 */
const val RECOMMEND_WARM_SLOTS=com.hgtv.star.core.AppConfig.Recommend.WARM_SLOTS
/** 移动网络下的相邻地址预热条数上限。 */
const val RECOMMEND_WARM_SLOTS_METERED=com.hgtv.star.core.AppConfig.Recommend.WARM_SLOTS_METERED

/**
 * 只取首集播放地址，不解析索引、不读取首块。
 *
 * 详情没有就交给 [warmRecommend] 先取详情。这里只把播放地址写进缓存，
 * 切换时仍然由播放内核正常取地址并解析当前视频。
 */
fun TvMainActivity.warmRecommendDetail(data: Detail,index: Int=-1): Boolean {
    if(screen!="recommend") return false
    // 当前这一部还没出画面时，任何相邻请求都不能抢首帧路径。
    if(!playbackReady) return false
    // 正在放的这一部不再重复取地址。
    if(data.series.id.isNotEmpty() && data.series.id==recommendItems.getOrNull(recommendIndex)?.id) return false
    val key=preparedKey(data)
    if(key.isEmpty()) return false
    // 地址已经落盘就不再重复请求。
    val achieved=recommendStore().warmedLevel(key)
    if(achieved!=null && achieved>=com.hgtv.star.core.CacheLevel.ADDRESS) return false
    if(recommendStore().isWarming(key)) return false
    if(!networkAvailable()) return false
    val metered=runCatching { connectivity.isMeteredConnection() }.getOrDefault(false)
    if(!hasWarmSlot(metered)) {
        if(BuildConfig.DEBUG) android.util.Log.i("HGTV-PERF","addressSkip reason=slot inFlight=${recommendStore().warmingCount()}")
        return false
    }
    recommendStore().beginWarm(key)
    val warm=RecommendWarm()
    recommendWarmJobs[key]=warm
    val quality=playbackMaxQuality
    warm.future=recommendWarmWorker.submit {
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
            main.post {
                // 只摘掉**自己**那一份：取消之后同一个键可能已经被新的一次预热占了
                // （划回去又划过来），无条件 remove 会把新的那一次从表里抹掉，
                // 它回来时就认不出自己、白拉一遍。
                val current=recommendWarmJobs[key]
                if(current===warm) {
                    recommendWarmJobs.remove(key)
                    recommendStore().finishWarm(key)
                } else {
                    return@post
                }
                if(isDestroyed || screen!="recommend") return@post
                recommendStore().finishWarm(key,com.hgtv.star.core.CacheLevel.ADDRESS)
                if(BuildConfig.DEBUG) {
                    android.util.Log.i(
                        "HGTV-PERF",
                        "addressDone index=$index key=$key "+
                            "took=${android.os.SystemClock.elapsedRealtime()-warm.startedAt}ms",
                    )
                }
                // 空出来的槽位马上补上。
                if(recommendStore().warmingCount()<RECOMMEND_WARM_SLOTS) prefetchRecommendNeighbors(recommendIndex)
            }
        } catch(problem: Exception) {
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
 * 地址预热还有没有并发额度。WiFi 最多两路，流量网络一路。
 */
private fun TvMainActivity.hasWarmSlot(metered: Boolean): Boolean {
    val inFlight=recommendStore().warmingCount()
    return inFlight<(if(metered) RECOMMEND_WARM_SLOTS_METERED else RECOMMEND_WARM_SLOTS)
}
