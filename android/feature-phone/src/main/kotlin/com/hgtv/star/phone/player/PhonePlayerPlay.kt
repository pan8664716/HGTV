// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 播放入口：打开会话、接预热源或后台取流并准备播放。 */
internal fun PhonePlayerActivity.play(
    episodeIndex: Int,
    startAt: Long = 0L,
    clearDanmakuOnSwitch: Boolean = false,
) {
    val d = detail ?: return
    if (episodeIndex !in d.episodes.indices) return
    syncAdjacentDetail(d)
    nextSeasonState.nextSeasonFinishedFor = ""
    val sameEpisode = episodeIndex == index
    if (!sameEpisode) hostSwitchPending = false
    // 换集时旧 PlayerView 可能仍绑着同一个进程级播放器；attach 后必须强制重交
    // 当前集自己的 Surface，否则解码在跑但画面一直不上屏。
    val rebindSurfaceAfterAttach = !sameEpisode || player == null
    if (!sameEpisode) {
        cancelPlaybackRequest()
        // 上一集的帧时间不能算到这一集头上；看门狗要靠它判断新画面是否真的上屏。
        lastFrameAt = 0L
        lastSurfaceFirstFrameAt = 0L
        holdFirstFrameAspectRatio()
        // 借下层推荐页 Surface 只适合进入播放页时同一集的交接。
        // 正式播放内部换集必须回到本页自己的 PlayerView，否则封面层在本页被隐藏，
        // 用户会继续看到下层旧画面直到新源出帧。
        if (playerFeedUnderlay) dropUnderlay()
        // 滑动预览会隐藏目标页的视频 Surface；完整起播兜底时也要恢复，避免只有封面层。
        playerPage.setVideoSurfaceVisible(true)
    }
    index = episodeIndex
    selectPlayerPagerEpisode(episodeIndex)
    val episodeId = d.episodes[episodeIndex]
    playbackCoordinator.open(
        seriesId = d.series.id,
        episodeId = episodeId,
        episodeIndex = episodeIndex,
        resumeAtMs = startAt,
        autoplay = true,
        resetEpisode = !sameEpisode,
    )
    // 换了一集：按集计的重试 / 降级额度全部归零（与电视端 `onEpisodeStart` 同一入口）。
    // 同一集重来（换档 / 手动重试）不在这里清：那会把「每集只重建一次」变成无限次。
    if (!sameEpisode) {
        playbackToasts.clear()
    }
    recovering = false
    cancelAutoRetry()
    clearPlaybackError()
    bindEpisodeUi(d, episodeIndex, clearDanmakuOnSwitch)
    prepareNextSeasonIfNeeded(d)

    // 预热命中：这一集的首块与地址都已经在手上了，只差挂媒体源（几十毫秒）。
    // 上限也要对得上：预热那一份比现在生效的档位高时不能接（见 `PhoneSession.takeNext`）。
    val warm = PhoneSession.takeNext(episodeId, playbackMaxQuality)
    if (warm != null) {
        // 旧媒体源先解绑（不 release 播放器），再挂预热好的这一集。
        playerListener?.let { player?.removeListener(it) }
        playerListener = null
        // 借下层 Surface 时**不要动推荐页那一块**：它是本页现在的输出端。
        if (!playerFeedUnderlay) PhoneServices.detach(playerPage.playerView)
        PhoneServices.detach(playerLandscapeVideo)
        attach(warm, startAt, rebindSurface = rebindSurfaceAfterAttach)
        return
    }

    releasePlayerImpl()
    setBufferingLabel("正在获取播放地址…")
    val requestScope = com.hgtv.star.core.RequestScope()
    playRequestScope = requestScope
    playRequestJob = scope.launch {
        try {
            // 上限读**实际生效**的那一档（自动降档结果优先），否则降档只在当前这一集生效。
            val maxQuality = playbackMaxQuality
            // 播放器打开先用 player 场景取当前集 + 后 14 集，避免快速跳集时二次请求。
            val modelQueue = d.episodes.drop(episodeIndex + 1).take(PhonePlayerActivity.playerModelQueueSize)
            val source = withContext(Dispatchers.IO) {
                requestScope.run {
                    runCatching {
                        playbackSource.stream(episodeId, maxQuality, d.playback, queue = modelQueue)
                    }
                }
            }
            if (!isActive || playRequestScope !== requestScope) return@launch
            var info = source.getOrNull()
            if (info == null) {
                val problem = source.exceptionOrNull()
                invalidateEpisodeCacheForRetry(episodeId, null)
                handleUnifiedPlaybackFailure(
                    problem ?: java.io.IOException("没有可用播放地址"),
                    com.hgtv.star.core.RecoveryLayer.ADDRESS,
                )
                return@launch
            }
            if (hostSwitchPending) {
                info = playbackSource.switchHost(info)
                hostSwitchPending = false
            }

            val preparedSource = withContext(Dispatchers.IO) {
                requestScope.run { runCatching { prepareSource(episodeId, info) } }
            }
            if (!isActive || playRequestScope !== requestScope) return@launch
            val prepared = preparedSource.getOrNull()
            if (prepared == null) {
                val problem = preparedSource.exceptionOrNull()
                // 本地准备会读落盘索引 / 首块；缓存对应的文件或地址不认了，
                // 继续重试同一份缓存只会重复失败。先整体作废这一集的地址与索引。
                if (problem == null || problem is java.io.IOException) {
                    invalidateEpisodeCacheForRetry(episodeId, info)
                }
                handleUnifiedPlaybackFailure(
                    problem ?: java.io.IOException("播放准备失败"),
                    com.hgtv.star.core.RecoveryLayer.PREPARE,
                )
                return@launch
            }

            if (!isActive || playRequestScope !== requestScope) {
                prepared.close()
                return@launch
            }
            attach(prepared, startAt, rebindSurface = rebindSurfaceAfterAttach)
        } finally {
            if (playRequestScope === requestScope) {
                playRequestScope = null
                playRequestJob = null
            }
        }
    }
}
