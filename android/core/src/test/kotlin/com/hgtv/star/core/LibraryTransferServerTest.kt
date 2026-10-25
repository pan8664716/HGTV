package com.hgtv.star.core

import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LibraryTransferServerTest {
    private val data=BackupData(listOf(Series("1","测试")),emptyList(),emptySet(),emptyList(),BackupSettings())
    private val backup=LibraryBackup.encode(data)
    private fun request(server: LibraryTransferServer,path: String="",body: String?=null,headers: String="",host: String?=null): String {
        val uri=URI(server.url)
        return Socket("127.0.0.1",uri.port).use { socket ->
            socket.soTimeout=3000
            val bytes=body?.toByteArray(Charsets.UTF_8) ?: byteArrayOf()
            val head="${if(body==null) "GET" else "POST"} ${uri.path}$path HTTP/1.1\r\nHost: ${host ?: uri.authority}\r\n$headers"+(if(body==null) "\r\n" else "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n")
            socket.getOutputStream().write(head.toByteArray()+bytes)
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }
    @Test fun `download is one use and validated import is delivered once then listener closes`() {
        val latch=CountDownLatch(1); var received: BackupData?=null
        LibraryTransferServer("127.0.0.1",backup,{ received=it; latch.countDown() }).use { server ->
            assertTrue(request(server).contains("frame-ancestors 'none'"))
            val download=request(server,"/download")
            assertTrue(download.contains("Content-Disposition: attachment")); assertEquals(backup,download.substringAfter("\r\n\r\n"))
            assertTrue(request(server,"/download").startsWith("HTTP/1.1 410"))
            assertTrue(request(server,"/import",backup).startsWith("HTTP/1.1 200"))
            assertTrue(latch.await(2,TimeUnit.SECONDS)); assertEquals(data,received)
            assertTrue(runCatching { request(server) }.isFailure)
        }
    }
    @Test fun `invalid input cross origin host duplicate headers and oversized bodies do not consume session`() {
        var imported=false
        LibraryTransferServer("127.0.0.1",backup,{ imported=true }).use { server ->
            assertTrue(request(server,host="evil.example").startsWith("HTTP/1.1 404"))
            assertTrue(request(server,"/wrong").startsWith("HTTP/1.1 404"))
            assertTrue(request(server,"/import",backup,"Origin: http://evil.example\r\n").startsWith("HTTP/1.1 403"))
            // 能明确指认来源的第三方才拦；`Sec-Fetch-Site: cross-site` 单独出现不算
            // （扫码 App 的内置浏览器会把本机页面也标成 cross-site，见下面那条测试）。
            assertTrue(request(server,"/download",headers="Origin: https://evil.example\r\n").startsWith("HTTP/1.1 403"))
            assertTrue(request(server,"/import",backup,"Host: other\r\n").startsWith("HTTP/1.1 400"))
            assertTrue(request(server,"/import","{}").startsWith("HTTP/1.1 400"))
            assertTrue(request(server,"/import",backup,"Transfer-Encoding: chunked\r\n").startsWith("HTTP/1.1 400"))
            val uri=URI(server.url)
            Socket("127.0.0.1",uri.port).use { s ->
                s.soTimeout=2000; s.getOutputStream().write("POST ${uri.path}/import HTTP/1.1\r\nHost: ${uri.authority}\r\nContent-Type: application/json\r\nContent-Length: ${LibraryBackup.MAX_BYTES+1}\r\n\r\n".toByteArray())
                assertTrue(s.getInputStream().readBytes().toString(Charsets.UTF_8).startsWith("HTTP/1.1 413"))
            }
            assertFalse(imported); assertTrue(request(server,"/download").startsWith("HTTP/1.1 200"))
        }
    }
    /**
     * `Origin: null` 与 `Sec-Fetch-Site: cross-site` 都要放行。
     *
     * 页面自己带 `Referrer-Policy: no-referrer`，浏览器提交表单时因此把 `Origin` 写成 `null`；
     * 扫码 App 的内置浏览器（微信 / QQ 的 X5 内核）还会把 `Sec-Fetch-Site` 标成 `cross-site`。
     * 早先这两条都被当成「来源不匹配」直接 403，手机端读到的就是「来源不匹配」，
     * 电视那边永远收不到备份。
     */
    @Test fun `null origin and opaque cross site are accepted`() {
        LibraryTransferServer("127.0.0.1",backup,{}).use { server ->
            val reply=request(server,"/download",headers="Origin: null\r\nSec-Fetch-Site: cross-site\r\n")
            assertTrue(reply,reply.startsWith("HTTP/1.1 200"))
            assertTrue(reply.contains("Content-Disposition: attachment"))
        }
    }

    @Test fun `close interrupts an active incomplete upload and expiry closes idle sessions`() {
        val server=LibraryTransferServer("127.0.0.1",backup,{ fail("must not import") })
        Socket("127.0.0.1",URI(server.url).port).use { socket ->
            socket.soTimeout=1500; socket.getOutputStream().write("POST /".toByteArray())
            Thread.sleep(80); server.close()
            assertTrue(runCatching { socket.getInputStream().read() }.getOrDefault(-1)==-1)
        }
        val expired=CountDownLatch(1)
        LibraryTransferServer("127.0.0.1",backup,{}, { expired.countDown() },50).use { expiring ->
            assertTrue(expired.await(2,TimeUnit.SECONDS)); assertTrue(runCatching { request(expiring) }.isFailure)
        }
    }
}
