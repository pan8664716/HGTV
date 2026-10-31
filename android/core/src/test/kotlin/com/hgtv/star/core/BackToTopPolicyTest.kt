// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「回到顶部」的显隐规则：两端共用这一份，因此锁死行为。
 *
 * 重点是**边界**与**还没量到行高**这两处：前者决定「下拉 5 行」到底是第 4 行末还是第 5 行末，
 * 后者决定列表首帧会不会闪一下按钮（行高为 0 时阈值算出来也是 0，任何滚动量都满足）。
 */
class BackToTopPolicyTest {

    @Test fun `four modes map to rows`() {
        assertEquals(BackToTopPolicy.ALWAYS, BackToTopPolicy.rows(BACK_TO_TOP_ALWAYS))
        assertEquals(5, BackToTopPolicy.rows(BACK_TO_TOP_ROWS5))
        assertEquals(8, BackToTopPolicy.rows(BACK_TO_TOP_ROWS8))
        assertEquals(BackToTopPolicy.NEVER, BackToTopPolicy.rows(BACK_TO_TOP_NEVER))
    }

    /** 认不出的值走出厂档：不能让界面卡在「什么都不显示」。 */
    @Test fun `unknown mode falls back to always`() {
        assertEquals(BackToTopPolicy.ALWAYS, BackToTopPolicy.rows(""))
        assertEquals(BackToTopPolicy.ALWAYS, BackToTopPolicy.rows("rows3"))
    }

    @Test fun `always shows even at the very top`() {
        assertTrue(BackToTopPolicy.visible(BACK_TO_TOP_ALWAYS, scrollY = 0, rowStride = 0))
        assertTrue(BackToTopPolicy.visible(BACK_TO_TOP_ALWAYS, scrollY = 4000, rowStride = 500))
    }

    @Test fun `never hides regardless of scroll`() {
        assertFalse(BackToTopPolicy.visible(BACK_TO_TOP_NEVER, scrollY = 0, rowStride = 500))
        assertFalse(BackToTopPolicy.visible(BACK_TO_TOP_NEVER, scrollY = 99_999, rowStride = 500))
    }

    /** 阈值是「恰好滚过 N 行」，4 行半还不显示、整 5 行就显示。 */
    @Test fun `rows5 waits for five full rows`() {
        val stride = 500
        assertFalse(BackToTopPolicy.visible(BACK_TO_TOP_ROWS5, 5 * stride - 1, stride))
        assertTrue(BackToTopPolicy.visible(BACK_TO_TOP_ROWS5, 5 * stride, stride))
    }

    @Test fun `rows8 waits for eight full rows`() {
        val stride = 320
        assertFalse(BackToTopPolicy.visible(BACK_TO_TOP_ROWS8, 8 * stride - 1, stride))
        assertTrue(BackToTopPolicy.visible(BACK_TO_TOP_ROWS8, 8 * stride, stride))
        // 8 行那一档在 5 行处仍然不显示（两档不能混）。
        assertFalse(BackToTopPolicy.visible(BACK_TO_TOP_ROWS8, 5 * stride, stride))
    }

    /** 行高还没量到（首帧）：那一档先不显示，避免在列表顶闪一下。 */
    @Test fun `unmeasured row stride hides row based modes`() {
        assertFalse(BackToTopPolicy.visible(BACK_TO_TOP_ROWS5, scrollY = 0, rowStride = 0))
        assertFalse(BackToTopPolicy.visible(BACK_TO_TOP_ROWS8, scrollY = 800, rowStride = 0))
    }

    /**
     * 四档文案：两端（电视设置格 / 手机设置行）读的是同一份，
     * 因此这里钉住「每一档都有自己的说法」——两档共用一个说法，用户就分不出自己选的是哪一档。
     */
    @Test fun `every mode has its own short and full label`() {
        val modes = listOf(
            BACK_TO_TOP_ALWAYS, BACK_TO_TOP_ROWS5, BACK_TO_TOP_ROWS8, BACK_TO_TOP_NEVER,
        )
        val full = modes.map { BackToTopPolicy.label(it) }
        val short = modes.map { BackToTopPolicy.shortLabel(it) }
        assertEquals(modes.size, full.toSet().size)
        assertEquals(modes.size, short.toSet().size)
        // 短读数必须真的比完整说法短，否则设置格上会被省略号吃掉尾巴。
        assertTrue(BackToTopPolicy.shortLabel(BACK_TO_TOP_ROWS5).length <
            BackToTopPolicy.label(BACK_TO_TOP_ROWS5).length)
        // 未知值按出厂档读，不让设置页显示一个选不中的档位。
        assertEquals(BackToTopPolicy.label(BACK_TO_TOP_ALWAYS), BackToTopPolicy.label("bogus"))
    }
}
