// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONObject.str(key: String) = if (isNull(key)) "" else optString(key, "").trim()

/**
 * 平台开关字段可能是 Boolean、Number 或 "1"/"true"/"yes" 字符串。
 */
fun JSONObject.flag(key: String) = when(val value=opt(key)) {
    is Boolean -> value
    is Number -> value.toDouble()!=0.0
    is String -> value.trim().let {
        it=="1" || it.equals("true",ignoreCase=true) || it.equals("yes",ignoreCase=true)
    }
    else -> false
}

fun cleanUrl(s: String) = s.replace("\\/","/")
    .replace("\\u002F","/",ignoreCase=true)
    .replace("\\u0026","&")
    .replace("&amp;","&")

internal fun defaultContentHttpClient(): OkHttpClient {
    val builder=HgtvTls.configure(OkHttpClient.Builder())
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .connectTimeout(15,TimeUnit.SECONDS)
        .readTimeout(30,TimeUnit.SECONDS)
        .callTimeout(45,TimeUnit.SECONDS)
        .addInterceptor(com.hgtv.star.core.session.SessionInterceptor())
    if(NetLog.timing) builder.eventListenerFactory(NetTiming.Factory)
    return builder.build()
}
