// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data

/**
 * 设备注册下发的 Cookie（`install_id` / `ttreq` / `store-region` …）。
 *
 * 这几个值原本只活在 `ContentRepository` 的内存里，进程一重启就没了，于是每个新进程的
 * 第一轮请求都是「裸设备」——平台按陌生设备处理，风控更严。存下来之后，
 * 下次启动由 `ContentRepository.restoreDeviceCookies` 灌回去。
 */
fun Library.registrationCookies(): Map<String,String> {
    return registrationCookiesImpl()
}

/**
 * 抹掉注册下发的 Cookie。
 *
 * 与 [saveDeviceIdentity] 传空同一个语义：设备身份换了一套，旧 Cookie 里的 `install_id`
 * 指向的是**上一台**设备，留着会让新身份的请求带着两份互相矛盾的设备号。
 */
fun Library.clearRegistrationCookies() {
    clearRegistrationCookiesImpl()
}

fun Library.saveRegistrationCookies(cookies: Map<String,String>) {
    saveRegistrationCookiesImpl(cookies)
}
