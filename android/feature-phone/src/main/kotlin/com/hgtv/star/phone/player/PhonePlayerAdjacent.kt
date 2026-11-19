// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.os.SystemClock
import android.view.View
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.upstream.DataSource
import com.hgtv.star.core.AppConfig
import com.hgtv.star.core.PlaybackCache
import com.hgtv.star.core.RemoteVideo
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
    var preRenderToken: Int = 0
    var preparedAt: Long = 0L
    var listener: Player.Listener? = null
}

internal class PhonePlayerAdjacentState {
    val slots = LinkedHashMap<Int, PhonePlayerAdjacentSlot>()
    val warming = mutableSetOf<Int>()
    var ghostIndex = -1
    var ghostDirection = 1
    var ghostLandscape = false
    var ghostAttached = false
    var generation = 0
    var warmRunnable: Runnable? = null
}

/**
 * 正式播放页的前后相邻集池。
 *
 * 当前集仍由 Activity 的 `player` 持有；这里只保留前后各一集。主方向下一集会在
 * ghost 页的独立 SurfaceView 上静音预渲染首帧，上一集则保留刚离开的那台播放器，
 * 下滑时重新挂回 ghost Surface 即可继续。
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
            if (target == index + 1 || playerAdjacentState.ghostIndex == target) {
                attachAdjacentToGhost(
                    target,
                    direction = playerAdjacentState.ghostDirection,
                )
            }
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
        override fun onRenderedFirstFrame() {
            slot.firstFrameRendered = true
            slot.preRendering = false
            if (player !== slot.player || index != slot.index) {
                runCatching { slot.player.pause() }
            }
            if (playerAdjacentState.ghostIndex == slot.index) showAdjacentGhostFrame(slot)
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

internal fun PhonePlayerActivity.attachAdjacentToGhost(
    target: Int,
    direction: Int,
) {
    val d = detail ?: return
    val slot = playerAdjacentState.slots[target] ?: run {
        ensureAdjacentEpisode(target)
        return
    }
    if (target !in d.episodes.indices) return
    if (playerAdjacentState.ghostIndex == target &&
        playerAdjacentState.ghostLandscape == playerLandscapeMode &&
        playerAdjacentState.ghostAttached
    ) {
        playerAdjacentState.ghostDirection = direction
        val h = slideHeightImpl().toFloat()
        val directionOffset = if (direction > 0) h else -h
        if (playerLandscapeMode) {
            swipeLandscapeGhost.visibility = View.VISIBLE
            swipeLandscapeGhost.translationY =
                swipeLandscape.translationY + directionOffset
        } else {
            swipeGhostPage.visibility = View.VISIBLE
            swipeGhostPage.translationY = playerPage.translationY + directionOffset
        }
        if (slot.firstFrameRendered) showAdjacentGhostFrame(slot) else startAdjacentPreRender(slot)
        return
    }
    val previousGhostIndex = playerAdjacentState.ghostIndex
    if (previousGhostIndex != target) {
        playerAdjacentState.slots[previousGhostIndex]?.let { previous ->
            previous.preRendering = false
            previous.preRenderToken++
            runCatching {
                previous.player.volume = 0f
                previous.player.playWhenReady = false
                previous.player.pause()
            }
        }
    }
    detachAdjacentGhostOutput()
    playerAdjacentState.ghostIndex = target
    playerAdjacentState.ghostDirection = direction
    playerAdjacentState.ghostLandscape = playerLandscapeMode
    playerAdjacentState.ghostAttached = true
    val label = "第${target + 1}集"
    val episodeId = d.episodes[target]
    val cover = d.episodeCover(episodeId).ifBlank { d.series.cover }
    val h = slideHeightImpl().toFloat()
    val directionOffset = if (direction > 0) h else -h
    attachAdjacentPlayerListener(slot)

    if (playerLandscapeMode) {
        if (playerLandscapeVideoInitialized) {
            swipeLandscapeGhostTitle.text = "${d.series.title} $label"
            showLandscapeGhostEpisodeCover(cover)
            slot.firstFrameRendered = false
            swipeLandscapeGhostVideo.player = slot.player
            swipeLandscapeGhostVideo.visibility = View.VISIBLE
            swipeLandscapeGhost.visibility = View.VISIBLE
            swipeLandscapeGhost.translationY =
                swipeLandscape.translationY + directionOffset
        }
    } else {
        swipeGhostPage.danmakuView.clear()
        swipeGhostPage.bind(
            d.series,
            label,
            watchText = "选集 ${target + 1}/${d.episodes.size}",
        )
        swipeGhostPage.setVideoSurfaceVisible(true)
        slot.firstFrameRendered = false
        swipeGhostPage.playerView.player = slot.player
        swipeGhostPage.showEpisodeCover(cover)
        swipeGhostPage.setProgress(0f)
        swipeGhostPage.setChromeVisible(playerChromeVisible)
        swipeGhostPage.visibility = View.VISIBLE
        swipeGhostPage.translationY = playerPage.translationY + directionOffset
    }
    startAdjacentPreRender(slot)
}

private fun PhonePlayerActivity.startAdjacentPreRender(slot: PhonePlayerAdjacentSlot) {
    if (slot.index == index || !slot.sourcePrepared || slot.firstFrameRendered || slot.preRendering) return
    slot.preRendering = true
    slot.player.volume = 0f
    slot.player.playWhenReady = true
    val token = ++slot.preRenderToken
    root.postDelayed(
        {
            if (playerAdjacentState.slots[slot.index] !== slot ||
                slot.preRenderToken != token || !slot.preRendering ||
                slot.index == index
            ) return@postDelayed
            slot.preRendering = false
            runCatching { slot.player.pause() }
        },
        AppConfig.Playback.Preload.PHONE_PRE_RENDER_TIMEOUT_MS,
    )
}

private fun PhonePlayerActivity.showAdjacentGhostFrame(slot: PhonePlayerAdjacentSlot) {
    if (playerLandscapeMode) {
        swipeLandscapeGhostCover?.apply {
            animate().cancel()
            visibility = View.GONE
        }
        swipeLandscapeGhostVideo.visibility = View.VISIBLE
    } else {
        swipeGhostPage.setVideoSurfaceVisible(true)
        swipeGhostPage.hideEpisodeCover()
    }
}

internal fun PhonePlayerActivity.restoreAdjacentGhostOffscreen() {
    val target = index + 1
    if (playerAdjacentState.slots.containsKey(target)) {
        attachAdjacentToGhost(target, direction = 1)
        return
    }
    hideAdjacentGhost()
}

internal fun PhonePlayerActivity.rebindAdjacentGhostForMode() {
    val target = playerAdjacentState.ghostIndex
    if (target >= 0 && playerAdjacentState.slots.containsKey(target)) {
        attachAdjacentToGhost(
            target,
            direction = playerAdjacentState.ghostDirection,
        )
    } else {
        restoreAdjacentGhostOffscreen()
    }
}

internal fun PhonePlayerActivity.hideAdjacentGhost() {
    detachAdjacentGhostOutput()
    playerAdjacentState.ghostIndex = -1
    playerAdjacentState.ghostDirection = 1
    playerAdjacentState.ghostLandscape = false
    playerAdjacentState.ghostAttached = false
    if (playerPageInitialized) {
        swipeGhostPage.visibility = View.GONE
        swipeGhostPage.translationY = 0f
    }
    if (playerLandscapeVideoInitialized) {
        swipeLandscapeGhost.visibility = View.GONE
        swipeLandscapeGhost.translationY = 0f
    }
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
    if (playerAdjacentState.ghostIndex == target) {
        detachAdjacentGhostOutput()
        playerAdjacentState.ghostIndex = -1
        playerAdjacentState.ghostLandscape = false
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
    val firstFrameOnCurrentSurface = slot.firstFrameRendered && !playerLandscapeMode
    slot.preRendering = false
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
        playerLandscapeVideo.player = exo
    } else {
        playerLandscapeVideo.player = null
        playerPage.setVideoSurfaceVisible(true)
        playerPage.playerView.player = exo
    }
}

internal fun PhonePlayerActivity.releaseAdjacentSlot(target: Int) {
    val slot = playerAdjacentState.slots.remove(target) ?: return
    slot.listener?.let(slot.player::removeListener)
    slot.listener = null
    slot.preRendering = false
    slot.preRenderToken++
    if (playerAdjacentState.ghostIndex == target) {
        hideAdjacentGhost()
    }
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
    if (playerPageInitialized) detachAdjacentOutput(swipeGhostPage.playerView)
    if (playerLandscapeVideoInitialized) detachAdjacentOutput(swipeLandscapeGhostVideo)
    playerAdjacentState.ghostAttached = false
}

private fun detachAdjacentOutput(view: PlayerView) {
    view.setKeepContentOnPlayerReset(false)
    view.player = null
    view.setKeepContentOnPlayerReset(true)
}
