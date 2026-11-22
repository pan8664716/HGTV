// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import com.hgtv.star.core.error.AppFailure
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 兼容旧调用点；统一值见 [AppConfig.Network]。 */
const val REQUEST_TIMEOUT_SECONDS = AppConfig.Network.REQUEST_TIMEOUT_SECONDS
const val REQUEST_TIMEOUT_MS = AppConfig.Network.REQUEST_TIMEOUT_MS

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
        .connectTimeout(REQUEST_TIMEOUT_SECONDS,TimeUnit.SECONDS)
        .readTimeout(REQUEST_TIMEOUT_SECONDS,TimeUnit.SECONDS)
        .writeTimeout(REQUEST_TIMEOUT_SECONDS,TimeUnit.SECONDS)
        .callTimeout(REQUEST_TIMEOUT_SECONDS,TimeUnit.SECONDS)
        .addInterceptor(com.hgtv.star.core.session.SessionInterceptor())
    if(NetLog.timing) builder.eventListenerFactory(NetTiming.Factory)
    return builder.build()
}

internal class CatalogHttpException(val status: Int, message: String) : IOException(message)

/** 内容接口的非 2xx 响应；保留统一事实，message 仍兼容旧调用点。 */
class ContentHttpException(
    val failure: AppFailure,
    val retryAfterMs: Long?,
    message: String,
) : IOException(message)

class AddressTimeoutException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

class SearchSessionExpiredException : IOException("搜索结果已过期，请从第 1 页重新搜索")

class SearchApiException(val reason: String, message: String) : IOException(message)

class DeviceNotRegisteredException(
    message: String = "设备正在初始化，请稍后重试",
) : IOException(message)

class SignInRequiredException(
    message: String = "需要先导入账号参数（sessionid）",
) : IOException(message)
