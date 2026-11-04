// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统标签配色的回归测试。
 *
 * 三种真实数据（真机抓包）：
 *
 * | `tag_info` | 结果 |
 * | --- | --- |
 * | `{"text":"新剧","bg_color":["#00AE83"]}` | 绿色纯色 |
 * | `{"text":"红果首发","bg_color":[]}` | 没有色，走兜底 |
 * | 爆剧那类 | 两色，渐变 |
 */
class LabelStyleTest {

    private val green = 0xFF00AE83.toInt()

    @Test
    fun `服务端给一个色就是纯色`() {
        assertEquals(listOf(green), LabelStyle.colors("新剧", "#00AE83"))
    }

    @Test
    fun `服务端给两个色就是渐变`() {
        val colors = LabelStyle.colors("爆剧", "#E8402A,#9C1B10")
        assertEquals(2, colors.size)
        assertEquals(0xFFE8402A.toInt(), colors[0])
        assertEquals(0xFF9C1B10.toInt(), colors[1])
    }

    @Test
    fun `八位色带透明度也认`() {
        assertEquals(listOf(0x80FF0000.toInt()), LabelStyle.colors("随便", "#80FF0000"))
    }

    /** 服务端漏给颜色时按文案兜底：不能让「新剧」退成一枚灰标签。 */
    @Test
    fun `没给色时上新与新剧走绿色`() {
        assertEquals(listOf(green), LabelStyle.colors("新剧", ""))
        assertEquals(listOf(green), LabelStyle.colors("今日上新", ""))
        assertEquals(listOf(green), LabelStyle.colors("上新", ""))
    }

    @Test
    fun `没给色时爆剧走红色渐变`() {
        val colors = LabelStyle.colors("爆剧", "")
        assertEquals(2, colors.size)
        // 两色不同才算渐变（同色画出来还是纯色）。
        assertTrue(colors[0] != colors[1])
    }

    /** 认不出的标签返回空：调用方退回深色压暗层，比瞎猜一个颜色稳妥。 */
    @Test
    fun `认不出的标签没有配色`() {
        assertEquals(emptyList<Int>(), LabelStyle.colors("红果首发", ""))
        assertEquals(emptyList<Int>(), LabelStyle.colors("", ""))
    }

    @Test
    fun `色串非法时不参与也不崩`() {
        // 全是坏值 → 当成没给，按文案兜底。
        assertEquals(listOf(green), LabelStyle.colors("新剧", "#GGGGGG"))
        assertEquals(listOf(green), LabelStyle.colors("新剧", " , ,"))
        // 一个坏一个好 → 只取好的那个。
        assertEquals(listOf(green), LabelStyle.colors("爆剧", "#ZZZ,#00AE83"))
    }

    @Test
    fun `最多取两个色`() {
        val colors = LabelStyle.colors("爆剧", "#111111,#222222,#333333")
        assertEquals(2, colors.size)
    }
}
