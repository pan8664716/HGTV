// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data

/**
 * 这份注册结果是不是**当前版本注册出来的**（缺 `registered-profile-stamp` = 不是）。
 *
 * 用来做一次升级迁移：1.2.3 换了注册线路与上报参数，旧版本注册下来的 `device_id` / `iid`
 * 在内容接口上会被当成陌生设备——搜索回 `200 + 0 字节`、播放回 `101000`，
 * 而「设置 → 清空缓存」之所以能修好，正是因为它把这些数字删了重新注册。
 *
 * 换新是**后台、可失败**的（见 `TvMainActivity.refreshRegistrationIdentity`）：
 * 换新成功才替换身份，失败就继续用旧的那一对，用户不会因此连首页都看不了。
 */
fun Library.registrationNeedsRefresh(): Boolean {
    return registrationNeedsRefreshImpl()
}

/**
 * 作废本机的注册结果（设备身份 + 下发 Cookie + 档案指纹）。
 *
 * 与 [saveDeviceIdentity] 传空同义，但多清一项指纹——三者必须一起走，
 * 少清一项都会让下一次 [deviceRegistered] 判成「还是好的」。
 */
fun Library.invalidateRegistration() {
    invalidateRegistrationImpl()
}

/**
 * 这一次「换新身份」的注册失败了，下次启动 / 下次取播放地址前再试。
 *
 * 为什么要落盘：注册失败（网络、风控、服务端抖动）时**不能把旧身份清掉**——
 * 清了就变成「设备未注册」，搜索回空响应、播放回 `110001`，用户看到的就是
 * 「升级之后什么都放不了」。因此失败只记一个标记，旧身份继续用，下一次再换。
 */
fun Library.markRegistrationPending() {
    markRegistrationPendingImpl()
}

fun Library.clearRegistrationPending() {
    clearRegistrationPendingImpl()
}

fun Library.registrationPending(): Boolean {
    return registrationPendingImpl()
}
