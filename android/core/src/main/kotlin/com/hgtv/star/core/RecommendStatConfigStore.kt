// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.json.JSONObject

/**
 * 推荐曝光上报的远程配置。
 *
 * 官方配置项为 `short_video_recommend_stat_report_duration`，当前抓包结构：
 * `{"enable":true,"duration":1.0}`。服务端没有下发或读取失败时按项目约定使用 10 秒兜底。
 */
object RecommendStatConfigStore {
    const val DEFAULT_DURATION_MS=10_000L
    const val CONFIG_KEY="short_video_recommend_stat_report_duration"

    @Volatile private var enabled=true
    @Volatile private var durationMs=DEFAULT_DURATION_MS

    fun isEnabled(): Boolean=enabled
    fun durationMs(): Long=durationMs

    /** 解析 `/service/settings/v3/` 的响应；找不到配置时保留默认值。 */
    fun applySettings(raw: String): Boolean {
        val root=runCatching { JSONObject(raw) }.getOrNull() ?: return false
        val settings=root.optJSONObject("data")?.optJSONObject("settings")
            ?: root.optJSONObject("settings")
            ?: root
        val node=find(settings,CONFIG_KEY) ?: return false
        val durationSeconds=when(val value=node.opt("duration")) {
            is Number -> value.toDouble()
            else -> value?.toString()?.toDoubleOrNull()
        } ?: (DEFAULT_DURATION_MS/1000.0)
        enabled=node.optBoolean("enable",true)
        durationMs=(durationSeconds*1000.0).toLong().coerceAtLeast(0L)
        return true
    }

    private fun find(node: JSONObject,key: String): JSONObject? {
        node.optJSONObject(key)?.let { return it }
        val value=node.opt(key)
        if(value is String) {
            runCatching { JSONObject(value) }.getOrNull()?.let { return it }
        }
        val keys=node.keys()
        while(keys.hasNext()) {
            val child=node.optJSONObject(keys.next()) ?: continue
            find(child,key)?.let { return it }
        }
        return null
    }
}
