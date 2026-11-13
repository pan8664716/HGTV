// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import com.hgtv.star.core.session.CookieRecord

internal fun ContentRepository.clearDeviceIdentityImpl() {
    deviceIdentity=emptyMap()
    registrationCookies=emptyMap()
    appSearchAlive=null
}

/** 记一次「服务端不认这份身份」。身份为空时不计：那是真的还没注册，不是被拒。 */
internal fun ContentRepository.noteDeviceRejectedImpl() {
    if(deviceIdentity["device_id"].isNullOrBlank()) return
    ContentRepository.incrementDeviceRejectedCount()
}

internal fun ContentRepository.deviceCookiesSnapshotImpl(): Map<String,String> =
    LinkedHashMap(registrationCookies)

/** 当前主身份 Cookie 快照；登录后是账号设备 Cookie，未登录时是主设备 Cookie。 */
internal fun ContentRepository.primaryCookiesSnapshotImpl(): Map<String,String> =
    CookieRecord.headerToMap(primaryCookieHeader())

/** 把上次落盘的注册 Cookie 灌回来（启动时调一次即可）。 */
internal fun ContentRepository.restoreDeviceCookiesImpl(cookies: Map<String,String>) {
    val clean=cookies.filterValues { it.isNotBlank() }
    if(clean.isEmpty()) return
    registrationCookies=LinkedHashMap(registrationCookies).apply { putAll(clean) }
}
