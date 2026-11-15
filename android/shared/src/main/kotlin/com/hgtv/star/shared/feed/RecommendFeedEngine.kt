// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.feed

import com.hgtv.star.core.CategorySelection
import com.hgtv.star.core.RecommendFeedSource
import com.hgtv.star.core.FeedCursor
import com.hgtv.star.core.OptionalFilter
import com.hgtv.star.core.RecommendCache
import com.hgtv.star.core.RecommendFlow
import com.hgtv.star.core.RecommendTaste
import com.hgtv.star.core.RecommendTab
import com.hgtv.star.core.Series
import com.hgtv.star.core.StageDeadline
import com.hgtv.star.shared.data.Library
import com.hgtv.star.shared.debug.StartupBudgets

/**
 * 推荐流的加载、续流、过滤、降级、重排和快照落盘。
 *
 * TV 与手机使用同一套 [RecommendFeedPolicy]；`cacheNamespace` 只用于区分不同的
 * 推荐流，不改变行为顺序。页面不再自己拼 `filter_ids`、判断空页、重试受众、
 * 处理游标停滞或合并分页。
 */
class RecommendFeedEngine(
    private val repository: RecommendFeedSource,
    private val library: Library,
    private val genderFilter: OptionalFilter,
    private val policy: RecommendFeedPolicy,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val debug: (String) -> Unit = {},
) {
    /**
     * 取一批推荐。
     *
     * 调用方应在后台线程调用。函数内部会按策略连续取页、换流和重试，
     * 直到得到可展示结果、确定没有更多内容或遇到不可恢复的错误。
     */
    suspend fun load(input: RecommendFeedInput): RecommendFeedResult {
        var items = input.items
        var page = input.page
        var hasMore = input.hasMore
        var session = input.sessionId
        var offset = input.nextOffset
        var filterMiss = input.filterMiss
        var filterIdsOff = input.filterIdsOff || library.recommendFilterIdsOff
        var genderProbe = input.genderProbe
        var processedPages = 0
        var emptyChain = 0
        var restarts = 0
        var requests = 0
        var filterFailures = 0
        var skipFilterOnce = false
        val firstLoadAt = if(input.append) 0L else now()
        val lines = ArrayList<String>(4)

        fun resetStream() {
            session = ""
            offset = 0
            hasMore = true
            persistCursor("", 0)
        }

        while (requests < policy.maxRequestsPerLoad) {
            if(emptyChain >= policy.maxPagesPerLoad) {
                if(restarts < policy.maxRestarts) {
                    restarts++
                    emptyChain = 0
                    resetStream()
                    continue
                }
                return result(
                    items, page, hasMore, session, offset, filterMiss, filterIdsOff,
                    genderProbe, processedPages, restarts, exhausted = true, lines = lines,
                )
            }
            if(input.append && !hasMore) {
                if(!policy.restartWhenNoMore || restarts >= policy.maxRestarts) {
                    return result(
                        items, page, hasMore, session, offset, filterMiss, filterIdsOff,
                        genderProbe, processedPages, restarts, exhausted = true, lines = lines,
                    )
                }
                restarts++
                resetStream()
                continue
            }
            if(!input.append && emptyChain == 0 && restarts == 0 &&
                policy.deepCursor > 0 && offset >= policy.deepCursor) {
                resetStream()
            }

            val requestOffset = offset
            val filters = if(input.noFilter || filterIdsOff || skipFilterOnce) "" else library.recommendFilterIds()
            skipFilterOnce = false
            val gender = library.recommendGender.ifEmpty { null }
                .takeIf { !genderFilter.off && !genderProbe }
            genderProbe = false
            val limit = if(input.append) policy.pageLimit else policy.firstLimit
            requests++
            val startedAt = now()
            val attempt = runCatching {
                if (!input.append && requests == 1) {
                    StageDeadline("recommend.register", StartupBudgets.REGISTER_MS).use { deadline ->
                        deadline.run { input.ensureRegistered() }
                    }
                } else {
                    input.ensureRegistered()
                }
                val requestBudget = if (!input.append && processedPages == 0) {
                    StartupBudgets.FEED_FIRST_MS
                } else {
                    StartupBudgets.FEED_PAGE_MS
                }
                StageDeadline("recommend.feed", requestBudget).use { deadline ->
                    deadline.run {
                        repository.recommendFeed(
                            selection = CategorySelection(gender = gender),
                            sessionId = session,
                            offset = requestOffset,
                            limit = limit,
                            genres = input.genres,
                            filterIds = filters,
                            tab = input.tab,
                        )
                    }
                }
            }
            if(attempt.isFailure) {
                val problem = attempt.exceptionOrNull() ?: IllegalStateException("推荐请求失败")
                val elapsed = now() - startedAt
                if (problem is com.hgtv.star.core.StageTimeoutException) {
                    lines += "stage timeout stage=${problem.stage} elapsed=${elapsed}ms"
                }
                if(filters.isNotEmpty() && elapsed < policy.quickFilterFailureMs) {
                    filterFailures++
                    if(filterFailures >= 2) filterIdsOff = true
                    skipFilterOnce = true
                    lines += "filter retry after ${elapsed}ms failures=$filterFailures"
                    continue
                }
                if(filters.isNotEmpty() && policy.disableFilterOnAnyFailure) {
                    filterIdsOff = true
                }
                return result(
                    items, page, hasMore, session, offset, filterMiss, filterIdsOff,
                    genderProbe, processedPages, restarts, error = problem, lines = lines,
                )
            }
            val feed = attempt.getOrThrow()

            if(filters.isNotEmpty() && feed.page.items.isEmpty()) {
                val miss = RecommendFlow.noteFilterMiss(filterMiss)
                filterMiss = miss.first
                if(miss.second) {
                    filterIdsOff = true
                    library.recommendFilterIdsOff = true
                }
                lines += "empty page with filter_ids=${filters.length}B"
                // 不带过滤重取一次，不把这次算成又翻了一页。
                continue
            }

            if(feed.page.items.isEmpty() && gender != null && !genderFilter.off) {
                genderFilter.onEmpty()
                genderProbe = true
                if(genderFilter.off) genderProbe = false
                lines += "empty page with gender=$gender misses=${genderFilter.misses}"
                continue
            }

            if(filters.isNotEmpty()) {
                filterMiss = 0
                filterFailures = 0
            }
            if(gender != null && feed.page.items.isNotEmpty()) genderFilter.onFilled()

            if(!input.append && feed.page.items.isEmpty() &&
                requestOffset > 0 && restarts < policy.maxRestarts) {
                restarts++
                resetStream()
                continue
            }

            val stalled = RecommendFlow.stalled(requestOffset, feed.nextOffset)
            if(stalled && restarts < policy.maxRestarts) {
                restarts++
                resetStream()
                continue
            }

            session = feed.sessionId
            offset = feed.nextOffset
            persistCursor(session, offset)

            val hidden = runCatching { library.hidden() }.getOrDefault(emptySet())
            val finished = runCatching { library.recommendFinished() }.getOrDefault(emptySet())
            val seen = runCatching { library.recommendSeen() }.getOrDefault(emptySet())
            val loadedIds = items.mapTo(HashSet(items.size)) { it.id }
            val loadedTitles = items.mapNotNull {
                RecommendFlow.titleKey(it.title).takeIf(String::isNotEmpty)
            }.toHashSet()
            val candidates = RecommendFlow.fresh(
                items = feed.page.items,
                hidden = hidden,
                finished = finished,
                seen = seen,
                loadedIds = loadedIds,
                loadedTitles = loadedTitles,
                seenTitle = { library.recommendSeenTitle(it) },
            )
            val expired = !input.append &&
                now() - firstLoadAt >= policy.firstBudgetMs
            val exhausted = !feed.page.hasMore || restarts >= policy.maxRestarts || expired
            val shown = if(candidates.isEmpty() && exhausted) {
                feed.page.items.filterNot { it.id in hidden || it.id in finished }
            } else {
                candidates
            }
            // 推荐流保持服务端返回顺序。官方书包流由服务端的 queue_names /
            // final_queue_name 排序；客户端不再做兴趣上浮或题材打散，避免改变平台推荐序。
            val arranged = shown
            val before = items.size
            items = when {
                input.append -> merge(items, arranged)
                input.restored && items.isNotEmpty() -> merge(items, arranged)
                else -> arranged
            }
            page++
            hasMore = feed.page.hasMore
            processedPages++
            if(!input.append || policy.saveOnAppend) {
                RecommendCache.save(items, policy.cacheNamespace)
            }
            val added = (items.size - before).coerceAtLeast(0)
            lines += "page=$page raw=${feed.page.items.size} kept=${shown.size} " +
                "arranged=${arranged.size} added=$added hasMore=$hasMore"
            if(shown.isEmpty() && feed.page.hasMore && !expired && items.isEmpty()) {
                emptyChain++
                continue
            }
            if(items.isEmpty()) {
                return result(
                    items, page, hasMore, session, offset, filterMiss, filterIdsOff,
                    genderProbe, processedPages, restarts, exhausted = true, lines = lines,
                )
            }
            // minItemsPerLoad 约束的是当前列表的总条数，不是单页新增数。
            // 官方 bookmall 流还会回 has_more=false + bottom_unlimited=true；
            // 对官方 Tab 在已经拿到卡片时继续补页，避免首屏永远停在 3 条。
            val officialUnlimited=input.tab.tabType!=null && input.genres.isEmpty()
            if(input.minItemsPerLoad > 0 && items.size < input.minItemsPerLoad &&
                processedPages < policy.maxPagesPerLoad &&
                (feed.page.hasMore || officialUnlimited)) {
                if(added==0) emptyChain++
                continue
            }
            return result(
                items, page, hasMore, session, offset, filterMiss, filterIdsOff,
                genderProbe, processedPages, restarts, lines = lines,
            )
        }

        return result(
            items, page, hasMore, session, offset, filterMiss, filterIdsOff,
            genderProbe, processedPages, restarts, exhausted = true, lines = lines,
        )
    }

    private fun persistCursor(session: String, offset: Int) {
        if(policy.namespacedCursor) {
            library.phoneFeedCursor = FeedCursor.upsert(
                library.phoneFeedCursor,
                FeedCursor.Entry(policy.cacheNamespace, session, offset),
            )
        } else {
            library.recommendCursor = if(session.isEmpty() && offset == 0) "" else "$session|$offset"
        }
    }

    private fun merge(old: List<Series>, new: List<Series>): List<Series> {
        if(new.isEmpty()) return old
        if(old.isEmpty()) return new
        val map = LinkedHashMap<String, Series>(old.size + new.size)
        old.forEach { map[it.id] = it }
        new.forEach { if(!map.containsKey(it.id)) map[it.id] = it }
        return map.values.toList()
    }

    private fun result(
        items: List<Series>,
        page: Int,
        hasMore: Boolean,
        session: String,
        offset: Int,
        filterMiss: Int,
        filterIdsOff: Boolean,
        genderProbe: Boolean,
        pages: Int,
        restarts: Int,
        exhausted: Boolean = false,
        error: Throwable? = null,
        lines: List<String> = emptyList(),
    ): RecommendFeedResult {
        lines.forEach(debug)
        return RecommendFeedResult(
            items = items,
            page = page,
            hasMore = hasMore,
            sessionId = session,
            nextOffset = offset,
            filterMiss = filterMiss,
            filterIdsOff = filterIdsOff,
            genderOff = genderFilter.off,
            genderProbe = genderProbe,
            pages = pages,
            restarts = restarts,
            exhausted = exhausted,
            empty = items.isEmpty(),
            error = error,
        )
    }
}

/** TV 与 Phone 共用的推荐加载策略。 */
data class RecommendFeedPolicy(
    val cacheNamespace: String = "",
    val firstLimit: Int = 20,
    val pageLimit: Int = 20,
    val maxPagesPerLoad: Int = 3,
    val maxRestarts: Int = 2,
    val maxRequestsPerLoad: Int = 12,
    val deepCursor: Int = Int.MAX_VALUE,
    val firstBudgetMs: Long = Long.MAX_VALUE,
    val quickFilterFailureMs: Long = Long.MAX_VALUE,
    val restartWhenNoMore: Boolean = false,
    val disableFilterOnAnyFailure: Boolean = false,
    val saveOnAppend: Boolean = true,
    val namespacedCursor: Boolean = true,
) {
    companion object {
        /** TV 与 Phone 共用的推荐加载节奏。 */
        const val FIRST_LIMIT = 60
        const val PAGE_LIMIT = 60
        const val MAX_PAGES_PER_LOAD = 3
        const val MAX_RESTARTS = 2
        const val DEEP_CURSOR = 120
        const val FIRST_BUDGET_MS = 3_000L
        const val QUICK_FILTER_FAILURE_MS = 1_500L
        const val MIN_ITEMS_PER_LOAD = 6

        /**
         * 两端唯一的差异是推荐流命名空间：Phone 按体裁拆流，TV 只有一条主推荐流。
         * 其余取流、补页、过滤、重试和落盘节奏必须完全一致。
         */
        fun standard(cacheNamespace: String) = RecommendFeedPolicy(
            cacheNamespace = cacheNamespace,
            firstLimit = FIRST_LIMIT,
            pageLimit = PAGE_LIMIT,
            maxPagesPerLoad = MAX_PAGES_PER_LOAD,
            maxRestarts = MAX_RESTARTS,
            deepCursor = DEEP_CURSOR,
            firstBudgetMs = FIRST_BUDGET_MS,
            quickFilterFailureMs = QUICK_FILTER_FAILURE_MS,
            restartWhenNoMore = true,
            disableFilterOnAnyFailure = true,
            saveOnAppend = true,
            namespacedCursor = true,
        )
    }
}

data class RecommendFeedInput(
    val items: List<Series>,
    val sessionId: String,
    val nextOffset: Int,
    val hasMore: Boolean,
    val page: Int,
    val filterMiss: Int,
    val filterIdsOff: Boolean,
    val genderProbe: Boolean,
    val append: Boolean,
    val restored: Boolean = false,
    val noFilter: Boolean = false,
    val genres: List<String>,
    val tab: RecommendTab = RecommendTab.RECOMMEND,
    val taste: RecommendTaste,
    val minItemsPerLoad: Int = 0,
    val ensureRegistered: () -> Unit = {},
)

data class RecommendFeedResult(
    val items: List<Series>,
    val page: Int,
    val hasMore: Boolean,
    val sessionId: String,
    val nextOffset: Int,
    val filterMiss: Int,
    val filterIdsOff: Boolean,
    val genderOff: Boolean,
    val genderProbe: Boolean,
    val pages: Int,
    val restarts: Int,
    val exhausted: Boolean,
    val empty: Boolean,
    val error: Throwable? = null,
)
