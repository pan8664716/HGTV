// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 首页的一栏。[board] 非空表示这一栏是一个热播榜（[RankBoard]），
 * 为空表示来自本机记录（收藏 / 历史 / 稍后观看）。
 *
 * [key] 同时是剧架的 key 与卡片焦点 key 的前缀（界面按 `key:剧集 ID` 记住焦点），
 * 因此它必须稳定：改 key 会让用户回到首页时丢掉原来的焦点位置。
 */
data class HomeColumn(val key: String, val title: String, val board: RankBoard?)

/**
 * 首页栏目的**固定顺序**：历史记录 → 我的收藏 → 稍后观看 → 热播漫剧 → 热播真人 → 热播AI → 热播上新。
 *
 * 顺序与名字收敛成一个纯函数（不依赖 Android）的原因和 [CardMenu] 一样：
 * 界面上「有哪些栏、谁在前」是一眼可查的需求，散在 `MainActivity` 里的 if 分支一旦顺序写错
 * 只能靠人眼看出来，而这里可以被单元测试钉住。
 *
 * 界面只负责按 key 往这些栏目里填内容：本机三栏填本机记录，四栏填对应榜单第 1 页的前十部；
 * 某一栏没有内容就不渲染（栏目仍然在这里，只是界面上不出现）。
 */
object HomeLayout {
    val columns: List<HomeColumn> = listOf(
        HomeColumn("history","历史记录",null),
        HomeColumn("favorites","我的收藏",null),
        HomeColumn("later","稍后观看",null)
    ) + RankBoard.entries.map { HomeColumn("rank-${it.storedValue}",it.homeTitle,it) }

    /** 栏目名，按 key 查（界面上不重复写一遍中文，避免两处名字对不上）。 */
    fun title(key: String) = columns.firstOrNull { it.key==key }?.title ?: key

    /** 这一栏对应的榜单；本机记录栏返回 null。 */
    fun board(key: String) = columns.firstOrNull { it.key==key }?.board
}
