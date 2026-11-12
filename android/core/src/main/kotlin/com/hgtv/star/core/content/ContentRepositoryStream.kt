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
    ): StreamInfo {
        require(id.matches(Regex("[0-9]{1,30}")))
        // 同一集在两条路上同时被要时（起播 + 预热）只真正取一次：后来的那条等在锁上，
        // 前一条取回来之后它直接吃缓存。见 [ContentRepository.STREAM_CACHE_MS]。
        val cacheKey=cacheKeyOf(id,maxQuality,playback)
        val lock=synchronized(streamLocks) { streamLocks.getOrPut(cacheKey) { Any() } }
        synchronized(lock) {
            val hit=synchronized(streamCache) { streamCache[cacheKey] }
            if(hit!=null && clock()-hit.at<ContentRepository.STREAM_CACHE_MS) return hit.info
            // 落盘缓存：冷却启动（进程被杀）、退出再进、同一集重播都走这一条。
            //
            // 取一次地址实测 500~1100ms（`net POST … wait=892ms` 是服务端），而这
            // 500~1100ms 在「已经看过这一集」的所有场景里都是白付的：地址在有效期内
            // 逐字节相同。这里只缓存 [PlaybackCache.URL_FRESH_MS] 内的那一份，
            // 过期或 403 就重新取（见 `RemoteVideo.range` 与 [forgetStreamImpl]）。
            PlaybackCache.loadUrl(cacheKey)?.let { cached ->
                if(NetLog.timing) NetLog.log("stream cache hit id=${id.takeLast(6)} quality=${cached.info.quality} age=${(System.currentTimeMillis()-cached.at)/1000}s")
                rememberStream(cacheKey,cached.info,clock())
                return cached.info
            }
            // **整个取地址阶段的硬上限**（见 [ContentRepository.ADDRESS_DEADLINE_MS]）：上限从这一刻算起，
            // 之后每一次请求都只用「剩余预算」当自己的超时，余额用完立刻抛。
            // 缓存命中不走这里，因此点「下一个」十几毫秒就能起播的那条路一分钱都不多花。
            val deadline=clock()+ADDRESS_DEADLINE_MS
            val info=try {
                fetchStream(id,maxQuality,deadline,playback,queue)
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
    private fun ContentRepository.cacheKeyOf(id: String,maxQuality: Int,playback: PlaybackProfile): String =
        "$id:$maxQuality:${playback.contentType}:${playback.videoPlatform}:$deviceLevel"
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
    ) {
        val key=cacheKeyOf(id,maxQuality,playback)
        synchronized(streamCache) { streamCache.remove(key) }
        PlaybackCache.invalidateUrl(key)
        // 「这条地址不认了」= 下一次取地址当成一次全新尝试，作废旧模型缓存。
        rotateDeviceForRetryImpl()
    }

    internal fun ContentRepository.forgetStreamAllQualitiesImpl(id: String) {
        synchronized(streamCache) {
            streamCache.keys.filter { it.startsWith("$id:") }.forEach(streamCache::remove)
        }
        PlaybackCache.invalidateUrlById(id)
        rotateDeviceForRetryImpl()
    }

    internal fun ContentRepository.rotateDeviceForRetryImpl(): String = invalidateModelCache()

    /** 真正去取播放地址（[streamImpl] 的缓存之外那一半）。[deadline] 见 [ContentRepository.ADDRESS_DEADLINE_MS]。 */
    private fun ContentRepository.fetchStream(
        id: String,
        maxQuality: Int,
        deadline: Long,
        playback: PlaybackProfile,
        queue: List<String>,
    ): StreamInfo {
        val model=videoModelImpl(id,deadline,playback,queue)
        val raw=model.opt("fallback_api")
        val fallback=when(raw) { is JSONObject -> raw.str("fallback_api"); is JSONArray -> raw.optString(0); else -> runCatching { JSONObject(raw.toString()).str("fallback_api") }.getOrDefault(raw.toString()) }
        if(!fallback.startsWith("https://")) throw IOException("播放地址格式异常")
        // 这一步同样是「取地址」的一部分：模型给了 `fallback_api`，但真正带 `video_list`
        // 的是它返回的那份 JSON。余额用完就直接抛，别让它在 45 秒的默认超时上再挂一次。
        val budget=remainingOf(deadline)
        if(budget<ADDRESS_MIN_BUDGET_MS) throw addressTimeout()
        val info=JSONObject(text(fallback,client=addressClient(budget),storeRegionHeaders=false))
            .optJSONObject("video_info")?.optJSONObject("data") ?: throw IOException("没有播放资源")
        return ContentPlaybackParser.parseStreamInfo(model,info,maxQuality)
    }
