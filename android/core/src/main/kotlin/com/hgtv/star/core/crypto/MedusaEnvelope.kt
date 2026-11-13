package com.hgtv.star.core

import java.io.ByteArrayOutputStream

private fun rotateLeft8(value: Int, n: Int): Int {
    val x = value and 0xFF
    val s = n and 7
    return if (s == 0) x else ((x shl s) or (x ushr (8 - s))) and 0xFF
}

/** Go `medusaExpand`：把 16 字节 key 扩成 48 字节轮密钥。 */
internal fun medusaExpand(key: ByteArray, variant: Int): ByteArray {
    val out = ByteArray(48)
    for (i in key.indices) {
        out[i] = (key[i].toInt() xor ((medusaRcon[variant] ushr (8 * (i % 4))) and 0xFF).toInt()).toByte()
    }
    for (word in 4 until 12) {
        var t = intArrayOf(
            out[4 * word - 4].toInt() and 0xFF,
            out[4 * word - 3].toInt() and 0xFF,
            out[4 * word - 2].toInt() and 0xFF,
            out[4 * word - 1].toInt() and 0xFF,
        )
        if (word % 4 == 0) {
            t = intArrayOf(
                medusaSboxes[variant][t[1]],
                medusaSboxes[variant][t[2]],
                medusaSboxes[variant][t[3]],
                medusaSboxes[variant][t[0]],
            )
            val mask = if (variant == 3) 249L else 255L
            val extra = ((medusaRcon[variant] ushr ((word shl 1) and 24)) and mask).toInt() and 0xFF
            t[0] = t[0] xor extra
        }
        for (j in 0 until 4) {
            out[word * 4 + j] = (t[j] xor (out[4 * (word - 4) + j].toInt() and 0xFF)).toByte()
        }
    }
    return out
}

internal fun medusaAddKey(s: ByteArray, key: ByteArray, variant: Int) {
    for (i in 0 until 16 step 4) {
        for (j in 0 until 4) {
            s[i + j] = (s[i + j].toInt() xor key[i + medusaPerms[variant][j]].toInt()).toByte()
        }
    }
}

internal fun medusaShift(variant: Int): IntArray {
    val old = IntArray(16) { 4 * medusaPerms[variant][it / 4] + it % 4 }
    val out = old.copyOf()
    val swaps = intArrayOf(1, 9, 5, 13, 9, 1, 13, 5, 2, 14, 6, 2, 10, 6, 14, 10)
    for (k in swaps.indices step 2) {
        out[swaps[k]] = old[swaps[k + 1]]
    }
    val dsts = intArrayOf(3, 7, 11, 15)
    for (i in dsts.indices) {
        out[dsts[i]] = old[medusaRow3[variant][i]]
    }
    return out
}

internal fun medusaSubShift(s: ByteArray, variant: Int) {
    val old = s.copyOf()
    val shift = medusaShift(variant)
    for (i in 0 until 16) {
        s[i] = medusaSboxes[variant][old[shift[i]].toInt() and 0xFF].toByte()
    }
}

internal fun medusaXtime(b: Int): Int =
    if (b and 128 != 0) ((b shl 1) xor 0x1b) and 0xFF else (b shl 1) and 0xFF

internal fun medusaMix(s: ByteArray, variant: Int) {
    val t = IntArray(16)
    for (i in 0 until 16 step 4) {
        for (j in 0 until 4) {
            t[i + j] = s[i + medusaPerms[variant][j]].toInt() and 0xFF
        }
    }
    for (lane in 0 until 4) {
        val a = t[lane]
        val b = t[lane + 4]
        val c = t[lane + 8]
        val d = t[lane + 12]
        s[lane] = (medusaXtime(a) xor medusaXtime(b) xor b xor c xor d).toByte()
        s[lane + 4] = (a xor medusaXtime(b) xor medusaXtime(c) xor c xor d).toByte()
        s[lane + 8] = (a xor b xor medusaXtime(c) xor medusaXtime(d) xor d).toByte()
        s[lane + 12] = (medusaXtime(a) xor a xor b xor c xor medusaXtime(d)).toByte()
    }
}

internal fun medusaSmall(data: ByteArray, variant: Int, key: ByteArray, iv: ByteArray): ByteArray {
    val keys = medusaExpand(key, variant)
    val pad = 16 - data.size % 16
    val plain = data + ByteArray(pad) { pad.toByte() }
    val state = iv.copyOf()
    val out = ByteArrayOutputStream(plain.size)
    var off = 0
    while (off < plain.size) {
        val s = ByteArray(16) { (plain[off + it].toInt() xor state[it].toInt()).toByte() }
        medusaAddKey(s, keys.copyOfRange(0, 16), variant)
        medusaSubShift(s, variant)
        medusaMix(s, variant)
        medusaAddKey(s, keys.copyOfRange(16, 32), variant)
        medusaSubShift(s, variant)
        medusaAddKey(s, keys.copyOfRange(32, 48), variant)
        for (i in 0 until 16) {
            state[i] = (s[i].toInt() xor keys[16 + i].toInt()).toByte()
        }
        out.write(state)
        off += 16
    }
    return out.toByteArray()
}

internal fun medusaObscure(plain: ByteArray, digest: ByteArray): ByteArray {
    val a = ByteArray(plain.size)
    for (i in plain.indices) {
        val k0 = digest[(4 * i) % 32].toInt() and 0xFF
        val k1 = digest[(4 * i + 1) % 32].toInt() and 0xFF
        val x = ((rotateLeft8(plain[i].toInt() and 0xFF, 4) + k0) and 0xFF).inv() xor k1
        a[a.size - 1 - i] = (((rotateLeft8(x and 0xFF, 3) + k1) and 0xFF).inv() xor k0).toByte()
    }
    val last = a.size - 1
    require(last >= 2) { "Medusa protobuf too short" }
    a[0] = (a[0] + ((a[last].toInt() xor a[last - 1].toInt()).inv())).toByte()
    a[1] = (a[1] + (a[last].toInt() xor a[0].toInt() xor 254)).toByte()
    for (i in 0 until a.size - 3) {
        val mixed = (a[i].toInt() xor rotateLeft8(a[i + 1].toInt() and 0xFF, 3) xor (i + 2)).inv()
        a[i + 2] = (a[i + 2] + mixed).toByte()
    }
    a[last] = (a[last].toInt() xor a[last - 1].toInt()).toByte()
    var sum = 0
    for (i in 1 until a.size) {
        sum = (sum + (a[i].toInt() and 0xFF)) and 0xFF
    }
    a[0] = ((a[0].toInt() xor a[1].toInt()) + sum).toByte()
    return a
}

/** 返回 (layout, tail)。`layout[i] = [offset, bitGroup]`。 */
internal fun medusaLayout(n: Int): Pair<Array<IntArray>, Int> {
    if (n == 11) {
        return Array(8) { intArrayOf(0, it) } to 7
    }
    if (n >= 248) {
        return Array(31) { intArrayOf(it * 8, 0) } to 31
    }
    error("unsupported Medusa inner length")
}

internal fun medusaEnvelope(
    plain: ByteArray,
    timestamp: Long,
    nonce: Long,
    flags: Long,
    prefix: ByteArray,
    mode: Int,
    key: ByteArray,
): String {
    if (mode == 1) error("SDK mode 1 has no supported signature")
    val n = le32(nonce.toInt())
    val f = le64(flags)
    val digest = sm3(key + n + key)
    var mixed = ByteArray(0)
    if (mode == 0) {
        val m = prefix + medusaObscure(plain, digest)
        val mask = be32(medusaAP(n.copyOfRange(2, 4), 0, 7, 3, 11, 5).toInt())
        mixed = ByteArray(m.size) { (m[m.size - 1 - it].toInt() xor mask[it % 4].toInt()).toByte() }
    }
    val inner = byteArrayOf(medusaAP(key, 0, 7, 3, 11, 5).toByte()) +
        f + mixed + n.copyOfRange(2, 4)
    val (layout, tail) = medusaLayout(inner.size)
    val small = ByteArray(31)
    for (i in layout.indices) {
        for (j in medusaSelect.indices) {
            val sel = medusaSelect[j]
            val bit = (inner[layout[i][0] + sel[0]].toInt() ushr ((sel[1] + layout[i][1]) and 7)) and 1
            small[i] = (small[i].toInt() or (bit shl j)).toByte()
        }
    }
    val cipher = medusaSmall(
        small,
        (timestamp and 3).toInt(),
        digest("MD5", key.copyOfRange(0, 16)),
        digest("MD5", key.copyOfRange(16, 32)),
    )
    val patched = inner.copyOf()
    for (i in layout.indices) {
        for (p in medusaPatch) {
            val at = layout[i][0] + p[1]
            val bit = (p[2] + layout[i][1]) and 7
            val cipherBit = (cipher[i].toInt() ushr p[0]) and 1
            patched[at] = ((patched[at].toInt() and (1 shl bit).inv()) or (cipherBit shl bit)).toByte()
        }
    }
    val outer = ByteArrayOutputStream()
    for (word in longArrayOf(5, 0xca4f4b2d, 0x430d7549, 0x2caeb53f, 0x56cc6d22)) {
        outer.write(le32((timestamp xor word).toInt()))
    }
    outer.write(n.copyOfRange(0, 2))
    outer.write(0)
    outer.write(cipher.size - tail)
    outer.write(cipher.copyOfRange(tail, cipher.size))
    outer.write(patched)
    return outer.toByteArray().base64()
}
