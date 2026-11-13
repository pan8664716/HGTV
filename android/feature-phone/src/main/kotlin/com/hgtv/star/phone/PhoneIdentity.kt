// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.hgtv.star.shared.data.Library
import com.hgtv.star.shared.data.registrationPending
import com.hgtv.star.shared.data.PlaybackCapability
import com.hgtv.star.shared.data.SessionBootstrap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * 把**本机身份与会话**灌进进程级仓库——电视端 `TvMainActivity.applyAccount()` 的手机版。
 *
 * ## 为什么必须有这一步
 *
 * 仓库（`ContentRepository`）是进程级单例，刚建出来时是一台「裸设备」：没有注册过的
 * `device_id`、没有账号凭据、没有注册下发的 Cookie。电视端每次读完书库都会调一次
 * `applyAccount()` 把它们灌回去；手机端早先**一次都没调**，后果是三件事同时不成立：
 *
 * * `repository.signedIn` 永远是 false → `PhoneAccountSync` 的收藏 / 进度 / 历史
 *   全部走「没登录就静默跳过」那条分支。用户明明登录了，手机上看的十集一条都没上云；
 * * App 搜索按匿名设备请求，未注册设备回 `200 + 0 字节`，界面上就是「搜不到」；
 * * 播放取地址按陌生设备计额度，容易撞限流（`101000 服务异常`）。
 *
 * ## 什么时候调
 *
 * 书库读完那一刻（[PhoneMainActivity]）、进「我的」页之前（[com.hgtv.star.phone.page.PhoneMinePage]）、
 * 登录成功与退出登录之后（[PhoneAccountActivity]）。函数本身是**幂等**的：
 * 只是把几份内存值抄进仓库，重复调没有副作用。
 */
internal object PhoneIdentity {

    /** 正在后台注册设备的标记：注册是网络请求，同一时刻只该有一次。 */
    @Volatile private var registering = false
    @Volatile private var registrationLatch: CountDownLatch? = null
    private val registrationListeners =
        CopyOnWriteArrayList<(com.hgtv.star.shared.data.DeviceRegistration.Outcome) -> Unit>()
    /** 播放能力只探一次（`MediaCodecList` 是 Binder 调用，电视上实测能卡 1–3 秒）。 */
    @Volatile private var capabilityApplied = false

    /**
     * 把书库里的身份 / 凭据 / 注册 Cookie 灌进仓库。
     *
     * 书库没读完时直接返回：那时 `deviceIdentity()` / `account()` 读到的都是空表，
     * 灌进去等于把仓库清空一次。
     */
    fun apply(context: Context, lib: Library) {
        if (!lib.isLoaded) return
        val app = context.applicationContext
        val repo = PhoneServices.repository(app)
        // 六项身份 / 凭据 / Cookie 的装配只有一份（`:shared` 的 `IdentityBinder`）：
        // 电视端 `applyAccount()` 用的也是它，两端不会因为漏抄一项而只在一边出问题。
        com.hgtv.star.shared.data.IdentityBinder.bind(lib, repo)

        if (com.hgtv.star.shared.BuildConfig.DEBUG) {
            android.util.Log.i(
                "HGTV-PHONE",
                "identity signedIn=${repo.signedIn} device=${repo.deviceIdentity.isNotEmpty()}" +
                    " token=${!repo.sessionToken.isNullOrBlank()}"
            )
        }
        ensureRegistered(app, lib, repo)
        applyPlaybackCapability(app, lib)
    }

    /**
     * 等 [apply] 触发的后台注册尝试结束。
     *
     * 只等“尝试结束”，不要求成功：注册失败也必须放行首屏，否则断网时会永久转圈。
     */
    fun awaitRegistration(timeoutMs: Long = 20_000L): Boolean {
        return registrationLatch?.await(timeoutMs, TimeUnit.MILLISECONDS) ?: true
    }

    /**
     * 注册尝试结束后在主线程回调；返回注销函数。
     *
     * 首屏不再等注册，因此推荐页可能在匿名身份上先发过一页请求。注册完成后
     * 页面据此只补拉空列表，不打断已经恢复出来的缓存或正在播放的内容。
     */
    fun onRegistrationFinished(
        listener: (com.hgtv.star.shared.data.DeviceRegistration.Outcome) -> Unit,
    ): () -> Unit {
        registrationListeners += listener
        return { registrationListeners -= listener }
    }

    /**
     * 确保本机有一台**平台注册过的**设备；没有就在后台注册一台并落盘。
     *
     * 注册是匿名的（不需要账号 / 密码 / 短信），一次请求即可。失败不打扰用户：
     * 搜索会报「接口暂无响应」、播放会报「暂不可用」，下次启动再试（与电视端同一条策略）。
     */
    private fun ensureRegistered(
        context: Context,
        lib: Library,
        repo: com.hgtv.star.core.ContentRepository,
    ) {
        if (registering) return
        val needsRegistration=com.hgtv.star.shared.data.DeviceRegistration.needsRegister(lib)
        if (!needsRegistration) return
        registering = true
        val latch=CountDownLatch(1)
        registrationLatch=latch
        Thread({
            val outcome = runCatching {
                com.hgtv.star.shared.data.DeviceRegistration.registerOnce(
                    library = lib,
                    repository = repo,
                    force = lib.registrationPending(),
                )
            }.getOrElse { com.hgtv.star.shared.data.DeviceRegistration.Outcome.FAILED }
            registering = false
            latch.countDown()
            notifyRegistrationFinished(outcome)
            when (outcome) {
                com.hgtv.star.shared.data.DeviceRegistration.Outcome.REGISTERED -> {
                    android.util.Log.i(
                        "HGTV-PHONE",
                        "device registered id=${lib.deviceIdentity()["device_id"]}",
                    )
                    // 首屏很可能已经用「没注册」的身份请求过了（App 搜索会空响应）：
                    // 注册一落地就通知界面按新身份重取一次。
                    com.hgtv.star.shared.debug.DebugLog.record(
                        "设备",
                        "注册完成 · 已换用新身份",
                        force = true,
                    )
                }
                com.hgtv.star.shared.data.DeviceRegistration.Outcome.FAILED ->
                    com.hgtv.star.shared.debug.DebugLog.record("设备", "注册失败 · 下次启动重试", force = true)
                com.hgtv.star.shared.data.DeviceRegistration.Outcome.ALREADY -> Unit
            }
        }, "hgtv-phone-register").start()
    }

    private fun notifyRegistrationFinished(
        outcome: com.hgtv.star.shared.data.DeviceRegistration.Outcome,
    ) {
        if(registrationListeners.isEmpty()) return
        Handler(Looper.getMainLooper()).post {
            registrationListeners.forEach { listener ->
                runCatching { listener(outcome) }
            }
        }
    }

    /**
     * 按这台设备的解码能力给一个**默认清晰度**（只对还没自己选过档位的用户生效）。
     *
     * 与电视端 `applyPlaybackCapability` 同一条规则：没有 HEVC 硬解的设备默认 1080
     * 只会让每次起播都先失败一次再降档（白等一轮 + 一次黑屏）。用户选过就不动他的设置。
     */
    private fun applyPlaybackCapability(context: Context, lib: Library) {
        if (capabilityApplied) return
        if (lib.qualityChosenByUser()) { capabilityApplied = true; return }
        capabilityApplied = true
        Thread({
            runCatching {
                val quality = PlaybackCapability.defaultQuality(context)
                if (quality > 0 && !lib.qualityChosenByUser()) lib.maxQuality = quality
                // 「有没有 HEVC 硬解」一并在这里探好（同一条后台线程）：错误页要用它决定
                // 给不给「切换为软解」，而那一刻不能再去扫 Binder。
                PlaybackCapability.warmHardwareHevc(context)
            }
            capabilityApplied = false
        }, "hgtv-phone-capability").start()
    }
}
