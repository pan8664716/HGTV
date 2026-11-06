// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 一次播放请求可消费的匿名设备身份。
 *
 * [profile] 同时包含注册时使用的设备档案与服务端下发的 `device_id` / `iid`；
 * [cookies] 是这次注册同源下发的 Cookie。调用方只能把它用于一次播放模型请求，
 * 用完不能回写全局设备身份，也不能复用给下一次请求。
 */
data class PlaybackDeviceLease(
    val profile: Map<String,String>,
    val cookies: Map<String,String>,
) {
    val cookieHeader: String
        get() = cookies.entries.joinToString("; ") { (name,value) -> "$name=$value" }
}
