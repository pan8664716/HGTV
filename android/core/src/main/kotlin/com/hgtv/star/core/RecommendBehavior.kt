// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * 推荐流客户端共用的曝光判定与上报入口。
 *
 * 电视和手机都从这里提交官方 `FilterImpression`。停留判定仍可供页面做本地
 * 推荐排序使用，但不会把非曝光行为混入官方曝光接口。
 */
object RecommendBehavior {
    const val QUICK_SWIPE_MS = 1_200L
    const val WATCH_DWELL_MS = 6_000L
    const val WATCH_PLAY_MS = 5_000L

    enum class Outcome { QUICK_SWIPE, SKIPPED, WATCHED }

    /** 推荐流里的本地行为；只有 [IMPRESSION] 会进入官方曝光上报接口。 */
    enum class Event(val wireValue: String) {
        IMPRESSION("impression"),
        CLICK("click"),
        PLAY_START("play_start"),
        PLAY_PROGRESS("play_progress"),
        PLAY_COMPLETE("play_complete"),
        QUICK_SWIPE("quick_swipe"),
        SKIPPED("skipped"),
        WATCHED("watched"),
        FAVORITE("favorite"),
        UNFAVORITE("unfavorite"),
        RESERVE("reserve"),
        UNRESERVE("unreserve"),
        HIDE("hide"),
        ERROR("error"),
    }

    /** 把一次推荐停留折算成同一种本地反馈。 */
    fun outcome(dwellMs: Long, playedMs: Long): Outcome {
        if (dwellMs < QUICK_SWIPE_MS) return Outcome.QUICK_SWIPE
        return if (dwellMs >= WATCH_DWELL_MS || playedMs >= WATCH_PLAY_MS) {
            Outcome.WATCHED
        } else {
            Outcome.SKIPPED
        }
    }

    /** 记录一次真正进入播放状态的推荐曝光。 */
    fun impression(repository: RecommendReportSink, series: Series): Boolean {
        return record(repository,series,Event.IMPRESSION)
    }

    /** 记录一次官方曝光；其它本地行为不进入 `recommend_stat/report/v1/`。 */
    fun record(
        repository: RecommendReportSink,
        series: Series,
        event: Event,
        episodeId: String = "",
        episodeIndex: Int = -1,
        positionMs: Long = -1,
        durationMs: Long = -1,
        reason: String = "",
    ): Boolean {
        if(event!=Event.IMPRESSION) return false
        val recommendInfo=series.recommendInfo
        val groupId=series.recommendGroupId.ifBlank {
            runCatching { JSONObject(recommendInfo).optString("gid") }.getOrDefault("")
        }
        val contentType=series.contentType
        if(recommendInfo.isBlank() || groupId.isBlank() || contentType<=0) return false
        return RecommendBehaviorReporter.enqueue(
            repository,
            RecommendStatData(
                groupId=groupId,
                recommendInfo=recommendInfo,
                contentType=contentType,
                statItemType=statItemType(contentType),
            ),
        )
    }

    /** 官方 `RecommendStatItemType`：剧集 / PUGC / 专辑。 */
    private fun statItemType(contentType: Int): Int = when(contentType) {
        1001,1006 -> 2
        1003 -> 3
        else -> 1
    }

    internal fun onConfigChanged() {
        RecommendBehaviorReporter.onConfigChanged()
    }
}

/** 一条官方 `StatData`：只包含抓包中实际序列化的五个字段。 */
data class RecommendStatData(
    val groupId: String,
    val recommendInfo: String,
    val contentType: Int,
    val statItemType: Int,
    val clientTime: Long = System.currentTimeMillis(),
)

/**
 * 推荐曝光批量器。
 *
 * 与官方客户端一致：收到曝光后按服务端配置的窗口等待，时间到后批量发送；
 * 失败时保留队列并按同一窗口重试，不阻塞播放。
 */
private object RecommendBehaviorReporter {
    private val lock = Any()
    private val pending = ArrayDeque<RecommendStatData>()
    private var repository: RecommendReportSink? = null
    private var scheduled: ScheduledFuture<*>? = null
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "hgtv-recommend-report").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }

    fun enqueue(repository: RecommendReportSink, data: RecommendStatData): Boolean {
        if(!RecommendStatConfigStore.isEnabled()) return false
        synchronized(lock) {
            pending.addLast(data)
            this.repository = repository
            if (scheduled == null) scheduleLocked()
            return true
        }
    }

    private fun flush() {
        val target: RecommendReportSink
        val batch: List<RecommendStatData>
        synchronized(lock) {
            scheduled = null
            if(!RecommendStatConfigStore.isEnabled()) {
                pending.clear()
                repository = null
                return
            }
            target = repository ?: return
            if (pending.isEmpty()) return
            batch = ArrayList(pending)
            pending.clear()
            repository = null
        }
        val ok = runCatching { target.reportRecommendStats(batch) }.isSuccess
        if (ok) return
        synchronized(lock) {
            // 失败的事件放回队头；新一轮事件仍然排在它们后面。
            for (index in batch.indices.reversed()) pending.addFirst(batch[index])
            repository = target
            if (pending.isNotEmpty() && scheduled == null) scheduleLocked()
        }
    }

    private fun scheduleLocked() {
        scheduled=worker.schedule(
            { flush() },
            RecommendStatConfigStore.durationMs(),
            TimeUnit.MILLISECONDS,
        )
    }

    fun onConfigChanged() {
        synchronized(lock) {
            scheduled?.cancel(false)
            scheduled=null
            if(!RecommendStatConfigStore.isEnabled()) {
                pending.clear()
                repository=null
                return
            }
            if(pending.isNotEmpty()) scheduleLocked()
        }
    }
}
