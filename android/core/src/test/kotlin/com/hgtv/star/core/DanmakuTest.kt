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
        assertTrue(DanmakuMode.FULL.showsStyle)
        assertTrue(DanmakuMode.TOP.showsStyle)
        assertFalse(DanmakuMode.HIDDEN.showsStyle)
    }

    @Test fun `danmaku mode round-trips through storage`() {
        DanmakuMode.entries.forEach { mode -> assertEquals(mode,DanmakuMode.fromStored(mode.storedValue)) }
        assertEquals(DanmakuMode.FULL,DanmakuMode.fromStored(null))
        assertEquals(DanmakuMode.FULL,DanmakuMode.fromStored("unknown"))
    }

    @Test fun `quick toggle cycles through every mode and returns to full`() {
        // 手机播放页底部那颗「弹幕」按钮：一次点击走一格。
        // 四档必须覆盖全部枚举值（新增档位时这条会失败，提醒把循环补上），
        // 并且从任何一档出发连点四次都要回到原档。
        var mode=DanmakuMode.FULL
        val seen=mutableListOf(mode)
        repeat(3) { mode=mode.next(); seen+=mode }
        assertEquals(DanmakuMode.entries.toSet(),seen.toSet())
        assertEquals(DanmakuMode.FULL,mode.next())
        DanmakuMode.entries.forEach { start ->
            var m=start
            repeat(DanmakuMode.entries.size) { m=m.next() }
            assertEquals(start,m)
        }
    }
}
