// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import java.io.File
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** TNC 响应：正文以及下一轮条件请求要带的摘要 / ETag。 */
data class TncResponse(
    val raw: String,
    val summary: String = "",
    val etag: String = "",
)

/** TNC 配置的运行时快照。所有集合都是不可变副本，刷新时整份替换。 */
data class TncSnapshot(
    val fetchedAt: Long,
    val summary: String,
    val etag: String,
    val updateIntervalMs: Long,
    val tncHosts: List<String>,
    val localHosts: List<String>,
    val apiHosts: List<String>,
    val preconnectHosts: List<String>,
    val httpDns: Map<String,List<String>>,
    val lastGoodHost: String = "",
)

/**
 * 字节 TTNet 的 TNC 配置。
 *
 * 这里只实现本工程需要的子集：TNC 入口、接口候选域名、HTTP DNS IP 表、更新周期和最近成功
 * 域名。QUIC / H2 / 多网络切换等 TTNet 内部策略不在这里复制。
 *
 * 启动时只读盘，不阻塞首屏；真正请求 TNC 由 [refreshAsync] 在后台单飞执行。TNC 本身失败时
 * 继续使用上一份快照，最后才回退到内置域名。
 */
object TncConfigStore {
    private const val SCHEMA_VERSION=1
    private const val DEFAULT_UPDATE_INTERVAL_MS=60*60*1000L
    private const val MIN_UPDATE_INTERVAL_MS=5*60*1000L
    private const val MAX_UPDATE_INTERVAL_MS=24*60*60*1000L
    private const val LOAD_WAIT_MS=300L
    private const val MAX_BACKOFF_MS=30*60*1000L

    /** 首次启动 / 配置丢失时仅用于找到 TNC；正常刷新会由响应里的 send_tnc_host_arrays 覆盖。 */
    val bootstrapHosts=listOf(
        "tnc0-alisc1.zijieapi.com",
        "tnc0-aliec2.zijieapi.com",
    )

    /**
     * TNC 不可用时的最后兜底。
     *
     * 它不是日常配置来源；只有首次启动、TNC 连续失败或缓存损坏时才会被使用。
     */
    private val fallbackApiHosts=listOf(
        "api5-normal-sinfonlinea.fqnovel.com",
        "api5-normal-sinfonlineb.fqnovel.com",
        "api5-normal-sinfonlinec.fqnovel.com",
        "api5-normal-lf.fqnovel.com",
        "api5-normal-lq.fqnovel.com",
        "api5-normal-hl.fqnovel.com",
    )

    /**
     * 设备注册的静态 bootstrap。
     *
     * TNC 还没成功时没有 `store-region`，不能请求 TNC；因此首次注册必须在这些官方等价
     * 入口之间轮换。TNC 已下发 log 主机时，也把这些入口接在后面，避免 TNC 配置里的
     * 单条线路全部失败后没有兜底。
     */
    private val fallbackLogHosts=listOf(
        "log5-applog-lf.fqnovel.com",
        "log5-applog-lq.fqnovel.com",
        "log5-applog-hl.fqnovel.com",
        "is.snssdk.com",
    )

    @Volatile private var snapshot: TncSnapshot?=null
    @Volatile private var store: File?=null
    @Volatile private var loaded=false
    @Volatile private var refreshing=false
    @Volatile private var pendingForce=false
    @Volatile private var failureCount=0
    @Volatile private var backoffUntil=0L

    private val loadedLatch=CountDownLatch(1)
    private val readyLatch=CountDownLatch(1)
    private val refreshLock=Any()
    private val worker=Executors.newSingleThreadExecutor { task ->
        Thread(task,"hgtv-tnc").apply { isDaemon=true }
    }
    private val writer=Executors.newSingleThreadExecutor { task ->
        Thread(task,"hgtv-tnc-store").apply { isDaemon=true }
    }

    /** 由 Application 在启动缓存阶段调用；只排后台读盘，不请求网络。 */
    fun init(file: File) {
        if(store!=null) return
        store=file
        worker.execute { load() }
    }

    /** 当前配置；磁盘还没读完时最多等 300ms，和 HgtvDns 的读盘策略一致。 */
    fun current(): TncSnapshot? {
        awaitLoaded()
        return snapshot
    }

    /**
     * 等第一份 TNC 配置可用。
     *
     * 只应由后台初始化 / 设备注册线程调用；超时返回 false，调用方继续走静态 bootstrap。
     */
    fun awaitReady(timeoutMs: Long): Boolean {
        awaitLoaded()
        if(snapshot!=null) return true
        if(store==null) return false
        return runCatching {
            readyLatch.await(timeoutMs.coerceAtLeast(0L),TimeUnit.MILLISECONDS)
        }.getOrDefault(false) && snapshot!=null
    }

    /** 动态接口域名；没有 TNC 配置时返回最后兜底表。 */
    fun apiHosts(): List<String> {
        val configured=current()?.apiHosts.orEmpty()
        return configured.takeIf { it.isNotEmpty() } ?: fallbackApiHosts
    }

    /** 最近成功域名排第一，其余保持 TNC 下发顺序。 */
    fun orderedApiHosts(): List<String> {
        val hosts=apiHosts()
        val last=current()?.lastGoodHost.orEmpty()
        if(last.isBlank() || last !in hosts) return hosts
        return listOf(last)+hosts.filter { it!=last }
    }

    /**
     * 给账号 / 播放这类固定角色选一个首选域名。
     *
     * 先尊重最近成功域名；没有时按 [roleHint] 找同族域名，最后取 TNC 第一项。
     */
    fun preferredHost(roleHint: String? = null,preferLastGood: Boolean=true): String {
        val hosts=orderedApiHosts()
        val last=current()?.lastGoodHost.orEmpty()
        if(preferLastGood && last.isNotBlank() && last in hosts) return last
        if(!roleHint.isNullOrBlank()) hosts.firstOrNull { it.contains(roleHint,ignoreCase=true) }?.let { return it }
        return hosts.first()
    }

    /** 从 TNC 的 local_host_filter 中选非 API 业务入口（例如 `i.snssdk.com`）。 */
    fun preferredLocalHost(roleHint: String? = null): String {
        val hosts=current()?.localHosts.orEmpty()
        if(!roleHint.isNullOrBlank()) {
            hosts.firstOrNull { it.equals(roleHint,ignoreCase=true) }?.let { return it }
            hosts.firstOrNull { it.contains(roleHint,ignoreCase=true) }?.let { return it }
        }
        return hosts.firstOrNull() ?: roleHint.orEmpty()
    }

    /** 当前 TNC 入口；响应里下发过新入口就优先用新入口。 */
    fun tncHosts(): List<String> =
        current()?.tncHosts?.takeIf { it.isNotEmpty() } ?: bootstrapHosts

    /** TNC 下发的日志/注册候选；首次注册仍使用静态 bootstrap。 */
    fun logHosts(): List<String> {
        val configured=current()?.localHosts.orEmpty()
            .filter { it.startsWith("log5-applog-",ignoreCase=true) }
        return (configured+fallbackLogHosts).distinct()
    }

    /** TNC 预连接 URL 里的主机；只用于 DNS 预热，不代表业务接口入口。 */
    fun preconnectHosts(): List<String> =
        current()?.preconnectHosts.orEmpty()

    /** 条件刷新使用的摘要。 */
    fun summary(): String = current()?.summary.orEmpty()

    /**
     * 读取 TNC 下发的 HTTP DNS 地址。
     *
     * @param allowStale true 时即使配置超过更新周期也返回；只应在系统 DNS 也失败后使用。
     */
    fun httpDnsAddresses(hostname: String,allowStale: Boolean=false): List<InetAddress>? {
        val current=current() ?: return null
        val now=System.currentTimeMillis()
        if(!allowStale && now-current.fetchedAt>current.updateIntervalMs) return null
        val values=current.httpDns[hostname] ?: return null
        return values.mapNotNull { value ->
            runCatching { InetAddress.getByName(value) }.getOrNull()
        }.takeIf { it.isNotEmpty() }
    }

    /**
     * 后台刷新。返回 true 表示本次已经排队；已有刷新在跑时只记一个强制刷新标记。
     *
     * [fetcher] 由 ContentRepository 提供，因为 TNC 请求要复用当前注册身份和签名器。
     */
    fun refreshAsync(
        force: Boolean=false,
        fetcher: (String?) -> TncResponse?,
    ): Boolean {
        val now=System.currentTimeMillis()
        synchronized(refreshLock) {
            if(refreshing) {
                if(force) pendingForce=true
                return false
            }
            if(!force && !refreshDue(now)) return false
            if(!force && now<backoffUntil) return false
            refreshing=true
        }
        worker.execute {
            var success=false
            try {
                val response=fetcher(current()?.summary)
                if(response!=null) success=applyResponse(response)
            } catch(_: Exception) {
                success=false
            } finally {
                val retryImmediately=synchronized(refreshLock) {
                    refreshing=false
                    if(success) {
                        failureCount=0
                        backoffUntil=0L
                    } else {
                        failureCount=(failureCount+1).coerceAtMost(8)
                        val delay=(60_000L shl (failureCount-1).coerceAtMost(5)).coerceAtMost(MAX_BACKOFF_MS)
                        backoffUntil=System.currentTimeMillis()+delay
                    }
                    val retry=pendingForce && success
                    pendingForce=false
                    retry
                }
                if(retryImmediately) refreshAsync(force=true,fetcher)
            }
        }
        return true
    }

    /** 播放 / 内容请求成功后记录线路，下一次优先复用。 */
    fun markHostSuccess(host: String) {
        val value=host.trim()
        if(value.isEmpty()) return
        val current=current() ?: return
        if(current.lastGoodHost==value) return
        snapshot=current.copy(lastGoodHost=value)
        persistAsync()
    }

    private fun refreshDue(now: Long): Boolean {
        val current=snapshot ?: return true
        return now-current.fetchedAt>=current.updateIntervalMs
    }

    private fun applyResponse(response: TncResponse): Boolean {
        val parsed=runCatching { parse(response) }.getOrNull() ?: return false
        val previous=snapshot
        snapshot=parsed.copy(lastGoodHost=previous?.lastGoodHost.orEmpty())
        readyLatch.countDown()
        persistAsync()
        NetLog.log(
            "tnc update apiHosts=${parsed.apiHosts.size} dnsHosts=${parsed.httpDns.size}"+
                " interval=${parsed.updateIntervalMs / 1000}s summary=${parsed.summary.take(12)}"
        )
        return true
    }

    private fun parse(response: TncResponse): TncSnapshot {
        val root=JSONObject(response.raw)
        val data=root.optJSONObject("data") ?: error("TNC data missing")
        val httpDns=parseHttpDns(data.optJSONObject("ttnet_http_dns_addr"))
        val localHosts=stringList(data.optJSONObject("tnc_config")?.opt("local_host_filter"))
        val apiHosts=(localHosts+httpDns.keys)
            .filter(::isApiHost)
            .distinct()
        require(apiHosts.isNotEmpty()) { "TNC api hosts missing" }
        val tncHosts=stringList(data.opt("send_tnc_host_arrays"))
            .filter { it.endsWith(".zijieapi.com",ignoreCase=true) }
            .distinct()
        val intervalSeconds=longValue(data.opt("tnc_update_interval"),3600L)
        val intervalMs=(intervalSeconds*1000L).coerceIn(MIN_UPDATE_INTERVAL_MS,MAX_UPDATE_INTERVAL_MS)
        val summary=response.summary.ifBlank { root.optString("summary") }
        return TncSnapshot(
            fetchedAt=System.currentTimeMillis(),
            summary=summary,
            etag=response.etag,
            updateIntervalMs=intervalMs,
            tncHosts=tncHosts,
            localHosts=localHosts,
            apiHosts=apiHosts,
            preconnectHosts=parsePreconnectHosts(data),
            httpDns=httpDns,
        )
    }

    private fun parsePreconnectHosts(data: JSONObject): List<String> {
        val urls=data.optJSONObject("ttnet_preconnect_urls") ?: return emptyList()
        return urls.keys().asSequence().mapNotNull { value ->
            runCatching { URI(value).host }.getOrNull()?.takeIf { it.isNotBlank() }
        }.distinct().toList()
    }

    private fun parseHttpDns(raw: JSONObject?): Map<String,List<String>> {
        if(raw==null) return emptyMap()
        val result=LinkedHashMap<String,List<String>>()
        for(host in raw.keys()) {
            val values=stringList(raw.opt(host))
                .flatMap { it.split(',') }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            if(values.isNotEmpty()) result[host]=values
        }
        return result
    }

    private fun stringList(value: Any?): List<String> = when(value) {
        null -> emptyList()
        is JSONArray -> (0 until value.length()).mapNotNull { index ->
            value.opt(index)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }
        is String -> listOf(value.trim()).filter { it.isNotEmpty() }
        else -> listOf(value.toString().trim()).filter { it.isNotEmpty() }
    }

    private fun longValue(value: Any?,fallback: Long): Long = when(value) {
        is Number -> value.toLong()
        else -> value?.toString()?.toLongOrNull() ?: fallback
    }

    private fun isApiHost(host: String): Boolean {
        val value=host.lowercase()
        return value.endsWith(".fqnovel.com") &&
            (value.startsWith("api5-normal-") || value.startsWith("api3-normal-") || value.startsWith("api5-sinfnormal"))
    }

    private fun load() {
        try {
            val file=store ?: return
            if(!file.isFile) return
            val root=JSONObject(file.readText())
            if(root.optInt("schema",0)!=SCHEMA_VERSION) return
            val httpDns=parseHttpDns(root.optJSONObject("httpDns"))
            val apiHosts=stringList(root.opt("apiHosts")).filter(::isApiHost)
            if(apiHosts.isEmpty()) return
            snapshot=TncSnapshot(
                fetchedAt=root.optLong("fetchedAt",0L),
                summary=root.optString("summary"),
                etag=root.optString("etag"),
                updateIntervalMs=root.optLong("updateIntervalMs",DEFAULT_UPDATE_INTERVAL_MS)
                    .coerceIn(MIN_UPDATE_INTERVAL_MS,MAX_UPDATE_INTERVAL_MS),
                tncHosts=stringList(root.opt("tncHosts")),
                localHosts=stringList(root.opt("localHosts")),
                apiHosts=apiHosts,
                preconnectHosts=stringList(root.opt("preconnectHosts")),
                httpDns=httpDns,
                lastGoodHost=root.optString("lastGoodHost"),
            )
            readyLatch.countDown()
        } catch(_: Exception) {
            snapshot=null
        } finally {
            loaded=true
            loadedLatch.countDown()
        }
    }

    private fun persistAsync() {
        val current=snapshot ?: return
        writer.execute {
            runCatching {
                val file=store ?: return@runCatching
                val root=JSONObject()
                    .put("schema",SCHEMA_VERSION)
                    .put("fetchedAt",current.fetchedAt)
                    .put("summary",current.summary)
                    .put("etag",current.etag)
                    .put("updateIntervalMs",current.updateIntervalMs)
                    .put("lastGoodHost",current.lastGoodHost)
                    .put("tncHosts",JSONArray(current.tncHosts))
                    .put("localHosts",JSONArray(current.localHosts))
                    .put("apiHosts",JSONArray(current.apiHosts))
                    .put("preconnectHosts",JSONArray(current.preconnectHosts))
                val dns=JSONObject()
                current.httpDns.forEach { (host,addresses) -> dns.put(host,JSONArray(addresses)) }
                root.put("httpDns",dns)
                file.parentFile?.mkdirs()
                val temp=File(file.parentFile,"${file.name}.tmp")
                temp.writeText(root.toString())
                if(!temp.renameTo(file)) {
                    file.writeText(temp.readText())
                    temp.delete()
                }
            }
        }
    }

    private fun awaitLoaded() {
        if(loaded) return
        if(store==null) return
        runCatching { loadedLatch.await(LOAD_WAIT_MS,TimeUnit.MILLISECONDS) }
    }
}
