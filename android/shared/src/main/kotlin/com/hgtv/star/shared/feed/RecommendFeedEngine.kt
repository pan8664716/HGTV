// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.feed

import com.hgtv.star.core.CategorySelection
import com.hgtv.star.core.AppConfig
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
 * 推荐流，不改变行为顺序。页面不再自己拼 `filter_ids`；曝光记录不参与取流，
 * 空页、受众降级、游标停滞和分页合并仍由这里统一处理。
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
        // 只推荐横屏是 TV 的请求偏好；筛选由官方推荐接口完成，客户端不再按卡片字段二次过滤。
        val horizontalOnly = policy.supportsHorizontalOnly && library.recommendHorizontalOnly
        var items = input.items
        var page = input.page
        var hasMore = input.hasMore
        var session = input.sessionId
        var offset = input.nextOffset
        var filterMiss = input.filterMiss
        var filterIdsOff = input.filterIdsOff
        var genderProbe = input.genderProbe
        var processedPages = 0
        var emptyChain = 0
        var restarts = 0
        var requests = 0
        val firstLoadAt = if(input.append) 0L else now()
        val lines = ArrayList<String>(4)

        fun resetStream() {
            session = ""
            offset = 0
            hasMore = true
            persistCursor("", 0, horizontalOnly)
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
            // 曝光窗口不再参与推荐流取数：既不回传 filter_ids，也不在本地删卡。
            val gender = library.recommendGender.ifEmpty { null }
                .takeIf { !genderFilter.off && !genderProbe }
            genderProbe = false
            val limit = if(input.append) policy.pageLimit else policy.firstLimit
            requests++
            val attempt = runCatching {
                if (!input.append && !input.restored && requests == 1) {
                    StageDeadline("recommend.register", StartupBudgets.REGISTER_MS).use { deadline ->
                        deadline.run { input.ensureRegistered() }
                    }
                } else {
                    input.ensureRegistered()
                }
                // 只有“真正无快照的首条”使用首屏预算；快照补齐虽然 append=false，
                // 但它已经在首帧之后，属于后台补页，不能套首条门槛。
                val criticalFirstRequest = !input.append && !input.restored && processedPages == 0
                val request = {
                    repository.recommendFeed(
                        selection = CategorySelection(gender = gender),
                        sessionId = session,
                        offset = requestOffset,
                        limit = limit,
                        genres = input.genres,
                        filterIds = "",
                        tab = input.tab,
                        horizontalOnly = horizontalOnly,
                    )
                }
                if (criticalFirstRequest) {
                    StageDeadline("recommend.feed", StartupBudgets.FEED_FIRST_MS).use { deadline ->
                        deadline.run(request)
                    }
                } else {
                    // 首帧后的补页 / 翻页不参与启动预算，避免后台请求慢时误报失败。
                    request()
                }
            }
            if(attempt.isFailure) {
                val problem = attempt.exceptionOrNull() ?: IllegalStateException("推荐请求失败")
                if (problem is com.hgtv.star.core.StageTimeoutException) {
                    lines += "stage timeout stage=${problem.stage}"
                }
                return result(
                    items, page, hasMore, session, offset, filterMiss, filterIdsOff,
                    genderProbe, processedPages, restarts, error = problem, lines = lines,
                )
            }
            val feed = attempt.getOrThrow()

            if(feed.page.items.isEmpty() && gender != null && !genderFilter.off) {
                genderFilter.onEmpty()
                genderProbe = true
                if(genderFilter.off) genderProbe = false
                lines += "empty page with gender=$gender misses=${genderFilter.misses}"
                continue
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
            persistCursor(session, offset, horizontalOnly)

            val hidden = runCatching { library.hidden() }.getOrDefault(emptySet())
            val finished = runCatching { library.recommendFinished() }.getOrDefault(emptySet())
            val loadedIds = items.mapTo(HashSet(items.size)) { it.id }
            val loadedTitles = items.mapNotNull {
                RecommendFlow.titleKey(it.title).takeIf(String::isNotEmpty)
            }.toHashSet()
            val pageItems = feed.page.items
            val candidates = RecommendFlow.fresh(
                items = pageItems,
                hidden = hidden,
                finished = finished,
                seen = emptySet(),
                loadedIds = loadedIds,
                loadedTitles = loadedTitles,
                filterExposure = false,
            )
            val expired = !input.append &&
                now() - firstLoadAt >= policy.firstBudgetMs
            val exhausted = !feed.page.hasMore || restarts >= policy.maxRestarts || expired
            val shown = if(candidates.isEmpty() && exhausted) {
                pageItems.filterNot { it.id in hidden || it.id in finished }
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
                if(items.isNotEmpty()) RecommendCache.save(items, policy.cacheNamespace)
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

    private fun persistCursor(session: String, offset: Int, horizontalOnly: Boolean) {
        if(policy.namespacedCursor) {
            library.phoneFeedCursor = FeedCursor.upsert(
                library.phoneFeedCursor,
                FeedCursor.Entry(policy.cursorNamespace(horizontalOnly), session, offset),
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
    /** 是否支持 TV 的「只推荐横屏短剧」服务端请求参数。手机 / 平板保持 false。 */
    val supportsHorizontalOnly: Boolean = false,
    val firstLimit: Int = AppConfig.Recommend.FIRST_LIMIT,
    val pageLimit: Int = AppConfig.Recommend.PAGE_LIMIT,
    val maxPagesPerLoad: Int = AppConfig.Recommend.MAX_PAGES_PER_LOAD,
    val maxRestarts: Int = AppConfig.Recommend.MAX_RESTARTS,
    val maxRequestsPerLoad: Int = AppConfig.Recommend.MAX_REQUESTS_PER_LOAD,
    val prefetchRemaining: Int = AppConfig.Recommend.PREFETCH_REMAINING,
    val deepCursor: Int = Int.MAX_VALUE,
    val firstBudgetMs: Long = Long.MAX_VALUE,
    val quickFilterFailureMs: Long = Long.MAX_VALUE,
    val restartWhenNoMore: Boolean = false,
    val disableFilterOnAnyFailure: Boolean = false,
    val saveOnAppend: Boolean = true,
    val namespacedCursor: Boolean = true,
) {
    fun cursorNamespace(horizontalOnly: Boolean): String =
        if(supportsHorizontalOnly && horizontalOnly) "$cacheNamespace-horizontal"
        else cacheNamespace

    /** 当前页还剩多少条时补下一页；20 条一页时等价于消费约 2/3 后预取。 */
    fun shouldPrefetch(remaining: Int): Boolean = remaining <= prefetchRemaining

    companion object {
        /** TV 与 Phone 共用的推荐加载节奏。 */
        const val FIRST_LIMIT = AppConfig.Recommend.FIRST_LIMIT
        const val PAGE_LIMIT = AppConfig.Recommend.PAGE_LIMIT
        const val MAX_PAGES_PER_LOAD = AppConfig.Recommend.MAX_PAGES_PER_LOAD
        const val MAX_RESTARTS = AppConfig.Recommend.MAX_RESTARTS
        const val DEEP_CURSOR = AppConfig.Recommend.DEEP_CURSOR
        const val FIRST_BUDGET_MS = AppConfig.Recommend.FIRST_BUDGET_MS
        const val QUICK_FILTER_FAILURE_MS = AppConfig.Recommend.QUICK_FILTER_FAILURE_MS
        const val MIN_ITEMS_PER_LOAD = AppConfig.Recommend.MIN_ITEMS_PER_LOAD
        const val PREFETCH_REMAINING = AppConfig.Recommend.PREFETCH_REMAINING

        fun shouldPrefetch(remaining: Int): Boolean = remaining <= PREFETCH_REMAINING

        /**
         * 两端差异只有推荐流命名空间和 TV 的横屏请求能力位：Phone 按体裁拆流，
         * TV 只有一条主推荐流；取流、补页、过滤、重试和落盘节奏仍共用同一套。
         */
        fun standard(cacheNamespace: String, supportsHorizontalOnly: Boolean = false) = RecommendFeedPolicy(
            cacheNamespace = cacheNamespace,
            supportsHorizontalOnly = supportsHorizontalOnly,
            firstLimit = FIRST_LIMIT,
            pageLimit = PAGE_LIMIT,
            maxPagesPerLoad = MAX_PAGES_PER_LOAD,
            maxRestarts = MAX_RESTARTS,
            prefetchRemaining = PREFETCH_REMAINING,
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
