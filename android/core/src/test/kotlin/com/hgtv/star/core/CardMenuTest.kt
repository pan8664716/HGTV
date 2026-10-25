// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.junit.Assert.*
import org.junit.Test

class CardMenuTest {
    private fun labels(rows: List<CardMenuItem>) = rows.map { it.label }
    private fun actions(rows: List<CardMenuItem>) = rows.map { it.action }

    @Test fun `home history shelf offers deleting this record`() {
        val rows = CardMenu.items(hasProgress = true, favorite = false, queued = false, inHistory = true, hotList = false)
        assertEquals(listOf("继续观看", "收藏这部剧", "加入稍后观看", "查看剧集详情", "删除记录"), labels(rows))
        assertEquals(CardAction.DELETE_HISTORY, rows.last().action)
    }

    @Test fun `history collection keeps only delete`() {
        val rows = CardMenu.items(hasProgress = true, favorite = true, queued = true, inHistory = true, hotList = false)
        assertEquals(listOf("继续观看", "取消收藏", "移出稍后观看", "查看剧集详情", "删除记录"), labels(rows))
        assertEquals(CardAction.DELETE_HISTORY, rows.last().action)
        // 本机记录页不再出现只对热门列表有意义的项：文案与动作一一对应，不会再有下标错位。
        assertFalse(actions(rows).contains(CardAction.HIDE_FROM_HOT))
    }

    @Test fun `hot list keeps hiding and local shelves do not offer it`() {
        val hot = CardMenu.items(hasProgress = false, favorite = false, queued = false, inHistory = false, hotList = true)
        assertEquals(listOf("直接播放", "收藏这部剧", "加入稍后观看", "查看剧集详情", "不在热播榜中显示"), labels(hot))
        assertEquals(CardAction.HIDE_FROM_HOT, hot.last().action)

        val favorites = CardMenu.items(hasProgress = false, favorite = true, queued = false, inHistory = false, hotList = false)
        assertEquals(listOf("直接播放", "取消收藏", "加入稍后观看", "查看剧集详情"), labels(favorites))
        assertFalse(favorites.any { it.action == CardAction.DELETE_HISTORY || it.action == CardAction.HIDE_FROM_HOT })
    }

    /**
     * 「我的 → 历史」也是观看记录上下文，同样要有「删除记录」。
     *
     * 这一栏读的是**账号侧**的历史（`accountData.history`），早先只有首页剧架与历史集合页
     * 被算作历史上下文，于是这一页长按卡片没有删除项——用户只能换到别的页面去删。
     */
    @Test fun `profile history tab also offers deleting this record`() {
        // 界面层把「我的 → 历史」折算成 inHistory=true（见 quickActions），这里钉住纯函数的结论。
        val rows = CardMenu.items(hasProgress = false, favorite = false, queued = false, inHistory = true, hotList = false)
        assertEquals(listOf("直接播放", "收藏这部剧", "加入稍后观看", "查看剧集详情", "删除记录"), labels(rows))
        assertEquals(CardAction.DELETE_HISTORY, rows.last().action)
        // 账号侧的历史不一定在本机有进度：第一项仍是「直接播放」，删除项照样在。
        assertFalse(rows.any { it.action == CardAction.HIDE_FROM_HOT })
    }

    @Test fun `label follows state while action stays fixed`() {
        val resumed = CardMenu.items(hasProgress = true, favorite = true, queued = true, inHistory = false, hotList = false)
        val fresh = CardMenu.items(hasProgress = false, favorite = false, queued = false, inHistory = false, hotList = false)
        assertEquals(labels(fresh), listOf("直接播放", "收藏这部剧", "加入稍后观看", "查看剧集详情"))
        assertEquals(actions(resumed), actions(fresh))
    }
}
