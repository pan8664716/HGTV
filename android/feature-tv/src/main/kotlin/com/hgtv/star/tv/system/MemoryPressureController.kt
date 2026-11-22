// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.content.ComponentCallbacks2
import android.graphics.Rect
import com.hgtv.star.core.PlaybackCache
import com.hgtv.star.shared.ui.Soft

/**
 * 把视口之外的封面从 ImageView 上摘掉（低内存专用，见 `ArtworkLoader.releaseHiddenCovers`）。
 *
 * 只摘「不在屏幕上」的那些：`getGlobalVisibleRect` 对滚出视口的卡返回 false，
 * 焦点卡与可见卡不动，否则用户眼前会闪一片灰框。摘掉之后位图才会真的落进
 * LRU 可淘汰的范围，`evictAll()` 才有意义（回读只要 3~10ms/张，且多数命中盘缓存）。
 *
 * **摘掉的是「显示」，不是「地址」**（见 `PosterCard.suspendCover`）：地址留着，
 * 回到视口时 [resumeVisibleCoversImpl] 直接按原地址挂回来。
 */
internal fun TvMainActivity.releaseOffscreenCoversImpl() {
    runCatching {
        val rect=Rect()
        // 首页那一份由 HomeScreen 自己数（它的剧架是未窗口化的，77 张常驻，是大头）。
        suspendedCovers=homeScreen?.suspendCoversOutside(rect) ?: 0
        gridRows.forEach { holder ->
            holder.cards.forEach { card ->
                if(!card.image.getGlobalVisibleRect(rect) && card.suspendCover()) suspendedCovers++
            }
        }
    }
}

/**
 * 把重新回到视口里的封面挂回去（滚动、焦点移动、窗口挪动之后都调一次）。
 *
 * `suspendedCovers == 0` 时是**一次整数比较**，平时滚动这条路径的代价为 0。
 */
internal fun TvMainActivity.resumeVisibleCoversImpl() {
    if(suspendedCovers<=0) return
    val rect=Rect()
    homeScreen?.resumeCoversInside(rect)
    gridRows.forEach { holder ->
        holder.cards.forEach { card ->
            if(!card.coverSuspended) return@forEach
            if(card.image.getGlobalVisibleRect(rect)) card.resumeCover()
        }
    }
    // 重新数一遍：首页与栅格各占一半，摘掉的那几张可能都回来了。
    suspendedCovers=(homeScreen?.suspendedCoverCount() ?: 0)+
        gridRows.sumOf { holder -> holder.cards.count { it.coverSuspended } }
}

/**
 * 4.4~7.x 上的低内存处理：统一按最重的一档放掉可重建缓存。
 */
internal fun TvMainActivity.onLowMemoryImpl() {
    PlaybackCache.trimMemory(severe=true)
    releaseOffscreenCovers()
    if(artworkReady) artwork.trimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    runCatching { Soft.trimMemory(0.2f) }
}

internal fun TvMainActivity.onTrimMemoryImpl(level: Int) {
    if(!mainInitialized) return
    // 柔光位图是可重建的纯缓存，内存吃紧时先收缩它。
    // **不能整份清空**：清空之后下一帧会把屏幕上每一张可见卡重新生成一遍，
    // 那是一次几百毫秒的主线程尖峰（线上那几条渲染 ANR 正是它）。按比例淘汰旧条目，
    // 可见的那一屏原样保留。
    // 低配只给焦点卡画柔光位图（见 [Soft.shadowBitmaps]），缓存里通常只有一两张，
    // 砍它没有任何收益，反而会把下一次焦点移动变成主线程上的位图重建
    // （`Soft.layers` → `build()` 是在 `draw()` 里同步跑的）。因此低配跳过这一步。
    if(level>=ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && Soft.shadowBitmaps) Soft.trimMemory()
    if(artworkReady) artwork.trimMemory(level)
    if(level>=ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
        PlaybackCache.trimMemory(
            severe=level>=ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
        )
        cancelPrefetch()
        // 推荐页的相邻地址预热是可重建的纯缓存：内存吃紧时先取消。
        if(screen!="recommend") closeRecommendCache()
        // 推荐页不能整份清掉（正在播的那一部就在里面），只收窄后续预热窗口。
        else {
            cancelScheduledPreload()
            recommendPrepared.cancel()
            slideCache.onTrimMemory(level>=ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        }
    }
}
