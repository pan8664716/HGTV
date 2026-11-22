// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 丢掉当前这份设备身份：注册结果与下发的 Cookie 一起清。
 *
 * 下一次 [registerDevice] 会拿到一套新的 `device_id` / `iid`，与当前设备档案自洽。
 */
fun ContentRepository.clearDeviceIdentity() = clearDeviceIdentityImpl()
