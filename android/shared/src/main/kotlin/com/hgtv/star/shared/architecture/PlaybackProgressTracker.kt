// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.architecture

import com.hgtv.star.core.BackupProgress
import com.hgtv.star.core.AppConfig
import com.hgtv.star.core.Series

/**
 * 播放进度采样与落盘节拍。
 *
 * 页面只提供播放器当前位置、时长与是否播完；夹取、完播判定、写盘间隔都在这里。
 */
class PlaybackProgressTracker(
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var lastSavedAt=0L

    fun reset() {
        lastSavedAt=0L
    }

    fun due(): Boolean {
        val now=clock()
        return lastSavedAt==0L || now-lastSavedAt>=intervalMs
    }

    fun noteSaved() {
        lastSavedAt=clock()
    }

    fun snapshot(
        series: Series,
        episodeId: String,
        episodeIndex: Int,
        position: Long,
        duration: Long,
        completed: Boolean,
    ): BackupProgress =
        if(completed) {
            BackupProgress.completed(series,episodeId,episodeIndex,duration)
        } else {
            BackupProgress.of(series,episodeId,episodeIndex,position,duration)
        }

    companion object {
        const val DEFAULT_INTERVAL_MS=AppConfig.Playback.PROGRESS_SAVE_INTERVAL_MS
    }
}
