// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

internal fun ContentRepository.urlWithHost(url: String,host: String): String {
    if(host.isBlank()) return url
    return okhttp3.HttpUrl.parse(url)?.newBuilder()
        ?.scheme("https")
        ?.host(host)
        ?.build()
        ?.toString()
        ?: url
}
