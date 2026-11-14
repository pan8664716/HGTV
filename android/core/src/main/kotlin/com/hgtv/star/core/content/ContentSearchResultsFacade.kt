// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/** 只搜一种内容类型：联调探针仍可显式指定 `tab_type`。 */
fun ContentRepository.search(keyword: String, page: Int, type: ContentType): CatalogPage =
    appSearch(keyword,page,type.searchTab,SEARCH_PAGE_SIZE)

/**
 * 官网搜索：`GET https://hongguoduanju.com/api/search/result?query={关键词}&page=N`。
 *
 * 2026-10 之前走的是搜索页 HTML 里的 `_ROUTER_DATA`；`www` 改成 301 到裸域名、
 * 页面也不再内联 SSR 数据之后，那条路整条失效。现在直连官网自己用的 JSON 接口，
 * 字段与卡片同源（`searchList[].video_data`），`totalPages` / `hasMore` 由服务端给。
 *
 * **一个逻辑页给界面 100 条**：服务端一页只有 10 条，而且 `count` / `page_size` / `limit`
 * 这类参数一律被忽略（见 `ContentRepository.WEB_SEARCH_PAGE_SIZE`），所以这里把
 * `ContentRepository.WEB_SEARCH_BATCH` 个物理页并发拉回来拼成一份，
 * 与 App 搜索一样每个逻辑页 100 条。
 *
 * 查不到东西时接口回的是 `{"searchList":[],"totalPages":1}`（HTTP 200），
 * 按「没有结果」处理，让界面显示「换个关键词试试」。
 */
fun ContentRepository.webSearch(keyword: String,page: Int = 1): CatalogPage =
    webSearchImpl(keyword,page)
