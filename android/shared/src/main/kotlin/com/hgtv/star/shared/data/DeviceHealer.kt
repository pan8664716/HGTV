// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.hgtv.star.core.AppConfig
import com.hgtv.star.core.ContentRepository
import com.hgtv.star.core.restoreDeviceCookies
import com.hgtv.star.shared.debug.DebugLog
import com.hgtv.star.shared.debug.Diagnostics
import java.util.concurrent.Executors

/**
 * 设备身份自愈：**服务端不认这份身份**时，换一台设备重注册。电视与手机共用这一份。
 *
 * ## 为什么必须共用
 *
 * 电视端原先自己写了一套（`TvMainActivity.healDeviceIdentity`），手机端一套都没有。
 * 结果是同一个故障：电视上搜索会自己恢复、手机上永远搜不出来，只能「清空缓存」。
 * 判定、预算、线程切换、落盘顺序（先转正候选档案、再落身份）全部收在这里，
 * 两端只负责「成功之后做什么」——电视重取这一页、手机重新起播。
 *
 * ## 两种触发，都只有服务端能给出
 *
 * 1. **服务端明确不认**：搜索回 `200 + 0 字节`
 *    （[com.hgtv.star.core.DeviceNotRegisteredException]）或播放模型回 `101000`。
 *    此时换一套身份重注册（新随机种子 → 新档案 → 匿名注册）。
 * 2. **本机还没有身份**（重装后档案丢了、上次注册失败）：补一次**普通**注册，
 *    **不换设备**——那会把用户变成一台新机器。
 *
 * 升级、换账号、清缓存都不换设备：平台认设备靠注册下发的 `install_id` / `sessionid`
 * Cookie，换 `cdid` 本身没有收益，只会让账号被当成陌生机器。
 *
 * ## 两道闸
 *
 * * 同一次拒绝只处置一次（[ContentRepository.deviceRejected] 计数，比已处理过的才动）；
 * * 一次进程最多 [LIMIT] 次、两次之间至少隔 [GAP_MS]——换设备要发一次注册请求，
 *   服务端真挂了的时候不能变成死循环。
 */
object DeviceHealer {

    /** 一次进程最多自愈几次。 */
    const val LIMIT = AppConfig.Account.DEVICE_HEAL_LIMIT

    /** 两次自愈之间的最小间隔。 */
    const val GAP_MS = AppConfig.Account.DEVICE_HEAL_GAP_MS

    private val lock = Any()
    private var tried = 0
    private var at = 0L
    /** 已经处置过的「被拒」计数（见 [ContentRepository.deviceRejected]）。 */
    private var handled = 0

    /**
     * 注册是**阻塞**的网络请求，而且同一时刻只该有一次：单线程队列天然把并发收成一次。
     * 页面上的「重试」连点、搜索与播放同时被拒，都会排进这一条线程。
     */
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "hgtv-device-heal") }
    /** 结果回主线程：两端的收尾（重取页面 / 重新起播）都必须在主线程做。 */
    private val main = Handler(Looper.getMainLooper())

    /**
     * 现在**会不会**接手（只判，不占预算）。
     *
     * 只给 [healAsync] 用：两端都要**同步**拿到「接不接手」才能决定要不要显示错误界面，
     * 而注册本身是阻塞的。做成私有是为了不让调用方绕过 [healAsync] 在主线程上直接调 [heal]。
     */
    private fun wouldHeal(
        library: Library?,
        repository: ContentRepository,
        forceRotate: Boolean,
    ): Boolean {
        if (library == null || !library.isLoaded) return false
        // 本机根本没有身份：那是「还没注册」，不是「被拒」，同样值得补一次（见 [heal]）。
        val registered = library.deviceRegistered()
        if (registered && !forceRotate && repository.deviceRejected <= 0) return false
        synchronized(lock) {
            if (registered && !forceRotate && repository.deviceRejected <= handled) return false
            val now = SystemClock.elapsedRealtime()
            return tried < LIMIT && (at == 0L || now - at >= GAP_MS)
        }
    }

    /**
     * 自愈一次（**阻塞**）。
     *
     * 只有两处会调它：`AppRuntime` 接的那个搜索钩子（`ContentRepository.search` 在
     * 后台线程上直接调），以及 [healAsync] 的工作线程。做成 `internal` 是为了让
     * `:feature-*` 拿不到它——页面层只能走 [healAsync]，避免在主线程上做网络请求。
     *
     * @return true = 已经拿到一份可用的身份，调用方可以直接重试原请求。
     */
    internal fun heal(
        library: Library?,
        repository: ContentRepository,
        reason: String,
        forceRotate: Boolean = false,
    ): Boolean {
        if (library == null || !library.isLoaded) return false
        val registered = library.deviceRegistered()
        if (registered && !forceRotate && repository.deviceRejected <= 0) return false
        // 先占预算再动网络：两个线程同时进来时只有一个能过，另一个直接退出。
        synchronized(lock) {
            if (registered && !forceRotate && repository.deviceRejected <= handled) return false
            val now = SystemClock.elapsedRealtime()
            if (tried >= LIMIT || (at > 0 && now - at < GAP_MS)) return false
            if (registered) handled = repository.deviceRejected
            tried++
            at = now
        }
        if (!registered) {
            // 还没有身份：补一次普通注册即可，**不要**换设备——那会把用户变成一台新机器。
            val outcome = runCatching {
                DeviceRegistration.registerOnce(
                    library = library,
                    repository = repository,
                    force = true,
                    regenerate = false,
                    stage = "device_heal_register",
                    detail = reason,
                )
            }.getOrElse { DeviceRegistration.Outcome.FAILED }
            return outcome == DeviceRegistration.Outcome.REGISTERED
        }
        Log.w("HGTV", "Device identity rejected ($reason); rebuilding (try=$tried)")
        val outcome = runCatching {
            // `regenerate = true`：新随机种子 → 新档案 → **匿名**注册（见 registerDevice）。
            // 带 Cookie 的注册服务端只会回同一个 device_id，等于白换。
            DeviceRegistration.registerOnce(
                library = library,
                repository = repository,
                force = true,
                regenerate = true,
                stage = "device_heal",
                detail = reason,
            )
        }.getOrElse { DeviceRegistration.Outcome.FAILED }
        if (outcome == DeviceRegistration.Outcome.FAILED) {
            Log.w("HGTV", "Device identity rebuild failed; keeping the old one")
            return false
        }
        // 身份与 Cookie 都已由 `registerOnce` 落盘并交给仓库；这里只保证内存里那一份是新的。
        repository.deviceIdentity = library.deviceIdentity()
        runCatching { repository.restoreDeviceCookies(library.registrationCookies()) }
        return true
    }

    /**
     * 后台自愈一次，结果回主线程。
     *
     * 这是两端**唯一**该调用的入口：判定、线程切换、诊断日志都在里面，
     * 调用方只写「成功之后做什么」。
     *
     * @param onResult 主线程回调。true = 已经拿到可用的新身份，可以直接重试原请求；
     *   false = 没修成（预算用尽 / 注册失败 / 本机没读到书库），调用方照常走错误分支。
     * @return true 表示**已经接手**（回调一定会来）；false 表示当场就判定不该动，回调不会来。
     */
    fun healAsync(
        library: Library?,
        repository: ContentRepository,
        reason: String,
        forceRotate: Boolean = false,
        onResult: (Boolean) -> Unit,
    ): Boolean {
        if (!wouldHeal(library, repository, forceRotate)) return false
        val firstTime = library?.deviceRegistered() != true
        DebugLog.record(
            "设备",
            if (firstTime) "$reason · 本机还没有身份，补一次注册"
            else if (forceRotate) "$reason · 换一台设备重试"
            else "$reason · 服务端不认这份身份，重建一次",
            force = true,
        )
        Diagnostics.warn(
            "device_identity",
            "设备身份自愈 reason=$reason firstTime=$firstTime force=$forceRotate " +
                "rejected=${repository.deviceRejected}",
        )
        worker.execute {
            val healed = heal(library, repository, reason, forceRotate)
            main.post {
                DebugLog.record(
                    "设备",
                    if (healed) "身份已就绪 · 重试原请求"
                    else "身份自愈失败 · 继续用旧身份",
                    force = true,
                )
                onResult(healed)
            }
        }
        return true
    }
}
