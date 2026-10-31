// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 卡片上那个「热度」数字怎么显示。
 *
 * ## 为什么需要它
 *
 * 热度有两个来源，格式还不一样：
 *
 * | 来源 | 形态 | 例子 |
 * | --- | --- | --- |
 * | 榜单接口 `recommend_reason_list` | **服务端已经排好的字符串** | `9014万` |
 * | 搜索卡片 `rec_text` | 原始文案，解析成数字存在 [Series.heat] | `1204万热度` → `12040000` |
 *
 * 两端各写一遍「优先用哪个、数字怎么换算」，就会出现同一部剧在电视上写 `9014万`、
 * 在手机上写 `9014.0万` 这种事。这里把它收成一个纯函数，配 `HeatLabelTest`。
 *
 * ## 没有热度时返回空串
 *
 * 调用方据此**整枚角标收起**——分类页、相关剧集、本机记录这些入口本来就没有热度，
 * 留一个「热度暂无」的占位只是噪音。
 */
object HeatLabel {

    /** 一万 / 一亿：与接口文案里的单位一致。 */
    private const val WAN = 10_000L
    private const val YI = 100_000_000L

    /**
     * 这一张卡要显示的热度文案。
     *
     * @param serverText 榜单接口给的原串（`9014万`）；没有就传 `null` 或空串。
     * @param value 解析出来的数字（[Series.heat]）；`0` 表示接口没给。
     */
    fun of(serverText: String?, value: Long): String {
        // 服务端那串是它自己排好的，**原样用**：它可能带「热度」后缀，剥掉即可。
        // 不做二次换算——`9014万` 换成 `9014.0万` 只会让人以为精度变了。
        val raw = serverText.orEmpty().trim().removeSuffix("热度").trim()
        if (raw.isNotEmpty()) return raw
        return format(value)
    }

    /**
     * 角标上真正显示的文案：**火苗 + 数值**（`🔥9014万`）。
     *
     * 火苗就用 emoji 字符，不自绘：两端用的是同一套系统 emoji 字体，
     * 形状天然一致，也不用为它维护一份路径。没有热度时返回空串，调用方整枚收起。
     */
    fun badge(serverText: String?, value: Long): String {
        val text = of(serverText, value)
        return if (text.isEmpty()) "" else "$FIRE$text"
    }

    /** 火苗。写成常量而不是散在各处，将来换图标只改这里。 */
    const val FIRE = "\uD83D\uDD25"

    /**
     * 数字 → 中文单位。
     *
     * 口径刻意对齐服务端自己的写法（见 [of] 的表格）：
     *
     * | 区间 | 写法 | 例 |
     * | --- | --- | --- |
     * | ≥ 1 亿 | 一位小数，整数不留 `.0` | `1.2亿` / `2亿` |
     * | 100 万 – 1 亿 | 整数万 | `1204万` |
     * | 1 万 – 100 万 | 一位小数万 | `1.2万` |
     * | < 1 万 | 原样 | `8632` |
     * | ≤ 0 | 空串（不显示） | — |
     *
     * 全程整数运算，不经过 `String.format`：那个要看 `Locale`，
     * 某些系统语言下会把小数点写成逗号（`1,2亿`）。
     */
    fun format(value: Long): String {
        if (value <= 0L) return ""
        if (value >= YI) {
            // 四舍五入到 0.1 亿：`(value + 0.05亿) / 0.1亿`
            val tenths = (value + YI / 20) / (YI / 10)
            return if (tenths % 10 == 0L) "${tenths / 10}亿" else "${tenths / 10}.${tenths % 10}亿"
        }
        if (value >= WAN * 100) return "${value / WAN}万"
        if (value >= WAN) {
            // 四舍五入到 0.1 万：`(value + 0.05万) / 0.1万`
            val tenths = (value + WAN / 20) / (WAN / 10)
            return if (tenths % 10 == 0L) "${tenths / 10}万" else "${tenths / 10}.${tenths % 10}万"
        }
        return value.toString()
    }
}
