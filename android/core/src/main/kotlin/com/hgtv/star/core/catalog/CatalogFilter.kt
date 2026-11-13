// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 「用户手动隐藏的剧」不该出现在哪些地方。
 *
 * 长按剧卡里那一项「不在热播榜中显示」写进的是 `Library.hide(id)`（一份落盘的 id 集合），
 * 而**生效**的位置不止热播榜：推荐流、分类、榜单、首页剧架都要跟着过滤——
 * 否则用户在榜单里隐藏了一部，滑到推荐流它又出现了，那个开关就等于没做。
 *
 * 这条规则两端必须一致，因此收成一个纯函数：调用方只管把当前列表和隐藏集合交进来。
 * 隐藏集合为空时**原样返回**同一个列表实例（绝大多数用户没有隐藏过任何东西，
 * 每次翻页都新建一遍 List 没有必要）。
 */
object CatalogFilter {

    fun visible(items: List<Series>, hidden: Set<String>): List<Series> =
        if (hidden.isEmpty()) items else items.filterNot { it.id in hidden }

    /**
     * 一页目录，摘掉被隐藏的条目。
     *
     * `hasMore` 与 `ranking` 原样带走：过滤只影响这一页显示几条，
     * 不能因此认为「没有下一页」——数据源那边还有。
     */
    fun visible(page: CatalogPage, hidden: Set<String>): CatalogPage =
        if (hidden.isEmpty()) page else page.copy(items = visible(page.items, hidden))
}
