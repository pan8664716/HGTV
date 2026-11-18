package com.hgtv.star.core

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuTest {
    /** 一段真实的窗口响应（截取字段，结构与平台一致）。 */
    private val window="""
    [
      {"comment":{"comment_id":"1","common":{"content":{"text":"终于更新啦！"}},"expand":{"offset_time":0},"stat":{"digg_count":22}}},
      {"comment":{"comment_id":"2","common":{"content":{"text":"  前面高能  "}},"expand":{"offset_time":478},"stat":{"digg_count":75}}},
      {"comment":{"comment_id":"3","common":{"content":{"text":""}},"expand":{"offset_time":604},"stat":{"digg_count":1}}},
      {"comment":{"comment_id":"4","common":{"content":{"text":"没有时间点"}},"expand":{},"stat":{"digg_count":3}}},
      {"comment":{"comment_id":"5","common":{"content":{}},"expand":{"offset_time":736}}},
      {"comment":{"common":{}}}
    ]
    """.trimIndent()

    @Test fun `parses only complete danmaku and trims the text`() {
        val items=parseDanmakuRows(JSONArray(window))
        assertEquals(listOf(0L,478L),items.map { it.offsetMs })
        assertEquals(listOf("终于更新啦！","前面高能"),items.map { it.text })
        assertEquals(listOf(22,75),items.map { it.diggCount })
    }

    @Test fun `missing data list is an empty window`() {
        assertEquals(emptyList<Danmaku>(),parseDanmakuRows(null))
        assertEquals(emptyList<Danmaku>(),parseDanmakuRows(JSONArray()))
    }

    @Test fun `hidden danmaku has no text style to adjust`() {
        // 隐藏档整层不画、也不发请求，字号与透明度改完看不到变化，
        // 设置页与播放设置面板都要把这两项收起来；其余两档照常显示。
        assertTrue(DanmakuMode.SIX.showsStyle)
        assertTrue(DanmakuMode.FIVE.showsStyle)
        assertFalse(DanmakuMode.HIDDEN.showsStyle)
    }

    @Test fun `danmaku mode round-trips through storage`() {
        DanmakuMode.entries.forEach { mode -> assertEquals(mode,DanmakuMode.fromStored(mode.storedValue)) }
        assertEquals(DanmakuMode.FIVE,DanmakuMode.fromStored(null))
        assertEquals(DanmakuMode.FIVE,DanmakuMode.fromStored("unknown"))
        assertEquals(DanmakuMode.FIVE,DanmakuMode.fromStored("top"))
        assertEquals(DanmakuMode.SIX,DanmakuMode.fromStored("full"))
    }

    @Test fun `quick toggle follows numeric order`() {
        DanmakuMode.entries.forEachIndexed { index,mode ->
            val expected=DanmakuMode.entries[(index+1)%DanmakuMode.entries.size]
            assertEquals(expected,mode.next())
        }
    }

    @Test fun `highlight slots grow every two lines`() {
        // 1~2 行只放一条聚合，3~4 行两条，5~6 行三条。
        assertEquals(listOf(1,1,2,2,3,3),
            (1..6).map { DanmakuHighlight.slotsForLines(it) })
        // 越界输入收敛到合法区间。
        assertEquals(1,DanmakuHighlight.slotsForLines(0))
        assertEquals(DanmakuHighlight.MAX_SLOTS,DanmakuHighlight.slotsForLines(99))
    }

    @Test fun `aggregation normalizes expression differences conservatively`() {
        assertEquals(
            danmakuAggregationKey("太好看了！！！[爱慕]"),
            danmakuAggregationKey(" 太好看了[哭] "),
        )
        assertEquals(
            danmakuAggregationKey("哈哈哈哈哈哈"),
            danmakuAggregationKey("哈哈哈"),
        )
        assertFalse(
            danmakuAggregationKey("不行")==danmakuAggregationKey("不行吧"),
        )
    }
}
