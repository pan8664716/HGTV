// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.json.JSONObject
import java.io.File

/**
 * 分类 / 榜单首屏的共享持久缓存。
 *
 * 分类按 `category/landpage` 的筛选条件缓存；榜单来自独立的 `cell/change`，
 * 使用 `rank-v2:<board>` 键，避免和旧分类榜或旧版四榜缓存串数据。
 */
object CatalogPageCache {
    private const val VERSION = 1
    private const val MAX_ENTRIES = AppConfig.Catalog.PAGE_CACHE_MAX_ENTRIES

    private data class Entry(
        val page: CatalogPage,
        val savedAt: Long,
        val raw: String,
    )

    @Volatile private var file: File? = null
    private val lock = Any()
    private var entries: MutableMap<String, Entry>? = null
    private val writer by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "hgtv-catalog-page-cache").apply { isDaemon = true }
        }
    }

    fun init(target: File) {
        synchronized(lock) {
            file = target
            entries = null
        }
        // 缓存文件可能包含多个 100 条首屏；在后台预解析，页面读取时只碰内存。
        writer.execute {
            synchronized(lock) { map() }
        }
    }

    fun load(selection: CategorySelection): CatalogPage? {
        return loadKey(cacheKey(selection))
    }

    fun loadRank(board: RankBoard): CatalogPage? = loadKey(rankCacheKey(board))

    private fun loadKey(key: String): CatalogPage? {
        val entry = synchronized(lock) { entries?.get(key) } ?: return null
        val age = System.currentTimeMillis() - entry.savedAt
        return entry.page.takeIf { age in 0..CatalogSnapshot.MAX_AGE_MS }
    }

    fun save(selection: CategorySelection, page: CatalogPage) {
        saveKey(cacheKey(selection), page)
    }

    fun saveRank(board: RankBoard, page: CatalogPage) {
        saveKey(rankCacheKey(board), page)
    }

    private fun saveKey(key: String, page: CatalogPage) {
        if (page.items.isEmpty()) return
        val now = System.currentTimeMillis()
        val raw = CatalogSnapshot.encode(
            items = page.items,
            hasMore = page.hasMore,
            now = now,
            ranking = page.ranking,
            maxItems = AppConfig.Catalog.PAGE_CACHE_MAX_ITEMS,
        )
        val entry = Entry(page, now, raw)
        synchronized(lock) {
            val all = map()
            all.remove(key)
            all[key] = entry
            while (all.size > MAX_ENTRIES) {
                all.remove(all.keys.firstOrNull() ?: break)
            }
        }
        val target = file ?: return
        val text = synchronized(lock) { serialize() } ?: return
        runCatching {
            writer.execute {
                runCatching { write(target, text) }
            }
        }
    }

    fun clear() {
        synchronized(lock) { entries = null }
        runCatching { file?.delete() }
    }

    private fun rankCacheKey(board: RankBoard): String = "rank-v2|${board.storedValue}"

    fun cacheKey(selection: CategorySelection): String {
        // sort=null 在无细分条件时走推荐流；有细分条件时 categoryApi 会回退到 hot_score。
        val effectiveSort = selection.sort
            ?: if (selection.hasNoDetailFilters()) "recommend" else CategorySelection.DEFAULT_SORT
        return listOf(
            selection.genre.orEmpty(),
            selection.theme.orEmpty(),
            selection.role.orEmpty(),
            selection.epoch.orEmpty(),
            effectiveSort,
            selection.gender.orEmpty(),
        ).joinToString("|")
    }

    private fun serialize(): String? = runCatching {
        val all = JSONObject()
        map().forEach { (key, value) -> all.put(key, value.raw) }
        JSONObject().put("version", VERSION).put("entries", all).toString()
    }.getOrNull()

    private fun write(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".tmp")
        tmp.writeText(text)
        target.delete()
        tmp.renameTo(target)
    }

    private fun map(): MutableMap<String, Entry> {
        entries?.let { return it }
        val target = file
        val parsed = linkedMapOf<String, Entry>()
        val now = System.currentTimeMillis()
        if (target != null && target.isFile) {
            runCatching {
                val root = JSONObject(target.readText())
                if (root.optInt("version") != VERSION) return@runCatching
                val all = root.optJSONObject("entries") ?: return@runCatching
                all.keys().forEach { key ->
                    val raw = all.optString(key)
                    if (raw.isBlank()) return@forEach
                    val page = CatalogSnapshot.decode(
                        raw,
                        now,
                        maxValueChars = 500_000,
                    ) ?: return@forEach
                    val savedAt = runCatching {
                        JSONObject(raw).getLong("savedAt")
                    }.getOrNull() ?: return@forEach
                    parsed[key] = Entry(page, savedAt, raw)
                }
            }
        }
        entries = parsed
        return parsed
    }
}
