// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import java.io.IOException

/** 榜单与分类的业务门面：分页、首页并发和筛选面板缓存都在这里决定。 */

/**
 * 榜单的**一个小页**（[RANK_API_PAGE_SIZE] 条）。
 *
 * 首页那四个剧架每栏只显示 10 条，取 20 条已经足够。
 */
fun ContentRepository.rankRaw(page: Int = 1, board: RankBoard = RankBoard.COMIC): CatalogPage {
    require(page in 1..100)
    return rankApiPage(page,board,RANK_API_PAGE_SIZE)
}

/**
 * 榜单页的一页：首次 30 条，后续页按 [LOGICAL_PAGE_SIZE] 条续接。
 *
 * 首屏先给用户 30 条，减少进入页面时的等待；第 2 页从 offset=30 开始，
 * 后续每页 100 条，不会漏条。
 */
fun ContentRepository.rank(page: Int = 1, board: RankBoard = RankBoard.COMIC): CatalogPage {
    require(page in 1..100)
    val size=if(page==1) RANK_FIRST_PAGE_SIZE else LOGICAL_PAGE_SIZE
    val offset=if(page==1) 0 else RANK_FIRST_PAGE_SIZE+(page-2)*LOGICAL_PAGE_SIZE
    return rankApiPage(page,board,size,offset)
}

/**
 * 首页四个榜单剧架的第 1 页：四个榜单彼此独立，串行要等四份 RTT，因此并发取。
 *
 * 单个榜单失败只让那一栏不出现（首页其余内容照常上屏），四个都失败才抛错。
 * 取消（切页 / 换标签）沿着 [RequestScope] 传进工作线程，在途的四个请求会一起收手。
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
        board to pool.submit(java.util.concurrent.Callable { RequestScope.withScope(scope) { runCatching { rankRaw(1,board) }.getOrNull() } })
    }
    val result=LinkedHashMap<RankBoard,CatalogPage>()
    futures.forEach { (board,future) -> future.get()?.let { page -> result[board]=page } }
    if(result.values.none { it.items.isNotEmpty() }) throw IOException("热门榜暂不可用")
    return result
}

/**
 * 分类目录的一页（`path` 来自 [CategoryCatalog]，例如 `comic-drama/fantasy`）。
 *
 * 走 App 分类接口 [categoryApi]，一次给 [CATEGORY_PAGE_SIZE] 条、
 * `has_more` 可一直往下翻；分类页**没有名次与热度**，所以 [CatalogPage.ranking] 恒为 null。
 */
fun ContentRepository.category(path: String, page: Int = 1): CatalogPage {
    require(path.isNotBlank() && path.length<=80 && page in 1..ContentRepository.MAX_CATALOG_PAGE)
    return categoryApi(CategoryCatalog.selectionOf(path),page,needPanel=false).page
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
            return CategoryFeed(categoryApi(selection,page,needPanel=false).page,panel)
        }
    }
    val result=categoryApi(selection,page,needPanel)
    if(needPanel) CategoryPanelCache.save(selection.genre,result.panel)
    return result
}
