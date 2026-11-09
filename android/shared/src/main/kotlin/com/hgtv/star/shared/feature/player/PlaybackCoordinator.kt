// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.feature.player

import com.hgtv.star.core.FailureAction
import com.hgtv.star.core.FailureAttribution
import com.hgtv.star.core.FailureFacts
import com.hgtv.star.core.Series
import com.hgtv.star.shared.architecture.PlaybackSession
import com.hgtv.star.shared.architecture.PlaybackSourceCoordinator
import com.hgtv.star.shared.data.Library
import com.hgtv.star.shared.feature.account.AccountController

/**
 * 平台播放器需要暴露给共享编排层的最小能力。
 *
 * 页面实现这个接口后，共享层只通过它读位置 / 状态和执行最基本的播放动作，
 * 不直接依赖 ExoPlayer 或 PlayerView。
 */
interface PlayerHost {
    fun currentPositionMs(): Long
    fun durationMs(): Long
    fun playbackState(): Int
    fun playWhenReady(): Boolean
    fun isPlaying(): Boolean
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun rebuildSameQuality(positionMs: Long)
}

/**
 * 播放行为的共享门面。
 *
 * 统一会话、取源、进度快照、落盘节拍、账号同步和失败决策入口。
 * 具体 UI、Surface、播放器创建、重试动画仍由 [PlayerHost] 所在平台负责。
 */
class PlaybackCoordinator(
    val session: PlaybackSession,
    val source: PlaybackSourceCoordinator,
    private val host: PlayerHost,
    private val library: Library,
    private val account: AccountController? = null,
) {
    val sessionId: Long get() = session.sessionId
    val playbackState: Int get() = host.playbackState()
    val playWhenReady: Boolean get() = host.playWhenReady()
    val isPlaying: Boolean get() = host.isPlaying()
    val currentPositionMs: Long get() = host.currentPositionMs()
    val durationMs: Long get() = host.durationMs()
    val effectiveMaxQuality: Int? get() = session.effectiveMaxQuality
    val autoRetryAttempts: Int get() = session.autoRetryAttempts
    val autoRetryAvailable: Boolean get() = session.autoRetryAvailable
    val autoRetryDelayMs: Long? get() = session.autoRetryDelayMs
    val pictureRecoveryCount: Int get() = session.pictureRecoveryCount
    val manualHint: String? get() = session.manualHint
    val lastReason: com.hgtv.star.core.DegradationKind? get() = session.lastReason

    fun open(
        seriesId: String,
        episodeId: String,
        episodeIndex: Int,
        resumeAtMs: Long = 0L,
        autoplay: Boolean = true,
        seamless: Boolean = false,
        resetEpisode: Boolean = true,
    ): Long = session.open(
        seriesId = seriesId,
        episodeId = episodeId,
        episodeIndex = episodeIndex,
        resumeAtMs = resumeAtMs,
        autoplay = autoplay,
        seamless = seamless,
        resetEpisode = resetEpisode,
    )

    fun onPlayerState(state: Int, playWhenReady: Boolean, bufferingReason: String = "") =
        session.onPlayerState(state, playWhenReady, bufferingReason)

    fun onProgress(positionMs: Long, durationMs: Long) =
        session.onProgress(positionMs, durationMs)

    fun onVideoSize(width: Int, height: Int) = session.onVideoSize(width, height)

    fun onSpeed(speed: Float) = session.onSpeed(speed)

    fun fail(failure: com.hgtv.star.core.playback.PlaybackFailure) = session.fail(failure)

    fun claimFailure(): Long? = session.claimFailure()

    fun decideFailure(
        facts: FailureFacts,
        attr: FailureAttribution,
        recommendFailures: Int = 0,
    ): FailureAction = session.decideFailure(facts, attr, recommendFailures)

    fun onManualRetry(): Boolean = session.onManualRetry()

    fun onManualQualityChange() = session.onManualQualityChange()

    fun onManualDecoderChange(maxQuality: Int? = null) =
        session.onManualDecoderChange(maxQuality)

    fun onEpisodeSwitch(episodeId: String) = session.onEpisodeSwitch(episodeId)
    fun onEpisodeStart(episodeId: String) = session.onEpisodeStart(episodeId)
    fun setMaxQuality(value: Int) = session.setMaxQuality(value)

    fun applyRetryPlan(plan: com.hgtv.star.core.PlaybackRetryPlan) =
        session.applyRetryPlan(plan)

    fun consumeAutoRetry(): Boolean = session.consumeAutoRetry()

    fun queuedRecoveryCount(key: String): Int = session.queuedRecoveryCount(key)

    fun noteQueuedRecovery(key: String) = session.noteQueuedRecovery(key)

    fun noteAutoNextFailure() = session.noteAutoNextFailure()

    fun pictureRecoveryCoolingDown(now: Long, cooldownMs: Long): Boolean =
        session.pictureRecoveryCoolingDown(now, cooldownMs)

    fun notePictureRecovery(now: Long) = session.notePictureRecovery(now)

    fun touched(): Boolean = session.touched()

    fun budgetSummary(): String = session.budgetSummary()

    fun onUserManualChange() = session.onUserManualChange()

    fun onLeavePlaybackToBackground() = session.onLeavePlaybackToBackground()

    fun retry(): Long = session.retry()

    fun recover() = session.recover()

    fun release() = session.release()

    /** 每 15 秒一次；页面只需在 tick 里问一次。 */
    fun progressDue(): Boolean = session.progress.due()

    fun noteProgressSaved() = session.progress.noteSaved()

    fun resetProgressClock() = session.progress.reset()

    /**
     * 生成并保存当前进度。位置 / 完播判定和账号同步都只走这一处。
     */
    fun saveProgress(
        series: Series,
        episodeId: String,
        episodeIndex: Int,
        positionMs: Long,
        durationMs: Long,
        completed: Boolean,
        episodeCount: Int = series.episodeCount,
    ) {
        if (durationMs <= 0) return
        val progress = session.progress.snapshot(
            series = series,
            episodeId = episodeId,
            episodeIndex = episodeIndex,
            position = positionMs,
            duration = durationMs,
            completed = completed,
        )
        runCatching { library.save(progress) }
        account?.progress(progress, episodeCount)
    }

    fun rebuildSameQuality(positionMs: Long = currentPositionMs) =
        host.rebuildSameQuality(positionMs)

    fun pause() = host.pause()
    fun play() = host.play()
    fun seekTo(positionMs: Long) = host.seekTo(positionMs)
}
