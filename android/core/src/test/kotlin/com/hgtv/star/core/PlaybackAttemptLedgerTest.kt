// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按集重试额度的重置语义（与 `PlaybackAttemptLedger` 的注释一一对应）。
 *
 * 这三条边界是这半年里反复踩出来的：
 * * 换集必须**全部**归零（少清一份，新一集就没有自动重试）；
 * * 换到另一集去重来**不能**清连播链的失败计数（清了就变成无限自动推进）；
 * * 用户手动重试**不能**清地址自愈标记（同一条地址救第二次还是同一条）。
 */
class PlaybackAttemptLedgerTest {

    @Test fun episodeStartClearsEverything() {
        val ledger=PlaybackAttemptLedger()
        ledger.consumeAttempt()
        ledger.notePictureRecovery(1_000)
        ledger.noteQueuedRecovery("ep1:1080",2)
        ledger.noteAutoNextFailure()
        ledger.noteStreamRetry("ep1:1080")

        ledger.onEpisodeStart()

        assertEquals(0,ledger.attempts)
        assertTrue(ledger.canAttempt)
        assertEquals(0,ledger.formalPictureRecoveries)
        assertFalse(ledger.pictureCoolingDown(1_500,20_000))
        assertEquals(0,ledger.queuedRecoveries("ep1:1080"))
        assertEquals(0,ledger.autoNextFailures)
        assertFalse(ledger.streamRetried("ep1:1080"))
    }

    /** 换到另一集去重来：其余归零，但**连播链的失败计数留着**。 */
    @Test fun episodeSwitchKeepsTheAutoAdvanceChain() {
        val ledger=PlaybackAttemptLedger()
        ledger.consumeAttempt()
        ledger.notePictureRecovery(1_000)
        ledger.noteQueuedRecovery("ep1:1080",2)
        ledger.noteAutoNextFailure()

        ledger.onEpisodeSwitch()

        assertEquals(0,ledger.attempts)
        assertEquals(0,ledger.formalPictureRecoveries)
        assertEquals(0,ledger.queuedRecoveries("ep1:1080"))
        assertEquals("连播链断了几次不能被这次换集清掉",1,ledger.autoNextFailures)
    }

    /** 手动重试：还掉自动重试 / 画面重建 / 队列重开，但不清地址自愈标记。 */
    @Test fun manualRetryKeepsTheStreamSelfHeal() {
        val ledger=PlaybackAttemptLedger()
        ledger.consumeAttempt()
        ledger.notePictureRecovery(1_000)
        ledger.noteQueuedRecovery("ep1:1080",2)
        ledger.noteStreamRetry("ep1:1080")
        ledger.noteAutoNextFailure()

        ledger.onManualRetry()

        assertEquals(0,ledger.attempts)
        assertEquals(0,ledger.formalPictureRecoveries)
        assertEquals(0,ledger.queuedRecoveries("ep1:1080"))
        assertTrue("同一条地址已经救过一次，手动重试不该再给一次",ledger.streamRetried("ep1:1080"))
        assertEquals(1,ledger.autoNextFailures)
    }

    /**
     * 地址自愈是**每集每个键一次**，不是「隔一次一次」。
     *
     * 早先只存一个字符串：撞到同一个键时先清空再放弃，于是下一次同样的失败又能救一次。
     */
    @Test fun streamSelfHealDoesNotAlternate() {
        val ledger=PlaybackAttemptLedger()
        ledger.noteStreamRetry("ep1:1080")
        assertTrue(ledger.streamRetried("ep1:1080"))
        assertTrue("再问一次仍然是「救过了」",ledger.streamRetried("ep1:1080"))
        // 同一集里另一条地址（例如队列里那一集）仍然有自己的一次机会。
        assertFalse(ledger.streamRetried("ep2:1080"))
        ledger.noteStreamRetry("ep2:1080")
        assertTrue(ledger.streamRetried("ep2:1080"))
    }

    /** 自动重试三次封顶，间隔 3s / 6s / 12s。 */
    @Test fun autoRetryIsBounded() {
        val ledger=PlaybackAttemptLedger()
        assertEquals(3_000L,ledger.delayMillis)
        assertTrue(ledger.consumeAttempt())
        assertEquals(6_000L,ledger.delayMillis)
        assertTrue(ledger.consumeAttempt())
        assertEquals(12_000L,ledger.delayMillis)
        assertTrue(ledger.consumeAttempt())
        assertFalse(ledger.canAttempt)
        assertEquals(null,ledger.delayMillis)
        assertFalse(ledger.consumeAttempt())
    }

    /** 队列重开按「集 + 上限」做键：换了键就是新的一次，同键累加。 */
    @Test fun queuedRecoveryIsKeyed() {
        val ledger=PlaybackAttemptLedger()
        ledger.noteQueuedRecovery("ep1:1080",1)
        assertEquals(1,ledger.queuedRecoveries("ep1:1080"))
        assertEquals(0,ledger.queuedRecoveries("ep1:720"))
        ledger.noteQueuedRecovery("ep1:1080",2)
        assertEquals(2,ledger.queuedRecoveries("ep1:1080"))
    }

    /** 画面重建的冷却窗口：窗口内不再给第二次。 */
    @Test fun pictureRecoveryCoolsDown() {
        val ledger=PlaybackAttemptLedger()
        assertFalse(ledger.pictureCoolingDown(1_000,20_000))
        ledger.notePictureRecovery(1_000)
        assertTrue(ledger.pictureCoolingDown(1_000+19_999,20_000))
        assertFalse(ledger.pictureCoolingDown(1_000+20_000,20_000))
    }
}
