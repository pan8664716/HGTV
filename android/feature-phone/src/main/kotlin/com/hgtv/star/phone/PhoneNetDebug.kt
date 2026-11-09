// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.hgtv.star.core.NetLog
import com.hgtv.star.core.NetLogLevel
import com.hgtv.star.shared.debug.DebugLog
import com.hgtv.star.shared.debug.Diagnostics
import com.hgtv.star.shared.debug.compactTls

/** 手机端网络日志出口：与 TV 端同一份过滤规则，只把有结论的行放进调试面板。 */
internal object PhoneNetDebug {
    @Volatile var tlsSummary = ""
        private set

    @Volatile private var installed = false

    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            installed = true
            NetLog.log = { line ->
                Diagnostics.trace("HGTV-NET", line)
                if (line.startsWith("TLS 可用版本=")) {
                    tlsSummary = compactTls(line.removePrefix("TLS "))
                } else if (keepLine(line) &&
                    !line.contains("interrupted", ignoreCase = true) &&
                    !line.contains("Canceled", ignoreCase = true)
                ) {
                    DebugLog.record("网络", line)
                }
            }
            NetLog.event = { level, line ->
                when (level) {
                    NetLogLevel.ERROR -> Diagnostics.error("HGTV-NET", line)
                    NetLogLevel.WARN -> Diagnostics.warn("HGTV-NET", line)
                    NetLogLevel.DEBUG -> Diagnostics.trace("HGTV-NET", line)
                }
            }
        }
    }

    private fun keepLine(line: String): Boolean = when {
        line.contains("fail", ignoreCase = true) -> true
        line.contains("失败") || line.contains("不可用") -> true
        line.contains(" HTTP ") -> true
        line.contains("Exception") || line.contains("超时") -> true
        line.startsWith("model path=") -> line.contains("bytes=0")
        line.startsWith("model device rotate ") || line.startsWith("model device_level ") -> true
        else -> false
    }
}
