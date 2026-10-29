// Kotlin adaptation of drpys 22261ad; GPL-3.0-only.
package com.hgtv.star.core

import java.io.IOException

object MediaCrypto {
    data class Sample(val start: Long, val end: Long, val iv: ByteArray, val encryptedOffset: Long = 0)
    data class Prepared(val header: ByteArray, val samples: List<Sample>)
    fun deriveKey(value: String): ByteArray {
        val raw=decode64(value); require(raw.size>=3) { "内容密钥格式异常" }
        val length=minOf(raw.size-(raw[0].u() xor raw[1].u() xor raw[2].u())+47,raw.size-1)
        require(length>=33)
        val work=raw.copyOfRange(1,1+length); var a=85; var b=246
        for(i in work.indices) { val old=work[i].u(); val previous=if(i and 1 != 0) a else b; if(i and 1 != 0) a=old else b=old; work[i]=(-21-Integer.bitCount(i)+(previous xor old)).toByte() }
        return hex(String(work.copyOfRange(1,33),Charsets.US_ASCII)).also { require(it.size==16) }
    }
    fun decryptUrl(value: String, seed: ByteArray): String {
        val raw=decode64(value); require(raw.size>=20 && raw[0].u()==0xa8 && raw[2].u()==1 && raw[3].u()==0)
        val constants=hex("4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58")
        val h=digest("SHA-512",digest("SHA-512",seed)+constants)
        val bytes=aes("CBC",h.copyOfRange(0,16),h.copyOfRange(16,32),raw.copyOfRange(4,4+(raw.size-4)/16*16))
        var end=bytes.size; val padding=bytes.last().u(); if(padding in 1..16 && padding<=end) end-=padding
        while(end>0 && bytes[end-1].u()==0) end--
        return String(bytes,0,end,Charsets.UTF_8)
    }
    private data class Box(val start: Int,val end: Int,val body: Int,val type: String)
    private fun box(data: ByteArray, at: Int, limit: Int = data.size): Box? {
        if(at<0 || at+8>limit) return null
        var size=data.u32(at); var header=8
        if(size==1L) { if(at+16>limit) return null; size=data.i64(at+8); header=16 }
        if(size==0L) size=(limit-at).toLong()
        if(size<header || size>limit-at) return null
        return Box(at,at+size.toInt(),at+header,String(data,at+4,4,Charsets.US_ASCII))
    }
    private fun find(data: ByteArray,type: String,start: Int,limit: Int): Box? {
        for(at in start..limit-8) if(data[at+4].toInt()==type[0].code) { val b=box(data,at,limit); if(b?.type==type) return b }
        return null
    }
    fun headerEnd(first: ByteArray,total: Long): Int {
        if(first.size<16 || String(first,4,4,Charsets.US_ASCII)!="ftyp") throw IOException("不支持的视频容器")
        val ftyp=first.u32(0)
        if(ftyp<8 || ftyp+8>first.size || String(first,ftyp.toInt()+4,4,Charsets.US_ASCII)!="moov") throw IOException("视频索引不在文件开头")
        val size=first.u32(ftyp.toInt()); val end=ftyp+size
        if(size<8 || end>total || end>8*1024*1024) throw IOException("视频索引大小异常")
        return end.toInt()
    }
    fun prepare(data: ByteArray,key: ByteArray?): Prepared {
        if(key==null) return Prepared(data.copyOf(),emptyList())
        val moov=box(data,data.u32(0).toInt()) ?: throw IOException("视频索引不完整")
        require(moov.type=="moov")
        // 预分配到底：一次起播要建 n 个样本（实测 4554~22000 个），
        // 不预分配的话 `ArrayList` 会在中途反复扩容 + 拷数组，这段正好在首帧路径上。
        val samples=ArrayList<Sample>(16384); val header=data.copyOf()
        var cursor=moov.body
        while(cursor<moov.end) {
            val track=box(data,cursor,moov.end) ?: throw IOException("视频轨道格式异常"); cursor=track.end
            if(track.type!="trak") continue
            val stbl=find(data,"stbl",track.body,track.end) ?: continue
            val saiz=find(data,"saiz",stbl.body,stbl.end) ?: continue
            val saio=find(data,"saio",stbl.body,stbl.end) ?: throw IOException("视频加密索引缺失")
            val stsz=find(data,"stsz",stbl.body,stbl.end) ?: throw IOException("样本索引缺失")
            val stsc=find(data,"stsc",stbl.body,stbl.end) ?: throw IOException("块索引缺失")
            val chunks=find(data,"stco",stbl.body,stbl.end) ?: find(data,"co64",stbl.body,stbl.end) ?: throw IOException("块偏移缺失")
            fun count(at: Int,limit: Int): Int { if(at+4>limit) throw IOException("索引越界"); return data.u32(at).also { if(it>200000) throw IOException("索引数量过大") }.toInt() }
            val n=count(stsz.body+8,stsz.end); val defaultSize=data.u32(stsz.body+4)
            if(defaultSize==0L && stsz.body+12+n.toLong()*4>stsz.end) throw IOException("样本索引截断")
            val sizes=LongArray(n) { if(defaultSize>0) defaultSize else data.u32(stsz.body+12+it*4) }
            val chunkCount=count(chunks.body+4,chunks.end); val width=if(chunks.type=="co64") 8 else 4
            if(chunks.body+8+chunkCount.toLong()*width>chunks.end) throw IOException("块偏移截断")
            val offsets=LongArray(chunkCount) { if(width==8) data.i64(chunks.body+8+it*width) else data.u32(chunks.body+8+it*width) }
            val entryCount=count(stsc.body+4,stsc.end)
            if(stsc.body+8+entryCount.toLong()*12>stsc.end || entryCount==0) throw IOException("块索引截断")
            val entries=(0 until entryCount).map { data.u32(stsc.body+8+it*12) to data.u32(stsc.body+12+it*12) }
            // ISO BMFF auxiliary-info flags may insert an eight-byte type/parameter pair.
            val sizeBase=saiz.body+4+(if(data.i32(saiz.body) and 1 != 0) 8 else 0)
            if(sizeBase+5>saiz.end) throw IOException("辅助索引截断")
            val auxDefault=data[sizeBase].u(); val auxCount=count(sizeBase+1,saiz.end)
            if(auxCount!=n || (auxDefault==0 && sizeBase+5+n>saiz.end)) throw IOException("辅助索引数量不一致")
            val auxSizes=IntArray(n) { if(auxDefault>0) auxDefault else data[sizeBase+5+it].u() }
            val offsetBase=saio.body+4+(if(data.i32(saio.body) and 1 != 0) 8 else 0)
            val offsetWidth=if(data[saio.body].u()==1) 8 else 4
            if(count(offsetBase,saio.end)!=1 || offsetBase+4+offsetWidth>saio.end) throw IOException("不支持的辅助索引布局")
            var aux=if(offsetWidth==8) data.i64(offsetBase+4) else data.u32(offsetBase+4)
            val auxEnd=aux+auxSizes.sumOf { it.toLong() }
            if(aux<0 || auxEnd>data.size) throw IOException("辅助数据超出索引范围")
            var sampleIndex=0; var entry=0
            for(chunk in offsets.indices) {
                while(entry+1<entries.size && entries[entry+1].first<=chunk+1) entry++
                val count=entries[entry].second
                if(count>n-sampleIndex || entries[entry].first>chunk+1) throw IOException("样本与块数量不一致")
                var position=offsets[chunk]
                repeat(count.toInt()) {
                    val length=sizes[sampleIndex]; val auxSize=auxSizes[sampleIndex]
                    if(position<0 || length<=0 || position>Long.MAX_VALUE-length || auxSize<8) throw IOException("样本索引异常")
                    // IV 一次分配到位。原来是 `copyOfRange(8) + ByteArray(8)`：每样本三次分配
                    // （copyOfRange 的数组、8 字节数组、拼接结果），4554 个样本就是一万三千多次小对象
                    // 分配——这段正好在首帧路径上，2G 盒子上量得出（`video index parse ... took=608ms`）。
                    val iv=ByteArray(16)
                    data.copyInto(iv,0,aux.toInt(),aux.toInt()+8)
                    if(auxSize==8) samples+=Sample(position,position+length,iv)
                    else {
                        if(auxSize<10) throw IOException("不支持的加密样本格式")
                        val subs=(data[aux.toInt()+8].u() shl 8) or data[aux.toInt()+9].u()
                        if(auxSize!=10+subs*6) throw IOException("加密子样本索引异常")
                        var offset=position; var encrypted=0L
                        for(sub in 0 until subs) {
                            val at=aux.toInt()+10+sub*6; val clear=(data[at].u() shl 8) or data[at+1].u(); val size=data.u32(at+2)
                            offset+=clear
                            if(offset+size>position+length) throw IOException("加密子样本越界")
                            if(size>0) samples+=Sample(offset,offset+size,iv,encrypted)
                            encrypted+=size; offset+=size
                        }
                        if(offset!=position+length) throw IOException("加密子样本长度异常")
                    }
                    position+=length; aux+=auxSize; sampleIndex++
                }
            }
            if(sampleIndex!=n) throw IOException("视频样本不完整")
            // Preserve the original codec from frma (AVC and HEVC), not a hardcoded HEVC tag.
            var at=stbl.body
            while(at+8<=stbl.end) {
                val sinf=find(data,"sinf",at,stbl.end) ?: break
                val frma=find(data,"frma",sinf.body,sinf.end) ?: throw IOException("原始编码格式缺失")
                if(frma.body+4>frma.end) throw IOException("原始编码格式无效")
                val original=data.copyOfRange(frma.body,frma.body+4)
                var entryAt=sinf.start-1
                while(entryAt>=stbl.body) {
                    val type=String(data,entryAt,4,Charsets.US_ASCII)
                    if(type=="encv" || type=="enca") { original.copyInto(header,entryAt); break }; entryAt--
                }
                "free".toByteArray().copyInto(header,sinf.start+4); header.fill(0,sinf.body,sinf.end); at=sinf.end
            }
        }
        if(samples.isEmpty()) throw IOException("视频加密索引不可用")
        val sorted=sortedByStart(samples)
        // 重叠检查用下标遍历：`zipWithNext()` 会为每一对现做两个 `Pair`，
        // 两万多个样本就是四万多个对象——起播路径上这点分配在 2G 盒子上是实打实的
        // GC 压力（实测这一段与首帧前的两次 GC 正好重合）。
        for(i in 1 until sorted.size) if(sorted[i-1].end>sorted[i].start) throw IOException("视频样本重叠")
        return Prepared(header,sorted)
    }

    /**
     * 按 `start` 排序样本，**不做装箱**。
     *
     * 早先这里是 `samples.sortedBy { it.start }`：`sortedBy` 走的是
     * `compareBy` + `Int`/`Long` 装箱，两万多个样本意味着两万多次 `Long.valueOf`
     * 与同样多次 `Pair`/比较器调用（见 2026-10-27 的线程栈：`TimSort.mergeHi`
     * 里全是 `Long.valueOf`）。而 mp4 的 `stco` 表本来就是按偏移递增给出的，
     * 绝大多数片子**根本不需要排序**——因此先花一次 O(n) 的无分配扫描判断，
     * 已经有序就原样返回。
     *
     * 真正乱序时退回 `Arrays.sort(Object[], Comparator)`：比较器直接比 `Long` 原始值，
     * 只在对象数组上做 TimSort，不产生任何装箱对象。
     */
    private fun sortedByStart(samples: List<Sample>): List<Sample> {
        if(samples.size<2) return samples
        var ordered=true
        for(i in 1 until samples.size) if(samples[i-1].start>samples[i].start) { ordered=false; break }
        if(ordered) return samples
        val array=samples.toTypedArray()
        java.util.Arrays.sort(array,Comparator { a,b -> a.start.compareTo(b.start) })
        return array.asList()
    }
    fun decrypt(data: ByteArray,start: Long,key: ByteArray?,prepared: Prepared): ByteArray {
        require(start>=0)
        val result=data.copyOf(); val end=start+data.size
        if(start<prepared.header.size) prepared.header.copyInto(result,0,start.toInt(),minOf(end,prepared.header.size.toLong()).toInt())
        if(key==null) return result
        val samples=prepared.samples
        var low=0; var high=samples.size
        while(low<high) { val middle=(low+high) ushr 1; if(samples[middle].end<=start) low=middle+1 else high=middle }
        // 这一块完全没有加密样本（moov 区、或者块尾的明文）：别再走 AES 引擎。
        if(low>=samples.size || samples[low].start>=end) return result
        // 解密：**ECB 出 keystream，自己异或**，不再用 `AES/CTR/NoPadding`。
        //
        // 原因是实测：这台 S905L3B（2G）上 `Cipher.init(CTR, iv)` 单次约 4 ms，
        // 而一集 2 MB 有几百个加密样本，于是**每 2 MB 固定 2 秒**——日志里
        // `video read ... decrypt=2003ms / 7547ms / 2214ms`，折合 1 MB/s，
        // 而且与样本数（不是字节数）成正比，正是逐个 `init` 的账。
        //
        // 换成标准做法：AES-ECB（无 IV，**整集只 init 一次**）批量加密计数器块，
        // 得到的就是 CTR 的 keystream，再异或回数据。每个样本只剩「铺计数器 +
        // 一次 update + 一段异或」，没有 `init`、没有 `IvParameterSpec` 分配。
        //
        // 计数器块 = `iv + (块序号)`，128 位大端自增（见 [fillCounters]）。
        val engine=engine(key)
        for(i in low until samples.size) {
            val sample=samples[i]; if(sample.start>=end) break
            val begin=maxOf(start,sample.start); val stop=minOf(end,sample.end); if(begin>=stop) continue
            val relative=begin-sample.start+sample.encryptedOffset
            var skip=(relative%16).toInt()
            var blockIndex=relative/16
            var from=(begin-start).toInt()
            var length=(stop-begin).toInt()
            while(length>0) {
                // 每块最多吃满缓冲区。**除最后一块外都让 `skip+chunk` 落在 16 的整数倍上**：
                // keystream 是按块生成的，跨块读取必须先对齐，否则下一块的 `skip` 算错。
                var chunk=minOf(length,engine.maxData)
                if(chunk<length) {
                    val misalign=(skip+chunk)%16
                    if(misalign!=0) chunk-=misalign
                    if(chunk<=0) chunk=minOf(length,engine.maxData)
                }
                val blocks=(skip+chunk+15)/16
                fillCounters(engine.counters,blocks,sample.iv,blockIndex)
                engine.cipher.update(engine.counters,0,blocks*16,engine.stream,0)
                val src=engine.stream
                for(j in 0 until chunk) result[from+j]=(result[from+j].toInt() xor src[skip+j].toInt()).toByte()
                from+=chunk; length-=chunk; blockIndex+=blocks.toLong(); skip=0
            }
        }
        return result
    }
    /**
     * 解密引擎：**按线程一份**。
     *
     * `Cipher` 不是线程安全的，而播放读线程与「下一集预热」可能同时在解密，
     * 所以各自持有一个。key 随剧集变化，变了才重新 `init`（ECB 没有 IV，
     * 这一次 init 是整集唯一的一次）。
     *
     * 两个缓冲区按线程复用，避免每次读 2 MB 就现开 4 MB 临时内存。
     */
    private class Engine {
        val cipher: javax.crypto.Cipher = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding")
        var keyBytes: ByteArray?=null
        val counters: ByteArray=ByteArray(CAPACITY)
        val stream: ByteArray=ByteArray(CAPACITY)
        /** 一次能解多少明文字节（留足首块空转的 15 字节与对齐余量）。 */
        val maxData: Int=CAPACITY-32
        companion object { const val CAPACITY=128*1024 }
    }
    private val engineLocal=ThreadLocal<Engine>()
    private fun engine(key: ByteArray): Engine {
        val cached=engineLocal.get() ?: Engine().also { engineLocal.set(it) }
        val known=cached.keyBytes
        if(known==null || !known.contentEquals(key)) {
            cached.cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,javax.crypto.spec.SecretKeySpec(key,"AES"))
            cached.keyBytes=key.copyOf()
        }
        return cached
    }
    /**
     * 铺计数器块：第 k 块 = `iv + (base + k)`，128 位大端、溢出截断。
     *
     * 早先这里每个样本都现建两个 `BigInteger`（`BigInteger(1,iv)` 还要先解析一遍字节），
     * 再 `and` 一个 128 位全 1 掩码。一集两万多个加密样本 = 六万多次大整数运算，
     * 实测与起播前那两次 GC 完全重合（2026-10-27 现场）。现在纯字节加法，零分配。
     */
    private fun fillCounters(dst: ByteArray,blocks: Int,iv: ByteArray,base: Long) {
        var at=0
        for(k in 0 until blocks) {
            System.arraycopy(iv,0,dst,at,16)
            var add=base+k; var i=at+15
            while(add!=0L && i>=at) {
                val sum=(dst[i].toInt() and 0xFF)+(add and 0xFF).toInt()
                dst[i]=sum.toByte()
                add=(add ushr 8)+(sum ushr 8).toLong()
                i--
            }
            at+=16
        }
    }
}
