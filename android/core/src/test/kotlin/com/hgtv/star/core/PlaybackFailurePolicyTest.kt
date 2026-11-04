// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两端共用的失败处置：钉住**判定顺序**与**额度记账的时机**。
 *
 * 这两件事最容易在各自实现里悄悄分叉：电视先救地址再降档，手机直接降档；
 * 电视在重试真正跑起来时记账，手机在排任务时记账——同一次失败烧掉的次数就不一样了。
 */
class PlaybackFailurePolicyTest {

    private fun attr(
        media: MediaKind = MediaKind.VIDEO,
        code: Int = 0,
        quality: Int = 1080,
        mode: VideoDecoderMode = VideoDecoderMode.HARDWARE,
        available: List<Int> = listOf(1080, 720, 480),
    ) = FailureAttribution(media, code, quality, mode, available)

    private fun facts(
        code: Int = 0,
        media: MediaKind = MediaKind.VIDEO,
        retryable: Boolean = true,
        streamExpired: Boolean = false,
        queued: Boolean = false,
        queuedNearEnd: Boolean = false,
        noHardware: Boolean = false,
        autoRetryAllowed: Boolean = true,
        onRecommend: Boolean = false,
    ) = FailureFacts(
        errorCode = code,
        stage = PlaybackStage.OTHER,
        media = media,
        retryable = retryable,
        streamExpired = streamExpired,
        queued = queued,
        queuedNearEnd = queuedNearEnd,
        noHardwareDecoder = noHardware,
        autoRetryAllowed = autoRetryAllowed,
        onRecommend = onRecommend,
    )

    private fun decide(
        f: FailureFacts,
        a: FailureAttribution = attr(code = f.errorCode, media = f.media),
        ledger: PlaybackAttemptLedger = PlaybackAttemptLedger(),
        session: SessionPlaybackPolicy = SessionPlaybackPolicy(),
        recommendFailures: Int = 0,
    ) = PlaybackFailurePolicy.decide(f, a, ledger, session, recommendFailures)

    // —— ① 地址 / 索引 / 连接层过期 ——

    @Test fun `地址过期第一次重取地址并记账`() {
        val ledger = PlaybackAttemptLedger()
        val action = decide(facts(streamExpired = true), ledger = ledger)
        assertTrue(action is FailureAction.RefetchStream)
        action as FailureAction.RefetchStream
        assertEquals(0, action.episodeOffset)
        assertTrue(action.consumeStreamBudget)
        // 记了账：同一集再来一次就不该再救。
        assertTrue(ledger.streamRetried("1080"))
    }

    @Test fun `同一集地址过期第二次落到错误页`() {
        val ledger = PlaybackAttemptLedger()
        decide(facts(streamExpired = true), ledger = ledger)
        val second = decide(facts(streamExpired = true), ledger = ledger)
        assertTrue(second is FailureAction.ShowError)
    }

    @Test fun `预加载阶段的失败不消耗这一集的额度`() {
        val ledger = PlaybackAttemptLedger()
        // 队列项在片尾窗口之外报错：当前这一集还在正常播，救回来就好，不该记账。
        val action = decide(facts(streamExpired = true, queued = true, queuedNearEnd = false), ledger = ledger)
        assertTrue(action is FailureAction.RefetchStream)
        assertTrue(!(action as FailureAction.RefetchStream).consumeStreamBudget)
        assertTrue(!ledger.streamRetried("1080"))
    }

    // —— ② 推荐页 ——

    @Test fun `推荐页放不了就换下一部`() {
        val action = decide(facts(onRecommend = true), recommendFailures = 0)
        assertEquals(FailureAction.SkipRecommend, action)
    }

    @Test fun `推荐页连续失败到上限才停手`() {
        val limit = PlaybackFailurePolicy.RECOMMEND_SKIP_LIMIT
        assertEquals(FailureAction.SkipRecommend, decide(facts(onRecommend = true), recommendFailures = limit - 1))
        assertEquals(FailureAction.GiveUpRecommend, decide(facts(onRecommend = true), recommendFailures = limit))
    }

    // —— ③ 队列项 ——

    @Test fun `片尾队列项失败从下一集重来一次`() {
        val ledger = PlaybackAttemptLedger()
        val action = decide(facts(queued = true, queuedNearEnd = true), ledger = ledger)
        assertTrue(action is FailureAction.RefetchStream)
        action as FailureAction.RefetchStream
        assertEquals(1, action.episodeOffset)
        assertTrue(action.fromStart)
        assertEquals(1, ledger.autoNextFailures)
    }

    @Test fun `片尾队列项第二次失败不再重切`() {
        val ledger = PlaybackAttemptLedger()
        decide(facts(queued = true, queuedNearEnd = true), ledger = ledger)
        val second = decide(facts(queued = true, queuedNearEnd = true), ledger = ledger)
        assertTrue(second is FailureAction.ShowError)
    }

    @Test fun `队列项在中段报错时原地接回当前集`() {
        val action = decide(facts(queued = true, queuedNearEnd = false))
        assertEquals(FailureAction.RecoverQueuedCurrent, action)
    }

    // —— ④ 解码类 ——

    @Test fun `解码器初始化失败先同档重建`() {
        val action = decide(facts(code = PlaybackErrorCode.DECODER_INIT_FAILED))
        assertTrue(action is FailureAction.Replay)
        assertEquals(DegradationKind.SAME_QUALITY_REBUILD, (action as FailureAction.Replay).plan.reason)
    }

    @Test fun `本机没有硬解时直接给软解建议不再降档`() {
        val session = SessionPlaybackPolicy()
        val action = decide(
            facts(code = PlaybackErrorCode.DECODER_QUERY_FAILED, noHardware = true),
            session = session,
        )
        assertTrue(action is FailureAction.ShowError)
        assertEquals(GiveUpReason.NO_LOWER_TIER, (action as FailureAction.ShowError).reason)
        // 必须留一条「换软解 + 换到哪一档」的建议，错误页据此说人话。
        assertTrue(!session.manualHint.isNullOrBlank())
    }

    @Test fun `降无可降时错误页带原因`() {
        val session = SessionPlaybackPolicy()
        val action = decide(
            facts(code = PlaybackErrorCode.FORMAT_EXCEEDS_CAPABILITIES),
            attr(code = PlaybackErrorCode.FORMAT_EXCEEDS_CAPABILITIES, quality = 480, available = listOf(480)),
            session = session,
        )
        assertTrue(action is FailureAction.ShowError)
        assertEquals(GiveUpReason.NO_LOWER_TIER, (action as FailureAction.ShowError).reason)
    }

    // —— ⑤ 网络 / 音频输出 ——

    @Test fun `网络类失败落到错误页并排一次自动重试`() {
        val action = decide(facts(code = 2000))
        assertTrue(action is FailureAction.ShowError)
        action as FailureAction.ShowError
        assertNull(action.reason)
        assertEquals(3_000L, action.autoRetryDelayMs)
    }

    @Test fun `不允许自动重试时不给倒计时`() {
        val action = decide(facts(code = 2000, autoRetryAllowed = false))
        assertNull((action as FailureAction.ShowError).autoRetryDelayMs)
    }

    @Test fun `不可重试的错误码不给倒计时`() {
        // 1000 = 源类（不是 3xxx/4xxx，不进解码降级那条路），且标成不可重试。
        val action = decide(facts(code = 1000, retryable = false))
        assertNull((action as FailureAction.ShowError).autoRetryDelayMs)
    }

    // —— 错误页文案与按钮 ——

    @Test fun `音频失败不给换软解与换档位`() {
        val a = attr(media = MediaKind.AUDIO, code = PlaybackErrorCode.DECODING_FAILED)
        val caps = FailurePresentation.capabilities(facts(code = PlaybackErrorCode.DECODING_FAILED, media = MediaKind.AUDIO), a, true)
        assertEquals(setOf(FailureCapability.RETRY), caps)
    }

    @Test fun `换档目标必须严格低于刚刚失败的那一档`() {
        val a = attr(quality = 480, available = listOf(1080, 720, 480))
        assertEquals(0, FailurePresentation.swapTarget(a))
    }

    @Test fun `没有 HEVC 硬解时错误页直接说换软解`() {
        val message = FailurePresentation.message(facts(code = PlaybackErrorCode.DECODING_FAILED), null, hevcSupported = false)
        assertTrue(message.contains("软件解码"))
    }
}
