package com.hgtv.star.core

internal data class MedusaIdentity(
    val aid: String,
    val deviceID: String,
    val licenseID: String,
    val appVersion: String,
    val sdkVersion: String,
    val channel: String,
    val token: String,
    val sdkPlatform: String,
)

/** v5 抓包中的设备字段；默认值仍由 protobuf 编码规则省略。 */
internal data class MedusaDevice(
    val collectionState: Int = 0,
    val cachedToken: String = "",
    val cache256: String = "",
    val cache264: Int = 0,
    val cache268: Int = 0,
    val batteryHealth: Int = 0,
    val cache276: Int = 0,
    val cache296: String = "",
    val timezone: String = "",
    val locale: String = "",
    val cpuCoreCount: Int = 0,
    val cache376: String = "",
    val storageRAMGiB: FloatArray = FloatArray(6),
    val androidRelease: String = "",
    val brightness: Int = 0,
    val systemVolume: Int = 0,
    val fltkTimeMS: Long = 0,
    val firstCollectionMS: Long = 0,
    val bootTimeMS: Long = 0,
    val loadTimeMS: Long = 0,
    val cache168: Long = 0,
    val cache176: Int = 0,
    val cache200: Int = 0,
    val usbConnected: Int = 0,
    val model: String = "",
    val brand: String = "",
    val board: String = "",
    val product: String = "",
    val device: String = "",
    val manufacturer: String = "",
    val hardware: String = "",
    val featureBits: Long = 0,
)

internal data class MedusaConfigMaps(
    val integers: Map<String, Int> = emptyMap(),
    val longs: Map<String, Long> = emptyMap(),
    val strings: Map<String, String> = emptyMap(),
) {
    fun integer(key: String): Int = integers[key] ?: 694367
    fun long(key: String): Long = longs[key] ?: 0L
    fun text(key: String): String = strings[key] ?: ""
}

/** v5 SDK 状态：进程、时间、检测位、配置映射和摘要校验。 */
internal data class MedusaSdk(
    val configMask: Long = 0,
    val root22: Int = 0,
    val startedAtSeconds: Long = 0,
    val devicePresent: Boolean = true,
    val processID: Int = 0,
    val parentProcessID: Int = 0,
    val clockValue: Long = 0,
    val state232: Int = 0,
    val detectionTypes: List<Int> = emptyList(),
    val cmr: Long = 0,
    val cmr2: Long = 0,
    val unH: Long = 0,
    val vpn: Int = 0,
    val sts: Int = 0,
    val fkd: Long = 0,
    val pd: Int = 0,
    val maps: MedusaConfigMaps = MedusaConfigMaps(),
    val requestChecks: DigestChecks = DigestChecks(),
)

internal data class MedusaProcess(
    val fl: IntArray = IntArray(9),
    val record0: Long = 0,
    var record8: Long = 0,
)

internal data class MedusaThread(
    var tls: Long = 0,
    val firstTPIDR: Long = 0,
    val callbackAddress: Long = 0,
    val pointerGap: Long = 0,
    val tokenPointerTLS: Long = 0,
    val tokenPointerLP: Long = 0,
    val process: MedusaProcess = MedusaProcess(),
)

/**
 * 签名选项。`timestamp`/`timeMS` 是同一时刻的秒与毫秒，原来还冗余存了
 * `nativeSeconds`/`sdkSeconds`（与 `timestamp` 恒等）。
 */
internal data class MedusaOptions(
    val timestamp: Long = 0,
    val timeMS: Long = 0,
    val body: ByteArray? = null,
    val bodyMD5: ByteArray? = null,
    val random: LongArray? = null,
)

/** Go `medusaTokenValid`：宽松的 base64 校验。 */
internal fun medusaTokenValid(token: String): Boolean {
    if (token.isEmpty()) return true
    val s = token.replace('-', '+').replace('_', '/')
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val table = IntArray(256) { -1 }
    for (i in alphabet.indices) table[alphabet[i].code] = i
    table['='.code] = 64
    var p = 0
    var pads = 0
    var count = 0
    while (p < s.length) {
        var spaces = false
        while (p < s.length && s[p] == ' ') {
            spaces = true
            p++
        }
        if (p == s.length) break
        val b = s[p]
        if (b == '\r' && p + 1 < s.length && s[p + 1] == '\n') {
            p++
            continue
        }
        if (b == '\n') {
            p++
            continue
        }
        if (spaces) return false
        if (b == '=') {
            pads++
            if (pads > 2) return false
        } else if (b.code >= 256 || table[b.code] < 0 || pads > 0) {
            return false
        }
        count++
        p++
    }
    if (count == 0) return false
    var quad = 0
    var nbytes = 3
    var out = 0
    for (ch in s) {
        if (ch == ' ' || ch == '\n' || ch == '\r') continue
        if (ch == '=') nbytes--
        quad++
        if (quad == 4) {
            if (nbytes > 0) out += nbytes
            quad = 0
        }
    }
    return out > 0
}

/**
 * Go `(DeviceState) message`：设备档案 protobuf 字段。
 *
 * [nowMS] 是本次请求的时刻（field 28），[record0] 是进程启动时刻，
 * 后者推出 `fltk/firstCollection/boot/load` 四个时间戳。
 */
internal fun medusaDeviceMessage(
    d: MedusaDevice,
    i: MedusaIdentity,
    nowMS: Long,
    record0: Long,
): Map<Int, Any?> {
    val v = HashMap<Int, Any?>()
    v[1] = 1
    v[2] = d.collectionState
    v[3] = i.aid
    v[4] = i.deviceID
    v[5] = d.cachedToken.ifBlank { i.token }
    v[6] = d.cache256
    v[7] = d.cache264
    v[8] = d.cache268
    v[9] = d.batteryHealth
    v[10] = d.cache276
    v[11] = d.cache296
    v[12] = d.timezone
    v[13] = d.locale
    v[14] = d.cpuCoreCount
    v[15] = d.cache376
    v[22] = d.androidRelease
    v[23] = d.brightness
    v[24] = d.systemVolume
    v[25] = d.fltkTimeMS.takeIf { it != 0L } ?: (record0 - 400)
    v[26] = d.firstCollectionMS.takeIf { it != 0L } ?: (record0 - 41)
    v[27] = d.bootTimeMS.takeIf { it != 0L } ?: (record0 - 1000)
    v[28] = nowMS
    v[29] = d.usbConnected
    v[30] = d.model
    v[31] = d.brand
    v[32] = d.board
    v[33] = d.product
    v[34] = d.device
    v[35] = d.manufacturer
    v[36] = d.hardware
    v[38] = d.featureBits
    v[40] = d.loadTimeMS.takeIf { it != 0L } ?: (record0 - 321)
    for (n in 0 until 6) v[16 + n] = d.storageRAMGiB[n]
    return v
}

/** Go `medusaContext`：medusa field 23。 */
internal fun medusaContext(
    i: MedusaIdentity,
    d: MedusaDevice,
    s: MedusaSdk,
    t: MedusaThread,
    o: MedusaOptions,
): Map<Int, Any?> {
    val m = s.maps
    val saved = m.long("fd8d1a41d3c56026d47f1f8ba146eb99")
    val p17 = ((saved and 15L.inv()) or ((saved and 240L) ushr 4)) xor
        ((s.configMask ushr 28) and 15)
    val v = HashMap<Int, Any?>()
    if (s.startedAtSeconds != 0L) {
        v[1] = ((o.timestamp - s.startedAtSeconds) and 0xFFFFFFFFL).toInt()
    }
    v[2] = m.integer("45ca7e170e474edadd7d40972a193ace").toLong()
    v[3] = m.integer("f866b105da86ad7edbaf9846a68fdbab").toLong()
    v[5] = m.integer("593fa435c8460ab2ac0b86b32e28de66")
    v[6] = i.sdkVersion
    v[7] = s.processID
    v[8] = i.channel
    v[14] = i.appVersion
    v[15] = s.parentProcessID
    if (s.clockValue != 0L) v[16] = s.clockValue
    v[17] = p17
    v[18] = t.firstTPIDR xor t.callbackAddress xor (o.timestamp and 0xFFFFFFFFL)
    v[19] = ((t.pointerGap xor (o.timestamp and 0xFFFFFFFFL)) and 0xFFFFFFFFL).toInt()
    v[20] = t.callbackAddress xor (o.timestamp and 0xFFFFFFFFL)
    v[22] = m.integer("cede084aa3812c79e4614ee74ba582dd")
    v[23] = m.integer("30c8cb0fcd3ea4c95cfd5170df341924")
    if (s.devicePresent) {
        v[12] = medusaDeviceMessage(d, i, o.timeMS, t.process.record0)
    }
    v[13] = mapOf(
        1 to (d.cache168.takeIf { it != 0L } ?: (t.process.record0 / 1000)),
        2 to (d.cache176.takeIf { it != 0 } ?: 3),
        4 to (d.cache200.takeIf { it != 0 } ?: 200),
    )
    return v
}

/** Go `medusaEnvironment`：medusa field 24 的 JSON。 */
internal fun medusaEnvironment(i: MedusaIdentity, s: MedusaSdk, t: MedusaThread): String {
    val kd = s.maps.integer("167774bf518c11948aa0784351ccf5a9")
    val mask = 0x7943f10b58L
    val lp = "2|${t.process.record8 xor mask}|${t.tls xor mask}|" +
        "${t.process.record0 xor mask}|${t.tokenPointerLP}"
    val parts = Array(10) { "" }
    for (n in t.process.fl.indices) {
        parts[n] = (t.process.fl[n].toLong() and 0xFFFFFFFFL).toString()
    }
    parts[9] = (t.tls and 0xFFFFFFFFL).toString()
    val fl = parts.joinToString("|")
    val dyn = s.maps.text("fa4c0a5bbd8dcedf5ee28719c5bb1df1")
    val tk = medusaTokenValid(i.token)
    return buildString {
        append("{\"cmr\":").append(s.cmr)
        append(",\"cmr2\":").append(s.cmr2)
        append(",\"un_h\":").append(s.unH)
        append(",\"vpn\":").append(s.vpn)
        append(",\"sts\":").append(s.sts)
        append(",\"kd\":").append(kd)
        append(",\"fkd\":").append(s.fkd)
        append(",\"pd\":").append(s.pd)
        append(",\"lp\":\"").append(lp).append('"')
        append(",\"fl\":\"").append(fl).append('"')
        append(",\"dyn\":\"").append(dyn).append('"')
        append(",\"do\":").append(s.state232)
        append(",\"tk\":").append(tk)
        append('}')
    }
}
