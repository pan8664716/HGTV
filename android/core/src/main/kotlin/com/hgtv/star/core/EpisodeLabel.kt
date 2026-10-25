// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 卡片 / 简介页上的「集数」文案。
 *
 * 统一成「99 集」：接口里的 `episode_right_text` 常写成「全 99 集」，
 * 这个「全」在卡片上既占地方又没有信息量（能播的集数就是这些），因此一律去掉。
 */
object EpisodeLabel {
    /** 已知集数 → 「99 集」；集数未知时给空串（调用方自行决定显示别的角标）。 */
    fun of(count: Int): String = if (count > 0) "$count 集" else ""

    /** 卡片右下角的统一写法：「共 99 集」；集数未知时给空串。 */
    fun total(count: Int): String = if (count > 0) "共 $count 集" else ""

    /**
     * 把接口给的集数文案归一：去掉开头的「全」，数字与「集」之间留一个空格。
     *
     * 只处理「全? N 集」这一种形状，其余文案（「更新至 12 集」「热播中」）原样返回，
     * 免得把服务端别的角标文案改坏。
     */
    fun normalize(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        // 只认「全? N 集」这一种形状：别的文案（「全剧已看完」这类）一个字都不动，
        // 否则去掉「全」会把它改成另一句话。
        val match = Regex("^全?\\s*([0-9]+)\\s*集$").find(text)
        return if (match != null) "${match.groupValues[1]} 集" else text
    }

    /** 卡片 / 简介页的第二行文字：只做「全 N 集 → N 集」的归一。 */
    fun cardStatus(text: String): String = normalize(text)
}
