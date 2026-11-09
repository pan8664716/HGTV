// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.media

import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.source.ProgressiveMediaSource

/**
 * 换媒体源：**先停、再挂**。
 *
 * 直接 `setMediaSource` 会让 ExoPlayer 走 `stopInternal → resetInternal → disableRenderer`，
 * 在**正在出帧**的解码器上 flush 时，部分厂商实现会抛
 * `MediaCodec.CodecException: Error 0x80000000`（三星 Exynos 上稳定复现），
 * 紧接着整条链路报 4003 解码失败——用户看到的就是「换个地址 / 换一集就放不了」。
 *
 * 先 `stop()` 让渲染器按正常顺序释放编解码器（状态回到 IDLE），再把新源挂上去，
 * 起播路径就与「第一次起播」完全一致。`PlayerView.setKeepContentOnPlayerReset(true)`
 * 会保留上一帧，因此观感上只是画面停一拍，不会黑一下。
 */
fun ExoPlayer.swapMediaSource(
    mediaId: String,
    url: String,
    dataSource: DataSource.Factory,
) {
    runCatching { stop() }
    setMediaSource(
        ProgressiveMediaSource.Factory(dataSource).createMediaSource(
            MediaItem.Builder().setMediaId(mediaId).setUri(url).build()
        )
    )
}
