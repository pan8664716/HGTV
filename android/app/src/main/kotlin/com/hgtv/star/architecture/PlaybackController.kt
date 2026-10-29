// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.architecture

import com.hgtv.star.core.playback.PlaybackEvent
import com.hgtv.star.core.playback.PlaybackFailure
import com.hgtv.star.core.playback.PlaybackMachine
import com.hgtv.star.core.playback.PlaybackPhase
import com.hgtv.star.core.playback.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 播放会话的唯一事实来源。
 *
 * ## 它替代了什么
 *
 * 改造前，「现在是第几次播放、播到哪一步」这件事由 `MainActivity` 上的七个计数器分担：
 *
 * | 计数器 | 原本要挡的问题 |
 * | --- | --- |
 * | `playbackSession` | 异步回调落到已失效的剧集 |
 * | `generation` | 异步回调落到已失效的页面 |
 * | `failureHandledSession` | `STATE_ENDED` 与 `onPlayerError` 重复处理 |
 * | `playbackWatchdogSession` | 看门狗落到已失效的会话 |
 * | `danmakuToken` | 弹幕请求串台 |
 * | `accountToken` | 账号请求串台 |
 * | `recommendToken` | 推荐流请求串台 |
 *
 * 七套各自定义、各自比较、各自重置的「代际号」，是这个类存在的直接理由。
 * 现在播放链路的会话号只有 [sessionId] 一个，由 [beginSession] 递增，
 * 任何异步回调都带着它回来，[PlaybackMachine] 在入口处丢弃旧会话的事件。
 *
 * ## 线程模型
 *
 * 状态用 `MutableStateFlow` 承载，[dispatch] 用 `update`（CAS）而不是 `value =`：
 * 播放回调来自主线程，进度采样来自 tick，网络回调可能来自 `network` 车道——
 * 直接赋值会让两个线程的更新互相覆盖。`update` 保证每次迁移都基于最新快照。
 *
 * 读取端（界面）用 `state.value` 同步读，或 `state.collect { }` 订阅。
 * 不在这里做任何 UI 操作：这个类不认识 Android 的 `View`。
 */
class PlaybackController {

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var nextSession = 0L

    /** 当前会话号。异步回调必须带着它回来。 */
    val sessionId: Long get() = _state.value.sessionId

    val phase: PlaybackPhase get() = _state.value.phase

    /** 是否至少 READY 过一次（= 改造前的 `playbackReady`）。 */
    val prepared: Boolean get() = _state.value.prepared

    /** 是否停在错误页上（= 改造前的 `playError`）。 */
    val failed: Boolean get() = _state.value.phase == PlaybackPhase.FAILED

    /**
     * 开一个新会话并返回它的号。
     *
     * **只在这里递增**。改造前 `playbackSession` 有两处 `++`（`playEpisode` 与
     * `releasePlayer`），分散在两万行里，漏一处就是「上一集的回调打到这一集上」。
     *
     * 关键点：这里必须**同时**把状态里的 `sessionId` 推上去，否则 `playbackSession`
     * 这个投影读到的还是旧值——「作废旧回调」这件事就失效了（旧监听器的
     * `session == playbackSession` 仍然成立，回调会继续作用在新一集上）。
     * 阶段与 `prepared` 保持不动：这一下只作废回调，不代表播放器状态变了。
     */
    fun beginSession(): Long {
        nextSession += 1
        val id = nextSession
        _state.update { it.copy(sessionId = id) }
        return id
    }

    /** 打开一集。 */
    fun open(
        seriesId: String,
        episodeId: String,
        episodeIndex: Int,
        resumeAtMs: Long = 0L,
        autoplay: Boolean = true,
        seamless: Boolean = false,
    ): Long {
        val id = beginSession()
        dispatch(
            PlaybackEvent.Open(
                sessionId = id,
                seriesId = seriesId,
                episodeId = episodeId,
                episodeIndex = episodeIndex,
                resumeAtMs = resumeAtMs,
                autoplay = autoplay,
                seamless = seamless,
            )
        )
        return id
    }

    /** 播放器状态变化。`playbackState` 用 Media3 的常量。 */
    fun onPlayerState(playbackState: Int, playWhenReady: Boolean, bufferingReason: String = "") =
        dispatch(PlaybackEvent.PlayerState(sessionId, playbackState, playWhenReady, bufferingReason))

    /** 进度采样。 */
    fun onProgress(positionMs: Long, durationMs: Long) =
        dispatch(PlaybackEvent.Progress(sessionId, positionMs, durationMs))

    fun onVideoSize(width: Int, height: Int) =
        dispatch(PlaybackEvent.VideoSize(sessionId, width, height))

    fun onSpeed(speed: Float) = dispatch(PlaybackEvent.Speed(sessionId, speed))

    /** 失败。 */
    fun fail(failure: PlaybackFailure) = dispatch(PlaybackEvent.Failed(sessionId, failure))

    /** 重试：回到准备态，尝试次数 +1。 */
    fun retry() = dispatch(PlaybackEvent.Retried(sessionId))

    /** 错误被清除但播放器还在（错误页退到后台再回来，不算一次全新尝试）。 */
    fun recover() = dispatch(PlaybackEvent.Recovered(sessionId))

    /** 释放。 */
    fun release() = dispatch(PlaybackEvent.Released(sessionId))

    /**
     * 兼容层：把改造前的 `playbackReady = true/false` 映射到状态机。
     *
     * 保留它是因为那 5 处赋值分散在三个文件、都夹在一长串副作用中间，
     * 逐个改写成 `onPlayerState(...)` 会让这次重构的 diff 无法审阅。
     * 语义仍然唯一：**`prepared` 只进不退**（见 [PlaybackMachine] 规则 2）。
     */
    fun setPrepared(value: Boolean) {
        val current = _state.value
        if (value == current.prepared) return
        if (value) {
            onPlayerState(PlaybackMachine.STATE_READY, current.playWhenReady)
        } else {
            // 回退到「准备中」：只在还没准备过时才允许，避免把已就绪的会话打回起点。
            _state.update {
                if (it.prepared) it else it.copy(phase = PlaybackPhase.PREPARING)
            }
        }
    }

    /**
     * 兼容层：把改造前的 `playError = true/false` 映射到状态机。
     *
     * 置 true 时如果没有具体现场，用一个占位 [PlaybackFailure]——错误页的按钮
     * 仍然由调用点自己算（那里有完整 cause 链），这里只负责「当前处于失败态」这一位。
     */
    fun setErrorFlag(value: Boolean) {
        if (value) {
            if (!failed) {
                fail(PlaybackFailure(track = com.hgtv.star.core.playback.MediaTrack.UNKNOWN, code = 0, reason = "", retryable = true))
            }
        } else if (failed) {
            recover()
        }
    }

    private fun dispatch(event: PlaybackEvent) {
        _state.update { PlaybackMachine.reduce(it, event) }
    }
}
