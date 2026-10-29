// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.hgtv.star.core.RemoteVideo

class VideoDataSource(private val video: RemoteVideo): BaseDataSource(true) {
    private var position=0L
    private var remaining=0L
    private var buffer=byteArrayOf()
    private var offset=0
    private var opened=false
    private var uri: Uri?=null
    /**
     * 这一路还没取过数据（= 还在首帧路径上）。
     *
     * 首块刻意只要 [FIRST_CHUNK]，之后才回到 [BUFFER_CHUNK]：
     * 1080P HEVC 的单个 I 帧实测 <300KB，出首帧根本用不到 2MB；
     * 而 2MB 在这台盒子上要 800~1800ms（见日志 `video range 262144-2097151`）。
     * 先要 512KB 就能把首帧等的时间压掉一半，剩下的大块由后台的第二次读补上，
     * 那时画面已经在播，慢一点用户感觉不到。
     *
     * 只在**冷启动**起作用：预热过的下一集前 1MB 已经在 `RemoteVideo` 的内存里，
     * 首块根本不会走网络。
     */
    private var firstChunk=true
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec); uri=dataSpec.uri; position=dataSpec.position
        if(position>video.total) throw java.io.IOException("读取位置超出视频长度")
        remaining=if(dataSpec.length==C.LENGTH_UNSET.toLong()) video.total-position else minOf(dataSpec.length,video.total-position)
        // 每次 open（起播 / seek 之后重开）都从「小块」开始：seek 到中段时同样只要
        // 一块够解出下一帧的数据，不该一次拉 2MB。
        buffer=byteArrayOf(); offset=0; firstChunk=true; opened=true; transferStarted(dataSpec); return remaining
    }
    override fun read(target: ByteArray,targetOffset: Int,length: Int): Int {
        if(length==0) return 0
        if(remaining==0L) return C.RESULT_END_OF_INPUT
        // 一次取 2MB：加载线程每取一次都要发一个同步 Range 请求并解密。
        //
        // 粒度直接决定起播要几个往返：`DefaultLoadControl` 的最小缓冲是 20 秒媒体，
        // 1080P（≈1.6 Mbps）就是 4 MB，原来按 1 MB 一块要**四次** Range 请求才够
        // 首帧，每次都是「发出去→等回来→解密」的串行等待；2 MB 只要两次。
        // 实测这台盒子单次往返 150~400ms（同一连接 keep-alive，不含 TLS），
        // 首帧因此能省掉两三百毫秒。
        //
        // 上限 2 MB 是 [RemoteVideo.read] 允许的单次最大值；再大就要把
        // `RemoteVideo.read` 的断言与内存占用一起放大，收益却已经不明显。
        if(offset>=buffer.size) {
            val want=if(firstChunk) FIRST_CHUNK else BUFFER_CHUNK
            firstChunk=false
            buffer=video.read(position,minOf(want,remaining).toInt()); offset=0
        }
        if(buffer.isEmpty()) throw java.io.EOFException("视频分段意外结束")
        val count=minOf(length,buffer.size-offset,remaining.toInt())
        buffer.copyInto(target,targetOffset,offset,offset+count); offset+=count; position+=count; remaining-=count; bytesTransferred(count); return count
    }
    override fun getUri()=uri
    override fun close() { buffer=byteArrayOf(); uri=null; if(opened) { opened=false; transferEnded() } }
    private companion object {
        /** 首块：只够一个 1080P 的 I 帧 + 若干 P 帧。 */
        const val FIRST_CHUNK=512L*1024
        /** 之后的大块：2MB，与 `RemoteVideo.read` 的上限一致（见那边的注释）。 */
        const val BUFFER_CHUNK=2L*1024*1024
    }
}
