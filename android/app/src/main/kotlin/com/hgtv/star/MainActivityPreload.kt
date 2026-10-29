// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import com.hgtv.star.core.CacheLevel
import com.hgtv.star.core.SlideCacheManager
import com.hgtv.star.core.RemoteVideo
import com.hgtv.star.core.lruPut

// 推荐页的「双向滑动预加载」编排：窗口与规则在 `core/SlideCache.kt`（纯逻辑、有单测），
// 这里只负责把它翻译成真正的工作——取详情、取地址、解析索引、拉首块，以及什么时候让路。

/**
 * 现在能不能做后台预加载。
 *
 * 文档第 7 节的 P0：**当前这一部的 buffer 低于 3 秒时，所有预加载让路**。
 * 理由很直接：预加载抢的是同一条下行带宽，而用户此刻正盯着的那一帧比「下一步可能看什么」重要得多。
 *
 * 播放器还没起来 / 已经暂停时不挡（那时没有 buffer 可抢）。
 */
private fun MainActivity.preloadGateOpen(): Boolean {
    val p=player ?: return true
    if(!p.isPlaying) return true
    val buffered=p.bufferedPosition-p.currentPosition
    if(buffered>=3_000L) return true
    if(BuildConfig.DEBUG) android.util.Log.i("HGTV-PERF","preloadSkip reason=buffer buffered=${buffered}ms")
    return false
}

/**
 * 一次计划最多派几条预加载。
 *
 * 计划本身给 5 条（前 3 + 后 2），但**同时**发出去 5 条的结果是每条都慢：
 * 这台盒子上一次取地址要 500~1100ms，5 条排队就是 5 秒，而用户 2 秒后可能已经换台了。
 * 4 条刚好覆盖「主方向三步 + 回看一步」。
 */
private const val PRELOAD_MAX_TARGETS=4

/**
 * 取消已经排定、还没开跑的那一次窗口预热。
 *
 * 换剧 / 离开推荐页 / 内存吃紧都要调：留着它等于「用户已经走了，两秒后还要按旧锚点去拉四条」。
 */
internal fun MainActivity.cancelScheduledPreload() {
    cancelWindowPreload()
    recommendDwellRunnable?.let { main.removeCallbacks(it) }
    recommendDwellRunnable=null
}

/** 只取消「窗口预热」那一次，不动停留计时（重排窗口时不该把停留判定也丢掉）。 */
private fun MainActivity.cancelWindowPreload() {
    recommendPreloadRunnable?.let { main.removeCallbacks(it) }
    recommendPreloadRunnable=null
}

/**
 * 排定一次窗口预热。
 *
 * 文档 4.1 的「首帧后延迟 2000ms」：首帧上屏之后解码器还在出帧、buffer 还在填，
 * 立刻去拉相邻几条会抢 CPU 与带宽（这台盒子上一次解密就要 200~400ms）。
 * 延迟期间用户如果已经滑走，[cancelScheduledPreload] 会把它取消。
 */
internal fun MainActivity.scheduleRecommendPreload(delayMs: Long,reason: String) {
    cancelWindowPreload()
    val runnable=Runnable {
        recommendPreloadRunnable=null
        if(isDestroyed || screen!="recommend") return@Runnable
        if(!preloadGateOpen()) {
            // 让路一次，1.5 秒后再看（那时 buffer 通常已经够了）。
            scheduleRecommendPreload(1_500L,"retry")
            return@Runnable
        }
        applyRecommendPlan(slideCache.plan(recommendIndex),"$reason")
    }
    recommendPreloadRunnable=runnable
    main.postDelayed(runnable,delayMs)
}

/**
 * 停留确认（文档 4.1 第二条）：当前这一部放了 1 秒还没被划走。
 *
 * 它只在一件事上起作用：快速连划时窗口被降级成「仅地址」，停下来之后要把
 * 「下一部」重新升回完整预热——否则用户停在某一条上，下一步按下去仍然是冷启动。
 */
internal fun MainActivity.scheduleRecommendDwell() {
    recommendDwellRunnable?.let { main.removeCallbacks(it) }
    // 只在「刚才是在连划」时才有意义：正常模式下主方向第一步本来就是完整预热，
    // 1 秒时再跑一遍计划只会提前跟 buffer 抢带宽（真正该跑的是 2 秒那一次）。
    if(slideCache.mode!=SlideCacheManager.Mode.FLING) { recommendDwellRunnable=null; return }
    val runnable=Runnable {
        recommendDwellRunnable=null
        if(isDestroyed || screen!="recommend") return@Runnable
        val plan=slideCache.onDwell(recommendIndex,android.os.SystemClock.elapsedRealtime())
        if(plan.mode==SlideCacheManager.Mode.NORMAL && preloadGateOpen()) applyRecommendPlan(plan,"dwell")
    }
    recommendDwellRunnable=runnable
    main.postDelayed(runnable,1_000L)
}

/**
 * 按一份计划真正发起预加载。
 *
 * 顺序就是计划里的顺序（主方向由近到远，再是反方向），因此带宽紧张时先到的是最该有的那一条。
 */
internal fun MainActivity.applyRecommendPlan(plan: SlideCacheManager.Plan,reason: String) {
    if(screen!="recommend") return
    if(BuildConfig.DEBUG) {
        val summary=plan.targets.joinToString(",") { "${it.index}:${it.level.name.first()}" }
        android.util.Log.i("HGTV-PERF","preload reason=$reason mode=${plan.mode} anchor=${plan.anchor} targets=$summary")
    }
    // 快速 fling：只留地址预取（文档 4.2）。数据预加载全部跳过——用户根本不会停在这些条目上。
    if(plan.mode==SlideCacheManager.Mode.FLING) {
        if(BuildConfig.DEBUG) android.util.Log.i("HGTV-PERF","preloadSkip reason=fling anchor=${plan.anchor}")
    }
    // 一次最多派 [PRELOAD_MAX_TARGETS] 条：计划里的顺序就是优先级，后面那些
    // （更远的地址预取）留给下一次换剧时再补。四条以上同时发只会互相抢带宽。
    var dispatched=0
    for(target in plan.targets) {
        if(dispatched>=PRELOAD_MAX_TARGETS) break
        val series=recommendItems.getOrNull(target.index) ?: continue
        dispatched++
        when(target.level) {
            CacheLevel.ADDRESS -> warmRecommend(series,target.index,CacheLevel.ADDRESS)
            CacheLevel.META -> warmRecommend(series,target.index,CacheLevel.META)
            CacheLevel.FULL -> warmRecommend(series,target.index,CacheLevel.FULL)
            CacheLevel.NONE -> Unit
        }
    }
}

/**
 * 把「某一部预热到某一级」派出去。
 *
 * 详情没有就先取详情（受 [DETAIL_PREFETCH_LIMIT] 限制），拿到之后按级别分派：
 *
 * | 级别 | 动作 | 数据量 |
 * |---|---|---|
 * | [CacheLevel.ADDRESS] | 只取播放地址（写盘） | ~20KB |
 * | [CacheLevel.META] | 取地址 + 解析索引（写盘），不取首块 | ~400KB |
 * | [CacheLevel.FULL] | 取地址 + 索引 + 首块 512KB，留在内存里等切换 | ~900KB |
 */
internal fun MainActivity.warmRecommend(series: com.hgtv.star.core.Series,index: Int,level: CacheLevel) {
    val cached=recommendDetails[series.id]
    if(cached!=null) {
        warmRecommendDetail(cached,level,index)
        return
    }
    if(series.id in recommendDetailLoading) return
    if(recommendDetailLoading.size>=DETAIL_PREFETCH_LIMIT) return
    recommendDetailLoading+=series.id
    io.submit {
        // 预热拿的也只是「第 1 集是哪个 vid」：走落盘缓存，连划时这几条不必各等一次 0.4~0.6s。
        val result=runCatching { repository.detail(series.id,cached=true) }
        main.post {
            recommendDetailLoading-=series.id
            if(isDestroyed || screen!="recommend") return@post
            result.onSuccess { data ->
                if(data.episodes.isEmpty()) return@onSuccess
                recommendDetails.lruPut(RECOMMEND_DETAIL_LIMIT,series.id,data)
                // 仍然在窗口里才继续（用户可能已经连着换了好几部）。
                val position=recommendItems.indexOfFirst { it.id==series.id }
                if(position>=0 && kotlin.math.abs(position-recommendIndex)<=4) warmRecommendDetail(data,level,position)
            }
        }
    }
}

/**
 * 淘汰「预热好的首集」：**方向偏置 LRU**（文档第 6 节）。
 *
 * 纯 LRU 对双向滑动不友好——用户滑回来时，刚被淘汰的那一份正是他要的。
 * 评分与窗口保护都在 [SlideCacheManager.evictionOrder] 里，这里只负责真的关掉。
 */
internal fun MainActivity.evictRecommendPrepared(limit: Int = RECOMMEND_PREPARED_LIMIT) {
    if(recommendPrepared.size<=limit) return
    val now=android.os.SystemClock.elapsedRealtime()
    val direction=slideCache.direction
    val entries=recommendPrepared.keys.map { key ->
        val index=recommendPreparedIndex[key] ?: -1
        SlideCacheManager.Entry(key,index,recommendPreparedAt[key] ?: 0L,slideCache.dwellOf(index),direction)
    }
    // 窗口外的先按评分淘汰；仍然超限时（窗口本身就有五六条）再按最旧的时间淘汰。
    // 下标未知的那些（-1）不参与评分：评分里的「距离」对它们没有意义，放到最后按时间淘汰。
    val known=entries.filter { it.index>=0 }
    val unknown=entries.filter { it.index<0 }.sortedBy { it.lastAccessAt }.map { it.key }
    val order=slideCache.evictionOrder(known,recommendIndex,now)+unknown
    var excess=recommendPrepared.size-limit
    for(key in order) {
        if(excess<=0) break
        val remote=recommendPrepared.remove(key) ?: continue
        recommendPreparedIndex.remove(key); recommendPreparedAt.remove(key)
        runCatching { remote.close() }
        // 内存里那份首块没了，但索引还在盘上（见 `PlaybackCache`）：级别降回 META，
        // 这样它万一又滑回窗口里，还能重新只补那 512KB，而不是整条重来。
        if(recommendWarmed[key]==CacheLevel.FULL) recommendWarmed[key]=CacheLevel.META
        excess--
        if(BuildConfig.DEBUG) android.util.Log.i("HGTV-PERF","evictReason=score key=$key left=${recommendPrepared.size}")
    }
}
