// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.hgtv.star.core.LibraryTransferServer
import com.hgtv.star.core.AppConfig
import com.hgtv.star.shared.compat.activeNetworkOrNull
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URI
import java.net.URL

/**
 * 通过 Android NSD 发现同一局域网里打开了「同步其他设备数据」的 HGTV。
 *
 * NSD 只做发现与地址解析；真正传输复用 [LibraryTransferServer] 的只读下载。
 * 服务名末尾带本次随机 token，旧设备即使不支持 TXT 记录也能从服务名还原下载路径。
 */
class LanLibrarySyncSession(
    context: Context,
    address: String,
    payload: String,
    private val onPeers: (List<LanSyncPeer>) -> Unit,
    private val onError: (String) -> Unit,
    private val discoverPeers: Boolean = true,
    private val onServing: () -> Unit = {},
    private val onDownloaded: () -> Unit = {},
    private val ttlMillis: Long = AppConfig.Lan.DEFAULT_TTL_MS,
) : Closeable {
    companion object {
        private const val SERVICE_TYPE = "_hgtv._tcp."
        private const val PROTOCOL = NsdManager.PROTOCOL_DNS_SD
        private val TOKEN = Regex("([0-9a-f]{32})$")

        fun fetchPayload(peer: LanSyncPeer): DeviceSyncPayload {
            val path = peer.path.takeIf {
                it.startsWith("/") && it.length <= 160 && it.none(Char::isISOControl)
            } ?: error("设备没有提供同步路径")
            val rawHost = peer.host.substringBefore('%')
            val host = if (rawHost.contains(':') && !rawHost.startsWith("[")) "[$rawHost]" else rawHost
            val connection = (URL("http://$host:${peer.port}$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = AppConfig.Network.LAN_TIMEOUT_MS.toInt()
                readTimeout = AppConfig.Network.LAN_TIMEOUT_MS.toInt()
                useCaches = false
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    error("其他设备返回 ${connection.responseCode}")
                }
                val declared = connection.contentLength
                require(declared <= 0 || declared <= DeviceSyncPackage.MAX_BYTES) { "同步数据超过 4 MB" }
                val input = connection.inputStream.buffered()
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    require(output.size() <= DeviceSyncPackage.MAX_BYTES) { "同步数据超过 4 MB" }
                }
                return DeviceSyncPackage.decode(output.toString("UTF-8"))
            } finally {
                connection.disconnect()
            }
        }
    }

    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
        ?: error("本机不支持 NSD")
    private val payload = payload
    private val address = address
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val peers = linkedMapOf<String, LanSyncPeer>()
    private val resolving = hashSetOf<String>()

    @Volatile private var closed = false
    @Volatile private var registered = false
    @Volatile private var discovering = false
    private var server: LibraryTransferServer? = null
    private var ownName = ""

    val isClosed: Boolean get() = closed

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {
            registered = true
            ownName = info.serviceName
            if (closed) {
                runCatching { nsd.unregisterService(this) }
                return
            }
            if (discoverPeers) {
                runCatching {
                    discovering = true
                    nsd.discoverServices(SERVICE_TYPE, PROTOCOL, discoveryListener)
                }.onFailure {
                    reportError("无法开始局域网发现")
                    close()
                }
            }
            main.post { if (!closed) onServing() }
        }

        override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            registered = false
            reportError("局域网同步服务注册失败（$errorCode）")
            close()
        }

        override fun onServiceUnregistered(info: NsdServiceInfo) {
            registered = false
        }

        override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            registered = false
        }
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            discovering = true
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            discovering = false
            reportError("局域网设备发现启动失败（$errorCode）")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            discovering = false
        }

        override fun onDiscoveryStopped(serviceType: String) {
            discovering = false
        }

        override fun onServiceFound(info: NsdServiceInfo) {
            if (closed ||
                !info.serviceType.trimEnd('.').equals(SERVICE_TYPE.trimEnd('.'), ignoreCase = true) ||
                info.serviceName == ownName) return
            synchronized(lock) {
                if (!resolving.add(info.serviceName)) return
            }
            runCatching {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        synchronized(lock) { resolving.remove(serviceInfo.serviceName) }
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        synchronized(lock) { resolving.remove(serviceInfo.serviceName) }
                        val host = serviceInfo.host?.hostAddress.orEmpty()
                        val path = pathOf(serviceInfo)
                        if (closed || host.isBlank() || serviceInfo.port <= 0 || path.isBlank()) return
                        val peer = LanSyncPeer(
                            serviceName = serviceInfo.serviceName,
                            displayName = serviceInfo.serviceName.replace(Regex("-[0-9a-f]{32}$"), ""),
                            host = host,
                            port = serviceInfo.port,
                            path = path,
                        )
                        synchronized(lock) { peers[peer.serviceName] = peer }
                        publish()
                    }
                })
            }.onFailure {
                synchronized(lock) { resolving.remove(info.serviceName) }
            }
        }

        override fun onServiceLost(info: NsdServiceInfo) {
            synchronized(lock) {
                peers.remove(info.serviceName)
                resolving.remove(info.serviceName)
            }
            publish()
        }
    }

    fun start() {
        if (closed) return
        val transfer = LibraryTransferServer(
            address = address,
            backup = payload,
            onImport = {},
            allowImport = false,
            maxBytes = DeviceSyncPackage.MAX_BYTES,
            singleDownload = false,
            ttlMillis = ttlMillis,
            onDownloaded = {
                main.post { if (!closed) onDownloaded() }
            },
        )
        server = transfer
        val uri = URI(transfer.url)
        val token = uri.path.trim('/').substringAfterLast('/')
        require(token.matches(Regex("[0-9a-f]{32}"))) { "同步 token 无效" }
        val model = Build.MODEL
            .replace(Regex("[^A-Za-z0-9._ -]"), " ")
            .trim()
            .take(16)
            .ifBlank { "Device" }
        val info = NsdServiceInfo().apply {
            serviceName = "HGTV-$model-$token"
            serviceType = SERVICE_TYPE
            port = uri.port
        }
        runCatching {
            nsd.registerService(info, PROTOCOL, registrationListener)
        }.onFailure {
            reportError("无法注册局域网同步服务")
            close()
        }
    }

    fun stopDiscovery() {
        if (!discovering) return
        discovering = false
        runCatching { nsd.stopServiceDiscovery(discoveryListener) }
    }

    private fun pathOf(info: NsdServiceInfo): String {
        val token = TOKEN.find(info.serviceName)?.groupValues?.getOrNull(1) ?: return ""
        return "/$token/download"
    }

    private fun publish() {
        val snapshot = synchronized(lock) { peers.values.sortedBy { it.displayName } }
        main.post { if (!closed) onPeers(snapshot) }
    }

    private fun reportError(message: String) {
        main.post { onError(message) }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        stopDiscovery()
        if (registered) runCatching { nsd.unregisterService(registrationListener) }
        registered = false
        server?.close()
        server = null
        synchronized(lock) {
            peers.clear()
            resolving.clear()
        }
    }
}

data class LanSyncPeer(
    val serviceName: String,
    val displayName: String,
    val host: String,
    val port: Int,
    val path: String,
)

/** 当前 Wi-Fi / 有线网络上的站点内 IPv4；NSD 同步服务用它绑定局域网地址。 */
fun Context.lanIpv4Address(): String? {
    val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    if (Build.VERSION.SDK_INT >= 23 && connectivity != null) {
        val network = connectivity.activeNetworkOrNull() ?: return null
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
        if (!(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return null
        connectivity.getLinkProperties(network)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
            ?.let { return it }
    }
    return runCatching {
        java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}
