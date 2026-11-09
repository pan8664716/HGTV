// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.hgtv.star.core.DegradationKind
import com.hgtv.star.core.FailureAction
import com.hgtv.star.core.FailureAttribution
import com.hgtv.star.core.FailureCapability
import com.hgtv.star.core.FailureFacts
import com.hgtv.star.core.FailurePresentation
import com.hgtv.star.core.GiveUpReason
import com.hgtv.star.core.MediaKind
import com.hgtv.star.core.PlaybackErrorCode
import com.hgtv.star.core.PlaybackFailurePolicy
import com.hgtv.star.core.RecommendBehavior
import com.hgtv.star.core.PlaybackQuality
import com.hgtv.star.core.PlaybackRetryPlan
import com.hgtv.star.core.VideoDecoderMode
import com.hgtv.star.phone.ui.PhoneStyle
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.phoneSize
import com.hgtv.star.phone.ui.roundedBackground
import com.hgtv.star.shared.data.DeviceHealer
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
    get() = playbackSessionState.effectiveMaxQuality ?: (library?.maxQuality ?: 1080)

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
 * **判断全部交给 `:core` 的 [PlaybackFailurePolicy]**：地址自愈、队列项接回、解码降级、
 * 退避重试、错误页，电视端调的是同一个函数。这里只做三件事：
 *
 * 1. 把平台侧的事实读出来（异常链 → 布尔量、播放器状态 → 队列/片尾、设备能力）；
 * 2. 执行决策给出的动作；
 * 3. 画错误页。
 *
 * 早先手机端自己写了一套「解码降级 + 盲目重试 + 一张卡片」：没有地址自愈、没有队列分支、
 * 不换设备号，于是同一个故障在电视上能自己救回来、在手机上直接死在黑屏上
 * （2026-11 现场：「第三集播完进不了第四集，退也退不出去」）。
 */
internal fun PhonePlayerActivity.handlePlayerFailure(error: PlaybackException) {
    // **每次起播只处置一次**：一次失败会在几毫秒里连报两三条（渲染器报完、播放器再收尾），
    // 逐条处置会把预算在几十毫秒里烧光。用代次判断而不是布尔：布尔在「重建又失败」时
    // 永远留在 true，第二次失败就再也不会被处置（实测就卡在这里）。
    val attempt = playbackCoordinator.claimFailure()
    if (attempt == null) {
        android.util.Log.i("HGTV-RECOVER", "same attempt, ignore code=${error.errorCode}")
        return
    }
    // 这一次失败的阶段：错误页标题按它取（与电视端同一张表），调试面板也读同一格。
    failureStage = PlaybackFailures.stageOf(error)
    val code = error.errorCode
    val attr = playbackAttribution(code)
    android.util.Log.i(
        "HGTV-RECOVER",
        "failure code=${error.errorCode} stage=$failureStage attempt=$attempt player=${player != null}",
    )
    // 出错这一路先停住：不停的话它会在后台继续重连同一条坏流，把重试预算烧在错误的地方。
    runCatching { player?.pause() }

    // 解码器根本没起来 / 查询失败：这台设备「能解 HEVC」的结论可能不成立，**重新探一次**。
    // 与电视端同一条处置（`PlaybackCapability.invalidate` + 后台重探）。
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

    // 服务端说「这台设备没注册过」（`101000`）：**先换一套身份重注册，再重取地址**。
    //
    // 手机端原先完全没有这一层：同一台机器在电视上会自己恢复，手机上只能报错让用户
    // 退出重进（重进也没用——身份还是那一份）。判定、预算、线程与注册全在 `:shared` 的
    // `DeviceHealer`，与电视端 `healDeviceIdentity` 同一份；这里只写「成功之后做什么」。
    //
    // 成功之后必须重新 `play()`：模型缓存已随身份变化作废，重取回来的才是新身份的那一条。
    if (PlaybackFailures.deviceRejected(error)) {
        val at = runCatching { player?.currentPosition ?: 0L }.getOrDefault(0L).coerceAtLeast(0)
        val taken = DeviceHealer.healAsync(library, PhoneServices.repository(this), "播放被拒") { healed ->
            if (healed) {
                clearPlaybackError()
                play(index, at)
            } else {
                // 换不成（预算用尽 / 注册失败）：回到正常决策，让错误页说明情况。
                decideAndRun(error, attr)
            }
        }
        if (taken) return
    }

    decideAndRun(error, attr)
}

/**
 * 读事实 → 决策 → 执行。
 *
 * 单独抽出来是为了「队列项接不回来」时能带着 `queued = false` **再决策一次**
 * （决策函数是纯的，重入安全，最多两层）。
 */
private fun PhonePlayerActivity.decideAndRun(error: PlaybackException, attr: FailureAttribution) {
    val code = error.errorCode
    val position = runCatching { player?.currentPosition ?: 0L }.getOrDefault(0L).coerceAtLeast(0)
    val duration = runCatching { player?.duration ?: 0L }.getOrDefault(0L)
    val queued = queuedEpisodeId.isNotEmpty()
    // 片尾窗口内报错 = 下一集真的没接上；窗口外报错 = 当前这一集还在正常播，
    // 坏的只是后台预加载的那一条（判据与电视端逐字一致）。
    val queuedNearEnd = queued && duration > 0 && position >= duration - QUEUED_NEAR_END_MS
    val facts = FailureFacts(
        errorCode = code,
        stage = failureStage,
        media = attr.media,
        retryable = PlaybackFailures.isRetryableCode(code),
        // 四条「缓存里的东西过期了」的判据在 `:shared`，与电视端同一份。
        streamExpired = PlaybackFailures.expired(error),
        videoChanged = PlaybackFailures.videoChanged(error),
        hostUnresolved = PlaybackFailures.hostUnresolved(error),
        transportDead = PlaybackFailures.transportDead(error),
        queued = queued,
        queuedNearEnd = queuedNearEnd,
        noHardwareDecoder = attr.media == MediaKind.VIDEO &&
            attr.currentMode == VideoDecoderMode.HARDWARE &&
            PlaybackCapability.cachedHardwareHevc() == false,
        autoRetryAllowed = !sleepStopped,
        // 手机这一条路径永远是「播放页」：推荐页有自己的一套（`PhoneFeedView` 换下一部）。
        onRecommend = false,
    )
    when (val action = playbackCoordinator.decideFailure(facts, attr)) {
        is FailureAction.RefetchStream -> refetchAndReplay(action, attr)
        FailureAction.RecoverQueuedCurrent -> {
            if (!recoverQueuedCurrent(position)) {
                // 接不回来（额度用尽 / 重开没成功）：队列已经清掉，按「当前这一集自己坏了」再判一次。
                decideAndRun(error, attr)
            }
        }
        is FailureAction.Replay -> replayWithPlan(action.plan)
        is FailureAction.ShowError -> showPlaybackError(attr, action.reason, action.autoRetryDelayMs)
        // 推荐页的两条分支在手机上由 `PhoneFeedView` 自己处理（换下一部 / 停在提示上），
        // 走不到这里。
        FailureAction.SkipRecommend, FailureAction.GiveUpRecommend -> Unit
    }
}

/**
 * 地址 / 索引 / 连接层过期：**作废之后重取一条地址重来**。
 *
 * 这是手机端原先完全没有的一层。只丢播放地址不够——地址按集签发，但 host 往往没变，
 * 重取回来还是同一个域名，于是又命中 DNS 里那份坏 IP、又复用连接池里那条半死连接，
 * 下一发照样卡满超时。因此 [FailureAction.RefetchStream.dropTransport] 要求把
 * 视频域名的解析结果与进程级连接池一起清掉。
 */
private fun PhonePlayerActivity.refetchAndReplay(
    action: FailureAction.RefetchStream,
    attr: FailureAttribution,
) {
    val d = detail ?: return
    val targetIndex = index + action.episodeOffset
    val episodeId = d.episodes.getOrNull(targetIndex) ?: return
    android.util.Log.w(
        "HGTV-RECOVER",
        "refetch episode=${targetIndex + 1} offset=${action.episodeOffset} transport=${action.dropTransport}",
    )
    // 索引与首块也是按「集 + 档位」落盘的：文件换过之后它们一起作废。
    // 键要用**这一次失败的那条流**：换到下一集时 `remote` 还是当前这一集，拿它算等于清错对象。
    val staleInfo = if (action.episodeOffset != 0) queuedVideo?.info else remote?.info
    playbackSource.invalidate(episodeId, staleInfo)
    if (action.dropTransport) {
        val hosts = LinkedHashSet<String>(3)
        remote?.host?.takeIf { it.isNotEmpty() }?.let(hosts::add)
        queuedVideo?.host?.takeIf { it.isNotEmpty() }?.let(hosts::add)
        PlaybackFailures.dropTransportCaches(hosts)
    }
    // 队列里那一集要救的是**它**，不是当前这一集：清掉队列，按目标集重来。
    queuedVideo?.let { runCatching { it.close() } }
    queuedVideo = null
    queuedEpisodeId = ""
    PhoneSession.clearNext()
    val at = if (action.fromStart) 0L else runCatching { player?.currentPosition ?: 0L }
        .getOrDefault(0L).coerceAtLeast(0)
    if (action.episodeOffset != 0) {
        // 换到另一集：按集计的额度跟着换（与电视端 `restartAnotherEpisode` 同一条规则）。
        playbackCoordinator.onEpisodeSwitch(episodeId)
    }
    play(targetIndex, at)
}

/**
 * 队列里那一集坏了、当前这一集还在正常播：**原地把当前集接回来**。
 *
 * ExoPlayer 对队列项的加载失败同样报致命错误，并且一报错就把**整台**播放器推到
 * `STATE_IDLE`——当前这一集的画面跟着停。不接回来的话，用户看到的是「这一集播到一半黑掉」，
 * 而且会白白消耗当前这一集的降级预算（电视端踩过这个坑，见 `recoverQueuedCurrent`）。
 *
 * @return false = 接不回来（额度用尽或重开失败），调用方要按「当前这一集自己坏了」重新决策。
 */
private fun PhonePlayerActivity.recoverQueuedCurrent(position: Long): Boolean {
    queuedVideo?.let { runCatching { it.close() } }
    queuedVideo = null
    queuedEpisodeId = ""
    PhoneSession.clearNext()
    val key = "$index:${playbackMaxQuality}"
    val used = playbackCoordinator.queuedRecoveryCount(key)
    if (used >= QUEUED_RECOVER_LIMIT) return false
    playbackCoordinator.noteQueuedRecovery(key)
    android.util.Log.i("HGTV-RECOVER", "queued episode failed; reopening current at ${position}ms")
    // 当前这一集的地址 / 索引 / 首块全部命中缓存，重开一次一个 Range 都不发。
    rebuildSameQuality(position)
    return true
}

/** 片尾窗口：与电视端 `queuedNearEnd` 同一个 2 秒。 */
private const val QUEUED_NEAR_END_MS = 2_000L

/**
 * 推荐来源的行为上报。只接受从推荐流带进来的同一部剧，避免把普通详情页播放算进推荐。
 */
internal fun PhonePlayerActivity.reportRecommendEvent(
    event: RecommendBehavior.Event,
    positionMs: Long = -1L,
    durationMs: Long = -1L,
    reason: String = "",
    episodeIndexOverride: Int? = null,
) {
    val series=PhoneSession.recommendSeries ?: return
    val data=PhoneSession.detail ?: return
    if(PhoneSession.feedKey.isEmpty() || series.id!=data.series.id) return
    val episodeIndex=episodeIndexOverride ?: PhoneSession.index
    val episodeId=data.episodes.getOrNull(episodeIndex).orEmpty()
    reportRecommendBehavior(
        context=this,
        event=event,
        seriesId=series.id,
        episodeId=episodeId,
        episodeIndex=episodeIndex,
        positionMs=positionMs,
        durationMs=durationMs,
        reason=reason,
    )
}

/** 队列项接回当前集的最多次数（按「集 + 上限」计）。 */
private const val QUEUED_RECOVER_LIMIT = 1

/**
 * 按计划重来一次：**参数只改一个维度**（同档重建 / 降一档），断点续播。
 *
 * 位置直接问播放器：出错时 `currentPosition` 仍然是失败那一刻的位置，
 * 拿界面上的读数会退回到上一次 tick（最多差一秒），读起来像「降级一次就跳回去了」。
 */
internal fun PhonePlayerActivity.replayWithPlan(plan: PlaybackRetryPlan) {
    val live = runCatching { player?.currentPosition ?: -1L }.getOrDefault(-1L)
    val at = (if (live > 0) live else 0L).coerceAtLeast(0)
    playbackCoordinator.applyRetryPlan(plan)
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
    playbackCoordinator.retry()
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

/**
 * 手动 / 自动重试前把失败那一路的地址与索引一起作废。
 *
 * 只换 `device_id` 不够：`stream()` 会先命中内存或落盘 URL 缓存，根本没有机会用新身份
 * 重新取地址，于是每次重试都原样复用同一条失败地址。这里同时清掉预取槽，避免又接回
 * 一份失败前准备好的旧流。
 */
private fun PhonePlayerActivity.prepareFreshRetrySource() {
    val episodeId = detail?.episodes?.getOrNull(index)
    val staleInfo = remote?.info
    PhoneSession.clearNext()
    if (episodeId != null) {
        playbackSource.invalidate(episodeId, staleInfo)
    } else {
        runCatching { PhoneServices.repository(this).rotateDeviceForRetry() }
    }
    resetSourceRetryForFreshAttempt()
}

/**
 * 排一次自动重试。
 *
 * **与电视端 `scheduleRecovery` 逐条对齐**：
 *
 * * 重试时**换设备号并作废旧地址 / 索引**，不是拿同一份媒体源重开；
 * * 额度在**重试真正跑起来那一刻**才扣（`consumeAttempt`），用户中途点「停止」不白烧；
 * * 倒计时写在错误页上（[PhonePlayerActivity.autoRetryLabel]），与电视端状态行同一个语义。
 */
internal fun PhonePlayerActivity.scheduleAutoRetry(waitMs: Long) {
    cancelAutoRetry()
    recovering = true
    retryJob = scope.launch {
        var left = (waitMs / 1000).coerceAtLeast(1)
        while (left > 0) {
            autoRetryLabel?.text = "自动重试：${left} 秒后 · ${playbackCoordinator.autoRetryAttempts + 1}/3"
            delay(1000)
            left--
        }
        if (isFinishing || isDestroyed) return@launch
        retryJob = null
        if (!playbackCoordinator.consumeAutoRetry()) {
            autoRetryLabel?.text = "已自动重试 3 次，仍无法播放。可手动重试或切换清晰度。"
            return@launch
        }
        autoRetryLabel?.text = "正在重试…"
        // 地址缓存必须先作废，否则重试会命中旧 URL，三次重试只是重放同一条失败流。
        prepareFreshRetrySource()
        val at = runCatching { player?.currentPosition ?: 0L }.getOrDefault(0L).coerceAtLeast(0)
        play(index, at)
    }
}

internal fun PhonePlayerActivity.cancelAutoRetry() {
    retryJob?.cancel()
    retryJob = null
}

/** 换集时取消正在取地址 / 准备媒体源的起播请求，并取消其网络调用。 */
internal fun PhonePlayerActivity.cancelPlaybackRequest() {
    playRequestJob?.cancel()
    playRequestJob = null
    playRequestScope?.cancel()
    playRequestScope = null
}

/** 取地址失败后那段 3 / 6 秒延迟重试，换集后不再执行。 */
internal fun PhonePlayerActivity.cancelSourceRetry() {
    sourceRetryRunnable?.let { root.removeCallbacks(it) }
    sourceRetryRunnable = null
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
internal fun PhonePlayerActivity.showPlaybackError(
    attr: FailureAttribution,
    reason: GiveUpReason?,
    /**
     * 非空 = 错误页显示的同时**排一次自动重试**，这一行就是倒计时读数。
     *
     * 与电视端完全一致：电视也是「错误页 + 状态行倒计时 + 停止自动重试」，而不是
     * 「先默默重试三次、失败了才给用户看」。用户能看见还要等多久，也能主动停掉。
     */
    autoRetryDelayMs: Long? = null,
) {
    reportRecommendEvent(
        RecommendBehavior.Event.ERROR,
        positionMs=runCatching { player?.currentPosition ?: -1L }.getOrDefault(-1L),
        durationMs=runCatching { player?.duration ?: -1L }.getOrDefault(-1L),
        reason=reason?.name ?: failureStage,
    )
    android.util.Log.i("HGTV-RECOVER", "error card reason=$reason hint=${playbackCoordinator.manualHint}")
    cancelAutoRetry()
    recovering = false
    errorAttr = attr
    player?.pause()
    syncPlayGlyph()
    clearPlaybackError()

    // 正文、能力判据、换档目标**全部取自 `:core` 的那一份**（与电视端同一个函数）：
    // 同一个故障在两端必须是同一句话、同一套按钮。只有排版归本页（一张居中卡片）。
    val hevcSupported = PlaybackCapability.cachedHardwareHevc() != false
    val facts = FailureFacts(
        errorCode = attr.errorCode,
        stage = failureStage,
        media = attr.media,
        retryable = false,
        noHardwareDecoder = attr.media == MediaKind.VIDEO &&
            attr.currentMode == VideoDecoderMode.HARDWARE && !hevcSupported,
    )
    val caps = FailurePresentation.capabilities(facts, attr, hevcSupported)
    val softTarget = FailurePresentation.softwareTarget(attr)
    val swapTarget = FailurePresentation.swapTarget(attr)

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
    // 正文**与电视端同一句话**（`FailurePresentation.message`）：同一个故障在两端读起来
    // 必须是同一个问题，否则用户照着电视上的说法描述、手机上却看到另一句。
    val message = buildString {
        append(
            FailurePresentation.message(
                facts = facts,
                manualHint = playbackCoordinator.manualHint,
                hevcSupported = hevcSupported,
            )
        )
        // 附一行「已经试过什么」：电视端写在日志里，手机上直接给出来——
        // 用户报「老是降清晰度」时，这一行能说明预算到底有没有生效。
        if (reason != null) append("\n\n已自动尝试：${playbackCoordinator.budgetSummary()}")
    }
    column.addView(TextView(this).apply {
        text = message
        setTextColor(PhoneStyle.textDim)
        phoneSize(12)
        setPadding(0, dp(8), 0, dp(12))
    })

    // 自动重试的倒计时读数（与电视端状态行同一个语义）。
    if (autoRetryDelayMs != null) {
        val label = TextView(this).apply {
            setTextColor(PhoneStyle.textFaint)
            phoneSize(11)
            setPadding(0, 0, 0, dp(10))
        }
        autoRetryLabel = label
        column.addView(label)
    }

    // 按钮行：主按钮（立即重试）独占一行，其余两颗并排，最下面「下一集」。
    column.addView(errorButton("立即重试", primary = true) {
        clearPlaybackError()
        // 归还本集的额度（策略那边有次数上限，连点第三次不再归还）。
        playbackCoordinator.onManualRetry()
        // 同时作废旧地址、旧索引与预取槽，确保这一下真的重新取流。
        prepareFreshRetrySource()
        val at = runCatching { player?.currentPosition ?: 0L }.getOrDefault(0L).coerceAtLeast(0)
        play(index, at)
    }, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, dp(42)
    ).apply { topMargin = dp(4) })

    val second = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    if (FailureCapability.SWITCH_SOFTWARE in caps) {
        second.addView(errorButton("切换为软解") {
            library?.decoderMode = VideoDecoderMode.SOFTWARE
            playbackCoordinator.onManualDecoderChange()
            clearPlaybackError()
            val at = runCatching { player?.currentPosition ?: 0L }.getOrDefault(0L).coerceAtLeast(0)
            rebuildSameQuality(at)
        }, LinearLayout.LayoutParams(0, dp(40), 1f))
    }
    if (FailureCapability.CHANGE_QUALITY in caps) {
        second.addView(errorButton("换到 ${swapTarget}P") {
            library?.maxQuality = PlaybackQuality.normalize(swapTarget)
            playbackCoordinator.onManualQualityChange()
            clearPlaybackError()
            // 新档位也必须重新取流，不能命中旧档位留下的地址或索引。
            prepareFreshRetrySource()
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

    // 「停止自动重试」：与电视端同一颗出口。排着重试却关不掉，用户只能干等。
    if (autoRetryDelayMs != null) {
        column.addView(errorButton("停止自动重试") {
            cancelAutoRetry()
            autoRetryLabel?.text = "自动重试已停止，观看位置已保留。"
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
    // 错误页摆好之后才排重试：倒计时那一行是卡片里的控件，先有卡片才写得上读数。
    if (autoRetryDelayMs != null) scheduleAutoRetry(autoRetryDelayMs)
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
    autoRetryLabel = null
    setBufferingLabel(null)
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
    if (playbackCoordinator.pictureRecoveryCoolingDown(now, 20_000L)) return
    if (playbackCoordinator.pictureRecoveryCount >= 1) {
        // 已经重建过一次还是没画面：如实告诉用户，别让它无限重建。
        toast(if (reason == "stall_buffer") "这一集加载不动，可以换一集" else "这一集画面出不来，可以换一集")
        return
    }
    playbackCoordinator.notePictureRecovery(now)
    android.util.Log.i("HGTV-PHONE", "picture lost ($reason), rebuilding once")
    val at = runCatching { player?.currentPosition ?: 0L }.getOrDefault(0L).coerceAtLeast(0)
    rebuildSameQuality(at)
    // 重建之后重新装一次看门狗：这一次还不行就该报错 / 提示了。
    root.postDelayed({ if (!isFinishing && !isDestroyed) armPictureWatchdog() }, 800)
}
