// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.hgtv.star.core.DanmakuEmoji
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.concurrent.Executors
import com.hgtv.star.compat.memoryBytes
import com.hgtv.star.compat.lowEndTv

/**
 * 弹幕表情图的解码与缓存。
 *
 * 图片在 `assets/danmaku_emoji`（Microsoft Fluent Emoji，MIT），文件名就是表情的码位
 * （`1f602.webp` = 😂，`2764-fe0f.webp` = ❤️）；名字到文件的映射在 [DanmakuEmoji]。
 *
 * 三件事必须在这里做掉，弹幕层才能每帧只画不算：
 * 1. **按固定边长解码**：一张图按 [DECODE_PX] 解出来，画的时候再缩到当前字号需要的尺寸。
 *    字号是用户滑出来的（0.6~2 倍），跟着字号解码等于为每个缩放档位各存一份位图，
 *    144 张图乘几档就能把低端电视的内存吃掉；固定边长只存一份。
 * 2. **容量封顶的 LRU**：低内存设备 4 MiB、其余 8 MiB。一张 128×128 的 ARGB_8888 是 64 KiB，
 *    8 MiB 能装 128 张，一屏弹幕里出现的表情种类远达不到这个数。
 * 3. **解码离开主线程**：弹幕层在绘制里调 [bitmap]，拿不到就先用字形画字、并把这张图排进队列；
 *    解好后回调 [Listener]，让弹幕层重画一帧把图换上。
 *
 * 这是进程级单例：弹幕层换集、重建视图都不该把已经解好的图丢掉。
 */
internal class DanmakuEmojiStore private constructor(context: Context) {
    /** 图到位之后叫一声，弹幕层借它重画一帧。 */
    fun interface Listener { fun onEmojiDecoded() }

    private val assets = context.applicationContext.assets
    /** 与 `TvStyle.animate` / `ArtworkLoader.lowRam` 同一个判据（见 compat/Platform.kt）。 */
    private val lowRam = context.applicationContext.lowEndTv()
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(if (lowRam) LOW_RAM_BYTES else NORMAL_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.memoryBytes
    }
    /** 已经排队、还没解完的文件名：同一次绘制里反复问同一张图不会排很多遍。 */
    private val queued = Collections.synchronizedSet(mutableSetOf<String>())
    private val workers = Executors.newFixedThreadPool(if (lowRam) 1 else 2) { runnable ->
        Thread(runnable, "hgtv-emoji").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val listeners = mutableListOf<WeakReference<Listener>>()
    @Volatile private var closed = false

    /**
     * 取一张已经解好的表情图；没有就返回 null 并把解码排进队列。
     *
     * 在弹幕层的绘制里调，因此只走缓存查表（快），真正的解码在 [workers] 上。
     */
    fun bitmap(asset: String): Bitmap? {
        if (closed) return null
        cache.get(asset)?.let { return it }
        if (queued.add(asset)) workers.execute {
            val decoded = runCatching {
                assets.open("$DIR/$asset").use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            queued.remove(asset)
            if (decoded != null && !closed) cache.put(asset, decoded)
            if (decoded != null && !closed) notifyListeners()
        }
        return null
    }

    /**
     * 把这条弹幕用到的表情全部排进解码队列。
     *
     * 换集、整批重排轨道时先叫一遍：等画到它们的时候图多半已经在缓存里，
     * 屏幕上就不会先闪一下字形再换成图片。
     */
    fun prefetch(texts: Iterable<String>) {
        if (closed) return
        texts.forEach { text -> DanmakuEmoji.parts(text).forEach { part -> part.asset?.let { bitmap(it) } } }
    }

    fun addListener(listener: Listener) {
        synchronized(listeners) {
            listeners.removeAll { it.get() == null || it.get() === listener }
            listeners += WeakReference(listener)
        }
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) {
            listeners.removeAll { it.get() == null || it.get() === listener }
        }
    }

    /**
     * 通知所有弹幕层重画一帧。
     *
     * 回调统一回到主线程：解码线程上直接让 View 重画（`invalidate`）是跨线程碰视图，
     * 而弹幕层可能刚好在被移除。
     */
    private fun notifyListeners() {
        val alive = synchronized(listeners) {
            listeners.removeAll { it.get() == null }
            listeners.mapNotNull { it.get() }
        }
        if(alive.isEmpty()) return
        main.post { alive.forEach { it.onEmojiDecoded() } }
    }

    /** 进程退出前收掉线程池；单例活到进程结束，正常情况下用不上。 */
    fun close() {
        closed = true
        workers.shutdownNow()
        cache.evictAll()
    }

    companion object {
        private const val DIR = "danmaku_emoji"
        private const val LOW_RAM_BYTES = 4 * 1024 * 1024
        private const val NORMAL_BYTES = 8 * 1024 * 1024

        @Volatile private var store: DanmakuEmojiStore? = null

        /** 进程级单例：第一次取的时候建，之后一直复用。 */
        fun get(context: Context): DanmakuEmojiStore {
            store?.let { return it }
            synchronized(DanmakuEmojiStore::class.java) {
                store?.let { return it }
                return DanmakuEmojiStore(context.applicationContext).also { store = it }
            }
        }
    }
}
