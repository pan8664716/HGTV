// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core.session

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 会话模块自己的 client。**只给心跳用**，不复用业务 client：
 *
 * * 业务 client 的 `callTimeout` 是 45 秒、`readTimeout` 30 秒，心跳没必要等这么久；
 * * 心跳要能在业务 client 被 `cancelAll()` / 连接池被 `evictAll()` 时**不受影响**
 *   （`MainActivity.onDestroy` 会干这件事）。
 */
object SessionClients {
    private fun tls(builder: OkHttpClient.Builder): OkHttpClient.Builder =
        com.hgtv.star.core.HgtvTls.configure(builder)

    fun forBeat(): OkHttpClient = tls(OkHttpClient.Builder())
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .connectTimeout(10,TimeUnit.SECONDS)
        .readTimeout(15,TimeUnit.SECONDS)
        .writeTimeout(15,TimeUnit.SECONDS)
        .callTimeout(20,TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // 心跳**不要**自动重试整套请求链：一次 beat 失败就等下一个 10 分钟，别放大流量
        .addInterceptor(SessionInterceptor())
        .build()
}
