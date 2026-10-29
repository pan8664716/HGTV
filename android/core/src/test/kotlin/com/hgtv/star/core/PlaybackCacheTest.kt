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
 * 落盘缓存的两件事必须成立，否则二次启动会**更慢**（读坏数据 → 重新下载 → 再写一遍）：
 *
 * 1. 索引写出去再读回来，与原来逐字段一致（含样本偏移、长度、IV、加密子样本偏移）；
 * 2. 数据坏了 / 文件总长对不上时返回 null，而不是把半个索引交给播放器。
 */
class PlaybackCacheTest {
    private lateinit var dir: File

    @Before fun setUp() {
        dir=Files.createTempDirectory("hgtv-cache").toFile()
        PlaybackCache.init(dir)
    }

    @After fun tearDown() {
        PlaybackCache.clear()
        PlaybackCache.detach()
        dir.deleteRecursively()
    }

    private fun prepared(): MediaCrypto.Prepared {
        val header=ByteArray(64) { it.toByte() }
        val samples=listOf(
            MediaCrypto.Sample(1000,1400,ByteArray(16) { (it+1).toByte() }),
            MediaCrypto.Sample(1400,2600,ByteArray(16) { (it+2).toByte() },48),
            MediaCrypto.Sample(2600,2601,ByteArray(16) { (it+3).toByte() })
        )
        return MediaCrypto.Prepared(header,samples)
    }

    @Test fun indexRoundTrip() {
        val original=prepared()
        PlaybackCache.saveIndex("7663468610496515096:1080",9_000_000L,original)
        val loaded=PlaybackCache.loadIndex("7663468610496515096:1080")
        assertNotNull(loaded)
        assertEquals(9_000_000L,loaded!!.total)
        assertTrue(original.header.contentEquals(loaded.prepared.header))
        assertEquals(original.samples.size,loaded.prepared.samples.size)
        original.samples.zip(loaded.prepared.samples).forEach { (a,b) ->
            assertEquals(a.start,b.start)
            assertEquals(a.end,b.end)
            assertEquals(a.encryptedOffset,b.encryptedOffset)
            // IV 只落前 8 字节，后 8 字节读回时补 0——这正是 `MediaCrypto.prepare` 的约定。
            assertTrue(a.iv.copyOfRange(0,8).contentEquals(b.iv.copyOfRange(0,8)))
            assertTrue(b.iv.copyOfRange(8,16).all { it==0.toByte() })
        }
        // 另一个键不能命中（缓存键是哈希，串键就是「放错集」）。
        assertNull(PlaybackCache.loadIndex("7663468610496515097:1080"))
    }

    @Test fun brokenIndexIsDropped() {
        PlaybackCache.saveIndex("42:480",1_000L,prepared())
        val file=dir.listFiles()!!.first { it.name.startsWith("idx-") }
        // 截断一半：读回来必须是 null，而且文件已经被删掉（下次不再白读一遍）。
        val bytes=file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size/2))
        assertNull(PlaybackCache.loadIndex("42:480"))
        assertTrue(dir.listFiles()!!.none { it.name.startsWith("idx-") })
    }

    @Test fun indexWithBytesBeyondTotalIsDropped() {
        // 文件缩了（重新上传了一版切片）：样本尾部越界，不能拿去喂播放器。
        PlaybackCache.saveIndex("7:720",500L,prepared())
        assertNull(PlaybackCache.loadIndex("7:720"))
    }

    @Test fun urlRoundTripAndExpiry() {
        val info=StreamInfo("https://cdn.example.com/a.mp4?sig=1",ByteArray(16) { 7 }, "1080P")
        PlaybackCache.saveUrl("123:1080:4",info)
        val loaded=PlaybackCache.loadUrl("123:1080:4")
        assertNotNull(loaded)
        assertEquals(info.url,loaded!!.info.url)
        assertEquals("1080P",loaded.info.quality)
        assertTrue(info.key!!.contentEquals(loaded.info.key))
        // 另一档 / 另一台设备档位不能命中。
        assertNull(PlaybackCache.loadUrl("123:720:4"))
        PlaybackCache.invalidateUrl("123:1080:4")
        assertNull(PlaybackCache.loadUrl("123:1080:4"))
    }

    @Test fun noDirectoryMeansNoCache() {
        PlaybackCache.detach()
        assertNull(PlaybackCache.loadIndex("1:1080"))
        // 没有目录时写入是空操作，不抛异常（冷启动早期 / 存储不可用时也走这条路）。
        PlaybackCache.saveIndex("1:1080",1_000L,prepared())
        PlaybackCache.saveUrl("1:1080:4",StreamInfo("https://a/b",null,"480P"))
    }

    // ── 首块（前 512KB 原始字节） ────────────────────────────────────────────────

    /**
     * 首块往返：这是「主页 → 推荐」/ 退出再进同一集时首帧唯一还在等的东西，
     * 读回来必须**逐字节一致**（差一个字节就是解密偏移全错，画面直接花掉）。
     */
    @Test fun headRoundTrip() {
        val head=ByteArray(512*1024) { (it*31).toByte() }
        PlaybackCache.saveHead("7663468610496515096:1080",9_000_000L,head)
        val loaded=PlaybackCache.loadHead("7663468610496515096:1080",9_000_000L)
        assertNotNull(loaded)
        assertEquals(head.size,loaded!!.size)
        assertTrue(head.contentEquals(loaded))
        // 另一集不能命中（串键就是「放错集」，画面会是别人的）。
        assertNull(PlaybackCache.loadHead("7663468610496515097:1080",9_000_000L))
    }

    /**
     * `total` 对不上 = 这一集换了一版切片：首块必须作废，否则解密偏移与新文件错位，
     * 表现是「有声黑屏」这种最难查的故障。
     */
    @Test fun headWithWrongTotalIsDropped() {
        PlaybackCache.saveHead("7:720",9_000_000L,ByteArray(4096) { 1 })
        assertNull(PlaybackCache.loadHead("7:720",8_000_000L))
        // 丢掉的这一份要顺手删掉：下次不再白读一遍。
        assertTrue(dir.listFiles()!!.none { it.name.startsWith("head-") })
    }

    /** 索引不认了（403/404、样本越界）= 内容变了：首块必须跟着一起删。 */
    @Test fun headDroppedWithIndex() {
        PlaybackCache.saveIndex("9:1080",9_000_000L,prepared())
        PlaybackCache.saveHead("9:1080",9_000_000L,ByteArray(2048) { 2 })
        assertNotNull(PlaybackCache.loadHead("9:1080",9_000_000L))
        PlaybackCache.invalidateIndex("9:1080")
        assertNull(PlaybackCache.loadIndex("9:1080"))
        assertNull(PlaybackCache.loadHead("9:1080",9_000_000L))
    }

    /** 首块损坏 / 被截断：返回 null，绝不把半个字节数组交给解密。 */
    @Test fun brokenHeadIsDropped() {
        PlaybackCache.saveHead("11:1080",9_000_000L,ByteArray(1024) { 3 })
        val file=dir.listFiles()!!.first { it.name.startsWith("head-") }
        val bytes=file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size-16))
        assertNull(PlaybackCache.loadHead("11:1080",9_000_000L))
        assertTrue(dir.listFiles()!!.none { it.name.startsWith("head-") })
    }
}
