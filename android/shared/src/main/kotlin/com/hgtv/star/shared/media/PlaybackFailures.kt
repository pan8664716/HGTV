// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.media

import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.ExoPlaybackException
import com.google.android.exoplayer2.mediacodec.MediaCodecRenderer
import com.hgtv.star.core.FailureAttribution
import com.hgtv.star.core.MediaKind
import com.hgtv.star.core.StreamInfo
import com.hgtv.star.core.VideoDecoderMode

/**
 * 一次播放失败的**归因**：这条错误落在哪条轨上、该按哪一档、哪些档位还能换。
 *
 * ## 为什么放在 `:shared`
 *
 * 这段判断原先只写在电视端的 `MainActivityPlayback.kt` 里（`playbackMediaKind` /
 * `playbackAttribution`）。手机端要做同一件事时只有两条路：抄一份，或者抽出来。
 * 抄一份的后果是可预见的——两端对「音频失败」的判定迟早分叉，一端给出「切换为软解」
 * 这种对音频完全无效的按钮，另一端不给。
 *
 * 因此**判断只留一份**，两端都调这里；决策仍由 `:core` 的
 * [com.hgtv.star.core.PlaybackDegradation] 做（纯逻辑、有单测）。
 */
object PlaybackFailures {

    /**
     * 这条错误属于视频轨还是音频轨。
     *
     * 顺序是「先看渲染器给的 mime，再看渲染器名字，最后看解码器初始化异常里的 mime」：
     * ExoPlayer 的 `PlaybackException` 在外层不一定带 `rendererFormat`（例如 4001 由
     * `DecoderInitializationException` 包着抛上来），只看最外层会把音频失败判成视频。
     *
     * 判不出来时**按视频处理**：视频占绝大多数，而按视频处理最多多给两颗按钮，
     * 按音频处理则会让真正的视频故障只剩一句「换一部」。
     */
    fun mediaKind(problem: Throwable?): MediaKind {
        var current = problem
        var guard = 0
        while (current != null && guard++ < 8) {
            if (current is ExoPlaybackException) {
                val mime = current.rendererFormat?.sampleMimeType.orEmpty()
                if (mime.startsWith("audio/")) return MediaKind.AUDIO
                if (mime.startsWith("video/")) return MediaKind.VIDEO
                val name = current.rendererName.orEmpty()
                if (name.contains("Audio", true)) return MediaKind.AUDIO
                if (name.contains("Video", true)) return MediaKind.VIDEO
            }
            if (current is MediaCodecRenderer.DecoderInitializationException) {
                val mime = current.mimeType.orEmpty()
                if (mime.startsWith("audio/")) return MediaKind.AUDIO
                if (mime.startsWith("video/")) return MediaKind.VIDEO
            }
            current = current.cause
        }
        return MediaKind.VIDEO
    }

    /**
     * 把这一次失败整理成策略能吃的归因。
     *
     * 档位与可用档位来自**这一次真正取到的流**（[StreamInfo]），不是用户设置：
     * 用户设 1080、CDN 只给 480 时，失败的其实是 480 那条，降档目标也只能从 480 往下找。
     *
     * 没有结构化档位（例如预热命中的旧对象）时退化成「只有当前这一档」：
     * 策略会因此降无可降，直接落到错误页——比瞎猜一个 480 安全。
     */
    fun attribution(
        problem: Throwable?,
        code: Int,
        info: StreamInfo?,
        fallbackQuality: Int,
        mode: VideoDecoderMode,
    ): FailureAttribution {
        val quality = info?.actualQuality?.takeIf { it > 0 } ?: fallbackQuality
        val available = info?.available?.takeIf { it.isNotEmpty() } ?: listOf(quality)
        return FailureAttribution(
            media = mediaKind(problem),
            errorCode = code,
            currentQuality = quality,
            currentMode = mode,
            available = available,
        )
    }

    /**
     * 这一次失败属于哪个**稳定阶段**（聚合键）。
     *
     * 与电视端 `playbackStage` 原本那一份逐条一致：错误码分段 → 具体解码码 → 兜底。
     * 阶段是调试面板的归并键，也是错误页标题的来源，因此**必须是常量**：
     * 一旦掺进集号、解码器名、错误码，归并出来就是一堆碎渣。
     *
     * 看门狗 / 画面恢复这类**没有 `PlaybackException`** 的失败由调用方自带 stage
     *（电视端的 `PlaybackDiagnosticException.stage`），这里只处理能从异常本身判出来的。
     */
    fun stageOf(problem: Throwable?): String {
        val playback = problem as? PlaybackException
        if (playback != null) return ExoFailureMapper.stageOf(playback.errorCode)
        return when (problem) {
            is java.io.IOException -> com.hgtv.star.core.PlaybackStage.IO
            is OutOfMemoryError -> com.hgtv.star.core.PlaybackStage.OOM
            else -> com.hgtv.star.core.PlaybackStage.OTHER
        }
    }

    /**
     * 阶段 → 一句稳定的话（错误页 / 调试面板的标题）。
     *
     * 两端共用这一张表：同一个故障在电视与手机上必须是同一句标题，否则用户照着
     * 电视上的说法描述问题，手机上看到的却是另一句。
     */
    fun titleOf(stage: String): String = com.hgtv.star.core.PlaybackStage.titleOf(stage)

    /**
     * 这条错误值不值得「等一会儿再来一次」。
     *
     * 判据两端共用（电视端原本内联在 `reportPlaybackFailure` 里，手机端抄了一份）：
     *
     * * 2xxx（网络 / IO）与超时：瞬态，重试通常就回来了；
     * * 5xxx（音频输出起不来）：多数是音频 HAL 被别的应用占着，等几秒就好；
     * * 普通 `IOException` 同上；
     * * 解码类（4xxx）**不在其中**：它走降级决策（同档重建 / 降一档），
     *   盲目重试只会把同一颗解码器再撞一次。
     */
    fun isRetryable(problem: Throwable?): Boolean {
        val code = (problem as? PlaybackException)?.errorCode
        if (code == null) return problem is java.io.IOException
        return ExoFailureMapper.retryable(code)
    }

    /** 同上，只给错误码的入口（手机端已经拿到 `errorCode`，不必再包一层异常）。 */
    fun isRetryableCode(code: Int): Boolean = ExoFailureMapper.retryable(code)

    // ---------------------------------------------------------------- 自愈判据
    //
    // 下面四条原先只写在电视端（`streamExpiredOf` / `videoChangedOf` / `transportDeadOf`），
    // 手机端根本没有这一层，于是同一个「地址过期」在电视上自己救回来了、在手机上直接死掉。
    // 判断本体收在这里，两端只负责执行。

    /** 这一串异常里有没有「播放地址已失效」（HTTP 403 / 404、签名时效）。 */
    fun expired(problem: Throwable?): Boolean =
        hasCause(problem) { it is com.hgtv.star.core.StreamExpiredException }

    /** 这一集的文件换过了：落盘的索引与首块是上一版的，`Content-Range` 总长对不上。 */
    fun videoChanged(problem: Throwable?): Boolean =
        hasCause(problem) { it is com.hgtv.star.core.VideoChangedException }

    /** 视频 CDN 的域名解析不出来。 */
    fun hostUnresolved(problem: Throwable?): Boolean =
        hasCause(problem) { it is java.net.UnknownHostException }

    /**
     * 名字解析出来了，但**连接层就没通**：超时 / 被拒 / 路由不可达 / TLS 握手失败。
     *
     * 与 [hostUnresolved] 分开只为日志能说清坏在 DNS 的「回答」上还是「路」上；
     * 自愈动作完全一样（清地址 + 清 DNS + 清连接池）。
     */
    fun transportDead(problem: Throwable?): Boolean = hasCause(problem) { it.isDeadTransport() }

    /** 取地址 / 连接层超时：界面应立即作废旧缓存并重试，不能当成用户取消。 */
    fun timedOut(problem: Throwable?): Boolean = hasCause(problem) {
        it is com.hgtv.star.core.AddressTimeoutException ||
            it is java.net.SocketTimeoutException ||
            (it is java.io.InterruptedIOException &&
                it.message.orEmpty().contains("timeout",ignoreCase=true))
    }

    /**
     * 自愈前把「连接层缓存」一起清掉：视频域名的解析结果 + 进程级连接池。
     *
     * 为什么不能只丢播放地址：地址是按集签发的，但 **host 常常没变**（同一部剧的相邻几集
     * 大多落在同一个 CDN shard 上）。重取回来的还是那个域名 → 又命中 DNS 里那份十分钟前的
     * 坏 IP → 又复用连接池里那条半死连接 → 下一发照样卡满超时。
     * 用户读到的就是「重试多少次都一样，换一集立刻好」。
     *
     * **只清 idle 连接**（`evictAll` 就是这个语义）：在途那条由调用方关掉旧的
     * `RemoteVideo` 时一起取消，两件事合起来才等于「下一条连接一定重新建」。
     */
    fun dropTransportCaches(hosts: Collection<String>) {
        for (host in hosts) {
            if (host.isEmpty()) continue
            runCatching { com.hgtv.star.core.HgtvDns.invalidate(host) }
        }
        runCatching { com.hgtv.star.shared.data.PlaybackTransport.evictIdleConnections() }
    }

    /** 顺着 cause 链找，深度上限 8：ExoPlayer 会套两三层包装，写死上限能防环形 cause。 */
    private fun hasCause(problem: Throwable?, predicate: (Throwable) -> Boolean): Boolean {
        var current = problem
        var depth = 0
        while (current != null && depth < 8) {
            if (predicate(current)) return true
            current = current.cause
            depth++
        }
        return false
    }

    private fun Throwable.isDeadTransport(): Boolean = when (this) {
        // 顺序有意义：`SocketTimeoutException` / `ConnectException` 都是 `IOException`，
        // 具体类型必须排在笼统判断之前。
        is com.hgtv.star.core.AddressTimeoutException,
        is java.net.SocketTimeoutException,
        is java.net.ConnectException,
        is java.net.NoRouteToHostException,
        is javax.net.ssl.SSLException -> true
        // 整调用超时（`callTimeout`）同样是**连接层假死**：写得出、读不回。
        // 只看消息带 `timeout` 的，别把主动取消算进来。
        is java.io.InterruptedIOException -> message.orEmpty().contains("timeout", ignoreCase = true)
        else -> false
    }
}
