package com.hgtv.star.core

import java.io.ByteArrayOutputStream

/** Go `medusaInteger`：把各种整数统一成 Long。 */
internal fun medusaInteger(value: Any?): Long = when (value) {
    is Long -> value
    is Int -> value.toLong()
    is Byte -> value.toLong()
    else -> error("invalid Medusa integer ${value?.let { it::class }}")
}

/** Go `medusaVarint`：无符号 64 位 varint。 */
internal fun medusaVarint(out: ByteArrayOutputStream, value: Long) {
    var v = value
    while (java.lang.Long.compareUnsigned(v, 128L) >= 0) {
        out.write(((v and 0x7F) or 0x80).toInt())
        v = v ushr 7
    }
    out.write((v and 0x7F).toInt())
}

/**
 * Go `medusaPB`：按 schema 编码 protobuf。
 *
 * 值类型约定：整数用 Long/Int，float 用 Float，bytes/string 用 ByteArray/String，
 * 嵌套消息用 `Map<Int, Any?>`。typ 语义与 Go 一致：
 * 1 = sint32，4 = sint64，13 = uint64，6 = fixed32，10 = float，14 = 截断到 0 的 bytes，
 * 15 = bytes，16 = 嵌套消息。
 */
internal fun medusaPB(values: Map<Int, Any?>, schema: List<MedusaDescriptor>): ByteArray {
    val out = ByteArrayOutputStream()
    for (d in schema) {
        val v = values[d.id] ?: continue
        when (d.typ) {
            1, 4, 13 -> {
                val n = medusaInteger(v)
                if (n == 0L) continue
                val x: Long = when (d.typ) {
                    1 -> {
                        require(n >= Int.MIN_VALUE.toLong() && n <= Int.MAX_VALUE.toLong()) {
                            "Medusa int32 overflow"
                        }
                        val i = n.toInt()
                        (((i shl 1) xor (i shr 31)).toLong()) and 0xFFFFFFFFL
                    }
                    4 -> (n shl 1) xor (n shr 63)
                    else -> n
                }
                medusaVarint(out, (d.id shl 3).toLong())
                medusaVarint(out, x)
            }

            6, 10 -> {
                val x: Int = if (d.typ == 10) {
                    val f = v as Float
                    if (f == 0f) continue
                    java.lang.Float.floatToRawIntBits(f)
                } else {
                    val n = medusaInteger(v).toInt()
                    if (n == 0) continue
                    n
                }
                medusaVarint(out, ((d.id shl 3) or 5).toLong())
                out.write(le32(x))
            }

            else -> {
                @Suppress("UNCHECKED_CAST")
                val raw: ByteArray = if (d.typ == 16) {
                    medusaPB(v as Map<Int, Any?>, d.fields)
                } else {
                    when (v) {
                        is ByteArray -> v
                        is String -> v.toByteArray()
                        else -> error("invalid Medusa bytes ${v::class}")
                    }
                }
                val data = if (d.typ == 14) {
                    val at = raw.indexOf(0.toByte())
                    if (at >= 0) raw.copyOf(at) else raw
                } else raw
                if (data.isEmpty() && d.typ != 16) continue
                medusaVarint(out, ((d.id shl 3) or 2).toLong())
                medusaVarint(out, data.size.toLong())
                out.write(data)
            }
        }
    }
    return out.toByteArray()
}
