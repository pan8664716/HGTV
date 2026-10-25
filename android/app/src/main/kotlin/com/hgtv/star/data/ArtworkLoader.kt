// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.data

import com.hgtv.star.Diagnostics


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
import com.hgtv.star.compat.memoryBytes
import com.hgtv.star.compat.lowEndTv

/** Page-owned artwork requests. Public methods are called on the UI thread; decoding stays on workers. */
class ArtworkLoader(context: Context) {
    companion object {
        /** 焦点那张卡。 */
        const val PRIORITY_FOCUSED = 0
        /** **真的在屏幕上**的卡（视口之内）。 */
        const val PRIORITY_VISIBLE = 1
        /**
         * 已经物化、但**在视口之外**的卡（窗口化多留的那几行）。
         *
         * 这一档是必须的：物化窗口按「视口上下各多留一行」算，一屏约 17 行 × 6 列 ≈ 102 张，
         * 而真正在屏幕上的只有约 3 行 ≈ 18 张。早先这 102 张**全拿 [PRIORITY_VISIBLE]**，
         * 于是屏幕上那 18 张要排在另外 84 张看不见的后面——
         * 实测 `visQAvg=162ms`、灰框 p50=265ms，而单张只要 11ms（`netAvg+decodeAvg`）。
         * 按真实可见性分开之后，屏幕上那一屏永远最先出图。
         */
        const val PRIORITY_NEARBY = 2
        /** 预取（视口之外的缓冲行）。 */
        const val PRIORITY_PREFETCH = 3
        /**
         * 同时在队里的请求上限（**不是**并发数，并发由 worker 数决定）。
         *
         * 这个值必须盖住「一屏可见 + 前后各一屏预取」的量：一屏物化约 17 行 × 6 列 ≈ 100 张，
         * 加上预取窗口 200 张，就是 300 张。
         *
         * 原先给 24 / 96 都不够，实测会 `capDrop=255`——可见卡的请求被预取挤掉，
         * 挤掉的代价是双份的：已经排到的位置没了，用户还得再等一次排队。
         *
         * 队列长一点**没有内存风险**：里面绝大多数只是「待办」，真正在下载的只有 worker 那么几张
         * （每张也就几十 KB）。队列太短才是问题——它会让「先做哪张」这个决策变得很迟钝。
         */
        private const val MAX_PENDING = 320

        /**
         * 同时在飞的图片请求数上限（`Dispatcher.maxRequests` 与 `maxRequestsPerHost`）。
         *
         * OkHttp 默认只给 5。这里给 16：比 worker 数（8）宽一档——
         * worker 在「等网络」时是闲着的，多出来的名额让下一个请求能立刻发出去，
         * 而不是等前一个完全收完。再多没意义（CDN 那边也排队）。
         */
        private const val MAX_REQUESTS = 16

        /** 空闲连接池容量。封面只有两三个域名，全保活也占不了多少内存。 */
        private const val MAX_IDLE_CONNECTIONS = 16

        // 曾经有过一个「预取最多占一半 worker」的上限，2026-10-17 拆掉了：
        // 它让一半 worker 空转等活、吞吐掉到理论值的 9%（见 `work()` 里的说明）。
        // 优先级排序本身就够——可见任务永远排在预取前面。
        private const val MAX_BYTES = 4 * 1024 * 1024

        /**
         * 默认图片线程数（见 [workerCount] 的说明）。
         *
         * 这个数是**延迟受限**下的吞吐上限：`吞吐 ≈ 线程数 ÷ 单张耗时`。
         * 实测单张网络耗时约 80ms（其中握手与 TTFB 占大头，传输只占几 ms），
         * 因此 8 个线程的理论上限就是 100 张/s——比用户快速下键时的消费速度慢。
         * 调大线程数不会增加 CPU 负担（解码只要 2~3ms，其余时间都在等网络），
         * 但会让 CDN 那边并发更多，因此它必须**实测**着调。
         */
        private const val DEFAULT_WORKERS = 8

        /**
         * 堆上限低于这个数就按低内存策略解封面（见 [lowRam]）。
         *
         * 256 MB 是这条线：192 / 256 是电视与模拟器的两档常见默认堆，384 以上才是
         * 4GB / 8GB 机型的常态。一屏 100 张 ARGB_8888 是 57 MB，
         * 在 256 MB 堆上再叠上播放器缓冲与弹幕就只剩几个 MB 余量。
         */
        private const val HEAP_TIGHT_BYTES = 256L * 1024 * 1024
    }

    /**
     * 一个请求的标识：**只有地址**。
     *
     * 2026-10-17 起请求尺寸固定为 [ArtworkUrl.REQUEST_WIDTH]，因此地址本身就唯一决定了
     * 「下载哪一张、多大」——尺寸不再进 Key。
     *
     * 这一条是踩出来的，前后两个版本都在这里翻过车：
     *
     * * 早先 Key 带高度，而高度是「按长宽比从宽度换算」出来的，两边原始高度差 1 像素
     *   就会算出 424 与 425 两个值 → 预取与可见卡各下一遍同一张图
     *   （实测 `dupEnq=558`、`skipOnDisk=558`，而 `warmHit` 恒为 0）；
     * * 之后 Key 只留宽度，但那时的宽度是**按卡片测量值**算出来的（304 → 320 档），
     *   于是「同一张封面在卡片里和在详情页大图里」是两个 Key、两份磁盘缓存。
     *
     * 现在请求地址与卡片无关，同一个槽位里的 Key 就只有一个。
     *
     * 2026-10-25：**Key 重新带上「解码档」**，但只带**两档**（320 / 640），不是测量值。
     *
     * 只有地址时，卡片（解到 320）与详情页主视觉（解到 560）共用一个缓存槽：
     *
     * * 先看卡片再进详情页 → 详情页那张主视觉是「320 拉大到 490」，电视上肉眼可见发虚；
     * * 先看详情页再回列表 → 每一张卡都钉着 560×800 的位图（约 1.8MB，是卡片位的 3 倍），
     *   低内存机的有效缓存直接砍掉 2/3。
     *
     * 只分两档就不会退回早先「304 与 284 各下一遍」那种 `dupEnq=558` 的坑：
     * 一档之内所有槽位共用同一份位图。磁盘缓存仍然只由地址决定（见 [diskKey]），
     * 因为盘里存的一律是 640 档原图，与解码成多大无关。
     */
    private data class Key(val url: String, val tier: Int)

    /** 解码档：卡片与大图各一档。按 [ArtworkUrl.decodeWidth] 的档位换算，量到 304 还是 284 都落在这里。 */
    private fun tierOf(targetWidth: Int) = if (targetWidth > 400) 640 else 320

    /**
     * 把一组**卡片测量值**归一到「解码成多大」。
     *
     * 与请求地址无关（请求一律 [ArtworkUrl.REQUEST_WIDTH]），只决定位图尺寸。
     * 宽度按 [ArtworkUrl.decodeWidth] 的档位取整，因此量到 304 还是 284 落到同一个值；
     * 返回的第二个分量是按长宽比换算出来的目标高度。
     */
    private fun normalize(widthPx: Int, heightPx: Int): Pair<Int, Int> {
        val scale = min(1.0, 1024.0 / max(widthPx, heightPx))
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
        /**
         * 这张卡**开始等图**的时刻（0 = 不等）。
         *
         * 只用来量「屏幕上那块灰框存在了多久」（见 [ArtworkTiming.blank]），
         * 不参与任何加载决策。
         */
        var blankAt = 0L
        lateinit var layout: View.OnLayoutChangeListener
        lateinit var attach: View.OnAttachStateChangeListener
    }
    private class Job(val key: Key, val owner: Long, var priority: Int, val order: Long,
                      /** 解码目标宽 / 高（**不进 Key**，见 [Key]）：只决定位图多大，不决定下载什么。 */
                      var targetWidth: Int = 0, var targetHeight: Int = 0) {
        val listeners = linkedSetOf<Binding>()
        /** 入队时刻（[android.os.SystemClock.elapsedRealtime]）：用来算「排队等了多久」。 */
        val queuedAt = android.os.SystemClock.elapsedRealtime()
        /**
         * 离预取窗口中心的**行距**（只对预取任务有意义）。
         *
         * 队列按「优先级 → 距离 → 入队序」排，因此离用户最近的那几张永远先开工。
         * 单靠入队顺序不行：窗口每往下挪一行，新进来的那一行是**最远**的，
         * 会被排在队尾——等它轮到的时候用户早就滚过去了，读到的就是一片灰框。
         * 窗口每次移动都会重算所有待办预取任务的距离并重排队列（见 [prefetchWindow]）。
         */
        var distanceRank = Int.MAX_VALUE / 2
        @Volatile var cancelled = false
        @Volatile var call: Call? = null
    }

    private val main = Handler(Looper.getMainLooper())
    /** 加载计时（只有 debug 包读日志才看得到，逻辑上不参与任何决策）。 */
    private val timing = ArtworkTiming()
    /**
     * 封面是否按「低内存策略」跑：**低配整机，或者本机堆上限小到装不下一屏**。
     *
     * 用 [lowEndTv] 而不是 `isLowRamDevice()`：后者在 2GB 电视盒子上返回 false
     * （`ro.config.low_ram` 被厂商关掉了），于是封面会按高端机去解——
     * ARGB_8888（每张 571 KB 而不是 285 KB）、缓存按 `堆/3` 最高 192 MB、8 个解码线程。
     * 三条叠在一起，在 192 MB 堆的机器上就是把主线程推进 GC 的原因。
     *
     * 后面那半句是**独立的第二判据**，别把它并进 [lowEndTv]：
     *
     * * 界面动画 / 焦点缩放跟**整机算力**走——4GB 的机器（含 Android Studio 的 4GB AVD）
     *   该给全效果，`lowEndTv()` 在那边必须是 false，否则设置页那枚
     *   「减少动画优化性能」永远不出现、用户还没法自己打开降级；
     * * 解码格式、缓存配额、解码线程数**只受堆约束**——4GB 机器照样常见
     *   `memoryClass = 192 / 256`（Android TV 与模拟器的默认档），在这种堆上按
     *   ARGB_8888 + 8 线程跑，一屏 100 张封面（57 MB）就够把进程推到上限。
     *
     * 因此这里取「两者之一」：整机低配 **或** 堆 ≤ 256 MB。
     */
    private val lowRam = context.applicationContext.lowEndTv() ||
        Runtime.getRuntime().maxMemory() <= HEAP_TIGHT_BYTES
    /**
     * 内存缓存容量。
     *
     * 一张 280×357 的 ARGB_8888 位图约 400 KB，一屏可见 18~24 张（约 8~10 MB）。
     * 早先只给 4 / 8 MB——**刚好装不下一屏**，于是滚动一屏就把上一屏全挤掉，
     * 往回滚等于全部重解码，这才是「滚动时一直看到灰卡」的直接原因。
     * 现在按「两屏 + 余量」给：低内存 16 MB、其余 24 MB（占用上限仍受系统与 trimMemory 约束）。
     */
    /**
     * 内存缓存容量。
     *
     * 一张 320×457 的 ARGB_8888 位图约 571 KB，而**一屏就要约 100 张**（17 行 × 6 列）。
     *
     * 这个数必须盖住「可见一屏 + 预取窗口」：预取下的图如果在用户走到之前就被挤出内存，
     * 那预取等于白做——图会重下，用户读到的还是灰框。
     * 实测过这个退化：缓存 96 MB（约 172 张）装不下「可见 102 张 + 预取 ±12 行 246 张」
     * 之后，`dupEnq`（同一张图被重复下载的次数）从 **0 涨到 754**，
     * 灰框 p90 从 63ms 涨到 **601ms**，且**恰好发生在缓存填满的那一刻**（n≈1500）。
     *
     * 早先给 192 MB（约 344 张），能装下整个窗口；但那是按桌面模拟器定的，
     * 在 `heapgrowthlimit` 只有 192 MB 的电视上等于「缓存上限 = 堆上限」（见 [cacheBytes]）。
     * 窗口仍然保持小而准（12 行），缓存按本机堆上限取份额。
     * 上限仍然受系统与 [trimMemory] 约束（内存吃紧时会先丢它，但那时确实该丢）。
     */
    private val maxHeapBytes = Runtime.getRuntime().maxMemory()
    /**
     * 内存缓存容量：**按本机堆上限算**，不再写死 192 MB。
     *
     * 线上 OOM 复盘：192 MB 的位图 LRU 落在堆上限 192 MB 的电视上，等于「缓存上限 = 堆上限」；
     * 再加上被 `ImageView` 钉住的那几十 MB，任何一次解码都会把进程推到边界，
     * 最后表现为 OkHttp 读线程里「24 字节分配失败」——那里只是第一个撞上上限的分配点。
     *
     * 取堆的 1/3（低内存机型 1/5），并夹在 24..192 MB（低内存 8..32 MB）：
     * * 192 MB 堆 → 64 MB（约 110 张，够「可见一屏 + 前后几行」）；
     * * 256 MB 堆 → 85 MB；
     * * 512 MB 堆 → 170 MB（仍低于原来的 192 MB 上限）。
     *
     * 缓存变小会让预取窗口更容易被挤掉，因此预取在缓存偏满时改为只落盘、不进内存
     * （见 [work]），避免为了没人看的图把可见卡挤出去。
     */
    private val cacheBytes: Int = run {
        val share = if (lowRam) maxHeapBytes / 5 else maxHeapBytes / 3
        val floorBytes = (if (lowRam) 8L else 24L) * 1024 * 1024
        val ceilingBytes = (if (lowRam) 32L else 192L) * 1024 * 1024
        share.coerceIn(floorBytes, ceilingBytes).toInt()
    }
    private val cache = object : LruCache<Key, Bitmap>(cacheBytes) {
        override fun sizeOf(key: Key, value: Bitmap) = value.memoryBytes
        /**
         * 被 LRU 挤出去的张数。
         *
         * 这是判断「预取有没有白做」的**决定性数字**：一张图下完进缓存、又在用户走到之前
         * 被挤出去，那这次预取就是白花的带宽与 CPU，用户到那里还得再下一次。
         * 实测它一旦开始涨（`dupEnq` 同时涨），灰框 p90 就从 63ms 跳到 600ms——
         * 也就是「前几百张很好、后面又不行了」那个拐点。
         */
        override fun entryRemoved(evicted: Boolean, key: Key, oldValue: Bitmap, newValue: Bitmap?) {
            if (evicted) timing.evicted()
        }
    }
    // 盘里存的是「640 档的 WebP」，key 就是地址（见 [diskKey]）。
    //
    // 目录名换到 v4：这一版的 key 从「地址|宽度」变成了纯地址（请求尺寸固定 640）。
    // 旧目录（v1/v2/v3）里的条目**一个新 key 都命中不了**——它们只会占着
    // 磁盘配额（实测 v3 有 90MB / 三千多个文件），因此这里直接删掉，不等 LRU 慢慢挤。
    private val disk = ArtworkCache(File(context.applicationContext.cacheDir, "artwork-v4").also {
        runCatching {
            it.parentFile?.listFiles { f -> f.name.startsWith("artwork-v") && f.name != it.name }
                ?.forEach { old -> old.deleteRecursively() }
        }
    })
    // This client owns its dispatcher and pool; cancelling artwork never cancels video or catalog calls.
    /**
     * 只做**计数**的事件监听（诊断用，见 [ArtworkTiming.connectionNew] / [ArtworkTiming.tlsHandshake]）。
     *
     * 为什么需要它：`netAvg` 里混着两件完全不同的事——
     *
     * * **握手**（DNS + TCP + TLS，设备上实测 3 + 30 + 35 = 68ms）：这部分只跟「连接有没有被复用」有关，
     *   复用了就是 0；
     * * **等服务器 + 收字节**（实测 TTFB 30ms + 传输几 ms）：这部分才是真的跟 CDN 有关。
     *
     * 只看 `netAvg` 分不出是哪一半，而两者的修法完全相反：前者要修连接池 / 上多路复用，
     * 后者只能换源或减字节。因此这里把「新建连接数」「新建 TLS 次数」直接数出来，
     * 再配合每张图的 `ttfb` / `body` 拆分（见 [ArtworkTiming.record]），一眼就能定位。
     *
     * `connectStart` 只在**连接池里没有可复用的连接**时才会被调用，因此它天然就是
     * 「这次请求又重头握手了一遍」的判据。
     */
    private val events = object : EventListener() {
        override fun connectStart(call: Call, inetSocket: InetSocketAddress, proxy: Proxy) {
            timing.connectionNew()
        }

        override fun secureConnectStart(call: Call) { timing.tlsHandshake() }
    }
    /**
     * 图片专用的 HTTP 客户端。
     *
     * **连接池与并发上限必须显式设置**——OkHttp 的默认值是 `maxRequestsPerHost = 5`，
     * 而这里同时有 [workerCount]（8）个线程要发请求：默认配置下只有 5 个能真的发出去，
     * 另外 3 个在 `Dispatcher` 里排队。实测同一批图在设备上的耗时是「80ms 固定延迟 +
     * 一点传输时间」（字节翻 3 倍只多 15ms，见下），也就是说瓶颈在**往返与排队**，
     * 不在带宽——那就更不该让请求卡在并发名额上。
     *
     * 连接池给到 [MAX_IDLE_CONNECTIONS] 并保活 5 分钟：封面全部来自同两三个 CDN 域名，
     * 连接复用率极高，避免每个请求都重新握手（那正是那 80ms 的主要来源之一）。
     */
    private val http = com.hgtv.star.core.HgtvTls.configure(OkHttpClient.Builder())
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS)
        .dispatcher(okhttp3.Dispatcher().apply {
            maxRequests = MAX_REQUESTS
            maxRequestsPerHost = MAX_REQUESTS
        })
        .connectionPool(okhttp3.ConnectionPool(MAX_IDLE_CONNECTIONS, 5, TimeUnit.MINUTES))
        .eventListener(events)
        .build()

    /**
     * 线程数的**临时覆盖**（只认 debug 包）：`filesDir/workers` 里写一个数字即可。
     *
     * 为什么留这个口子：`吞吐 ≈ 线程数 ÷ 单张耗时`，而单张耗时是**设备与网络相关的**，
     * 只有真机能定；每试一个值都重新打包太慢。清缓存跑一轮、改一个数字、再跑一轮，
     * 同一台设备同一批图，`rate` 直接可比。
     *
     * ```
     * adb shell "run-as com.hgtv.star sh -c 'echo 16 > /data/data/com.hgtv.star/files/workers'"
     * adb shell am force-stop com.hgtv.star     # 下次启动生效
     * ```
     *
     * 正式包里这个文件根本不会被读（[enabled] 门控），删掉即可回到默认值。
     */
    private val workerOverride: Int? =
        if (!ArtworkTiming.enabled) null
        else runCatching { File(context.filesDir, "workers").readText().trim().toInt() }
            .getOrNull()?.takeIf { it in 1..64 }
    /**
     * 图片线程数。
     *
     * 一张图的活是「等网络 + 解小图 + 落盘」的混合：等网络时不占 CPU，因此线程数可以比核数多。
     * 实测（4 核模拟器、清空缓存后按 500 次下键）：
     *
     * | worker | 吞吐 |
     * | --- | --- |
     * | 2 | 28 张/s |
     * | 6 | 166 张/s |
     * | 8 | 175 张/s |
     * | 10 | 176 张/s |
     * | 14 | 106 张/s（开始互相抢 CPU） |
     *
     * 取 8：已经贴着上限，又给低端电视留了余量（核少的机器上线程多了反而更慢）。
     * 低内存机型仍然只给 1 个：那种机器上内存与 CPU 都是稀缺资源，宁慢勿抖。
     */
    private val workerCount = when {
        workerOverride != null -> workerOverride
        // 低配给 3，而不是原来的 1。
        //
        // 1 个 worker 在这类机器上只有 20 多张/s 的吞吐，而一次焦点移动要铺 24 张：
        // 灰框会持续 1 秒以上——那是「图慢」，用户同样说卡。
        // 3 个既能把一屏（约 18 张）在 1 秒内铺满，又不会把 4 核 A53 全吃掉：
        // UI 线程与解码线程是同一批核，实测 8 个并发会互相抢 CPU（吞吐反而下降）。
        lowRam -> 3
        // 堆小的电视（192/256 MB）把并发压到 4：每个 worker 在下载 / 解码时都有
        // 0.5~1 MB 的瞬时占用，8 个叠在一起就是那根压垮骆驼的稻草。
        maxHeapBytes < 256L * 1024 * 1024 -> 4
        else -> DEFAULT_WORKERS
    }
    private val workers = Executors.newFixedThreadPool(workerCount) { runnable ->
        Thread(runnable, "hgtv-artwork").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val signal = Object()
    // 注意用 (容量, Comparator) 这个构造器：单参数 Comparator 的重载是 API 24 才有的。
    private val pending = PriorityQueue<Job>(
        16,
        object : Comparator<Job> {
            override fun compare(a: Job, b: Job): Int {
                if (a.priority != b.priority) return if (a.priority < b.priority) -1 else 1
                if (a.distanceRank != b.distanceRank) return if (a.distanceRank < b.distanceRank) -1 else 1
                return if (a.order < b.order) -1 else if (a.order > b.order) 1 else 0
            }
        })
    /** 已经**完整下过**的封面（key）。用来量「同一张图被重复下载」的比例，见 [ArtworkTiming.duplicateEnqueue]。 */
    private val downloaded = HashSet<Key>()
    private val jobs = mutableMapOf<Key, Job>()
    private val bindings = WeakHashMap<ImageView, Binding>()
    /**
     * 已经请求过、但**还没拿到图**的绑定。补位只扫这一撮，不再全量扫 [bindings]。
     *
     * 早先 `refillVisible()` 遍历整张 `bindings`（窗口化后仍有 100~320 个 ImageView），
     * 对每个调 `getGlobalVisibleRect`（向上求交、每次分配 Rect/Point），再 `sortedBy`
     * 加 `take(MAX_PENDING)` 逐个 `request()`——而 `request()` 里的 `pending.remove(existing)`
     * 是 PriorityQueue 的**线性扫描**。一屏 18~24 张图陆续返回就是 18~24 次全量扫描，
     * 1GB 盒子上单帧 100~300ms，按住下键时表现为整屏卡死半秒。
     *
     * 现在候选取自这里（只有真正在等图的几十个），并且每帧最多跑一次（见 [scheduleRefill]）。
     * 只在 [signal] 里读写。
     */
    private val waiting = HashSet<Binding>()
    /** 同一帧里的多次补位合并成一次：8 个 worker 同时完成时只跑一次全量判定。 */
    @Volatile private var refillScheduled = false
    private var owner = 0L
    private var order = 0L
    @Volatile private var closed = false
    /** 这次运行里是否已经记过一条 OOM：同一次内存枯竭会连着失败几十张，只留第一条。 */
    @Volatile private var oomLogged = false

    /** 把计时汇总落盘到 [file]（debug 包专用，见 `MainActivity.onCreate`）。 */
    fun attachTiming(file: java.io.File) {
        timing.attach(file)
        // 每张一行 logcat 的开关：`filesDir/verbose` 里写 1 才打（见 [ArtworkTiming.verbose]）。
        // logcat 是同步写，几千行会把 worker 的墙钟时间吃掉一大截（`busy%` 却看不出来）。
        timing.attachVerbose(file.parentFile?.let { java.io.File(it, "verbose") })
    }

    /** 立刻打一份现场快照（诊断用：想知道「此刻」的状态时调它）。 */
    fun snapshot(reason: String) = timing.mark(reason)

    init {
        // 现场在**锁外**取（见 [timingContext]）：注册的 lambda 由计时器在锁外调用。
        timing.context = { timingContext() }
        // 把 signal 交给计时器：它在 debug 包里会检查「有没有人在持有 signal 时进计时器」——
        // 那正是 2026-10-17 那次 ANR 的形状（见 ArtworkTiming 的类注释）。
        timing.loaderLock(signal)
        ArtworkTiming.WORKER_HINT=workerCount
        repeat(workerCount) { workers.execute(::work) }
    }

    /**
     * 汇总行里附上的「现场」：缓存占用、队列深度、正在预取几张、磁盘条目数。
     *
     * **必须在 `timing.lock` 之外调用**。早先它是通过 `ArtworkTiming.context {}` 注册的，
     * 而那个 lambda 是在 `timing.lock` 里执行的——它内部又去拿 `signal`；
     * 同时主线程在 `request()` 里持有 `signal` 又去拿 `timing.lock`。
     * 两个方向的锁一交叉就是死锁：主线程卡 25 秒 → `Input dispatching timed out` ANR
     * （2026-10-17 00:49 的 anr 文件里就是这个环）。
     *
     * 现在改成「先把现场取出来（只在 `signal` 里做，不碰 `timing`），再交给计时器」。
     */
    private fun timingContext(): String {
        val size = cache.size(); val max = cache.maxSize()
        // 用 Long 算百分比：size 是字节数，`size * 100` 会超过 Int 上限（100MB 时溢出成负数）。
        val percent = if (max > 0) size.toLong() * 100 / max else 0L
        val queued = synchronized(signal) { pending.size }
        return "workers=$workerCount cache=${size / 1048576}MB/${max / 1048576}MB($percent%) queue=$queued " +
            "diskFiles=${runCatching { disk.count() }.getOrDefault(-1)}"
    }

    /** Size defaults to the laid-out content bounds, including the detail page's portrait artwork. */
    fun load(view: ImageView, url: String, priority: Int = PRIORITY_VISIBLE, widthPx: Int = 0, heightPx: Int = 0) {
        if (closed) return
        val previous = bindings[view]
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
        bindings[view] = binding
        view.addOnLayoutChangeListener(binding.layout)
        view.addOnAttachStateChangeListener(binding.attach)
        request(binding)
    }

    /**
     * **预取**：把一张封面排进队列，但不绑任何 View。
     *
     * 与 [load] 的区别只有一个：没有 binding，因此取回来之后只进内存缓存（见 [work] 里
     * `listeners` 为空那一支），等那张卡真的被物化时 [request] 会发现 `jobs[key]` 已经
     * 或缓存里已经有了——**同一张图永远只下一次**。
     *
     * 为什么需要它：列表的窗口化只物化视口附近那几行，`load` 只能对**已经存在**的 View 生效，
     * 因此焦点往下走时，图是「那一行被物化之后才开始下」的——用户读到的就是
     * 「焦点走到哪儿才加载那一片，前面一大片灰框只有文字」。预取把「下面几屏」的封面
     * 提前排上队，与焦点跑得多快无关。
     *
     * 队列满时**直接放弃**（不挤掉别人）：预取是锦上添花，不能因为它把真正可见的卡挤出去。
     */
    fun prefetch(url: String, widthPx: Int, heightPx: Int) {
        prefetchWindow(listOf(url), widthPx, heightPx)
    }

    /**
     * 设定**预取窗口**：只有这一批封面会被预取，窗口外的预取任务会被丢掉。
     *
     * 界面层按「焦点 / 消费位置」为中心算出前后各一屏的封面（见
     * `MainActivity.prefetchAroundWindow`），每次窗口移动时调一次。
     *
     * 为什么要「窗口」而不是「排上就不管」：
     *
     * * 焦点在下走时，**后面**那一屏已经看过了，不用再取；而**前面**那一屏是接下来要看的，
     *   必须提前取——用户往回滚时同理（前面变后面）。以焦点为中心取前后各一屏，
     *   两个方向都够用；
     * * 没有窗口的话，用户飞快划过几百张会把每一张都排进队列（而它们**不会被取消**，
     *   见 [detachJob]），队列会一直涨、带宽全花在再也不看的图上。
     *
     * 窗口的推进是**幂等**的：已经在队里 / 已经在缓存里的直接跳过，
     * 因此界面层可以在每次滚动时无脑调用它。
     */
    fun prefetchWindow(urls: List<String>, widthPx: Int, heightPx: Int) {
        if (closed || widthPx <= 0 || heightPx <= 0) return
        // 解码尺寸只影响「预取回来的位图多大」；下载什么由地址决定（固定 640 档）。
        val (targetWidth, targetHeight) = normalize(widthPx, heightPx)
        timing.shape(prefetch=true,targetWidth,targetHeight)
        // 调用方按「由近及远」给顺序（见 `MainActivity.prefetchAroundWindow`），
        // 因此下标本身就是距离的近似值；这里再按它排一次队。
        // 预取的 Key 必须与可见卡算出来的**完全一致**（含解码档），否则预取下的图可见卡命中不了，
        // 又会重下一遍——这正是早先 `warmHit` 恒为 0 的形状。
        val tier = tierOf(targetWidth)
        val wanted = HashMap<Key, Int>(urls.size * 2)
        urls.forEachIndexed { index, url ->
            if (url.isNotBlank()) wanted[Key(ArtworkUrl.source(url), tier)] = index
        }
        var dropped = 0
        var queuedCount = 0
        var duplicateCount = 0
        // 内存缓存里已有的先筛掉——**在锁外**做（`cache.get` 会拿 LRU 自己的锁，
        // 在 `signal` 里做就是锁环，见 [ArtworkTiming] 的类注释）。
        val cached=wanted.keys.filter { cache.get(it) != null }.toHashSet()
        synchronized(signal) {
            if (closed) return
            // 先丢掉滑出窗口的**预取**任务（有 binding 的可见卡不动：用户正看着它们）。
            // 只有还没被 worker 取走的才丢得掉，已经在下载的那几张让它跑完。
            pending.toList().forEach { job ->
                if (job.listeners.isEmpty() && job.key !in wanted) { cancelLocked(job); dropped++ }
            }
            // **不重排**队列里已有的预取任务。
            //
            // 试过按「离窗口中心的距离」重排（每次窗口移动把 300 多项重排一遍），
            // 结果更差：`qAvg` 从 340ms 涨到 459ms、灰框 p50 从 53ms 涨到 140ms。
            // 原因是重排本身在持锁状态下做 O(n log n) 的工作，而窗口每次挪一行就发生一次，
            // 反倒把 worker 取任务的路径堵住了。现在的做法是「新任务按距离插队 +
            // 旧的按入队序」，由近及远的顺序在入队那一刻就定好了（见调用方）。
            // 再补窗口内还没排上的。
            for ((key, rank) in wanted) {
                if (key in cached || jobs.containsKey(key)) continue
                // 之前完整下过一次、现在缓存里没有了：这是缓存抖动的代价（见 [ArtworkTiming.duplicateEnqueue]）。
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
        // 计时埋点在**锁外**（见 [timingContext]）。
        timing.prefetchCall()
        if(dropped > 0) timing.prefetchCancelled(dropped)
        repeat(queuedCount) { timing.enqueued(); timing.prefetchQueued() }
        repeat(duplicateCount) { timing.duplicateEnqueue() }
    }

    private fun request(binding: Binding) {
        val view = binding.view.get() ?: return
        if (closed || binding.owner != owner || bindings[view] !== binding) return
        val width = if (binding.width > 0) binding.width else view.width - view.paddingLeft - view.paddingRight
        val height = if (binding.height > 0) binding.height else view.height - view.paddingTop - view.paddingBottom
        if (width <= 0 || height <= 0) return
        // A malformed layout cannot ask an old TV to allocate a full-screen, multi-megapixel thumbnail.
        // 尺寸走 [normalize]：它挡掉超大布局，并给出**解码**目标（位图多大）。
        val (targetWidth, targetHeight) = normalize(width, height)
        // 发出去的地址**不按卡片尺寸**：固定换成 640 档的 webp（见 [ArtworkUrl] 的类注释——
        // 640 是官方全站最热的尺寸、CDN 命中率 58~84%，而按卡片算出来的 320 是冷 key，
        // 每一张都要 CDN 现场变换，实测加权耗时 177ms vs 97ms）。
        // 带防盗链签名的地址重写到免签镜像域名，其余原样（见 [ArtworkUrl]）。
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
        // 内存里没有、要真的去取：记下这一刻，取回来时用它算「灰框存在了多久」。
        if (binding.blankAt == 0L) binding.blankAt = android.os.SystemClock.elapsedRealtime()
        var cancelled: Job? = null
        /** 这张图**已经在队列里**（预取排上的）。 */
        var warm = false
        var capDropped = false
        var saturated = false
        // 记账与入队/去掉记账放在同一段临界区里：出锁之后才写，中间可能已经被别的路径摘掉。
        synchronized(signal) { waiting.add(binding) }
        synchronized(signal) {
            // **不要在这里 `return`**。`synchronized` 与 `let` 都是 inline 函数，
            // 它们内部的 `return` 会直接返回 `request()` 整个函数，把下面那几行记账全跳过——
            // 早先就是这样，`warmHit` 永远记 0，让人误以为「预取和可见卡要的不是同一张图」，
            // 白查了好几轮。现在改成「只记标志 + 出锁后统一记账」。
            val existing = jobs[key]
            if (existing != null) {
                existing.listeners += binding
                // 可见卡量到的尺寸比预取算的更准，顺手更新解码目标（位图大小，与下载无关）。
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
                    // 队列里最差的那个都比这一张要紧：这一张排不进去（少见，且不记账）。
                    if (worst.priority < binding.priority) {
                        saturated = true
                        // 这一张挤不进去：它不在队列里，补位台账里也不能留着它。
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
        // 计时埋点一律在**锁外**做：它们要拿 `ArtworkTiming` 的锁，而在锁内拿就是死锁
        // （见 [timingContext] 的说明）。这几个计数只是统计，晚一点记没有关系。
        if(warm) timing.warmHit()
        else if(!saturated) { timing.coldMiss(); timing.enqueued() }
        if(capDropped) timing.capDrop()
        // 被挤掉的那一个要真的取消（`cancel()` 必须在锁外调：它会回调 Dispatcher）。
        cancelled?.call?.cancel()
        timing.shape(prefetch=false,targetWidth,targetHeight)
    }

    /**
     * 盘缓存的 key。
     *
     * 只由地址决定：请求尺寸固定 640，因此同一张封面在整个应用里**只有一条**磁盘缓存。
     * （早先带上目标宽度，于是「卡片 320 档」与「详情页大图 640 档」各存一份，
     * 同一张图在盘上出现两次，条目数虚高、清理也来得更早。）
     */
    private fun diskKey(key: Key) = key.url

    private fun cancelLocked(job: Job) {
        job.cancelled = true
        pending.remove(job)
        if (jobs[job.key] === job) jobs.remove(job.key)
        job.listeners.clear()
    }

    /**
     * 卡片被解绑（滚出窗口 / 行被回收复用）时**不取消请求**，只摘掉它的监听者。
     *
     * 这是「按住下键时大片灰框只有文字」的根治点。原先的写法是「最后一个监听者走了就取消」，
     * 实测一次 500 张的滚动里 `detachCancel=1231`（发出 2366 个请求、过半在排队时被掐掉）：
     * 快速滚动时每一行只在窗口里待几十毫秒，请求刚排上队那一行就被回收了——
     * 于是**什么都没下到**，焦点再走回来又要重排一次，如此往复。
     * 用户看到的正是「焦点走到哪儿才开始加载那一片」。
     *
     * 现在让请求跑完：结果照样进内存缓存与磁盘缓存（[work] 里监听者为空那一支），
     * 那一行下次被物化时 [request] 直接命中缓存——**同一张图永远只下一次**。
     *
     * 代价是「用户飞快划过、再也不回来的那些图」也下了一遍。这可以接受：
     * 一张图只有几十 KB（见 [ArtworkUrl] 的改写），而队列有 [MAX_PENDING] 上限、
     * 满了会挤掉优先级最低的那些（预取优先被挤），因此不会无界地跑下去。
     *
     * 换页（[cancelPage]）仍然**全部取消**：那批图属于上一页，留着纯属浪费。
     */
    private fun detachJob(binding: Binding) {
        // 先摘补位台账：`binding.key` 为空时也要摘，否则它会在 waiting 里留一辈子。
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
        binding.view.get()?.let { view ->
            view.removeOnLayoutChangeListener(binding.layout)
            view.removeOnAttachStateChangeListener(binding.attach)
            if (bindings[view] === binding) bindings.remove(view)
        }
        detachJob(binding)
        binding.view.clear()
    }

    /** Release every old-page listener and bitmap binding, including queued and in-flight requests. */
    fun cancelPage(clearMemory: Boolean = false) {
        // 换页 / 重建是一个统计边界：这一段到这里结束，下一段从新页面开始算。
        // 反复「掐掉在途请求重发」会在汇总里表现成 pageCancel 很大、有效并发很低。
        timing.mark(if(clearMemory) "cancelPage(clearMemory)" else "cancelPage")
        owner++
        val old = bindings.values.toList()
        old.forEach { binding -> binding.view.get()?.setImageDrawable(null); unbind(binding) }
        val cancelled = synchronized(signal) {
            jobs.values.toList().also { list -> list.forEach(::cancelLocked); signal.notifyAll() }
        }
        if(cancelled.isNotEmpty()) timing.pageCancel(cancelled.size)
        cancelled.forEach { it.call?.cancel() }
        if (clearMemory) cache.evictAll()
        // 这一份只是「重复下载」的统计依据，属于上一页；换页后不清会随浏览无限增长。
        synchronized(signal) { downloaded.clear(); waiting.clear() }
    }

    fun cancelAll(clearMemory: Boolean = false) = cancelPage(clearMemory)

    /**
     * 内存吃紧时，让界面把**视口之外**的封面从 ImageView 上摘掉。
     *
     * 为什么必须由界面来做：下面的 `cache.evictAll()` 只解除 LRU 自己的引用，
     * 位图只要还挂在某个 `ImageView` 上就仍然活着。首页是**未窗口化**的
     * （7 栏 ≈ 77 张卡常驻），那就是 44MB 不受任何缓存预算约束的内存；
     * 栅格窗口里还有约 34 张（≈19MB）。低内存时不清它，trim 基本是空转，
     * 系统下一次还是会杀进程——线上「长时间运行后闪退」的另一半就在这里。
     *
     * 由 `MainActivity` 在 `onCreate` 里接到 `releaseOffscreenCovers`。
     */
    var releaseHiddenCovers: (() -> Unit)? = null

    @Suppress("DEPRECATION") // Android 8 devices still report the running-memory pressure levels.
    fun trimMemory(level: Int) {
        // 档位是**从小到大**排列的（RUNNING_LOW=10 < RUNNING_CRITICAL=15 < UI_HIDDEN=20
        // < BACKGROUND=40 < MODERATE=60 < COMPLETE=80），因此判断必须按「从大到小」写。
        // 早先写的是 `level >= RUNNING_CRITICAL -> evictAll()`，它把 UI_HIDDEN 与
        // BACKGROUND 也一并吞了（`>=` 匹配所有更高档），退后台就整份清空。
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                // 先让界面把视口外的图摘下来，再清 LRU——否则位图仍被 View 钉着，等于没清。
                runCatching { releaseHiddenCovers?.invoke() }
                cache.evictAll()
            }
            // 退到后台（UI 不可见，20）与整机内存吃紧（40+）都只留两成：
            // 可见卡已经不需要这些位图了，回来时从磁盘读（3~10ms/张）。
            // 这两档必须排在 RUNNING_CRITICAL（15）**之前**判断，否则 `>= 15` 会把它们吞掉。
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ->
                cache.trimToSize((cacheBytes.toLong() * 2 / 10).toInt().coerceAtLeast(1))
            // 前台已经到临界：可见卡也可能保不住，全部让出去，让系统先活下来。
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                runCatching { releaseHiddenCovers?.invoke() }
                cache.evictAll()
            }
            // 内存吃紧时砍到六成：保住可见的那一屏，预取的那部分先让出去——
            // 总比整个进程被系统杀掉好。目标是**缓存自己的六成**，不是写死的 64 MB：
            // 缓存本身已经按堆上限缩过（见 [cacheBytes]），128 MB 堆上缓存只有 42 MB。
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                // 视口外那几栏先让出去：它们要回来时读盘只要 3~10ms，而这里的收益是几十 MB。
                runCatching { releaseHiddenCovers?.invoke() }
                cache.trimToSize((cacheBytes.toLong() * 6 / 10).toInt().coerceAtLeast(1))
            }
        }
    }

    /**
     * 清除本机数据专用：只停线程与网络，**不碰任何 View**（可以在后台线程调）。
     *
     * 与 [close] 的区别是不做 `cancelPage`：那里会把绑定中的 ImageView 置空，
     * 而重置流程已经从后台线程直接杀进程，没有必要再去动界面。
     */
    fun abandonForReset(timeoutMs: Long = 2000): Boolean {
        if (!closed) {
            closed = true
            synchronized(signal) { signal.notifyAll() }
            workers.shutdownNow()
        }
        Thread({
            runCatching {
                http.dispatcher().cancelAll()
                http.connectionPool().evictAll()
                http.dispatcher().executorService().shutdown()
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
        // TLS socket pool disposal can block and must not happen on the UI thread.
        Thread({
            http.dispatcher().cancelAll()
            http.connectionPool().evictAll()
            http.dispatcher().executorService().shutdown()
        }, "hgtv-artwork-cleanup").start()
    }

    private fun work() {
        while (!closed) {
            // 「等任务 + 拿调度锁」这一段单独计时（见 [ArtworkTiming.idle]）：
            // 它和「干活」加起来才是 worker 的全部时间，两者一减就知道空转在哪。
            val tWait = android.os.SystemClock.elapsedRealtime()
            var waited = false
            val job = try {
                synchronized(signal) {
                    while (true) {
                        while (pending.isEmpty() && !closed) { waited = true; signal.wait() }
                        if (closed) return
                        // 直接取队头。队列按「优先级 → 距离 → 入队序」排序，
                        // 因此可见任务永远排在预取前面——**这就是全部需要的调度**。
                        //
                        // 早先这里还叠了一层「预取最多占一半 worker」的限制（留一半待命给可见卡）：
                        // 拿不到额度就把任务放回队列、`wait(50)` 再重试。那一层是灾难——
                        // 队列里几乎全是预取任务时，4 个 worker 跑预取、另外 4 个每 50ms
                        // 空转一轮永远拿不到活，**worker 利用率掉到 9%**
                        // （实测吞吐 35 张/s，而 8 worker × 21ms/张 的理论值是 381 张/s），
                        // 可见卡反而要等那 4 个预取 worker 空出来。优先级排序已经够了。
                        return@synchronized (pending.poll() ?: continue)
                    }
                    @Suppress("UNREACHABLE_CODE") null
                }
            } catch (_: InterruptedException) { return } ?: return
            val tPoll = android.os.SystemClock.elapsedRealtime()
            timing.idle(tPoll - tWait, waited)
            // **预取任务：图已经在磁盘里就直接丢掉，不读进内存。**
            //
            // 这一条是「前几百张很好、后面退化」的根治点。实测拐点处的现场：
            //
            // | 指标 | 值 | 说明 |
            // | --- | --- | --- |
            // | `netAvg` | 5ms | 网络几乎不花时间——图都在磁盘里 |
            // | `qAvg` | 271ms | 可见卡却在排队 |
            // | `warmHit` | 0 | 预取从来没被可见卡命中过 |
            // | `evicted` | 1600 | 预取搬进内存的图又被挤出去 |
            // | `pfInFlight` | 4/4 | 预取一直占着 4 个 worker |
            //
            // 也就是说：磁盘一旦被填满，预取就只剩「磁盘 → 内存」的无用搬运——
            // 搬进来的图用户还没走到就被挤掉（`evicted` 一直涨），
            // 同时占着 4 个 worker，可见卡只能排在后面等。
            // 丢掉它之后：那 4 个 worker 立刻空出来给可见卡，
            // 而用户真走到时从磁盘读一张只要 3~10ms（`diskHit` 那一档）。
            //
            // 判定放在**锁外**（`disk.has` 与计时器各有自己的锁，在 `signal` 里做就是锁环）。
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
            } catch (_: Exception) { null } catch (oom: OutOfMemoryError) {
                // **必须留痕**：早先这里完全静默，线上只能看到「OkHttp 读线程分配 24 字节失败」，
                // 真正的现场（哪一步、缓存多大、还剩多少堆）一条都没有。
                if (!oomLogged) {
                    oomLogged = true
                    val line = "artwork OOM cache=${cache.size() / 1048576}MB/" +
                        "${cache.maxSize() / 1048576}MB heap=${heapFreeBytes() / 1048576}MB free " +
                        "job=${job.key.url.take(80)}"
                    android.util.Log.w("HGTV", line, oom)
                    // 电视的堆只有 192/256MB，OOM 是真实的机型问题：哪台机器、缓存多大、
                    // 还剩多少堆，这三项决定是调缓存上限还是换解码尺寸。
                    Diagnostics.warn("HGTV-IMG", line, oom)
                }
                cache.evictAll()
                null
            }
            if (bitmap == null && !job.cancelled) timing.failed()
            // 循环体总时长（含 `fetch` 之外的内存缓存查询）：`loop + idle` 应当填满 worker 的墙钟，
            // 对不上就说明时间被第三处吃掉了（最可能是 `ArtworkCache` 那把全局锁）。
            timing.loop(android.os.SystemClock.elapsedRealtime() - tPoll)
            // `cache.put` **必须在 `signal` 之外**：它会挤掉旧条目，而 LRU 的
            // `entryRemoved` 回调会去拿 `ArtworkTiming` 的锁——在 `signal` 里做就是
            // 2026-10-17 那次 ANR 的锁环（见 `ArtworkTiming` 的类注释）。
            // 先把监听者摘下来（含「是不是预取」的判断），再做入缓存。
            val listeners = synchronized(signal) {
                if (jobs[job.key] === job) jobs.remove(job.key)
                // 记下「这张图已经完整下过一次」：它之后如果又从缓存里被挤掉、
                // 还要重下，就是缓存抖动的代价（见 [ArtworkTiming.duplicateEnqueue]）。
                if (!job.cancelled && !closed && bitmap != null) downloaded += job.key
                job.listeners.toList().also { list ->
                    // 这批已经有结论了（成功贴图 / 失败标记），不必再进补位台账。
                    list.forEach { waiting.remove(it) }
                    job.listeners.clear()
                }
            }
            if (!job.cancelled && !closed && bitmap != null) {
                // 预取任务：缓存已经装了本机堆的四成以上时**只落盘、不进内存**。
                // 预取的价值在于「用户走到时不用等网络」，而磁盘命中只要 3~10ms；
                // 为了几张还没人看的图把可见卡挤出缓存，是净亏。
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
                // 预取（没有监听者）**不要把位图带进主线程的闭包**：那个闭包除了
                // `refillVisible()` 什么也不做，却会把一张 0.5MB 的位图多钉住一会儿。
                // 主线程排队时（换页、布局）这类闭包会积压成第二份缓存。
                if (listeners.isEmpty()) {
                    // 预取只腾出一个队列名额：合并后每帧最多补一次，
                    // 不再「每完成一张就跑一遍全量扫描」（见 [scheduleRefill]）。
                    scheduleRefill()
                } else main.post {
                    if (job.owner == owner && !closed) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        listeners.forEach { binding ->
                            val view = binding.view.get()
                            if (view != null && bindings[view] === binding && binding.key == job.key) {
                                if (bitmap != null) {
                                    view.setImageBitmap(bitmap); binding.applied = job.key
                                    // 这一块灰框到此结束：记下它存在了多久（见 [ArtworkTiming.blank]）。
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

    /**
     * 把「补位」合并成每帧最多一次。
     *
     * 8 个 worker 同时完成时，早先是 8 个独立的 `main.post`、每个都跑一遍全量扫描；
     * 现在只排一次，`refillScheduled` 在消息真正执行时才复位。
     */
    private fun scheduleRefill() {
        if (refillScheduled || closed) return
        refillScheduled = true
        main.post {
            refillScheduled = false
            if (!closed) refillVisible()
        }
    }

    /**
     * 补位：队列满时被挤掉的可见卡，等名额空出来后重新排队。
     *
     * 候选集只取 [waiting]（真正在等图的那几十个绑定）。早先这里是全量扫 `bindings`：
     * 一屏图陆续返回时每张都跑一遍「100~320 次 `getGlobalVisibleRect` + `sortedBy` +
     * `take(320)` + `request`」，而 `request` 里的 `pending.remove(existing)` 是
     * PriorityQueue 的 O(n) 扫描——1GB 盒子上单帧 100~300ms（见 [waiting] 的说明）。
     */
    private fun refillVisible() {
        val candidates = synchronized(signal) { if (waiting.isEmpty()) emptyList() else waiting.toList() }
        if (candidates.isEmpty()) return
        val rect = Rect()
        val dead = ArrayList<Binding>(0)
        candidates.sortedBy { it.priority }.forEach { binding ->
            val view = binding.view.get()
            if (view == null) { dead += binding; return@forEach }
            if (bindings[view] !== binding) { dead += binding; return@forEach }
            val key = binding.key ?: return@forEach
            if (binding.applied == key || binding.failed == key) return@forEach
            if (view.getGlobalVisibleRect(rect)) request(binding)
        }
        if (dead.isNotEmpty()) synchronized(signal) { dead.forEach { waiting.remove(it) } }
    }

    /** 本机堆还能再分配多少字节（不含尚未回收的垃圾）。 */
    private fun heapFreeBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
    }

    /**
     * 分配前的余量检查：预计要占 [bytes]，而已用堆超过上限的六成时先丢缓存。
     *
     * 为什么要它：LRU 只知道自己装了多少，不知道「被 ImageView 钉住的位图 + 解码瞬时峰值」
     * 已经吃掉了多少。线上那次 OOM 就是缓存 192 MB 撞上 192 MB 的堆——
     * 等 `BitmapFactory` 真的分配失败时，进程已经没救了。
     *
     * 只 trim、不拒绝：trim 之后仍然可能不够（可见卡钉着的位图不受缓存控制），
     * 那时让分配失败走 [work] 的 OOM 分支，至少能留下日志。
     */
    private fun ensureRoom(bytes: Long) {
        // 留出堆的两成做安全垫：解码 / 下载缓冲 / 系统临时对象都要从这里面出。
        val reserve = maxHeapBytes / 5
        if (heapFreeBytes() - bytes >= reserve) return
        // 先丢掉缓存的一半；还不够就把缓存整个让出去——可见卡钉着的位图不受缓存控制，
        // 这时候保住进程比保住「回头滚不重解」重要。
        cache.trimToSize((cache.size() / 2).coerceAtLeast(1))
        if (heapFreeBytes() - bytes < reserve / 2) cache.evictAll()
    }

    /**
     * 读完响应体。
     *
     * `Content-Length` 可信时**直接读进一个正好大小的数组**，省掉
     * `ByteArrayOutputStream` 扩容 + `toByteArray()` 的那份拷贝——
     * 8 个 worker 同时下载时，这份拷贝就是几十 MB 的瞬时峰值。
     * 长度未知（chunked）时才退回 `ByteArrayOutputStream`。
     */
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
        // 盘里存的是目标尺寸的 WebP：命中就直接解小图，不再需要原图。
        // 这一步的耗时单独记（`ArtworkCache` 全类共用一把 `@Synchronized` 锁，见 [ArtworkTiming.diskGet]）。
        val t0=android.os.SystemClock.elapsedRealtime()
        val cached = disk.get(diskKey(job.key))
        val tDisk=android.os.SystemClock.elapsedRealtime()
        timing.diskGet(tDisk - t0)
        // 网络这一段拆成「等到响应头」与「读完响应体」两截（见 [ArtworkTiming.record] 的 ttfb/body）。
        var proto = "-"
        var ttfbMs = 0L
        var bodyMs = 0L
        val bytes = cached ?: run {
            if (job.cancelled) return null
            val call = http.newCall(Request.Builder().url(job.key.url).build())
            job.call = call
            if (job.cancelled) { call.cancel(); return null }
            // 从这里开始算：这段时间里包含了「OkHttp 队列 → 建连/复用 → 服务端响应头」。
            // 它与 `netMs` 的差就是读响应体的时间，因此 `ttfb` 大 = 延迟问题，
            // `body` 大 = 带宽问题（两者要改的东西完全不同）。
            val tSend = android.os.SystemClock.elapsedRealtime()
            call.execute().use { response ->
                val tHeaders = android.os.SystemClock.elapsedRealtime()
                proto = response.protocol().toString()
                ttfbMs = tHeaders - tSend
                val body = response.body() ?: return null
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
        // 解码前先看一眼堆余量：解码要一次分配整张位图（320×457 ≈ 0.56MB，
        // 详情页大图可到 1MB+），缓存满了但还没触发 GC 时，这一步就是 OOM 的现场。
        ensureRoom(2L * 1024 * 1024)
        val bitmap = decode(bytes, job.targetWidth, job.targetHeight) { job.cancelled } ?: return null
        val tDecode=android.os.SystemClock.elapsedRealtime()
        // 只把**刚从网络取回来**的那一份写盘（缩略图）；命中盘缓存的路径不用再编码。
        if (cached == null) {
            if (job.cancelled) { bitmap.recycle(); return null }
            // 网络回来的那一份**本身就是 webp**（见 [ArtworkUrl]：请求的就是
            // `~tplv-shrink:640:0.webp`，且 640 档正是官方全站预热过的那个尺寸），
            // 因此直接把它落盘即可，不必再「解码成位图 → 重新编码成 webp」走一遍——
            // 实测那一步的 `enc` 均值 40ms，而它占了 worker 相当一部分时间
            // （几个线程全都排在磁盘锁后面）。这也是选 `.webp` 而不是命中率更高的
            // `.image` 的原因之一：`.image` 是 JPEG，落盘前得多花一次编码。
            // 只有拿到的不是 webp 时（签名域名那类原样下载的）才需要重新编码。
            val tPut = android.os.SystemClock.elapsedRealtime()
            if (isWebp(bytes)) disk.put(diskKey(job.key), bytes)
            else encodeForDisk(bitmap)?.let { disk.put(diskKey(job.key), it) }
            timing.diskPut(android.os.SystemClock.elapsedRealtime() - tPut)
        }
        val tEnd=android.os.SystemClock.elapsedRealtime()
        // 净时间（不含排队）交给计时器算 busy%——见 [ArtworkTiming.busy]。
        timing.busy(tEnd-startedAt)
        // 一张图的完整时间线：排队（等 worker）→ 读盘 → 网络 → 解码 → 编码落盘。
        // 四项分开记是必须的：只有看出「哪一项占大头」才知道该改什么（见 [ArtworkTiming]）。
        timing.record(fromDisk=cached!=null,bytes=bytes.size,queueMs=startedAt-job.queuedAt,
            netMs=if(cached!=null) 0 else tNet-tDisk,decodeMs=tDecode-tNet,encodeMs=tEnd-tDecode,
            totalMs=tEnd-job.queuedAt,pending=synchronized(signal){pending.size},
            host=job.key.url.substringAfter("//").substringBefore("/"),
            template=job.key.url.substringAfter("~", "none").substringBefore("?").take(34),
            url=job.key.url, proto=proto, ttfbMs=ttfbMs, bodyMs=bodyMs)
        return bitmap
    }

    /**
     * 把原始字节解成目标尺寸的位图。
     *
     * 两处关键优化：
     *
     * 0. **源图是 640 档的**（请求固定 640，见 [ArtworkUrl]），而位图要的是卡片尺寸——
     *    因此这里的缩放是必须的：640×914 的 webp 解到 320×457。`inSampleSize` 会先把
     *    采样降到 2 倍以内，再让解码器直接缩到位，全程只分配一张目标尺寸的位图。
     * 1. **一次解到目标尺寸**。先 `inJustDecodeBounds` 量出源尺寸、算出 `inSampleSize`，
     *    再让 `BitmapFactory` 用 `inScaled + inDensity/inTargetDensity` 直接产出目标尺寸——
     *    不再「解出接近源尺寸的位图、再 `createScaledBitmap` 复制一张」。
     *    原来那一步会在堆上同时存在两张位图（一屏 24 张就是多出约 10 MB 的瞬时峰值）。
     * 2. 低内存机型用 RGB_565：位图内存省一半（缓存因此装得下一屏），
     *    代价是大面积纯色渐变上可能出现轻微色带——海报类图片基本无感。
     */
    private fun decode(bytes: ByteArray, targetWidth: Int, targetHeight: Int, cancelled: () -> Boolean): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..32768 || bounds.outHeight !in 1..32768) return null
        // Fit the entire source into the measured target. Cropping at decode time cannot be
        // undone by ImageView.FIT_CENTER and used to remove most of a portrait poster.
        // 目标高度**不在 Key 里**（见 [Key]），由调用方作为参数传进来。
        val fit = min(1.0, min(targetWidth.toDouble() / bounds.outWidth, targetHeight.toDouble() / bounds.outHeight))
        val width = max(1, (bounds.outWidth * fit).toInt())
        val height = max(1, (bounds.outHeight * fit).toInt())
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
        if ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > (if (lowRam) 2 else 4) * 1024 * 1024) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            // 低内存机型用 RGB_565，其余用 ARGB_8888。
            //
            // 320×457 的 ARGB_8888 是 571KB、565 是 286KB。1GB 机器上 `cacheBytes`
            // 落在 32MB（`maxHeap/5` 夹到上限），8888 只装得下 **56 张**——装不下一屏
            // （可见 18~24 张 + 预取窗口 100+ 张），于是滚动时必然持续丢图重解，
            // 灰框 p90 直接跳到几百毫秒。565 之后同一预算装 114 张，覆盖一屏加余量。
            //
            // 早先这里写的是「565 会让网络变慢，所以不用」——那条结论不成立：
            // `inPreferredConfig` 是**解码端**参数，请求地址是同一个固定 640 档 webp，
            // 服务端无从得知客户端的像素格式，HTTP 耗时不可能因此变化；那 88ms
            // 只能来自换批次 / 冷 CDN key / 同时改了别的参数。
            //
            // 只在低内存档开：其余机型位图预算够，保留 8888 免得大面积渐变
            //（天空、发光）出现色带。
            inPreferredConfig =
                if (lowRam) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            // 采样后仍大于目标尺寸时，让解码器直接缩到位（一次分配、不再复制）。
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

    /** 这段字节是不是 WebP（`RIFF....WEBP`）：是的话可以原样落盘，不用重新编码。 */
    private fun isWebp(bytes: ByteArray): Boolean =
        bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()

    /**
     * 把解码后的位图缩成落盘用的 WebP。
     *
     * 只在「这次是从网络取回来的」时候调用（命中盘缓存的路径本来就已经是小图），
     * 因此每张封面一辈子只编码一次。编码失败就当没缓存，不影响显示。
     */
    private fun encodeForDisk(bitmap: Bitmap): ByteArray? = try {
        val out = ByteArrayOutputStream(32 * 1024)
        @Suppress("DEPRECATION") // API 26 起 WebP 有损编码可用，质量 80 足够做缩略图。
        bitmap.compress(Bitmap.CompressFormat.WEBP, 80, out)
        out.toByteArray()
    } catch (_: Throwable) { null }
}
