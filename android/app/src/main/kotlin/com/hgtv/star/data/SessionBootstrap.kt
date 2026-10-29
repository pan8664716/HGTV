// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.data

import android.content.Context
import com.hgtv.star.core.session.SessionBridge
import com.hgtv.star.core.session.SessionClients
import com.hgtv.star.core.session.SessionHeaders
import com.hgtv.star.core.session.SessionKeeper

/**
 * 会话装配。`HgtvApplication.onCreate` 里调一次。
 *
 * 三件事：
 * 1. 建 [AppSessionStore]（读写 `native-session-v1`）并交给 [SessionBridge]；
 * 2. 建心跳（[SessionKeeper]）并接到 [SessionBridge]；
 * 3. 留一个外部镜像回调，由 `Library` 挂上（重装后靠镜像认回设备与会话）。
 *
 * 为什么 `OkHttpClient` 不由这里建：`ContentRepository` 的默认参数
 * （`val http: OkHttpClient = defaultHttpClient()`）在对象构造时就建 client，
 * 而工程里有四处 `ContentRepository(...)`。只要拦截器挂在 `defaultHttpClient()` 里，
 * 那四处自动都带上会话，不必改调用点。
 */
object SessionBootstrap {

    @Volatile private var keeper: SessionKeeper? = null
    @Volatile private var store: AppSessionStore? = null
    /** 建 keeper 用的日志出口：后台线程要用，因此从 [init] 的入参抄一份。 */
    @Volatile private var logSink: (String) -> Unit = {}
    /**
     * keeper 还在后台线程上建、这期间有人调过 [onAppStart] 的记账位。
     *
     * 见 [init]：建心跳 client 要解系统根证书表（首次 2~3 秒），因此 keeper 不能在主线程建。
     * 建好那一刻如果这个位是 `true`，补一次 `onAppStart(true)`——否则「冷启动那次 boot 心跳」
     * 会因为「调的时候 keeper 还是 null」被静默吞掉，登录态要等十分钟后的下一次心跳才确认。
     */
    @Volatile private var pendingStart = false
    /** 上面那一次要不要按「必须被服务端确认一次」发（登录成功那一刻传 `true`）。 */
    @Volatile private var pendingForce = false
    /** 建 keeper 的线程是否还在跑：`shutdown()` 之后它若才建完，要当场收掉。 */
    @Volatile private var stopped = false

    /**
     * 心跳用的设备档案。`Application.onCreate` 时还没有登录凭据，先用 SP 里那份；
     * 之后 [updateDevice] 换掉即可——[SessionKeeper] 持的是 lambda，每次请求现读。
     */
    @Volatile private var device: Map<String,String> = emptyMap()

    val sessionStore: AppSessionStore? get() = store

    /** 外部镜像写入（`/sdcard/.hgtv/`）。由 `Library` 在构造时挂上。 */
    @Volatile var mirror: ((String,String?) -> Unit)? = null
    fun attachMirror(block: (String,String?) -> Unit) { mirror = block }

    /** 设备档案有变化（注册成功 / 导入凭据 / 换账号）时调一次。 */
    fun updateDevice(profile: Map<String,String>) { device = profile.filterValues { it.isNotBlank() } }

    private val headers: () -> Map<String,String> = { SessionHeaders.build(device) }

    fun init(
        context: Context,
        store: AppSessionStore,
        log: (String) -> Unit = {},
        onExpired: (String) -> Unit = {}
    ) {
        this.store = store
        device = store.deviceProfile
        SessionBridge.state = store
        SessionBridge.log = log
        SessionBridge.onExpired = onExpired
        SessionBridge.onTokenChanged = { token -> log("token rotated len=${token.length}") }
        logSink = log
        stopped = false
        // **不能在主线程建**：`SessionClients.forBeat()` 会建一个 OkHttpClient，
        // 而建 client 要解析系统根证书表（150 张 PEM，实测 2.5~3.0 秒）。
        // 这一段原先在 `Application.onCreate` 里同步跑，正是冷启动里最贵的那 3 秒
        // （2026-10-27 线程栈：main 停在 `DerValue.toByteArray`）。
        Thread({ buildKeeper() }, "hgtv-session-init").start()
    }

    /** 在后台线程上建心跳（见 [init]）；建好之前 [onAppStart] 只记账。 */
    private fun buildKeeper() {
        runCatching {
            val created = SessionKeeper(
                client = SessionClients.forBeat(),
                deviceHeaders = headers,
                onExpired = { reason -> SessionBridge.onExpired?.invoke(reason) },
                log = logSink
            )
            // 建的过程中用户已经退出（`shutdown()`）：不装配，当场收掉。
            if(stopped) { created.shutdown(); return }
            keeper = created
            if(pendingStart) {
                val force = pendingForce
                pendingStart = false
                pendingForce = false
                created.onAppStart(force)
            }
        }.onFailure { problem ->
            logSink("会话保活初始化失败 ${problem.javaClass.simpleName} ${problem.message}")
        }
    }

    /**
     * 冷启动 / 登录成功后调（内部幂等）。
     *
     * @param force 登录成功那一刻传 `true`（新会话必须被服务端确认一次）；
     *   冷启动传默认值——会话还很久才到期时**不**额外发请求，只在快到期 / 缺 token 时补一发。
     */
    fun onAppStart(force: Boolean = false) {
        val active = keeper
        // keeper 还没建好（后台线程正在解系统根证书表）：记账，建好那一刻补发。
        // **记全部调用**，不只是 force：冷启动那次 boot 心跳走的是默认值，
        // 漏掉它等于「打开应用后十分钟内登录态都不被确认」。
        if(active == null) {
            pendingStart = true
            if(force) pendingForce = true
            return
        }
        active.onAppStart(force)
    }

    /** 每个 Activity 的 `onResume` 调：补一次心跳 + 重排 10 分钟。 */
    fun onForeground() { keeper?.onForeground() }

    /**
     * 外部镜像里**刚补回会话**时调（重装 / 清除数据后的第一次启动）。
     *
     * 为什么需要：镜像读盘在后台线程，可能比 `MainActivity` 那次 [onAppStart] 晚一步——
     * 那一次判成「未登录」直接返回，之后的 `onResume` 也早已发生过，于是本轮进程再也不会有心跳。
     * 这里在恢复完成的那一刻补一次；`onAppStart` 自己会判「会话是否还新鲜」，不会多打请求。
     */
    fun onSessionRestored() { keeper?.onAppStart() }

    /** 用户点「退出登录」时先调这里，再 `Library.clearAccount()`。 */
    fun onLogout() { keeper?.onLogout() }

    /** 长连接推来会话变更时调（可选）。 */
    fun onFrontierMessage(raw: String) { keeper?.onFrontierMessage(raw) }

    /**
     * 停心跳。用于进程退出，以及「清空应用缓存」——后者从点下确认到进程被杀之间还有约 1 秒，
     * 心跳正好在这段时间里回写 `native-session-v1`（cookie / token / last-beat-at），
     * 会把它刚删掉的文件原样写回来。
     *
     * 停完把 [keeper] 置空：`onForeground()` / `onAppStart()` 之后都变成空操作，
     * 不必依赖「用户不会再按一下」这种假设。
     */
    fun shutdown() {
        stopped = true
        pendingStart = false
        keeper?.shutdown()
        keeper=null
    }
}
