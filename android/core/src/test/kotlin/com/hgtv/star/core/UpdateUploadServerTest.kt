package com.hgtv.star.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 手机 → 电视上传安装包的传输层。
 *
 * 只测传输与校验这一段（真机上还要过系统的 `getPackageArchiveInfo` 与安装器）：
 * 页面能打开、合法包能落盘并回调、坏包不消费会话、Host / 体积 / 来源的边界都拦住。
 */
class UpdateUploadServerTest {
    private fun tempDir(): File = File(System.getProperty("java.io.tmpdir"), "hgtv-upload-${System.nanoTime()}").apply { mkdirs() }

    /**
     * 造一个「像 APK」的 zip：必须过 64 KB 的下限，且带 AndroidManifest.xml 与 classes.dex。
     *
     * 填充必须**不可压缩**（随机字节）：全零的 70 KB 压缩后只剩 1 KB，
     * 落盘尺寸就过不了 64 KB 的下限——那不是被测代码的问题，是假包造得不像。
     */
    private fun fakeApk(padding: Int = 70 * 1024): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml")); zip.write(ByteArray(1024) { 7 }); zip.closeEntry()
            zip.putNextEntry(ZipEntry("classes.dex"))
            val random = ByteArray(padding).also { java.util.Random(7L).nextBytes(it) }
            zip.write(random)
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun post(server: UpdateUploadServer, body: ByteArray, path: String = "/apk", host: String? = null): String {
        val uri = URI(server.url)
        return Socket("127.0.0.1", uri.port).use { socket ->
            socket.soTimeout = 5000
            val head = "POST ${uri.path}$path HTTP/1.1\r\nHost: ${host ?: uri.authority}\r\n" +
                "Content-Type: application/vnd.android.package-archive\r\nContent-Length: ${body.size}\r\n\r\n"
            socket.getOutputStream().write(head.toByteArray())
            socket.getOutputStream().write(body)
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun get(server: UpdateUploadServer, path: String = "", host: String? = null): String {
        val uri = URI(server.url)
        return Socket("127.0.0.1", uri.port).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().write("GET ${uri.path}$path HTTP/1.1\r\nHost: ${host ?: uri.authority}\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    @Test fun `page opens and a valid apk lands then installs once`() {
        val dir = tempDir()
        val target = File(dir, "incoming.apk")
        val latch = CountDownLatch(1)
        var received: File? = null
        UpdateUploadServer("127.0.0.1", target, { file -> received = file; latch.countDown() }).use { server ->
            val page = get(server)
            assertTrue(page.startsWith("HTTP/1.1 200"))
            assertTrue(page.contains("把安装包传到电视"))
            assertTrue(page.contains("/apk"))          // 上传地址写在脚本里

            val apk = fakeApk()
            assertTrue(post(server, apk).startsWith("HTTP/1.1 200"))
            assertTrue("回调必须触发", latch.await(3, TimeUnit.SECONDS))
            assertEquals(target, received)
            assertEquals(apk.size.toLong(), target.length())
            assertFalse("不能留下 .part", File(dir, "incoming.apk.part").exists())
            // 一次性：装完这个页面就该失效（服务线程已经把 listener 关了）。
            assertTrue(runCatching { get(server) }.isFailure)
        }
    }

    @Test fun `a non zip body is rejected and does not consume the session`() {
        val dir = tempDir()
        val target = File(dir, "incoming.apk")
        var delivered = false
        UpdateUploadServer("127.0.0.1", target, { delivered = true }).use { server ->
            val junk = ByteArray(70 * 1024) { 0x41 }
            val response = post(server, junk)
            assertTrue(response.startsWith("HTTP/1.1 400"))
            assertTrue(response.contains("不是一个安装包"))
            assertFalse(delivered)
            assertFalse(target.exists())
            // 会话没被消费：页面仍然可用。
            assertTrue(get(server).startsWith("HTTP/1.1 200"))
        }
    }

    @Test fun `host token size and origin boundaries`() {
        val dir = tempDir()
        var delivered = false
        UpdateUploadServer("127.0.0.1", File(dir, "incoming.apk"), { delivered = true }).use { server ->
            assertTrue(get(server, host = "evil.example").startsWith("HTTP/1.1 404"))
            assertTrue(get(server, "/wrong").startsWith("HTTP/1.1 404"))
            assertTrue(post(server, fakeApk(), path = "/wrong").startsWith("HTTP/1.1 404"))
            // 超过 80 MB 的声明直接拒绝，不读请求体。
            val uri = URI(server.url)
            Socket("127.0.0.1", uri.port).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().write(
                    ("POST ${uri.path}/apk HTTP/1.1\r\nHost: ${uri.authority}\r\nContent-Length: " +
                        "${UpdateUploadServer.MAX_BYTES + 1}\r\n\r\n").toByteArray()
                )
                socket.getOutputStream().flush()
                val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
                assertTrue(response, response.startsWith("HTTP/1.1 413"))
            }
            assertFalse(delivered)
        }
    }
}
