// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「预热结果还能不能用」的判据。
 *
 * 这一位守的是现场故障「播几集后按下一集 → 地址过期（HTTP 403）」：
 * 队列项与推荐页预热缓存里装的是带时效的播放地址，取用时必须校验年龄。
 */
class PreparedFreshnessTest {

    private val ttl = PlaybackCache.PREPARED_FRESH_MS

    @Test fun `刚预热好的可以直接用`() {
        assertTrue(PlaybackCache.isPreparedFresh(1_000L, 1_000L))
        assertTrue(PlaybackCache.isPreparedFresh(1_000L, 1_000L + 60_000))
    }

    @Test fun `正好到上限仍然算新鲜`() {
        assertTrue(PlaybackCache.isPreparedFresh(0L + 1L, 1L + ttl))
    }

    @Test fun `超过上限就不能再用`() {
        assertFalse(PlaybackCache.isPreparedFresh(1_000L, 1_000L + ttl + 1))
        // 一小时前预热的：绝对不能拿去起播。
        assertFalse(PlaybackCache.isPreparedFresh(1_000L, 1_000L + 60 * 60 * 1000))
    }

    @Test fun `没有时间戳一律当过期`() {
        // `queuedAt` / `recommendPreparedAt` 初值都是 0：没记过时刻就是「不知道多旧」，
        // 按过期处理（重新取地址），不要赌它。
        assertFalse(PlaybackCache.isPreparedFresh(0L, 1_000L))
        assertFalse(PlaybackCache.isPreparedFresh(-1L, 1_000L))
    }

    @Test fun `时钟倒退时也当过期`() {
        // 理论上 `elapsedRealtime` 不会倒退，但设备重启后 `elapsedRealtime` 归零，
        // 而某个残留时间戳仍是重启前的值：这时差值是负的，必须判过期而不是新鲜。
        assertFalse(PlaybackCache.isPreparedFresh(50_000L, 1_000L))
    }

    @Test fun `预热窗口与落盘地址窗口是同一个数字`() {
        // 全站只有**一处**定义：`PlaybackCache.URL_FRESH_MS`。内存缓存、落盘缓存、
        // 预热结果（队列项 / 推荐页）全部引用它。
        //
        // 早先这里是三个各写各的数字（内存 2 分钟 / 落盘 20 分钟 / 预热无上限），
        // 于是「哪一份地址还能用」取决于它从哪条路进来，同一集在不同路径下有效期不一样。
        assertEquals(PlaybackCache.URL_FRESH_MS, PlaybackCache.PREPARED_FRESH_MS)
    }

    @Test fun `窗口期是十分钟`() {
        // 这个断言是**故意写死**的：改窗口期就必须同时改这条用例，
        // 逼着改动者想一遍「为什么改、改了之后哪些路径受影响」。
        assertEquals(10 * 60 * 1000L, PlaybackCache.URL_FRESH_MS)
    }
}
