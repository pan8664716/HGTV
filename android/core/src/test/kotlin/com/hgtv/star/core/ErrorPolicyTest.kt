// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorPolicyTest {
    private fun context(
        networkAvailable: Boolean = true,
        idempotent: Boolean = true,
        networkAttempts: Int = 0,
        retryAfterMs: Long? = null,
        recoverySteps: Int = 0,
        maxRecoverySteps: Int = 3,
    ): FailureContext {
        val now = 1_000L
        return FailureContext(
            operationId = "op",
            requestId = "req",
            nowMs = now,
            networkAvailable = networkAvailable,
            meteredNetwork = false,
            foreground = true,
            idempotent = idempotent,
            retryAfterMs = retryAfterMs,
            retry = RetryContext(
                operationId = "op",
                startedAtMs = now,
                deadlineMs = now + 30_000L,
                networkAttempts = networkAttempts,
                maxNetworkAttempts = 5,
                recoverySteps = recoverySteps,
                maxRecoverySteps = maxRecoverySteps,
            ),
        )
    }

    private fun failure(
        code: String,
        domain: FailureDomain = FailureDomain.HTTP,
        httpStatus: Int? = null,
    ) = AppFailure(
        domain = domain,
        canonicalCode = code,
        operation = "test",
        httpStatus = httpStatus,
    )

    @Test fun `离线只等待网络`() {
        val decision = ErrorPolicy.decide(failure(FailureCodes.NET_OFFLINE, FailureDomain.TRANSPORT), context())
        assertEquals(FailureDisposition.WAIT_NETWORK, decision.disposition)
    }

    @Test fun `幂等 HTTP 503 生成有界恢复计划`() {
        val decision = ErrorPolicy.decide(failure(FailureCodes.HTTP_503), context())
        assertEquals(FailureDisposition.INTERNAL_RECOVERY, decision.disposition)
        assertTrue(decision.plan!!.steps.any { it.type == RecoveryStepType.RETRY_SAME_HOST })
        assertTrue(decision.plan!!.steps.any { it.type == RecoveryStepType.RETRY_OTHER_HOST })
    }

    @Test fun `非幂等写请求不自动重试`() {
        val decision = ErrorPolicy.decide(failure(FailureCodes.HTTP_503), context(idempotent = false))
        assertEquals(FailureDisposition.SHOW_FINAL_ERROR, decision.disposition)
    }

    @Test fun `设备未注册先自愈再重放`() {
        val decision = ErrorPolicy.decide(
            failure(FailureCodes.DEVICE_UNREGISTERED, FailureDomain.DEVICE),
            context(),
        )
        assertEquals(FailureDisposition.INTERNAL_RECOVERY, decision.disposition)
        assertEquals(RecoveryStepType.HEAL_DEVICE, decision.plan!!.steps.first().type)
    }

    @Test fun `解码器初始化失败只重建`() {
        val decision = ErrorPolicy.decide(
            failure(FailureCodes.PLAYER_DECODER_INIT_FAILED, FailureDomain.PLAYBACK),
            context(),
        )
        assertEquals(FailureDisposition.INTERNAL_RECOVERY, decision.disposition)
        assertEquals(listOf(RecoveryStepType.REBUILD_DECODER), decision.plan!!.steps.map { it.type })
    }

    @Test fun `权限错误不进入网络重试`() {
        val decision = ErrorPolicy.decide(
            failure(FailureCodes.PLAYER_IO_PERMISSION_DENIED, FailureDomain.PLAYBACK),
            context(),
        )
        assertEquals(FailureDisposition.SHOW_FINAL_ERROR, decision.disposition)
    }

    @Test fun `解析错误不进入解码降级`() {
        val decision = ErrorPolicy.decide(
            failure(FailureCodes.PLAYER_CONTAINER_MALFORMED, FailureDomain.PLAYBACK),
            context(),
        )
        assertEquals(FailureDisposition.SHOW_FINAL_ERROR, decision.disposition)
    }

    @Test fun `超过三十秒的 Retry-After 不自动等待`() {
        val decision = ErrorPolicy.decide(
            failure(FailureCodes.HTTP_503),
            context(retryAfterMs = 31_000L),
        )
        assertEquals(FailureDisposition.SHOW_FINAL_ERROR, decision.disposition)
    }

    @Test fun `能力模型只给格式超出能力开放降档`() {
        val capability = ErrorPolicy.capability(
            failure(FailureCodes.PLAYER_FORMAT_EXCEEDS_CAPABILITIES, FailureDomain.PLAYBACK),
        )
        assertTrue(capability.canDegradeQuality)
        assertTrue(capability.canSwitchDecoder)
        assertFalse(capability.canTryBackupCdn)
    }

    @Test fun `Exo 网络失败在离线时不消耗重试`() {
        val decision = ErrorPolicy.decide(
            failure(FailureCodes.NET_CONNECT, FailureDomain.PLAYBACK),
            context(networkAvailable = false),
        )
        assertEquals(FailureDisposition.WAIT_NETWORK, decision.disposition)
    }

    @Test fun `音频失败只重建音频`() {
        val decision = ErrorPolicy.decide(
            failure(FailureCodes.PLAYER_AUDIO_INIT_FAILED, FailureDomain.PLAYBACK),
            context(),
        )
        assertEquals(FailureDisposition.INTERNAL_RECOVERY, decision.disposition)
        assertEquals(listOf(RecoveryStepType.REBUILD_AUDIO), decision.plan!!.steps.map { it.type })
    }

    @Test fun `DRM 错误保留独立终态`() {
        val decision = ErrorPolicy.decide(
            failure(FailureCodes.DRM_LICENSE_EXPIRED, FailureDomain.DRM),
            context(),
        )
        assertEquals(FailureDisposition.SHOW_FINAL_ERROR, decision.disposition)
        assertEquals("error_drm", decision.userMessageKey)
    }

    @Test fun `恢复步骤用尽后不再生成计划`() {
        val decision = ErrorPolicy.decide(
            failure(FailureCodes.PLAYER_DECODER_INIT_FAILED, FailureDomain.PLAYBACK),
            context(recoverySteps = 3, maxRecoverySteps = 3),
        )
        assertEquals(FailureDisposition.SHOW_FINAL_ERROR, decision.disposition)
    }
}
