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
        player = PhoneServices.newPlayer(context, feedLibrary?.decoderMode),
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
    slot.preparedAt = android.os.SystemClock.elapsedRealtime()
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
            runCatching {
                slot.player.playWhenReady = false
                slot.player.pause()
            }
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
    if (index != feedPlayingIndex) slot.player.playWhenReady = false
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
 * 相邻页完整预热：独立播放器、独立 MediaSource，默认不播放也不出声。
 */
internal fun PhoneFeedView.primePooledPlayerImpl(
    index: Int,
    detail: Detail,
    episodeIndex: Int,
    episodeId: String,
    video: RemoteVideo,
): Boolean {
    if (index !in feedItems.indices || abs(index - feedPlayingIndex) > 1) {
        runCatching { video.close() }
        return false
    }
    val existing = pooledPlayerSlot(index)
    if (existing?.sourcePrepared == true) {
        runCatching { video.close() }
        return false
    }
    val series = feedItems[index]
    val slot = ensurePooledPlayerSlot(index, series.id, episodeId)
    slot.detail = detail
    slot.episodeIndex = episodeIndex
    slot.remote = video
    slot.sourcePrepared = false
    slot.firstFrameRendered = false
    slot.preparedAt = 0L

    val page = feedPageAt(index)
    if (page != null) {
        page.bind(series, "第${episodeIndex + 1}集")
        applyPageVideoSettingsImpl(page)
        page.updateVideoFrame(video.info.videoWidth, video.info.videoHeight)
        if (page.playerView.player !== slot.player) {
            page.playerView.detachPooledOutput()
            page.playerView.player = slot.player
        }
        val verticalSource = if (video.info.videoWidth > 0 && video.info.videoHeight > 0) {
            video.info.videoHeight > video.info.videoWidth
        } else {
            series.vertical
        }
        page.playerView.primeAspectRatio(
            video.info.videoWidth,
            video.info.videoHeight,
            verticalSource,
        )
    }

    val factory = DataSource.Factory { VideoDataSource(video) }
    slot.player.swapMediaSource(episodeId, video.info.url, factory)
    slot.sourcePrepared = false
    slot.player.setPlaybackSpeed(feedUserSpeed)
    slot.player.playWhenReady = false
    attachFeedListener(slot.player, index)
    if (abs(index - feedPlayingIndex) <= 1) {
        // 相邻页此时不可见，不需要等 AspectRatioFrameLayout 出首帧；提前 prepare
        // 才能让用户滑过来时真的已经有一台可播放的独立播放器。
        val prepared = runCatching { slot.player.prepare() }.isSuccess
        if (prepared) {
            if (feedPooledPlayers[index] !== slot) return false
            slot.sourcePrepared = true
            slot.preparedAt = android.os.SystemClock.elapsedRealtime()
            if (com.hgtv.star.shared.BuildConfig.DEBUG) {
                android.util.Log.i(
                    "HGTV-PHONE",
                    "player pool prime index=$index anchor=$feedPlayingIndex " +
                        "page=${page != null}",
                )
            }
        } else {
            releasePooledPlayerSlot(index)
            return false
        }
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
    if (!slot.sourcePrepared) return false
    val series = feedItems.getOrNull(index) ?: return false
    val detail = slot.detail ?: return false
    val remote = slot.remote ?: return false

    pausePooledPlayersExcept(slot.player)
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
    }
    attachFeedListener(slot.player, index)
    publishPooledSession(detail, slot.episodeIndex, remote, remote.info.quality)
    slot.player.setPlaybackSpeed(feedUserSpeed)
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
