// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core.error

/**
 * 纯决策入口。
 *
 * 这里不发请求、不碰 Android、不读页面。网络层和播放器层只负责把事实映射成
 * [AppFailure]，执行器负责按 [FailureDecision] 执行。
 */
object ErrorPolicy {
    fun decide(failure: AppFailure, context: FailureContext): FailureDecision {
        if (context.retry.cancelled) return cancel()
        val code = failure.canonicalCode
        if (code == FailureCodes.NET_OFFLINE ||
            (!context.networkAvailable && code in NETWORK_CODES)
        ) {
            return FailureDecision(FailureDisposition.WAIT_NETWORK)
        }
        if (context.retry.expired(context.nowMs)) return final()

        return when (code) {
            FailureCodes.CANCEL_USER,
            FailureCodes.CANCEL_LOCAL,
            -> cancel()

            FailureCodes.AUTH_SESSION_EXPIRED -> {
                if (!context.idempotent) final("error_auth_required")
                else internal(
                    context,
                    listOf(RecoveryStep(RecoveryStepType.REFRESH_AUTH)),
                    userMessageKey = "error_auth_refresh",
                )
            }

            FailureCodes.AUTH_REQUIRED -> final("error_auth_required")

            FailureCodes.DEVICE_UNREGISTERED,
            FailureCodes.DEVICE_PROFILE_INVALID,
            -> {
                if (!context.idempotent) final("error_device_init")
                else internal(
                    context,
                    listOf(
                        RecoveryStep(RecoveryStepType.HEAL_DEVICE),
                        RecoveryStep(RecoveryStepType.RETRY_SAME_HOST),
                    ),
                    userMessageKey = "error_device_init",
                )
            }

            FailureCodes.NET_DNS -> retryNetwork(
                context,
                listOf(
                    RecoveryStep(RecoveryStepType.CLEAR_DNS),
                    RecoveryStep(RecoveryStepType.RETRY_OTHER_HOST),
                ),
            )
            FailureCodes.NET_CONNECT,
            FailureCodes.NET_TLS,
            -> retryNetwork(
                context,
                listOf(RecoveryStep(RecoveryStepType.RETRY_OTHER_HOST)),
            )
            FailureCodes.NET_TIMEOUT -> retryNetwork(
                context,
                listOf(
                    RecoveryStep(RecoveryStepType.RETRY_SAME_HOST),
                    RecoveryStep(RecoveryStepType.RETRY_OTHER_HOST),
                ),
            )
            FailureCodes.NET_IO -> retryNetwork(
                context,
                listOf(RecoveryStep(RecoveryStepType.RETRY_SAME_HOST)),
            )
            FailureCodes.PLAYER_TIMEOUT -> retryNetwork(
                context,
                listOf(RecoveryStep(RecoveryStepType.RETRY_SAME_HOST)),
            )

            FailureCodes.HTTP_408,
            FailureCodes.HTTP_425,
            FailureCodes.HTTP_429,
            FailureCodes.HTTP_500,
            FailureCodes.HTTP_502,
            FailureCodes.HTTP_503,
            FailureCodes.HTTP_504,
            -> retryHttp(context)

            FailureCodes.HTTP_501,
            FailureCodes.HTTP_505,
            FailureCodes.HTTP_OTHER_5XX,
            FailureCodes.HTTP_OTHER_4XX,
            FailureCodes.HTTP_400,
            FailureCodes.HTTP_403,
            FailureCodes.HTTP_405,
            FailureCodes.HTTP_409,
            FailureCodes.HTTP_412,
            FailureCodes.HTTP_413,
            FailureCodes.HTTP_415,
            FailureCodes.HTTP_422,
            -> final("error_server")

            FailureCodes.HTTP_401 -> final("error_auth_required")
            FailureCodes.HTTP_404,
            FailureCodes.HTTP_410,
            -> final("error_content_unavailable")
            FailureCodes.HTTP_416 -> final("error_media")
            FailureCodes.HTTP_499 -> final("error_network")

            FailureCodes.API_PARAM_INVALID,
            FailureCodes.API_REQUEST_SHAPE_INVALID,
            FailureCodes.API_RISK_REJECTED,
            FailureCodes.API_COMMUNITY_UNAVAILABLE,
            FailureCodes.API_UNKNOWN,
            -> final("error_server")

            FailureCodes.AUTH_CODE_1,
            FailureCodes.AUTH_CODE_3,
            FailureCodes.AUTH_CODE_7,
            FailureCodes.AUTH_CODE_22,
            FailureCodes.AUTH_CODE_1045,
            FailureCodes.AUTH_CODE_2046,
            FailureCodes.AUTH_CODE_9001,
            -> final("error_auth_required")

            FailureCodes.MEDIA_STREAM_EXPIRED -> {
                if (!context.idempotent) final("error_media")
                else internal(
                    context,
                    listOf(
                        RecoveryStep(RecoveryStepType.INVALIDATE_STREAM),
                        RecoveryStep(RecoveryStepType.TRY_BACKUP_CDN),
                        RecoveryStep(RecoveryStepType.REPREPARE_MEDIA),
                    ),
                    userMessageKey = "error_media_refresh",
                )
            }
            FailureCodes.MEDIA_CHANGED -> internal(
                context,
                listOf(
                    RecoveryStep(RecoveryStepType.INVALIDATE_INDEX),
                    RecoveryStep(RecoveryStepType.REPREPARE_MEDIA),
                ),
                userMessageKey = "error_media_refresh",
            )
            FailureCodes.MEDIA_RANGE_INVALID,
            FailureCodes.MEDIA_RANGE_NOT_SATISFIABLE,
            FailureCodes.MEDIA_DECRYPT_FAILED,
            -> internal(
                context,
                listOf(
                    RecoveryStep(RecoveryStepType.INVALIDATE_INDEX),
                    RecoveryStep(RecoveryStepType.REPREPARE_MEDIA),
                ),
                userMessageKey = "error_media_refresh",
            )

            FailureCodes.PLAYER_DECODER_INIT_FAILED,
            FailureCodes.PLAYER_DECODING_FAILED,
            -> if (context.retry.decoderRebuilds < 1) {
                internal(
                    context,
                    listOf(RecoveryStep(RecoveryStepType.REBUILD_DECODER)),
                    userMessageKey = "error_decoder",
                )
            } else final("error_decoder")

            FailureCodes.PLAYER_DECODER_QUERY_FAILED -> final("error_decoder")
            FailureCodes.PLAYER_FORMAT_EXCEEDS_CAPABILITIES,
            FailureCodes.PLAYER_FORMAT_UNSUPPORTED,
            -> if (context.retry.qualityDegrades < 2) {
                internal(
                    context,
                    listOf(RecoveryStep(RecoveryStepType.DEGRADE_QUALITY)),
                    userMessageKey = "error_decoder",
                )
            } else final("error_decoder")

            FailureCodes.PLAYER_UNKNOWN,
            FailureCodes.PLAYER_REMOTE_ERROR,
            FailureCodes.PLAYER_LIVE_WINDOW,
            FailureCodes.PLAYER_RUNTIME_CHECK,
            -> final("error_playback")
            FailureCodes.PLAYER_IO_UNSPECIFIED -> retryNetwork(
                context,
                listOf(
                    RecoveryStep(RecoveryStepType.RETRY_SAME_HOST),
                    RecoveryStep(RecoveryStepType.RETRY_OTHER_HOST),
                ),
            )
            FailureCodes.PLAYER_HTTP_CONTENT_TYPE,
            FailureCodes.PLAYER_RANGE_INVALID,
            FailureCodes.PLAYER_CONTAINER_MALFORMED,
            FailureCodes.PLAYER_MANIFEST_MALFORMED,
            FailureCodes.PLAYER_CONTAINER_UNSUPPORTED,
            FailureCodes.PLAYER_MANIFEST_UNSUPPORTED,
            -> final("error_media")
            FailureCodes.PLAYER_HTTP_STATUS -> when (failure.httpStatus) {
                408, 425, 429, in 500..599 -> retryNetwork(
                    context,
                    listOf(
                        RecoveryStep(RecoveryStepType.RETRY_SAME_HOST),
                        RecoveryStep(RecoveryStepType.RETRY_OTHER_HOST),
                    ),
                )
                else -> final("error_media")
            }
            FailureCodes.MEDIA_NOT_FOUND -> {
                if (!context.idempotent) final("error_media")
                else internal(
                    context,
                    listOf(
                        RecoveryStep(RecoveryStepType.INVALIDATE_STREAM),
                        RecoveryStep(RecoveryStepType.TRY_BACKUP_CDN),
                    ),
                    userMessageKey = "error_media_refresh",
                )
            }
            FailureCodes.PLAYER_IO_PERMISSION_DENIED,
            FailureCodes.PLAYER_CLEARTEXT_BLOCKED,
            -> final("error_media")

            FailureCodes.PLAYER_AUDIO_INIT_FAILED,
            FailureCodes.PLAYER_AUDIO_WRITE_FAILED,
            -> if (context.retry.recoverySteps < context.retry.maxRecoverySteps) {
                internal(
                    context,
                    listOf(RecoveryStep(RecoveryStepType.REBUILD_AUDIO)),
                    userMessageKey = "error_audio",
                )
            } else final("error_audio")

            FailureCodes.DRM_UNSPECIFIED,
            FailureCodes.DRM_SCHEME_UNSUPPORTED,
            FailureCodes.DRM_PROVISIONING_FAILED,
            FailureCodes.DRM_CONTENT_ERROR,
            FailureCodes.DRM_LICENSE_ACQUISITION_FAILED,
            FailureCodes.DRM_DISALLOWED_OPERATION,
            FailureCodes.DRM_SYSTEM_ERROR,
            FailureCodes.DRM_DEVICE_REVOKED,
            FailureCodes.DRM_LICENSE_EXPIRED,
            -> final("error_drm")

            FailureCodes.PLAYER_VIDEO_FRAME_PROCESSOR_INIT_FAILED,
            FailureCodes.PLAYER_VIDEO_FRAME_PROCESSING_FAILED,
            -> if (context.retry.recoverySteps < context.retry.maxRecoverySteps) {
                internal(
                    context,
                    listOf(RecoveryStep(RecoveryStepType.REBUILD_FRAME_PROCESSOR)),
                    userMessageKey = "error_playback",
                )
            } else final("error_playback")

            FailureCodes.CONTENT_SCHEMA_CHANGED,
            FailureCodes.MEDIA_CONTENT_TYPE_INVALID,
            -> final("error_media")

            FailureCodes.STORE_CORRUPT,
            FailureCodes.STORE_NO_SPACE,
            FailureCodes.STORE_IO,
            FailureCodes.SNAPSHOT_NEWER_VERSION,
            FailureCodes.SNAPSHOT_CHECKSUM_FAILED,
            -> final("error_storage")

            FailureCodes.UPDATE_METADATA_INVALID,
            FailureCodes.UPDATE_METADATA_EMPTY,
            FailureCodes.UPDATE_DOWNLOAD_HTTP,
            FailureCodes.UPDATE_DOWNLOAD_SHORT,
            FailureCodes.UPDATE_CHECKSUM_FAILED,
            FailureCodes.UPDATE_INSTALL_ABORTED,
            FailureCodes.UPDATE_INSTALL_BLOCKED,
            FailureCodes.UPDATE_INSTALL_CONFLICT,
            FailureCodes.UPDATE_INSTALL_INVALID,
            FailureCodes.UPDATE_INSTALL_INCOMPATIBLE,
            FailureCodes.UPDATE_INSTALL_STORAGE,
            -> final("error_update")

            FailureCodes.PERMISSION_DENIED -> final("error_permission")
            FailureCodes.SYSTEM_UNAVAILABLE,
            FailureCodes.SYSTEM_OOM,
            FailureCodes.SYSTEM_SECURITY,
            FailureCodes.SYSTEM_LINKAGE,
            FailureCodes.SYSTEM_ILLEGAL_STATE,
            -> final("error_system")
            FailureCodes.LOCAL_ARGUMENT -> final("error_local")
            FailureCodes.IMAGE_HTTP,
            FailureCodes.IMAGE_DECODE,
            FailureCodes.IMAGE_TOO_LARGE,
            FailureCodes.IMAGE_CACHE_IO,
            -> final("error_image")
            FailureCodes.DANMAKU_HTTP,
            FailureCodes.DANMAKU_PARSE,
            FailureCodes.DANMAKU_RENDER,
            -> FailureDecision(
                disposition = FailureDisposition.DROP_AND_SKIP,
                userMessageKey = "error_danmaku",
            )
            FailureCodes.LAN_DISCOVERY,
            FailureCodes.LAN_CONNECT,
            FailureCodes.LAN_SYNC,
            -> final("error_lan")

            FailureCodes.UNKNOWN -> final("error_unknown")

            else -> final(defaultMessageKey(failure.domain))
        }
    }

    fun capability(failure: AppFailure): FailureCapability {
        val code = failure.canonicalCode
        val canRebuildDecoder = code in setOf(
            FailureCodes.PLAYER_DECODER_INIT_FAILED,
            FailureCodes.PLAYER_DECODING_FAILED,
        )
        val canSwitchDecoder = code in setOf(
            FailureCodes.PLAYER_DECODER_QUERY_FAILED,
            FailureCodes.PLAYER_FORMAT_EXCEEDS_CAPABILITIES,
            FailureCodes.PLAYER_FORMAT_UNSUPPORTED,
        )
        val canDegrade = code in setOf(
            FailureCodes.PLAYER_FORMAT_EXCEEDS_CAPABILITIES,
            FailureCodes.PLAYER_FORMAT_UNSUPPORTED,
        )
        return FailureCapability(
            canRetry = code in RETRYABLE_CODES,
            canChangeHost = code in NETWORK_CODES || code in TRANSIENT_HTTP_CODES,
            canRefreshAddress = code in MEDIA_REFRESH_CODES,
            canTryBackupCdn = code in MEDIA_REFRESH_CODES,
            canRebuildDecoder = canRebuildDecoder,
            canDegradeQuality = canDegrade,
            canSwitchDecoder = canSwitchDecoder,
            canHealDevice = code in setOf(
                FailureCodes.DEVICE_UNREGISTERED,
                FailureCodes.DEVICE_PROFILE_INVALID,
            ),
        )
    }

    private fun retryHttp(context: FailureContext): FailureDecision {
        if (!context.idempotent) return final("error_server")
        if (context.retry.networkAttempts >= context.retry.maxNetworkAttempts) {
            return final("error_server")
        }
        if (context.retry.recoverySteps >= context.retry.maxRecoverySteps) {
            return final("error_server")
        }
        val delay = context.retryAfterMs ?: (200L shl context.retry.networkAttempts.coerceAtMost(4))
        if (delay > MAX_RETRY_DELAY_MS) return final("error_server")
        return FailureDecision(
            disposition = FailureDisposition.INTERNAL_RECOVERY,
            retryDelayMs = delay,
            retryAfterMs = context.retryAfterMs,
            userMessageKey = "error_network",
            plan = RecoveryPlan(
                steps = listOf(
                    RecoveryStep(RecoveryStepType.RETRY_SAME_HOST, delayMs = delay),
                    RecoveryStep(RecoveryStepType.RETRY_OTHER_HOST),
                ),
                deadlineMs = context.retry.deadlineMs,
                sharedBudgetKey = context.operationId,
            ),
        )
    }

    private fun retryNetwork(
        context: FailureContext,
        steps: List<RecoveryStep>,
    ): FailureDecision {
        if (!context.idempotent) return final("error_network")
        if (context.retry.networkAttempts >= context.retry.maxNetworkAttempts) {
            return final("error_network")
        }
        if (context.retry.recoverySteps >= context.retry.maxRecoverySteps) {
            return final("error_network")
        }
        return FailureDecision(
            disposition = FailureDisposition.INTERNAL_RECOVERY,
            retryDelayMs = 200L shl context.retry.networkAttempts.coerceAtMost(4),
            userMessageKey = "error_network",
            plan = RecoveryPlan(
                steps = steps,
                deadlineMs = context.retry.deadlineMs,
                sharedBudgetKey = context.operationId,
            ),
        )
    }

    private fun internal(
        context: FailureContext,
        steps: List<RecoveryStep>,
        userMessageKey: String,
    ): FailureDecision {
        if (context.retry.recoverySteps >= context.retry.maxRecoverySteps) {
            return final(userMessageKey)
        }
        return FailureDecision(
            disposition = FailureDisposition.INTERNAL_RECOVERY,
            userMessageKey = userMessageKey,
            plan = RecoveryPlan(
                steps = steps,
                deadlineMs = context.retry.deadlineMs,
                sharedBudgetKey = context.operationId,
            ),
        )
    }

    private fun cancel() = FailureDecision(FailureDisposition.CANCEL_SILENT)

    private fun final(messageKey: String? = null) =
        FailureDecision(FailureDisposition.SHOW_FINAL_ERROR, userMessageKey = messageKey)

    private fun defaultMessageKey(domain: FailureDomain): String = when (domain) {
        FailureDomain.CANCEL -> "error_cancelled"
        FailureDomain.TRANSPORT,
        FailureDomain.HTTP,
        FailureDomain.API,
        -> "error_server"
        FailureDomain.AUTH -> "error_auth_required"
        FailureDomain.DEVICE -> "error_device_init"
        FailureDomain.CONTENT,
        FailureDomain.MEDIA,
        FailureDomain.PLAYBACK,
        -> "error_media"
        FailureDomain.DRM -> "error_drm"
        FailureDomain.STORAGE -> "error_storage"
        FailureDomain.UPDATE -> "error_update"
        FailureDomain.PERMISSION -> "error_permission"
        FailureDomain.IMAGE -> "error_image"
        FailureDomain.DANMAKU -> "error_danmaku"
        FailureDomain.LAN -> "error_lan"
        FailureDomain.SYSTEM -> "error_system"
        FailureDomain.LOCAL -> "error_local"
        FailureDomain.UNKNOWN -> "error_unknown"
    }

    private val NETWORK_CODES = setOf(
        FailureCodes.NET_OFFLINE,
        FailureCodes.NET_DNS,
        FailureCodes.NET_CONNECT,
        FailureCodes.NET_TIMEOUT,
        FailureCodes.NET_TLS,
        FailureCodes.NET_IO,
        FailureCodes.PLAYER_TIMEOUT,
    )

    private val TRANSIENT_HTTP_CODES = setOf(
        FailureCodes.HTTP_408,
        FailureCodes.HTTP_425,
        FailureCodes.HTTP_429,
        FailureCodes.HTTP_500,
        FailureCodes.HTTP_502,
        FailureCodes.HTTP_503,
        FailureCodes.HTTP_504,
    )

    private val MEDIA_REFRESH_CODES = setOf(
        FailureCodes.MEDIA_STREAM_EXPIRED,
        FailureCodes.MEDIA_NOT_FOUND,
        FailureCodes.PLAYER_HTTP_STATUS,
    )

    private val RETRYABLE_CODES = NETWORK_CODES + TRANSIENT_HTTP_CODES + setOf(
        FailureCodes.AUTH_SESSION_EXPIRED,
        FailureCodes.AUTH_REQUIRED,
        FailureCodes.DEVICE_UNREGISTERED,
        FailureCodes.DEVICE_PROFILE_INVALID,
        FailureCodes.MEDIA_STREAM_EXPIRED,
        FailureCodes.MEDIA_CHANGED,
        FailureCodes.MEDIA_RANGE_INVALID,
        FailureCodes.MEDIA_RANGE_NOT_SATISFIABLE,
        FailureCodes.MEDIA_DECRYPT_FAILED,
        FailureCodes.MEDIA_NOT_FOUND,
        FailureCodes.PLAYER_IO_UNSPECIFIED,
        FailureCodes.PLAYER_HTTP_STATUS,
        FailureCodes.PLAYER_DECODER_INIT_FAILED,
        FailureCodes.PLAYER_DECODER_QUERY_FAILED,
        FailureCodes.PLAYER_DECODING_FAILED,
        FailureCodes.PLAYER_FORMAT_EXCEEDS_CAPABILITIES,
        FailureCodes.PLAYER_FORMAT_UNSUPPORTED,
        FailureCodes.PLAYER_AUDIO_INIT_FAILED,
        FailureCodes.PLAYER_AUDIO_WRITE_FAILED,
        FailureCodes.PLAYER_VIDEO_FRAME_PROCESSOR_INIT_FAILED,
        FailureCodes.PLAYER_VIDEO_FRAME_PROCESSING_FAILED,
    )

    private const val MAX_RETRY_DELAY_MS = 30_000L
}
