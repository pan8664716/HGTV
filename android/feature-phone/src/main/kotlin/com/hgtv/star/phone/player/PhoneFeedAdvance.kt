// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.os.SystemClock
import android.view.View
import com.google.android.exoplayer2.upstream.DataSource
import com.hgtv.star.core.Detail
import com.hgtv.star.shared.media.VideoDataSource
import com.hgtv.star.shared.media.swapMediaSource
import com.hgtv.star.shared.nav.Route

/**
 * 一集播完：落 completed 进度 → 进正式播放页播下一集（最后一集则换下一部）。
 *
 * 判据与 TV 一致：
 * * 只在信息流是**前台且在播**时触发（`paused` / 已经切走过就不算）；
 * * 只在**真的播到结尾**时推进（`STATE_ENDED` 本身就是这个含义，但排队故障也可能把它
 *   提前抛出来，所以再核一次播放位置）；
 * * 同一集只推进一次（`advancing` 防重入）。
 */
internal fun PhoneFeedView.onEpisodeEnded(index: Int) {
    if (feedPaused || feedHidden) return
    // `advancing` 只用来防重入。交接一旦没走完（播放页没起来、用户中途返回、
    // 跳转被系统拦掉），这个标志会一直卡着，之后每一集都不再连播——
    // 用户读到的就是「卡在这一集末尾」。给它 5 秒的寿命。
    if (feedAdvancing && SystemClock.elapsedRealtime() - feedAdvancingAt < feedAdvanceGuardMs) return
    feedAdvancing = false
    val series = feedItems.getOrNull(index) ?: return
    val d = feedPlayingDetail ?: return
    val exo = feedPlayer ?: return
    // 复核：位置必须真的在片尾（留 2 秒余量，与 TV 的 atEnd 判据同值）。
    val duration = exo.duration.coerceAtLeast(0)
    if (duration <= 0 || exo.currentPosition < duration - 2_000) return

    val current = feedCurrentEpisodeIndex[index] ?: 0
    runCatching {
        feedRecommendPlayback.reportEvent(
            series,
            com.hgtv.star.core.RecommendBehavior.Event.PLAY_COMPLETE,
            episodeId=feedCurrentEpisodeIds[index].orEmpty(),
            episodeIndex=current,
            positionMs=duration,
            durationMs=duration,
        )
    }

    val next = current + 1
    if (next <= d.episodes.lastIndex) {
        feedAdvancing = true
        feedAdvancingAt = SystemClock.elapsedRealtime()
        // 交接给正片：**不暂停**。两边用的是同一个进程级播放器，暂停只会让
        // 切页那一下断一拍；播放页接手的是同一路解码，声音是连续的。
        PhoneSession.resumeOnAdopt = true
        PhoneSession.beginHandoff()
        PhoneSession.takeOver(PhoneSession.Owner.PLAYER)
        settleRecommendWatch()
        runCatching { feedRecommendStore.markWatched(series) }
        onHidden(handoff = true)
        // **在切页之前**把下一集挂上播放器：这样正片一进来接的就是已经在解码的这一路，
        // 声音不断、画面第一帧立刻有。不预热直接切页时，新窗口要等「取详情 → 取地址 →
        // 解析首块 → 出第一帧」，那一段就是用户看到的黑屏。
        val primed = primeHandoff(d, next)
        android.util.Log.i("HGTV-PHONE", "handoff to formal episode=${next + 1} primed=$primed")
        hideChromeForHandoff(series)
        // 下一集从 0 秒开始（不是续播：这一集用户还没看过）。
        // `noTransition` 关掉系统转场：滑动 / 淡入那 200ms 里新窗口还没画出视频，
        // 用户看到的就是一次黑屏。
        context.phoneNav().open(
            Route.Player(
                series.id,
                series.title,
                episode = next,
                fromRecommend = true,
                noTransition = true,
            )
        )
        // 4.4–5.x 不认 FLAG_ACTIVITY_NO_ANIMATION 的入场：再显式关一次。
        (context as? android.app.Activity)?.overridePendingTransition(0, 0)
    } else {
        // 最后一集：与 TV 的「全剧已播完 → 换一部接着看」等价——往下滑一屏。
        // 同样上锁：否则每 500ms 的兜底检查会反复触发这一段（反复滚动 / 反复 loadMore）。
        feedAdvancing = true
        feedAdvancingAt = SystemClock.elapsedRealtime()
        val nextSeries = index + 1
        if (nextSeries < feedItems.size) {
            feedSmoothScrollToPosition(nextSeries)
            playIndexForSettings(nextSeries, -1L, clearDanmakuOnSwitch = true)
        } else {
            loadMore()
            feedHint.text = "全剧已播完，正在换下一部…"
            feedHint.visibility = View.VISIBLE
            feedHint.bringToFront()
        }
    }
}

/**
 * 切页之前先把下一集挂到进程级播放器上（预热命中时才做）。
 *
 * 用的是 [maybePrefetchNext] 在片尾 30 秒内备好的那份 `RemoteVideo`：地址与首块都已在手，
 * 这里只差 `setMediaSource`（几十毫秒）。挂上之后立即 `playWhenReady = true`，
 * 因此切页那 200ms 里声音是连续的，新窗口拿到 Surface 时立刻就有帧。
 *
 * 返回 false 表示没预热到（例如刚起播就拖到片尾）：调用方照常切页，
 * 由正式播放页自己取地址——那条路会慢一点，但不会出错。
 */
internal fun PhoneFeedView.primeHandoff(d: Detail, nextIndex: Int): Boolean {
    val nextId = d.episodes.getOrNull(nextIndex) ?: return false
    val warm = PhoneSession.takeNext(nextId) ?: return false
    val exo = feedPlayer ?: run { runCatching { warm.close() }; return false }
    val ok = runCatching {
        val factory = DataSource.Factory { VideoDataSource(warm) }
        exo.swapMediaSource(nextId, warm.info.url, factory)
        exo.setPlaybackSpeed(feedUserSpeed)
        exo.playWhenReady = true
        // 必须在这里就 prepare：`swapMediaSource` 先 `stop()` 再挂源，此刻播放器停在 IDLE。
        // 不 prepare 的话，紧跟着起来的正式播放页看到的是一台「IDLE、没有在准备」的播放器，
        // 会判成接不上 → 重新取详情、重新取地址、从 0 起播——用户看到的就是闪黑 + 从头开始。
        // prepare 之后这一路马上开始缓冲，播放页接手时可能已经有首帧（`keepContentOnPlayerReset`
        // 还会保留上一帧），画面与声音都不断。
        exo.prepare()
    }.isSuccess
    if (!ok) { runCatching { warm.close() }; return false }
    PhoneSession.publish(d, nextIndex, warm, warm.info.quality, PhoneSession.Owner.PLAYER)
    return true
}
