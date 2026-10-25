// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.data

import android.content.Context
import android.content.Intent

/**
 * 掉线通知。对齐原版 `com.dragon.read.user.h0` 的 `action_reading_user_session_expired`：
 * **只有确认失效（`data.error_name == "session_expired"`）才发**，界面收到才清 UI。
 *
 * 为什么放 app 模块：`sendBroadcast` 需要 `android.content.Context`，而 `android/core`
 * 是纯 Kotlin JVM 模块。判定逻辑在 core（`SessionKeeper`），发送在这里。
 */
object SessionEvents {
    const val ACTION_SESSION_EXPIRED = "com.hgtv.star.action.SESSION_EXPIRED"
    const val EXTRA_REASON = "reason"

    fun sendExpired(context: Context,reason: String) {
        runCatching {
            context.sendBroadcast(
                Intent(ACTION_SESSION_EXPIRED)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_REASON,reason)
            )
        }
    }
}
