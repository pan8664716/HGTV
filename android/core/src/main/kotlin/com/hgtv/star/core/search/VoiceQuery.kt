// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 语音识别结果的归一化（电视端与手机端共用一份，纯文本规则，放在 `core` 里便于单测）。
 *
 * 系统返回的是一条口语句子（「我想看……」「帮我搜一下……」），直接拿去搜会把前缀
 * 当成剧名的一部分。这里只做**低风险**的清理：
 *
 * - 去首尾空白与标点 / 引号；
 * - 去掉明确的口语前缀（长前缀优先，削完为空就不削——那说明剧名本身就是这个词）；
 * - 按输入框同一条上限截断（[MAX]），不让超长结果被静默丢弃。
 *
 * **不做**同音字纠正、不做分词：那两类改动会把正确的剧名改错，
 * 而识别错的时候用户看得见输入框里的字，自己改一下比被「猜」更可靠。
 */
object VoiceQuery {

    /** 与搜索输入框同一条长度上限（`SearchHistory.remember` 也是 80）。 */
    const val MAX = 80

    private const val EDGE = " \t\n\r。，、！？；：,.!?;:「」『』“”\"'《》<>()（）[]【】"

    /**
     * 口语前缀表，**按长度从长到短**排。
     *
     * 顺序是规则的一部分：`startsWith` 取第一个命中的，短前缀排在前面就会把长前缀吃掉——
     * 「帮我搜一下末日漂流」先命中「帮我搜」，剩下的「一下末日漂流」就成了搜索词。
     * 这份表原先按语义顺序手写，因此一直带着这个错；现在由长度决定顺序，加新前缀不必再想放哪。
     */
    private val PREFIXES = listOf(
        "帮我搜一下", "帮我找一下", "我想看一下", "我要看一下", "搜索一下", "搜一下",
        "我想看", "我要看", "我想搜", "帮我搜", "帮我找", "搜索", "查找",
    ).sortedByDescending { it.length }

    fun normalize(raw: String): String {
        var text = raw.trim().trim { it in EDGE }
        for (prefix in PREFIXES) {
            if (!text.startsWith(prefix)) continue
            val rest = text.removePrefix(prefix).trim { it in EDGE }
            // 削完什么都不剩，说明这句话本来就只有前缀（剧名就叫「搜索」的那种），原样保留。
            if (rest.isNotEmpty()) { text = rest; break }
        }
        if (text.length > MAX) text = text.substring(0, MAX)
        return text
    }
}
