// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.IOException

/**
 * 播放地址已经失效（HTTP 403/404）。
 *
 * 单独一个类型是为了让界面层能分辨「地址过期，换一条地址重来一次就好」与
 * 「视频本身读不出来」。前者在回落盘缓存之后会变常见（缓存 20 分钟，
 * 而服务端可能提前让签名失效），必须能自己救回来，而不是让用户看到「解码失败」。
 */
class StreamExpiredException(message: String): IOException(message)

/**
 * 这一集的文件**换过了**（同一个地址，`Content-Range` 的总长与索引里记的不一致）。
 *
 * 与 [StreamExpiredException] 分开一个类型，是因为它在现场很常见却又最容易被误读：
 * 落盘的索引 / 首块是上一版的，重传之后的文件对不上，报错文本是「视频资源已变化」——
 * 用户看到的就是一句莫名其妙的失败。它的**处理方式与地址过期完全一样**：
 * 丢掉这一集的索引与地址，重取一次。界面层据此自愈，不给用户看错误页。
 */
class VideoChangedException(message: String): IOException(message)

/** One playback stream. No listening socket or local HTTP service. */
class RemoteVideo(private val http: OkHttpClient, val info: StreamInfo, private val cacheKey: String?=null): Closeable {
    @Volatile private var closed=false
    private val calls=mutableSetOf<Call>()
    data class Range(val bytes: ByteArray,val total: Long)
    /**
     * 这一集视频的实际域名。
     *
     * 自愈路径要按它清 DNS（见 `MainActivity.dropDeadTransport`）：地址是接口按集下发的
     * 签名 URL，相邻两集常常落在不同的 CDN shard / 不同的 IP 段上，因此「坏的是哪一个
     * 域名」只能从这一集自己的地址里读出来，不能拿上一集或接口域名顶替。
     * 解析失败返回空串（调用方按「没有域名可清」处理，不会因此中断自愈）。
     */
    val host: String get() = runCatching { java.net.URI(info.url).host }.getOrNull().orEmpty()
    var total: Long=0; private set
    private lateinit var prepared: MediaCrypto.Prepared
    /**
     * 已经拿到手的文件头部字节（`[0, size)`），从 0 开始连续。
     *
     * 起播时它承担两件事：
     *
     * * **索引解析期间并行把首块拉下来**（见 [startHeadPrefetch]）：解析两万多样本实测
     *   700~900ms，那段时间网络本来是空转的；而首块 512KB 单独要 700~1150ms。
     *   两件事并起来，首帧路径上就少掉整整一段 Range。
     * * 预热（[warm]）与重播（同一集切回来）直接从这里读，不再走网络。
     */
    @Volatile private var head=byteArrayOf()
    private val headLock=Any()
    @Volatile private var headJob: Thread?=null
    /**
     * 首块是不是**已经在盘上**了（本次命中盘上缓存，或本次已经写过一次）。
     *
     * 有它才不会每预热一次就把同一份 512KB 重写一遍：写盘虽在后台线程，
     * 反复写同一个文件既浪费 flash 也会和正在读它的播放线程抢 IO。
     */
    @Volatile private var headPersisted=false
    /**
     * 头部预取的目标长度（[prepare] 时读一次）。
     *
     * 冷起播只要 [HEAD_CHUNK]（够解第一帧）；[warm] 是「下一集预热」，它要 1MB，
     * 于是先把目标放大再 `prepare()`——这样那 1MB 仍然只发**一次** Range，
     * 而且照样与索引解析并行（不要预热时再补第二次，那是白多一个往返）。
     */
    @Volatile private var headTarget=HEAD_CHUNK
    private fun range(start: Long,end: Long): Range {
        if(closed) throw IOException("播放请求已取消")
        require(start>=0 && end>=start && end-start<8*1024*1024)
        val began=System.nanoTime()
        val request=Request.Builder().url(info.url).header("User-Agent",VendorConstants.VIDEO_UA).header("Referer","https://novel.snssdk.com/").header("Range","bytes=$start-$end").header("Accept-Encoding","identity").build()
        val call=http.newCall(request)
        synchronized(calls) { if(closed) throw IOException("播放请求已取消"); calls+=call }
        try {
            val result=RequestScope.execute(call) { response ->
                // 403 / 404：地址或文件不认了。缓存（索引 + 地址）必须一起作废，
                // 否则重试永远读到同一份已经失效的东西。
                if(response.code()==403 || response.code()==404) {
                    PlaybackCache.invalidateIndex(cacheKey)
                    throw StreamExpiredException("播放地址已失效（HTTP ${response.code()}）")
                }
                if(response.code()!=206) throw IOException("视频服务器不支持分段读取（HTTP ${response.code()}）")
                val match=Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty()) ?: throw IOException("视频分段响应异常")
                val (begin,finish,size)=match.destructured
                val full=size.toLong(); val last=minOf(end,full-1)
                if(begin.toLong()!=start || finish.toLong()!=last || full<=0) throw IOException("视频分段范围不匹配")
                if(total!=0L && full!=total) {
                    // 这一集的内容换过了（重新上传 / 换了一版切片）：落盘索引原地作废。
                    PlaybackCache.invalidateIndex(cacheKey)
                    throw VideoChangedException("视频资源已变化")
                }
                val n=(last-start+1).toInt(); val body=response.body() ?: throw IOException("视频分段为空")
                val bytes=body.source().readByteArray(n.toLong())
                Range(bytes,full)
            }
            // 起播那几秒里到底等了几个 Range、每个多久：只在调试包打（与 `Stage ...` 同一套开关）。
            if(NetLog.timing) {
                NetLog.log("video range bytes=$start-$end size=${result.bytes.size} took=${(System.nanoTime()-began)/1_000_000}ms")
            }
            return result
        } finally { synchronized(calls) { calls-=call } }
    }
    /**
     * 这一份能不能**原地复用**：索引已经解析好（[prepare] 走完过一遍），而且没有被 [close] 过。
     *
     * 给「同档重建」判用（见 `MainActivity.replayWithPlan`）：把解码器放掉重开时，
     * 只要这一份还能用就不必重取地址、也不必重读盘——索引、文件头、首块全在它里面。
     *
     * `close()` 之后必须判 false：那时 `head` 已经清空、在途请求全部取消，
     * 再拿去给播放器读只会得到一句「播放请求已取消」。
     */
    val isReusable: Boolean get() = ::prepared.isInitialized && !closed
    @Synchronized fun prepare(): RemoteVideo {
        if(::prepared.isInitialized) return this
        // 这一集最近已经解析过一遍（同一集重播 / 退出再进 / 进程重启之后）：
        // 索引直接从盘上读回来，省掉 moov 那 256KB 的 Range 与两万多样本的解析。
        // `total` 一并带回来，第一次 Range 的 `Content-Range` 会当场校验它——
        // 文件换过了就是一次「视频资源已变化」，上面那条分支会把这份缓存删掉。
        PlaybackCache.loadIndex(cacheKey)?.let { cached ->
            total=cached.total
            prepared=cached.prepared
            // 索引缓存里连头部字节一起存了（见 `PlaybackCache.saveIndex`）：直接当作已经读到的
            // 前 N 字节，并从头部结束处开始预取首块。于是「命中索引缓存」这条路连一次
            // moov Range 都不用发，首块还能在 `setMediaSource` 之前就备好。
            //
            // 首块本身也落盘了（见 `PlaybackCache.saveHead`）：这一集刚刚播过（主页 → 推荐、
            // 退出再进、进程被杀之后重进），前 512KB 直接从盘上读，`startHeadPrefetch` 会当场早退，
            // 首帧路径上**一次 Range 都不用发**。盘上没有时才退回「头部字节 + 后台补首块」。
            val diskHead=PlaybackCache.loadHead(cacheKey,total)
            synchronized(headLock) { head=diskHead ?: cached.prepared.header }
            headPersisted=diskHead!=null
            startHeadPrefetch()
            return this
        }
        val first=range(0,256*1024-1); total=first.total
        // 首块命中：索引可能刚被作废过（样本越界 / 换了清晰度键），但同一集的前 512KB 还在盘上。
        // 它比刚取到的 256KB 更长，直接顶上去——这一集就不必再为「首块」发一次 Range。
        // 正确性同样由 `total` 保证（对不上就是 null，见 `PlaybackCache.readHead`）。
        val diskHead=PlaybackCache.loadHead(cacheKey,total)?.takeIf { it.size>first.bytes.size }
        val base=diskHead ?: first.bytes
        synchronized(headLock) { head=base }
        headPersisted=diskHead!=null
        val end=MediaCrypto.headerEnd(base,total)
        val header=if(end>base.size) base+range(base.size.toLong(),end-1L).bytes else base.copyOf(end)
        // 索引解析之前就把首块派出去（见 [startHeadPrefetch]）：这一段与解析完全并行。
        startHeadPrefetch()
        // 这一段的耗时是「二次启动还能不能更快」的唯一判据：
        // moov 要几个 Range、解析两万多样本要多久，都拆开记一行。
        val began=System.nanoTime()
        prepared=MediaCrypto.prepare(header,info.key)
        if(prepared.samples.any { it.end>total }) throw IOException("视频样本超出资源大小")
        if(NetLog.timing) NetLog.log(
            "video index parse index=${header.size}B samples=${prepared.samples.size}"+
            " took=${(System.nanoTime()-began)/1_000_000}ms"
        )
        // 异步写：这一条 257KB 的索引写实测 139ms，而它只影响**下一次**起播。
        // 同步写等于让这一次的首帧白等 139ms（日志：`video index cache save ... took=139ms`
        // 正好夹在 `Stage prepare` 与 `Stage workDone` 之间）。
        PlaybackCache.saveIndexLater(cacheKey,total,prepared)
        return this
    }

    /**
     * 后台把 `[head.size, HEAD_CHUNK)` 这一段拉下来，**与索引解析同时跑**。
     *
     * 首帧只等这一块：1080P HEVC 的 I 帧实测 <300KB，512KB 够解出第一帧。
     * 剩下的由 [read] 在播放中继续取——那时画面已经在走，慢一点用户感觉不到。
     *
     * 失败静默：真要用的时候 [read] 自己会再取一次（与改动前完全一样）。
     */
    private fun startHeadPrefetch() {
        val target=minOf(total,headTarget)
        // 从**已经连续拿到的长度**开始：索引区（`moov`）常常落在第一个 256KB 里，
        // 那时 `head` 已经覆盖它，从这里接着取正好接上，不会重复拉一段。
        val from=head.size.toLong()
        if(from>=target) return
        headJob=Thread({
            runCatching {
                val bytes=range(from,target-1).bytes
                var reached=0
                synchronized(headLock) {
                    val current=head
                    // 只往后接：`warm()` 可能已经把它拉得更远了。
                    if(current.size==from.toInt()) {
                        val merged=current+bytes
                        head=merged
                        reached=merged.size
                    }
                }
                // 首块落盘：下次「主页 → 推荐」/ 退出再进这一集，首帧不必再等这 512KB。
                // 只在**后台线程**上写（本方法就跑在自己的线程里），主线程永远不做这 512KB 的写盘。
                if(reached>=HEAD_CHUNK) persistHead()
            }
        },"hgtv-video-head").apply { isDaemon=true; start() }
    }

    /**
     * 把已经连续拿到的前 [HEAD_CHUNK] 字节落盘（见 `PlaybackCache.saveHead`）。
     *
     * 只在预热 / 预取的后台线程上调用：512KB 的写盘在这台盒子上是几十毫秒，
     * 放在主线程就等于把刚省下来的那一段又还回去。
     */
    private fun persistHead() {
        val key=cacheKey ?: return
        if(headPersisted) return
        val size=minOf(total,HEAD_CHUNK).toInt()
        val snapshot=synchronized(headLock) { if(head.size>=size) head.copyOf(size) else null } ?: return
        headPersisted=true
        PlaybackCache.saveHead(key,total,snapshot)
    }

    /**
     * 已经连续拿到的前 N 字节（含索引区）。
     *
     * 给上层做「提取器类预热」用（见 `MainActivity.primeExtractor`）：那段字节就是
     * ExoPlayer 解析 moov 要读的前 512KB，拿它当场跑一遍能把它那条路径的类加载与 JIT 提前做掉。
     *
     * 直接返回内部数组（不复制）：`head` 只会被整体替换，从不原地改写，读到的永远是
     * 一份自洽的快照。
     */
    fun headBytes(): ByteArray = synchronized(headLock) { head }

    /**
     * 需要 `[0, size)` 里的字节：预取还在跑就等它（上限 [HEAD_WAIT_MS]）。
     *
     * 只等首块：seek 到中段时这一段早就结束了，`join` 立即返回。
     */
    private fun awaitHead(size: Long) {
        if(head.size>=size) return
        val job=headJob ?: return
        runCatching { job.join(HEAD_WAIT_MS) }
    }
    /** Bounded warm-up. Retains raw bytes so decryption still uses the absolute sample offset. */
    @Synchronized fun warm(limit: Long = WARM_BYTES): RemoteVideo {
        // 见 [headTarget]：先放大目标再 `prepare()`，1MB 只发一次 Range。
        headTarget=limit.coerceIn(HEAD_CHUNK,WARM_BYTES)
        prepare()
        val size=minOf(total,headTarget).toInt()
        awaitHead(size.toLong())
        val current=head
        if(current.size<size) {
            val more=range(current.size.toLong(),size-1L).bytes
            synchronized(headLock) { if(head.size<size) head=current+more }
        }
        // 预热走的这条补拉路径也写一份首块：这样「划回来」时连 512KB 都不用等。
        persistHead()
        return this
    }
    /**
     * 只准备「元数据」：取地址之后的索引解析 + 写盘，**不取首块**（文档 L2）。
     *
     * 与 [warm] 的差别就在 `headTarget`：这里停在索引区，一个视频字节都不多拉。
     * 调用方用完立刻 `close()`——索引已经在盘上，内存里没必要留着。
     */
    @Synchronized fun warmMeta(): RemoteVideo {
        // 0 = 不预取首块（见 [startHeadPrefetch] 的早退）：只把索引解析出来写盘。
        headTarget=0L
        prepare()
        return this
    }
    fun read(start: Long,length: Int): ByteArray {
        require(length in 1..2*1024*1024 && start>=0)
        if(closed) throw IOException("播放请求已取消")
        if(start>=total) return byteArrayOf()
        val began=System.nanoTime()
        // 首块（含索引区）可能还在后台拉：等它比重新发一次 Range 便宜得多。
        if(start<HEAD_CHUNK) awaitHead(minOf(start+length.toLong(),HEAD_CHUNK))
        val cached=head; val end=minOf(total,start+length)
        val available=(minOf(end,cached.size.toLong())-start).coerceAtLeast(0).toInt()
        val first=if(available>0) cached.copyOfRange(start.toInt(),start.toInt()+available) else byteArrayOf()
        val fetchedAt=System.nanoTime()
        val raw=if(start+available<end) first+range(start+available,end-1).bytes else first
        val decryptedAt=System.nanoTime()
        val plain=MediaCrypto.decrypt(raw,start,info.key,prepared)
        if(NetLog.timing) {
            val done=System.nanoTime()
            NetLog.log(
                "video read start=$start len=$length cached=$available"+
                " fetch=${(fetchedAt-began)/1_000_000}ms"+
                " net=${(decryptedAt-fetchedAt)/1_000_000}ms"+
                " decrypt=${(done-decryptedAt)/1_000_000}ms"
            )
        }
        return plain
    }
    /**
     * 关掉这一集。
     *
     * `head` 是**为了少走一次网络**而留的前 1 MB 原始字节（见 [warm]），
     * 它跟播放器生命周期同长；一集切走之后没有人还会读它，留着就是白占 1 MB——
     * 切集时 `video` / `queuedVideo` / `retiringVideo` 可能同时存在，最多就是 3 MB。
     */
    override fun close() {
        synchronized(calls) { closed=true; calls.toList().forEach { it.cancel() }; calls.clear() }
        synchronized(headLock) { head=byteArrayOf() }
    }

    private companion object {
        /** 首块目标：够解出第一帧（见 [startHeadPrefetch]）。 */
        const val HEAD_CHUNK=512L*1024
        /** 预热（下一集 / 相邻剧集）保留的长度，与改动前一致。 */
        const val WARM_BYTES=1024L*1024
        /** 等预取的上限：超过就自己发 Range，绝不在首帧路径上干等。 */
        const val HEAD_WAIT_MS=3_000L
    }
}
