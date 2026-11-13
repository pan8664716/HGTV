// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 卡片左上角那个「系统标签」的配色。
 *
 * ## 颜色从哪来
 *
 * 服务端在卡片数据里给了 `tag_info`：
 *
 * ```json
 * {"text":"新剧",   "bg_color":["#00AE83"]}
 * {"text":"红果首发","bg_color":[]}          // 没有自定义色，还带一张 icon
 * ```
 *
 * `bg_color` 是**字符串数组**：一个元素 = 纯色，两个元素 = 渐变（「爆剧」那类红到深红）。
 * 这里只做两件事：把颜色解析成 `Int`，以及**在服务端没给色时按文案兜底**。
 *
 * ## 为什么要有兜底
 *
 * 服务端换文案是常事（「今日上新」→「上新」），颜色却是按文案配的。只认文案不认色，
 * 新文案就会退成一枚灰标签；只认色不认文案，服务端哪天漏给 `bg_color` 就整枚变黑。
 * 两条都留着，谁都失效时还有一条能看。
 *
 * 返回空列表 = 调用方用默认的深色压暗层（不认识的标签就那样，比瞎猜一个颜色稳妥）。
 */
object LabelStyle {

    /** 「今日上新」「新剧」这类：绿色。与服务端给 `新剧` 的那个 `#00AE83` 同色。 */
    private const val GREEN = 0xFF00AE83.toInt()

    /** 「爆剧」这类：红 → 深红的渐变（服务端通常自己会给两色，这里只是兜底）。 */
    private val RED_GRADIENT = intArrayOf(0xFFE8402A.toInt(), 0xFF9C1B10.toInt())

    /**
     * 这个标签该用什么底色。
     *
     * @param text 标签文案（[Series.label]）
     * @param bg 服务端给的原始色串（[Series.labelBg]，逗号分隔）
     * @return 0 个 = 没有配色；1 个 = 纯色；2 个 = 渐变（第一个是起点）。
     */
    fun colors(text: String, bg: String): List<Int> {
        val fromServer = bg.split(',')
            .mapNotNull { parseHex(it) }
            .take(2)
        if (fromServer.isNotEmpty()) return fromServer
        return when {
            text.contains("上新") || text.contains("新剧") -> listOf(GREEN)
            text.contains("爆") -> RED_GRADIENT.toList()
            else -> emptyList()
        }
    }

    /** `#RRGGBB` / `#AARRGGBB` → ARGB；认不出返回 null（服务端偶尔给空串或 `null`）。 */
    private fun parseHex(raw: String): Int? {
        val body = raw.trim().removePrefix("#")
        val value = body.toLongOrNull(16) ?: return null
        return when (body.length) {
            6 -> (0xFF000000L or value).toInt()
            8 -> value.toInt()
            else -> null
        }
    }
}
