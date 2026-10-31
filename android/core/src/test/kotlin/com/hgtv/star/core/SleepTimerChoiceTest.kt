package com.hgtv.star.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「定时关闭」八档的落点：**两端共用这一份**，因此档位与分钟 / 集数的对应关系
 * 必须在这里钉住——早先电视与手机各写一遍字面量，改一端就会悄悄分叉。
 */
class SleepTimerChoiceTest {
    private class Clock(var now: Long = 1_000L) : () -> Long {
        override fun invoke(): Long = now
    }

    @Test
    fun labelsAreTheEightSharedChoices() {
        assertEquals(8, SleepTimer.LABELS.size)
        assertEquals("关闭", SleepTimer.LABELS.first())
        assertEquals("再 5 集", SleepTimer.LABELS.last())
    }

    @Test
    fun minutesChoicesArmTheDeadline() {
        val clock = Clock()
        val timer = SleepTimer(clock)
        assertTrue(SleepTimer.applyChoice(timer, 3))
        assertEquals("60 分钟档", 60L * 60_000L, timer.remainingMillis())
        assertEquals("60 分钟后", timer.label())
    }

    @Test
    fun episodeChoicesCountDownByEpisode() {
        val clock = Clock()
        val timer = SleepTimer(clock)
        assertTrue(SleepTimer.applyChoice(timer, 6))
        assertEquals("再 3 集", 3, timer.episodesLeft)
        assertFalse("第一集播完不该停", timer.episodeEnded())
        assertFalse("第二集播完不该停", timer.episodeEnded())
        assertTrue("第三集播完停", timer.episodeEnded())
    }

    @Test
    fun offAndOutOfRangeCancelEverything() {
        val clock = Clock()
        val timer = SleepTimer(clock)
        SleepTimer.applyChoice(timer, 2)
        assertTrue(timer.active)
        assertFalse("「关闭」返回 false", SleepTimer.applyChoice(timer, 0))
        assertFalse(timer.active)
        // 越界按关闭处理：UI 传进来的下标宁可停掉，也不要抛。
        SleepTimer.applyChoice(timer, 2)
        assertFalse(SleepTimer.applyChoice(timer, 99))
        assertFalse(timer.active)
    }
}
