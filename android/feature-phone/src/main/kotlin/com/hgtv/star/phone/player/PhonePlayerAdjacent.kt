// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.os.SystemClock
import android.view.View
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.video.VideoSize
import com.google.android.exoplayer2.upstream.DataSource
import com.hgtv.star.core.AppConfig
import com.hgtv.star.core.PlaybackCache
import com.hgtv.star.core.RemoteVideo
import com.hgtv.star.core.VideoFrameMode
import com.hgtv.star.core.toFit
import com.hgtv.star.phone.ui.resizeMode
import com.hgtv.star.shared.media.VideoDataSource
import com.hgtv.star.shared.media.swapMediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

internal class PhonePlayerAdjacentSlot(
    val index: Int,
    val episodeId: String,
    val remote: RemoteVideo,
    val player: ExoPlayer,
) {
    var sourcePrepared: Boolean = true
    var firstFrameRendered: Boolean = false
    var preRendering: Boolean = false
    var previewPlaying: Boolean = false
    var preRenderToken: Int = 0
    var preparedAt: Long = 0L
    var listener: Player.Listener? = null
}

internal class PhonePlayerAdjacentState {
    val slots = LinkedHashMap<Int, PhonePlayerAdjacentSlot>()
    val warming = mutableSetOf<Int>()
    var generation = 0
    var warmRunnable: Runnable? = null
}

/**
 * 正式播放页的前后相邻集池。
 *
 * 当前集仍由 Activity 的 `player` 持有；这里只保留前后各一集。主方向下一集会在
 * 对应 ViewPager2 集页的独立 SurfaceView 上静音预渲染首帧，
 * 上一集则保留刚离开的那台播放器，下滑时重新挂回该集 Surface 即可继续。
 */
internal fun PhonePlayerActivity.scheduleAdjacentEpisodeWarmup() {
    if (!playerRootInitialized) return
    playerAdjacentState.warmRunnable?.let(root::removeCallbacks)
    val task = Runnable {
        playerAdjacentState.warmRunnable = null
        if (isFinishing || isDestroyed || !playerForeground) return@Runnable
        val exo = player
        if (exo?.isPlaying == true &&
            exo.bufferedPosition - exo.currentPosition < 1_500L
        ) {
            scheduleAdjacentEpisodeWarmup()
            return@Runnable
        }
        ensureAdjacentEpisode(index - 1)
        ensureAdjacentEpisode(index + 1)
    }
    playerAdjacentState.warmRunnable = task
    root.postDelayed(task, 300L)
}

internal fun PhonePlayerActivity.ensureAdjacentEpisode(target: Int) {
    if (!playerForeground) return
    val d = detail ?: return
    if (target !in d.episodes.indices || target == index || abs(target - index) != 1) return
    if (playerAdjacentState.slots.containsKey(target) ||
        !playerAdjacentState.warming.add(target)
    ) return

    val seriesId = d.series.id
    val episodeId = d.episodes[target]
    val quality = playbackMaxQuality
    val anchorIndex = index
    val generation = playerAdjacentState.generation
    scope.launch {
        try {
            val prepared = withContext(Dispatchers.IO) {
                runCatching {
                    val queue = d.episodes.drop(target + 1).take(PhonePlayerActivity.playerModelQueueSize)
                    val info = playbackSource.stream(
                        episodeId,
                        quality,
                        d.playback,
                        queue,
                    )
                    if (target == anchorIndex + 1) {
                        playbackSource.warm(episodeId, info) { it.warm() }
                    } else {
                        playbackSource.prepare(episodeId, info)
                    }
                }.getOrNull()
            } ?: return@launch
            if (generation != playerAdjacentState.generation ||
                isFinishing || isDestroyed || detail?.series?.id != seriesId ||
                target !in d.episodes.indices || abs(target - index) != 1
            ) {
                prepared.close()
                return@launch
            }

            val exo = PhoneServices.newPlayer(
                this@ensureAdjacentEpisode,
                library?.decoderMode,
                handleAudioFocus = false,
            )
            val factory = DataSource.Factory { VideoDataSource(prepared) }
            exo.swapMediaSource(episodeId, prepared.info.url, factory)
            exo.setPlaybackSpeed(sessionUserSpeed)
            exo.volume = 0f
            exo.playWhenReady = false
            val slot = PhonePlayerAdjacentSlot(target, episodeId, prepared, exo)
            attachAdjacentPlayerListener(slot)
            playerAdjacentState.slots[target] = slot
            trimAdjacentSlots()
            if (runCatching { exo.prepare() }.isFailure) {
                releaseAdjacentSlot(target)
                return@launch
            }
            slot.preparedAt = SystemClock.elapsedRealtime()
            attachAdjacentToPagerPage(target)
            if (com.hgtv.star.shared.BuildConfig.DEBUG) {
                android.util.Log.i(
                    "HGTV-PHONE",
                    "player adjacent ready index=${target + 1} current=${index + 1}",
                )
            }
        } finally {
            playerAdjacentState.warming.remove(target)
        }
    }
}

private fun PhonePlayerActivity.attachAdjacentPlayerListener(slot: PhonePlayerAdjacentSlot) {
    if (slot.listener != null) return
    val hook = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (playerLandscapeMode) return
            val page = playerEpisodePager.pageAt(slot.index) ?: return
            if (page.playerView.player !== slot.player) return
            page.setResizeMode(
                (library?.frameMode ?: VideoFrameMode.FIT)
                    .toFit()
                    .resizeMode()
            )
            page.updateVideoFrame(videoSize.width, videoSize.height)
        }

        override fun onRenderedFirstFrame() {
            slot.firstFrameRendered = true
            slot.preRendering = false
            if (!slot.previewPlaying && (player !== slot.player || index != slot.index)) {
                runCatching { slot.player.pause() }
            }
            if (playerLandscapeMode) {
                playerLandscapePager.pageAt(slot.index)?.let { page ->
                    if (page.video.player === slot.player) page.hideCover()
                }
            } else {
                playerEpisodePager.pageAt(slot.index)?.let { page ->
                    if (page.playerView.player === slot.player) page.hideEpisodeCover()
                }
            }
        }

        override fun onPlayerError(error: com.google.android.exoplayer2.PlaybackException) {
            android.util.Log.w(
                "HGTV-PHONE",
                "adjacent player error episode=${slot.index + 1} ${error.errorCodeName}",
            )
            releaseAdjacentSlot(slot.index)
        }
    }
    slot.listener = hook
    slot.player.addListener(hook)
}

/**
 * 把相邻集播放器挂到它对应的 ViewPager2 页面。
 *
 * 页面由 `offscreenPageLimit=1` 提前创建，因此这里在用户开始滑动之前就能把
 * 独立 `SurfaceView` 与独立 ExoPlayer 绑定，并以 0 音量完成首帧预渲染。
 */
internal fun PhonePlayerActivity.attachAdjacentToPagerPage(target: Int) {
    if (playerLandscapeMode) {
        attachAdjacentToLandscapePagerPage(target)
        return
    }
    val page = playerEpisodePager.pageAt(target) ?: run {
        ensureAdjacentEpisode(target)
        return
    }
    val slot = playerAdjacentState.slots[target] ?: run {
        ensureAdjacentEpisode(target)
        return
    }
    val d = detail ?: return
    if (target !in d.episodes.indices || target == index) return
    val episodeId = d.episodes[target]
    val cover = d.episodeCover(episodeId).ifBlank { d.series.cover }
    if (page.playerView.player === slot.player) {
        startAdjacentPreRender(slot)
        if (swipePlaybackPreviewIndex == target) playAdjacentPreview(slot)
        return
    }
    page.danmakuView.clear()
    page.bind(
        d.series,
        "第${target + 1}集",
        watchText = "选集 ${target + 1}/${d.episodes.size}",
    )
    page.setPlaybackLocked(playerControlsLocked)
    page.setChromeVisible(playerChromeVisible)
    page.setResizeMode(
        (library?.frameMode ?: VideoFrameMode.FIT)
            .toFit()
            .resizeMode()
    )
    page.setVideoScale(((library?.videoScale ?: 100f) / 100f).coerceIn(.1f, 1f))
    val width = slot.remote.info.videoWidth.takeIf { it > 0 } ?: 0
    val height = slot.remote.info.videoHeight.takeIf { it > 0 } ?: 0
    if (width > 0 && height > 0) page.updateVideoFrame(width, height)
    page.setVideoSurfaceVisible(true)
    if (page.playerView.player !== slot.player) {
        detachAdjacentOutput(page.playerView)
        page.playerView.player = slot.player
    }
    if (slot.firstFrameRendered) page.hideEpisodeCover() else page.showEpisodeCover(cover)
    val duration = runCatching { slot.player.duration }.getOrDefault(0L)
    val position = runCatching { slot.player.currentPosition }.getOrDefault(0L)
    page.setProgress(if (duration > 0L) position.toFloat() / duration else 0f)
    startAdjacentPreRender(slot)
    if (swipePlaybackPreviewIndex == target) playAdjacentPreview(slot)
}

internal fun PhonePlayerActivity.attachAdjacentToLandscapePagerPage(target: Int) {
    if (!playerLandscapeMode) {
        attachAdjacentToPagerPage(target)
        return
    }
    val page = playerLandscapePager.pageAt(target) ?: run {
        ensureAdjacentEpisode(target)
        return
    }
    val slot = playerAdjacentState.slots[target] ?: run {
        ensureAdjacentEpisode(target)
        return
    }
    val d = detail ?: return
    if (target !in d.episodes.indices || target == index) return
    val cover = d.episodeCover(d.episodes[target]).ifBlank { d.series.cover }
    if (page.video.player === slot.player) {
        startAdjacentPreRender(slot)
        if (swipePlaybackPreviewIndex == target) playAdjacentPreview(slot)
        return
    }
    page.bind(d, target)
    applyDanmakuSettingsToView(page.danmaku)
    attachAdjacentPlayerListener(slot)
    page.video.visibility = View.VISIBLE
    if (page.video.player !== slot.player) {
        detachAdjacentOutput(page.video)
        page.video.player = slot.player
    }
    if (slot.firstFrameRendered) page.hideCover() else page.showCover(cover)
    startAdjacentPreRender(slot)
    if (swipePlaybackPreviewIndex == target) playAdjacentPreview(slot)
}

private fun PhonePlayerActivity.startAdjacentPreRender(slot: PhonePlayerAdjacentSlot) {
    if (slot.index == index || !slot.sourcePrepared || slot.firstFrameRendered ||
        slot.preRendering || slot.previewPlaying
    ) return
    slot.preRendering = true
    slot.player.volume = 0f
    slot.player.playWhenReady = true
    val token = ++slot.preRenderToken
    root.postDelayed(
        {
            if (playerAdjacentState.slots[slot.index] !== slot ||
                slot.preRenderToken != token || !slot.preRendering ||
                slot.index == index || slot.previewPlaying
            ) return@postDelayed
            slot.preRendering = false
            runCatching { slot.player.pause() }
        },
        AppConfig.Playback.Preload.PHONE_PRE_RENDER_TIMEOUT_MS,
    )
}

internal fun PhonePlayerActivity.startAdjacentSwipePreview(target: Int): Boolean {
    val slot = playerAdjacentState.slots[target] ?: return false
    if (playerLandscapeMode) {
        val page = playerLandscapePager.pageAt(target) ?: return false
        if (page.video.player !== slot.player) {
            attachAdjacentToLandscapePagerPage(target)
            return slot.previewPlaying
        }
        if (playerLandscapePager.pageAt(target)?.video?.player !== slot.player) return false
    } else {
        val page = playerEpisodePager.pageAt(target) ?: return false
        if (page.playerView.player !== slot.player) {
            attachAdjacentToPagerPage(target)
            return slot.previewPlaying
        }
        if (playerEpisodePager.pageAt(target)?.playerView?.player !== slot.player) return false
    }
    if (slot.index == index || player === slot.player) return false
    return playAdjacentPreview(slot)
}

private fun PhonePlayerActivity.playAdjacentPreview(slot: PhonePlayerAdjacentSlot): Boolean {
    if (slot.index == index || player === slot.player) return false
    slot.previewPlaying = true
    slot.preRendering = false
    slot.preRenderToken++
    slot.player.volume = 1f
    PhoneServices.enableAudioFocus(slot.player, true)
    slot.player.playWhenReady = true
    return true
}

internal fun PhonePlayerActivity.stopAdjacentSwipePreview(target: Int = swipePlaybackPreviewIndex) {
    if (target < 0) return
    val slot = playerAdjacentState.slots[target] ?: return
    if (player === slot.player) return
    slot.previewPlaying = false
    slot.player.volume = 0f
    slot.player.playWhenReady = false
    runCatching { slot.player.pause() }
    PhoneServices.enableAudioFocus(slot.player, false)
}

internal fun PhonePlayerActivity.restoreAdjacentGhostOffscreen() {
    attachAdjacentToPagerPage(index + 1)
}

internal fun PhonePlayerActivity.rebindAdjacentGhostForMode() {
    attachAdjacentToPagerPage(index + 1)
}

internal fun PhonePlayerActivity.hideAdjacentGhost() {
    detachAdjacentGhostOutput()
}

internal fun PhonePlayerActivity.activateAdjacentEpisode(
    target: Int,
    previousPosition: Long,
    previousDuration: Long,
): Boolean {
    val d = detail ?: return false
    if (target !in d.episodes.indices || abs(target - index) != 1) return false
    val slot = playerAdjacentState.slots.remove(target) ?: return false
    if (slot.preparedAt <= 0L ||
        SystemClock.elapsedRealtime() - slot.preparedAt > PlaybackCache.URL_FRESH_MS
    ) {
        playerAdjacentState.slots[target] = slot
        releaseAdjacentSlot(target)
        return false
    }
    val oldPlayer = player ?: return false
    val oldIndex = index
    val oldRemote = remote
    val oldEpisodeId = d.episodes.getOrNull(oldIndex).orEmpty()

    playerListener?.let(oldPlayer::removeListener)
    playerListener = null
    oldPlayer.playWhenReady = false
    oldPlayer.volume = 0f
    PhoneServices.enableAudioFocus(oldPlayer, false)
    runCatching {
        val current = oldPlayer.currentMediaItemIndex.coerceAtLeast(0)
        if (oldPlayer.mediaItemCount > current + 1) {
            oldPlayer.removeMediaItems(current + 1, oldPlayer.mediaItemCount)
        }
    }
    queuedVideo?.let { runCatching { it.close() } }
    queuedVideo = null
    queuedEpisodeId = ""
    PhoneSession.nextQueued = false
    PhoneSession.clearNext()

    if (oldRemote != null && oldEpisodeId.isNotEmpty()) {
        PhonePlayerAdjacentSlot(
            index = oldIndex,
            episodeId = oldEpisodeId,
            remote = oldRemote,
            player = oldPlayer,
        ).also {
            it.firstFrameRendered = false
            it.preparedAt = SystemClock.elapsedRealtime()
            playerAdjacentState.slots[oldIndex] = it
        }
    } else {
        runCatching { oldPlayer.release() }
    }
    silenceAdjacentPlayers(except = oldPlayer)

    slot.listener?.let(slot.player::removeListener)
    slot.listener = null
    val firstFrameOnCurrentSurface = slot.firstFrameRendered
    slot.preRendering = false
    slot.previewPlaying = false
    slot.preRenderToken++
    PhoneServices.activate(slot.player)
    player = slot.player
    remote = slot.remote
    slot.player.setPlaybackSpeed(sessionUserSpeed)
    slot.player.volume = 1f
    PhoneServices.enableAudioFocus(slot.player, true)
    slot.player.playWhenReady = true
    listenerLastDuration = previousDuration
    attachCurrentPlayerOutput()

    queuedVideo = slot.remote
    onAdvanced(
        target,
        completedPrevious = false,
        previousPosition = previousPosition,
        clearDanmakuOnSwitch = true,
        closePreviousRemote = false,
    )
    attachPlayerListener(slot.player)
    runCatching { oldPlayer.pause() }
    if (firstFrameOnCurrentSurface) {
        completeFirstFrameAspectRatio()
        hideEpisodeCover()
        playerDanmakuLoader.onFrameRendered(slot.player.currentPosition)
    } else {
        playerBufferingMutedUntil = 0L
        setBufferingLabel("正在准备第${target + 1}集…")
    }
    trimAdjacentSlots()
    android.util.Log.i("HGTV-PHONE", "adjacent activated episode=${target + 1}")
    return true
}

private fun PhonePlayerActivity.attachCurrentPlayerOutput() {
    val exo = player ?: return
    if (playerLandscapeMode) {
        playerPage.playerView.player = null
        attachCurrentPlayerToLandscapePagerPage()
    } else {
        playerLandscapeVideo.player = null
        attachCurrentPlayerToPagerPage()
    }
}

internal fun PhonePlayerActivity.releaseAdjacentSlot(target: Int) {
    val slot = playerAdjacentState.slots.remove(target) ?: return
    slot.listener?.let(slot.player::removeListener)
    slot.listener = null
    slot.preRendering = false
    slot.previewPlaying = false
    slot.preRenderToken++
    playerEpisodePager.pageAt(target)?.let(::detachPlayerPagerPage)
    playerLandscapePager.pageAt(target)?.let(::detachLandscapePagerPage)
    runCatching { slot.player.pause() }
    runCatching { slot.player.release() }
    if (PhoneServices.peekPlayer() === slot.player) PhoneServices.deactivate(slot.player)
    if (slot.remote !== PhoneSession.remote) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            { runCatching { slot.remote.close() } },
            AppConfig.Playback.Preload.PHONE_CLOSE_DELAY_MS,
        )
    }
}

internal fun PhonePlayerActivity.releaseAllAdjacentPlayers() {
    playerAdjacentState.generation++
    playerAdjacentState.warmRunnable?.let(root::removeCallbacks)
    playerAdjacentState.warmRunnable = null
    playerAdjacentState.warming.clear()
    playerAdjacentState.slots.keys.toList().forEach(::releaseAdjacentSlot)
    hideAdjacentGhost()
}

internal fun PhonePlayerActivity.pauseAdjacentPlayers() {
    playerAdjacentState.generation++
    playerAdjacentState.warming.clear()
    playerAdjacentState.warmRunnable?.let(root::removeCallbacks)
    playerAdjacentState.warmRunnable = null
    playerAdjacentState.slots.values.forEach { slot ->
        slot.firstFrameRendered = false
        slot.preRendering = false
        slot.previewPlaying = false
        slot.preRenderToken++
        runCatching {
            slot.player.volume = 0f
            slot.player.playWhenReady = false
            slot.player.pause()
        }
        PhoneServices.enableAudioFocus(slot.player, false)
    }
    detachAdjacentGhostOutput()
}

internal fun PhonePlayerActivity.reconcileAdjacentSlots() {
    if (playerAdjacentState.slots.containsKey(index)) releaseAdjacentSlot(index)
    trimAdjacentSlots()
}

private fun PhonePlayerActivity.trimAdjacentSlots() {
    playerAdjacentState.slots.keys
        .filter { abs(it - index) > 1 }
        .toList()
        .forEach(::releaseAdjacentSlot)
}

private fun PhonePlayerActivity.silenceAdjacentPlayers(except: ExoPlayer?) {
    playerAdjacentState.slots.values.forEach { slot ->
        if (slot.player !== except) {
            slot.preRendering = false
            slot.previewPlaying = false
            slot.preRenderToken++
            runCatching {
                slot.player.volume = 0f
                slot.player.playWhenReady = false
                slot.player.pause()
            }
            PhoneServices.enableAudioFocus(slot.player, false)
        }
    }
}

internal fun PhonePlayerActivity.detachAdjacentGhostOutput() {
    if (playerEpisodePagerInitialized && !playerLandscapeMode) {
        detachPlayerPagerOutputsExcept(playerPage)
    }
    if (playerLandscapePagerInitialized) {
        detachLandscapePagerOutputsExcept(playerLandscapePager.pageAt(index))
    }
}

private fun detachAdjacentOutput(view: PlayerView) {
    view.setKeepContentOnPlayerReset(false)
    view.player = null
    view.setKeepContentOnPlayerReset(true)
}
