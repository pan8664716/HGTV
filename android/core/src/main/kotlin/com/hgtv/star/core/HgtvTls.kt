// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import okhttp3.CipherSuite
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 全工程共用的 TLS 协商参数。
 *
 * ## 为什么需要它
 *
 * OkHttp 3.12 的默认值 `ConnectionSpec.MODERN_TLS` 是 **1.3 / 1.2 / 1.1 / 1.0 全开**，
 * 而本工程的服务端（`*.fqnovel.com` / `*.hongguoduanju.com` / `*.byteimg.com`）
 * 实测**只接受 TLS 1.2 / 1.3**：用 1.1 去连会被回 `protocol_version` alert 掐断，
 * 客户端看到的就是
 *
 * ```
 * javax.net.ssl.SSLProtocolException: SSL handshake aborted:
 *   ssl=0x70daaa28: Failure in SSL library, usually a protocol error
 * ```
 *
 * ## 为什么光加 ConnectionSpec 不够（关键）
 *
 * OkHttp 的 `ConnectionSpec.supportedSpec()` 求交集的对象是
 * `socket.getEnabledProtocols()`，**不是** `getSupportedProtocols()`。
 *
 * Android 4.4（API 19）的 Conscrypt **支持** TLS 1.2，但**默认不启用**
 * （`TLSv1.2` 要到 API 20 才进默认列表）。于是：
 *
 * - 不干预 → socket 只启用 `TLSv1` / `SSLv3` → 服务端拒绝握手（就是上面那条崩溃）；
 * - 只把 spec 改成 1.2/1.3 → 交集为空 → OkHttp 抛
 *   `UnknownServiceException: Unable to find acceptable protocols`，连 TCP 都不会发。
 *
 * 因此必须在 **socket 造出来的那一刻**先把 `TLSv1.2`（和可用的 `TLSv1.3`）打开，
 * 再交给 OkHttp。本文件的 [socketFactory] 就是干这件事的。
 *
 * ## 套件
 *
 * 在 `MODERN_TLS` 的 14 个套件之外补 4 个 CBC：API 19/20 的旧 Conscrypt 不一定带
 * `TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256`（GCM 套件 API 21+ 才普遍可用），
 * 而服务端实测也接受 `ECDHE-RSA-AES128-SHA` / `AES128-SHA` 这类 CBC 套件。
 * 不补的话，4.4 上即使强制 1.2 也可能因无套件可用而 abort。
 *
 * ## 证书链
 *
 * 服务端挂在 `DigiCert Global Root G2` 下，而这批老设备（4.4 / 5.0）的系统根库
 * 里没有这张根，会报 `Trust anchor for certification path not found`。
 * 处理分两档（见 [SKIP_CERT_CHECK]）：
 *
 * - **API < 22（Android 5.1 以下）**：直接跳过证书链校验；
 * - **API ≥ 22**：系统信任库 + 内置 G2 根，两条都走 PKIX。
 *
 * 两档都不改动 OkHttp 的主机名校验，TLS 版本同样只走 1.2 / 1.3。
 */
object HgtvTls {
    /**
     * 配置版本标记。
     *
     * 每改一次 TLS/证书策略就 +1：客户装的是不是最新包，看日志第一行就知道，
     * 不用再猜「他到底装的哪一版」。
     */
    private const val TLS_BUILD = "tls-7"

    private val LEGACY_CBC_SUITES = listOf(
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
        CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA,
        CipherSuite.TLS_RSA_WITH_AES_256_CBC_SHA,
    )

    /** 只协商 1.2 / 1.3，含旧机可用的 CBC 兜底套件。 */
    val SPEC: ConnectionSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
        .cipherSuites(
            *((ConnectionSpec.MODERN_TLS.cipherSuites.orEmpty() + LEGACY_CBC_SUITES).toTypedArray())
        )
        .build()

    /**
     * 直接喂给 `OkHttpClient.Builder.connectionSpecs(...)`。
     *
     * [ConnectionSpec.CLEARTEXT] 必须留着：播放地址与部分图片 CDN 会下发 `http://`，
     * 去掉会让这些请求直接抛 `UnknownServiceException`。
     */
    val SPECS: List<ConnectionSpec> = listOf(SPEC, ConnectionSpec.CLEARTEXT)

    /**
     * 服务端证书链的根：`DigiCert Global Root G2`。
     *
     * 指纹（SHA-256）
     * `CB:3C:CB:B7:60:31:E5:E0:13:8F:8D:D3:9A:23:F9:DE:47:FF:C3:5E:43:C1:14:4C:EA:27:D4:6A:5A:B1:CB:5F`
     *
     * 2014 年前后的 Android（4.4 / 5.x）系统根证书库里没有它，
     * 于是 `*.fqnovel.com` / `*.byteimg.com` / `*.hongguoduanju.com` 一律报
     * `Trust anchor for certification path not found`——证书链本身是完整的
     * （服务端会下发中间证书），缺的只是链顶这一张。
     */
    private val EXTRA_ROOT_PEM = """
        -----BEGIN CERTIFICATE-----
        MIIDjjCCAnagAwIBAgIQAzrx5qcRqaC7KGSxHQn65TANBgkqhkiG9w0BAQsFADBh
        MQswCQYDVQQGEwJVUzEVMBMGA1UEChMMRGlnaUNlcnQgSW5jMRkwFwYDVQQLExB3
        d3cuZGlnaWNlcnQuY29tMSAwHgYDVQQDExdEaWdpQ2VydCBHbG9iYWwgUm9vdCBH
        MjAeFw0xMzA4MDExMjAwMDBaFw0zODAxMTUxMjAwMDBaMGExCzAJBgNVBAYTAlVT
        MRUwEwYDVQQKEwxEaWdpQ2VydCBJbmMxGTAXBgNVBAsTEHd3dy5kaWdpY2VydC5j
        b20xIDAeBgNVBAMTF0RpZ2lDZXJ0IEdsb2JhbCBSb290IEcyMIIBIjANBgkqhkiG
        9w0BAQEFAAOCAQ8AMIIBCgKCAQEAuzfNNNx7a8myaJCtSnX/RrohCgiN9RlUyfuI
        2/Ou8jqJkTx65qsGGmvPrC3oXgkkRLpimn7Wo6h+4FR1IAWsULecYxpsMNzaHxmx
        1x7e/dfgy5SDN67sH0NO3Xss0r0upS/kqbitOtSZpLYl6ZtrAGCSYP9PIUkY92eQ
        q2EGnI/yuum06ZIya7XzV+hdG82MHauVBJVJ8zUtluNJbd134/tJS7SsVQepj5Wz
        tCO7TG1F8PapspUwtP1MVYwnSlcUfIKdzXOS0xZKBgyMUNGPHgm+F6HmIcr9g+UQ
        vIOlCsRnKPZzFBQ9RnbDhxSJITRNrw9FDKZJobq7nMWxM4MphQIDAQABo0IwQDAP
        BgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwIBhjAdBgNVHQ4EFgQUTiJUIBiV
        5uNu5g/6+rkS7QYXjzkwDQYJKoZIhvcNAQELBQADggEBAGBnKJRvDkhj6zHd6mcY
        1Yl9PMWLSn/pvtsrF9+wX3N3KjITOYFnQoQj8kVnNeyIv/iPsGEMNKSuIEyExtv4
        NeF22d+mQrvHRAiGfzZ0JFrabA0UWTW98kndth/Jsw1HKj2ZL7tcu7XUIOGZX1NG
        Fdtom/DzMNU+MeKNhJ7jitralj41E6Vf8PlwUHBHQRFXGU7Aj64GxJUTFy8bJZ91
        8rGOmaFvE7FBcf6IKshPECBV1/MUReXgRPTqh5Uykw7+U0b6LJ3/iyK5S9kJRaTe
        pLiaWN0bfVKfjllDiIGknibVb63dDcY3fe0Dkhvld1927jyNxF1WW6LZZm6zNTfl
        MrY=
        -----END CERTIFICATE-----
    """.trimIndent()

    /**
     * 平台根证书库 → 自建 PKIX 校验器。
     *
     * **不能**直接用 `TrustManagerFactory.init(null)` 拿到的那个：在声明了
     * `networkSecurityConfig` 的应用里，它是 `android.security.net.config.RootTrustManager`，
     * 其 **2 参 `checkServerTrusted` 一律抛**
     * `SSLHandshakeException: Domain specific configurations require that hostname aware
     * checkServerTrusted(X509Certificate[], String, String) is used`。
     *
     * 而 OkHttp 3.x 的证书链清理（`CertificateChainCleaner`）恰恰只用 2 参版本——
     * 于是握手成功后立刻失败，表现出来就是「连不上更新服务器」，所有 HTTPS 一起挂。
     *
     * 这里改成把系统 CA 库（Android 上是 `AndroidCAStore`）装进一个普通 `KeyStore`，
     * 再用 `TrustManagerFactory` 造 PKIX 校验器：证书链校验规则与系统一致，
     * 但不带「按域配置」那层包装，2 参 / 3 参都能用。
     */
    private val SYSTEM_TRUST_MANAGER: X509TrustManager = trustManager(systemCaStore())

    /** 系统 CA 库；拿不到（桌面 JVM 单测）时返回 null，退回平台默认库。 */
    private fun systemCaStore(): KeyStore? = runCatching {
        val store = KeyStore.getInstance("AndroidCAStore")
        store.load(null, null)
        store
    }.onFailure {
        NetLog.log("AndroidCAStore 不可用，退回平台默认信任库：${it.javaClass.simpleName} ${it.message}")
    }.getOrNull()

    /** 内置的那一张根（`DigiCert Global Root G2`），解析只要一两毫秒。 */
    private val EXTRA_ROOT_CERT: X509Certificate = java.security.cert.CertificateFactory.getInstance("X.509")
        .generateCertificate(ByteArrayInputStream(EXTRA_ROOT_PEM.toByteArray())) as X509Certificate

    /** 只信任上面那一张根的校验器（PKIX，签名与有效期照常检查）。 */
    private val EXTRA_TRUST_MANAGER: X509TrustManager = run {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("digicert_global_root_g2", EXTRA_ROOT_CERT)
        }
        trustManager(store)
    }

    /**
     * Android 5.1（API 22）以下：**不校验证书链**。
     *
     * 这批机器（4.4 / 5.0）的系统根证书库是 2013 年前后打包的，缺
     * `DigiCert Global Root G2`，服务端又只挂这一张根，结果是每台机器上
     * 每个接口都报 `Trust anchor for certification path not found`。
     * 这些设备没有再升级系统证书库的通道，因此按产品决定直接跳过校验。
     *
     * 只影响证书链校验；**主机名校验仍然由 OkHttp 自己做**（[OkHostnameVerifier]），
     * 协议版本也仍然是 TLS 1.2 / 1.3。
     *
     * `core` 是纯 Kotlin 模块，编译期没有 `android.os.Build`，所以反射取 SDK_INT；
     * 取不到（桌面 JVM / 单测）一律按「现代设备」处理，走严格校验。
     */
    private val SDK_INT: Int = runCatching {
        Class.forName("android.os.Build\$VERSION").getField("SDK_INT").getInt(null)
    }.getOrElse {
        // 反射失败时兜底：Dalvik 只出现在 Android 4.x，按 API 19 处理。
        if (System.getProperty("java.vm.name").orEmpty().contains("Dalvik", ignoreCase = true)) 19
        else Int.MAX_VALUE
    }

    /** 低于 Android 5.1 的机器跳过证书链校验。 */
    val SKIP_CERT_CHECK: Boolean = SDK_INT < 22

    /** 内置 G2 根优先、系统库兜底，两条都走 PKIX。 */
    private val STRICT_TRUST_MANAGER: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
            SYSTEM_TRUST_MANAGER.checkClientTrusted(chain, authType)

        /**
         * **先验内置单锚，失败再回退系统 150 锚**（顺序反了会慢，见下）。
         *
         * 系统根库那条路要为 150 张根逐张建 `TrustAnchor`、再跑一遍完整的
         * `CertPathValidator`（PKIX 建链 + 签名 + 基本约束 + 扩展解析）。这台
         * S905L3B / 2G 盒子冷启动时实测 **1.3~2.9 秒**，且这些类第一次用时全靠
         * 解释执行；等到第二次握手同类已经 JIT 好，就只剩几十毫秒——日志里
         * `tls=` 从 2913ms → 1298ms → 20ms 的衰减正是它。
         *
         * 本工程要连的域名全部挂在 `DigiCert Global Root G2` 下，服务端也会下发
         * 完整链（leaf → 中间 CA → G2），所以单锚校验既正确又**更严**（只认这一张根），
         * 不存在放宽：链断、过期、签名错照样抛 `CertificateException`。
         *
         * 单锚这条路的对象在类初始化时就建好了，第一次握手只做一次 `CertPathValidator`
         * 单链验证，耗时稳定在毫秒级。回退分支兜住「以后换了别的根」的情况。
         */
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            try {
                EXTRA_TRUST_MANAGER.checkServerTrusted(chain, authType)
                return
            } catch (_: CertificateException) {
                // 不是 G2 这条链（或确实有问题）：交给系统根库判定，抛它的原始异常。
            }
            SYSTEM_TRUST_MANAGER.checkServerTrusted(chain, authType)
        }

        /**
         * 系统根证书表**只解一次**。
         *
         * OkHttp 每建一个 client（`Builder.sslSocketFactory(...)` → `CertificateChainCleaner.get()`
         * → `buildTrustRootIndex()`）都会立刻问一遍这里。而
         * `NetworkSecurityTrustManager.getAcceptedIssuers()` 是**现读现解**：
         * 把 `/system/etc/security/cacerts` 下每一张 PEM 都 `CertificateFactory` 解一遍，
         * 再算 `subjectX500Principal`（`X500Name.parseDER` + NFKC 归一化）。
         *
         * 实测（S905L3B / 2G 盒子，150 张根证书）这一遍 **2.5~3.0 秒**，
         * 而且第一个 client 是在 `Application.onCreate` 里建的，等于整段启动都压在它上面
         * （见 2026-10-27 的线程栈：main 停在 `DerValue.toByteArray`）。
         *
         * 证书表在进程生命周期内不会变（系统更新根证书要重启），缓存一份即可。
         */
        /**
         * **不阻塞**地给出信任锚。
         *
         * 两条来源，谁先就绪用谁：
         *
         * 1. [WARM_ISSUERS]——后台线程解出来的完整系统根表（150 张）；
         * 2. [BOOTSTRAP_ISSUERS]——只有内置那张 `DigiCert Global Root G2`，构造时就有，**零耗时**。
         *
         * 为什么 2 够用：本工程要连的域名（`*.fqnovel.com` / `*.snssdk.com` /
         * `*.byteimg.com` / `*.fqnovelpic.com` / `hongguoduanju.com`）**服务端都把根证书
         * 一起下发**（实测三张：leaf → `RapidSSL TLS RSA CA G1` → `DigiCert Global Root G2`），
         * 而 OkHttp 的 `CertificateChainCleaner` 只要能在这张表里找到链路顶端那张根，
         * 就不会再走「补下载根证书」的分支。因此在这张表就绪之前，返回这一张也完全正确。
         *
         * 早先这里直接读系统表（2.5~3.0 秒，见上）：第一个 client 是在 `Application.onCreate`
         * 里建的，等于整段启动都压在这上面。改成「先给这一张、后台再补齐」之后，
         * 建 client 变成零成本，而那 2.5 秒彻底移出启动路径。
         *
         * 注意：这只影响**链清理**（`CertificateChainCleaner` 用它反查根）。
         * 真正校验链的 [checkServerTrusted] 先走内置单锚、再回退系统 PKIX
         * （[EXTRA_TRUST_MANAGER] → [SYSTEM_TRUST_MANAGER]），不因为这里少给几张就放宽。
         */
        override fun getAcceptedIssuers(): Array<X509Certificate> = WARM_ISSUERS ?: BOOTSTRAP_ISSUERS
    }

    /** 只有内置那张根：零成本，供系统根表解好之前使用（见 [STRICT_TRUST_MANAGER] 的说明）。 */
    private val BOOTSTRAP_ISSUERS: Array<X509Certificate> = arrayOf(EXTRA_ROOT_CERT)

    /**
     * 后台解出来的完整系统根表；`null` = 还没好。
     *
     * 刻意**不用** `lazy`：`lazy` 的求值线程会把「谁先来谁等 2.5 秒」的问题留给
     * 第一个调用者，而第一个调用者往往就是主线程。这里只暴露「好了没有」。
     */
    @Volatile private var WARM_ISSUERS: Array<X509Certificate>? = null

    /**
     * 低版本用的「不校验」实现。
     *
     * `checkServerTrusted` 直接返回，只留一行日志说明这台设备的 SDK；
     * 主机名校验不受影响（OkHttp 在校验链之后仍会比对 CN / SAN）。
     */
    private val RELAXED_TRUST_MANAGER: X509TrustManager = object : X509TrustManager {
        private val reported = java.util.concurrent.atomic.AtomicBoolean(false)

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            if (reported.compareAndSet(false, true)) {
                NetLog.log("SDK $SDK_INT < 22：已跳过证书链校验 ${chain.firstOrNull()?.subjectDN}")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** 对外只暴露一个：按系统版本选严格或放宽。 */
    val TRUST_MANAGER: X509TrustManager =
        if (SKIP_CERT_CHECK) RELAXED_TRUST_MANAGER else STRICT_TRUST_MANAGER

    private fun trustManager(store: KeyStore?): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(store)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /**
     * 在 socket 造出来的第一时间打开 TLS 1.2 / 1.3。
     *
     * 只保留这两个版本：`TLSv1` / `SSLv3` 留着只会让「降级到 1.1」的路径
     * 变成一次必然被服务端拒绝的握手。
     */
    /**
     * 注意：握手时用的是 **SSLContext 里装的 TrustManager**。
     *
     * [OkHttpClient.Builder.sslSocketFactory] 的第二个参数只被 OkHttp 拿去建
     * `CertificateChainCleaner`，**不参与握手校验**——所以这里必须把
     * [TRUST_MANAGER] 真正塞进 `SSLContext.init(...)`，否则「跳过证书链」形同虚设
     * （这正是 tls-4 上 `跳过证书链=true` 却仍然报 Trust anchor 的原因）。
     */
    private val sslContext: SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<javax.net.ssl.TrustManager>(TRUST_MANAGER), null)
    }

    val socketFactory: SSLSocketFactory = PrepareTlsSocketFactory(
        sslContext.socketFactory
    )

    /**
     * 给某个 client 一次性配好 TLS。
     *
     * 六个出口（内容接口 / 登录 / 更新 / 探针 / 播放 / 封面）都必须走这里：
     * 只配 `connectionSpecs` 不配 `sslSocketFactory` 的话，API 19 上会退化成
     * 「交集为空 → UnknownServiceException」。
     */
    private val configured = java.util.concurrent.atomic.AtomicBoolean(false)

    fun configure(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        if (configured.compareAndSet(false, true)) {
            NetLog.log("TLS 配置 $TLS_BUILD sdk=$SDK_INT 跳过证书链=$SKIP_CERT_CHECK")
        }
        return builder
            .connectionSpecs(SPECS)
            .sslSocketFactory(socketFactory, TRUST_MANAGER)
            // 带缓存的解析器：这台盒子的 `getaddrinfo` 单次 60~450ms，而冷启动要碰
            // 十来个域名、每个域名建连时各问一遍（见 [HgtvDns]）。
            .dns(HgtvDns)
    }

    /**
     * 把「解系统根证书表 + 建第一个 client」这两件重活提前做完。
     *
     * **必须在后台线程调**（一次 2~3 秒，见 [WARM_ISSUERS] 的说明）。
     * 由 `HgtvApplication.onCreate` 起一条线程来跑，与界面建视图并行；
     * 解好之后写进 [WARM_ISSUERS]，此后任何线程建 client 都立刻拿到完整表。
     * 解好之前也不是「没有」——[BOOTSTRAP_ISSUERS] 那张根对本工程的所有域名都够用。
     */
    fun warm() {
        if(WARM_ISSUERS==null) WARM_ISSUERS=acceptedIssuersFromSystem()
        runCatching { configure(OkHttpClient.Builder()).build() }
    }

    /** 真正去解系统根表；失败就留 `null`，让调用方继续用 [BOOTSTRAP_ISSUERS]。 */
    private fun acceptedIssuersFromSystem(): Array<X509Certificate>? = runCatching {
        SYSTEM_TRUST_MANAGER.acceptedIssuers
    }.getOrElse {
        NetLog.log("系统根证书表读取失败：${it.javaClass.simpleName} ${it.message}")
        null
    }?.takeIf { it.isNotEmpty() }
}

/**
 * 把 `TLSv1.2` / `TLSv1.3` 在 socket 创建时就启用。
 *
 * 其余方法全部原样转发；只有 `createSocket` 系列会在返回前调整一次协议列表。
 */
private class PrepareTlsSocketFactory(
    private val delegate: SSLSocketFactory
) : SSLSocketFactory() {
    private val wanted = arrayOf("TLSv1.3", "TLSv1.2")
    /** 只在第一次建 socket 时报告一次平台支持的协议，避免刷屏。 */
    private val reported = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        prepare(delegate.createSocket(s, host, port, autoClose))

    override fun createSocket(host: String, port: Int): Socket =
        prepare(delegate.createSocket(host, port))

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        prepare(delegate.createSocket(host, port, localHost, localPort))

    override fun createSocket(host: InetAddress, port: Int): Socket =
        prepare(delegate.createSocket(host, port))

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        prepare(delegate.createSocket(address, port, localAddress, localPort))

    private fun prepare(socket: Socket): Socket {
        if (socket is SSLSocket) {
            val supported = socket.supportedProtocols.toSet()
            val enable = wanted.filter { it in supported }
            // 一个都不支持（极老的 provider）时保持原状，交给上层报错，不在这里崩。
            if (enable.isNotEmpty()) {
                socket.enabledProtocols = enable.toTypedArray()
            }
            if (reported.compareAndSet(false, true)) {
                NetLog.log("TLS 可用版本=${supported.filter { it.startsWith("TLS") }} 启用=$enable")
            }
        }
        return socket
    }
}
