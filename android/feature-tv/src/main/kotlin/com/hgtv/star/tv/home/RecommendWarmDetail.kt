// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import com.hgtv.star.core.Detail
import com.hgtv.star.core.RemoteVideo
import com.hgtv.star.shared.compat.isMeteredConnection
import com.hgtv.star.shared.data.AppRuntime

/** 同时在途的预热条数上限（WiFi）。再多只会互相抢带宽，当前这一部反而起播更慢。 */
const val RECOMMEND_WARM_SLOTS=com.hgtv.star.core.AppConfig.Recommend.WARM_SLOTS
/** 同时在途的预热条数上限（移动网络）：够「下一部」就好，别拿用户流量去猜方向。 */
const val RECOMMEND_WARM_SLOTS_METERED=com.hgtv.star.core.AppConfig.Recommend.WARM_SLOTS_METERED

/**
 * 取首集地址并预热（索引 + 前约 2.5MB）。已经有同一份、或正在预热同一份时直接跳过。
 *
 * 三条与「手机比电视卡」直接相关的取舍：
 *
 * * **不再因为「移动网络」整条退出**。早先这里对 `isMeteredConnection()` 直接 return：
 *   电视永远是 WiFi / 有线，所以电视一切正常；手机一用流量，预热**一次都没有**，
 *   每次切换都是「详情 0.5s + 取地址 + 首批数据 1s」的冷启动——那才是触屏上那股卡劲。
 *   用户本来就在放视频流，相邻一部的约 2.5MB 与它同一个量级，省这一下毫无意义。
 *   现在改成「在途条数减到 1」，流量上仍然克制。
 * * **多槽**：单槽时新的一次会被旧的那次挡回去（`if(recommendWarming.isNotEmpty()) return`），
 *   连划几下就再也没有预热落地，[recommendPrepared] 一直是空的。
 * * **取消要连网络一起掐掉**（[cancelRecommendWarm]）：被作废的预热不占带宽。
 */
fun TvMainActivity.warmRecommendDetail(data: Detail,level: com.hgtv.star.core.CacheLevel=com.hgtv.star.core.CacheLevel.FULL,index: Int=-1): Boolean {
    if(screen!="recommend") return false
    // 推荐首屏还在转圈 / 当前这一部还没出画面：这时候预热只是往 `modelGate` 里排队，
    // 把**正在等的这一次取地址**推到后面去。
    //
    // 实测（2026-10-27，S905L3B / 2G）：冷启动进推荐，`Stage stream` 花了 2049ms，
    // 而这段时间里 `modelGate` 上排着三个请求——正片起播、相邻预热、下一集预取。
    // 三个都是 0.6~1.0 秒的往返，串行加起来正好两秒。
    if(!playbackReady) return false
    // 正在放的这一部不预热：它的首集已经在播放器手里（`recommendPrepared` 里那份已被取走），
    // 再 warm 一次等于把同一个 22MB 文件的 moov **再解析一遍**（两万多个样本 + 一次排序），
    // 两条线程各占一个核，起播那一下反而更慢。
    if(data.series.id.isNotEmpty() && data.series.id==recommendItems.getOrNull(recommendIndex)?.id) return false
    val key=preparedKey(data)
    if(key.isEmpty()) return false
    // 已经到这个级别（或更高）就跳过：ADDRESS 铺过之后再升 FULL 时，不必重取一次地址。
    val achieved=recommendStore().warmedLevel(key)
    if(achieved!=null && achieved>=level) return false
    if(recommendStore().isWarming(key)) return false
    if(!networkAvailable()) return false
    val metered=runCatching { connectivity.isMeteredConnection() }.getOrDefault(false)
    // 分级并发上限（文档第 7 节）：WiFi 同时最多 2 个完整预热，流量网络 1 个；
    // 元数据 / 地址预热仍按各自的更轻量额度执行。
    if(!hasWarmSlot(level,metered)) {
        if(BuildConfig.DEBUG) android.util.Log.i("HGTV-PERF","preloadSkip reason=slot level=$level inFlight=${recommendStore().warmingCount()}")
        return false
    }
    recommendStore().beginWarm(key)
    val warm=RecommendWarm()
    recommendWarmJobs[key]=warm
    val quality=playbackMaxQuality
    warm.future=recommendWarmWorker.submit {
        var remote: RemoteVideo?=null
        try {
            // client 与仓库都在工作线程上拿：两者都是懒建 / 进程级共享的，
            // 第一次触碰要建一个 OkHttpClient（解析系统根证书表）。放在主线程上取，
            // 等于把冷启动那段成本搬到「划一下」的那一帧里。
            val http=com.hgtv.star.shared.data.PlaybackTransport.client
            val source=AppRuntime.playbackRepository(this)
            val sourceCoordinator=com.hgtv.star.shared.architecture.PlaybackSourceCoordinator(source,http)
            val warmCoordinator=com.hgtv.star.shared.feed.RecommendWarmCoordinator(source,library,sourceCoordinator)
            if(!library.deviceRegistered()) { registerDeviceOnce(); source.inheritDevice(repository) }
            // 整段都在这一次预热自己的取消域里：划过去之后 `cancelRecommendWarm` 能立刻
            // 把在途的 HTTP 掐掉（否则那个线程还在读 512KB，结果没人要）。
            val prepared=warm.scope.run {
                warmCoordinator.warm(data,data.episodes.first(),quality,level).also { remote=it }
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
                    // 这一次预热已经被取消（它正好被起播了 / 划过去了 / 离开了推荐页）：
                    // 结果没人要，当场关掉那份首块与它的连接。
                    runCatching { prepared?.close() }
                    return@post
                }
                if(isDestroyed || screen!="recommend") { runCatching { prepared?.close() }; return@post }
                // 级别只升不降：这一次做到了哪一级，下一次就不会再重复做更低的那一级。
                recommendStore().finishWarm(key,level)
                if(prepared!=null) {
                    recommendPrepared[key]=prepared
                    recommendPreparedIndex[key]=if(index>=0) index else recommendItems.indexOfFirst { it.id==data.series.id }
                    recommendPreparedAt[key]=android.os.SystemClock.elapsedRealtime()
                    evictRecommendPrepared()
                }
                if(BuildConfig.DEBUG) {
                    android.util.Log.i(
                        "HGTV-PERF",
                        "warmDone level=$level index=$index key=$key prepared=${if(prepared!=null) 1 else 0} "+
                            "took=${android.os.SystemClock.elapsedRealtime()-warm.startedAt}ms "+
                            "preparedCount=${recommendPrepared.size}",
                    )
                }
                // 空出来的槽位马上补上：连划时总有两条在转（见 [prefetchRecommendNeighbors]）。
                if(recommendStore().warmingCount()<RECOMMEND_WARM_SLOTS) prefetchRecommendNeighbors(recommendIndex)
            }
        } catch(problem: Exception) {
            remote?.close()
            main.post {
                if(recommendWarmJobs.remove(key)===warm) recommendStore().finishWarm(key)
            }
            if(BuildConfig.DEBUG) {
                android.util.Log.w(
                    "HGTV-PERF",
                    "warmFailed level=$level index=$index key=$key "+
                        "error=${problem.javaClass.simpleName}:${problem.message.orEmpty().take(160)}",
                )
            }
        }
    }
    return true
}

/**
 * 这一级还有没有并发额度（文档第 7 节的优先级表）。
 *
 * 完整预热最贵（512KB + 一次索引解析），WiFi 最多两路；再多会让每条都变慢。
 * 流量网络仍只开一路。地址预取只有 20KB，可以多开几条。
 */
private fun TvMainActivity.hasWarmSlot(level: com.hgtv.star.core.CacheLevel,metered: Boolean): Boolean {
    val inFlight=recommendStore().warmingCount()
    return when(level) {
        com.hgtv.star.core.CacheLevel.FULL -> inFlight<(if(metered) RECOMMEND_WARM_SLOTS_METERED else RECOMMEND_WARM_SLOTS)
        com.hgtv.star.core.CacheLevel.META -> inFlight<(if(metered) 1 else 2)
        else -> inFlight<(if(metered) 2 else 3)
    }
}
