// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.debug

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TLS 读数压缩的回归测试。
 *
 * 这条读数在调试面板里是**唯一**能回答「连不上是不是因为系统 TLS 太旧」的信息，
 * 因此压缩规则一旦漂移，排查时看到的结论就是错的。
 */
class TlsSummaryTest {

    @Test
    fun `四档可用只启用后两档`() {
        val raw = "可用版本=[TLSv1, TLSv1.1, TLSv1.2, TLSv1.3] 启用=[TLSv1.3, TLSv1.2]"
        assertEquals("支持 1.0~1.3 · 启用 1.3/1.2", compactTls(raw))
    }

    @Test
    fun `单档不写成区间`() {
        val raw = "可用版本=[TLSv1] 启用=[TLSv1]"
        assertEquals("支持 1.0 · 启用 1.0", compactTls(raw))
    }

    @Test
    fun `没有启用档时写无`() {
        val raw = "可用版本=[TLSv1, TLSv1.2] 启用=[]"
        assertEquals("支持 1.0~1.2 · 启用 无", compactTls(raw))
    }

    @Test
    fun `解析不出档位时原样截断到 60 字`() {
        val raw = "某种没见过的格式 ".repeat(20)
        val out = compactTls(raw)
        assertEquals(60, out.length)
        assertEquals(raw.take(60), out)
    }

    /** 真机日志里出现过 `TLSv1.3` 之外的空格与逗号，正则必须只认版本号本身。 */
    @Test
    fun `多余空格与逗号不影响解析`() {
        val raw = "可用版本=[TLSv1.2,  TLSv1.3] 启用=[ TLSv1.3 ]"
        assertEquals("支持 1.2~1.3 · 启用 1.3", compactTls(raw))
    }
}
