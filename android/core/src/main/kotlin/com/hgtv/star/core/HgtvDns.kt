// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import okhttp3.Dns
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * 带缓存的 DNS，替换 OkHttp 默认的「每次新建连接都问一遍系统」。
 *
 * ## 为什么值得专门做一个
 *
 * 2026-10-27 在 S905L3B（2G，Android 9）上抓栈，冷启动那 5 秒里
 * `pool-5-thread-1` 反复停在
 *
 * ```
 * libcore.io.Linux.android_getaddrinfo  ← 阻塞十几~几百毫秒
 * InetAddress.getAllByName → okhttp3.Dns$1.lookup → RouteSelector.next
 * ```
 *
 * 这台盒子的 `getaddrinfo` 走的是 DHCP 下发的路由器 DNS，单次 60~450ms 不等。
 * 而冷启动一次要碰好几个域名（内容接口 6 个、图片 CDN 2 个、视频 CDN 由接口临时下发），
 * 每个域名在**建立第一条连接**时都要问一遍；连接池里的连接被回收之后还要再问。
 *
 * 系统自己的 DNS 缓存（`net.dns1` + `libcore` 的 TTL 缓存）在这台 ROM 上近乎不存在——
 * 同一域名连续三次 `InetAddress.getByName` 实测仍各花 80~300ms。
 *
 * ## 取舍
 *
 * * 只缓存**成功**结果，失败照常抛 `UnknownHostException`（调用方的重试逻辑不变）；
 * * TTL 10 分钟：短视频 CDN 的解析在会话内不会变，而连接本身由 OkHttp 的连接池管理，
 *   这里缓存的是「域名→IP」，不改变任何重连行为；
 * * 上限 32 个域名，满了直接清空（这是冷启动优化，不是通用 DNS 服务器）；
 * * **不缓存失败**：网络切换（Wi-Fi 断开 / 代理生效）之后必须能立刻重新解析。
 *
 * ## 为什么还要落盘
 *
 * 上面那份缓存只活在进程里。盒子上一集播完就被系统回收、或者用户退出再进，
 * 冷启动那 1.6 秒的 `vas-lf-x.snssdk.com` 又要重付一次（实测日志：
 * `net GET vas-lf-x.snssdk.com dns=1597ms`）。落盘之后第二次启动直接命中，
 * `dns=-1ms`（走缓存）或者几十毫秒。
 *
 * 两档寿命：
 * * [DISK_TTL_MS]（30 分钟）内直接当有效用；
 * * 过期但仍能靠系统解析出来 → 照常更新；
 * * **系统解析失败**（路由器 DNS 不响应、弱网）→ 退回 [FALLBACK_TTL_MS]（12 小时）
 *   内的那一份，宁可拿一个可能已经过期的 IP 去试，也好过整条链路直接
 *   `UnknownHostException`（连接失败上层还能重试，解析失败在界面上的表现就是「什么都放不了」）。
 */
object HgtvDns : Dns {
    private const val TTL_MS = 10*60*1000L
    private const val DISK_TTL_MS = 30*60*1000L
    private const val FALLBACK_TTL_MS = 12*60*60*1000L
    private const val MAX_HOSTS = 32

    private class Entry(val addresses: List<InetAddress>,val at: Long)

    private val cache=ConcurrentHashMap<String,Entry>()
    /** 盘上那一份（启动后第一条 `lookup` 之前由后台线程读进来）。 */
    private val disk=ConcurrentHashMap<String,Entry>()
    @Volatile private var store: java.io.File?=null
    /**
     * 盘上那份读完的信号。
     *
     * 为什么需要等：Application 一起就是「读盘」与「预热 12 个域名」两条线程，
     * 读盘可能要几十毫秒（文件小，但对 2G 盒子是一次真实 IO）。预热先跑到 `lookup`
     * 时盘上还是空的，于是它照样去问系统 DNS——那 1.6 秒就又付了一次，落盘等于白做。
     * 这里只等一次，**上限 300ms**：读盘异常 / 文件不存在时立刻放行，绝不把请求卡住。
     */
    private val diskReady=java.util.concurrent.CountDownLatch(1)

    /**
     * 落盘目录由 Application 传进来（`core` 不认识 Android）。
     *
     * 传 null 也安全：所有磁盘相关逻辑直接短路，退化成纯内存缓存。
     */
    fun init(file: java.io.File) {
        store=file
        try { writer.execute { runCatching { loadDisk() } } } catch(_: Exception) { }
    }

    private val writer by lazy { java.util.concurrent.Executors.newSingleThreadExecutor { runnable -> Thread(runnable,"hgtv-dns-store").apply { isDaemon=true } } }

    private fun loadDisk() {
        try {
            val file=store ?: return
            if(!file.isFile) return
            val now=System.currentTimeMillis()
            runCatching {
                file.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        // 每行：`host <TAB> 时间戳 <TAB> ip,ip,...`
                        val parts=line.split('\t')
                        if(parts.size!=3) return@forEach
                        val at=parts[1].toLongOrNull() ?: return@forEach
                        if(now-at>FALLBACK_TTL_MS) return@forEach
                        val addresses=resolveLiterals(parts[2])
                        if(addresses.isNotEmpty()) disk[parts[0]]=Entry(addresses,at)
                    }
                }
            }
            if(NetLog.timing) NetLog.log("dns disk entries=${disk.size}")
        } finally {
            diskReady.countDown()
        }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        awaitDisk()
        val now=System.currentTimeMillis()
        cache[hostname]?.let { if(now-it.at<TTL_MS) return it.addresses }
        disk[hostname]?.let { if(now-it.at<DISK_TTL_MS) { cache[hostname]=it; return it.addresses } }
        val addresses=try {
            Dns.SYSTEM.lookup(hostname)
        } catch(e: java.net.UnknownHostException) {
            // 系统解析不出来：退回盘上那份（见类注释）。
            //
            // **视频 CDN 域名不走这条**（见 [isVideoHost]）：12 小时的旧 IP 对内容接口
            // 是「能少一次 UnknownHost」，对视频是「拿一个已经下线的边缘节点去试」——
            // 表现就是建连卡满 `connectTimeout` 之后报「连接视频服务器超时」。
            // 宁可当场抛 / 走公共 DNS 拿一份新的，也不要拿旧 IP 硬试。
            if(!isVideoHost(hostname)) {
                disk[hostname]?.takeIf { now-it.at<FALLBACK_TTL_MS }?.let {
                    cache[hostname]=it
                    return it.addresses
                }
            }
            // 盘上也没有：最后再问一次公共 DNS（见 [PublicDns]）。
            //
            // 现场：TCL 电视上 `v5-se-…qznovelvod.com` 系统解析恒为 EAI_NODATA，
            // 而同一时刻从这台电视 ping 公共 DNS 解析出来的 IP 是通的、接口域名也都正常——
            // 说明是这个域名在这台设备的解析链路上被答成了「不存在」，不是资源真的没了。
            // 不兜这一下，那一集就永远停在「播放失败」上，重试多少次都一样。
            val public=PublicDns.lookup(hostname)
            if(public.isNotEmpty()) {
                val entry=Entry(public,now)
                if(cache.size>=MAX_HOSTS) cache.clear()
                cache[hostname]=entry
                disk[hostname]=entry
                persist()
                return public
            }
            // 公共 DNS 也没答上来：把「系统与兜底都失败」这件事记一行。
            // 只在失败路径上写，正常请求一行都不多。
            NetLog.log("dns fallback failed host=$hostname")
            throw e
        }
        if(addresses.isNotEmpty()) {
            if(cache.size>=MAX_HOSTS) cache.clear()
            val entry=Entry(addresses,now)
            cache[hostname]=entry
            disk[hostname]=entry
            persist()
        }
        return addresses
    }

    /**
     * 忘掉一个域名的解析结果（内存与盘上一并清空）。
     *
     * 由播放层的自愈路径调用（见 `MainActivity.playerFailure` → `dropDeadTransport`）：
     * 视频建连超时 / 被拒之后，那个 IP 已经不能再用了，而它正好在
     * [TTL_MS]（10 分钟）与 [DISK_TTL_MS]（30 分钟）里各有一份——不清掉的话，
     * 重取地址、重试、杀进程重进都还是同一个死 IP，用户看到的就是「这一集永远放不了」。
     *
     * 只清这一个域名：其余域名的解析结果仍然有效，清全表等于把冷启动那 1.6 秒重新付一遍。
     */
    fun invalidate(hostname: String) {
        if(hostname.isEmpty()) return
        cache.remove(hostname)
        if(disk.remove(hostname)!=null) persist()
    }

    /**
     * 视频 CDN 域名（播放地址里下发的那些）。
     *
     * 只用来决定「能不能拿盘上那份 12 小时前的旧 IP 兜底」，所以判据宁可宽一点：
     * 现场见过的形态是 `v5-se-<频道>-reading-video-a.qznovelvod.com`
     * 与 `vas-lf-x.snssdk.com`（视频分发）。
     */
    private fun isVideoHost(hostname: String): Boolean {
        val lower=hostname.lowercase()
        return lower.contains("video") || lower.endsWith(".qznovelvod.com") || lower.startsWith("vas-")
    }

    /** 纯字面量 IP → `InetAddress`，不走系统解析（`getByName` 对字面量不做 DNS 查询）。 */
    private fun resolveLiterals(value: String): List<InetAddress> =
        value.split(',').mapNotNull { text ->
            val trimmed=text.trim()
            if(trimmed.isEmpty()) null else runCatching { InetAddress.getByName(trimmed) }.getOrNull()
        }

    private fun persist() {
        val file=store ?: return
        val now=System.currentTimeMillis()
        val snapshot=buildString {
            for((host,entry) in disk) {
                if(now-entry.at>FALLBACK_TTL_MS) continue
                append(host).append('\t').append(entry.at).append('\t')
                append(entry.addresses.joinToString(",") { it.hostAddress ?: "" })
                append('\n')
            }
        }
        runCatching { writer.execute { runCatching { file.writeText(snapshot) } } }
    }

    /**
     * 后台预解析。
     *
     * 只做「把名字换成 IP」，不发任何应用层请求，因此不需要凭据、也不会污染统计。
     * 失败静默：真正的请求打到这个域名时自己会再解析一次（那时每域名只多一次
     * `getaddrinfo`，与本改动之前一样）。
     */
    fun warm(hosts: Collection<String>) {
        awaitDisk()
        for(host in hosts) {
            if(host.isEmpty()) continue
            val now=System.currentTimeMillis()
            val memory=cache[host]
            if(memory!=null && now-memory.at<TTL_MS) continue
            val stored=disk[host]
            if(stored!=null && now-stored.at<DISK_TTL_MS) { cache[host]=stored; continue }
            runCatching { lookup(host) }
        }
    }

    private fun awaitDisk() {
        if(store==null) return
        runCatching { diskReady.await(300,java.util.concurrent.TimeUnit.MILLISECONDS) }
    }

    /** 冷启动会碰到的域名（内容接口 / 图片 / 视频 Referer / 日志与安全接口）。 */
    val BOOT_HOSTS: List<String> = listOf(
        // **播放地址里下发的那个 CDN 域名放第一位**。它在接口返回之前没人知道，但实测跨剧集
        // 基本就是这一个（`vas-lf-x` = 视频分发），而且它不预热要 **1.6 秒**：
        // `net GET vas-lf-x.snssdk.com dns=1597ms`，直接顶在 `Stage stream` 上。
        //
        // 排在最后时（早先的写法）预热线程按顺序逐个解析，前面的十个域名各花
        // 60~450ms，等轮到它时用户已经点到播放了——等于没预热。放第一位就一定能先解析完。
        "vas-lf-x.snssdk.com",
        "api5-normal-sinfonlinea.fqnovel.com",
        "api5-normal-sinfonlineb.fqnovel.com",
        "api5-normal-sinfonlinec.fqnovel.com",
        "api5-normal-lf.fqnovel.com",
        "api5-normal-lq.fqnovel.com",
        "api5-normal-hl.fqnovel.com",
        "p3-reading-sign.fqnovelpic.com",
        "p6-novel.byteimg.com",
        "novel.snssdk.com",
        "i.snssdk.com",
        "log5-applog-lf.fqnovel.com",
        "hongguoduanju.com",
    )
}
