// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.hgtv.star.core.Series
import com.hgtv.star.shared.nav.Route

/** 进播放页。先停掉这一条流，否则两边声音叠在一起。 */
internal fun PhoneFeedView.openPlayer(page: PhoneVideoPage, fullscreen: Boolean) {
    val series = page.currentSeries() ?: return
    val index = feedChildAdapterPosition(page).takeIf { it >= 0 } ?: feedPlayingIndex
    launchPlayer(
        series = series,
        episode = liveEpisodeOf(series, index),
        fullscreen = fullscreen,
        // 「观看完整短剧」是明确的「我要接着看」：即使用户刚在信息流里暂停过也起播。
        play = true,
    )
}

/**
 * 起播放页之前先把本页控制层收起。
 *
 * 播放页的窗口是透明的（见 `PlayerTheme`）：晚收的那几十毫秒里，本页这套
 * 「选集 全N集｜第x集」会从它的透明底板下透出来，紧接着又被播放页自己那份
 * 「观看完整短剧」盖掉——用户读到的是按钮文字闪了一下。
 *
 * 只在播放页**真的会借这块 Surface** 时才收（9.0+ 且这一路还在）：更早的系统上
 * 播放页窗口不透明，下层根本看不见，收了只是多一次无谓的视图切换。
 */
internal fun PhoneFeedView.hideChromeForHandoff(series: Series) {
    if (android.os.Build.VERSION.SDK_INT < 28) return
    if (!PhoneSession.feedUnderlayUsable(series.id)) return
    PhoneSession.hideFeedChrome()
}

/**
 * 打开正式播放页，并把这一路**原样交出去**。
 *
 * 三件事缺一不可（缺一件用户就会看到「内容断了一下」）：
 * 1. `beginHandoff()` + `onHidden(handoff = true)`：交接窗口内不暂停播放器，
 *    媒体源与解码器都在跑，播放页只把 Surface 接过去；
 * 2. 把「进来要不要播」写进会话（`resumeOnAdopt`），播放页接手时照它决定；
 * 3. 不要系统转场动画：那 200ms 里新窗口还没画出视频，读起来就是一次黑屏。
 */
internal fun PhoneFeedView.launchPlayer(series: Series, episode: Int, fullscreen: Boolean, play: Boolean) {
    // 明确点进正片与电视端 `enterFormalPlayback` 同义：按「看了」记 30 天，
    // 并停掉停留计时，避免随后 `onHidden` 再把这次点击算成划走。
    settleRecommendWatchForNavigation()
    runCatching { feedRecommendStore.markWatched(series) }
    runCatching {
        feedRecommendPlayback.reportEvent(
            series,
            com.hgtv.star.core.RecommendBehavior.Event.CLICK,
            episodeId=feedCurrentEpisodeIds[feedPlayingIndex].orEmpty(),
            episodeIndex=feedCurrentEpisodeIndex[feedPlayingIndex] ?: -1,
            positionMs=runCatching { feedPlayer?.currentPosition ?: 0L }.getOrDefault(0L),
            durationMs=runCatching { feedPlayer?.duration ?: 0L }.getOrDefault(0L),
        )
    }
    feedRecommendWatchingId = ""
    feedRecommendWatchStart = 0L
    PhoneSession.resumeOnAdopt = play
    PhoneSession.beginHandoff()
    // 交接一开始就把这一路记在播放页名下：`PhoneMainActivity.onStop` 与
    // `onHidden` 都按 `owner` 判断「这一路是不是正被别人接手」，仍记成信息流
    // 的话它们会在播放页刚起来的几十毫秒里把它按停——用户读到一次断音。
    PhoneSession.takeOver(PhoneSession.Owner.PLAYER)
    onHidden(handoff = true)
    hideChromeForHandoff(series)
    context.phoneNav().open(
        Route.Player(
            series.id,
            series.title,
            episode = episode,
            fromRecommend = true,
            fullscreen = fullscreen,
            noTransition = true,
        )
    )
    (context as? android.app.Activity)?.overridePendingTransition(0, 0)
}

/**
 * 「这一屏现在放到第几集」。
 *
 * 优先用**进程级会话**里的集号：它才是播放器上真正挂着的那一集。
 * 本地 map 只是「这一屏显示过第几集」，在片尾交接、播放页里换过集、
 * 或这一屏还没起播时都可能落后。
 *
 * 传错的后果很具体：播放页会判「要的集号与会话里的不一致」→ 拒绝接手 →
 * 重新取详情、重新取地址、从头起播，用户看到的就是「切过去内容断了」。
 */
internal fun PhoneFeedView.liveEpisodeOf(series: Series, index: Int): Int {
    // 判据用 `live`（不看地址新鲜度）：地址过期只影响后续 Range 请求，播放页接手时
    // 会原地换一条地址、位置不动。用 `adoptable` 的话，停久了再点进去传的集号会退回
    // 本页记的旧值 → 播放页判「集号对不上」→ 重新取详情、从 0 起播。
    if (PhoneSession.live(series.id) && PhoneSession.index >= 0) return PhoneSession.index
    return feedCurrentEpisodeIndex[index] ?: 0
}
