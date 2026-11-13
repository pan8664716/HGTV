// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import com.hgtv.star.tv.list.showCatalog

fun TvMainActivity.returnToDetail(forceDetail: Boolean=false) {
    sleepTimer.cancel(); sleepChoice=0; sleepStopped=false; saveProgress(); library.flush(); invalidatePage(); detachPlayerSurface()
    playbackFromRecommend=false
    if(directPlayback && !forceDetail) showCatalog() else { directPlayback=false; showDetail(true) }
}

/**
 * 把视口之外的封面从 ImageView 上摘掉（低内存专用，见 `ArtworkLoader.releaseHiddenCovers`）。
 *
 * 只摘「不在屏幕上」的那些：`getGlobalVisibleRect` 对滚出视口的卡返回 false，
 * 焦点卡与可见卡不动，否则用户眼前会闪一片灰框。摘掉之后位图才会真的落进
 * LRU 可淘汰的范围，`evictAll()` 才有意义（回读只要 3~10ms/张，且多数命中盘缓存）。
 *
 * **摘掉的是「显示」，不是「地址」**（见 `PosterCard.suspendCover`）：地址留着，
 * 回到视口时 [resumeVisibleCovers] 直接按原地址挂回来。
 */
fun TvMainActivity.releaseOffscreenCovers() = releaseOffscreenCoversImpl()

/**
 * 把重新回到视口里的封面挂回去（滚动、焦点移动、窗口挪动之后都调一次）。
 *
 * `suspendedCovers == 0` 时是**一次整数比较**，平时滚动这条路径的代价为 0。
 */
fun TvMainActivity.resumeVisibleCovers() = resumeVisibleCoversImpl()

/**
 * 4.4~7.x 上的低内存回调（`onTrimMemory` 之外的一条独立路径）。
 *
 * 不实现它，老电视上「整机内存告急」时只有系统日志，进程里什么都不做——
 * 而那正是最该放掉视口外封面的时刻。统一按最重的一档处理。
 */
internal fun TvMainActivity.handleLowMemory() {
    onLowMemoryImpl()
}

internal fun TvMainActivity.handleTrimMemory(level: Int) {
    onTrimMemoryImpl(level)
}

internal fun TvMainActivity.handleDestroy(superDestroy: () -> Unit) {
    onDestroyImpl(superDestroy)
}
