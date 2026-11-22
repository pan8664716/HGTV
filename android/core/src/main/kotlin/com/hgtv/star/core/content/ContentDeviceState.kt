// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import com.hgtv.star.core.session.CookieRecord

internal fun ContentRepository.clearDeviceIdentityImpl() {
    deviceIdentity=emptyMap()
    registrationCookies=emptyMap()
    appSearchAlive=null
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
