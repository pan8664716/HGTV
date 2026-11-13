// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.view.View
import android.widget.TextView
import com.hgtv.star.core.Detail
import com.hgtv.star.core.RelatedWorks
import com.hgtv.star.core.Series
import com.hgtv.star.core.SeriesSeasons
import com.hgtv.star.core.detail
import com.hgtv.star.core.related
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class PhoneNextSeasonState {
    var nextSeason: Series? = null
    var nextSeasonFor = ""
    var nextSeasonLoading = false
    var nextSeasonToken = 0
    var nextSeasonJob: Job? = null
    var autoNextSeasonPending = false
    var nextSeasonOpening = false
    var nextSeasonFinishedFor = ""
    lateinit var nextSeasonNotice: TextView
    val noticeReady: Boolean get()=::nextSeasonNotice.isInitialized
}

/**
 * 兜底换集：预热没成功、或列表里没挂上时，片尾仍然要能往下走。
 *
 * 与 TV 的 `STATE_ENDED → playEpisode` 同一条路，只是没有「无感」可言（会重新取地址）。
 */
internal fun PhonePlayerActivity.next() {
    val d = detail ?: return
    // 定时关闭（按集计）：片尾那一下先问计时器，到点就停在这里，不进下一集。
    // 与 TV 的 `sleepTimer.episodeEnded()` 同一条判据，且**必须在换集之前**问——
    // 换完再问就只能停在新一集的第一帧，用户读到的是「它已经跳过去了」。
    if (!sleepStopped && nextSeasonSleepTimer.episodesLeft > 0 && nextSeasonSleepTimer.episodeEnded()) {
        stopForSleepForNextSeason()
        return
    }
    if (index + 1 < d.episodes.size) play(index + 1) else advanceToNextSeasonOrFinish(d)
}

/**
 * 进入最后一集时提前查下一季，查到就在画面底部提示。
 */
internal fun PhonePlayerActivity.prepareNextSeasonIfNeeded(d: Detail) {
    val active = d.series.id == detail?.series?.id &&
        index == d.episodes.lastIndex &&
        library?.autoNext == true &&
        !sleepStopped
    if (!active) {
        clearNextSeasonLookup()
        return
    }
    ensureNextSeason(d.series)
}

internal fun PhonePlayerActivity.clearNextSeasonLookup() {
    nextSeasonState.nextSeasonToken++
    nextSeasonState.nextSeasonJob?.cancel()
    nextSeasonState.nextSeasonJob = null
    nextSeasonState.nextSeasonFor = ""
    nextSeasonState.nextSeason = null
    nextSeasonState.nextSeasonLoading = false
    nextSeasonState.autoNextSeasonPending = false
    nextSeasonState.nextSeasonOpening = false
    nextSeasonState.nextSeasonFinishedFor = ""
    hideNextSeasonNotice()
}

internal fun PhonePlayerActivity.ensureNextSeason(series: Series) {
    if (nextSeasonState.nextSeasonFor == series.id) {
        if (!nextSeasonState.nextSeasonLoading) showNextSeasonNotice()
        return
    }
    val token = ++nextSeasonState.nextSeasonToken
    nextSeasonState.nextSeasonJob?.cancel()
    nextSeasonState.nextSeasonFor = series.id
    nextSeasonState.nextSeason = null
    nextSeasonState.nextSeasonLoading = true
    nextSeasonState.nextSeasonOpening = false
    hideNextSeasonNotice()
    val cached = PhoneSession.relatedWorks(series.id)
    if (cached != null) {
        completeNextSeasonLookup(series, cached, token)
        return
    }
    val activity=this
    nextSeasonState.nextSeasonJob = scope.launch {
        val works = withContext(Dispatchers.IO) {
            runCatching {
                PhoneServices.repository(activity).related(series.id, series.title)
            }.getOrNull()
        }
        if (token != nextSeasonState.nextSeasonToken || isFinishing || isDestroyed) return@launch
        if (works != null) PhoneSession.rememberRelated(series.id, works)
        completeNextSeasonLookup(series, works, token)
    }
}

internal fun PhonePlayerActivity.completeNextSeasonLookup(
    series: Series,
    works: RelatedWorks?,
    token: Int,
) {
    if (token != nextSeasonState.nextSeasonToken || isFinishing || isDestroyed) return
    nextSeasonState.nextSeasonJob = null
    nextSeasonState.nextSeasonLoading = false
    nextSeasonState.nextSeason = works?.let { SeriesSeasons.nextSeason(series, it) }
    if (nextSeasonState.nextSeason != null) showNextSeasonNotice() else hideNextSeasonNotice()
    if (nextSeasonState.autoNextSeasonPending) {
        nextSeasonState.autoNextSeasonPending = false
        val target = nextSeasonState.nextSeason
        if (target != null) openNextSeason(target) else finishSeriesPlayback(series)
    }
}

internal fun PhonePlayerActivity.showNextSeasonNotice() {
    val d = detail ?: return
    val target = nextSeasonState.nextSeason ?: return
    val active = d.series.id == nextSeasonState.nextSeasonFor &&
        index == d.episodes.lastIndex &&
        library?.autoNext == true &&
        !sleepStopped
    if (!active) {
        hideNextSeasonNotice()
        return
    }
    nextSeasonState.nextSeasonNotice.text =
        "将自动播放 ${SeriesSeasons.titleWithSeason(target)}"
    nextSeasonState.nextSeasonNotice.visibility = View.VISIBLE
}

internal fun PhonePlayerActivity.hideNextSeasonNotice() {
    if (nextSeasonState.noticeReady) {
        nextSeasonState.nextSeasonNotice.visibility = View.GONE
    }
}

/** 最后一集播完：有下一季就播第 1 集，否则停在「全剧已播完」。 */
internal fun PhonePlayerActivity.advanceToNextSeasonOrFinish(d: Detail) {
    val series = d.series
    if (nextSeasonState.nextSeasonFinishedFor == series.id) return
    if (library?.autoNext != true) {
        finishSeriesPlayback(series)
        return
    }
    if (nextSeasonState.nextSeasonFor == series.id) {
        if (nextSeasonState.nextSeasonLoading) {
            nextSeasonState.autoNextSeasonPending = true
            return
        }
        val target = nextSeasonState.nextSeason
        if (target != null) openNextSeason(target) else finishSeriesPlayback(series)
        return
    }
    nextSeasonState.autoNextSeasonPending = true
    ensureNextSeason(series)
}

internal fun PhonePlayerActivity.openNextSeason(target: Series) {
    if (nextSeasonState.nextSeasonOpening) return
    nextSeasonState.nextSeasonOpening = true
    nextSeasonState.autoNextSeasonPending = false
    hideNextSeasonNotice()
    val token = ++nextSeasonState.nextSeasonToken
    nextSeasonState.nextSeasonJob?.cancel()
    val activity=this
    nextSeasonState.nextSeasonJob = scope.launch {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                PhoneServices.repository(activity).detail(target.id)
            }.getOrNull()
        }
        if (token != nextSeasonState.nextSeasonToken || isFinishing || isDestroyed) return@launch
        if (loaded == null || loaded.episodes.isEmpty()) {
            nextSeasonState.nextSeasonOpening = false
            finishSeriesPlayback(detail?.series ?: target)
            return@launch
        }
        nextSeasonState.nextSeasonOpening = false
        nextSeasonState.nextSeasonJob = null
        nextSeasonState.nextSeasonFor = ""
        nextSeasonState.nextSeason = null
        nextSeasonState.nextSeasonLoading = false
        nextSeasonState.nextSeasonFinishedFor = ""
        detail = loaded
        play(0)
    }
}

internal fun PhonePlayerActivity.finishSeriesPlayback(series: Series) {
    nextSeasonState.nextSeasonFinishedFor = series.id
    nextSeasonState.nextSeasonOpening = false
    nextSeasonState.autoNextSeasonPending = false
    hideNextSeasonNotice()
    player?.pause()
    hint.text = "全剧已播完"
    nextSeasonHintSticky = true
    hint.visibility = View.VISIBLE
    hint.bringToFront()
}
