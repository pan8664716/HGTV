// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.view.View
import com.hgtv.star.core.Detail
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
    // 相邻项只预热详情与播放地址；当前这一部仍然由播放内核正常取源、解析并起播。
    val key=preparedKey(data)
    val fromStart=position<=0L
    val warmingCurrent=key.isNotEmpty() && recommendStore().isWarming(key)
    if(fromStart && key.isNotEmpty() && warmingCurrent) {
        cancelRecommendWarm(key)
    }
    if(BuildConfig.DEBUG) {
        val episode=data.episodes.firstOrNull().orEmpty()
        val urlKey="$episode:${playbackMaxQuality}:${repository.deviceLevel}"
        android.util.Log.i(
            "HGTV-PERF",
            "switch ${com.hgtv.star.core.PlaybackCache.probe(urlKey,key)} "+
                "level=${recommendStore().warmedLevel(key) ?: "-"} cancelledWarm=${if(warmingCurrent) 1 else 0}",
        )
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
    // 相邻地址预热统一由播放监听器在首帧后发起。
    // 快刷到这一批末尾了：把下一页提前取回来，别让用户在末尾那一下等网络（见 [ensureRecommendLookahead]）。
    ensureRecommendLookahead()
    StartupTrace.done(
        "recommend.episode.start",
        startedAt,
        "id=${data.series.id}",
    )
}

/**
 * 推荐地址预热用的缓存键：`集id:清晰度`。
 *
 * 换清晰度之后旧地址自然对不上，避免把另一档的地址当成当前档命中。
 */
fun TvMainActivity.preparedKey(data: Detail): String {
    val id=data.episodes.firstOrNull() ?: return ""
    return "$id:${playbackMaxQuality}"
}
