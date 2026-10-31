// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快进 / 快退的调度外框（[SeekStepPump]）：合并窗里只落地一次、窗内连按会加速、
 * 换集 / 手拖之后作废。
 */
class SeekStepPumpTest {

    private var clock = 1_000L
    private var position = 30_000L
    private var duration = 120_000L
    private val seeks = ArrayList<Long>()

    private val pump = SeekStepPump(
        now = { clock },
        position = { position },
        duration = { duration },
        seek = { seeks += it },
    )

    @Test
    fun taps_inside_window_merge_into_one_seek() {
        // 三次连按（每次间隔 100ms）：目标累计到 +30s，但**一次都不落地**。
        repeat(3) {
            pump.request(1, 10)
            clock += 100
        }
        assertTrue(seeks.isEmpty())
        assertEquals(60_000L, pump.pendingTarget)

        assertTrue(pump.flush())
        assertEquals(listOf(60_000L), seeks)
        // 落地之后没有在途目标：再 flush 一次不该重复跳。
        assertFalse(pump.flush())
        assertEquals(1, seeks.size)
    }

    @Test
    fun sixth_tap_is_three_times() {
        repeat(5) {
            pump.request(1, 10)
            clock += 100
        }
        val plan = pump.request(1, 10) ?: error("应当能跳")
        assertEquals(6, plan.tap)
        assertEquals(30_000L, plan.stepMs)
        // 从 30s 起：前五次各 10s、第六次 30s → 110s（还没到 119.5s 的片尾线）。
        assertEquals(110_000L, plan.targetMs)
    }

    @Test
    fun slow_taps_do_not_accelerate() {
        // 间隔超过合并窗：每次都是「第一次」，也就不加速（慢慢按不该被判成连按）。
        val steps = (1..6).map {
            val plan = pump.request(1, 10) ?: error("应当能跳")
            clock += SeekStep.MERGE_WINDOW_MS + 1
            pump.flush()
            plan.stepMs
        }
        assertEquals(List(6) { 10_000L }, steps)
    }

    @Test
    fun cancel_drops_pending_and_counter() {
        pump.request(1, 10)
        clock += 100
        pump.request(1, 10)
        pump.cancel()
        assertEquals(-1L, pump.pendingTarget)
        assertFalse(pump.flush())
        // 计数也归零：下一按重新从 1 数（不会一上来就 3 倍）。
        val plan = pump.request(1, 10) ?: error("应当能跳")
        assertEquals(1, plan.tap)
        assertEquals(10_000L, plan.stepMs)
    }

    @Test
    fun no_duration_means_no_plan() {
        duration = 0
        assertNull(pump.request(1, 10))
        assertFalse(pump.flush())
        assertTrue(seeks.isEmpty())
    }

    @Test
    fun backward_step_stops_at_zero() {
        position = 5_000L
        pump.request(-1, 10)
        pump.flush()
        assertEquals(listOf(0L), seeks)
    }
}
