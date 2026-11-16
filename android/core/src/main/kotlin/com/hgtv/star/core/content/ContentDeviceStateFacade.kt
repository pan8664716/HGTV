// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 丢掉当前这份设备身份：注册结果与下发的 Cookie 一起清。
 *
 * 下一次 [registerDevice] 会拿到一套新的 `device_id` / `iid`，与当前设备档案自洽。
 */
fun ContentRepository.clearDeviceIdentity() = clearDeviceIdentityImpl()

/** 记一次「服务端不认这份身份」。身份为空时不计：那是真的还没注册，不是被拒。 */
internal fun ContentRepository.noteDeviceRejected() = noteDeviceRejectedImpl()

/**
 * 新一集起播前把档位重置回默认的 1。
 *
 * 1 档是前台首选；限流是临时的，上一集升到 5 不代表下一集也要多要档位。
 */
fun ContentRepository.beginEpisode() = beginEpisodeImpl()

/** 1 档空响应：升到 5 再请求一次；升到 5 后本集不再回退。 */
internal fun ContentRepository.escalateDeviceLevel() {
    escalateDeviceLevelImpl()
}
