// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 二次启动 / 二次播放用的**落盘**缓存：视频索引（moov + 两万条样本表）与播放地址。
 *
 * ## 为什么值得做
 *
 * 冷启动实测（S905L3B / 2G）：
 *
 * ```
 * Stage stream +1143ms   ← 含取播放地址（服务端 0.9s）
 * Stage prepare +2867ms  ← 含 moov 分段下载 + 解析（解析约 700~900ms，两万多样本）
 * ```
 *
 * 这两段里有 1.5~2 秒是**纯本地可复用**的活：同一集的 moov 不会变、播放地址在有效期内
 * 也不会变。第一次播放把它们算完、写盘；之后（重放同一集、退出再进、进程被杀之后）
 * 直接读盘，`Stage prepare` 只剩一次首块 Range。
 *
 * ## 为什么不缓存更多
 *
 * 低端机上限很硬（2G / 256MB 堆）：
 *
 * * **索引**：一集约 0.6~1.5MB（头部 256~600KB + 每样本 32 字节），值得；
 * * **首块视频数据**：一集 2MB 起，几部就上 10MB，**不落盘**（只留 [RemoteVideo] 里那份 1MB 内存预热）；
 * * **解码器实例 / ExoPlayer**：跨进程不可能，池化又要在原地重配另一部剧的 SPS，
 *   正是「刷几部之后黑屏有声」的来源，不做（见 `MainActivity.playEpisode` 里 `seamless` 那段注释）。
 *
 * ## 失效
 *
 * * 索引：`total`（文件字节数）对不上、样本越界、HTTP 403/404 → 当场删掉重来；
 * * 地址：超过 [URL_FRESH_MS] 就重新取（见下面的「统一窗口」）；
 * * 目录总大小超过 [MAX_BYTES]：按 `lastModified` 淘汰最旧的一半；
 * * 本机「清空数据」：`LocalReset` → [clear]。
 *
 * 这里只做文件读写，不认识 Android（`core` 是纯 JVM 模块），目录由 `HgtvApplication` 传进来，
 * 单元测试直接给一个临时目录即可。
 */
object PlaybackCache {
    private const val MAGIC=0x48475456 // "HGTV"
    private const val INDEX_VERSION=1
    /**
     * 地址缓存格式版本。
     *
     * 2 起多了「请求档位 / 实际档位 / 实际编码 / 全部可播档位」四项（见 [StreamInfo]）。
     * 版本不符就整条当未命中：旧文件留在盘上会被下一次 [saveUrl] 覆盖，不会错位读取。
     */
    private const val URL_VERSION=3
    private const val HEAD_VERSION=1
    /** 首块上限：与 `RemoteVideo.HEAD_CHUNK` 一致（512KB），留一点余量。 */
    private const val MAX_HEAD_BYTES=1024*1024

    /**
     * **地址的统一窗口期：10 分钟。超过就重新取地址。**
     *
     * 这是全站唯一一处定义，内存缓存、落盘缓存、预热结果（队列项 / 推荐页）全部引用它——
     * 改造前这三个数字各写各的（2 分钟 / 20 分钟 / 无上限），于是「哪一份地址还能用」
     * 取决于它从哪条路进来，同一集在不同路径下有效期不一样，排查时根本对不上。
     *
     * ## 为什么是 10 分钟
     *
     * 服务端签发的有效期通常 1~2 小时，但**实测会提前作废**：签名时效、CDN 换线、
     * 负载均衡摘掉某个边缘节点，都会让一条「名义上还有效」的地址返回 403。
     * 现场表现就是「播几集后按下一集 → 地址过期：播放地址已失效（HTTP 403）」，
     * 退出再进又好了（缓存被清掉，重新取地址）。
     *
     * 10 分钟的依据是**真实节奏**：一集短剧 2~3 分钟，连看几集也在一集之内完成预热与消费；
     * 超过 10 分钟还留着的地址，说明用户已经离开这条节奏（暂停、走开、停在某一页），
     * 这时多花 500~1100ms 重取一次，远好过拿一条可能已被作废的地址去撞 403。
     *
     * ## 代价
     *
     * 「重看已看过的集」从 20 分钟内免取地址降到 10 分钟。超出窗口的那一次多一次网络往返，
     * 换来的是「不会拿死地址起播」——用户可感知的失败比半秒等待贵得多。
     */
    const val URL_FRESH_MS=10*60*1000L

    /**
     * 一份**预热结果**还能不能直接拿去起播。
     *
     * 与 [URL_FRESH_MS] 同一个窗口，因此直接引用它——预热结果里装的就是一份地址，
     * 没有任何理由比落盘的地址活得更久。
     *
     * 现场故障「播几集后按下一集 → 地址过期（HTTP 403）」的直接成因就在这里：
     * 队列项（`queuedVideo`）与推荐页预热（`recommendPrepared`）都记了生成时刻，
     * 但改造前**只用来做 LRU 排序，取用时从不校验**。用户在片尾暂停十几分钟再继续，
     * 或者十几分钟后才划回预热过的那一部，就会拿一条已经作废的地址去起播。
     */
    const val PREPARED_FRESH_MS=URL_FRESH_MS

    /**
     * 一份预热结果还能不能用。
     *
     * @param preparedAt 生成时刻（`SystemClock.elapsedRealtime()` 口径）
     * @param now 当前时刻，同口径
     */
    fun isPreparedFresh(preparedAt: Long,now: Long): Boolean =
        preparedAt>0L && now-preparedAt in 0..PREPARED_FRESH_MS
    /** 索引的兜底寿命：文件本身变了会先被 `total` 校验挡掉，这里只防目录无限长草。 */
    private const val INDEX_TTL_MS=7L*24*60*60*1000
    /** 目录总大小上限：索引 0.6~1.5MB 一条，48MB 够留二十多集。 */
    private const val MAX_BYTES=48L*1024*1024
    /** 一条索引的上限（`MediaCrypto.headerEnd` 允许 8MB 的头部，这里留足余量）。 */
    private const val MAX_FILE_BYTES=16L*1024*1024

    data class Index(val total: Long,val prepared: MediaCrypto.Prepared)
    data class Url(val info: StreamInfo,val at: Long)

    @Volatile private var root: File?=null
    /** 读写都在这把锁里串行：起播、预热、下一集预取会同时碰它。 */
    private val lock=Any()
    @Volatile private var lastTrim=0L
    /**
     * 索引的**异步写盘**线程（见 [saveIndexLater]）。
     *
     * 一条 257KB 的索引写在这台盒子上是 **139ms**（日志：`video index cache save
     * bytes=257084 samples=4554 took=139ms`），而它原来就在 `prepare()` 里同步做——
     * 也就是首帧路径上白等 139ms。索引只影响**下一次**起播，没有任何理由让这一次等它。
     */
    private val indexWriter by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable,"hgtv-index-write").apply { isDaemon=true }
        }
    }

    fun init(dir: File) {
        if(root!=null) return
        synchronized(lock) {
            if(root!=null) return
            runCatching { dir.mkdirs() }
            root=dir
        }
        // 目录可能已经长到几十 MB（上个版本的残留 / 用户反复试不同剧）：后台收一次。
        Thread({ runCatching { trim() } },"hgtv-cache-trim").apply { isDaemon=true }.start()
    }

    private fun dir(): File? = root?.takeIf { it.isDirectory || it.mkdirs() }

    /** 单元测试用：卸掉目录，回到「没有缓存」的状态。 */
    internal fun detach() { synchronized(lock) { root=null } }

    private fun sha1(value: String): String {
        val digest=MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
        val out=StringBuilder(digest.size*2)
        for(byte in digest) out.append("0123456789abcdef"[(byte.toInt() shr 4) and 0xF]).append("0123456789abcdef"[byte.toInt() and 0xF])
        return out.toString()
    }

    private fun indexFile(key: String): File? = dir()?.let { File(it,"idx-${sha1(key)}.bin") }
    private fun urlFile(key: String): File? = dir()?.let {
        // 文件名带**集 id 前缀**：地址失效时要能按集一次删掉所有档位的那条
        //（见 [invalidateUrlById]）。键的格式是 `集:上限:档位`，集 id 是纯数字，
        // 不含冒号，因此 `substringBefore(':')` 就是集 id。
        //
        // 早先只有 `url-<sha1>.bin`：哈希之后无法按集匹配，于是「队列里那一集坏了」
        // 只能按 `集:上限:档位` 精确删——而上限 / 档位只要有一个对不上就删不到，
        // 重切时原样命中同一条坏地址，第二次必然还是 403。
        val id=key.substringBefore(':')
        File(it,"url-$id-${sha1(key)}.bin")
    }

    /**
     * 这一集的**所有档位**地址都不认了。
     *
     * 用在「队列里那一集失败」这一条路上：那时我们只知道是哪一集，不知道它当初是按
     * 哪个上限预取的（预取用的上限可能已经被降档改过），精确键算不出来。按集整体作废
     * 才是安全的——多删几条地址的代价只是下一次多一次网络往返。
     */
    fun invalidateUrlById(id: String) {
        if(id.isEmpty()) return
        val dir=dir() ?: return
        runCatching {
            dir.listFiles()?.forEach { file ->
                // 加尾随 `-` 是为了不误伤前缀相同的另一集（`url-123-` 不会匹配 `url-1234-`）。
                if(file.name.startsWith("url-$id-")) file.delete()
            }
        }
    }
    private fun headFile(key: String): File? = dir()?.let { File(it,"head-${sha1(key)}.bin") }

    // ── 视频索引 ────────────────────────────────────────────────────────────────

    /** 读一集的索引；没有 / 坏了 / 过期都返回 null（坏了会顺手删掉）。 */
    fun loadIndex(key: String?): Index? {
        if(key.isNullOrEmpty()) return null
        val file=indexFile(key) ?: return null
        if(!file.isFile) return null
        if(System.currentTimeMillis()-file.lastModified()>INDEX_TTL_MS) { file.delete(); return null }
        val began=System.nanoTime()
        val loaded=try { readIndex(file) } catch(_: Exception) { null }
        if(loaded==null) { file.delete(); return null }
        if(NetLog.timing) NetLog.log("video index cache hit bytes=${file.length()} samples=${loaded.prepared.samples.size} took=${(System.nanoTime()-began)/1_000_000}ms")
        return loaded
    }

    /** 写一集的索引（原子替换：先写 `.tmp` 再 rename，进程中途被杀不会留下半份）。 */
    fun saveIndex(key: String?,total: Long,prepared: MediaCrypto.Prepared) {
        if(key.isNullOrEmpty() || total<=0) return
        // 没有加密样本（明文片源）时索引里没有可缓存的东西：`MediaCrypto.prepare` 那条路
        // 本来就能在几毫秒内走完，落一份空文件只会让下次读盘命中一个更没用的结果。
        if(prepared.samples.isEmpty()) return
        val file=indexFile(key) ?: return
        val began=System.nanoTime()
        val ok=runCatching {
            val tmp=File(file.path+".tmp")
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp),64*1024)).use { output ->
                output.writeInt(MAGIC); output.writeInt(INDEX_VERSION); output.writeLong(total)
                output.writeInt(prepared.header.size); output.write(prepared.header)
                val samples=prepared.samples
                output.writeInt(samples.size)
                var previous=0L
                for(sample in samples) {
                    output.writeLong(sample.start-previous); previous=sample.start
                    output.writeLong(sample.end-sample.start)
                    output.writeLong(sample.encryptedOffset)
                    // iv 是 16 字节、后 8 字节恒为 0（见 `MediaCrypto.prepare`）：只落前 8 字节。
                    output.write(sample.iv,0,8)
                }
            }
            file.delete()
            tmp.renameTo(file)
        }.isSuccess
        if(ok && NetLog.timing) NetLog.log("video index cache save bytes=${file.length()} samples=${prepared.samples.size} took=${(System.nanoTime()-began)/1_000_000}ms")
        scheduleTrim()
    }

    private fun readIndex(file: File): Index? {
        if(file.length()>MAX_FILE_BYTES) return null
        DataInputStream(BufferedInputStream(file.inputStream(),64*1024)).use { input ->
            if(input.readInt()!=MAGIC || input.readInt()!=INDEX_VERSION) return null
            val total=input.readLong()
            val headerLength=input.readInt()
            if(total<=0 || headerLength<8 || headerLength>MAX_FILE_BYTES) return null
            val header=ByteArray(headerLength)
            input.readFully(header)
            val count=input.readInt()
            if(count !in 0..200000) return null
            val samples=ArrayList<MediaCrypto.Sample>(count)
            val iv=ByteArray(16)
            var start=0L
            for(i in 0 until count) {
                start+=input.readLong()
                val length=input.readLong()
                val encrypted=input.readLong()
                java.util.Arrays.fill(iv,0.toByte())
                input.readFully(iv,0,8)
                if(start<0 || length<=0) return null
                samples+=MediaCrypto.Sample(start,start+length,iv.copyOf(),encrypted)
            }
            if(samples.isNotEmpty() && samples.last().end>total) return null
            return Index(total,MediaCrypto.Prepared(header,samples))
        }
    }

    /**
     * 把索引**放到后台线程**写盘（起播路径用这一条）。
     *
     * 与 [saveIndex] 的区别只有一个：谁来执行那 139ms 的写。起播时调这一条，
     * 首帧不必等它；索引写进去之后同样会被 [loadIndex] 命中。
     *
     * 语义上仍是「尽力而为」：写失败只是下次要重新解析，不影响本次播放。
     */
    fun saveIndexLater(key: String?,total: Long,prepared: MediaCrypto.Prepared) {
        if(key.isNullOrEmpty() || total<=0 || prepared.samples.isEmpty()) return
        runCatching { indexWriter.execute { runCatching { saveIndex(key,total,prepared) } } }
    }

    /** 这一集的索引不认了（HTTP 403/404、`total` 变了、样本越界）。 */
    fun invalidateIndex(key: String?) {
        if(key.isNullOrEmpty()) return
        runCatching { indexFile(key)?.delete() }
        // 索引不认了 = 这一集的内容变了：首块字节也不再是同一个文件的前 512KB。
        runCatching { headFile(key)?.delete() }
    }

    // ── 首块（前 512KB 原始字节） ────────────────────────────────────────────────

    /**
     * 落盘的**首块**：`[0, 512KB)` 的原始（加密）字节。
     *
     * 文档里这一项写的是「内存，进程死清」。实测这里值得落盘：`主页 → 推荐` 或者
     * 退出再进同一集时，首块要重新走一次 Range（这台盒子上 512KB ≈ 600~800ms），
     * 而它就是首帧前唯一还在等的东西。512KB 一条、48MB 目录上限，够留近百集。
     *
     * 正确性由 `total`（文件字节数）保证：内容换过 → `range()` 报「视频资源已变化」→
     * [invalidateIndex] 把索引与首块一起删掉。
     */
    fun loadHead(key: String?,total: Long): ByteArray? {
        if(key.isNullOrEmpty() || total<=0) return null
        val file=headFile(key) ?: return null
        if(!file.isFile) return null
        val bytes=try { readHead(file,total) } catch(_: Exception) { null }
        if(bytes==null) { file.delete(); return null }
        if(NetLog.timing) NetLog.log("video head cache hit bytes=${bytes.size}")
        return bytes
    }

    fun saveHead(key: String?,total: Long,bytes: ByteArray) {
        if(key.isNullOrEmpty() || total<=0 || bytes.isEmpty()) return
        val file=headFile(key) ?: return
        runCatching {
            val tmp=File(file.path+".tmp")
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp),64*1024)).use { output ->
                output.writeInt(MAGIC); output.writeInt(HEAD_VERSION); output.writeLong(total)
                output.writeInt(bytes.size); output.write(bytes)
            }
            file.delete()
            tmp.renameTo(file)
        }
        scheduleTrim()
    }

    private fun readHead(file: File,total: Long): ByteArray? {
        if(file.length()>MAX_HEAD_BYTES+64) return null
        DataInputStream(BufferedInputStream(file.inputStream(),64*1024)).use { input ->
            if(input.readInt()!=MAGIC || input.readInt()!=HEAD_VERSION) return null
            if(input.readLong()!=total) return null
            val length=input.readInt()
            if(length !in 1..MAX_HEAD_BYTES) return null
            val bytes=ByteArray(length)
            input.readFully(bytes)
            return bytes
        }
    }

    // ── 播放地址 ────────────────────────────────────────────────────────────────

    fun loadUrl(key: String): Url? {
        val file=urlFile(key) ?: return null
        if(!file.isFile) return null
        val loaded=try { readUrl(file) } catch(_: Exception) { null }
        if(loaded==null) { file.delete(); return null }
        if(System.currentTimeMillis()-loaded.at>URL_FRESH_MS) return null
        return loaded
    }

    fun saveUrl(key: String,info: StreamInfo) {
        val file=urlFile(key) ?: return
        runCatching {
            val tmp=File(file.path+".tmp")
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp),8*1024)).use { output ->
                output.writeInt(MAGIC); output.writeInt(URL_VERSION)
                output.writeLong(System.currentTimeMillis())
                writeText(output,info.url); writeText(output,info.quality)
                val key0=info.key
                output.writeInt(key0?.size ?: -1)
                if(key0!=null) output.write(key0)
                output.writeInt(info.requestedQuality)
                output.writeInt(info.actualQuality)
                val options=info.available
                output.writeInt(options.size)
                for(option in options) output.writeInt(option)
            }
            file.delete()
            tmp.renameTo(file)
        }
        scheduleTrim()
    }

    fun invalidateUrl(key: String) {
        runCatching { urlFile(key)?.delete() }
    }

    /**
     * 调试打点用：这一集现在能命中哪些缓存。
     *
     * 只读文件头，不解码内容（[loadIndex] 会真的反序列化两万条样本，那是几十毫秒，
     * 不该为了打一行日志付这笔钱）。
     */
    fun probe(urlKey: String,mediaKey: String): String {
        val url=urlFile(urlKey)?.isFile==true
        val index=indexFile(mediaKey)?.isFile==true
        val head=headFile(mediaKey)?.isFile==true
        return "address=${if(url) 1 else 0} meta=${if(index) 1 else 0} head=${if(head) 1 else 0}"
    }

    /** 这一集的索引与首块是不是都在盘上（= 起播不需要任何视频网络请求）。 */
    fun hasMedia(key: String): Boolean =
        indexFile(key)?.isFile==true && headFile(key)?.isFile==true

    /**
     * 这一集的索引与首块是不是都在盘上——**任意档位**都算。
     *
     * 只用于「粗判」这一个场景：推荐页决定要不要为这部再预热一次（见 `mediaCached`）。
     * 那时还不知道真正会选中哪一档（那要取一次地址），而键是「集 + 实际档位」
     * （见 [StreamInfo.mediaKey]），拼不出精确值。拼一个错的键去查，结果恒为 false——
     * 表现是「盘上明明有、仍然每次重新预热一遍」，白占带宽。
     *
     * 文件名是键的 sha1（见 [indexFile]），没法按前缀扫，因此这里**枚举**可能的档位：
     * 平台用过的那些 + 0（认不出档位时 [StreamInfo.mediaKey] 用请求上限）。
     * 十几次 sha1 + 存在性检查，微秒级；漏判只会多预热一次，不影响正确性。
     */
    fun hasAnyMedia(id: String): Boolean {
        for(quality in KNOWN_QUALITIES) {
            if(hasMedia("$id:$quality")) return true
        }
        return false
    }
    /** 平台下发过的档位（见 `PlaybackQuality.options` 与现场抓包），外加 0。 */
    private val KNOWN_QUALITIES=intArrayOf(0,360,480,540,576,720,896,1080,1440,2160)

    private fun writeText(output: DataOutputStream,value: String) {
        val bytes=value.toByteArray(Charsets.UTF_8)
        output.writeInt(bytes.size); output.write(bytes)
    }

    private fun readText(input: DataInputStream): String {
        val length=input.readInt()
        require(length in 0..(1 shl 20)) { "缓存字段异常" }
        val bytes=ByteArray(length); input.readFully(bytes)
        return String(bytes,Charsets.UTF_8)
    }

    private fun readUrl(file: File): Url? {
        if(file.length()>1 shl 20) return null
        DataInputStream(BufferedInputStream(file.inputStream(),8*1024)).use { input ->
            if(input.readInt()!=MAGIC || input.readInt()!=URL_VERSION) return null
            val at=input.readLong()
            val url=readText(input); val quality=readText(input)
            val keyLength=input.readInt()
            val key=if(keyLength<0) null else ByteArray(keyLength).also { input.readFully(it) }
            if(url.isEmpty()) return null
            val requestedQuality=input.readInt()
            val actualQuality=input.readInt()
            val count=input.readInt()
            if(count !in 0..16) return null
            val available=ArrayList<Int>(count)
            repeat(count) {
                val value=input.readInt()
                if(value>0) available+=value
            }
            return Url(
                StreamInfo(url,key,quality,requestedQuality,actualQuality,available),
                at
            )
        }
    }

    // ── 目录维护 ────────────────────────────────────────────────────────────────

    /** 距上一次清理超过一分钟才真的扫一遍目录（`saveIndex` 每条都会调到这里）。 */
    private fun scheduleTrim() {
        val now=System.currentTimeMillis()
        if(now-lastTrim<60_000L) return
        lastTrim=now
        Thread({ runCatching { trim() } },"hgtv-cache-trim").apply { isDaemon=true }.start()
    }

    fun trim() {
        val dir=dir() ?: return
        synchronized(lock) {
            val files=dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: return
            var total=0L
            for((index,file) in files.withIndex()) {
                total+=file.length()
                // 超上限：保留最新的那几条，其余（含 `.tmp` 残留）删掉。
                // 条数上限放宽到 80：首块也是 512KB 一条，40 条会让「来回刷十来部」就开始淘汰。
                if(total>MAX_BYTES || index>=80) runCatching { file.delete() }
            }
        }
    }

    /** 本机「清空数据」：把所有派生缓存一起丢掉。 */
    fun clear() {
        val dir=dir() ?: return
        synchronized(lock) { dir.listFiles()?.forEach { runCatching { it.delete() } } }
    }
}
