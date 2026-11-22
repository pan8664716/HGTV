// SPDX-License-Identifier: GPL-3.0-only
@file:JvmName("MainActivityPlaybackKt")
@file:JvmMultifileClass
package com.hgtv.star.tv

import com.hgtv.star.shared.ui.UiText
import com.hgtv.star.shared.compat.hasInternet
import com.hgtv.star.shared.compat.isMeteredConnection

import android.app.Activity
import android.view.*
import android.widget.*
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.mediacodec.MediaCodecRenderer
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.ui.PlayerView
import com.hgtv.star.core.*
import com.hgtv.star.shared.data.WatchProgress
import com.hgtv.star.shared.data.PlaybackCapability
import com.hgtv.star.shared.data.AppRuntime
import com.hgtv.star.shared.media.VideoDataSource
import com.hgtv.star.shared.compat.setVectorIcon
import com.hgtv.star.tv.player.PlaybackMenu
import com.hgtv.star.shared.media.MediaSessionHost
import com.hgtv.star.tv.TvDialog
import com.hgtv.star.shared.compat.attachedToWindow
import com.hgtv.star.shared.architecture.network
import com.hgtv.star.shared.debug.NetSpeed
import com.hgtv.star.tv.R
fun TvMainActivity.networkAvailable(): Boolean = connectivity.hasInternet()
/**
 * 网络刚恢复，而起播正卡在「正在获取播放地址…」：把在途那一发作废，按原位置立刻重取。
 *
 * ## 为什么必须由网络回调来推一把
 *
 * 取地址跑在 [work] 的 io 线程上，卡住时界面上一个字都不变，唯一的出口是那一发自己超时。
 * 现场（2026-10-30，小米 TV，1.2.9.1(67)）：**断网 → 按下一集 → 两秒后联网**，
 * 界面停在「正在获取播放地址…」整整 **49.6 秒**——
 * `net POST … failed after 45001ms / InterruptedIOException: timeout`，
 * 而网络其实在第 2 秒就回来了。用户的感受是「网络已经好了，它还在转圈」。
 *
 * 现在有两道闸：
 * 1. 取地址每条实际链接 20 秒超时（见 `ContentRepository.ADDRESS_ATTEMPT_TIMEOUT_MS`）——**兜底**；
 * 2. 这一条：网络恢复的那一刻就把在途那一发作废重来——**不再等兜底**。
 *
 * ## 作废为什么用 `generation++` + [cancelWork]
 *
 * [work] 的两个回调都按提交那一刻的 `generation` 丢弃结果，因此旧那一发晚回来的
 * 成功 / 失败都不会再落到界面上。失败尤其不能落：那会把一次「用户什么都没做」的等待
 * 记成一次起播失败，白白烧掉一次自动重试额度（3 次用完就只剩错误页）。
 *
 * 旧那一发的 `finally` 也不会把 [playbackFetching] 抹掉（那里比的是
 * [playbackFetchSeq]，新一发已经把它推走了），所以这一发重取期间下一集预热仍然让路。
 */
fun TvMainActivity.refetchAddressOnNetwork() {
    if(isDestroyed || screen!="player" || !playbackFetching || playError) return
    if(detail==null || !networkAvailable()) return
    android.util.Log.i(
        "HGTV",
        "Network restored while fetching address; refetching episode=${episodeIndex+1} at=${startPosition}ms"
    )
    generation++; cancelWork()
    playEpisode(episodeIndex,startPosition.coerceAtLeast(0),requestedAutoplay,direct=false)
}
fun TvMainActivity.currentPosition(): Long = pendingSeek ?: player?.currentPosition?.takeIf { playbackReady } ?: startPosition
fun TvMainActivity.cancelRecovery() {
    main.removeCallbacks(retryRunnable)
    retryPending=false
    autoRecovery=false
    retryEpisodeId=""
}
fun TvMainActivity.scheduleRecovery() {
    if(!foreground || !playError || !autoRecovery || retryPending) return
    if(!playbackCoordinator.autoRetryAvailable) { autoRecovery=false; playbackText.text=UiText.get(com.hgtv.star.shared.R.string.ui_0082); return }
    if(!networkAvailable()) { playbackText.text=UiText.get(com.hgtv.star.shared.R.string.ui_0206, episodeIndex+1, formatTime(retryPosition)); return }
    val delay=playbackCoordinator.autoRetryDelayMs ?: return
    playbackText.text=UiText.get(com.hgtv.star.shared.R.string.ui_0012, if(retryAutoplay) UiText.get(com.hgtv.star.shared.R.string.ui_0301) else UiText.get(com.hgtv.star.shared.R.string.ui_0302), delay/1000f, playbackCoordinator.autoRetryAttempts+1, formatTime(retryPosition))
    retryEpisodeId=detail?.episodes?.getOrNull(episodeIndex).orEmpty()
    retryPending=true; main.postDelayed(retryRunnable,delay)
}
/**
 * 这次失败落在**哪条轨**上。
 *
 * 判断本体已经收进 `:shared` 的 [com.hgtv.star.shared.media.PlaybackFailures]：
 * 手机端也要用同一份（音频失败不该被当成「视频能力不足」去降清晰度），
 * 两端各写一份迟早会分叉。这里保留同名函数只是为了不动调用点。
 */
fun playbackMediaKind(problem: Throwable?): MediaKind =
    com.hgtv.star.shared.media.PlaybackFailures.mediaKind(problem)


/**
 * 把这一次失败整理成策略能吃的归因。
 *
 * 档位与编码来自**这一次真正取到的流**（[com.hgtv.star.core.StreamInfo]），不是用户设置：
 * 用户设 1080、CDN 只给 480 时，失败的其实是 480 那条，降档目标也只能从 480 往下找。
 *
 * [failedQueued]：这一次失败属于**队列里那一集**（片尾预热挂进去的下一集）。那时
 * [video] 是当前这一集的流，用它会得出错误的 `currentQuality` 与 `available`，
 * 于是降档从错误的一档开始、还可能把「当前这一档其实没问题」的结论压进会话层。
 * 判据与 [playerFailure] 里那段队列分支同源，调用方在 `dropQueuedEpisode()` **之前**
 * 把那一份 `StreamInfo` 抄出来传进来。
 */
fun TvMainActivity.playbackAttribution(
    problem: Throwable?,
    code: Int,
    failedQueued: StreamInfo? = null
): FailureAttribution {
    val info=failedQueued ?: video?.info ?: queuedVideo?.info
    // 归因本体在 `:shared`（手机端同一份）：档位来自这一次真正取到的流，不是用户设置；
    // 解码方式用**实际生效**的那一档（用户可能自己点过「软件解码」）。
    return com.hgtv.star.shared.media.PlaybackFailures.attribution(
        problem=problem,
        code=code,
        info=info,
        fallbackQuality=playbackMaxQuality,
        mode=playerDecoderMode(),
    )
}

/**
 * 用户主动发起的一次**全新尝试**：把按集计的全部额度与账本一起还给这一集。
 *
 * 两半都要还，而且必须**同时**还：
 *
 * * `SessionPlaybackPolicy` 管自动降级（同档重建一次 / 降档两次），
 *   它自己带「归还次数」上限（`MANUAL_RETRY_LIMIT`）；
 * * `PlaybackAttemptLedger` 管自动重试 3 次 / 画面重建一次 / 队列项重开两次，
 *   这三份过去归零条件都挂在 `recovering=false` 上，而错误页那几颗按钮都带
 *   `recovering=true`，所以它们从来不会被还——「立即重试」点下去只是闪回同一句提示。
 *
 * 归还必须是**一次性的**：策略那边拒绝归还（连点第三次）时，这里整体停下。
 * 早先只挡了策略那一半，下面三份照样重置，于是连点按钮可以无限批次地重来。
 */
internal fun TvMainActivity.resetBudgetsForManualRetry() {
    playbackCoordinator.onManualRetry()
}

/**
 * 重试前把这一集所有清晰度的地址与当前索引一起作废。
 *
 * 只换 `device_id` 不会绕过 `stream()` 的内存 / 落盘 URL 缓存，重试仍会拿到同一条
 * 失败地址；预取槽也要清掉，避免又接回失败前准备好的旧流。
 */
internal fun TvMainActivity.prepareFreshPlaybackRetry() {
    val episodeId=detail?.episodes?.getOrNull(episodeIndex)
    val staleInfo=video?.info
    cancelPrefetch()
    if(episodeId!=null) {
        playbackSource.invalidate(episodeId, staleInfo)
    } else {
        runCatching { repository.invalidatePlaybackCacheForRetry() }
    }
}
/**
 * 从这一集切到**另一集**去重来（片尾没接上 / 队首那一集坏了）。
 *
 * `recovering=true` 的语义只有一条：**同一集的同一次起播重来**，因此它刻意不去清
 * 按集计的额度——那正是「自动降级不会无限循环」的前提。可一旦目标集换了，
 * 这几份不按集做键的账本（自动重试 / 画面重建 / 手动建议 / 队列重开）就全部张冠李戴：
 * 新一集开播时没有任何自动重试机会、画面一停就直接落错误页，错误页上还顶着
 * **上一集**的「请切换为软件解码」。
 *
 * 档位上限（[SessionPlaybackPolicy.effectiveMaxQuality]）**照旧沿用**：那是
 * 「这台机器放不了这一档」的结论，与具体哪一集无关。
 */
private fun TvMainActivity.restartAnotherEpisode(index: Int,at: Long,autoplay: Boolean) {
    // 按**新一集**重来一遍预算；连播链的失败计数不清，避免无限自动推进。
    detail?.episodes?.getOrNull(index)?.let { playbackCoordinator.onEpisodeSwitch(it) }
    playEpisode(index,at,autoplay,recovering=true)
}
/**
 * 执行一次降级重试：**按计划重新起播 + 断点续播**，参数由 [PlaybackRetryPlan] 决定。
 *
 * 两种计划，都只改一件事：
 *
 * * [DegradationKind.SAME_QUALITY_REBUILD]（`maxQuality==null`）：什么参数都不改，只是把
 *   解码器放掉重开。走的是 `keepMedia=true` 那条窄路——**播放层不重建、这一集的
 *   `RemoteVideo` 原地留着**，所以地址 / 索引 / 首块都不重取（一次 Range 都不发，
 *   也不重读盘），只剩下「放掉解码器 + 首帧」这一段；
 *   早先它也走 `recovering=true` 的整条重建：摘掉整个播放层重新 inflate、再走一遍取地址
 *   （命中缓存）与 `prepare()`（读盘上的索引 + 首块）——为「重开一颗解码器」付了整页的钱。
 * * [DegradationKind.QUALITY_DOWN]：换一档上限，自然换一条地址（新档位的缓存键本来就是空的）。
 *
 * 「切软解」不在这条路上：那是用户点按钮 → [applyDecoderMode]。
 *
 * 现场先收干净再重来：看门狗、自动重试、预取、队列项都作废——它们都是按**上一次起播**排的，
 * 留着只会在新一次起播时抢解码器或往旧目标上推进。
 */
fun TvMainActivity.replayWithPlan(plan: PlaybackRetryPlan) {
    // 断点位置直接问播放器：出错时 `playbackReady` 可能已被清掉，[currentPosition] 会退回
    // [startPosition]（本集开头），表现为「降级一次就从头播」。播放器在错误态下
    // `currentPosition` 仍然是有效的（它就是失败那一刻的位置），取它更准。
    val live=runCatching { player?.currentPosition ?: -1L }.getOrDefault(-1L)
    val at=(if(live>0) live else currentPosition()).coerceAtLeast(0)
    val play=requestedAutoplay && !sleepStopped
    // 上限先落进会话层，再记留痕：`noteDegrade` 会打出 `cap=`，那一刻读到的必须已经是**新的**上限。
    playbackCoordinator.applyRetryPlan(plan)
    // 提示一次就够：同一个 (集, 理由, 目标档位) 在一次播放里只弹一条（见 `ToastDeduper`）。
    //
    // 键里**必须**带目标档位：1080 → 720 与 720 → 480 是同一次播放里连着发生的两次降档，
    // 早先两处共用一个「集 + 理由」的键，第二次被去重吞掉——用户眼前是「已降为 720P」，
    // 实际放的是 480，于是「清晰度自己变低了」这条反馈永远查不出来。
    plan.toast?.let { text ->
        val key="${detail?.episodes?.getOrNull(episodeIndex) ?: episodeIndex}:${plan.reason}:${plan.maxQuality ?: 0}"
        if(playbackToasts.shown(key)) runCatching { tvTools.toast(text) }
    }
    // 降级本身必须留痕（见 [noteDegrade]）：本集被标成「这一次起播失败过」，于是真的出画面时
    // `PlaybackDiag.takeRecovered()` 能记下一条「救回来了」的正样本。
    // 早先这条路直接 return、什么标记都不留，于是「降级救回率」永远是 0 条样本——
    // `QUALITY_DOWN_LIMIT` 够不够、`softwareQuality` 的 720 是保守还是过头，全都无从校准。
    noteDegrade(plan)
    disarmPlaybackWatchdog(); disarmFrameStallWatchdog()
    cancelRecovery(); cancelPrefetch(); dropQueuedEpisode()
    restoreBufferingUi(); retirePreviousEpisode()
    // 降档**不**去 `forgetStream`：地址按「集 + 上限 + device_level」做键，上限一变就是
    // 另一个键、必然重取一条新地址，旧那条留着下次切回高档还能用。
    // 早先这里无条件清一次，清的是**新**上限那个（原本就空的）键，什么也没删掉，
    // 代价却是 `forgetStream` 尾部把 `video_model` 缓存一起打掉——
    // 白付一次 500~1100ms 的 POST，而坏的东西明明是本机解码器，与身份无关。
    // 同档重建走窄路（`keepMedia`）：视图与这一集的 `RemoteVideo` 原地留着，
    // 只 `stop()` 放掉解码器。降档必须回到整条重建——上限变了就是另一条地址、
    // 另一份索引，留着的这一份用不上。
    playEpisode(episodeIndex,at,play,recovering=true,keepMedia=plan.maxQuality==null)
}

fun TvMainActivity.playerFailure(problem: Throwable) {
    // 同一次片尾故障可能先来 onPlayerError、再来 STATE_ENDED（或反过来）。
    // 两条路都去「切下一集」就是重复切换：同一会话 1.5 秒内只处理第一条。
    // 判据带会话号，所以新一集自己的错误不会被这条规则误吞。
    //
    // 但「时间窗」本身不足以证明这是同一条：万一将来有一条路在原地重试而不换会话，
    // 第二条**真实**失败会被静默丢掉，而那时 `playbackReady` 仍是 true、两条看门狗都不动
    // ——就是永久冻结。因此再加一道：只有播放器确实已经停了（READY / BUFFERING 之外的
    // 任何状态）才允许吞；真的还在播就按新故障处理。
    val now=android.os.SystemClock.elapsedRealtime()
    val stillPlaying=player?.let {
        it.playbackState==Player.STATE_READY || it.playbackState==Player.STATE_BUFFERING
    }==true
    if(failureHandledSession==playbackSession && now-failureHandledAt in 0..1500 && !stillPlaying) {
        android.util.Log.i("HGTV","Duplicate playback failure ignored session=$playbackSession")
        return
    }
    failureHandledSession=playbackSession; failureHandledAt=now
    // 同一件事的解码器报错可能还挂在 1.5 秒的等待里（见 [playbackDiagnostics] 的
    // `onVideoCodecError`）：这条已经带着完整 cause 链报了，那条不必再来一遍。
    pendingCodecReport?.let { main.removeCallbacks(it) }; pendingCodecReport=null
    // 上报放在互斥窗之后、任何重试分支之前：同一次故障只报一条，且「自动重试成功」
    // 也不会让这条记录消失——用户看到过的那次失败是真的发生过。
    // —— 判定：**与手机端同一个函数**（`PlaybackFailurePolicy.decide`）——
    //
    // 这一段以前是电视端自己的一套 if/else（地址自愈 → 推荐页 → 队列项 → 解码降级 → 错误页），
    // 手机端另写了一套、少了前三段，于是同一个故障在电视上能自己救回来、在手机上直接死在
    // 黑屏上。现在「怎么办」只留在 `:core` 那一份规则里，两端都调它；
    // 各端只负责**读事实**（[FailureFacts]）与**执行**，界面各自画。
    //
    // 下面每一条分支的**执行**仍是电视端原来的写法，一个像素都没改——换的只是
    // 「什么时候走哪一条」这个判断。
    val decodeCode=(problem as? PlaybackException)?.errorCode ?: 0
    // 队列那一集出错时要拿**它的**档位表去归因（`video` 是当前这一集）。
    // 必须在 `dropQueuedEpisode()` 之前抄走，错误页的按钮也要用它。
    val failedQueuedInfo=queuedVideo?.info
    val queuedTargetDetail=queuedDetail
    val attr=playbackAttribution(problem,decodeCode,failedQueuedInfo)
    val failureMedia=playbackMediaKind(problem)
    val queuedDuration=player?.duration?.coerceAtLeast(0) ?: 0
    // 队列项是在**片尾窗口内**报的错（= 下一集真的没接上），还是窗口外
    //（= 当前这一集还在正常播，坏的只是后台预加载的那一条）。
    val queuedNearEnd=queuedEpisodeId!=null && queuedDuration>0 &&
        (player?.currentPosition ?: 0)>=queuedDuration-2_000
    val facts=FailureFacts(
        errorCode=decodeCode,
        stage=playbackStage(problem),
        media=failureMedia,
        retryable=com.hgtv.star.shared.media.PlaybackFailures.isRetryable(problem),
        streamExpired=streamExpiredOf(problem),
        videoChanged=videoChangedOf(problem),
        hostUnresolved=hasCauseOf<java.net.UnknownHostException>(problem),
        transportDead=transportDeadOf(problem),
        queued=queuedEpisodeId!=null,
        queuedNearEnd=queuedNearEnd,
        noHardwareDecoder=failureMedia==MediaKind.VIDEO &&
            playerDecoderMode()==VideoDecoderMode.HARDWARE && !hardwareHevcAvailable,
        autoRetryAllowed=!sleepStopped,
        onRecommend=screen=="recommend",
    )
    if(screen=="recommend" || playbackFromRecommend) {
        val data=detail
        if(data!=null) {
            val failedEpisode=if(facts.queued) queuedEpisodeId.orEmpty()
            else data.episodes.getOrNull(episodeIndex).orEmpty()
            recommendPlaybackController.reportEvent(
                data.series,
                com.hgtv.star.core.RecommendBehavior.Event.ERROR,
                episodeId=failedEpisode,
                episodeIndex=if(facts.queued) -1 else episodeIndex,
                positionMs=runCatching { player?.currentPosition ?: -1L }.getOrDefault(-1L),
                durationMs=runCatching { player?.duration ?: -1L }.getOrDefault(-1L),
                reason=facts.stage,
            )
        }
    }
    // 队列项接不回来时要按「当前这一集自己坏了」再判一次：决策函数是纯的，重入安全。
    fun decideFailure(queued: Boolean)=playbackCoordinator.decideFailure(
        facts.copy(queued=queued),attr,recommendPlaybackFailures
    )
    var failureAction=decideFailure(facts.queued)
    if(failureAction==FailureAction.RecoverQueuedCurrent) {
        // 队列里那一集坏了、当前这一集还在正常播：原地把当前集接回来。
        // ExoPlayer 对队列项的加载失败同样报致命错误，并且一报错就把**整台**播放器推到
        // `STATE_IDLE`——不接回来，用户看到的是「这一集播到一半黑掉」。
        dropQueuedEpisode(); restoreBufferingUi(); retirePreviousEpisode()
        if(recoverQueuedCurrent()) return
        android.util.Log.w("HGTV","Queued episode failed; giving up automatic advance")
        failureAction=decideFailure(false)
    }
    // 自愈路径（`RefetchStream`）救回来就不该记一条故障，否则诊断面板里会出现
    // 「刚报错、其实画面正常」的记录——因此上报放在它之后。
    if(failureAction !is FailureAction.RefetchStream) {
        disarmPlaybackWatchdog(); disarmFrameStallWatchdog()
        reportPlaybackFailure(problem,playbackStage(problem))
    }
    // 放弃自动处置时**为什么**放弃：错误页据此决定给哪几颗按钮。
    var giveUp: GiveUpReason?=null
    when(failureAction) {
        is FailureAction.RefetchStream -> {
            // 落盘地址过期（403 / 404）/ 文件换过 / 域名解析不出来 / 连接层假死：
            // **换一条新地址原地重来一次**，不给用户看错误页。
            //
            // 缓存 20 分钟的地址有可能被服务端提前作废（签名时效、CDN 换线），而这是
            // 「回落到盘上以后才可能出现」的新失败点，必须自己救回来。
            val failingEpisode=queuedEpisodeId ?: detail?.episodes?.getOrNull(episodeIndex)
            if(failingEpisode!=null) {
                val why=when {
                    facts.hostUnresolved -> "Video host unresolved; refetching url"
                    facts.transportDead -> "Transport dead; refetching url, dns & pool"
                    facts.videoChanged -> "Video changed; refetching url & index"
                    facts.preloadFailure -> "Queued preload failed; refetching url (budget kept)"
                    else -> "Stream expired; refetching url"
                }
                android.util.Log.w("HGTV","$why episode=${episodeIndex+1}")
                // 这一集的地址不认了。**按集整体作废，而不是按精确键**：
                //
                // 精确键是 `集:上限:device_level`，而这条地址当初是按**预取那一刻**的上限与
                // 档位取回来的。从取地址到失败之间，降档、重建、换解码都可能把上限改过，
                // `device_level` 也可能变过——键对不上时 [forgetStream] 等于什么都没删，
                // 重来时会原样命中同一条死地址，第二次必然还是 403。
                //
                // 多删几条的代价只是下一次多一次网络往返（500~1100ms），
                // 而删错对象的代价是用户看到错误页。
                // 索引与首块也是按「这一集 + 这一档」落盘的，文件换过之后它们一起作废。
                //
                // 键必须用**实际**那一份（`StreamInfo.mediaKey`：集 + 实际档位）。
                // 早先这里拼的是 `"$集:$上限"`，而索引键早就改成带实际档位了——
                // 拼错等于什么也没删，下一次起播仍然读那份对不上新文件的旧索引。
                val staleInfo=if(queuedEpisodeId!=null) queuedVideo?.info ?: video?.info else video?.info
                playbackSource.invalidate(failingEpisode, staleInfo)
                // 地址之外的那两层：视频域名的解析结果、连接池里的空闲连接。
                if(failureAction.dropTransport) dropDeadTransport()
                val at=currentPosition().coerceAtLeast(0)
                disarmPlaybackWatchdog(); disarmFrameStallWatchdog()
                cancelRecovery(); cancelPrefetch(); dropQueuedEpisode()
                restoreBufferingUi(); retirePreviousEpisode()
                if(queuedTargetDetail!=null && failureAction.fromStart) {
                    main.post { if(!isDestroyed) enterNextSeason(queuedTargetDetail) }
                    return
                }
                // 队首那一集失败：从它的开头重来，而不是重放当前这一集（见 `queuedNearEnd`）。
                val retryIndex=if(failureAction.fromStart) episodeIndex+failureAction.episodeOffset else episodeIndex
                val retryAt=if(failureAction.fromStart) 0L else at
                if(retryIndex in (detail?.episodes?.indices ?: IntRange.EMPTY)) {
                    // 换了集就走 [restartAnotherEpisode]：按集计的额度必须跟着换，否则新一集
                    // 会继承上一集的自动重试 / 画面重建次数，一个都不会再有。
                    main.post { if(!isDestroyed) restartAnotherEpisode(retryIndex,retryAt,requestedAutoplay) }
                    return
                }
                main.post { if(!isDestroyed) playEpisode(episodeIndex,at,requestedAutoplay,recovering=true) }
                return
            }
        }
        FailureAction.SkipRecommend, FailureAction.GiveUpRecommend -> {
            // 推荐页出错：不摆播放页那套「重试 / 返回选集」的错误界面（那里没有选集、
            // 也没有详情页可回），直接换下一部。连换几部都放不了才停在一句提示上。
            val failed=detail?.series?.title ?: recommendItems.getOrNull(recommendIndex)?.title
            // 已经出过画面就不是「这一部放不了」，而是播放中断。此时继续自动换剧会把
            // 用户刚看到的作品直接划走，甚至连续跳过多部；停在当前作品让用户决定重试。
            if(recommendPlaybackHasFrame || pictureUpNow()) {
                recommendPlaybackFailures=0
                player?.pause()
                showRecommendMessage("${if(failed!=null) "《$failed》" else "当前作品"}播放中断\n按确定键重试，按返回键回主页面")
                return
            }
            recommendPlaybackFailures++
            if(failureAction==FailureAction.GiveUpRecommend) {
                // 连续几部都放不了：把这一层收干净（画面 / 传输 / 弹幕），只留一句提示。
                // 不写历史（推荐页本来就不写），也不改 `screen`——返回键仍然是回主页面。
                player?.pause()
                showRecommendMessage("${if(failed!=null) "《$failed》" else "这一部"}暂时无法播放\n按确定键重试，按返回键回主页面")
                return
            }
            val next=recommendIndex+1
            when {
                next in recommendItems.indices -> main.post { if(!isDestroyed && screen=="recommend") playRecommendAt(next,0L,1) }
                // 走到末尾：先补一页再从第一部试（这条流到底了，补页那一侧会换一条新流）。
                // 补页请求已经在路上时不能干等（那样这一屏就停在失败的那一集上），直接环绕回第一部。
                else -> main.post {
                    if(isDestroyed || screen!="recommend") return@post
                    if(recommendLoading) {
                        if(recommendItems.isNotEmpty()) playRecommendAt(0,0L,1)
                        else showRecommendMessage("暂时没有可播放的推荐\n按确定键重试，按返回键回主页面")
                    } else {
                        val guard=recommendToken.get()
                        loadRecommendPage(
                            append=true,
                            minItemsPerLoad=recommendItems.size+1,
                        ) {
                            if(recommendToken.get()!=guard) return@loadRecommendPage
                            if(recommendItems.isNotEmpty()) playRecommendAt(0,0L,1)
                            else showRecommendMessage("暂时没有可播放的推荐\n按确定键重试，按返回键回主页面")
                        }
                    }
                }
            }
            return
        }
        FailureAction.RecoverQueuedCurrent -> {
            // 上面已经处理过（接回当前集）；走到这里说明状态已经变了，直接落错误页。
            android.util.Log.w("HGTV","Queued recovery requested but no longer applicable")
        }
        is FailureAction.Replay -> {
            // 解码类：同档重建 / 降一档。决策在 `:core` 的 `PlaybackDegradation` 里。
            replayWithPlan(failureAction.plan)
            return
        }
        is FailureAction.ShowError -> {
            giveUp=failureAction.reason
            // 错误页上要不要排自动重试：决策层已经按 `retryable` / 额度 / 前台状态算过，
            // 这里只负责执行（下面 `if(retryable && !sleepStopped)` 那段仍由本页决定按钮文案）。
        }
    }
    retryPosition=currentPosition().coerceAtLeast(0); retryAutoplay=requestedAutoplay && !sleepStopped
    saveProgress(); cancelRecovery(); cancelPrefetch()
    // 无感切集留下的两个尾巴：缓冲指示要恢复，上一集的视频与传输要收掉（这一集已经出错，不会再出画面了）。
    restoreBufferingUi(); retirePreviousEpisode(); dropQueuedEpisode()
    playError=true; player?.pause(); syncBufferingDots(); progressBar.visibility=View.GONE; hud.visibility=View.VISIBLE; controls.removeAllViews(); controls.visibility=View.VISIBLE; panel=true
    // 停在错误页上不再需要守着屏幕：用户看完提示可能就走开了（见 [syncKeepScreenOn]）。
    syncKeepScreenOn()
    // 顶部那条信息是常驻的，出错时按当前页再刷一遍。
    syncPlaybackInfo()
    dismissPlaybackMenu()
    // 这一次失败落在哪条轨上：下面**所有**文案与按钮都要按它分流。
    // 音频走全表选择器（见 `decoderSelector`），换解码方式与换档位都不作用在它身上，
    // 因此「切换为软解 / 换到 xP」这两颗按钮对音频必须**不出现**——点了只会再撞一次。
    val media=playbackMediaKind(problem)
    val videoProblem=media==MediaKind.VIDEO
    // 值不值得等一会儿再来一次：判据只有一份（`:shared` 的 `PlaybackFailures.isRetryable`），
    // 手机端用的是同一个函数。
    val retryable=com.hgtv.star.shared.media.PlaybackFailures.isRetryable(problem)
    // 解码器压根没起来：这台设备对「能解 HEVC」的声明可能不成立，**重新探一次**。
    //
    // 注意这里**不再**写 `library.maxQuality`（早先是「一失败就压到 480」）：
    // 那是把「这一刻放不了」记成用户的偏好，写进去 `qualityChosenByUser` 就恒为真，
    // 启动时那次「按能力给默认值」永远不再生效，而且 CDN 没有 ≤480 时还会形成
    // 「降档 → 兼容 1080 → 再 4001」的隐式循环（2026-10-28 现场）。
    // 降档由 [PlaybackDegradation] 决策、写进会话层，用户设置只有他自己能动。
    //
    // 也不能在这一条上直接把 `playbackHevcSupported` 判成 false：解码器初始化失败也可能只是
    // 这一刻实例被占满 / 系统忙，一次失败不足以给这台设备定性。重探的结果才是判据。
    if(problem is PlaybackException && problem.errorCode in listOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED) &&
        videoProblem) {
        PlaybackCapability.invalidate(this)
        // 内存里那份「有没有硬解」也要一起作废，否则界面（与手机端）读到的还是旧结论。
        PlaybackCapability.invalidateHardwareHevc()
        io.submit {
            val hevc=PlaybackCapability.canDecodeHevc(this)
            // 重新探一次并灌回内存缓存（判据只有一份）。
            PlaybackCapability.warmHardwareHevc(this)
            val hardware=PlaybackCapability.cachedHardwareHevc() ?: true
            main.post {
                if(isDestroyed) return@post
                // device_level 固定为 4，与解码能力无关，这里不动它。
                playbackHevcSupported=hevc
                hardwareHevcAvailable=hardware
            }
        }
    }
    // 降级决策已经走完也放不了：把「换软解 + 换到哪一档」摆到文案与按钮上，
    // **由用户点**（需求：优先硬解，不支持硬解时提示用户换成软解和支持的清晰度）。
    //
    // 文案**与手机端同一份**（`FailurePresentation.message`）：同一个故障在两端必须是
    // 同一句话，否则用户照着电视上的说法描述问题，手机上看到的却是另一句。
    // 只有排版归本页（控制条里的一行字 + 一排按钮）。
    val manualHint=playbackCoordinator.manualHint
    playbackText.text=FailurePresentation.message(facts,manualHint,playbackHevcSupported)
    // 说明：`giveUp` 只进日志（见本函数末尾那一行），不直接参与这里的文案——
    // 放弃时 `decide` 一定已经写过 `manualHint`，那句带着具体档位，比任何通用文案都准。
    // 按钮的可见性由三件事决定：媒体类型（音频不给视频按钮）、当前解码方式、还有没有更低档。
    val actions=row(); controls.addView(actions)
    // 手动重试会换身份并作废这一集的地址 / 索引缓存，确保真正重新取流。
    addButton(actions,"立即重试") {
        prepareFreshPlaybackRetry()
        // 用户手动重试：归还本集预算（否则决策立刻再返回 null，点一下闪回错误页），
        // 但**不**撤销已经降下来的档位——那是既成事实，倒退只会再撞一次。
        // 归还次数有上限（见 `MANUAL_RETRY_LIMIT`），因此下面必须带 `recovering=true`：
        // 那一位才保证 `onEpisodeStart` 不去重置预算与归还次数，否则连点就成了无限次降档。
        //
        // 还款必须**一并还自动重试那 3 次**（见 [resetBudgetsForManualRetry]）：那是另一套
        // 额度，归零条件同样挂在 `recovering=false` 上。少了这一半，自动重试一旦用尽，
        // 用户点这颗按钮之后 `scheduleRecovery()` 第一句就返回，从此再也不会有任何自动重试
        // ——手动重试反而把这一集的重试永久关掉了。
        resetBudgetsForManualRetry()
        playEpisode(episodeIndex,retryPosition,retryAutoplay,recovering=true)
    }.requestFocus()
    // 下面两个按钮的判据取**这一次真正失败的那条流**（`failedQueuedInfo` 优先）：
    // 队列那一集失败时 `video` 是当前这一集，用它会让按钮作用错一集——文案说「480P 放不了」，
    // 点下去换的却是当前集的档。与 [playbackAttribution] 同源。
    // 按钮的**能力判据**与手机端同一份（`FailurePresentation.capabilities`）：
    // 只对视频给「换软解 / 换档位」，音频走全表选择器，那两颗对音频完全无效。
    // 换档目标也必须**严格低于刚刚失败的那一档**（判据在 `FailurePresentation.swapTarget`），
    // 否则降到 480 失败后按钮仍写「换到 720P」，点下去把清晰度抬回去、必然复现同一类失败。
    val caps=FailurePresentation.capabilities(facts,attr,playbackHevcSupported)
    // 硬解失败：给一个「切换为软解」的按钮，并**同时**把档位降到软解跑得动的那一档
    //（只换解码器不降档，1080 软解在盒子上照样是掉帧 / 卡死，等于白换一次）。
    //
    // 上限取**当前这一档**：换解码器允许停在原档（480 软解照放），但绝不能把已经降下来的
    // 档位抬回去——那是「这台机器这一刻放不了」的既成结论，与 `SessionPlaybackPolicy`
    // 里「不倒退」的口径一致。
    val softTarget=FailurePresentation.softwareTarget(attr)
    if(PlaybackFailureCapability.SWITCH_SOFTWARE in caps) addButton(actions,"切换为软解") {
        library.decoderMode=VideoDecoderMode.SOFTWARE
        playbackCoordinator.onManualDecoderChange(softTarget)
        applyDecoderMode(retryPosition,retryAutoplay)
    }
    val swapTarget=FailurePresentation.swapTarget(attr)
    if(PlaybackFailureCapability.CHANGE_QUALITY in caps) addButton(actions,"换到 ${swapTarget}P") {
        prepareFreshPlaybackRetry()
        // 同「立即重试」：这是用户主动发起的新一轮尝试，自动重试 / 画面重建的额度一起还。
        // 档位在还款之后才落进会话层——`onManualRetry` 不动 `effectiveMaxQuality`，
        // 顺序反过来也能工作，但先还账再定档读起来才是「先清预算、再设目标」。
        resetBudgetsForManualRetry()
        playbackCoordinator.setMaxQuality(swapTarget)
        playEpisode(episodeIndex,retryPosition,retryAutoplay,recovering=true)
    }
    addButton(actions,"返回选集") { returnToDetail(true) }
    if(retryable && !sleepStopped) {
        addButton(controls,UiText.get(com.hgtv.star.shared.R.string.ui_0027)) { cancelRecovery(); playbackText.text=UiText.get(com.hgtv.star.shared.R.string.ui_0210) }
        autoRecovery=true; scheduleRecovery()
    }
    // 错误界面挂一枚短码（同一行，不加行——这一行高度是钉死的 47dp）。
    // 用户反馈「解码失败」时报出它，就能直接对上诊断里那一条。
    //
    // **没有 `PlaybackException` 的路（本地准备失败、取流失败）没有数字码**：
    // 早先照样拼一个「错误码 0」出来，用户报「错误码 0」时谁也查不到东西；
    // 后来改成挂异常类名，又踩了另一个坑——正式包经 R8 混淆之后类名变成 `y1` / `a1`
    // 这种两三字符的短码，现场就出现「播放失败 · Y1」，用户报上来谁也查不到（1.2.7 实测）。
    // 因此这里只输出 [playbackFailureTag] 里的稳定常量。
    val code=(problem as? PlaybackException)?.errorCode ?: 0
    if(code!=0) playbackText.text=UiText.get(com.hgtv.star.shared.R.string.ui_0014, playbackText.text, code)
    else playbackText.text="${playbackText.text} · ${playbackFailureTag(problem)}"+
        // 为什么还要把原因挂上去：`本地准备失败` 底下有十几种完全不同的原因
        //（容器不认 / 索引不在文件头 / 加密索引缺失 / 索引截断 / 资源换了……），
        // 只报一个大类等于没报——现场只能回一句「查不到」。这些 message 本来就是中文短句，
        // 直接给用户看，他报上来的那句话就能落到具体那一行判断上。
        playbackFailureReason(problem)?.let { "：$it" }.orEmpty()
    // 日志同样不带类名：正式包里那串是混淆之后的短码，读日志的人对不上。
    // 阶段（`playback_prepare` / `playback_io` …）是稳定聚合键，与调试面板同源。
    // `giveUp` 一并打出来：现场问「为什么它没自动降档」时，这一栏直接回答
    //（音频没有可换的维度 / 已经是最低档 / 预算用完）。
    android.util.Log.w("HGTV","Playback failure stage=${playbackStage(problem)} tag=${playbackFailureTag(problem)}"+
        (if(problem is PlaybackException) " code=${problem.errorCodeName}" else "")+
        " giveUp=${giveUp?.name ?: "-"}"+
        " msg=${playbackFailureReason(problem) ?: "-"}")
}
/**
 * 队列里那一集出错之后，把**当前这一集**原地接回来（同档、静默、不动预算）。
 *
 * ## 为什么必须做
 *
 * ExoPlayer 对**队列里那一集**（片尾预热挂进去的下一集）的加载 / 解码失败，同样报成致命
 * `PlayerError`，而 2.19 的 `ExoPlayerImpl` 收到错误的第一件事就是
 * `PlaybackInfo.copyWithPlaybackState(1)`——整台播放器落到 `STATE_IDLE`，**当前这一集的画面
 * 同时停住**（`isPlaying` 也是 false，没有任何「还在正常播」的状态可读）。
 * 于是「下一集那条流坏了」在用户眼里是「这一集播到一半黑掉了」。
 *
 * 早先这里直接往后落到降级决策：消费**当前集**的预算、把 `effectiveMaxQuality` 压下一档
 * （跨集沿用）、最后弹错误页。用户看到的后果是「第 7 集放不了，之后整部剧都变糊了」——
 * 而当前这一集根本没坏。
 *
 * ## 为什么代价可以接受
 *
 * 当前这一集的播放地址 / moov 索引 / 首块全部命中缓存（见 `PlaybackCache` 与
 * `RemoteVideo`），重开一次**一个 Range 都不发**，代价只是一次换源黑屏。
 * 相比「把用户扔到错误页 + 永久压一档」，这个交换明显划算。
 *
 * ## 三条硬边界
 *
 * 1. 不消费 `SessionPlaybackPolicy` 的预算、不写档位上限——这不是「这台机器放不了这一档」
 *    的结论，坏的是隔壁那一集；
 * 2. 不弹提示、不进错误页、不写观看历史（`saveProgress` 在 `playError` 之前不会被走到，
 *    而这里连 `playError` 都不置位）；
 * 3. **有界**：[QUEUED_RECOVERY_LIMIT] 次都救不回来，说明坏的是当前这一集自己或者本机
 *    解码器，这才让位给正常的降级 / 错误页路径（那里的归因已经指向队列那一集，
 *    见 `playerFailure` 里的 `failedQueuedInfo`）。
 *
 * 次数按「集 + 当前上限」计：换集（非 `recovering`）归零，见 [playEpisode]。
 */
private const val QUEUED_RECOVERY_LIMIT=com.hgtv.star.core.AppConfig.Playback.TV_QUEUED_RECOVERY_LIMIT

private fun TvMainActivity.recoverQueuedCurrent(): Boolean {
    val key="${detail?.episodes?.getOrNull(episodeIndex).orEmpty()}:${playbackMaxQuality}"
    val used=playbackCoordinator.queuedRecoveryCount(key)
    if(used>=QUEUED_RECOVERY_LIMIT) return false
    // 位置直接问播放器：`STATE_IDLE` 下它仍然保留失败那一刻的读数（错误不会把位置清零），
    // 而 [currentPosition] 会看 `pendingSeek` / `playbackReady`，在出错态下不可靠。
    val live=runCatching { player?.currentPosition ?: -1L }.getOrDefault(-1L)
    val at=(if(live>0) live else startPosition).coerceAtLeast(0)
    playbackCoordinator.noteQueuedRecovery(key)
    // 队列那一集已经确认坏过一次：片尾不再对它做「同档重切一次」的那次尝试
    //（见 `playerFailure` 里 `atEnd` 分支的 `ledger.autoNextFailures<1`）——重来只会再黑一次。
    playbackCoordinator.noteAutoNextFailure()
    // 片尾预热窗口的计数一并归零：这一集要重新挂下一集，得从头数它自己的重发次数。
    prefetchAttempts=0; prefetchAttemptAt=0
    android.util.Log.w(
        "HGTV",
        "Queued episode failed mid-episode; reopening current=${episodeIndex+1} at=${at}ms"+
            " try=${used+1}/$QUEUED_RECOVERY_LIMIT quality=${playbackMaxQuality}P"
    )
    playEpisode(episodeIndex,at,requestedAutoplay && !sleepStopped,recovering=true)
    return true
}

/**
 * 错误页那枚短码：没有数字错误码时显示什么。
 *
 * **只返回稳定常量**，绝不返回 `javaClass.simpleName`：正式包里类名被 R8 混淆成
 * `y1` / `a1` 这种两三个字符的短码，页面就成了「播放失败 · Y1」——用户报这串字母，
 * 谁都查不出是哪条路径。这里按「用户能看懂、我们能对上阶段」来分：
 *
 * | 短码 | 含义 | 对应阶段 |
 * | --- | --- | --- |
 * | 取流失败 | 取播放地址 / 拉视频字节失败（网络、网关空响应） | `playback_io` |
 * | 本地准备失败 | 地址拿到了，但容器 / 索引 / 加密头读不出来 | `playback_prepare` |
 * | 地址过期 | 缓存下来的地址被服务端提前作废（重取一次仍失败） | `playback_io` |
 * | 资源已变化 | 这一集的文件换过了，旧索引对不上（重取一次仍失败） | `playback_io` |
 * | 内存不足 | `OutOfMemoryError` 被兜住 | `playback_oom` |
 */
fun playbackFailureTag(problem: Throwable?): String = PlaybackFailureLabels.tag(problem)
/**
 * 这一串异常里那句**能落到具体判断**的原因，没有就返回 null。
 *
 * 只收「短、单行、消息本身是给人看的」那种：`视频索引不在文件开头`、`没有可用清晰度`。
 * 长度上限 20：再长就会把错误页那一行顶出屏幕，而看不到的那半句对定位没有帮助
 *（真要长消息，日志里有完整一条）。深度上限 8，与 [streamExpiredOf] 一致。
 */
fun playbackFailureReason(problem: Throwable?): String? {
    return PlaybackFailureLabels.reason(problem)
}
/** 顺着 cause 链找某个类型，深度上限 8（与 [streamExpiredOf] 同因：防环形 cause）。 */
inline fun <reified T: Throwable> hasCauseOf(problem: Throwable?): Boolean {
    var current=problem
    var depth=0
    while(current!=null && depth<8) {
        if(current is T) return true
        current=current.cause
        depth++
    }
    return false
}
/**
 * 这一串异常里有没有「网络层就没通」，有就返回一句给用户看的话。
 *
 * 与 [playbackFailureReason] 分开，是因为它要**优先于**类型判断：同一个
 * `UnknownHostException` 可能被 `PlaybackPrepareException`、`HttpDataSourceException`
 * 或 [PlaybackException] 包上一层两层，只看最外层的类型永远归错类。
 */
fun networkFailureOf(problem: Throwable?): String? {
    return PlaybackFailureLabels.networkOf(problem)
}
/**
 * 这一串异常里有没有「这一集的文件换过了」。
 *
 * 判断本体已收进 `:shared` 的 `PlaybackFailures.videoChanged`（手机端同一份）：
 * 地址过期 / 文件换过 / 域名解析失败 / 连接层假死这四条判据两端必须完全一致，
 * 否则同一个故障在电视上自己救回来了、在手机上直接死掉。
 */
fun videoChangedOf(problem: Throwable?): Boolean =
    com.hgtv.star.shared.media.PlaybackFailures.videoChanged(problem)
/**
 * 这一串异常里有没有「名字解析出来了，但连接层就没通」。
 *
 * 判断本体见 `:shared` 的 `PlaybackFailures.transportDead`（与手机端同一份）。
 */
fun transportDeadOf(problem: Throwable?): Boolean =
    com.hgtv.star.shared.media.PlaybackFailures.transportDead(problem)
/**
 * 自愈前把「连接层缓存」一起清掉：视频域名的解析结果 + 进程级连接池。
 *
 * 清法收在 `:shared` 的 `PlaybackFailures.dropTransportCaches`（手机端同一份）；
 * 这里只负责按优先级收集「这一次可能涉及的 host」——`prepare()` 失败时 [TvMainActivity.video]
 * 还是上一集的（见该字段的注释），拿它清等于清错对象，因此要带上 [TvMainActivity.lastStreamHost]。
 */
fun TvMainActivity.dropDeadTransport() {
    val hosts=LinkedHashSet<String>(4)
    lastStreamHost.takeIf { it.isNotEmpty() }?.let(hosts::add)
    video?.host?.takeIf { it.isNotEmpty() }?.let(hosts::add)
    queuedVideo?.host?.takeIf { it.isNotEmpty() }?.let(hosts::add)
    com.hgtv.star.shared.media.PlaybackFailures.dropTransportCaches(hosts)
    if(hosts.isNotEmpty()) android.util.Log.w("HGTV","Transport cache cleared hosts=$hosts pool=evicted")
}
/**
 * 这一串异常里有没有「播放地址已失效」。
 *
 * 判断本体见 `:shared` 的 `PlaybackFailures.expired`（与手机端同一份）。
 */
fun streamExpiredOf(problem: Throwable?): Boolean =
    com.hgtv.star.shared.media.PlaybackFailures.expired(problem)
