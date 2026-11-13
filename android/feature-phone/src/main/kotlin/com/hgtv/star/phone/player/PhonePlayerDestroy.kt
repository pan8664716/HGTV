// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.view.WindowManager
import kotlinx.coroutines.cancel

internal fun PhonePlayerActivity.handlePlayerDestroy() {
    PhoneSession.playerForeground = false
    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    PhoneDebugReport.resetSamples()
    if (playerRootInitialized) root.removeCallbacks(relatedChromeHideRunnable)
    // 方向尺子跟着页面一起收：留着会在页面已经没了之后还去改方向。
    releasePortraitPin()
    disarmPictureWatchdog()
    cancelAutoRetry()
    clearPlaybackError()
    player?.let { runCatching { saveProgress(it.currentPosition, it.duration) } }
    // **交给推荐页**：不 release 播放器、不关 remote、不清媒体源。
    // 退回信息流时它会把同一个 PlayerView 重新绑上去接着播——
    // 早先这里 releasePlayer() 把媒体源和地址一起丢了，于是每次返回都重新取地址、
    // 重新 prepare、从头起播（用户看到的「每退一次就重新加载」就是这个）。
    detachForHandoffImpl()
    // 借过下层画面就把它自己的控制层还回去；不然退回推荐页会少一套标题与导航栏。
    // 推荐页已经在自己 onShown() 里要回去了的话，这一句是空操作（见 ensureFeedChrome）。
    if (playerFeedChromeHeld) {
        playerFeedChromeHeld = false
        PhoneSession.restoreFeedChrome()
    }
    // 借出去的那块 Surface 一直在出帧：**不要**在这里清标志。
    // 信息流的 onResume 早于本页 onDestroy，它接受手时就是靠这个标志
    // 判断「不用重挂」——清早了它会把同一台播放器摘下来再挂回去，白闪一帧。
    // 由信息流接手后自己清零（见 PhoneFeedView.adoptPlaying）。
    if (playerFeedUnderlay && !playerUnderlayBound) PhoneSession.underlayActive = false
    runtimeState.mediaSession?.release()
    runtimeState.mediaSession = null
    scope.cancel()
}
