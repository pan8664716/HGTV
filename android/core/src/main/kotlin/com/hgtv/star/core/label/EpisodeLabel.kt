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

/**
 * 选集分页的页码与区间文案。
 *
 * 电视与手机的选集面板都要回答同样两个问题：**一共几页、这一页是第几集到第几集**。
 * 两端原先各写一遍（`feature-tv/player/EpisodeGrid` 的 `pagerChips` 与
 * `feature-phone/ui/PhoneEpisodeGrid` 的 `render`），页码算法与 `第 A—B 集` 的破折号
 * 只要有一处改错，两端读起来就不一样。这里把它抽成纯函数，两端共用。
 *
 * 每页集数由各端自己归一（电视 10–50、手机 20–100，还都要按每行个数取整），
 * 因此 [perPage] 是**已归一**的值，这里只做区间与页数换算。
 */
object EpisodePaging {
    /** 一共几页（至少 1 页：没有剧集时也显示「第 1/1 页」，不留一块空白）。 */
    fun pages(count: Int, perPage: Int): Int {
        if (perPage <= 0) return 1
        return ((count + perPage - 1) / perPage).coerceAtLeast(1)
    }

    /** 第 [page] 页的闭区间集号（0 基页号、1 基集号）；越界时夹到合法范围。 */
    fun range(count: Int, perPage: Int, page: Int): IntRange {
        if (count <= 0 || perPage <= 0) return 0..-1
        val pages = pages(count, perPage)
        val safe = page.coerceIn(0, pages - 1)
        val from = safe * perPage
        val to = minOf(from + perPage, count) - 1
        return from..to
    }

    /** 「第 1—20 集 · 共 99 集」：选集面板的区间行（两端逐字相同）。 */
    fun rangeText(count: Int, perPage: Int, page: Int): String {
        val r = range(count, perPage, page)
        if (r.isEmpty()) return ""
        return "第 ${r.first + 1}—${r.last + 1} 集 · 共 $count 集"
    }

    /** 「第 3/5 页」：翻页按钮旁边那一行。 */
    fun pageText(count: Int, perPage: Int, page: Int): String {
        val pages = pages(count, perPage)
        val safe = page.coerceIn(0, pages - 1)
        return "第 ${safe + 1}/$pages 页"
    }

    /** 分页芯片上的文字：「21-40」（[page] 越界时夹到合法范围）。 */
    fun chipText(count: Int, perPage: Int, page: Int): String {
        val r = range(count, perPage, page)
        if (r.isEmpty()) return ""
        return "${r.first + 1}-${r.last + 1}"
    }
}
