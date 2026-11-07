// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 一台只用于播放模型空响应兜底的备用设备。
 *
 * 它不会写入仓库的 [ContentRepository.deviceIdentity]，也不会替换本机主设备；
 * 调用方只在单次播放模型请求里临时使用这套档案和 Cookie。
 */
data class PlaybackBackupDevice(
    val id: String,
    val profile: Map<String,String>,
    val cookies: Map<String,String>,
) {
    val cookieHeader: String
        get() = cookies.entries.joinToString("; ") { (name,value) -> "$name=$value" }
}
