// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpFailureMapperTest {
    @Test fun `503 保留状态和 canonical code`() {
        val failure = HttpFailureMapper.fromStatus(
            status = 503,
            operation = "detail",
            host = "api.example.com",
            logId = "log-1",
        )
        assertEquals(FailureDomain.HTTP, failure.domain)
        assertEquals(FailureCodes.HTTP_503, failure.canonicalCode)
        assertEquals(503, failure.httpStatus)
        assertEquals("api.example.com", failure.host)
        assertEquals("log-1", failure.logId)
    }

    @Test fun `未登记 5xx 不自动扩大为已知业务码`() {
        val failure = HttpFailureMapper.fromStatus(598, "detail")
        assertEquals(FailureCodes.HTTP_OTHER_5XX, failure.canonicalCode)
    }

    @Test fun `Retry-After 秒数按秒解析`() {
        assertEquals(12_000L, HttpFailureMapper.parseRetryAfter("12", 0L))
    }

    @Test fun `Retry-After 超过三十秒保留超限标记`() {
        assertTrue(HttpFailureMapper.parseRetryAfter("31", 0L)!! > 30_000L)
    }

    @Test fun `DNS 异常映射为 NET_DNS`() {
        val failure = HttpFailureMapper.fromThrowable(
            java.net.UnknownHostException("api.example.com"),
            "detail",
        )
        assertEquals(FailureCodes.NET_DNS, failure.canonicalCode)
        assertEquals(FailureDomain.TRANSPORT, failure.domain)
    }
}
