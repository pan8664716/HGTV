// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core.error

import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * HTTP 和传输异常的纯映射层。
 *
 * 这里只保存事实，不决定换域、设备自愈或 UI。endpoint 业务码由各业务服务继续映射。
 */
object HttpFailureMapper {
    fun fromResponse(
        response: Response,
        operation: String,
    ): AppFailure {
        val failure = fromStatus(
            status = response.code(),
            operation = operation,
            host = response.request().url().host(),
            logId = response.header("x-tt-logid"),
        )
        val bodySummary = runCatching { response.peekBody(4096).string() }
            .getOrNull()
            ?.trim()
            ?.take(220)
            .orEmpty()
        return if (bodySummary.isEmpty()) failure else failure.copy(upstreamMessage = bodySummary)
    }

    fun fromStatus(
        status: Int,
        operation: String,
        host: String? = null,
        logId: String? = null,
    ): AppFailure {
        val code = when (status) {
            400 -> FailureCodes.HTTP_400
            401 -> FailureCodes.HTTP_401
            403 -> FailureCodes.HTTP_403
            404 -> FailureCodes.HTTP_404
            405 -> FailureCodes.HTTP_405
            408 -> FailureCodes.HTTP_408
            409 -> FailureCodes.HTTP_409
            410 -> FailureCodes.HTTP_410
            412 -> FailureCodes.HTTP_412
            413 -> FailureCodes.HTTP_413
            415 -> FailureCodes.HTTP_415
            416 -> FailureCodes.HTTP_416
            422 -> FailureCodes.HTTP_422
            425 -> FailureCodes.HTTP_425
            429 -> FailureCodes.HTTP_429
            499 -> FailureCodes.HTTP_499
            500 -> FailureCodes.HTTP_500
            501 -> FailureCodes.HTTP_501
            502 -> FailureCodes.HTTP_502
            503 -> FailureCodes.HTTP_503
            504 -> FailureCodes.HTTP_504
            505 -> FailureCodes.HTTP_505
            in 400..499 -> FailureCodes.HTTP_OTHER_4XX
            in 500..599 -> FailureCodes.HTTP_OTHER_5XX
            else -> FailureCodes.UNKNOWN
        }
        return AppFailure(
            domain = FailureDomain.HTTP,
            canonicalCode = code,
            operation = operation,
            stage = "response",
            endpoint = operation,
            severity = if (status in 400..499) FailureSeverity.WARN else FailureSeverity.ERROR,
            httpStatus = status,
            logId = logId,
            host = host,
        )
    }

    fun fromThrowable(
        problem: Throwable,
        operation: String,
        host: String? = null,
    ): AppFailure {
        val current = rootCause(problem)
        val code = when (current) {
            is UnknownHostException -> FailureCodes.NET_DNS
            is SocketTimeoutException -> FailureCodes.NET_TIMEOUT
            is ConnectException,
            is NoRouteToHostException,
            -> FailureCodes.NET_CONNECT
            is javax.net.ssl.SSLException -> FailureCodes.NET_TLS
            is IOException -> FailureCodes.NET_IO
            else -> FailureCodes.UNKNOWN
        }
        return AppFailure(
            domain = if (code == FailureCodes.UNKNOWN) FailureDomain.UNKNOWN else FailureDomain.TRANSPORT,
            canonicalCode = code,
            operation = operation,
            stage = "transport",
            endpoint = operation,
            severity = FailureSeverity.WARN,
            host = host,
            cause = problem,
            debugDetail = current.message?.take(220),
        )
    }

    fun parseRetryAfter(value: String?, nowMs: Long): Long? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        text.toLongOrNull()?.let { seconds ->
            if (seconds < 0L) return null
            return seconds.coerceAtMost(MAX_RETRY_AFTER_SECONDS + 1) * 1_000L
        }
        return runCatching {
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }
            val target = format.parse(text)?.time ?: return null
            (target - nowMs).coerceAtLeast(0L).coerceAtMost(MAX_RETRY_AFTER_MS + 1L)
        }.getOrNull()
    }

    private fun rootCause(problem: Throwable): Throwable {
        var current = problem
        var depth = 0
        while (current.cause != null && depth++ < 8) current = current.cause!!
        return current
    }

    private const val MAX_RETRY_AFTER_SECONDS = 30L
    private const val MAX_RETRY_AFTER_MS = MAX_RETRY_AFTER_SECONDS * 1_000L
}
