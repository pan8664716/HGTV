// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * 公共 DNS 兜底解析：自己发裸 UDP 查询，不经过系统解析器。
 *
 * ## 为什么需要它（现场证据）
 *
 * TCL 电视（`tcl_mt5879_cn`，Android 11）上有一集永远播不了：
 *
 * ```
 * HGTV-NET: v5-se-zjnbtc-reading-video-a.qznovelvod.com/.../ UnknownHostException:
 *           Unable to resolve host "v5-se-zjnbtc-reading-video-a.qznovelvod.com": No address associated with hostname
 * ```
 *
 * 同一个域名、同一时刻：
 *
 * * 电脑上用路由器 DNS（192.168.31.1）、223.5.5.5、119.29.29.29 查，**都有 A 记录**
 *   （`183.56.135.40` 等一串）；
 * * 解析出来的 IP 从这台电视 `ping`，**41ms 通**；
 * * 同一个客户端打 `api5-normal-*.fqnovel.com` 全部正常，只有这个视频 CDN 域名解析不出来。
 *
 * 也就是：域名没死、线路没断，是**这台设备的解析链路**对这个域名的回答是
 * `EAI_NODATA`（IPv6-only 的 DNS 上游、路由器的域名过滤、DNS 缓存里的负结果都会长成这样）。
 * 播放窗口只有十几秒，等运营商 DNS 缓存过期是不现实的，因此这里直接问公共 DNS。
 *
 * ## 边界
 *
 * * 只在**系统解析失败之后**调用（[HgtvDns.lookup] 的 catch 分支），正常路径一次都不走；
 * * 每个服务器 1.5 秒超时、最多试三个，最坏 4.5 秒，且不在主线程上（OkHttp 建连线程）；
 * * 只查 A，查不到再查 AAAA，**不做递归**（响应里带 CNAME 也能读到后面的 A 记录）；
 * * 响应被截断（`TC=1`）或 UDP 完全不通时，同一条查询走 **TCP 53** 再来一次：
 *   有些电视 / 路由会把 UDP 53 掐掉（只是把包扔掉），TCP 反而是通的；
 * * `core` 是纯 JVM：只用 `java.net`，不碰 `android.*`。
 *
 * ## 为什么每条失败都要留日志
 *
 * 现场第一次跑这一版时，`dns public` 一行都没出现，而域名在电脑上明明能解析——
 * 没有日志就分不清是「UDP 没发出去」「服务器没回」还是「回了但我没解析出来」。
 * 因此 [lookup] 在**全部失败**时把每个服务器的错误类型都记下来（只在失败路径上写，
 * 成功路径仍然只有一行）。
 */
internal object PublicDns {
    /** 三个公共 DNS，按国内可达性排序；全部超时才放弃。 */
    private val SERVERS = listOf("223.5.5.5","119.29.29.29","180.76.76.76")
    private const val TIMEOUT_MS = 1_500
    private const val TYPE_A = 1
    private const val TYPE_AAAA = 28
    private const val CLASS_IN = 1
    /** 响应缓冲：13 条 A 记录 + 两级 CNAME 约 300 字节，1232 是 EDNS 常用的安全上限。 */
    private const val BUFFER = 1_232

    /** 依次问各个服务器，谁先答对就用谁；全都不行返回空表（调用方继续抛原来的异常）。 */
    fun lookup(hostname: String): List<InetAddress> {
        if(hostname.isEmpty() || hostname.length>253) return emptyList()
        val errors=StringBuilder()
        // 先 A 后 AAAA：IPv6-only 的线路也要能救回来（视频 CDN 基本都有 v6 授权）。
        for(type in intArrayOf(TYPE_A,TYPE_AAAA)) {
            for(server in SERVERS) {
                val result=runCatching { query(server,hostname,type) }
                val addresses=result.getOrNull()
                if(!addresses.isNullOrEmpty()) {
                    NetLog.log("dns public host=$hostname server=$server addrs=${addresses.size}"+(if(type==TYPE_AAAA) " v6=true" else ""))
                    return addresses
                }
                val error=result.exceptionOrNull()
                if(errors.isNotEmpty()) errors.append(' ')
                errors.append(server).append(':').append(error?.let { it.javaClass.simpleName+":"+it.message } ?: "no-answer")
            }
        }
        NetLog.log("dns public fail host=$hostname $errors")
        return emptyList()
    }

    /** 发一个查询（先 UDP，必要时 TCP），把响应里的 A / AAAA 记录解出来。 */
    private fun query(server: String, hostname: String, type: Int): List<InetAddress> {
        val id=(System.nanoTime() and 0xFFFF).toInt()
        val request=buildQuery(id,hostname,type)
        val response=ByteArray(BUFFER)
        val packet=DatagramPacket(response,response.size)
        runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout=TIMEOUT_MS
                socket.send(DatagramPacket(request,request.size,InetAddress.getByName(server),53))
                socket.receive(packet)
            }
        }.onSuccess {
            // 只解**真正收到的那几个字节**：`DatagramPacket` 不会把数组缩到实际长度，
            // 后面那截是上一次的残留 / 全 0，拿整块去解析会读到不存在的记录。
            val data=response.copyOf(packet.length)
            val parsed=parseResponse(data,id,type)
            // `TC=1`：服务器说「答案太长，512 字节装不下，请用 TCP」。
            if(parsed.isNotEmpty() || !truncated(data)) return parsed
        }
        // UDP 不通 / 被截断：同一条查询走 TCP。TCP 53 与 UDP 53 是两条独立的通路，
        // 现场那些「UDP 扔包、TCP 通」的网络就是这么救回来的。
        return queryTcp(server,hostname,type)
    }

    /** TCP DNS：2 字节长度前缀 + 报文原文。 */
    private fun queryTcp(server: String, hostname: String, type: Int): List<InetAddress> {
        val id=(System.nanoTime() and 0xFFFF).toInt()
        val request=buildQuery(id,hostname,type)
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(InetAddress.getByName(server),53),TIMEOUT_MS)
            socket.soTimeout=TIMEOUT_MS
            val out=socket.getOutputStream()
            out.write(byteArrayOf((request.size ushr 8).toByte(),(request.size and 0xFF).toByte()))
            out.write(request)
            out.flush()
            val input=socket.getInputStream()
            val length=(input.read() shl 8) or input.read()
            if(length<=0) return emptyList()
            val response=ByteArray(length)
            var read=0
            while(read<length) {
                val n=input.read(response,read,length-read)
                if(n<0) return emptyList()
                read+=n
            }
            return parseResponse(response,id,type)
        }
    }

    private fun truncated(data: ByteArray): Boolean = data.size>3 && (data[2].toInt() and 0x02)!=0

    private fun buildQuery(id: Int, hostname: String, type: Int): ByteArray {
        val out=java.io.ByteArrayOutputStream(64)
        fun u16(value: Int) { out.write((value ushr 8) and 0xFF); out.write(value and 0xFF) }
        u16(id)
        u16(0x0100)                       // 标准查询、要求递归
        u16(1); u16(0); u16(0); u16(1)    // QDCOUNT=1、ARCOUNT=1（一条 EDNS0 OPT）
        for(label in hostname.split('.')) {
            if(label.isEmpty() || label.length>63) throw IllegalArgumentException("非法域名")
            val bytes=label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size); out.write(bytes)
        }
        out.write(0)
        u16(type); u16(CLASS_IN)
        // EDNS0 OPT：声明本端能收 1232 字节，服务器就不会因为 512 字节上限而截断
        //（视频 CDN 一个域名十几条 A，加上 CNAME 链很容易撞线）。
        out.write(0)                      // 根域名
        u16(41); u16(BUFFER)              // TYPE=OPT，CLASS=UDP payload size
        u16(0); u16(0)                    // TTL 四个字节（扩展 rcode + 版本 + 标志，全 0）
        u16(0)                            // RDLEN=0
        return out.toByteArray()
    }

    /** 响应里挑出与 [type] 对得上的记录；ID 不符 / 被截断 / rcode 非 0 一律当作没答上来。 */
    private fun parseResponse(data: ByteArray, id: Int, type: Int): List<InetAddress> {
        fun u8(at: Int)=data[at].toInt() and 0xFF
        fun u16(at: Int)=(u8(at) shl 8) or u8(at+1)
        if(data.size<12) return emptyList()
        if(u16(0)!=id) return emptyList()
        if(u8(2) and 0x80==0) return emptyList()   // 不是响应
        if(u16(2) and 0x000F!=0) return emptyList() // rcode：NXDOMAIN / SERVFAIL
        val questions=u16(4); val answers=u16(6)
        var cursor=12
        repeat(questions) {
            cursor=skipName(data,cursor)
            cursor+=4                              // QTYPE + QCLASS
            if(cursor>data.size) return emptyList()
        }
        val addresses=ArrayList<InetAddress>(4)
        repeat(answers) {
            if(cursor>data.size) return@repeat
            cursor=skipName(data,cursor)
            if(cursor+10>data.size) return@repeat
            val recordType=u16(cursor)
            val length=u16(cursor+8)
            val start=cursor+10
            val end=start+length
            if(end>data.size) return@repeat
            val expected=when(recordType) { TYPE_A -> 4; TYPE_AAAA -> 16; else -> 0 }
            if(recordType==type && length==expected) {
                runCatching { InetAddress.getByAddress(data.copyOfRange(start,end)) }.getOrNull()?.let(addresses::add)
            }
            cursor=end
        }
        return addresses
    }

    /** 跳过（可能带压缩指针的）域名；返回它后面的第一个字节。 */
    private fun skipName(data: ByteArray, start: Int): Int {
        var pos=start
        while(pos<data.size) {
            val length=data[pos].toInt() and 0xFF
            if(length==0) return pos+1
            if(length and 0xC0==0xC0) return pos+2
            pos+=1+length
        }
        return data.size
    }
}
