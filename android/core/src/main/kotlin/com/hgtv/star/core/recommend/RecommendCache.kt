// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 推荐页那一列卡片的落盘快照：**先画出来，再刷新**。
 *
 * ## 解决什么
 *
 * 冷启动进推荐页的链路是「推荐流（签名 + 服务端约 1s）→ 详情 → 取地址 → 拉首块」，
 * 首屏实测 `Recommend first screen 2092ms`、首帧 6.4s。其中第一段（取列表）
 * 只是「决定放哪一部」——而用户上一次看的正是同一批里的一部。
 *
 * 于是：把上一次真正上过屏的那一列存下来，再单独记一行「**上一次停在哪儿**」
 * （[rememberPlayed]），下次进程刚起时**直接从它的下一部接着播**：
 * 那一部正是上一轮在播时预热过的（详情 / 播放地址 / 索引 / 首块全都已在盘上，
 * 见 `DetailCache` 与 `PlaybackCache`），不经过网络，也不重新取流。
 *
 * **不是从第 0 条开始**：这一列的第 0 条是「本轮最早看过的那一部」，
 * 按它起播的结果就是「每次打开应用都是同一部剧」。游标 + 1 才是「接着往下看」。
 *
 * 流仍然在后台取，回来之后只往后接新条目（不整页替换，否则会把正在播那一部的下标挪走）。
 *
 * ## 边界
 *
 * * 只存卡片本身（`Series.toJson`），不存详情 / 播放地址——那些各有各的缓存与失效规则；
 * * 过期 [TTL_MS] 就不用了：推荐流每天在变，拿三天前的列表起播等于「推的老是那几部」；
 * * 上限 [MAX_ITEMS] 条，一条约 300~600 字节，整份 <40KB；
 * * 写盘在调用线程（主线程的那一次回调里）做会卡：因此 [save] 只把快照交给后台线程，
 *   真正写盘与上层 `recommendItems` 无关。
 */
object RecommendCache {
    /** 这份快照最多当多久的「首屏」。 */
    private const val TTL_MS=AppConfig.Recommend.CACHE_TTL_MS
    private const val MAX_ITEMS=AppConfig.Recommend.CACHE_MAX_ITEMS

    @Volatile private var file: File?=null
    /** 「上一次停在哪儿」：一行的剧 id（几字节），与 [file] **分开写**。 */
    @Volatile private var position: File?=null
    private val writer by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable,"hgtv-feed-cache").apply { isDaemon=true }
        }
    }

    fun init(target: File) {
        file=target
        position=File(target.parentFile,target.name.substringBeforeLast('.')+".pos")
    }

    /**
     * 按 [namespace] 取一份快照文件。
     *
     * 电视只有一条推荐流，用默认那一份；手机是「漫剧 / 真人 / AI剧」三条并列的流
     * （见 `PhoneFeedView` 的 `setGenre`），共用一份会互相覆盖——翻到另一类时铺出来的
     * 是上一类的卡片。因此这里给一个命名空间：`feed-v1-comic.json` 这种。
     * 空串就是电视那一份，行为与从前逐字节一致。
     */
    private fun fileOf(namespace: String): File? {
        val base=file ?: return null
        if(namespace.isEmpty()) return base
        val name=base.name
        val ext=name.substringAfterLast('.',"")
        val stem=name.substringBeforeLast('.')
        return File(base.parentFile,if(ext.isEmpty()) "$stem-$namespace" else "$stem-$namespace.$ext")
    }

    private fun positionOf(namespace: String): File? {
        val base=position ?: return null
        if(namespace.isEmpty()) return base
        return File(base.parentFile,base.name+"."+namespace)
    }

    /** 最近的快照；没有 / 过期 / 读坏了都返回空列表（调用方按「没有缓存」处理）。 */
    fun load(namespace: String = ""): List<Series> {
        val target=fileOf(namespace) ?: return emptyList()
        if(!target.isFile) return emptyList()
        if(System.currentTimeMillis()-target.lastModified()>TTL_MS) return emptyList()
        val began=System.nanoTime()
        val items=runCatching {
            val root=JSONObject(target.readText())
            val array=root.optJSONArray("items") ?: return@runCatching emptyList<Series>()
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { card -> runCatching { Series.fromJson(card) }.getOrNull() }
            }.filter { it.id.isNotEmpty() && it.title.isNotEmpty() }
        }.getOrDefault(emptyList())
        if(NetLog.timing) NetLog.log("feed cache ${if(items.isEmpty()) "miss" else "hit items=${items.size}"} took=${(System.nanoTime()-began)/1_000_000}ms")
        return items
    }

    /** 存下这一次上屏的列表（截前 [MAX_ITEMS] 条）。 */
    fun save(items: List<Series>, namespace: String = "") {
        val target=fileOf(namespace) ?: return
        if(items.isEmpty()) return
        val array=JSONArray()
        items.take(MAX_ITEMS).forEach { array.put(it.toJson()) }
        val text=JSONObject().put("at",System.currentTimeMillis()).put("items",array).toString()
        runCatching { writer.execute { runCatching { target.writeText(text) } } }
    }

    /**
     * 记下「刚刚真的在放这一部」（冷启动从它的**下一部**接着播）。
     *
     * 只写一行剧 id，几字节、原子替换。它与整份快照分开写的原因：快照 40KB，
     * 而这部机器上一秒能划两三部——每划一下就重写 40KB 既浪费 flash 又会跟
     * 正在读卡的播放线程抢 IO。
     */
    fun rememberPlayed(seriesId: String, namespace: String = "") {
        val target=positionOf(namespace) ?: return
        if(seriesId.isEmpty()) return
        val text=seriesId.take(30).filter { it.isDigit() }
        if(text.isEmpty()) return
        runCatching {
            writer.execute {
                runCatching { writePosition(target,text) }
            }
        }
    }

    /**
     * 把旧版本写在默认命名空间的位置迁移到新的流命名空间。
     *
     * 早期 TV 快照使用默认文件，后来快照切到 `official-v2`；如果位置文件不一起迁移，
     * 升级后的第一次冷启动会找不到上一次播放项。迁移只在目标位置不存在时执行，
     * 只复制一行剧 id，不会覆盖新版本已经写下的位置。
     */
    fun migratePosition(fromNamespace: String = "", toNamespace: String) {
        if(fromNamespace==toNamespace) return
        val source=positionOf(fromNamespace) ?: return
        val target=positionOf(toNamespace) ?: return
        if(target.isFile || !source.isFile) return
        val text=runCatching { source.readText(Charsets.UTF_8).trim() }.getOrDefault("")
        if(text.isEmpty()) return
        runCatching { writePosition(target,text) }
    }

    private fun writePosition(target: File,text: String) {
        val tmp=File(target.path+".tmp")
        tmp.writeText(text)
        target.delete()
        tmp.renameTo(target)
    }

    /** 上一次停在的那一部（没有 / 过期就是空串）。 */
    fun lastPlayed(namespace: String = ""): String {
        val target=positionOf(namespace) ?: return ""
        // 位置与快照是同一轮写的，快照过期了它也就不该再算数。
        val snapshot=fileOf(namespace) ?: return ""
        if(!target.isFile || !snapshot.isFile) return ""
        if(System.currentTimeMillis()-snapshot.lastModified()>TTL_MS) return ""
        return runCatching { target.readText(Charsets.UTF_8).trim() }.getOrDefault("")
    }

    fun clear(namespace: String = "") {
        runCatching { fileOf(namespace)?.delete() }
        runCatching { positionOf(namespace)?.delete() }
    }

    /**
     * 等已经排队的快照写入结束后再删一次。
     *
     * 切换过滤条件时可能刚好有一个旧快照正在后台落盘；只做同步 [clear] 的话，
     * 旧任务随后仍会把竖屏卡片写回来。这个清理任务排在写队列尾部，保证最终文件为空。
     */
    fun clearAfterPendingWrites(namespace: String = "") {
        val snapshot=fileOf(namespace) ?: return
        val position=positionOf(namespace) ?: return
        runCatching {
            writer.execute {
                runCatching {
                    snapshot.delete()
                    position.delete()
                }
            }
        }
    }
}
