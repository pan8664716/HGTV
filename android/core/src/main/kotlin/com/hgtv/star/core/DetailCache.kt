// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 剧集详情的落盘缓存（只给**推荐页起播**用）。
 *
 * ## 为什么需要
 *
 * 推荐页起播要三段：详情（`video_detail` 约 0.4~0.6s）→ 播放地址（有落盘缓存就 0ms）
 * → 索引 + 首块（有落盘缓存就 0ms）。三段里只剩第一段还在走网络。
 *
 * 同一进程内它由 `recommendDetails`（内存 LRU 6 条）兜着；但 2G 盒子上「主页看一会儿
 * 再回推荐」很容易整进程被回收，那一次就退回「整条链路重新取」，用户读到的正是
 * 「缓存好像没用上」。
 *
 * 这里把详情（剧卡片 + 分集 vid 列表）落一份到盘上，[TTL_MS] 内直接当有效：
 * 进程被杀之后进推荐，列表来自 `RecommendCache`、详情来自这里、地址与索引来自
 * `PlaybackCache`——首屏一次网络请求都不用发。
 *
 * ## 边界
 *
 * * 只存 [MAX_ENTRIES] 条（推荐页一屏够用），一条几 KB；
 * * [TTL_MS] 之后不用：分集表会变（更新了新集），拿半小时前的去起播第 1 集没问题，
 *   再久就不该替用户决定「这部还有没有新集」；
 * * **详情页不走这里**（`ContentRepository.detail` 默认仍然现取）：那一页用户是来读
 *   集数与更新的，宁可慢一点也要新。
 */
object DetailCache {
    private const val VERSION=1
    private const val MAX_ENTRIES=8
    /** 与 [RecommendCache] 的 TTL 对齐：快照能当首屏多久，详情就能配它多久。 */
    private const val TTL_MS=30*60*1000L

    @Volatile private var file: File?=null
    private val lock=Any()
    /** 解析过的整份缓存（键 = 剧 id）。第一次 [load] / [save] 时从盘上读一次。 */
    private var entries: MutableMap<String,JSONObject>?=null
    private val writer by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable,"hgtv-detail-cache").apply { isDaemon=true }
        }
    }

    fun init(target: File) { file=target }

    /** 命中就返回详情；没有 / 过期 / 读坏了返回 null。 */
    fun load(id: String): Detail? {
        if(id.isEmpty()) return null
        val began=System.nanoTime()
        val entry=synchronized(lock) { map()[id] } ?: return null
        if(System.currentTimeMillis()-entry.optLong("at")>TTL_MS) return null
        val detail=runCatching {
            val series=Series.fromJson(entry.getJSONObject("series"))
            val rows=entry.optJSONArray("episodes") ?: JSONArray()
            val episodes=(0 until rows.length()).mapNotNull { rows.optString(it).takeIf { vid -> vid.isNotEmpty() } }
            if(series.id.isEmpty()) return@runCatching null
            Detail(series,episodes,entry.optBoolean("upcoming",false),entry.optBoolean("reserved",false))
        }.getOrNull() ?: return null
        if(NetLog.timing) NetLog.log("detail cache hit id=${id.takeLast(6)} episodes=${detail.episodes.size} took=${(System.nanoTime()-began)/1_000_000}ms")
        return detail
    }

    /** 存一份详情（后台线程写盘，调用方通常就在 io 线程上）。 */
    fun save(detail: Detail) {
        val id=detail.series.id
        if(id.isEmpty()) return
        val entry=runCatching {
            JSONObject().put("at",System.currentTimeMillis())
                .put("series",detail.series.toJson())
                .put("episodes",JSONArray().apply { detail.episodes.forEach { put(it) } })
                .put("upcoming",detail.upcoming)
                .put("reserved",detail.reserved)
        }.getOrNull() ?: return
        val target=file ?: return
        synchronized(lock) {
            val map=map()
            map[id]=entry
            // 超上限：丢最早写进来的那几条（`LinkedHashMap` 的插入序，够用且不必排序）。
            while(map.size>MAX_ENTRIES) map.keys.firstOrNull()?.let(map::remove)
        }
        val text=synchronized(lock) {
            runCatching {
                val body=JSONObject().put("version",VERSION)
                val all=JSONObject()
                map().forEach { (key,value) -> all.put(key,value) }
                body.put("entries",all).toString()
            }.getOrNull()
        } ?: return
        runCatching { writer.execute { runCatching { write(target,text) } } }
    }

    /** 原子替换：先写 `.tmp` 再 rename，进程中途被杀不会留下半份。 */
    private fun write(target: File,text: String) {
        val tmp=File(target.path+".tmp")
        tmp.writeText(text)
        target.delete()
        tmp.renameTo(target)
    }

    fun clear() {
        synchronized(lock) { entries=null }
        runCatching { file?.delete() }
    }

    /** 整份缓存只读一次盘（每次起播都读一遍 JSON 太浪费，这份文件本来就只有几 KB）。 */
    private fun map(): MutableMap<String,JSONObject> {
        entries?.let { return it }
        val target=file
        val parsed=runCatching {
            if(target==null || !target.isFile) return@runCatching mutableMapOf()
            val root=JSONObject(target.readText())
            if(root.optInt("version",-1)!=VERSION) return@runCatching mutableMapOf()
            val all=root.optJSONObject("entries") ?: return@runCatching mutableMapOf()
            val out=LinkedHashMap<String,JSONObject>()
            all.keys().asSequence().forEach { key -> all.optJSONObject(key)?.let { out[key]=it } }
            out
        }.getOrDefault(mutableMapOf())
        entries=parsed
        return parsed
    }
}
