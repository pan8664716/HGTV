// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data

import com.hgtv.star.core.ContentRepository
import com.hgtv.star.shared.debug.Diagnostics

/**
 * 设备注册（匿名）的**唯一实现**：电视与手机共用。
 *
 * ## 为什么抽出来
 *
 * 「本机这台设备在平台上注册过没有」决定了三件事能不能成立：搜索出不出数据、
 * 播放取不取得到地址、弹幕有没有一条。这段逻辑原先只有电视端有（`registerDeviceOnce`），
 * 手机端是后来补的一个简化版——只判「有没有身份」，于是下面三种情况在手机上都得不到处理：
 *
 * * 旧版本用随机档案注册过、这一版把档案换成了派生值 → 身份与档案对不上，
 *   平台按陌生设备处理（搜索 `200 + 0 字节`、播放 `110001`）；
 * * 上一次换新失败（无网络 / 被风控）→ 本机记着「待换新」，却没有人再试；
 * * 注册成功之后必须**先转正候选档案、再落身份**，顺序反了下次启动又会判成对不上。
 *
 * ## 换新是「先注册、成功才替换」
 *
 * 注册之前**绝不先抹掉旧身份**：失败时旧身份继续可用，用户仍然能看片；
 * 只在失败时记一个待重试标记，下次启动 / 下次取播放地址前再试。
 */
object DeviceRegistration {

    /** 一次注册尝试的结果。调用方据此决定要不要刷新界面 / 记现场。 */
    enum class Outcome {
        /** 本来就已经注册好，什么都没做。 */
        ALREADY,

        /** 这一次注册成功，身份已落盘。 */
        REGISTERED,

        /** 这一次失败了（旧身份仍在，已记待重试）。 */
        FAILED,
    }

    /**
     * 现在需不需要注册。
     *
     * 三个条件任一成立就要：档案被重建过、上一次换新失败过、或者本机根本没有身份。
     */
    fun needsRegister(library: Library): Boolean {
        if (library.consumeProfileRebuilt()) return true
        if (library.registrationPending()) return true
        return !library.deviceRegistered()
    }

    /**
     * 注册一次（**阻塞**，调用方负责放到 IO 线程）。
     *
     * @param force true = 无条件换一套新身份（档案重建之后必须这么做）。
     * @param detail 现场前缀（各端自己拼：机型、页面、账号态）。只进诊断，不影响判定。
     */
    fun registerOnce(
        library: Library,
        repository: ContentRepository,
        force: Boolean = false,
        stage: String = "device_register",
        detail: String = "",
    ): Outcome {
        if (!force && library.deviceRegistered()) return Outcome.ALREADY
        // 有待换的候选档案时按候选注册：平台才会把新身份绑在新档案上，
        // 而当前请求仍用旧档案 + 旧身份继续可用。
        val pendingProfile = library.hasPendingProfile()
        // 换新时不带旧身份：带着它，平台会原样回同一对 `device_id` / `iid`。
        val registered = repository.registerDevice(
            library.registrationProfile(),
            withIdentity = !pendingProfile,
        ) ?: run {
            // 失败原因从仓库的 `lastRegisterError` 拿：`registerDevice` 的契约是
            // 「失败返回 null」，异常在它内部就被吃掉了。
            val problem = repository.lastRegisterError
            Diagnostics.fail(
                stage,
                "设备注册失败",
                if (detail.isBlank()) Diagnostics.chainOf(problem)
                else "$detail cause=${Diagnostics.chainOf(problem)}",
                problem,
            )
            // 记一个待重试标记：下次启动 / 下次取播放地址前会再试一次换新。
            library.markRegistrationPending()
            return Outcome.FAILED
        }
        library.clearRegistrationPending()
        // 先转正候选档案、再落身份：顺序反了的话 `saveDeviceIdentity` 会把旧档案的指纹
        // 写进去，下一次启动就判成「身份与档案对不上」又去重注册。
        if (pendingProfile) library.commitPendingProfile()
        commit(library, repository, registered)
        return Outcome.REGISTERED
    }

    /**
     * 把一次注册结果**落盘并交给仓库**：身份、注册 Cookie、仓库里那一份身份。
     *
     * 单独抽出来是因为登录流程（电视的 `registerForPhoneLogin`、手机的
     * `registerForLogin`）也要做同一件事：那条路上的注册是**按登录账号的档案**发的，
     * 结果同样必须先落盘、再交给仓库，否则下一次启动会判成「身份与档案对不上」。
     *
     * 顺序不能反：`saveDeviceIdentity` 会把**当前档案**的指纹一起写进去，
     * 所以调用方必须在调用它之前把档案换好（候选档案转正见 [registerOnce]）。
     */
    fun commit(
        library: Library,
        repository: ContentRepository,
        registered: com.hgtv.star.core.DeviceRegister.Registered,
    ) {
        library.saveDeviceIdentity(registered.deviceId, registered.installId)
        repository.deviceIdentity = library.deviceIdentity()
        // 注册下发的 Cookie（`install_id` / `ttreq` / `store-region`）落盘：
        // 它们原本只活在这个进程的内存里，重启后第一条请求就会变回「裸设备」。
        val cookies = repository.deviceCookiesSnapshot()
        if (cookies.isNotEmpty()) {
            library.saveRegistrationCookies(cookies)
            SessionBootstrap.sessionStore?.saveDeviceCookies(cookies)
        }
    }
}
