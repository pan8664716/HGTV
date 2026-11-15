// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 分类页筛选面板缓存，按大类分别保存。
 *
 * 小分类很少变化，但切换大类时原来的实现会重新请求整份 `selector_rows`。
 * 这里把每个大类第一次拿到的面板保存到内存与磁盘，后续切换大类只请求剧集列表。
 */
object CategoryPanelCache {
    private const val VERSION=1
    private const val TTL_MS=30L*24*60*60*1000

    @Volatile private var file: File?=null
    private val lock=Any()
    private var entries: MutableMap<String,JSONObject>?=null
    private val writer by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable,"hgtv-category-panel-cache").apply { isDaemon=true }
        }
    }

    fun init(target: File) {
        synchronized(lock) {
            file=target
            entries=null
        }
    }

    /** 命中且未过期时返回该大类的小分类面板。 */
    fun load(genre: String?): CategoryPanel? {
        val entry=synchronized(lock) { map()[keyOf(genre)] } ?: return null
        if(System.currentTimeMillis()-entry.optLong("at")>TTL_MS) return null
        return runCatching {
            parsePanel(entry.getJSONObject("panel"))
        }.getOrNull()?.takeIf { it.rows.isNotEmpty() }?.withCatalogThemes(genre)
    }

    /** 保存接口返回的筛选面板；写盘在独立线程完成。 */
    fun save(genre: String?, panel: CategoryPanel?) {
        if(panel==null || panel.rows.isEmpty()) return
        val normalized=panel.withCatalogThemes(genre)
        val entry=JSONObject()
            .put("at",System.currentTimeMillis())
            .put("panel",panelToJson(normalized))
        synchronized(lock) {
            map()[keyOf(genre)]=entry
        }
        val target=file ?: return
        val text=synchronized(lock) { serialize() } ?: return
        runCatching {
            writer.execute {
                runCatching { write(target,text) }
            }
        }
    }

    fun clear() {
        synchronized(lock) { entries=null }
        runCatching { file?.delete() }
    }

    private fun keyOf(genre: String?): String =
        genre?.takeIf { it.isNotBlank() } ?: "recommend"

    private fun serialize(): String? = runCatching {
        val all=JSONObject()
        map().forEach { (key,value) -> all.put(key,value) }
        JSONObject().put("version",VERSION).put("entries",all).toString()
    }.getOrNull()

    private fun write(target: File,text: String) {
        target.parentFile?.mkdirs()
        val tmp=File(target.path+".tmp")
        tmp.writeText(text)
        target.delete()
        tmp.renameTo(target)
    }

    private fun map(): MutableMap<String,JSONObject> {
        entries?.let { return it }
        val target=file
        val parsed=linkedMapOf<String,JSONObject>()
        if(target!=null && target.isFile) {
            runCatching {
                val root=JSONObject(target.readText())
                if(root.optInt("version")!=VERSION) return@runCatching
                val all=root.optJSONObject("entries") ?: return@runCatching
                all.keys().forEach { key ->
                    all.optJSONObject(key)?.let { parsed[key]=it }
                }
            }
        }
        entries=parsed
        return parsed
    }

    private fun panelToJson(panel: CategoryPanel): JSONObject {
        val rows=JSONArray()
        panel.rows.forEach { row ->
            val items=JSONArray()
            row.options.forEach { option ->
                items.put(JSONObject().put("id",option.id).put("label",option.label))
            }
            rows.put(JSONObject()
                .put("type",row.type)
                .put("title",row.title)
                .put("items",items))
        }
        return JSONObject().put("rows",rows)
    }

    private fun parsePanel(value: JSONObject): CategoryPanel {
        val source=value.optJSONArray("rows") ?: JSONArray()
        val rows=(0 until source.length()).mapNotNull { index ->
            val row=source.optJSONObject(index) ?: return@mapNotNull null
            val type=row.str("type")
            val items=row.optJSONArray("items") ?: JSONArray()
            val options=(0 until items.length()).mapNotNull { itemIndex ->
                val item=items.optJSONObject(itemIndex) ?: return@mapNotNull null
                val id=item.str("id")
                val label=item.str("label")
                if(id.isBlank() || label.isBlank()) null else CategoryFilterOption(id,label)
            }
            if(type.isBlank() || options.isEmpty()) null
            else CategoryFilterRow(type,row.str("title"),options)
        }
        return CategoryPanel(rows)
    }
}
