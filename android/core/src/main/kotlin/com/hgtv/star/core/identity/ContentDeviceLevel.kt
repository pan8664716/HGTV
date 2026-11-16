// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

internal fun ContentRepository.beginEpisodeImpl() {
    deviceLevel=1
}

internal fun ContentRepository.escalateDeviceLevelImpl() {
    if(deviceLevel>=5) return
    deviceLevel=5
    NetLog.emit("model device_level 1 -> 5")
}
