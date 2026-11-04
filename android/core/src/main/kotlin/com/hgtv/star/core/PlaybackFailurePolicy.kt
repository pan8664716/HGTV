// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 一次播放失败的**事实**：平台层负责从异常与现场里读出这些布尔量，决策层只认它们。
 *
 * ## 为什么要有这一层
 *
 * 电视与手机原先各写一份「失败之后做什么」：电视有地址自愈、队列项接回、换设备号、
 * 自动重试倒计时；手机只有「解码降级 + 盲目重试 + 一张卡片」。结果同一个故障在两端
 * 表现完全不同——电视能自己救回来，手机直接停在黑屏上（2026-11 现场：「第三集播完
 * 进不了第四集，退也退不出去」）。
 *
 * 现在判断只留一份（[PlaybackFailurePolicy.decide]），两端只负责**执行**与**画界面**。
 * 事实怎么读（`UnknownHostException`、`Content-Range` 对不上……）仍留在各端，
 * 因为那需要 media3 / 平台 API；但「读到之后怎么处置」只有这一处。
 */
data class FailureFacts(
    /** ExoPlayer 的 `errorCode`；不是 `PlaybackException` 时传 0。 */
    val errorCode: Int,
    /** 稳定聚合键（[PlaybackStage]），错误页标题与调试面板都用它。 */
    val stage: String,
    /** 这条错误落在哪条轨上。 */
    val media: MediaKind,
    /** 值不值得等一会儿再来一次（`:shared` 的 `PlaybackFailures.isRetryable`）。 */
    val retryable: Boolean,
    /** 地址过期（HTTP 403 / 404、签名时效）。 */
    val streamExpired: Boolean = false,
    /** 这一集的文件换过了（`VideoChangedException`：索引与首块是上一版的）。 */
    val videoChanged: Boolean = false,
    /** 视频 CDN 域名解析不出来。 */
    val hostUnresolved: Boolean = false,
    /** 连接层就没通（超时 / 被拒 / TLS 握手失败）。 */
    val transportDead: Boolean = false,
    /** 失败的是**队列里那一集**（片尾预热挂进去的下一集），不是正在播的这一集。 */
    val queued: Boolean = false,
    /** 队列项是在片尾窗口内报的错（= 下一集真的没接上）。 */
    val queuedNearEnd: Boolean = false,
    /** 本机一颗 HEVC 硬解都没有：4002 与「这一档太高」无关，降档只会白烧。 */
    val noHardwareDecoder: Boolean = false,
    /** 用户当前是否允许自动重试（前台、没被定时关闭停住、没被用户点「停止」）。 */
    val autoRetryAllowed: Boolean = true,
    /** 当前页面是推荐页：放不了就换下一部，不摆播放页那套错误界面。 */
    val onRecommend: Boolean = false,
) {
    val videoProblem: Boolean get() = media == MediaKind.VIDEO

    /**
     * 解码类（4xxx）才进降级决策。
     *
     * **不含 3xxx**：那是容器 / 封装解析失败，换档位、换解码方式都不作用在它身上，
     * 降档只会白烧一轮取地址与一次黑屏。1xxx / 2xxx 同理走重试与错误页。
     */
    val decoding: Boolean get() = errorCode in 4000..4999
    val audioOutput: Boolean get() = errorCode in 5000..5999

    /**
     * 画面侧的问题（封装解析 3xxx + 解码 4xxx）。
     *
     * **只用于文案**：3xxx 是容器 / 封装读不出来，换档位、换解码方式都不作用在它身上，
     * 因此不进降级决策（[decoding] 只认 4xxx）；但对用户来说两者都是「这一段画面放不了」，
     * 说同一句话就够了。
     */
    val pictureProblem: Boolean get() = decoding || errorCode in 3000..3999

    /** 缓存里的东西过期了：地址 / 索引 / 连接层，作废后重取一次就能好。 */
    val staleCache: Boolean
        get() = streamExpired || videoChanged || hostUnresolved || transportDead

    /** 队列项在片尾窗口**之外**报错：当前这一集还在正常播，IO 只可能来自后台预加载。 */
    val preloadFailure: Boolean get() = queued && !queuedNearEnd
}

/**
 * 决策层给出的**处置**。执行方式各端自己定，语义只有这一份。
 */
sealed interface FailureAction {
    /**
     * 缓存里的地址 / 索引 / 连接层坏了：作废后重取一条地址重来。
     *
     * [episodeOffset] 0 = 当前这一集，1 = 下一集（片尾队列项没接上）。
     * [fromStart] 表示从这一集开头重来（队列项坏了必须从头，当前位置属于上一集）。
     * [consumeStreamBudget] 为 false 时**不记账**：后台预加载阶段的失败不该花掉
     * 用户还没开始看的那一集的自愈额度。
     */
    data class RefetchStream(
        val episodeOffset: Int,
        val fromStart: Boolean,
        val dropTransport: Boolean,
        val consumeStreamBudget: Boolean,
    ) : FailureAction

    /**
     * 队列里那一集坏了，而当前这一集还在正常播：**原地把当前集接回来**。
     *
     * 执行失败（额度用尽 / 重开没成功）时，调用方要带着 `queued = false` 重新决策一次。
     */
    data object RecoverQueuedCurrent : FailureAction

    /** 解码类：同档重建 / 降档（决策在 [PlaybackDegradation] 里，这里只是转发）。 */
    data class Replay(val plan: PlaybackRetryPlan) : FailureAction

    /**
     * 停在这里交给用户。
     *
     * [autoRetryDelayMs] 非空时**同时**排一次自动重试：错误页照常显示，
     * 状态行给出倒计时，用户可点「停止自动重试」——电视与手机同一个行为。
     */
    data class ShowError(
        val reason: GiveUpReason?,
        val autoRetryDelayMs: Long?,
    ) : FailureAction

    /** 推荐页：换下一部。 */
    data object SkipRecommend : FailureAction

    /** 推荐页：连续几部都放不了，停在一句提示上，不再自动翻。 */
    data object GiveUpRecommend : FailureAction
}

/**
 * 失败处置的**唯一决策入口**：电视与手机都调这里。
 *
 * ## 判定顺序（顺序本身是规则，不要随手调换）
 *
 * 1. **地址 / 索引 / 连接层过期** —— 先救，救回来就不该记一条故障；
 * 2. **推荐页** —— 那一页没有选集也没有详情可回，规则是「换下一部」；
 * 3. **队列项** —— 片尾没接上就重切下一集（每集一次），中段坏了就把当前集接回来；
 * 4. **解码类** —— 交给 [PlaybackDegradation]（同档重建 / 降档 / 放弃）；
 * 5. 其余 —— 错误页，可重试的同时排一次自动重试。
 *
 * ## 副作用
 *
 * 这个函数会**消费额度**（[PlaybackAttemptLedger] 的地址自愈与连播链计数），
 * 因为「什么时候记账」本身就是规则的一部分——放到各端去记，两端迟早记在不同时刻
 * （电视在重试真正跑起来时记，手机在排任务时记，于是同一次失败烧掉的次数不一样）。
 * 额度对象是纯 Kotlin 类，因此这个函数仍然可以单测。
 */
object PlaybackFailurePolicy {

    /** 推荐页连续失败几部才停手。 */
    const val RECOMMEND_SKIP_LIMIT = 3

    fun decide(
        facts: FailureFacts,
        /** 这一次失败的归因（档位、可用档位、当前解码方式），由平台层按真实那条流构造。 */
        attr: FailureAttribution,
        ledger: PlaybackAttemptLedger,
        session: SessionPlaybackPolicy,
        /** 推荐页已经连续失败了几部。 */
        recommendFailures: Int = 0,
    ): FailureAction {
        // ① 缓存里的东西过期了：作废后重取一条地址重来。
        if (facts.staleCache) {
            // 后台预加载阶段的失败不消耗这一集的额度：用户还没开始看这一集，
            // 它的自愈额度不该在这里花掉（详见 TV 端那段现场记录）。
            if (facts.preloadFailure) {
                return FailureAction.RefetchStream(
                    episodeOffset = 0,
                    fromStart = false,
                    dropTransport = true,
                    consumeStreamBudget = false,
                )
            }
            // 地址自愈的键带档位：换了上限就是另一条地址，值得再救一次。
            val key = "${attr.currentQuality}"
            if (!ledger.streamRetried(key)) {
                ledger.noteStreamRetry(key)
                return FailureAction.RefetchStream(
                    episodeOffset = if (facts.queuedNearEnd) 1 else 0,
                    fromStart = facts.queuedNearEnd,
                    dropTransport = true,
                    consumeStreamBudget = true,
                )
            }
            // 这一集已经救过一次了：继续往下走（通常是错误页）。
        }

        // ② 推荐页：放不了就换下一部。
        if (facts.onRecommend) {
            return if (recommendFailures + 1 > RECOMMEND_SKIP_LIMIT) {
                FailureAction.GiveUpRecommend
            } else {
                FailureAction.SkipRecommend
            }
        }

        // ③ 队列项（片尾预热挂进去的下一集）。
        if (facts.queued) {
            if (facts.queuedNearEnd) {
                // 片尾没接上：从下一集开头重切一次。只给一次——反复重切只会让画面一黑一亮地循环。
                if (ledger.autoNextFailures < 1) {
                    ledger.noteAutoNextFailure()
                    return FailureAction.RefetchStream(
                        episodeOffset = 1,
                        fromStart = true,
                        dropTransport = false,
                        consumeStreamBudget = false,
                    )
                }
            } else {
                // 中段报错：当前这一集还在正常播，坏的是后台预加载的那一条。原地接回当前集。
                return FailureAction.RecoverQueuedCurrent
            }
        }

        // ④ 解码类：同档重建一次 / 降档最多两次 / 放弃。
        if (facts.decoding) {
            val queryFailed = facts.errorCode == PlaybackErrorCode.DECODER_QUERY_FAILED && facts.videoProblem
            val noHardware = facts.videoProblem && facts.noHardwareDecoder
            if (queryFailed || noHardware) {
                // 任何档位都是同一张空表，降档只会白烧两次起播：直接把「切软解」摆出来。
                session.noteManualHint(PlaybackDegradation.manualHint(attr))
                return FailureAction.ShowError(GiveUpReason.NO_LOWER_TIER, autoRetryDelay(facts, ledger))
            }
            when (val decision = PlaybackDegradation.decide(attr, session)) {
                is RetryDecision.Retry -> return FailureAction.Replay(decision.plan)
                is RetryDecision.GiveUp ->
                    return FailureAction.ShowError(decision.reason, autoRetryDelay(facts, ledger))
            }
        }

        // ⑤ 其余（网络 / 超时 / 音频输出 / 未知）：错误页 + 自动重试。
        return FailureAction.ShowError(null, autoRetryDelay(facts, ledger))
    }

    /**
     * 下一次自动重试该等多久；不该排时返回 null。
     *
     * **只读不消费**：额度在重试真正跑起来那一刻才扣（[PlaybackAttemptLedger.consumeAttempt]），
     * 这样用户中途按了「停止自动重试」或退出播放页，不会白烧一次。
     */
    fun autoRetryDelay(facts: FailureFacts, ledger: PlaybackAttemptLedger): Long? {
        if (!facts.retryable || !facts.autoRetryAllowed) return null
        if (!ledger.canAttempt) return null
        return ledger.delayMillis
    }
}

/**
 * 错误页的**文案与能力**：两端说同一句话、给同一套按钮。
 *
 * 只有排版归各端（电视是控制条里的一行 + 一排按钮，手机是一张居中卡片），
 * 「说什么、能做什么」必须一致，否则同一个故障在两端读起来是两个问题。
 */
object FailurePresentation {

    /** 错误页正文。`hevcSupported` 为 false 时给的是「换软解」的具体动作，而不是笼统的重试。 */
    fun message(
        facts: FailureFacts,
        manualHint: String?,
        hevcSupported: Boolean,
    ): String = when {
        // 音频输出起不来：设备被别的应用占着，与片源无关。
        facts.audioOutput -> "音频输出初始化失败，可重试或稍后再试。"
        // 音频：走到这里说明本机的音频解码器全军覆没，换哪一档都一样。
        facts.pictureProblem && !facts.videoProblem -> "音频解码失败，这台设备无法播放该音轨。"
        // 解码类降无可降：直接给出手动处置建议（换软解 / 换到具体哪一档）。
        // 必须带 `pictureProblem` 门槛：这条建议是上一次解码失败留下的，若这一集随后因为
        // 网络超时进来，把「请切换为软件解码」顶上去就是一句与故障无关的误导。
        manualHint != null && facts.pictureProblem -> manualHint
        facts.pictureProblem && !hevcSupported -> "这台设备没有 HEVC 硬解，请在播放设置里切换为软件解码。"
        facts.pictureProblem -> "无法解码当前视频，可在播放设置里换更低清晰度或软件解码。"
        else -> "播放失败，可重试或切换清晰度。"
    }

    /**
     * 错误页上该出现哪些手动出口。
     *
     * 判据与「换到哪一档」同源：音频走全表选择器，换解码方式与换档位都不作用在它身上，
     * 因此那两颗按钮对音频必须**不出现**——点了只会再撞一次。
     */
    fun capabilities(
        facts: FailureFacts,
        attr: FailureAttribution,
        hevcSupported: Boolean,
    ): Set<FailureCapability> {
        val result = linkedSetOf(FailureCapability.RETRY)
        if (facts.videoProblem && attr.currentMode == VideoDecoderMode.HARDWARE && hevcSupported) {
            result += FailureCapability.SWITCH_SOFTWARE
        }
        if (facts.videoProblem && swapTarget(attr) > 0) result += FailureCapability.CHANGE_QUALITY
        return result
    }

    /**
     * 「换到 xP」的目标档位；0 = 没有更低的档可换（按钮不该出现）。
     *
     * 必须**严格低于**刚刚失败的那一档：早先直接取 `softwareQuality(available)`，
     * 降到 480 失败后按钮仍写「换到 720P」，点下去把清晰度抬回去、必然复现同一类失败。
     */
    fun swapTarget(attr: FailureAttribution): Int {
        val lower = PlaybackDegradation.lowerThan(attr.available, attr.currentQuality) ?: return 0
        return PlaybackDegradation.softwareQuality(attr.available, lower)
    }

    /** 切软解时应该同时落到哪一档（0 = 停在原档）。 */
    fun softwareTarget(attr: FailureAttribution): Int =
        PlaybackDegradation.softwareQuality(attr.available, attr.currentQuality)
}

/** 错误页上的一颗手动出口。 */
enum class FailureCapability {
    /** 立即重试。 */
    RETRY,

    /** 切换为软件解码。 */
    SWITCH_SOFTWARE,

    /** 换到更低的一档清晰度。 */
    CHANGE_QUALITY,
}
