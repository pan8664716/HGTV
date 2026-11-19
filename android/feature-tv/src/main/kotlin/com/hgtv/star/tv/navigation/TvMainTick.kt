// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.view.View

/** 播放页每秒调度：定时停止、界面刷新、预取、进度采样和落盘节拍。 */
internal fun TvMainActivity.runTvMainTick(task: Runnable) {
    if(!foreground || isDestroyed) return
    // 推荐页在「整屏一行提示」时还没有播放层（hud 是 `playEpisode` 建的），
    // 这一支必须先确认它已经存在，否则冷启动取不到推荐时每秒一次的 tick 会直接崩。
    if((screen=="player" || screen=="recommend") && hudReady) {
        // 池实例可能在监听器挂上之前已经 READY；每秒用真实播放状态收口一次，
        // 防止旧 playbackReady 让“正在缓冲”永久留在画面上。
        syncBufferingDots()
        if(sleepTimer.poll()) stopForSleep()
        if(hud.visibility==View.VISIBLE) updatePlaybackText()
        // 顶部信息条是常驻的：时间每分钟跳一格、网速每秒刷一次，与控制层收没收起无关。
        updatePlaybackInfoReadings()
        maybePrefetch()
        maybeQueueNext()
        if(screen=="recommend") updateRecommendOverlay()
        // 时长按秒记一份：片尾自动推进时 `STATE_ENDED` 不会触发，补 completed 记录要用它。
        // 只在播放器报的确实是**当前这一集**时才记：片尾换 period 的那一瞬，
        // `duration` 已经是下一集的，照抄会把上一集的 completed 记录写错长度。
        val current=detail?.episodes?.getOrNull(episodeIndex)
        if(playbackReady && player?.currentMediaItem?.mediaId==current) {
            player?.duration?.takeIf { it>0 }?.let { lastEpisodeDuration=it }
        }
        // 每秒把进度采样进状态机：这是 [PlaybackState.positionMs] 的唯一来源，
        // 界面上「已看 / 总长」与断点续播都读它。放在时长更新之后，两者同一帧。
        if(playbackReady) {
            playbackCoordinator.onProgress(player?.currentPosition ?: 0L, lastEpisodeDuration)
            reportRecommendProgress()
        }
        // 断点落盘节拍由共享会话统一；页面只把当前播放器状态交给它。
        if(player?.isPlaying==true && playbackCoordinator.progressDue()) {
            saveProgress()
            playbackCoordinator.noteProgressSaved()
        }
    }
    main.postDelayed(task,1000)
}
