// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.json.JSONObject

/**
 * 出品方：把接口给的 `copyright` 归一成能直接显示的名字。
 *
 * 短剧那一栏里有一部分是**占位串**（`短剧版权3493645060`、`短剧版权5146586586`），
 * 那是平台没填版权方时自动生成的编号，显示出来只会让卡片第二行变成一串数字。
 * 这类一律当空值处理：卡片那一行收起，由 [authorsOf] 的详情兜底去补。
 */
fun ContentRepository.publisher(raw: String): String {
    return ContentCatalogParser.publisher(raw)
}

/**
 * 题材 / 类型标签：卡片第二行显示的那一串（`玄幻 · 逆袭`）。
 *
 * 三处来源同一个意思，字段名不同：
 * * App 列表 / 搜索 / 详情：`category_schema`（JSON 字符串，`[{"name":"玄幻"}]`）；
 * * 官网搜索：`category_list`（真数组）。
 *
 * 最多取 5 个、` · ` 连接（与详情页信息行同一套写法）。都没有就返回空串，
 * 卡片第二行退回状态行（进度 / 热度），而不是留一条空白。
 */
fun ContentRepository.tagsOf(o: JSONObject, key: String = "category_schema"): String {
    return ContentCatalogParser.tagsOf(o,key)
}
