package com.hgtv.star.core

import java.security.SecureRandom

private val MEDUSA_ROOT_MAGIC = byteArrayOf(
    45, 75, 79, -54, 73, 117, 13, 67, 63, -75, -82, 44, 34, 109, -52, 86,
)

private val MEDUSA_DEFAULT_KEY = byteArrayOf(
    -78, 60, 42, 71, 10, 32, -47, 5, -36, 63, -116, 68, 56, -8, 39, 46,
    -44, 109, 108, -119, -1, -36, -34, -35, 81, 8, 105, -115, 38, -23, 72, -99,
)

private val medusaFLTable = byteArrayOf(
    58, 50, 42, 34, 26, 18, 10, 2, 60, 52, 44, 36, 28, 20, 12, 4,
    62, 54, 46, 38, 30, 22, 14, 6, 64, 56, 48, 40, 32, 24, 16, 8,
    57, 49, 41, 33, 25, 17, 9, 1, 59, 51, 43, 35, 27, 19, 11, 3,
    61, 53, 45, 37, 29, 21, 13, 5, 63, 55, 47, 39, 31, 23, 15, 7,
)

private fun readLe32(data: ByteArray, at: Int): Long =
    (data[at].toLong() and 0xFF) or
        ((data[at + 1].toLong() and 0xFF) shl 8) or
        ((data[at + 2].toLong() and 0xFF) shl 16) or
        ((data[at + 3].toLong() and 0xFF) shl 24)

internal fun medusaCRC64(data: ByteArray): Long {
    var h = 0L
    for (b in data) {
        var x = (h xor (b.toLong() and 0xFF)) and 255
        for (i in 0 until 8) {
            x = if (x and 1L != 0L) (x ushr 1) xor 0x95ac9329ac4bc9b5uL.toLong()
            else x ushr 1
        }
        h = x xor (h ushr 8)
    }
    return h
}

internal fun medusaQuery(url: String): String {
    val s = url.substringBefore('#')
    val at = s.indexOf('?')
    return if (at >= 0) s.substring(at + 1) else ""
}

internal fun applyFLStages(p: MedusaProcess, vararg stages: Int) {
    for (n in stages) {
        when (n) {
            2 -> p.fl[2] = ((p.record0 and 0xFFFFFFFFL) xor (p.record0 ushr 32)).toInt()
            3 -> p.fl[3] = Integer.reverseBytes(p.fl[2])
            4 -> {
                val b = ByteArray(8)
                le32(p.fl[3]).copyInto(b, 0)
                p.fl[4] = medusaAP(b, 0x201507L, 6, 4, 12, 7).toInt()
            }
            5 -> {
                var x = p.fl[4]
                for (bit in intArrayOf(0, 1)) {
                    if (p.record8 and (1L shl bit) != 0L) x = Integer.rotateLeft(x, 1)
                }
                var v = 0
                for (i in 0 until 4) {
                    v = v or ((medusaFLTable[(x ushr (i * 8)) and 63].toInt() and 0xFF) shl (i * 8))
                }
                p.fl[5] = v
            }
            6 -> {
                val x = (p.fl[5].toLong() and 0xFFFFFFFFL) xor
                    ((p.fl[5].toLong() and 0xFFFFFFFFL) shl 13)
                val y = (x xor (x ushr 17)).toInt()
                p.fl[6] = y xor (y shl 5)
            }
        }
        p.record8 = p.record8 or (1L shl n)
    }
}

internal class MedusaResult(
    val header: String,
    val tls: Long,
    val record8: Long,
    val selector: Int,
    val digest: ByteArray,
    val protobuf: ByteArray,
)

/** Go `MedusaDetailed`。 */
internal fun medusaDetailed(
    url: String,
    i: MedusaIdentity,
    d: MedusaDevice,
    s: MedusaSdk,
    thread: MedusaThread,
    o: MedusaOptions,
): MedusaResult {
    val rnd = o.random ?: run {
        val b = ByteArray(12)
        SecureRandom().nextBytes(b)
        LongArray(3) { (readLe32(b, it * 4) and 0x7fffffff) }
    }
    val process = thread.process
    process.record8 = process.record8 or 256
    val tlsMask = java.lang.Long.rotateLeft(
        thread.tokenPointerTLS xor medusaCRC64(i.licenseID.toByteArray()) xor
            (o.timestamp and 0xFFFFFFFFL),
        4,
    ) and 0xFFFFFFFFL
    thread.tls = (thread.tls xor tlsMask) and 0xFFFFFFFFL

    val stub = o.bodyMD5 ?: if (o.body != null && o.body.isNotEmpty()) {
        digest("MD5", o.body)
    } else {
        ByteArray(16)
    }
    val query = medusaQuery(url).toByteArray()
    val qh = sm3(query)
    val data = ByteArray(52)
    qh.copyInto(data, 0, 0, 32)
    stub.copyInto(data, 32, 0, 16)
    le32((o.timestamp and 0xFFFFFFFFL).toInt()).copyInto(data, 48)
    val selector = (medusaAP(data, 0x20230928L, 6, 4, 12, 7) and 15).toInt()
    val digest = medusaTail(medusaCore(data, selector, o.timestamp), s.requestChecks)

    var detection = 0L
    for (n in s.detectionTypes) detection = detection or (1L shl (n and 31))
    val prefix = le64(detection)
    var root17 = (o.timestamp and 0xFFFFFFFFL).toInt()

    val v = HashMap<Int, Any?>()
    v[1] = MEDUSA_ROOT_MAGIC
    v[2] = 5
    v[3] = (rnd[0] and 0xFFFFFFFFL).toInt()
    v[4] = i.aid
    v[5] = i.deviceID
    v[6] = i.licenseID
    v[7] = i.appVersion
    v[8] = i.sdkPlatform
    v[9] = 0x040A0300
    v[10] = prefix
    v[11] = 0
    v[12] = o.timestamp
    v[13] = digest
    v[14] = qh.copyOfRange(0, 6)
    v[15] = mapOf(
        1 to s.maps.integer("04b29480233f4def5c875875b6bdc3b1"),
        2 to s.maps.integer("e98d2f001da5678b39482efbdf5770dc"),
        3 to s.maps.integer("7dc22b2c6a992f0232345df41303f5ea"),
        4 to s.maps.integer("f0036511a681da731501008a14354fa3"),
        5 to s.maps.integer("73e41d5295b696cc41657fef7bba312e"),
    )
    v[16] = i.token
    v[17] = root17
    v[20] = "none"
    v[21] = (s.configMask and 0xfffffff).toInt()
    v[22] = s.root22
    v[23] = medusaContext(i, d, s, thread, o)
    v[24] = medusaEnvironment(i, s, thread)

    val plain = medusaPB(v, medusaSchema)
    val flags = (3L shl 59) or
        ((qh[0].toLong() and 63) shl 46) or
        ((digest[0].toLong() and 63) shl 40) or
        (1L shl 32) or
        (rnd[2] and 0xFFFFFFFFL)
    val header = medusaEnvelope(plain, o.timestamp, rnd[1], flags, prefix, 0, MEDUSA_DEFAULT_KEY)
    return MedusaResult(header, thread.tls, process.record8, selector, digest, plain)
}

/**
 * 进程状态。只保留真正跨请求推进的量：启动时刻、状态位、5 个指针值。
 *
 * `FL[0..8]` 由 `record0`/`record8` 经 [applyFLStages] 推出，`tls` 由 license+ts
 * 推出，两者都不再落状态（每次签名现算，结果相同）。
 */
internal class AutonomousProcess(
    var record0: Long = 0,
    var record8: Long = 0,
    var firstTPIDR: Long = 0,
    var callbackAddress: Long = 0,
    var pointerGap: Long = 0,
    var tokenPointerTLS: Long = 0,
    var tokenPointerLP: Long = 0,
)

/**
 * TLS 输入值：`0xb462c5a0` 与 ts 的预抵消量。
 *
 * `medusaDetailed` 里还会再 XOR 一次同样的 mask，最终输出恒为 `0xb462c5a0`，
 * 所以这个值不必存，由 license + ts 现算即可。
 */
private fun medusaTlsInput(licenseID: String, ts: Long): Long =
    (0xb462c5a0L xor (java.lang.Long.rotateLeft(
        medusaCRC64(licenseID.toByteArray()) xor (ts and 0xFFFFFFFFL),
        4,
    ) and 0xFFFFFFFFL)) and 0xFFFFFFFFL


/**
 * Go `AutonomousState`：每个设备/进程一份，跨请求推进。
 *
 * [generate] 每次都刷新 31 位随机数并递增请求计数——真机就是这样做的；
 * 复用旧随机数会让服务端把后续请求判成重放。
 */
internal class AutonomousState(
    val identity: MedusaIdentity,
    val device: MedusaDevice,
    val sdk: MedusaSdk,
    val process: AutonomousProcess,
    var requestCount: Long,
    var random: LongArray,
) {
    /** 测试可注入固定随机源；生产为 null，走 SecureRandom。 */
    var randomFunc: (() -> LongArray)? = null

    fun initialize(nowMS: Long) {
        process.record0 = nowMS
        process.record8 = 0x83
        val p = MedusaProcess(IntArray(9), process.record0, process.record8)
        applyFLStages(p, 2, 3, 4, 5, 6)
        process.record8 = p.record8
        val b = ByteArray(12)
        SecureRandom().nextBytes(b)
        for (i in 0 until 3) random[i] = readLe32(b, i * 4) and 0x7fffffff
    }

    fun generate(url: String, body: ByteArray?, bodyMD5: ByteArray?, ts: Long, nowMS: Long): String =
        generateDetailed(url, body, bodyMD5, ts, nowMS).header

    fun generateDetailed(
        url: String,
        body: ByteArray?,
        bodyMD5: ByteArray?,
        ts: Long,
        nowMS: Long,
    ): MedusaResult {
        val integers = HashMap(sdk.maps.integers)
        integers[KEY_C1] = requestCount.toInt()
        val sdkCopy = sdk.copy(maps = sdk.maps.copy(integers = integers))
        val processCopy = MedusaProcess(IntArray(9), process.record0, process.record8)
        applyFLStages(processCopy, 2, 3, 4, 5, 6)
        val thread = MedusaThread(
            tls = medusaTlsInput(identity.licenseID, ts),
            firstTPIDR = process.firstTPIDR,
            callbackAddress = process.callbackAddress,
            pointerGap = process.pointerGap,
            tokenPointerTLS = process.tokenPointerTLS,
            tokenPointerLP = process.tokenPointerLP,
            process = processCopy,
        )
        val injected = randomFunc?.invoke()
        if (injected != null) {
            random = injected
        } else {
            val fresh = ByteArray(12)
            SecureRandom().nextBytes(fresh)
            for (i in 0 until 3) random[i] = readLe32(fresh, i * 4) and 0x7fffffff
        }
        val options = MedusaOptions(
            timestamp = ts,
            timeMS = nowMS,
            body = body,
            bodyMD5 = bodyMD5,
            random = random,
        )
        val result = medusaDetailed(url, identity, device, sdkCopy, thread, options)
        requestCount++
        process.record8 = processCopy.record8
        return result
    }

    companion object {
        private const val KEY_C1 = "04b29480233f4def5c875875b6bdc3b1"
    }
}

/** Go `DefaultAutonomousState`：与官方 7.4.3.32 稳定态一致的初始档案。 */
internal fun defaultAutonomousState(
    deviceID: String,
    token: String,
    nowMS: Long,
    processID: Int = runCatching {
        java.lang.management.ManagementFactory.getRuntimeMXBean()
            .name.substringBefore('@').toInt()
    }.getOrDefault(1),
): AutonomousState {
    val identity = MedusaIdentity(
        aid = "8662",
        deviceID = deviceID,
        licenseID = "646637794",
        appVersion = "7.4.3.32",
        sdkVersion = "v04.10.03.04-bugfix",
        channel = "update_64",
        token = token,
        sdkPlatform = "v04.10.03-ml-android",
    )
    val device = MedusaDevice(
        collectionState = 7,
        cache256 = "!noperm!",
        timezone = "Asia/Shanghai,8",
        locale = "zh_CN",
        cpuCoreCount = 8,
        storageRAMGiB = floatArrayOf(8f, 7.5f, 6.5f, 5.5f, 4.5f, 3.5f),
        androidRelease = "12",
        brightness = 102,
        systemVolume = 7,
        usbConnected = -1,
        model = "VER-AN00",
        brand = "HONOR",
        board = "VER-AN00",
        product = "VER-AN00",
        device = "VER-AN00",
        manufacturer = "HONOR",
        hardware = "VER-AN00",
        featureBits = 21,
    )
    val sdk = MedusaSdk(
        configMask = 312,
        processID = processID,
        parentProcessID = 1,
        detectionTypes = listOf(8, 15),
        cmr = 16777216,
        cmr2 = 16777216,
        unH = 1904867320,
        sts = 32251,
        fkd = 3855421825,
        pd = 1510177550,
        maps = MedusaConfigMaps(
            integers = mapOf(
                "45ca7e170e474edadd7d40972a193ace" to 247235599,
                "f866b105da86ad7edbaf9846a68fdbab" to 247235596,
                "593fa435c8460ab2ac0b86b32e28de66" to 7,
                "04b29480233f4def5c875875b6bdc3b1" to 1,
                "e98d2f001da5678b39482efbdf5770dc" to 5,
                "7dc22b2c6a992f0232345df41303f5ea" to 694367,
                "f0036511a681da731501008a14354fa3" to 0,
                "73e41d5295b696cc41657fef7bba312e" to 1261494702,
                "cede084aa3812c79e4614ee74ba582dd" to -780914011,
                "30c8cb0fcd3ea4c95cfd5170df341924" to -780914011,
                "167774bf518c11948aa0784351ccf5a9" to 0,
            ),
            longs = mapOf("fd8d1a41d3c56026d47f1f8ba146eb99" to 983),
            strings = mapOf("fa4c0a5bbd8dcedf5ee28719c5bb1df1" to ""),
        ),
        requestChecks = DigestChecks(configCount = 1),
    )
    val process = AutonomousProcess(
        record0 = nowMS,
        record8 = 0x83,
        firstTPIDR = 491512328192,
        callbackAddress = 491857043860,
        pointerGap = 1804,
        tokenPointerLP = 495781201056,
    )
    return AutonomousState(
        identity = identity,
        device = device,
        sdk = sdk,
        process = process,
        requestCount = 1,
        random = longArrayOf(1234567, 2345678, 3456789),
    )
}
