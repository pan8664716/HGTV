// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.video.VideoSize
import com.hgtv.star.core.VideoFrameMode
import com.hgtv.star.core.toFit
import com.hgtv.star.phone.ui.resizeMode
import com.hgtv.star.shared.debug.PlaybackStartupTrace

/** 挂播放器监听（换集时先摘旧的：进程单例上不能越积越多）。 */
internal fun PhonePlayerActivity.attachPlayerListener(exo: ExoPlayer) {
    playerListener?.let { exo.removeListener(it) }
    val hook = object : Player.Listener {
        /**
         * 片尾。
         *
         * * 播放列表里还挂着下一集 → ExoPlayer 自己会无感推进，这里什么都不做
         *   （界面由 [onAdvanced] 接手）；
         * * 没有下一集 → 最后一集播完，走老路。
         */
        override fun onPlaybackStateChanged(state: Int) {
            if (player !== exo) return
            player?.let { playbackSessionState.onPlayerState(state, it.playWhenReady) }
            syncKeepScreenOnForListener()
            syncDanmakuClockNowForListener()
            syncProgressBarsNowForListener()
            // 真的能放了：重试窗口结束、错误页收起（与电视端「出第一帧」同一职责）。
            syncBufferingUiForListener(state)
            if (state == Player.STATE_READY) {
                watchdog.onReady()
                notePlaybackHealthy()
                // 个别设备不回调 onRenderedFirstFrame：READY 后短暂兜底撤图，
                // 避免封面一直盖着已经播放的画面。
                root.postDelayed({
                    if (player?.playbackState == Player.STATE_READY) hideEpisodeCover()
                }, 600L)
            }
            if (state == Player.STATE_ENDED) {
                val hasNextItem = runCatching {
                    val exo = player ?: return@runCatching false
                    val current = exo.currentMediaItemIndex.coerceAtLeast(0)
                    exo.mediaItemCount > current + 1
                }.getOrDefault(false)
                if (hasNextItem) return
                PhoneSession.nextQueued = false
                next()
            }
        }

        /** 无感换集：自动推进或手动 seek 到队列下一项时回调。 */
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (player !== exo) return
            val nextIndex = detail?.episodes?.indexOf(mediaItem?.mediaId).takeIf { it != null && it >= 0 }
                ?: (index + 1)
            val manual = reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK &&
                listenerManualNextTarget == nextIndex
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && !manual) return
            if (manual) listenerManualNextTarget = -1
            if (manual) {
                onAdvanced(
                    nextIndex,
                    completedPrevious = false,
                    previousPosition = listenerManualNextPosition,
                    clearDanmakuOnSwitch = true,
                )
                listenerManualNextPosition = 0L
            } else {
                onAdvanced(nextIndex)
            }
        }

        // 只同步底部那颗按钮的图标。中心图标**不跟着 isPlaying 走**：
        // 缓冲卡一下、片尾、切集都会让 isPlaying 变 false，那时冒出一个暂停图标
        // 会让人以为「自己按了暂停」。中心图标只在用户明确操作时出现。
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (player !== exo) return
            syncPlayGlyphForListener()
            syncDanmakuClockNowForListener()
            syncProgressBarsNowForListener()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (player !== exo) return
            syncKeepScreenOnForListener()
            syncDanmakuClockNowForListener()
        }

        override fun onPlaybackParametersChanged(parameters: com.google.android.exoplayer2.PlaybackParameters) {
            if (player !== exo) return
            syncDanmakuClockNowForListener()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (player !== exo) return
            danmakuTargetForListener()?.resetCursor()
            syncDanmakuClockNowForListener()
            syncProgressBarsNowForListener()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (player !== exo) return
            playbackSessionState.onVideoSize(videoSize.width, videoSize.height)
            listenerDebugVideo = "${videoSize.width}x${videoSize.height}"
            // 画面模式按用户选择走，**不按片源方向强制 ZOOM**：
            // 竖屏短剧在竖屏里等比铺满会裁掉左右，换集后突然放大就是从这里来的。
            val target = (library?.frameMode ?: VideoFrameMode.FIT).toFit().resizeMode()
            playerPage.setResizeMode(target)
            if (playerLandscapeMode) {
                playerLandscapeVideo.resizeMode = target
                syncLandscapeLetterboxImpl()
            }
            if (playerFeedUnderlay) PhoneSession.setFeedVideoResize(target)
            playerPage.updateVideoFrame(videoSize.width, videoSize.height)
            updateLandscapeVideoFrameForListener(videoSize.width, videoSize.height)
            if (listenerPendingFullscreen) root.post { enterPendingFullscreenForListener() }
        }

        override fun onRenderedFirstFrame() {
            if (player !== exo) return
            // 部分设备只回调首帧、不走逐帧心跳；首帧同样是画面已经上屏的硬证据。
            lastSurfaceFirstFrameAt = android.os.SystemClock.elapsedRealtime()
            watchdog.onFrame()
            completeFirstFrameAspectRatio()
            hideEpisodeCover()
            setBufferingLabel(null)
            PlaybackStartupTrace.firstFrame("phone.player")
            // 画面真的上屏才放行弹幕；STATE_READY 后还可能回 buffering，
            // 那时放行会让弹幕先跑、出帧后再清空重排（与 TV 端同一闸门）。
            playerDanmakuLoader.onFrameRendered(player?.currentPosition ?: 0L)
            root.postDelayed({
                if (player === exo && exo.isPlaying) {
                    playbackSessionState.markStablePlayback()
                }
            }, com.hgtv.star.core.AppConfig.Playback.RECOVERY_STABLE_MS)
        }

        override fun onPlayerError(error: com.google.android.exoplayer2.PlaybackException) {
            if (player !== exo) return
            // 调试面板要把错误码留下：用户报「花屏 / 有声无画」时，这一行就是结论。
            listenerDebugError = "${error.errorCodeName}(${error.errorCode})"
            syncDebugOverlayForListener()
            // 自愈：同档重建 / 降档 / 退避重试 / 错误页，决策全在 `:core`（见 PhonePlaybackRecovery）。
            handlePlayerFailure(error)
        }
    }
    playerListener = hook
    exo.addListener(hook)
    // 「画面还在走」的唯一硬证据是逐帧回调：播放位置在走、声音在响，都可能只是音频在动。
    // 回调在播放线程上、每帧一次，因此这里只写一个 volatile 字段，不碰任何 View。
    exo.setVideoFrameMetadataListener { _, _, _, _ ->
        if (player !== exo) return@setVideoFrameMetadataListener
        // 调试包注入「有声音无画面」（`DebugFaults.STALL_FRAME`）：故意不记账，
        // 让看门狗那条链路能在开发机上按需复现。正式包里 BuildConfig.DEBUG 是常量 false。
        if (!com.hgtv.star.shared.BuildConfig.DEBUG ||
            com.hgtv.star.shared.debug.DebugFaults.mode(this) !=
            com.hgtv.star.shared.debug.DebugFaults.Mode.STALL_FRAME
        ) {
            lastFrameAt = android.os.SystemClock.elapsedRealtime()
            watchdog.onFrame()
        }
    }
}

/**
 * 无感换集：ExoPlayer 已经把下一集的 period 推上屏了，这里只把**界面与记录**跟上。
 *
 * 与 TV 的 `onEpisodeAdvanced` 同一套职责划分：
 *
 * * 播完那一集按 `completed` 落盘（此时 `duration` 已经是新一集的了，必须用旧值）；
 * * 集号、标题、选集按钮、会话全部换到新一集；
 * * **不重设媒体源**——重设就会黑屏重来，那正是要避免的。
 */
internal fun PhonePlayerActivity.onAdvanced(
    nextIndex: Int,
    completedPrevious: Boolean = true,
    previousPosition: Long = 0L,
    clearDanmakuOnSwitch: Boolean = false,
    closePreviousRemote: Boolean = true,
) {
    val d = detail ?: return
    if (nextIndex !in d.episodes.indices) return
    finishAdvanced(
        nextData = d,
        nextIndex = nextIndex,
        previousData = d,
        previousPosition = previousPosition,
        previousDuration = listenerLastDuration,
        completedPrevious = completedPrevious,
        clearDanmakuOnSwitch = clearDanmakuOnSwitch,
        closePreviousRemote = closePreviousRemote,
    )
}

internal fun PhonePlayerActivity.finishAdvanced(
    nextData: com.hgtv.star.core.Detail,
    nextIndex: Int,
    previousData: com.hgtv.star.core.Detail,
    previousPosition: Long,
    previousDuration: Long,
    completedPrevious: Boolean,
    clearDanmakuOnSwitch: Boolean,
    closePreviousRemote: Boolean,
) {
    if (nextIndex !in nextData.episodes.indices) return
    val previous = index
    // 无感换集也必须结束“借用推荐页 Surface”的交接状态。继续借会把下层页面
    // 留在透明播放页背后，横竖片源比例切换时正好从新黑边里透出来。
    if (playerFeedUnderlay) {
        dropUnderlay()
    }

    reportRecommendEvent(
        if(completedPrevious) com.hgtv.star.core.RecommendBehavior.Event.PLAY_COMPLETE
        else com.hgtv.star.core.RecommendBehavior.Event.SKIPPED,
        positionMs=previousPosition,
        durationMs=previousDuration,
    )

    // 播完那一集：写 completed（用切走之前记下的时长）。
    val lib = library
    if (lib != null && previous in previousData.episodes.indices && previousDuration > 0) {
        // 自动推进才是「整集看完」；手动上滑是中途换集，只保存当前续播点。
        val done = playbackSessionState.progress.snapshot(
            series = previousData.series,
            episodeId = previousData.episodes[previous],
            episodeIndex = previous,
            position = previousPosition,
            duration = previousDuration,
            completed = completedPrevious,
        )
        runCatching { lib.save(done) }
        // 账号侧也要跟着走（与 TV 的 `syncProgress` 同一条路）：不推的话，
        // 在手机上看完的这几集换到电视上还是「没看过」。
        runCatching { PhoneAccountSync.progress(this, done, previousData.episodes.size) }
    }

    detail = nextData
    index = nextIndex
    selectPlayerPagerEpisode(nextIndex)
    attachCurrentPlayerToPagerPage()
    showEpisodeCover(
        nextData.episodeCover(nextData.episodes[nextIndex]).ifBlank { nextData.series.cover }
    )
    reconcileAdjacentSlots()
    PhoneSession.nextQueued = false
    PhoneSession.clearNext()
    // 新一集必须先清掉上一集的帧证据，弹幕加载才不会把旧首帧误判成新集已出画面。
    lastFrameAt = 0L
    lastSurfaceFirstFrameAt = 0L
    bindEpisodeUiForListener(nextData, nextIndex, clearDanmakuOnSwitch)
    syncChipsForListener()
    listenerLastDuration = 0
    listenerPrefetchAt = 0
    // 无感切集没有走 `attach()`：上一集出帧后看门狗已经撤掉，这里必须按新一集重挂。
    // 否则音频继续走、视频停在上一帧时，没有任何恢复路径会被触发。
    armPictureWatchdog()
    // 会话换成**这一集**的 RemoteVideo：它在挂队列时就被 `queuedVideo` 记下了。
    // 用 `PhoneSession.remote` 会把上一集的引用写回去——那一份马上要关，
    // 信息流接手时会拿到一个已关闭的传输。
    val video = queuedVideo
    queuedVideo = null
    queuedEpisodeId = ""
    if (video != null) {
        remote = video
        PhoneSession.publish(
            nextData,
            nextIndex,
            video,
            video.info.quality,
            PhoneSession.Owner.PLAYER,
            closePrevious = closePreviousRemote,
        )
    }
    reportRecommendEvent(
        com.hgtv.star.core.RecommendBehavior.Event.PLAY_START,
        positionMs=0,
        durationMs=0,
        episodeIndexOverride=nextIndex,
    )
    prepareNextSeasonIfNeeded(nextData)
    scheduleAdjacentEpisodeWarmup()
    muteBufferingUiForListener()
    android.util.Log.i(
        "HGTV-PHONE",
        "advanced seamlessly series=${nextData.series.id} episode=${nextIndex + 1}",
    )
}
