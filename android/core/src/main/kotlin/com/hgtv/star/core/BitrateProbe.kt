// SPDX-License-Identifier: GPL-3.0-only.
package com.hgtv.star.core

/**
 * 一次性探针：抓几部短剧的 1080P 播放地址，从 MP4 头部量它们的真实码率。
 *
 * 码率不取接口自报的字段，直接算：文件总字节 × 8 ÷ `mvhd` 时长。
 * 这样量到的是「电视端实际要拉多少字节」，接口字段写错也不影响结论。
 *
 * 用法：把 installDist 的 lib 目录整体放到 java 的 classpath 上，运行 BitrateProbeKt，参数是样本数。
 */

private fun u32(b: ByteArray,at: Int): Long =
    ((b[at].toLong() and 0xff) shl 24) or ((b[at+1].toLong() and 0xff) shl 16) or
        ((b[at+2].toLong() and 0xff) shl 8) or (b[at+3].toLong() and 0xff)

private fun u64(b: ByteArray,at: Int): Long = (u32(b,at) shl 32) or u32(b,at+4)

private fun fourcc(b: ByteArray,at: Int)=String(b,at,4,Charsets.US_ASCII)

/** 遍历 [from,to) 里的同级 box，产出 (类型, 内容起点, 内容终点)。 */
private fun boxes(b: ByteArray,from: Int,to: Int): List<Triple<String,Int,Int>> {
    val out=mutableListOf<Triple<String,Int,Int>>()
    var at=from
    while(at+8<=to) {
        var size=u32(b,at); var header=8
        if(size==1L) { if(at+16>to) break; size=u64(b,at+8); header=16 }
        if(size==0L) size=(to-at).toLong()
        if(size<header || at+size>to) break
        out+=Triple(fourcc(b,at+4),at+header,(at+size).toInt())
        at+=size.toInt()
    }
    return out
}

private fun find(b: ByteArray,from: Int,to: Int,type: String)=boxes(b,from,to).firstOrNull { it.first==type }

private data class Track(val kind: String,val codec: String,val width: Int,val height: Int,val samples: Long,val bytes: Long,val durationMs: Long)
private data class Mp4Info(val durationMs: Long,val width: Int,val height: Int,val video: Track?)

private fun children(b: ByteArray,box: Triple<String,Int,Int>,type: String)=find(b,box.second,box.third,type)

/** 轨道级解析：mdhd 拿时长、stsd 拿编码、stsz 拿样本数与总字节（就能分开算视频/音频码率）。 */
private fun track(b: ByteArray,trak: Triple<String,Int,Int>): Track? {
    val mdia=children(b,trak,"mdia") ?: return null
    val hdlr=children(b,mdia,"hdlr") ?: return null
    val kind=String(b,hdlr.second+8,4,Charsets.US_ASCII)
    val mdhd=children(b,mdia,"mdhd") ?: return null
    val version=bv(b,mdhd.second)
    val timescale=if(version==1) u32(b,mdhd.second+20) else u32(b,mdhd.second+12)
    val duration=if(version==1) u64(b,mdhd.second+24) else u32(b,mdhd.second+16)
    if(timescale==0L) return null
    val minf=children(b,mdia,"minf") ?: return null
    val stbl=children(b,minf,"stbl") ?: return null
    val stsd=children(b,stbl,"stsd") ?: return null
    // stsd：version+flags(4) + entry_count(4) + 条目 size(4) + 条目 format(4)
    val codec=runCatching { String(b,stsd.second+12,4,Charsets.US_ASCII) }.getOrDefault("?")
    // hvcC：profile_idc 决定是 Main(1) 还是 Main10(2)，level_idc 决定标称上限。电视的硬解
    // 对 Main10 往往只保证 1080p30，倍速播放正好踩在这个上限上。
    var profile=""
    if(codec=="hvc1" || codec=="hev1") {
        val at=(stsd.second..(stsd.third-4)).firstOrNull { String(b,it,4,Charsets.US_ASCII)=="hvcC" }
        if(at!=null && at+4+13<=b.size) {
            val idc=b[at+4+1].toInt() and 0x1f
            val level=b[at+4+12].toInt() and 0xff
            profile=(if(idc==2) "Main10" else if(idc==1) "Main" else "idc$idc")+"/L$level"
        }
    }
    val stsz=children(b,stbl,"stsz") ?: return null
    val count=u32(b,stsz.second+8)
    val fixed=u32(b,stsz.second+4)
    val bytes=if(fixed>0) fixed*count else (0 until count.toInt()).sumOf { u32(b,stsz.second+12+it*4).toLong() }
    var width=0; var height=0
    val tkhd=children(b,trak,"tkhd")
    if(tkhd!=null) {
        val at=if(bv(b,tkhd.second)==1) tkhd.second+88 else tkhd.second+76
        if(at+8<=tkhd.third) { width=(u32(b,at) shr 16).toInt(); height=(u32(b,at+4) shr 16).toInt() }
    }
    return Track(kind,codec+if(profile.isEmpty()) "" else "·$profile",width,height,count,bytes,duration*1000/timescale)
}

private fun parseMoov(head: ByteArray): Mp4Info? {
    val moov=find(head,0,head.size,"moov") ?: return null
    val mvhd=find(head,moov.second,moov.third,"mvhd") ?: return null
    val version=bv(head,mvhd.second)
    val timescale=if(version==1) u32(head,mvhd.second+20) else u32(head,mvhd.second+12)
    val duration=if(version==1) u64(head,mvhd.second+24) else u32(head,mvhd.second+16)
    if(timescale==0L) return null
    val tracks=boxes(head,moov.second,moov.third).filter { it.first=="trak" }.mapNotNull { track(head,it) }
    val video=tracks.firstOrNull { it.kind=="vide" }
    return Mp4Info(duration*1000/timescale,video?.width ?: 0,video?.height ?: 0,video)
}

private fun bv(b: ByteArray,at: Int)=(b[at].toInt() and 0xff)

fun main(args: Array<String>) {
    val repository=ContentRepository()
    val registered=repository.registerDevice()
    if(registered==null) { println("设备注册失败，后续接口拿不到数据"); return }
    println("设备已注册 device_id=${registered.deviceId} iid=${registered.installId}\n")
    val wanted=args.firstOrNull()?.toIntOrNull() ?: 10
    val maxQuality=args.getOrNull(1)?.toIntOrNull() ?: 1080
    val feed=runCatching { repository.homeFeed() }.getOrElse { println("首页失败：${it.message}"); return }
    val series=feed.values.flatMap { it.items }.distinctBy { it.id }
    println("首页拿到 ${series.size} 部候选\n")
    val rows=mutableListOf<String>()
    for(item in series) {
        if(rows.size>=wanted) break
        val detail=runCatching { repository.detail(item.id) }.getOrNull() ?: continue
        val vid=detail.episodes.firstOrNull() ?: continue
        val stream=runCatching { repository.stream(vid,maxQuality) }.getOrNull() ?: continue
        runCatching {
            RemoteVideo(repository.http,stream).use { remote ->
                remote.prepare()
                val headSize=minOf(remote.total,2L*1024*1024).toInt()
                val head=remote.read(0,headSize)
                val info=parseMoov(head)
                val started=System.nanoTime()
                val bytes=if(remote.total>headSize) remote.read(headSize.toLong(),1024*1024) else ByteArray(0)
                val elapsedMs=(System.nanoTime()-started)/1_000_000.0
                val seconds=if(info!=null && info.durationMs>0) info.durationMs/1000.0 else 0.0
                val kbps=if(seconds>0) remote.total*8/1000.0/seconds else 0.0
                val v=info?.video
                val vkbps=if(v!=null && v.durationMs>0) v.bytes*8/1000.0/(v.durationMs/1000.0) else 0.0
                val fps=if(v!=null && v.durationMs>0) v.samples*1000.0/v.durationMs else 0.0
                val line=String.format(
                    "%-20s %-4s %4dx%-4d %-5s %6.1fs %5.1f MB  总=%7.1f 视频=%7.1f kbps %5.1f fps 拉取=%.2f MB/s",
                    detail.series.title.take(20),stream.quality,info?.width ?: 0,info?.height ?: 0,v?.codec ?: "?",seconds,
                    remote.total/1024.0/1024.0,kbps,vkbps,fps,
                    if(elapsedMs>0) bytes.size/1024.0/1024.0/(elapsedMs/1000.0) else 0.0
                )
                rows+=line
                println(line)
            }
        }.onFailure { println("${detail.series.title.take(20)} 取样失败：${it.message}") }
    }
    if(rows.isNotEmpty()) {
        fun column(name: String)=rows.mapNotNull { Regex("$name=\\s*([0-9.]+)").find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
        val total=column("总"); val video=column("视频")
        val codecs=rows.mapNotNull { Regex("\\s(avc1|hvc1|hev1|mp4v|vp09)\\s").find(it)?.groupValues?.get(1) }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        println("\n样本=${rows.size}")
        println("整文件码率 kbps：最低=${total.minOrNull()?.toInt()} 最高=${total.maxOrNull()?.toInt()} 平均=${total.average().toInt()}")
        println("视频码率   kbps：最低=${video.minOrNull()?.toInt()} 最高=${video.maxOrNull()?.toInt()} 平均=${video.average().toInt()}")
        println("编码分布：$codecs")
        println("2× 需要的带宽（按最高样本）：${(total.maxOrNull() ?: 0.0)*2/1000} Mbps")
        println("3s 真实缓冲需要的内存（按最高码率 ×2 倍速 × 3s）：${(total.maxOrNull() ?: 0.0)*2*3/8/1024} MB")
    }
    repository.http.connectionPool().evictAll(); repository.http.dispatcher().executorService().shutdown()
}
