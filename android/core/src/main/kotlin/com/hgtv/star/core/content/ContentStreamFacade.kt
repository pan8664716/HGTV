// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 取一集的播放地址。
 *
 * 全站只有一种视频编码（HEVC），因此这里没有「挑哪条编码」这一维：
 * 上限之内取最高的一档，上限之内没有就取最低一档（标注「兼容资源」）。
 */
fun ContentRepository.stream(
    id: String,
    maxQuality: Int = 1080,
    playback: PlaybackProfile = PlaybackProfile(),
    queue: List<String> = emptyList(),
): StreamInfo = streamImpl(id,maxQuality,playback,queue)

/**
 * [maxQuality] 必须与当初 [stream] 用的那个一致，且**读的是当前档位**：
 * 键里带着 `device_level`，降档重试时要按新的上限去删，否则删的是另一条
 *（真正那条还留在盘上，下一次取地址又命中它，白重试一轮）。
 */
fun ContentRepository.forgetStream(
    id: String,
    maxQuality: Int = 1080,
    playback: PlaybackProfile = PlaybackProfile(),
) = forgetStreamImpl(id,maxQuality,playback)

/**
 * 这一集**所有档位**的地址都不认了。
 *
 * 用在「队列里那一集失败」这条路上：那一刻只知道是哪一集，不知道它当初是按哪个上限
 * 预取的（预取时的上限可能已经被降档改过，`device_level` 也可能变过），
 * 精确键算不出来——[forgetStream] 用错键就等于什么都没删，重切时原样命中同一条
 * 坏地址，第二次必然还是 403。
 *
 * 内存缓存按 `集:` 前缀整体清（键是明文，能前缀匹配）；落盘按集 id 清
 * （见 `PlaybackCache.invalidateUrlById`）。多删几条的代价只是下一次多一次往返。
 */
fun ContentRepository.forgetStreamAllQualities(id: String) =
    forgetStreamAllQualitiesImpl(id)

/**
 * 用户侧「再试一次」时作废当前设备签发的播放模型缓存。
 *
 * 设备号只能由平台注册下发；身份失效时由 `DeviceHealer` 重新注册。
 * 这里保留原方法名供两端调用，但不再伪造新的 `device_id`。
 */
fun ContentRepository.rotateDeviceForRetry(): String =
    rotateDeviceForRetryImpl()
