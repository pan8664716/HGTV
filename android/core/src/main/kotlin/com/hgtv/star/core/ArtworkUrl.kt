// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 封面地址的改写：**换到免签镜像域名、按卡片尺寸取图、用 webp**。
 *
 * 平台下发的封面地址长这样（两种形态）：
 *
 * ```
 * https://p6-novel.byteimg.com/novel-pic/<hash>~tplv-shrink:640:0.image
 * https://p3-reading-sign.fqnovelpic.com/novel-pic/<hash>~tplv-81nmtwyey9-superreso-aifit:400:0.heic?lk3s=…&x-signature=…
 * ```
 *
 * 而卡片上的海报只有 304×404。三档改写按收益从大到小：
 *
 * 1. **签名域名 → 免签镜像域名**（收益最大，且是实测才发现的）。
 *    `pN-reading-sign.fqnovelpic.com` 上的模板是 `superreso-aifit:400:0.heic`——
 *    它要先解超分图再缩，**解一张要 96ms**（同尺寸 webp 只要 4ms，差 24 倍）。
 *    实测 1000 张封面里 94% 走的是这条路径，`decode` 均值 96ms 而能改写的 webp 只有 4ms：
 *    瓶颈根本不在网络（`net` 均值 24ms），全在 CPU 解 HEIC 超分图这一步。
 *    同一条路径在免签镜像域名上可以让 CDN 直接出 webp，这一步开销几乎消失。
 * 2. **模板尺寸 → 卡片尺寸**。模板一律是 `400`（首页那批是 `640`）。
 *    首页 24 张实测 3.6 MB（均值 152 KB、最大一张 1.1 MB 的 PNG）→ 0.7 MB（均值 48 KB）。
 * 3. **格式 → webp**。同一张图同一尺寸：`.image` 25 KB、`.jpeg` 25 KB、`.heic` 11 KB、
 *    **`.webp` 17 KB**。比 jpeg 省三分之一；heic 虽然更小却在 CPU 上很贵（见第 1 条），
 *    且 Android 8 上不是每台都能解（API 28 才普遍支持）。
 *
 * ## 实测边界（都是踩出来的，别再改回去）
 *
 * * **免签镜像域名的编号不是一一对应的**。`p9-reading-sign` 的图在 `p9-novel` 上是 403，
 *   在 `p3-novel` / `p6-novel` 上却是 200（同一张图的副本在多个接入点上）。
 *   因此这里一律收敛到 [FREE_HOST] 一个入口——**947 张唯一封面 947/947 全部 200**，
 *   也省掉按编号试错。
 * * **只认这两个域名族，其余一律原样返回**：`pN-reading-sign.fqnovelpic.com`（签名，重写到免签镜像）
 *   与 `pN-novel.byteimg.com`（免签，只换模板段）。别的地址不碰——模板改写只对平台自己的图片
 *   CDN 有意义，对第三方域名改过去只会拿不到图。没有模板段的地址同样不动。
 * * **不放大着要**：免签域名上要的宽度取「卡片需要的」与「地址里已有的」中较小者。
 * * 查询串（`?lk3s=…&x-signature=…`）在重写成免签地址时整个丢掉：免签域名不校验签名，
 *   留着它反而可能因为过期时间失效（实测：在签名域名上改任何一个字符都回 403，
 *   但换到免签域名之后同一张图正常出图）。
 *
 * ## 请求尺寸**固定**为 [REQUEST_WIDTH]，不再按卡片大小取
 *
 * 2026-10-17 实测（16112 张处女封面、逐条读 `X-Bdcdn-Cache-Status`）：
 *
 * | 模板 | CDN 命中率 | 冷变换 p50 | 字节 |
 * | --- | --- | --- | --- |
 * | `shrink:640:0.image`（官方首页在用的） | 76~84% | 38~127ms | 137 KB |
 * | `shrink:640:0.webp` | 58~62% | 130~177ms | 80 KB |
 * | `shrink:320:0.webp`（我们原先按卡片算的） | **26~34%** | **196~232ms** | 28 KB |
 * | 400 / 480 / 560 / 720 / 960 / 1080 各档 | **0~13%** | 190~330ms | — |
 *
 * 原因很直白：CDN 的缓存键是「图 + 模板参数」，**640 是官方全站都在用的尺寸**，
 * 边缘节点上早就变换好了；而 320 是我们自己挑的，每一个都是冷 key，
 * 要 CDN 现场做 resize + webp 编码（`Imagex-Fmt: webp2webp`）。
 * 按「命中率 × 命中耗时 + 未命中率 × 冷变换耗时」算，640 档是 **97ms**，320 档是 **177ms**。
 *
 * 字节只从 28 KB 涨到 80 KB，而设备上实测「字节翻 3 倍只多 15ms」——**延迟主导，不是带宽**。
 *
 * 三个附带好处：
 * 1. 下载到的就是 webp，可以**原样落盘**（见 `ArtworkLoader.fetch`），省掉每张约 40ms 的重新编码；
 * 2. 同一张封面在所有槽位（卡片 / 详情页大图）**共用一个地址**，磁盘缓存条目数直接减半以上；
 * 3. 请求地址与卡片测量值无关，预取与可见卡永远不会因为「量到 304 还是 284」而算出两个 key。
 *
 * 代价是解码前的源图更大（640×914 而不是 320×457），因此**解码目标仍按卡片尺寸算**
 * （见 [decodeWidth]）——位图大小不受影响，内存缓存该装多少还装多少。
 *
 * 纯函数：输入地址，输出该请求的地址。单测钉住了上面每一条边界。
 */
object ArtworkUrl {
    /**
     * **请求宽度**（固定）。
     *
     * 实测这是全站最热的尺寸（官方首页在用、CDN 命中率 58~84%，见类注释），
     * 同时字节数（webp 80 KB）远没到带宽敏感区。
     */
    const val REQUEST_WIDTH = 640
    /** 解码宽度下限：比这更小的图在电视上会明显发虚，不如多花几 KB。 */
    const val MIN_WIDTH = 160
    /** 解码宽度上限：再大就是浪费——一屏最多六列，1080p 下卡片也就 300 出头。 */
    const val MAX_WIDTH = 640
    /** 宽度取整的档位。CDN 的变换结果会被缓存，档位固定能让同一张图在不同槽位间复用。 */
    private const val STEP = 80
    /**
     * 平台封面的长宽比下限（百分比）：竖版海报实测约 70%（如 240×343）。
     *
     * 只用来在「框特别高」时反推源图至少要有多宽，不参与常规计算。
     */
    private const val MIN_ASPECT = 70

    /** 免签镜像入口：所有带签名的封面都收敛到这一个域名（实测全量可用，见类注释）。 */
    private const val FREE_HOST = "p3-novel.byteimg.com"

    /** 带防盗链签名的封面域名：`p3-reading-sign.fqnovelpic.com` 这一类（编号 3/6/9 都有）。 */
    private val signedHost = Regex("""^p\d+-reading-sign\.fqnovelpic\.com$""")

    /** 免签的图片 CDN 域名：`p3-novel.byteimg.com` 这一类（编号 3/6/9 都有）。 */
    private val freeHost = Regex("""^p\d+-novel\.byteimg\.com$""")

    /** 路径里的模板段：`~tplv-…` 到扩展名（`.image` / `.heic` / `.jpeg` …）。 */
    private val template = Regex("""~tplv-[^?/]+""")

    /**
     * 这张封面**实际应该请求**的地址。
     *
     * 尺寸固定 [REQUEST_WIDTH]（见类注释）：请求地址与卡片测量值无关，
     * 因此同一张封面在所有槽位上都是同一个地址。
     * 返回 [url] 本身表示「不改写」（不认识的域名、没有模板段）。
     */
    fun source(url: String): String {
        if (url.isEmpty()) return url
        val target = REQUEST_WIDTH
        val host = url.substringAfter("//").substringBefore('/')
        // 带签名的地址：整条路径重写到免签镜像（换域名 + 换模板 + 丢掉查询串）。
        if (signedHost.matches(host)) {
            val head = url.substringAfter("//").substringBefore('~').substringBefore('?')
            val path = head.substringAfter('/', "")
            // 路径里没有模板段（理论上不会）时不动：那种地址拿不到免签镜像的保证。
            if (path.isEmpty() || '~' !in url) return url
            return "https://$FREE_HOST/$path~tplv-shrink:$target:0.webp"
        }
        // 免签域名：只把模板段换成目标尺寸的 webp，其余（含查询串）原样。
        //
        // **原模板宽度一律覆盖，不做「不放大着要」的让步**：模板段描述的是**变换规格**
        // 而不是源图尺寸——写 640 是让 CDN 从原图重算一张 640 的，不是把原来那张拉伸。
        // 而缓存键必须与「官方那一份」完全一致才可能命中：地址里留着 200 / 400 / 1080
        // 就永远是冷 key（实测各档命中率 0~13%，640 档 58~84%）。
        if (!freeHost.matches(host)) return url
        val match = template.find(url) ?: return url
        val tail = url.substring(match.range.last + 1)
        return url.substring(0, match.range.first) + "~tplv-shrink:$target:0.webp" + tail
    }

    /**
     * **解码**目标宽度（与请求地址无关——请求一律是 [REQUEST_WIDTH]）。
     *
     * 界面把封面**整张等比放进**卡片的框里（`ImageView` 的 FIT_CENTER，不裁切），
     * 因此清晰度的要求只有一条：**源图的宽度不小于框的宽度**——那样按宽度贴满时是 1:1。
     *
     * 高度只在「框是竖长条」时才可能成为约束：源图的高 = 宽 ÷ 长宽比，而平台封面全是
     * 竖版海报（实测长宽比约 0.70，如 240×343）。因此用 [MIN_ASPECT] 兜一下：
     * 框特别高时按「源图至少这么宽才够高」反推。
     *
     * 早先这里取的是 `max(width, height)`，即框的**较长边**——卡片 304×404 于是解了 480 档，
     * **多解 2.25 倍像素**（480×686 而屏幕上只显示 283×404），实测字节数也多 42%。
     *
     * 向上取整到 [STEP] 的倍数（档位固定，同一张图在不同槽位之间才能复用同一份位图），
     * 再夹进 [MIN_WIDTH]..[MAX_WIDTH]。
     */
    fun decodeWidth(width: Int, height: Int): Int {
        val need = maxOf(width, height * MIN_ASPECT / 100, MIN_WIDTH)
        val rounded = (need + STEP - 1) / STEP * STEP
        return rounded.coerceIn(MIN_WIDTH, MAX_WIDTH)
    }
}
