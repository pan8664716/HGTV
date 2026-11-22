// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

internal data class ContentRepositoryStreamCacheEntry(val info: StreamInfo,val at: Long)

    internal fun ContentRepository.streamImpl(
        id: String,
        maxQuality: Int,
        playback: PlaybackProfile,
        queue: List<String>,
        direct: Boolean,
    ): StreamInfo {
        require(id.matches(Regex("[0-9]{1,30}")))
        // 同一集在两条路上同时被要时（起播 + 预热）只真正取一次：后来的那条等在锁上，
        // 前一条取回来之后它直接吃缓存。真实播放错误会显式作废。
        val level=requestDeviceLevel(direct)
        val cacheKey=cacheKeyOf(id,maxQuality,playback,level)
        val lock=synchronized(streamLocks) { streamLocks.getOrPut(cacheKey) { Any() } }
        synchronized(lock) {
            val hit=synchronized(streamCache) { streamCache[cacheKey] }
            if(hit!=null) return hit.info
            // 落盘缓存：冷却启动（进程被杀）、退出再进、同一集重播都走这一条。
            //
            // 取一次地址实测 500~1100ms（`net POST … wait=892ms` 是服务端），而这
            // 500~1100ms 在「已经看过这一集」的所有场景里都是白付的。缓存不按时间失效，
            // 403 / Range 异常时由 `RemoteVideo.range` 与 [forgetStreamImpl] 显式作废。
            PlaybackCache.loadUrl(cacheKey)?.let { cached ->
                if(NetLog.timing) NetLog.log("stream cache hit id=${id.takeLast(6)} quality=${cached.info.quality} age=${(System.currentTimeMillis()-cached.at)/1000}s")
                rememberStream(cacheKey,cached.info,clock())
                return cached.info
            }
            // 每个实际播放接口链接各自 20 秒（见 [ContentRepository.ADDRESS_ATTEMPT_TIMEOUT_MS]）；
            // 这里不设整轮总预算，避免前一条慢链接把后续候选链接的额度一起吃光。
            // 缓存命中不走这里，因此点「下一个」十几毫秒就能起播的那条路一分钱都不多花。
            val info=try {
                fetchStream(id,maxQuality,playback,queue,direct)
            } catch(e: AddressTimeoutException) {
                throw e
            } catch(e: IOException) {
                if(e.isAddressTimeout()) throw addressTimeout(e)
                throw e
            }
            rememberStream(cacheKey,info,clock())
            PlaybackCache.saveUrl(cacheKey,info)
            // 锁表跟着缓存一起收：这里大多是「取完就没人在等」的一次性键，
            // 不收的话每换一部剧就多留一个对象，长刷推荐会一直涨。
            synchronized(streamLocks) { if(streamLocks.size>ContentRepository.STREAM_CACHE_LIMIT*4) streamLocks.remove(cacheKey) }
            return info
        }
    }

    /**
     * 内存 / 落盘缓存与锁表的键：集 id + 请求上限 + 播放档案 + `device_level`。
     */
    private fun ContentRepository.cacheKeyOf(
        id: String,
        maxQuality: Int,
        playback: PlaybackProfile,
        level: Int,
    ): String = "$id:$maxQuality:${playback.contentType}:${playback.videoPlatform}:$level"
    private fun ContentRepository.rememberStream(key: String,info: StreamInfo,at: Long) {
        synchronized(streamCache) {
            if(streamCache.size>=ContentRepository.STREAM_CACHE_LIMIT) streamCache.keys.firstOrNull()?.let(streamCache::remove)
            streamCache[key]=ContentRepositoryStreamCacheEntry(info,at)
        }
    }

    internal fun ContentRepository.forgetStreamImpl(
        id: String,
        maxQuality: Int,
        playback: PlaybackProfile,
        direct: Boolean,
    ) {
        val level=requestDeviceLevel(direct)
        val key=cacheKeyOf(id,maxQuality,playback,level)
        synchronized(streamCache) { streamCache.remove(key) }
        PlaybackCache.invalidateUrl(key)
        // 「这条地址不认了」= 下一次取地址当成一次全新尝试，作废旧模型缓存。
        invalidateModelCache()
    }

    internal fun ContentRepository.forgetStreamAllQualitiesImpl(id: String) {
        synchronized(streamCache) {
            streamCache.keys.filter { it.startsWith("$id:") }.forEach(streamCache::remove)
        }
        PlaybackCache.invalidateUrlById(id)
        invalidateModelCache()
    }

    /** 真正去取播放地址（[streamImpl] 的缓存之外那一半）。 */
    private fun ContentRepository.fetchStream(
        id: String,
        maxQuality: Int,
        playback: PlaybackProfile,
        queue: List<String>,
        direct: Boolean,
    ): StreamInfo {
        val model=videoModelImpl(id,playback,queue,direct)
        ContentPlaybackParser.parseInlineStreamInfo(model,maxQuality)?.let { inline ->
            if(NetLog.timing) NetLog.log("video model inline id=${id.takeLast(6)} quality=${inline.quality}")
            return inline
        }
        val raw=model.opt("fallback_api")
        val fallback=when(raw) { is JSONObject -> raw.str("fallback_api"); is JSONArray -> raw.optString(0); else -> runCatching { JSONObject(raw.toString()).str("fallback_api") }.getOrDefault(raw.toString()) }
        if(!fallback.startsWith("https://")) throw IOException("播放地址格式异常")
        // 模型给了 `fallback_api` 后，真正带 `video_list` 的是它返回的那份 JSON。
        // 这一条链接同样单独拿完整的 20 秒，不复用模型请求的剩余时间。
        val info=JSONObject(
            text(
                fallback,
                client=addressClient(ADDRESS_ATTEMPT_TIMEOUT_MS),
                storeRegionHeaders=false,
            )
        )
            .optJSONObject("video_info")?.optJSONObject("data") ?: throw IOException("没有播放资源")
        return ContentPlaybackParser.parseStreamInfo(model,info,maxQuality)
    }

    private fun ContentRepository.requestDeviceLevel(direct: Boolean): Int =
        if(direct) ContentRepository.DIRECT_DEVICE_LEVEL else deviceLevel
