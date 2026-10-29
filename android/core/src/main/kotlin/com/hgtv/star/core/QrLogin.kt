// SPDX-License-Identifier: GPL-3.0-only
// 协议见 docs/HGTVWeb扫码登录分析.md（2026-10-23 实测）。
package com.hgtv.star.core

import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/**
 * 扫码登录（Passport 的 Web 出码 + 长轮询）。
 *
 * ## 三步
 *
 * ```
 * ① 出码   GET /passport/web/get_qrcode/?next=&aid=513755
 *          → token（32 hex + `_lq`）+ qrcode（PNG 的 data URL）+ expire_time（now+60）
 * ② 轮询   GET /passport/web/check_qrconnect/?next=&token=&aid=513755   每 1.1 秒一次
 *          → new → scanned → confirmed
 * ③ 落地   confirmed 的那一次响应里直接 `Set-Cookie` 下发 14 条会话 Cookie
 *          → [credentials] 交给 `ContentRepository.credentials`，与手机号登录得到的完全同一族
 * ```
 *
 * ## 几个只有实测才知道的点
 *
 * * **`aid` 只能是 513755**：Web 接口不认 App 侧的 8662（回 `16 该应用无权限`）。
 * * **签名参数全部可省**：`a_bogus` / `msToken` / `account_sdk_source_info` 服务端不校验，
 *   裸 HTTPS 请求一律 200。
 * * **过期不用重新出码**：`check_qrconnect` 在 `expired` 的那一次响应里**自带新码**
 *   （`token` + `qrcode` + `expire_time`）。因此客户端只要一直轮询，二维码就能原地续期，
 *   这正是「过期自动刷新」的实现基础（见 [Outcome.Renewed]）。
 * * **确认后 Cookie 在同一响应里落地**：`redirect_url` 只是让浏览器跳回首页，
 *   不带任何凭证；真正的登录态是那一次响应的 `Set-Cookie`。
 */
class QrLogin(
    // 钉死 HTTP/1.1：与 ContentRepository / PhoneLogin 同一理由（平台 h2 读线程异常会打死进程）。
    private val http: OkHttpClient = HgtvTls.configure(OkHttpClient.Builder())
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .connectTimeout(15,TimeUnit.SECONDS)
        .readTimeout(20,TimeUnit.SECONDS)
        .callTimeout(30,TimeUnit.SECONDS)
        .build(),
    private val clock: () -> Long = System::currentTimeMillis
) {
    companion object {
        /** 出码 / 轮询的主机：与账号侧同一族域名，实测可用。 */
        const val HOST = "https://api5-normal-lq.fqnovel.com"
        /** Web 站点的 `aid`（App 侧的 8662 会被拒）。 */
        const val AID = "513755"
        /** `next` 只要非空即可（平台不校验域名），这里回填主机自身。 */
        private const val NEXT = "$HOST/"
        /** `Set-Cookie` 里的属性名：它们不是 Cookie，收进来会污染会话串。 */
        private val SET_COOKIE_ATTRIBUTES = setOf(
            "path","domain","expires","max-age","secure","httponly","samesite","priority","partitioned"
        )
        /** 轮询间隔：与官方前端 `setTimeout(fn, 1000)` 对齐（网络 RTT ≈ 100ms）。 */
        const val POLL_MS = 1100L
    }

    /** 一张二维码（token + PNG 字节 + 过期时刻，秒）。 */
    data class Code(val token: String,val png: ByteArray,val expireAt: Long) {
        // data class 里带 ByteArray 时 equals / hashCode 按引用算，这里显式补上按内容比较。
        override fun equals(other: Any?): Boolean =
            this===other || (other is Code && token==other.token && png.contentEquals(other.png) && expireAt==other.expireAt)
        override fun hashCode(): Int = token.hashCode()*31+png.contentHashCode()+expireAt.hashCode()
    }

    /** 一次轮询的结果。 */
    sealed class Outcome {
        /** 还没人扫。 */
        object Waiting: Outcome()
        /** 手机扫了、还没点确认（平台在这一步**不泄露**身份，只给一个匿名头像）。 */
        data class Scanned(val appId: Int): Outcome()
        /** 手机点了确认：会话 Cookie 已在本次响应里落地。 */
        data class Confirmed(val credentials: AccountCredentials,val nickname: String): Outcome()
        /** 旧码过期，平台在响应里直接给了新码：原地替换即可。 */
        data class Renewed(val code: Code): Outcome()
        /** 失败：可直接展示给用户的一句话。 */
        data class Failed(val message: String): Outcome()
    }

    /**
     * 设备档案（与内容接口 / 手机号登录同一份）。
     *
     * 出码与轮询本身**不需要**它（平台不校验签名），但确认之后落地的
     * [AccountCredentials] 要带上一致的 `device_id` / `iid` / `cdid`，
     * 否则后续账号接口的 `x-medusa` 签名与 Cookie 里的设备对不上。
     */
    var device: Map<String,String> = emptyMap()

    /** 诊断日志出口（`core` 没有 Android SDK），由界面层注入 `android.util.Log`。 */
    var log: (String) -> Unit = {}
    private fun trace(message: String) { runCatching { log(message) } }

    /** 最近一次失败的原因（界面在出码失败时原样显示）。 */
    var lastError: String = ""
        private set

    /** 平台下发的 Cookie（出码时的 `passport_csrf_token`、确认时的 14 条会话）。 */
    private val cookies = linkedMapOf<String,String>()
    /** 会话 Token（`X-Tt-Token` 响应头；与 Cookie 是两套并行凭证）。 */
    private var token: String = ""
    /** TicketGuard 静态签名（`bd-ticket-guard-sign-res-static-ts-sign` 响应头）。 */
    private var tsSign: String = ""
    /**
     * 串行化网络访问。
     *
     * 界面层可能在一次轮询还在飞的时候按了「刷新二维码」：两次请求会同时读写
     * [cookies] / [token] 这两份普通容器，并发下轻则丢 Cookie，重则
     * `ConcurrentModificationException` 打死轮询线程。一把锁把「发请求 + 收 Cookie」
     * 变成原子的，顺带保证同一时刻只有一个在途请求。
     */
    private val lock = Any()

    /** 出码（一次请求），失败返回 null 并把原因写进 [lastError]。 */
    fun fetchCode(): Code? {
        val json = request("/passport/web/get_qrcode/",listOf("next" to NEXT,"aid" to AID,"language" to "zh"))
        if(json==null) return null
        val data = json.optJSONObject("data")
        if(data==null) { lastError="账号服务返回的内容不完整"; return null }
        val code = data.optString("error_code")
        if(code.isNotEmpty() && code!="0") {
            lastError = data.optString("description").ifBlank { "二维码获取失败（$code）" }
            return null
        }
        val token = data.optString("token")
        val png = decodeImage(data.optString("qrcode"))
        if(token.isBlank() || png==null) { lastError="平台没有返回二维码"; return null }
        return Code(token,png,data.optLong("expire_time",0L))
    }

    /**
     * 轮询一次。
     *
     * 平台对**未知 token** 也回 `expired` + 一张新码（不区分「不存在」与「已过期」），
     * 因此 [Outcome.Renewed] 既覆盖「真过期」，也覆盖「平台把这个 token 丢了」——
     * 两种情况对客户端是同一件事：换码接着等。
     */
    fun check(token: String): Outcome {
        if(token.isBlank()) return Outcome.Failed("二维码已失效，请刷新")
        val json = request("/passport/web/check_qrconnect/",listOf("next" to NEXT,"token" to token,"aid" to AID))
            ?: return Outcome.Failed(lastError.ifBlank { "网络不可用，请稍后重试" })
        val data = json.optJSONObject("data") ?: return Outcome.Failed("账号服务返回的内容不完整")
        val code = data.optString("error_code")
        if(code.isNotEmpty() && code!="0") {
            return Outcome.Failed(data.optString("description").ifBlank { "扫码状态读取失败（$code）" })
        }
        return when(data.optString("status").lowercase(Locale.ROOT)) {
            "1","new" -> Outcome.Waiting
            "2","scanned" -> Outcome.Scanned(data.optInt("scan_app_id",0))
            "3","confirmed" -> {
                // **不要把响应体里的 `token` 当成会话 token**：那是**二维码**的 token
                // （32 位 hex + `_lq` 后缀，与 `get_qrcode` 同一个东西），不是 `X-Tt-Token`。
                // 早先直接赋给 [token]，于是后续请求带着一个 35 字符的二维码 token 出去；
                // 平台虽然容忍（实测 `user/info` 仍回正常账号），但心跳 / 播放那条链路
                // 对 token 形状敏感，属于不该存在的偏差。真正的会话 token 由登录后
                // 立刻发的那一次 boot 心跳从响应头换回来（见 `SessionKeeper`）。
                val confirmToken = data.optString("token")
                if(confirmToken.isNotBlank() && !confirmToken.endsWith("_lq") && confirmToken.length>64) {
                    this.token=confirmToken
                }
                val user = data.optJSONObject("user_data")
                val nickname = user?.optString("screen_name").orEmpty()
                    .ifBlank { user?.optString("name").orEmpty() }
                Outcome.Confirmed(credentials(),nickname)
            }
            "4","5","expired" -> {
                val fresh = data.optString("token")
                val png = decodeImage(data.optString("qrcode"))
                if(fresh.isBlank() || png==null) Outcome.Failed("二维码已失效，请刷新")
                else Outcome.Renewed(Code(fresh,png,data.optLong("expire_time",0L)))
            }
            else -> Outcome.Waiting
        }
    }

    /** 当前会话整理成与手机号登录同一种凭据（Cookie 串 + 设备档案 + 过期时间）。 */
    fun credentials(): AccountCredentials = synchronized(lock) {
        AccountCredentials(
            cookie=AccountCredentialsParser.headerOf(cookies),
            token=token,
            tsSign=tsSign,
            deviceId=device["device_id"].orEmpty(),
            installId=device["iid"].orEmpty(),
            cdid=device["cdid"].orEmpty(),
            openudid=device["openudid"].orEmpty(),
            expiresAt=AccountCredentialsParser.expiryOf(cookies),
            device=device
        )
    }

    /** `data:image/png;base64,...` → PNG 字节。 */
    private fun decodeImage(value: String): ByteArray? {
        if(value.isBlank()) return null
        val payload = value.substringAfter("base64,",value)
        return runCatching { decode64(payload) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /**
     * 拼出一次请求的完整 URL。
     *
     * 单独抽出来是为了**能被单测钉住**——这里曾经出过一次只在真机上才暴露的事故：
     * OkHttp 3 → 4 迁移时把 `HttpUrl.parse(HOST+path)` 机械改写成
     * `HOST+path.toHttpUrlOrNull()`，**括号丢了**。`path` 是相对路径，解析结果是 null，
     * 于是 `HOST + null` 拼出 `https://api5-normal-lq.fqnovel.comnull`——
     * 编译通过、类型正确，只有运行时打到一个不存在的主机上。
     *
     * 现在 `(HOST + path)` 先拼成一个绝对地址再解析，返回值可能是 null（地址非法），
     * 由调用方给出「接口地址无效」。
     */
    internal fun endpoint(path: String,params: List<Pair<String,String>>): HttpUrl? {
        // 空路径会拼出「站点根」，那不是任何一个接口：宁可当非法地址处理。
        if(path.isBlank()) return null
        return (HOST + path).toHttpUrlOrNull()?.newBuilder()
            ?.apply { params.forEach { (key,value) -> addQueryParameter(key,value) } }
            ?.build()
    }

    /** 一次 GET：收 `Set-Cookie` / `X-Tt-Token`，回 JSON；失败返回 null。 */
    private fun request(path: String,params: List<Pair<String,String>>): JSONObject? = synchronized(lock) {
        val url = endpoint(path,params) ?: run { lastError="接口地址无效"; return null }
        val builder = Request.Builder().url(url)
            .header("User-Agent",VendorConstants.userAgent(device,video = false))
            .header("Accept","application/json, text/plain, */*")
            .header("Referer","$HOST/")
        if(cookies.isNotEmpty()) builder.header("Cookie",cookies.entries.joinToString("; ") { (k,v) -> "$k=$v" })
        if(token.isNotBlank()) builder.header("X-Tt-Token",token)
        return try {
            val raw = http.newCall(builder.get().build()).execute().use { response ->
                response.headers("Set-Cookie").forEach { header -> collectCookies(header) }
                response.header("X-Tt-Token")?.takeIf { it.isNotBlank() }?.let { token=it }
                response.header("bd-ticket-guard-sign-res-static-ts-sign")?.takeIf { it.isNotBlank() }?.let { tsSign=it }
                response.body?.string().orEmpty()
            }
            if(raw.isBlank()) { lastError="账号服务暂无响应，请稍后重试"; return null }
            trace("← $path ${raw.take(160)}")
            JSONObject(raw)
        } catch(e: IOException) {
            lastError=e.message.orEmpty().ifBlank { "网络不可用，请稍后重试" }
            trace("✗ $path ${e.javaClass.simpleName}: ${e.message}")
            null
        } catch(e: Exception) {
            lastError=e.message.orEmpty().ifBlank { "扫码登录失败，请稍后重试" }
            trace("✗ $path ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * 收一条 `Set-Cookie`。
     *
     * 第一段一定是 `name=value`，后面是属性（`Path` / `Domain` / `Max-Age` …），
     * 那些**不能**当 Cookie 存；`Max-Age=0` 表示删除（平台在登录成功时会删 `reg-store-region`）。
     */
    private fun collectCookies(header: String) {
        var name = ""
        var value = ""
        var expired = false
        header.split(';').forEachIndexed { index, raw ->
            val part = raw.trim()
            val at = part.indexOf('=')
            if(at<=0) return@forEachIndexed
            val key = part.substring(0,at).trim()
            val text = part.substring(at+1).trim()
            when {
                index==0 -> { name=key; value=text }
                key.equals("Max-Age",true) && text=="0" -> expired=true
            }
        }
        if(name.isBlank() || name.lowercase(Locale.ROOT) in SET_COOKIE_ATTRIBUTES) return
        if(expired || value.isEmpty()) cookies.remove(name) else cookies[name]=value
    }
}
