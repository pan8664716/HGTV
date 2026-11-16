// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.feature.recommend

import com.hgtv.star.core.ContentRepository
import com.hgtv.star.core.Detail
import com.hgtv.star.core.DetailCache
import com.hgtv.star.core.RecommendBehavior
import com.hgtv.star.core.RemoteVideo
import com.hgtv.star.core.Series
import com.hgtv.star.core.StageDeadline
import com.hgtv.star.core.detail
import com.hgtv.star.shared.architecture.PlaybackSourceCoordinator
import com.hgtv.star.shared.data.Library
import com.hgtv.star.shared.debug.PlaybackStartupTrace
import com.hgtv.star.shared.debug.StartupBudgets
import com.hgtv.star.shared.feed.RecommendStore

/**
 * 推荐流中“选一部并准备播放”的共享行为。
 *
 * 页面仍负责播放器、Surface、动画和输入；这里统一详情缓存、续播点、地址准备、
 * 连续失败上限、曝光与停留结算。
 */
class RecommendPlaybackController(
    private val repository: ContentRepository,
    private val library: Library,
    private val source: PlaybackSourceCoordinator,
    private val store: RecommendStore,
    private val skipLimit: Int = 4,
    private val detailLimit: Int = 8,
) {
    data class Prepared(
        val detail: Detail,
        val episodeIndex: Int,
        val episodeId: String,
        val position: Long,
        val remote: RemoteVideo,
        val cacheHit: Boolean,
    )

    private val details = object : LinkedHashMap<String, Detail>(detailLimit, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Detail>?): Boolean =
            size > detailLimit
    }

    private var failures = 0
    private var watchingId = ""
    private var watchingAt = 0L
    private var lastProgressAt = 0L
    private var lastProgressEpisode = ""
    private var lastStartedEpisode = ""

    fun cachedDetail(seriesId: String): Detail? = synchronized(details) { details[seriesId] }

    fun rememberDetail(detail: Detail) {
        if (detail.episodes.isEmpty()) return
        synchronized(details) { details[detail.series.id] = detail }
        runCatching { DetailCache.save(detail) }
    }

    /**
     * 准备推荐中的一部。
     *
     * [startAt] < 0 时按 [resumeSaved] 决定是否从本机进度续播；
     * [takeWarm] 用于接入页面已有的相邻预热槽，命中时不再取地址和读索引。
     */
    suspend fun prepare(
        series: Series,
        maxQuality: Int,
        startAt: Long = -1L,
        resumeSaved: Boolean = true,
        takeWarm: ((String, Int) -> RemoteVideo?)? = null,
    ): Prepared? {
        val cached = cachedDetail(series.id)
        val detail = cached ?: runCatching { DetailCache.load(series.id) }.getOrNull()
        val detailStarted=PlaybackStartupTrace.begin(
            "detail",
            "id=${series.id} cache=${detail!=null}",
        )
        val loaded = detail ?: runCatching {
            StageDeadline("recommend.detail",StartupBudgets.DETAIL_MS).use { deadline ->
                deadline.run {
                    repository.detail(series.id,includeAuthor=false)
                }
            }
        }.getOrNull()
        PlaybackStartupTrace.done(
            "detail",
            detailStarted,
            "id=${series.id} cache=${detail!=null} success=${loaded!=null}",
        )
        if (loaded == null) return null
        rememberDetail(loaded)
        if (loaded.episodes.isEmpty()) return null

        val saved = if (resumeSaved) runCatching { library.progress(series.id) }.getOrNull() else null
        val savedIndex = saved?.let { row ->
            loaded.episodes.indexOf(row.episodeId).takeIf { it >= 0 }
        } ?: 0
        val episodeIndex = savedIndex.coerceIn(0, loaded.episodes.lastIndex)
        val episodeId = loaded.episodes[episodeIndex]
        val position = when {
            startAt >= 0 -> startAt
            saved?.episodeId == episodeId -> saved.position
            else -> 0L
        }

        val warm = takeWarm?.invoke(episodeId, maxQuality)
        val remote = warm ?: runCatching {
            val streamStarted=PlaybackStartupTrace.begin("stream", "episode=$episodeId")
            val info=StageDeadline("recommend.stream",StartupBudgets.STREAM_MS).use { deadline ->
                deadline.run {
                    source.stream(episodeId, maxQuality, loaded.playback, direct=true)
                }
            }
            PlaybackStartupTrace.done("stream",streamStarted,"episode=$episodeId quality=${info.quality}")
            val prepareStarted=PlaybackStartupTrace.begin("prepare", "episode=$episodeId")
            StageDeadline("recommend.prepare",StartupBudgets.PREPARE_MS).use { deadline ->
                deadline.run {
                    source.prepare(episodeId, info)
                }
            }.also {
                PlaybackStartupTrace.done("prepare",prepareStarted,"episode=$episodeId")
            }
        }.getOrNull() ?: return null
        return Prepared(
            detail = loaded,
            episodeIndex = episodeIndex,
            episodeId = episodeId,
            position = position,
            remote = remote,
            cacheHit = warm != null,
        )
    }

    fun rememberShown(series: Series) {
        if(watchingId!=series.id) lastStartedEpisode=""
        store.markShown(series)
        watchingId = series.id
        watchingAt = android.os.SystemClock.elapsedRealtime()
    }

    fun settleWatch(playedMs: Long): RecommendBehavior.Outcome? {
        val id = watchingId
        val start = watchingAt
        watchingId = ""
        watchingAt = 0L
        if (id.isEmpty() || start == 0L) return null
        val dwell = android.os.SystemClock.elapsedRealtime() - start
        return store.settleWatch(id, dwell, playedMs)
    }

    fun reportImpression(series: Series) {
        store.reportImpression(series)
    }

    /** 同一集只报一次起播；READY 与首帧两个回调都会调用这里。 */
    fun reportPlayStart(
        series: Series,
        episodeId: String,
        episodeIndex: Int,
        positionMs: Long,
        durationMs: Long,
    ) {
        val key=episodeId.ifBlank { "$episodeIndex" }
        if(key==lastStartedEpisode) return
        lastStartedEpisode=key
        reportEvent(
            series,
            RecommendBehavior.Event.PLAY_START,
            episodeId=episodeId,
            episodeIndex=episodeIndex,
            positionMs=positionMs,
            durationMs=durationMs,
        )
    }

    fun reportEvent(
        series: Series,
        event: RecommendBehavior.Event,
        episodeId: String = "",
        episodeIndex: Int = -1,
        positionMs: Long = -1,
        durationMs: Long = -1,
        reason: String = "",
    ) {
        store.reportEvent(series,event,episodeId,episodeIndex,positionMs,durationMs,reason)
    }

    fun reportEventById(
        seriesId: String,
        event: RecommendBehavior.Event,
        episodeId: String = "",
        episodeIndex: Int = -1,
        positionMs: Long = -1,
        durationMs: Long = -1,
        reason: String = "",
    ): Boolean = store.reportEventById(
        seriesId,event,episodeId,episodeIndex,positionMs,durationMs,reason,
    )

    fun reportProgress(
        series: Series,
        episodeId: String,
        episodeIndex: Int,
        positionMs: Long,
        durationMs: Long,
    ) {
        if(durationMs<=0 || positionMs<0) return
        val now=android.os.SystemClock.elapsedRealtime()
        if(lastProgressEpisode==episodeId && now-lastProgressAt<15_000L) return
        lastProgressEpisode=episodeId
        lastProgressAt=now
        reportEvent(
            series,
            RecommendBehavior.Event.PLAY_PROGRESS,
            episodeId,
            episodeIndex,
            positionMs,
            durationMs,
        )
    }

    fun reportProgressById(
        seriesId: String,
        episodeId: String,
        episodeIndex: Int,
        positionMs: Long,
        durationMs: Long,
    ) {
        if(durationMs<=0 || positionMs<0) return
        val now=android.os.SystemClock.elapsedRealtime()
        if(lastProgressEpisode==episodeId && now-lastProgressAt<15_000L) return
        val series=store.items.firstOrNull { it.id==seriesId } ?: return
        lastProgressEpisode=episodeId
        lastProgressAt=now
        reportEvent(
            series,
            RecommendBehavior.Event.PLAY_PROGRESS,
            episodeId,
            episodeIndex,
            positionMs,
            durationMs,
        )
    }

    fun noteFailure(): Boolean {
        failures++
        return failures <= skipLimit
    }

    fun resetFailures() {
        failures = 0
    }

    fun markWatched(series: Series) {
        store.markWatched(series)
    }

    fun clear() {
        synchronized(details) { details.clear() }
        failures = 0
        watchingId = ""
        watchingAt = 0L
        lastProgressAt = 0L
        lastProgressEpisode = ""
        lastStartedEpisode = ""
    }
}
