// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/** 同一套业务参数与设备参数，只换接口域名后重签。 */
internal fun ContentRepository.resignForHost(
    signed: Signer.Request,
    host: String,
    profile: Map<String,String>,
): Signer.Request? {
    val parsed=okhttp3.HttpUrl.parse(signed.url) ?: return null
    val values=linkedMapOf<String,String>()
    parsed.queryParameterNames().forEach { name ->
        if(name!="ts" && name!="_rticket") parsed.queryParameter(name)?.let { values[name]=it }
    }
    val base=parsed.newBuilder().scheme("https").host(host).query(null).build().toString()
    val next=signer.sign(base,values,signed.body,signed.method,deviceOverride=profile)
    val preserved=signed.headers.filterKeys {
        it.equals("Cookie",ignoreCase=true) ||
            it.equals("x-tt-token",ignoreCase=true) ||
            it.equals("X-HGTV-Identity",ignoreCase=true) ||
            it.equals("X-HGTV-Anonymous",ignoreCase=true) ||
            it.equals("comment-source",ignoreCase=true) ||
            it.equals("server-channel",ignoreCase=true) ||
            it.equals("x-ss-stub",ignoreCase=true)
    }
    return next.copy(headers=next.headers+preserved)
}

internal fun ContentRepository.alternateApiHosts(currentHost: String): List<String> =
    TncConfigStore.orderedApiHosts().filterNot { it.equals(currentHost,ignoreCase=true) }

internal fun ContentRepository.urlWithHost(url: String,host: String): String {
    if(host.isBlank()) return url
    return okhttp3.HttpUrl.parse(url)?.newBuilder()
        ?.scheme("https")
        ?.host(host)
        ?.build()
        ?.toString()
        ?: url
}
