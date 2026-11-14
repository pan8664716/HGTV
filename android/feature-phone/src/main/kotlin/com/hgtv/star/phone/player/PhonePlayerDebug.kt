// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.view.View
import com.google.android.exoplayer2.Player
import com.hgtv.star.phone.ui.dp

/**
 * 调试模式那一块面板：打开就挂、关掉就摘。
 *
 * 与电视端的 `syncDebugOverlay` 同一条规则：把 `:shared` 的「近况」出口接到
 * [com.hgtv.star.shared.debug.DebugLog]，然后每秒把播放现场刷一遍。
 * 面板**不吃触摸**（见 `PhoneDebugOverlay`），开着它照样能点按钮。
 */
internal fun PhonePlayerActivity.syncDebugOverlayImpl() {
    val lib = library ?: return
    val on = runCatching { lib.debugMode }.getOrDefault(false)
    com.hgtv.star.shared.debug.DebugLog.enabled = on
    if (!on) {
        if (debugOverlay?.visibility == View.VISIBLE) PhoneDebugReport.resetSamples()
        debugOverlay?.visibility = View.GONE
        return
    }
    com.hgtv.star.shared.debug.debugSink = { tag, line ->
        com.hgtv.star.shared.debug.DebugLog.record(tag, line)
    }
    val overlay = debugOverlay ?: com.hgtv.star.phone.ui.PhoneDebugOverlay(this).also {
        debugOverlay = it
        com.hgtv.star.phone.ui.PhoneDebugOverlay.place(it, root, dp(8))
    }
    overlay.update(debugLinesImpl())
    PhoneDebugReport.sampleAsync(this) {
        if (!isFinishing && !isDestroyed) syncDebugOverlayImpl()
    }
}

/** 播放现场那几行（与电视端面板的播放页内容同源：状态 / 位置 / 解码 / 尺寸 / 错误）。 */
internal fun PhonePlayerActivity.debugLinesImpl(): List<String> {
    val extras = ArrayList<Pair<String, String>>(12)
    val d = detail
    val p = player
    val quality = debugQualityLabel()
    if (d != null) extras += "剧集" to
        "${d.series.title} · ${index + 1}/${d.episodes.size}集 · $quality"
    val state = when {
        p == null -> "无播放器"
        p.playbackState == Player.STATE_BUFFERING -> "缓冲"
        p.playbackState == Player.STATE_READY -> if (p.isPlaying) "播放中" else "暂停"
        p.playbackState == Player.STATE_ENDED -> "已结束"
        else -> "空闲"
    }
    if (p != null) {
        extras += "状态" to "$state · ${debugClock(p.currentPosition)}/${debugClock(p.duration.coerceAtLeast(0))}" +
            " · 缓冲 ${debugClock(p.bufferedPosition.coerceAtLeast(0))}（${p.bufferedPercentage}%）" +
            " · ${p.playbackParameters.speed}×"
    } else {
        extras += "状态" to state
    }
    val metrics = PhoneServices.metrics
    val init = if (metrics.initMs >= 0) " ${metrics.initMs}ms" else ""
    extras += "解码" to "${PhoneDecoder.mode.label} ${metrics.decoderName.ifBlank { "-" }}$init"
    extras += "编码" to metrics.mime.ifBlank { "-" } +
        metrics.codecs.takeIf { it.isNotBlank() }?.let { " · ${it.take(70)}" }.orEmpty()
    val size = if (metrics.width > 0 && metrics.height > 0)
        "${metrics.width}x${metrics.height}" else listenerDebugVideo
    val fps = if (metrics.frameRate > 0f) "@${Math.round(metrics.frameRate)}" else ""
    val bitrate = if (metrics.bitrate > 0) " ${metrics.bitrate / 1000}k" else ""
    val frameAge = if (lastFrameAt > 0L) {
        "${(android.os.SystemClock.elapsedRealtime() - lastFrameAt).coerceAtLeast(0) / 1000}s 前"
    } else "无逐帧回调"
    extras += "画面" to "$size$fps$bitrate · 帧龄 $frameAge · 竖屏=${d?.series?.vertical ?: false}" +
        " · ${if (playerLandscapeMode) "横屏" else "竖屏"}"
    val firstFrame = lastFrameAt > 0L || metrics.firstFrameAt > 0L
    extras += "帧数" to "首帧=${if (firstFrame) "是" else "否"}" +
        " · 丢帧=${metrics.droppedFrames} · 欠载=${metrics.underruns}" +
        " · 解码错=${metrics.lastCodecError.isNotBlank()}"
    // 实际生效的档位与用户设置可能不同：自动降档只写会话层（与电视端同一条规则）。
    val cap = playbackMaxQuality
    val requested = library?.maxQuality ?: 0
    extras += "画质" to "${if (cap > 0) "${cap}P" else "-"}" +
        (if (cap < requested) "（本次降档）" else "") + " · 实际 $quality"
    if (playbackSessionState.touched()) {
        val reason = when (playbackSessionState.lastReason) {
            com.hgtv.star.core.DegradationKind.SAME_QUALITY_REBUILD -> "原档重建"
            com.hgtv.star.core.DegradationKind.QUALITY_DOWN -> "降清晰度"
            null -> "待手动处理"
        }
        extras += "降级" to "$reason · ${playbackSessionState.budgetSummary()} · 重试=${playbackSessionState.autoRetryAttempts}"
    }
    val errors = listOf(listenerDebugError, metrics.lastCodecError).filter { it.isNotBlank() }
        .joinToString(" · ")
    return PhoneDebugReport.lines(
        this, "播放", library, extras,
        problem = if (errors.isBlank()) "无" else "播放失败",
        error = errors,
        recent = 5,
    )
}
