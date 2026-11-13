// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import com.hgtv.star.core.session.CookieRecord

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

/** 主设备 200 空响应时，把同一条签名请求临时改签成一台备用设备。 */
internal fun ContentRepository.backupRequest(
    signed: Signer.Request,
    backup: PlaybackBackupDevice,
): Signer.Request? {
    val parsed=okhttp3.HttpUrl.parse(signed.url) ?: return null
    val original=linkedMapOf<String,String>()
    parsed.queryParameterNames().forEach { name ->
        if(name!="ts" && name!="_rticket") parsed.queryParameter(name)?.let { original[name]=it }
    }
    val values=LinkedHashMap(original)
    ContentRepository.DEVICE_QUERY_KEYS.forEach(values::remove)
    val fullProfile=LinkedHashMap(VendorConstants.device).apply {
        putAll(backup.profile)
        protocolValuesValue.forEach { (key,value) -> if(!containsKey(key)) put(key,value) }
        // 客户端版本是接口协议的一部分，沿用原请求，不因换设备而换版本。
        listOf("version_code","version_name","manifest_version_code","update_version_code","pv_player")
            .forEach { key -> original[key]?.let { put(key,it) } }
    }
    ContentRepository.DEVICE_QUERY_KEYS.forEach { key ->
        fullProfile[key]?.takeIf { it.isNotBlank() }?.let { values[key]=it }
    }
    val base=parsed.newBuilder().query(null).build().toString()
    val next=signer.sign(
        base,
        values,
        signed.body,
        signed.method,
        deviceOverride=backup.profile,
    )
    val cookies=CookieRecord.headerToMap(backup.cookieHeader)
    val extra=linkedMapOf(
        "Cookie" to backup.cookieHeader,
        "X-HGTV-Anonymous" to "1",
    )
    cookies["store-region"]?.takeIf { it.isNotBlank() }?.let { extra["X-Tt-Store-Region"]=it }
    cookies["store-region-src"]?.takeIf { it.isNotBlank() }?.let { extra["X-Tt-Store-Region-Src"]=it }
    signed.headers["comment-source"]?.let { extra["comment-source"]=it }
    signed.headers["server-channel"]?.let { extra["server-channel"]=it }
    signed.headers["x-ss-stub"]?.let { extra["x-ss-stub"]=it }
    return next.copy(headers=next.headers+extra)
}

/** 无签名请求的备用地址：只替换设备参数，业务参数原样保留。 */
internal fun ContentRepository.backupQueryUrl(
    url: String,
    backup: PlaybackBackupDevice,
): String? {
    val parsed=okhttp3.HttpUrl.parse(url) ?: return null
    val original=linkedMapOf<String,String>()
    parsed.queryParameterNames().forEach { name ->
        if(name!="ts" && name!="_rticket") parsed.queryParameter(name)?.let { original[name]=it }
    }
    val values=LinkedHashMap(original)
    ContentRepository.DEVICE_QUERY_KEYS.forEach(values::remove)
    val fullProfile=LinkedHashMap(VendorConstants.device).apply {
        putAll(backup.profile)
        protocolValuesValue.forEach { (key,value) -> if(!containsKey(key)) put(key,value) }
        listOf("version_code","version_name","manifest_version_code","update_version_code","pv_player")
            .forEach { key -> original[key]?.let { put(key,it) } }
    }
    ContentRepository.DEVICE_QUERY_KEYS.forEach { key ->
        fullProfile[key]?.takeIf { it.isNotBlank() }?.let { values[key]=it }
    }
    return parsed.newBuilder().query(null).build().toString()+"?"+
        Signer.encode(values)
}

internal fun ContentRepository.urlWithHost(url: String,host: String): String {
    if(host.isBlank()) return url
    return okhttp3.HttpUrl.parse(url)?.newBuilder()
        ?.scheme("https")
        ?.host(host)
        ?.build()
        ?.toString()
        ?: url
}
