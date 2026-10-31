// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 推荐筛选项的降级状态机（[OptionalFilter]）：回空页先重试、两次即永久关掉。
 *
 * 这条规则对应两个真实的用户可见问题：
 * * 电视上选了「男频」之后推荐页整页「推荐暂时取不到」；
 * * 手机上的「推荐受众」设置从来不发出去（形同虚设）。
 */
class OptionalFilterTest {

    @Test
    fun first_empty_asks_for_a_retry() {
        val filter = OptionalFilter("gender")
        assertTrue(filter.onEmpty())
        assertEquals(1, filter.misses)
        assertFalse("第一次空页只是重试，不该直接关掉", filter.off)
    }

    @Test
    fun second_empty_disables_the_layer() {
        val filter = OptionalFilter("gender")
        filter.onEmpty()
        filter.onEmpty()
        assertEquals(2, filter.misses)
        assertTrue(filter.off)
    }

    @Test
    fun filled_resets_the_counter() {
        val filter = OptionalFilter("gender")
        filter.onEmpty()
        filter.onFilled()
        assertEquals(0, filter.misses)
        assertFalse(filter.off)
        // 偶发空页不该累积成「永久关掉」：清零之后再空一次仍然只是重试。
        assertTrue(filter.onEmpty())
        assertFalse(filter.off)
    }

    @Test
    fun reset_gives_the_layer_another_chance() {
        val filter = OptionalFilter("gender")
        filter.onEmpty(); filter.onEmpty()
        assertTrue(filter.off)
        filter.reset()
        assertFalse(filter.off)
        assertEquals(0, filter.misses)
    }

    @Test
    fun custom_limit_is_honoured() {
        // `filter_ids` 用的是默认 2；单测另一个阈值，确认不是写死。
        val once = OptionalFilter("filter_ids", limit = 1)
        once.onEmpty()
        assertTrue(onlyFirst(once))
    }

    private fun onlyFirst(filter: OptionalFilter): Boolean = filter.off
}
