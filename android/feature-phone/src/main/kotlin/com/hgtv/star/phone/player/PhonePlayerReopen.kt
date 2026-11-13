// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.google.android.exoplayer2.upstream.DataSource
import com.hgtv.star.shared.media.VideoDataSource
import com.hgtv.star.shared.media.swapMediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 原地换一条地址：**不换集、不换 Surface、不回片头**。
 *
 * 用在「会话里这一路还活着、只是签名地址过期」这一种情况（见 PhoneSession.urlExpired）。
 * 早先它走的是 play(index, at)：那一条会 releasePlayer() 把播放器从 PlayerView 上摘下来、
 * 关掉旧地址、重新 prepare——摘 Surface 那一下就是用户看到的闪黑，
 * 而这一路的内容其实一秒都没断过。
 */
internal fun PhonePlayerActivity.reopenCurrentEpisodeInPlace(at: Long) {
    val d = detail ?: return
    val episodeId = d.episodes.getOrNull(index) ?: return
    val exo = player ?: return
    val position = at.coerceAtLeast(0L).takeIf { it > 0 } ?: PhoneSession.positionMs
    val maxQuality = playbackMaxQuality
    // 用户此刻是播还是暂停，换地址不能把它改掉（暂停着换完还是暂停）。
    val wantPlay = exo.playWhenReady
    scope.launch {
        val prepared = withContext(Dispatchers.IO) {
            runCatching {
                playerPlaybackSource.load(episodeId, maxQuality, d.playback)
            }.getOrNull()
        }
        if (prepared == null) {
            // 换地址失败：退回常规起播（会重新取详情 / 地址），别停在死画面上。
            if (!isFinishing && !isDestroyed) playForReopen(index, position)
            return@launch
        }
        if (isFinishing || isDestroyed) {
            prepared.close()
            return@launch
        }
        val factory = DataSource.Factory { VideoDataSource(prepared) }
        exo.swapMediaSource(episodeId, prepared.info.url, factory)
        queuedVideo?.let { runCatching { it.close() } }
        queuedVideo = null
        queuedEpisodeId = ""
        PhoneSession.clearNext()
        remote = prepared
        PhoneSession.publish(d, index, prepared, prepared.info.quality, PhoneSession.Owner.PLAYER)
        exo.setPlaybackSpeed(chipUserSpeed)
        exo.prepare()
        if (position > 0) exo.seekTo(position)
        exo.playWhenReady = wantPlay
        android.util.Log.i(
            "HGTV-PHONE",
            "reopen in place episode=${index + 1} at=$position"
        )
    }
}
