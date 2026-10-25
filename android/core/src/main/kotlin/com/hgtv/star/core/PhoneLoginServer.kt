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
 * 上行短信那一步在**这一页自己**的轮询预算：3 秒一次、五次（≈15 秒）。
 *
 * 参考实现的客户端是「3 秒 × 30 次」的 90 秒轮询，但那是它的桌面/电视端在做轮询；
 * 这一页是同步页面，用户的浏览器会一直转圈，因此只替用户等十几秒就把控制权还回去
 * （页面会原样保留「我已发送」按钮，再点一次继续等）。
 */
private const val UPSMS_POLLS=5
private const val UPSMS_POLL_MS=3000L

/**
 * 手机号登录的一次性局域网页面：**在手机上**输手机号、收验证码、填验证码。
 *
 * ## 为什么要在手机上做
 *
 * 手机号 + 4–8 位验证码，用遥控器在电视上敲要按几十下方向键；
 * 而项目里「手机输入 / 搜索登录态 / 备份恢复 / 导入账号参数」已经都走
 * 「电视出二维码 → 手机扫码打开一次性页面」这套（见 [PhoneSearchServer] / [AccountImportServer]），
 * 这里沿用同一套约束：随机地址、只监听局域网、限时、用完即关。
 *
 * ## 三步（都在这个页面上完成）
 *
 * ```
 * ① 填手机号 → 发验证码        走 [PhoneLogin.sendCode]
 * ② 填验证码 → 换会话          走 [PhoneLogin.login]
 * ③ 若平台要求上行短信：       显示平台给的号码与内容，用户发完点「我已发送」
 *    显示收件号码 + 短信内容   → 走 [PhoneLogin.verifyUpsms] → 再 login 一次
 * ```
 *
 * 登录成功时把凭据交给 [onLoggedIn]（电视侧据此保存并刷新「我的」页）。
 * 凭据**只在电视本机落盘**，页面上不回显任何凭证。
 */
private const val LOGIN_FOOTER = "仅本次端口打开期间有效，最长 10 分钟；凭据只保存在电视本机。"

class PhoneLoginServer(
    address: String,
    private val login: PhoneLogin,
    private val onLoggedIn: (AccountCredentials)->Unit,
    private val onExpired: ()->Unit = {},
    ttlMillis: Long = 600_000
) : Closeable {
    private val stopped=AtomicBoolean(false)
    private val consumed=AtomicBoolean(false)
    private val local=InetAddress.getByName(address).also { require(it.isLoopbackAddress || it.isSiteLocalAddress) }
    private val listener=ServerSocket(0,4,local).apply { soTimeout=250 }
    // 短信往返比「输剧名」慢得多，因此时限比其它一次性页面长（默认 10 分钟）。
    private val deadline=System.nanoTime()+ttlMillis.coerceIn(1,900_000)*1_000_000
    private val token=ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
    private val host="${local.hostAddress}:${listener.localPort}"
    /** 手机扫码打开的就是这个地址。 */
    val url="http://$host/$token"
    /** 当前这一步的手机号与验证码（上行短信那一步要重新登录，需要它们）。 */
    @Volatile private var phone=""
    @Volatile private var code=""
    /** 上行短信那一步要展示的收件号码与短信内容（重试时页面要原样再摆一次）。 */
    @Volatile private var smsNumber=""
    @Volatile private var smsContent=""
    init { Thread({ serve() },"hgtv-phone-login").apply { isDaemon=true; start() } }
    override fun close() { stopped.set(true); runCatching { listener.close() } }

    private fun serve() {
        try {
            while(!stopped.get() && System.nanoTime()<deadline) {
                val socket=try { listener.accept() } catch(_: SocketTimeoutException) { continue }
                socket.use { it.soTimeout=30_000; if(!stopped.get()) runCatching { handle(it) } }
            }
        } catch(_: Exception) { /* 关闭弹窗会打断 accept */ }
        finally { val expired=!stopped.get() && !consumed.get(); close(); if(expired) onExpired() }
    }

    private fun line(input: InputStream,max: Int): String {
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
        var size=0
        while(true) {
            // 单个头 8 KB、总量 16 KB：手机浏览器（微信 / QQ 内置）的 UA 与 Sec-CH-UA 系列很长。
            val next=line(input,8192); size+=next.length+2
            if(size>16384) return respond(socket,431,"请求过大")
            if(next.isEmpty()) break
            val at=next.indexOf(':'); if(at<=0) return respond(socket,400,"请求格式错误")
            if(headers.put(next.substring(0,at).lowercase(Locale.ROOT),next.substring(at+1).trim())!=null) return respond(socket,400,"重复请求头")
        }
        if(stopped.get() || System.nanoTime()>=deadline) return respond(socket,410,"页面已失效，请在电视上重新打开")
        if(headers["host"]!=host || !request[1].startsWith("/$token")) return respond(socket,404,"页面不存在")
        // 来源校验：只拦能明确指认的第三方站点（扫码 App 的内置浏览器会把 Origin 变成 null，那种放行）。
        val origin=headers["origin"]
        if(origin!=null && origin!="null" && origin!="http://$host" &&
            (origin.startsWith("http://") || origin.startsWith("https://"))) return respond(socket,403,"来源不匹配")
        val path=request[1].removePrefix("/$token")
        // 扫码打开的就是首页（`/`）；其余路径都只走 POST。
        if(request[0]=="GET") return respond(socket,200,if(path.isEmpty() || path=="/") phonePage() else phonePage())
        if(request[0]!="POST") return respond(socket,405,"不支持的操作")
        if(headers.containsKey("transfer-encoding")) return respond(socket,400,"请求格式错误")
        if(headers["content-type"]?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)!="application/x-www-form-urlencoded")
            return respond(socket,400,"请求格式错误")
        val length=headers["content-length"]?.toIntOrNull()?.takeIf { it in 1..8192 } ?: return respond(socket,413,"内容过长")
        val body=ByteArray(length); var offset=0
        while(offset<body.size) { val n=input.read(body,offset,body.size-offset); if(n<0) return; offset+=n }
        val fields=String(body,Charsets.UTF_8).split('&').mapNotNull {
            val at=it.indexOf('='); if(at<=0) null else it.substring(0,at) to runCatching { URLDecoder.decode(it.substring(at+1),"UTF-8") }.getOrDefault("")
        }.toMap()
        when(path) {
            "/send" -> respond(socket,200,send(fields["phone"].orEmpty().trim()))
            "/login" -> respond(socket,200,submit(fields["code"].orEmpty().trim()))
            "/verify" -> respond(socket,200,verify())
            else -> respond(socket,404,"页面不存在")
        }
    }

    /** ① 发验证码。 */
    private fun send(value: String): String {
        val result=login.sendCode(value) { profile -> registerDevice(profile) }
        return when(result) {
            is PhoneLogin.Result.Sent -> { phone=value; codePage("验证码已发送到 $value") }
            is PhoneLogin.Result.NeedUpsms -> {
                // 发码这一步先被要求发一条上行短信：这一步还没有验证码可提交。
                phone=value; code=""; smsNumber=result.number; smsContent=result.content
                upsmsPage(result.number,result.content)
            }
            is PhoneLogin.Result.Failed -> phonePage(result.message)
            else -> phonePage("验证码发送失败，请稍后重试")
        }
    }

    /** ② 提交验证码。 */
    private fun submit(value: String): String {
        val result=login.login(phone,value)
        return when(result) {
            is PhoneLogin.Result.LoggedIn -> {
                finish(result.credentials)
                PhonePage.done("已登录到电视")
            }
            is PhoneLogin.Result.NeedUpsms -> {
                code=value; smsNumber=result.number; smsContent=result.content
                upsmsPage(result.number,result.content)
            }
            is PhoneLogin.Result.Failed -> codePage(result.message)
            else -> codePage("登录失败，请稍后重试")
        }
    }

    /**
     * ③ 上行短信验证：用户发出短信之后。
     *
     * 平台通常要过几秒才看得到这条短信，那期间它回 `error_code=1045`（[PhoneLogin.UpsmsResult.Pending]），
     * **不是失败**。参考实现是「间隔 3 秒、最多 30 次」的 90 秒轮询；这一页是同步页面，
     * 浏览器会一直转圈，因此这里只替用户轮询 [UPSMS_POLLS] 轮就交还：
     * 页面把号码与内容原样再摆一遍，用户点一次「我已发送」即可接着等。
     *
     * 只有平台明确结束这次验证（`9001` / 「长时间未操作，验证已过期」这类）才回到
     * 「获取新验证码」那一页——与参考实现同一条规则，且不重复发短信。
     */
    private fun verify(): String {
        repeat(UPSMS_POLLS) { round ->
            if(round>0) Thread.sleep(UPSMS_POLL_MS)
            when(val result=login.verifyUpsms()) {
                is PhoneLogin.UpsmsResult.Pending -> Unit
                is PhoneLogin.UpsmsResult.Failed -> return if(login.waitingUpsms) upsmsPage(smsNumber,smsContent,result.message)
                    else phonePage(result.message)
                is PhoneLogin.UpsmsResult.Ticket -> {
                    // 挑战来自发码那一步时没有验证码可登：直接重新发一张新码让用户填。
                    if(login.mfaFromSendCode) {
                        val resent=login.sendCode(phone) { profile -> registerDevice(profile) }
                        return when(resent) {
                            is PhoneLogin.Result.Sent -> codePage("验证通过，新的验证码已发到 $phone")
                            is PhoneLogin.Result.NeedUpsms -> {
                                smsNumber=resent.number; smsContent=resent.content
                                upsmsPage(resent.number,resent.content)
                            }
                            is PhoneLogin.Result.Failed -> phonePage(resent.message)
                            else -> phonePage("验证通过，但新验证码没有发出，请重新打开登录页")
                        }
                    }
                    val logged=login.login(phone,code,result.value)
                    return when(logged) {
                        is PhoneLogin.Result.LoggedIn -> {
                            finish(logged.credentials)
                            PhonePage.done("已登录到电视")
                        }
                        is PhoneLogin.Result.Failed -> if(login.waitingUpsms) upsmsPage(smsNumber,smsContent,logged.message)
                            else phonePage(logged.message)
                        else -> upsmsPage(smsNumber,smsContent,"验证未完成，请稍后重试")
                    }
                }
            }
        }
        return upsmsPage(smsNumber,smsContent,
            "平台还没看到这条短信。请确认已经用本机号码发出，稍等几秒再点一次下面的按钮。")
    }

    /** 登录成功：一次性页面到此为止。 */
    private fun finish(credentials: AccountCredentials) {
        if(!consumed.compareAndSet(false,true)) return
        close()
        onLoggedIn(credentials)
    }

    /**
     * 注册设备。注册请求必须在电视这一侧发（手机只做界面），因此这里直接调仓库的注册。
     *
     * 失败返回 null，由 [PhoneLogin.sendCode] 判成「没注册成功」并如实提示。
     */
    private var registerHook: ((Map<String,String>)->DeviceRegister.Registered?)? = null
    /** 由电视侧注入：拿一份设备档案去匿名注册一台设备。 */
    fun onRegister(hook: (Map<String,String>)->DeviceRegister.Registered?) { registerHook=hook }
    private fun registerDevice(profile: Map<String,String>): DeviceRegister.Registered? =
        registerHook?.invoke(profile)

    // ------------------------------------------------------------------ 页面

    /** 三页共用一套外壳（见 [PhonePage]）：手机竖屏、新拟态卡片、凹陷输入框。 */
    private fun shell(title: String,body: String,footer: String=LOGIN_FOOTER) = PhonePage.shell(title,body,footer)

    private fun phonePage(error: String="") = shell("手机号登录",
        """<span class="pill">手机号登录</span>""" +
        """<h1>用手机号登录</h1>""" +
        """<p class="lead">填写手机号并收取验证码，登录结果自动回到电视。</p>""" +
        (if(error.isNotBlank()) """<p class="err">$error</p>""" else "") +
        """<div class="card">""" +
        """<form method="post" action="/$token/send">""" +
        """<input name="phone" inputmode="tel" autocomplete="tel" maxlength="20" required autofocus """ +
        """placeholder="手机号（可带国家区号）">""" +
        """<button class="btn-primary" type="submit">发送验证码</button>""" +
        """</form>""" +
        """<p class="hint">未注册号码会自动创建账号。</p>""" +
        """</div>""",
        footer = "仅本次端口打开期间有效，最长 10 分钟；凭据只保存在电视本机。")

    private fun codePage(hint: String) = shell("填写验证码",
        """<span class="pill">手机号登录</span>""" +
        """<h1>填写验证码</h1>""" +
        """<p class="lead">$hint</p>""" +
        """<div class="card">""" +
        """<form method="post" action="/$token/login">""" +
        """<input name="code" inputmode="numeric" autocomplete="one-time-code" maxlength="8" required autofocus """ +
        """placeholder="短信里的验证码">""" +
        """<button class="btn-primary" type="submit">登录</button>""" +
        """</form>""" +
        """</div>""" +
        """<div class="card">""" +
        """<form method="post" action="/$token/send">""" +
        """<input type="hidden" name="phone" value="$phone">""" +
        """<button class="btn-ghost" type="submit">重新发送验证码</button>""" +
        """</form>""" +
        """</div>""",
        footer = "仅本次端口打开期间有效，最长 10 分钟；凭据只保存在电视本机。")

    private fun upsmsPage(number: String,content: String,error: String="") = shell("上行短信验证",
        """<span class="pill">手机号登录</span>""" +
        """<h1>还需要一条短信</h1>""" +
        (if(error.isNotBlank()) """<p class="err">$error</p>""" else "") +
        """<p class="lead">平台要求用本机号码主动发一条短信来确认是本人操作。请用刚才那个手机号发送下面这条短信。</p>""" +
        """<div class="card">""" +
        """<h2>收件号码</h2><code class="block">$number</code>""" +
        """<h2>短信内容</h2><code class="block">$content</code>""" +
        """</div>""" +
        """<div class="card">""" +
        """<form method="post" action="/$token/verify"><button class="btn-primary" type="submit">我已发送</button></form>""" +
        """<p class="hint">这一步请在几分钟内做完：平台的验证码与本次验证都有时效。</p>""" +
        """</div>""",
        footer = "仅本次端口打开期间有效，最长 10 分钟；凭据只保存在电视本机。")

    private fun respond(socket: Socket,code: Int,message: String) {
        // 2xx 的 message 已经是一整页 HTML；4xx / 5xx 由这里统一套成错误页。
        val html=if(code in 200..299) message else PhonePage.failed("手机号登录",message,"回到电视重新打开登录窗口即可。")
        val body=html.toByteArray(Charsets.UTF_8)
        val header="HTTP/1.1 $code Response\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'\r\n\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(body); flush() }
    }
}
