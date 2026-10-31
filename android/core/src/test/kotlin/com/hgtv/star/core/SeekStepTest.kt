// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 快进 / 快退的共用规则（[SeekStep]）：两端的手感靠这一份单测锁住。
 *
 * 盯住三件事：档位夹取、第 5 次之后 3 倍、片尾前留 0.5 秒。
 */
class SeekStepTest {

    @Test
    fun step_uses_configured_seconds_and_clamps_range() {
        assertEquals(10_000L, SeekStep.stepMs(10))
        // 超出 2..20 的档位（旧设置文件里可能存着 1 或 30）一律夹回范围内。
        assertEquals(2_000L, SeekStep.stepMs(1))
        assertEquals(20_000L, SeekStep.stepMs(60))
    }

    @Test
    fun acceleration_starts_after_five_taps() {
        // 第 1..5 次按设置步长，第 6 次起 3 倍——与电视端「连按 5 次后每次 30 秒」等价。
        (1..SeekStep.ACCEL_AFTER_TAPS).forEach { tap ->
            assertEquals("第 $tap 次", 10_000L, SeekStep.stepMs(10, tap))
        }
        assertEquals(30_000L, SeekStep.stepMs(10, SeekStep.ACCEL_AFTER_TAPS + 1))
        assertEquals(60_000L, SeekStep.stepMs(20, SeekStep.ACCEL_AFTER_TAPS + 1))
    }

    @Test
    fun target_stops_before_tail() {
        // 一集 60 秒：往前跳到头停在 59.5 秒，不能落到最后一帧（那会立刻触发自动连播）。
        assertEquals(59_500L, SeekStep.target(10_000L, 1, 90_000L, 60_000L))
        // 往后跳到头停在 0。
        assertEquals(0L, SeekStep.target(5_000L, -1, 90_000L, 60_000L))
        // 正常一步。
        assertEquals(20_000L, SeekStep.target(10_000L, 1, 10_000L, 120_000L))
        assertEquals(0L, SeekStep.target(10_000L, -1, 10_000L, 120_000L))
    }

    @Test
    fun short_clip_never_gets_a_negative_limit() {
        // 不到 0.5 秒的片源（广告位 / 损坏文件）：上限夹到 0，而不是负数。
        assertEquals(0L, SeekStep.limitMs(300L))
        assertEquals(0L, SeekStep.limitMs(-1L))
    }

    @Test
    fun tap_counter_resets_outside_merge_window() {
        assertEquals(1, SeekStep.nextTap(0, 0))
        // 合并窗内：累计。
        assertEquals(2, SeekStep.nextTap(1, SeekStep.MERGE_WINDOW_MS))
        assertEquals(6, SeekStep.nextTap(5, 10))
        // 窗口外：重新数（慢慢按不该被判成连按）。
        assertEquals(1, SeekStep.nextTap(5, SeekStep.MERGE_WINDOW_MS + 1))
    }
}
