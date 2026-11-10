// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.media
import android.net.Uri
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.upstream.BaseDataSource
import com.google.android.exoplayer2.upstream.DataSpec
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
        // 播放中的后续分片按 512KB 取：加载线程每取一次都要发一个同步 Range 请求并解密。
        //
        // 2MB 分片在这台盒子上单次解密要 180~727ms，并产生数 MB 临时数组；
        // 缓存增长时正好与弹幕的 60fps 绘制抢 CPU，日志会连续出现
        // `Skipped 54~127 frames`。降到 512KB 后单次解密/分配缩短到约四分之一，
        // Range 次数会增加，但每次网络等待都会让出 CPU，弹幕明显更稳。
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
        /** 后续分片：512KB，优先保证弹幕与主线程帧率，不追求单次传输字节数。 */
        const val BUFFER_CHUNK=512L*1024
    }
}
