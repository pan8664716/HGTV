// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.feature.catalog

import com.hgtv.star.core.EpisodePaging

/**
 * 选集网格的纯状态：页大小、当前页、范围文案与翻页边界。
 * TV / Phone 只负责把范围画成各自尺寸的格子。
 */
class EpisodeGridState(
    val count: Int,
    current: Int,
    pageSize: Int,
    val columns: Int,
) {
    val perPage: Int = (pageSize / columns * columns).coerceIn(columns, 100)
    var page: Int = (current / perPage).coerceAtLeast(0)
        set(value) {
            field = value.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        }

    val pageCount: Int
        get() = EpisodePaging.pages(count, perPage)

    val range: IntRange
        get() = EpisodePaging.range(count, perPage, page)

    val rangeText: String
        get() = EpisodePaging.rangeText(count, perPage, page)

    val pageText: String
        get() = EpisodePaging.pageText(count, perPage, page)

    fun chipText(index: Int): String =
        EpisodePaging.chipText(count, perPage, index)

    fun canMove(direction: Int): Boolean =
        direction != 0 && page + direction in 0 until pageCount

    fun move(direction: Int): Boolean {
        if (!canMove(direction)) return false
        page += direction
        return true
    }
}
