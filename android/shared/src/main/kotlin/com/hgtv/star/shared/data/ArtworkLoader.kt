// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data
import com.hgtv.star.core.body
import com.hgtv.star.core.connectionPool
import com.hgtv.star.core.AppConfig
import com.hgtv.star.core.dispatcher
import com.hgtv.star.core.executorService
import com.hgtv.star.core.protocol
import com.hgtv.star.shared.debug.Diagnostics


import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import okhttp3.Call
import com.hgtv.star.core.ArtworkUrl
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ref.WeakReference
import java.util.PriorityQueue
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import com.hgtv.star.shared.compat.memoryBytes
import com.hgtv.star.shared.compat.lowEndTv

class ArtworkLoader(context: Context) {
    companion object {
        const val PRIORITY_FOCUSED = 0
        const val PRIORITY_VISIBLE = 1
        const val PRIORITY_NEARBY = 2
        const val PRIORITY_PREFETCH = 3
        private const val MAX_PENDING = AppConfig.Artwork.LOADER_MAX_PENDING

        private const val MAX_REQUESTS = AppConfig.Artwork.LOADER_MAX_REQUESTS

        private const val MAX_IDLE_CONNECTIONS = AppConfig.Artwork.LOADER_MAX_IDLE_CONNECTIONS

        private const val MAX_BYTES = AppConfig.Artwork.LOADER_MAX_DECODE_BYTES

        private const val DEFAULT_WORKERS = AppConfig.Artwork.LOADER_WORKERS

        private const val HEAP_TIGHT_BYTES = AppConfig.Artwork.LOADER_HEAP_TIGHT_BYTES
    }

    private data class Key(val url: String, val tier: Int)

    private fun tierOf(targetWidth: Int) = if (targetWidth > 400) 640 else 320

    private fun normalize(widthPx: Int, heightPx: Int): Pair<Int, Int> {
        val scale = min(
            1.0,
            AppConfig.Artwork.LOADER_MAX_DECODE_DIMENSION.toDouble() / max(widthPx, heightPx),
        )
        val rawWidth = max(1, (widthPx * scale).toInt())
        val rawHeight = max(1, (heightPx * scale).toInt())
        val stepped = ArtworkUrl.decodeWidth(rawWidth, rawHeight)
        val height = if (rawWidth > 0) max(1, (rawHeight.toLong() * stepped / rawWidth).toInt()) else rawHeight
        return stepped to height
    }
    private class Binding(view: ImageView, val owner: Long, val url: String, var priority: Int,
                          val width: Int, val height: Int) {
        val view = WeakReference(view)
        var key: Key? = null
        var applied: Key? = null
        var failed: Key? = null
        var blankAt = 0L
        lateinit var layout: View.OnLayoutChangeListener
        lateinit var attach: View.OnAttachStateChangeListener
    }
    private class Job(val key: Key, val owner: Long, var priority: Int, val order: Long,
                      var targetWidth: Int = 0, var targetHeight: Int = 0) {
        val listeners = linkedSetOf<Binding>()
        val queuedAt = android.os.SystemClock.elapsedRealtime()
        var distanceRank = Int.MAX_VALUE / 2
        @Volatile var cancelled = false
        @Volatile var call: Call? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val timing = ArtworkTiming()
    private val lowRam = context.applicationContext.lowEndTv() ||
        Runtime.getRuntime().maxMemory() <= HEAP_TIGHT_BYTES
    private val maxHeapBytes = Runtime.getRuntime().maxMemory()
    private val cacheBytes: Int = run {
        val share = if (lowRam) {
            maxHeapBytes / AppConfig.Artwork.LOADER_LOW_RAM_HEAP_DIVISOR
        } else {
            maxHeapBytes / AppConfig.Artwork.LOADER_NORMAL_HEAP_DIVISOR
        }
        val floorBytes = if (lowRam) {
            AppConfig.Artwork.LOADER_LOW_RAM_FLOOR_BYTES
        } else {
            AppConfig.Artwork.LOADER_NORMAL_FLOOR_BYTES
        }
        val ceilingBytes = if (lowRam) {
            AppConfig.Artwork.LOADER_LOW_RAM_CEILING_BYTES
        } else {
            AppConfig.Artwork.LOADER_NORMAL_CEILING_BYTES
        }
        share.coerceIn(floorBytes, ceilingBytes).toInt()
    }
    private val cache = object : LruCache<Key, Bitmap>(cacheBytes) {
        override fun sizeOf(key: Key, value: Bitmap) = value.memoryBytes
        override fun entryRemoved(evicted: Boolean, key: Key, oldValue: Bitmap, newValue: Bitmap?) {
            if (evicted) timing.evicted()
        }
    }
    private val disk = ArtworkCache(File(context.applicationContext.cacheDir, "artwork-v4").also {
        runCatching {
            it.parentFile?.listFiles { f -> f.name.startsWith("artwork-v") && f.name != it.name }
                ?.forEach { old -> old.deleteRecursively() }
        }
    })
    private val events = object : EventListener() {
        override fun connectStart(call: Call, inetSocket: InetSocketAddress, proxy: Proxy) {
            timing.connectionNew()
        }

        override fun secureConnectStart(call: Call) { timing.tlsHandshake() }
    }
    private val httpRef = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        com.hgtv.star.core.HgtvTls.configure(OkHttpClient.Builder())
            .connectTimeout(AppConfig.Network.IMAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(AppConfig.Network.IMAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(AppConfig.Network.IMAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(AppConfig.Network.IMAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .dispatcher(okhttp3.Dispatcher().apply {
                maxRequests = MAX_REQUESTS
                maxRequestsPerHost = MAX_REQUESTS
            })
            .connectionPool(okhttp3.ConnectionPool(MAX_IDLE_CONNECTIONS, 5, TimeUnit.MINUTES))
            .eventListener(events)
            .build()
    }
    private val http: OkHttpClient get() = httpRef.value

    private val workerOverride: Int? =
        if (!ArtworkTiming.enabled) null
        else runCatching { File(context.filesDir, "workers").readText().trim().toInt() }
            .getOrNull()?.takeIf { it in 1..64 }
    private val workerCount = when {
        workerOverride != null -> workerOverride
        lowRam -> 3
        maxHeapBytes < 256L * 1024 * 1024 -> 4
        else -> DEFAULT_WORKERS
    }
    private val workers = Executors.newFixedThreadPool(workerCount) { runnable ->
        Thread(runnable, "hgtv-artwork").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val signal = Object()
    private val pending = PriorityQueue<Job>(
        16,
        object : Comparator<Job> {
            override fun compare(a: Job, b: Job): Int {
                if (a.priority != b.priority) return if (a.priority < b.priority) -1 else 1
                if (a.distanceRank != b.distanceRank) return if (a.distanceRank < b.distanceRank) -1 else 1
                return if (a.order < b.order) -1 else if (a.order > b.order) 1 else 0
            }
        })
    private val downloaded = HashSet<Key>()
    private val jobs = mutableMapOf<Key, Job>()
    /**
     * 只用于按 View 查当前绑定，**绝不直接遍历**。
     *
     * `WeakHashMap` 的迭代器在旧 Dalvik 上会被 GC 清 key 打断，线上表现为
     * `NoSuchElementException`。需要遍历时统一读 [activeBindings]。
     */
    private val bindingByView = WeakHashMap<ImageView, Binding>()
    private val activeBindings = linkedSetOf<Binding>()
    private val waiting = HashSet<Binding>()
    @Volatile private var refillScheduled = false
    private var owner = 0L
    private var order = 0L
    @Volatile private var closed = false
    @Volatile private var oomLogged = false
    @Volatile private var networkUnavailable: Throwable? = null

    fun attachTiming(file: java.io.File) {
        timing.attach(file)
        timing.attachVerbose(file.parentFile?.let { java.io.File(it, "verbose") })
    }

    fun snapshot(reason: String) = timing.mark(reason)

    init {
        timing.context = { timingContext() }
        timing.loaderLock(signal)
        ArtworkTiming.WORKER_HINT=workerCount
        repeat(workerCount) { workers.execute(::work) }
    }

    private fun timingContext(): String {
        val size = cache.size(); val max = cache.maxSize()
        val percent = if (max > 0) size.toLong() * 100 / max else 0L
        val queued = synchronized(signal) { pending.size }
        return "workers=$workerCount cache=${size / 1048576}MB/${max / 1048576}MB($percent%) queue=$queued " +
            "diskFiles=${runCatching { disk.count() }.getOrDefault(-1)}"
    }

    fun load(view: ImageView, url: String, priority: Int = PRIORITY_VISIBLE, widthPx: Int = 0, heightPx: Int = 0) {
        if (closed) return
        val previous = bindingByView[view]
        if (previous != null && previous.owner == owner && previous.url == url && previous.width == widthPx && previous.height == heightPx) {
            previous.priority = priority.coerceIn(PRIORITY_FOCUSED, PRIORITY_PREFETCH)
            previous.failed = null
            request(previous)
            return
        }
        if (previous != null) unbind(previous)
        view.setImageDrawable(null)
        if (url.isBlank()) return
        val binding = Binding(view, owner, url, priority.coerceIn(PRIORITY_FOCUSED, PRIORITY_PREFETCH), widthPx, heightPx)
        binding.layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> request(binding) }
        binding.attach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { request(binding) }
            override fun onViewDetachedFromWindow(v: View) { unbind(binding) }
        }
        bindingByView[view] = binding
        activeBindings += binding
        view.addOnLayoutChangeListener(binding.layout)
        view.addOnAttachStateChangeListener(binding.attach)
        request(binding)
    }

    fun prefetch(url: String, widthPx: Int, heightPx: Int) {
        prefetchWindow(listOf(url), widthPx, heightPx)
    }

    fun prefetchWindow(urls: List<String>, widthPx: Int, heightPx: Int) {
        if (closed || widthPx <= 0 || heightPx <= 0) return
        val (targetWidth, targetHeight) = normalize(widthPx, heightPx)
        timing.shape(prefetch=true,targetWidth,targetHeight)
        val tier = tierOf(targetWidth)
        val wanted = HashMap<Key, Int>(urls.size * 2)
        urls.forEachIndexed { index, url ->
            if (url.isNotBlank()) wanted[Key(ArtworkUrl.source(url), tier)] = index
        }
        var dropped = 0
        var queuedCount = 0
        var duplicateCount = 0
        val cached=wanted.keys.filter { cache.get(it) != null }.toHashSet()
        synchronized(signal) {
            if (closed) return
            pending.toList().forEach { job ->
                if (job.listeners.isEmpty() && job.key !in wanted) { cancelLocked(job); dropped++ }
            }
            for ((key, rank) in wanted) {
                if (key in cached || jobs.containsKey(key)) continue
                if (downloaded.contains(key)) duplicateCount++
                if (pending.size >= MAX_PENDING) break
                val job = Job(key, owner, PRIORITY_PREFETCH, ++order, targetWidth, targetHeight)
                job.distanceRank = rank
                jobs[key] = job
                pending += job
                queuedCount++
            }
            signal.notifyAll()
        }
        timing.prefetchCall()
        if(dropped > 0) timing.prefetchCancelled(dropped)
        repeat(queuedCount) { timing.enqueued(); timing.prefetchQueued() }
        repeat(duplicateCount) { timing.duplicateEnqueue() }
    }

    private fun request(binding: Binding) {
        val view = binding.view.get() ?: return
        if (closed || binding.owner != owner || bindingByView[view] !== binding) return
        val width = if (binding.width > 0) binding.width else view.width - view.paddingLeft - view.paddingRight
        val height = if (binding.height > 0) binding.height else view.height - view.paddingTop - view.paddingBottom
        if (width <= 0 || height <= 0) return
        val (targetWidth, targetHeight) = normalize(width, height)
        val key = Key(ArtworkUrl.source(binding.url), tierOf(targetWidth))
        if (binding.key != key) { detachJob(binding); binding.key = key; binding.failed = null; binding.blankAt = 0L }
        if (binding.applied == key || binding.failed == key) {
            synchronized(signal) { waiting.remove(binding) }
            return
        }
        cache.get(key)?.let { bitmap ->
            view.setImageBitmap(bitmap); binding.applied = key; binding.blankAt = 0L
            synchronized(signal) { waiting.remove(binding) }
            return
        }
        if (binding.blankAt == 0L) binding.blankAt = android.os.SystemClock.elapsedRealtime()
        var cancelled: Job? = null
        var warm = false
        var capDropped = false
        var saturated = false
        synchronized(signal) { waiting.add(binding) }
        synchronized(signal) {
            val existing = jobs[key]
            if (existing != null) {
                existing.listeners += binding
                if (targetWidth > 0) existing.targetWidth = targetWidth
                if (targetHeight > 0) existing.targetHeight = targetHeight
                if (binding.priority < existing.priority) {
                    val queued = pending.remove(existing)
                    existing.priority = binding.priority
                    if (queued) pending.add(existing)
                }
                warm = true
            } else {
                if (pending.size >= MAX_PENDING) {
                    val worst = pending.maxWithOrNull(compareBy<Job> { it.priority }.thenBy { -it.order })!!
                    if (worst.priority < binding.priority) {
                        saturated = true
                        waiting.remove(binding)
                    } else { cancelLocked(worst); capDropped = true; cancelled = worst }
                }
                if (!saturated) {
                    val job = Job(key, owner, binding.priority, ++order, targetWidth, targetHeight)
                    job.listeners += binding
                    jobs[key] = job
                    pending += job
                    signal.notifyAll()
                }
            }
        }
        if(warm) timing.warmHit()
        else if(!saturated) { timing.coldMiss(); timing.enqueued() }
        if(capDropped) timing.capDrop()
        cancelled?.call?.cancel()
        timing.shape(prefetch=false,targetWidth,targetHeight)
    }

    private fun diskKey(key: Key) = key.url

    private fun cancelLocked(job: Job) {
        job.cancelled = true
        pending.remove(job)
        if (jobs[job.key] === job) jobs.remove(job.key)
        job.listeners.clear()
    }

    private fun detachJob(binding: Binding) {
        synchronized(signal) { waiting.remove(binding) }
        val key = binding.key ?: return
        val keep = synchronized(signal) {
            jobs[key]?.takeIf { it.owner == binding.owner }?.let { job ->
                job.listeners.remove(binding)
                job.listeners.isEmpty()
            } ?: false
        }
        if (keep) timing.detachKeep()
    }

    private fun unbind(binding: Binding) {
        activeBindings.remove(binding)
        binding.view.get()?.let { view ->
            view.removeOnLayoutChangeListener(binding.layout)
            view.removeOnAttachStateChangeListener(binding.attach)
            if (bindingByView[view] === binding) bindingByView.remove(view)
        }
        detachJob(binding)
        binding.view.clear()
    }

    fun cancelPage(clearMemory: Boolean = false) {
        timing.mark(if(clearMemory) "cancelPage(clearMemory)" else "cancelPage")
        owner++
        val old = activeBindings.toList()
        old.forEach { binding -> binding.view.get()?.setImageDrawable(null); unbind(binding) }
        val cancelled = synchronized(signal) {
            jobs.values.toList().also { list -> list.forEach(::cancelLocked); signal.notifyAll() }
        }
        if(cancelled.isNotEmpty()) timing.pageCancel(cancelled.size)
        cancelled.forEach { it.call?.cancel() }
        if (clearMemory) cache.evictAll()
        synchronized(signal) { downloaded.clear(); waiting.clear() }
    }

    fun cancelAll(clearMemory: Boolean = false) = cancelPage(clearMemory)

    var releaseHiddenCovers: (() -> Unit)? = null

    @Suppress("DEPRECATION") // Android 8 devices still report the running-memory pressure levels.
    fun trimMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                runCatching { releaseHiddenCovers?.invoke() }
                cache.evictAll()
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ->
                cache.trimToSize((cacheBytes.toLong() * 2 / 10).toInt().coerceAtLeast(1))
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                runCatching { releaseHiddenCovers?.invoke() }
                cache.evictAll()
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                runCatching { releaseHiddenCovers?.invoke() }
                cache.trimToSize((cacheBytes.toLong() * 6 / 10).toInt().coerceAtLeast(1))
            }
        }
    }

    fun abandonForReset(timeoutMs: Long = 2000): Boolean {
        if (!closed) {
            closed = true
            synchronized(signal) { signal.notifyAll() }
            workers.shutdownNow()
        }
        Thread({
            runCatching {
                if (httpRef.isInitialized()) {
                    http.dispatcher.cancelAll()
                    http.connectionPool.evictAll()
                    http.dispatcher.executorService.shutdown()
                }
            }
        }, "hgtv-artwork-reset").start()
        return runCatching { workers.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
    }

    fun close() {
        if (closed) return
        closed = true
        cancelPage(clearMemory = true)
        synchronized(signal) { signal.notifyAll() }
        workers.shutdownNow()
        Thread({
            if (httpRef.isInitialized()) {
                http.dispatcher.cancelAll()
                http.connectionPool.evictAll()
                http.dispatcher.executorService.shutdown()
            }
        }, "hgtv-artwork-cleanup").start()
    }

    private fun work() {
        while (!closed) {
            val tWait = android.os.SystemClock.elapsedRealtime()
            var waited = false
            val job = try {
                synchronized(signal) {
                    while (true) {
                        while (pending.isEmpty() && !closed) { waited = true; signal.wait() }
                        if (closed) return
                        return@synchronized (pending.poll() ?: continue)
                    }
                    @Suppress("UNREACHABLE_CODE") null
                }
            } catch (_: InterruptedException) { return } ?: return
            val tPoll = android.os.SystemClock.elapsedRealtime()
            timing.idle(tPoll - tWait, waited)
            if (job.listeners.isEmpty()) {
                val tHas = android.os.SystemClock.elapsedRealtime()
                val onDisk = disk.has(diskKey(job.key))
                timing.diskHas(android.os.SystemClock.elapsedRealtime() - tHas)
                if (onDisk) {
                    timing.prefetchSkippedOnDisk()
                    timing.loop(android.os.SystemClock.elapsedRealtime() - tPoll)
                            synchronized(signal) { if (jobs[job.key] === job) jobs.remove(job.key) }
                    continue
                }
            }
            val startedAt = android.os.SystemClock.elapsedRealtime()
            if (job.listeners.isNotEmpty()) timing.visibleQueue(startedAt - job.queuedAt)
            val bitmap = try {
                if (job.cancelled) null else cache.get(job.key) ?: fetch(job, startedAt)
            } catch (problem: LinkageError) {
                recordNetworkFailure(problem)
                null
            } catch (_: Exception) { null } catch (oom: OutOfMemoryError) {
                if (!oomLogged) {
                    oomLogged = true
                    val line = "artwork OOM cache=${cache.size() / 1048576}MB/" +
                        "${cache.maxSize() / 1048576}MB heap=${heapFreeBytes() / 1048576}MB free " +
                        "job=${job.key.url.take(80)}"
                    android.util.Log.w("HGTV", line, oom)
                    Diagnostics.warn("HGTV-IMG", line, oom)
                }
                cache.evictAll()
                null
            }
            if (bitmap == null && !job.cancelled) timing.failed()
            timing.loop(android.os.SystemClock.elapsedRealtime() - tPoll)
            val listeners = synchronized(signal) {
                if (jobs[job.key] === job) jobs.remove(job.key)
                if (!job.cancelled && !closed && bitmap != null) downloaded += job.key
                job.listeners.toList().also { list ->
                    list.forEach { waiting.remove(it) }
                    job.listeners.clear()
                }
            }
            if (!job.cancelled && !closed && bitmap != null) {
                val prefetched = listeners.isEmpty()
                if (!prefetched || cache.size() < maxHeapBytes * 2 / 5) {
                    try { cache.put(job.key, bitmap) } catch (oom: OutOfMemoryError) {
                        oomLogged = true
                        android.util.Log.w("HGTV", "artwork cache put OOM", oom)
                        Diagnostics.warn("HGTV-IMG", "cache put OOM size=${cache.size() / 1048576}MB", oom)
                        cache.evictAll()
                    }
                }
            }
            if (!job.cancelled && !closed) {
                if (listeners.isEmpty()) {
                    scheduleRefill()
                } else main.post {
                    if (job.owner == owner && !closed) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        listeners.forEach { binding ->
                            val view = binding.view.get()
                            if (view != null && bindingByView[view] === binding && binding.key == job.key) {
                                if (bitmap != null) {
                                    view.setImageBitmap(bitmap); binding.applied = job.key
                                    binding.blankAt.takeIf { it > 0 }?.let { started -> timing.blank(now - started) }
                                    binding.blankAt = 0L
                                } else binding.failed = job.key
                            }
                        }
                        scheduleRefill()
                    }
                }
            }
        }
    }

    private fun recordNetworkFailure(problem: Throwable) {
        if (networkUnavailable != null) return
        networkUnavailable = problem
        android.util.Log.e("HGTV", "artwork network unavailable", problem)
        runCatching {
            Diagnostics.warn("HGTV-IMG", "artwork 网络初始化失败，本次运行停止加载封面", problem)
        }
    }

    private fun scheduleRefill() {
        if (refillScheduled || closed) return
        refillScheduled = true
        main.post {
            refillScheduled = false
            if (!closed) refillVisible()
        }
    }

    private fun refillVisible() {
        val candidates = synchronized(signal) { if (waiting.isEmpty()) emptyList() else waiting.toList() }
        if (candidates.isEmpty()) return
        val rect = Rect()
        val dead = ArrayList<Binding>(0)
        candidates.sortedBy { it.priority }.forEach { binding ->
            val view = binding.view.get()
            if (view == null) { dead += binding; return@forEach }
            if (bindingByView[view] !== binding) { dead += binding; return@forEach }
            val key = binding.key ?: return@forEach
            if (binding.applied == key || binding.failed == key) return@forEach
            if (view.getGlobalVisibleRect(rect)) request(binding)
        }
        if (dead.isNotEmpty()) synchronized(signal) { dead.forEach { waiting.remove(it) } }
    }

    private fun heapFreeBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
    }

    private fun ensureRoom(bytes: Long) {
        val reserve = maxHeapBytes / 5
        if (heapFreeBytes() - bytes >= reserve) return
        cache.trimToSize((cache.size() / 2).coerceAtLeast(1))
        if (heapFreeBytes() - bytes < reserve / 2) cache.evictAll()
    }

    private fun readBody(input: java.io.InputStream, expected: Long, cancelled: () -> Boolean): ByteArray? {
        if (expected in 1..MAX_BYTES.toLong()) {
            val data = ByteArray(expected.toInt())
            var offset = 0
            while (offset < data.size) {
                if (cancelled()) return null
                val count = input.read(data, offset, data.size - offset)
                if (count < 0) break
                offset += count
            }
            return if (offset == data.size) data else data.copyOf(offset)
        }
        val out = ByteArrayOutputStream(32 * 1024)
        val buffer = ByteArray(8192)
        while (!cancelled()) {
            val count = input.read(buffer)
            if (count < 0) break
            if (out.size() + count > MAX_BYTES) return null
            out.write(buffer, 0, count)
        }
        return if (cancelled()) null else out.toByteArray()
    }

    private fun fetch(job: Job, startedAt: Long): Bitmap? {
        val t0=android.os.SystemClock.elapsedRealtime()
        val cached = disk.get(diskKey(job.key))
        val tDisk=android.os.SystemClock.elapsedRealtime()
        timing.diskGet(tDisk - t0)
        var proto = "-"
        var ttfbMs = 0L
        var bodyMs = 0L
        val bytes = cached ?: run {
            if (networkUnavailable != null) return null
            if (job.cancelled) return null
            val call = http.newCall(Request.Builder().url(job.key.url).build())
            job.call = call
            if (job.cancelled) { call.cancel(); return null }
            val tSend = android.os.SystemClock.elapsedRealtime()
            call.execute().use { response ->
                val tHeaders = android.os.SystemClock.elapsedRealtime()
                proto = response.protocol.toString()
                ttfbMs = tHeaders - tSend
                val body = response.body ?: return null
                if (!response.isSuccessful || body.contentLength() > MAX_BYTES) return null
                val data = body.byteStream().use { input ->
                    readBody(input, body.contentLength(), { job.cancelled })
                } ?: return null
                bodyMs = android.os.SystemClock.elapsedRealtime() - tHeaders
                if (job.cancelled) return null
                data
            }
        }
        if (job.cancelled) return null
        val tNet=android.os.SystemClock.elapsedRealtime()
        ensureRoom(2L * 1024 * 1024)
        val bitmap = decode(bytes, job.targetWidth, job.targetHeight) { job.cancelled } ?: return null
        val tDecode=android.os.SystemClock.elapsedRealtime()
        if (cached == null) {
            if (job.cancelled) { bitmap.recycle(); return null }
            val tPut = android.os.SystemClock.elapsedRealtime()
            if (isWebp(bytes)) disk.put(diskKey(job.key), bytes)
            else encodeForDisk(bitmap)?.let { disk.put(diskKey(job.key), it) }
            timing.diskPut(android.os.SystemClock.elapsedRealtime() - tPut)
        }
        val tEnd=android.os.SystemClock.elapsedRealtime()
        timing.busy(tEnd-startedAt)
        timing.record(fromDisk=cached!=null,bytes=bytes.size,queueMs=startedAt-job.queuedAt,
            netMs=if(cached!=null) 0 else tNet-tDisk,decodeMs=tDecode-tNet,encodeMs=tEnd-tDecode,
            totalMs=tEnd-job.queuedAt,pending=synchronized(signal){pending.size},
            host=job.key.url.substringAfter("//").substringBefore("/"),
            template=job.key.url.substringAfter("~", "none").substringBefore("?").take(34),
            url=job.key.url, proto=proto, ttfbMs=ttfbMs, bodyMs=bodyMs)
        return bitmap
    }

    private fun decode(bytes: ByteArray, targetWidth: Int, targetHeight: Int, cancelled: () -> Boolean): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..32768 || bounds.outHeight !in 1..32768) return null
        val fit = min(1.0, min(targetWidth.toDouble() / bounds.outWidth, targetHeight.toDouble() / bounds.outHeight))
        val width = max(1, (bounds.outWidth * fit).toInt())
        val height = max(1, (bounds.outHeight * fit).toInt())
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
        if ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > (if (lowRam) 2 else 4) * 1024 * 1024) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig =
                if (lowRam) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            val sampledWidth = bounds.outWidth / sample
            val sampledHeight = bounds.outHeight / sample
            if (sampledWidth > width || sampledHeight > height) {
                inScaled = true
                inDensity = sampledWidth
                inTargetDensity = width
            }
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        if (cancelled()) { decoded.recycle(); return null }
        val result = if (decoded.width == width && decoded.height == height) decoded
        else try { Bitmap.createScaledBitmap(decoded, width, height, true).also { if (it !== decoded) decoded.recycle() } }
        catch (problem: Throwable) { decoded.recycle(); throw problem }
        if (cancelled()) { result.recycle(); return null }
        return result
    }

    private fun isWebp(bytes: ByteArray): Boolean =
        bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()

    private fun encodeForDisk(bitmap: Bitmap): ByteArray? = try {
        val out = ByteArrayOutputStream(32 * 1024)
        @Suppress("DEPRECATION") // API 26 起 WebP 有损编码可用，质量 80 足够做缩略图。
        bitmap.compress(Bitmap.CompressFormat.WEBP, 80, out)
        out.toByteArray()
    } catch (_: Throwable) { null }
}
