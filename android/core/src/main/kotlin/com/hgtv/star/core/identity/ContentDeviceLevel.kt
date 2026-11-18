// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

internal fun ContentRepository.beginEpisodeImpl() {
    deviceLevel=4
}

internal fun ContentRepository.escalateDeviceLevelImpl() {
    if(deviceLevel>=5) return
    val before=deviceLevel
    deviceLevel=before+1
    NetLog.emit("model device_level $before -> ${deviceLevel}")
}
