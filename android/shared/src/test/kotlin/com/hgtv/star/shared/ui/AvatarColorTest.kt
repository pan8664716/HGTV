// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 头像底色的回归测试。
 *
 * 重点不是「颜色好不好看」，而是**取值稳定**：同一串名字永远同一个颜色，
 * 且取值必须落在那一组深色里。用户靠「颜色 + 首字」认出这是同一个人，
 * 一旦算法漂移（换哈希初值、重排色盘），所有已有记录的颜色会整体换一遍。
 */
class AvatarColorTest {

    /** 与 [authorColor] 内部同一组色值，用于校验「结果一定来自这个集合」。 */
    private val palette = setOf(
        0xFF35596E.toInt(), 0xFF44576F.toInt(), 0xFF5A4A6B.toInt(),
        0xFF3F6357.toInt(), 0xFF6B5540.toInt(), 0xFF4C4C63.toInt(),
    )

    @Test
    fun `结果只来自那六个深色`() {
        for (name in listOf("红果", "编剧甲", "导演乙", "", "a", "很长很长的作者名字用来占位")) {
            assertTrue("$name 取到了色盘之外的颜色", authorColor(name) in palette)
        }
    }

    @Test
    fun `同名每次都是同一个颜色`() {
        repeat(5) {
            assertEquals(authorColor("红果短剧"), authorColor("红果短剧"))
        }
    }

    @Test
    fun `不同名字会分到不同颜色`() {
        // 六个色位，取七个不同名字，至少出现两种颜色——否则等于「永远同一个色」。
        val names = listOf("a", "b", "c", "d", "e", "f", "g")
        assertTrue(names.map(::authorColor).toSet().size > 1)
    }

    /**
     * 钉住具体取值：这条用例是**跨端一致性**的护栏。
     *
     * 电视端与手机端现在都调这一个函数，因此两端必然一致；但如果有人改了色盘顺序
     * 或哈希初值，这条会立刻红——那正是「所有用户头像换色」的告警。
     */
    @Test
    fun `具体取值不随重构漂移`() {
        assertEquals(0xFF4C4C63.toInt(), authorColor("红果"))
        assertEquals(0xFF44576F.toInt(), authorColor(""))
        assertEquals(0xFF5A4A6B.toInt(), authorColor("a"))
    }

    @Test
    fun `非空名字不会落到负数下标`() {
        // 哈希可能为负，实现里用 `and 0x7FFFFFFF` 抹掉符号位；这里直接跑一批名字确认不抛异常。
        val names = (0 until 200).map { "作者$it" } + listOf("\uD83D\uDE00", "zzz", "Z")
        for (name in names) {
            assertTrue("$name 取到了色盘之外的颜色", authorColor(name) in palette)
        }
    }
}
