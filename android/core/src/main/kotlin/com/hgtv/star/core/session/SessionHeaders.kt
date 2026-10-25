// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core.session

/**
 * 心跳 / 会话请求要带的设备头。
 *
 * **必须与业务请求同源**（`VendorConstants.userAgent`）：两份头不一致 = 同一次会话里
 * 出现两台设备，是平台要求「重新短信验证」的常见触发点。
 *
 * 放在 `core` 而不是 `app`：`VendorConstants` 是 `internal object`，只有同模块能引用。
 */
object SessionHeaders {
    /**
     * @param device 设备档案（`Library.accountDevice()` 那份，键名与 `dump_device.py` 一致）。
     *   空表时回落到内置档案，与业务请求逐字节一致。
     */
    fun build(device: Map<String,String>): Map<String,String> = linkedMapOf(
        "User-Agent" to com.hgtv.star.core.VendorConstants.userAgent(device,video = false),
        "X-VC-Bdturing-Sdk-Version" to "4.0.3.cn",
        "Lc" to "101",
        "Accept-Language" to "zh-CN,zh;q=0.9"
    )
}
