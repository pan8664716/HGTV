// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.hgtv.star.shared.ui.UiText
import android.view.View
import android.widget.TextView
import com.hgtv.star.core.Detail
import com.hgtv.star.core.Series
import com.hgtv.star.core.SeriesSeasons
import com.hgtv.star.core.detail
import com.hgtv.star.core.seriesSeasons
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
    var nextSeasonDetail: Detail? = null
    var nextSeasonDetailFor = ""
    var nextSeasonDetailLoading = false
    var nextSeasonDetailToken = 0
    var nextSeasonDetailJob: Job? = null
    var nextSeasonPoolLoading = false
    var nextSeasonPoolFor = ""
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
    nextSeasonState.nextSeasonDetailToken++
    nextSeasonState.nextSeasonDetailJob?.cancel()
    nextSeasonState.nextSeasonDetailJob = null
    nextSeasonState.nextSeasonDetailFor = ""
    nextSeasonState.nextSeasonDetail = null
    nextSeasonState.nextSeasonDetailLoading = false
    nextSeasonState.nextSeasonPoolLoading = false
    nextSeasonState.nextSeasonPoolFor = ""
    nextSeasonState.autoNextSeasonPending = false
    nextSeasonState.nextSeasonOpening = false
    nextSeasonState.nextSeasonFinishedFor = ""
    hideNextSeasonNotice()
    releaseNextSeasonPoolSlot()
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
    val activity=this
    nextSeasonState.nextSeasonJob = scope.launch {
        val seasons = withContext(Dispatchers.IO) {
            runCatching {
                PhoneServices.repository(activity).seriesSeasons(series.id)
            }.getOrNull()
        }
        if (token != nextSeasonState.nextSeasonToken || isFinishing || isDestroyed) return@launch
        completeNextSeasonLookup(series, seasons, token)
    }
}

internal fun PhonePlayerActivity.completeNextSeasonLookup(
    series: Series,
    seasons: List<Series>?,
    token: Int,
) {
    if (token != nextSeasonState.nextSeasonToken || isFinishing || isDestroyed) return
    nextSeasonState.nextSeasonJob = null
    nextSeasonState.nextSeasonLoading = false
    nextSeasonState.nextSeason = seasons?.let { SeriesSeasons.nextSeason(series, it) }
    val target = nextSeasonState.nextSeason
    if (target != null) {
        showNextSeasonNotice()
        ensureNextSeasonDetail(target)
    } else {
        hideNextSeasonNotice()
    }
    if (nextSeasonState.autoNextSeasonPending) {
        if (target == null) {
            nextSeasonState.autoNextSeasonPending = false
            finishSeriesPlayback(series)
        }
    }
}

private fun PhonePlayerActivity.ensureNextSeasonDetail(target: Series) {
    if (nextSeasonState.nextSeasonDetailFor == target.id) return
    nextSeasonState.nextSeasonDetailToken++
    nextSeasonState.nextSeasonDetailJob?.cancel()
    val token = nextSeasonState.nextSeasonDetailToken
    nextSeasonState.nextSeasonDetailFor = target.id
    nextSeasonState.nextSeasonDetail = null
    nextSeasonState.nextSeasonDetailLoading = true
    nextSeasonState.nextSeasonPoolLoading = false
    nextSeasonState.nextSeasonPoolFor = ""
    val activity = this
    nextSeasonState.nextSeasonDetailJob = scope.launch {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                PhoneServices.repository(activity).detail(target.id)
            }.getOrNull()
        }
        if (token != nextSeasonState.nextSeasonDetailToken || isFinishing || isDestroyed) return@launch
        nextSeasonState.nextSeasonDetailLoading = false
        nextSeasonState.nextSeasonDetailJob = null
        val detail = loaded?.takeIf { it.episodes.isNotEmpty() }
        if (detail == null) {
            if (nextSeasonState.autoNextSeasonPending) {
                nextSeasonState.autoNextSeasonPending = false
                openNextSeason(target)
            }
            return@launch
        }
        nextSeasonState.nextSeasonDetail = detail
        ensureNextSeasonEpisode(detail)
        if (nextSeasonState.autoNextSeasonPending &&
            !activateNextSeasonPool(detail)
        ) {
            // 池仍在准备时继续等；池准备完成后会在 `ensureNextSeasonEpisode` 里激活。
            if (!nextSeasonState.nextSeasonPoolLoading) {
                nextSeasonState.autoNextSeasonPending = false
                playNextSeasonDirect(detail)
            }
        }
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

/** 最后一集播完：有下一季就播第 1 集，否则停在「全剧已播完」并展示相关剧集。 */
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
    val cached = nextSeasonState.nextSeasonDetail?.takeIf {
        nextSeasonState.nextSeasonDetailFor == target.id && it.episodes.isNotEmpty()
    }
    if (cached != null) {
        if (activateNextSeasonPool(cached)) return
        if (nextSeasonState.nextSeasonPoolLoading &&
            nextSeasonState.nextSeasonPoolFor == target.id
        ) {
            nextSeasonState.autoNextSeasonPending = true
            return
        }
        playNextSeasonDirect(cached)
        return
    }
    if (nextSeasonState.nextSeasonDetailLoading &&
        nextSeasonState.nextSeasonDetailFor == target.id
    ) {
        nextSeasonState.autoNextSeasonPending = true
        return
    }
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
        nextSeasonState.nextSeasonDetail = loaded
        nextSeasonState.nextSeasonDetailFor = loaded.series.id
        if (!activateNextSeasonPool(loaded)) playNextSeasonDirect(loaded)
    }
}

private fun PhonePlayerActivity.playNextSeasonDirect(target: Detail) {
    releaseNextSeasonPoolSlot()
    nextSeasonState.nextSeasonOpening = false
    nextSeasonState.autoNextSeasonPending = false
    nextSeasonState.nextSeasonDetailToken++
    nextSeasonState.nextSeasonDetailJob?.cancel()
    nextSeasonState.nextSeasonDetailJob = null
    nextSeasonState.nextSeasonDetail = null
    nextSeasonState.nextSeasonDetailFor = ""
    nextSeasonState.nextSeasonDetailLoading = false
    nextSeasonState.nextSeasonPoolLoading = false
    nextSeasonState.nextSeasonPoolFor = ""
    nextSeasonState.nextSeasonFor = ""
    nextSeasonState.nextSeason = null
    nextSeasonState.nextSeasonLoading = false
    nextSeasonState.nextSeasonFinishedFor = ""
    detail = target
    play(0)
}

internal fun PhonePlayerActivity.activateNextSeasonPool(target: Detail): Boolean {
    val current = detail ?: return false
    if (index != current.episodes.lastIndex) return false
    if (nextSeasonState.nextSeasonDetailFor != target.series.id) return false
    val key = current.episodes.size
    val slot = playerAdjacentState.slots[key] ?: return false
    if (slot.detail?.series?.id != target.series.id || slot.episodeIndex != 0) return false
    if (slot.preparedAt <= 0L) {
        releaseAdjacentSlot(key)
        return false
    }
    val oldPlayer = player ?: return false
    val oldRemote = remote
    val previousPosition = runCatching { oldPlayer.currentPosition }.getOrDefault(0L)
    val previousDuration = listenerLastDuration.coerceAtLeast(
        runCatching { oldPlayer.duration }.getOrDefault(0L)
    )

    playerAdjacentState.slots.remove(key)
    playerListener?.let(oldPlayer::removeListener)
    playerListener = null
    runCatching {
        oldPlayer.volume = 0f
        oldPlayer.playWhenReady = false
        oldPlayer.pause()
    }
    PhoneServices.enableAudioFocus(oldPlayer, false)
    playerAdjacentState.slots.keys.toList().forEach(::releaseAdjacentSlot)
    if (PhoneServices.peekPlayer() === oldPlayer) PhoneServices.deactivate(oldPlayer)
    runCatching { oldPlayer.release() }

    slot.listener?.let(slot.player::removeListener)
    slot.listener = null
    slot.preRendering = false
    slot.previewPlaying = false
    slot.preRenderToken++
    player = slot.player
    remote = slot.remote
    queuedVideo = slot.remote
    PhoneServices.activate(slot.player)
    slot.player.setPlaybackSpeed(sessionUserSpeed)
    slot.player.volume = 1f
    PhoneServices.enableAudioFocus(slot.player, true)
    slot.player.playWhenReady = true

    detail = target
    if (playerEpisodePagerInitialized) playerEpisodePagerAdapter.notifyDataSetChanged()
    if (playerLandscapePagerInitialized) playerLandscapePagerAdapter.notifyDataSetChanged()
    selectPlayerPagerEpisode(0)
    attachCurrentPlayerOutput()
    finishAdvanced(
        nextData = target,
        nextIndex = 0,
        previousData = current,
        previousPosition = previousPosition,
        previousDuration = previousDuration,
        completedPrevious = true,
        clearDanmakuOnSwitch = true,
        closePreviousRemote = true,
    )
    attachPlayerListener(slot.player)
    root.post {
        if (player === slot.player && index == 0) attachCurrentPlayerOutput()
    }
    nextSeasonState.nextSeasonDetail = null
    nextSeasonState.nextSeasonDetailFor = ""
    nextSeasonState.nextSeasonPoolLoading = false
    nextSeasonState.nextSeasonPoolFor = ""
    nextSeasonState.nextSeasonFor = ""
    nextSeasonState.nextSeason = null
    nextSeasonState.nextSeasonLoading = false
    nextSeasonState.nextSeasonOpening = false
    nextSeasonState.autoNextSeasonPending = false
    nextSeasonState.nextSeasonFinishedFor = ""
    hideNextSeasonNotice()
    android.util.Log.i(
        "HGTV-PHONE",
        "next season activated from pool series=${target.series.id}",
    )
    return true
}

internal fun PhonePlayerActivity.finishSeriesPlayback(series: Series) {
    nextSeasonState.nextSeasonFinishedFor = series.id
    nextSeasonState.nextSeasonOpening = false
    nextSeasonState.autoNextSeasonPending = false
    hideNextSeasonNotice()
    player?.pause()
    hint.text = UiText.get(com.hgtv.star.shared.R.string.ui_0028)
    nextSeasonHintSticky = true
    hint.visibility = View.VISIBLE
    hint.bringToFront()
    val finished = detail?.takeIf { it.series.id == series.id } ?: return
    if (rootReady && !isFinishing && !isDestroyed) {
        showRelated(finished, UiText.get(com.hgtv.star.shared.R.string.ui_0191), UiText.get(com.hgtv.star.shared.R.string.ui_0109))
    }
}
