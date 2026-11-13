// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import com.hgtv.star.shared.debug.DebugLog
import com.hgtv.star.shared.debug.Diagnostics
import com.hgtv.star.shared.debug.compactTls

/** 网络失败诊断：客户设备上「首页空白」时，这行日志给出主机名与异常类型。 */
internal fun TvMainActivity.setupNetworkLogging() {
    com.hgtv.star.core.NetLog.log={ line ->
        // 只走 [Diagnostics.trace]，它本身就是 `Log.i(tag, line)`。
        Diagnostics.trace("HGTV-NET",line)
        // 调试模式打开时，网络层的每一行都进右上角那块面板；
        // TLS 能力单独归到「TLS」标头，不再混进「网络」近况。
        if(line.startsWith("TLS 可用版本=")) {
            DebugTls.summary=compactTls(line.removePrefix("TLS "))
        } else if(!keepNetLine(line)) {
            // 纯统计行不进面板，logcat 里照旧有（上面 [Diagnostics.trace]）。
        } else if(!line.contains("interrupted",ignoreCase=true) && !line.contains("Canceled",ignoreCase=true)) {
            // 「线程被打断 / 主动取消」不是网络故障，不挤占调试面板的 40 条近况。
            DebugLog.record("网络",line)
        }
    }
    com.hgtv.star.core.NetLog.event={ level,line ->
        when(level) {
            com.hgtv.star.core.NetLogLevel.ERROR -> Diagnostics.error("HGTV-NET",line)
            com.hgtv.star.core.NetLogLevel.WARN -> Diagnostics.warn("HGTV-NET",line)
            com.hgtv.star.core.NetLogLevel.DEBUG -> Diagnostics.trace("HGTV-NET",line)
        }
    }
}
