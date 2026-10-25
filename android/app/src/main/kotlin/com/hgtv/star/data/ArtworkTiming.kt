// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.data

import android.os.SystemClock
import android.util.Log

/**
 * 封面加载的**计时统计**（只在 debug 包里用日志看，不参与界面逻辑）。
 *
 * 为什么要它：封面「慢」有好几种完全不同的原因，靠肉眼分辨不出来——
 *
 * * **网络慢**（单张 `net` 大）→ 该换尺寸 / 格式，或者换 CDN；
 * * **并发不够**（`q` 排队久、有效并发远小于 worker 数）→ 该加 worker；
 * * **解码慢**（`decode` 大）→ 该减尺寸、换 RGB_565；
 * * **请求发得不够快**（每张都很健康，但墙钟时间远大于「张数 × 单张 ÷ worker」）→
 *   问题不在图片层，而在**列表侧的请求节奏**（比如队列上限 [ArtworkLoader.MAX_PENDING]
 *   一直被打满、或者每次翻页都把在途请求全掐掉重发）。
 *
 * 因此每张图都打一行**机器可读**的记录，并在关键张数（见 [MARKS]）打一行汇总。
 * 汇总里 `par` 是「有效并发」= 单张耗时之和 ÷ 墙钟时间：它接近 worker 数说明吞吐被
 * worker 打满；远小于 worker 数说明有大量时间没人干活（请求没接上或反复被取消）。
 */
internal class ArtworkTiming {
    companion object {
        const val TAG = "HGTV-IMG"
        /**
         * 只在 debug 包里打日志。
         *
         * 每一张图一行 + 每几十张一行汇总，量很大（跑一千张就是一千多行），
         * 在真机上 logcat 写入本身会成为可测量的开销——诊断用的东西不能拖慢正式使用。
         * 正式包连字符串拼接都不做（[record] 开头就返回）。
         */
        val enabled = com.hgtv.star.BuildConfig.DEBUG
        /**
         * worker 数（只用于算 `busy%`，由 [ArtworkLoader] 注入）。
         *
         * 这里存一份而不是去问加载器：计时器不该反过来依赖它（会绕成循环依赖，
         * 也会让「锁的归属」更难看清）。
         */
        @Volatile var WORKER_HINT = 8
        /** 存多少条样本用于算分位数（够 500 张就行，留足余量）。 */
        private const val SAMPLE_LIMIT = 8000
        /** 在这些张数上打汇总行。 */
        private val MARKS = setOf(25, 50, 75, 100, 150, 200, 250, 300, 350, 400, 450, 500,
            600, 700, 800, 1000, 1250, 1500, 2000, 3000, 4000, 5000)
    }
    /**
     * 汇总行的落盘文件（`filesDir/artwork-timing.txt`）。
     *
     * 为什么要落盘：真机上抓 logcat 要连电脑、要滚屏、环形缓冲还会把前面的行冲掉——
     * 而这些数恰恰是「按住下键走几百张」之后才有意义的。写文件之后跑完直接把
     * 这一个文件拿出来就能看全（每张一行的那份仍然只进 logcat，不进文件）。
     */
    @Volatile private var sink: java.io.File? = null

    /**
     * **每张图一行 logcat** 的开关（默认关）。
     *
     * 为什么要能关：logcat 写入是**同步**的，一行要几毫秒（模拟器上更多），
     * 而这里一张图打一行、一跑就是几千行。它花的时间**不进 `busy%`**（那是在 `fetch` 里量的），
     * 却实实在在占着 worker 的墙钟时间——表现出来就是「worker 占用率只有 25%」这种假象。
     * 汇总行仍然**始终落盘**（[persist]），因此关掉它不影响拿数据。
     *
     * 打开方式（debug 包）：
     * ```
     * adb shell "run-as com.hgtv.star sh -c 'echo 1 > /data/data/com.hgtv.star/files/verbose'"
     * ```
     */
    @Volatile var verbose = false

    /** 由 `Library` 在启动时给一个可写的文件路径（见 `MainActivity.onCreate`）。 */
    fun attach(file: java.io.File?) { sink = file }

    /** 由 `ArtworkLoader` 在构造时调：从 `filesDir/verbose` 决定要不要打每张一行。 */
    fun attachVerbose(file: java.io.File?) {
        verbose = file != null && runCatching { file.readText().trim() == "1" }.getOrDefault(false)
    }

    private fun persist(line: String) {
        val target = sink ?: return
        runCatching { target.appendText(line + "\n") }
    }

    private val lock = Any()

    /**
     * `ArtworkLoader.signal`（由它注入）。本类的锁**绝不允许**在持有它时获取。
     *
     * 2026-10-17 00:49 的 ANR 就是这么来的：主线程在 `request()` 里持有 `signal` 时调用
     * [coldMiss]（拿本类的锁），同时图片线程在 [record] 里持有本类的锁、又通过
     * 「现场」那个回调去拿 `signal`——AB-BA 死锁，主线程卡 25 秒被判 ANR。
     *
     * 现在所有埋点都挪到锁外了，但这类错误很容易在后续改动里复发（加一行统计就够了），
     * 因此这里加一道**只在 debug 包生效**的检测：持有 `signal` 时进本类的锁，就报一条日志。
     */
    @Volatile private var loaderLock: Any? = null

    fun loaderLock(lock: Any) { loaderLock = lock }

    private inline fun <T> guarded(block: () -> T): T {
        val loader = loaderLock
        if (enabled && loader != null && Thread.holdsLock(loader)) {
            Log.e(TAG, "检测到在持有 ArtworkLoader 锁时进入计时器——这正是死锁的形状，" +
                "把这次埋点挪到锁外（见 ArtworkTiming 的类注释）")
        }
        return synchronized(lock) { block() }
    }
    private var firstAt = 0L
    private var lastAt = 0L
    private var count = 0
    private var diskHits = 0
    private var failures = 0
    private var enqueuedCount = 0
    private var capDrops = 0
    private var pageCancels = 0
    private var detachCancels = 0
    private var queuedCancels = 0
    private var detachKeeps = 0
    private var coldMiss = 0
    private var warmHit = 0
    private var prefetchQueued = 0
    private var prefetchSkipped = 0
    private var prefetchCalls = 0
    private var prefetchCancelled = 0
    private var dupEnqueue = 0
    private var evicted = 0
    private var skippedOnDisk = 0
    private var busyMs = 0L
    /** 预取请求过的 Key（前若干个的「尺寸指纹」），用来和可见卡要的对比。 */
    private val prefetchShapes = LinkedHashMap<String, Int>()
    private val visibleShapes = LinkedHashMap<String, Int>()
    private var visibleQueueSum = 0L
    private var visibleQueueN = 0
    private var visibleQueueMax = 0L
    /**
     * 汇总行里额外附上的一小段现场（由 [ArtworkLoader] 提供）。
     *
     * **在锁外调用**：它内部会去拿 `ArtworkLoader.signal`，而主线程可能在持有 `signal`
     * 的同时调用本类的 `coldMiss()` 等方法（拿本类的锁）。两个方向交叉就是死锁
     * （见 `ArtworkLoader.timingContext`）。
     */
    @Volatile var context: () -> String = { "" }
    private val blanks = ArrayList<Int>(512)
    private var blankOver200 = 0
    private var blankMax = 0
    private var bytes = 0L
    /**
     * 「握手」与「传输」分开记。
     *
     * 设备上实测的分解（`dns=3ms conn=30ms tls=35ms ttfb=30ms total=107ms`）说明
     * **握手占了六成**，真正等服务器的只有三成。若 `ttfbAvg` 远大于 `bodyAvg`，
     * 说明瓶颈在「连接建立 / 服务端响应」而不是带宽——那就该靠连接复用（`connNew` 小）
     * 与多路复用解决，而不是把图缩得更小（实测字节翻 3 倍只多 15ms）。
     */
    private var ttfbSum = 0L
    private var bodySum = 0L
    /**
     * 新建 TCP 连接 / 新建 TLS 握手的次数（由 `OkHttp` 的 `EventListener` 记）。
     *
     * `connNew` 接近 `n` 就说明**连接根本没被复用**：每个请求都从 DNS 开始重来。
     * 这是 `netAvg` 里那块固定开销的唯一直观证据。
     */
    private var connNew = 0
    private var tlsNew = 0
    /** 实际协商到的协议（`h2` / `http/1.1`）。服务端支持 h2，客户端不一定用上了。 */
    private val protocols = LinkedHashMap<String, Int>()
    /**
     * worker 在 `signal.wait()` / 取任务上花掉的时间（毫秒，累计）。
     *
     * 这是判断「worker 空转」的**唯一直接证据**：`busy%` 只说明「有多少时间在干活」，
     * 却看不出剩下那些时间花在哪——是在等任务（队列被抽干），还是在等锁（磁盘缓存争用）。
     * `idle` 把「等任务 + 拿调度锁」那一截单独量出来，两者一减就知道是谁。
     */
    private var idleSum = 0L
    private var idleCount = 0
    /**
     * worker 取到任务后，**整个循环体**（含探盘、读盘、网络、解码、落盘）的累计墙钟时间。
     *
     * `busy` 只量 `fetch()` 里面那一段，而循环体里还有两处在 `fetch` 之外：
     * 预取探盘（`disk.has`）与内存缓存查询（`cache.get`）。
     * `loopSum + idleSum` 应该约等于 `workers × wall`——对不上就说明有第三处在吃时间
     * （最可能是 `ArtworkCache` 那把全局锁）。
     */
    private var loopSum = 0L
    /** worker 取到任务的次数，以及其中「队列是空的、真的等过」的次数。 */
    private var polls = 0
    private var starved = 0
    /**
     * `ArtworkCache` 那三个 `@Synchronized` 方法各自的累计耗时。
     *
     * 它们共用**同一把锁**，而 8 个 worker 都要走这条路：只要其中一个进了全目录扫描
     * （[ArtworkCache] 的 `prune`），其余全部在锁外排队。这项如果大，
     * 表现就是「网络明明只要 200ms，图却要几秒才出来」。
     */
    private var diskGetSum = 0L
    private var diskPutSum = 0L
    private var diskHasSum = 0L
    private var diskHasCount = 0
    private var queueSum = 0L
    private var netSum = 0L
    private var decodeSum = 0L
    private var totalSum = 0L
    private val totals = ArrayList<Int>(512)
    private val nets = ArrayList<Int>(512)

    /** 每建一个请求就记一次（含后来被取消的）：它是「请求发得够不够快」的分母。 */
    fun enqueued() { guarded { enqueuedCount++ } }

    /** 队列满被挤掉的（[ArtworkLoader.MAX_PENDING] 那条策略）。 */
    fun capDrop() { guarded { capDrops++ } }

    /** 换页 / 重建时被掐掉的在途请求数。 */
    fun pageCancel(size: Int) { guarded { pageCancels += size } }

    /**
     * 卡片被解绑（滚出窗口 / 行被回收复用）时掐掉的请求数。
     *
     * 这个数字最要紧：**每掐一个，已经花掉的网络与 CPU 就全白费**，而且那张图
     * 下次滚回来还要重下。它远大于「完成的张数」时，用户看到的就是
     * 「焦点走到哪儿才开始加载那一片」——因为前面的都在途中被掐了。
     */
    fun detachCancel(queued: Boolean) {
        guarded { detachCancels++; if (queued) queuedCancels++ }
    }

    /**
     * 卡片被解绑但请求**留着跑完**的次数（见 `ArtworkLoader.detachJob`）。
     *
     * 这个数大是**好事**：说明滚过去的那几屏真的在下图，而不是「排上队又被掐掉」。
     */
    fun detachKeep() { guarded { detachKeeps++ } }

    fun failed() { guarded { failures++ } }

    /**
     * 一张**可见**的卡从「开始等图」到「图出现」的时长（毫秒）。
     *
     * 这是最贴近用户感受的指标：它就是「屏幕上那块灰框存在了多久」。
     * 用户说的「刚开始完全无感、到了某处周围一圈灰框」在这里就是
     * 「绝大多数 < 100ms，但某一段出现几百毫秒的尾巴」。
     *
     * 只看**第一次**要图到拿到图（重复请求同一张不算），因此它不会因为
     * 滚动时的反复绑定而虚高。
     */
    fun blank(ms: Long) {
        synchronized(lock) {
            if (blanks.size < SAMPLE_LIMIT) blanks += ms.toInt()
            if (ms > 200) blankOver200++
            if (ms > blankMax) blankMax = ms.toInt()
        }
    }

    /**
     * 一张**可见**的卡要图时，那张图还没被请求过（队列里没有、内存缓存里也没有）。
     *
     * 这是判断「预取够不够」的唯一硬指标：
     * * 冷失多 → 预取覆盖不到，图是「焦点到了才开始下」（要靠扩大预取窗口解决）；
     * * 冷失少而灰框多 → 图**已经在路上**，只是没赶在用户看到之前完成（要靠加并发 / 减字节解决）。
     */
    fun coldMiss() { guarded { coldMiss++ } }

    /** 要图时那张图已经在队列里（预取排上的）。 */
    fun warmHit() { guarded { warmHit++ } }

    /** 预取排进队列 / 因队列满被跳过。 */
    fun prefetchQueued() { guarded { prefetchQueued++ } }

    /** [ArtworkLoader.prefetchWindow] 被调用的次数，以及它丢掉了多少个滑出窗口的预取。 */
    fun prefetchCall() { guarded { prefetchCalls++ } }
    fun prefetchCancelled(n: Int) { guarded { prefetchCancelled += n } }

    /**
     * 同一张封面**被重新入队**的次数（它之前已经完整下过一次、但已被挤出缓存）。
     *
     * 这个数大说明缓存在抖动：图下回来了、又被预取挤出内存，于是下次还要重下。
     * 它直接决定「滚动到某处之后开始出现灰框」——前几百张都在缓存里所以秒开，
     * 缓存开始抖动之后每一张都得重下。
     */
    fun duplicateEnqueue() { guarded { dupEnqueue++ } }

    /**
     * 被内存缓存的 LRU 挤出去的张数（见 `ArtworkLoader` 里的 `entryRemoved`）。
     *
     * 它开始涨就说明**缓存装不下预取窗口**：预取下的图在用户走到之前就被挤掉了，
     * 那一次预取白做，用户到那里只能重下。
     */
    fun evicted() { guarded { evicted++ } }

    /**
     * 预取任务因为「图已经在磁盘里」被直接丢掉的数量（见 `ArtworkLoader.work`）。
     *
     * 这个数是**好事**：说明预取没有去做「磁盘 → 内存」的无用搬运，
     * worker 因此留给了可见卡。它涨起来之后 `qAvg` 应该跟着掉。
     */
    fun prefetchSkippedOnDisk() { guarded { skippedOnDisk++ } }

    /**
     * 记下这次请求的「尺寸指纹」（`宽x高`）。
     *
     * 为什么需要它：`warmHit` 只能告诉你「有没有命中」，命不中时看不出**差在哪**。
     * 把预取与可见卡各自的尺寸分布打出来，一眼就能看出是不是尺寸算得不一样
     * （早先就靠它排除了「预取与可见卡 Key 不同」这个假设）。
     */
    fun shape(prefetch: Boolean, width: Int, height: Int) {
        guarded {
            val map = if (prefetch) prefetchShapes else visibleShapes
            if (map.size < 12) { val k = "${width}x$height"; map[k] = (map[k] ?: 0) + 1 }
        }
    }

    private fun shapesText() =
        "pfShape=${prefetchShapes.entries.joinToString(",") { "${it.key}:${it.value}" }} " +
            "visShape=${visibleShapes.entries.joinToString(",") { "${it.key}:${it.value}" }}"


    /**
     * **可见**任务从入队到被 worker 取走的等待时长（毫秒）。
     *
     * 这个数才是「用户按下键之后，那一屏的图要等多久才开工」。
     * 总体的 `qAvg` 会把大量预取任务的排队时间混进来（它们本来就该慢慢排），因此不能看那个。
     * 它偏大只有一个原因：worker 全被预取占着（见 `ArtworkLoader.prefetchInFlight`）。
     */
    fun visibleQueue(ms: Long) {
        synchronized(lock) {
            visibleQueueSum += ms; visibleQueueN++
            if (ms > visibleQueueMax) visibleQueueMax = ms
        }
    }
    fun prefetchSkipped() { guarded { prefetchSkipped++ } }

    /**
     * worker 花在这张图上的**净时间**（网络 + 解码 + 编码，不含排队）。
     *
     * 汇总里的 `busy%` = 所有净时间之和 ÷ (worker 数 × 墙钟时间)。
     * 它才是「worker 有没有被喂饱」的唯一判据：
     *
     * * 接近 100% → 吞吐已经打满，再想快只能减单张成本（更小的图 / 更快的 CDN）；
     * * 远低于 100% → **worker 在空转**，问题在调度（谁拿不到活、谁在等锁），不在网络。
     *
     * 实测过一次这个数掉到 9% 的情况：`qAvg=364ms` 而 `netAvg` 只有 18ms，
     * 单张净时间 21ms、队列里还有 163 个任务——时间全花在「等一个能开工的 worker」上。
     */
    fun busy(ms: Long) { guarded { busyMs += ms } }

    /**
     * 一个 worker 在「等任务 / 拿调度锁」上花掉的时长（毫秒）。
     *
     * **必须在 `signal` 之外调用**（见类注释里的锁顺序）。
     */
    fun idle(ms: Long, waited: Boolean) {
        guarded { idleSum += ms; idleCount++; polls++; if (waited) starved++ }
    }

    /** 一个任务从「被 worker 取走」到「彻底处理完」的墙钟时间（见 [loopSum]）。 */
    fun loop(ms: Long) { guarded { loopSum += ms } }

    /** 读盘缓存（`ArtworkCache.get`）的耗时。 */
    fun diskGet(ms: Long) { guarded { diskGetSum += ms } }

    /** 写盘缓存（`ArtworkCache.put`，含可能的目录清理）的耗时。 */
    fun diskPut(ms: Long) { guarded { diskPutSum += ms } }

    /** 预取探盘（`ArtworkCache.has`）的耗时。 */
    fun diskHas(ms: Long) { guarded { diskHasSum += ms; diskHasCount++ } }

    /** `OkHttp` 开始建一条新连接（不是从连接池里复用）。 */
    fun connectionNew() { guarded { connNew++ } }

    /** `OkHttp` 开始一次新的 TLS 握手。 */
    fun tlsHandshake() { guarded { tlsNew++ } }

    /** 一张图加载完成。所有时间单位都是毫秒。 */
    fun record(fromDisk: Boolean, bytes: Int, queueMs: Long, netMs: Long, decodeMs: Long,
               encodeMs: Long, totalMs: Long, pending: Int, host: String, template: String, url: String,
               proto: String, ttfbMs: Long, bodyMs: Long) {
        if (!enabled) return
        // 每张一行的字符串拼接本身也有成本（几千次 `format`），因此同样按开关来。
        var line: String? = null
        val summary: String?
        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            if (firstAt == 0L) {
                firstAt = now
                Log.i(TAG, "START 开始计时（磁盘缓存已清空）")
                persist("START 开始计时（磁盘缓存已清空）")
            }
            lastAt = now
            count++
            if (fromDisk) diskHits++
            this.bytes += bytes
            queueSum += queueMs; netSum += netMs; decodeSum += decodeMs; totalSum += totalMs
            ttfbSum += ttfbMs; bodySum += bodyMs
            protocols[proto] = (protocols[proto] ?: 0) + 1
            if (totals.size < SAMPLE_LIMIT) { totals += totalMs.toInt(); nets += netMs.toInt() }
            if (verbose) {
                line = "n=$count t=${"%.2f".format((now - firstAt) / 1000.0)} q=$queueMs net=$netMs bytes=$bytes " +
                    "ttfb=$ttfbMs body=$bodyMs proto=$proto " +
                    "decode=$decodeMs enc=$encodeMs total=$totalMs pending=$pending disk=${if (fromDisk) 1 else 0} " +
                    "host=$host tpl=$template url=$url"
            }
            summary = if (count in MARKS) snapshotLocked() else null
        }
        line?.let { Log.i(TAG, it) }
        // 现场在**锁外**取：它要去拿 ArtworkLoader 的锁，而在锁内拿就是死锁（见 [context]）。
        summary?.let { line -> val full = "$line ${context()}"; Log.i(TAG, full); persist(full) }
    }

    /** 换页 / 暂停等边界打一行：把「这一段统计」和下一段分开。 */
    fun mark(reason: String) {
        if (!enabled) return
        val summary = guarded { if (count == 0) null else snapshotLocked() }
        val body = summary?.let { "$it ${context()}" }
        val line = "MARK $reason" + (body?.let { " | $it" } ?: "（还没有加载过图）")
        Log.i(TAG, line); persist(line)
    }

    private fun snapshotLocked(): String {
        val wall = (lastAt - firstAt) / 1000.0
        val avg = if (count > 0) totalSum.toDouble() / count else 0.0
        // 有效并发 = 单张耗时之和 ÷ 墙钟时间。接近 worker 数 = 吞吐被打满。
        val par = if (wall > 0) (totalSum / 1000.0) / wall else 0.0
        return "STATS n=$count wall=${"%.2f".format(wall)}s rate=${"%.2f".format(if (wall > 0) count / wall else 0.0)}/s " +
            "bytes=${"%.2f".format(bytes / 1048576.0)}MB avgBytes=${bytes / count.coerceAtLeast(1)} " +
            "qAvg=${queueSum / count.coerceAtLeast(1)} netAvg=${netSum / count.coerceAtLeast(1)} " +
            "ttfbAvg=${ttfbSum / count.coerceAtLeast(1)} bodyAvg=${bodySum / count.coerceAtLeast(1)} " +
            "proto=${protocols.entries.joinToString(",") { "${it.key}:${it.value}" }} " +
            "connNew=$connNew tlsNew=$tlsNew " +
            "decodeAvg=${decodeSum / count.coerceAtLeast(1)} totalAvg=${avg.toInt()} " +
            "p50=${pct(totals, 50)} p90=${pct(totals, 90)} p99=${pct(totals, 99)} " +
            "netP50=${pct(nets, 50)} netP90=${pct(nets, 90)} " +
            "par=${"%.2f".format(par)} enq=$enqueuedCount diskHit=$diskHits capDrop=$capDrops " +
            "detachKeep=$detachKeeps detachCancel=$detachCancels (queued=$queuedCancels) " +
            "pageCancel=$pageCancels fail=$failures " +
            "blankN=${blanks.size} blankP50=${pct(blanks, 50)} blankP90=${pct(blanks, 90)} " +
            "blankMax=$blankMax blankOver200=$blankOver200 " +
            "coldMiss=$coldMiss warmHit=$warmHit pfQueued=$prefetchQueued pfSkipped=$prefetchSkipped " +
            "pfCalls=$prefetchCalls pfCancelled=$prefetchCancelled dupEnq=$dupEnqueue evicted=$evicted " +
            "skipOnDisk=$skippedOnDisk ${shapesText()} busy=${if (wall > 0) busyMs * 100 / (WORKER_HINT * wall * 1000).toLong() else 0}% " +
            "idleAvg=${idleSum / idleCount.coerceAtLeast(1)} idleSum=${idleSum / 1000}s " +
            "loopSum=${loopSum / 1000}s polls=$polls starved=$starved " +
            "workPct=${if (wall > 0) (loopSum + idleSum) * 100 / (WORKER_HINT * wall * 1000).toLong() else 0}% " +
            "diskGetAvg=${diskGetSum / count.coerceAtLeast(1)} diskPutAvg=${diskPutSum / count.coerceAtLeast(1)} " +
            "diskHasAvg=${diskHasSum / diskHasCount.coerceAtLeast(1)} " +
            "visQAvg=${visibleQueueSum / visibleQueueN.coerceAtLeast(1)} visQMax=$visibleQueueMax (n=$visibleQueueN)"
    }

    private fun pct(samples: List<Int>, percent: Int): Int {
        if (samples.isEmpty()) return 0
        val sorted = samples.sorted()
        return sorted[((sorted.size - 1) * percent / 100).coerceIn(0, sorted.size - 1)]
    }
}
