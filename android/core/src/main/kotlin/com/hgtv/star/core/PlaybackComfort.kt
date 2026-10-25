// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 视频解码方式。
 *
 * 硬件解码走芯片自带的解码器（省电、发热低、4K 更稳），软件解码走 CPU 解码
 * （兼容性更好：花屏、绿屏、音画不同步、黑屏有声这类片源问题通常能绕过）。
 *
 * 选择在**创建播放器时**生效：切换会重建播放器并从当前位置续播，见
 * `MainActivity.applyDecoderMode`。
 */
enum class VideoDecoderMode(val label: String) {
    HARDWARE("硬件解码"), SOFTWARE("软件解码");
    companion object { fun fromStored(value: String?)=entries.firstOrNull { it.name==value } ?: HARDWARE }
}

enum class VideoFrameMode(val label: String) {
    FIT("完整画面"), ZOOM("等比铺满"), FILL("拉伸铺满");
    companion object { fun fromStored(value: String?)=entries.firstOrNull { it.name==value } ?: FIT }
}

/** Session-only timer driven by a monotonic clock, independent of playback speed. */
class SleepTimer(private val now: ()->Long) {
    private var deadline: Long?=null
    var episodesLeft=0; private set
    val active get()=deadline!=null || episodesLeft>0
    fun cancel() { deadline=null; episodesLeft=0 }
    fun afterMinutes(minutes: Int) { require(minutes in 1..180); cancel(); deadline=now()+minutes*60_000L }
    fun afterEpisodes(count: Int) { require(count in 1..10); cancel(); episodesLeft=count }
    fun remainingMillis()=deadline?.let { (it-now()).coerceAtLeast(0) }
    fun poll(): Boolean { if(deadline?.let { now()>=it }!=true) return false; cancel(); return true }
    fun episodeEnded(): Boolean {
        if(poll()) return true
        if(episodesLeft<=0) return false
        episodesLeft--
        return episodesLeft==0
    }
    fun label(): String = remainingMillis()?.let { "${(it+59_999)/60_000} 分钟后" }
        ?: if(episodesLeft>0) "再播完 $episodesLeft 集" else "关闭"
}
