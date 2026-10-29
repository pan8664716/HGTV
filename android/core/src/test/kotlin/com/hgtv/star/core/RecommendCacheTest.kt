// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 推荐快照 + 「停在哪一部」的落盘：冷启动续播（i → i+1）全靠这两份文件。 */
class RecommendCacheTest {
    private lateinit var dir: File

    @Before fun setUp() {
        dir=Files.createTempDirectory("hgtv-feed").toFile()
        RecommendCache.init(File(dir,"feed-v1.json"))
        RecommendCache.clear()
    }

    @After fun tearDown() {
        RecommendCache.clear()
        dir.deleteRecursively()
    }

    private fun series(id: String)=Series(id,"剧$id","https://c/$id.jpg")

    private fun await(condition: () -> Boolean) {
        val deadline=System.currentTimeMillis()+2_000
        while(!condition() && System.currentTimeMillis()<deadline) Thread.sleep(10)
    }

    @Test fun snapshotRoundTrip() {
        RecommendCache.save((1..5).map { series("$it") })
        await { dir.listFiles()!!.any { it.name=="feed-v1.json" } }
        val items=RecommendCache.load()
        assertEquals(listOf("1","2","3","4","5"),items.map { it.id })
        assertEquals("剧3",items[2].title)
    }

    /** 「停在哪一部」与快照分开落盘：读回来必须就是那一部（续播时 +1 靠它）。 */
    @Test fun positionRoundTrip() {
        RecommendCache.save((1..3).map { series("$it") })
        await { dir.listFiles()!!.any { it.name=="feed-v1.json" } }
        // 快照还没落盘时位置不算数（两份是同一轮写的）。
        RecommendCache.rememberPlayed("2")
        await { RecommendCache.lastPlayed()=="2" }
        assertEquals("2",RecommendCache.lastPlayed())
    }

    /** 没有快照时位置文件不该单独生效：拿一部「不在任何列表里」的剧去 +1 是错的。 */
    @Test fun positionWithoutSnapshotIsIgnored() {
        RecommendCache.rememberPlayed("9")
        Thread.sleep(200)
        assertEquals("",RecommendCache.lastPlayed())
    }

    @Test fun clearRemovesBoth() {
        RecommendCache.save((1..2).map { series("$it") })
        RecommendCache.rememberPlayed("1")
        await { RecommendCache.lastPlayed()=="1" }
        RecommendCache.clear()
        assertTrue(dir.listFiles().orEmpty().none { it.name.startsWith("feed-v1") })
        assertEquals("",RecommendCache.lastPlayed())
    }
}
