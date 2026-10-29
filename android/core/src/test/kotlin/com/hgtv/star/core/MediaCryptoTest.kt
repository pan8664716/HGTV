package com.hgtv.star.core

import org.junit.Assert.*
import org.junit.Test

class MediaCryptoTest {
    private val key=hex("2b7e151628aed2a6abf7158809cf4f3c")
    private val iv=hex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff")
    private val plain=hex("6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710")
    private val encrypted=hex("874d6191b620e3261bef6864990db6ce9806f66b7970fdff8617187bb9fffdff5ae4df3edbd5d35e5b4f09020db03eab1e031dda2fbe03d1792170a0f3009cee")
    @Test fun nistCtrVectorsAcrossEveryUnalignedRange() {
        val prefix=ByteArray(23){0x66}; val source=prefix+encrypted+ByteArray(12){0x77}
        val expected=prefix+plain+ByteArray(12){0x77}
        val prepared=MediaCrypto.Prepared(byteArrayOf(),listOf(MediaCrypto.Sample(23,87,iv)))
        for(start in source.indices) for(length in 1..minOf(29,source.size-start)) assertArrayEquals("$start+$length",expected.copyOfRange(start,start+length),MediaCrypto.decrypt(source.copyOfRange(start,start+length),start.toLong(),key,prepared))
    }
    @Test fun subsamplesPreserveClearBytesAndContinueCtrOffset() {
        val source=byteArrayOf(1,2,3)+encrypted.copyOfRange(0,21)+byteArrayOf(4,5)+encrypted.copyOfRange(21,64)
        val expected=byteArrayOf(1,2,3)+plain.copyOfRange(0,21)+byteArrayOf(4,5)+plain.copyOfRange(21,64)
        val prepared=MediaCrypto.Prepared(byteArrayOf(),listOf(MediaCrypto.Sample(3,24,iv),MediaCrypto.Sample(26,69,iv,21)))
        assertArrayEquals(expected,MediaCrypto.decrypt(source,0,key,prepared))
        assertArrayEquals(expected.copyOfRange(22,47),MediaCrypto.decrypt(source.copyOfRange(22,47),22,key,prepared))
    }
    /**
     * 跨缓冲区的大块：大于 128 KB 的样本会被切成多段（见 `MediaCrypto.Engine.maxData`），
     * 段与段之间必须正好落在 16 字节的计数器块边界上，否则 keystream 会错位。
     *
     * 这里拿 JDK 自带的 `AES/CTR` 当参考答案，逐字节比对；顺带覆盖「起始位置不在块首」。
     */
    @Test fun largeSampleMatchesReferenceCtrAcrossChunkBoundaries() {
        val size=300_000
        val cipherText=ByteArray(size).also(java.util.Random(7)::nextBytes)
        // 注意：**不要**写成 `Cipher.apply { init(..., IvParameterSpec(iv)) }`。
        // `apply` 的接收者是 `Cipher`，而它有 `getIV()`，Kotlin 会把这个**合成属性**
        // 顶掉外层的 `iv` 字段——拿到的是「还没 init 所以是 null」的 IV，
        // 报出来的就是 `IvParameterSpec: iv is null`。这里显式取本类的字段。
        val ivBytes=this.iv
        val reference=javax.crypto.Cipher.getInstance("AES/CTR/NoPadding").also {
            it.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(key,"AES"),
                javax.crypto.spec.IvParameterSpec(ivBytes)
            )
        }.doFinal(cipherText)
        val prepared=MediaCrypto.Prepared(byteArrayOf(),listOf(MediaCrypto.Sample(0,size.toLong(),iv)))
        assertArrayEquals(reference,MediaCrypto.decrypt(cipherText.copyOf(),0,key,prepared))
        // 起始位置 5 字节（不在块首）+ 长度跨段：解密结果应与参考实现的同一段一致。
        val offset=5
        assertArrayEquals(
            reference.copyOfRange(offset,size),
            MediaCrypto.decrypt(cipherText.copyOfRange(offset,size),offset.toLong(),key,prepared)
        )
    }
    private fun box(type: String,body: ByteArray)=be32(body.size+8)+type.toByteArray()+body
    private fun fixture(codec: String="avc1",auxSize: Int=8): ByteArray {
        val ftyp=box("ftyp","isom0000".toByteArray())
        fun moov(offset: Int): ByteArray {
            val entry=box("encv",ByteArray(78)+box("sinf",box("frma",codec.toByteArray())))
            val stsd=box("stsd",ByteArray(4)+be32(1)+entry)
            val stsz=box("stsz",ByteArray(4)+be32(16)+be32(1))
            val stsc=box("stsc",ByteArray(4)+be32(1)+be32(1)+be32(1)+be32(1))
            val stco=box("stco",ByteArray(4)+be32(1)+be32(4096))
            val saiz=box("saiz",ByteArray(4)+byteArrayOf(auxSize.toByte())+be32(1))
            val saio=box("saio",ByteArray(4)+be32(1)+be32(offset))
            return box("moov",box("trak",box("mdia",box("minf",box("stbl",stsd+stsz+stsc+stco+saiz+saio))))+box("free",iv.copyOf(8)))
        }
        val initial=ftyp+moov(0)
        return ftyp+moov(initial.size-8)
    }
    @Test fun headerRestoresOriginalAvcCodecAndSampleIndex() {
        val data=fixture(); val prepared=MediaCrypto.prepare(data,key)
        assertTrue(String(prepared.header).contains("avc1")); assertFalse(String(prepared.header).contains("encv")); assertFalse(String(prepared.header).contains("sinf"))
        assertEquals(1,prepared.samples.size); assertEquals(4096L,prepared.samples[0].start); assertEquals(4112L,prepared.samples[0].end)
        assertEquals(data.size,MediaCrypto.headerEnd(data,5000))
    }
    @Test(expected=java.io.IOException::class) fun truncatedAuxiliaryDataFailsClosed() { MediaCrypto.prepare(fixture(auxSize=16),key) }
    @Test(expected=java.io.IOException::class) fun oversizedMoovRejected() { MediaCrypto.headerEnd(box("ftyp","isom0000".toByteArray())+be32(9000000)+"moov".toByteArray(),10000000) }
}
