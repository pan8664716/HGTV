// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.junit.Assert.*
import org.junit.Test

class RankBoardTest {
    /**
     * 首页七个栏目与顺序是需求钉死的：
     * 历史记录 / 我的收藏 / 稍后观看 / 热播漫剧 / 热播真人 / 热播AI / 热播上新。
     */
    @Test fun `home columns follow the requested order and wording`() {
        assertEquals(listOf("历史记录","我的收藏","稍后观看","热播漫剧","热播真人","热播AI","热播上新"),
            HomeLayout.columns.map { it.title })
        assertEquals(listOf("热播漫剧","热播真人","热播AI","热播上新"),RankBoard.entries.map { it.homeTitle })
    }

    /** 前三个栏目是本机记录（没有榜单），后四个各对应一个榜单，顺序与 [RankBoard] 一致。 */
    @Test fun `local columns carry no board and rank columns map one to one`() {
        assertNull(HomeLayout.board("history")); assertNull(HomeLayout.board("favorites")); assertNull(HomeLayout.board("later"))
        assertEquals(RankBoard.entries,HomeLayout.columns.mapNotNull { it.board })
        assertEquals(RankBoard.entries.map { "rank-${it.storedValue}" },HomeLayout.columns.drop(3).map { it.key })
    }

    /** 剧架 key 是焦点记忆与缓存键的一部分：必须唯一、稳定，且能反查回栏目名与榜单。 */
    @Test fun `column keys are unique and resolve back to title and board`() {
        val keys=HomeLayout.columns.map { it.key }
        assertEquals(keys.size,keys.distinct().size)
        HomeLayout.columns.forEach { column ->
            assertEquals(column.title,HomeLayout.title(column.key))
            assertEquals(column.board,HomeLayout.board(column.key))
        }
        // 认不出的 key 退回自身，界面不会显示空标题。
        assertEquals("unknown",HomeLayout.title("unknown"))
    }

    /**
     * 榜单页的四个按钮文案是需求钉死的短名：漫剧 / 真人 / AI剧 / 上新；
     * 页面主标题固定「热播榜」，不跟着按钮变（见界面代码）。
     */
    @Test fun `rank switcher uses the four short labels`() {
        assertEquals(listOf("漫剧","真人","AI剧","上新"),RankBoard.entries.map { it.label })
    }

    /**
     * 四个榜单各自的 App 分类与 `rankKey` 都不同：`rankKey` 同时是本机缓存的键，写错就会串数据；
     * `genre` 为空的那个是「上新」（全站综合，不筛分类），它只能是唯一一个。
     * 三个热播榜按热度排，上新按上架时间排。
     */
    @Test fun `genres and rank keys are distinct and stable`() {
        assertEquals(listOf("comic_series","short_play","ai_series",null),RankBoard.entries.map { it.genre })
        assertEquals(listOf("comic","real","ai","new"),RankBoard.entries.map { it.storedValue })
        assertEquals(listOf("hot_score","hot_score","hot_score","online_time"),RankBoard.entries.map { it.sort })
        assertEquals(RankBoard.entries.size,RankBoard.entries.map { it.storedValue }.distinct().size)
        assertEquals(1,RankBoard.entries.count { it.genre==null })
    }

    /** 旧本机设置 / 缓存里的键读进来要认得；认不出的键退回默认（漫剧榜）。 */
    @Test fun `stored keys round trip and unknown values fall back`() {
        RankBoard.entries.forEach { assertEquals(it,RankBoard.fromStored(it.storedValue)) }
        assertEquals(RankBoard.COMIC,RankBoard.fromStored(null))
        assertEquals(RankBoard.COMIC,RankBoard.fromStored("unknown"))
    }
}
