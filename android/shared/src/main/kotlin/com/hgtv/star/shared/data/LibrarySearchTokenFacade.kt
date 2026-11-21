// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data

/**
 * 搜索登录态（可选，App 抓到的 `x-tt-token`，整段 Cookie 也可以）。
 *
 * 不填也能搜索（本机会先匿名注册一台设备，见 `DeviceRegister`）；填上后按该账号请求。
 * 它只用于搜索请求，不进备份文件，也不参与「手机备份与恢复」。
 */
var Library.searchToken: String
    get() {
        restoreFromMirrorForAccount()
        val token=identitySessionPrefs.getString("x-tt-token", "").orEmpty()
        mirrorSyncImpl("x-tt-token",token)
        return token
    }
    set(value) {
        if(identityDiscarding) return
        identitySessionPrefs.edit().putString("x-tt-token", value.trim()).apply()
        // 清空令牌是明确的删除意图（用户在设置里清掉的），走 remove 而不是 put(null)：
        // 后者在读盘之前只会被当成「本机还没有」，盘上那份不会被抹掉。
        if(value.isBlank()) identityMirror.remove("x-tt-token") else identityMirror.put("x-tt-token",value.trim())
        RecoverySnapshotManager.schedule(identityApp)
    }
