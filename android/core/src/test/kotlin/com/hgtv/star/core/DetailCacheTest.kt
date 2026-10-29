// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 详情落盘缓存：推荐页起播的「详情」这一段（0.4~0.6s）靠它省掉。
 *
 * 三条必须成立：
 *
 * 1. 写进去再读回来，剧卡片与分集表逐项一致（分集顺序错了会播成别的集）；
 * 2. 坏文件 / 版本不符 → 返回 null（宁可重新取，也不能把半份数据交给起播）；
 * 3. 超上限时按写入顺序淘汰最旧的那条，不会无限长草。
 */
class DetailCacheTest {
    private lateinit var file: File

    @Before fun setUp() {
        file=File(Files.createTempDirectory("hgtv-detail").toFile(),"detail-v1.json")
        DetailCache.init(file)
        DetailCache.clear()
    }

    @After fun tearDown() {
        DetailCache.clear()
        file.parentFile?.deleteRecursively()
    }

    private fun detail(id: String,episodes: Int)=Detail(
        Series(id,"剧$id","https://c/$id.jpg","简介","全${episodes}集","都市",author="出品方",
            episodeCount=episodes,seasonIndex=1,notice="预告",vertical=true),
        (1..episodes).map { "$id$it" },upcoming=episodes==0,reserved=true
    )

    @Test fun roundTrip() {
        val original=detail("7663",3)
        DetailCache.save(original)
        val loaded=DetailCache.load("7663")
        assertNotNull(loaded)
        assertEquals(original.series,loaded!!.series)
        assertEquals(original.episodes,loaded.episodes)
        assertEquals(original.upcoming,loaded.upcoming)
        assertEquals(original.reserved,loaded.reserved)
        // 另一部不能命中。
        assertNull(DetailCache.load("7664"))
    }

    /** 落盘之后**新的一次进程**（这里用重新 init 模拟）还要能读到。 */
    @Test fun survivesReinit() {
        DetailCache.save(detail("42",2))
        // 写盘在后台线程：等它落下来（正常几毫秒）。
        val deadline=System.currentTimeMillis()+2_000
        while(!file.isFile && System.currentTimeMillis()<deadline) Thread.sleep(10)
        assertTrue(file.isFile)
        DetailCache.init(file)
        assertNotNull(DetailCache.load("42"))
    }

    @Test fun brokenFileIsIgnored() {
        // 半份 / 乱码的文件：返回 null，而不是把解析到一半的详情交给起播。
        file.writeText("{ 这不是 JSON")
        DetailCache.init(file)
        assertNull(DetailCache.load("42"))
        // 坏文件不该让写入也失败：下一次取到真详情仍然能存下来。
        DetailCache.save(detail("42",1))
        assertNotNull(DetailCache.load("42"))
    }

    @Test fun oldestEntryIsDropped() {
        repeat(9) { index -> DetailCache.save(detail("${100+index}",1)) }
        // 上限 8 条：最早写的那一条被淘汰，其余都在。
        assertNull(DetailCache.load("100"))
        assertNotNull(DetailCache.load("108"))
    }

    @Test fun noFileMeansNoCache() {
        DetailCache.init(File(file.parentFile,"missing.json"))
        assertNull(DetailCache.load("42"))
    }
}
