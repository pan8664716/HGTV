// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import com.hgtv.star.core.SlideCacheManager
import com.hgtv.star.core.detail
import com.hgtv.star.core.lruPut
import com.hgtv.star.shared.compat.lowEndTv

// 推荐页的「双向滑动预加载」编排：窗口与规则在 `core/SlideCache.kt`（纯逻辑、有单测），
// 这里只负责取详情和播放地址，不读取视频索引或首块。

/**
 * 现在能不能做后台预加载。
 *
 * 文档第 7 节的 P0：**当前这一部的 buffer 低于 1.5 秒时，所有预加载让路**。
 * 理由很直接：预加载抢的是同一条下行带宽，而用户此刻正盯着的那一帧比「下一步可能看什么」重要得多。
 *
 * 播放器还没起来 / 已经暂停时不挡（那时没有 buffer 可抢）。
 */
private fun TvMainActivity.preloadGateOpen(): Boolean {
    val p=player ?: return true
    if(!p.isPlaying) return true
    val buffered=p.bufferedPosition-p.currentPosition
    if(buffered>=1_500L) return true
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
private const val PRELOAD_MAX_TARGETS=com.hgtv.star.core.AppConfig.Playback.PRELOAD_MAX_TARGETS

/**
 * 取消已经排定、还没开跑的那一次窗口预热。
 *
 * 换剧 / 离开推荐页 / 内存吃紧都要调：留着它等于「用户已经走了，两秒后还要按旧锚点去拉四条」。
 */
fun TvMainActivity.cancelScheduledPreload() {
    preloadScheduler().cancel()
}

/**
 * 排定一次窗口预热。
 *
 * 首帧后只等 300ms：解码器还在出帧、buffer 还在填，
 * 立刻去拉相邻几条会抢 CPU 与带宽（这台盒子上一次解密就要 200~400ms）。
 * 延迟期间用户如果已经滑走，[cancelScheduledPreload] 会把它取消。
 *
 * 延迟 / 让路重试 / 停留确认 / 取消这四件事的编排在 `:shared` 的
 * [com.hgtv.star.shared.feed.PreloadScheduler] 里，手机端用的是**同一个类**：
 * 早先两端各写一遍，同一个问题常常只修在一端。
 */
fun TvMainActivity.scheduleRecommendPreload(delayMs: Long,reason: String) {
    preloadScheduler().scheduleWindow(delayMs,reason)
}

/**
 * 停留确认（文档 4.1 第二条）：当前这一部放了 1 秒还没被划走。
 *
 * 它只在一件事上起作用：快速连划后停下来，重新排一次「下一部」的地址预热。
 */
fun TvMainActivity.scheduleRecommendDwell() {
    preloadScheduler().scheduleDwell()
}

/**
 * 本页那份预热排程（懒建一次）。
 *
 * 注入的都是「这一端才有的东西」：锚点下标、是否还在推荐页、带宽是否让路、真正派发。
 * 排程规则本身不在各端。
 */
private fun TvMainActivity.preloadScheduler(): com.hgtv.star.shared.feed.PreloadScheduler =
    preloadSchedulerRef ?: com.hgtv.star.shared.feed.PreloadScheduler(
        slideCache=slideCache,
        anchor={ recommendIndex },
        active={ !isDestroyed && screen=="recommend" },
        gateOpen={ preloadGateOpen() },
        plan={ slideCache.neighborWindow(recommendIndex,slideCache.direction) },
        apply={ planned,reason -> applyRecommendPlan(planned,reason) },
        main=main,
    ).also { preloadSchedulerRef=it }

/**
 * 按一份计划真正发起预加载。
 *
 * 顺序就是计划里的顺序（主方向由近到远，再是反方向），因此带宽紧张时先到的是最该有的那一条。
 */
fun TvMainActivity.applyRecommendPlan(plan: SlideCacheManager.Plan,reason: String) {
    if(screen!="recommend") return
    if(BuildConfig.DEBUG) {
        val summary=plan.targets.joinToString(",") { "${it.index}:${it.level.name.first()}" }
        android.util.Log.i("HGTV-PERF","preload reason=$reason mode=${plan.mode} anchor=${plan.anchor} targets=$summary")
    }
    // 快速 fling：只留地址预取（文档 4.2）。数据预加载全部跳过——用户根本不会停在这些条目上。
    if(plan.mode==SlideCacheManager.Mode.FLING) {
        if(BuildConfig.DEBUG) android.util.Log.i("HGTV-PERF","preloadSkip reason=fling anchor=${plan.anchor}")
    }
    // 低配电视首帧后先只准备主方向最近的一部，完成后由 warmRecommendDetail 自动续上
    // 其余相邻作品，避免多个详情 / 地址请求同时抢当前画面恢复时的网络。
    val targets=if(reason=="first-frame" && runCatching { lowEndTv() }.getOrDefault(true)) {
        // 片尾附近主方向可能越界；仍要从计划里取第一条真实存在的目标，
        // 不能让一个空下标挡住后面的反方向缓存。
        plan.targets.filter { it.index in recommendItems.indices }.take(1)
    } else {
        plan.targets
    }
    // 一次最多派 [PRELOAD_MAX_TARGETS] 条：计划里的顺序就是优先级，后面那些
    // （更远的地址预取）留给下一次换剧时再补。四条以上同时发只会互相抢带宽。
    var dispatched=0
    for(target in targets) {
        if(dispatched>=PRELOAD_MAX_TARGETS) break
        val series=recommendItems.getOrNull(target.index) ?: continue
        if(target.level==com.hgtv.star.core.CacheLevel.NONE) continue
        val accepted=warmRecommend(series,target.index)
        if(accepted) dispatched++
    }
}

/**
 * 把「某一部」的详情与播放地址预热出去。
 *
 * 详情没有就先取详情（受 [DETAIL_PREFETCH_LIMIT] 限制），拿到之后只取播放地址。
 * 不创建 RemoteVideo，因此不会解析索引，也不会读取任何视频字节。
 */
fun TvMainActivity.warmRecommend(series: com.hgtv.star.core.Series,index: Int): Boolean {
    val cached=recommendDetails[series.id]
    if(BuildConfig.DEBUG) {
        android.util.Log.i("HGTV-PERF","addressDispatch index=$index detail=${if(cached!=null) 1 else 0}")
    }
    if(cached!=null) {
        prefetchRecommendCovers(listOf(cached))
        return warmRecommendDetail(cached,index)
    }
    if(series.id in recommendDetailLoading) return false
    if(recommendDetailLoading.size>=DETAIL_PREFETCH_LIMIT) return false
    recommendDetailLoading+=series.id
    io.submit {
        // 预热拿的也只是「第 1 集是哪个 vid」：走落盘缓存，连划时这几条不必各等一次 0.4~0.6s。
        val result=runCatching { repository.detail(series.id,cached=true,includeAuthor=false) }
        main.post {
            recommendDetailLoading-=series.id
            if(isDestroyed || screen!="recommend") return@post
            result.onSuccess { data ->
                if(data.episodes.isEmpty()) return@onSuccess
                recommendDetails.lruPut(RECOMMEND_DETAIL_LIMIT,series.id,data)
                prefetchRecommendCovers(listOf(data))
                // 仍然在窗口里才继续（用户可能已经连着换了好几部）。
                val position=recommendItems.indexOfFirst { it.id==series.id }
                if(position>=0 && kotlin.math.abs(position-recommendIndex)<=4) warmRecommendDetail(data,position)
            }.onFailure { problem ->
                if(BuildConfig.DEBUG) {
                    android.util.Log.w(
                        "HGTV-PERF",
                        "addressDetailFailed index=$index id=${series.id} "+
                            "error=${problem.javaClass.simpleName}:${problem.message.orEmpty().take(160)}",
                    )
                }
            }
        }
    }
    return true
}
