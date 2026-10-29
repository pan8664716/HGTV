// Kotlin port of drpys 22261adfa31435e3b3ef5a730b8a2a75bcaf1715; GPL-3.0-only.
// Only hash branch 0 is emitted: adjust the request ticket before signing.
package com.hgtv.star.core

import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class Signer(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: () -> Double = Math::random,
    /**
     * 真机设备档案（导入账号时随凭据一起带来，见 [AccountCredentials.device]）。
     *
     * 空着就用 [VendorConstants.device] 里那份内置档案。`x-medusa` 的 deviceProto 里
     * 机型 / 系统版本这些字段原先写死成 Redmi + Android 16，与凭据所属的设备对不上，
     * 写接口（追剧 / 进度）会被平台判成异常客户端；这里改成跟随档案取值。
     */
    private var device: Map<String, String> = emptyMap()
) {
    /** 账号凭据变化时更新档案；空档案回落到内置那份。 */
    fun updateDevice(values: Map<String, String>) { device = values.filterValues { it.isNotBlank() } }

    /**
     * 档案里没有的字段一律回落到内置默认值。
     *
     * 参数 `d` 是**这一次签名**用的档案，默认取 [device]。
     *
     * 为什么必须能逐次传：签名里有两处会把 `device_id` 编进密文
     *（`x-medusa` 的 message 第 5 号字段与 deviceProto 第 4 号字段）。
     * 播放接口要按 `device_id` 换身份时才可能绕过限流（见 `ContentRepository.modelDeviceId`），
     * 而如果 query 里是新 id、密文里还是旧 id，服务端拿到的就是一份自相矛盾的请求，
     * 直接回 `110001 未知异常`（2026-10-27 实测：App 换 id 后 117 字节 `code 110001`，
     * 同一份内容在本机探针里 `code 0`，差别就在这里）。
     */
    private fun deviceValue(d: Map<String,String>, key: String, fallback: String): String =
        d[key]?.takeIf { it.isNotBlank() } ?: fallback
    private fun deviceValue(d: Map<String,String>, key: String, fallback: () -> String): String =
        d[key]?.takeIf { it.isNotBlank() } ?: fallback()
    private fun deviceId(d: Map<String,String>) = deviceValue(d,"device_id") { VendorConstants.device.getValue("device_id") }
    private fun deviceType(d: Map<String,String>) = deviceValue(d,"device_type", "25053RT47C")
    private fun deviceBrand(d: Map<String,String>) = deviceValue(d,"device_brand", "Redmi")
    /** 厂商：Samsung / Xiaomi 这类，按品牌推；认不出来就直接用品牌名。 */
    private fun manufacturer(d: Map<String,String>) = when {
        deviceBrand(d).contains("samsung", ignoreCase = true) -> "Samsung"
        deviceBrand(d).contains("redmi", ignoreCase = true) -> "Xiaomi"
        deviceBrand(d).contains("xiaomi", ignoreCase = true) -> "Xiaomi"
        else -> deviceBrand(d)
    }
    /**
     * 一次签好名的请求。
     *
     * [method] 单独拎出来，是因为**签名只覆盖 query 与 body，不覆盖方法**：同一条签名
     * 既可以按 GET 发，也可以按 POST 发。早先靠「有没有 body」隐式决定方法，
     * 结果 `search/tab` 明明要 GET，却被读成「没 body 就是 GET」这种巧合——
     * 平台那边 POST 该路径是 404，谁照着旧文档改成 POST 就立刻「搜索暂不可用」。
     */
    data class Request(
        val url: String,
        val headers: Map<String, String>,
        val body: ByteArray?,
        /** 实际要发的 HTTP 方法；默认「有 body 用 POST、没有用 GET」。 */
        val method: String = if (body == null) "GET" else "POST"
    )
    private fun rand() = (random() * 4294967296.0).toLong().toInt()
    private fun unsigned(v: Int) = v.toLong() and 0xffffffffL
    private fun varint(value: Long): ByteArray {
        var n = value
        return ByteArrayOutputStream().apply { while (n > 127) { write(((n and 127) or 128).toInt()); n = n ushr 7 }; write(n.toInt()) }.toByteArray()
    }
    private data class Field(val tag: Int, val value: Any, val type: String = "string")
    private fun f(tag: Int, value: Any, type: String = "string") = Field(tag, value, type)
    private fun proto(vararg fields: Field): ByteArray = ByteArrayOutputStream().apply {
        for ((tag, value, type) in fields) {
            when (type) {
                "sint" -> { val n = (value as Number).toLong(); write(varint(tag.toLong() shl 3)); write(varint(if (n < 0) -n * 2 - 1 else n * 2)) }
                "float" -> { write(varint((tag.toLong() shl 3) or 5)); write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat((value as Number).toFloat()).array()) }
                else -> { val bytes = if (value is ByteArray) value else value.toString().toByteArray(); if (bytes.isNotEmpty()) { write(varint((tag.toLong() shl 3) or 2)); write(varint(bytes.size.toLong())); write(bytes) } }
            }
        }
    }.toByteArray()
    private fun getIv(initial: Int, data: ByteArray): Int {
        var value = initial
        for (i in data.indices) value = if (i and 1 == 0) (value ushr 4) xor value xor (value shl 6) xor data[i].u() else ((value ushr 7) xor value xor (data[i].u() or (value shl 12))).inv()
        return value
    }
    private fun iv(query: ByteArray, body: ByteArray, ts: Int) = getIv(getIv(getIv(0x20230928, query), body), le32(ts))
    fun branch(query: String, body: ByteArray?, ts: Int): Int = (iv(sm3(query.toByteArray()), body?.let { digest("MD5", it) } ?: ByteArray(16), ts) and 15) % 3
    private fun sumMd5(data: ByteArray): Int {
        var check = 0x20220420
        for (i in 0 until 12) {
            val temp = (check ushr (if (i and 1 == 0) 3 else 5)) xor check
            check = if (i and 1 == 0) data[i].u() xor (check shl 7) else (data[i].u() or (check shl 11)).inv()
            check = check xor temp
        }
        return (check or 4) xor 0x1000000
    }
    fun hashF13(query: ByteArray, body: ByteArray, ts: Int): ByteArray {
        val index = (iv(query, body, ts) and 15) / 3
        require((iv(query, body, ts) and 15) % 3 == 0) { "Unsupported hash branch" }
        val ivV1 = intArrayOf(0xc4a78580.toInt(),0xb3c0fd39.toInt(),0xc58c5686.toInt(),0xc9aa3ba7.toInt(),0xf5a7adf2.toInt(),0x963c2ed1.toInt())[index]
        val count = ivV1 + ts
        val tt = VendorConstants.hashTable.map { ror(it, (count + 1) and 255) }
        val shift = (count + 2) and 7
        val pad = intArrayOf(0xfa,0x45,0x61,0xd7).map { ((it or (it shl 8)) shr shift).toByte() }.toByteArray()
        val data = query + body + le32(ts) + pad + hex("00000000000001a0")
        val di = (data.indices step 4).map { data.i32(it) }.toMutableList()
        for (i in 0 until 112) {
            val x = di[i + 1]; val y = di[i + 14]
            di.add(di[i] + di[i + 9] + (rol(x,14) xor rol(x,25) xor (x ushr 3)) + (rol(y,13) xor rol(y,15) xor (y ushr 10)))
        }
        val initial = intArrayOf(0x7aba4fc8,0x67166507,0x6403fa00,0x340f512f,984304912,3005047866L.toInt(),2874125293L.toInt(),2152413264L.toInt()).map { ror(it, count) }
        val chosen = arrayOf(intArrayOf(101,5,7,6,3,2,1,0,5,4,3),intArrayOf(96,0,6,7,5,3,2,1,5,4,4),intArrayOf(96,7,6,2,1,4,0,5,4,3,5),intArrayOf(99,3,6,2,4,5,1,0,0,7,6),intArrayOf(96,0,5,6,7,3,1,2,5,4,4),intArrayOf(100,2,0,3,5,4,6,7,2,1,5))[index]
        val d = initial.toMutableList()
        for (i in 0 until chosen[0]) {
            val base = ivV1 + i
            val n1 = ((d[chosen[3]] xor d[chosen[4]]) and d[chosen[1]]) xor d[chosen[3]]
            val n2 = rol(d[chosen[1]],26) xor rol(d[chosen[1]],21) xor rol(d[chosen[1]],7)
            val n4 = di[base and 127] + n1 + n2 + tt[base and 63] + d[chosen[5]]
            val n5 = rol(d[chosen[2]],30) xor rol(d[chosen[2]],19) xor rol(d[chosen[2]],10)
            val n6 = (d[chosen[2]] and d[chosen[6]]) or ((d[chosen[2]] or d[chosen[6]]) and d[chosen[7]])
            val old = d[chosen[9]]
            d.add(0,d.removeAt(7)); d[chosen[10]] = n5 + n6 + n4; d[chosen[8]] = old + n4
        }
        val ret = (0..7).fold(byteArrayOf()) { a, i -> a + be32(d[i] + initial[i]) }
        val folded = xor(ret.copyOfRange(0,16),ret.copyOfRange(16,32))
        return folded + le32(sumMd5(folded))
    }
    fun gorgon(query: String, body: ByteArray?, ts: Int, random: Int): String {
        val input = digest("MD5",query.toByteArray()).copyOf(4) + (body?.let { digest("MD5",it).copyOf(4) } ?: ByteArray(4)) + ByteArray(4) + le32(67503104) + be32(ts)
        val key = intArrayOf(0x4a,0x40,0x16,(random shr 8) and 255,0x47,0x6c,1,random and 255)
        val s = IntArray(256) { it }; var j = 0
        for (i in 0..255) { j = (j + s[i] + key[i % 8]) and 255; s[i] = s[j] }
        j = 0
        val out = ByteArray(input.size) { n -> val i = n + 1; j += s[i]; val y = s[j and 255]; s[i] = y; (input[n].u() xor s[(y + y) and 255]).toByte() }
        for (i in out.indices) {
            val value = ((out[i].u() shr 4) or (out[i].u() shl 4)) and 255
            val next = out[if (i + 1 < out.size) i + 1 else 0].u()
            val reversed = Integer.reverse((next xor value) and 255) ushr 24
            out[i] = (reversed xor 20).inv().toByte()
        }
        return (hex("8404") + byteArrayOf(random.toByte(),(random shr 8).toByte(),0x40,1) + out).hex()
    }
    private fun deviceProto(d: Map<String,String>) = proto(f(1,1,"sint"),f(2,2,"sint"),f(3,"8662"),f(4,deviceId(d)),f(5,"Ai6svO3PyrwDOUSmO6ZcResxu"),f(6,"!noperm!"),f(7,-888888,"sint"),f(8,-888888,"sint"),f(9,3,"sint"),f(10,-888888,"sint"),f(11,"!notset!"),f(12,"Asia/Shanghai,8"),f(13,"zh_CN"),f(14,4,"sint"),f(16,255.24993896484375,"float"),f(17,35.58599090576172,"float"),f(18,3.467449188232422,"float"),f(19,3.467449188232422,"float"),f(20,255.1754913330078,"float"),f(21,42.17544174194336,"float"),f(22,deviceValue(d,"os_version","16")),f(23,41,"sint"),f(24,deviceValue(d,"os_api") { "36" }.toIntOrNull() ?: 36,"sint"),f(25,1728388016635L,"sint"),f(26,1728388016635L,"sint"),f(27,1728388016635L,"sint"),f(28,1728388016637L,"sint"),f(29,-1,"sint"),f(30,deviceType(d)),f(31,deviceBrand(d)),f(32,deviceType(d)),f(33,deviceType(d)),f(34,manufacturer(d)),f(35,deviceBrand(d)),f(36,deviceBrand(d)),f(38,31,"sint"))
    private fun xmxor(data: ByteArray, key: ByteArray): ByteArray {
        val encoded = IntArray(data.size)
        for (i in data.indices) {
            val at = (i * 4) and 28; val d0 = key[at].u(); val d1 = key[at + 1].u(); val v = data[i].u()
            var d2 = (((v shl 4) or (v ushr 4)) and 255) + d0
            d2 = d2.inv() xor d1; d2 = (((d2 and 255) shl 3) or ((d2 and 255) ushr 5)) and 255
            d2 = (d2 + d1) and 255; d2 = (d2 xor d0) and 255; encoded[data.size-i-1] = d2.inv() and 255
        }
        val last = encoded.last() xor encoded[encoded.size-2]; val first = encoded[0]
        encoded[0] = (last.inv() + first) and 255
        encoded[1] = ((encoded[0] xor encoded.last() xor 254) + encoded[1]) and 255
        encoded[2] = (encoded[2] + ((last-first) xor (((encoded[1] shl 3) or (encoded[1] ushr 5)) and 255) xor 2)) and 255
        for (i in 0 until encoded.size-4) {
            val temp = (((encoded[i+2] shl 3) or (encoded[i+2] ushr 5)) and 255) xor encoded[i+1] xor (i+3)
            encoded[i+3] = (temp.inv() + encoded[i+3]) and 255
        }
        encoded[encoded.size-1] = encoded.last() xor encoded[encoded.size-2]
        encoded[0] = ((encoded[0] xor encoded[1]) + encoded.drop(1).sum()) and 255
        return encoded.map { it.toByte() }.toByteArray()
    }
    fun medusa(url: String, body: ByteArray?, ts: Int, d: Map<String,String> = device): String {
        val bodyMd5 = body?.let { digest("MD5",it) } ?: ByteArray(16)
        val query = url.substringAfter('?').toByteArray(); val querySm3 = sm3(query)
        val queryBodyTs = hashF13(querySm3,bodyMd5,ts)
        val nested = proto(f(1,111,"sint"),f(2,10,"sint"),f(3,694367,"sint"),f(5,586952199,"sint"))
        val messageRand = unsigned(rand()); val envLaunch = (random()*21).toInt()+100; val envPid = (random()*2000).toInt()+10001
        val version = deviceValue(d,"version_name") { VendorConstants.device.getValue("version_name") }
        val env = proto(f(1,envLaunch,"sint"),f(2,146331399,"sint"),f(3,146331396,"sint"),f(5,7,"sint"),f(6,"v04.06.04.03-bugfix"),f(7,envPid,"sint"),f(12,deviceProto(d)),f(13,proto(f(1,clock()/1000,"sint"),f(2,-2,"sint"),f(4,200,"sint"))),f(14,version))
        val queryHash = sm3(query+bodyMd5+"none".toByteArray())
        val message = proto(f(1,hex("f7e85ffad7d7dc3bd62ac87057cf6118")),f(2,3,"sint"),f(3,messageRand,"sint"),f(4,"8662"),f(5,deviceId(d)),f(6,"1588093228"),f(7,version),f(8,"v04.06.04-ml-android"),f(9,67503104,"sint"),f(10,hex("4001000000000000")),f(12,ts,"sint"),f(13,queryBodyTs),f(14,querySm3.copyOf(6)),f(15,nested),f(16,"AXYQOS6n2m60x1fVZHIrH3iol"),f(17,ts,"sint"),f(19,queryHash),f(20,"none"),f(21,312,"sint"),f(23,env),f(24,"""{"cmr":16777216,"cmr2":16777216,"un_h":1879194040,"vpn":0,"kd":0,"fkd":3672518972,"pd":-1872573247,"dyn":"","do":0,"tk":true}"""))
        val r = rand(); val signKey = hex("8ebdfa3806ecc5cee79423e6029ed82540bc2218bb7eaef71cb691f7aa8aa2f5")
        val hash = sm3(signKey+le32(r)+signKey); val d1 = (r shr 16) and 255
        val seed = le32((((d1 shl 11) or (r ushr 24)) xor (d1 shr 5) xor d1).inv())
        val transformed = (hex("4001000000000000")+xmxor(message,hash)).reversedArray()
        for (i in transformed.indices) transformed[i] = (transformed[i].u() xor seed[i.inv() and 3].u()).toByte()
        val check = ((querySm3[0].u() and 63) shl 14) or 0x18000001 or ((queryBodyTs[0].u() and 63) shl 8)
        val packed = byteArrayOf(0x35)+le32(rand())+le32(check)+transformed+byteArrayOf((r shr 16).toByte(),(r shr 24).toByte())
        val encrypted = AesV3(hex("f1593376766ea98d34f31b057a9d5be4"),ts).encrypt(packed,hex("1fe109a4125283f418de9e051a969e12"))
        val versionBytes = hex("03000000f7e85ffad7d7dc3bd62ac87057cf6118")
        val prefix = (0 until 20 step 4).fold(byteArrayOf()) { a,i -> a+le32(versionBytes.i32(i,true) xor ts) }
        return (prefix+byteArrayOf(r.toByte(),(r shr 8).toByte(),0,1)+encrypted).base64()
    }
    fun helios(ts: Int): String {
        val random = rand()
        val ascii = digest("MD5",le32(random)+"8662".toByteArray()).hex().toByteArray()
        val words = (0..3).map { ascii.i64(it*8,true) }.toMutableList(); val table = mutableListOf(words[0])
        var b0 = words.removeAt(0); var b8 = words.removeAt(0)
        for (i in 0 until 34) {
            var x8 = (java.lang.Long.rotateRight(b8,8)+b0) xor i.toLong()
            words.add(x8); x8 = x8 xor java.lang.Long.rotateRight(b0,61); table.add(x8); b0=x8; b8=words.removeAt(0)
        }
        val text = "$ts-1588093228-8662".toByteArray(); val padding = 16 - text.size % 16
        val data = text+ByteArray(padding) { padding.toByte() }
        var output = le32(random)
        for (at in data.indices step 16) {
            var a = data.i64(at,true); var b = data.i64(at+8,true)
            for (i in 0 until 34) { b = table[i] xor (a + java.lang.Long.rotateRight(b,8)); a = b xor java.lang.Long.rotateRight(a,61) }
            output += le64(a)+le64(b)
        }
        return output.base64()
    }
    /**
     * 签名一条请求。
     *
     * [deviceOverride] 非空时，**这一次**签名用它的档案（query 参数仍以 [values] 为准）。
     *
     * 为什么需要：[values] 里换掉 `device_id` 而 [device] 里还是旧的，`x-medusa` 会编进旧 id，
     * 服务端看到 query 与密文不一致就回 `110001`。播放接口按 `device_id` 换身份绕限流
     *（见 `ContentRepository.modelDeviceId`），因此那一条必须把同一份档案同时交给两者。
     */
    fun sign(
        base: String,
        values: Map<String,String>,
        body: ByteArray? = null,
        method: String? = null,
        deviceOverride: Map<String,String>? = null
    ): Request {
        val d=(deviceOverride ?: device).filterValues { it.isNotBlank() }
        val now = clock(); val ts = (now / 1000).toInt()
        var ticket = now; var query: String
        for (offset in 0..127) {
            ticket = now + offset; query = encode(values + mapOf("ts" to ts.toString(), "_rticket" to ticket.toString()))
            if (branch(query,body,ts) == 0) {
                val url = "$base?$query"
                val random = (random()*65536).toInt()
                // `passport-sdk-version` 分两档（参考实现 `passport_client.go` 的 `request`）：
                // Passport 路径用 5051452，其余内容 / 账号接口用 6031190。
                // 早先一律写 50561，两个都不对。
                val passportPath = base.contains("/passport/")
                val headers = linkedMapOf("User-Agent" to VendorConstants.userAgent(d, video = body != null),"Accept" to "application/json; charset=utf-8,application/x-protobuf", "x-ss-req-ticket" to ticket.toString(),"x-tt-request-tag" to "t=0;n=0","sdk-version" to "2","passport-sdk-version" to (if(passportPath) "5051452" else "6031190"),"x-xs-from-web" to "0","x-khronos" to ts.toString(),"x-ladon" to be32(ts).base64(),"x-argus" to le32(ts).base64(),"x-gorgon" to gorgon(query,body,ts,random),"x-helios" to helios(ts),"x-medusa" to medusa(url,body,ts,d),"x-tt-dt" to "")
                if(passportPath) {
                    headers["x-vc-bdturing-sdk-version"]="4.0.3.cn"
                    headers["lc"]="101"
                    headers["x-ss-dp"]="8662"
                }
                if (body != null) { headers["Content-Type"]="application/json; charset=UTF-8"; headers["x-vc-bdturing-sdk-version"]="3.7.2.cn"; headers["x-ss-stub"]=digest("MD5",body).hex().uppercase() }
                return Request(url,headers,body,method ?: if(body==null) "GET" else "POST")
            }
        }
        error("无法生成请求签名")
    }
    companion object {
        fun encode(values: Map<String,String>) = values.entries.joinToString("&") { (k,v) -> "${encode(k)}=${encode(v)}" }
        fun encode(value: String): String = URLEncoder.encode(value,"UTF-8").replace("+","%20").replace("%7E","~")
    }
}
