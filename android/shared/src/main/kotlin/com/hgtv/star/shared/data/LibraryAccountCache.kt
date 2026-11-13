// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data

import com.hgtv.star.core.AccountCacheEntry
import com.hgtv.star.core.AccountData

/** 缓存身份：登录账号优先；未登录但设备已注册时，用主设备身份隔离匿名缓存。 */
internal fun Library.cacheIdentityKeyImpl(): String {
    val cookie=identitySessionPrefs.getString("account-cookie","").orEmpty()
    if(cookie.isNotBlank()) return cookie
    val device=identitySessionPrefs.getString("registered-device-id","").orEmpty()
    val install=identitySessionPrefs.getString("registered-install-id","").orEmpty()
    return if(
        isDeviceNumberForIdentity(device) &&
        isDeviceNumberForIdentity(install)
    ) {
        "device|$device|$install"
    } else {
        ""
    }
}

internal fun Library.cacheIdentityAvailableImpl(): Boolean =
    cacheIdentityKeyImpl().isNotBlank()

internal fun Library.accountCacheImpl(): AccountCacheEntry? {
    settingsState()
    return identityAccountCacheStore.current()
}

internal fun Library.cacheAccountImpl(data: AccountData, now: Long) {
    if(identityDiscarding) return
    if(!cacheIdentityAvailableImpl()) return
    settingsState()
    identityAccountCacheStore.cache(data,now)
}

internal fun Library.clearAccountCacheImpl() {
    if(identityDiscarding) return
    identityAccountCacheStore.clear()
}
