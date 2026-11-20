// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import com.hgtv.star.shared.ui.UiText

internal object PlaybackFailureLabels {
    fun tag(problem: Throwable?): String = when(problem) {
        null -> UiText.get(com.hgtv.star.shared.R.string.ui_0385)
        else -> when {
            networkOf(problem)!=null -> UiText.get(com.hgtv.star.shared.R.string.ui_0386)
            hasCauseOf<PlaybackPrepareException>(problem) -> UiText.get(com.hgtv.star.shared.R.string.ui_0387)
            hasCauseOf<com.hgtv.star.core.StreamExpiredException>(problem) -> UiText.get(com.hgtv.star.shared.R.string.ui_0388)
            hasCauseOf<com.hgtv.star.core.VideoChangedException>(problem) -> UiText.get(com.hgtv.star.shared.R.string.ui_0389)
            hasCauseOf<java.io.IOException>(problem) -> UiText.get(com.hgtv.star.shared.R.string.ui_0386)
            problem is OutOfMemoryError -> UiText.get(com.hgtv.star.shared.R.string.ui_0390)
            else -> UiText.get(com.hgtv.star.shared.R.string.ui_0385)
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
                is com.hgtv.star.core.AddressTimeoutException -> return UiText.get(com.hgtv.star.shared.R.string.ui_0391)
                is java.net.UnknownHostException -> return UiText.get(com.hgtv.star.shared.R.string.ui_0392)
                is java.net.SocketTimeoutException -> return UiText.get(com.hgtv.star.shared.R.string.ui_0393)
                is java.net.ConnectException -> return UiText.get(com.hgtv.star.shared.R.string.ui_0394)
                is java.net.NoRouteToHostException -> return UiText.get(com.hgtv.star.shared.R.string.ui_0395)
                is javax.net.ssl.SSLException -> return UiText.get(com.hgtv.star.shared.R.string.ui_0396)
                is java.io.InterruptedIOException ->
                    if(current.message.orEmpty().contains("timeout",ignoreCase=true)) return UiText.get(com.hgtv.star.shared.R.string.ui_0397)
            }
            current=current.cause
            depth++
        }
        return null
    }
}
