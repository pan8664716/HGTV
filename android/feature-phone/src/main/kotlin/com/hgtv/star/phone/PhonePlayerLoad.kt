// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun PhonePlayerActivity.load(seriesId: String) {
    val activity = this
    scope.launch {
        val lib = PhoneServices.awaitLibrary(activity)
        library = lib
        if (playerChromeVisible) scheduleChromeHideForMode()
        // 倍速是**落盘设置**：用户刚在推荐页调到 2 倍速（那一侧写进了书库），进来接着用。
        chipUserSpeed = lib.playbackSpeed

        // **接手**：推荐页刚把这一集挂上进程级播放器（PhoneSession），
        // 这里就不重新取详情、不重新取地址、不重新 prepare ——
        // 那三步加起来是 1~2 秒的黑屏，正是用户说的「每次进播放页都重新加载」。
        if (adoptFromSession(seriesId)) return@launch

        // 会话里已经有**同一部剧**的详情（片尾自动进正片就是这条路：要放的是下一集，
        // 集号对不上 adoptFromSession 的条件，但详情与下一集的预热结果都在手上）。
        // 不再请求一次详情：那是这条链路上最贵的一步（一次 App 接口往返）。
        val cached = PhoneSession.detail?.takeIf {
            it.series.id == seriesId && PhoneSession.remote != null && it.episodes.isNotEmpty()
        }
        // 详情：内存会话 → 落盘缓存 → 接口（与电视端 DetailCache 同一条顺序）。
        // 落盘那一份正是推荐页预热时写的，因此「进正片」这一段常常一次请求都不发。
        val fromDisk = if (cached == null) withContext(Dispatchers.IO) {
            runCatching { com.hgtv.star.core.DetailCache.load(seriesId) }.getOrNull()
        } else null
        val d = cached ?: fromDisk ?: withContext(Dispatchers.IO) {
            runCatching {
                PhoneServices.repository(activity).detail(seriesId)
            }.getOrNull()
        }
        if (d == null) {
            playerLandInfoLeft.text = "详情加载失败"
            return@launch
        }
        if (cached == null && fromDisk == null) {
            runCatching { com.hgtv.star.core.DetailCache.save(d) }
        }
        detail = d
        if (d.episodes.isEmpty()) {
            playerLandInfoLeft.text = if (d.upcoming) "尚未上线" else "暂无剧集"
            return@launch
        }

        val saved = withContext(Dispatchers.IO) {
            runCatching { lib.progress(seriesId) }.getOrNull()
        }
        val resumeIndex = saved?.let { s ->
            d.episodes.indexOf(s.episodeId).takeIf { it >= 0 }
        } ?: 0
        val resumeAt = if (saved != null && resumeIndex == d.episodes.indexOf(saved.episodeId)) {
            saved.position
        } else {
            0L
        }
        val start = intent.getIntExtra(PhonePlayerActivity.EXTRA_EPISODE, -1)
            .takeIf { it in d.episodes.indices } ?: resumeIndex
        val startAt = if (start == resumeIndex) resumeAt else 0L
        playForLoad(start, startAt)
    }
}
