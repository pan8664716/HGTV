// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * [PublicDns] 的活体校验：手写的 DNS 报文必须能被公共 DNS 认，解析结果必须能用。
 *
 * 需要外网，默认跳过（与 `Live*` 那批一致）：`HONGGUO_DNS=1 ./gradlew :core:test --tests '*PublicDnsTest'`
 */
class PublicDnsTest {
    @Test fun resolvesVideoCdnHost() {
        assumeTrue("HONGGUO_DNS", System.getenv("HONGGUO_DNS") == "1")
        NetLog.log={ println("NETLOG $it") }
        val addresses=PublicDns.lookup("v5-se-zjnbtc-reading-video-a.qznovelvod.com")
        println("addresses=${addresses.map { it.hostAddress }}")
        assertTrue("视频 CDN 域名应能解析出地址", addresses.isNotEmpty())
        assertTrue("解析出的地址必须能反查成 IP", addresses.all { it.hostAddress!=null })
    }

    @Test fun plainHostWorks() {
        assumeTrue("HONGGUO_DNS", System.getenv("HONGGUO_DNS") == "1")
        NetLog.log={ println("NETLOG $it") }
        println("plain=${PublicDns.lookup("www.qq.com").map { it.hostAddress }}")
    }

    @Test fun unknownHostGivesNothing() {
        assumeTrue("HONGGUO_DNS", System.getenv("HONGGUO_DNS") == "1")
        assertTrue(PublicDns.lookup("no-such-host-$(System.nanoTime()).qznovelvod.com").isEmpty())
    }
}
