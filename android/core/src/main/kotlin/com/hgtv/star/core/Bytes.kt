// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.digests.SM3Digest

internal fun Byte.u() = toInt() and 255
internal fun hex(value: String): ByteArray {
    require(value.length % 2 == 0)
    return ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
internal fun ByteArray.hex() = joinToString("") { "%02x".format(it.u()) }

/**
 * Base64 的本地实现。
 *
 * Android 4.4（API 19）没有 `java.util.Base64`（那是 API 26 才有的），而 `core` 是纯 JVM
 * 模块、不能依赖 `android.util.Base64`。这里只实现平台真正用到的两种形态：
 * 标准字母表 + `+/`，以及 URL 安全字母表 `-_`；解码时两种都收，末尾的 `=` 可省。
 */
private const val BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

internal fun decode64(s: String): ByteArray {
    val out = ByteArrayOutputStream(s.length * 3 / 4)
    var buffer = 0
    var bits = 0
    for (raw in s) {
        if (raw == '=') break
        val c = when (raw) { '-' -> '+'; '_' -> '/'; else -> raw }
        if (c.isWhitespace()) continue
        val value = BASE64.indexOf(c)
        if (value < 0) continue
        buffer = (buffer shl 6) or value
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out.write((buffer ushr bits) and 255)
        }
    }
    return out.toByteArray()
}

internal fun ByteArray.base64(): String {
    val out = StringBuilder((size + 2) / 3 * 4)
    var index = 0
    while (index < size) {
        val b0 = this[index].u()
        val b1 = if (index + 1 < size) this[index + 1].u() else 0
        val b2 = if (index + 2 < size) this[index + 2].u() else 0
        out.append(BASE64[b0 ushr 2])
        out.append(BASE64[((b0 and 3) shl 4) or (b1 ushr 4)])
        if (index + 1 < size) out.append(BASE64[((b1 and 15) shl 2) or (b2 ushr 6)]) else out.append('=')
        if (index + 2 < size) out.append(BASE64[b2 and 63]) else out.append('=')
        index += 3
    }
    return out.toString()
}

internal fun le32(n: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array()
internal fun be32(n: Int) = ByteBuffer.allocate(4).putInt(n).array()
internal fun le64(n: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(n).array()
internal fun ByteArray.i32(at: Int, little: Boolean = false) = ByteBuffer.wrap(this, at, 4).order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN).int
internal fun ByteArray.u32(at: Int) = i32(at).toLong() and 0xffffffffL
internal fun ByteArray.i64(at: Int, little: Boolean = false) = ByteBuffer.wrap(this, at, 8).order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN).long
internal fun digest(algorithm: String, value: ByteArray): ByteArray = MessageDigest.getInstance(algorithm).digest(value)
internal fun sm3(value: ByteArray): ByteArray = SM3Digest().let { d -> d.update(value, 0, value.size); ByteArray(32).also { d.doFinal(it, 0) } }
internal fun aes(mode: String, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray = Cipher.getInstance("AES/$mode/NoPadding").run {
    init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)); doFinal(data)
}
internal fun xor(a: ByteArray, b: ByteArray) = ByteArray(a.size) { (a[it].u() xor b[it % b.size].u()).toByte() }
internal fun rol(v: Int, n: Int) = Integer.rotateLeft(v, n)
internal fun ror(v: Int, n: Int) = Integer.rotateRight(v, n)
