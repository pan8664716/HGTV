// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.player

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
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec); uri=dataSpec.uri; position=dataSpec.position
        if(position>video.total) throw java.io.IOException("读取位置超出视频长度")
        remaining=if(dataSpec.length==C.LENGTH_UNSET.toLong()) video.total-position else minOf(dataSpec.length,video.total-position)
        buffer=byteArrayOf(); offset=0; opened=true; transferStarted(dataSpec); return remaining
    }
    override fun read(target: ByteArray,targetOffset: Int,length: Int): Int {
        if(length==0) return 0
        if(remaining==0L) return C.RESULT_END_OF_INPUT
        // 一次取 1MB：加载线程每取一次都要发一个同步 Range 请求并解密，
        // 倍速播放时字节吞吐按倍速上涨，512KB 的粒度会把请求次数放大到
        // 每秒三四次，往返延迟直接吃掉缓冲。
        if(offset>=buffer.size) { buffer=video.read(position,minOf(1024*1024L,remaining).toInt()); offset=0 }
        if(buffer.isEmpty()) throw java.io.EOFException("视频分段意外结束")
        val count=minOf(length,buffer.size-offset,remaining.toInt())
        buffer.copyInto(target,targetOffset,offset,offset+count); offset+=count; position+=count; remaining-=count; bytesTransferred(count); return count
    }
    override fun getUri()=uri
    override fun close() { buffer=byteArrayOf(); uri=null; if(opened) { opened=false; transferEnded() } }
}
