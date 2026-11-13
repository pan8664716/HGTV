// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

fun ContentRepository.heatValue(raw: String): Long {
    return heatValueImpl(raw)
}

/**
 * 两个作者名是不是同一家。
 *
 * 只忽略空白与大小写：`multi-detail` 与列表接口给的是同一个字段（`copyright` → `author`），
 * 差异只可能出在两端空格与全角 / 半角上，做模糊匹配反而会把「同 IP」的别家作品混进来。
 */
internal fun ContentRepository.sameAuthor(left: String,right: String): Boolean {
    return sameAuthorImpl(left, right)
}

/**
 * 同 [sameAuthor]，但**作者为空也算命中**。
 *
 * 只给 `fillFamilySeasons` 用：`related` 的卡片不带 `copyright`（实测全为空串），
 * 未上线的季（`枯骨成仙第九季`）在搜索里也是空。此时「是不是这一家的」只能靠家族名判定
 *（调用方已经先按 [SeriesSeasons.normalize] 比对过剧名），空作者不能再被当成「别家」丢掉，
 * 否则那几季永远补不回来——这正是「同一部剧只显示 3 季」的另一半原因。
 *
 * 代价：`枯骨成仙第一季`（别家翻拍，`related` 里作者同样为空）会被一起收下。
 * 它没法与「未上线的自家季」区分，界面上它的作者行留空，不会误标成这一家。
 */
internal fun ContentRepository.authorUnknownOrSame(left: String,right: String): Boolean {
    return authorUnknownOrSameImpl(left, right)
}
