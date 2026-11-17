// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.net.ConnectivityManager
import com.google.android.exoplayer2.Player
import com.hgtv.star.shared.media.MediaSessionHost

internal class PhonePlayerRuntimeState {
    var mediaSession: MediaSessionHost? = null
    var mediaSessionFailed = false
    var networkWatch: ConnectivityManager.NetworkCallback? = null
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
    val d = detail ?: return
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
 * 网络恢复时**立刻重取地址**，不等退避计时（与电视端 `refetchAddressOnNetwork` 同一条）。
 *
 * 手机切 Wi-Fi / 5G 比电视频繁：断网那一下正好在取地址，退避还要等 3~12 秒，
 * 而网络其实已经好了。电视端早就有这条，手机端原先连网络状态都不看。
 *
 * 只在**正在等地址**（[sourceRetryState] 的计数 > 0）或**停在错误页**时动手，
 * 正常播放期间网络抖动不该把这一集重来一遍。
 */
internal fun PhonePlayerActivity.watchNetwork() {
    if (runtimeState.networkWatch != null) return
    // `registerDefaultNetworkCallback` 是 API 24 起才有的；更低版本交给退避重试兜底。
    if (android.os.Build.VERSION.SDK_INT < 24) return
    val manager = getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
        as? ConnectivityManager ?: return
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (errorCard == null && sourceRetryState.count == 0) return@runOnUiThread
                cancelAutoRetry()
                val at = runCatching { player?.currentPosition ?: 0L }.getOrDefault(0L).coerceAtLeast(0)
                play(index, at)
            }
        }
    }
    if (runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess) {
        runtimeState.networkWatch = callback
    }
}

internal fun PhonePlayerActivity.unwatchNetwork() {
    val callback = runtimeState.networkWatch ?: return
    runtimeState.networkWatch = null
    val manager = getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
        as? ConnectivityManager
    runCatching { manager?.unregisterNetworkCallback(callback) }
}
