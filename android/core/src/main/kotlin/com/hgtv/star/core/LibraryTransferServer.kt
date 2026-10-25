// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean

/** Short-lived LAN transfer. Uploaded records are validated here, but only the TV may apply them. */
class LibraryTransferServer(address: String,private val backup: String,private val onImport: (BackupData)->Unit,
    private val onExpired: ()->Unit={},ttlMillis: Long=300_000): Closeable {
    private val stopped=AtomicBoolean(false)
    private val expiry=Timer("hgtv-transfer-expiry",true)
    private val local=InetAddress.getByName(address).also { require(it.isLoopbackAddress || it.isSiteLocalAddress) }
    private val listener=ServerSocket(0,4,local).apply { soTimeout=250 }
    @Volatile private var active: Socket?=null
    private val deadline=System.nanoTime()+ttlMillis.coerceIn(1,300_000)*1_000_000
    private val token=ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
    private val host="${local.hostAddress}:${listener.localPort}"
    val url="http://$host/$token"
    private var downloaded=false
    init {
        require(backup.toByteArray(Charsets.UTF_8).size<=LibraryBackup.MAX_BYTES)
        expiry.schedule(object: TimerTask() { override fun run()=expire() },ttlMillis.coerceIn(1,300_000))
        Thread({ serve() },"hgtv-library-transfer").apply { isDaemon=true; start() }
    }
    private fun expire() { if(stopped.compareAndSet(false,true)) { close(); onExpired() } }
    override fun close() { stopped.set(true); expiry.cancel(); runCatching { listener.close() }; runCatching { active?.close() } }
    private fun serve() {
        try {
            while(!stopped.get() && System.nanoTime()<deadline) {
                val socket=try { listener.accept() } catch(_: SocketTimeoutException) { continue }
                active=socket
                socket.use { it.soTimeout=2000; if(!stopped.get()) runCatching { handle(it) } }
                active=null
            }
        } catch(_: Exception) { /* Closing the TV dialog interrupts accept/read. */ }
        finally { if(!stopped.get()) expire() else close() }
    }
    private fun line(input: InputStream,max: Int): String {
        val out=ByteArrayOutputStream()
        while(out.size()<max) {
            val next=input.read(); if(next<0) throw java.io.EOFException()
            if(next==10) return out.toString("US-ASCII").trimEnd('\r')
            out.write(next)
        }
        throw java.io.IOException("Header too large")
    }
    private fun handle(socket: Socket) {
        if(!socket.inetAddress.let { it.isLoopbackAddress || it.isSiteLocalAddress }) return
        val input=socket.getInputStream().buffered(); val request=line(input,1024).split(' ')
        if(request.size!=3) return
        val headers=mutableMapOf<String,String>(); var size=0
        while(true) {
            // 单个头 8 KB、总量 16 KB：手机浏览器（微信 / QQ 内置）的 UA 与 Sec-CH-UA 系列很长。
            val next=line(input,8192); size+=next.length+2
            if(size>16384) return respond(socket,431,"请求过大")
            if(next.isEmpty()) break
            val at=next.indexOf(':'); if(at<=0) return respond(socket,400,"请求格式错误")
            if(headers.put(next.substring(0,at).lowercase(Locale.ROOT),next.substring(at+1).trim())!=null) return respond(socket,400,"重复请求头")
        }
        if(stopped.get() || System.nanoTime()>=deadline) return respond(socket,410,"二维码已过期")
        if(headers["host"]!=host || request[1] !in listOf("/$token","/$token/download","/$token/import")) return respond(socket,404,"页面不存在")
        // 来源校验与另外三个手机页同一套（见 AccountImportServer 里那段说明）：
        // 页面自己带 `Referrer-Policy: no-referrer`，浏览器提交表单时因此把 `Origin` 写成 `null`；
        // 扫码 App 的内置浏览器（微信 / QQ 的 X5 内核）也会这么写，并把 `Sec-Fetch-Site`
        // 标成 `cross-site`。这里只拦**能明确指认的第三方站点**，认不出来源的放行——
        // 真正的边界是随机端口 + 128 位 token + 一次性消费 + 只接受局域网来源地址。
        val origin=headers["origin"]
        if(origin!=null && origin!="null" && origin!="http://$host" &&
            (origin.startsWith("http://") || origin.startsWith("https://"))) return respond(socket,403,"来源不匹配")
        if(request[0]=="GET" && request[1]=="/$token") return respond(socket,200,page())
        if(request[0]=="GET" && request[1]=="/$token/download") {
            if(downloaded) return respond(socket,410,"本次备份已下载，请在电视上重新打开二维码")
            respond(socket,200,backup,"application/json; charset=utf-8","Content-Disposition: attachment; filename=hgtv-library.json\r\n")
            downloaded=true; return
        }
        if(request[0]!="POST" || request[1]!="/$token/import") return respond(socket,405,"不支持的操作")
        if(headers.containsKey("transfer-encoding") || headers["content-type"]?.substringBefore(';')!="application/json") return respond(socket,400,"请选择 JSON 备份文件")
        val length=headers["content-length"]?.toIntOrNull()?.takeIf { it in 1..LibraryBackup.MAX_BYTES } ?: return respond(socket,413,"备份不能超过 2 MB")
        val bytes=ByteArray(length); var offset=0
        // A total deadline also bounds slow clients that keep individual reads below the socket timeout.
        val uploadDeadline=minOf(deadline,System.nanoTime()+10_000_000_000)
        while(offset<length) {
            if(stopped.get() || System.nanoTime()>=uploadDeadline) return respond(socket,408,"上传超时，请重试")
            val count=input.read(bytes,offset,minOf(8192,length-offset)); if(count<0) return; offset+=count
        }
        val data=runCatching { LibraryBackup.decode(bytes.toString(Charsets.UTF_8)) }.getOrNull() ?: return respond(socket,400,"备份格式无效，请选择本应用导出的 JSON 文件")
        if(stopped.get() || System.nanoTime()>=deadline) return respond(socket,410,"二维码已过期")
        try { respond(socket,200,"已发送到电视，请用遥控器确认恢复。确认前不会更改记录。") }
        finally { close(); onImport(data) }
    }
    /**
     * 备份页：手机竖屏、两张凸起卡片（下载 / 恢复）。
     *
     * 与其它三个扫码页共用 [PhonePage] 的样式；这一页多一段 JS（读文件后 POST），
     * 因此 CSP 里的 `script-src` 只放行带 nonce 的这一段。
     */
    private fun page()=PhonePage.shell("备份与恢复",
        """<span class="pill">备份恢复</span>""" +
        """<h1>备份与恢复</h1>""" +
        """<p class="lead">收藏、观看位置、搜索记录与播放设置，只在手机与电视间传输。</p>""" +
        """<div class="card">""" +
        """<h2>下载备份</h2>""" +
        """<a class="btn btn-primary" href="/$token/download" download>下载备份文件</a>""" +
        """<p class="hint">每次打开仅可下载一次。</p>""" +
        """</div>""" +
        """<div class="card">""" +
        """<h2>恢复备份</h2>""" +
        """<label class="picker"><input id="file" type="file" accept=".json,application/json">""" +
        """<span class="picker-tag">选择</span>""" +
        """<span class="picker-name" id="picked">未选择文件</span></label>""" +
        """<button id="send" class="btn-primary" type="button">发送到电视确认</button>""" +
        """<p id="status" class="hint">最大 2 MB，需在电视上确认后合并。</p>""" +
        """</div>""",
        footer = "仅本次端口打开期间有效，最长 5 分钟；内容只保存在电视本机。",
        script = """<script nonce="$token">""" +
            """document.getElementById('file').onchange=function(){""" +
            """var f=this.files[0];document.getElementById('picked').textContent=f?f.name:'未选择文件'};""" +
            """document.getElementById('send').onclick=function(){""" +
            """var file=document.getElementById('file').files[0],status=document.getElementById('status'),button=this;""" +
            """if(!file||file.size>2097152){status.textContent='请选择不超过 2 MB 的备份文件';return}""" +
            """button.disabled=true;status.textContent='正在发送…';""" +
            """var reader=new FileReader();""" +
            """reader.onerror=function(){status.textContent='文件读取失败';button.disabled=false};""" +
            """reader.onload=function(){""" +
            """fetch('/$token/import',{method:'POST',headers:{'Content-Type':'application/json'},body:reader.result})""" +
            """.then(function(r){return r.text().then(function(t){status.textContent=r.ok?'已发送到电视，请用遥控器确认恢复。':t;if(!r.ok)button.disabled=false})})""" +
            """.catch(function(){status.textContent='连接已失效，请在电视上重新打开二维码';button.disabled=false})};""" +
            """reader.readAsText(file)};""" +
            """</script>""")

    private fun respond(socket: Socket,code: Int,message: String,type: String=if(message.startsWith("<!doctype")) "text/html; charset=utf-8" else "text/plain; charset=utf-8",extra: String="") {
        // 页面走 [page]；POST 的结果（成功 / 失败）都是一句纯文字，交给 fetch 显示在页内，
        // 因此这里保持 text/plain —— 只有整页 HTML 才套 [PhonePage]。
        val body=message.toByteArray(Charsets.UTF_8)
        val header="HTTP/1.1 $code Response\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-$token'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'\r\n$extra\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(body); flush() }
    }
}
