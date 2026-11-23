// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.architecture

import com.hgtv.star.core.FailureAction
import com.hgtv.star.core.FailureAttribution
import com.hgtv.star.core.FailureFacts
import com.hgtv.star.core.PlaybackAttemptLedger
import com.hgtv.star.core.PlaybackFailurePolicy
import com.hgtv.star.core.PlaybackRetryPlan
import com.hgtv.star.core.RecoveryDecision
import com.hgtv.star.core.RecoveryRequest
import com.hgtv.star.core.SessionPlaybackPolicy
import com.hgtv.star.core.UnifiedPlaybackRecovery
import com.hgtv.star.core.VideoDecoderMode
import com.hgtv.star.core.playback.PlaybackFailure

/**
 * 播放行为的共享门面。
 *
 * 它把三件本来会散落在页面里的状态收在一起：
 * * [PlaybackController]：当前会话与阶段；
 * * [PlaybackAttemptLedger]：按集计的重试预算；
 * * [SessionPlaybackPolicy]：本机降档 / 重建 / 手动处置结论。
 *
 * TV/Phone 仍负责创建播放器、取地址和绑 Surface；这些平台动作通过页面执行。
 * 会话推进、预算重置、失败决策和手动重试语义由这里统一。
 */
class PlaybackSession(
    val controller: PlaybackController = PlaybackController(),
    val ledger: PlaybackAttemptLedger = PlaybackAttemptLedger(),
    val policy: SessionPlaybackPolicy = SessionPlaybackPolicy(),
    val recovery: UnifiedPlaybackRecovery = UnifiedPlaybackRecovery(),
) {
    private var nextAttempt = 0L
    private var handledAttempt = -1L
    val progress=PlaybackProgressTracker()

    val sessionId: Long get() = controller.sessionId
    /** 当前起播尝试号；同一尝试只处置一次失败，重建 / 重试后进入下一次。 */
    val attemptId: Long get() = nextAttempt
    val effectiveMaxQuality: Int? get() = policy.effectiveMaxQuality
    val effectiveDecoderMode: VideoDecoderMode? get() = policy.effectiveDecoderMode
    val manualHint: String? get() = policy.manualHint
    val lastReason: com.hgtv.star.core.DegradationKind? get() = policy.lastReason

    val autoRetryAttempts: Int get() = ledger.attempts
    val autoRetryAvailable: Boolean get() = ledger.canAttempt
    val autoRetryDelayMs: Long? get() = ledger.delayMillis

    val pictureRecoveryCount: Int get() = ledger.formalPictureRecoveries

    fun beginSession(): Long = controller.beginSession()

    /**
     * 打开一集。
     *
     * [resetEpisode] 为 false 用于「同一集同一次起播的重来」（降档、同档重建、
     * 手动重试、地址自愈）；这时不能清空按集计的预算，否则重试上限失效。
     */
    fun open(
        seriesId: String,
        episodeId: String,
        episodeIndex: Int,
        resumeAtMs: Long = 0L,
        autoplay: Boolean = true,
        seamless: Boolean = false,
        resetEpisode: Boolean = true,
    ): Long {
        if(resetEpisode) onEpisodeStart(episodeId)
        nextAttempt++
        return controller.open(
            seriesId = seriesId,
            episodeId = episodeId,
            episodeIndex = episodeIndex,
            resumeAtMs = resumeAtMs,
            autoplay = autoplay,
            seamless = seamless,
        )
    }

    /** 新一集：播放状态、按集预算和降级预算一起归零。 */
    fun onEpisodeStart(episodeId: String) {
        policy.onEpisodeStart(episodeId)
        ledger.onEpisodeStart()
        recovery.onEpisodeStart()
    }

    /**
     * 切到另一集重来，但连播链还没接上。
     *
     * 与 [onEpisodeStart] 的差异是保留 `autoNextFailures`，防止片尾失败后无限自动推进。
     */
    fun onEpisodeSwitch(episodeId: String) {
        policy.onEpisodeStart(episodeId)
        ledger.onEpisodeSwitch()
        recovery.onEpisodeStart()
    }

    /**
     * 用户手动重试。
     *
     * 返回 false = 本集的手动归还额度已经用尽；按钮仍可用，但这次不再额外归还预算。
     */
    fun onManualRetry(): Boolean {
        if(!policy.onManualRetry()) return false
        ledger.onManualRetry()
        recovery.onManualRetry()
        return true
    }

    fun onManualQualityChange() = policy.onManualQualityChange()

    /**
     * 用户切换解码方式；[maxQuality] 非空时同时把本次会话的清晰度上限落定。
     *
     * 两端原先都是先调策略、再直接写 `setMaxQuality`，拆成两步很容易漏掉后一步。
     */
    fun onManualDecoderChange(maxQuality: Int? = null) {
        policy.onManualDecoderChange()
        maxQuality?.takeIf { it>0 }?.let(policy::setMaxQuality)
    }

    fun onUserManualChange() = policy.onUserManualChange()

    fun onLeavePlaybackToBackground() = policy.onLeavePlaybackToBackground()

    fun touched(): Boolean = policy.touched()

    fun budgetSummary(): String = policy.budgetSummary()

    /** 把一次自动降级计划完整落到会话：先写新的清晰度上限，再记降档起点。 */
    fun applyRetryPlan(plan: PlaybackRetryPlan) {
        plan.maxQuality?.let(policy::setMaxQuality)
        plan.fromQuality?.let(policy::noteQualityDown)
    }

    fun setMaxQuality(value: Int) = policy.setMaxQuality(value)

    fun setEffectiveDecoderMode(value: VideoDecoderMode) =
        policy.setEffectiveDecoderMode(value)

    fun decideRecovery(request: RecoveryRequest): RecoveryDecision =
        recovery.decide(request)

    fun resetRecoveryForManualRetry() = recovery.onManualRetry()

    fun markStablePlayback() = recovery.markStablePlayback()

    fun consumeAutoRetry(): Boolean = ledger.consumeAttempt()

    fun queuedRecoveryCount(key: String): Int = ledger.queuedRecoveries(key)

    fun noteQueuedRecovery(key: String) {
        ledger.noteQueuedRecovery(key,queuedRecoveryCount(key)+1)
    }

    fun noteAutoNextFailure() = ledger.noteAutoNextFailure()

    fun pictureRecoveryCoolingDown(now: Long,cooldownMs: Long): Boolean =
        ledger.pictureCoolingDown(now,cooldownMs)

    fun notePictureRecovery(now: Long) = ledger.notePictureRecovery(now)

    fun onPlayerState(state: Int, playWhenReady: Boolean, bufferingReason: String = "") =
        controller.onPlayerState(state, playWhenReady, bufferingReason)

    fun onProgress(positionMs: Long, durationMs: Long) =
        controller.onProgress(positionMs, durationMs)

    fun onVideoSize(width: Int, height: Int) =
        controller.onVideoSize(width, height)

    fun onSpeed(speed: Float) = controller.onSpeed(speed)

    fun fail(failure: PlaybackFailure) = controller.fail(failure)

    /**
     * 同一集的同一次起播重来。
     *
     * 播放状态机的会话号不在这里递增（它标识的是“哪一集 / 哪一路播放器绑定”），
     * 但失败去重需要单独前进：重建后再失败必须能再处置一次。
     */
    fun retry(): Long {
        nextAttempt++
        controller.retry()
        return nextAttempt
    }

    /**
     * 领取当前尝试的失败处置权。
     *
     * 返回 null = 这一代已经处置过，调用方直接忽略重复回调。
     */
    fun claimFailure(): Long? {
        val current=nextAttempt
        if(handledAttempt==current) return null
        handledAttempt=current
        return current
    }

    fun recover() = controller.recover()

    fun release() = controller.release()

    fun decideFailure(
        facts: FailureFacts,
        attr: FailureAttribution,
        recommendFailures: Int = 0,
    ): FailureAction = PlaybackFailurePolicy.decide(
        facts = facts,
        attr = attr,
        ledger = ledger,
        session = policy,
        recommendFailures = recommendFailures,
    )
}
