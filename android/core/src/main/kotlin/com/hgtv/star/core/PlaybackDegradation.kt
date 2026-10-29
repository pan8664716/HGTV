// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 播放重试 / 降级的**全部决策**都收敛在这个文件里，纯逻辑、不碰 Android、可直接单测。
 *
 * ## 前提：全站只有一种视频编码
 *
 * 平台下发的可播视频只有 HEVC（`bytevc1` / `h265_hvc1`），其余 `bytevc2` / `bvc2`
 * 在 Android 上没有解码器，取地址时就被剔掉了（见 `ContentRepository.fetchStream`）。
 *
 * 因此早期那两维已经**整维删除**：
 *
 * * 「换编码族（HEVC ↔ AVC）」——`device_level` 报 4/5 时五档全是 HEVC，换档位换不来
 *   另一条编码，这一维没有目标可换；
 * * 「自动硬解 → 软解」——HEVC 软解在电视盒子上多数会掉帧甚至卡死，**不替用户做这个决定**。
 *   硬解走完就把「换成软解 + 换到哪一档」摆到界面上，由用户自己点（见 [PlaybackDegradation.manualHint]）。
 *
 * ## 剩下的只有两条，按代价从小到大
 *
 * 1. **同档重建**（每集一次）：4001 / 4003 → 放掉解码器重开一次。地址与索引全部命中缓存，
 *    一次 Range 都不发、不动清晰度、不弹提示。这两条在现场多半是解码器实例被占、
 *    `configure` 偶发失败、或解到一帧坏样本；
 * 2. **降清晰度**（每集两次）：同一次响应里还有更低档就降一档；
 * 3. 都走完还是不行 → 把「换软解 / 换到哪一档」的手动处置建议写进会话，返回
 *    [RetryDecision.GiveUp]（带上理由），界面据此进错误页并决定给哪几颗按钮。
 *
 * **音频轨只走第 1 条**：它与档位、解码方式都无关，唯一的自动处置是把解码器放掉重开
 * （`AUDIO_REBUILD_LIMIT`）。早先音频一进来就返回 null，
 * 一次瞬态 AAC 失败等于直接判死。
 *
 * **绝不自动软解、绝不改用户设置。**
 *
 * ## 三条硬边界
 *
 * 1. **硬解优先**：自动降级只降档，切软解是用户的知情选择；
 * 2. **不改用户的设置**：降级只写 [SessionPlaybackPolicy]，`Library.maxQuality` 只由用户改；
 * 3. **有界**：重建一次 + 降档两次用尽即返回 null，绝不递归重试。
 */
enum class MediaKind { VIDEO, AUDIO }

/** 一次降级的理由：调试面板与日志都按它聚合。 */
enum class DegradationKind {
    /** 降分辨率档位。 */
    QUALITY_DOWN,
    /**
     * 同一档重建一次：不换清晰度、不换解码方式，只是把解码器放掉重开。
     *
     * 对应 4001（解码器初始化失败）与 4003（解码中失败）：前者多半是实例被别的应用占满、
     * `configure` 偶发失败，后者多半是坏帧 / SPS 变更，重开一次解码器往往就好了。
     * 而 4004 / 4005（超出能力 / 格式不支持）才是真的该降档——它们不会走这一级。
     */
    SAME_QUALITY_REBUILD
}

/**
 * 一次重试要改的东西。
 *
 * 字段为 null = 该维度不变。**回放路径只有一条**（重新起播 + 断点续播），参数不同而已：
 * [maxQuality] 为 null 就是「原档重建」，此时地址由缓存命中，不进网络。
 *
 * [fromQuality] 是**这次降档的起点**（刚刚失败的那一档）。会话层拿它记一笔，
 * 下次同档失败时就能判断「降了等于没降」（CDN 只回兼容资源），不再白烧一次预算。
 */
data class PlaybackRetryPlan(
    val maxQuality: Int? = null,
    val reason: DegradationKind,
    val toast: String?,
    val fromQuality: Int? = null
)

/**
 * 自动处置走不下去的原因。
 *
 * 它的用处有三个：① 让 [PlaybackDegradation.decide] 的返回值自带语义——早先 `null`
 * 同时表示「音频」「没有更低的档」「预算用完了」三件事；② 单测能钉住**为什么**放弃；
 * ③ 现场日志里一眼看出是「降无可降」还是「预算用完了」。
 *
 * 错误页的**按钮**仍由媒体类型 / 当前解码方式 / 还有没有更低档决定（见 `playerFailure`），
 * 不靠这个反推——音频失败时那两颗视频按钮就是这样被挡掉的。
 */
enum class GiveUpReason {
    /** 音频轨：档位与解码方式都与它无关，本机没有任何可换的维度。 */
    AUDIO_NO_RECOVERY,
    /** 这一档已经是本次响应里最低的一档，或者上次降档根本没有换来更低的一档。 */
    NO_LOWER_TIER,
    /** 还有更低的档，但本集的自动处置预算已经用尽。 */
    BUDGET_EXHAUSTED
}

/**
 * 一次失败的处置结论。
 *
 * 早先 [PlaybackDegradation.decide] 返回 `PlaybackRetryPlan?`，`null` 既表示「预算用尽」
 * 也表示「没有更低的档」也表示「音频」，页面只能靠「有 manualHint 且是解码类」反推；
 * 音频那一路没有 manualHint，于是照样摆出了「切换为软解 / 换到 720P」两颗
 * **对音频完全无效**的按钮。现在放弃也要说明理由（[GiveUpReason]），
 * 页面另按媒体类型收口按钮。
 */
sealed interface RetryDecision {
    data class Retry(val plan: PlaybackRetryPlan): RetryDecision
    data class GiveUp(val reason: GiveUpReason): RetryDecision
}

/**
 * 一次失败的归因。
 *
 * [available] 是**已经剔除** `bytevc2` / `main_url` 为空 / 档位认不出（0）之后的可播档位，
 * 因此它既是「能降到哪」的依据，也是「建议用户换到哪一档」的依据。
 */
data class FailureAttribution(
    val media: MediaKind,
    val errorCode: Int,
    val currentQuality: Int,
    val currentMode: VideoDecoderMode,
    val available: List<Int>
)

/** ExoPlayer 的 4xxx 错误码（值抄一份，让 core 不依赖 Android）。 */
object PlaybackErrorCode {
    const val DECODER_INIT_FAILED = 4001
    const val DECODER_QUERY_FAILED = 4002
    const val DECODING_FAILED = 4003
    const val FORMAT_EXCEEDS_CAPABILITIES = 4004
    const val FORMAT_UNSUPPORTED = 4005
}

/**
 * 会话级的降级状态。
 *
 * **绝不写 `Library`**：降档是「这一台机器这一刻放不了」，不是「用户想要更低的清晰度」。
 * 早先把 `library.maxQuality` 直接写成 480，用户卸载重装都恢复不了，还会让启动时那次
 * 「按设备能力给默认值」永远不再生效（`Library.qualityChosenByUser` 只看 prefs 有没有那个键）。
 *
 * 生命周期：
 * * [effectiveMaxQuality] 跨集沿用，退出播放页时由界面层丢掉整个对象；
 * * 预算每集重置（[onEpisodeStart]），手动重试只清本集（[onManualRetry]），
 *   用户手动改设置则全部清空（[onUserManualChange]）。
 */
class SessionPlaybackPolicy {
    @Volatile var effectiveMaxQuality: Int? = null
        private set

    /** 最近一次降级的理由，调试面板显示用。 */
    @Volatile var lastReason: DegradationKind? = null
        private set

    /**
     * 自动降级走完也放不了时的**手动处置建议**（界面把它显示在错误页上）。
     *
     * 内容由 [PlaybackDegradation.manualHint] 生成：换软解、还是换到哪一档清晰度。
     * 换集 / 手动重试 / 用户改设置时清空。
     */
    @Volatile var manualHint: String? = null
        private set

    private val used = HashMap<String,Int>()
    private var episode = ""
    /**
     * 上一次降档的**起点档位**（刚刚失败的那一档），0 = 还没降过。
     *
     * 降档是「请求一个更低的上限」，而 CDN 在上限内一档都没有时会回一条兼容资源
     * （见 `ContentRepository.fetchStream` 的 `affordable.firstOrNull() ?: candidates.last()`）。
     * 于是可能出现「请求 720、拿回来还是 1080」——这一趟注定复现同一类失败，
     * 却已经花掉一次降档预算、一次取地址和一次黑屏。有了这个起点就能在
     * [PlaybackDegradation.decide] 里直接跳过这一维（见 [qualityDownIneffective]）。
     */
    private var degradeFrom = 0
    /**
     * 本集用户手动重试过几次（[onManualRetry]）。
     *
     * 手动重试**归还**自动降级的预算，否则点一下立刻又撞同一个上限、闪回错误页。
     * 但归还必须是有限的：连点五次就白烧五次降档 + 五次取地址，用户看到的只是
     * 「它自己在反复重来」。[MANUAL_RETRY_LIMIT] 之后不再归还——按钮仍然可用，
     * 只是这一次不再多花预算。
     */
    private var manualRetries = 0

    /**
     * 这一集开始：预算全部归零，**档位上限沿用**（它是「这台机器放不了这一档」的结论，
     * 与具体哪一集无关）。
     *
     * 不按 id 去重：无感切集（片尾自动推进）与「从第 3 集点回第 2 集」都必须清预算。
     * 早先这里有一句 `if(id==episode) return`，于是同一集重进时上一轮留下的
     * [manualHint] 会被带进新一次起播——用户看到的是一句与当前故障无关的处置建议。
     */
    fun onEpisodeStart(id: String) {
        episode=id
        used.clear()
        manualRetries=0
        lastReason=null
        manualHint=null
        degradeFrom=0
    }

    /**
     * 用户按了「立即重试」：只清本集的预算（已经降下来的档位是既成事实，不倒退）。
     *
     * 返回 false = **归还额度已经用尽**：按钮仍然可用，但这一次不再多花预算。
     * 调用方必须据此决定其余几份额度要不要一起还（见 `resetBudgetsForManualRetry`）——
     * 早先这里静默返回、外面照样重置自动重试与画面重建，于是连点按钮可以无限批次地重来。
     */
    fun onManualRetry(): Boolean {
        if(manualRetries>=MANUAL_RETRY_LIMIT) return false
        manualRetries++
        used.clear()
        lastReason=null
        manualHint=null
        degradeFrom=0
        return true
    }

    /** 用户手动改清晰度 / 解码方式 / 退出播放页：全部让位，按他的选择来。 */
    fun onUserManualChange() {
        used.clear()
        manualRetries=0
        effectiveMaxQuality=null
        lastReason=null
        manualHint=null
        degradeFrom=0
    }

    /** 用户手动改**清晰度**：撤销自动降档与全部预算，之后完全按他设的上限走。 */
    fun onManualQualityChange() {
        used.clear()
        manualRetries=0
        effectiveMaxQuality=null
        lastReason=null
        manualHint=null
        degradeFrom=0
    }

    /**
     * 只是**退到后台**（息屏 / 切到别的应用）又回来：档位上限与预算**原样保留**。
     *
     * 退到后台不是「用户离开了这一集」：`onResume` 会按同一集、同一位置接着播。
     * 早先那条路走的是 [onUserManualChange]（连档位上限一起清掉），于是回到前台
     * 又拿 1080 去撞一次必然失败的起播——黑一下、转一圈、再降一次、再弹一遍同样的提示。
     * 息屏是电视盒子上最常见的动作，一天能走十几次。
     *
     * 真正离开播放页（[MainActivity.returnToDetail] / 换剧 / 销毁实例）仍然走
     * [onUserManualChange]：那时「这一台机器这一刻放不了」的结论本就该丢掉。
     */
    fun onLeavePlaybackToBackground() { /* 有意留空：见上面的说明 */ }

    /**
     * 用户手动改**解码方式**。
     *
     * 不改 [effectiveMaxQuality]：他只是换了解码器，不代表要把已经降下来的档位退回去
     *（退回去只会再撞一次同样的失败）。只把「请手动换软解」那条建议消掉。
     */
    fun onManualDecoderChange() {
        manualHint=null
    }

    fun setMaxQuality(value: Int) { effectiveMaxQuality=value }
    fun note(reason: DegradationKind) { lastReason=reason }
    fun noteManualHint(text: String) { manualHint=text }

    fun usedCount(kind: DegradationKind,media: MediaKind): Int = used[key(kind,media)] ?: 0

    /**
     * 本集已用预算的一行摘要（调试面板用）。
     *
     * 用户报「老是降清晰度」时，这一行能直接看出是**预算没生效**（次数超过上限）
     * 还是**真的连降了两次**（次数就是 2，说明片源那几档都解不了）。
     */
    fun budgetSummary(): String {
        val audio=usedCount(DegradationKind.SAME_QUALITY_REBUILD,MediaKind.AUDIO)
        return "重建 ${usedCount(DegradationKind.SAME_QUALITY_REBUILD,MediaKind.VIDEO)}/$SAME_REBUILD_LIMIT" +
            " 降档 ${usedCount(DegradationKind.QUALITY_DOWN,MediaKind.VIDEO)}/$QUALITY_DOWN_LIMIT" +
            (if(audio>0) " 音频重建 $audio/$AUDIO_REBUILD_LIMIT" else "")
    }

    /** 记一次降档的起点（见 [degradeFrom]）。 */
    fun noteQualityDown(from: Int) { if(from>0) degradeFrom=from }

    /**
     * 上一次降档是否**没有换来更低的一档**：这一次失败仍停在起点或更高的档位上。
     *
     * 成立时再降一次只会拿到同一条流，`decide` 直接给出手动建议，不再消费预算。
     */
    fun qualityDownIneffective(current: Int)=degradeFrom>0 && current>=degradeFrom

    /** 本集有没有发生过任何降级 / 有没有待用户处理的手动建议（面板据此决定要不要显示那一行）。 */
    fun touched(): Boolean = used.isNotEmpty() || lastReason!=null || manualHint!=null

    fun canConsume(kind: DegradationKind,media: MediaKind): Boolean = usedCount(kind,media)<limitOf(kind,media)

    fun consume(kind: DegradationKind,media: MediaKind) {
        if(!canConsume(kind,media)) return
        used[key(kind,media)]=usedCount(kind,media)+1
        lastReason=kind
    }

    private fun key(kind: DegradationKind,media: MediaKind)="$episode:$kind:$media"

    /**
     * 预算表：视频每集同档重建一次、最多再连降两档；音频与档位无关，只有「同档重建」一维。
     */
    private fun limitOf(kind: DegradationKind,media: MediaKind): Int = when {
        media==MediaKind.AUDIO ->
            if(kind==DegradationKind.SAME_QUALITY_REBUILD) AUDIO_REBUILD_LIMIT else 0
        kind==DegradationKind.SAME_QUALITY_REBUILD -> SAME_REBUILD_LIMIT
        else -> QUALITY_DOWN_LIMIT
    }

    companion object {
        /**
         * 每集只做一次「同档重建」。
         *
         * 一次就够：解码器实例被占是瞬态的，重开一次没救回来，说明问题不在「这一次初始化」
         * 而在这一档本身（或者这颗解码器彻底坏了），再重建只是让用户多看一次黑屏。
         */
        const val SAME_REBUILD_LIMIT=1

        /** 每集最多自动降两档：1080 → 720 → 480，再低就没有意义了。 */
        const val QUALITY_DOWN_LIMIT=2

        /** 每集最多「归还」两次手动重试的预算（见 [manualRetries]）。 */
        const val MANUAL_RETRY_LIMIT=2

        /**
         * 音频轨的同档重建额度：每集一次。
         *
         * 音频与清晰度、解码方式都无关，能做的只有「把解码器放掉重开」。
         * 4001（init 失败）/ 4003（解到坏帧）在音频上同样是瞬态的——早先这一维是 0，
         * 于是一次 AAC 抖动就把用户直接扔进错误页，而那页面上没有任何对音频有效的按钮。
         */
        const val AUDIO_REBUILD_LIMIT=1
    }
}

/**
 * 提示去重：同一个 key 在一次播放里只弹一次。
 *
 * 连播场景下每集都会重新判断档位，但「CDN 缺 1080P」是客观事实，不是这一集的新问题——
 * 每集弹一次会把提示变成噪音。
 */
class ToastDeduper(private val capacity: Int = 64) {
    private val shown=linkedSetOf<String>()

    /** 这个 key 第一次出现时返回 true（调用方据此真的弹）。 */
    fun shown(key: String): Boolean {
        if(key in shown) return false
        if(shown.size>=capacity) shown.remove(shown.first())
        shown+=key
        return true
    }

    fun clear() { shown.clear() }
}

object PlaybackDegradation {

    /** 比当前低、且最高的一档；没有就返回 null。 */
    fun lowerThan(available: List<Int>,current: Int): Int? =
        available.filter { it>0 && it<current }.maxOrNull()

    /**
     * 软解建议档位：**最高 720，且不高于 [cap]**。
     *
     * 判据不做运行时掉帧探测（误报率高），只按「盒子上的 HEVC 软解能跑到哪一档」保守给：
     * 720 在弱 CPU 上普遍能跑，1080 基本必掉帧。没有 ≤720 的档时取最低那档。
     *
     * [cap] 是调用方给的「允许的最高一档」（**含**），默认不设限（只在没有失败上下文时用）；
     * 传 0 表示「没有任何可接受档位」，返回 0，调用方据此**不显示**按钮。
     *
     * 为什么必须有这个参数：[FailureAttribution.available] 是 CDN 这一次发的**全表**，
     * 降档不会让它缩短。降到 480 之后它仍然是 `[1080, 720, 480]`，不设上限就会建议
     * 「换到 720P」——把清晰度抬回刚刚失败的那一档，硬解不变、重取一次地址，
     * 必然复现同一类失败，只多一轮 500~1100ms 取地址与一次黑屏。
     */
    fun softwareQuality(available: List<Int>, cap: Int = Int.MAX_VALUE): Int {
        val usable=available.filter { it>0 && it<=cap }
        if(usable.isEmpty()) return 0
        return usable.filter { it<=720 }.maxOrNull() ?: usable.min()
    }

    /**
     * 自动降级走完也放不了时给用户的手动处置建议。
     *
     * 只有两种：
     * * 硬解放不了 → 换成**软件解码**（停在当前档）+ 换到软解跑得动的档；
     * * 已经在软解 → 只能继续降档。
     *
     * 两句话都必须点明**具体档位**，否则用户不知道「支持的清晰度」到底是哪一档。
     *
     * 建议的档位必须**严格低于**刚刚失败的那一档（[lowerThan]）：同一档换个说法再试一次，
     * 结果与刚才完全一样——`decide` 正是把这档的预算用完了才走到这里。早先这里直接取
     * `softwareQuality(available)`，于是 480 失败后仍然建议「720P 及以下」，
     * 把刚失败的那一档包进了建议范围。
     *
     * 没有更低的档时不能假装还有：那说明这台设备连全表最低档都放不了，只能如实说。
     */
    fun manualHint(attr: FailureAttribution): String {
        val lower=lowerThan(attr.available,attr.currentQuality)
        val software=if(lower!=null) softwareQuality(attr.available,lower) else 0
        val target=if(software>0) "${software}P 及以下" else null
        return when(attr.currentMode) {
            VideoDecoderMode.HARDWARE ->
                if(target!=null)
                    "本机硬解不支持 ${attr.currentQuality}P（HEVC），请在播放设置里切换为软件解码，并选择 $target 的清晰度。"
                else
                    "本机硬解不支持 ${attr.currentQuality}P（HEVC），请切换为软件解码；已经没有更低的清晰度可选。"
            VideoDecoderMode.SOFTWARE ->
                if(target!=null)
                    "软件解码也放不了 ${attr.currentQuality}P（HEVC），请在播放设置里选择 $target 的清晰度。"
                else
                    "软件解码也放不了 ${attr.currentQuality}P（HEVC），已经没有更低的清晰度可选，请换一部或稍后重试。"
        }
    }

    /**
     * 决策的唯一入口。
     *
     * 顺序即优先级，命中即返回。返回 [RetryDecision.GiveUp] = 交给错误页
     * （预算耗尽 / 音频 / 降无可降），同时把 [manualHint] 写进会话，
     * 界面据此提示用户手动换软解 / 换清晰度。
     */
    fun decide(attr: FailureAttribution,session: SessionPlaybackPolicy): RetryDecision {
        // 音频：`decoderSelector` 里音频走全表（不受硬解 / 软解档位影响），
        // ExoPlayer 自己的 fallback 会在厂商 AAC 失败之后落到 `c2.android.aac.decoder`。
        // 走到这里说明本机所有音频解码器都起不来——换档位、换解码方式都救不回来。
        //
        // 但 4001 / 4003 仍然可能是**瞬态**的（实例被占 / 解到一帧坏样本），
        // 而音频唯一的自动处置就是「放掉解码器重开一次」，不降档、不改解码方式。
        if(attr.media==MediaKind.AUDIO) {
            if(rebuildable(attr.errorCode) &&
                session.canConsume(DegradationKind.SAME_QUALITY_REBUILD,MediaKind.AUDIO)) {
                session.consume(DegradationKind.SAME_QUALITY_REBUILD,MediaKind.AUDIO)
                return RetryDecision.Retry(
                    PlaybackRetryPlan(
                        maxQuality=null,
                        reason=DegradationKind.SAME_QUALITY_REBUILD,
                        // 静默：用户什么都没做错，声音自己回来就行。
                        toast=null
                    )
                )
            }
            return RetryDecision.GiveUp(GiveUpReason.AUDIO_NO_RECOVERY)
        }

        // ① 先试**同档重建**：4001（初始化失败）/ 4003（解码中失败）在电视盒子上多半是
        //   解码器实例被别的应用占满、`configure` 偶发失败，或者解到一帧坏样本。
        //   重开一次解码器往往就好了，而换档位要重取一条地址、还要永久降一档——
        //   代价与收益不成比例。4004 / 4005（超出能力 / 格式不支持）不走这一级：
        //   那才是「这一档真的解不了」，重建多少次都一样。
        if(rebuildable(attr.errorCode) &&
            session.canConsume(DegradationKind.SAME_QUALITY_REBUILD,MediaKind.VIDEO)) {
            session.consume(DegradationKind.SAME_QUALITY_REBUILD,MediaKind.VIDEO)
            return RetryDecision.Retry(
                PlaybackRetryPlan(
                    maxQuality=null,
                    reason=DegradationKind.SAME_QUALITY_REBUILD,
                    // 静默：用户什么都没做错，画面自己回来就行，不必为一次成功重建弹提示。
                    toast=null
                )
            )
        }
        val lower=lowerThan(attr.available,attr.currentQuality)
        if(lower!=null) {
            // 上一次降档没有换来更低的一档（CDN 只回了一条兼容资源）：
            // 再降一次拿到的是同一条流，只会多一轮 500~1100ms 取地址与一次黑屏。
            if(session.qualityDownIneffective(attr.currentQuality)) {
                session.noteManualHint(manualHint(attr))
                return RetryDecision.GiveUp(GiveUpReason.NO_LOWER_TIER)
            }
            if(!session.canConsume(DegradationKind.QUALITY_DOWN,MediaKind.VIDEO)) {
                session.noteManualHint(manualHint(attr))
                return RetryDecision.GiveUp(GiveUpReason.BUDGET_EXHAUSTED)
            }
            session.consume(DegradationKind.QUALITY_DOWN,MediaKind.VIDEO)
            return RetryDecision.Retry(
                PlaybackRetryPlan(
                    maxQuality=lower,
                    reason=DegradationKind.QUALITY_DOWN,
                    toast=when(attr.currentMode) {
                        VideoDecoderMode.HARDWARE -> "硬解不支持 ${attr.currentQuality}P，已降为 ${lower}P"
                        VideoDecoderMode.SOFTWARE -> "已降为 ${lower}P（原 ${attr.currentQuality}P 无法解码）"
                    },
                    fromQuality=attr.currentQuality
                )
            )
        }
        // 降无可降：不再自动做任何事，把「换软解 / 换到哪一档」交给用户。
        session.noteManualHint(manualHint(attr))
        return RetryDecision.GiveUp(GiveUpReason.NO_LOWER_TIER)
    }

    /** 这两个错误码值得先原地重开一次解码器（见 [DegradationKind.SAME_QUALITY_REBUILD]）。 */
    private fun rebuildable(code: Int): Boolean =
        code==PlaybackErrorCode.DECODER_INIT_FAILED || code==PlaybackErrorCode.DECODING_FAILED
}
