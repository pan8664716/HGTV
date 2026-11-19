// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import java.io.IOException

/** 榜单与分类的业务门面：分页、首页并发和筛选面板缓存都在这里决定。 */

/**
 * 榜单的**一个小页**（[RANK_API_PAGE_SIZE] 条）。
 *
 * 首页八个榜单剧架每栏只显示 10 条，正好复用榜单接口的一页。
 */
fun ContentRepository.rankRaw(page: Int = 1, board: RankBoard = RankBoard.RECOMMEND): CatalogPage {
    require(page in 1..100)
    return rankApiPage(page,board,RANK_API_PAGE_SIZE)
}

/**
 * 榜单页的一页：首屏与后续页都由接口的 `next_offset` 游标续接。
 *
 * [previous] 是上一页返回的 [ComicRanking]；其中保存了 `rank_version`、
 * `session_id`、`filter_ids` 和 `next_offset`。首页缓存恢复后也要把它交给下一页请求。
 */
fun ContentRepository.rank(
    page: Int = 1,
    board: RankBoard = RankBoard.RECOMMEND,
    previous: ComicRanking? = null,
): CatalogPage {
    require(page in 1..100)
    if(page==1) CatalogPageCache.loadRank(board)?.let { return it }
    return rankApiPage(page,board,RANK_API_PAGE_SIZE,cursor=previous).also { fetched ->
        if(page==1) CatalogPageCache.saveRank(board,fetched)
    }
}

/**
 * 首页八个榜单剧架的第 1 页：八个榜单彼此独立，串行要等八份 RTT，因此并发取。
 *
 * 单个榜单失败只让那一栏不出现（首页其余内容照常上屏），八个都失败才抛错。
 * 取消（切页 / 换标签）沿着 [RequestScope] 传进工作线程，在途请求会一起收手。
 *
 * **不再每次调用新建一个池**：改造前这里是 `newFixedThreadPool(4)` + `finally shutdownNow()`，
 * 每次进首页都创建再销毁四条线程；`shutdownNow()` 还会打断在途请求（被下游的
 * `runCatching` 吞成「这一栏没有内容」）。现在用共享的 `fanout` 车道，
 * 并发度不变，创建/销毁与误打断都没了。
 */
fun ContentRepository.homeFeed(): Map<RankBoard,CatalogPage> {
    val scope=RequestScope.current()
    val pool=WorkLanes.fanout.executor
    val futures=RankBoard.entries.map { board ->
        // 首页每栏只显示 10 条，取 [RANK_API_PAGE_SIZE] 条的小页就够。
        board to pool.submit(java.util.concurrent.Callable {
            runCatching { RequestScope.withScope(scope) { rankRaw(1,board) } }
        })
    }
    val result=LinkedHashMap<RankBoard,CatalogPage>()
    var firstFailure: Throwable?=null
    futures.forEach { (board,future) ->
        val outcome=runCatching { future.get() }
            .getOrElse { failure -> Result.failure(failure.cause ?: failure) }
        outcome.onSuccess { page -> result[board]=page }
            .onFailure { failure -> if(firstFailure==null) firstFailure=failure }
    }
    if(result.values.none { it.items.isNotEmpty() }) {
        val failure=firstFailure
        if(failure is IOException) throw failure
        throw IOException("热门榜暂不可用",failure)
    }
    return result
}

/**
 * 分类目录的一页（`path` 来自 [CategoryCatalog]，例如 `comic-drama/fantasy`）。
 *
 * 走 App 分类接口 [categoryApi]，一次给 [CATEGORY_PAGE_SIZE] 条、
 * `has_more` 可一直往下翻。接口响应与榜单共用，解析时保留名次 / 热度，
 * 这样同一份首屏缓存可直接供分类和榜单使用；分类界面本身不展示名次。
 */
fun ContentRepository.category(path: String, page: Int = 1): CatalogPage {
    require(path.isNotBlank() && path.length<=80 && page in 1..ContentRepository.MAX_CATALOG_PAGE)
    return categoryFeed(CategoryCatalog.selectionOf(path),page,needPanel=false).page
}

/**
 * 分类页：按 [selection] 取一页内容；[needPanel] 为真时**同一次请求**
 * 顺带把整份筛选面板（`selector_rows`）带回来。
 *
 * 这就是「一次拿到所有筛选条件」的入口：`need_selector_panel=true` +
 * `req_type=default` 的响应里同时有 `video_data` 与 `selector_rows`，
 * 面板包含体裁 / 主题 / 设定 / 背景 / 推荐 / 受众 / 时间 / 状态全部行。
 *
 * 注意：主题 / 设定 / 背景 / 状态会随体裁变化（`genre` 行带
 * `refresh_other_selector_row=true`）。换体裁时优先读取
 * [CategoryPanelCache]，命中后只请求内容列表。
 */
fun ContentRepository.categoryFeed(selection: CategorySelection, page: Int = 1, needPanel: Boolean = false): CategoryFeed {
    require(page in 1..ContentRepository.MAX_CATALOG_PAGE)
    if(needPanel) {
        CategoryPanelCache.load(selection.genre)?.let { panel ->
            if(page==1) CatalogPageCache.load(selection)?.let { cached ->
                return CategoryFeed(cached,panel)
            }
            val fetched=categoryApi(selection,page,needPanel=false).page
            if(page==1) CatalogPageCache.save(selection,fetched)
            return CategoryFeed(fetched,panel)
        }
    } else if(page==1) {
        CatalogPageCache.load(selection)?.let { return CategoryFeed(it,null) }
    }
    val result=categoryApi(selection,page,needPanel)
    if(page==1) CatalogPageCache.save(selection,result.page)
    if(needPanel) CategoryPanelCache.save(selection.genre,result.panel)
    return result
}
