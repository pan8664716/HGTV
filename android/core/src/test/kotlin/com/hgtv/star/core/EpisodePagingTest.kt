package com.hgtv.star.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选集分页的页码与区间文案（电视与手机共用同一份，见 [EpisodePaging]）。
 *
 * 这两件事以前两端各写一遍：页码算法每改一次都要对两处，文案里的破折号也可能各写一种。
 * 这里把它们钉死，任何一端想改口径都只能改这一个地方。
 */
class EpisodePagingTest {
    @Test fun `pages rounds up and never drops below one`() {
        assertEquals(1, EpisodePaging.pages(0, 20))
        assertEquals(1, EpisodePaging.pages(1, 20))
        assertEquals(1, EpisodePaging.pages(20, 20))
        assertEquals(2, EpisodePaging.pages(21, 20))
        assertEquals(5, EpisodePaging.pages(99, 20))
        // perPage 非法时不留空白：仍然算一页。
        assertEquals(1, EpisodePaging.pages(99, 0))
    }

    @Test fun `range clamps to the same page and covers the tail`() {
        assertEquals(0..19, EpisodePaging.range(99, 20, 0))
        assertEquals(20..39, EpisodePaging.range(99, 20, 1))
        // 最后一页不满：区间只到最后一集。
        assertEquals(80..98, EpisodePaging.range(99, 20, 4))
        // 越界页号夹到首 / 尾，不返回空区间（否则网格会铺成空白）。
        assertEquals(0..19, EpisodePaging.range(99, 20, -3))
        assertEquals(80..98, EpisodePaging.range(99, 20, 42))
        assertTrue(EpisodePaging.range(0, 20, 0).isEmpty())
    }

    @Test fun `labels are byte identical on both ends`() {
        assertEquals("第 1—20 集 · 共 99 集", EpisodePaging.rangeText(99, 20, 0))
        assertEquals("第 81—99 集 · 共 99 集", EpisodePaging.rangeText(99, 20, 4))
        assertEquals("第 1/5 页", EpisodePaging.pageText(99, 20, 0))
        assertEquals("第 5/5 页", EpisodePaging.pageText(99, 20, 99))
        assertEquals("21-40", EpisodePaging.chipText(99, 20, 1))
        assertEquals("", EpisodePaging.rangeText(0, 20, 0))
    }

    @Test fun `every episode belongs to exactly one page`() {
        val count = 137
        val perPage = 30
        val seen = mutableListOf<Int>()
        repeat(EpisodePaging.pages(count, perPage)) { page ->
            val r = EpisodePaging.range(count, perPage, page)
            assertTrue("page $page should not be empty", !r.isEmpty())
            seen += r.first..r.last
        }
        assertEquals((0 until count).toList(), seen)
    }
}
