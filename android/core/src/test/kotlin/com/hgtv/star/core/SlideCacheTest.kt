// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双向滑动的三条规则必须能各自单独验证，否则接进推荐页之后没法判断
 * 「回看慢」到底是窗口没覆盖，还是被 fling 降级了。
 */
class SlideCacheTest {
    private fun levels(plan: SlideCacheManager.Plan)=plan.targets.associate { it.index to it.level }

    @Test fun windowIsAsymmetricAndLevelsDescend() {
        val slide=SlideCacheManager()
        val plan=slide.onItemChanged(5,1,1_000L)
        val levels=levels(plan)
        // 主方向：+1 完整、+2 元数据、+3 仅地址。
        assertEquals(CacheLevel.FULL,levels[6])
        assertEquals(CacheLevel.META,levels[7])
        assertEquals(CacheLevel.ADDRESS,levels[8])
        // 反方向：-1 元数据（刚看过，回看概率高）、-2 仅地址。
        assertEquals(CacheLevel.META,levels[4])
        assertEquals(CacheLevel.ADDRESS,levels[3])
        // 非对称：向前 3 条、向后 2 条。
        assertEquals(5,plan.targets.size)
        assertEquals(SlideCacheManager.Mode.NORMAL,plan.mode)
    }

    @Test fun directionFlipsTheWholeWindow() {
        val slide=SlideCacheManager()
        val plan=slide.onItemChanged(5,-1,1_000L)
        val levels=levels(plan)
        // 往回滑时，回看方向就是主方向：-1 必须完整预热。
        assertEquals(CacheLevel.FULL,levels[4])
        assertEquals(CacheLevel.META,levels[3])
        assertEquals(CacheLevel.ADDRESS,levels[2])
        assertEquals(CacheLevel.META,levels[6])
        assertEquals(CacheLevel.ADDRESS,levels[7])
    }

    @Test fun fastSlidesDegradeToAddressOnlyThenRecoverOnDwell() {
        val slide=SlideCacheManager()
        // 一秒两三部：连续三次 400ms 间隔的换台（第一次没有「上一次」可比，不计入连滑）。
        slide.onItemChanged(1,1,1_000L)
        slide.onItemChanged(2,1,1_400L)
        slide.onItemChanged(3,1,1_800L)
        val fling=slide.onItemChanged(4,1,2_200L)
        assertEquals(SlideCacheManager.Mode.FLING,fling.mode)
        assertEquals(SlideCacheManager.Reason.FLING,fling.reason)
        // 降级后只预取地址：一个字节的首块都不拉。
        assertTrue(fling.targets.all { it.level==CacheLevel.ADDRESS })
        // 停下来 1.2 秒：恢复完整预热。
        val dwell=slide.onDwell(4,3_400L)
        assertEquals(SlideCacheManager.Mode.NORMAL,dwell.mode)
        assertEquals(CacheLevel.FULL,levels(dwell)[5])
    }

    @Test fun slowSlidesStayNormal() {
        val slide=SlideCacheManager()
        slide.onItemChanged(1,1,1_000L)
        slide.onItemChanged(2,1,2_200L)
        val plan=slide.onItemChanged(3,1,3_500L)
        assertEquals(SlideCacheManager.Mode.NORMAL,plan.mode)
        assertEquals(CacheLevel.FULL,levels(plan)[4])
    }

    @Test fun revisitingSoonPromotesTwoStepsAhead() {
        val slide=SlideCacheManager()
        slide.onItemChanged(5,1,1_000L)
        slide.onItemChanged(6,1,2_000L)
        // 3 秒内滑回 5：这一部是「用户关注」的，主方向两步都升完整预热。
        val back=slide.onItemChanged(5,-1,4_000L)
        assertEquals(SlideCacheManager.Reason.REVISIT,back.reason)
        val levels=levels(back)
        assertEquals(CacheLevel.FULL,levels[4])
        assertEquals(CacheLevel.FULL,levels[3])
        // 回看方向那一条也升到完整（刚滑回来的反方向就是「下一步很可能去的地方」）。
        assertEquals(CacheLevel.FULL,levels[6])
    }

    @Test fun directionStreakExtendsTheForwardWindow() {
        val slide=SlideCacheManager()
        slide.onItemChanged(1,1,1_000L)
        slide.onItemChanged(2,1,2_500L)
        slide.onItemChanged(3,1,4_000L)
        // 连续三次同向：主方向再放远一条（第 4 条，只取地址）。
        val plan=slide.onItemChanged(4,1,5_500L)
        assertEquals(CacheLevel.ADDRESS,levels(plan)[8])
    }

    @Test fun evictionKeepsTheWindowAndProtectsDwelledItems() {
        val slide=SlideCacheManager()
        slide.onItemChanged(10,1,1_000L)
        val entries=listOf(
            // 窗口内：不淘汰。
            SlideCacheManager.Entry("near-forward",11,1_000L,0,1),
            // 刚看过的、停留了 3 秒：即使远也要留住。
            SlideCacheManager.Entry("dwelled",6,900L,3_000L,1),
            // 远、没停留、在反方向：最先淘汰。
            SlideCacheManager.Entry("far-back",1,950L,0,-1),
            SlideCacheManager.Entry("mid-forward",14,950L,0,1)
        )
        val order=slide.evictionOrder(entries,10,2_000L)
        assertTrue("near-forward" !in order)
        assertEquals("far-back",order.first())
        assertTrue(order.indexOf("mid-forward")<order.indexOf("dwelled"))
    }

    @Test fun trimKeepsOnlyTheImmediateNextItem() {
        val slide=SlideCacheManager()
        slide.onItemChanged(5,1,1_000L)
        slide.onTrimMemory(severe=true)
        val plan=slide.plan(5)
        assertEquals(SlideCacheManager.Reason.TRIM,plan.reason)
        assertEquals(listOf(SlideCacheManager.Target(6,CacheLevel.FULL)),plan.targets)
        slide.resetTrim()
        assertEquals(5,slide.plan(5).targets.size)
    }
}
