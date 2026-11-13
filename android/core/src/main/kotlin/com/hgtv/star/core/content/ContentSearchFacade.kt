// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 搜索提示词（联想词）：App 搜索框用的那个接口。
 *
 * ```
 * GET https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/search/suggest/v:version/?q={关键词}
 * ```
 *
 * 需要设备参数与签名（[ContentRepository.searchValues] + [Signer]），不需要登录态。空关键词不发请求。
 *
 * 响应里 `data.query_result_v2[]` 每条的字段：
 *
 * | 字段 | 说明 |
 * | --- | --- |
 * | `name`（兜底 `display_words[0]`） | 提示词本身 |
 * | `word_type_str` | `short_play_name`（剧名）/ `title` / `album` / `common_query` / `short_play_category` … |
 * | `video_data` | **有 `series_id` 时**才是「这条本身就是一部剧」，可以直接进详情；否则只能当查询词 |
 *
 * `video_data` 与列表卡片是同一套字段（`title` / `cover` / `video_desc` / `episode_cnt` /
 * `copyright`），因此复用 `apiCard` 解析。
 */
fun ContentRepository.suggestions(
    keyword: String,
    count: Int = 10,
    source: SearchSource = SearchSource.APP,
): List<SearchSuggestion> =
    if(source==SearchSource.WEB) webSuggestions(keyword,count) else appSuggestions(keyword,count)

/**
 * 官网的联想词：`GET /api/search/suggestion?query={关键词}&count=N`。
 *
 * 免签名、免登录；`video_data` 与卡片同源，有 `series_id` 的那几条本身就是一部剧。
 * 搜索源选「官网」时搜索页那一行用它。
 *
 * 老地址 `/incent_resource/suggestion?app_id=8662` 现在仍然能回，但字段是 `suggest_list`、
 * 与页面那一套绑得更紧；新地址跟搜索结果是同一个接口组，一起换过来更稳（两个都认）。
 */
fun ContentRepository.webSuggestions(keyword: String, count: Int = 10): List<SearchSuggestion> =
    webSuggestionsImpl(keyword,count)

/**
 * 热搜词：搜索页空输入时铺在下面那一排。
 *
 * 平台**没有**热搜榜接口（`hot_word` / `suggest_word` 这类路径实测全是 404），
 * 但 [suggestions] 这个联想词接口本身就是「按热度排的候选」：拿几个题材词当种子各问一次，
 * 把返回里**不是某一部剧**的那些（`title` / `common_query` / `short_play_category` / `category` /
 * `album`——`video_data` 是空对象）收集起来去重，就是一份现成的热词表。
 *
 * 只取种子词里靠前的若干条：联想词是按相关度排的，越靠前越像「大家在搜的」。
 * 全部种子词都失败时退回 `FALLBACK_HOT`：搜索页不能因为一个提示接口而空着。
 */
fun ContentRepository.hotSearches(
    limit: Int = 12,
    source: SearchSource = SearchSource.APP,
): List<String> =
    hotSearchesImpl(limit,source)
