// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv.list
import android.view.View
import com.hgtv.star.tv.TvMainActivity
import com.hgtv.star.core.Series
import com.hgtv.star.tv.openDetail
import com.hgtv.star.tv.quickActions

/*
 * 剧卡上「点一下」与「长按 / 菜单键」这两个动作，全站只有这一份实现。
 *
 * 首页剧架（[HomeScreen]）与列表页栅格（[SeriesGrid]）是同一个列表组件的两种排版，
 * 卡片本身都是 [com.hgtv.star.tv.PosterCard]，因此这两个动作也必须一致：
 * 点一张卡 = 记住它是谁 → 进详情页（历史记录那一栏按设置决定是否直接续播）；
 * 长按 / 菜单键 = 记住它是谁 → 弹出这张卡的快捷菜单（收藏 / 稍后观看 / 详情）。
 *
 * 「记住它是谁」指的是 [TvMainActivity.catalogFocus]：它是这一页的焦点落点 key，
 * 返回列表时要按它把焦点放回原来那张卡，因此两个动作都要先记再走。
 */

/** 点一张剧卡：记住它的 id，然后按 [resume] 与设置决定进详情页还是直接接着上次看到的位置播。 */
fun TvMainActivity.openSeries(item: Series, resume: Boolean = false) {
    catalogFocus = item.id
    openDetail(item, resume && !library.historyPreferDetail)
}

/**
 * 长按 / 菜单键：弹出这张卡的快捷菜单。
 *
 * [shelf] 是卡片所在剧架的 key（[HomeScreen.Shelf.key]）；列表页的卡片不属于任何剧架，
 * 传空即可。菜单据此决定有没有「删除记录」这类只在某个剧架里成立的项。
 */
fun TvMainActivity.seriesMenu(item: Series, anchor: View, shelf: String? = null) {
    catalogFocus = item.id
    quickActions(item, anchor, shelf)
}
