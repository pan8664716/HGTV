// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

internal object PlaybackFailureLabels {
    fun tag(problem: Throwable?): String = when(problem) {
        null -> "未知错误"
        else -> when {
            networkOf(problem)!=null -> "取流失败"
            hasCauseOf<PlaybackPrepareException>(problem) -> "本地准备失败"
            hasCauseOf<com.hgtv.star.core.StreamExpiredException>(problem) -> "地址过期"
            hasCauseOf<com.hgtv.star.core.VideoChangedException>(problem) -> "资源已变化"
            hasCauseOf<java.io.IOException>(problem) -> "取流失败"
            problem is OutOfMemoryError -> "内存不足"
            else -> "未知错误"
        }
    }

    fun reason(problem: Throwable?): String? {
        networkOf(problem)?.let { return it }
        var current=problem
        var depth=0
        while(current!=null && depth<8) {
            val message=current.message
            if(!message.isNullOrBlank() && message.length<=24 && '\n' !in message) return message
            current=current.cause
            depth++
        }
        return null
    }

    fun networkOf(problem: Throwable?): String? {
        var current=problem
        var depth=0
        while(current!=null && depth<8) {
            when(current) {
                is com.hgtv.star.core.AddressTimeoutException -> return "取播放地址超时"
                is java.net.UnknownHostException -> return "视频服务器域名无法解析"
                is java.net.SocketTimeoutException -> return "连接视频服务器超时"
                is java.net.ConnectException -> return "连不上视频服务器"
                is java.net.NoRouteToHostException -> return "网络不通，无法访问视频服务器"
                is javax.net.ssl.SSLException -> return "视频连接加密握手失败"
                is java.io.InterruptedIOException ->
                    if(current.message.orEmpty().contains("timeout",ignoreCase=true)) return "视频服务器响应超时"
            }
            current=current.cause
            depth++
        }
        return null
    }
}
