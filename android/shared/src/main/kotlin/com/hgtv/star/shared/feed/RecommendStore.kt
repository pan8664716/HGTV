// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.feed

import com.hgtv.star.core.RecommendDataSource
import com.hgtv.star.core.CacheLevel
import com.hgtv.star.core.FeedCursor
import com.hgtv.star.core.OptionalFilter
import com.hgtv.star.core.RecommendBehavior
import com.hgtv.star.core.RecommendCache
import com.hgtv.star.core.RecommendCellCursor
import com.hgtv.star.core.RecommendTaste
import com.hgtv.star.core.RecommendTab
import com.hgtv.star.core.Series
import com.hgtv.star.shared.data.Library

/**
 * 推荐流的页面无关状态。
 *
 * 页面只保留当前可见下标与列表控件；列表、游标、过滤降级、加载结果、兴趣画像和
 * 曝光 / 划走反馈都在这里。TV 与 Phone 的差异只通过 [RecommendFeedPolicy] 和
 * 体裁参数传入，不在 Store 内判断平台。
 */
class RecommendStore(
    private val repository: RecommendDataSource,
    private val library: Library,
    private val policy: RecommendFeedPolicy,
    private val genderFilter: OptionalFilter = OptionalFilter("recommendGender"),
    private val debug: (String) -> Unit = {},
) {
    private val engine=RecommendFeedEngine(
        repository=repository,
        library=library,
        genderFilter=genderFilter,
        policy=policy,
        debug=debug,
    )
    private val warmLock=Any()
    private val warming=mutableSetOf<String>()
    private val warmed=mutableMapOf<String,CacheLevel>()
    private val reportedImpressions=mutableSetOf<String>()

    var items: MutableList<Series> = mutableListOf()
    var sessionId: String = ""
    var nextOffset: Int = 0
    var cell: RecommendCellCursor = RecommendCellCursor()
    var page: Int = 0
    var hasMore: Boolean = true
    var filterMiss: Int = 0
    var filterIdsOff: Boolean = false
    var genderOff: Boolean = false
    var genderProbe: Boolean = false
    var restored: Boolean = false
    var loading: Boolean = false
    var tasteCache: RecommendTaste? = null

    val gender: OptionalFilter get() = genderFilter

    /** 从落盘游标恢复续流位置；两端都按推荐流命名空间隔离。 */
    fun restoreCursor() {
        val horizontalOnly=policy.supportsHorizontalOnly && library.recommendHorizontalOnly
        if(policy.namespacedCursor) {
            val saved=runCatching {
                FeedCursor.of(library.phoneFeedCursor,policy.cursorNamespace(horizontalOnly))
            }.getOrNull()
            sessionId=saved?.session.orEmpty()
            nextOffset=saved?.offset ?: 0
        } else {
            val cursor=library.recommendCursor
            sessionId=cursor.substringBefore('|')
            nextOffset=cursor.substringAfter('|',"0").toIntOrNull()?.coerceAtLeast(0) ?: 0
        }
    }

    /** 推荐请求条件变化时丢掉当前续流位置。 */
    fun resetCursor() {
        val horizontalOnly=policy.supportsHorizontalOnly && library.recommendHorizontalOnly
        sessionId=""
        nextOffset=0
        cell=RecommendCellCursor()
        page=0
        hasMore=true
        if(policy.namespacedCursor) {
            library.phoneFeedCursor=FeedCursor.remove(
                library.phoneFeedCursor,
                policy.cursorNamespace(horizontalOnly),
            )
        } else {
            library.recommendCursor=""
        }
    }

    /** 恢复落盘快照；返回真正可用的条目，空表示没有可用快照。 */
    fun restoreSnapshot(hidden: Set<String> = emptySet()): List<Series> {
        val snapshot=runCatching { RecommendCache.load(policy.cacheNamespace) }
            .getOrDefault(emptyList())
            .filterNot { it.id in hidden }
        if(snapshot.isEmpty()) return emptyList()
        items=snapshot.toMutableList()
        restored=true
        hasMore=true
        return snapshot
    }

    /** 接管页面已经持有的列表（本进程返回推荐页 / 预热结果交接）。 */
    fun replaceItems(value: List<Series>, markRestored: Boolean = restored) {
        items=value.toMutableList()
        restored=markRestored
    }

    fun resetTaste() {
        tasteCache=null
    }

    fun taste(): RecommendTaste =
        tasteCache ?: runCatching {
            RecommendTaste.of(library.history(),library.favorites())
        }.getOrDefault(RecommendTaste()).also { tasteCache=it }

    /** 在 UI 线程拍一份请求输入，避免后台线程边读边被页面修改。 */
    fun input(
        append: Boolean,
        noFilter: Boolean = false,
        genres: List<String> = emptyList(),
        tab: RecommendTab = RecommendTab.RECOMMEND,
        minItemsPerLoad: Int = 0,
        ensureRegistered: () -> Unit = {},
    ): RecommendFeedInput = RecommendFeedInput(
        items=items.toList(),
        sessionId=sessionId,
        nextOffset=nextOffset,
        hasMore=hasMore,
        page=page,
        filterMiss=filterMiss,
        filterIdsOff=filterIdsOff,
        genderProbe=genderProbe,
        append=append,
        restored=restored,
        noFilter=noFilter,
        genres=genres,
        tab=tab,
        taste=taste(),
        minItemsPerLoad=minItemsPerLoad,
        ensureRegistered=ensureRegistered,
        cell=cell,
    )

    /** 在后台线程取数；结果由页面回到 UI 线程后调用 [apply]。 */
    suspend fun load(input: RecommendFeedInput): RecommendFeedResult =
        engine.load(input)

    fun apply(result: RecommendFeedResult) {
        filterMiss=result.filterMiss
        filterIdsOff=result.filterIdsOff
        genderOff=result.genderOff
        genderProbe=result.genderProbe
        sessionId=result.sessionId
        nextOffset=result.nextOffset
        cell=result.cell
        items=result.items.toMutableList()
        page=result.page
        hasMore=result.hasMore
        if(result.error==null) restored=false
    }

    fun reportImpression(index: Int) {
        val series=items.getOrNull(index) ?: return
        reportImpression(series)
    }

    fun reportImpression(series: Series) {
        val key="${series.recommendGroupId}|${series.recommendInfo}"
        if(key in reportedImpressions) return
        if(RecommendBehavior.impression(repository,series)) reportedImpressions+=key
    }

    fun reportEvent(
        series: Series,
        event: RecommendBehavior.Event,
        episodeId: String = "",
        episodeIndex: Int = -1,
        positionMs: Long = -1,
        durationMs: Long = -1,
        reason: String = "",
    ) {
        RecommendBehavior.record(
            repository=repository,
            series=series,
            event=event,
            episodeId=episodeId,
            episodeIndex=episodeIndex,
            positionMs=positionMs,
            durationMs=durationMs,
            reason=reason,
        )
    }

    fun reportEventById(
        seriesId: String,
        event: RecommendBehavior.Event,
        episodeId: String = "",
        episodeIndex: Int = -1,
        positionMs: Long = -1,
        durationMs: Long = -1,
        reason: String = "",
    ): Boolean {
        val series=items.firstOrNull { it.id==seriesId } ?: return false
        reportEvent(series,event,episodeId,episodeIndex,positionMs,durationMs,reason)
        return true
    }

    /** 这一部真正上屏：记进本机曝光窗口，供后续过滤与排序使用。 */
    fun markShown(series: Series) {
        library.markRecommended(listOf(series.id),listOf(series.title))
    }

    /** 明确进入正片：按「看了」记冷却，避免随后退出又被记成一次划走。 */
    fun markWatched(series: Series) {
        library.markRecommendWatched(series.id)
    }

    fun beginWarm(key: String): Boolean = synchronized(warmLock) {
        if(key.isEmpty() || key in warming) false else warming.add(key)
    }

    fun isWarming(key: String): Boolean = synchronized(warmLock) { key in warming }

    fun warmingCount(): Int = synchronized(warmLock) { warming.size }

    fun warmedLevel(key: String): CacheLevel? = synchronized(warmLock) { warmed[key] }

    fun finishWarm(key: String,level: CacheLevel?=null) {
        synchronized(warmLock) {
            warming.remove(key)
            if(level!=null) {
                val previous=warmed[key]
                if(previous==null || previous<level) warmed[key]=level
            }
        }
    }

    fun downgradeWarm(key: String,from: CacheLevel,to: CacheLevel) {
        synchronized(warmLock) {
            if(warmed[key]==from) warmed[key]=to
        }
    }

    fun cancelWarm(key: String) {
        synchronized(warmLock) { warming.remove(key) }
    }

    fun clearWarm() {
        synchronized(warmLock) {
            warming.clear()
            warmed.clear()
        }
    }

    /**
     * 结掉一次推荐停留，并写入本机曝光窗口。
     *
     * 返回判定结果给页面决定是否只做 UI 收尾；判定阈值和标记都在这里，
     * TV / Phone 不再各写一遍。
     */
    fun settleWatch(seriesId: String,dwellMs: Long,playedMs: Long): RecommendBehavior.Outcome {
        val outcome=RecommendBehavior.outcome(dwellMs,playedMs)
        val series=items.firstOrNull { it.id==seriesId }
        when(outcome) {
            RecommendBehavior.Outcome.QUICK_SWIPE -> {
                series?.let {
                    reportEvent(it,RecommendBehavior.Event.QUICK_SWIPE,positionMs=playedMs)
                }
            }
            RecommendBehavior.Outcome.WATCHED -> library.markRecommendWatched(seriesId)
            RecommendBehavior.Outcome.SKIPPED -> {
                library.markRecommendSkipped(seriesId)
                items.firstOrNull { it.id==seriesId }?.let { tasteCache?.skipped(it) }
            }
        }
        if(outcome==RecommendBehavior.Outcome.WATCHED) {
            series?.let {
                reportEvent(it,RecommendBehavior.Event.WATCHED,positionMs=playedMs)
            }
        } else if(outcome==RecommendBehavior.Outcome.SKIPPED) {
            series?.let {
                reportEvent(it,RecommendBehavior.Event.SKIPPED,positionMs=playedMs)
            }
        }
        return outcome
    }
}
