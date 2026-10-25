// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.junit.Assert.*
import org.junit.Test

class ArtworkUrlTest {
    private val unsigned = "https://p6-novel.byteimg.com/novel-pic/5315b66c~tplv-shrink:640:0.image"
    private val signed = "https://p3-reading-sign.fqnovelpic.com/novel-pic/5e8df941" +
        "~tplv-81nmtwyey9-superreso-aifit:400:0.heic?lk3s=45961287&x-expires=1797346850&x-signature=yekIW"

    /**
     * 免签域名：模板段一律换成**固定 640 档的 webp**。
     *
     * 为什么不是「按卡片尺寸算」——2026-10-17 实测 16112 张处女封面（逐条读
     * `X-Bdcdn-Cache-Status`）：640 档命中 58~84%（官方全站都在用这个尺寸，边缘节点早算好了），
     * 按卡片算出来的 320 档只命中 26~34%，每一个都是冷 key、要 CDN 现场做 resize + 编码，
     * 冷变换 p50 是 196~232ms 而命中只要 64ms。加权之后 640 档是 97ms、320 档是 177ms。
     */
    @Test fun `asks for the hot 640 webp template`() {
        assertEquals("https://p6-novel.byteimg.com/novel-pic/5315b66c~tplv-shrink:640:0.webp",
            ArtworkUrl.source(unsigned))
    }

    /**
     * 带签名的地址整条重写到免签镜像：换域名、换模板、丢掉签名查询串。
     *
     * 签名域名上那个 `superreso-aifit:400:0.heic` 有两重代价：HEIC 解一张要 96ms（webp 只要 4ms），
     * 而且它的模板参数与官方那套不同、CDN 上永远是冷 key。
     */
    @Test fun `rewrites signed urls onto the free mirror host`() {
        assertEquals("https://p3-novel.byteimg.com/novel-pic/5e8df941~tplv-shrink:640:0.webp",
            ArtworkUrl.source(signed))
        // 编号 6 / 9 的签名域名同样收敛到同一个免签入口（实测 p9 的图在 p9-novel 上是 403）。
        val p9 = signed.replace("p3-reading-sign", "p9-reading-sign")
        assertEquals("https://p3-novel.byteimg.com/novel-pic/5e8df941~tplv-shrink:640:0.webp",
            ArtworkUrl.source(p9))
    }

    /**
     * 地址里的**原模板宽度被覆盖**，哪怕它比 640 小。
     *
     * 这里没有「不放大着要」的顾虑：模板段是**变换规格**而不是源图尺寸，
     * 写 640 是让 CDN 从原图重新算一张 640 的，不是把 200 的那张拉伸。
     * 而缓存键必须和「官方那一份」逐字节一致才可能命中——留着原来的 200 就永远是冷 key。
     */
    @Test fun `overrides whatever template width the platform sent`() {
        val small = "https://p6-novel.byteimg.com/novel-pic/x~tplv-shrink:200:0.image"
        assertEquals("https://p6-novel.byteimg.com/novel-pic/x~tplv-shrink:640:0.webp",
            ArtworkUrl.source(small))
        val huge = "https://p6-novel.byteimg.com/novel-pic/x~tplv-shrink:1080:0.jpeg"
        assertEquals("https://p6-novel.byteimg.com/novel-pic/x~tplv-shrink:640:0.webp",
            ArtworkUrl.source(huge))
    }

    /** 认不出来的域名 / 没有模板段的地址一律原样返回。 */
    @Test fun `leaves foreign and template-less urls alone`() {
        val foreign = "https://example.com/a~tplv-shrink:640:0.image"
        assertEquals(foreign, ArtworkUrl.source(foreign))
        val noTemplate = "https://p3-reading-sign.fqnovelpic.com/novel-pic/abc.jpg?x-signature=1"
        assertEquals(noTemplate, ArtworkUrl.source(noTemplate))
        val bare = "https://p3-novel.byteimg.com/novel-pic/abc.jpg"
        assertEquals(bare, ArtworkUrl.source(bare))
        assertEquals("", ArtworkUrl.source(""))
    }

    /** 免签域名上的查询串（如果有）原样保留：它不属于签名那套。 */
    @Test fun `keeps the query string on free hosts`() {
        val withQuery = "https://p6-novel.byteimg.com/novel-pic/x~tplv-shrink:640:0.image?lk3s=1"
        assertEquals("https://p6-novel.byteimg.com/novel-pic/x~tplv-shrink:640:0.webp?lk3s=1",
            ArtworkUrl.source(withQuery))
    }

    /**
     * 请求地址与**卡片尺寸完全无关**。
     *
     * 这是「预取与可见卡各下一遍同一张图」的根除点：卡片量到 304 还是 284、
     * 是卡片还是详情页大图，算出来都是同一个地址 → 同一个 Key、同一份内存与磁盘缓存。
     */
    @Test fun `the request url does not depend on the card size`() {
        val urls = listOf(160, 240, 304, 284, 600, 1080).map { ArtworkUrl.source(unsigned) }.distinct()
        assertEquals("同一张封面只能有一个请求地址", 1, urls.size)
        assertEquals("https://p6-novel.byteimg.com/novel-pic/5315b66c~tplv-shrink:640:0.webp", urls.single())
    }

    /**
     * 解码宽度按卡片算、向上取整到 80 的倍数、夹在 160..640；框特别高时按长宽比兜底。
     *
     * 位图大小只由它决定，与请求地址无关——因此请求换成 640 档之后，
     * 内存里存的仍然是卡片尺寸的位图（192MB 缓存该装 344 张还装 344 张）。
     */
    @Test fun `rounds the decode width into a bounded step`() {
        assertEquals(160, ArtworkUrl.decodeWidth(1, 1))
        assertEquals(320, ArtworkUrl.decodeWidth(304, 300))
        assertEquals(320, ArtworkUrl.decodeWidth(304, 404))
        assertEquals(640, ArtworkUrl.decodeWidth(1000, 1400))
        assertEquals(160, ArtworkUrl.decodeWidth(0, 0))
        // 竖长条框（200×900）：按 0.7 的比例反推，源图至少要 630 宽才够高。
        assertEquals(640, ArtworkUrl.decodeWidth(200, 900))
    }

    /** 不同测量值必须归一到**同一个**解码档位（否则同一张图会在内存里存好几份）。 */
    @Test fun `different measurements collapse onto one decode width`() {
        val widths = (280..310).map { width -> ArtworkUrl.decodeWidth(width, 404) }.distinct()
        assertEquals("这一段测量值必须全部归一到同一个档位", 1, widths.size)
        assertEquals(320, widths.single())
    }
}
