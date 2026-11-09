// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 唯一一台备用设备。
 *
 * 它不会写入仓库的 [ContentRepository.deviceIdentity]，也不会替换本机主设备；
 * 调用方只在主身份收到 `HTTP 200 + 空 body` 时，用这套完整的档案和 Cookie 重签一次。
 */
data class PlaybackBackupDevice(
    val id: String,
    val profile: Map<String,String>,
    val cookies: Map<String,String>,
) {
    val cookieHeader: String
        get() = cookies.entries.joinToString("; ") { (name,value) -> "$name=$value" }
}
