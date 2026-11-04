// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 区号与号码归一：两端（电视 / 手机）走同一份实现，这里钉住它。
 *
 * 手机端原先没有区号选择，号码交给 `e164Phone` 猜：只有「11 位且以 1 开头」才当中国大陆
 * 补 `+86`，其余一律 `+数字`。香港 8 位号码于是被拼成 `+91234567`（少了 `852`），
 * 发码必然失败——这正是「电视能登录、手机不行」的根因。
 */
class PhoneRegionTest {

    @Test fun `默认是中国大陆且按所选区号拼成 E164`() {
        assertEquals("+86", PhoneRegion.dialOf(PhoneRegion.DEFAULT))
        assertEquals("+8613812345678", PhoneRegion.normalize("138 1234 5678", PhoneRegion.DEFAULT))
    }

    @Test fun `香港八位号码按 852 拼而不是当成大陆号码`() {
        val hk = PhoneRegion.labels.indexOfFirst { it.startsWith("+852") }
        assertEquals("+85291234567", PhoneRegion.normalize("9123 4567", hk))
    }

    @Test fun `用户自己敲了加号就原样送出不再套一次区号`() {
        // 否则「+86」按钮 + 「+8613…」输入框会拼成 `+86+8613…`。
        assertEquals("+85291234567", PhoneRegion.normalize("+852 9123 4567", PhoneRegion.DEFAULT))
        assertEquals("+8613812345678", PhoneRegion.normalize("+86 13812345678", 0))
    }

    @Test fun `空号码返回 null`() {
        assertNull(PhoneRegion.normalize("", 0))
        assertNull(PhoneRegion.normalize("  -  ", 0))
        assertNull(PhoneRegion.normalize("+", 0))
    }

    @Test fun `从 E164 拆出区号与本地号码`() {
        val hk = PhoneRegion.labels.indexOfFirst { it.startsWith("+852") }
        assertEquals(hk to "91234567", PhoneRegion.split("+85291234567"))
        assertEquals(PhoneRegion.DEFAULT to "13812345678", PhoneRegion.split("+8613812345678"))
    }

    @Test fun `认不出区号时退回默认档并保留全部数字`() {
        // `+1` 与 `+12…` 这种前缀冲突要按最长匹配：`+1` 就是美国 / 加拿大那一档。
        val us = PhoneRegion.labels.indexOfFirst { it.startsWith("+1 ") }
        assertEquals(us, PhoneRegion.indexOf("+12025550123"))
        // 完全不认识的号段：认不出，退回默认档，数字一个都不丢。
        assertEquals(-1, PhoneRegion.indexOf("+9995551234"))
        assertEquals(PhoneRegion.DEFAULT to "9995551234", PhoneRegion.split("+9995551234"))
    }

    @Test fun `下标越界收敛到合法范围`() {
        assertEquals(0, PhoneRegion.clamp(-3))
        assertEquals(PhoneRegion.labels.lastIndex, PhoneRegion.clamp(99))
    }
}
