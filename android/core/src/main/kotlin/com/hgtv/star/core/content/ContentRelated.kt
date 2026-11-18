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
 * **空响应体是常态**（HTTP 200 + 0 字节，实测约一半概率），与设备是否注册无关：
 * 同一条线路连发几次里总有一次成功，所以这里换线重发、拿到 code 0 就返回；
 * 六条线路轮一圈都拿不到才抛出去（related 会把那一次失败当成「没有相关作品」）。
 */
internal fun ContentRepository.relatedRows(id: String): List<RelatedEntry> {
    var failure: IOException?=null
    val hosts=relatedHosts()
    for(attempt in 0 until relatedAttempts) {
        val host=hosts[attempt%hosts.size]
        if(attempt>0) {
            NetLog.retry("related",attempt+1,relatedAttempts,failure?.message.orEmpty())
        }
        val values=appValues()
        values.putAll(linkedMapOf(
            "scene" to "10","from" to "detail_page_more_related","book_id" to id,
            "new_search_middle_page" to "false","bookstore_tab_type" to "0",
            "query_history_removed" to "false","user_is_login" to "0",
            "bookstore_tab" to "0","search_middle_page_version" to "0","pad_column_detail" to "0",
            "hot_word_exchange" to "false","device_level" to "4","limit" to "0","offset" to "0",
            "is_horizontal_screen" to "false","reader_single_col_from_ip_strategy" to "false"
        ))
        try {
            val signed=signer.sign("https://$host/reading/bookapi/plan/v:version/",values)
            val raw=text(signed.url,signed.withSession())
            if(raw.isBlank()) { failure=IOException("相关作品接口暂无响应"); continue }
            val data=JSONObject(raw)
            if(data.optInt("code",-1)!=0) {
                failure=IOException("相关作品接口暂不可用（${data.optInt("code")}）"); continue
            }
            val rows=ContentDetailParser.parseRelatedRows(
                data,
                relatedCellShowType,
                relatedCellName,
            )
            if(rows.isNotEmpty()) return rows
            failure=IOException("相关作品数据为空")
        } catch(e: Exception) {
            failure=e as? IOException ?: IOException(e.message ?: "相关作品请求失败")
        }
    }
    throw failure ?: IOException("相关作品接口暂不可用")
}
