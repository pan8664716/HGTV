// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.view.View
import com.hgtv.star.core.AppConfig
import com.hgtv.star.core.Detail
import com.hgtv.star.core.StreamInfo
import com.hgtv.star.shared.media.PlaybackFailures

internal class PhoneSourceRetryState {
    var runnable: Runnable? = null
    val limit = AppConfig.Playback.PHONE_SOURCE_RETRY_LIMIT
    val stepMs = AppConfig.Playback.PHONE_SOURCE_RETRY_STEP_MS
    val missingCode = 2000
    var episode = ""
    var count = 0
}

internal fun PhonePlayerActivity.resetSourceRetryForFreshAttempt() {
    sourceRetryState.episode = ""
    sourceRetryState.count = 0
}

/**
 * 换集 / 首集取地址失败：先按原提示与退避重试，额度用完再交给错误页。
 */
internal fun PhonePlayerActivity.onEpisodeSourceMissing(
    d: Detail,
    episodeIndex: Int,
    at: Long,
    problem: Throwable? = null,
) {
    val episodeId = d.episodes.getOrNull(episodeIndex) ?: return
    val timedOut = problem != null && PlaybackFailures.timedOut(problem)
    if (problem != null && com.hgtv.star.core.isInterruption(problem) && !timedOut) return
    if (problem != null) {
        android.util.Log.w(
            "HGTV-RECOVER",
            "source missing episode=${episodeIndex + 1} cache invalidated=${problem is java.io.IOException}",
            problem,
        )
    }
    if (sourceRetryState.episode != episodeId) {
        sourceRetryState.episode = episodeId
        sourceRetryState.count = 0
    }
    if (sourceRetryState.count < sourceRetryState.limit) {
        sourceRetryState.count++
        // 超时已经耗掉了完整的取地址预算，第一次重试立即执行；后续仍按退避。
        val wait = if(timedOut && sourceRetryState.count==1) 0L else sourceRetryState.stepMs * sourceRetryState.count
        // 与「第 N 集」那类提示走同一套 token：后到的提示会把这一条顶掉。
        hint.text = if(wait==0L) {
            "第 ${episodeIndex + 1} 集取地址超时，正在重试…"
        } else {
            "第 ${episodeIndex + 1} 集还没取到播放地址，${wait / 1000} 秒后重试…"
        }
        sourceRetryHintToken++
        sourceRetryHintSticky = true
        hint.visibility = View.VISIBLE
        hint.bringToFront()
        val token = sourceRetryHintToken
        cancelSourceRetry()
        val retry = object : Runnable {
            override fun run() {
                if (sourceRetryState.runnable === this) sourceRetryState.runnable = null
                if (!isFinishing && !isDestroyed) {
                    // 重试前先把提示收掉：重试成功时不该还挂着「重试中」。
                    if (sourceRetryHintToken == token) {
                        sourceRetryHintSticky = false
                        hint.visibility = View.GONE
                    }
                    play(episodeIndex, at)
                }
            }
        }
        sourceRetryState.runnable = retry
        root.postDelayed(retry, wait)
        return
    }
    // 额度用完：交给错误页（带「立即重试 / 下一集」），绝不留一块黑屏给用户。
    sourceRetryHintSticky = false
    hint.visibility = View.GONE
    failureStage = com.hgtv.star.core.PlaybackStage.PREPARE
    showPlaybackError(
        playbackAttribution(sourceRetryState.missingCode),
        com.hgtv.star.core.GiveUpReason.BUDGET_EXHAUSTED,
    )
}

/**
 * 清掉这一集当前所有缓存，并让下一次取流使用新的请求 device_id。
 *
 * 与电视端的 `prepareFreshPlaybackRetry` 保持同一条语义：
 * 地址、索引、首块、预取槽和连接层一起作废，不能只重放同一条失败流。
 */
internal fun PhonePlayerActivity.invalidateEpisodeCacheForRetry(episodeId: String, info: StreamInfo?) {
    queuedVideo?.let { runCatching { it.close() } }
    queuedVideo = null
    queuedEpisodeId = ""
    PhoneSession.clearNext()
    playbackSource.invalidate(episodeId)
    info?.let {
        val host = runCatching { java.net.URI(it.url).host }.getOrNull().orEmpty()
        if (host.isNotEmpty()) {
            PlaybackFailures.dropTransportCaches(listOf(host))
        }
    }
}
