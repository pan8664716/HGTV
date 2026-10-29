package com.hgtv.star.core

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.net.InetSocketAddress

class FasterBrowsingTest {
    private class Resource: Closeable { var closed=false; override fun close() { closed=true } }
    @Test fun claimedPrefetchHasExactlyOneOwner() {
        var now=1L; val slot=PreparedSlot<Resource>({now}); val result=Resource()
        val ticket=slot.begin("episode:720"); assertTrue(slot.complete(ticket,"episode:720",result))
        assertSame(result,slot.take("episode:720")); slot.clear(); assertFalse(result.closed)
        assertNull(slot.take("episode:720")); result.close()
    }
    @Test fun qualityMismatchAndExpiredResultsCloseInsteadOfPlaying() {
        var now=1L; val slot=PreparedSlot<Resource>({now},100)
        val wrong=Resource(); slot.complete(slot.begin("1:720"),"1:720",wrong)
        assertNull(slot.take("1:1080")); assertTrue(wrong.closed)
        val expired=Resource(); slot.complete(slot.begin("2:1080"),"2:1080",expired); now=102
        assertNull(slot.take("2:1080")); assertTrue(expired.closed)
    }
    @Test fun staleCompletionCannotReplaceNewEpisodeOrRepopulateAfterExit() {
        val slot=PreparedSlot<Resource>({1L}); val first=slot.begin("1"); val second=slot.begin("2")
        val stale=Resource(); assertFalse(slot.complete(first,"1",stale)); assertTrue(stale.closed)
        val latest=Resource(); assertTrue(slot.complete(second,"2",latest)); slot.clear(); assertTrue(latest.closed)
        val late=Resource(); assertFalse(slot.complete(second,"2",late)); assertTrue(late.closed)
    }
    @Test fun cacheIsBoundedAndRejectsCorruptionClockRollbackAndStaleData() {
        val items=(1..40).map { Series(it.toString(),"剧 $it",description="介绍".repeat(1000)) }
        val value=CatalogSnapshot.encode(items,true,1000)
        val page=CatalogSnapshot.decode(value,1100)!!
        assertEquals(30,page.items.size); assertTrue(page.hasMore); assertEquals(1500,page.items.first().description.length)
        assertNull(CatalogSnapshot.decode(value,999)); assertNull(CatalogSnapshot.decode(value,1000+8*24*60*60*1000L))
        assertNull(CatalogSnapshot.decode("{bad",1100)); assertNull(CatalogSnapshot.decode("x".repeat(200001),1100))
    }
    @Test fun oldBackupStillImportsAndNewQuickActionListsMergeWithinLimits() {
        val base=BackupData(listOf(Series("1","收藏")),emptyList(),emptySet(),emptyList(),BackupSettings())
        val old=JSONObject(LibraryBackup.encode(base)).apply { remove("later"); remove("hidden") }.toString()
        assertTrue(LibraryBackup.decode(old).later.isEmpty())
        val extra=base.copy(later=listOf(Series("2","稍后看")),hidden=setOf("3"))
        val imported=LibraryBackup.decode(LibraryBackup.encode(extra))
        assertEquals(extra,imported)
        val merged=LibraryBackup.merge(base.copy(later=listOf(Series("4","本机"))),imported,false)
        assertEquals(listOf("4","2"),merged.later.map { it.id }); assertEquals(setOf("3"),merged.hidden)
        val invalid=JSONObject(LibraryBackup.encode(extra)).put("hidden",org.json.JSONArray(listOf("bad")))
        assertThrows(IllegalArgumentException::class.java) { LibraryBackup.decode(invalid.toString()) }
    }
    @Test fun cachedVideoPrefixServesRepeatReadsAndFetchesOnlyMissingTail() {
        fun box(type: String,body: ByteArray)=be32(body.size+8)+type.toByteArray()+body
        val head=box("ftyp","isom0000".toByteArray())+box("moov",byteArrayOf())
        val data=head+ByteArray(2*1024*1024-head.size) { (it%239).toByte() }
        val ranges=java.util.Collections.synchronizedList(mutableListOf<String>())
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/video") { exchange ->
            val range=exchange.requestHeaders.getFirst("Range"); ranges+=range
            val (start,requested)=range.removePrefix("bytes=").split('-').map(String::toInt)
            val end=minOf(data.lastIndex,requested); val bytes=data.copyOfRange(start,end+1)
            exchange.responseHeaders.add("Content-Range","bytes $start-$end/${data.size}")
            exchange.sendResponseHeaders(206,bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val client=OkHttpClient()
        try {
            val remote=RemoteVideo(client,StreamInfo("http://127.0.0.1:${server.address.port}/video",null,"720P")).warm()
            assertEquals(listOf("bytes=0-262143","bytes=262144-1048575"),ranges.toList())
            assertArrayEquals(data.copyOfRange(11,700011),remote.read(11,700000)); assertEquals(2,ranges.size)
            assertArrayEquals(data.copyOfRange(1000000,1200000),remote.read(1000000,200000))
            assertEquals("bytes=1048576-1199999",ranges.last())
            remote.close(); assertThrows(java.io.IOException::class.java) { remote.read(0,1) }
        } finally { server.stop(0); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    }
}
