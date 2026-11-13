// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 调试面板读取的 TLS 现场。
 *
 * 不能只依赖 `NetLog`：首个 TLS 连接通常发生在 Application 启动阶段，那时页面还没
 * 安装日志出口，一次性能力日志会永久丢失。这里由 [HgtvTls] 在 socket 创建时直接写入，
 * 与日志接收时机无关。
 */
object TlsStatus {
    @Volatile private var capability=""
    @Volatile private var handshake=""

    fun recordCapability(supported: List<String>,enabled: List<String>) {
        val ordered=supported.sortedBy(::rank)
        val span=when(ordered.size) {
            0 -> ""
            1 -> nice(ordered.first())
            else -> "${nice(ordered.first())}~${nice(ordered.last())}"
        }
        val enable=enabled.sortedBy(::rank).map(::nice).joinToString("/").ifEmpty { "无" }
        capability="支持 ${span.ifEmpty { "未知" }} · 启用 $enable"
    }

    fun recordHandshake(version: String?,host: String?) {
        val protocol=version.orEmpty().replace("TLSv","TLS ")
        if(protocol.isBlank()) return
        val target=host.orEmpty().take(28)
        handshake=buildString {
            append("已握手 ").append(protocol)
            if(target.isNotBlank()) append(" · ").append(target)
        }
    }

    fun summaryOrEmpty(): String = when {
        handshake.isNotBlank() -> handshake
        capability.isNotBlank() -> "$capability · 未捕获握手"
        else -> ""
    }

    fun summary(): String = summaryOrEmpty().ifBlank { "等待网络请求" }

    fun resetForTest() {
        capability=""
        handshake=""
    }

    private fun nice(value: String)=if(value=="1") "1.0" else value
    private fun rank(value: String)=when(value) {
        "TLSv1" -> 10
        "TLSv1.1" -> 11
        "TLSv1.2" -> 12
        "TLSv1.3" -> 13
        else -> 99
    }
}
