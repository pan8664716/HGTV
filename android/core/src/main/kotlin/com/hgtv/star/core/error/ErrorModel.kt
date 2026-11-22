// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core.error

/** 错误属于哪一层，不表示哪个页面。 */
enum class FailureDomain {
    CANCEL,
    TRANSPORT,
    HTTP,
    API,
    AUTH,
    DEVICE,
    CONTENT,
    PLAYBACK,
    MEDIA,
    DRM,
    STORAGE,
    UPDATE,
    PERMISSION,
    IMAGE,
    DANMAKU,
    LAN,
    SYSTEM,
    LOCAL,
    UNKNOWN,
}

enum class FailureSeverity {
    INFO,
    WARN,
    ERROR,
    FATAL,
}

/** 只描述事实，不携带 UI 动作或重试结果。 */
data class AppFailure(
    val domain: FailureDomain,
    val canonicalCode: String,
    val operation: String,
    val stage: String = "",
    val endpoint: String? = null,
    val method: String? = null,
    val severity: FailureSeverity = FailureSeverity.ERROR,
    val httpStatus: Int? = null,
    val upstreamCode: String? = null,
    val upstreamMessage: String? = null,
    val logId: String? = null,
    val host: String? = null,
    val resourceId: String? = null,
    val attempt: Int = 0,
    val debugDetail: String? = null,
    val cause: Throwable? = null,
)

/** 一次操作的共享预算；网络请求、恢复步骤和播放器恢复分别计数。 */
data class RetryContext(
    val operationId: String,
    val startedAtMs: Long,
    val deadlineMs: Long,
    val networkAttempts: Int = 0,
    val maxNetworkAttempts: Int = 5,
    val recoverySteps: Int = 0,
    val maxRecoverySteps: Int = 3,
    val decoderRebuilds: Int = 0,
    val qualityDegrades: Int = 0,
    val visitedHosts: Set<String> = emptySet(),
    val visitedUrls: Set<String> = emptySet(),
    val cancelled: Boolean = false,
) {
    fun expired(nowMs: Long): Boolean = cancelled || nowMs >= deadlineMs
}

data class FailureContext(
    val operationId: String,
    val requestId: String,
    val nowMs: Long,
    val networkAvailable: Boolean,
    val meteredNetwork: Boolean,
    val foreground: Boolean,
    val idempotent: Boolean,
    val retry: RetryContext,
    /** 服务端要求的最早重试间隔；属于本次决策上下文，不属于错误事实。 */
    val retryAfterMs: Long? = null,
    val playerState: String? = null,
)

enum class FailureDisposition {
    CANCEL_SILENT,
    INTERNAL_RECOVERY,
    WAIT_NETWORK,
    WAIT_USER,
    SHOW_FINAL_ERROR,
    DROP_AND_SKIP,
}

enum class RecoveryStepType {
    CLEAR_DNS,
    RETRY_SAME_HOST,
    RETRY_OTHER_HOST,
    REFRESH_AUTH,
    HEAL_DEVICE,
    FALLBACK_ENDPOINT,
    INVALIDATE_STREAM,
    INVALIDATE_INDEX,
    TRY_BACKUP_CDN,
    REPREPARE_MEDIA,
    REBUILD_DECODER,
    REBUILD_AUDIO,
    REBUILD_FRAME_PROCESSOR,
    DEGRADE_QUALITY,
    WAIT_NETWORK,
    WAIT_USER,
    SHOW_FINAL_ERROR,
    DROP_AND_SKIP,
}

data class RecoveryStep(
    val type: RecoveryStepType,
    val maxAttempts: Int = 1,
    val delayMs: Long = 0L,
    val required: Boolean = true,
)

data class RecoveryPlan(
    val steps: List<RecoveryStep>,
    val deadlineMs: Long,
    val sharedBudgetKey: String,
)

data class FailureDecision(
    val disposition: FailureDisposition,
    val retryDelayMs: Long? = null,
    val retryAfterMs: Long? = null,
    val userMessageKey: String? = null,
    val plan: RecoveryPlan? = null,
)

/** 页面能力模型；页面只消费它，不自行解析错误码。 */
data class FailureCapability(
    val canRetry: Boolean,
    val canChangeHost: Boolean,
    val canRefreshAddress: Boolean,
    val canTryBackupCdn: Boolean,
    val canRebuildDecoder: Boolean,
    val canDegradeQuality: Boolean,
    val canSwitchDecoder: Boolean,
    val canHealDevice: Boolean,
)

object FailureCodes {
    const val CANCEL_USER = "CANCEL_USER"
    const val CANCEL_LOCAL = "CANCEL_LOCAL"
    const val NET_OFFLINE = "NET_OFFLINE"
    const val NET_DNS = "NET_DNS"
    const val NET_CONNECT = "NET_CONNECT"
    const val NET_TIMEOUT = "NET_TIMEOUT"
    const val NET_TLS = "NET_TLS"
    const val NET_IO = "NET_IO"

    const val HTTP_400 = "HTTP_400"
    const val HTTP_401 = "HTTP_401"
    const val HTTP_403 = "HTTP_403"
    const val HTTP_404 = "HTTP_404"
    const val HTTP_405 = "HTTP_405"
    const val HTTP_408 = "HTTP_408"
    const val HTTP_409 = "HTTP_409"
    const val HTTP_410 = "HTTP_410"
    const val HTTP_412 = "HTTP_412"
    const val HTTP_413 = "HTTP_413"
    const val HTTP_415 = "HTTP_415"
    const val HTTP_416 = "HTTP_416"
    const val HTTP_422 = "HTTP_422"
    const val HTTP_425 = "HTTP_425"
    const val HTTP_429 = "HTTP_429"
    const val HTTP_499 = "HTTP_499"
    const val HTTP_500 = "HTTP_500"
    const val HTTP_501 = "HTTP_501"
    const val HTTP_502 = "HTTP_502"
    const val HTTP_503 = "HTTP_503"
    const val HTTP_504 = "HTTP_504"
    const val HTTP_505 = "HTTP_505"
    const val HTTP_OTHER_4XX = "HTTP_OTHER_4XX"
    const val HTTP_OTHER_5XX = "HTTP_OTHER_5XX"

    const val API_PARAM_INVALID = "API_PARAM_INVALID"
    const val API_REQUEST_SHAPE_INVALID = "API_REQUEST_SHAPE_INVALID"
    const val API_RISK_REJECTED = "API_RISK_REJECTED"
    const val API_COMMUNITY_UNAVAILABLE = "API_COMMUNITY_UNAVAILABLE"
    const val API_UNKNOWN = "API_UNKNOWN"

    const val DEVICE_UNREGISTERED = "DEVICE_UNREGISTERED"
    const val DEVICE_PROFILE_INVALID = "DEVICE_PROFILE_INVALID"
    const val AUTH_SESSION_EXPIRED = "AUTH_SESSION_EXPIRED"
    const val AUTH_REQUIRED = "AUTH_REQUIRED"
    const val AUTH_CODE_1 = "AUTH_CODE_1"
    const val AUTH_CODE_3 = "AUTH_CODE_3"
    const val AUTH_CODE_7 = "AUTH_CODE_7"
    const val AUTH_CODE_22 = "AUTH_CODE_22"
    const val AUTH_CODE_1045 = "AUTH_CODE_1045"
    const val AUTH_CODE_2046 = "AUTH_CODE_2046"
    const val AUTH_CODE_9001 = "AUTH_CODE_9001"
    const val CONTENT_SCHEMA_CHANGED = "CONTENT_SCHEMA_CHANGED"

    const val PLAYER_UNKNOWN = "PLAYER_UNKNOWN"
    const val PLAYER_REMOTE_ERROR = "PLAYER_REMOTE_ERROR"
    const val PLAYER_LIVE_WINDOW = "PLAYER_LIVE_WINDOW"
    const val PLAYER_TIMEOUT = "PLAYER_TIMEOUT"
    const val PLAYER_RUNTIME_CHECK = "PLAYER_RUNTIME_CHECK"
    const val PLAYER_IO_UNSPECIFIED = "PLAYER_IO_UNSPECIFIED"
    const val PLAYER_HTTP_CONTENT_TYPE = "PLAYER_HTTP_CONTENT_TYPE"
    const val PLAYER_HTTP_STATUS = "PLAYER_HTTP_STATUS"
    const val MEDIA_NOT_FOUND = "MEDIA_NOT_FOUND"
    const val PLAYER_IO_PERMISSION_DENIED = "PLAYER_IO_PERMISSION_DENIED"
    const val PLAYER_CLEARTEXT_BLOCKED = "PLAYER_CLEARTEXT_BLOCKED"
    const val PLAYER_RANGE_INVALID = "PLAYER_RANGE_INVALID"
    const val PLAYER_CONTAINER_MALFORMED = "PLAYER_CONTAINER_MALFORMED"
    const val PLAYER_MANIFEST_MALFORMED = "PLAYER_MANIFEST_MALFORMED"
    const val PLAYER_CONTAINER_UNSUPPORTED = "PLAYER_CONTAINER_UNSUPPORTED"
    const val PLAYER_MANIFEST_UNSUPPORTED = "PLAYER_MANIFEST_UNSUPPORTED"
    const val PLAYER_DECODER_INIT_FAILED = "PLAYER_DECODER_INIT_FAILED"
    const val PLAYER_DECODER_QUERY_FAILED = "PLAYER_DECODER_QUERY_FAILED"
    const val PLAYER_DECODING_FAILED = "PLAYER_DECODING_FAILED"
    const val PLAYER_FORMAT_EXCEEDS_CAPABILITIES = "PLAYER_FORMAT_EXCEEDS_CAPABILITIES"
    const val PLAYER_FORMAT_UNSUPPORTED = "PLAYER_FORMAT_UNSUPPORTED"
    const val PLAYER_AUDIO_INIT_FAILED = "PLAYER_AUDIO_INIT_FAILED"
    const val PLAYER_AUDIO_WRITE_FAILED = "PLAYER_AUDIO_WRITE_FAILED"
    const val DRM_UNSPECIFIED = "DRM_UNSPECIFIED"
    const val DRM_SCHEME_UNSUPPORTED = "DRM_SCHEME_UNSUPPORTED"
    const val DRM_PROVISIONING_FAILED = "DRM_PROVISIONING_FAILED"
    const val DRM_CONTENT_ERROR = "DRM_CONTENT_ERROR"
    const val DRM_LICENSE_ACQUISITION_FAILED = "DRM_LICENSE_ACQUISITION_FAILED"
    const val DRM_DISALLOWED_OPERATION = "DRM_DISALLOWED_OPERATION"
    const val DRM_SYSTEM_ERROR = "DRM_SYSTEM_ERROR"
    const val DRM_DEVICE_REVOKED = "DRM_DEVICE_REVOKED"
    const val DRM_LICENSE_EXPIRED = "DRM_LICENSE_EXPIRED"
    const val PLAYER_VIDEO_FRAME_PROCESSOR_INIT_FAILED = "PLAYER_VIDEO_FRAME_PROCESSOR_INIT_FAILED"
    const val PLAYER_VIDEO_FRAME_PROCESSING_FAILED = "PLAYER_VIDEO_FRAME_PROCESSING_FAILED"

    const val MEDIA_STREAM_EXPIRED = "MEDIA_STREAM_EXPIRED"
    const val MEDIA_CHANGED = "MEDIA_CHANGED"
    const val MEDIA_RANGE_INVALID = "MEDIA_RANGE_INVALID"
    const val MEDIA_RANGE_NOT_SATISFIABLE = "MEDIA_RANGE_NOT_SATISFIABLE"
    const val MEDIA_CONTENT_TYPE_INVALID = "MEDIA_CONTENT_TYPE_INVALID"
    const val MEDIA_DECRYPT_FAILED = "MEDIA_DECRYPT_FAILED"

    const val STORE_CORRUPT = "STORE_CORRUPT"
    const val STORE_NO_SPACE = "STORE_NO_SPACE"
    const val STORE_IO = "STORE_IO"
    const val SNAPSHOT_NEWER_VERSION = "SNAPSHOT_NEWER_VERSION"
    const val SNAPSHOT_CHECKSUM_FAILED = "SNAPSHOT_CHECKSUM_FAILED"

    const val UPDATE_METADATA_INVALID = "UPDATE_METADATA_INVALID"
    const val UPDATE_METADATA_EMPTY = "UPDATE_METADATA_EMPTY"
    const val UPDATE_DOWNLOAD_HTTP = "UPDATE_DOWNLOAD_HTTP"
    const val UPDATE_DOWNLOAD_SHORT = "UPDATE_DOWNLOAD_SHORT"
    const val UPDATE_CHECKSUM_FAILED = "UPDATE_CHECKSUM_FAILED"
    const val UPDATE_INSTALL_ABORTED = "UPDATE_INSTALL_ABORTED"
    const val UPDATE_INSTALL_BLOCKED = "UPDATE_INSTALL_BLOCKED"
    const val UPDATE_INSTALL_CONFLICT = "UPDATE_INSTALL_CONFLICT"
    const val UPDATE_INSTALL_INVALID = "UPDATE_INSTALL_INVALID"
    const val UPDATE_INSTALL_INCOMPATIBLE = "UPDATE_INSTALL_INCOMPATIBLE"
    const val UPDATE_INSTALL_STORAGE = "UPDATE_INSTALL_STORAGE"

    const val PERMISSION_DENIED = "PERMISSION_DENIED"
    const val SYSTEM_UNAVAILABLE = "SYSTEM_UNAVAILABLE"
    const val SYSTEM_OOM = "SYSTEM_OOM"
    const val SYSTEM_SECURITY = "SYSTEM_SECURITY"
    const val SYSTEM_LINKAGE = "SYSTEM_LINKAGE"
    const val SYSTEM_ILLEGAL_STATE = "SYSTEM_ILLEGAL_STATE"
    const val LOCAL_ARGUMENT = "LOCAL_ARGUMENT"

    const val IMAGE_HTTP = "IMAGE_HTTP"
    const val IMAGE_DECODE = "IMAGE_DECODE"
    const val IMAGE_TOO_LARGE = "IMAGE_TOO_LARGE"
    const val IMAGE_CACHE_IO = "IMAGE_CACHE_IO"
    const val DANMAKU_HTTP = "DANMAKU_HTTP"
    const val DANMAKU_PARSE = "DANMAKU_PARSE"
    const val DANMAKU_RENDER = "DANMAKU_RENDER"
    const val LAN_DISCOVERY = "LAN_DISCOVERY"
    const val LAN_CONNECT = "LAN_CONNECT"
    const val LAN_SYNC = "LAN_SYNC"

    const val UNKNOWN = "UNKNOWN"
}
