// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滑杆触摸状态机的回归测试。
 *
 * 这四条规则原先在三个滑杆里各写一遍，而它们正是「滑杆手感」的全部内容：
 * 轻点要落值、纵向要放行给外层滚动、斜划不能吃掉纵向、抬起要清状态。
 * 抽成纯逻辑后可以在这里逐条钉住，不必上真机用手划。
 */
class SliderDragTest {

    private fun drag(slop: Int = 10) = SliderDrag(slop)

    @Test
    fun `轻点抬手返回 true 且从未进入拖动`() {
        val d = drag()
        d.down(100f, 100f)
        // 位移在 slop 以内：不算拖动。
        assertFalse(d.move(104f, 102f))
        assertFalse(d.dragging)
        assertTrue("轻点必须由 up() 报出来，调用方才会落值", d.up())
        assertFalse(d.dragging)
    }

    @Test
    fun `横向越过 slop 才算拖动 且只报一次`() {
        val d = drag(slop = 10)
        d.down(100f, 100f)
        assertTrue("第一次越过阈值要返回 true，调用方据此抢父容器拦截", d.move(120f, 100f))
        assertTrue(d.dragging)
        assertFalse("同一个手势里不该重复报「刚开始拖动」", d.move(140f, 100f))
        assertTrue(d.dragging)
    }

    @Test
    fun `纵向位移更大时不进入拖动`() {
        val d = drag(slop = 10)
        d.down(100f, 100f)
        // 斜着划：横向 20、纵向 60 —— 这一串事件该留给外层 ScrollView。
        assertFalse(d.move(120f, 160f))
        assertFalse(d.dragging)
        assertTrue("没拖动过，抬手仍算轻点", d.up())
    }

    @Test
    fun `刚好等于 slop 不算拖动`() {
        val d = drag(slop = 10)
        d.down(100f, 100f)
        assertFalse("阈值是「大于」，等于要放行", d.move(110f, 100f))
        assertFalse(d.dragging)
    }

    @Test
    fun `拖动后抬手不算轻点`() {
        val d = drag(slop = 10)
        d.down(100f, 100f)
        d.move(130f, 100f)
        assertFalse("已经拖过了，抬手不该再按 x 落一次值", d.up())
        assertFalse(d.dragging)
    }

    @Test
    fun `取消后状态清干净 下一次按下重新计`() {
        val d = drag(slop = 10)
        d.down(100f, 100f)
        d.move(130f, 100f)
        d.cancel()
        assertFalse(d.dragging)

        // 取消之后的一次轻点，仍然是轻点。
        d.down(0f, 0f)
        assertFalse(d.move(2f, 0f))
        assertTrue(d.up())
    }

    @Test
    fun `上一次的拖动状态不会带进下一次按下`() {
        val d = drag(slop = 10)
        d.down(100f, 100f)
        d.move(130f, 100f)
        // 不调用 up / cancel 直接再按下（框架在极端情况下会这样补事件）。
        d.down(200f, 200f)
        assertFalse(d.dragging)
        assertFalse(d.move(202f, 200f))
        assertTrue(d.up())
    }

    @Test
    fun `纵向优先于横向的边界 刚好 dx 等于 dy`() {
        val d = drag(slop = 10)
        d.down(100f, 100f)
        // dx == dy == 20：要求 dx 严格大于 dy 才算拖动。
        assertFalse(d.move(120f, 120f))
        assertFalse(d.dragging)
    }

    @Test
    fun `大 slop 下小幅移动一直不算拖动`() {
        val d = drag(slop = 100)
        d.down(0f, 0f)
        assertEquals(false, d.move(99f, 0f))
        assertTrue(d.move(101f, 0f))
    }
}
