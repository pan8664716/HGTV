// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data

import com.hgtv.star.core.OfficialDeviceIds

/**
 * 这一次「换新身份」的注册失败了，下次启动 / 下次取播放地址前再试。
 *
 * 为什么要落盘：注册失败（网络、风控、服务端抖动）时**不能把旧身份清掉**——
 * 清了就变成「设备未注册」，搜索回空响应、播放回 `110001`，用户看到的就是
 * 「升级之后什么都放不了」。因此失败只记一个标记，旧身份继续用，下一次再换。
 */
internal fun Library.markRegistrationPendingImpl() {
    if(identityDiscarding) return
    identitySessionPrefs.edit().putBoolean("registration-pending",true).apply()
}

internal fun Library.clearRegistrationPendingImpl() {
    if(identityDiscarding) return
    identitySessionPrefs.edit().remove("registration-pending").apply()
}

internal fun Library.registrationPendingImpl(): Boolean {
    ensureIdentityLayoutImpl()
    return identitySessionPrefs.getBoolean("registration-pending",false) || pendingProfileImpl()!=null
}

/**
 * 生成一条只用于备用播放设备注册的随机档案。
 *
 * 不改写主设备档案和主身份；返回值只交给持久备用池。
 */
internal fun Library.freshPlaybackBackupProfileImpl(): Map<String,String> {
    return freshDeviceProfileImpl("playback_backup")
}

internal fun Library.freshDeviceProfileImpl(source: String): Map<String,String> {
    val base=deviceProfile()
    if(base.isEmpty()) return emptyMap()
    return LinkedHashMap(base).apply {
        put("cdid",OfficialDeviceIds.cdid())
        put("openudid",OfficialDeviceIds.openudid(null))
        put("openudid-source",source)
        put("clientudid",OfficialDeviceIds.clientudid())
    }
}
