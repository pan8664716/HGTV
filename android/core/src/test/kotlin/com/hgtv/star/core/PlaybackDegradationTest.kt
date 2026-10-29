// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 降级决策的规格表（与 `PlaybackDegradation` 的注释一一对应）。
 *
 * 这些用例只钉「决策」，不碰 Android：档位集合、错误码、解码方式都是入参，
 * 因此不需要模拟器，也不依赖任何设备。
 *
 * **前提：全站只有 HEVC 一种编码**，所以这里没有「换编码族」的用例，
 * 也没有「自动切软解」的用例——那两维已经整维删除。
 */
class PlaybackDegradationTest {

    private fun attr(
        code: Int,
        quality: Int = 1080,
        mode: VideoDecoderMode = VideoDecoderMode.HARDWARE,
        media: MediaKind = MediaKind.VIDEO,
        available: List<Int> = listOf(1080,720,480)
    )=FailureAttribution(media,code,quality,mode,available)

    /** 这一次决策给出的重试计划；放弃时为 null。 */
    private fun retry(attr: FailureAttribution,session: SessionPlaybackPolicy)=
        (PlaybackDegradation.decide(attr,session) as? RetryDecision.Retry)?.plan

    /** 这一次决策放弃的理由；给计划时为 null。 */
    private fun giveUp(attr: FailureAttribution,session: SessionPlaybackPolicy)=
        (PlaybackDegradation.decide(attr,session) as? RetryDecision.GiveUp)?.reason

    /**
     * 音频 4001：**先原档重建一次**（不降档、不改解码方式），重建也失败才放弃。
     *
     * 早先这里直接返回 null——一次瞬态 AAC 失败就把用户扔进错误页，
     * 而那页面上两颗按钮（换软解 / 换档位）对音频完全无效。
     */
    @Test fun audioRebuildsOnceThenGivesUp() {
        val session=SessionPlaybackPolicy()
        session.onEpisodeStart("ep1")
        val first=retry(attr(PlaybackErrorCode.DECODER_INIT_FAILED,media=MediaKind.AUDIO),session)!!
        assertEquals(DegradationKind.SAME_QUALITY_REBUILD,first.reason)
        assertNull(first.maxQuality)
        assertNull(first.toast)
        // 音频永远不动档位。
        assertEquals(0,session.usedCount(DegradationKind.QUALITY_DOWN,MediaKind.VIDEO))
        assertEquals(1,session.usedCount(DegradationKind.SAME_QUALITY_REBUILD,MediaKind.AUDIO))
        // 第二次：额度用尽，放弃，并且理由要说清是「音频没有可换的维度」。
        assertEquals(
            GiveUpReason.AUDIO_NO_RECOVERY,
            giveUp(attr(PlaybackErrorCode.DECODER_INIT_FAILED,media=MediaKind.AUDIO),session)
        )
        // 音频不给手动建议：错误页对音频有自己的文案，不需要「换软解 / 换档位」。
        assertNull(session.manualHint)
    }

    /** 音频 4004 / 4005（格式层面）不重建：重建多少次都一样。 */
    @Test fun audioFormatLimitsGiveUpImmediately() {
        val session=SessionPlaybackPolicy()
        session.onEpisodeStart("ep1")
        assertEquals(
            GiveUpReason.AUDIO_NO_RECOVERY,
            giveUp(attr(PlaybackErrorCode.FORMAT_EXCEEDS_CAPABILITIES,media=MediaKind.AUDIO),session)
        )
    }

    /**
     * 4001：**先原档重建一次**（不动清晰度、不重取地址），重建也失败才降一档到 720。
     *
     * 顺序是本文件最值钱的一条：降档要重取一条地址、还要把「这一台机器放不了这一档」
     * 记进会话，代价远大于把解码器放掉重开。
     */
    @Test fun decoderInitRebuildsBeforeDowngrading() {
        val session=SessionPlaybackPolicy()
        session.onEpisodeStart("ep1")
        val first=retry(attr(PlaybackErrorCode.DECODER_INIT_FAILED),session)!!
        assertEquals(DegradationKind.SAME_QUALITY_REBUILD,first.reason)
        assertNull(first.maxQuality)
        // 重建是静默的：用户什么都没做错，画面自己回来就行。
        assertNull(first.toast)
        val second=retry(attr(PlaybackErrorCode.DECODER_INIT_FAILED),session)!!
        assertEquals(DegradationKind.QUALITY_DOWN,second.reason)
        assertEquals(720,second.maxQuality)
        // 起点档位要带出去：会话层据此判断「降了等于没降」。
        assertEquals(1080,second.fromQuality)
    }

    /** 4003（解到一半坏帧 / SPS 变更）与 4001 同权：也先重建一次。 */
    @Test fun decodingFailedRebuildsFirst() {
        val session=SessionPlaybackPolicy()
        session.onEpisodeStart("ep1")
        assertEquals(
            DegradationKind.SAME_QUALITY_REBUILD,
            retry(attr(PlaybackErrorCode.DECODING_FAILED),session)!!.reason
        )
    }

    /** 4004 / 4005 是「这一档真的超出能力」：重建多少次都一样，直接降档。 */
    @Test fun formatLimitsSkipRebuild() {
        listOf(PlaybackErrorCode.FORMAT_EXCEEDS_CAPABILITIES,PlaybackErrorCode.FORMAT_UNSUPPORTED).forEach { code ->
            val session=SessionPlaybackPolicy()
            session.onEpisodeStart("ep1")
            val plan=retry(attr(code),session)!!
            assertEquals(DegradationKind.QUALITY_DOWN,plan.reason)
            assertEquals(720,plan.maxQuality)
            assertEquals(0,session.usedCount(DegradationKind.SAME_QUALITY_REBUILD,MediaKind.VIDEO))
        }
    }

    /** 重建预算每集只有一次：第二次 4001 直接进降档，不会再来一次假重建。 */
    @Test fun rebuildBudgetIsOnePerEpisode() {
        val session=SessionPlaybackPolicy()
        session.onEpisodeStart("ep1")
        session.consume(DegradationKind.SAME_QUALITY_REBUILD,MediaKind.VIDEO)
        assertFalse(session.canConsume(DegradationKind.SAME_QUALITY_REBUILD,MediaKind.VIDEO))
        // 换一集：预算回来。
        session.onEpisodeStart("ep2")
        assertTrue(session.canConsume(DegradationKind.SAME_QUALITY_REBUILD,MediaKind.VIDEO))
    }

    /** 预算有界：降档最多两次，第三次同一维度不再给计划，而是留下手动处置建议。 */
    @Test fun qualityDowngradeBudgetIsBounded() {
        val session=SessionPlaybackPolicy()
        val chain=listOf(1080,720,480).map { q -> retry(attr(PlaybackErrorCode.FORMAT_EXCEEDS_CAPABILITIES,q),session)?.maxQuality }
        assertEquals(listOf(720,480,null),chain)
        // 已经降无可降：必须给用户一条明确的手动建议，而不是闷着进错误页。
        val hint=session.manualHint
        assertTrue(hint ?: "",(hint ?: "").contains("软件解码"))
        assertTrue(hint ?: "",(hint ?: "").contains("480P"))
        // 理由必须是「没有更低的档」，不是「预算用尽」——两者的界面动作不一样。
        assertEquals(
            GiveUpReason.NO_LOWER_TIER,
            giveUp(attr(PlaybackErrorCode.FORMAT_EXCEEDS_CAPABILITIES,480),session)
        )
    }

    /** 还有更低的档、但预算已经用尽：理由必须是 BUDGET_EXHAUSTED。 */
    @Test fun budgetExhaustedIsReportedSeparately() {
        val session=SessionPlaybackPolicy()
        session.onEpisodeStart("ep1")
        // 先把「同档重建」那一份额度用掉，否则 4003 会先走重建（那是另一条用例）。
        session.consume(DegradationKind.SAME_QUALITY_REBUILD,MediaKind.VIDEO)
        val available=listOf(1080,720,480,360)
        // 1080 → 720、720 → 480：两次预算用完，而 480 下面还有 360。
        assertEquals(720,retry(attr(PlaybackErrorCode.DECODING_FAILED,1080,available=available),session)!!.maxQuality)
        assertEquals(480,retry(attr(PlaybackErrorCode.DECODING_FAILED,720,available=available),session)!!.maxQuality)
        assertEquals(
            GiveUpReason.BUDGET_EXHAUSTED,
            giveUp(attr(PlaybackErrorCode.DECODING_FAILED,480,available=available),session)
        )
    }

    /**
     * 降档没有换来更低的一档（CDN 只回兼容资源）：不再消费第二次预算。
     *
     * 现场形态：请求 720，`fetchStream` 因为「上限内一档都没有」回了 1080。
     * 这一档刚刚才失败过，再降一次拿到的是同一条流，只会多一轮取地址与一次黑屏。
     */
    @Test fun ineffectiveDowngradeStopsTheLadder() {
        val session=SessionPlaybackPolicy()
        session.onEpisodeStart("ep1")
        session.consume(DegradationKind.SAME_QUALITY_REBUILD,MediaKind.VIDEO)
        val plan=retry(attr(PlaybackErrorCode.DECODING_FAILED,1080),session)!!
        assertEquals(720,plan.maxQuality)
        session.noteQualityDown(plan.fromQuality!!)
        // 换回来的还是 1080（兼容资源）：这一次必须直接放弃。
        assertEquals(
            GiveUpReason.NO_LOWER_TIER,
            giveUp(attr(PlaybackErrorCode.DECODING_FAILED,1080),session)
        )
        assertEquals(1,session.usedCount(DegradationKind.QUALITY_DOWN,MediaKind.VIDEO))
    }

    /** 已经是最低档、下面没有更低的档：重建用完之后立刻放弃，并建议换软解。 */
    @Test fun atLowestTierAsksTheUserToSwitchToSoftware() {
        val session=SessionPlaybackPolicy()
        session.onEpisodeStart("ep1")
        val rebuild=retry(
            attr(PlaybackErrorCode.DECODING_FAILED,480,available=listOf(1080,480)),session
        )!!
        assertEquals(DegradationKind.SAME_QUALITY_REBUILD,rebuild.reason)
        assertEquals(
            GiveUpReason.NO_LOWER_TIER,
            giveUp(attr(PlaybackErrorCode.DECODING_FAILED,480,available=listOf(1080,480)),session)
        )
        assertTrue(session.manualHint!!.contains("软件解码"))
    }

    /** 已经在软解：重建一次 → 再降一档；降无可降时只建议换清晰度。 */
    @Test fun softwareModeOnlySuggestsALowerTier() {
        val session=SessionPlaybackPolicy()
        session.onEpisodeStart("ep1")
        assertEquals(
            DegradationKind.SAME_QUALITY_REBUILD,
            retry(attr(PlaybackErrorCode.DECODER_INIT_FAILED,1080,mode=VideoDecoderMode.SOFTWARE),session)!!.reason
        )
        val plan=retry(attr(PlaybackErrorCode.DECODER_INIT_FAILED,1080,mode=VideoDecoderMode.SOFTWARE),session)!!
        assertEquals(DegradationKind.QUALITY_DOWN,plan.reason)
        assertEquals(720,plan.maxQuality)
        val empty=SessionPlaybackPolicy()
        empty.onEpisodeStart("ep1")
        empty.consume(DegradationKind.SAME_QUALITY_REBUILD,MediaKind.VIDEO)
        assertEquals(
            GiveUpReason.NO_LOWER_TIER,
            giveUp(attr(PlaybackErrorCode.DECODING_FAILED,480,mode=VideoDecoderMode.SOFTWARE,available=listOf(480)),empty)
        )
        assertTrue(empty.manualHint!!.contains("清晰度"))
        // 软解分支只提「换清晰度」，不再提「切软解」——用户已经在软解上了。
        assertFalse(empty.manualHint!!.contains("切换为软件解码"))
    }

    /** 手动重试归还预算是**有限**的：连点第三次不再归还，避免无限次降档 + 取地址。 */
    @Test fun manualRetryRestoreIsBounded() {
        val session=SessionPlaybackPolicy()
        session.onEpisodeStart("ep1")
        // 第一次、第二次：归还（预算重新可用）。
        repeat(2) {
            session.consume(DegradationKind.QUALITY_DOWN,MediaKind.VIDEO)
            session.consume(DegradationKind.QUALITY_DOWN,MediaKind.VIDEO)
            assertFalse(session.canConsume(DegradationKind.QUALITY_DOWN,MediaKind.VIDEO))
            assertTrue("前两次必须归还",session.onManualRetry())
            assertTrue(session.canConsume(DegradationKind.QUALITY_DOWN,MediaKind.VIDEO))
        }
        // 第三次：归还额度已经用尽——按钮仍然可用，但这一次不再多花预算。
        // 返回 false 让调用方把**其余几份额度**也一起停掉（见 `resetBudgetsForManualRetry`）。
        session.consume(DegradationKind.QUALITY_DOWN,MediaKind.VIDEO)
        session.consume(DegradationKind.QUALITY_DOWN,MediaKind.VIDEO)
        assertFalse(session.onManualRetry())
        assertFalse(session.canConsume(DegradationKind.QUALITY_DOWN,MediaKind.VIDEO))
    }

    /** 退到后台再回来：档位上限与预算都要留着（见 `onLeavePlaybackToBackground`）。 */
    @Test fun backgroundRoundTripKeepsSession() {
        val session=SessionPlaybackPolicy()
        session.onEpisodeStart("ep1")
        session.setMaxQuality(720)
        session.consume(DegradationKind.QUALITY_DOWN,MediaKind.VIDEO)
        session.noteManualHint("x")
        session.onLeavePlaybackToBackground()
        assertEquals(720,session.effectiveMaxQuality)
        assertEquals(1,session.usedCount(DegradationKind.QUALITY_DOWN,MediaKind.VIDEO))
        assertEquals("x",session.manualHint)
        // 真正离开播放页才清。
        session.onUserManualChange()
        assertNull(session.effectiveMaxQuality)
    }

    /** 软解建议档位：最高 720；没有 ≤720 的档就取最低那档。 */
    @Test fun softwareQualityStopsAt720() {
        assertEquals(720,PlaybackDegradation.softwareQuality(listOf(1080,720,480)))
        assertEquals(720,PlaybackDegradation.softwareQuality(listOf(1080,720)))
        assertEquals(1080,PlaybackDegradation.softwareQuality(listOf(1080)))
        assertEquals(0,PlaybackDegradation.softwareQuality(emptyList()))
    }

    /**
     * 软解目标**绝不高于给定上限**：降到 480 之后不能再给出 720。
     *
     * `available` 是 CDN 这一次发的全表，降档不会让它缩短，因此「降到哪一档」只能靠上限
     * 收口。上限传 0（= 没有可接受档位）时返回 0，调用方据此不显示按钮。
     */
    @Test fun softwareQualityNeverExceedsCap() {
        val a=listOf(1080,720,480)
        assertEquals(480,PlaybackDegradation.softwareQuality(a,480))
        assertEquals(720,PlaybackDegradation.softwareQuality(a,1080))
        assertEquals(480,PlaybackDegradation.softwareQuality(a,PlaybackDegradation.lowerThan(a,720)!!))
        // 已经是最低档：严格更低的那一档不存在 → 0，而不是退回 720。
        assertEquals(0,PlaybackDegradation.softwareQuality(a,PlaybackDegradation.lowerThan(a,480) ?: 0))
    }

    /**
     * 降无可降时的建议**不能把清晰度抬回去**：480 失败后不许再出现 720。
     *
     * 早先 `manualHint` 直接取 `softwareQuality(available)`，于是建议是「720P 及以下」——
     * 语义上含 480，但它把刚失败的那一档包进了建议范围，用户照着点就是再撞一次。
     */
    @Test fun hintNeverSuggestsRaisingQuality() {
        val session=SessionPlaybackPolicy()
        session.onEpisodeStart("ep1")
        // 480 这一档已经是全表最低：重建用掉之后立刻放弃，留下手动建议。
        session.consume(DegradationKind.SAME_QUALITY_REBUILD,MediaKind.VIDEO)
        giveUp(attr(PlaybackErrorCode.DECODING_FAILED,480,available=listOf(1080,720,480)),session)
        val hint=session.manualHint!!
        assertFalse(hint.contains("720P"))
        assertTrue(hint.contains("480P"))
        assertTrue(hint.contains("软件解码"))
        // 已经没有更低的档：必须如实说，不能再写「选择 xxx 及以下的清晰度」。
        assertFalse(hint.contains("及以下"))
    }

    /** 720 失败时建议的档位是 480，不是刚失败的那一档。 */
    @Test fun hintPointsBelowTheFailedTier() {
        val session=SessionPlaybackPolicy()
        session.onEpisodeStart("ep1")
        session.consume(DegradationKind.SAME_QUALITY_REBUILD,MediaKind.VIDEO)
        session.consume(DegradationKind.QUALITY_DOWN,MediaKind.VIDEO)
        session.consume(DegradationKind.QUALITY_DOWN,MediaKind.VIDEO)
        giveUp(attr(PlaybackErrorCode.DECODING_FAILED,480,available=listOf(1080,720,480)),session)
        val fresh=SessionPlaybackPolicy()
        fresh.onEpisodeStart("ep1")
        fresh.consume(DegradationKind.SAME_QUALITY_REBUILD,MediaKind.VIDEO)
        fresh.consume(DegradationKind.QUALITY_DOWN,MediaKind.VIDEO)
        fresh.consume(DegradationKind.QUALITY_DOWN,MediaKind.VIDEO)
        // 软解在 720 上失败：预算用尽，建议只能指向 480。
        giveUp(attr(PlaybackErrorCode.DECODING_FAILED,720,mode=VideoDecoderMode.SOFTWARE,
            available=listOf(1080,720,480)),fresh)
        val hint=fresh.manualHint!!
        assertTrue(hint.contains("480P"))
        // 文案里会出现「放不了 720P」（那是失败的那一档），但建议的档位必须是 480。
        assertFalse(hint.contains("720P 及以下"))
        assertTrue(hint.contains("480P 及以下"))
    }

    /** 换集：预算清空，档位上限沿用（不重复撞同一档、也不重复提示）。 */
    @Test fun episodeStartResetsBudgetButKeepsQualityCap() {
        val session=SessionPlaybackPolicy()
        session.onEpisodeStart("ep1")
        session.setMaxQuality(720)
        session.consume(DegradationKind.QUALITY_DOWN,MediaKind.VIDEO)
        session.noteManualHint("x")
        session.noteQualityDown(1080)
        session.onEpisodeStart("ep2")
        assertEquals(720,session.effectiveMaxQuality)
        assertEquals(0,session.usedCount(DegradationKind.QUALITY_DOWN,MediaKind.VIDEO))
        assertNull(session.manualHint)
        // 降档起点也要跟着换集归零，否则新一集第一次降档就被判成「无效」。
        assertFalse(session.qualityDownIneffective(1080))
    }

    /** 手动重试只清预算，不倒退已经降下来的档位。 */
    @Test fun manualRetryKeepsEffectiveQuality() {
        val session=SessionPlaybackPolicy()
        session.onEpisodeStart("ep1")
        session.setMaxQuality(720)
        session.consume(DegradationKind.QUALITY_DOWN,MediaKind.VIDEO)
        session.onManualRetry()
        assertEquals(720,session.effectiveMaxQuality)
        assertTrue(session.canConsume(DegradationKind.QUALITY_DOWN,MediaKind.VIDEO))
    }

    /** 用户手动改解码方式：只消掉「请换软解」那条建议，不动档位上限。 */
    @Test fun manualDecoderChangeClearsHintButKeepsQuality() {
        val session=SessionPlaybackPolicy()
        session.onEpisodeStart("ep1")
        session.setMaxQuality(720)
        session.noteManualHint("请切换为软件解码")
        session.onManualDecoderChange()
        assertEquals(720,session.effectiveMaxQuality)
        assertNull(session.manualHint)
    }

    /** 承诺给用户的提示：同一 (集, 理由) 只弹一次。 */
    @Test fun toastIsDedupedPerKey() {
        val deduper=ToastDeduper()
        assertTrue(deduper.shown("ep1:QUALITY_DOWN"))
        assertFalse(deduper.shown("ep1:QUALITY_DOWN"))
        assertTrue(deduper.shown("ep2:QUALITY_DOWN"))
    }

    /** 索引键必须区分档位，否则降档会命中上一份索引。 */
    @Test fun mediaKeySeparatesQuality() {
        val high=StreamInfo("https://a/b",null,"1080P",1080,1080)
        val low=StreamInfo("https://a/c",null,"720P",1080,720)
        assertFalse(high.mediaKey("1")==low.mediaKey("1"))
        assertEquals("1:1080",high.mediaKey("1"))
    }
}
