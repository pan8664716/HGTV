// Derived from drpys 22261ad; GPL-3.0-only. Protocol-specific transform, not standard AES.
package com.hgtv.star.core

class AesV3(key: ByteArray, timestamp: Int) {
    private val word = timestamp and 3
    private val box = VendorConstants.sbox.copyOfRange(word*256,(word+1)*256).map { it.u() }
    private val con = arrayOf(intArrayOf(1,0,2,3),intArrayOf(2,0,3,1),intArrayOf(0,1,3,2),intArrayOf(1,0,2,3))[word]
    private val order = arrayOf(intArrayOf(0,9,14,11,4,13,2,7,8,1,6,15,12,5,10,3),intArrayOf(0,9,14,15,4,13,2,7,8,1,6,3,12,5,10,11),intArrayOf(0,9,14,7,4,13,2,11,8,1,6,3,12,5,10,15),intArrayOf(0,9,14,11,4,13,2,7,8,1,6,15,12,5,10,3))[word]
    private val keys: Array<IntArray>
    init {
        val initial = intArrayOf(0xca025ddc.toInt(),0x823dc546.toInt(),0xc9420583.toInt(),0xc298225f.toInt())[word]
        val expanded = (xor(le32(initial)+le32(initial)+le32(initial)+le32(initial),key)+ByteArray(32)).map { it.u() }.toIntArray()
        var rounds = 8
        for (i in 4 until 12) {
            val at = 4*(i-1); var a=expanded[at]; var b=expanded[at+1]; var c=expanded[at+2]; var d=expanded[at+3]
            if (i and 3 == 0) { val t=((initial shr (rounds and 24)) xor box[b]) and 255; b=box[c]; c=box[d]; d=box[a]; a=t }
            rounds+=2
            expanded[at+4]=a xor expanded[at-12]; expanded[at+5]=b xor expanded[at-11]; expanded[at+6]=c xor expanded[at-10]; expanded[at+7]=d xor expanded[at-9]
        }
        keys = Array(12) { expanded.copyOfRange(it*4,it*4+4) }
    }
    private fun block(value: ByteArray): ByteArray {
        val rows = Array(4) { i -> IntArray(4) { j -> value[i*4+j].u() } }
        fun addCon(offset: Int) { for(i in 0..3) for(j in 0..3) rows[i][j] = rows[i][j] xor keys[offset+i][con[j]] }
        addCon(0)
        for(round in 1..2) {
            for(i in 0..3) for(j in 0..3) rows[i][j]=box[rows[i][j]]
            val before = rows.map { it.copyOf() }
            for(i in 0..3) rows[i]=before[con[i]].copyOf()
            val flat = rows.flatMap { it.toList() }
            for(i in 0..15) rows[i/4][i%4]=flat[order[i]]
            if(round == 1) {
                for(i in 0..3) { val old=rows[i].copyOf(); rows[i]=IntArray(4) { old[con[it]] } }
                fun xt(x: Int) = ((x shl 1) xor (if(x and 128 != 0) 0x1b else 0)) and 255
                for(i in 0..3) {
                    val t=rows[0][i] xor rows[1][i] xor rows[2][i] xor rows[3][i]; val u=rows[0][i]
                    rows[0][i]=rows[0][i] xor t xor xt(rows[0][i] xor rows[1][i])
                    rows[1][i]=rows[1][i] xor t xor xt(rows[1][i] xor rows[2][i])
                    rows[2][i]=rows[2][i] xor t xor xt(rows[2][i] xor rows[3][i])
                    rows[3][i]=rows[3][i] xor t xor xt(rows[3][i] xor u)
                }
            }
            addCon(round*4)
        }
        for(i in 0..3) for(j in 0..3) rows[i][j]=rows[i][j] xor keys[4+i][j]
        return rows.flatMap { it.toList() }.map { it.toByte() }.toByteArray()
    }
    fun encrypt(source: ByteArray, iv: ByteArray): ByteArray {
        require(source.size >= 248)
        val plaintext=ByteArray(32)
        for(i in 0 until 31) {
            val at=i*8
            plaintext[i]=(((source[at].u() shr 4) and 2) or (source[at+1].u() and 64) or ((source[at+2].u() shr 2) and 1) or ((source[at+3].u() shl 3) and 128) or ((source[at+4].u() shr 1) and 4) or ((source[at+5].u() shl 3) and 16) or ((source[at+6].u() shl 5) and 32) or ((source[at+7].u() shr 4) and 8)).toByte()
        }
        plaintext[31]=1
        val first=block(xor(plaintext.copyOfRange(0,16),iv))
        val key=first+block(xor(plaintext.copyOfRange(16,32),first))
        val out=source.copyOf()
        for(i in 0 until 31) {
            val at=i*8; val k=key[i].u()
            out[at]=((out[at].u() and 0xdf) or ((k shl 4) and 32)).toByte()
            out[at+1]=((out[at+1].u() and 0xbf) or (k and 64)).toByte()
            out[at+2]=((out[at+2].u() and 0xfb) or ((k shl 2) and 4)).toByte()
            out[at+3]=((out[at+3].u() and 0xef) or ((k shr 3) and 16)).toByte()
            out[at+4]=((out[at+4].u() and 0xf7) or ((k+k) and 8)).toByte()
            out[at+5]=((out[at+5].u() and 0xfd) or ((k shr 3) and 2)).toByte()
            out[at+6]=((out[at+6].u() and 0xfe) or ((k shr 5) and 1)).toByte()
            out[at+7]=((out[at+7].u() and 0x7f) or ((k shl 4) and 128)).toByte()
        }
        return byteArrayOf(key.last())+out
    }
}
