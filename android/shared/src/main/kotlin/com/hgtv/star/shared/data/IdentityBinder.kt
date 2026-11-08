// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data

import com.hgtv.star.core.ContentRepository
import com.hgtv.star.core.session.SessionBridge

/**
 * 把**本机身份与会话**灌进进程级仓库：电视与手机共用这一份。
 *
 * ## 为什么必须有这一步
 *
 * 仓库（`ContentRepository`）是进程级单例，刚建出来时是一台「裸设备」：没有注册过的
 * `device_id`、没有账号凭据、没有注册下发的 Cookie。书库读完那一刻不灌回去，
 * 三件事同时不成立：
 *
 * * `repository.signedIn` 永远是 false → 收藏 / 进度 / 历史全走「没登录就静默跳过」，
 *   用户明明登录了，看的十集一条都没上云；
 * * App 搜索按匿名设备请求，未注册设备回 `200 + 0 字节`，界面上就是「搜不到」；
 * * 播放取地址按陌生设备计额度，容易撞限流（`101000 服务异常`）。
 *
 * ## 为什么抽出来
 *
 * 这六项赋值原先在电视端 `applyAccount()` 里，手机端是后来照抄的一份。抄一份的后果
 * 是可预见的：漏一项（手机端最初就漏了 `sessionToken`）就只在那一端出现「搜索不对」，
 * 而两边的现象描述一模一样，排查时根本分不出是哪一端漏的。
 *
 * 幂等：只是把几份内存值抄进仓库，重复调没有副作用。
 */
object IdentityBinder {

    /**
     * 灌一次。书库没读完时**什么都不做**：那时 `deviceIdentity()` / `account()` 读到的
     * 都是空表，灌进去等于把仓库清空一次。
     */
    fun bind(library: Library, repository: ContentRepository) {
        if (!library.isLoaded) return
        // 设备身份：只有平台注册下发的那一对（没注册过时是空表，不自编随机值）。
        repository.registerProfile = library.deviceProfile()
        repository.deviceIdentity = library.deviceIdentity()
        // 账号凭据：`signedIn` 与所有账号接口都看它。
        repository.credentials = runCatching { library.account() }.getOrNull()?.takeIf { it.configured }
        // 注册下发的 Cookie（`install_id` / `ttreq` / `store-region`）：少了它，
        // 新进程的第一轮请求是「裸设备」，平台按陌生设备处理。
        runCatching { repository.restoreDeviceCookies(library.registrationCookies()) }
        // 拦截器默认只补主设备 Cookie；账号 Cookie 仅由带 `X-HGTV-Identity: account`
        // 的账号页请求 / 会话心跳显式使用。
        SessionBridge.mainDeviceCookies = { library.registrationCookies() }
        // 心跳的设备头要用这台机器的档案（机型 / 系统 / cdid），与业务请求同源。
        runCatching { SessionBootstrap.updateDevice(library.accountDevice()) }
        // 历史遗留的「只给搜索用的口令」：搜索接口认它。
        repository.sessionToken = runCatching { library.searchToken }.getOrDefault("").ifBlank { null }
        // 身份与注册 Cookie 就绪后再刷新 TNC。缓存未过期时这里是空操作，
        // 过期时在后台单飞请求，不阻塞首屏。
        runCatching { repository.refreshTncAsync() }
    }
}
