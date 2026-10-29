// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core.playback

/**
 * 一次播放所处的阶段。
 *
 * 这是**唯一**一处描述「现在播到哪一步」的地方。改造前这件事由 `MainActivity` 上的
 * 五个布尔量分担（`playbackReady` / `playError` / `pausedForLifecycle` /
 * `bufferingSuppressed` / `requestedAutoplay`），它们有 32 种组合，而其中只有 6 种是
 * 真实可达的——剩下 26 种组合一旦出现，界面上就是「有画面却说加载中」这类自相矛盾的状态。
 *
 * 阶段之间的合法迁移见 [PlaybackMachine]。
 */
enum class PlaybackPhase {
    /** 没有打开任何一集（还没进播放页，或已经退出）。 */
    IDLE,

    /**
     * 正在准备：取播放地址、建播放器、解析索引、挂 Surface。
     *
     * 这一段用户看到的是封面 / 黑场 + 转圈，**还没有任何一帧画面**。
     */
    PREPARING,

    /**
     * 播放器在缓冲。
     *
     * 与 [PREPARING] 的区别是「已经准备好过至少一次」：中途 seek、弱网抖动都会回到这里，
     * 但这时 `prepared` 仍为 true，进度条位置是可信的。
     */
    BUFFERING,

    /** 可以播了。播放 / 暂停由 [PlaybackState.playWhenReady] 表达，不再拆成两个阶段。 */
    READY,

    /** 这一集播完了（片尾自动推进前的那一瞬，或用户关闭连播时的终态）。 */
    ENDED,

    /** 出错，界面显示错误页。 */
    FAILED,
}

/**
 * 一次播放会话的完整状态。
 *
 * **不可变**：所有变化都通过 [PlaybackMachine.reduce] 产生新实例。这样做的直接好处是
 * 任何一次界面渲染读到的都是一份自洽的快照，不会读到「一半新一半旧」的字段组合。
 */
data class PlaybackState(
    /** 会话号：每打开一集 +1。异步回调带着旧会话号回来时直接丢弃。 */
    val sessionId: Long = 0,
    val phase: PlaybackPhase = PlaybackPhase.IDLE,
    val seriesId: String = "",
    val episodeId: String = "",
    val episodeIndex: Int = 0,
    /** 是否**至少 READY 过一次**。等价于改造前的 `playbackReady`，用来判断进度是否可信。 */
    val prepared: Boolean = false,
    /** 播放器是否处于「自动往下播」的意图（暂停时为 false）。 */
    val playWhenReady: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    /** 这一次缓冲的原因（诊断用；空串表示不是缓冲态）。 */
    val bufferingReason: String = "",
    /** 无感切集：上一集的画面还停在屏幕上，新源正在顶替。 */
    val seamless: Boolean = false,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    /** 本集第几次尝试（自动重试 / 降档 / 换解码都会 +1）。 */
    val attempt: Int = 0,
    /** 失败详情；非 null 时 [phase] 必为 [PlaybackPhase.FAILED]。 */
    val failure: PlaybackFailure? = null,
) {
    /** 进度是否可信（播放器已经准备好过）。 */
    val progressTrusted: Boolean get() = prepared

    /** 是否在播放页且已经有画面（用来决定要不要显示转圈）。 */
    val showSpinner: Boolean
        get() = phase == PlaybackPhase.PREPARING || phase == PlaybackPhase.BUFFERING

    /** 是否处于「用户可操作的播放态」（能暂停、能拖进度）。 */
    val interactive: Boolean get() = phase == PlaybackPhase.READY || phase == PlaybackPhase.BUFFERING
}

/** 失败落在哪条轨上。 */
enum class MediaTrack { VIDEO, AUDIO, SOURCE, UNKNOWN }

/**
 * 一次失败的现场。
 *
 * 与改造前的 `playError: Boolean` 相比，这里保留了「为什么失败」——错误页的两颗按钮
 * （切换软解 / 换到 xP）必须知道失败的是视频还是音频，否则会给用户一个改不了问题的按钮。
 */
data class PlaybackFailure(
    val track: MediaTrack,
    val code: Int,
    val reason: String,
    val retryable: Boolean,
) {
    /** 面板 / 日志里的一行摘要。 */
    fun summary(): String = "track=$track code=$code retryable=$retryable reason=$reason"
}
