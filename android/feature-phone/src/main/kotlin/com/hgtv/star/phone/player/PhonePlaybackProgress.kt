// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.upstream.DataSource
import com.hgtv.star.core.PlaybackCache
import com.hgtv.star.core.prefetchModels
import com.hgtv.star.core.throwIfV5
import com.hgtv.star.shared.media.VideoDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun PhonePlayerActivity.startTicker() {
    progressTicker?.cancel()
    playbackCoordinator.resetProgressClock()
    progressTicker = scope.launch {
        while (true) {
            delay(1000)
            syncKeepScreenOnForListener()
            val p = player ?: continue
            val duration = p.duration
            if (duration > 0) {
                val position = p.currentPosition
                listenerLastDuration = duration
                playbackCoordinator.onProgress(position, duration)
                PhoneSession.markPosition(position)
                // 还在播就刷新会话时间戳（见 PhoneSession.touch）：退回推荐页时才敢接手。
                if (!progressDragging) updateProgress(position, duration)
                // 媒体面板的进度 / 状态跟着走：锁屏上那条进度不能停在进入播放页那一刻。
                syncMediaSessionForProgress()
                // 定时关闭：按分钟计的那几档到点在这里停（按集计的在片尾那一下停）。
                if (!sleepStopped && nextSeasonSleepTimer.poll()) stopForSleepForProgress()
                // 调试面板：与进度同一节拍刷新（关着时这一步只是一次布尔判断）。
                syncDebugOverlayForListener()
                // 起播稳定后尽早准备下一集，手动上滑与片尾自动连播共用这一份。
                maybePrefetchNext(position)
                enqueueNextIfReady()
                if (playbackCoordinator.progressDue()) {
                    reportRecommendEvent(
                        com.hgtv.star.core.RecommendBehavior.Event.PLAY_PROGRESS,
                        position,
                        duration,
                    )
                    saveProgress(position, duration)
                    playbackCoordinator.noteProgressSaved()
                }
            }
        }
    }
}

internal fun PhonePlayerActivity.updateProgress(position: Long, duration: Long) {
    val permille = (position * 1000 / duration).toInt().coerceIn(0, 1000)
    playerPage.setProgress(permille / 1000f)
    val bufferedPermille=((player?.bufferedPosition ?: position).coerceIn(0,duration)*1000/duration)
        .toInt().coerceIn(0,1000)
    playerPage.setBufferedProgress(bufferedPermille/1000f)
    if (!progressDragging) {
        progressLandSeek.progress = permille
        progressLandSeek.secondaryProgress = bufferedPermille
    }
    progressLandCurrent.text = com.hgtv.star.core.PlaybackInfoLine.time(position)
    progressLandTotal.text = com.hgtv.star.core.PlaybackInfoLine.time(duration)
    // 时间每分钟才变一次，但网速每秒都在变：两项任意一项开着就按秒刷。
    if (library?.playerClock == true || library?.playerSpeed == true) syncPlayInfoForProgress()
}

/** 播放状态变化时立即补进度与缓存段，不等下一拍 ticker。 */
internal fun PhonePlayerActivity.syncProgressBarsNow() {
    val p = player ?: return
    val duration = p.duration
    if (duration <= 0) return
    updateProgress(p.currentPosition.coerceIn(0, duration), duration)
}

internal fun PhonePlayerActivity.saveProgress(position: Long, duration: Long) {
    val d = detail ?: return
    val episodeId = d.episodes.getOrNull(index) ?: return
    playbackCoordinator.saveProgress(
        series = d.series,
        episodeId = episodeId,
        episodeIndex = index,
        positionMs = position,
        durationMs = duration,
        completed = false,
        episodeCount = d.episodes.size,
    )
}

/**
 * 播放稳定后尽早预热下一集。
 *
 * 手动上下滑可能发生在任意时刻，不能像以前一样等到片尾 30 秒才准备。这里在当前集
 * 稳定播放 2 秒后开始取地址与首块，并把它挂进播放列表；片尾自动推进与手动上滑
 * 都复用这一项，因此不会重新 release 播放器。
 *
 * 地址不按时间主动失效；预热结果真正播放失败时再由正常错误路径重取。
 */
internal fun PhonePlayerActivity.maybePrefetchNext(position: Long) {
    val d = detail ?: return
    val lib = library ?: return
    if (!lib.autoNext) return
    val nextIndex = index + 1
    if (nextIndex > d.episodes.lastIndex) return
    if (PhoneSession.nextQueued) return
    if (PhoneSession.nextRemote != null) return
    if (player?.isPlaying != true) return
    if (position < PhonePlayerActivity.prefetchStartMs) return
    val now = System.currentTimeMillis()
    if (now < listenerPrefetchAt) return
    val nextId = d.episodes[nextIndex]
    if (progressPrefetchingFor == nextId) return
    progressPrefetchingFor = nextId
    // 失败就 8 秒后再试一次：片尾之前还有机会，但不至于每秒撞接口。
    listenerPrefetchAt = now + 8_000

    val maxQuality = playbackMaxQuality
    val activity=this
    scope.launch {
        val modelQueue = d.episodes.drop(nextIndex + 1).take(PhonePlayerActivity.playerModelQueueSize)
        val info = withContext(Dispatchers.IO) {
            runCatching {
                playbackSource.stream(nextId, maxQuality, d.playback, queue = modelQueue)
            }
                .onFailure { it.throwIfV5() }
                .getOrNull()
        }
        val prepared = if (info == null) null else withContext(Dispatchers.IO) {
            prepareForProgress(nextId, info)
        }
        progressPrefetchingFor = ""
        if (prepared == null) return@launch
        // 期间用户可能已经换集 / 换剧：对不上就丢掉，别把别的集挂上去。
        if (detail?.episodes?.getOrNull(index + 1) != nextId) {
            prepared.close()
            return@launch
        }
        PhoneSession.putNext(nextId, prepared)
        enqueueNextIfReady()
        // 下一集已经可播后，再在后台补 preload 模型；已有缓存会被仓库跳过。
        scope.launch(Dispatchers.IO) {
            runCatching {
                PhoneServices.repository(activity)
                    .prefetchModels(d.episodes.drop(nextIndex).take(12), d.playback)
            }
                .onFailure { it.throwIfV5() }
        }
    }
}

/**
 * 把预热好的下一集**挂进播放列表**。
 *
 * 为什么不是片尾再 `setMediaSource`：那会 `resetInternal → releaseCodec → prepare`，
 * 黑屏、转圈、解码器重建全来一遍。挂进列表之后 ExoPlayer 自己把 period 前移一格
 * （`DISCONTINUITY_REASON_AUTO_TRANSITION`），画面不断。
 *
 * `shouldLoadNextMediaPeriod()` 要求当前 period 已完全缓冲，所以早挂不会抢当前这一集的带宽。
 */
internal fun PhonePlayerActivity.enqueueNextIfReady() {
    if (PhoneSession.nextQueued) return
    if (!sleepStopped && nextSeasonSleepTimer.episodesLeft > 0) return
    val d = detail ?: return
    val exo = player ?: return
    val nextIndex = index + 1
    if (nextIndex > d.episodes.lastIndex) return
    val nextId = d.episodes[nextIndex]
    val video = PhoneSession.takeNext(nextId, playbackMaxQuality) ?: return

    val factory = DataSource.Factory { VideoDataSource(video) }
    val source = ProgressiveMediaSource.Factory(factory).createMediaSource(
        MediaItem.Builder().setMediaId(nextId).setUri(video.info.url).build()
    )
    if (runCatching { exo.addMediaSource(source) }.isFailure) {
        runCatching { video.close() }
        return
    }
    queuedVideo = video
    queuedEpisodeId = nextId
    PhoneSession.nextQueued = true
    android.util.Log.i("HGTV-PHONE", "next queued id=${nextId.takeLast(6)} episode=${nextIndex + 1}")
}
