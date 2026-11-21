// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import com.hgtv.star.shared.ui.UiText
import android.os.Build
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.mediacodec.MediaCodecRenderer
import com.hgtv.star.core.*
import com.hgtv.star.shared.compat.isMeteredConnection
import com.hgtv.star.shared.debug.DebugLog
import com.hgtv.star.shared.debug.Diagnostics

fun TvMainActivity.playbackScene(): String {
    val sample=runCatching {
        val p=player
        val state=when(p?.playbackState) {
            Player.STATE_IDLE -> "idle"; Player.STATE_BUFFERING -> "buffering"
            Player.STATE_READY -> "ready"; Player.STATE_ENDED -> "ended"; else -> "none"
        }
        val position=p?.currentPosition ?: 0
        val duration=p?.duration?.coerceAtLeast(0) ?: 0
        val buffered=p?.bufferedPosition ?: 0
        "state=$state pos=${position/1000}s dur=${duration/1000}s buf=${buffered/1000}s"
    }.getOrDefault("state=unknown")
    val net=runCatching { if(connectivity.isMeteredConnection()) "mobile" else "wifi" }.getOrDefault("?")
    val fg=runCatching { "fg=$foreground autoplay=$requestedAutoplay" }.getOrDefault("fg=?")
    return "$sample videoTrack=${PlaybackDiag.tracksSelected} firstFrame=${PlaybackDiag.firstFrame}"+
        " codecErr=${PlaybackDiag.codecErrors} dropped=${PlaybackDiag.droppedFrames} underrun=${PlaybackDiag.underruns}"+
        " net=$net $fg"
}

private val decoderTableCache=mutableMapOf<String,String>()

private fun availableDecoders(mime: String): String {
    if(mime.isEmpty()) return "-"
    synchronized(decoderTableCache) { decoderTableCache[mime]?.let { return it } }
    val value=runCatching {
        val infos: List<android.media.MediaCodecInfo> = if(Build.VERSION.SDK_INT>=21) {
            runCatching {
                android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).codecInfos.toList()
            }.getOrElse { legacyDecoderInfos() }
        } else {
            legacyDecoderInfos()
        }
        val names=infos.filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime,true) }
        }.map { info ->
            val hardware=if(Build.VERSION.SDK_INT>=29) info.isHardwareAccelerated else !looksSoftware(info.name)
            val flags=runCatching {
                val caps=info.getCapabilitiesForType(mime)
                buildString {
                    if(caps.isFeatureRequired(
                            android.media.MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback,
                        )) append("!tunnel")
                    if(caps.isFeatureRequired(
                            android.media.MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback,
                        )) append("!secure")
                }
            }.getOrDefault("")
            (if(hardware) "hw:" else "sw:")+info.name+flags
        }
        if(names.isEmpty()) "none" else names.joinToString(",")
    }.getOrDefault("?")
    synchronized(decoderTableCache) { decoderTableCache[mime]=value }
    return value
}

private fun looksSoftware(name: String): Boolean {
    val lower=name.lowercase()
    return lower.startsWith("omx.google.") || lower.contains(".sw.") || lower.contains("software") ||
        lower.contains("ffmpeg") || lower.contains("avcodec")
}

private fun legacyDecoderInfos(): List<android.media.MediaCodecInfo> =
    (0 until android.media.MediaCodecList.getCodecCount()).mapNotNull {
        runCatching { android.media.MediaCodecList.getCodecInfoAt(it) }.getOrNull()
    }

private fun PlaybackDiag.fillFormatFrom(problem: Throwable?,player: Player?) {
    var current=problem; var guard=0
    while(current!=null && guard++<8) {
        if(current is MediaCodecRenderer.DecoderInitializationException) {
            if(mime.isEmpty()) mime=current.mimeType.orEmpty()
            if(decoderName.isEmpty()) decoderName=current.codecInfo?.name.orEmpty()
            break
        }
        current=current.cause
    }
    val sample=runCatching {
        player?.currentTracks?.groups?.firstOrNull { it.type==C.TRACK_TYPE_VIDEO }?.getTrackFormat(0)
    }.getOrNull()
    if(sample!=null) {
        if(codecs.isEmpty()) codecs=sample.codecs.orEmpty()
        if(width==0) { width=sample.width; height=sample.height }
        if(frameRate==0f) frameRate=sample.frameRate
        if(bitrate==0) bitrate=sample.bitrate
        if(mime.isEmpty()) mime=sample.sampleMimeType.orEmpty()
        if(container.isEmpty()) container=sample.containerMimeType.orEmpty()
    }
}

private fun formatLine(): String = buildString {
    append("mode=").append(PlaybackDiag.decoderMode)
    if(PlaybackDiag.decoderName.isNotEmpty()) append(" decoder=").append(PlaybackDiag.decoderName)
    if(PlaybackDiag.initMs>=0) append(" init=").append(PlaybackDiag.initMs).append("ms")
    append(" codec=").append(PlaybackDiag.codecs.ifEmpty { "-" })
    append(" mime=").append(PlaybackDiag.mime.ifEmpty { "-" })
    append(' ').append(PlaybackDiag.width).append('x').append(PlaybackDiag.height)
    append('@').append(PlaybackDiag.frameRate)
    append(" rot=").append(PlaybackDiag.rotation)
    append(" br=").append(PlaybackDiag.bitrate)
    if(PlaybackDiag.container.isNotEmpty()) append(" container=").append(PlaybackDiag.container)
    append(" quality=").append(PlaybackDiag.quality.ifEmpty { "-" })
}

fun stageTitle(stage: String): String =
    com.hgtv.star.shared.media.PlaybackFailures.titleOf(stage)

fun TvMainActivity.noteDegrade(plan: PlaybackRetryPlan) {
    val tier=plan.maxQuality ?: 0
    val text=UiText.get(com.hgtv.star.shared.R.string.ui_0209)+when(plan.reason) {
        DegradationKind.SAME_QUALITY_REBUILD -> "原档重建 ${playbackMaxQuality}P"
        DegradationKind.QUALITY_DOWN -> "降档 → ${tier}P"
    }
    DebugLog.record("播放",text+" · 预算["+playbackCoordinator.budgetSummary()+"]",force=true)
    PlaybackDiag.degrade()
    android.util.Log.w("HGTV-PLAY","degrade reason=${plan.reason.name}"+
        " target=${if(tier>0) "${tier}P" else "same"}"+
        " cap=${playbackMaxQuality}P mode=${playbackDecoderMode.name}"+
        " budget=[${playbackCoordinator.budgetSummary()}]")
}

fun TvMainActivity.reportPlaybackFailure(problem: Throwable?,stage: String) {
    PlaybackDiag.fillFormatFrom(problem,runCatching { player }.getOrNull())
    if(stage!="playback_codec_error") {
        PlaybackDiag.note(stage,stageTitle(stage),(problem as? PlaybackException)?.errorCode ?: 0,chainOf(problem))
    }
    val code=(problem as? PlaybackException)?.errorCode ?: 0
    val codeName=runCatching { (problem as? PlaybackException)?.errorCodeName.orEmpty() }.getOrDefault("")
    val codeText=when {
        code==0 -> ""
        codeName.isBlank() -> " · 错误码 $code"
        else -> " · 错误码 $code($codeName)"
    }
    val first=PlaybackDiag.claim(stage,code)
    if(first && stage!="playback_codec_error") {
        DebugLog.record("播放",stageTitle(stage)+" · $stage$codeText"+(if(problem!=null) " · "+chainOf(problem) else ""),force=true)
    }
    refreshDebugOverlay()
    if(!first) return
    val episodeId=PlaybackDiag.episodeId
    val episodeIndex=PlaybackDiag.episodeIndex
    val episodeCount=PlaybackDiag.episodeCount
    val seriesTitle=PlaybackDiag.seriesTitle
    val decoderName=PlaybackDiag.decoderName
    val mime=PlaybackDiag.mime
    val scene=playbackScene()
    val failedDecoder=decoderOf(problem)
    val errorChain=chainOf(problem)
    val title=stageTitle(stage)
    val degradeInfo=runCatching {
        val policy=sessionPlayback
        val info=video?.info
        "cap=${playbackMaxQuality}P req=${info?.requestedQuality ?: 0}P actual=${info?.actualQuality ?: 0}P"+
            " level=${repository.deviceLevel} mode=${playerDecoderMode().name}"+
            " plan=${policy.lastReason?.name ?: "-"} hint=${policy.manualHint?.take(40) ?: "-"}"+
            " budget=[${policy.budgetSummary()}] avail=${info?.available?.joinToString(",") ?: "-"}"
    }.getOrDefault("")
    runCatching {
        io.execute {
            runCatching {
                val detail=buildString {
                    append("stage=").append(stage)
                    if(code!=0) append(" code=").append(code)
                    if(codeName.isNotBlank()) append('/').append(codeName)
                    append(" ep=").append(episodeIndex+1).append('/').append(episodeCount)
                    append(" id=").append(episodeId)
                    append(' ').append(formatLine())
                    val decoder=failedDecoder.ifEmpty { decoderName }
                    if(decoder.isNotEmpty() && decoder!=decoderName) append(" failedDecoder=").append(decoder)
                    append(" decoders=").append(availableDecoders(mime))
                    if(degradeInfo.isNotEmpty()) append(" degrade=").append(degradeInfo)
                    append(' ').append(scene)
                    append(" dev=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    append(" os=").append(Build.VERSION.RELEASE).append('(').append(Build.VERSION.SDK_INT).append(')')
                    append(" app=").append(AppVersion.name).append('(').append(AppVersion.code).append(')')
                    append(" title=").append(seriesTitle.take(24))
                    append(" err=").append(errorChain)
                }
                android.util.Log.w("HGTV","Playback failure stage=$stage $title | ${detail.take(900)}")
            }
        }
    }
}
