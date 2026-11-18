// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.view.View
import com.hgtv.star.core.Detail
import com.hgtv.star.shared.compat.isMeteredConnection
import com.hgtv.star.shared.debug.StartupDeferredTasks
import com.hgtv.star.shared.debug.StartupTrace

/**
 * 「在屏幕上待多久算看了」的两个门槛（都取小一点的那一个判到就算看了）：
 * 停留 6 秒，或者真的播了 5 秒。
 *
 * 为什么两条：只按停留会误判——详情取不回来时用户也停在一屏「正在加载」上；
 * 只按播放会漏掉「静音看了个开头就换台」那些——播放器还没报出位置。
 * 这两个数值只决定**冷却时长**（14 天 / 30 天），不决定推不推，因此不需要更精细。
 */
private const val WATCH_DWELL_MS=com.hgtv.star.core.RecommendBehavior.WATCH_DWELL_MS
private const val WATCH_PLAY_MS=com.hgtv.star.core.RecommendBehavior.WATCH_PLAY_MS
/**
 * 「连划」的下限：停留不到这么久就不当成一次「不喜欢」。
 *
 * 手机上一秒划两三部时，每一下都会走到 [settleRecommendWatch]；早先这些全被判成
 * 「一进来就划走」——14 天冷却 + 同类负反馈，一批连划下来等于把好几类都记成「讨厌」，
 * 下一次推荐自然就不准了。上过屏这件事已经由 [markRecommended] 记了 7 天曝光，
 * 这里只负责别把「没看清」当成「不喜欢」。
 */
private const val QUICK_SWIPE_MS=com.hgtv.star.core.RecommendBehavior.QUICK_SWIPE_MS

/**
 * 结掉「当前这一部在推荐页待了多久」，写进曝光窗口。
 *
 * 这是文档 §2.3 里 `repeat_penalty` 的客户端等价物：系统不告诉我们「这一部你喜不喜欢」，
 * 但「一进来就划走」与「看了几秒」是两种完全不同的信号，冷却时长差一倍。
 * 只延长不缩短（见 [com.hgtv.star.core.RecommendMarks.mark]），因此重复调用无害。
 */
fun TvMainActivity.settleRecommendWatch() {
    val played=runCatching { player?.currentPosition ?: 0L }.getOrDefault(0L)
    recommendPlaybackController.settleWatch(played)
}

/** 推荐页当前这一部真正开始出画面时，交给两端共用的曝光上报器。 */
fun TvMainActivity.reportRecommendImpression() {
    if(screen!="recommend") return
    val series=recommendItems.getOrNull(recommendIndex) ?: return
    val data=detail
    val exposureSeries=series.copy(
        contentType=series.contentType.takeIf { it>0 } ?: data?.playback?.contentType ?: 0,
    )
    recommendPlaybackController.reportImpression(exposureSeries)
    val episodeId=data?.episodes?.getOrNull(episodeIndex).orEmpty()
    recommendPlaybackController.reportPlayStart(
        series,
        episodeId=episodeId,
        episodeIndex=episodeIndex,
        positionMs=runCatching { player?.currentPosition ?: 0L }.getOrDefault(0L),
        durationMs=runCatching { player?.duration ?: 0L }.getOrDefault(0L),
    )
}

/** 推荐来源的播放进度，15 秒一次；正式播放页和推荐内嵌播放都走这里。 */
fun TvMainActivity.reportRecommendProgress() {
    if(screen!="recommend" && !playbackFromRecommend) return
    val data=detail ?: return
    val p=player ?: return
    val episodeId=data.episodes.getOrNull(episodeIndex) ?: return
    recommendPlaybackController.reportProgressById(
        data.series.id,
        episodeId,
        episodeIndex,
        p.currentPosition.coerceAtLeast(0),
        p.duration.coerceAtLeast(0),
    )
}

/**
 * 真正起播一集。
 *
 * 从第 1 集开始（推荐是「随便看看」，不从上次的进度续）；
 * `playEpisode` 内部会按进入时的 `screen` 决定留在 `recommend`（见那里的 `recommendEntry`），
 * 因此这里不需要再改回来。
 */
internal fun TvMainActivity.startRecommendEpisode(data: Detail,position: Long,autoplay: Boolean=true) {
    val startedAt=StartupTrace.now()
    StartupTrace.mark(
        "recommend.episode.start",
        "id=${data.series.id} title=${data.series.title.take(40)} autoplay=$autoplay",
    )
    cancelRecommendBatchModels(resetWindow=false)
    // READY 与真正首帧都会尝试排一次预热；这是新一部的预热轮次，不能沿用上一部的标记。
    recommendAfterFirstFrameScheduled=false
    detail=data
    recommendHistoryPending=false
    // 从这里开始计时：换下一部时由 [settleRecommendWatch] 结掉（看了 → 30 天，划走 → 14 天）。
    recommendWatchingId=data.series.id
    recommendWatchStart=android.os.SystemClock.elapsedRealtime()
    // 记住「停在哪儿」：下次进程刚起时从它的**下一部**接着播（见 `openRecommend` 的
    // `resumeIndex`）。写一行剧 id，几字节、后台线程，不从主线程碰 IO。
    com.hgtv.star.core.RecommendCache.rememberPlayed(data.series.id,RECOMMEND_CACHE_NAMESPACE)
    // 换到另一部：上一次「画面丢了自动重建」的额度重新给满（见 [recoverRecommendPicture]）。
    // 不清的话，看过一部要重建两回的片子之后，后面每一部都只剩一次机会。
    pictureRecoveries=0; pictureRecoveryAt=0L
    // 相邻那一部的首集如果已经预热好，直接把它交给播放内核：不用再取地址、读索引、
    // 拉首批数据，切换就是「换一帧」而不是「等一次网络」。取走之后所有权归播放器。
    val key=preparedKey(data)
    val fromStart=position<=0L
    // 这一部正好在预热、而且这次要从头起播：取消**这一份**。
    //
    // 不取消的话，预热线程还在拉同一集的地址 / 首块，播放器又发一条完全相同的请求，
    // 两条会互相抢带宽，用户看到的反而是「越预热越慢」。其他相邻条目的预热不动，
    // 因此不会退回到「每次滑动都把整个窗口掐掉」。
    //
    // 移动网络本来就只开一个预热槽，同样必须在这里让路。
    val warmingCurrent=key.isNotEmpty() && recommendStore().isWarming(key)
    val metered=runCatching { connectivity.isMeteredConnection() }.getOrDefault(false)
    if(fromStart && key.isNotEmpty() && (warmingCurrent || metered)) {
        cancelRecommendWarm(key)
    }
    // 预热结果的**年龄校验**：超过 [PlaybackCache.PREPARED_FRESH_MS] 的那一份不能再用。
    //
    // 推荐页的预热是「划到附近就提前把首集地址取回来」，用户可能十几分钟后才划到它；
    // 而地址是服务端签发的带时效地址，过期后 `RemoteVideo.range` 读到 403，界面报
    // 「地址过期：播放地址已失效（HTTP 403）」。退出重进就好了——因为缓存被清掉、重新取地址。
    //
    // 宁可丢掉这一份重新取（多 500~1100ms），也不要拿一条已作废的地址去起播。
    val preparedAt=recommendPreparedAt[key] ?: 0L
    val preparedFresh=com.hgtv.star.core.PlaybackCache.isPreparedFresh(
        preparedAt,android.os.SystemClock.elapsedRealtime())
    val prepared=if(fromStart && preparedFresh) recommendPrepared.remove(key) else null
    // 过期的这一份要**当场关掉**：它还握着 1MB 首块与一条传输，留着只是占内存。
    if(fromStart && !preparedFresh) recommendPrepared.remove(key)?.let { runCatching { it.close() } }
    // `FULL` 表示内存池里仍有 2.5MB 首块。播放器取走这一份后，内存所有权已经转给
    // `video`，池里不再有可复用项；必须降回 `META`，否则窗口补货会把「历史做过 FULL」
    // 误判成「现在还有 FULL」，之后这一条永远不再发预热请求。
    if(fromStart && key.isNotEmpty()) {
        recommendStore().downgradeWarm(key,com.hgtv.star.core.CacheLevel.FULL,com.hgtv.star.core.CacheLevel.META)
    }
    // 取走之后下标/时刻表也要跟着摘掉，否则淘汰时会对着一个已经不存在的键算分。
    if(prepared!=null || fromStart) { recommendPreparedIndex.remove(key); recommendPreparedAt.remove(key) }
    // 起播路径的命中情况：一次换剧到底靠的是内存预热、磁盘索引还是全新拉取。
    // 只在调试包打（`Playback first frame` 那一行只给总时长，看不出钱花在哪儿）。
    if(BuildConfig.DEBUG) {
        // 地址键带 `device_level`（见 `ContentRepository.stream`）；
        // 索引 / 首块键带的是「集 + 实际档位」（见 `StreamInfo.mediaKey`）。
        // 早先这里把两段拼成一个键去查，`meta` / `head` 永远是 0——日志上看着像「缓存没用上」。
        val episode=data.episodes.firstOrNull().orEmpty()
        val urlKey="$episode:${playbackMaxQuality}:${repository.deviceLevel}"
        // 索引键优先用**这一份预热实际取到的**那个（`mediaKey` 用实际档位，可能低于上限）。
        val mediaKey=prepared?.info?.mediaKey(episode) ?: key
        android.util.Log.i("HGTV-PERF","switch prepared=${if(prepared!=null) 1 else 0} "+
            com.hgtv.star.core.PlaybackCache.probe(urlKey,mediaKey)+
            " level=${recommendStore().warmedLevel(key) ?: "-"} cancelledWarm=${if(warmingCurrent) 1 else 0}")
    }
    // 连续失败计数**不在这里**归零：起播（取地址成功）不等于能放，
    // 真正出画面之后才清零（见播放监听器的 READY 分支），否则整条推荐都坏掉时
    // 计数永远只有 1，会一直往下换、停不下来。
    // 过渡黑场必须先**完全不透明**再换源：命中预热缓存时换源是瞬时的，
    // 淡入还没走完就 reset 媒体，旧画面会带着半透明露出来（正是那一下拉伸）。
    recommendCover?.let { cover -> cover.animate().cancel(); cover.alpha=1f }
    // 从后台回来那一次不自动播：画面停在那一帧，浮层上写「已暂停」，按播放键继续。
    playEpisode(
        0,
        position.coerceAtLeast(0),
        autoplay=autoplay,
        prepared=prepared,
        clearDanmakuOnSwitch=true,
    )
    screen="recommend"
    hud.visibility=View.GONE
    syncPlaybackInfo()
    // 底部浮层由 [playEpisode] 收尾统一挂（重建整棵树与复用播放器两条路都会走到那里，
    // 它按新的 `detail.series` 重挂，并带上这一下的滑动方向动画）；这里只把数值刷上去。
    updateRecommendOverlay()
    // 手机 / 平板：每次进推荐页、每次上下换剧，底部那条信息（剧名 / 第几集 / 进度 / 状态）
    // 都**默认露出来**，不用先点一下画面。电视不走这条——那一页的信息是常显的底部浮层
    // （见 `attachRecommendOverlay`），不需要也不该弹出控制条。
    // 露出来之后按设置里的秒数自动收起（与正式播放同一个计时器，见 `showHud` /
    // `scheduleOverlayAutoHide`）；轻点画面照旧随时能切显隐。上下换剧走到这里会重新唤出，
    // 计时也随之重排——用户每换一部都还能看到「这是哪一部、看到第几集、播到哪儿」。
    if(touchArmed) showHud()
    // 相邻预热统一由播放监听器的 READY 分支在首帧后发起；这里不提前排。
    // 例外：这一次是**内存预热命中**（首块都在手里，这一部一个网络请求都不用发），
    // 带宽此刻是空的，顺手把下一部的地址取回来（20KB，不取首块）。
    // 遥控器「一秒一下」的节奏里，晚启动的窗口预热根本来不及跑，这一条是那段时间的兜底。
    if(prepared!=null) {
        val nextIndex=recommendIndex+slideCache.direction
        val next=recommendItems.getOrNull(nextIndex)
        if(next!=null) {
            StartupDeferredTasks.scheduleLatestOnMain("recommend.nextAddress",30_000L) {
                if(screen=="recommend") {
                    warmRecommend(next,nextIndex,com.hgtv.star.core.CacheLevel.ADDRESS)
                }
            }
        }
    }
    // 快刷到这一批末尾了：把下一页提前取回来，别让用户在末尾那一下等网络（见 [ensureRecommendLookahead]）。
    ensureRecommendLookahead()
    StartupTrace.done(
        "recommend.episode.start",
        startedAt,
        "id=${data.series.id} prepared=${prepared!=null}",
    )
}

/**
 * 预热用的缓存键：`集id:清晰度`。
 *
 * 换清晰度之后旧的那份自然对不上、会被丢掉——档位一变就是**另一个文件**，
 * 沿用旧键会把别的码流交给播放器，也可能把刚预热好的那份顶掉。
 * 全站只有 HEVC 一种编码，键里不再需要编码族那一段。
 */
fun TvMainActivity.preparedKey(data: Detail): String {
    val id=data.episodes.firstOrNull() ?: return ""
    return "$id:${playbackMaxQuality}"
}
