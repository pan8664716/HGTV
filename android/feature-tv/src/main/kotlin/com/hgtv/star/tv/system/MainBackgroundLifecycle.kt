// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import kotlinx.coroutines.cancel

internal fun TvMainActivity.onPauseImpl(superOnPause: () -> Unit) {
    if(resetting) { superOnPause(); return }
    if(!mainInitialized) {
        foreground=false
        superOnPause()
        return
    }
    foreground=false; main.removeCallbacks(initializationTick); cancelRecovery(); cancelPrefetch(); mediaSession?.deactivate()
    // 退到后台：屏幕常亮跟着松开（见 `syncKeepScreenOn`）。
    syncKeepScreenOn()
    sleepTimer.cancel(); sleepChoice=0; favoriteMonitor.stop(); detailAutoplay=false; requestedAutoplay=false; pausedForLifecycle=true
    player?.pause(); saveProgress(); library.flush(); superOnPause()
}

internal fun TvMainActivity.onStopImpl(superOnStop: () -> Unit) {
    if(resetting) { superOnStop(); return }
    if(!mainInitialized) { superOnStop(); return }
    restoreStoppedPage=true; generation++; cancelWork(); tvTools.close()
    // 退到后台**不销毁播放器**（进程内复用，见 [detachPlayerSurface] 的说明）：
    // 画面层、传输、弹幕一起收，但 ExoPlayer 实例留着。回到前台由 onResume 的
    // restoreStoppedPage 接着播，不再走一次 `ExoPlayer.Builder.build()`。
    // `keepSession=true`：退到后台不是离开这一集，回来还要按同一位置接着播。
    // 保留本次会话的降级结论，否则又会拿刚被证明放不了的上限重撞一次
    // （见 [com.hgtv.star.core.SessionPlaybackPolicy.onLeavePlaybackToBackground]）。
    if(screen=="player") { startPosition=currentPosition(); saveProgress(); detachPlayerSurface(keepSession=true) }
    // 推荐页不写进度，只记下位置并卸掉画面；回到前台由 onResume 接着播。
    // 顺手结掉「这一部看了多久」：退到后台的那段时间不算进停留时长，
    // 否则放着不管一小时再回来，一部本来划走的剧会被记成「看了」。
    else if(screen=="recommend") {
        startPosition=currentPosition()
        detachPlayerSurface(keepSession=true)
        recommendPrepared.cancel()
        settleRecommendWatch()
    }
    artwork.cancelPage(clearMemory=true); library.flush(); superOnStop()
}

internal fun TvMainActivity.onDestroyImpl(superOnDestroy: () -> Unit) {
    if(!mainInitialized) {
        appScope.cancel()
        superOnDestroy()
        return
    }
    generation++; cancelWork()
    closeLanSyncSession()
    runCatching { unregisterReceiver(initializationSessionExpiredReceiver) }
    runCatching { initializationNetworkWatch?.cancel() }; initializationNetworkWatch=null
    recommendToken.incrementAndGet(); recommendPageToken.incrementAndGet(); closeRecommendCache()
    favoriteMonitor.destroy(); tvTools.destroy(); closeAccountPanelSession(); saveProgress(); releasePlayer(); library.flush()
    if(artwork.releaseHiddenCovers===initializationArtworkReleaseCallback) artwork.releaseHiddenCovers=null
    releaseCatalogViews(); mediaSession?.release(); mediaSession=null; main.removeCallbacksAndMessages(null)
    // 页面级协程作用域：只取消「属于这个 Activity」的协程，**不碰**线程池。
    //
    // 线程池是进程级的（见 `AppExecutors`），这里绝不能关：重建后的新 Activity 会
    // 继续往同一批线程上提交任务，关掉就是「提交到已关闭的池」——那正是改造前
    // 七个 `shutdownNow()` 埋下的崩溃面。
    appScope.cancel()
    superOnDestroy()
}
