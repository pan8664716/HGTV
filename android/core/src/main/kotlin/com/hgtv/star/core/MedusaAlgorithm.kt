package com.hgtv.star.core

/**
 * medusa 三个核心族的纯算法实现（等价于官方 metasec 的 core 段）。
 *
 * 这段代码是 `x_medusa.go`（无 VM 版）的逐行移植：selector 决定走哪一族，
 * 三族分别对应 SHA-256 变体、MD5 变体、宽轨迹查表（AES 风格）三种结构。
 * 与原 IR 解释器逐字节等价，已用 384 组 core + 201 组 extension 向量对拍。
 *
 * 32 位语义：Go 的 uint32 运算在 Kotlin 里用 Int（溢出回绕一致），
 * uint64 用 Long；移位一律走 Integer/Long.rotateLeft。
 */

private class Mix32Profile(val lanes: IntArray, val sum2Target: Int, val roundOffset: Int, val rounds: Int)

private class Word32Profile(val rotation: Int, val permutation: IntArray)

private class Table64Profile(val rotation: Int, val tableIndex: Int, val permutation: IntArray)

private val medusaMix32Profiles: Map<Int, Mix32Profile> = mapOf(
    0 to Mix32Profile(intArrayOf(0, 1, 3, 7, 4, 5, 2, 6), 7, 15, 100),
    3 to Mix32Profile(intArrayOf(5, 3, 6, 4, 0, 2, 7, 1), 2, 13, 96),
    6 to Mix32Profile(intArrayOf(7, 6, 5, 4, 3, 1, 0, 2), 3, 62, 94),
    9 to Mix32Profile(intArrayOf(6, 7, 4, 0, 2, 5, 1, 3), 4, 79, 97),
    12 to Mix32Profile(intArrayOf(3, 5, 2, 7, 1, 0, 4, 6), 7, 66, 96),
    15 to Mix32Profile(intArrayOf(6, 2, 7, 4, 1, 3, 0, 5), 6, 127, 99),
)

private val medusaWord32Profiles: Map<Int, Word32Profile> = mapOf(
    2 to Word32Profile(27, intArrayOf(8, 1, 12, 11, 9, 6, 0, 4, 13, 7, 3, 2, 10, 5, 14, 15)),
    5 to Word32Profile(2, intArrayOf(9, 8, 13, 6, 10, 7, 14, 11, 0, 1, 2, 15, 12, 4, 5, 3)),
    8 to Word32Profile(15, intArrayOf(10, 11, 7, 14, 9, 12, 2, 13, 3, 6, 15, 5, 0, 8, 1, 4)),
    11 to Word32Profile(18, intArrayOf(7, 5, 11, 12, 6, 1, 15, 14, 13, 8, 4, 3, 9, 0, 10, 2)),
    14 to Word32Profile(17, intArrayOf(5, 8, 3, 6, 4, 2, 0, 12, 10, 7, 15, 9, 13, 11, 1, 14)),
)

private val medusaTable64Profiles: Map<Int, Table64Profile> = mapOf(
    1 to Table64Profile(17, 0, intArrayOf(1, 5, 0, 3, 7, 2, 4, 6)),
    4 to Table64Profile(51, 1, intArrayOf(5, 0, 7, 6, 2, 3, 1, 4)),
    7 to Table64Profile(54, 2, intArrayOf(1, 5, 0, 6, 2, 7, 3, 4)),
    10 to Table64Profile(39, 3, intArrayOf(3, 1, 7, 4, 5, 6, 2, 0)),
    13 to Table64Profile(37, 4, intArrayOf(2, 0, 6, 1, 5, 3, 7, 4)),
)

private fun rotl8(value: Int, n: Int): Int {
    val k = ((n % 8) + 8) % 8
    val v = value and 0xFF
    return ((v shl k) or (v ushr (8 - k))) and 0xFF
}

private fun u32BE(b: ByteArray, at: Int): Int =
    ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
        ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

private fun u32LE(b: ByteArray, at: Int): Int =
    (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
        ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

private fun u64LE(b: ByteArray, at: Int): Long {
    var v = 0L
    for (i in 7 downTo 0) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
    return v
}

private fun u64BE(b: ByteArray, at: Int): Long {
    var v = 0L
    for (i in 0 until 8) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
    return v
}

private fun putU32BE(b: ByteArray, at: Int, v: Int) {
    b[at] = (v ushr 24).toByte()
    b[at + 1] = (v ushr 16).toByte()
    b[at + 2] = (v ushr 8).toByte()
    b[at + 3] = v.toByte()
}

private fun putU32LE(b: ByteArray, at: Int, v: Int) {
    b[at] = v.toByte()
    b[at + 1] = (v ushr 8).toByte()
    b[at + 2] = (v ushr 16).toByte()
    b[at + 3] = (v ushr 24).toByte()
}

private fun putU64LE(b: ByteArray, at: Int, v: Long) {
    for (i in 0 until 8) b[at + i] = (v ushr (i * 8)).toByte()
}

private fun putU64BE(b: ByteArray, at: Int, v: Long) {
    for (i in 0 until 8) b[at + i] = (v ushr ((7 - i) * 8)).toByte()
}

private fun medusaFoldHalves(encoded: ByteArray): ByteArray {
    val out = ByteArray(16)
    for (i in 0 until 16) out[i] = (encoded[i].toInt() xor encoded[i + 16].toInt()).toByte()
    return out
}

private fun medusaMix32(input: ByteArray, timestamp: Int, profile: Mix32Profile): ByteArray {
    val rotation = timestamp + profile.roundOffset
    val block = ByteArray(64)
    input.copyInto(block, 0, 0, 52)
    for (i in 0 until 4) {
        block[52 + i] = rotl8(medusaMix32Padding[i].toInt(), -(rotation + 2)).toByte()
    }
    putU64BE(block, 56, 52L * 8)
    val message = IntArray(128)
    for (i in 0 until 16) message[i] = u32BE(block, i * 4)
    for (i in 16 until 128) {
        val left = message[i - 15]
        val right = message[i - 2]
        val sigma0 = Integer.rotateLeft(left, -7) xor Integer.rotateLeft(left, -18) xor (left ushr 3)
        val sigma1 = Integer.rotateLeft(right, -17) xor Integer.rotateLeft(right, -19) xor (right ushr 10)
        message[i] = message[i - 16] + sigma0 + message[i - 7] + sigma1
    }
    val initial = IntArray(8) { Integer.rotateLeft(medusaMix32Initial[it], -rotation) }
    val constants = IntArray(64) { Integer.rotateLeft(medusaMix32Constants[it], -(rotation + 1)) }
    var state = initial.copyOf()
    val lanes = profile.lanes
    for (round in profile.roundOffset until profile.roundOffset + profile.rounds) {
        val a = state[lanes[0]]
        val b = state[lanes[1]]
        val c = state[lanes[2]]
        val d = state[lanes[3]]
        val e = state[lanes[4]]
        val f = state[lanes[5]]
        val g = state[lanes[6]]
        val h = state[lanes[7]]
        val choose = g xor (e and (f xor g))
        val majority = ((a or b) and c) or (a and b)
        val sigma0 = Integer.rotateLeft(a, -2) xor Integer.rotateLeft(a, -13) xor Integer.rotateLeft(a, -22)
        val sigma1 = Integer.rotateLeft(e, -6) xor Integer.rotateLeft(e, -11) xor Integer.rotateLeft(e, -25)
        val sum1 = h + sigma1 + choose + constants[round and 63] + message[round and 127]
        val sum2 = sigma0 + majority
        val following = intArrayOf(state[7], state[0], state[1], state[2], state[3], state[4], state[5], state[6])
        following[(lanes[3] + 1) and 7] = d + sum1
        following[profile.sum2Target] = sum1 + sum2
        state = following
    }
    val encoded = ByteArray(32)
    for (i in 0 until 8) putU32BE(encoded, i * 4, state[i] + initial[i])
    return medusaFoldHalves(encoded)
}

private fun medusaCompressWord32(
    state: IntArray,
    block: ByteArray,
    constants: IntArray,
    permutation: IntArray,
): IntArray {
    val message = IntArray(16)
    for (i in 0 until 16) message[i] = u32LE(block, permutation[i] * 4)
    var a = state[0]
    var b = state[1]
    var c = state[2]
    var d = state[3]
    for (round in 0 until 64) {
        val mix = when {
            round < 16 -> d xor (b and (c xor d))
            round < 32 -> c xor (d and (b xor c))
            round < 48 -> b xor c xor d
            else -> c xor (b or d.inv())
        }
        val value = a + mix + message[medusaWord32Schedule[round]] + constants[round]
        val nextB = b + Integer.rotateLeft(value, medusaWord32Rotations[round])
        // Go 的元组赋值 a, b, c, d = d, nextB, b, c：右值先算完再落盘。
        val oldB = b
        val oldC = c
        a = d
        b = nextB
        c = oldB
        d = oldC
    }
    return intArrayOf(state[0] + a, state[1] + b, state[2] + c, state[3] + d)
}

private fun medusaWord32(input: ByteArray, timestamp: Int, profile: Word32Profile): ByteArray {
    val rotation = timestamp + profile.rotation
    val initial = IntArray(4) { Integer.rotateLeft(medusaWord32Initial[it], -rotation) }
    val constants = IntArray(64) {
        Integer.rotateLeft(medusaWord32Constants[it], -(rotation + 1 + it / 16))
    }
    val block = ByteArray(64)
    input.copyInto(block, 0, 0, 52)
    for (i in 0 until 8) {
        block[52 + i] = rotl8(medusaWord32Padding[i].toInt(), -(rotation + 5)).toByte()
    }
    putU32LE(block, 60, 52 * 8)
    val state = medusaCompressWord32(initial, block, constants, profile.permutation)
    val out = ByteArray(16)
    for (i in 0 until 4) {
        val mask = Integer.rotateLeft(medusaWord32Mask[i], -(rotation + 6))
        putU32LE(out, i * 4, state[i] xor mask)
    }
    return out
}

private fun medusaBuildTable64(profile: Table64Profile): Array<LongArray> {
    val inverse = IntArray(8)
    for (i in 0 until 8) inverse[profile.permutation[i]] = i
    val substitution = ByteArray(256) {
        rotl8(medusaTable64SubstitutionSeed[it].toInt(), -profile.rotation).toByte()
    }
    val key = ByteArray(16) {
        rotl8(medusaTable64KeySeed[it].toInt(), -profile.rotation).toByte()
    }
    val table = ByteArray(2048)
    for (i in 0 until 16) {
        val seed = (medusaTable64InitialSeed[i].toInt() and 0xFF) xor (medusaTable64KeySeed[i].toInt() and 0xFF)
        table[i] = rotl8(seed, -profile.rotation).toByte()
    }
    for (index in 2 until 256) {
        val previous = (index - 1) * 8
        val mixed = ByteArray(8) { table[previous + profile.permutation[it]] }
        if (index % 4 == 0) {
            val old = mixed.copyOf()
            for (lane in 0 until 8) {
                mixed[lane] = substitution[old[(lane + 3) and 7].toInt() and 0xFF]
            }
            mixed[0] = (mixed[0].toInt() xor (substitution[256 - index].toInt() and 0xFF)).toByte()
            mixed[4] = (mixed[4].toInt() xor (substitution[index].toInt() and 0xFF)).toByte()
        }
        val offset = (index - 2) * 8 + (substitution[index - 1].toInt() and 0xFF)
        val salted = substitution[(index + (key[index and 15].toInt() and 0xFF)) and 255].toInt() and 0xFF
        val position = (offset xor salted) % 2040
        for (lane in 0 until 8) {
            table[index * 8 + lane] = (table[position + lane].toInt() xor (mixed[lane].toInt() and 0xFF)).toByte()
        }
    }
    val tables = Array(8) { LongArray(256) }
    for (i in 0 until 256) tables[0][i] = u64LE(table, i * 8)
    for (lane in 1 until 8) {
        for (block in 0 until 256 step 8) {
            for (index in 0 until 8) {
                tables[lane][block + index] =
                    java.lang.Long.rotateLeft(tables[lane - 1][block + inverse[index]], -profile.rotation)
            }
        }
    }
    return tables
}

private val medusaTable64Boxes: Array<Array<LongArray>> by lazy {
    val boxes = Array(5) { Array(8) { LongArray(256) } }
    for ((_, profile) in medusaTable64Profiles) {
        boxes[profile.tableIndex] = medusaBuildTable64(profile)
    }
    boxes
}

private fun medusaTable64Round(words: LongArray, tables: Array<LongArray>): LongArray {
    val out = LongArray(8)
    for (column in 0 until 8) {
        for (lane in 0 until 8) {
            val index = ((words[(column - lane) and 7] ushr (56 - lane * 8)) and 0xFF).toInt()
            out[column] = out[column] xor tables[lane][index]
        }
    }
    return out
}

private fun medusaTable64(input: ByteArray, timestamp: Int, profile: Table64Profile): ByteArray {
    val rotation = timestamp + profile.rotation
    val initial = java.lang.Long.rotateLeft(timestamp.toLong() and 0xFFFFFFFFL, -rotation)
    var state = LongArray(8) { initial }
    val constants = LongArray(10) {
        java.lang.Long.rotateLeft(medusaTable64Constants[it], -(rotation + 1))
    }
    val message = ByteArray(128)
    input.copyInto(message, 0, 0, 52)
    message[52] = 0x80.toByte()
    putU64BE(message, 120, 52L * 8)
    val tables = medusaTable64Boxes[profile.tableIndex]
    var offset = 0
    while (offset < message.size) {
        val words = LongArray(8) { u64BE(message, offset + it * 8) }
        var key = longArrayOf(state[4], state[5], state[6], state[7], state[0], state[1], state[2], state[3])
        val cipher = LongArray(8)
        for (lane in 0 until 8) cipher[lane] = words[profile.permutation[lane]] xor key[lane]
        for (constant in constants) {
            key = medusaTable64Round(key, tables)
            key[0] = key[0] xor constant
            val mixed = medusaTable64Round(cipher, tables)
            for (lane in 0 until 8) {
                cipher[lane] = mixed[lane] xor key[lane]
            }
        }
        for (lane in 0 until 8) {
            state[lane] = state[lane] xor cipher[lane] xor words[(lane + 1) and 7]
        }
        offset += 64
    }
    val encoded = ByteArray(32)
    val lanes = intArrayOf(1, 3, 5, 6)
    for (index in 0 until 8) {
        for (lane in 0 until 4) {
            encoded[index * 4 + lane] = (state[7 - index] ushr (lanes[lane] * 8)).toByte()
        }
    }
    return medusaFoldHalves(encoded)
}

/** selector 分派：三族轮换，和官方 core 段的 16 个入口一一对应。 */
internal fun medusaCoreAlgorithm(input: ByteArray, selector: Int, timestamp: Int): ByteArray {
    medusaMix32Profiles[selector]?.let { return medusaMix32(input, timestamp, it) }
    medusaWord32Profiles[selector]?.let { return medusaWord32(input, timestamp, it) }
    medusaTable64Profiles[selector]?.let { return medusaTable64(input, timestamp, it) }
    error("Medusa selector must be between 0 and 15")
}

/** extension 段：MD5 变体 + 自定义置换，输入长度任意。 */
internal fun medusaExtensionAlgorithm(data: ByteArray): ByteArray {
    var paddingLength = 60 - data.size % 64
    if (paddingLength <= 0) paddingLength += 64
    val message = ByteArray(data.size + paddingLength + 4)
    data.copyInto(message, 0)
    val copy = minOf(paddingLength, medusaWord32Padding.size)
    for (i in 0 until copy) message[data.size + i] = medusaWord32Padding[i]
    putU32LE(message, message.size - 4, data.size * 8)
    var state = medusaWord32Initial.copyOf()
    var offset = 0
    while (offset < message.size) {
        val block = message.copyOfRange(offset, offset + 64)
        state = medusaCompressWord32(state, block, medusaWord32Constants, medusaExtensionPermutation)
        offset += 64
    }
    val out = ByteArray(16)
    for (i in 0 until 4) putU32LE(out, i * 4, state[i] xor medusaWord32Mask[i])
    return out
}
