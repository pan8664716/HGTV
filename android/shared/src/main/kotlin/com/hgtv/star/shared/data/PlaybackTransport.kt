// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data
import com.hgtv.star.core.connectionPool
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit

/**
 * 播放链路（取地址 / 预热 / 分片读视频）共用的传输层。
 *
 * ## 为什么必须共享、而且必须走 HTTP/1.1
 *
 * 线上崩溃（TCL t982 / Android 11，连续播放 34 分钟后）：
 *
 * ```
 * IndexOutOfBoundsException: off=0, len=1482 out of bounds (size=0)
 *   at java.nio.DirectByteBuffer.get
 *   at ConscryptEngineSocket$SSLInputStream.processDataFromSocket
 *   at okio.Okio$2.read
 *   at okio.RealBufferedSource.require
 *   at Http2Reader.nextFrame
 *   at Http2Connection$ReaderRunnable.execute
 * ```
 *
 * 这是 **HTTP/2 连接读线程**在平台 TLS 里越界，与内存无关（那份日志 Java 堆只用了 31/256 MB）。
 *
 * 两个条件把它推出来：
 * 1. 每一集都新建一个 `OkHttpClient`，切集时 `cancelAll() + evictAll() + shutdown()`——
 *    在途的 TLS 连接被关闭，而 h2 的读线程正阻塞在 `read()` 里；
 * 2. h2 的读线程是 OkHttp 自己起的**裸线程**，异常没有人接，直接打死进程。
 *
 * 因此这里做两件事：
 * * **进程级共享一个 client**：切集只取消这一集自己的 call（`RemoteVideo.close()`），
 *   不再关连接池、不再 shutdown dispatcher；
 * * **只用 HTTP/1.1**：视频本来就是单流 Range 请求，多路复用没有收益；
 *   没有 h2 读线程，上面那条栈就不可能再出现。
 *
 * 图片链路（`ArtworkLoader`）仍然走 h2：那里并发多、复用收益真实，
 * 而且它的读线程只在下载期间活着，不在播放的长时间线上。
 */
object PlaybackTransport {
    /**
     * 连接池上限：同一时刻最多两集在跑（当前 + 预热），各留一条连接就够了，
     * 多留几条是为了切集瞬间的交接。
     */
    private const val MAX_IDLE = 4

    /**
     * 丢掉池里所有**空闲**连接（在途的那条不动，由 `RemoteVideo.close()` 取消）。
     *
     * 自愈路径要用它：路由器 NAT 表项被静默回收之后，池里那条连接「看起来还活着」——
     * 写得出、读不回，于是每次取流都卡满 `readTimeout`。`OkHttp` 自己不会因此剔除它
     *（`retryOnConnectionFailure` 只对建连阶段生效），只能由调用方显式清一次，
     * 下一条请求才会真的重新握手。见 `TvMainActivity.dropDeadTransport`。
     */
    fun evictIdleConnections() {
        runCatching { client.connectionPool.evictAll() }
    }

    val client: OkHttpClient by lazy {
        com.hgtv.star.core.HgtvTls.configure(OkHttpClient.Builder())
            // **钉死 HTTP/1.1**：见类注释。服务端支持 h2，这里主动不用。
            .protocols(listOf(Protocol.HTTP_1_1))
            // 会话身份：这条 client 也是 `ContentRepository` 的传输层（取播放地址 / 预热），
            // 不挂拦截器就享受不到 token 轮换与 cookie 续期。分片读视频走的是别的 client
            // （`RemoteVideo` 自建），不受这一行影响。
            //
            // **只挂一次**。早先这里挂了两遍（同一行在下面又出现一次），后果不是「多补一次 Cookie」
            // 那么简单：`SessionInterceptor` 每个响应都会 `peekBody(32KB)` 嗅探 `session_expired`，
            // 两遍就是两次完整 body 拷贝 + 两次 UTF-8 解码，而这段代码正好在
            // `/novel/player/video_model` 的响应上——也就是首帧关键路径。
            .addInterceptor(com.hgtv.star.core.session.SessionInterceptor())
            // **建连最多 3 秒**（原 8 秒）。这是「断网切集」那 30~50 秒冻屏的最后一块：
            //
            // 取地址整段已经有 3 秒硬上限（见 `ContentRepository.ADDRESS_DEADLINE_MS`），
            // 但 `RemoteVideo.prepare()` 用的是**这条** client，它不在那段预算里。
            // 断网时 `range(0,256KB)` 的 SYN 石沉大海，旧值要等满 8 秒才抛
            //（现场：`Stage prepare` 前后各卡 8~16 秒，串行两次 Range 就是 16 秒）。
            //
            // 为什么是 3 秒而不是更短：正常建连是十几毫秒，最坏的一次实测是
            // **3105ms**（2026-10-30，小米 TV，断网恢复那一刻的冷 DNS + 冷路由，
            // 日志 `net POST … tcp=3135ms`）。3 秒正好兜住它；再短就会把「慢但能成」
            // 的一发判成失败，白多一轮重来。
            //
            // **只收这一项**：`readTimeout` / `callTimeout` 不动。分片读 1MB 在这台盒子的
            // 弱网下真的会慢到几十秒，收它们是拿正常播放去换一个已经由上层兜住的场景。
            .connectTimeout(3, TimeUnit.SECONDS)
            // 与改动前播放用的超时一致：一次 Range 读最多 1 MB，30 秒读超时、
            // 45 秒整调用超时。不要再收紧——电视盒子在弱网下真的会慢到这个量级。
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .dispatcher(Dispatcher().apply {
                // 播放是顺序 Range 读，并发本来就只有 1~2；预热另算一条。
                maxRequests = 6
                maxRequestsPerHost = 6
            })
            .connectionPool(ConnectionPool(MAX_IDLE, 5, TimeUnit.MINUTES))
            .build()
    }
}
