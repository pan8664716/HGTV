// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 登录表单左侧的国家 / 地区区号候选。
 *
 * ## 为什么放在 `:core` 而不是各端各写一份
 *
 * 电视与手机的手机号登录必须**给同一批区号、拼出同一串 E.164**：用户在电视上选了
 * 「+852」，换到手机上登录时看到的候选与拼法必须一致，否则同一串本地号码在两端会拼成
 * 两个不同的国际号码，平台只认其中一个，而用户看到的是「电视能登录、手机不行」。
 *
 * 手机端原先**根本没有区号选择**，号码直接交给 [e164Phone] 猜：只有 11 位且以 1 开头的
 * 才当中国大陆补 `+86`，其余一律 `+数字`——香港 8 位号码会被拼成 `+91234567`，
 * 少了 `852`，发码必然失败。现在两端走同一份归一规则。
 */
object PhoneRegion {

    /** 候选区号，第一个是默认值。文案与登录表单左侧按钮上显示的一致。 */
    val labels = listOf(
        "+86 中国大陆", "+852 中国香港", "+853 中国澳门", "+886 中国台湾",
        "+1 美国 / 加拿大", "+81 日本", "+82 韩国", "+65 新加坡", "+60 马来西亚",
    )

    /** 默认档：中国大陆。 */
    const val DEFAULT = 0

    /** 下标收敛到合法范围（换版本后候选数量可能变过，旧下标不能越界）。 */
    fun clamp(index: Int): Int = index.coerceIn(0, labels.lastIndex)

    /** 按钮上显示的那一段：`+86`。 */
    fun dialOf(index: Int): String = labels[clamp(index)].substringBefore(" ")

    /** 拼 E.164 时用的数字部分：`86`。 */
    fun digitsOf(index: Int): String = dialOf(index).drop(1)

    /**
     * 把用户输入的本地号码按所选区号拼成 E.164。
     *
     * * 用户自己敲了带 `+` 的完整国际号码 → 原样送出，不再套一次左边的区号
     *   （否则「+86」按钮 + 「+8613…」输入框会拼成 `+86+8613…`）；
     * * 其余情况只留数字，再拼上 `+区号`。
     *
     * **不判断号码长度、也不判断「是不是 1 开头」**：中国大陆 11 位，香港 / 澳门 8 位、
     * 日本 10–11 位、美国 10 位，各地区的形状都不一样。按中国大陆的规则卡，其它地区的
     * 号码会在本地就被拦下来——用户看到的是「号码明明是对的，却提示格式不对」。
     * 长度与合法性交给平台判，它回的错误文案原样显示在说明行里。
     */
    fun normalize(raw: String, index: Int): String? {
        val text = raw.trim()
        if (text.startsWith("+")) {
            val digits = text.filter { it.isDigit() }
            return if (digits.isEmpty()) null else "+" + digits
        }
        val local = text.filter { it.isDigit() }
        if (local.isEmpty()) return null
        return "+" + digitsOf(index) + local
    }

    /**
     * 从一串 E.164 里认出区号，返回候选下标；认不出返回 -1。
     *
     * 按**最长匹配**：`+852` 要先于 `+85` 这种不存在的短前缀，因此按长度倒序取第一个。
     * 预填「上一次填过的号码」时用它把区号与本地号码拆开——整串塞进输入框会让框里
     * 出现 `+86`，提交时再拼一次就变成 `+86+8613…`。
     */
    fun indexOf(e164: String): Int {
        val text = e164.trim()
        if (!text.startsWith("+")) return -1
        return labels.indices
            .map { it to dialOf(it) }
            .filter { (_, dial) -> text.startsWith(dial) }
            .maxByOrNull { (_, dial) -> dial.length }
            ?.first ?: -1
    }

    /** 把 E.164 拆成「区号下标 + 本地号码」，用于表单预填。 */
    fun split(e164: String): Pair<Int, String> {
        val index = indexOf(e164)
        val local = if (index >= 0) e164.trim().removePrefix(dialOf(index)) else e164
        return clamp(if (index >= 0) index else DEFAULT) to local.filter { it.isDigit() }
    }
}
