// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data

import org.json.JSONObject

/**
 * 设备注册下发的 Cookie（`install_id` / `ttreq` / `store-region` …）。
 *
 * 这几个值原本只活在 `ContentRepository` 的内存里，进程一重启就没了，于是每个新进程的
 * 第一轮请求都是「裸设备」——平台按陌生设备处理，风控更严。存下来之后，
 * 下次启动由 `ContentRepository.restoreDeviceCookies` 灌回去。
 */
internal fun Library.registrationCookiesImpl(): Map<String,String> {
    ensureIdentityLayoutImpl()
    restoreFromMirrorForRegistrationCookies()
    val raw = identitySessionPrefs.getString("registered-cookies","").orEmpty()
    if(raw.isBlank()) return emptyMap()
    return runCatching {
        val json = JSONObject(raw)
        buildMap {
            for(key in json.keys()) json.optString(key).takeIf { it.isNotBlank() }?.let { put(key,it) }
        }
    }.getOrDefault(emptyMap())
}

/**
 * 抹掉注册下发的 Cookie。
 *
 * 与 [saveDeviceIdentity] 传空同一个语义：设备身份换了一套，旧 Cookie 里的 `install_id`
 * 指向的是**上一台**设备，留着会让新身份的请求带着两份互相矛盾的设备号。
 */
internal fun Library.clearRegistrationCookiesImpl() {
    if(identityDiscarding) return
    identitySessionPrefs.edit().remove("registered-cookies").commit()
    identityMirror.remove("registered-cookies")
}

internal fun Library.saveRegistrationCookiesImpl(cookies: Map<String,String>) {
    if(identityDiscarding) return
    val clean = cookies.filterValues { it.isNotBlank() }
    if(clean.isEmpty()) return
    val json = JSONObject().apply { clean.forEach { (key,value) -> put(key,value) } }.toString()
    identitySessionPrefs.edit().putString("registered-cookies",json).commit()
    identityMirror.put("registered-cookies",json)
    // 与身份同一次注册的产物：分开落盘就可能出现「device_id 在、install_id 不在」，
    // 那半份身份在服务端等于陌生设备（搜索空响应 / 播放 101000）。
    identityMirror.flush()
}
