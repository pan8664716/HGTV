// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 片库里的内容类型。**界面上的「漫剧 / 短剧」开关已经去掉**，搜索与首页都不再问用户「看哪一类」，
 * 这个枚举现在只剩两处用处：
 *
 * 1. [searchTab]：旧版 App 搜索接口按 `tab_type` 分 tab（`19` 漫剧、`11` 短剧 / 真人剧）；
 *    界面搜索现走不带 `tab_type` 的综合请求，这里只给类型探针保留显式 tab，[AI] 为 `0`。
 * 2. [catalog]：片库目录的路由段（`comic-drama` / `real-drama` / `ai-drama`），
 *    搜索第 2 页起从这几本目录里筛片名。
 *
 * [storedValue] 写在本机备份的 `settings.type` 与搜索历史里，**取值不能改**：
 * 旧版本的备份文件里是 `comic` / `short`，读进来必须仍然认得。
 */
enum class ContentType(val storedValue: String, val label: String, val searchTab: Int, val catalog: String) {
    COMIC("comic","漫剧",19,"comic-drama"),
    SHORT("short","短剧",11,"real-drama"),
    AI("ai","AI剧",0,"ai-drama");

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.storedValue==value } ?: COMIC
        /** 有对应 App 搜索 tab 的类型（[AI] 没有，搜索时跳过它）。 */
        val searchable = entries.filter { it.searchTab>0 }
    }
}
