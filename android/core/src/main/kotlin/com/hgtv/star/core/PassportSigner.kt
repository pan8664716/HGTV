// Derived from github.com/aston314/hongguo-tv (core/cmd/hongguotv/passport_sign.go); GPL-3.0-only.
package com.hgtv.star.core

import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Passport（账号体系）的请求签名，**只产 branch 2**。
 *
 * ## 为什么单独一份
 *
 * [Signer] 只产 **branch 0**（`(iv & 15) % 3 == 0`），那是内容 / 播放接口在用的分支；
 * Passport 的 `/passport/mobile/send_code/v1/`、`/passport/mobile/sms_login/` 这条线
 * 走的是 **branch 2**（`(iv & 15) % 3 == 2`），F13 用的是另一套算法
 * （5 组 seed + 64 轮 order 表 + 独立的 64 个常量表），与 branch 0 的 hashF13 完全不通用。
 *
 * 实测：用 branch 0 去打 Passport，一律回 `error_code=16 该应用无权限`；
 * 换 branch 2 之后 `send_code` 回 `message=success`、`sms_login` 直接返回会话。
 * 因此这里的表与算法照参考实现逐行移植，**不要与 [Signer] 的那套混用**。
 *
 * ## 分支是靠试出来的
 *
 * `_rticket` 从当前毫秒起逐个 +1，直到算出的分支号为 2（最多试 256 次），
 * 与官方客户端的 `TuneBranch` 同一思路。
 */
internal class PassportSigner(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: () -> Double = Math::random,
    /** 设备档案：`x-medusa` 里的机型 / 系统版本要跟着它走（见 [Signer] 的同名字段）。 */
    private var device: Map<String, String> = emptyMap()
) {
    fun updateDevice(values: Map<String, String>) { device = values.filterValues { it.isNotBlank() } }

    private fun deviceValue(key: String, fallback: String): String =
        device[key]?.takeIf { it.isNotBlank() } ?: fallback
    private fun deviceValueOf(key: String, fallback: () -> String): String =
        device[key]?.takeIf { it.isNotBlank() } ?: fallback()
    private fun deviceId() = deviceValueOf("device_id") { VendorConstants.device.getValue("device_id") }
    private fun deviceType() = deviceValue("device_type", "25053RT47C")
    private fun deviceBrand() = deviceValue("device_brand", "Redmi")

    /** 一次签名请求：完整 URL + 该带的头。 */
    data class Request(val url: String, val headers: Map<String, String>, val body: ByteArray?)

    private fun rand(): Int = (random() * 4294967296.0).toLong().toInt()
    private fun u32(v: Int) = v.toLong() and 0xffffffffL
    private fun le32(n: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array()

    // ------------------------------------------------------------------ 基础原语

    /** `passportIV`：逐字节的移位 / 异或混合，branch 0 与 branch 2 共用。 */
    private fun passportIv(data: ByteArray, initial: Int): Int {
        var iv = initial
        for (i in data.indices) {
            iv = if (i and 1 == 0) (iv ushr 4) xor iv xor (iv shl 6) xor data[i].u()
                 else ((iv ushr 7) xor iv xor (data[i].u() or (iv shl 12))).inv()
        }
        return iv
    }

    /** 分支号与两段摘要：`iv` 由 query 的 SM3 与 body 的 MD5 叠出来。 */
    private fun branchOf(query: String, body: ByteArray?, ts: Int): Pair<Int, ByteArray> {
        val q = sm3(query.toByteArray())
        val m = if (body != null && body.isNotEmpty()) digest("MD5", body) else ByteArray(16)
        val iv = passportIv(le32(ts), passportIv(m, passportIv(q, 0x20230928)))
        return iv to q
    }

    /** body 的 MD5；没有 body 时是全零 16 字节（与参考实现一致）。 */
    private fun bodyDigest(body: ByteArray?): ByteArray =
        if (body != null && body.isNotEmpty()) digest("MD5", body) else ByteArray(16)

    // ------------------------------------------------------------------ F13（branch 2）

    /**
     * F13：branch 2 专用的 20 字节摘要。
     *
     * 与 branch 0 的 `Signer.hashF13` 是两套完全不同的算法：这里用
     * `seeds[group] + ts` 做轮数计数，消息体固定 64 字节（`q[32] + m[16] + ts[4] + 8 + 4`），
     * 每轮取哪个字由 [PASSPORT_ORDERS] 决定，常量表是 [PASSPORT_MD5_CONSTANTS]。
     */
    private fun f13(iv: Int, ts: Int, q: ByteArray, m: ByteArray): ByteArray {
        val group = ((iv and 15) - 2) / 3
        val seeds = intArrayOf(0x8980f29b.toInt(), 0xeb549c7f.toInt(), 0xb08726db.toInt(), 0xd40cb5e6.toInt(), 0xe8f559e4.toInt())
        val count = seeds[group] + ts
        val shift = (count + 5) and 7
        val msg = ByteArray(64)
        System.arraycopy(q, 0, msg, 0, 32)
        System.arraycopy(m, 0, msg, 32, 16)
        System.arraycopy(le32(ts), 0, msg, 48, 4)
        hex("8496779dd4150bf8").forEachIndexed { i, x ->
            msg[52 + i] = (((x.u() * 257) ushr shift) and 255).toByte()
        }
        msg[60] = 0xa0.toByte(); msg[61] = 1; msg[62] = 0; msg[63] = 0
        val start = intArrayOf(0x79e0f2fb, 0xc8b52570.toInt(), 0xebc2f8cd.toInt(), 0x7c104d93)
        val end = intArrayOf(0x19be4866, 0xe85986b4.toInt(), 0xe19b326e.toInt(), 0x71d1d7d4)
        for (i in start.indices) {
            start[i] = Integer.rotateRight(start[i], count and 31)
            end[i] = Integer.rotateRight(end[i], (count + 6) and 31)
        }
        var a = start[0]; var b = start[1]; var c = start[2]; var d = start[3]
        val shifts = arrayOf(intArrayOf(7,12,17,22), intArrayOf(5,9,14,20), intArrayOf(4,11,16,23), intArrayOf(6,10,15,21))
        for (i in 0 until 64) {
            val f = when (i / 16) {
                0 -> (b and c) or (b.inv() and d)
                1 -> (b and d) or (c and d.inv())
                2 -> b xor c xor d
                else -> c xor (b or d.inv())
            }
            val at = PASSPORT_ORDERS[group * 64 + i] * 4
            val word = ByteBuffer.wrap(msg, at, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val constant = Integer.rotateRight(PASSPORT_MD5_CONSTANTS[i], (count + 1) and 31)
            val x = b + Integer.rotateLeft(a + f + word + constant, shifts[i / 16][i % 4])
            a = d; d = c; c = b; b = x
        }
        val out = ByteArray(20)
        intArrayOf(a, b, c, d).forEachIndexed { i, x ->
            System.arraycopy(le32((start[i] + x) xor end[i]), 0, out, i * 4, 4)
        }
        var check = 0x20220420
        for (i in 0 until 12) {
            val x = out[i].u()
            check = if (i and 1 == 0) ((check ushr 3) xor check) xor x xor (check shl 7)
                    else ((check ushr 5) xor check) xor (x or (check shl 11)).inv()
        }
        System.arraycopy(le32((check or 4) xor 0x1000000), 0, out, 16, 4)
        return out
    }

    // ------------------------------------------------------------------ 各个头

    /**
     * X-Helios：34 轮表驱动的伪随机流，明文是 `"{ts}-1588093228-8662"`。
     *
     * 密钥由 `MD5(le32(nonce) + "8662")` 的十六进制串前 32 字节切成 4 个 64 位字，
     * 之后每轮 `x = (rotateRight(pending0,8) + k) ^ i`、`k = x ^ rotateLeft64(k,3)`。
     */
    private fun helios(ts: Int, nonce: Int): String {
        val ascii = digest("MD5", le32(nonce) + "8662".toByteArray()).hex().toByteArray()
        val words = (0..3).map { ascii.i64(it * 8, true) }.toMutableList()
        val table = mutableListOf(words[0])
        var b0 = words.removeAt(0)
        var b8 = words.removeAt(0)
        for (i in 0 until 34) {
            var x8 = (java.lang.Long.rotateRight(b8, 8) + b0) xor i.toLong()
            words.add(x8)
            x8 = x8 xor java.lang.Long.rotateRight(b0, 61)
            table.add(x8)
            b0 = x8; b8 = words.removeAt(0)
        }
        val text = "$ts-1588093228-8662".toByteArray()
        val padding = 16 - text.size % 16
        val data = text + ByteArray(padding) { padding.toByte() }
        var output = le32(nonce)
        for (at in data.indices step 16) {
            var a = data.i64(at, true); var b = data.i64(at + 8, true)
            for (i in 0 until 34) {
                b = table[i] xor (a + java.lang.Long.rotateRight(b, 8))
                a = b xor java.lang.Long.rotateRight(a, 61)
            }
            output += le64(a) + le64(b)
        }
        return output.base64()
    }

    /**
     * X-Gorgon：modified RC4 + 半字节交换 + 按位反转。
     *
     * 输入是 `MD5(query)[0:4] + MD5(body)[0:4] + 0 + 67503104 + ts`，key 含随机数。
     * 与 [Signer.gorgon] 同一套算法（两边的常量与 key 逐字节一致，已对照确认）。
     */
    private fun gorgon(query: String, body: ByteArray?, ts: Int, nonce: Int): String {
        val input = digest("MD5", query.toByteArray()).copyOf(4) +
            (if (body != null && body.isNotEmpty()) digest("MD5", body).copyOf(4) else ByteArray(4)) +
            ByteArray(4) + le32(67503104) + be32(ts)
        val key = intArrayOf(0x4a, 0x40, 0x16, (nonce shr 8) and 255, 0x47, 0x6c, 1, nonce and 255)
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0..255) { j = (j + s[i] + key[i % 8]) and 255; s[i] = s[j] }
        j = 0
        val out = ByteArray(input.size) { n ->
            val i = n + 1
            j += s[i]
            val y = s[j and 255]
            s[i] = y
            (input[n].u() xor s[(y + y) and 255]).toByte()
        }
        for (i in out.indices) {
            val value = ((out[i].u() shr 4) or (out[i].u() shl 4)) and 255
            val next = out[if (i + 1 < out.size) i + 1 else 0].u()
            val reversed = Integer.reverse((next xor value) and 255) ushr 24
            out[i] = (reversed xor 20).inv().toByte()
        }
        return (hex("8404") + byteArrayOf(nonce.toByte(), (nonce shr 8).toByte(), 0x40, 1) + out).hex()
    }

    // ------------------------------------------------------------------ protobuf 与 XMXOR

    private fun varint(value: Long): ByteArray {
        var n = value
        return java.io.ByteArrayOutputStream().apply {
            while (n > 127) { write(((n and 127) or 128).toInt()); n = n ushr 7 }
            write(n.toInt())
        }.toByteArray()
    }

    private data class Field(val tag: Int, val value: Any, val type: String = "string")

    private fun sint(tag: Int, value: Long) = Field(tag, value, "sint")
    private fun float(tag: Int, value: Float) = Field(tag, value, "float")
    private fun text(tag: Int, value: String) = Field(tag, value, "string")
    private fun blob(tag: Int, value: ByteArray) = Field(tag, value, "string")

    private fun proto(vararg fields: Field): ByteArray = java.io.ByteArrayOutputStream().apply {
        for ((tag, value, type) in fields) {
            when (type) {
                "sint" -> {
                    val n = (value as Number).toLong()
                    write(varint((tag.toLong() shl 3)))
                    write(varint((n shl 1) xor (n shr 63)))
                }
                "float" -> {
                    write(varint(((tag.toLong() shl 3) or 5)))
                    write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat((value as Number).toFloat()).array())
                }
                else -> {
                    val bytes = if (value is ByteArray) value else value.toString().toByteArray()
                    if (bytes.isNotEmpty()) {
                        write(varint(((tag.toLong() shl 3) or 2)))
                        write(varint(bytes.size.toLong()))
                        write(bytes)
                    }
                }
            }
        }
    }.toByteArray()

    /** `x-medusa` 里的设备 protobuf（字段号与取值照参考实现）。 */
    private fun deviceProto(): ByteArray {
        val fields = mutableListOf<Field>()
        fields += sint(1, 1); fields += sint(2, 2)
        fields += text(3, "8662"); fields += text(4, deviceId())
        fields += text(5, "Ai6svO3PyrwDOUSmO6ZcResxu"); fields += text(6, "!noperm!")
        fields += sint(7, -888888); fields += sint(8, -888888); fields += sint(9, 3); fields += sint(10, -888888)
        fields += text(11, "!notset!"); fields += text(12, "Asia/Shanghai,8"); fields += text(13, "zh_CN")
        fields += sint(14, 4)
        fields += float(16, 255.24993896484375f); fields += float(17, 35.58599090576172f)
        fields += float(18, 3.467449188232422f); fields += float(19, 3.467449188232422f)
        fields += float(20, 255.1754913330078f); fields += float(21, 42.17544174194336f)
        fields += text(22, deviceValue("os_version", "16")); fields += sint(23, 41)
        fields += sint(24, (deviceValueOf("os_api") { "36" }).toIntOrNull()?.toLong() ?: 36L)
        fields += sint(25, 1728388016635L); fields += sint(26, 1728388016635L)
        fields += sint(27, 1728388016635L); fields += sint(28, 1728388016637L)
        fields += sint(29, -1)
        // 30..36：机型 / 品牌交替，最后三格是品牌（参考实现里 34 也是品牌名）。
        fields += text(30, deviceType()); fields += text(31, deviceBrand()); fields += text(32, deviceType())
        fields += text(33, deviceType()); fields += text(34, deviceBrand()); fields += text(35, deviceBrand())
        fields += text(36, deviceBrand())
        fields += sint(38, 31)
        return proto(*fields.toTypedArray())
    }

    private fun medusaMessage(query: String, ts: Int, nonce: Int, hashRand: Int, q: ByteArray, m: ByteArray, f13: ByteArray): ByteArray {
        val device = deviceProto()
        val report = proto(sint(1, ts.toLong()), sint(2, -2), sint(4, 200))
        val env = proto(
            sint(1, ((random() * 21).toInt() + 100).toLong()),
            sint(2, 146331399), sint(3, 146331396), sint(5, 7),
            text(6, "v04.06.04.03-bugfix"),
            sint(7, ((random() * 2000).toInt() + 10001).toLong()),
            blob(12, device), blob(13, report),
            text(14, deviceValueOf("version_name") { VendorConstants.device.getValue("version_name") })
        )
        val count = proto(sint(1, 111), sint(2, 10), sint(3, 694367), sint(5, 586952199))
        val queryHash = sm3(query.toByteArray() + m + "none".toByteArray())
        return proto(
            blob(1, hex("f7e85ffad7d7dc3bd62ac87057cf6118")),
            sint(2, 3), sint(3, nonce.toLong()),
            text(4, "8662"), text(5, deviceId()), text(6, "1588093228"),
            text(7, deviceValueOf("version_name") { VendorConstants.device.getValue("version_name") }),
            text(8, "v04.06.04-ml-android"),
            sint(9, 67503104), blob(10, hex("4001000000000000")),
            sint(12, ts.toLong()), blob(13, f13), blob(14, q.copyOf(6)), blob(15, count),
            text(16, "AXYQOS6n2m60x1fVZHIrH3iol"), sint(17, ts.toLong()),
            blob(19, queryHash), text(20, "none"), sint(21, 312),
            blob(23, env),
            text(24, """{"cmr":16777216,"cmr2":16777216,"un_h":1879194040,"vpn":0,"kd":0,"fkd":3672518972,"pd":-1872573247,"dyn":"","do":0,"tk":true}""")
        )
    }

    /** XMXOR：按 key 的 4 字节轮转做逐字节变换，再对尾部做校验修正。 */
    private fun xmxor(data: ByteArray, key: ByteArray): ByteArray {
        val encoded = IntArray(data.size)
        for (i in data.indices) {
            val at = (i * 4) and 28
            val d0 = key[at].u(); val d1 = key[at + 1].u(); val v = data[i].u()
            var d2 = ((((v shl 4) or (v ushr 4)) and 255) + d0) and 255
            d2 = (d2.inv() xor d1) and 255
            d2 = ((((d2 and 255) shl 3) or ((d2 and 255) ushr 5)) and 255)
            d2 = (d2 + d1) and 255
            d2 = (d2 xor d0) and 255
            encoded[data.size - i - 1] = d2.inv() and 255
        }
        val last = encoded.last() xor encoded[encoded.size - 2]
        val first = encoded[0]
        encoded[0] = (last.inv() + first) and 255
        encoded[1] = ((encoded[0] xor encoded.last() xor 254) + encoded[1]) and 255
        encoded[2] = (encoded[2] + ((last - first) xor (((encoded[1] shl 3) or (encoded[1] ushr 5)) and 255) xor 2)) and 255
        for (i in 0 until encoded.size - 4) {
            val temp = ((((encoded[i + 2] shl 3) or (encoded[i + 2] ushr 5)) and 255) xor encoded[i + 1] xor (i + 3)) and 255
            encoded[i + 3] = (temp.inv() + encoded[i + 3]) and 255
        }
        encoded[encoded.size - 1] = encoded.last() xor encoded[encoded.size - 2]
        encoded[0] = ((encoded[0] xor encoded[1]) + encoded.drop(1).sum()) and 255
        return encoded.map { it.toByte() }.toByteArray()
    }

    /** X-Medusa：protobuf 环境信息 + XMXOR + AesV3，外层是「20 字节版本前缀 + 4 字节随机尾」。 */
    private fun medusa(query: String, ts: Int, nonce: Int, hashRand: Int, xmRand: Int, q: ByteArray, m: ByteArray): String {
        val f13 = f13(passportIv(le32(ts), passportIv(m, passportIv(q, 0x20230928))), ts, q, m)
        val message = medusaMessage(query, ts, nonce, hashRand, q, m, f13)
        val signKey = hex("8ebdfa3806ecc5cee79423e6029ed82540bc2218bb7eaef71cb691f7aa8aa2f5")
        val key = sm3(signKey + le32(hashRand) + signKey)
        val d1 = (hashRand shr 16) and 255
        val seed = le32((((d1 shl 11) or (hashRand ushr 24)) xor (d1 shr 5) xor d1).inv())
        var transformed = (hex("4001000000000000") + xmxor(message, key)).reversedArray()
        for (i in transformed.indices) transformed[i] = (transformed[i].u() xor seed[i.inv() and 3].u()).toByte()
        val check = ((q[0].u() and 63) shl 14) or 0x18000001 or ((f13[0].u() and 63) shl 8)
        val packed = byteArrayOf(0x35) + le32(xmRand) + le32(check) + transformed +
            byteArrayOf((hashRand shr 16).toByte(), (hashRand shr 24).toByte())
        val encrypted = AesV3(hex("f1593376766ea98d34f31b057a9d5be4"), ts)
            .encrypt(packed, hex("1fe109a4125283f418de9e051a969e12"))
        val versionBytes = hex("03000000f7e85ffad7d7dc3bd62ac87057cf6118")
        val prefix = (0 until 20 step 4).fold(byteArrayOf()) { acc, i -> acc + le32(versionBytes.i32(i, true) xor ts) }
        return (prefix + byteArrayOf(hashRand.toByte(), (hashRand shr 8).toByte(), 0, 1) + encrypted).base64()
    }

    // ------------------------------------------------------------------ 入口

    /**
     * 给一次 Passport 请求签名。
     *
     * [base] 是去掉 query 的完整地址；[values] 是 query 参数（会被就地补上 `ts` / `_rticket`）。
     * 返回的 [Request.url] 已经带好 query，[Request.headers] 里是那一整套头。
     */
    fun sign(base: String, values: Map<String, String>, body: ByteArray? = null): Request {
        val now = clock()
        val ts = (now / 1000).toInt()
        var ticket = now
        var query = ""
        var q = ByteArray(32)
        var m = ByteArray(16)
        var iv = 0
        // 分支 2 靠试 `_rticket` 命中：官方客户端也是这么做的（最多 256 次）。
        for (offset in 0L until 256L) {
            ticket = now + offset
            query = encode(values + mapOf("ts" to ts.toString(), "_rticket" to ticket.toString()))
            val pair = branchOf(query, body, ts)
            if ((pair.first and 15) % 3 == 2) { iv = pair.first; q = pair.second; m = bodyDigest(body); break }
        }
        require(query.isNotEmpty() && (iv and 15) % 3 == 2) { "无法生成 Passport 签名（branch 2）" }
        val n = IntArray(4) { rand() }
        val headers = linkedMapOf(
            "X-Khronos" to ts.toString(),
            "X-SS-Req-Ticket" to ticket.toString(),
            "X-Helios" to helios(ts, n[3]),
            "X-Medusa" to medusa(query, ts, n[0], n[1], n[2], q, m),
            "X-Gorgon" to gorgon(query, body, ts, n[0])
        )
        if (body != null && body.isNotEmpty()) headers["X-SS-STUB"] = m.hex().uppercase()
        return Request("$base?$query", headers, body)
    }

    companion object {
        fun encode(values: Map<String, String>) = values.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
        fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("%7E", "~")
    }
}
