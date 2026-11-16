// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.architecture

import com.hgtv.star.core.ContentRepository
import com.hgtv.star.core.PlaybackCache
import com.hgtv.star.core.PlaybackProfile
import com.hgtv.star.core.RemoteVideo
import com.hgtv.star.core.StreamInfo
import com.hgtv.star.core.forgetStreamAllQualities
import com.hgtv.star.core.stream
import com.hgtv.star.shared.data.PlaybackTransport
import okhttp3.OkHttpClient

/**
 * TV / Phone 共用的播放源获取门面。
 *
 * 页面仍然负责何时请求、展示什么状态、以及把准备好的 [RemoteVideo] 挂到播放器；
 * 这里统一三件容易写漏的事：
 * * 取地址始终走同一组参数；
 * * 本地准备失败时一定关闭已经创建的 [RemoteVideo]；
 * * 地址 / 索引作废使用同一个键与顺序。
 */
class PlaybackSourceCoordinator(
    private val repository: ContentRepository,
    private val client: OkHttpClient = PlaybackTransport.client,
) {
    fun stream(
        episodeId: String,
        maxQuality: Int,
        playback: PlaybackProfile,
        queue: List<String> = emptyList(),
        direct: Boolean = false,
    ): StreamInfo = repository.stream(episodeId,maxQuality,playback,queue,direct)

    /**
     * 准备一份可挂到播放器的源。
     *
     * [beforePrepare] 用于保留页面侧的必要差异（例如调试故障注入），它在
     * `RemoteVideo` 创建之后、真正读索引之前执行。任何异常都会先关闭这一份源。
     */
    fun prepare(
        episodeId: String,
        info: StreamInfo,
        beforePrepare: ((RemoteVideo) -> Unit)? = null,
    ): RemoteVideo {
        val remote=RemoteVideo(client,info,info.mediaKey(episodeId))
        return try {
            beforePrepare?.invoke(remote)
            remote.prepare()
            remote
        } catch(problem: Throwable) {
            runCatching { remote.close() }
            throw problem
        }
    }

    fun load(
        episodeId: String,
        maxQuality: Int,
        playback: PlaybackProfile,
        queue: List<String> = emptyList(),
        direct: Boolean = false,
        beforePrepare: ((RemoteVideo) -> Unit)? = null,
    ): RemoteVideo = prepare(
        episodeId,
        stream(episodeId,maxQuality,playback,queue,direct),
        beforePrepare,
    )

    /**
     * 创建并预热一份源。
     *
     * [warm] 由调用方决定预热级别（地址级已经由 [stream] 完成，这里是元数据或首块），
     * 预热失败时同样保证关闭这一份源。
     */
    fun warm(
        episodeId: String,
        info: StreamInfo,
        warm: (RemoteVideo) -> Unit = { it.warm() },
    ): RemoteVideo {
        val remote=RemoteVideo(client,info,info.mediaKey(episodeId))
        return try {
            warm(remote)
            remote
        } catch(problem: Throwable) {
            runCatching { remote.close() }
            throw problem
        }
    }

    /** 丢掉这一集的地址与索引；[info] 为空时只清地址。 */
    fun invalidate(episodeId: String, info: StreamInfo? = null) {
        runCatching { repository.forgetStreamAllQualities(episodeId) }
        info?.let { runCatching { PlaybackCache.invalidateIndex(it.mediaKey(episodeId)) } }
    }

    fun host(info: StreamInfo): String =
        runCatching { java.net.URI(info.url).host }.getOrNull().orEmpty()
}
