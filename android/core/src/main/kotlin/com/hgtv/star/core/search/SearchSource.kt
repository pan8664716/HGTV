// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 搜索页的数据源（设置里可选）。
 *
 * 只影响**搜索**：结果列表、联想词、热搜词。分类 / 榜单 / 详情 / 播放仍然走 App 接口，
 * 因此这个开关不会改变「点开一部剧之后」的任何行为。
 *
 * * [APP]：`api5-normal-sinfonlinea.fqnovel.com` 的搜索接口，结果全、能一直翻页，
 *   但要求这台设备在服务端注册过（未注册时回空响应体）；
 * * [WEB]：官网 `hongguoduanju.com` 的搜索接口（`/api/search/result`），免签名免登录，
 *   一次 10 条、按页翻，适合「App 接口暂时没数据」时换一条路。
 *   （2026-10 起官网页面不再内联 SSR 数据，因此从「解析搜索页 HTML」改成了直连 JSON 接口。）
 *
 * [storedValue] 写进本机设置与备份文件，**取值不能改**。
 */
enum class SearchSource(val storedValue: String,val label: String,val hint: String) {
    APP("app","App","App 搜索接口 · 结果全，可一直翻页"),
    WEB("web","官网","官网搜索 · 免登录，结果略少");

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.storedValue==value } ?: APP
    }
}
