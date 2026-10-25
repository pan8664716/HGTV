// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core.session

import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 心跳保活。对齐原版：
 *
 * * `com/ss/android/token/b.java` —— `sendEmptyMessageDelayed(1000, 600000)` 的 10 分钟循环；
 * * `com/ss/android/token/c.java` —— 成功 / 失败**都续期**，只有 `session_expired` 才清凭证；
 * * `com/bytedance/sdk/account/impl/i.java` —— 账号 SDK 那条 `account/info` 轮询；
 * * `com/bytedance/sdk/account/impl/b0.java` —— frontier 主动失效通道。
 *
 * ## 为什么不用 `android.os.Handler`
 *
 * `android/core` 是 `kotlin("jvm")` 纯 Kotlin 模块，编译期没有 `android.*`；而且 Handler 的
 * `postDelayed` 在 Doze 下会被整体推迟。这里用 `ScheduledExecutorService`（后台单线程）。
 *
 * ## 三条铁律（缺一条就会「用一阵子掉登录」）
 *
 * 1. **失败也续期**：网络异常、5xx、400、404 一律不清凭证，10 分钟后接着打；
 * 2. **只认 `data.error_name == "session_expired"`**：过期时 HTTP 状态码仍是 200；
 * 3. **token 与 cookie 分开**：`X-Tt-Token` 只放 token，`Cookie` 只放 cookie。
 */
class SessionKeeper(
    private val client: OkHttpClient,
    /** 设备档案头（`User-Agent` / `Lc` …），与业务请求同一份。 */
    private val deviceHeaders: () -> Map<String,String> = { emptyMap() },
    /** 确认失效时回调（清 UI / 广播），由装配层接。 */
    private val onExpired: (reason: String) -> Unit = {},
    private val log: (String) -> Unit = {}
) {
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task,"hgtv-session-beat").apply { isDaemon = true }
        }

    @Volatile private var tick: ScheduledFuture<*>? = null
    @Volatile private var selfCheck: ScheduledFuture<*>? = null
    private val beatInFlight = AtomicBoolean(false)
    private val infoInFlight = AtomicBoolean(false)
    @Volatile private var started = false
    /** 上一次真正发出去的心跳时刻（`elapsedRealtime` 毫秒）：回前台的补发按它节流。 */
    @Volatile private var lastBeatAt = 0L

    // ---------------- 生命周期 ----------------

    /**
     * 冷启动 / 登录成功后调：`account/info(scene=boot)` + `beat(scene=boot)` + 60 秒自检，
     * 然后排上 10 分钟循环。
     *
     * 登录成功后再调一次是**有意的**：新会话需要立刻被服务端确认一次（导入的 cookie
     * 更是只有 cookie、要先用这次响应把 `X-Tt-Token` 换回来）。
     *
     * **不是每次都打**：会话本身的有效期很长（实测 `sid_guard` 的 `max-age` 是 60 天，
     * 每次心跳都会重新签发一遍）。只有三种情况才补这一发（见 [needsRenewal]）：手上没有 token、
     * 距上次成功心跳超过半小时（应用关了一阵 / 待机很久）、`sid_guard` 快到期。
     * 几分钟内重复打开电视不会产生额外请求；打开之后的持续续期由 10 分钟循环负责。
     *
     * @param force 刚登录成功时传 `true`：新会话要被服务端确认一次（那一次的响应才会把
     *   `sid_guard` / `X-Tt-Token` 换成本次登录的那一份）。
     */
    fun onAppStart(force: Boolean = false) {
        if(!SessionBridge.signedIn()) return
        started = true
        if(force || needsRenewal()) {
            accountInfo(SCENE_BOOT)
            beat(SCENE_BOOT)
        } else {
            log("session healthy (last beat ${hoursSinceBeat()}h ago), skip boot beat")
        }
        scheduleSelfCheck()
        start()
    }

    /**
     * 回前台：补一次 `polling` + 重排 10 分钟。
     *
     * 为什么必须有：设备休眠 / 厂商省电会让后台定时整体推迟，后台待两小时醒来时上一次心跳
     * 可能已隔了几小时。`Activity.onResume` 里补一发，空窗就收敛到「回前台立即续上」。
     */
    fun onForeground() {
        if(!SessionBridge.signedIn()) return
        // 本轮进程还没起过心跳：`onAppStart` 当时判成「未登录」而直接返回了（典型场景是
        // 重装 / 清除数据后的第一次启动——外部镜像在后台线程才恢复完，比 `onAppStart` 晚一步）。
        // 这里补一次 `onAppStart` 而不是直接 `beat(POLLING)`：冷启动那一发必须是 boot 场景，
        // 「会话还新鲜就别打」的判据也只在那里；它内部会把 [started] 置位并排上 10 分钟循环。
        // 递归是安全的：`onAppStart` 只在真的登录着时才置位，不会再回到这里。
        if(!started) { onAppStart(); return }
        // 节流：一次 onResume 补一发是「填掉后台空窗」，用户来回切页签不该按秒打接口。
        if(System.nanoTime()/1_000_000 - lastBeatAt < FOREGROUND_MIN_GAP_MS) {
            ensureScheduled()
            return
        }
        started = true
        // 会话还很久才到期（且 token 在手）时不必补这一发：10 分钟循环到位后自然会续。
        if(needsRenewal()) {
            beat(SCENE_POLLING)
        } else {
            log("session healthy (last beat ${hoursSinceBeat()}h ago), skip foreground beat")
            ensureScheduled()
        }
    }

    /** 用户主动退出：停循环 + 清会话（`Library.clearAccount` 负责清外部镜像）。 */
    fun onLogout() {
        stop()
        started = false
        SessionBridge.pendingExpiry = null
        SessionBridge.clearSession()
    }

    /** 从"现在"起重新计时；重复调用安全（先取消旧任务再排新的）。 */
    @Synchronized
    fun start() {
        if(!SessionBridge.signedIn()) return
        tick?.cancel(false)
        tick = scheduler.schedule({ runBeatTick() },INTERVAL_MS,TimeUnit.MILLISECONDS)
    }

    /**
     * 已经排着下一发就**别动它**，没排才排。
     *
     * 为什么需要：`onForeground` 在「会话健康、不必补发」时如果直接调 [start]，
     * 每 `onResume` 一次就把 10 分钟重新计时一遍——用户频繁切页签 / 开合菜单时，
     * 那条循环会被一直推迟到「最后一次 resume + 10 分钟」，等于心跳停摆。
     */
    @Synchronized
    private fun ensureScheduled() {
        val current = tick
        if(current!=null && !current.isDone && !current.isCancelled) return
        start()
    }

    @Synchronized
    fun stop() {
        tick?.cancel(false)
        tick = null
        selfCheck?.cancel(false)
        selfCheck = null
    }

    /** 进程退出用。 */
    fun shutdown() {
        stop()
        scheduler.shutdownNow()
    }

    // ---------------- 心跳 ----------------

    private fun runBeatTick() {
        expireIfFlagged()
        beat(SCENE_POLLING)
    }

    fun beat(scene: String) = beat(scene, withoutToken = false)

    /**
     * @param withoutToken 这一次**不带** `X-Tt-Token`（只靠 cookie）。
     *
     * 为什么要这个开关：实测平台对「token 与 cookie 不属于同一次签发」的组合会回
     * `session_expired`，**哪怕 cookie 本身完全有效**——同一个 cookie 去掉 token 再打一次
     * 就回 `success` 并下发一枚 355 字符的新 token。本机 SP 里只要残留一枚旧 token
     * （换号、扫码确认时写错、`X-Tt-Token` 被截断），下一次心跳就会被判死、清掉整份凭证，
     * 表现就是「早晨一打开发现掉登录了」。所以 [onResponse] 里收到 `session_expired` 且
     * 手上确实带着 token 时，先去掉 token 重打一次，两次都过期才算真失效。
     */
    private fun beat(scene: String, withoutToken: Boolean) {
        expireIfFlagged()
        if(!SessionBridge.signedIn()) { stop(); return }
        if(!beatInFlight.compareAndSet(false,true)) return
        lastBeatAt = System.nanoTime()/1_000_000
        val first = SessionBridge.state?.firstBeat ?: true
        val url = "$BEAT_BASE?scene=$scene&first_beat=$first"
        client.newCall(beatRequest(url, injectToken = !withoutToken)).enqueue(object: Callback {
            override fun onFailure(call: Call,e: IOException) {
                beatInFlight.set(false)
                // ★ 网络失败不清凭证：原版 c.b() 走的就是这条路，末尾照样续期
                log("beat($scene) net=${e.javaClass.simpleName}")
                finishBeat()
            }

            override fun onResponse(call: Call,response: okhttp3.Response) {
                beatInFlight.set(false)
                val code = response.code()
                val body = runCatching { response.body()?.string().orEmpty() }.getOrDefault("")
                // 只收属于当前会话的 token（判据与拦截器同一条，见 `SessionBridge.decorate`）。
                val sid=SessionBridge.state?.cookieHeader?.let { CookieRecord.value(it,"sessionid") }.orEmpty()
                response.header("X-Tt-Token")?.takeIf { it.isNotBlank() }
                    ?.takeIf { sid.isBlank() || it.length<SessionBridge.TOKEN_CHECK_LENGTH || it.contains(sid) }
                    ?.let { SessionBridge.saveToken(it) }
                runCatching { response.close() }
                if(isSessionExpired(body)) {
                    // 带着 token 被判死时先别清凭证：去掉 token 用 cookie 重打一次。
                    // 平台按「token 与 cookie 必须同源」校验，旧 token 会让**有效的 cookie**
                    // 被判成过期（实测：同 cookie + 陈旧 token → session_expired；
                    // 同 cookie 不带 token → success + 新 token）。这一发重试既判真伪，
                    // 也顺手把新 token 换回来。
                    if(!withoutToken && SessionBridge.state?.token?.isNotBlank()==true) {
                        log("beat($scene) session_expired with token -> retry cookie-only")
                        beatInFlight.set(false)
                        beat(scene, withoutToken = true)
                        return
                    }
                    log("beat($scene) session_expired -> logout")
                    expire("beat")
                    return
                }
                when {
                    code==400 -> log("beat($scene) http 400 (self-check only)")
                    code==404 -> log("beat($scene) http 404 (config off)")
                    code !in 200..299 -> log("beat($scene) http $code")
                    else -> log("beat($scene) http $code")
                }
                SessionBridge.state?.firstBeat = false
                // 记下「最后一次真的打成功」，下次开应用据此决定要不要补一发（见 [needsRenewal]）
                if(code in 200..299) SessionBridge.state?.lastBeatAtMs = System.currentTimeMillis()
                finishBeat()
            }
        })
    }

    private fun finishBeat() {
        if(!SessionBridge.signedIn()) { stop(); return }
        start()
    }

    /**
     * 现在要不要「立刻补一发」。
     *
     * 判据只有三条，任何一条成立就打：
     *
     * 1. **手上没有 token**（导入的 `cookieStore.xml` 只有 cookie）：这一发是唯一能把
     *    `X-Tt-Token` 换回来的途径；
     * 2. **距上次成功心跳超过 [RENEW_INTERVAL_MS]**（从没打过也算）：应用关了一阵子、
     *    电视待机很久，token 该续了；
     * 3. **`sid_guard` 快到期**：剩余不足 [RENEW_BEFORE_MS]。
     *
     * 都不成立说明会话刚续过（`max-age` 是 60 天量级，每次心跳都会重新签发），
     * 这一发纯属多余——用户开个电视不该为此多一次请求。真正的周期续期由 [start] 的
     * 10 分钟循环负责，它只在应用开着时跑。
     */
    private fun needsRenewal(): Boolean {
        val current = SessionBridge.state ?: return true
        // 导入的 `cookieStore.xml` 只有 cookie、没有 token：这一发是唯一能把 token 换回来的途径
        if(current.token.isBlank()) return true
        // 从没在这儿成功打过心跳：先确认一次（同时把 first_beat / token 对齐）
        val last = current.lastBeatAtMs
        if(last<=0L) return true
        // 距上一次成功心跳已经超过 [RENEW_INTERVAL_MS]（应用关了几天 / 待机很久）→ 补一发
        if(System.currentTimeMillis()-last > RENEW_INTERVAL_MS) return true
        // 最后一层保险：`sid_guard` 快到期
        val at = current.expiresAtMs
        return at>0L && (at-System.currentTimeMillis()) < RENEW_BEFORE_MS
    }

    /** 距上一次成功心跳多少小时（日志用；没打过给 -1）。 */
    private fun hoursSinceBeat(): Long {
        val last = SessionBridge.state?.lastBeatAtMs ?: 0L
        return if(last<=0L) -1L else (System.currentTimeMillis()-last)/3_600_000L
    }

    // ---------------- 账号信息 ----------------

    /**
     * `GET /passport/account/info/v2/?scene=…`：把服务端的会话状态同步回来。
     * 判定与 beat 完全相同——只有 `session_expired` 才登出。
     */
    fun accountInfo(scene: String) = accountInfo(scene, withoutToken = false)

    /** @param withoutToken 见 [beat] 的同名参数：去 token 复核一次。 */
    private fun accountInfo(scene: String, withoutToken: Boolean) {
        if(!SessionBridge.signedIn()) return
        if(!infoInFlight.compareAndSet(false,true)) return
        client.newCall(beatRequest("$ACCOUNT_INFO_BASE?scene=$scene", injectToken = !withoutToken)).enqueue(object: Callback {
            override fun onFailure(call: Call,e: IOException) {
                infoInFlight.set(false)
                log("accountInfo($scene) net=${e.javaClass.simpleName}")
            }

            override fun onResponse(call: Call,response: okhttp3.Response) {
                infoInFlight.set(false)
                val body = runCatching { response.body()?.string().orEmpty() }.getOrDefault("")
                val sid=SessionBridge.state?.cookieHeader?.let { CookieRecord.value(it,"sessionid") }.orEmpty()
                response.header("X-Tt-Token")?.takeIf { it.isNotBlank() }
                    ?.takeIf { sid.isBlank() || it.length<SessionBridge.TOKEN_CHECK_LENGTH || it.contains(sid) }
                    ?.let { SessionBridge.saveToken(it) }
                runCatching { response.close() }
                if(isSessionExpired(body)) {
                    // 与 [beat] 同一条理由：旧 token 会让有效 cookie 被判过期，先去掉 token 复核。
                    if(!withoutToken && SessionBridge.state?.token?.isNotBlank()==true) {
                        log("accountInfo($scene) session_expired with token -> retry cookie-only")
                        infoInFlight.set(false)
                        accountInfo(scene, withoutToken = true)
                        return
                    }
                    log("accountInfo($scene) session_expired -> logout")
                    expire("account_info")
                }
            }
        })
    }

    // ---------------- frontier ----------------

    /**
     * 长连接推来「会话可能要失效」时调用：暂停轮询 → 立刻打一次 `scene=frontier` →
     * 3 秒后恢复轮询。没有这条通道也能保活，只是被踢下线最多晚 10 分钟才发现。
     */
    fun onFrontierMessage(raw: String) {
        if(!SessionBridge.signedIn()) return
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if(json.optBoolean("degrade",false)) return
        stop()
        beat(SCENE_FRONTIER)
        scheduler.schedule({ if(SessionBridge.signedIn()) start() },FRONTIER_RESUME_MS,TimeUnit.MILLISECONDS)
    }

    // ---------------- 内部 ----------------

    private fun beatRequest(url: String, injectToken: Boolean = true): Request {
        val builder = Request.Builder()
            .url(url)
            .get()
            .header("X-SS-DP","8662")
            .header("X-Tt-Store-Region",SessionBridge.state?.storeRegion ?: "cn")
            .header("X-Tt-Store-Region-Src","did")
            .header("sdk-version","2")
            .header("passport-sdk-version","5051452")
            .header("Accept","application/json")
        // token 与 cookie 分头送：token 为空（导入的 cookieStore.xml）时**不发**这个头。
        if(injectToken) {
            SessionBridge.state?.token?.takeIf { it.isNotBlank() }?.let { builder.header("X-Tt-Token",it) }
        }
        // 心跳必须同时带设备 cookie（`install_id` / `ttreq`），否则平台认不出这台设备
        SessionBridge.cookieHeaderForBeat().takeIf { it.isNotEmpty() }?.let { builder.header("Cookie",it) }
        deviceHeaders().forEach { (name,value) -> if(value.isNotBlank()) builder.header(name,value) }
        return builder.build()
    }

    /** ★ 唯一判定入口：HTTP 200 + body 里的 `data.error_name`。 */
    private fun isSessionExpired(body: String): Boolean {
        if(body.isBlank()) return false
        val data = runCatching { JSONObject(body).optJSONObject("data") }.getOrNull() ?: return false
        return data.optString("error_name").equals(ERR_SESSION_EXPIRED,ignoreCase = true)
    }

    private fun expire(reason: String) {
        stop()
        started = false
        // 清凭证在这里做（而不是在拦截器里）：此时已确认是 `session_expired`，
        // 且没有下游还在读响应体。
        SessionBridge.clearSession()
        onExpired(reason)
    }

    /**
     * 消费拦截器留下的标记（拦截器在响应体线程上只能标记，见 `SessionBridge.markExpired`）。
     */
    fun expireIfFlagged() {
        val reason = SessionBridge.pendingExpiry ?: return
        SessionBridge.pendingExpiry = null
        if(!SessionBridge.signedIn()) return   // 标记可能是上一次会话留下的
        // 拦截器是在**业务请求**上嗅到 `session_expired` 的，那一条同样可能只是
        // 「token 与 cookie 不同源」造成的误判（见 [beat] 的说明）。这里不直接清凭证，
        // 而是去 token 打一发心跳：真失效时那一发仍会回 `session_expired` 并走 [expire]，
        // 误判时它会把新 token 换回来，会话继续可用。
        if(SessionBridge.state?.token?.isNotBlank()==true) {
            log("expiry flagged ($reason) -> verify with cookie-only beat")
            beat(SCENE_POLLING, withoutToken = true)
            return
        }
        expire(reason)
    }

    private fun scheduleSelfCheck() {
        selfCheck?.cancel(false)
        selfCheck = scheduler.schedule({
            // 登录着但循环没跑起来，就把它拉起来
            if(SessionBridge.signedIn() && tick==null) {
                log("self-check: beat loop not scheduled, restart")
                start()
            }
        },SELF_CHECK_DELAY_MS,TimeUnit.MILLISECONDS)
    }

    companion object {
        /** 原版 `z68.c.f484030d` = 600000。 */
        const val INTERVAL_MS = 600_000L
        /** 原版 `sendEmptyMessageDelayed(2000, 60000)`。 */
        const val SELF_CHECK_DELAY_MS = 60_000L
        const val FRONTIER_RESUME_MS = 3_000L
        /** 回前台的补发节流：一分钟内只补一次。 */
        const val FOREGROUND_MIN_GAP_MS = 60_000L
        /**
         * 「开应用时该不该补一发」的时间阈值：距上一次成功心跳超过 30 分钟才补。
         *
         * 与 10 分钟循环同一个量级：应用关掉 / 待机半小时以上再打开，就补一发把 token 续上；
         * 用户来回切页签、几分钟内重复进「我的」页则不再重复请求。
         */
        const val RENEW_INTERVAL_MS = 30*60_000L
        /**
         * 「快到期」的阈值：剩余不足 3 天才补这一发。
         *
         * 实测 `sid_guard` 的 `max-age` 是 60 天、且**每次心跳都会重新签发**，所以正常情况下
         * 打开应用时剩余时间总有几十天——那就不该为此多发一次请求。留 3 天余量是为了
         * 容忍「用户连着几天不开应用」这种情形。
         */
        const val RENEW_BEFORE_MS = 3*24*3_600_000L

        const val SCENE_BOOT = "boot"
        const val SCENE_POLLING = "polling"
        const val SCENE_FRONTIER = "frontier"
        const val SCENE_WAP = "wap_login"

        const val ERR_SESSION_EXPIRED = "session_expired"

        /** 硬编码在 `TTTokenManager.getHost()` 里，与原版一致。 */
        const val BEAT_BASE = "https://security.snssdk.com/passport/token/beat/v2/"
        const val ACCOUNT_INFO_BASE = "https://security.snssdk.com/passport/account/info/v2/"
    }
}
