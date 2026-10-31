// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.ui

/**
 * 作者头像的底色：按名字在这几个深色里挑一个。
 *
 * 全部压到「白字放上去仍然清楚」的明度区间，并且都偏冷 / 偏灰，
 * 与页面的新拟态底板放在同一屏里不打架；换主题不需要改它（头像是不随主题变的图片位）。
 *
 * ## 为什么放在公共层
 *
 * 同一部剧的作者在电视和手机上必须是**同一个颜色**——用户读到的「这是谁」靠的是
 * 颜色与名字的对应关系，两端各写一份色盘，改一处忘一处就会变成「同一个人两种颜色」。
 * 早先这段在两个模块里各有一份逐字相同的实现（`MainActivityDetail` / `PhoneCards`），
 * 现在只有这一份。
 *
 * 算法本身刻意保持**逐字不变**：色盘顺序与哈希初值（`7` / `31`）决定了几万条已有
 * 记录的颜色，改任何一个数字，所有用户的头像底色都会整体换一遍。
 */
fun authorColor(name: String): Int {
    var hash = 7
    for (ch in name) hash = hash * 31 + ch.code
    return AVATAR_PALETTE[(hash and 0x7FFFFFFF) % AVATAR_PALETTE.size]
}

/** 六个深色。顺序即取色顺序，**不要重排**（见 [authorColor] 的说明）。 */
private val AVATAR_PALETTE = intArrayOf(
    0xFF35596E.toInt(), 0xFF44576F.toInt(), 0xFF5A4A6B.toInt(),
    0xFF3F6357.toInt(), 0xFF6B5540.toInt(), 0xFF4C4C63.toInt(),
)
