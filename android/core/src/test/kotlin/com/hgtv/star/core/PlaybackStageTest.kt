package com.hgtv.star.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段标题表的两条不变量：
 *
 * 1. 每个阶段都有**自己的**标题（两档共用一个标题，用户与调试面板都分不出来）；
 * 2. 未知阶段退回「播放失败」，不能把常量本身当标题读给用户。
 */
class PlaybackStageTest {
    @Test
    fun everyStageHasItsOwnTitle() {
        val titles = PlaybackStage.ALL.map { PlaybackStage.titleOf(it) }
        assertEquals("阶段数与标题数不一致", PlaybackStage.ALL.size, titles.size)
        assertEquals("有阶段共用了同一个标题", titles.size, titles.toSet().size)
        assertTrue("标题里不应出现常量本身", titles.none { it.startsWith("playback_") })
    }

    @Test
    fun unknownStageFallsBackToGenericTitle() {
        assertEquals("播放失败", PlaybackStage.titleOf(""))
        assertEquals("播放失败", PlaybackStage.titleOf("playback_something_new"))
    }
}
