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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一次性局域网页面：把 App 的 `cookieStore.xml`（或整段 Cookie）从手机送到电视。
 *
 * 与 [LibraryTransferServer] 同一套约束：随机 128 位地址、只监听局域网地址、
 * 校验 Host、拦下能指认的第三方来源（认不出来源的放行，见 [handle] 里的说明）、
 * 限制请求体大小与总时长、用完即关。页面本身不带任何外部资源，
 * 也不在服务端存任何东西——收到的文本直接交给 [onImport]。
 *
 * 两种提交方式：
 * - **上传文件**：`multipart/form-data` 里的 `cookieStore.xml`（`<input type=file>`）；
 * - **粘贴文本**：`application/x-www-form-urlencoded` 的 `payload=…`。
 *
 * 两种都归一到「一段文本」再交给回调，由 [AccountCredentialsParser] 决定它是 XML 还是 Cookie 串。
 */
class AccountImportServer(address: String, private val onImport: (String)->Unit, private val onExpired: ()->Unit = {},
    ttlMillis: Long = 300_000) : Closeable {
    private val stopped = AtomicBoolean(false)
    private val consumed = AtomicBoolean(false)
    private val local = InetAddress.getByName(address).also { require(it.isLoopbackAddress || it.isSiteLocalAddress) }
    private val listener = ServerSocket(0, 4, local).apply { soTimeout = 250 }
    private val deadline = System.nanoTime() + ttlMillis.coerceIn(1, 300_000) * 1_000_000
    private val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
    private val host = "${local.hostAddress}:${listener.localPort}"
    /** 手机扫码打开的就是这个地址。 */
    val url = "http://$host/$token"
    init { Thread({ serve() }, "hgtv-account-import").apply { isDaemon = true; start() } }
    override fun close() { stopped.set(true); runCatching { listener.close() } }

    private fun serve() {
        try {
            while (!stopped.get() && System.nanoTime() < deadline) {
                val socket = try { listener.accept() } catch (_: SocketTimeoutException) { continue }
                socket.use { it.soTimeout = 3000; if (!stopped.get()) runCatching { handle(it) } }
            }
        } catch (_: Exception) { /* 关闭弹窗会打断 accept */ }
        finally { val expired = !stopped.get() && !consumed.get(); close(); if (expired) onExpired() }
    }

    private fun line(input: InputStream, max: Int): String {
        val out = ByteArrayOutputStream()
        while (out.size() < max) {
            val next = input.read()
            if (next < 0) throw java.io.EOFException()
            if (next == 10) return out.toString("US-ASCII").trimEnd('\r')
            out.write(next)
        }
        throw java.io.IOException("Header too large")
    }

    private fun handle(socket: Socket) {
        if (!socket.inetAddress.let { it.isLoopbackAddress || it.isSiteLocalAddress }) return
        val input = socket.getInputStream().buffered()
        val request = line(input, 1024).split(' ')
        if (request.size != 3) return
        val headers = mutableMapOf<String, String>()
        var size = 0
        while (true) {
            // 单个头 8 KB、总量 16 KB：手机浏览器（微信 / QQ 内置）的 UA 与 Sec-CH-UA 系列很长。
            val next = line(input, 8192); size += next.length + 2
            if (size > 16384) return respond(socket, 431, "请求过大")
            if (next.isEmpty()) break
            val at = next.indexOf(':'); if (at <= 0) return respond(socket, 400, "请求格式错误")
            if (headers.put(next.substring(0, at).lowercase(Locale.ROOT), next.substring(at + 1).trim()) != null) return respond(socket, 400, "重复请求头")
        }
        if (stopped.get() || consumed.get() || System.nanoTime() >= deadline) return respond(socket, 410, "二维码已失效，请在电视上重新打开")
        if (headers["host"] != host || request[1] != "/$token") return respond(socket, 404, "页面不存在")
        if (request[0] == "GET") return respond(socket, 200, page())
        if (request[0] != "POST") return respond(socket, 405, "不支持的操作")
        // 表单来源：手机浏览器打开的就是本机这个一次性页面，提交时 `Origin` 本来是
        // `http://<电视 IP>:<端口>`。但**扫码 App 的内置浏览器**（微信 / QQ / 支付宝那一套 X5 内核）
        // 会把页面套进自己的容器里再显示，提交时 `Origin` 变成字符串 `null`（不透明来源）、
        // `Sec-Fetch-Site` 也会被标成 `cross-site`——页面内容明明是电视发的，来源却指不出来，
        // 用户读到的就是「来源不匹配」，无论重开几次二维码都导不进来。
        // 因此这里只拦**能明确指认的第三方站点**（`http(s)://` 且不是本机）；
        // 认不出来源的（`null` / 干脆没有这个头）放行。真正的边界不在这条头校验上：
        // 端口是临时随机端口、地址是 128 位随机地址、15 分钟时限、一次性消费，
        // 而且只接受局域网来源地址（见 [handle] 开头那一句）。
        val origin = headers["origin"]
        if (origin != null && origin != "null" && origin != "http://$host" &&
            (origin.startsWith("http://") || origin.startsWith("https://"))) {
            return respond(socket, 403, "来源不匹配")
        }
        if (headers.containsKey("transfer-encoding")) return respond(socket, 400, "请求格式错误")
        val type = headers["content-type"]?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        if (type != "multipart/form-data" && type != "application/x-www-form-urlencoded") {
            return respond(socket, 400, "请上传 cookieStore.xml，或粘贴整段 Cookie")
        }
        val length = headers["content-length"]?.toIntOrNull()?.takeIf { it in 1..MAX_BYTES }
            ?: return respond(socket, 413, "内容不能超过 ${MAX_BYTES / 1024} KB")
        val body = ByteArray(length); var offset = 0
        val uploadDeadline = minOf(deadline, System.nanoTime() + 10_000_000_000)
        while (offset < length) {
            if (stopped.get() || System.nanoTime() >= uploadDeadline) return respond(socket, 408, "上传超时，请重试")
            val count = input.read(body, offset, minOf(8192, length - offset)); if (count < 0) return; offset += count
        }
        val text = if (type == "multipart/form-data") {
            multipart(body, headers["content-type"].orEmpty())
        } else {
            form(body)
        }
        if (text.isNullOrBlank()) return respond(socket, 400, "没读到内容，请重新选择文件或粘贴 Cookie")
        if (stopped.get() || System.nanoTime() >= deadline || !consumed.compareAndSet(false, true)) return respond(socket, 410, "二维码已失效")
        try { respond(socket, 200, "<h1>已导入到电视</h1><p>请回到电视继续。</p>") }
        finally { close(); onImport(text) }
    }

    /** 从 multipart 里取第一个文件 / 字段的文本内容。 */
    private fun multipart(body: ByteArray, contentType: String): String? {
        val boundary = Regex("""boundary=("?)([^";]+)\1""").find(contentType)?.groupValues?.get(2)?.trim() ?: return null
        val delimiter = ("--$boundary").toByteArray(Charsets.ISO_8859_1)
        val text = String(body, Charsets.ISO_8859_1)
        val parts = text.split(String(delimiter, Charsets.ISO_8859_1)).drop(1)
        parts.forEach { part ->
            if (part.startsWith("--")) return@forEach
            val split = part.indexOf("\r\n\r\n"); if (split < 0) return@forEach
            val head = part.substring(0, split)
            // 只认文件字段与 payload 字段，避免把别的表单值当成凭据。
            if (!head.contains("filename=") && !head.contains("name=\"payload\"")) return@forEach
            val value = part.substring(split + 4).removeSuffix("\r\n")
            if (value.isNotBlank()) return value
        }
        return null
    }

    private fun form(body: ByteArray): String? =
        String(body, Charsets.UTF_8).split('&').map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == "payload" }
            ?.let { runCatching { java.net.URLDecoder.decode(it[1], "UTF-8") }.getOrNull() }

    /**
     * 上传页：手机竖屏、一张凸起卡片里两个入口（选文件 / 粘 Cookie）。
     *
     * 与其它三个扫码页共用 [PhonePage] 的样式：输入框是凹陷槽、按钮分主次、
     * 顶部一枚胶囊标签交代这是往电视送什么。
     */
    private fun page() = PhonePage.shell("导入账号参数",
        """<span class="pill">导入参数</span>""" +
        """<h1>导入账号参数</h1>""" +
        """<p class="lead">上传 <code>cookieStore.xml</code>，或直接粘贴 Cookie。</p>""" +
        """<div class="card">""" +
        """<h2>上传文件</h2>""" +
        """<form method="post" enctype="multipart/form-data" action="/$token">""" +
        """<label class="picker"><input id="cookie" type="file" name="cookieStore" """ +
        """accept=".xml,text/xml,application/xml,application/json">""" +
        """<span class="picker-tag">选择</span>""" +
        """<span class="picker-name" id="picked">未选择文件</span></label>""" +
        """<button class="btn-primary" type="submit">上传到电视</button>""" +
        """</form>""" +
        """<p class="hint">可附带 <code>device_profile.json</code>，用于连片同步。</p>""" +
        """</div>""" +
        """<div class="card">""" +
        """<h2>粘贴 Cookie</h2>""" +
        """<form method="post" action="/$token">""" +
        """<textarea name="payload" placeholder="sessionid=…; uid_tt=…" autocapitalize="off" spellcheck="false"></textarea>""" +
        """<button class="btn-ghost" type="submit">提交 Cookie</button>""" +
        """</form>""" +
        """</div>""" +
        """<p id="status" class="hint">正在等待上传…</p>""",
        footer = "仅本次端口打开期间有效，最长 5 分钟；内容只保存在电视本机。",
        script = """<script nonce="$token">""" +
            """document.getElementById('cookie').onchange=function(){""" +
            """var f=this.files[0];document.getElementById('picked').textContent=f?f.name:'未选择文件'};""" +
            """document.querySelectorAll('form').forEach(function(f){f.onsubmit=function(){""" +
            """document.getElementById('status').textContent='正在导入…'}});""" +
            """</script>""")

    private fun respond(socket: Socket, code: Int, message: String) {
        // 2xx 的 message 可能是一整页（[page]），也可能是一句结果（成功 / 失败提示）；
        // 后者与所有 4xx / 5xx 一起套成统一的结果页。
        val html = when {
            message.contains("<html") -> message
            code in 200..299 -> PhonePage.done(message.removePrefix("<h1>").substringBefore("</h1>"))
            else -> PhonePage.failed("导入账号参数", message, "回到电视重新打开二维码即可。")
        }
        val bytes = html.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code Response\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-$token'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'\r\n\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
    }

    companion object {
        /** 上传上限：`cookieStore.xml` 实测约 180 KB，留一倍余量。 */
        const val MAX_BYTES = 512 * 1024
    }
}
