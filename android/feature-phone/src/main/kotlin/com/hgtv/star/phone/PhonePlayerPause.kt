// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

internal fun PhonePlayerActivity.handlePlayerPause() {
    PhoneSession.playerForeground = false
    playerForeground = false
    unwatchNetwork()
    syncKeepScreenOn()
    relatedChromeEpoch++
    root.removeCallbacks(relatedChromeHideRunnable)
    // 退到后台就不再每秒对弹幕时钟（回来时由 onResume 重新排）。
    root.removeCallbacks(playerDanmakuTick)
    // 退到前台之外：暂停与后台都不是卡死，看门狗一律撤掉（回来时由 onResume 重装）。
    disarmPictureWatchdog()
    // 退了前台就不再占着系统媒体面板：那时面板上的按键应该交给真正在前台的应用。
    runtimeState.mediaSession?.deactivate()
    val handoff = handingBackToFeedForLifecycle()
    // 退回推荐页时**不在这里**还控制层：这一刻推荐页还没接完（A.onResume 还没跑），
    // 它手上的集号与进度条都还是离开前那一份，先亮出来就是「退回去标题 / 集号 /
    // 进度条先跳一下再对上」。改由推荐页在自己 onShown() 的最后调
    // PhoneSession.ensureFeedChrome()——那一步数据已经对齐，两边同时在位也就不会露空。
    // 交接没走成（推荐页没接上）时由 onDestroy 兜底还回去。
    player?.let { exo ->
        // **先记状态再暂停**：退回推荐页时信息流会按这个值决定要不要续播。
        // 顺序反了（先 pause）记到的永远是 false，用户看到的就成了「退回去就不动了」。
        PhoneSession.resumeOnAdopt = exo.playWhenReady
        if (com.hgtv.star.shared.BuildConfig.DEBUG) {
            android.util.Log.i(
                "HGTV-PHONE",
                "player pause handoff=$handoff pos=${exo.currentPosition} " +
                    "state=${exo.playbackState} pwr=${exo.playWhenReady} " +
                    "items=${exo.mediaItemCount} cur=${exo.currentMediaItemIndex}"
            )
        }
        saveProgress(exo.currentPosition, exo.duration)
        // 退回推荐页时**不暂停**：这一路马上要在信息流里继续放。
        // 暂停会让退回的那一屏先停一拍再恢复（还要重新缓冲），读起来就是「内容断了」。
        if (!handoff) exo.pause()
    }
}
