// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/**
 * 一次性局域网页面：把 **APK 从手机送到电视**。
 *
 * 这是「电视自己下不动 / 装不上」时最可靠的一条路：
 * 手机浏览器连 GitHub 与镜像都比电视稳（电视上的旧 WebView 常常连不上现代 TLS），
 * 下载完直接把这个页面打开、选文件、上传，电视本地就有包了，不需要电视联网。
 *
 * 与 [AccountImportServer] / [LibraryTransferServer] 同一套约束：随机 128 位地址、
 * 只监听局域网地址、校验 Host、拦下能明确指认的第三方来源、限制体积与总时长、
 * 一次性消费、用完即关。
 *
 * 传输方式是**原始请求体**而不是 `multipart/form-data`：页面里用 `XMLHttpRequest`
 * 把 `File` 直接 POST 过来，服务端按 `Content-Length` 边收边写盘。
 * 这样 4~80 MB 的包全程不经过内存，也不会在 multipart 的边界解析上出错。
 */
class UpdateUploadServer(
    address: String,
    private val destination: File,
    private val onReady: (File) -> Unit,
    private val onExpired: () -> Unit = {},
    ttlMillis: Long = 900_000L
) : Closeable {
    private val stopped = AtomicBoolean(false)
    private val consumed = AtomicBoolean(false)
    private val local = InetAddress.getByName(address).also { require(it.isLoopbackAddress || it.isSiteLocalAddress) }
    private val listener = ServerSocket(0, 4, local).apply { soTimeout = 250 }
    private val deadline = System.nanoTime() + ttlMillis.coerceIn(1, 900_000L) * 1_000_000
    private val token = ByteArray(16).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private val host = "${local.hostAddress}:${listener.localPort}"

    /** 手机扫码打开的就是这个地址。 */
    val url = "http://$host/$token"

    init { Thread({ serve() }, "hgtv-update-upload").apply { isDaemon = true; start() } }

    override fun close() {
        stopped.set(true)
        runCatching { listener.close() }
    }

    private fun serve() {
        try {
            while (!stopped.get() && System.nanoTime() < deadline) {
                val socket = try { listener.accept() } catch (_: SocketTimeoutException) { continue }
                socket.use { it.soTimeout = 5000; if (!stopped.get()) runCatching { handle(it) } }
            }
        } catch (_: Exception) {
            // 关闭弹窗会打断 accept。
        } finally {
            val expired = !stopped.get() && !consumed.get()
            close()
            if (expired) onExpired()
        }
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
            val next = line(input, 8192)
            size += next.length + 2
            if (size > 16384) return reject(socket, input, headers, 431, "请求过大")
            if (next.isEmpty()) break
            val at = next.indexOf(':')
            if (at <= 0) return reject(socket, input, headers, 400, "请求格式错误")
            if (headers.put(next.substring(0, at).lowercase(Locale.ROOT), next.substring(at + 1).trim()) != null) {
                return reject(socket, input, headers, 400, "重复请求头")
            }
        }
        if (stopped.get() || consumed.get() || System.nanoTime() >= deadline) {
            return reject(socket, input, headers, 410, "页面已失效，请在电视上重新打开")
        }
        val path = request[1]
        if (headers["host"] != host || !path.startsWith("/$token")) {
            return reject(socket, input, headers, 404, "页面不存在")
        }
        // 来源校验与 [AccountImportServer] 同一条规则：只拦能明确指认的第三方站点，
        // 微信 / QQ 内置浏览器把 `Origin` 变成 `null` 时放行。
        val origin = headers["origin"]
        if (origin != null && origin != "null" && origin != "http://$host" &&
            (origin.startsWith("http://") || origin.startsWith("https://"))
        ) {
            return reject(socket, input, headers, 403, "来源不匹配")
        }
        // 路径认识、方法不对才是 405；子路径不认识一律 404。
        val known = path == "/$token" || path == "/$token/apk"
        when {
            request[0] == "GET" && path == "/$token" -> respond(socket, 200, page())
            request[0] == "POST" && path == "/$token/apk" -> receive(socket, input, headers)
            known -> reject(socket, input, headers, 405, "不支持的操作")
            else -> reject(socket, input, headers, 404, "页面不存在")
        }
    }

    /**
     * 先回一句错、再把请求体读掉丢弃。
     *
     * 顺序不能反：拒绝一个带 `Content-Length` 的 POST 时如果不去读请求体，
     * 服务端一关连接，内核就对这个连接发 RST，**客户端可能连那句错误都收不到**
     * （手机上传页显示成「网络中断」而不是真正的原因）。先写响应再排空，两边都能拿到话。
     */
    private fun reject(socket: Socket, input: InputStream, headers: Map<String, String>, code: Int, message: String) {
        respond(socket, code, message)
        val length = headers["content-length"]?.toLongOrNull() ?: return
        // 声明超过上限的那种请求（413）不去排空：让浏览器自己中断，比在这儿读 80 MB 快。
        if (length <= 0 || length > MAX_BYTES) return
        val until = System.nanoTime() + 3_000_000_000L
        var left = length
        val buffer = ByteArray(16384)
        while (left > 0 && System.nanoTime() < until) {
            val count = runCatching { input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt()) }.getOrNull() ?: return
            if (count < 0) return
            left -= count
        }
    }

    /** 按 `Content-Length` 把请求体流式写入 [destination]，随后校验它确实是一个 APK。 */
    private fun receive(socket: Socket, input: InputStream, headers: Map<String, String>) {
        if (headers.containsKey("transfer-encoding")) return reject(socket, input, headers, 400, "请求格式错误")
        val length = headers["content-length"]?.toLongOrNull()?.takeIf { it in 1..MAX_BYTES }
            ?: return reject(socket, input, headers, 413, "安装包不能超过 ${MAX_BYTES / 1048576} MB")
        val partial = File(destination.parentFile, destination.name + ".part")
        destination.parentFile?.mkdirs()
        val uploadDeadline = minOf(deadline, System.nanoTime() + 180_000_000_000L)
        try {
            partial.outputStream().use { output ->
                val buffer = ByteArray(65536)
                var total = 0L
                while (total < length) {
                    if (stopped.get() || System.nanoTime() >= uploadDeadline) {
                        return reject(socket, input, headers, 408, "上传超时，请重试")
                    }
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), length - total).toInt())
                    if (count < 0) return respond(socket, 400, "上传中断，请重试")
                    output.write(buffer, 0, count)
                    total += count
                }
                output.fd.sync()
            }
            if (!looksLikeApk(partial)) {
                partial.delete()
                // 请求体已经完整读完了：这里不能再走 [reject] 的排空（那会白等 3 秒）。
                return respond(socket, 400, "这不是一个安装包（APK），请重新选择文件")
            }
            if (!partial.renameTo(destination)) {
                partial.delete()
                return respond(socket, 500, "无法保存安装包，请检查电视存储空间")
            }
            if (!consumed.compareAndSet(false, true)) {
                destination.delete()
                return respond(socket, 410, "页面已失效")
            }
            respond(socket, 200, "已传到电视")
            close()
            onReady(destination)
        } finally {
            partial.delete()
        }
    }

    /**
     * 粗判「是不是一个 APK」：能当 zip 打开，且里面有 `AndroidManifest.xml` 与 `classes*.dex`。
     *
     * 更细的判据（包名 / 版本号 / 签名）在电视那边用系统接口做（见 AppUpdater 的体检），
     * 这里只挡住「用户选错文件 / 上传到一半被截断」这两类明显错误——
     * 截断的 zip 在 `ZipFile` 里读目录时就会抛异常。
     */
    private fun looksLikeApk(file: File): Boolean = runCatching {
        if (file.length() < MIN_BYTES) return false
        ZipFile(file).use { zip ->
            var manifest = false
            var dex = false
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val name = entries.nextElement().name
                if (name == "AndroidManifest.xml") manifest = true
                if (!dex && name.startsWith("classes") && name.endsWith(".dex")) dex = true
                if (manifest && dex) return true
            }
            manifest && dex
        }
    }.getOrDefault(false)

    private fun page() = PhonePage.shell(
        "传安装包",
        """<span class="pill">传安装包</span>""" +
            """<h1>把安装包传到电视</h1>""" +
            """<p class="lead">在这台手机上下好红果TV的新版 APK，再选中它上传。电视不需要联网。</p>""" +
            """<div class="card">""" +
            """<label class="picker"><input id="apk" type="file" """ +
            """accept=".apk,application/vnd.android.package-archive">""" +
            """<span class="picker-tag">选择</span>""" +
            """<span class="picker-name" id="picked">未选择文件</span></label>""" +
            """<button class="btn-primary" id="send" type="button" disabled>上传到电视</button>""" +
            // 进度条不依赖 PhonePage 的样式：一条内联的空槽，宽度由脚本改。
            // 共用样式表里没有进度条（其它三个页面都是一次性提交），在这里内联最省事。
            """<div style="height:6px;border-radius:99px;background:#1f242e;""" +
            """box-shadow:inset 3px 3px 7px #171b22,inset -3px -3px 7px #313949;margin:12px 0 4px">""" +
            """<i id="bar" style="display:block;height:6px;width:0;border-radius:99px;""" +
            """background:#ff7a5c;transition:width .2s"></i></div>""" +
            """</div>""" +
            """<p id="status" class="hint">还没有选择文件。</p>""",
        footer = "只在这个页面打开期间有效，最长 15 分钟；安装包只保存在电视本机。",
        script = """<script nonce="$token">""" +
            """var input=document.getElementById('apk'),send=document.getElementById('send'),""" +
            """bar=document.getElementById('bar'),status=document.getElementById('status');""" +
            """input.onchange=function(){var f=this.files[0];""" +
            """document.getElementById('picked').textContent=f?f.name+' · '+(f.size/1048576).toFixed(1)+' MB':'未选择文件';""" +
            """send.disabled=!f;status.textContent=f?'可以点「上传到电视」了。':'还没有选择文件。'};""" +
            """send.onclick=function(){var f=input.files[0];if(!f)return;""" +
            """send.disabled=true;var x=new XMLHttpRequest();""" +
            """x.open('POST','/$token/apk',true);""" +
            """x.upload.onprogress=function(e){if(!e.lengthComputable)return;""" +
            """var p=Math.round(e.loaded*100/e.total);bar.style.width=p+'%';""" +
            """status.textContent='正在上传 '+p+'%（请不要退出本页）'};""" +
            """x.onload=function(){if(x.status===200){bar.style.width='100%';""" +
            """status.textContent='已传到电视，请回到电视按「安装」。'}""" +
            """else{send.disabled=false;status.textContent='上传失败：'+x.responseText}};""" +
            """x.onerror=function(){send.disabled=false;status.textContent='网络中断，请重试。'};""" +
            """x.send(f)};""" +
            """</script>"""
    )

    private fun respond(socket: Socket, code: Int, message: String) {
        val html = when {
            message.contains("<html") -> message
            code in 200..299 -> PhonePage.done(message)
            else -> PhonePage.failed("传安装包", message, "回到电视重新打开这个页面即可。")
        }
        val bytes = html.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code Response\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\n" +
            "Referrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\n\r\n"
        socket.getOutputStream().apply {
            write(header.toByteArray(Charsets.US_ASCII))
            write(bytes)
            flush()
        }
    }

    companion object {
        /** 上传上限：与 [AppUpdate.MAX_APK_BYTES] 同一档（80 MB）。 */
        const val MAX_BYTES = 80L * 1024 * 1024
        /** 小于这个尺寸一定不是安装包：挡住「选了一张图片 / 一份文档」。 */
        private const val MIN_BYTES = 64L * 1024
    }
}
