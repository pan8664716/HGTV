// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core.session

import okhttp3.Interceptor
import okhttp3.Response

/**
 * 对齐原版 `px7/a.java`（Retrofit 拦截器）+ `TTTokenManager.addRequestHeader`
 * / `processResponseHeader`。三件事：
 *
 * 1. **补身份**（只对会话 host）：`X-Tt-Token` 只放 token、`Cookie` 只放 cookie；
 * 2. **每个响应扫 `X-Tt-Token`**：服务端可以在任意接口的响应头里换 token；
 * 3. **每个响应合并 `Set-Cookie`**：登录之外的接口也会下发 / 删除 cookie。
 *
 * 挂在哪：`ContentRepository.defaultHttpClient()`。这样 `MainActivity.repository`、
 * `FavoriteMonitor`、播放预取、`PlaybackTransport` 的仓库自动都带上。
 */
class SessionInterceptor(
    /**
     * 是否给会话 host 补 `X-Tt-Token`。
     *
     * 默认 `true`（与真机一致：App 每个业务请求都带 token）。若某条接口在补齐之后回空，
     * 先把这里关掉定位，再修正 token 内容——**不要**退化成「不带 token」长期运行。
     */
    private val injectToken: Boolean = true
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val sessionHost = isSessionHost(original.url().host())
        // 播放取地址接口（`/novel/player/...`）**只补 Cookie、不补 token**：
        // 这条接口对 `X-Tt-Token` 的长度极其敏感（整段 cookie 串塞进去直接回 `200 + 0 字节`）。
        val skipToken = original.url().encodedPath().startsWith("/novel/player/")
        val request = if(sessionHost) {
            SessionBridge.decorate(original.newBuilder(),injectToken && !skipToken).build()
        } else original
        // 取播放地址的请求留一行原始 URL：故障时最需要看的就是它带的 `device_id`
        //（这条接口按设备计额度，见 `ContentRepository.modelDeviceId`）。
        // 不再逐条 dump 请求头：`x-medusa` / `x-hellos` 这类字段几百字符，
        // 一条请求就能把十几行刷掉，真正的结论（路径 / 字节数 / 业务码）反而被挤出屏幕。
        if(request.url().encodedPath().contains("video_model")) {
            SessionBridge.log("model req ${request.method()} ${request.url()}")
        }
        val response = chain.proceed(request)
        if(sessionHost) {
            val rotated = response.header("X-Tt-Token")
            if(!rotated.isNullOrBlank() && rotated!=SessionBridge.state?.token) {
                // 只落「属于当前这份会话」的 token。
                //
                // 换号 / 重新扫码之后，服务端仍可能在一小段时间内把**上一份会话**的 token
                // 回给我们（在途请求、网关缓存都够造成）。照收就会把一份与新 cookie 不同源的
                // token 存进 SP，之后每个请求都带着它 → 平台按「不同源」把有效会话降级成
                // 匿名设备，界面上就是「登录成功却一条数据都没有」。
                val sid = SessionBridge.state?.cookieHeader?.let { CookieRecord.value(it,"sessionid") }.orEmpty()
                if(sid.isNotBlank() && rotated.length>=SessionBridge.TOKEN_CHECK_LENGTH && !rotated.contains(sid)) {
                    SessionBridge.log("ignored foreign token len=${rotated.length} (sessionid mismatch)")
                } else if(SessionBridge.saveToken(rotated)) {
                    SessionBridge.log("token rotated by ${original.url().host()}")
                }
            }
            val cookies = response.headers("Set-Cookie")
            // 续期（`sid_guard` 每次心跳都会重新签发）就是靠这里落盘的：只在**真的有变化**时记一行，
            // 否则每个业务请求都会刷一条日志。
            if(cookies.isNotEmpty() && SessionBridge.mergeSetCookie(cookies)) {
                SessionBridge.log("cookie renewed by ${original.url().host()} (${cookies.size} set)")
            }
            sniffExpired(response)
        }
        return response
    }

    /**
     * `session_expired` 嗅探。**只看 body 里的 `data.error_name`**，不看状态码：
     * beat 过期时服务端照样回 `HTTP 200`。
     *
     * 只嗅探小 JSON 响应：媒体分片（`/novel/player/`）与超过 32 KB 的响应跳过，
     * 用 `peekBody` 复制一份，下游照常读原 body。
     */
    private fun sniffExpired(response: Response) {
        if(SessionBridge.state==null) return
        if(response.request().url().encodedPath().startsWith("/novel/player/")) return
        val length = response.body()?.contentLength() ?: -1L
        if(length<=0L || length>SNIFF_LIMIT) return
        val text = runCatching { response.peekBody(SNIFF_LIMIT).string() }.getOrNull() ?: return
        if(!text.contains("session_expired",ignoreCase = true)) return
        val flag = runCatching {
            org.json.JSONObject(text).optJSONObject("data")?.optString("error_name").orEmpty()
        }.getOrNull().orEmpty()
        if(flag.equals(ERR_SESSION_EXPIRED,ignoreCase = true)) SessionBridge.markExpired("http:${response.code()}")
    }

    private fun isSessionHost(host: String): Boolean {
        val h = host.lowercase()
        return SESSION_HOSTS.any { h==it || h.endsWith(".$it") }
    }

    private companion object {
        /** 对齐原版 `share_cookie_host_list`（`dc5/a.java`）。 */
        val SESSION_HOSTS = listOf(
            "snssdk.com","zijieapi.com","ulpay.com","fqnovel.com",
            "ecombdapi.com","luckysf.net","iluckysf.net",
            "awemeughun.com","awemeugsoul.com"
        )
        const val SNIFF_LIMIT = 32L*1024
        const val ERR_SESSION_EXPIRED = "session_expired"
    }
}
