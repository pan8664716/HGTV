// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.hgtv.star.core.AppConfig
import com.hgtv.star.core.PlaybackCache
import com.hgtv.star.core.RemoteVideo

/**
 * 推荐流「相邻几部」的预热结果（与电视端 `MainActivityPreload` 同一个思路，手机化之后只留必要部分）。
 *
 * ## 为什么需要它
 *
 * 手机推荐流是「上下滑换一部」。滑过去那一下如果才开始取详情 → 取地址 → 解析索引 → 拉首块，
 * 用户看到的就是一次黑屏 + 转圈（实测 500~1500ms）。电视端为此做了双向窗口预热
 * （`core/SlideCache.kt`，纯逻辑、有单测），手机这里把**同一份窗口规则**接上，
 * 只是预热结果的存放更省：手机上同时只留 2 份准备好的 [RemoteVideo]。
 *
 * ## 两级
 *
 * * 地址级（`CacheLevel.ADDRESS` / `META`）：只把 `stream()` 调一遍，让地址落进
 *   [PlaybackCache]。不占额外内存，滑过去时至少省掉一次取地址往返；
 * * 完整级（`CacheLevel.FULL`）：额外 `prepare()` 出索引与首块，滑过去只差挂媒体源。
 *
 * ## 谁负责关
 *
 * 取走（[take]）之后由播放方负责关；被淘汰 / 清空时这里自己关（延后 1 秒，
 * 等可能还挂着的 MediaSource 先 release 完）。
 */
object PhonePreload {

    private class Entry(
        val seriesId: String,
        val episodeId: String,
        /** 预热时的实际档位：比当前生效上限高的一律不接（同 `PhoneSession.takeNext`）。 */
        val quality: Int,
        val remote: RemoteVideo,
        val at: Long,
    )

    /** 同时留几份「完整预热」。手机上 2 份已经覆盖主方向一步 + 回看一步。 */
    private const val MAX_FULL = AppConfig.Playback.Preload.PHONE_MAX_FULL

    private val lock = Any()
    private val slots = LinkedHashMap<String, Entry>()

    /** 记下一份准备好的结果（同一部剧只留最新的一份）。 */
    fun put(seriesId: String, episodeId: String, remote: RemoteVideo) {
        val entry = Entry(
            seriesId = seriesId,
            episodeId = episodeId,
            quality = remote.info.actualQuality.takeIf { it > 0 } ?: remote.info.requestedQuality,
            remote = remote,
            at = android.os.SystemClock.elapsedRealtime(),
        )
        val dropped = ArrayList<RemoteVideo>(1)
        synchronized(lock) {
            slots.remove(seriesId)?.let { dropped += it.remote }
            slots[seriesId] = entry
            while (slots.size > MAX_FULL) {
                val oldest = slots.keys.first()
                slots.remove(oldest)?.let { dropped += it.remote }
            }
        }
        dropped.forEach { closeLater(it) }
        if (com.hgtv.star.shared.BuildConfig.DEBUG) {
            android.util.Log.i("HGTV-PHONE", "preload put series=$seriesId episode=$episodeId")
        }
    }

    /**
     * 取走这一部这一集的预热结果；没有 / 已过期 / 档位不对都返回 null。
     *
     * 取走即从槽位移除：同一份 [RemoteVideo] 被挂两次会让第二个 MediaSource 读一个已关闭的传输。
     */
    fun take(seriesId: String, episodeId: String, maxQuality: Int): RemoteVideo? {
        val entry: Entry
        synchronized(lock) {
            val hit = slots[seriesId] ?: return null
            if (hit.episodeId != episodeId) return null
            if (maxQuality > 0 && hit.quality > maxQuality) {
                // 自动降档之后接一份更高档的预热，等于把刚降下来的档位又顶回去。
                slots.remove(seriesId)
                closeLater(hit.remote)
                return null
            }
            if (android.os.SystemClock.elapsedRealtime() - hit.at > PlaybackCache.URL_FRESH_MS) {
                slots.remove(seriesId)
                closeLater(hit.remote)
                return null
            }
            slots.remove(seriesId)
            entry = hit
        }
        return entry.remote
    }

    /** 丢掉某一部的预热（换剧 / 手动换清晰度）。 */
    fun drop(seriesId: String) {
        val removed = synchronized(lock) { slots.remove(seriesId) }
        removed?.let { closeLater(it.remote) }
    }

    /** 全清（离开推荐页 / 宿主销毁）。 */
    fun clear() {
        val all = synchronized(lock) {
            val copy = slots.values.map { it.remote }
            slots.clear()
            copy
        }
        all.forEach { closeLater(it) }
    }

    private fun closeLater(remote: RemoteVideo) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            runCatching { remote.close() }
        }, AppConfig.Playback.Preload.PHONE_CLOSE_DELAY_MS)
    }
}
