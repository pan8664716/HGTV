// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.app.ActivityManager
import android.content.Context
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.upstream.DataSource
import com.hgtv.star.core.AppConfig
import com.hgtv.star.core.Detail
import com.hgtv.star.core.PlaybackCache
import com.hgtv.star.core.RemoteVideo
import com.hgtv.star.phone.ui.primeAspectRatio
import com.hgtv.star.shared.media.VideoDataSource
import com.hgtv.star.shared.media.swapMediaSource
import kotlin.math.abs

internal class FeedPlayerSlot(
    val index: Int,
    var seriesId: String,
    var episodeId: String,
    var detail: Detail?,
    var episodeIndex: Int,
    var remote: RemoteVideo?,
    val player: ExoPlayer,
) {
    /** 媒体源已经挂上并调用过 prepare；不代表首帧一定已经出现。 */
    var sourcePrepared: Boolean = false
    var firstFrameRendered: Boolean = false
    var preRendering: Boolean = false
    var previewPlaying: Boolean = false
    var preparedAt: Long = 0L

    fun sourceFresh(): Boolean =
        preparedAt > 0L &&
            android.os.SystemClock.elapsedRealtime() - preparedAt <= PlaybackCache.URL_FRESH_MS
}

internal fun PhoneFeedView.feedPlayerPoolLimit(): Int {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    return if (manager?.isLowRamDevice == true) {
        AppConfig.Playback.Preload.PHONE_LOW_RAM_PLAYER_POOL_SIZE
    } else {
        AppConfig.Playback.Preload.PHONE_PLAYER_POOL_SIZE
    }
}

internal fun PhoneFeedView.pooledPlayerSlot(index: Int): FeedPlayerSlot? {
    val slot = feedPooledPlayers[index] ?: return null
    val currentSeriesId = feedItems.getOrNull(index)?.id
    if (currentSeriesId == null || currentSeriesId != slot.seriesId) {
        releasePooledPlayerSlot(index)
        return null
    }
    return slot
}

internal fun PhoneFeedView.ensurePooledPlayerSlot(
    index: Int,
    seriesId: String,
    episodeId: String,
): FeedPlayerSlot {
    val current = feedPooledPlayers[index]
    if (current != null && current.seriesId == seriesId && current.episodeId == episodeId) {
        return current
    }
    if (current != null) releasePooledPlayerSlot(index)
    val slot = FeedPlayerSlot(
        index = index,
        seriesId = seriesId,
        episodeId = episodeId,
        detail = null,
        episodeIndex = feedCurrentEpisodeIndex[index] ?: 0,
        remote = null,
        player = PhoneServices.newPlayer(
            context,
            feedLibrary?.decoderMode,
            handleAudioFocus = false,
        ),
    )
    feedPooledPlayers[index] = slot
    trimPooledPlayers(feedPlayingIndex, preferred = index)
    if (com.hgtv.star.shared.BuildConfig.DEBUG) {
        android.util.Log.i(
            "HGTV-PHONE",
            "player pool create index=$index size=${feedPooledPlayers.size} " +
                "limit=${feedPlayerPoolLimit()}",
        )
    }
    return slot
}

/**
 * 把正式播放页正在使用的实例收进当前页的池槽。
 *
 * 同一台实例可能原本挂在另一个索引（播放页里换过集 / 换过作品），先从旧索引摘掉，
 * 但**不能 release**，它仍然属于正式播放页。
 */
internal fun PhoneFeedView.adoptPooledPlayerSlot(
    index: Int,
    player: ExoPlayer,
    seriesId: String,
    episodeId: String,
    detail: Detail?,
    episodeIndex: Int,
    remote: RemoteVideo?,
) {
    val oldKeys = feedPooledPlayers
        .filterValues { it.player === player && it.index != index }
        .keys
        .toList()
    oldKeys.forEach { oldIndex ->
        val oldSlot = feedPooledPlayers[oldIndex]
        feedPageAt(oldIndex)?.let { page ->
            if (page.playerView.player === player) page.playerView.detachPooledOutput()
        }
        feedPooledPlayers.remove(oldIndex)
        oldSlot?.remote?.takeIf { it !== PhoneSession.remote }?.let(::closeRemoteLater)
    }

    val existing = feedPooledPlayers[index]
    if (existing != null && existing.player !== player) releasePooledPlayerSlot(index)
    val slot = existing?.takeIf { it.player === player } ?: FeedPlayerSlot(
        index = index,
        seriesId = seriesId,
        episodeId = episodeId,
        detail = detail,
        episodeIndex = episodeIndex,
        remote = remote,
        player = player,
    ).also { feedPooledPlayers[index] = it }
    slot.seriesId = seriesId
    slot.episodeId = episodeId
    slot.detail = detail
    slot.episodeIndex = episodeIndex
    slot.remote = remote
    slot.sourcePrepared = true
    slot.preRendering = false
    slot.previewPlaying = false
    slot.preparedAt = android.os.SystemClock.elapsedRealtime()
    runCatching { player.volume = 1f }
    trimPooledPlayers(feedPlayingIndex)
}

internal fun PhoneFeedView.releasePooledPlayerSlot(index: Int) {
    val slot = feedPooledPlayers.remove(index) ?: return
    val wasActive = feedPlayer === slot.player
    detachFeedListener(slot.player)
    feedPageAt(index)?.let { page ->
        if (page.playerView.player === slot.player) page.playerView.detachPooledOutput()
    }
    if (feedPlayer === slot.player) feedPlayerSlot = null
    PhoneServices.deactivate(slot.player)
    runCatching { slot.player.pause() }
    runCatching { slot.player.release() }
    val remote = slot.remote
    if (remote != null && remote !== PhoneSession.remote) {
        closeRemoteLater(remote)
    }
    if (com.hgtv.star.shared.BuildConfig.DEBUG) {
        android.util.Log.i(
            "HGTV-PHONE",
            "player pool release index=$index active=$wasActive " +
                "size=${feedPooledPlayers.size}",
        )
    }
}

internal fun PhoneFeedView.releaseAllPooledPlayers(resetSession: Boolean = false) {
    feedPoolIdleRelease?.let { feedRemoveCallbacks(it) }
    feedPoolIdleRelease = null
    val sessionRemote = PhoneSession.remote
    feedPooledPlayers.keys.toList().forEach(::releasePooledPlayerSlot)
    feedPlayerSlot = null
    feedRemoteSlot = null
    if (resetSession) {
        PhoneSession.reset()
        if (sessionRemote != null) {
            closeRemoteLater(sessionRemote)
        }
    }
}

/** ExoPlayer.release() 之后内部播放线程会退出；池里不能再把这种实例当成可播放对象。 */
internal fun ExoPlayer.isFeedPlayerAlive(): Boolean =
    runCatching { playbackLooper.thread.isAlive }.getOrDefault(false)

/**
 * 丢弃已经 release 的实例，不再次调用 pause / release。
 *
 * 播放器可能已经从池里删掉，但另一个重建中的 FeedView 仍拿着旧字段；这时必须
 * 同时清掉池槽、PlayerView 输出和进程级活动指针，避免后续点击落到 dead handler。
 */
internal fun PhoneFeedView.discardReleasedPooledPlayer(player: ExoPlayer): Boolean {
    var found = false
    feedPooledPlayers
        .filterValues { it.player === player }
        .keys
        .toList()
        .forEach { index ->
            val slot = feedPooledPlayers.remove(index) ?: return@forEach
            found = true
            feedPlayerListeners.remove(slot.player)
            feedPageAt(index)?.let { page ->
                if (page.playerView.player === slot.player) page.playerView.detachPooledOutput()
            }
            slot.remote?.takeIf { it !== PhoneSession.remote }?.let(::closeRemoteLater)
        }
    if (feedPlayer === player) {
        feedPlayerSlot = null
        found = true
    }
    PhoneServices.deactivate(player)
    return found
}

/**
 * 当前播放器被误释放后自动恢复。
 *
 * 点击播放/暂停和进度 ticker 都会调用这里：先彻底摘掉失效实例，再按当前卡片重新
 * 取详情 / 地址并起播。旧实例已经 release，绝不能继续在它上面 pause / play / prepare。
 */
internal fun PhoneFeedView.recoverReleasedFeedPlayer(player: ExoPlayer) {
    val index = feedPlayingIndex
    val canRestart = !feedHidden && isAttachedToWindow && index in feedItems.indices
    val sessionBelongsHere = PhoneSession.owner == PhoneSession.Owner.FEED &&
        feedSessionBelongsToThisFeed()
    val sessionRemote = PhoneSession.remote.takeIf { sessionBelongsHere }
    discardReleasedPooledPlayer(player)
    if (sessionBelongsHere) {
        PhoneSession.reset()
        if (sessionRemote != null) closeRemoteLater(sessionRemote)
    }
    feedPlayingDetailSlot = null
    feedRemoteSlot = null
    feedPaused = false
    if (com.hgtv.star.shared.BuildConfig.DEBUG) {
        android.util.Log.w(
            "HGTV-PHONE",
            "recover released player index=$index attached=$isAttachedToWindow " +
                "hidden=$feedHidden restart=$canRestart",
        )
    }
    notifyPlayStateChangedImpl()
    if (canRestart) {
        playIndexForSettings(index, -1L, clearDanmakuOnSwitch = false)
    }
}

private fun closeRemoteLater(remote: RemoteVideo) {
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
        { runCatching { remote.close() } },
        AppConfig.Playback.Preload.PHONE_CLOSE_DELAY_MS,
    )
}

/**
 * 按到当前页的距离保留播放器。
 *
 * 正常设备最多 3 台（上一页 / 当前页 / 下一页），低内存设备 2 台。
 * 池满时只淘汰离当前页最远的一台；正在播放的当前页永远不会被淘汰。
 */
internal fun PhoneFeedView.trimPooledPlayers(anchor: Int, preferred: Int = anchor) {
    if (feedPooledPlayers.isEmpty()) return
    feedPooledPlayers.values
        .filter { feedItems.getOrNull(it.index)?.id != it.seriesId }
        .map { it.index }
        .toList()
        .forEach(::releasePooledPlayerSlot)
    val limit = feedPlayerPoolLimit().coerceAtLeast(1)
    val keep = feedPooledPlayers.keys
        .sortedWith(
            compareBy<Int> {
                when (it) {
                    anchor -> 0
                    preferred -> 1
                    else -> 2
                }
            }.thenBy { abs(it - anchor) }.thenBy { it }
        )
        .take(limit)
        .toHashSet()
    feedPooledPlayers.keys
        .filterNot { it in keep }
        .toList()
        .forEach(::releasePooledPlayerSlot)
}

internal fun PhoneFeedView.pausePooledPlayersExcept(player: ExoPlayer?) {
    feedPooledPlayers.values.forEach { slot ->
        if (slot.player !== player) {
            slot.preRendering = false
            slot.previewPlaying = false
            runCatching {
                slot.player.playWhenReady = false
                slot.player.pause()
            }
            PhoneServices.enableAudioFocus(slot.player, false)
        }
    }
}

internal fun PhoneFeedView.releasePooledPlayersExcept(player: ExoPlayer?) {
    feedPooledPlayers.values
        .filter { it.player !== player }
        .map { it.index }
        .toList()
        .forEach(::releasePooledPlayerSlot)
}

internal fun PhoneFeedView.cancelPoolIdleRelease() {
    feedPoolIdleRelease?.let { feedRemoveCallbacks(it) }
    feedPoolIdleRelease = null
}

/**
 * 隐藏页只保留当前播放器一小段时间；超时后仍不可见就彻底归还解码器。
 *
 * 这样快速切 tab / 短暂退后台不会重建播放器，而三个体裁页也不会长期各占一台。
 */
internal fun PhoneFeedView.schedulePoolIdleRelease() {
    cancelPoolIdleRelease()
    val task = Runnable {
        feedPoolIdleRelease = null
        if (!feedHidden) return@Runnable
        val ownsSession = PhoneSession.owner == PhoneSession.Owner.FEED &&
            feedSessionBelongsToThisFeed()
        releaseAllPooledPlayers(resetSession = ownsSession)
    }
    feedPoolIdleRelease = task
    feedPostDelayed(
        task,
        AppConfig.Playback.Preload.PHONE_PLAYER_POOL_IDLE_RELEASE_MS,
    )
}

internal fun PhoneFeedView.markPooledPlayerSourceReady(index: Int) {
    pooledPlayerSlot(index)?.let {
        it.sourcePrepared = true
        if (it.preparedAt <= 0L) {
            it.preparedAt = android.os.SystemClock.elapsedRealtime()
        }
    }
}

internal fun PhoneFeedView.markPooledPlayerFirstFrame(index: Int) {
    pooledPlayerSlot(index)?.firstFrameRendered = true
}

/**
 * 发布当前活动播放器，但保留仍被池内其他页使用的上一条 RemoteVideo。
 *
 * 池切页不是“旧源不再需要”：上一页为了回看仍保留独立播放器。只有该槽真正被
 * 淘汰或整个池释放时，才关闭对应的地址与传输。
 */
internal fun PhoneFeedView.publishPooledSession(
    detail: Detail,
    episodeIndex: Int,
    remote: RemoteVideo,
    quality: String,
) {
    val previous = PhoneSession.remote
    val previousStillPooled = previous != null && previous !== remote &&
        feedPooledPlayers.values.any { it.remote === previous }
    PhoneSession.publish(
        detail = detail,
        index = episodeIndex,
        remote = remote,
        quality = quality,
        by = PhoneSession.Owner.FEED,
        feed = feedCacheKey(),
        closePrevious = !previousStillPooled,
    )
}

/**
 * 把已经预热的播放器挂到它自己的页面。
 *
 * 相邻页各有一块 PlayerView，因此这里不碰当前页字段，也不重设媒体源。
 */
internal fun PhoneFeedView.attachPooledPlayerToPageImpl(
    page: PhoneVideoPage,
    index: Int,
) {
    val slot = pooledPlayerSlot(index) ?: return
    val series = feedItems.getOrNull(index) ?: return
    page.bind(series, "第${(slot.episodeIndex + 1)}集")
    applyPageVideoSettingsImpl(page)
    val info = slot.remote?.info
    val width = info?.videoWidth?.takeIf { it > 0 } ?: 0
    val height = info?.videoHeight?.takeIf { it > 0 } ?: 0
    if (width > 0 && height > 0) page.updateVideoFrame(width, height)
    if (page.playerView.player !== slot.player) {
        page.playerView.detachPooledOutput()
        page.playerView.player = slot.player
    }
    val verticalSource = if (width > 0 && height > 0) height > width else series.vertical
    page.playerView.primeAspectRatio(width, height, verticalSource)
    if (slot.firstFrameRendered) page.hideEpisodeCover() else page.showEpisodeCover(series.cover)
    if (index != feedPlayingIndex) slot.player.playWhenReady = false
    if (index != feedPlayingIndex) startPooledPreRenderImpl(index, slot)
}

/** ViewHolder 复用时：池里没有这一项就摘掉旧引用，有就挂回它自己的独立播放器。 */
internal fun PhoneFeedView.attachPooledPlayerForAdapter(
    page: PhoneVideoPage,
    index: Int,
) {
    if (pooledPlayerSlot(index) == null) {
        page.playerView.detachPooledOutput()
        return
    }
    attachPooledPlayerToPageImpl(page, index)
}

/**
 * 回收池内实例时同时关闭 PlayerView 快门。
 *
 * `keepContentOnPlayerReset=true` 会保留上一帧，适合同一页原地换源；页面已被回收时
 * 这一帧属于旧作品，必须关掉，否则下次复用到这个 ViewHolder 会先闪出旧画面。
 */
internal fun PlayerView.detachPooledOutput() {
    setKeepContentOnPlayerReset(false)
    player = null
    setKeepContentOnPlayerReset(true)
}

/**
 * 在相邻页自己的 Surface 上静音预渲染首帧。
 *
 * ExoPlayer 的 `prepare + playWhenReady=false` 只完成缓冲，解码器通常要等真正播放
 * 才启动。这里短暂以 0 音量播放，拿到 `onRenderedFirstFrame` 后立即暂停；用户滑入时
 * 直接恢复音量与播放状态，避免切页后再等解码器初始化。
 */
internal fun PhoneFeedView.startPooledPreRenderImpl(index: Int, slot: FeedPlayerSlot) {
    val page = feedPageAt(index) ?: return
    if (feedHidden || index == feedPlayingIndex || slot.firstFrameRendered || slot.preRendering ||
        slot.previewPlaying || !slot.sourcePrepared || !page.isAttachedToWindow
    ) return
    slot.preRendering = true
    runCatching { slot.player.volume = 0f }
    slot.player.playWhenReady = true
    val player = slot.player
    feedPostDelayed(
        {
            if (feedPooledPlayers[index] !== slot || !slot.preRendering ||
                index == feedPlayingIndex || slot.player !== player || slot.previewPlaying
            ) return@feedPostDelayed
            slot.preRendering = false
            runCatching { player.pause() }
            if (com.hgtv.star.shared.BuildConfig.DEBUG) {
                android.util.Log.i(
                    "HGTV-PHONE",
                    "player pool pre-render timeout index=$index anchor=$feedPlayingIndex",
                )
            }
        },
        AppConfig.Playback.Preload.PHONE_PRE_RENDER_TIMEOUT_MS,
    )
}

/** 相邻页首帧已经上屏：立刻暂停静音预渲染，保留这一帧等待用户滑入。 */
internal fun PhoneFeedView.finishPooledPreRenderImpl(index: Int, player: ExoPlayer) {
    val slot = feedPooledPlayers[index] ?: return
    if (!slot.preRendering || slot.player !== player || slot.previewPlaying) return
    slot.preRendering = false
    if (index != feedPlayingIndex) runCatching { player.pause() }
}

/**
 * 相邻页完整预热：独立播放器、独立 MediaSource，默认不播放也不出声。
 *
 * 这是手机端接近 VEVod 无缝体验的关键：用户滑到下一页之前，目标页已经有自己的
 * `ExoPlayer`、索引、首块和 Surface；切页只激活实例，不再从详情和地址请求开始。
 * [video] 的所有权在进入本方法后归播放器池；失败时由这里负责关闭。
 */
internal fun PhoneFeedView.primePooledPlayerImpl(
    index: Int,
    detail: Detail,
    episodeIndex: Int,
    episodeId: String,
    video: RemoteVideo,
): Boolean {
    if (feedHidden || !isAttachedToWindow || index !in feedItems.indices ||
        abs(index - feedPlayingIndex) > 1
    ) {
        runCatching { video.close() }
        return false
    }
    val series = feedItems[index]
    if (series.id != detail.series.id) {
        runCatching { video.close() }
        return false
    }
    val existing = pooledPlayerSlot(index)
    if (existing?.sourcePrepared == true || existing?.player === feedPlayer) {
        runCatching { video.close() }
        return false
    }

    val slot = ensurePooledPlayerSlot(index, series.id, episodeId)
    slot.detail = detail
    slot.episodeIndex = episodeIndex
    slot.remote = video
    slot.sourcePrepared = false
    slot.firstFrameRendered = false
    slot.preparedAt = 0L

    val prepared = runCatching {
        feedPageAt(index)?.let { page ->
            attachPooledPlayerToPageImpl(page, index)
        }
        val factory = DataSource.Factory { VideoDataSource(video) }
        slot.player.swapMediaSource(episodeId, video.info.url, factory)
        slot.player.setPlaybackSpeed(feedUserSpeed)
        slot.player.playWhenReady = false
        attachFeedListener(slot.player, index)
        slot.player.prepare()
    }.isSuccess
    if (!prepared) {
        releasePooledPlayerSlot(index)
        return false
    }
    slot.sourcePrepared = true
    slot.preparedAt = android.os.SystemClock.elapsedRealtime()
    startPooledPreRenderImpl(index, slot)
    if (feedSwipePreviewIndex == index) startFeedSwipePreviewImpl(index)
    trimPooledPlayers(feedPlayingIndex, preferred = index)
    if (com.hgtv.star.shared.BuildConfig.DEBUG) {
        android.util.Log.i(
            "HGTV-PHONE",
            "player pool prime index=$index anchor=$feedPlayingIndex " +
                "episode=${episodeIndex + 1} page=${feedPageAt(index) != null}",
        )
    }
    return true
}

/**
 * 当前页已有独立预热播放器时直接激活，不再换 Surface、不再 setMediaSource。
 */
internal fun PhoneFeedView.activatePreparedPooledPlayerImpl(
    index: Int,
    slot: FeedPlayerSlot,
    clearDanmakuOnSwitch: Boolean,
): Boolean {
    if (!slot.sourcePrepared || !slot.player.isFeedPlayerAlive() || slot.player.playerError != null) {
        releasePooledPlayerSlot(index)
        return false
    }
    val series = feedItems.getOrNull(index) ?: return false
    val detail = slot.detail ?: return false
    val remote = slot.remote ?: return false

    pausePooledPlayersExcept(slot.player)
    slot.preRendering = false
    slot.previewPlaying = false
    feedPlayerSlot = slot.player
    feedPlayingDetailSlot = detail
    feedRemoteSlot = remote
    feedCurrentEpisodeIndex[index] = slot.episodeIndex
    feedCurrentEpisodeIds[index] = slot.episodeId

    val page = feedPageAt(index)
    if (page != null) {
        attachPageForSurface(
            slot.player,
            page,
            series,
            slot.episodeIndex,
            forceRebind = false,
            clearDanmakuOnSwitch = clearDanmakuOnSwitch,
            videoInfo = remote.info,
        )
        if (!slot.firstFrameRendered) page.showEpisodeCover(series.cover)
    }
    attachFeedListener(slot.player, index)
    publishPooledSession(detail, slot.episodeIndex, remote, remote.info.quality)
    slot.player.setPlaybackSpeed(feedUserSpeed)
    runCatching { slot.player.volume = 1f }
    PhoneServices.enableAudioFocus(slot.player, true)
    slot.player.playWhenReady = !feedPaused
    if (slot.firstFrameRendered || slot.player.playbackState == Player.STATE_READY) {
        reportRecommendImpression(index)
        scheduleFeedFillAfterFirstFrame()
        scheduleRecommendBatchModels(index)
    }
    startProgressTicker()
    trimPooledPlayers(index)
    if (com.hgtv.star.shared.BuildConfig.DEBUG) {
        android.util.Log.i(
            "HGTV-PHONE",
            "player pool activate index=$index size=${feedPooledPlayers.size} " +
                "firstFrame=${slot.firstFrameRendered}",
        )
    }
    PhoneSession.endHandoff()
    return true
}
