// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 播放恢复的操作层。
 *
 * 每层独立计数，互不占用额度。层与层之间可以嵌套：例如播放器网络错误会触发
 * 一次新的取地址操作，但那次取地址失败只消耗 [ADDRESS] 的额度，不会把 [PLAYER]
 * 的剩余次数一起吃掉。
 */
enum class RecoveryLayer {
    ADDRESS,
    PREPARE,
    PLAYER,
    TRANSPORT,
    PICTURE,
    QUEUE,
}

/** 错误发生在哪个播放界面。恢复动作相同，展示位置由平台决定。 */
enum class RecoverySurface {
    FORMAL,
    RECOMMEND,
}

/** 统一错误分类只由 canonical code 与已经归一化的事实决定。 */
enum class RecoveryErrorKind {
    NETWORK,
    SOURCE,
    PREPARE,
    PARSE,
    DECODER_INIT,
    DECODER_QUERY,
    DECODING,
    FORMAT_EXCEEDS,
    FORMAT_UNSUPPORTED,
    AUDIO,
    DRM,
    PICTURE,
    BUFFER,
    NON_RETRYABLE,
    UNKNOWN,
}

/**
 * 平台层只负责把现场读成这份事实，不再决定重试几次、先做什么。
 */
data class RecoveryRequest(
    val layer: RecoveryLayer,
    val surface: RecoverySurface = RecoverySurface.FORMAL,
    val errorCode: Int = 0,
    val stage: String = "",
    val media: MediaKind = MediaKind.VIDEO,
    val retryable: Boolean = false,
    val streamExpired: Boolean = false,
    val videoChanged: Boolean = false,
    val hostUnresolved: Boolean = false,
    val transportDead: Boolean = false,
    val currentQuality: Int = 0,
    val available: List<Int> = emptyList(),
    val currentMode: VideoDecoderMode = VideoDecoderMode.HARDWARE,
    val networkAvailable: Boolean = true,
    val queued: Boolean = false,
    val queuedNearEnd: Boolean = false,
)

sealed interface RecoveryAction {
    data object RefetchAddress : RecoveryAction
    data object RetryPrepare : RecoveryAction
    data object RebuildPlayer : RecoveryAction
    data class SwitchSoftware(val maxQuality: Int?) : RecoveryAction
    data class LowerQuality(val targetQuality: Int) : RecoveryAction
    data object SwitchHostAndRefetch : RecoveryAction
    data object RecoverQueuedCurrent : RecoveryAction
    data object WaitForNetwork : RecoveryAction
    data object ShowPassiveHint : RecoveryAction
}

data class RecoveryDecision(
    val layer: RecoveryLayer,
    val kind: RecoveryErrorKind,
    val action: RecoveryAction,
    val attempt: Int,
    val maxAttempts: Int,
)

/**
 * 每次播放事件共用一份分层额度。
 *
 * 只有真正准备执行一次恢复动作时才 [consume]；等待网络、显示提示、用户退出都不扣次数。
 * 稳定播放、换集或用户主动重试时整体清零。
 */
class UnifiedRecoveryBudget(
    private val maxAttempts: Int = AppConfig.Playback.RECOVERY_ATTEMPTS,
) {
    private val used = HashMap<RecoveryLayer, Int>()

    val limit: Int get() = maxAttempts

    fun attempts(layer: RecoveryLayer): Int = used[layer] ?: 0
    fun remaining(layer: RecoveryLayer): Int = (maxAttempts - attempts(layer)).coerceAtLeast(0)
    fun canAttempt(layer: RecoveryLayer): Boolean = attempts(layer) < maxAttempts

    fun consume(layer: RecoveryLayer): Int {
        val next = (attempts(layer) + 1).coerceAtMost(maxAttempts)
        used[layer] = next
        return next
    }

    fun reset(layer: RecoveryLayer) {
        used.remove(layer)
    }

    fun resetAll() {
        used.clear()
    }
}

/**
 * 两端唯一的播放恢复决策入口。
 *
 * 关键约束：
 * * 每层最多 3 次，中间没有固定等待；
 * * 不更换设备号；
 * * 永不自动切换下一集或下一部；
 * * 自动软解、自动降档只写当前播放会话；
 * * 不可恢复错误直接进入非阻塞提示。
 */
object UnifiedPlaybackRecoveryPolicy {

    fun decide(
        request: RecoveryRequest,
        budget: UnifiedRecoveryBudget,
    ): RecoveryDecision {
        val kind = classify(request)
        if (kind == RecoveryErrorKind.NON_RETRYABLE) {
            return terminal(request, kind, budget)
        }
        if (!request.networkAvailable && kind in setOf(RecoveryErrorKind.NETWORK, RecoveryErrorKind.SOURCE)) {
            return decision(request, kind, RecoveryAction.WaitForNetwork, budget.attempts(request.layer))
        }
        return when (request.layer) {
            RecoveryLayer.ADDRESS -> addressDecision(request, kind, budget)
            RecoveryLayer.PREPARE -> prepareDecision(request, kind, budget)
            RecoveryLayer.PLAYER -> playerDecision(request, kind, budget)
            RecoveryLayer.PICTURE -> pictureDecision(request, kind, budget)
            RecoveryLayer.QUEUE -> queueDecision(request, kind, budget)
            RecoveryLayer.TRANSPORT -> addressDecision(request, kind, budget)
        }
    }

    fun classify(request: RecoveryRequest): RecoveryErrorKind {
        val code = request.errorCode
        return when {
            request.layer == RecoveryLayer.PICTURE -> RecoveryErrorKind.PICTURE
            request.stage == PlaybackStage.STALL_FRAME -> RecoveryErrorKind.PICTURE
            request.stage == PlaybackStage.STALL_BUFFER -> RecoveryErrorKind.BUFFER
            code == PlaybackErrorCode.DECODER_INIT_FAILED -> RecoveryErrorKind.DECODER_INIT
            code == PlaybackErrorCode.DECODER_QUERY_FAILED -> RecoveryErrorKind.DECODER_QUERY
            code == PlaybackErrorCode.DECODING_FAILED -> RecoveryErrorKind.DECODING
            code == PlaybackErrorCode.FORMAT_EXCEEDS_CAPABILITIES -> RecoveryErrorKind.FORMAT_EXCEEDS
            code == PlaybackErrorCode.FORMAT_UNSUPPORTED -> RecoveryErrorKind.FORMAT_UNSUPPORTED
            code in 4000..4999 -> RecoveryErrorKind.DECODING
            code in 5000..5999 -> RecoveryErrorKind.AUDIO
            code in 6000..6999 -> RecoveryErrorKind.DRM
            code in 3000..3999 -> RecoveryErrorKind.PARSE
            request.streamExpired || request.videoChanged ||
                request.hostUnresolved || request.transportDead -> RecoveryErrorKind.SOURCE
            request.stage == PlaybackStage.PREPARE -> RecoveryErrorKind.PREPARE
            request.stage == PlaybackStage.IO || request.stage == PlaybackStage.TIMEOUT -> RecoveryErrorKind.NETWORK
            code in 2000..2999 || code == 1003 -> RecoveryErrorKind.NETWORK
            request.retryable -> RecoveryErrorKind.NETWORK
            else -> RecoveryErrorKind.NON_RETRYABLE
        }
    }

    private fun addressDecision(
        request: RecoveryRequest,
        kind: RecoveryErrorKind,
        budget: UnifiedRecoveryBudget,
    ): RecoveryDecision {
        if (budget.canAttempt(RecoveryLayer.ADDRESS)) {
            val attempt = budget.consume(RecoveryLayer.ADDRESS)
            return decision(request, kind, RecoveryAction.RefetchAddress, attempt)
        }
        if (kind in setOf(RecoveryErrorKind.NETWORK, RecoveryErrorKind.SOURCE) &&
            budget.canAttempt(RecoveryLayer.TRANSPORT)
        ) {
            val attempt = budget.consume(RecoveryLayer.TRANSPORT)
            budget.reset(RecoveryLayer.ADDRESS)
            return decision(
                request,
                kind,
                RecoveryAction.SwitchHostAndRefetch,
                attempt,
                layer = RecoveryLayer.TRANSPORT,
            )
        }
        return terminal(request, kind, budget)
    }

    private fun prepareDecision(
        request: RecoveryRequest,
        kind: RecoveryErrorKind,
        budget: UnifiedRecoveryBudget,
    ): RecoveryDecision {
        if (budget.canAttempt(RecoveryLayer.PREPARE)) {
            val attempt = budget.consume(RecoveryLayer.PREPARE)
            return decision(request, kind, RecoveryAction.RetryPrepare, attempt)
        }
        return terminal(request, kind, budget)
    }

    private fun playerDecision(
        request: RecoveryRequest,
        kind: RecoveryErrorKind,
        budget: UnifiedRecoveryBudget,
    ): RecoveryDecision {
        if (!budget.canAttempt(RecoveryLayer.PLAYER)) return terminal(request, kind, budget)
        val attempt = budget.consume(RecoveryLayer.PLAYER)
        val lower = PlaybackDegradation.lowerThan(request.available, request.currentQuality)
        val softwareQuality = PlaybackDegradation.softwareQuality(
            request.available,
            lower ?: request.currentQuality,
        )
        val action = when (kind) {
            RecoveryErrorKind.NETWORK,
            RecoveryErrorKind.SOURCE,
            RecoveryErrorKind.PREPARE -> RecoveryAction.RefetchAddress

            RecoveryErrorKind.PARSE -> when (attempt) {
                1 -> RecoveryAction.RefetchAddress
                2 -> if (lower != null) RecoveryAction.LowerQuality(lower) else RecoveryAction.RefetchAddress
                else -> RecoveryAction.SwitchHostAndRefetch
            }

            RecoveryErrorKind.DECODER_INIT -> when (attempt) {
                1 -> RecoveryAction.RebuildPlayer
                2 -> softwareAction(request, softwareQuality)
                else -> lower?.let(RecoveryAction::LowerQuality) ?: softwareAction(request, softwareQuality)
            }

            RecoveryErrorKind.DECODER_QUERY -> when (attempt) {
                1 -> softwareAction(request, softwareQuality)
                2 -> lower?.let(RecoveryAction::LowerQuality) ?: RecoveryAction.RebuildPlayer
                else -> RecoveryAction.RefetchAddress
            }

            RecoveryErrorKind.DECODING -> when (attempt) {
                1 -> RecoveryAction.RebuildPlayer
                2 -> softwareAction(request, softwareQuality)
                else -> lower?.let(RecoveryAction::LowerQuality) ?: RecoveryAction.RefetchAddress
            }

            RecoveryErrorKind.FORMAT_EXCEEDS,
            RecoveryErrorKind.FORMAT_UNSUPPORTED -> {
                lower?.let(RecoveryAction::LowerQuality)
                    ?: softwareAction(request, softwareQuality)
            }

            RecoveryErrorKind.AUDIO -> RecoveryAction.RebuildPlayer
            RecoveryErrorKind.DRM -> when (attempt) {
                1 -> RecoveryAction.RebuildPlayer
                2 -> RecoveryAction.RefetchAddress
                else -> RecoveryAction.RebuildPlayer
            }

            RecoveryErrorKind.PICTURE,
            RecoveryErrorKind.BUFFER -> when (attempt) {
                1, 2 -> RecoveryAction.RebuildPlayer
                else -> RecoveryAction.RefetchAddress
            }

            RecoveryErrorKind.NON_RETRYABLE,
            RecoveryErrorKind.UNKNOWN -> RecoveryAction.ShowPassiveHint
        }
        return decision(request, kind, action, attempt)
    }

    private fun pictureDecision(
        request: RecoveryRequest,
        kind: RecoveryErrorKind,
        budget: UnifiedRecoveryBudget,
    ): RecoveryDecision {
        if (!budget.canAttempt(RecoveryLayer.PICTURE)) return terminal(request, kind, budget)
        val attempt = budget.consume(RecoveryLayer.PICTURE)
        val action = if (attempt < budget.limit) RecoveryAction.RebuildPlayer else RecoveryAction.RefetchAddress
        return decision(request, kind, action, attempt)
    }

    private fun queueDecision(
        request: RecoveryRequest,
        kind: RecoveryErrorKind,
        budget: UnifiedRecoveryBudget,
    ): RecoveryDecision {
        if (!budget.canAttempt(RecoveryLayer.QUEUE)) return terminal(request, kind, budget)
        val attempt = budget.consume(RecoveryLayer.QUEUE)
        return decision(request, kind, RecoveryAction.RecoverQueuedCurrent, attempt)
    }

    private fun softwareAction(request: RecoveryRequest, maxQuality: Int): RecoveryAction =
        if (request.currentMode == VideoDecoderMode.HARDWARE) {
            RecoveryAction.SwitchSoftware(maxQuality.takeIf { it > 0 })
        } else {
            RecoveryAction.RebuildPlayer
        }

    private fun decision(
        request: RecoveryRequest,
        kind: RecoveryErrorKind,
        action: RecoveryAction,
        attempt: Int,
        layer: RecoveryLayer = request.layer,
    ) = RecoveryDecision(
        layer = layer,
        kind = kind,
        action = action,
        attempt = attempt,
        maxAttempts = AppConfig.Playback.RECOVERY_ATTEMPTS,
    )

    private fun terminal(
        request: RecoveryRequest,
        kind: RecoveryErrorKind,
        budget: UnifiedRecoveryBudget,
    ) = RecoveryDecision(
        layer = request.layer,
        kind = kind,
        action = RecoveryAction.ShowPassiveHint,
        attempt = budget.attempts(request.layer),
        maxAttempts = budget.limit,
    )
}

/**
 * 一次播放事件的分层额度与稳定播放重置。
 */
class UnifiedPlaybackRecovery {
    val budget = UnifiedRecoveryBudget()

    fun decide(request: RecoveryRequest): RecoveryDecision =
        UnifiedPlaybackRecoveryPolicy.decide(request, budget)

    fun markStablePlayback() = budget.resetAll()
    fun onEpisodeStart() = budget.resetAll()
    fun onManualRetry() = budget.resetAll()
}
