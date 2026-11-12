package com.hgtv.star.core

/** `medusaTail` 用到的检查位，来自 SDKState。 */
internal data class DigestChecks(
    val unH: Long = 0,
    val exception: Int = 0,
    val loader: Int = 0,
    val pointerGap: Int = 0,
    val configCount: Int = 0,
    val gapEqual16: Int = 0,
)

/** Go `medusaAP`：带四个移位量的滚动混合。 */
internal fun medusaAP(data: ByteArray, initial: Long, a: Int, b: Int, c: Int, d: Int): Long {
    var h = initial and 0xFFFFFFFFL
    for (i in data.indices) {
        val v = data[i].toLong() and 0xFF
        if (i and 1 == 0) {
            h = (h xor ((h shl a) and 0xFFFFFFFFL) xor v xor (h ushr b)) and 0xFFFFFFFFL
        } else {
            val t = ((h shl c) and 0xFFFFFFFFL) or v
            h = (t xor (h ushr d) xor h).inv() and 0xFFFFFFFFL
        }
    }
    return h
}


/** core 段：纯算法实现，取代 core IR 解释器。 */
internal fun medusaCore(input: ByteArray, selector: Int, timestamp: Long): ByteArray =
    medusaCoreAlgorithm(input, selector, timestamp.toInt())

// extension 段（medusaExtensionAlgorithm）当前未启用：生产路径的 Config48/Config56
// 恒为空，官方 7.4.3.32 也不带 field 18/19/20。实现保留在 MedusaAlgorithm.kt。

/** Go `medusaTail`：在 16 字节摘要后追加 4 字节校验。 */
internal fun medusaTail(core: ByteArray, checks: DigestChecks): ByteArray {
    val out = ByteArray(20)
    core.copyInto(out, 0, 0, 16)
    val eight = checks.exception == 1 || checks.loader == 1 ||
        (checks.unH and 0xFFFFFFFFL) == 0xf6590850L
    val n = if (eight) 8 else 12
    val ap = medusaAP(core.copyOfRange(0, n), 0x20220420L, 7, 3, 11, 5)
    for (i in 0 until 4) {
        out[16 + i] = ((ap ushr (i * 8)) and 0xFF).toByte()
    }
    out[16] = if (eight) (out[16].toInt() and 4.inv()).toByte()
    else (out[16].toInt() or 4).toByte()
    out[16] = if (checks.gapEqual16 == 1) (out[16].toInt() and 32.inv()).toByte()
    else (out[16].toInt() or 32).toByte()
    if (checks.configCount > 0) out[19] = (out[19].toInt() xor 1).toByte()
    return out
}
