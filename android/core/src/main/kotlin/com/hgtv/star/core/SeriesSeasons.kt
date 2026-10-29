// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 剧名里的季号解析。
 *
 * **季号优先用接口给的字段**（[Series.seasonIndex]）：
 *
 * | 接口 | 季号字段 |
 * | --- | --- |
 * | 分类 / 榜单（`category/landpage`） | `sub_title_list` 里 `data_type == 0` 的那条「第N季」（单季也写第1季） |
 * | 详情（`video_detail`）、相关作品（`plan/v:version`） | `season_index`（数字） |
 * | 搜索（`search/tab`）、提示词（`search/suggest`） | **没有**，只能从剧名读 |
 *
 * 剧名解析只当兜底（搜索 / 提示词那两条接口就是兜底场景），因此这里做两件纯逻辑的事：
 *
 * 1. [base]：剥掉剧名尾部的季号，得到「基名」——
 *    `聚宝仙盆之杂灵根才是真BOSS第十三季` → `聚宝仙盆之杂灵根才是真BOSS`；
 * 2. [season]：取出季号，`第一季` / `第1季` / `S1` / `Season 1` 都算 1，没有季号算 1（第 1 季）。
 *
 * 筛选同系列时用 [normalize] 把候选剧名同样剥掉季号再比对，避免把
 * `咱家剑宗团宠小师妹第五季` 当成 `一梦青玄，团宠小师妹一剑毁天灭地` 的兄弟。
 */
object SeriesSeasons {
    /** 中文数字：只覆盖到「九十九」这一档，短剧季数远到不了三位数。 */
    private val CN_DIGITS = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5,
        '六' to 6, '七' to 7, '八' to 8, '九' to 9
    )
    private const val CN_TEN = '十'

    /**
     * 季号后缀的几种写法（`name` 捕获季号本身，允许前后有空格）：
     * 第X季 / 第X部 / X季 / X部 / SX / Season X / 第X期。
     */
    private val SUFFIX = Regex(
        "[\\s·\\-—_]*(?:第\\s*([0-9]{1,2}|[零〇一二两三四五六七八九十]{1,3})\\s*[季部期]" +
            "|([0-9]{1,2})\\s*[季部期]" +
            "|(?i:season)\\s*([0-9]{1,2})" +
            "|(?i:s)\\s*([0-9]{1,2}))\\s*$"
    )

    /** 中文数字 → 整数：支持「十三」「二十三」「十」「二十」。解析不了返回 null。 */
    internal fun chineseNumber(value: String): Int? {
        if (value.isEmpty()) return null
        if (value.all { it.isDigit() }) return value.toIntOrNull()
        val ten = value.indexOf(CN_TEN)
        if (ten < 0) {
            var sum = 0
            for (ch in value) sum = sum * 10 + (CN_DIGITS[ch] ?: return null)
            return sum
        }
        val head = value.substring(0, ten)
        val tail = value.substring(ten + 1)
        val tens = if (head.isEmpty()) 1 else (CN_DIGITS[head.single()] ?: return null)
        val ones = if (tail.isEmpty()) 0 else (CN_DIGITS[tail.single()] ?: return null)
        return tens * 10 + ones
    }

    /**
     * 剧名尾部季号的匹配结果：基名 + 季号 + 被剥掉的后缀。
     * [matched] 为 false 表示剧名里没有季号（这一部就是第 1 季）。
     */
    data class Parsed(val base: String, val season: Int, val matched: Boolean)

    fun parse(title: String): Parsed {
        val clean = title.trim()
        val hit = SUFFIX.find(clean) ?: return Parsed(clean, 1, false)
        val raw = hit.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return Parsed(clean, 1, false)
        val number = chineseNumber(raw) ?: return Parsed(clean, 1, false)
        if (number <= 0 || number > 99) return Parsed(clean, 1, false)
        return Parsed(clean.substring(0, hit.range.first).trim(), number, true)
    }

    /** 搜索用的基名：剥掉尾部季号。 */
    fun base(title: String): String = parse(title).base

    /**
     * 季号的两种写法：中文（`第十三季`，平台上剧名就是这么写的）与阿拉伯（`第13季`）。
     *
     * 同系列各季现在由「相关作品」接口直接给出（见 [ContentRepository.related]），
     * 这里保留两种写法的互转：一是给测试做自洽性校验（生成的标签必须能被 [season] 认回来），
     * 二是外部工具（`tools/`、联调脚本）按季号拼剧名时用得上。
     */
    fun labels(season: Int): List<String> = if(season<1 || season>99) emptyList()
        else listOf("第" + chinese(season) + "季","第" + season + "季")

    /** 整数 → 中文数字（1–99）：1 一、10 十、11 十一、20 二十、25 二十五。 */
    internal fun chinese(value: Int): String {
        if(value<=0 || value>99) return value.toString()
        val ones=arrayOf("零","一","二","三","四","五","六","七","八","九")
        if(value<10) return ones[value]
        if(value==10) return "十"
        if(value<20) return "十"+ones[value-10]
        val tens=value/10; val rest=value%10
        return ones[tens]+"十"+(if(rest==0) "" else ones[rest])
    }

    /** 剧名里的季号；没有季号算 1。 */
    fun season(title: String): Int = parse(title).season

    /** 季号：优先接口字段（[Series.seasonIndex]），接口没给时才从剧名里读。 */
    fun season(series: Series): Int = series.seasonIndex.takeIf { it>0 } ?: season(series.title)

    /**
     * 卡片右上角要标的季号。
     *
     * **每张卡都标**：剧名里没写季号的按第 1 季算（与 [season] 同一套规则）。
     *
     * 早先这里对单季的剧返回空串（「平台绝大多数短剧没有分季，给每张卡都补一个
     * 第 1 季是凭空猜的」）。现在改成一律标：分季信息只写在剧名里，而用户需要
     * 「每张卡都能一眼读出这是第几季」——同一部剧的第二季与第一季并排出现在列表里时，
     * 空角标会让人分不清哪一张才是自己在看的。
     *
     * 代价是满屏「第 1 季」，但它是**准确的**（没有季号就是第 1 季），
     * 而准确比留白重要。
     */
    fun badge(series: Series): String = "第 " + season(series) + " 季"

    /**
     * 卡片右上角标注季号用的文案：`第 13 季`。
     *
     * 剧名里没有季号的按第 1 季标（与 [season] 同一套规则）：
     * 平台的分季信息只写在剧名里，界面上要「每张卡都能读出这是第几季」就只能用它。
     */
    fun badge(title: String): String = "第 " + season(title) + " 季"

    /**
     * 比对用的归一化剧名：剥季号 → 全角转半角 → 去掉空白与常见标点。
     *
     * 平台上的剧名写法并不统一（`一梦青玄，团宠小师妹一剑毁天灭地` 与
     * `一梦青玄,团宠小师妹一剑毁天灭地` 都出现过），只按原串比对会漏掉同一部剧。
     *
     * **季号不只在末尾**：`聚宝仙盆第二季：灵根亿点点废，战力亿点强` 这种把季号写在中间的，
     * [base] 剥不掉（它只管尾部），这里再剥一次「出现在任意位置的季号」，同一部剧才认得回来。
     * 剥的是「第N季 / 第N部 / N季 / Sx / Season x」这种明显的季号片段，
     * 剥完剩下的一模一样才算同一部剧。
     */
    fun normalize(title: String): String = buildString {
        for (ch in ANY_SEASON.replace(base(title), "")) {
            val half = when (ch) {
                in '０'..'９' -> ch - 0xFEE0
                in 'Ａ'..'Ｚ' -> ch - 0xFEE0
                in 'ａ'..'ｚ' -> ch - 0xFEE0
                '　' -> ' '
                else -> ch
            }
            if (half.isWhitespace()) continue
            if (half in "，,。.、；;：:！!？?·-—_~～()（）[]【】《》\"'“”‘’") continue
            append(half.lowercaseChar())
        }
    }

    /** 出现在**任意位置**的季号片段（见 [normalize]）：中英文数字、`第N季` / `第N部` / `Sx` / `Season x`。 */
    private val ANY_SEASON = Regex(
        "(?i)(?:第\\s*(?:[0-9]{1,3}|[零〇一二两三四五六七八九十]{1,3})\\s*[季部]" +
            "|[0-9]{1,3}\\s*[季部]" +
            "|season\\s*[0-9]{1,3}" +
            "|s\\s*[0-9]{1,3})"
    )

    /**
     * 从搜索候选里挑出同一部剧的其它季，按季号升序排列。
     *
     * [current] 是正在看的那一部：它自己不进结果（界面另有位置显示），但它的基名决定筛选范围。
     * 返回的列表只含「归一化基名与当前剧完全相同」的条目。
     */
    fun siblings(current: String, candidates: List<Series>): List<Series> {
        val key = normalize(current)
        if (key.isEmpty()) return emptyList()
        // 「是不是同一部剧」仍按归一化剧名判断（不同 IP 的季号会各自从 1 开始，只看季号会串），
        // 但**排序与季号本身用接口给的字段**：相关作品那一条接口每行都带 `season_index`。
        return candidates
            .filter { normalize(it.title) == key }
            .sortedWith(compareBy({ season(it) }, { it.title }))
    }
}
