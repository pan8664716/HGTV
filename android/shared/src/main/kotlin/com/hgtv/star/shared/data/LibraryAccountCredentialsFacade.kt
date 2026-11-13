// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data

import com.hgtv.star.core.AccountCredentials

/**
 * 账号参数（个人信息页导入的整套登录态）。
 *
 * 与 `searchToken` 同一个文件、同一套约定：**不进备份、不随记录迁移**，
 * 因为它是平台凭据而不是本机偏好。这里存的是 Cookie 串 + 设备参数 + 过期时间。
 */
fun Library.account(): AccountCredentials {
    return accountImpl()
}

fun Library.saveAccount(credentials: AccountCredentials) {
    saveAccountImpl(credentials)
}

fun Library.clearAccount() {
    clearAccountImpl()
}

/**
 * 设备档案：**只能从设备上读**的那批参数（`device_id` / 机型 / 系统版本 / cdid …）。
 *
 * 存成一条 JSON，字段名与 `tools/login-lab/dump_device.py` 产出的
 * `device_profile.json` 一致，导入那个文件即可整份灌进来。
 */
fun Library.accountDevice(): Map<String,String> {
    return accountDeviceImpl()
}

fun Library.saveAccountDevice(device: Map<String,String>) {
    saveAccountDeviceImpl(device)
}
