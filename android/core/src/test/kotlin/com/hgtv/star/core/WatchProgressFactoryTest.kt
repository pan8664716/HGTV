package com.hgtv.star.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 观看进度的生成规则（电视与手机共用，见 `BackupProgress.of` / `BackupProgress.completed`）。
 *
 * 这两条以前散在四个地方各写一遍：「离片尾 3 秒算看完」与「位置夹到时长以内」。
 * 有一处写成 2 秒、有一处漏了夹取，用户就会看到「历史里明明看完了却标着没看完」
 * 或「02:31 / 02:30」这种读数。
 */
class WatchProgressFactoryTest {
    private val series = Series(id = "s1", title = "测试剧")

    @Test fun `position is clamped into the episode duration`() {
        val off = BackupProgress.of(series, "e1", 0, position = 900_000, duration = 60_000)
        assertEquals(60_000, off.position)
        assertEquals(60_000, off.duration)
        val negative = BackupProgress.of(series, "e1", 0, position = -5_000, duration = 60_000)
        assertEquals(0, negative.position)
    }

    @Test fun `completed inside the three second tail only`() {
        val nearEnd = BackupProgress.of(series, "e1", 0, position = 58_000, duration = 60_000)
        assertTrue(nearEnd.completed)
        // 边界：正好差 3 秒算看完，差 3 秒零 1 毫秒不算。
        assertTrue(BackupProgress.of(series, "e1", 0, 57_000, 60_000).completed)
        assertFalse(BackupProgress.of(series, "e1", 0, 56_999, 60_000).completed)
        // 时长未知（还没拿到 duration）时不判「看完」：否则一进播放页就被记成已看完。
        assertFalse(BackupProgress.of(series, "e1", 0, 0, 0).completed)
    }

    @Test fun `completed episode records the whole duration`() {
        val done = BackupProgress.completed(series, "e2", 1, 42_000)
        assertEquals(42_000, done.position)
        assertEquals(42_000, done.duration)
        assertTrue(done.completed)
        assertEquals(1, done.episodeIndex)
        assertEquals("e2", done.episodeId)
    }
}
