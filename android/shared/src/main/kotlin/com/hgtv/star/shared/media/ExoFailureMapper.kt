// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.media

import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.upstream.HttpDataSource
import com.hgtv.star.core.PlaybackStage
import com.hgtv.star.core.error.AppFailure
import com.hgtv.star.core.error.FailureCodes
import com.hgtv.star.core.error.FailureDomain
import com.hgtv.star.core.error.FailureSeverity

/**
 * ExoPlayer 2.19.1 的精确错误映射。
 *
 * canonical code 使用跨平台语义名，原始 Exo 数字保留在 [AppFailure.upstreamCode]。
 */
object ExoFailureMapper {
    fun map(problem: PlaybackException, operation: String = "playback"): AppFailure {
        val code = problem.errorCode
        return AppFailure(
            domain = if (code in 6000..6999) FailureDomain.DRM else FailureDomain.PLAYBACK,
            canonicalCode = canonicalCode(code),
            operation = operation,
            stage = stageOf(code),
            endpoint = operation,
            severity = severityOf(code),
            httpStatus = httpStatusOf(problem),
            upstreamCode = code.toString(),
            upstreamMessage = problem.message?.take(220),
            cause = problem,
        )
    }

    fun canonicalCode(code: Int): String = when (code) {
        PlaybackException.ERROR_CODE_UNSPECIFIED -> FailureCodes.PLAYER_UNKNOWN
        PlaybackException.ERROR_CODE_REMOTE_ERROR -> FailureCodes.PLAYER_REMOTE_ERROR
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> FailureCodes.PLAYER_LIVE_WINDOW
        PlaybackException.ERROR_CODE_TIMEOUT -> FailureCodes.PLAYER_TIMEOUT
        PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK -> FailureCodes.PLAYER_RUNTIME_CHECK
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> FailureCodes.PLAYER_IO_UNSPECIFIED
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> FailureCodes.NET_CONNECT
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> FailureCodes.NET_TIMEOUT
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> FailureCodes.PLAYER_HTTP_CONTENT_TYPE
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> FailureCodes.PLAYER_HTTP_STATUS
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> FailureCodes.MEDIA_NOT_FOUND
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> FailureCodes.PLAYER_IO_PERMISSION_DENIED
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> FailureCodes.PLAYER_CLEARTEXT_BLOCKED
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> FailureCodes.PLAYER_RANGE_INVALID
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> FailureCodes.PLAYER_CONTAINER_MALFORMED
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> FailureCodes.PLAYER_MANIFEST_MALFORMED
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> FailureCodes.PLAYER_CONTAINER_UNSUPPORTED
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> FailureCodes.PLAYER_MANIFEST_UNSUPPORTED
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> FailureCodes.PLAYER_DECODER_INIT_FAILED
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> FailureCodes.PLAYER_DECODER_QUERY_FAILED
        PlaybackException.ERROR_CODE_DECODING_FAILED -> FailureCodes.PLAYER_DECODING_FAILED
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
            FailureCodes.PLAYER_FORMAT_EXCEEDS_CAPABILITIES
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            FailureCodes.PLAYER_FORMAT_UNSUPPORTED
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED -> FailureCodes.PLAYER_AUDIO_INIT_FAILED
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> FailureCodes.PLAYER_AUDIO_WRITE_FAILED
        PlaybackException.ERROR_CODE_DRM_UNSPECIFIED -> FailureCodes.DRM_UNSPECIFIED
        PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED -> FailureCodes.DRM_SCHEME_UNSUPPORTED
        PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED -> FailureCodes.DRM_PROVISIONING_FAILED
        PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR -> FailureCodes.DRM_CONTENT_ERROR
        PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED ->
            FailureCodes.DRM_LICENSE_ACQUISITION_FAILED
        PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION ->
            FailureCodes.DRM_DISALLOWED_OPERATION
        PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR -> FailureCodes.DRM_SYSTEM_ERROR
        PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED -> FailureCodes.DRM_DEVICE_REVOKED
        PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED -> FailureCodes.DRM_LICENSE_EXPIRED
        PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED ->
            FailureCodes.PLAYER_VIDEO_FRAME_PROCESSOR_INIT_FAILED
        PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED ->
            FailureCodes.PLAYER_VIDEO_FRAME_PROCESSING_FAILED
        else -> if (code >= PlaybackException.CUSTOM_ERROR_CODE_BASE) {
            "PLAYER_CUSTOM_$code"
        } else {
            FailureCodes.PLAYER_UNKNOWN
        }
    }

    fun stageOf(code: Int): String = when (code) {
        PlaybackException.ERROR_CODE_TIMEOUT -> PlaybackStage.TIMEOUT
        PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK -> PlaybackStage.RUNTIME
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        -> PlaybackStage.IO
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        -> PlaybackStage.PARSE
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> PlaybackStage.DECODER_INIT
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> PlaybackStage.DECODER_QUERY
        PlaybackException.ERROR_CODE_DECODING_FAILED -> PlaybackStage.DECODING
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> PlaybackStage.DECODER_LIMITS
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> PlaybackStage.DECODER_UNSUPPORTED
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        -> PlaybackStage.AUDIO_OUTPUT
        in 6000..6999 -> PlaybackStage.DRM
        PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED,
        PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
        -> PlaybackStage.FRAME_PROCESSOR
        else -> PlaybackStage.OTHER
    }

    fun retryable(code: Int): Boolean = when (code) {
        PlaybackException.ERROR_CODE_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED,
        PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
        -> true
        else -> false
    }

    private fun httpStatusOf(problem: PlaybackException): Int? {
        var current: Throwable? = problem
        var depth = 0
        while (current != null && depth++ < 8) {
            if (current is HttpDataSource.InvalidResponseCodeException) return current.responseCode
            current = current.cause
        }
        return null
    }

    private fun severityOf(code: Int): FailureSeverity = when (code) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_TIMEOUT,
        -> FailureSeverity.WARN
        in 6000..6999 -> FailureSeverity.FATAL
        else -> FailureSeverity.ERROR
    }
}
