// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.json.JSONObject
import java.io.IOException

/**
 * 取「相关作品」那一栏的原始条目。
 *
 * 请求体（query）按官方 App 的形态给：scene=10、from=detail_page_more_related、
 * book_id={剧集 ID}，外加设备参数与签名（见 appValues / Signer）。
 * from 必须正是 detail_page_more_related：换别的取值服务端一律回空响应体。
 *
 * `HTTP 200 + 空响应` 由 [ContentRepository.text] 统一重试一次，仍为空即失败；
 * 这里只处理正常业务失败与线路切换。
 */
internal fun ContentRepository.relatedRows(id: String): List<RelatedEntry> =
    planRows(
        id = id,
        from = "detail_page_more_related",
        cellName = relatedCellName,
        filterId = null,
        operation = "related",
    )

/**
 * 取同一部剧的全部季。
 *
 * 抓包里详情页的「相关作品」cell 混着同 IP 的其他剧；自动连播需要的是另一条
 * `from=single_col_same_ip_series` 请求，返回 cell 名为「系列剧」，只含同一系列各季。
 * `filter_ids` 必须与 `book_id` 相同，参数形态见 `docs/HONGGUO_API.md` 4.6.1。
 */
internal fun ContentRepository.sameSeriesRows(id: String): List<RelatedEntry> =
    planRows(
        id = id,
        from = "single_col_same_ip_series",
        cellName = seriesCellName,
        filterId = id,
        operation = "series",
    )

private fun ContentRepository.planRows(
    id: String,
    from: String,
    cellName: String,
    filterId: String?,
    operation: String,
): List<RelatedEntry> {
    var failure: IOException?=null
    val label=if(from=="single_col_same_ip_series") "系列剧接口" else "相关作品接口"
    val hosts=relatedHosts()
    for(attempt in 0 until relatedAttempts) {
        val host=hosts[attempt%hosts.size]
        if(attempt>0) {
            NetLog.retry(operation,attempt+1,relatedAttempts,failure?.message.orEmpty())
        }
        val values=appValues()
        val common=linkedMapOf(
            "scene" to "10","from" to from,"book_id" to id,
            "new_search_middle_page" to "false","bookstore_tab_type" to "0",
            "query_history_removed" to "false","user_is_login" to "0",
            "bookstore_tab" to "0","search_middle_page_version" to "0","pad_column_detail" to "0",
            "hot_word_exchange" to "false",
        )
        if(filterId!=null) common["filter_ids"]=filterId
        common.putAll(linkedMapOf(
            "device_level" to "4","limit" to "0","offset" to "0",
            "is_horizontal_screen" to "false","reader_single_col_from_ip_strategy" to "false"
        ))
        values.putAll(common)
        try {
            val signed=signer.sign("https://$host/reading/bookapi/plan/v:version/",values)
            val raw=text(signed.url,signed.withSession())
            val data=JSONObject(raw)
            if(data.optInt("code",-1)!=0) {
                failure=IOException("${label}暂不可用（${data.optInt("code")}）"); continue
            }
            val rows=ContentDetailParser.parseRelatedRows(
                data,
                relatedCellShowType,
                cellName,
            )
            if(rows.isNotEmpty()) return rows
            failure=IOException("${label}数据为空")
        } catch(e: Exception) {
            if(e is V5SignatureFailedException) throw e
            failure=e as? IOException ?: IOException(e.message ?: "${label}请求失败")
        }
    }
    throw failure ?: IOException("${label}暂不可用")
}
