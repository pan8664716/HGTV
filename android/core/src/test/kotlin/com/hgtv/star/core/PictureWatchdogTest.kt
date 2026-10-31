// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureWatchdogTest {

    private var now = 1_000L
    private val dog = PictureWatchdog({ now })

    @Test fun `没到阈值时继续等`() {
        dog.arm()
        now += 10_000
        assertEquals(PictureWatchdog.Verdict.WAITING, dog.check())
    }

    @Test fun `一直不进入就绪超过 90 秒判卡缓冲`() {
        dog.arm()
        now += 89_000
        assertEquals(PictureWatchdog.Verdict.WAITING, dog.check())
        now += 1_000
        assertEquals(PictureWatchdog.Verdict.STALL_BUFFER, dog.check())
    }

    @Test fun `就绪之后 15 秒没画面判没画面`() {
        dog.arm()
        now += 5_000
        dog.onReady()
        now += 14_000
        assertEquals(PictureWatchdog.Verdict.WAITING, dog.check())
        now += 1_000
        assertEquals(PictureWatchdog.Verdict.NO_FRAME, dog.check())
    }

    @Test fun `出过一帧就不再判`() {
        dog.arm()
        dog.onReady()
        dog.onFrame()
        now += 60_000
        assertEquals(PictureWatchdog.Verdict.DONE, dog.check())
        assertFalse(dog.active)
    }

    /**
     * 「缓冲中暂停两分钟再继续」不该命中 stall_buffer——这是看门狗误报最典型的一种。
     *
     * 调用方在暂停时 [PictureWatchdog.disarm]、恢复时重新 [PictureWatchdog.arm]，
     * 因此那两分钟根本不在计时窗口里。
     */
    @Test fun `暂停期间不计入缓冲超时`() {
        dog.arm()
        now += 60_000
        dog.disarm()
        now += 120_000
        assertEquals(PictureWatchdog.Verdict.DONE, dog.check())
        dog.arm()
        now += 30_000
        assertEquals(PictureWatchdog.Verdict.WAITING, dog.check())
        now += 60_000
        assertEquals(PictureWatchdog.Verdict.STALL_BUFFER, dog.check())
    }

    /** 先 READY 再暂停五分钟、回来一按播放，不该当场被判成「没画面」。 */
    @Test fun `就绪后暂停再继续重新计时`() {
        dog.arm()
        dog.onReady()
        now += 10_000
        dog.disarm()
        now += 300_000
        dog.arm()
        now += 5_000
        assertEquals(PictureWatchdog.Verdict.WAITING, dog.check())
    }

    @Test fun `起播时已经是就绪的立刻按就绪计时`() {
        dog.arm(alreadyReady = true)
        now += 15_000
        assertEquals(PictureWatchdog.Verdict.NO_FRAME, dog.check())
    }

    @Test fun `撤销之后不再判`() {
        dog.arm()
        dog.disarm()
        now += 999_000
        assertEquals(PictureWatchdog.Verdict.DONE, dog.check())
        assertFalse(dog.active)
    }

    @Test fun `重复就绪不会重置起点`() {
        dog.arm()
        now += 10_000
        dog.onReady()
        now += 10_000
        dog.onReady()
        now += 5_000
        assertEquals(PictureWatchdog.Verdict.NO_FRAME, dog.check())
    }

    @Test fun `起播后短暂就绪不算卡缓冲`() {
        dog.arm()
        now += 30_000
        dog.onReady()
        assertTrue(dog.active)
        now += 5_000
        assertEquals(PictureWatchdog.Verdict.WAITING, dog.check())
    }
}
