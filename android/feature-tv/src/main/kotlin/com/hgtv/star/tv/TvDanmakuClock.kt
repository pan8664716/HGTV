// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import com.google.android.exoplayer2.Player

/**
 * 把播放器的当前状态采样给弹幕层。
 *
 * **绘制线程不读播放器**：读位置只发生在这里（主线程，每秒一次，外加播放状态 / 跳转 /
 * 倍速变化），弹幕层自己按系统时钟外推。这样即使 ExoPlayer 内部状态异常，
 * 也最多让弹幕停在上一帧，不会在 `onDraw` 里抛异常把进程带走。
 *
 * 采样本身也包在 [runCatching] 里：线上那条 `PlaybackInfo.timeline` 为 null 的崩溃
 * 就是从 `currentPosition` 抛出来的；这里失败时按「暂停在当前位置」处理。
 */
fun TvMainActivity.syncDanmakuClock() {
    val view=danmakuView ?: return
    val p=player
    if(p==null) {
        view.stopClock()
        return
    }
    val sample=runCatching { Triple(p.currentPosition,p.isPlaying,p.playbackParameters.speed) }.getOrNull()
    if(sample==null) {
        view.setClock(view.clockPositionMillis(),false)
        return
    }
    val readyPlaying=sample.second && runCatching { p.playbackState==Player.STATE_READY }.getOrDefault(false)
    val running=readyPlaying && foreground && !pausedForLifecycle
    view.setClock(sample.first,running,sample.third)
}
