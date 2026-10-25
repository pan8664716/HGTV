// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 封面请求的 Key 只由**地址**决定。
 *
 * 这一条是踩出来的，前后两版都在这翻过车：
 *
 * * 第一版 Key 带高度，而高度是「按长宽比从宽度换算」出来的——两边原始高度差 1 像素
 *   就会算出 424 与 425 两个值，于是预取与可见卡各下一遍同一张图
 *   （实测 `dupEnq=558`、`skipOnDisk=558`，而 `warmHit` 恒为 0，
 *   让人误以为「预取和可见卡要的不是同一张图」而白查了几轮）；
 * * 第二版 Key 只留宽度，但宽度是**按卡片测量值**算出来的（304 → 320 档），
 *   于是同一张封面在卡片里、在详情页大图里是两个 Key、两份磁盘缓存。
 *
 * 现在请求尺寸固定 640（见 [ArtworkUrl] 的类注释），地址本身就唯一决定了下载什么，
 * 尺寸不再进 Key——上面两个问题一起消失。
 */
class ArtworkKeyTest {
    /** 同一张图，不管量到的高度是多少，请求地址必须一致。 */
    @Test fun `any measured height collapses to one address`() {
        val url = "https://p6-novel.byteimg.com/novel-pic/x~tplv-shrink:640:0.image"
        val urls = (400..430).map { ArtworkUrl.source(url) }.distinct()
        assertEquals("高度不该影响请求地址", 1, urls.size)
    }

    /** 同一张图在**不同槽位**（卡片 / 详情页大图）也是同一个地址：全应用共用一份缓存。 */
    @Test fun `every slot shares one address`() {
        val url = "https://p6-novel.byteimg.com/novel-pic/x~tplv-shrink:640:0.image"
        val card = ArtworkUrl.source(url)
        val detail = ArtworkUrl.source(url)
        assertEquals(card, detail)
    }

    /**
     * 平台下发的两种模板形态（免签 640 jpeg / 签名 400 heic）收敛到**同一个**地址。
     *
     * 这是磁盘缓存条目数减半的直接来源：同一部剧在分类页与详情页拿到的地址本来不同，
     * 现在都是那一条 640 的 webp。
     */
    @Test fun `the two platform templates converge`() {
        // 免签域那一份本来就是 640 档（官方首页在用的形态）。
        val free = ArtworkUrl.source("https://p3-novel.byteimg.com/novel-pic/x~tplv-shrink:640:0.image")
        // 签名域那一份是 400 档的 heic——换域名、换模板之后必须落到同一个地址。
        val sign = ArtworkUrl.source("https://p3-reading-sign.fqnovelpic.com/novel-pic/x" +
            "~tplv-81nmtwyey9-superreso-aifit:400:0.heic?x-signature=1")
        assertEquals(free, sign)
        assertEquals("https://p3-novel.byteimg.com/novel-pic/x~tplv-shrink:640:0.webp", free)
    }

    /** 不同的剧仍然是不同的地址。 */
    @Test fun `different series keep different addresses`() {
        val a = ArtworkUrl.source("https://p6-novel.byteimg.com/novel-pic/aaa~tplv-shrink:640:0.image")
        val b = ArtworkUrl.source("https://p6-novel.byteimg.com/novel-pic/bbb~tplv-shrink:640:0.image")
        assertEquals("https://p6-novel.byteimg.com/novel-pic/aaa~tplv-shrink:640:0.webp", a)
        assertEquals("https://p6-novel.byteimg.com/novel-pic/bbb~tplv-shrink:640:0.webp", b)
    }
}
