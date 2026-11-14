// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/** 榜单、分类、搜索和本机列表共用的分页边界。 */
const val CATALOG_MAX_PAGE = 100
/** 一次预取最多连续补这么多页，避免一页刚落地就被再次取空。 */
const val CATALOG_PREFETCH_PAGES = 2
/** 追加失败后的自动重试冷却时间。 */
const val CATALOG_RETRY_COOLDOWN_MS = 3_000L

/**
 * 一页目录数据追加后的统一结果。
 *
 * 电视端与手机/平板端只负责何时请求下一页，去重、页尾、名次合并和最大页码
 * 必须由这一份规则决定，否则同一份接口数据会在两端出现不同条数或不同结束时机。
 */
data class CatalogPagingResult(
    val items: List<Series>,
    val added: List<Series>,
    val page: Int,
    val hasMore: Boolean,
    val ranking: ComicRanking?,
) {
    /** 下一次请求的页码；没有更多时保持当前页。 */
    val nextPage: Int get() = if (hasMore) page + 1 else page
}

object CatalogPaging {
    /**
     * 追加一页并归一化分页状态。
     *
     * 服务端偶尔会在 `hasMore=true` 时返回整页重复或空页；这种页不能继续推进，
     * 否则两端都会反复请求同一个位置。`added` 为空时直接结束。
     */
    fun append(
        current: List<Series>,
        currentRanking: ComicRanking?,
        loadedPage: Int,
        incoming: CatalogPage,
    ): CatalogPagingResult {
        val seen = current.mapTo(HashSet(current.size)) { it.id }
        val added = incoming.items.filter { seen.add(it.id) }
        val hasMore = loadedPage < CATALOG_MAX_PAGE && incoming.hasMore && added.isNotEmpty()
        return CatalogPagingResult(
            items = current + added,
            added = added,
            page = loadedPage,
            hasMore = hasMore,
            ranking = mergeRanking(currentRanking, incoming.ranking),
        )
    }

    /** 已加载内容只剩这么多条时提前取下一页。 */
    fun shouldPrefetch(remaining: Int): Boolean =
        remaining <= CATALOG_PREFETCH_REMAINING

    /** 恢复缓存时推断下一次应请求哪一页；不完整首屏重新补第 1 页。 */
    fun nextPageForRestored(items: Int, hasMore: Boolean): Int = when {
        !hasMore -> ((items + CATALOG_PAGE_SIZE - 1) / CATALOG_PAGE_SIZE).coerceAtLeast(1)
        items < CATALOG_PAGE_SIZE -> 1
        else -> items / CATALOG_PAGE_SIZE + 1
    }.coerceIn(1, CATALOG_MAX_PAGE)

    /** 榜单追加页只带自己那一页的名次，必须与之前页合并。 */
    fun mergeRanking(previous: ComicRanking?, next: ComicRanking?): ComicRanking? = when {
        previous == null -> next
        next == null -> previous
        else -> next.copy(positions = previous.positions + next.positions)
    }
}
