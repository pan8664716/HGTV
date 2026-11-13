// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.view.View

/**
 * 应用「定时关闭」的选择。
 *
 * 与 TV 的 sleepLabels 逐项对应：
 * * 关闭 → cancel()，并清掉「已停」状态（用户可能刚刚被停过一次又继续看）；
 * * 15 / 30 / 60 / 90 分钟 → 按分钟计，由 startTicker 里的 poll() 负责停；
 * * 本集播完 / 再 3 集 / 再 5 集 → 按集计，片尾那一下由 episodeEnded() 拦住。
 *
 * 按集计时时**不把下一集挂进播放列表**（见 maybePrefetchNext / enqueueNextIfReady）：
 * 挂进去 ExoPlayer 会自己推进，界面来不及在片尾停住，用户看到的就是「说好一集，结果又播了」。
 */
internal fun PhonePlayerActivity.applySleepChoice(choice: Int) {
    playerSleepChoice = choice.coerceIn(0, com.hgtv.star.core.SleepTimer.LABELS.lastIndex)
    sleepStopped = false
    // 档位与分钟的对应关系只有一份（core/SleepTimer.applyChoice）：与电视端同源，
    // 用户在电视上选的「再 3 集」到手机上仍是同一档。
    val armed = com.hgtv.star.core.SleepTimer.applyChoice(
        nextSeasonSleepTimer,
        playerSleepChoice,
    )
    if (armed) {
        hint.text = "定时已设置 · ${nextSeasonSleepTimer.label()}"
        hint.visibility = View.VISIBLE
    }
    if (!armed) hint.visibility = View.GONE
    syncChips()
}

/** 定时到点：暂停、落盘进度、给一句提示。与 TV 的 stopForSleep 同一套动作。 */
internal fun PhonePlayerActivity.stopForSleep() {
    sleepStopped = true
    playerSleepChoice = 0
    nextSeasonSleepTimer.cancel()
    player?.pause()
    player?.let { runCatching { saveProgress(it.currentPosition, it.duration) } }
    hint.text = "定时停止已生效 · 观看位置已保存"
    hint.visibility = View.VISIBLE
    syncPlayGlyphForSleep()
    syncChips()
    syncMediaSession()
    syncKeepScreenOnForSleep()
}
