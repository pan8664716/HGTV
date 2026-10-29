// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放状态机的迁移规则。
 *
 * 这些用例存在的意义：改造前播放链路的每个状态转换都埋在 `MainActivity` 的 15,000 行里，
 * 一行覆盖都没有；而它是全应用最容易出「界面自相矛盾」的地方（有画面却说加载中、
 * 出错了进度条还在动）。把迁移收进纯函数之后，这些规则第一次可以被钉死。
 */
class PlaybackMachineTest {

    private fun opened(session: Long = 1, index: Int = 0) = PlaybackMachine.reduce(
        PlaybackState(),
        PlaybackEvent.Open(session, "series", "ep-$index", index, resumeAtMs = 5_000, autoplay = true),
    )

    private fun state(session: Long = 1, playbackState: Int, playWhenReady: Boolean = true) =
        PlaybackEvent.PlayerState(session, playbackState, playWhenReady)

    @Test
    fun `open 进入准备态并清空上一次的现场`() {
        val dirty = opened().copy(prepared = true, attempt = 3, failure = PlaybackFailure(MediaTrack.VIDEO, 4001, "x", true))
        val next = PlaybackMachine.reduce(dirty, PlaybackEvent.Open(2, "s2", "e2", 4, resumeAtMs = 0, autoplay = false))

        assertEquals(2L, next.sessionId)
        assertEquals(PlaybackPhase.PREPARING, next.phase)
        assertFalse(next.prepared)
        assertEquals(0, next.attempt)
        assertNull(next.failure)
        assertFalse(next.playWhenReady)
        assertEquals("e2", next.episodeId)
        assertEquals(4, next.episodeIndex)
    }

    @Test
    fun `旧会话的事件一律丢弃`() {
        val ready = PlaybackMachine.reduce(opened(), state(1, PlaybackMachine.STATE_READY))
        val stale = PlaybackMachine.reduce(ready, state(0, PlaybackMachine.STATE_ENDED))

        // 状态原样返回：旧回调不该把正在播的这一集改成「播完了」。
        assertEquals(PlaybackPhase.READY, stale.phase)
        assertEquals(1L, stale.sessionId)
    }

    @Test
    fun `READY 之后 prepared 一直为真`() {
        var s = opened()
        s = PlaybackMachine.reduce(s, state(1, PlaybackMachine.STATE_READY))
        assertTrue(s.prepared)

        s = PlaybackMachine.reduce(s, state(1, PlaybackMachine.STATE_BUFFERING))
        assertTrue("中途缓冲不该把「进度可信」打回 false", s.prepared)
        assertEquals(PlaybackPhase.BUFFERING, s.phase)

        s = PlaybackMachine.reduce(s, state(1, PlaybackMachine.STATE_ENDED))
        assertTrue(s.prepared)
        assertEquals(PlaybackPhase.ENDED, s.phase)
    }

    @Test
    fun `没 prepared 过的 IDLE 仍然是准备态`() {
        val s = PlaybackMachine.reduce(opened(), state(1, PlaybackMachine.STATE_IDLE))
        assertEquals(PlaybackPhase.PREPARING, s.phase)
    }

    @Test
    fun `prepared 之后的 IDLE 不改阶段`() {
        var s = PlaybackMachine.reduce(opened(), state(1, PlaybackMachine.STATE_READY))
        s = PlaybackMachine.reduce(s, state(1, PlaybackMachine.STATE_IDLE))
        assertEquals(PlaybackPhase.READY, s.phase)
        assertTrue(s.prepared)
    }

    @Test
    fun `播完之后往回拖可以重新 READY`() {
        var s = PlaybackMachine.reduce(opened(), state(1, PlaybackMachine.STATE_READY))
        s = PlaybackMachine.reduce(s, state(1, PlaybackMachine.STATE_ENDED))
        assertEquals(PlaybackPhase.ENDED, s.phase)

        s = PlaybackMachine.reduce(s, state(1, PlaybackMachine.STATE_READY))
        assertEquals(PlaybackPhase.READY, s.phase)
    }

    @Test
    fun `失败必须带现场且不会出现在 IDLE 上`() {
        val idle = PlaybackMachine.reduce(
            PlaybackState(sessionId = 1),
            PlaybackEvent.Failed(1, PlaybackFailure(MediaTrack.VIDEO, 4001, "boom", true)),
        )
        assertEquals("IDLE 上不接失败", PlaybackPhase.IDLE, idle.phase)
        assertNull(idle.failure)

        val failed = PlaybackMachine.reduce(
            opened(),
            PlaybackEvent.Failed(1, PlaybackFailure(MediaTrack.AUDIO, 4001, "audio", true)),
        )
        assertEquals(PlaybackPhase.FAILED, failed.phase)
        assertEquals(MediaTrack.AUDIO, failed.failure?.track)
        assertEquals(4001, failed.failure?.code)
    }

    @Test
    fun `重新 READY 会清掉上一次的失败`() {
        var s = PlaybackMachine.reduce(
            opened(),
            PlaybackEvent.Failed(1, PlaybackFailure(MediaTrack.VIDEO, 4001, "boom", true)),
        )
        s = PlaybackMachine.reduce(s, state(1, PlaybackMachine.STATE_READY))
        assertEquals(PlaybackPhase.READY, s.phase)
        assertNull(s.failure)
    }

    @Test
    fun `重试回到准备态并把尝试次数加一`() {
        var s = PlaybackMachine.reduce(
            opened(),
            PlaybackEvent.Failed(1, PlaybackFailure(MediaTrack.VIDEO, 4001, "boom", true)),
        )
        s = PlaybackMachine.reduce(s, PlaybackEvent.Retried(1))

        assertEquals(PlaybackPhase.PREPARING, s.phase)
        assertEquals(1, s.attempt)
        assertNull(s.failure)
        assertFalse(s.prepared)
    }

    @Test
    fun `进度被钳制在 0 到时长之间`() {
        var s = PlaybackMachine.reduce(opened(), PlaybackEvent.Progress(1, -100, 60_000))
        assertEquals(0L, s.positionMs)

        s = PlaybackMachine.reduce(s, PlaybackEvent.Progress(1, 90_000, 60_000))
        assertEquals(60_000L, s.positionMs)
        assertEquals(60_000L, s.durationMs)
    }

    @Test
    fun `时长为 0 时不钳制上界`() {
        // 直播 / 时长还没解析出来时 duration 是 0，此时不能把进度钳成 0。
        val s = PlaybackMachine.reduce(opened(), PlaybackEvent.Progress(1, 12_345, 0))
        assertEquals(12_345L, s.positionMs)
    }

    @Test
    fun `释放回到 IDLE 但保留会话号`() {
        var s = PlaybackMachine.reduce(opened(7), state(7, PlaybackMachine.STATE_READY))
        s = PlaybackMachine.reduce(s, PlaybackEvent.Released(7))

        assertEquals(PlaybackPhase.IDLE, s.phase)
        assertEquals(7L, s.sessionId)
        assertFalse(s.prepared)
        assertEquals("", s.episodeId)
    }

    @Test
    fun `缓冲原因只在缓冲态保留`() {
        var s = PlaybackMachine.reduce(opened(), PlaybackEvent.PlayerState(1, PlaybackMachine.STATE_BUFFERING, true, "network"))
        assertEquals("network", s.bufferingReason)

        s = PlaybackMachine.reduce(s, state(1, PlaybackMachine.STATE_READY))
        assertEquals("", s.bufferingReason)
    }

    @Test
    fun `倍速被夹在合理区间`() {
        val s = PlaybackMachine.reduce(opened(), PlaybackEvent.Speed(1, 0f))
        assertEquals(0.1f, s.speed, 0.0001f)
    }

    @Test
    fun `无感切集标记随 Open 一起落地`() {
        val s = PlaybackMachine.reduce(
            PlaybackState(),
            PlaybackEvent.Open(1, "s", "e", 2, seamless = true),
        )
        assertTrue(s.seamless)
    }
}
