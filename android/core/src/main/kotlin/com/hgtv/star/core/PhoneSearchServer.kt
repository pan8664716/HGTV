// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A one-use, short-lived LAN form. The page itself carries no cookies, no external resources and
 * nothing stored server-side; it only receives what the operator types on the phone and hands the
 * value to [onQuery].
 *
 * 同一份实现服务两个表单：[Form.search]（手机输入剧名）与 [Form.sessionToken]（粘贴搜索登录态）。
 */
class PhoneSearchServer(
    address: String,
    private val onQuery: (String)->Unit,
    private val onExpired: ()->Unit = {},
    ttlMillis: Long = 300_000,
    private val form: Form = Form.search
): Closeable {
    /** 表单文案与校验：[field] 是表单字段名，[maxLength] 是字符上限，[maxBytes] 是请求体上限。 */
    class Form(val title: String,val prompt: String,val field: String,val placeholder: String,val submit: String,val done: String,val maxLength: Int,val maxBytes: Int) {
        companion object {
            val search=Form("在电视上搜索","输入剧名，电视会按当前选择的漫剧或短剧分类搜索。","query","输入剧名或关键词","发送到电视","已发送到电视",80,4096)
            val sessionToken=Form("填写搜索登录态","粘贴 App 抓到的 x-tt-token（整段 Cookie 也可以）。电视只把它用在搜索请求上，不联网核对、不上传。","token","粘贴 x-tt-token","保存到电视","已保存到电视",4096,8192)
        }
    }
    private val stopped=AtomicBoolean(false)
    private val consumed=AtomicBoolean(false)
    private val localAddress=InetAddress.getByName(address).also { require(it.isLoopbackAddress || it.isSiteLocalAddress) }.hostAddress
    private val listener=ServerSocket(0,4).apply { soTimeout=500 }
    private val deadline=System.nanoTime()+ttlMillis.coerceIn(1,300_000)*1_000_000
    private val token=ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
    private val host="$localAddress:${listener.localPort}"
    val url="http://$host/$token"
    private val thread=Thread({ serve() },"hgtv-phone-input").apply { isDaemon=true }
    init {
        thread.start()
    }
    override fun close() { stopped.set(true); runCatching { listener.close() } }
    private fun serve() {
        try {
            while(!stopped.get() && System.nanoTime()<deadline) {
                val client=try { listener.accept() } catch(_: SocketTimeoutException) { continue }
                client.use { socket ->
                    socket.soTimeout=2000
                    try { handle(socket) } catch(_: Exception) { /* Discard malformed or disconnected requests. */ }
                }
            }
        } catch(_: Exception) { /* Closing the dialog interrupts accept(). */ }
        finally {
            val expired=!stopped.get() && !consumed.get()
            close()
            if(expired) onExpired()
        }
    }
    private fun line(input: InputStream, max: Int): String {
        val out=ByteArrayOutputStream()
        while(out.size()<max) {
            val next=input.read()
            if(next<0) throw java.io.EOFException()
            if(next==10) return out.toString("US-ASCII").trimEnd('\r')
            out.write(next)
        }
        throw java.io.IOException("Header too large")
    }
    private fun handle(socket: Socket) {
        if(!socket.inetAddress.let { it.isLoopbackAddress || it.isSiteLocalAddress }) return
        val input=socket.getInputStream().buffered()
        val request=line(input,1024).split(' ')
        if(request.size!=3) return
        val headers=mutableMapOf<String,String>()
        var bytes=0
        while(true) {
            // 单个头上限放到 8 KB、总量放到 16 KB：手机浏览器（尤其微信 / QQ 内置）的
            // User-Agent 与 Sec-CH-UA 系列加起来很容易过 4 KB，早先按 4 KB / 8 KB 卡，
            // 请求会被直接丢掉——手机端表现为「发送后没有任何反应」，电视端也收不到回调。
            val next=line(input,8192); bytes+=next.length+2
            if(bytes>16384) return respond(socket,431,"请求过大")
            if(next.isEmpty()) break
            val at=next.indexOf(':'); if(at<=0) return respond(socket,400,"请求格式错误")
            val key=next.substring(0,at).lowercase(Locale.ROOT)
            if(headers.put(key,next.substring(at+1).trim())!=null) return respond(socket,400,"重复请求头")
        }
        if(stopped.get() || consumed.get() || System.nanoTime()>=deadline) return respond(socket,410,"二维码已失效，请在电视上重新打开手机输入")
        if(headers["host"]!=host || request[1] !in listOf("/$token","/$token/submit")) return respond(socket,404,"页面不存在")
        if(request[0]=="GET" && request[1]=="/$token") return respond(socket,200,page())
        if(request[0]!="POST" || request[1]!="/$token/submit") return respond(socket,405,"不支持的操作")
        // 来源校验：同源表单提交本来就带 `Origin: http://<ip>:<port>`；但部分手机浏览器 /
        // 内置 WebView 会把它写成 `null`（隐私模式、从其它 App 跳转过来）。那一种同样放行——
        // 真正挡住跨站提交的是下面的一次性 token 与 Host 校验，Origin 只是多一道。
        val origin=headers["origin"]
        if(origin!=null && origin!="null" && origin!="http://$host") return respond(socket,403,"来源不匹配")
        // Content-Type 比对放宽到「大小写不敏感 + 去掉 charset 参数」：不同手机浏览器写出来的
        // 形式不一样（`application/x-www-form-urlencoded;charset=UTF-8`、带空格的都有）。
        val type=headers["content-type"]?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        if(headers.containsKey("transfer-encoding") || type!="application/x-www-form-urlencoded") return respond(socket,400,"请求格式错误")
        val length=headers["content-length"]?.toIntOrNull()?.takeIf { it in 1..form.maxBytes } ?: return respond(socket,413,"输入内容过长")
        val body=ByteArray(length); var offset=0
        while(offset<body.size) { val n=input.read(body,offset,body.size-offset); if(n<0) return; offset+=n }
        val fields=String(body,Charsets.UTF_8).split('&').map { it.split('=',limit=2) }
        if(fields.size!=1 || fields[0].size!=2 || fields[0][0]!=form.field) return respond(socket,400,"请填写内容后再提交")
        val query=runCatching { URLDecoder.decode(fields[0][1],"UTF-8").trim() }.getOrNull()
        if(query.isNullOrBlank() || query.length>form.maxLength || query.any { it.isISOControl() }) return respond(socket,400,"请输入 1—${form.maxLength} 个字符")
        if(stopped.get() || System.nanoTime()>=deadline || !consumed.compareAndSet(false,true)) return respond(socket,410,"二维码已失效")
        try { respond(socket,200,PhonePage.done(form.done)) }
        finally { close(); onQuery(query) }
    }
    /**
     * 表单页。
     *
     * 版式照手机竖屏定：一张凸起卡片里放一行说明 + 一枚凹陷输入框 + 一枚主按钮，
     * 顶部一枚胶囊标签交代「这是在往电视送什么」。表单本身不带任何回显，
     * 用户输入的内容不会出现在响应里（测试钉住了这一条）。
     */
    private fun page(): String {
        val hint = when (form.field) {
            "token" -> "整段 Cookie 也可以直接粘进来。"
            else -> "发送后电视立刻开始搜索。"
        }
        return PhonePage.shell(form.title,
            """<span class="pill">手机输入</span>""" +
            """<h1>${form.title}</h1>""" +
            """<p class="lead">${form.prompt}</p>""" +
            """<div class="card">""" +
            """<form method="post" action="/$token/submit">""" +
            """<input name="${form.field}" maxlength="${form.maxLength}" required autofocus """ +
            """placeholder="${form.placeholder}" autocomplete="off" autocapitalize="off" spellcheck="false">""" +
            """<button class="btn-primary" type="submit">${form.submit}</button>""" +
            """</form>""" +
            """<p class="hint">$hint</p>""" +
            """</div>""",
            footer = "仅本次端口打开期间有效，最长 5 分钟；内容只保存在电视本机。")
    }
    private fun respond(socket: Socket, code: Int, message: String) {
        // 4xx / 5xx 走统一的错误页；2xx 的 message 已经是一整页 HTML（见 [page] / [PhonePage.done]）。
        val html = if (code in 200..299) message else PhonePage.failed(form.title, message, "回到电视重新打开手机输入即可。")
        val body=html.toByteArray(Charsets.UTF_8)
        val header="HTTP/1.1 $code Response\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'\r\n\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(body); flush() }
    }
}
