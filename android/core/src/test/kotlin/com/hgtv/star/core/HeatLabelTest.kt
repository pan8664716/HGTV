// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 热度文案的回归测试。
 *
 * 这一条最容易被「顺手改一下格式」破坏：`9014万` 改成 `9014.0万`、`1.2亿` 改成 `1.20亿`，
 * 两端就会各显示一种写法。用例把两种来源、四档区间都钉住。
 */
class HeatLabelTest {

    @Test
    fun `服务端原串优先且原样使用`() {
        assertEquals("9014万", HeatLabel.of("9014万", 0))
        // 同一部剧既有原串又有数字时，用原串（它是服务端自己排好的）。
        assertEquals("9014万", HeatLabel.of("9014万", 12040000))
    }

    @Test
    fun `原串带热度后缀时剥掉`() {
        assertEquals("1204万", HeatLabel.of("1204万热度", 0))
        assertEquals("1204万", HeatLabel.of("  1204万热度  ", 0))
    }

    @Test
    fun `没有原串时按数字换算`() {
        assertEquals("1204万", HeatLabel.of(null, 12040000))
        assertEquals("1204万", HeatLabel.of("", 12040000))
        assertEquals("1.2亿", HeatLabel.of("   ", 120000000))
    }

    @Test
    fun `没有任何热度时返回空串`() {
        assertEquals("", HeatLabel.of(null, 0))
        assertEquals("", HeatLabel.of("", 0))
        assertEquals("", HeatLabel.of("热度", 0))
    }

    @Test
    fun `万位：百万以下一位小数 以上整数`() {
        assertEquals("1万", HeatLabel.format(10_000))
        assertEquals("1.2万", HeatLabel.format(12_345))
        assertEquals("1.2万", HeatLabel.format(12_000))
        // 100 万起改整数，与接口那串「1204万」同一写法。
        assertEquals("100万", HeatLabel.format(999_999))
        assertEquals("1204万", HeatLabel.format(12_040_000))
    }

    @Test
    fun `亿位：一位小数且整数不留点零`() {
        assertEquals("1亿", HeatLabel.format(100_000_000))
        assertEquals("1.2亿", HeatLabel.format(120_000_000))
        assertEquals("2亿", HeatLabel.format(200_000_000))
        assertEquals("12.5亿", HeatLabel.format(1_250_000_000))
    }

    @Test
    fun `一万以下原样显示`() {
        assertEquals("8632", HeatLabel.format(8_632))
        assertEquals("1", HeatLabel.format(1))
    }

    @Test
    fun `负数与零不显示`() {
        assertEquals("", HeatLabel.format(0))
        assertEquals("", HeatLabel.format(-1))
    }

    /**
     * 角标文案 = 火苗 + 数值。
     *
     * 火苗是 emoji 字符而不是自绘图标，因此这里把它钉成常量：
     * 两端显示的是同一个字，换图标时只改 [HeatLabel.FIRE]。
     */
    @Test
    fun `角标文案带火苗`() {
        assertEquals("\uD83D\uDD25" + "9014万", HeatLabel.badge("9014万", 0))
        assertEquals("\uD83D\uDD25" + "1204万", HeatLabel.badge(null, 12_040_000))
    }

    @Test
    fun `没有热度时角标文案为空`() {
        assertEquals("", HeatLabel.badge(null, 0))
        assertEquals("", HeatLabel.badge("", 0))
    }
}
