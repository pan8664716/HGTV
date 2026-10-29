// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core.playback

/**
 * 驱动 [PlaybackState] 的事件。
 *
 * 事件只描述**发生了什么**，不描述**该做什么**。要做什么全部收在 [PlaybackMachine.reduce]
 * 里，因此「同一种情况在两条路径上给出不同结果」这类 bug 在结构上就不成立了——
 * 改造前这类 bug 是常态（`STATE_ENDED` 与 `onPlayerError` 各写一套收尾，
 * 于是需要 `failureHandledSession` + 1.5 秒窗口去补）。
 */
sealed interface PlaybackEvent {

    /** 打开一集（换集 / 重试 / 换清晰度 / 换解码都会产生一条）。 */
    data class Open(
        val sessionId: Long,
        val seriesId: String,
        val episodeId: String,
        val episodeIndex: Int,
        val resumeAtMs: Long = 0L,
        val autoplay: Boolean = true,
        val seamless: Boolean = false,
    ) : PlaybackEvent

    /**
     * 播放器自身状态变化（来自 `Player.Listener`）。
     *
     * [playbackState] 用 Media3 的常量（1=IDLE / 2=BUFFERING / 3=READY / 4=ENDED），
     * 但这里用 Int 而不是直接引用 `Player`：`core` 是纯 JVM 模块，不能依赖 Android。
     */
    data class PlayerState(
        val sessionId: Long,
        val playbackState: Int,
        val playWhenReady: Boolean,
        val bufferingReason: String = "",
    ) : PlaybackEvent

    /** 进度采样（每秒一次）。 */
    data class Progress(
        val sessionId: Long,
        val positionMs: Long,
        val durationMs: Long,
    ) : PlaybackEvent

    /** 片源尺寸确定（竖屏短剧全屏的判据之一）。 */
    data class VideoSize(
        val sessionId: Long,
        val width: Int,
        val height: Int,
    ) : PlaybackEvent

    /** 倍速变化。 */
    data class Speed(val sessionId: Long, val speed: Float) : PlaybackEvent

    /** 失败。 */
    data class Failed(val sessionId: Long, val failure: PlaybackFailure) : PlaybackEvent

    /** 重试（自动降级 / 用户手动重试）：回到 [PlaybackPhase.PREPARING]，尝试次数 +1。 */
    data class Retried(val sessionId: Long) : PlaybackEvent

    /** 释放播放器（退出播放页 / 换剧）。 */
    data class Released(val sessionId: Long) : PlaybackEvent

    /** 错误被清除但播放器还在（错误页上退到后台再回来，不算一次全新尝试）。 */
    data class Recovered(val sessionId: Long) : PlaybackEvent
}

/**
 * 播放状态机。
 *
 * 纯函数：`reduce(旧状态, 事件) = 新状态`。没有副作用、没有 Android 依赖、不持有播放器，
 * 因此可以用单元测试把「所有非法迁移」逐个钉死——这正是改造前做不到的部分：
 * 播放链路的每个状态转换都埋在 `MainActivity` 的 15,000 行里，一行覆盖都没有。
 *
 * ## 规则
 *
 * 1. **旧会话的事件一律丢弃**：`event.sessionId != state.sessionId` 时原样返回。
 *    这一条取代了改造前散在四处的「代际号比较」（`playbackSession` /
 *    `failureHandledSession` / `playbackWatchdogSession` / `danmakuToken`）。
 * 2. **`prepared` 只进不退**：同一会话内一旦 READY 过，后续的 BUFFERING / ENDED / FAILED
 *    都不再把它清回 false。它表达的是「进度可信」，不是「此刻能播」。
 * 3. **`FAILED` 只能由非 IDLE 状态进入**，且必须带 [PlaybackFailure]。
 * 4. **`ENDED → READY` 合法**：用户往回拖会重新进入 READY（ExoPlayer 的真实行为）。
 * 5. **进度钳制**：`durationMs > 0` 时 `positionMs` 夹在 `[0, durationMs]`。
 */
object PlaybackMachine {

    /** Media3 的 `Player.STATE_IDLE`。 */
    const val STATE_IDLE = 1
    /** Media3 的 `Player.STATE_BUFFERING`。 */
    const val STATE_BUFFERING = 2
    /** Media3 的 `Player.STATE_READY`。 */
    const val STATE_READY = 3
    /** Media3 的 `Player.STATE_ENDED`。 */
    const val STATE_ENDED = 4

    fun reduce(state: PlaybackState, event: PlaybackEvent): PlaybackState {
        // 规则 1：旧会话的事件一律丢弃。
        //
        // `Open` 是**唯一例外**：它本身就是「建立新会话」这个动作，此时
        // `event.sessionId` 必然不等于旧值——按规则 1 拦掉就等于永远打不开下一集。
        if (event !is PlaybackEvent.Open && event.sessionId() != state.sessionId) return state

        return when (event) {
            is PlaybackEvent.Open -> state.copy(
                sessionId = event.sessionId,
                phase = PlaybackPhase.PREPARING,
                seriesId = event.seriesId,
                episodeId = event.episodeId,
                episodeIndex = event.episodeIndex,
                prepared = false,
                playWhenReady = event.autoplay,
                positionMs = event.resumeAtMs.coerceAtLeast(0L),
                durationMs = 0L,
                bufferingReason = "",
                seamless = event.seamless,
                videoWidth = 0,
                videoHeight = 0,
                attempt = 0,
                failure = null,
            )

            is PlaybackEvent.PlayerState -> state.copy(
                phase = phaseOf(state, event),
                prepared = state.prepared || event.playbackState == STATE_READY,
                playWhenReady = event.playWhenReady,
                bufferingReason = if (event.playbackState == STATE_BUFFERING) event.bufferingReason else "",
                // 一旦重新进入 READY / BUFFERING，上一次的失败就不再是当前状态。
                failure = if (event.playbackState == STATE_READY || event.playbackState == STATE_BUFFERING) null else state.failure,
            )

            is PlaybackEvent.Progress -> state.copy(
                positionMs = clampPosition(event.positionMs, event.durationMs),
                durationMs = event.durationMs.coerceAtLeast(0L),
            )

            is PlaybackEvent.VideoSize -> state.copy(
                videoWidth = event.width.coerceAtLeast(0),
                videoHeight = event.height.coerceAtLeast(0),
            )

            is PlaybackEvent.Speed -> state.copy(speed = event.speed.coerceAtLeast(0.1f))

            // 规则 3：IDLE 上不会有失败。
            is PlaybackEvent.Failed ->
                if (state.phase == PlaybackPhase.IDLE) state
                else state.copy(phase = PlaybackPhase.FAILED, failure = event.failure)

            is PlaybackEvent.Retried -> state.copy(
                phase = PlaybackPhase.PREPARING,
                prepared = false,
                failure = null,
                attempt = state.attempt + 1,
                bufferingReason = "",
            )

            is PlaybackEvent.Recovered -> state.copy(
                phase = if (state.prepared) PlaybackPhase.BUFFERING else PlaybackPhase.PREPARING,
                failure = null,
            )

            is PlaybackEvent.Released -> PlaybackState(sessionId = state.sessionId)
        }
    }

    /**
     * 播放器状态 → 阶段。
     *
     * `IDLE` 要分两种情况：已经 prepared 过的（seek 后短暂回到 IDLE）保持原阶段，
     * 没 prepared 过的就是还在准备。直接把 IDLE 映射成 `PREPARING` 会让「缓冲中途
     * 短暂 IDLE」把进度条打回起点，用户看到的是「画面没变、进度条跳了」。
     */
    private fun phaseOf(state: PlaybackState, event: PlaybackEvent.PlayerState): PlaybackPhase =
        when (event.playbackState) {
            STATE_BUFFERING -> PlaybackPhase.BUFFERING
            STATE_READY -> PlaybackPhase.READY
            STATE_ENDED -> PlaybackPhase.ENDED
            STATE_IDLE -> if (state.prepared) state.phase else PlaybackPhase.PREPARING
            else -> state.phase
        }

    private fun clampPosition(position: Long, duration: Long): Long {
        val nonNegative = position.coerceAtLeast(0L)
        return if (duration > 0L) nonNegative.coerceAtMost(duration) else nonNegative
    }

    private fun PlaybackEvent.sessionId(): Long = when (this) {
        is PlaybackEvent.Open -> sessionId
        is PlaybackEvent.PlayerState -> sessionId
        is PlaybackEvent.Progress -> sessionId
        is PlaybackEvent.VideoSize -> sessionId
        is PlaybackEvent.Speed -> sessionId
        is PlaybackEvent.Failed -> sessionId
        is PlaybackEvent.Retried -> sessionId
        is PlaybackEvent.Released -> sessionId
        is PlaybackEvent.Recovered -> sessionId
    }
}
