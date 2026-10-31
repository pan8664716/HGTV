package com.hgtv.star.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 播放信息条的两段文案：两端共用，因此锁死格式。
 *
 * 集数那一项在电视上是「第 3 / 171 集」，手机上早先只有「第 3 集」——
 * 同一个开关显示不同内容，用户没法拿一端的情况描述另一端的问题。
 */
class PlaybackInfoLineTest {
    @Test
    fun episodeShowsTotalWhenKnown() {
        assertEquals("第 3 / 171 集", PlaybackInfoLine.episode(2, 171))
        assertEquals("第 1 / 1 集", PlaybackInfoLine.episode(0, 1))
    }

    @Test
    fun episodeFallsBackWhenTotalUnknown() {
        assertEquals("第 5 集", PlaybackInfoLine.episode(4, 0))
        assertEquals("第 5 集", PlaybackInfoLine.episode(4, -1))
    }

    @Test
    fun clockIsZeroPaddedAndClamped() {
        assertEquals("09:07", PlaybackInfoLine.clock(9, 7))
        assertEquals("00:00", PlaybackInfoLine.clock(-1, -5))
        assertEquals("23:59", PlaybackInfoLine.clock(24, 60))
    }

    @Test
    fun timeIsMinuteSecondAndClampsNegative() {
        // 两端原先各写一份：位置未知时播放器返回 -1，漏了夹取就会显示 "-1:-1"。
        assertEquals("00:00", PlaybackInfoLine.time(-1))
        assertEquals("00:05", PlaybackInfoLine.time(5_000))
        assertEquals("01:05", PlaybackInfoLine.time(65_000))
        // 毫秒截断到秒（不是四舍五入）：与两端原来的写法一致，不会在同一秒里差 1 秒。
        assertEquals("01:05", PlaybackInfoLine.time(65_999))
        // 超过一小时仍按分钟累加（两端原是同一语义，这里锁住不改成 hh:mm:ss）。
        assertEquals("75:00", PlaybackInfoLine.time(75 * 60_000L))
    }
}
