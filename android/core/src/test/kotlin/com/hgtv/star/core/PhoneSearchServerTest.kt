package com.hgtv.star.core

import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PhoneSearchServerTest {
    private fun request(server: PhoneSearchServer,method: String="GET",path: String?=null,body: String="",headers: String="",host: String?=null): String {
        val uri=URI(server.url)
        return Socket("127.0.0.1",uri.port).use { socket ->
            socket.soTimeout=3000
            val bytes=body.toByteArray(Charsets.UTF_8)
            socket.getOutputStream().write(("$method ${path ?: uri.path} HTTP/1.1\r\nHost: ${host ?: uri.authority}\r\n"+headers+if(method=="POST") "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: ${bytes.size}\r\n\r\n" else "\r\n").toByteArray()+bytes)
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }
    @Test fun `phone form accepts one unicode search without reflecting it in HTML`() {
        val received=CountDownLatch(1); var query=""
        PhoneSearchServer("127.0.0.1",{ query=it;received.countDown() }).use { server ->
            val page=request(server)
            assertTrue(page.startsWith("HTTP/1.1 200")); assertTrue(page.contains("Cache-Control: no-store")); assertTrue(page.contains("form-action 'self'"))
            val value="测试 <script>alert(1)</script>"
            val reply=request(server,"POST",URI(server.url).path+"/submit","query="+URLEncoder.encode(value,"UTF-8"))
            assertTrue(reply.startsWith("HTTP/1.1 200")); assertFalse(reply.contains(value))
            assertTrue(received.await(2,TimeUnit.SECONDS)); assertEquals(value,query)
            assertTrue(runCatching { request(server) }.isFailure)
        }
    }
    @Test fun `wrong token host and cross site posts cannot submit`() {
        var submitted=false
        PhoneSearchServer("127.0.0.1",{ submitted=true }).use { server ->
            assertTrue(request(server,path="/wrong").startsWith("HTTP/1.1 404"))
            assertTrue(request(server,host="evil.example").startsWith("HTTP/1.1 404"))
            assertTrue(request(server,"POST",URI(server.url).path+"/submit","query=test","Origin: http://evil.example\r\n").startsWith("HTTP/1.1 403"))
            assertFalse(submitted)
        }
    }
    /**
     * `Referrer-Policy: no-referrer` 会让浏览器把表单提交的 `Origin` 写成 `null`。
     *
     * 这一条是**手机上「发送后没有任何反应」的真正原因**：页面响应头里带 no-referrer，
     * Chrome / Safari / 微信内置浏览器提交表单时一律发 `Origin: null`，而服务端原先
     * 只认 `http://<ip>:<port>`，于是回了 403「来源不匹配」，电视那边永远收不到回调。
     * 一次性 token + Host 校验已经足够，因此 `null` 必须放行。
     */
    @Test fun `form post with a null origin still submits`() {
        val received=CountDownLatch(1); var query=""
        PhoneSearchServer("127.0.0.1",{ query=it;received.countDown() }).use { server ->
            val reply=request(server,"POST",URI(server.url).path+"/submit","query=%E9%81%AE%E5%A4%A9","Origin: null\r\n")
            assertTrue(reply,reply.startsWith("HTTP/1.1 200"))
            assertTrue(received.await(2,TimeUnit.SECONDS)); assertEquals("遮天",query)
        }
    }

    /** 手机浏览器的 UA / Sec-CH-UA 系列很长：头部总量过 4 KB 也要能收下。 */
    @Test fun `long browser headers are accepted`() {
        val received=CountDownLatch(1)
        PhoneSearchServer("127.0.0.1",{ received.countDown() }).use { server ->
            val ua="User-Agent: Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "+"x".repeat(2500)+"\r\n"
            val ch="sec-ch-ua: "+"\"Not(A:Brand\";v=\"24\", ".repeat(40)+"\r\n"
            val reply=request(server,"POST",URI(server.url).path+"/submit","query=abc",ua+ch)
            assertTrue(reply,reply.startsWith("HTTP/1.1 200"))
            assertTrue(received.await(2,TimeUnit.SECONDS))
        }
    }

    @Test fun `invalid and oversized input does not consume the session`() {
        PhoneSearchServer("127.0.0.1",{}).use { server ->
            for(body in listOf("query=","query="+"x".repeat(81),"query=a&query=b","query=hello%0Aworld","query=%zz")) {
                val response=request(server,"POST",URI(server.url).path+"/submit",body)
                assertTrue(response,response.startsWith("HTTP/1.1 400"))
            }
            assertTrue(request(server,"POST",URI(server.url).path+"/submit","query="+"x".repeat(4096)).startsWith("HTTP/1.1 413"))
            assertTrue(request(server).startsWith("HTTP/1.1 200"))
        }
    }
    @Test fun `closing or expiry makes a session unreachable`() {
        val closed=PhoneSearchServer("127.0.0.1",{}); closed.close()
        assertTrue(runCatching { request(closed) }.isFailure)
        val expired=CountDownLatch(1)
        PhoneSearchServer("127.0.0.1",{ fail("expired session submitted") },{ expired.countDown() },1).use { server ->
            assertTrue(expired.await(2,TimeUnit.SECONDS))
            assertTrue(runCatching { request(server) }.isFailure)
        }
    }
    /**
     * 手机页是**竖屏新拟态**那一套：带 viewport-fit、有卡片与凹陷输入框、
     * 主按钮是 `primary`，并且不含任何外部资源（CSP 只放行内联样式）。
     *
     * 钉住这几条是因为四个扫码页共用同一份外壳（[PhonePage]）：
     * 谁把它改回「一坨大字 + 通栏按钮」，这里会先失败。
     */
    @Test fun `phone form uses the shared portrait neumorphic shell`() {
        PhoneSearchServer("127.0.0.1",{}).use { server ->
            val page=request(server)
            assertTrue(page.contains("viewport-fit=cover"))
            // 新拟态的四个阴影档位是这套样式的骨架（凸起 / 凹陷各两档）。
            assertTrue(page.contains("--out:"))
            assertTrue(page.contains("--in-sm:"))
            assertTrue(page.contains("class=\"card\""))
            assertTrue(page.contains("btn-primary"))
            assertFalse("不能引用外部资源",page.contains("http://") || page.contains("https://"))
        }
    }

    /** 登录态表单复用同一个一次性页面：字段名换成 token，长口令可以提交，搜索字段名不再成立。 */
    @Test fun `session token form takes a long token and ignores the search field name`() {
        val received=CountDownLatch(1); var saved=""
        PhoneSearchServer("127.0.0.1",{ saved=it;received.countDown() },form=PhoneSearchServer.Form.sessionToken).use { server ->
            val page=request(server)
            assertTrue(page.startsWith("HTTP/1.1 200")); assertTrue(page.contains("name=\"token\""))
            assertTrue(request(server,"POST",URI(server.url).path+"/submit","query=abc").startsWith("HTTP/1.1 400"))
            val value="x-tt-token="+"t".repeat(600)
            assertTrue(request(server,"POST",URI(server.url).path+"/submit","token="+URLEncoder.encode(value,"UTF-8")).startsWith("HTTP/1.1 200"))
            assertTrue(received.await(2,TimeUnit.SECONDS)); assertEquals(value,saved)
        }
    }
}
