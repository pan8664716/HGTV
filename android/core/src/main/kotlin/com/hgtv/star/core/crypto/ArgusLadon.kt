// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * X-Argus / X-Ladon 容器。
 *
 * 两者共享同一次请求的 11 字节 nonce；只有真实容器能通过新注册设备的校验，
 * 4 字节时间戳占位只对已受信任设备“看起来可用”。
 */
internal object ArgusLadon {
    data class Headers(val argus: String, val ladon: String)

    private val key = hex(
        "ac1adaae95a7af94a5114ab3b3a97dd8" +
            "0050aa0a39314c40528caec95256c28c"
    )
    private const val ROUND_CONST = 0x3DC94C3A046D678BUL
    private val random = SecureRandom()
    private val rounds by lazy { buildRounds() }

    fun sign(query: String, body: ByteArray?, ts: Int, device: Map<String, String>): Headers {
        val nonce = ByteArray(11).also(random::nextBytes)
        return Headers(
            argus = argus(query, body, ts, device, nonce),
            ladon = ladon(deviceValue(device, "aid", "8662"), ts, nonce.copyOfRange(2, 6))
        )
    }

    private fun argus(
        query: String,
        body: ByteArray?,
        ts: Int,
        device: Map<String, String>,
        nonce: ByteArray
    ): String {
        val bodyHash = if (body == null || body.isEmpty()) sm3(ByteArray(16)) else sm3(digest("MD5", body))
        val queryHash = sm3(query.toByteArray())
        val counters = pbVarint(1, 1) + pbVarint(2, 1) + pbVarint(3, 1) + pbVarint(7, 3348294860L)
        val env = pbBytes(1, deviceValue(device, "device_type", "25053RT47C").toByteArray()) +
            pbBytes(2, deviceValue(device, "os_version", "16").toByteArray()) +
            pbBytes(3, deviceValue(device, "channel", "update_64").toByteArray()) +
            pbVarint(4, 2147483648L)
        val nonceValue = ByteBuffer.wrap(nonce, 6, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0x7fffffffL
        val message = pbVarint(1, 0x20200929L * 2) +
            pbVarint(2, 2) +
            pbVarint(3, nonceValue) +
            pbBytes(4, "8662".toByteArray()) +
            pbBytes(5, deviceValue(device, "device_id", VendorConstants.device.getValue("device_id")).toByteArray()) +
            pbBytes(6, "1611921764".toByteArray()) +
            pbBytes(7, deviceValue(device, "version_name", VendorConstants.device.getValue("version_name")).toByteArray()) +
            pbBytes(8, "v04.07.01-ml-android".toByteArray()) +
            pbVarint(9, 135135744) +
            pbBytes(10, ByteArray(8)) +
            pbVarint(11, 0) +
            pbVarint(12, ts.toLong() * 2) +
            pbBytes(13, bodyHash.copyOf(6)) +
            pbBytes(14, queryHash.copyOf(6)) +
            pbBytes(15, counters) +
            pbBytes(16, ByteArray(0)) +
            pbBytes(20, "none".toByteArray()) +
            pbVarint(21, 738) +
            pbBytes(23, env) +
            pbVarint(25, 2)
        val stream = argusKeyStream(pad16(message))
        val mask = hex("d04ffdffd04ffdff")
        for (i in stream.indices) stream[i] = (stream[i].u() xor mask[i % mask.size].u()).toByte()
        val payload = (mask + stream).reversedArray()
        val container = hex("a6e783ee7001100918") + payload + hex("567b")
        val encrypted = Cipher.getInstance("AES/CBC/NoPadding").run {
            init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(digest("MD5", key.copyOfRange(0, 16)), "AES"),
                IvParameterSpec(digest("MD5", key.copyOfRange(16, 32)))
            )
            doFinal(pad16(container))
        }
        return (hex("3ccc") + encrypted).base64()
    }

    private fun ladon(aid: String, ts: Int, prefix: ByteArray): String {
        val digest = digest("MD5", prefix + aid.toByteArray()).hex().toByteArray()
        var left = ByteBuffer.wrap(digest, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
        var right = ByteBuffer.wrap(digest, 8, 8).order(ByteOrder.LITTLE_ENDIAN).long
        val queue = ArrayDeque<Long>().apply {
            add(ByteBuffer.wrap(digest, 16, 8).order(ByteOrder.LITTLE_ENDIAN).long)
            add(ByteBuffer.wrap(digest, 24, 8).order(ByteOrder.LITTLE_ENDIAN).long)
        }
        val keys = LongArray(34)
        for (i in keys.indices) {
            keys[i] = left
            val mixed = (java.lang.Long.rotateRight(right, 8) + left) xor i.toLong()
            queue.addLast(mixed)
            left = mixed xor java.lang.Long.rotateLeft(left, 3)
            right = queue.removeFirst()
        }
        val out = pad16("$ts-1611921764-$aid".toByteArray())
        val buffer = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (offset in out.indices step 16) {
            var lv = buffer.getLong(offset)
            var rv = buffer.getLong(offset + 8)
            for (roundKey in keys) {
                rv = roundKey xor (lv + java.lang.Long.rotateRight(rv, 8))
                lv = rv xor java.lang.Long.rotateLeft(lv, 3)
            }
            buffer.putLong(offset, lv)
            buffer.putLong(offset + 8, rv)
        }
        return (prefix + out).base64()
    }

    private fun argusKeyStream(data: ByteArray): ByteArray {
        val out = data.copyOf()
        for (offset in out.indices step 16) {
            var left = ByteBuffer.wrap(out, offset, 8).order(ByteOrder.LITTLE_ENDIAN).long
            var right = ByteBuffer.wrap(out, offset + 8, 8).order(ByteOrder.LITTLE_ENDIAN).long
            for (roundKey in rounds) {
                val nextLeft = right
                val nextRight = left xor
                    (java.lang.Long.rotateLeft(right, 1) and java.lang.Long.rotateLeft(right, 8)) xor
                    java.lang.Long.rotateLeft(right, 2) xor
                    roundKey
                left = nextLeft
                right = nextRight
            }
            val block = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            block.putLong(left)
            block.putLong(right)
            System.arraycopy(block.array(), 0, out, offset, 16)
        }
        return out
    }

    private fun buildRounds(): LongArray {
        val derived = sm3(key + hex("3ccc567b") + key)
        val result = LongArray(72)
        for (i in 0 until 4) {
            result[i] = ByteBuffer.wrap(derived, i * 8, 8).order(ByteOrder.LITTLE_ENDIAN).long
        }
        for (i in 4 until result.size) {
            val mixed = java.lang.Long.rotateRight(result[i - 1], 3) xor result[i - 3]
            val mixed2 = mixed xor java.lang.Long.rotateRight(mixed, 1)
            val bit = ((ROUND_CONST shr ((i - 4) % 62)) and 1uL).toLong()
            result[i] = result[i - 4].inv() xor mixed2 xor bit xor 3L
        }
        return result
    }

    private fun pad16(data: ByteArray): ByteArray {
        val padding = 16 - data.size % 16
        return data + ByteArray(padding) { padding.toByte() }
    }

    private fun pbVarint(field: Int, value: Long): ByteArray =
        varint(field.toLong() shl 3) + varint(value)

    private fun pbBytes(field: Int, value: ByteArray): ByteArray =
        varint((field.toLong() shl 3) or 2) + varint(value.size.toLong()) + value

    private fun varint(value: Long): ByteArray {
        var n = value
        return ByteArrayOutputStream().apply {
            while (n < 0 || n >= 128) {
                write(((n and 127) or 128).toInt())
                n = n ushr 7
            }
            write(n.toInt())
        }.toByteArray()
    }

    private fun deviceValue(device: Map<String, String>, key: String, fallback: String): String =
        device[key]?.takeIf { it.isNotBlank() } ?: fallback
}
