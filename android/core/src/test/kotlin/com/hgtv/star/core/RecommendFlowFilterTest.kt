// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 推荐取页的两条两端共用判定（[RecommendFlow.fresh] / [RecommendFlow.stalled]）。
 *
 * 六条过滤规则原先在电视与手机里各写一遍——任何一端少一条，两端推荐就会不一致。
 */
class RecommendFlowFilterTest {

    private fun series(id: String, title: String) = Series(id = id, title = title)

    @Test
    fun each_rule_removes_its_own_case() {
        val items = listOf(
            series("1", "留着的这一部"),
            series("2", "手动静掉了"),
            series("3", "已经追完了"),
            series("4", "曝光窗口里有"),
            series("5", "本会话已经在列表里"),
            series("6", "剧名推过"),
            series("7", "换季的另一部 第二季"),
        )
        val kept = RecommendFlow.fresh(
            items = items,
            hidden = setOf("2"),
            finished = setOf("3"),
            seen = setOf("4"),
            loadedIds = setOf("5"),
            loadedTitles = setOf(RecommendFlow.titleKey("换季的另一部 第一季")),
            seenTitle = { it.startsWith("剧名推过") },
        )
        assertEquals(listOf("1"), kept.map { it.id })
    }

    @Test
    fun recommendation_feed_can_ignore_exposure_history() {
        val items = listOf(series("1", "曝光窗口里推过"), series("2", "新的一部"))
        val kept = RecommendFlow.fresh(
            items = items,
            hidden = emptySet(),
            finished = emptySet(),
            seen = setOf("1"),
            loadedIds = emptySet(),
            loadedTitles = emptySet(),
            seenTitle = { it == "曝光窗口里推过" },
            filterExposure = false,
        )
        assertEquals(listOf("1", "2"), kept.map { it.id })
    }

    @Test
    fun feed_filter_ids_only_uses_the_current_window() {
        val items = listOf(series("1", "一"), series("2", "二"), series("3", "三"))
        assertEquals("2,3", RecommendFlow.feedFilterIds(items, limit = 2))
    }

    @Test
    fun same_id_appearing_twice_in_one_page_is_kept_as_is() {
        // 本函数只按「外部给的集合」过滤，不负责页内去重（服务端偶发同页重复，
        // 由列表层的 id 判断处理）。锁住这一点，避免以后有人顺手加去重改变行为。
        val items = listOf(series("1", "同一部"), series("1", "同一部"))
        val kept = RecommendFlow.fresh(
            items = items, hidden = emptySet(), finished = emptySet(), seen = emptySet(),
            loadedIds = emptySet(), loadedTitles = emptySet(),
        )
        assertEquals(2, kept.size)
    }

    @Test
    fun empty_page_stays_empty() {
        val kept = RecommendFlow.fresh(
            items = emptyList(), hidden = emptySet(), finished = emptySet(), seen = emptySet(),
            loadedIds = emptySet(), loadedTitles = emptySet(),
        )
        assertTrue(kept.isEmpty())
    }

    @Test
    fun blank_title_does_not_match_blank_keys() {
        // 没有剧名的卡片不能被空标题的「已推剧名」误删：titleKey 为空时跳过这一条。
        val items = listOf(series("1", ""))
        val kept = RecommendFlow.fresh(
            items = items, hidden = emptySet(), finished = emptySet(), seen = emptySet(),
            loadedIds = emptySet(), loadedTitles = setOf(""),
            seenTitle = { false },
        )
        assertEquals(1, kept.size)
    }

    @Test
    fun stalled_only_after_first_page() {
        // 流头不算停滞（offset==0 时 next_offset 前进与否是下一页的事）。
        assertFalse(RecommendFlow.stalled(0, 0))
        assertFalse(RecommendFlow.stalled(0, 20))
        assertTrue(RecommendFlow.stalled(20, 20))
        assertTrue(RecommendFlow.stalled(40, 30))
        assertFalse(RecommendFlow.stalled(20, 40))
    }

    @Test
    fun filter_miss_disables_on_second_strike() {
        val first = RecommendFlow.noteFilterMiss(0)
        assertEquals(1, first.first)
        assertFalse(first.second)
        val second = RecommendFlow.noteFilterMiss(first.first)
        assertEquals(RecommendFlow.FILTER_MISS_LIMIT, second.first)
        assertTrue(second.second)
    }
}
