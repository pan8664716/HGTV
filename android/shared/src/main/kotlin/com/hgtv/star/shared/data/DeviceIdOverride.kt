// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data

import com.hgtv.star.core.ContentRepository
import com.hgtv.star.core.DeviceRegister

/**
 * 手动 device_id 的唯一写入入口。
 *
 * 持久值保存在 [Library]；[ContentRepository] 只拿当前播放覆盖值。
 * 修改后清掉旧设备签发的播放模型与地址缓存，下一次取流立即按新值签名。
 */
object DeviceIdOverride {
    fun current(library: Library): String = library.manualDeviceId()

    fun save(library: Library, repository: ContentRepository, raw: String): Boolean {
        val clean=DeviceRegister.normalizeManualDeviceId(raw) ?: return false
        if(!library.saveManualDeviceId(clean)) return false
        repository.updateManualDeviceId(clean)
        return true
    }

    fun clear(library: Library, repository: ContentRepository) {
        library.clearManualDeviceId()
        repository.updateManualDeviceId("")
    }
}
