// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * 一次请求的连接耗时分解（调试包专用，见 [NetLog.timing]）。
 *
 * 为什么要它：`Recommend stage feed +3400ms` 这种数字只说明「这一次请求慢」，
 * 看不出慢在 DNS、TCP、TLS 握手、还是服务端。而这四条的修法完全不同
 *（缓存解析 / 预建连接 / 降低握手成本 / 换线路）。这里只打一行：
 *
 * ```
 * net POST api5-normal-sinfonlineb.fqnovel.com conn=reused dns=-1ms tcp=-1ms tls=-1ms
 *     connect=-1ms pre=26ms send=0ms wait=1069ms body=61ms 37981B total=1118ms
 * ```
 *
 * 各栏的边界（**都是上一栏结束到下一栏结束，互不重叠**）：
 *
 * | 栏 | 从 | 到 | 大了说明 |
 * |---|---|---|---|
 * | `dns` | 开始解析 | 拿到 IP | 域名解析慢（见 [HgtvDns]） |
 * | `tcp` | 发起 TCP | TCP 建好 | 链路 / 机房远 / 首次建 socket |
 * | `tls` | 开始握手 | 握手完成 | 握手本身（含我们的 PKIX 验链） |
 * | `pre` | 调用开始 | 开始写请求头 | 连接池排队 + 拦截器链，**慢在客户端** |
 * | `send` | 写请求头 | 请求体写完 | 上传慢（我们只有 1 KB 级 JSON） |
 * | `wait` | 请求写完 | 响应头到齐 | **服务端处理 + 一个 RTT，客户端管不着** |
 * | `body` | 开始读响应体 | 读完 | 下行带宽 / 解压 |
 *
 * 早先这里把「开始读响应头」当成首字节（`ttfb`），于是 POST 的 1 秒服务端等待
 * 被算进了 `body`，读日志时会误以为「是我们读得慢」。OkHttp 3.12 的
 * `CallServerInterceptor` 是先回调 `responseHeadersStart` 再 `readResponseHeaders`，
 * 所以必须用 `responseHeadersEnd` 才算真正的「服务端回来了」。
 *
 * 新连接一定打；**复用的连接**只在这一次 `total` 超过 [SLOW_REUSED_MS] 时打
 *（`conn=reused`，只有 `ttfb` / `body` 两栏有意义）——复用的那次如果还是慢，
 * 说明瓶颈在服务端或请求体，而不在握手。只在调试包挂载，实现上全是普通字段，不加锁。
 */
internal class NetTiming : EventListener() {
    /** 每个请求一份实例（`EventListener` 有状态，不能共享）。 */
    object Factory : EventListener.Factory {
        override fun create(call: Call): EventListener = NetTiming()
    }

    private companion object {
        /** 复用连接超过这个总耗时才打日志，免得每个小请求都占一行。 */
        const val SLOW_REUSED_MS=300L
    }

    private var callStart=0L
    private var dnsStart=0L
    private var dnsEnd=0L
    private var connectStart=0L
    private var connectEnd=0L
    private var secureStart=0L
    private var secureEnd=0L
    private var requestAt=0L
    private var requestEndAt=0L
    private var headersAt=0L
    private var headersEndAt=0L
    private var bodyStartAt=0L
    private var logged=false

    private fun now()=System.nanoTime()
    private fun ms(from: Long,to: Long)=if(from==0L || to<from) -1L else (to-from)/1_000_000L

    override fun callStart(call: Call) { callStart=now() }
    override fun dnsStart(call: Call,domainName: String) { dnsStart=now() }
    override fun dnsEnd(call: Call,domainName: String,inetAddressList: List<java.net.InetAddress>) { dnsEnd=now() }
    override fun connectStart(call: Call,inetSocketAddress: InetSocketAddress,proxy: Proxy) { connectStart=now() }
    override fun connectEnd(call: Call,inetSocketAddress: InetSocketAddress,proxy: Proxy,protocol: Protocol?) {
        connectEnd=now()
    }
    override fun secureConnectStart(call: Call) { secureStart=now() }
    override fun secureConnectEnd(call: Call,handshake: Handshake?) { secureEnd=now() }
    override fun requestHeadersStart(call: Call) { requestAt=now() }
    override fun requestHeadersEnd(call: Call,request: okhttp3.Request) { requestEndAt=now() }
    override fun responseHeadersStart(call: Call) { headersAt=now() }
    override fun responseHeadersEnd(call: Call,response: okhttp3.Response) { headersEndAt=now() }
    override fun responseBodyStart(call: Call) { bodyStartAt=now() }

    /**
     * 响应体读完才发现「这一次用了多久」——`ttfb` 只到响应头，读 body 的时间在 2G 盒子上
     * 可能比它更长（几百 KB 的 JSON 解压 + 传输）。放在 `responseBodyEnd` 才是一次请求的全貌。
     */
    override fun responseBodyEnd(call: Call,byteCount: Long) {
        if(logged) return
        logged=true
        val endAt=now()
        val reused=dnsStart==0L && connectStart==0L && secureStart==0L
        val total=ms(callStart,endAt)
        if(reused && total<SLOW_REUSED_MS) return
        val host=runCatching { call.request().url.host }.getOrDefault("?")
        val handshakeEnd=if(secureEnd>0L) secureEnd else connectEnd
        NetLog.log(
            "net ${call.request().method} $host ${if(reused) "conn=reused" else "conn=new"}"+
            " dns=${ms(dnsStart,dnsEnd)}ms"+
            " tcp=${ms(connectStart,connectEnd)}ms"+
            " tls=${ms(secureStart,secureEnd)}ms"+
            " connect=${ms(connectStart,handshakeEnd)}ms"+
            // 三段分开看，别混：
            // `pre`  = 调用线程进到「真正写请求头」之前：连接池排队 + 拦截器链（签名在调用方，不在这里）
            // `send` = 写请求头 + 请求体
            // `wait` = 请求写完到**服务端响应头到齐**——这才是真·服务端处理 + 一个 RTT
            " pre=${ms(callStart,requestAt)}ms"+
            " send=${ms(requestAt,requestEndAt)}ms"+
            " wait=${ms(requestEndAt,headersEndAt)}ms"+
            " body=${ms(bodyStartAt,endAt)}ms"+
            " $byteCount"+"B"+
            " total=${total}ms"
        )
    }

    /** 失败 / 取消也要留一行：`callFailed` 时 body 回调不会来。 */
    override fun callFailed(call: Call,ioe: java.io.IOException) {
        if(logged) return
        logged=true
        val host=runCatching { call.request().url.host }.getOrDefault("?")
        NetLog.log(
            "net ${call.request().method} $host failed after ${ms(callStart,now())}ms ${ioe.javaClass.simpleName}: ${ioe.message}"
        )
    }
}
