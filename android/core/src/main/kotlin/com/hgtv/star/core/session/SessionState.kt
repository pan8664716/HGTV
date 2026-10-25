// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core.session

import okhttp3.Request

/**
 * 会话状态的最小接口。**放在 `core` 里、不带任何 Android 类型**：
 *
 * `android/core` 是 `kotlin("jvm")` 纯 Kotlin 模块，编译期没有 `android.*`。因此：
 *
 * * `CookieRecord` / 请求头装配 / 拦截器 / 心跳（本包其余文件）→ `core`
 * * `SharedPreferences` 实现、广播、`Application` 接线 → `app`（`com.hgtv.star.data.AppSessionStore`）
 *
 * 这样 `ContentRepository.defaultHttpClient()` 可以直接装上拦截器，
 * 不必改任何一处 `ContentRepository(...)` 的调用点。
 */
interface SessionState {
    /** 当前 `X-Tt-Token`。 */
    val token: String

    /** 会话 cookie 的扁平串（`name=value; name=value`）。 */
    val cookieHeader: String

    /** 设备注册下发的 cookie（`install_id` / `ttreq` / `store-region` …）。 */
    val deviceCookies: Map<String,String>

    /** `store-region`，以 cookie 里那份为准；没有时 `cn`。 */
    val storeRegion: String

    /** 真机设备档案（`account-device` 那份 JSON 解出来的）。心跳的 `User-Agent` 用它。 */
    val deviceProfile: Map<String,String>

    /**
     * 会话到期时刻（绝对毫秒）；`0` = 本机还不知道。
     *
     * 来源是 `sid_guard`（`sessionid|签发时间|max-age|可读时间`），由登录响应或心跳响应里的
     * `Set-Cookie` 推算。心跳**每次都会重新签发 `sid_guard`**（实测签发时间随心跳推进），
     * 所以它始终是「最近一次确认 + max-age」。
     */
    val expiresAtMs: Long

    /**
     * 原版 `first_beat`：只有首次心跳带 `true`，成功后永久 `false`。
     *
     * 每次启动都发 `true` 会被服务端当成重复首刷。
     */
    var firstBeat: Boolean

    /**
     * 上一次**成功**心跳的墙上时间（epoch 毫秒）；`0` = 本机还没打过。
     *
     * 为什么要落盘、为什么要读它：进程被杀 / 待机几天之后重新打开应用，只有它知道
     * 「这个 token 已经几天没续过了」。`sid_guard` 的 `max-age` 是 60 天量级、每次心跳
     * 都会重新签发，单看它永远显示「还有几十天」，判断不出「该续了」。
     */
    var lastBeatAtMs: Long

    /** token 真的变了才落盘，并返回是否变化。 */
    fun saveToken(token: String,tsSign: String = ""): Boolean

    /** 合并一批 `Set-Cookie`，返回是否有变化。 */
    fun mergeSetCookie(setCookies: List<String>): Boolean

    /** 设备注册下发的 cookie（`install_id` / `ttreq` / `store-region` …）落盘。 */
    fun saveDeviceCookies(cookies: Map<String,String>)

    /**
     * 只清会话（token / ts-sign / cookie），**不动设备身份与设备档案**。
     *
     * 设备身份（`registered-device-id` / `registered-install-id`）是「这台设备」的凭据，
     * 退出登录后必须保留：换账号登录时平台仍要认出同一台设备。
     */
    fun clearSession()
}

/**
 * 全局唯一的会话出口。
 *
 * 为什么要一个全局 object：`core` 里的 client 是**在对象构造时**建好的
 * （`ContentRepository(val http: OkHttpClient = defaultHttpClient())`），而会话是在
 * `Application.onCreate` 之后才就绪的。把 [state] 做成可空的可变引用，拦截器每次请求时现读，
 * 就能做到「先建 client、后登录」也不丢会话。
 *
 * `state` 为空 = 匿名（未登录），拦截器只补设备 cookie，不补 token。
 */
object SessionBridge {
    @Volatile var state: SessionState? = null

    /** 确认失效时的回调（`app` 层接广播 + 清 UI）。 */
    @Volatile var onExpired: ((reason: String) -> Unit)? = null

    /** token 轮换时的回调（落外部镜像 / 打日志用）。 */
    @Volatile var onTokenChanged: ((token: String) -> Unit)? = null

    /** 诊断日志出口；`core` 没有 `android.util.Log`，由 `app` 注入。 */
    @Volatile var log: (String) -> Unit = {}

    /**
     * 把会话身份补到一个请求上。**token 与 cookie 严格分开**：
     *
     * ```
     * X-Tt-Token: <token>                         ← 只有 token
     * Cookie: <device cookies>; <session cookie>  ← 只有 cookie
     * ```
     */
    fun decorate(builder: Request.Builder,injectToken: Boolean = true): Request.Builder {
        val current = state
        val merged = LinkedHashMap<String,String>()
        // ① 设备 cookie 铺底（`install_id` / `ttreq` / `store-region`）。
        current?.deviceCookies?.forEach { (name,value) ->
            if(name.isNotBlank() && value.isNotBlank()) merged[name] = value
        }
        // ② 调用方**已经写好的** `Cookie` 不能丢。
        //
        // 这一条是「扫码登录报『这份状态无效，平台把它当成匿名设备』」的根因：
        // `ContentRepository.withSession()` 会把刚拿到的整套账号凭据（含 `sessionid`）写进
        // 请求头，而这里原先直接 `header("Cookie", 设备cookie)` 整段覆盖——扫码成功那一刻
        // `AppSessionStore` 里还没有这份会话（要等校验通过才落盘），于是 `sessionid` 被
        // 设备 cookie 顶掉，平台按匿名设备返回一个「用户20114780」式的临时账号。
        // 实测：会话 cookie + 设备 cookie → 正常账号；只剩设备 cookie → 匿名账号。
        // ③ 现读会话层：服务端可能刚轮换过（心跳 / 任意响应头的 `Set-Cookie`）。
        current?.cookieHeader?.takeIf { it.isNotBlank() }
            ?.let { merged.putAll(CookieRecord.headerToMap(it)) }
        // ④ 调用方最后压一次：它代表「这一次请求明确要用的那份会话」，必须压过上面两层。
        // 顺序不能反——反了就会出现「刚扫码拿到的 `sessionid` 被上一份过期会话盖回去」。
        builder.build().header("Cookie")?.takeIf { it.isNotBlank() }
            ?.let { merged.putAll(CookieRecord.headerToMap(it)) }
        // token 同理：调用方明确给了就用它的（登录那一刻换回来的那份），没有才补会话层的。
        // 反过来的话，SP 里残留的旧 token 会把刚拿到的新会话顶掉——平台按「token 与 cookie
        // 不同源」判过期，这正是「早晨一打开就掉登录」的成因。
        val token = current?.token.orEmpty()
        val explicitToken = builder.build().header("X-Tt-Token").orEmpty()
        val effective = explicitToken.takeIf { it.isNotBlank() } ?: token
        if(injectToken && effective.isNotBlank() && consistent(effective, merged["sessionid"].orEmpty())) {
            builder.header("X-Tt-Token", effective)
        } else if(injectToken && effective.isNotBlank()) {
            // **不一致就整条不发**，而不是照发。实测平台规则（同一份有效 cookie）：
            //
            // ```
            // 不带 token                          → 正常账号
            // 98 字符以内的 token                  → 正常账号（不校验）
            // ≥99 字符且内嵌**本会话** sessionid   → 正常账号
            // ≥99 字符但内嵌**别的** sessionid     → 匿名设备（「用户20114780」）
            // ```
            //
            // 也就是说陈旧 token 的破坏力不是「被忽略」，而是把整份有效会话降级成匿名。
            // 丢掉它只是少一次校验通过，带着它却是直接掉登录，因此这里宁可什么都不发——
            // 心跳那一发会把正确的新 token 换回来。
            // 调用方写进去的那一份也要显式摘掉，否则它照样会发出去。
            builder.removeHeader("X-Tt-Token")
            log("drop stale token len=${effective.length} (sessionid mismatch)")
        }
        if(merged.isNotEmpty()) builder.header("Cookie",CookieRecord.mapToHeader(merged))
        builder.header("X-SS-DP","8662")
        builder.header("X-Tt-Store-Region",current?.storeRegion ?: "cn")
        builder.header("X-Tt-Store-Region-Src","did")
        builder.header("Sdk-Version","2")
        builder.header("passport-sdk-version","5051452")
        builder.header("X-Xs-From-Web","0")
        return builder
    }

    /**
     * token 与 cookie 是不是同一次会话签发的。
     *
     * 短 token（<99 字符）平台不校验，一律放行；长 token 的形状是
     * `00<sessionid><...>`，用「内嵌本会话 sessionid」判同源最可靠。
     * 没有 sessionid（未登录 / 只导入了设备 cookie）时不拦。
     */
    private fun consistent(token: String, sessionId: String): Boolean {
        if(token.length<TOKEN_CHECK_LENGTH) return true
        if(sessionId.isBlank()) return true
        return token.contains(sessionId)
    }

    /** 当前要发给心跳的 `Cookie` 头（设备 + 会话）。 */
    fun cookieHeaderForBeat(): String {
        val current = state ?: return ""
        val merged = LinkedHashMap<String,String>()
        current.deviceCookies.forEach { (name,value) ->
            if(name.isNotBlank() && value.isNotBlank()) merged[name] = value
        }
        merged.putAll(CookieRecord.headerToMap(current.cookieHeader))
        return CookieRecord.mapToHeader(merged)
    }

    /**
     * 能不能打心跳。判据是**会话 cookie 或 token 有其一**：
     *
     * * 手机号 / 扫码登录：两样都有；
     * * 导入 `cookieStore.xml`：只有 cookie、没有 `X-Tt-Token`。这种会话照样要心跳——
     *   第一次 beat / account/info 的响应头就会把 `X-Tt-Token` 发回来，拦截器收到即落盘。
     */
    fun signedIn(): Boolean {
        val current = state ?: return false
        return current.token.isNotEmpty() || current.cookieHeader.contains("sessionid=")
    }

    fun saveToken(token: String,tsSign: String = ""): Boolean {
        val changed = state?.saveToken(token,tsSign) ?: false
        if(changed) onTokenChanged?.invoke(token)
        return changed
    }

    /** 清会话（退出登录 / 确认失效）。设备身份不受影响。 */
    fun clearSession() { state?.clearSession() }

    fun mergeSetCookie(setCookies: List<String>): Boolean = state?.mergeSetCookie(setCookies) ?: false

    /**
     * 服务端说会话过期（`data.error_name == "session_expired"`）。
     *
     * **不在这里清凭证**：本方法可能在响应体还没读完时被调（拦截器），当场清 token 会让
     * 同一批在途请求全部退化成匿名请求。这里只把标记交给 `SessionKeeper`，由它统一执行。
     */
    fun markExpired(reason: String) { pendingExpiry = reason }
    @Volatile var pendingExpiry: String? = null

    /**
     * 平台从哪个长度开始校验 `X-Tt-Token` 与 cookie 同源（实测 98 放行、99 拦下）。
     *
     * 放在这里而不是 `SessionKeeper`：判断发生在每一个业务请求的装配点上。
     */
    const val TOKEN_CHECK_LENGTH = 99
}
