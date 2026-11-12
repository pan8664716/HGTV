// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data

import com.hgtv.star.core.AccountCredentials

/**
 * 账号参数（个人信息页导入的整套登录态）。
 *
 * 与 `searchToken` 同一个文件、同一套约定：**不进备份、不随记录迁移**，
 * 因为它是平台凭据而不是本机偏好。这里存的是 Cookie 串 + 设备参数 + 过期时间。
 */
internal fun Library.accountImpl(): AccountCredentials {
    // 重装后这一步就是把 `sessionid` 那一串捞回来的地方：拿到之后
    // `applyAccount` 会直接把它交给仓库，用户不必再登录一次。
    restoreFromMirrorForAccount()
    // 反过来也要补：老版本装过、镜像里还没有这次会话的，读一次就把它写进镜像，
    // 否则卸载重装丢的就正是这串 `sessionid`。
    mirrorSyncForAccount("account-cookie",identitySessionPrefs.getString("account-cookie","").orEmpty())
    mirrorSyncForAccount("account-device",identitySessionPrefs.getString("account-device","").orEmpty())
    mirrorSyncForAccount("account-expires",identitySessionPrefs.getLong("account-expires",0))
    mirrorSyncForAccount("session-token",identitySessionPrefs.getString("session-token","").orEmpty())
    mirrorSyncForAccount("last-beat-at",identitySessionPrefs.getLong("last-beat-at",0))
    val cookie = identitySessionPrefs.getString("account-cookie", "").orEmpty()
    // 读的时候也校一次：老版本可能已经把一份「不同源」的 token 落盘了（换号 / 重扫码之后
    // 服务端仍在回上一份会话的 token）。这种 token 会让平台把有效会话降级成匿名设备，
    // 因此**读出来就丢掉**，由心跳重新换一份回来。判据与 `SessionBridge.decorate` 同一条。
    val sid = com.hgtv.star.core.session.CookieRecord.value(cookie,"sessionid")
    val storedToken = identitySessionPrefs.getString("session-token", "").orEmpty()
    val token = storedToken.takeIf {
        it.isBlank() || sid.isBlank() || it.length<com.hgtv.star.core.session.SessionBridge.TOKEN_CHECK_LENGTH ||
            it.contains(sid)
    }.orEmpty()
    if(storedToken.isNotBlank() && token.isBlank()) {
        identitySessionPrefs.edit().remove("session-token").remove("ts-sign").apply()
        identityMirror.remove("session-token")
        identityMirror.remove("ts-sign")
    }
    return AccountCredentials(
        cookie = cookie,
        token = token,
        tsSign = identitySessionPrefs.getString("ts-sign", "").orEmpty(),
        deviceId = accountDevice()["device_id"].orEmpty(),
        installId = accountDevice()["iid"].orEmpty(),
        cdid = accountDevice()["cdid"].orEmpty(),
        openudid = accountDevice()["openudid"].orEmpty(),
        expiresAt = identitySessionPrefs.getLong("account-expires", 0),
        device = accountDevice()
    )
}

internal fun Library.saveAccountImpl(credentials: AccountCredentials) {
    if(identityDiscarding) return
    // 换会话（换号 / 重新扫码）时先把**上一份** token 抹掉。
    //
    // 不抹的后果是实测过的：token 与 cookie 不同源时，平台不是忽略 token，而是把整份
    // 有效会话降级成匿名设备（「用户20114780」）——「登录成功但立刻报状态无效」。
    // 这里按 `sessionid` 判是不是同一份会话；同一份会话下 token 为空则保留（空值不该
    // 抹掉好 token，见 `AppSessionStore.saveToken` 的说明）。
    val previousSid = com.hgtv.star.core.session.CookieRecord
        .value(identitySessionPrefs.getString("account-cookie", "").orEmpty(), "sessionid")
    val nextSid = com.hgtv.star.core.session.CookieRecord.value(credentials.cookie.trim(), "sessionid")
    if(previousSid.isNotBlank() && nextSid.isNotBlank() && previousSid!=nextSid) {
        identitySessionPrefs.edit().remove("session-token").remove("ts-sign").apply()
        identityMirror.remove("session-token")
        identityMirror.remove("ts-sign")
    }
    identitySessionPrefs.edit()
        .putString("account-cookie", credentials.cookie.trim())
        .putLong("account-expires", credentials.expiresAt)
        .apply()
    // token 与它自己的签名一起落盘：心跳、拦截器与 `AppSessionStore` 读的是这两个键。
    // 空值不覆盖——一次异常响应不该把好会话的 token 抹掉（见 `AppSessionStore.saveToken`）。
    credentials.token.takeIf { it.isNotBlank() }?.let { token ->
        identitySessionPrefs.edit().putString("session-token",token).apply()
        identityMirror.put("session-token",token)
    }
    credentials.tsSign.takeIf { it.isNotBlank() }?.let { sign ->
        identitySessionPrefs.edit().putString("ts-sign",sign).apply()
        identityMirror.put("ts-sign",sign)
    }
    // 档案单独存一份 JSON：字段比 [AccountCredentials] 那几个多（机型 / 系统 / klink_egdi …），
    // 展开成一条条 pref 不划算，签名要的是整份。
    saveAccountDevice(credentials.device)
    // 会话一有新值就覆盖外部镜像（登录成功 / 导入凭据 / 换号都走这里）：
    // 外部那份**永远是最新的**，重装后捞回来就能直接用，不会拿着过期的会话去请求。
    if(credentials.cookie.isNotBlank()) identityMirror.put("account-cookie",credentials.cookie.trim())
    if(credentials.expiresAt>0) identityMirror.put("account-expires",credentials.expiresAt)
}

internal fun Library.clearAccountImpl() {
    if(identityDiscarding) return
    identitySessionPrefs.edit().remove("account-cookie").remove("account-device")
        .remove("account-expires").remove("session-token").remove("ts-sign")
        .remove("last-beat-at").apply()
    // 外部镜像也要一起抹掉这三项：只清本机的话，**下次启动会把刚退掉的账号又救回来**
    // （用户点了退出登录，重开却还是登录状态）。设备身份不受影响，继续留着。
    mirrorAccountKeysValue.forEach { key -> identityMirror.remove(key) }
    // 账号数据快照跟着一起清：留着它下次登录（甚至是**另一个**账号）就会先把别人的
    // 收藏 / 历史铺出来，再等刷新纠正——那几秒钟界面上是错的内容。
    clearAccountCache()
}
