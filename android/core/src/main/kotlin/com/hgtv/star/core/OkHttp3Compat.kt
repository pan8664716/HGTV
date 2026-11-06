// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import okhttp3.ConnectionSpec
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.ByteString
import java.util.concurrent.ExecutorService

/*
 * OkHttp 3.12 is a Java API. These aliases keep the Kotlin call sites written for
 * OkHttp 4 readable while the application needs to support Android 4.4.
 */

val Request.url: HttpUrl get() = url()
val Request.method: String get() = method()
val Request.headers: Headers get() = headers()
val Request.body: RequestBody? get() = body()

val OkHttpClient.connectionPool: ConnectionPool get() = connectionPool()
val OkHttpClient.dispatcher: Dispatcher get() = dispatcher()
val Dispatcher.executorService: ExecutorService get() = executorService()

val Response.request: Request get() = request()
val Response.protocol: okhttp3.Protocol get() = protocol()
val Response.code: Int get() = code()
val Response.message: String get() = message()
val Response.handshake: okhttp3.Handshake? get() = handshake()
val Response.headers: Headers get() = headers()
val Response.body: ResponseBody? get() = body()
val Response.networkResponse: Response? get() = networkResponse()
val Response.cacheResponse: Response? get() = cacheResponse()
val Response.priorResponse: Response? get() = priorResponse()

val HttpUrl.scheme: String get() = scheme()
val HttpUrl.host: String get() = host()
val HttpUrl.port: Int get() = port()
val HttpUrl.encodedPath: String get() = encodedPath()
val HttpUrl.encodedQuery: String? get() = encodedQuery()
val HttpUrl.encodedUsername: String get() = encodedUsername()
val HttpUrl.encodedPassword: String get() = encodedPassword()

val ConnectionSpec.cipherSuites: List<okhttp3.CipherSuite>? get() = cipherSuites()

fun String.toMediaType(): MediaType = MediaType.get(this)

fun String.toRequestBody(contentType: MediaType? = null): RequestBody =
    RequestBody.create(contentType, this)

fun ByteArray.toRequestBody(contentType: MediaType? = null): RequestBody =
    RequestBody.create(contentType, this)

fun ByteString.toRequestBody(contentType: MediaType? = null): RequestBody =
    RequestBody.create(contentType, this)

fun String.toResponseBody(contentType: MediaType? = null): ResponseBody =
    ResponseBody.create(contentType, this)

fun ByteArray.toResponseBody(contentType: MediaType? = null): ResponseBody =
    ResponseBody.create(contentType, this)

fun String.toHttpUrlOrNull(): HttpUrl? = HttpUrl.parse(this)

operator fun Headers.iterator(): Iterator<Pair<String, String>> =
    object : Iterator<Pair<String, String>> {
        private var index = 0

        override fun hasNext(): Boolean = index < size()

        override fun next(): Pair<String, String> {
            val current = index++
            return name(current) to value(current)
        }
    }
