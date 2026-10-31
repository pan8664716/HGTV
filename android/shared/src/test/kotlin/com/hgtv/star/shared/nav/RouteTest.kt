// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面清单的回归测试。
 *
 * 重点不在「路由怎么跳」——那是各端适配器的事——而在**页面名不许漂移**：
 * `screenName` 会被埋点、诊断日志与进程恢复写进数据里，改了它等于历史数据对不上。
 */
class RouteTest {

    /**
     * 钉住每一个页面名。
     *
     * 这条用例的价值在于「改的人必须先想一下」：如果确实要改名，就得同时考虑
     * 已经落盘的埋点与恢复数据，而不是顺手改一个字符串。
     */
    @Test
    fun `页面名保持稳定`() {
        assertEquals("home", Route.Home.screenName)
        assertEquals("recommend", Route.Recommend.screenName)
        assertEquals("detail", Route.Detail("s1").screenName)
        assertEquals("player", Route.Player("s1").screenName)
        assertEquals("search", Route.Search.screenName)
        assertEquals("settings", Route.Settings.screenName)
        assertEquals("account", Route.Account.screenName)
    }

    @Test
    fun `页面名互不重复`() {
        val names = listOf(
            Route.Home.screenName,
            Route.Recommend.screenName,
            Route.Detail("s1").screenName,
            Route.Player("s1").screenName,
            Route.Search.screenName,
            Route.Settings.screenName,
            Route.Account.screenName,
        )
        assertEquals("页面名必须唯一，否则埋点分不清是哪一页", names.size, names.toSet().size)
    }

    /**
     * 「没指定集数」与「第 0 集」必须是两个不同的值。
     *
     * 手机端读的是 `getIntExtra(EXTRA_EPISODE, -1)`：`-1` 表示按本机进度续播，
     * `0` 表示用户指定了第 1 集。如果哪天有人把 `episode` 从 `Int?` 改成带默认值的 `Int`，
     * 这条会立刻红——那正是一次「续播悄悄失效」的事故。
     */
    @Test
    fun `未指定集数与第零集不可混为一谈`() {
        val resume = Route.Player("s1")
        val firstEpisode = Route.Player("s1", episode = 0)
        assertNotEquals(resume, firstEpisode)
        assertEquals(null, resume.episode)
        assertEquals(0, firstEpisode.episode)
    }

    @Test
    fun `详情页标题只影响展示不影响身份`() {
        val withTitle = Route.Detail("s1", "某部剧")
        assertEquals("detail", withTitle.screenName)
        assertEquals("s1", withTitle.seriesId)
        assertEquals("某部剧", withTitle.title)
        // 不传标题时为空串，两端都显示「加载中…」。
        assertEquals("", Route.Detail("s1").title)
    }

    @Test
    fun `播放页的四个开关默认全关`() {
        val route = Route.Player("s1")
        assertTrue(!route.fromRecommend && !route.fullscreen && !route.noTransition)
    }
}
