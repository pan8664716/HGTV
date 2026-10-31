// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.hgtv.star.core.DegradationKind
import com.hgtv.star.core.FailureAttribution
import com.hgtv.star.core.GiveUpReason
import com.hgtv.star.core.MediaKind
import com.hgtv.star.core.PlaybackDegradation
import com.hgtv.star.core.PlaybackErrorCode
import com.hgtv.star.core.PlaybackQuality
import com.hgtv.star.core.PlaybackRetryPlan
import com.hgtv.star.core.RetryDecision
import com.hgtv.star.core.VideoDecoderMode
import com.hgtv.star.phone.ui.PhoneStyle
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.phoneSize
import com.hgtv.star.phone.ui.roundedBackground
import com.hgtv.star.shared.data.PlaybackCapability
import com.hgtv.star.shared.media.PlaybackFailures
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 手机端的播放失败自愈：**决策全部来自 `:core`，与电视端同一套**。
 *
 * ## 为什么必须复用而不是另写一套
 *
 * 电视端这套规则是被现场事故一条条磨出来的（见 `core/PlaybackDegradation.kt` 的注释）：
 *
 * * 4001 / 4003 先**同档重建一次**（解码器实例被占 / 解到坏帧），不白降一档；
 * * 4004 / 4005 才降档，每集最多降两档；
 * * 降过一次却没换来更低的一档（CDN 只回兼容资源）→ 不再白烧第二轮；
 * * 音频失败与档位、解码方式无关，只允许「放掉解码器重开一次」；
 * * **绝不自动改用户设置**，也绝不静默切软解。
 *
 * 手机端早先什么都没有：一次解码失败就停在那里，只有一行「播放失败 XXXX」的小字，
 * 用户只能退出重进。这里把同一套决策接进来，两端从此不会对同一台机器给出不同结论。
 *
 * ## 手机端多出来的两件事
 *
 * 1. **网络 / 源类失败自动退避重试**（3 / 6 / 12 秒，来自 `PlaybackAttemptLedger`）：
 *    手机上切网（Wi-Fi ↔ 5G）、地铁里丢包比电视常见得多，一次瞬态失败不该判死；
 * 2. **错误页是触控的**：三颗按钮（立即重试 / 换软解 / 换档位），能点的才出现——
 *    音频失败时那两颗视频按钮必须不出现，否则点了只会再撞一次同样的错。
 */

/** 本次播放**实际生效**的清晰度上限：会话层降档结果 > 用户设置。 */
internal val PhonePlayerActivity.playbackMaxQuality: Int
    get() = sessionPlayback.effectiveMaxQuality ?: (library?.maxQuality ?: 1080)

/** 把这一次失败整理成策略能吃的归因（判断本体在 `:shared`，与电视端同一份）。 */
internal fun PhonePlayerActivity.playbackAttribution(code: Int): FailureAttribution =
    PlaybackFailures.attribution(
        problem = null,
        code = code,
        info = remote?.info,
        fallbackQuality = playbackMaxQuality,
        mode = library?.decoderMode ?: VideoDecoderMode.HARDWARE,
    )

/**
 * 一次失败的总入口。
 *
 * 顺序：解码类（4xxx）走降级决策 → 网络 / 源 / 音频输出走退避重试 → 其余直接进错误页。
 * 重试期间（[PhonePlayerActivity.recovering]）再来的错误一律忽略：
 * 那是同一次失败的余波，不是新的一次。
 */
internal fun PhonePlayerActivity.handlePlayerFailure(error: PlaybackException) {
    // **每次起播只处置一次**：一次失败会在几毫秒里连报两三条（渲染器报完、播放器再收尾），
    // 逐条处置会把预算在几十毫秒里烧光。用代次判断而不是布尔：布尔在「重建又失败」时
    // 永远留在 true，第二次失败就再也不会被处置（实测就卡在这里）。
    if (handledAttempt == attemptSeq) {
        android.util.Log.i("HGTV-RECOVER", "same attempt, ignore code=${error.errorCode}")
        return
    }
    handledAttempt = attemptSeq
    // 这一次失败的阶段：错误页标题按它取（与电视端同一张表），调试面板也读同一格。
    failureStage = PlaybackFailures.stageOf(error)
    android.util.Log.i("HGTV-RECOVER", "failure code=${error.errorCode} stage=$failureStage attempt=$attemptSeq player=${player != null}")
    val code = error.errorCode
    val attr = playbackAttribution(code)
    android.util.Log.i("HGTV-RECOVER", "attr media=${attr.media} q=${attr.currentQuality} avail=${attr.available} mode=${attr.currentMode}")
    // 出错这一路先停住：不停的话它会在后台继续重连同一条坏流，把重试预算烧在错误的地方。
    runCatching { player?.pause() }

    // 解码器根本没起来 / 查询失败：这台设备「能解 HEVC」的结论可能不成立，**重新探一次**。
    // 与电视端同一条处置（`PlaybackCapability.invalidate` + 后台重探）：不重探的话，
    // 错误页读到的还是那份旧缓存，「切换为软解」该出现的时候不出现。
    if (attr.media == MediaKind.VIDEO && code in listOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        )
    ) {
        PlaybackCapability.invalidate(this)
        PlaybackCapability.invalidateHardwareHevc()
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            PlaybackCapability.warmHardwareHevc(this@handlePlayerFailure)
        }
    }

    // ① 解码类：交给 `:core` 的决策（同档重建 / 降档 / 放弃）。
    if (code in 4000..4999) {
        // 本机**一颗 HEVC 硬解都没有**：4002 与「这一档太高」无关——任何档位都是同一张空表，
        // 降档只会白烧两次起播。直接把「切软解 + 具体档位」给出来（与电视端同一条短路）。
        // 读**内存缓存**，不在主线程做 Binder 扫描（`MediaCodecList` 在低配上能卡 1~3 秒，
        // 而这里是「刚出错、正要给用户看错误页」的时刻，卡一下就是把错误页也拖没了）。
        // 缓存由启动时的后台预热填好；还不知道时按「有硬解」处理——少给一颗按钮，
        // 比让用户在错误页上多等两秒强（见 `PlaybackCapability.cachedHardwareHevc`）。
        val noHardware = attr.media == MediaKind.VIDEO &&
            attr.currentMode == VideoDecoderMode.HARDWARE &&
            PlaybackCapability.cachedHardwareHevc() == false
        val queryFailed = code == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED &&
            attr.media == MediaKind.VIDEO
        if (queryFailed || noHardware) {
            sessionPlayback.noteManualHint(PlaybackDegradation.manualHint(attr))
            showPlaybackError(attr, GiveUpReason.NO_LOWER_TIER)
            return
        }
        when (val decision = PlaybackDegradation.decide(attr, sessionPlayback)) {
            is RetryDecision.Retry -> {
                replayWithPlan(decision.plan)
                return
            }
            is RetryDecision.GiveUp -> {
                showPlaybackError(attr, decision.reason)
                return
            }
        }
    }

    // ② 网络 / 超时 / 音频输出：退避重试。手机上切网比电视频繁得多。
    if (retryable(code) && ledger.canAttempt) {
        val wait = ledger.delayMillis ?: 0L
        ledger.consumeAttempt()
        scheduleAutoRetry(wait)
        return
    }

    // ③ 其余（或额度用尽）：进错误页，交给用户。
    showPlaybackError(attr, null)
}

/** 这些错误值得等一会儿再来一次：判据只有一份（`:shared` 的 `PlaybackFailures.isRetryableCode`）。 */
private fun retryable(code: Int): Boolean = PlaybackFailures.isRetryableCode(code)

/**
 * 按计划重来一次：**参数只改一个维度**（同档重建 / 降一档），断点续播。
 *
 * 位置直接问播放器：出错时 `currentPosition` 仍然是失败那一刻的位置，
 * 拿界面上的读数会退回到上一次 tick（最多差一秒），读起来像「降级一次就跳回去了」。
 */
internal fun PhonePlayerActivity.replayWithPlan(plan: PlaybackRetryPlan) {
    val live = runCatching { player?.currentPosition ?: -1L }.getOrDefault(-1L)
    val at = (if (live > 0) live else 0L).coerceAtLeast(0)
    plan.maxQuality?.let { sessionPlayback.setMaxQuality(it) }
    plan.fromQuality?.let { sessionPlayback.noteQualityDown(it) }
    plan.toast?.let { text ->
        val key = "${detail?.episodes?.getOrNull(index) ?: index}:${plan.reason}:${plan.maxQuality ?: 0}"
        if (playbackToasts.shown(key)) toast(text)
    }
    cancelAutoRetry()
    clearPlaybackError()
    if (plan.reason == DegradationKind.SAME_QUALITY_REBUILD) {
        // 同档重建：地址与索引都还是好的，**只放掉解码器重开**。
        // 走完整重取会把一次「解码器实例被占」放大成一次 500~1100ms 的取地址 + 一次黑屏。
        rebuildSameQuality(at)
        return
    }
    // 降档：上限变了就是另一条地址、另一份索引，必须整条重来（缓存键含档位，不会撞旧索引）。
    play(index, at)
}

/**
 * 同档重建：`stop()` 放掉编解码器，`prepare()` 用**同一份媒体源**重新初始化。
 *
 * 不用 `setMediaSource` 再来一遍：那会连带把地址、索引、首块全部丢掉，
 * 而这一次失败的原因就在本机解码器上，与那条流无关。
 */
internal fun PhonePlayerActivity.rebuildSameQuality(at: Long) {
    val exo = player
    if (exo == null) return
    // 新的一次尝试：错误代次跟着前进，这一次的失败才会被处置。
    attemptSeq++
    recovering = true
    // 先把「解码方式」这一档写进选择器（用户可能刚点了「切换为软解」）。
    PhoneServices.player(this, library?.decoderMode)
    runCatching { exo.stop() }
    runCatching { exo.prepare() }
    if (at > 0) runCatching { exo.seekTo(at) }
    exo.playWhenReady = true
    syncPlayGlyph()
    startTicker()
}

/** 排一次自动重试；同一时刻只排一个。 */
internal fun PhonePlayerActivity.scheduleAutoRetry(waitMs: Long) {
    cancelAutoRetry()
    recovering = true
    hint.text = "连接不稳，${(waitMs / 1000).coerceAtLeast(1)} 秒后重试…"
    hint.visibility = View.VISIBLE
    retryJob = scope.launch {
        delay(waitMs)
        if (isFinishing || isDestroyed) return@launch
        retryJob = null
        hint.visibility = View.GONE
        val at = runCatching { player?.currentPosition ?: 0L }.getOrDefault(0L).coerceAtLeast(0)
        // 同一条地址重开：地址本身没坏（坏的是网络那一下），重取只会白付一次取地址。
        rebuildSameQuality(at)
    }
}

internal fun PhonePlayerActivity.cancelAutoRetry() {
    retryJob?.cancel()
    retryJob = null
}

/** 真正出画面了：重试窗口结束、错误页收起（与电视端的 `onRenderedFirstFrame` 同一职责）。 */
internal fun PhonePlayerActivity.notePlaybackHealthy() {
    if (recovering) recovering = false
    if (errorCard != null) clearPlaybackError()
}

/**
 * 错误页：一句话说清「为什么放不了」，下面只摆**对这条轨有效**的按钮。
 *
 * 三颗按钮的出现条件（与电视端同一套判断）：
 * * 「立即重试」——永远有，它同时归还本集的自动重试额度（有限次）；
 * * 「切换为软解」——只在**视频**轨、且当前是硬解时出现（音频走全表，换它没用）；
 * * 「换到 NP」——只在**视频**轨、且还有更低一档时出现，档位取软解跑得动的那一档；
 * * 「下一集」——不是最后一集时出现：用户可能只是想跳过这一集。
 */
internal fun PhonePlayerActivity.showPlaybackError(attr: FailureAttribution, reason: GiveUpReason?) {
    android.util.Log.i("HGTV-RECOVER", "error card reason=$reason hint=${sessionPlayback.manualHint}")
    cancelAutoRetry()
    recovering = false
    errorAttr = attr
    player?.pause()
    syncPlayGlyph()
    clearPlaybackError()

    val video = attr.media == MediaKind.VIDEO
    val lower = if (video) PlaybackDegradation.lowerThan(attr.available, attr.currentQuality) else null
    val softTarget = if (lower != null) PlaybackDegradation.softwareQuality(attr.available, lower) else 0

    val card = FrameLayout(this).apply {
        setBackgroundColor(0xCC000000.toInt())
        isClickable = true
    }
    val column = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(14))
        background = roundedBackground(0xEE1A1A1A.toInt(), 0f)
    }
    column.addView(TextView(this).apply {
        // 标题按阶段取（与电视端同一张表）：同一个故障两端必须是同一句话。
        // 认不出阶段时退回「播放失败」——比把错误码当标题读给用户强。
        text = PlaybackFailures.titleOf(failureStage)
        setTextColor(Color.WHITE)
        phoneSize(16)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    })
    val message = buildString {
        append(sessionPlayback.manualHint ?: when (reason) {
            GiveUpReason.AUDIO_NO_RECOVERY -> "这一集的音频在本机放不了，可以换一集或稍后重试。"
            GiveUpReason.BUDGET_EXHAUSTED -> "已经自动重试过，还是没能放起来。"
            GiveUpReason.NO_LOWER_TIER -> "这一档清晰度在本机放不了，也没有更低的档位可换。"
            null -> if (attr.errorCode in 2000..2999 || attr.errorCode in 5000..5999) {
                "网络或音频输出暂时不可用，已经自动重试过几次。"
            } else {
                "这一集暂时放不了（${attr.errorCode}）。"
            }
        })
        if (reason != null) append("\n\n已自动尝试：${sessionPlayback.budgetSummary()}")
    }
    column.addView(TextView(this).apply {
        text = message
        setTextColor(PhoneStyle.textDim)
        phoneSize(12)
        setPadding(0, dp(8), 0, dp(12))
    })

    // 按钮行：主按钮（立即重试）独占一行，其余两颗并排，最下面「下一集」。
    column.addView(errorButton("立即重试", primary = true) {
        clearPlaybackError()
        // 归还本集的额度（策略那边有次数上限，连点第三次不再归还）。
        sessionPlayback.onManualRetry()
        ledger.onManualRetry()
        val at = runCatching { player?.currentPosition ?: 0L }.getOrDefault(0L).coerceAtLeast(0)
        play(index, at)
    }, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, dp(42)
    ).apply { topMargin = dp(4) })

    val second = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    if (video && attr.currentMode == VideoDecoderMode.HARDWARE) {
        second.addView(errorButton("切换为软解") {
            library?.decoderMode = VideoDecoderMode.SOFTWARE
            sessionPlayback.onManualDecoderChange()
            clearPlaybackError()
            val at = runCatching { player?.currentPosition ?: 0L }.getOrDefault(0L).coerceAtLeast(0)
            rebuildSameQuality(at)
        }, LinearLayout.LayoutParams(0, dp(40), 1f))
    }
    if (softTarget > 0) {
        second.addView(errorButton("换到 ${softTarget}P") {
            library?.maxQuality = PlaybackQuality.normalize(softTarget)
            sessionPlayback.onManualQualityChange()
            clearPlaybackError()
            play(index, 0L)
        }, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            if (second.childCount > 0) leftMargin = dp(8)
        })
    }
    if (second.childCount > 0) {
        column.addView(second, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(40)
        ).apply { topMargin = dp(8) })
    }

    val d = detail
    if (d != null && index + 1 < d.episodes.size) {
        column.addView(errorButton("下一集") {
            clearPlaybackError()
            play(index + 1)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(40)
        ).apply { topMargin = dp(8) })
    }

    card.addView(column, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply {
        gravity = Gravity.CENTER
        leftMargin = dp(24)
        rightMargin = dp(24)
    })
    root.addView(card, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    ))
    errorCard = card
}

/** 一颗按钮：主按钮用主题色实心，其余用半透明白描边。 */
private fun PhonePlayerActivity.errorButton(
    label: String,
    primary: Boolean = false,
    onClick: () -> Unit,
): TextView = TextView(this).apply {
    text = label
    setTextColor(if (primary) Color.WHITE else PhoneStyle.text)
    phoneSize(13)
    gravity = Gravity.CENTER
    background = if (primary) {
        roundedBackground(PhoneStyle.accent, 0f)
    } else {
        roundedBackground(0x22FFFFFF, 0f)
    }
    setOnClickListener { onClick() }
}

internal fun PhonePlayerActivity.clearPlaybackError() {
    errorCard?.let { root.removeView(it) }
    errorCard = null
    errorAttr = null
}

/** 这组错误码是不是「解码器根本没起来」（错误页要重探一次设备能力）。 */
internal fun isDecoderStartupCode(code: Int): Boolean =
    code == PlaybackErrorCode.DECODER_INIT_FAILED || code == PlaybackErrorCode.DECODER_QUERY_FAILED

// ---------------------------------------------------------------- 看门狗

/**
 * 装一次看门狗（每次起播 / 换集 / 从后台回来都要重新装）。
 *
 * 盯的是两种**不抛异常**的故障：有声音没画面、一直卡在缓冲。
 * 判据在 `:core` 的 [com.hgtv.star.core.PictureWatchdog]（纯逻辑、有单测），
 * 这里只负责喂时间戳与执行处置——与电视端同一套阈值。
 */
internal fun PhonePlayerActivity.armPictureWatchdog() {
    disarmPictureWatchdog()
    val exo = player ?: return
    // 用户自己暂停 / 退到后台：那不是卡死，直接不盯（见 `PictureWatchdog` 的说明）。
    if (exo.playWhenReady != true) return
    watchdog.arm(alreadyReady = exo.playbackState == Player.STATE_READY)
    val tick = object : Runnable {
        override fun run() {
            if (watchdogTick !== this) return
            if (isFinishing || isDestroyed) { watchdog.disarm(); watchdogTick = null; return }
            val p = player
            if (p == null || p.playWhenReady != true) {
                // 暂停了：撤掉看门狗，恢复播放时由 `play()` / `resume` 重新装。
                watchdog.disarm(); watchdogTick = null; return
            }
            when (watchdog.check()) {
                com.hgtv.star.core.PictureWatchdog.Verdict.DONE -> { watchdogTick = null; return }
                com.hgtv.star.core.PictureWatchdog.Verdict.NO_FRAME -> {
                    watchdogTick = null
                    recoverLostPicture("no_first_frame")
                    return
                }
                com.hgtv.star.core.PictureWatchdog.Verdict.STALL_BUFFER -> {
                    watchdogTick = null
                    recoverLostPicture("stall_buffer")
                    return
                }
                com.hgtv.star.core.PictureWatchdog.Verdict.WAITING -> Unit
            }
            root.postDelayed(this, com.hgtv.star.core.PictureWatchdog.TICK_MS)
        }
    }
    watchdogTick = tick
    root.postDelayed(tick, com.hgtv.star.core.PictureWatchdog.TICK_MS)
}

internal fun PhonePlayerActivity.disarmPictureWatchdog() {
    watchdog.disarm()
    watchdogTick?.let { root.removeCallbacks(it) }
    watchdogTick = null
}

/**
 * 画面丢了：**重建一次播放层**，断点续播。
 *
 * 每集只做一次（`PlaybackAttemptLedger` 的画面重建额度，与电视端同一条规则）：
 * 重建第二次仍然是黑屏的话，问题不在「这一层坏了」而在这一档片源本身，
 * 再重建只是让用户多等 15 秒。
 *
 * 处置前**先把地址与媒体源原样留着**：这类故障与那条流无关（音频都在响），
 * 重取一次地址只是白付 500~1100ms，还可能换到另一条同样有问题的资源。
 */
internal fun PhonePlayerActivity.recoverLostPicture(reason: String) {
    val now = android.os.SystemClock.elapsedRealtime()
    if (ledger.pictureCoolingDown(now, 20_000L)) return
    if (ledger.formalPictureRecoveries >= 1) {
        // 已经重建过一次还是没画面：如实告诉用户，别让它无限重建。
        toast(if (reason == "stall_buffer") "这一集加载不动，可以换一集" else "这一集画面出不来，可以换一集")
        return
    }
    ledger.notePictureRecovery(now)
    android.util.Log.i("HGTV-PHONE", "picture lost ($reason), rebuilding once")
    val at = runCatching { player?.currentPosition ?: 0L }.getOrDefault(0L).coerceAtLeast(0)
    rebuildSameQuality(at)
    // 重建之后重新装一次看门狗：这一次还不行就该报错 / 提示了。
    root.postDelayed({ if (!isFinishing && !isDestroyed) armPictureWatchdog() }, 800)
}
