// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data

/**
 * 生成一条只用于备用播放设备注册的随机档案。
 *
 * 不改写主设备档案和主身份；返回值只交给持久备用池。
 */
fun Library.freshPlaybackBackupProfile(): Map<String,String> {
    return freshPlaybackBackupProfileImpl()
}

/**
 * 注册成功之后把候选档案转正（档案与种指纹一起换）。
 *
 * **必须与 [saveDeviceIdentity] 一起调用**（先这一条、再那一条）：身份是按候选档案注册出来的，
 * 只换身份不换档案，下一次启动 [deviceRegistered] 就会发现「身份与档案对不上」。
 */
fun Library.commitPendingProfile() {
    commitPendingProfileImpl()
}

/**
 * 注册请求该用哪一套档案：有待换的候选就用候选，否则用当前档案。
 *
 * 老版本随机档案升级时，请求必须**按候选档案**发出去，平台才会把新的 `device_id` 绑在
 * 新档案上；而其它请求（搜索 / 播放）此刻仍用旧档案，旧身份因此继续可用。
 */
fun Library.registrationProfile(): Map<String,String> {
    return registrationProfileImpl()
}

/** 有没有待转正的候选档案（见 [registrationProfile]）。 */
fun Library.hasPendingProfile(): Boolean = hasPendingProfileImpl()
