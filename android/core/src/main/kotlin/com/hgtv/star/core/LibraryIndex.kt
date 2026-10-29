// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.json.JSONArray
import org.json.JSONObject

/** Main-thread-owned library. JSON is parsed once, and getters never parse or sort. */
class LibraryIndex(data: BackupData, updates: Map<String, FavoriteUpdate> = emptyMap()) {
    private var favoriteRows = data.favorites.distinctBy { it.id }.take(500)
    private var favoriteIds = favoriteRows.map { it.id }.toSet()
    private var laterRows = data.later.distinctBy { it.id }.take(100)
    private var laterIds = laterRows.map { it.id }.toSet()
    private var historyRows = data.history.sortedByDescending { it.updatedAt }.distinctBy { it.series.id }.take(200)
    private val progressById = historyRows.associateBy { it.series.id }.toMutableMap()
    private var watchedIds = data.watched.toSet()
    private var hiddenIds = data.hidden.take(500).toSet()
    private var searchRows = data.searches.take(20)
    /**
     * 用户在本机删掉过的观看记录（剧集 ID）。
     *
     * 为什么要单独记一份：历史那一栏的内容是**本机进度 + 账号侧历史**合并出来的
     * （见 `localItems` / `accountItems`），而账号侧那半份是从平台拉回来的。
     * 平台对「删除观看记录」只认 `read_history/update`（阅读历史那条，小说用的），
     * 短剧列表走的是 `read_progress/list`——软删不一定真的把它从进度列表里摘掉。
     * 于是删完那一刻界面上没了，**下次刷新（5 分钟后 / 按刷新 / 重启）它又回来了**，
     * 用户读到的就是「删了没删掉」。
     *
     * 因此本机自己记一份墓碑：删过的 id 在合并时一律过滤掉，不论平台那边还回不回来。
     * 只有**用户重新看这部剧**（写入新进度）时才摘掉墓碑——那时它本来就该重新出现。
     */
    private var deletedHistoryIds: Set<String> = data.historyDeleted.take(500).toSet()
    private var updateRows = updates.filterKeys { it in favoriteIds }
    private var updateCount = updateRows.values.count { it.added > 0 }
    /**
     * 本机记下的预约状态（剧集 ID → 是否已预约）。
     *
     * 平台只在详情接口给 `online_subscribed`，而那个请求要联网；用户点了「预约」之后
     * 按钮必须立刻翻成「已预约」、下次进来也还是这个状态。这里存的就是那一份「最后已知」，
     * 详情页拿到接口值时以接口为准并写回（见 `reserveSeries`）。
     */
    private var reserveRows: MutableMap<String,Boolean> = mutableMapOf()
    var settings = data.settings

    fun favorites() = favoriteRows
    fun favorite(id: String) = id in favoriteIds
    fun later() = laterRows
    fun queued(id: String) = id in laterIds
    fun history() = historyRows
    fun historyDeleted(): Set<String> = deletedHistoryIds
    fun historyDeleted(id: String) = id in deletedHistoryIds
    fun setHistoryDeleted(ids: Set<String>) { deletedHistoryIds = ids.take(500).toSet() }
    fun progress(id: String) = progressById[id]
    fun watched(id: String) = id in watchedIds
    fun watched() = watchedIds
    fun hidden() = hiddenIds
    fun searches() = searchRows
    fun updates() = updateRows
    fun favoriteUpdate(id: String) = updateRows[id]
    fun reserves(): Map<String,Boolean> = reserveRows
    fun reserved(id: String) = reserveRows[id] == true
    fun reservedKnown(id: String) = reserveRows.containsKey(id)
    fun setReserved(id: String, value: Boolean) { reserveRows[id] = value }
    fun updatedFavorites() = updateCount

    fun toggle(series: Series): Boolean {
        val added = !favorite(series.id)
        favoriteRows = (if (added) listOf(series) + favoriteRows else favoriteRows.filterNot { it.id == series.id }).take(500)
        favoriteIds = favoriteRows.map { it.id }.toSet()
        updateRows = updateRows.filterKeys { it in favoriteIds }
        updateCount = updateRows.values.count { it.added > 0 }
        return added
    }
    fun toggleLater(series: Series): Boolean {
        val added = !queued(series.id)
        laterRows = (if (added) listOf(series) + laterRows else laterRows.filterNot { it.id == series.id }).take(100)
        laterIds = laterRows.map { it.id }.toSet()
        return added
    }
    /** A paused player's identical checkpoint does not refresh its timestamp or trigger a write. */
    fun save(progress: BackupProgress): Boolean {
        val previous = progressById[progress.series.id]
        if (previous != null && previous.copy(updatedAt = progress.updatedAt) == progress) return false
        historyRows = (listOf(progress) + historyRows.filterNot { it.series.id == progress.series.id }).take(200).sortedByDescending { it.updatedAt }
        progressById.clear()
        historyRows.forEach { progressById[it.series.id] = it }
        // 用户重新看了这部剧：墓碑摘掉，账号侧那一条也该重新出现。
        deletedHistoryIds = deletedHistoryIds - progress.series.id
        return true
    }
    fun setWatched(id: String, value: Boolean): Boolean {
        if (watched(id) == value) return false
        watchedIds = if (value) watchedIds + id else watchedIds - id
        return true
    }
    fun hide(id: String) { hiddenIds = (hiddenIds + id).toList().takeLast(500).toSet() }
    fun unhideAll() { hiddenIds = emptySet() }
    fun rememberSearch(query: String) { searchRows = SearchHistory.remember(searchRows, query) }
    fun clearSearches() { searchRows = emptyList() }
    /**
     * 清空全部观看记录，返回被清掉的剧集 ID（调用方拿它去同步删除云端）。
     *
     * 与逐条 [removeHistory] 的区别只是「一次删完」：同样留下墓碑，
     * 账号侧那半份下次刷新回来照样被过滤（见 [deletedHistoryIds]）。
     * 收藏、稍后观看、已隐藏的剧集都不动。
     */
    fun clearHistory(): List<String> {
        val removed=historyRows.map { it.series.id }
        if(removed.isEmpty()) return removed
        historyRows=emptyList()
        progressById.clear()
        watchedIds=watchedIds - removed.toSet()
        deletedHistoryIds=(deletedHistoryIds + removed).toList().takeLast(500).toSet()
        return removed
    }
    /**
     * 把一批剧集记为「本机已删除的观看记录」（只留墓碑，不动其它数据）。
     *
     * 清空历史时用：账号侧那半份里的条目本机没有进度，[clearHistory] 收不到它们的 id，
     * 得由调用方把「云端也删了的那一批」补记进来，否则下次刷新它们又会出现在列表里。
     */
    fun markHistoryDeleted(ids: Collection<String>) {
        if(ids.isEmpty()) return
        deletedHistoryIds=(deletedHistoryIds + ids).toList().takeLast(500).toSet()
    }
    fun removeHistory(id: String) {
        historyRows = historyRows.filterNot { it.series.id == id }
        progressById.remove(id)
        watchedIds = watchedIds - id
        // 同时留一块墓碑：账号侧那半份下次刷新回来时照样过滤掉（见 [deletedHistoryIds]）。
        deletedHistoryIds = (deletedHistoryIds + id).toList().takeLast(500).toSet()
    }
    fun observeFavorite(id: String, count: Int, now: Long, acknowledge: Boolean): Boolean {
        if (!favorite(id) || count <= 0) return false
        val previous = updateRows[id]
        val next = previous?.observe(count, now, acknowledge) ?: FavoriteUpdate.first(count, now)
        if (previous == next) return false
        updateRows = updateRows + (id to next)
        updateCount += (if (next.added > 0) 1 else 0) - (if ((previous?.added ?: 0) > 0) 1 else 0)
        return true
    }
    fun snapshot() = BackupData(favoriteRows, historyRows, watchedIds, searchRows, settings, laterRows, hiddenIds, deletedHistoryIds)
}

/** Keeps the native-library-v1 disk keys and rich local metadata compatible with older APKs. */
object LibraryDisk {
    fun decode(values: Map<String, *>): LibraryIndex {
        fun rows(key: String) = runCatching { JSONArray(values[key] as? String ?: "[]") }.getOrDefault(JSONArray())
        fun series(key: String): List<Series> {
            val data = rows(key)
            return (0 until data.length()).mapNotNull { runCatching { Series.fromJson(data.getJSONObject(it)) }.getOrNull() }
        }
        fun ids(key: String) = (values[key] as? Set<*>)?.filterIsInstance<String>()?.toSet().orEmpty()
        val history = rows("progress").let { rows ->
            (0 until rows.length()).mapNotNull { i -> runCatching {
                val o = rows.getJSONObject(i)
                BackupProgress(Series.fromJson(o.getJSONObject("series")), o.getString("episodeId"), o.getInt("episodeIndex"),
                    o.getLong("position"), o.getLong("duration"), o.optBoolean("completed"), o.getLong("updatedAt"))
            }.getOrNull() }
        }
        val rawUpdates = runCatching { JSONObject(values["favoriteUpdates"] as? String ?: "{}") }.getOrDefault(JSONObject())
        val updates = rawUpdates.keys().asSequence().mapNotNull { id -> FavoriteUpdate.decode(rawUpdates.optJSONObject(id))?.let { id to it } }.toMap()
        val settings = BackupSettings(PlaybackQuality.normalize((values["quality"] as? Number)?.toInt() ?: 1080),
            PlaybackSpeed.normalize((values["playbackSpeed"] as? Number)?.toFloat() ?: 1f), values["autoNext"] as? Boolean ?: true,
            ContentType.fromStored(values["contentType"] as? String), VideoFrameMode.fromStored(values["frameMode"] as? String),
            DanmakuMode.fromStored(values["danmakuMode"] as? String),
            DanmakuStyle.scale((values["danmakuScale"] as? Number)?.toFloat() ?: 1f),
            DanmakuStyle.alpha((values["danmakuAlpha"] as? Number)?.toFloat() ?: 1f),
            DanmakuSpeed.fromStored(values["danmakuSpeed"] as? String),
            // 按键绑定：磁盘上就是 [KeyMap.encode] 的产物（空串 = 全默认），坏值由 decode 逐条丢弃。
            KeyMap.decode(values["keymap"] as? String).encode(),
            VideoDecoderMode.fromStored(values["decoderMode"] as? String),
            // 相同弹幕聚合：旧版本磁盘没有这个键，按开启处理（与之前的行为一致）。
            values["danmakuMerge"] as? Boolean ?: true,
            // 搜索源：旧版本磁盘没有这个键，按 App 处理（与之前的行为一致）。
            SearchSource.fromStored(values["searchSource"] as? String),
            // 首页每行卡片数：旧版本磁盘没有这个键，按 6 处理（与之前的行为一致）。
            (values["homeColumns"] as? Number)?.toInt()?.coerceIn(4,8) ?: 6,
            // 导航栏圆形入口：逐个开关（值是隐藏的那些 key）；旧版本磁盘没有这个键 = 全部显示。
            (values["hiddenNav"] as? Set<*>)?.filterIsInstance<String>()?.joinToString(",") ?: "",
            // 调试模式：旧版本磁盘没有这个键，按关闭处理。
            values["debugMode"] as? Boolean ?: false,
            // 启动进入推荐：旧版本磁盘没有这个键，按开启处理（与需求 1 一致）。
            values["launchRecommend"] as? Boolean ?: true,
            // 首页本机记录三栏：与 [hiddenNav] 一样存成字符串集合；旧版本磁盘没有这个键 = 三栏全显示。
            (values["hiddenHome"] as? Set<*>)?.filterIsInstance<String>()?.filter { it in HOME_KEYS }?.joinToString(",") ?: "",
            // 「减少动画」：旧版本磁盘没有这个键，按关闭处理（与之前的行为一致）。
            values["reduceAnim"] as? Boolean ?: false,
            // 播放显示四项：旧版本磁盘没有这些键，按默认值处理（**四项全关**：
            // 常驻画面只在唤出菜单 / 轻点时才显示，见 `syncPlaybackInfo`）。
            values["playerTitle"] as? Boolean ?: false,
            values["playerEpisode"] as? Boolean ?: false,
            values["playerClock"] as? Boolean ?: false,
            values["playerSpeed"] as? Boolean ?: false,
            // 画面缩放：旧版本磁盘没有这个键，按 100%（铺满）处理。
            ((values["videoScale"] as? Number)?.toFloat() ?: 100f).coerceIn(1f,100f),
            // 竖屏短剧全屏：旧版本磁盘没有这个键，按关闭处理（与之前的行为一致）。
            values["autoFullscreen"] as? Boolean ?: false,
            // 选集一页集数：旧版本磁盘没有这个键，按 20 处理（与之前的行为一致）；
            // 只认 10 / 20 / 30 / 40 / 50，坏值一律回到 20。
            (values["episodePageSize"] as? Number)?.toInt()?.takeIf { it in 10..50 }?.let { it / 10 * 10 } ?: 20,
            // 分页边缘翻页：旧版本磁盘没有这个键，按开启处理（与之前的行为一致）。
            values["episodeEdgeTurn"] as? Boolean ?: true,
            // 「返回顶部」浮层显隐：旧版本磁盘没有这个键、或值不在四档里，按「始终显示」处理。
            (values["backToTop"] as? String)?.takeIf { it in BACK_TO_TOP_MODES } ?: BACK_TO_TOP_DEFAULT,
            // 聚合条数阈值：旧版本磁盘没有这个键，按 2 处理（与之前的行为一致）；坏值夹进 2..10。
            (values["danmakuMergeMin"] as? Number)?.toInt()?.coerceIn(DANMAKU_MERGE_MIN_RANGE) ?: 2,
            // 播放设置 / 选集面板自动消失秒数：旧版本磁盘没有这个键，按 6 秒处理；坏值夹进 2..15。
            (values["playerPanelTimeout"] as? Number)?.toInt()?.coerceIn(PLAYER_PANEL_TIMEOUT_RANGE)
                ?: PLAYER_PANEL_TIMEOUT_DEFAULT,
            // 快进 / 快退单次跳秒：旧版本磁盘没有这个键，按 10 秒处理；坏值夹进 2..20。
            (values["seekStep"] as? Number)?.toInt()?.coerceIn(2,20) ?: 10)
        val reserves = runCatching { JSONObject(values["reserves"] as? String ?: "{}") }.getOrDefault(JSONObject())
        val index = LibraryIndex(BackupData(series("favorites"), history, ids("watched"),
            SearchHistory.decode(values["searches"] as? String ?: "[]"), settings, series("later"), ids("hidden"),
            ids("historyDeleted")), updates)
        reserves.keys().asSequence().forEach { id -> index.setReserved(id, reserves.optBoolean(id, false)) }
        return index
    }
    fun series(rows: List<Series>) = JSONArray(rows.map { it.toJson() }).toString()
    fun reserves(rows: Map<String,Boolean>) = JSONObject().also { out -> rows.forEach { (id,v) -> out.put(id,v) } }.toString()
    fun history(rows: List<BackupProgress>) = JSONArray(rows.map { p ->
        JSONObject().put("series", p.series.toJson()).put("episodeId", p.episodeId).put("episodeIndex", p.episodeIndex)
            .put("position", p.position).put("duration", p.duration).put("completed", p.completed).put("updatedAt", p.updatedAt)
    }).toString()
    fun updates(rows: Map<String, FavoriteUpdate>) = JSONObject().also { result -> rows.forEach { (id, update) -> result.put(id, update.json()) } }.toString()
}
