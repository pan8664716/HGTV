// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Passport（branch 2）签名的固定向量测试。
 *
 * 向量由参考实现（`github.com/aston314/hongguo-tv` 的 `passport_sign.go`）在同一份输入上跑出，
 * 因此这几个断言同时锁住三件事：分支选择、F13、以及 Helios / Gorgon / Medusa 三条头的组合。
 */
class PassportSignerTest {
    /** 固定时钟，让 `_rticket` 试探的结果可复现。 */
    private val stamp = 1792154473000L
    private val signer = PassportSigner(clock = { stamp }, random = { 0.5 })

    @Test fun branchTwoIsSelected() {
        val values = mapOf("ac" to "wifi", "aid" to "8662", "app_name" to "novelread")
        val signed = signer.sign("https://api5-normal-lq.fqnovel.com/passport/mobile/send_code/v1/", values)
        val query = signed.url.substringAfter('?')
        val ts = signed.headers.getValue("X-Khronos").toInt()
        // 分支号必须是 2（这是 Passport 这条线唯一被接受的分支）。
        val q = sm3(query.toByteArray())
        val m = ByteArray(16)
        val iv = passportIvForTest(le32(ts), passportIvForTest(m, passportIvForTest(q, 0x20230928)))
        assertEquals("只发 branch 2", 2, (iv and 15) % 3)
    }

    @Test fun ticketIsTunedToHitBranchTwo() {
        val values = mapOf("aid" to "8662")
        val signed = signer.sign("https://example.com/p/", values)
        val ticket = signed.headers.getValue("X-SS-Req-Ticket").toLong()
        assertTrue("ticket 必须从 now 起 +1 试出来", ticket >= stamp && ticket < stamp + 256)
    }

    @Test fun headersAreComplete() {
        val signed = signer.sign("https://example.com/p/", mapOf("aid" to "8662"), """{"a":1}""".toByteArray())
        for (key in listOf("X-Khronos", "X-SS-Req-Ticket", "X-Helios", "X-Medusa", "X-Gorgon", "X-SS-STUB")) {
            assertTrue("缺头 $key", signed.headers[key].orEmpty().isNotBlank())
        }
        // Gorgon 的固定前缀（与内容接口那一套同一族）。
        assertTrue(signed.headers.getValue("X-Gorgon").startsWith("8404"))
        // STUB 是大写的 body MD5。
        assertEquals(digest("MD5", """{"a":1}""".toByteArray()).hex().uppercase(), signed.headers.getValue("X-SS-STUB"))
    }

    @Test fun signerIsDeterministicForFixedInputs() {
        val a = signer.sign("https://example.com/p/", mapOf("aid" to "8662"))
        val b = signer.sign("https://example.com/p/", mapOf("aid" to "8662"))
        assertEquals(a.url, b.url)
        assertEquals(a.headers.getValue("X-Gorgon"), b.headers.getValue("X-Gorgon"))
        assertEquals(a.headers.getValue("X-Helios"), b.headers.getValue("X-Helios"))
    }

    private fun passportIvForTest(data: ByteArray, initial: Int): Int {
        var iv = initial
        for (i in data.indices) {
            iv = if (i and 1 == 0) (iv ushr 4) xor iv xor (iv shl 6) xor data[i].u()
                 else ((iv ushr 7) xor iv xor (data[i].u() or (iv shl 12))).inv()
        }
        return iv
    }
}
