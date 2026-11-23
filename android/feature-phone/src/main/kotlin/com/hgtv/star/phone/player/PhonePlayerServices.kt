// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.google.android.exoplayer2.Player
import com.hgtv.star.shared.compat.Cancellable
import com.hgtv.star.shared.compat.NetworkWatcher
import com.hgtv.star.shared.compat.connectivityManager
import com.hgtv.star.shared.compat.hasInternet
import com.hgtv.star.shared.media.MediaSessionHost

internal class PhonePlayerRuntimeState {
    var mediaSession: MediaSessionHost? = null
    var mediaSessionFailed = false
    var networkWatch: Cancellable? = null
    var networkWasUp = false
}

/**
 * 建（或复用）媒体会话。
 *
 * 回调只做「跟播放有关的那几件事」：播放 / 暂停、拖到某处、上一集 / 下一集、停止。
 * 每个回调都先看 `player != null`：会话可能在播放页收尾的最后一帧被系统调一下，
 * 那时播放器已经交回推荐页，不能再被它拽回来。
 */
internal fun PhonePlayerActivity.ensureMediaSession(): MediaSessionHost? {
    runtimeState.mediaSession?.let { return it }
    if (runtimeState.mediaSessionFailed) return null
    val created = MediaSessionHost.create(
        this,
        { if (!playerControlsLocked) player?.play() },
        { if (!playerControlsLocked) player?.pause() },
        { position -> if (!playerControlsLocked) player?.seekTo(position) },
        { direction ->
            if (playerControlsLocked) return@create
            val d = detail ?: return@create
            val target = index + direction
            if (target in d.episodes.indices) play(target, clearDanmakuOnSwitch = true)
        },
        { if (!isFinishing) finish() },
    )
    if (created == null) runtimeState.mediaSessionFailed = true else runtimeState.mediaSession = created
    return created
}

/** 把「正在放什么」同步给系统（每秒一次，与进度条同一个节拍）。 */
internal fun PhonePlayerActivity.syncMediaSession() {
    // 旧播放页可能还在返回栈里，ticker 也会继续跑；它不能把自己重新挂回系统媒体面板，
    // 更不能拿当前全局播放器的进度去更新上一季的标题。
    if (!playerForeground || isFinishing || isDestroyed) return
    if (PhoneSession.owner != PhoneSession.Owner.PLAYER) return
    val d = detail ?: return
    if (PhoneSession.seriesId != d.series.id || PhoneSession.index != index) return
    val session = ensureMediaSession() ?: return
    val p = player
    val state = when {
        p == null -> android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED
        p.playbackState == Player.STATE_BUFFERING ->
            android.support.v4.media.session.PlaybackStateCompat.STATE_BUFFERING
        p.playbackState == Player.STATE_ENDED ->
            android.support.v4.media.session.PlaybackStateCompat.STATE_STOPPED
        p.isPlaying -> android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING
        else -> android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED
    }
    session.update(
        title = d.series.title,
        episode = index,
        duration = p?.duration?.coerceAtLeast(0) ?: 0,
        position = p?.currentPosition ?: 0,
        speed = sessionUserSpeed,
        state = state,
        previous = index > 0,
        next = index < d.episodes.lastIndex,
    )
}

/**
 * 网络恢复时**立刻重取地址**，没有任何退避等待（与电视端同一条）。
 *
 * 手机切 Wi-Fi / 5G 比电视频繁：断网那一下正好在取地址时，网络一恢复就继续。
 *
 * 只在**停在非阻塞恢复提示**时动手，
 * 正常播放期间网络抖动不该把这一集重来一遍。
 */
internal fun PhonePlayerActivity.watchNetwork() {
    if (runtimeState.networkWatch != null) return
    runtimeState.networkWasUp = connectivityManager()?.hasInternet() ?: false
    // NetworkCallback 是 API 21 类。不能把这个类型放进播放页运行状态，否则 4.4 在
    // 进入 onResume 时就会因类解析失败崩溃；版本分流统一交给 NetworkWatcher。
    val watch = runCatching {
        NetworkWatcher.watch(this) {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val up = connectivityManager()?.hasInternet() ?: false
                val restored = up && !runtimeState.networkWasUp
                runtimeState.networkWasUp = up
                if (!restored) return@runOnUiThread
                if (errorCard == null) return@runOnUiThread
                cancelAutoRetry()
                val at = runCatching { player?.currentPosition ?: 0L }.getOrDefault(0L).coerceAtLeast(0)
                play(index, at)
            }
        }
    }.getOrNull() ?: return
    runtimeState.networkWatch = watch
}

internal fun PhonePlayerActivity.unwatchNetwork() {
    val watch = runtimeState.networkWatch ?: return
    runtimeState.networkWatch = null
    runtimeState.networkWasUp = false
    watch.cancel()
}
