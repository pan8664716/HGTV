// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data

/**
 * 用户有没有确认过「第三方客户端」的使用须知。
 *
 * 存在会话偏好里（不进备份、不进镜像）：换一台设备、或者清除数据之后要重新确认一次，
 * 这是这一条声明应有的语义——它是「这台设备上的这位用户」的知情同意，不是可以搬走的偏好。
 */
var Library.thirdPartyAccepted: Boolean
    get() = identitySessionPrefs.getBoolean("third-party-accepted",false)
    set(value) {
        if(identityDiscarding) return
        if(value==thirdPartyAccepted) return
        identitySessionPrefs.edit().putBoolean("third-party-accepted",value).apply()
    }

/**
 * 用户有没有自己选过清晰度。
 *
 * 判据就是磁盘上有没有 `quality` 这个键：`LibraryDisk.decode` 在缺键时按 1080 兜底，
 * 但**不写回磁盘**，所以「没有这个键」恰好等于「还从没选过」。
 * 首次启动按设备解码能力给默认值（见 `PlaybackCapability.defaultQuality`）时用它做闸门，
 * 用户点过任意一档之后就不再覆盖他的选择。
 */
fun Library.qualityChosenByUser(): Boolean = settingsPrefs.contains("quality")
