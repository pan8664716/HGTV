// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import android.os.Build
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.analytics.AnalyticsListener
import com.google.android.exoplayer2.decoder.DecoderReuseEvaluation
import com.google.android.exoplayer2.mediacodec.MediaCodecRenderer
import com.hgtv.star.compat.isMeteredConnection
import com.hgtv.star.core.DegradationKind
import com.hgtv.star.core.PlaybackRetryPlan

/**
 * 播放失败的可观测层：**正式包里唯一还活着的那份证据**。
 *
 * 为什么单独做一层：正式包的 proguard 规则把 `Log.i` 整段删掉了（见 proguard-rules.pro），
 * 用户报「解码失败」时本机什么都不剩，而这类故障的成因全在**设备侧**——
 * 哪颗解码器、什么 codec string、多少分辨率帧率、硬解还是软解、
 * 是不是「READY 了但一帧都没出」。这些只能在本机现场里带回来。
 *
 * U-APM 已整体移除（见 `build.gradle.kts`），这条链路改成**只落本地**：
 * 一行完整现场——阶段、解码器名、codec string、分辨率帧率、设备上注册了哪些解码器、
 * 播放到哪儿——写进 logcat，同时进调试面板。用户报「解码失败」时打开调试模式就能读出来。
 *
 * 去重见 [PlaybackDiag.claim]：同一阶段一次启动只记一条，
 * 用户连点三次「立即重试」不会把记录刷成三条一样的。
 */
internal object PlaybackDiag {
    /**
     * 单进程记录上限。
     *
     * 去重键是**阶段**而不是「阶段+集号」：一台机器的 HEVC 硬解坏了，用户会一集一集往下撞，
     * 按集去重就会把同一件事刷成几十条。按阶段去重之后，每次启动最多也就 7 条
     * （source / io / parse / decoder_init / decoder_query / decoding / limits / unsupported / codec_error），
     * 一眼就能看出「这台是解码器初始化挂」还是「这台根本没缓冲到」。
     */
    private const val MAX_REPORTS = 12
    /** 解码器自报错误的额度（见 [claim]）。 */
    private const val MAX_CODEC_ERROR_REPORTS = 3

    private var reports = 0
    private var codecErrorReports = 0
    private val claimed = mutableSetOf<String>()
    /** 这一集报过失败没有：用来配对「降级 / 重试之后真的出画面了」。 */
    private var failed = false
    /**
     * 这一集有没有做过**自动处置**（同档重建 / 降档）。
     *
     * 单独一位而不是直接置 [failed]：那个位的另一处用途是
     * 「本会话已经报过用户可感知的失败」（[failedThisSession]），
     * 而自动处置**没有**产出任何上报记录。把它算成「已报过失败」会让随后
     * 解码器自己报的错（`playback_codec_error`）被吞掉——那恰恰是重建之后
     * 最需要看到的一条。因此这里只回答「这一次起播失败过」，不影响去重。
     */
    private var degraded = false
    private var recovered = false

    @Volatile var seriesTitle = ""
    @Volatile var episodeId = ""
    @Volatile var episodeIndex = 0
    @Volatile var episodeCount = 0
    @Volatile var quality = ""
    @Volatile var decoderMode = ""
    @Volatile var decoderName = ""
    @Volatile var initMs = -1L
    @Volatile var mime = ""
    @Volatile var codecs = ""
    @Volatile var container = ""
    @Volatile var width = 0
    @Volatile var height = 0
    @Volatile var frameRate = 0f
    @Volatile var bitrate = 0
    @Volatile var rotation = 0
    @Volatile var tracksSelected = true
    @Volatile var firstFrame = false
    @Volatile var codecErrors = 0
    @Volatile var droppedFrames = 0
    @Volatile var underruns = 0

    /**
     * 最近一帧真正上屏的时刻（`SystemClock.elapsedRealtime()`；0 = 这一集还没出过帧）。
     *
     * 与 [firstFrame] 的区别：那个是「这一集出过帧没有」，只在 [begin] / [nextEpisode] 清零；
     * 这个是**一直往前滚**的，因此既能判「一帧都没出」，也能判「播到一半画面停住了」——
     * 后者正是用户反馈的「自动播放几集后黑屏」最常见的形态（解码器卡死、surface 丢了）。
     */
    @Volatile var lastFrameAt = 0L

    // —— 屏幕版现场（见 `DebugOverlay.kt` 的调试模式面板）——
    // 记录只落本地；黑屏时屏幕上那块面板读的就是这三个字段。
    // 只记「用户眼前这一次」：阶段标题、错误码、cause 链压成的一行。
    @Volatile var lastStage = ""
    @Volatile var lastStageTitle = ""
    @Volatile var lastCode = 0
    @Volatile var lastError = ""

    /**
     * 这条现场是什么时候、针对哪一集记下的（`SystemClock.elapsedRealtime()` / 集标识）。
     *
     * 面板上的「问题」只该说**现在**还成立的事：这两项让 [livePlaybackProblem] 能判断
     * 「记下之后是不是已经换了集、或者早就过去了」，避免把上一集、上一次的失败一直挂在屏幕上。
     */
    @Volatile var lastStageAt = 0L
    @Volatile var lastStageEpisode = ""

    /**
     * 调试注入：画面照常出，但**故意不记账**（见 `DebugFaults.Mode.STALL_FRAME`）。
     *
     * 有了它，「有声音无画面」这条记录才测得了；同时这里也是**唯一**的注入开关——
     * 出帧的三条证据链（Player 监听、Analytics 监听、逐帧心跳）都看它，
     * 否则随便哪一条漏判就会把注入测试变成「一切正常」。
     */
    @Volatile var injectedNoFrame = false

    /**
     * 现在记着的这条是不是「停帧 / 缓冲」类（逐帧心跳一到就可以当场作废）。
     *
     * 单独一个 volatile 标记是给 [MainActivity.playbackFrameClock] 用的：那条回调每帧一次、
     * 在播放线程上，不能在里面加锁，也不该每帧去读一个字符串再比。
     */
    @Volatile var stallNoted = false

    /** 记下这一次的现场：屏幕上那块面板与调试面板共用同一份，避免两处描述不一致。 */
    @Synchronized fun note(stage: String,title: String,code: Int,error: String) {
        // 错误链最多 6 段、每段 160 字：屏幕上那块板子只放得下两行，截断在记的时候做，
        // 免得每个刷新周期都去切一次长字符串。
        lastStage=stage; lastStageTitle=title; lastCode=code; lastError=error.take(240)
        lastStageAt=android.os.SystemClock.elapsedRealtime(); lastStageEpisode=episodeId
        stallNoted=stage.startsWith("playback_stall")
    }

    /**
     * 清掉屏幕上那条现场（问题已经不再成立）。
     *
     * 与 [claimed] 无关：那个管的是「记录要不要去重」，这里管的是「面板上还挂不挂着」。
     * 两者必须分开——画面回来了就该从面板上消失，但同一次启动里同一阶段仍只记一条。
     */
    @Synchronized fun clearProblem() {
        if(lastStage.isEmpty() && lastError.isEmpty() && lastCode==0) return
        lastStage=""; lastStageTitle=""; lastCode=0; lastError=""
        lastStageAt=0L; lastStageEpisode=""
        stallNoted=false
    }

    /**
     * 画面确实上屏了（[androidx.media3] / ExoPlayer 的 `onRenderedFirstFrame`）。
     *
     * 「没有画面」这类结论当场作废：之前报的「已就绪但迟迟没有画面」在出帧那一刻就不再成立，
     * 面板上继续挂着它，用户看到的就是「明明有画面，却说我黑屏」。
     */
    fun pictureUp(): Boolean {
        // 注入模式下不记账：见 [injectedNoFrame]。返回 false 让调用方也不要撤看门狗。
        if(injectedNoFrame) return false
        firstFrame=true
        // 只有真的记着停帧类问题时才加锁清理：这条回调每集一次，但没必要无条件抢锁。
        if(stallNoted) clearProblem()
        return true
    }

    /** 新会话：清空上一集的一切，避免把上一集的解码器名带进这一集的报告。 */
    @Synchronized fun begin(playSession: Int,title: String,id: String,index: Int,count: Int,mode: String,qualityLabel: String = "") {
        // `claimed` **不**清：跨集去重，同一阶段一次启动只报一条（见 MAX_REPORTS 的注释）。
        // 同一集的重试（用户按「立即重试」/「尝试 720P」/ 自动恢复）**保留**失败标记：
        // 清掉的话，降级救回来这件事就永远记不上（见 [takeRecovered]）。
        // 换了一集才清零——上一集的失败与这一集的成败无关。
        if(id!=episodeId) { failed=false; degraded=false; recovered=false }
        // 每一次起播都是**新的一次尝试**：上一次留下的现场（失败阶段 / 错误链 / 停帧计时）
        // 到这一刻起不再成立——播放层正在重建。不清的话面板会把上一轮、上一集的
        // 「有声音无画面」继续挂在新一集上，用户看到的就是「明明有画面却说我黑屏」。
        // 这一轮再失败的话，`note()` 会重新记下现场。
        clearProblem()
        seriesTitle=title; episodeId=id; episodeIndex=index; episodeCount=count
        // 先按**请求的**清晰度落一个值：本地准备阶段就失败时，真实清晰度还没拿到，
        // 但「用户点的是 1080P」本身就是关键信息。
        quality=qualityLabel; decoderMode=mode; decoderName=""; initMs=-1
        mime=""; codecs=""; container=""; width=0; height=0; frameRate=0f; bitrate=0; rotation=0
        tracksSelected=true; firstFrame=false; codecErrors=0; droppedFrames=0; underruns=0; lastFrameAt=0
    }

    /**
     * 无感切集（片尾自动推进）用：播放器没重建，解码器还是同一颗。
     *
     * 只换「这一集是谁」，并把「出过帧没有」清掉——上一集的帧数不能算到这一集头上，
     * 否则看门狗会把这一集的黑屏当成已经出过画面而放过。
     */
    @Synchronized fun nextEpisode(id: String,index: Int,qualityLabel: String) {
        episodeId=id; episodeIndex=index; quality=qualityLabel
        firstFrame=false; failed=false; degraded=false; recovered=false; lastFrameAt=0
        // 同一颗解码器换了源：上一集记下的现场同样不再成立。
        clearProblem()
    }

    /**
     * 认领一次记录。
     *
     * 去重键是**阶段 + 错误码**：同一阶段里 4004（解码失败）与 4100（厂商自定义码）
     * 是两件不同的事，只按阶段去重会让后一条永远记不上——而用户报的正是那串数字。
     * 错误码为 0（看门狗 / 本地准备失败这类没有 `PlaybackException` 的）仍按阶段去重。
     */
    @Synchronized fun claim(stage: String,code: Int = 0): Boolean {
        if(reports>=MAX_REPORTS) return false
        // 解码器自己喊的错可能被 fallback 接住、也可能连着喊好几次（同一集不同样本），
        // 因此它单独给一个小额度，而不是「一次启动只留第一条」——第一条未必是最有代表性的。
        if(stage=="playback_codec_error") {
            if(codecErrorReports>=MAX_CODEC_ERROR_REPORTS) return false
            codecErrorReports++; reports++
            return true
        }
        if(!claimed.add(if(code!=0) "$stage#$code" else stage)) return false
        reports++
        failed=true; recovered=false
        return true
    }

    /** 本会话是否已经报过「用户可感知的失败」——用来让 `playback_codec_error` 让位。 */
    @Synchronized fun failedThisSession(): Boolean = failed

    /**
     * 记一次**自动处置**（同档重建 / 降档）：这一次起播失败过，只是还没报成一条故障。
     *
     * 只置 [degraded]，**不**置 [failed]：那个位的另一处用途是「本会话已经报过用户可感知
     * 的失败」（[failedThisSession]），而自动处置没有产出任何上报记录。混在一起会让
     * 解码器随后自己报的错（`playback_codec_error`）被吞掉——那是重建之后最需要的一条。
     */
    @Synchronized fun degrade() { degraded=true; recovered=false }

    /** 失败过（报过故障或做过自动处置）、之后又真的出了画面：这是「救回来了」的正样本。 */
    @Synchronized fun takeRecovered(): Boolean {
        if((!failed && !degraded) || recovered) return false
        recovered=true
        return true
    }

    fun format(value: Format?) {
        if(value==null) return
        mime=value.sampleMimeType.orEmpty()
        codecs=value.codecs.orEmpty()
        container=value.containerMimeType.orEmpty()
        width=value.width; height=value.height
        frameRate=value.frameRate; bitrate=value.bitrate; rotation=value.rotationDegrees
    }

    fun decoder(name: String,durationMs: Long) { decoderName=name; initMs=durationMs }

    /** 编码族：只留短标签，事件参数里放得下。 */
    val codecFamily: String get()=when {
        mime.contains("hevc",true) || codecs.startsWith("hvc",true) || codecs.startsWith("hev",true) -> "hevc"
        mime.contains("avc",true) || codecs.startsWith("avc",true) -> "avc"
        mime.contains("vp9",true) -> "vp9"
        mime.contains("av01",true) -> "av1"
        mime.isEmpty() -> "unknown"
        else -> mime.substringAfter('/').take(8)
    }

    val resolution: String get()=when {
        height<=0 -> "?"
        height>=1080 -> "1080"
        height>=720 -> "720"
        height>=480 -> "480"
        else -> "$height"
    }
}

/**
 * 解码失败的记录载体。
 *
 * **堆栈保持原样**，不伪造帧。
 *
 * 不在栈顶压人工帧（`fileName=null`、`line=-1`）：人工构造的 `(Unknown Source)` 帧
 * 没有合法的类 / 文件 / 行号，服务端的符号化与聚合解析会判它无效。
 *
 * 现在**堆栈保持原样**（`playerFailure → reportPlaybackFailure`，所有阶段都一样），
 * 阶段靠 [stage] 字段区分——logcat 与调试面板都按它聚合。
 */
internal class PlaybackDiagnosticException(
    val stage: String,
    message: String,
    cause: Throwable?=null
): Exception(message,cause)

/**
 * 本地「把播放地址变成可播流」这一步失败：容器不认、索引不在文件头、加密索引读不出来。
 *
 * 与设备解码能力**无关**，却和「解码失败」在界面上是同一句话。分开记，
 * 才能一眼看出到底是 CDN 给了另一种封装，还是这台机器的解码器不行。
 */
internal class PlaybackPrepareException(message: String,cause: Throwable?=null): java.io.IOException(message,cause)

/**
 * 阶段判定。
 *
 * `PlaybackException.errorCode` 的分段是 ExoPlayer 自己定的：
 * 1xxx 源 / 2xxx IO / 3xxx 容器解析 / 4xxx 解码。4xxx 里再细分，
 * 因为「找不到解码器」和「解码器初始化失败」和「解出来了但格式超出能力」是三种完全不同的修法。
 */
internal fun playbackStage(problem: Throwable?): String {
    val code=(problem as? PlaybackException)?.errorCode
    if(code==null) return when(problem) {
        // 看门狗 / 画面恢复这类**没有 PlaybackException** 的失败自带稳定 stage
        //（`playback_stall_frame` 等）。不认它就会一律落进 `playback_other`，
        // 于是「自己重建也没救回来」这条记录和别的杂项混成一组。
        is PlaybackDiagnosticException -> problem.stage
        is PlaybackPrepareException -> "playback_prepare"
        is java.io.IOException -> "playback_io"
        is OutOfMemoryError -> "playback_oom"
        else -> "playback_other"
    }
    return when(code) {
        in 1000..1999 -> "playback_source"
        in 2000..2999 -> "playback_io"
        in 3000..3999 -> "playback_parse"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "playback_decoder_init"
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> "playback_decoder_query"
        PlaybackException.ERROR_CODE_DECODING_FAILED -> "playback_decoding"
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "playback_decoder_limits"
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "playback_decoder_unsupported"
        // 4xxx 里还有厂商自己塞进来的码：统一归到「解码」，别丢进 other。
        in 4000..4999 -> "playback_decoding"
        else -> "playback_other"
    }
}

/** 从 cause 链里挖出真正失败的那颗解码器名（`DecoderInitializationException` 带着它）。 */
private fun decoderOf(problem: Throwable?): String {
    var current=problem; var guard=0
    while(current!=null && guard++<8) {
        if(current is MediaCodecRenderer.DecoderInitializationException) return current.codecInfo?.name ?: current.mimeType
        current=current.cause
    }
    return ""
}

/**
 * cause 链压成一行：只保留类名与 message，够定位，又不会撑爆日志行。
 *
 * 实现统一在 [Diagnostics.chainOf]：播放链路与其它模块用同一套格式，
 * 两处写法一致，读日志时不会因为措辞不同而当成两件事。
 */
private fun chainOf(problem: Throwable?): String = Diagnostics.chainOf(problem)

/** 当前播放现场：位置 / 状态 / 轨道 / 网络。日志里的这一行决定了「是没解出来还是没缓冲到」。 */
internal fun MainActivity.playbackScene(): String {
    // 这个函数会被**播放线程**调用（`onVideoCodecError` 就在那条线上），
    // 而 ExoPlayer 的 getter 要求应用线程。全部包进 runCatching：
    // 拿不到就退化成「未知」，绝不让诊断代码自己变成新的崩溃源。
    val sample=runCatching {
        val p=player
        val state=when(p?.playbackState) {
            Player.STATE_IDLE -> "idle"; Player.STATE_BUFFERING -> "buffering"
            Player.STATE_READY -> "ready"; Player.STATE_ENDED -> "ended"; else -> "none"
        }
        val position=p?.currentPosition ?: 0
        val duration=p?.duration?.coerceAtLeast(0) ?: 0
        val buffered=p?.bufferedPosition ?: 0
        "state=$state pos=${position/1000}s dur=${duration/1000}s buf=${buffered/1000}s"
    }.getOrDefault("state=unknown")
    val net=runCatching { if(connectivity.isMeteredConnection()) "mobile" else "wifi" }.getOrDefault("?")
    val fg=runCatching { "fg=$foreground autoplay=$requestedAutoplay" }.getOrDefault("fg=?")
    return "$sample videoTrack=${PlaybackDiag.tracksSelected} firstFrame=${PlaybackDiag.firstFrame}"+
        " codecErr=${PlaybackDiag.codecErrors} dropped=${PlaybackDiag.droppedFrames} underrun=${PlaybackDiag.underruns}"+
        " net=$net $fg"
}

/**
 * 解码器表的内存缓存：`mime → "hw:xxx,sw:yyy"`。
 *
 * 一次进程生命周期内系统的解码器表不会变（换解码器要重启），因此只枚举一次。
 * 这不是「省几十毫秒」：`MediaCodecList(REGULAR_CODECS).codecInfos` 在电视盒子上是
 * Binder 调用，实测 1–3 秒（见 `PlaybackCapability` 的类注释），而这条路径每报一次失败
 * 都会走到——同一台机器连撞三次解码失败，就要白等三次。
 */
private val decoderTableCache=mutableMapOf<String,String>()

/**
 * 这台机器为当前编码注册了哪些解码器（硬解 / 软解各几颗）。
 *
 * 只在**失败路径**调用，回答「是系统里根本没有这颗解码器，还是有但初始化失败」——
 * 两者的修法完全不同（前者只能换清晰度 / 换编码，后者可以靠 fallback 或换档位救）。
 *
 * 调用方必须在**后台线程**上调：`MediaCodecList` 是 Binder 调用，1–3 秒。
 */
private fun availableDecoders(mime: String): String {
    if(mime.isEmpty()) return "-"
    synchronized(decoderTableCache) { decoderTableCache[mime]?.let { return it } }
    val value=runCatching {
        // API 19 上没有 `MediaCodecList(int)` / `codecInfos`（都是 21 才加的），
        // 只能走 getCodecCount / getCodecInfoAt 这条老路。4.4 的电视正是最容易解不出 HEVC 的一批，
        // 这条分支不能少。
        val infos: List<android.media.MediaCodecInfo> = if(Build.VERSION.SDK_INT>=21) {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.toList()
        } else {
            (0 until android.media.MediaCodecList.getCodecCount()).mapNotNull {
                runCatching { android.media.MediaCodecList.getCodecInfoAt(it) }.getOrNull()
            }
        }
        val names=infos.filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime,true) }
        }.map { info ->
            // `isHardwareAccelerated` 是 29 才有的；更早的系统按厂商命名约定判断，
            // 判错也只是标签不准，不影响这条记录的价值。
            val hardware=if(Build.VERSION.SDK_INT>=29) info.isHardwareAccelerated else !looksSoftware(info.name)
            (if(hardware) "hw:" else "sw:")+info.name
        }
        if(names.isEmpty()) "none" else names.joinToString(",")
    }.getOrDefault("?")
    synchronized(decoderTableCache) { decoderTableCache[mime]=value }
    return value
}

/** 老系统上判断软解：AOSP 的软解一律叫 `OMX.google.*`，厂商软解常带 `sw` / `software`。 */
private fun looksSoftware(name: String): Boolean {
    val lower=name.lowercase()
    return lower.startsWith("omx.google.") || lower.contains(".sw.") || lower.contains("software") ||
        lower.contains("ffmpeg") || lower.contains("avcodec")
}

/**
 * 格式的兜底来源。
 *
 * `onVideoInputFormatChanged` 是**解码器初始化成功之后**才发的（ExoPlayer 在
 * `maybeInitCodec` 之后才通知），所以「解码器初始化失败」这条路里它永远来不及——
 * 而那恰恰是最需要格式信息的一类。两个兜底，按可靠性排序：
 *
 * 1. `DecoderInitializationException.mimeType`：异常自己带着要解的 mime；
 * 2. `player.currentTracks`：轨道没被选上（4005）时 `onVideoInputFormatChanged` 也不会发，
 *    但 `Tracks` 里仍然有这条未选中的视频轨，格式（含 codecs 串）都在里面。
 */
private fun PlaybackDiag.fillFormatFrom(problem: Throwable?,player: Player?) {
    // 异常链先补 mime 与失败的解码器名：这两项异常里一定带着。
    var current=problem; var guard=0
    while(current!=null && guard++<8) {
        if(current is MediaCodecRenderer.DecoderInitializationException) {
            if(mime.isEmpty()) mime=current.mimeType.orEmpty()
            if(decoderName.isEmpty()) decoderName=current.codecInfo?.name.orEmpty()
            break
        }
        current=current.cause
    }
    // 再从 `Tracks` 里补 codecs 串 / 分辨率 / 帧率：未选中的轨道也在这里，
    // 因此「4005 没有可解码的视频轨道」这条路同样拿得到完整格式。
    val sample=runCatching {
        player?.currentTracks?.groups?.firstOrNull { it.type==C.TRACK_TYPE_VIDEO }?.getTrackFormat(0)
    }.getOrNull()
    if(sample!=null) {
        if(codecs.isEmpty()) codecs=sample.codecs.orEmpty()
        if(width==0) { width=sample.width; height=sample.height }
        if(frameRate==0f) frameRate=sample.frameRate
        if(bitrate==0) bitrate=sample.bitrate
        if(mime.isEmpty()) mime=sample.sampleMimeType.orEmpty()
        if(container.isEmpty()) container=sample.containerMimeType.orEmpty()
    }
}

/** 格式那一行：解码失败的全部决定性信息都在这里。 */
private fun formatLine(): String = buildString {
    append("mode=").append(PlaybackDiag.decoderMode)
    if(PlaybackDiag.decoderName.isNotEmpty()) append(" decoder=").append(PlaybackDiag.decoderName)
    if(PlaybackDiag.initMs>=0) append(" init=").append(PlaybackDiag.initMs).append("ms")
    append(" codec=").append(PlaybackDiag.codecs.ifEmpty { "-" })
    append(" mime=").append(PlaybackDiag.mime.ifEmpty { "-" })
    append(' ').append(PlaybackDiag.width).append('x').append(PlaybackDiag.height)
    append('@').append(PlaybackDiag.frameRate)
    append(" rot=").append(PlaybackDiag.rotation)
    append(" br=").append(PlaybackDiag.bitrate)
    if(PlaybackDiag.container.isNotEmpty()) append(" container=").append(PlaybackDiag.container)
    append(" quality=").append(PlaybackDiag.quality.ifEmpty { "-" })
}

/**
 * 阶段 → 一句稳定的话。
 *
 * **必须是常量**：调试面板按 `type` 归并同一类故障，但标题同时会作为人读的标题展示；
 * 一旦掺进集号、解码器名、错误码，归并出来就是一堆碎渣。
 * 动态细节全部进 detail（跟着这条记录一起写进日志）。
 */
internal fun stageTitle(stage: String): String = when(stage) {
    "playback_source" -> "播放失败：资源不可用"
    "playback_io" -> "播放失败：读取视频出错"
    "playback_parse" -> "播放失败：视频封装无法解析"
    "playback_prepare" -> "播放失败：播放地址准备失败"
    "playback_decoder_init" -> "解码失败：解码器初始化失败"
    "playback_decoder_query" -> "解码失败：找不到可用解码器"
    "playback_decoding" -> "解码失败：解码过程中出错"
    "playback_decoder_limits" -> "解码失败：视频超出设备解码能力"
    "playback_decoder_unsupported" -> "解码失败：设备不支持该编码"
    "playback_codec_error" -> "解码失败：解码器报错"
    "playback_stall_frame" -> "解码失败：有声音无画面"
    "playback_stall_buffer" -> "播放失败：长时间无法起播"
    "playback_oom" -> "播放失败：内存不足"
    else -> "播放失败"
}

/**
 * 记一次「自动处置」（同档重建 / 降一档）。
 *
 * 它不是一条故障记录（不打 stage、不进上报去重表），但它必须把这一集标成
 * 「这一次起播失败过」（[PlaybackDiag.degrade]），于是真的出画面时
 * [PlaybackDiag.takeRecovered] 才会命中，「降级救回来了」这条正样本才有来源。
 *
 * 早先降级路径直接 `playEpisode(..., recovering=true)` 就走了，一个标记都不留，
 * 于是 `takeRecovered()` 永远返回 false——`QUALITY_DOWN_LIMIT` 到底够不够、
 * `softwareQuality` 卡的 720 是保守还是过头，全都拿不到现场数据。
 *
 * 只在主线程调用（`replayWithPlan` 就在主线程），读的都是本进程的字段。
 */
internal fun MainActivity.noteDegrade(plan: PlaybackRetryPlan) {
    val tier=plan.maxQuality ?: 0
    val text="自动处置 "+when(plan.reason) {
        DegradationKind.SAME_QUALITY_REBUILD -> "原档重建 ${playbackMaxQuality}P"
        DegradationKind.QUALITY_DOWN -> "降档 → ${tier}P"
    }
    DebugLog.record("播放",text+" · 预算["+sessionPlayback.budgetSummary()+"]",force=true)
    PlaybackDiag.degrade()
    // 这一行是「降级救回率」的唯一来源：`takeRecovered()` 之后会在出帧时再补一条
    // `HGTV-PLAY recovered … cap=`，两行成对。
    android.util.Log.w("HGTV-PLAY","degrade reason=${plan.reason.name}"+
        " target=${if(tier>0) "${tier}P" else "same"}"+
        " cap=${playbackMaxQuality}P mode=${playbackDecoderMode.name}"+
        " budget=[${sessionPlayback.budgetSummary()}]")
}

/**
 * 记一次播放失败。
 *
 * [stage] 是聚合键；标题取 [stageTitle]（常量），细节进 detail。
 * 这样按 stage 分组时，「某型号 HEVC 硬解全挂」是一组清清楚楚的记录，
 * 而不是几千条各不相同的杂音，而每一条的具体现场又都还在。
 */
internal fun MainActivity.reportPlaybackFailure(problem: Throwable?,stage: String) {
    // 先补格式：解码器初始化失败 / 轨道没选上这两条路都不会触发 `onVideoInputFormatChanged`，
    // 不补的话 codec、mime、分辨率、可用解码器列表全空——那正是最需要它们的时候。
    // 这一步放在 claim 之前：屏幕上的面板也要用这份格式，而去重挡掉的只是「再传一条」，
    // 不该连带把用户眼前的说明也一起挡掉。
    PlaybackDiag.fillFormatFrom(problem,runCatching { player }.getOrNull())
    // 屏幕上的现场（调试模式打开时才画，见 `DebugOverlay.kt`）。
    // `playback_codec_error` 不算：那一类多半被 fallback 接住、画面还在走。
    //
    // note 每次都写（幂等，面板上的「最近问题」要跟着最新的现场走）；
    // 近况那一栏只在**第一次**认领这个 stage 时记一条——停帧看门狗每 4 秒会再报一次，
    // 每次都记的话，几轮下来「近况」里全是同一句话，把真正有用的上下文挤掉了。
    if(stage!="playback_codec_error") {
        PlaybackDiag.note(stage,stageTitle(stage),(problem as? PlaybackException)?.errorCode ?: 0,chainOf(problem))
    }
    // 错误码（`PlaybackException.errorCode`）与它的常量名：用户报「错误码 4001」时，
    // 面板的「近况」与日志里都要能直接对上，不能只有出错界面上那一行看得见。
    val code=(problem as? PlaybackException)?.errorCode ?: 0
    val codeName=runCatching { (problem as? PlaybackException)?.errorCodeName.orEmpty() }.getOrDefault("")
    val codeText=when {
        code==0 -> ""
        codeName.isBlank() -> " · 错误码 $code"
        else -> " · 错误码 $code($codeName)"
    }
    val first=PlaybackDiag.claim(stage,code)
    if(first && stage!="playback_codec_error") {
        // 近况里带上错误码：面板的「问题」行只显示**当前仍成立**的问题，
        // 画面恢复之后它会消失；这条近况是它消失之后唯一还留着错误码的地方。
        // `force=true`：用户常是先看到错误码、之后才开调试模式，关着就丢会对不上。
        DebugLog.record("播放",stageTitle(stage)+" · $stage$codeText"+(if(problem!=null) " · "+chainOf(problem) else ""),force=true)
    }
    refreshDebugOverlay()
    if(!first) return
    // 归属信息必须**在主线程当场抄走**：下面那段采集可能跑上几秒，
    // 那时候用户早就切到下一集了，再读 `PlaybackDiag.episodeId` 会把这条记到错的集上。
    val episodeId=PlaybackDiag.episodeId
    val episodeIndex=PlaybackDiag.episodeIndex
    val episodeCount=PlaybackDiag.episodeCount
    val seriesTitle=PlaybackDiag.seriesTitle
    val decoderName=PlaybackDiag.decoderName
    val mime=PlaybackDiag.mime
    // `playbackScene()` 读的是 ExoPlayer 的 getter，必须在应用线程上取（函数内部已 runCatching）。
    val scene=playbackScene()
    val failedDecoder=decoderOf(problem)
    val errorChain=chainOf(problem)
    val title=stageTitle(stage)
    // 降级现场：**必须在这条线程上抄走**（与会话状态同理，几秒后可能已经换集 / 换档）。
    //
    // 用户报「老是降清晰度 / 一直弹提示」时，这一行能直接回答三个问题：这一次为什么降
    // （`plan`）、预算还剩几次（`budget`）、取流有没有指定编码族（`prefer`）。
    // 早先这些只活在内存里，出错之后一条都留不下，只能靠猜。
    val degradeInfo=runCatching {
        val policy=sessionPlayback
        val info=video?.info
        "cap=${playbackMaxQuality}P req=${info?.requestedQuality ?: 0}P actual=${info?.actualQuality ?: 0}P"+
            " level=${repository.deviceLevel} mode=${playerDecoderMode().name}"+
            " plan=${policy.lastReason?.name ?: "-"} hint=${policy.manualHint?.take(40) ?: "-"}"+
            " budget=[${policy.budgetSummary()}] avail=${info?.available?.joinToString(",") ?: "-"}"
    }.getOrDefault("")
    // 采集与记录全部挪到 io 线程。
    //
    // 为什么必须挪：这条路**只会**在主线程被走到（`onPlayerError`、看门狗 tick、
    // `onVideoCodecError` 延迟 1.5 秒的那条 Runnable），而 `availableDecoders()` 里的
    // `MediaCodecList(REGULAR_CODECS).codecInfos` 是 Binder 调用，1GB 盒子上实测 1–3 秒
    // （同一个坑见 `PlaybackCapability` 的类注释）。主线程卡这么久，用户那一刻正在按
    // 「立即重试」——输入事件无人处理就是 ANR。出错界面恰恰是最需要界面还能响应的时候。
    runCatching {
        io.execute {
            // 线程池可能在 Activity 销毁时已经 shutdownNow：那条失败不能再抛出来变成新的崩溃源。
            runCatching {
                val detail=buildString {
                    append("stage=").append(stage)
                    // 数字错误码 + 常量名：按 stage 聚合，但要能和用户报的那串数字直接对上。
                    if(code!=0) append(" code=").append(code)
                    if(codeName.isNotBlank()) append('/').append(codeName)
                    append(" ep=").append(episodeIndex+1).append('/').append(episodeCount)
                    append(" id=").append(episodeId)
                    append(' ').append(formatLine())
                    val decoder=failedDecoder.ifEmpty { decoderName }
                    if(decoder.isNotEmpty() && decoder!=decoderName) append(" failedDecoder=").append(decoder)
                    append(" decoders=").append(availableDecoders(mime))
                    if(degradeInfo.isNotEmpty()) append(" degrade=").append(degradeInfo)
                    append(' ').append(scene)
                    append(" dev=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    append(" os=").append(Build.VERSION.RELEASE).append('(').append(Build.VERSION.SDK_INT).append(')')
                    append(" app=").append(BuildConfig.VERSION_NAME).append('(').append(BuildConfig.VERSION_CODE).append(')')
                    append(" title=").append(seriesTitle.take(24))
                    append(" err=").append(errorChain)
                }
                // 只落本地：一行完整现场写进 logcat（调试面板另有一份，见上面的 `DebugLog`）。
                // 解码器名、codec string、分辨率帧率、设备上注册了哪些解码器、播放到哪儿，全在这一行里。
                android.util.Log.w("HGTV","Playback failure stage=$stage $title | ${detail.take(900)}")
            }
        }
    }
}

/**
 * 播放诊断监听：**正式包也挂**（与 debug 那份 [playbackAnalytics] 不同，这里不写日志，只记账）。
 *
 * 只记四件事：视频格式、解码器名与初始化耗时、解码器报错、丢帧 / 欠载。
 * 前两件是「为什么这台解不出来」的答案，后两件是「解出来了但很难看」的旁证。
 */
internal fun MainActivity.playbackDiagnostics()=object: AnalyticsListener {
    // 2.19 里真正被调用的是带 DecoderReuseEvaluation 的这版；两参那版是 deprecated 的兼容壳。
    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?
    ) { PlaybackDiag.format(format) }
    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long
    ) { PlaybackDiag.decoder(decoderName,initializationDurationMs) }
    override fun onVideoCodecError(eventTime: AnalyticsListener.EventTime,videoCodecError: Exception) {
        PlaybackDiag.codecErrors++
        // 解码器初始化失败**不在这里报**：ExoPlayer 紧接着会抛 `onPlayerError`，
        // 那条带 errorCode（4001）、带完整 cause 链，比这一条准。
        if(videoCodecError is MediaCodecRenderer.DecoderInitializationException) return
        // 其余解码器错误（解到一半坏帧、`CodecException` 之类）有两种走向：
        //
        // * 被 fallback 接住 / 只丢一帧 —— 不进 `onPlayerError`，**只有这一条能记下来**；
        // * 接不住 —— ExoPlayer 紧接着抛 `onPlayerError`，cause 链里原样带着这个异常，
        //   再报一条就是同一次失败两条记录（实测同一毫秒两条）。
        //
        // 因此延迟 1.5 秒再报（与 [playerFailure] 的互斥窗同长）：
        // 这期间 `playerFailure` 报过了，说明是同一次，这条作废；没报就是被接住了。
        //
        // 这条回调在**播放线程**上，而读取播放器状态更适合主线程，
        // 因此转一次主线程；会话号变了（用户已经切走）就丢掉。
        val session=playbackSession
        val pending=Runnable {
            if(screen=="player" && playbackSession==session && !PlaybackDiag.failedThisSession()) {
                reportPlaybackFailure(videoCodecError,"playback_codec_error")
            }
        }
        pendingCodecReport?.let { main.removeCallbacks(it) }
        pendingCodecReport=pending
        main.postDelayed(pending,1_500)
    }
    /**
     * 首帧上屏的第二条证据链。
     *
     * `Player.Listener.onRenderedFirstFrame` 与这条是两条独立回调（后者带 surface / 渲染耗时，
     * 隧道模式下也会来）。只要任一条到了就算「画面已经上屏」，面板上「没有画面」的结论当场作废
     *（见 [PlaybackDiag.pictureUp]）——这正是「明明有画面却说我黑屏」最直接的修法。
     */
    override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime,output: Any,renderTimeMs: Long) {
        // 注入模式下 `pictureUp()` 返回 false：那时**不撤看门狗**，
        // 否则「有声音无画面」这条记录就测不出来了。
        if(PlaybackDiag.pictureUp()) disarmPlaybackWatchdog()
    }
    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime,droppedFrames: Int,elapsedMs: Long) {
        PlaybackDiag.droppedFrames+=droppedFrames
    }
    override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime,bufferSize: Int,bufferSizeMs: Long,elapsedSinceLastFeedMs: Long) {
        PlaybackDiag.underruns++
    }
}

/**
 * 起播看门狗：**没有异常的解码失败**。
 *
 * 最典型的症状是「READY 了、进度条在走、声音有、画面全黑」——ExoPlayer 认为一切正常，
 * 只有一帧都没上屏。这条路不进 [playerFailure]，日志里干干净净，正是用户反馈里最难查的那类。
 *
 * 判据只用两件事：是否出过第一帧、以及等了多久。出帧即撤，所以正常播放一次都不会触发。
 */
/**
 * 看门狗的巡检周期。
 *
 * 必须**远小于**阈值：早先它和 [WATCHDOG_NO_FRAME_MS] 都是 15 秒，于是「READY 了但没画面」
 * 最坏要等 15（第一次巡检）+15（满阈值）= **30 秒**才动手，用户对着黑屏干等半分钟。
 * 巡检本身只是几次字段读，3 秒一次的成本可以忽略。
 */
private const val WATCHDOG_TICK_MS = 3_000L
/** READY 之后多久还没出帧就判黑屏。留足解码器首帧 + 首屏合成的时间。 */
private const val WATCHDOG_NO_FRAME_MS = 15_000L
/**
 * 一直没到 READY（卡在 buffering）多久算卡死。
 *
 * 90 秒是刻意留宽的：起播要凑满 20 秒缓冲才算 READY，弱网电视上这一步本来就慢，
 * 阈值卡太紧会把「网络慢」误报成「解码失败」，那比不报更糟。
 */
private const val WATCHDOG_STALL_MS = 90_000L

internal fun MainActivity.armPlaybackWatchdog(playSession: Int) {
    disarmPlaybackWatchdog()
    playbackWatchdogSession=playSession
    playbackWatchdogAt=android.os.SystemClock.elapsedRealtime()
    // 已经在 READY 状态时才挂（非自动播那条路）：黑屏计时立刻起算，不再多等一轮 tick。
    playbackWatchdogReadyAt=if(playbackReady) playbackWatchdogAt else 0L
    val tick=object: Runnable {
        override fun run() {
            if(playbackWatchdog!==this) return
            // 会话变了 / 已经出错 / 离开播放页：这条看门狗到此为止。
            // 推荐页同样要盯：那一页换剧后黑屏有声是用户最常碰到的一种，且它不会抛异常，
            // 以前只认 `player` 时这一条永远走不到。
            if(!onPlaybackPage() || playError || playbackSession!=playSession) { playbackWatchdog=null; return }
            // 出过首帧**或**逐帧心跳在走，都说明画面已经上屏：撤掉，不再判黑屏。
            // 只认 `onRenderedFirstFrame` 时，隧道模式 / 个别老系统会被误判成「一直没画面」。
            if(pictureUpNow()) { playbackWatchdog=null; return }
            // 播放器这一刻恰好为空（正在重建）：等下一轮，别让看门狗自己死掉。
            val p=player ?: run { main.postDelayed(this,WATCHDOG_TICK_MS); return }
            // 用户自己暂停 / 退到后台 / 本来就是不自动播：不算卡死，继续等。
            if(!foreground || !requestedAutoplay || p.playWhenReady!=true) {
                // 暂停 / 后台这段**不算「在等画面」**：两个起点都推到这一刻，恢复播放后重新数。
                // `playbackWatchdogReadyAt` 不推的话，先 READY 再暂停五分钟、回来一按播放会当场
                // 被判成黑屏并触发一次重建（其实首帧 200ms 后就到了）——那是「看门狗误报」最典型的一种。
                // `playbackWatchdogAt` 同样要推：它是 [WATCHDOG_STALL_MS]（90 秒「一直没到 READY」）
                // 的起点，不推的话「在缓冲中暂停两分钟再继续」会立刻命中 stall_buffer，
                // 白做一次整层重建。
                playbackWatchdogReadyAt=0L
                playbackWatchdogAt=android.os.SystemClock.elapsedRealtime()
                main.postDelayed(this,WATCHDOG_TICK_MS); return
            }
            val now=android.os.SystemClock.elapsedRealtime()
            if(playbackReady) {
                // READY 的那一刻起算：解码器初始化 + 首帧渲染都在这一段里。
                if(playbackWatchdogReadyAt==0L) playbackWatchdogReadyAt=now
                if(now-playbackWatchdogReadyAt>=WATCHDOG_NO_FRAME_MS) {
                    reportPlaybackFailure(PlaybackDiagnosticException("playback_stall_frame","播放器已就绪但迟迟没有画面"),"playback_stall_frame")
                    playbackWatchdog=null
                    // 报告之后就地自愈，别让用户对着一块黑屏自己找路
                    //（正片见 [recoverFormalPicture]，推荐页见 [recoverRecommendPicture]）。
                    recoverLostPicture("no_first_frame")
                    return
                }
            } else if(now-playbackWatchdogAt>=WATCHDOG_STALL_MS) {
                reportPlaybackFailure(PlaybackDiagnosticException("playback_stall_buffer","播放器长时间停在缓冲，未进入可播放状态"),"playback_stall_buffer")
                playbackWatchdog=null
                recoverLostPicture("stall_buffer")
                return
            }
            main.postDelayed(this,WATCHDOG_TICK_MS)
        }
    }
    playbackWatchdog=tick
    main.postDelayed(tick,WATCHDOG_TICK_MS)
}

/**
 * 「画面确实上屏了」的统一判据：出过首帧，**或**逐帧心跳在走。
 *
 * 只认 `onRenderedFirstFrame` 是不够的：隧道模式（tunneling）与部分老系统不回调它，
 * 而逐帧心跳（[playbackFrameClock]）那时照样在走。两样都看，才不会把「画面明明在放」
 * 判成「一直没出画面」——那正是面板上那句误报的来源。
 */
internal fun MainActivity.pictureUpNow(): Boolean =
    !PlaybackDiag.injectedNoFrame && (PlaybackDiag.firstFrame || PlaybackDiag.lastFrameAt>0L)

/**
 * 面板上「问题」那一条：**只写现在仍然成立的事**。
 *
 * 原先这一行读的是 [PlaybackDiag.lastStage]——那是「最近一次失败」的账本，只要这一集里
 * 出过一次问题，它就一直挂在那儿。可故障常常当场就自愈了（解码器 fallback、重建播放层、
 * 网络补上缓冲），画面已经在走，面板还在说「有声音无画面」——用户读到的就是「显示得不对」。
 *
 * 因此这里把账本里的结论**拿到现在重新判一遍**：
 * * 停帧类：现在真的还在播、而且最近一帧确实超过阈值，才算数；
 * * 缓冲类：现在真的还没到可播放状态，才算数；
 * * 解码 / 资源类：这一集已经出过画面就说明恢复了（这类失败一定发生在出画面之前），不再显示。
 *
 * 时间戳只用来提示「这条是多久以前记的」，不再承担「是不是还在发生」的判断。
 */
internal fun MainActivity.livePlaybackProblem(): String {
    val stage=PlaybackDiag.lastStage
    if(stage.isBlank()) return ""
    // 记下现场之后已经换了集：那是上一集的事，不该挂在这一集上。
    if(PlaybackDiag.lastStageEpisode.isNotBlank() && PlaybackDiag.lastStageEpisode!=PlaybackDiag.episodeId) return ""
    val now=android.os.SystemClock.elapsedRealtime()
    val live=when(stage) {
        "playback_stall_frame" -> {
            val last=PlaybackDiag.lastFrameAt
            if(last>0L) {
                // 有逐帧心跳：必须确实在播、而且最近一帧真的超时，才算「画面停住」。
                val p=player ?: return ""
                if(!foreground || !p.playWhenReady || p.playbackState!=Player.STATE_READY || !p.isPlaying) return ""
                val stalled=now-last
                if(stalled<FRAME_STALL_MS) return ""
                "画面停住 ${stalled/1000}s"
            } else {
                // 这台设备不走逐帧回调（隧道模式等）：只能按「出过首帧没有」判。
                // 出过首帧就说明画面正常，这一条不挂；一帧都没出才是真的没画面。
                if(PlaybackDiag.firstFrame) return ""
                PlaybackDiag.lastStageTitle
            }
        }
        "playback_stall_buffer" -> {
            // 已经能播了就不是问题；还卡着就把「卡了多久」说清楚。
            if(playbackReady) return "" else "还没进入可播放状态"
        }
        // 解码 / 资源类失败：出过画面即视为已恢复（这类失败都发生在首帧之前）。
        else -> if(pictureUpNow()) return "" else PlaybackDiag.lastStageTitle
    }
    if(live.isBlank()) return ""
    // 停帧 / 缓冲这两条自带「多久」，不再重复叠时间；其余补一句「多久以前记的」。
    if(stage.startsWith("playback_stall")) return live
    val age=((now-PlaybackDiag.lastStageAt).coerceAtLeast(0))/1000
    val whenText=if(age<60) "${age}s前" else "${age/60}m前"
    return "$live · $whenText"
}

internal fun MainActivity.disarmPlaybackWatchdog() {
    playbackWatchdog?.let { main.removeCallbacks(it) }
    playbackWatchdog=null
}

/**
 * 逐帧心跳：每有一帧**即将上屏**就刷新一次 [PlaybackDiag.lastFrameAt]。
 *
 * 这是「画面还在走」的唯一硬证据——播放位置在走、声音在响，都可能只是音频与时间轴在动。
 * 回调在**播放线程**上、每帧一次，因此这里只做一次 volatile 赋值，不碰任何 View。
 *
 * **只在这里更新**：隧道模式（tunneling）与部分老系统不会走这条回调，
 * 那时 `lastFrameAt` 一直是 0，停滞看门狗据此**自己让路**（见 [armFrameStallWatchdog]）。
 * 若在 `onRenderedFirstFrame` 里也记一次，这类设备就会在首帧之后 12 秒被误判成「画面停住」。
 */
internal fun MainActivity.playbackFrameClock()=com.google.android.exoplayer2.video.VideoFrameMetadataListener { _,_,_,_ ->
    // 注入模式（DebugFaults.STALL_FRAME）下故意不记账，见 [PlaybackDiag.injectedNoFrame]。
    if(PlaybackDiag.injectedNoFrame) return@VideoFrameMetadataListener
    PlaybackDiag.lastFrameAt=android.os.SystemClock.elapsedRealtime()
    // 帧在走：之前记下的「画面停住 / 已就绪但没画面」当场不成立，面板上那条现场一并清掉。
    // 这里每帧一次、在播放线程上，因此只看一个 volatile 标记，命中（罕见）才去加锁清理。
    if(PlaybackDiag.stallNoted) PlaybackDiag.clearProblem()
}

/**
 * 「画面停住」看门狗：**已经在播、却突然不再出帧**。
 *
 * 用户反馈的「自动播放几集后就黑屏」多数是这个形态：播放器状态还是 READY、进度条还在走、
 * 声音可能也还在，但解码器不再吐帧（或 surface 丢了）。它不抛异常，起播看门狗又已经因为
 * 「出过第一帧」撤掉了，于是屏幕上只剩一块黑，调试面板里一条记录都没有。
 *
 * 判据只看「最近一帧上屏是什么时候」：逐帧回调（见 [PlaybackDiag.lastFrameAt]）每次刷新，
 * 超过 [FRAME_STALL_MS] 没有新帧、且播放器确实在播（不是暂停 / 缓冲 / 后台），才判停住。
 * 缓冲与拖动都会让出帧中断几秒，因此阈值给到 12 秒，正常播放一次都不会触发。
 *
 * `lastFrameAt == 0`（这一集从没收到过逐帧回调）时**直接让路**：那说明这台设备 /
 * 这条渲染路径根本不走 `VideoFrameMetadataListener`，此时「没有新帧」什么也证明不了。
 *
 * 同一个巡检还负责**播到一半掉进 BUFFERING**：起播看门狗在首帧后就撤了，而下面这条
 * 停帧判据要求 `STATE_READY`，于是「永远转圈」过去没有任何看门狗覆盖（见 [BUFFER_STALL_MS]）。
 */
private const val FRAME_STALL_TICK_MS = 4_000L
private const val FRAME_STALL_MS = 12_000L

/**
 * 已经在播、却停在 BUFFERING 多久算死。
 *
 * 判据用**缓冲位置不再前进**而不是绝对时长：弱网下 `bufferedPosition` 一直在涨就还有希望，
 * 把「慢」误判成「死」比不判更糟。30 秒足够覆盖一次正常的 seek 回填与弱网抖动。
 */
private const val BUFFER_STALL_MS = 30_000L

internal fun MainActivity.armFrameStallWatchdog(playSession: Int) {
    disarmFrameStallWatchdog()
    // 缓冲停滞的计时放在闭包里：看门狗重新挂上（换集 / 重建）时自然归零。
    var bufferSince=0L
    var bufferAt=-1L
    val tick=object: Runnable {
        override fun run() {
            if(playbackStallWatchdog!==this) return
            // 推荐页同样要盯（理由见 `armPlaybackWatchdog`）。
            if(!onPlaybackPage() || playError || playbackSession!=playSession) { playbackStallWatchdog=null; return }
            val p=player ?: run { main.postDelayed(this,FRAME_STALL_TICK_MS); return }
            // 只有「确实该在走」才算：暂停 / 后台 / 用户没让播时不出帧、不缓冲都是正常的。
            val active=foreground && requestedAutoplay && p.playWhenReady
            if(!active) { bufferSince=0; main.postDelayed(this,FRAME_STALL_TICK_MS); return }
            val now=android.os.SystemClock.elapsedRealtime()
            // ① 播到一半掉进 BUFFERING，而且缓冲位置不再前进。
            //    这一段过去完全没人管：起播看门狗首帧后就撤了，下面的停帧判据又要求 READY。
            if(p.playbackState==Player.STATE_BUFFERING) {
                val buffered=p.bufferedPosition
                if(buffered>bufferAt) { bufferAt=buffered; bufferSince=now }
                else if(bufferSince==0L) bufferSince=now
                else if(now-bufferSince>=BUFFER_STALL_MS) {
                    reportPlaybackFailure(
                        PlaybackDiagnosticException("playback_stall_buffer","播放中缓冲停止前进 ${BUFFER_STALL_MS/1000} 秒"),
                        "playback_stall_buffer"
                    )
                    // 先归零再交给恢复路径：推荐页那边被冷却挡掉时不会立刻重建，
                    // 这一条 tick 每 4 秒还会再来，不归零就会每 4 秒重复触发一次。
                    bufferSince=0; bufferAt=-1
                    recoverLostPicture("stall_buffer")
                }
                main.postDelayed(this,FRAME_STALL_TICK_MS); return
            }
            bufferSince=0; bufferAt=-1
            // ② 已经在播、却不再出帧。
            if(p.playbackState!=Player.STATE_READY || !p.isPlaying) { main.postDelayed(this,FRAME_STALL_TICK_MS); return }
            val last=PlaybackDiag.lastFrameAt
            if(last<=0L) { main.postDelayed(this,FRAME_STALL_TICK_MS); return }
            val stalled=now-last
            if(stalled>=FRAME_STALL_MS) {
                // 报告会顺带把屏幕上的现场摆出来（见 [reportPlaybackFailure]）。
                // 去重由 `claim` 负责：同一进程里这条 stage 只记一次，但面板会一直留着并继续刷新。
                reportPlaybackFailure(
                    PlaybackDiagnosticException("playback_stall_frame","播放中画面停止刷新 ${stalled/1000} 秒"),
                    "playback_stall_frame"
                )
                // 推荐页：画面停了但声音还在（用户反馈的那种「刷几部之后黑屏、快进快退都没反应」），
                // 就地重建播放层；正片同样重建一次、再不行落错误页。
                // 冷却与次数上限在 [recoverLostPicture] 里，这条 tick 每 4 秒还会再来，
                // 不会因此连着重启。
                recoverLostPicture("frame_stall")
            }
            main.postDelayed(this,FRAME_STALL_TICK_MS)
        }
    }
    playbackStallWatchdog=tick
    main.postDelayed(tick,FRAME_STALL_TICK_MS)
}

internal fun MainActivity.disarmFrameStallWatchdog() {
    playbackStallWatchdog?.let { main.removeCallbacks(it) }
    playbackStallWatchdog=null
}

/**
 * 「画面丢了」的统一入口：按页面分派。
 *
 * 推荐页换下一部 / 就地重建；正片就地重建一次、再不行落错误页。
 * 两页的故障是同一种（解码器不再吐帧，不抛异常），只是处置的出口不同。
 */
internal fun MainActivity.recoverLostPicture(reason: String) {
    // 缓冲停住 = 一个字节都没再来，坏的多半是下面那两层缓存（DNS 记了一个已经下线的 IP /
    // 连接池里那条半死连接）。不先清掉，重建之后命中的还是同一份解析结果、同一条连接。
    // 出帧类的失败（no_first_frame / frame_stall）不清：那时数据是通的，问题在解码器。
    if(reason=="stall_buffer") dropDeadTransport()
    if(screen=="recommend") recoverRecommendPicture(reason) else recoverFormalPicture(reason)
}

/**
 * 正式播放页的画面恢复：**同样的故障、同样的修法**，只是这边不换剧。
 *
 * 两条看门狗捕的都是「没有异常的解码失败」（READY 了不出帧 / 播着播着不再出帧）。
 * 推荐页会就地重建播放层，正片早先**只记一条、然后什么都不做**：`playError` 仍是 false，
 * 控制条还写着「播放中」，看门狗停掉不再重试，用户对着一块黑屏——快进快退也没用，
 * 拖动同样要解码器吐帧。
 *
 * 修法与用户自己试出来的完全一致（换一集 / 重进播放页画面就回来了）：那一步的差别不是
 * 换了片源，而是整棵树重建——换掉 `PlayerView` / Surface、放掉 MediaCodec 再重建。
 * 因此这里在原位做同一件事：不换集、不写历史、不动清晰度与解码方式，从当前位置续播。
 *
 * **只给一次**：重建一次仍然没画面，就落错误页（那里有重试 / 换清晰度 / 切软解 / 返回选集），
 * 由用户决定。换解码方式与换清晰度都是他的知情选择，这里不做。
 * 冷却窗口用于挡掉「起播看门狗与停帧看门狗同时命中」这一类重复触发。
 */
private const val FORMAL_PICTURE_RECOVERY_LIMIT=1
private const val FORMAL_PICTURE_RECOVERY_COOLDOWN_MS=20_000L

internal fun MainActivity.recoverFormalPicture(reason: String) {
    // 只在正式播放页动手：推荐页有自己的换剧策略（[recoverRecommendPicture]）。
    if(screen!="player" || !foreground || playError) return
    val data=detail ?: return
    if(episodeIndex !in data.episodes.indices) return
    val p=player ?: return
    val now=android.os.SystemClock.elapsedRealtime()
    if(ledger.formalPictureRecoveries>=FORMAL_PICTURE_RECOVERY_LIMIT) {
        // 已经重建过一次还是没画面：交给错误页，别再自己闪。
        playerFailure(PlaybackDiagnosticException("playback_stall_frame","画面停止刷新，重建后仍未恢复"))
        return
    }
    if(ledger.pictureCoolingDown(now,FORMAL_PICTURE_RECOVERY_COOLDOWN_MS)) return
    ledger.notePictureRecovery(now)
    // 位置直接问播放器：这一路没有 `playbackReady` 参与（它是 true 才会判停帧），
    // 但 `currentPosition()` 还要看 `pendingSeek`，用播放器自身的读数更准。
    val at=runCatching { p.currentPosition }.getOrDefault(0L).coerceAtLeast(0)
    android.util.Log.w("HGTV","Formal picture lost ($reason) episode=${episodeIndex+1} at=${at}ms try=${ledger.formalPictureRecoveries}")
    // 重建期间画面层会被整块换掉：这一句告诉用户「不是卡死，是在重来」，比一块黑屏强。
    playbackText.text="画面停止刷新，正在重建播放…"
    playEpisode(episodeIndex,at,p.playWhenReady,recovering=true)
}
