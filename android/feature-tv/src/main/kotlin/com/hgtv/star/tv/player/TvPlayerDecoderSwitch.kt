// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

/**
 * 换解码方式：重建播放器并从当前位置接着播。
 *
 * 解码器是在播放器创建时定下的，运行中改不了；因此先把播放器放掉（[playEpisode] 的
 * `reuse` 判据要求 `player!=null`，这里置空就是让它走「重建」那条路），
 * 再按新档位重新起播。画面会闪一下，但播放位置、倍速、连播状态都不变。
 *
 * [position] / [play] 由**故障降级**那条路传进来（见 `replayWithPlan`）：出错时
 * `playbackReady` 可能已经被清掉，这里再读一次 `currentPosition()` 会退回本集开头，
 * 于是「切软解」永远从第 0 秒开始——用户看到的是画面倒退，而不是接着刚才那一下继续。
 */
fun TvMainActivity.applyDecoderMode(position: Long = -1L, play: Boolean? = null) {
    if(screen!="player" && screen!="recommend") return
    val old=player ?: return
    val resumeAt=if(position>=0) position else currentPosition()
    val resumePlay=play ?: old.playWhenReady
    // 先把这一集的进度落盘：下面立刻把 player 置空，playEpisode 里那次 saveProgress 就取不到播放器了。
    saveProgress()
    playerListener?.let { old.removeListener(it) }; playerListener=null
    // 任何在切档前已经开始的异步构建都失效：即使稍后又切回同一档，也不能把旧实例装回来。
    playerBuildEpoch++
    playerView?.player=null; player=null; playbackReady=false
    configuredPlayer=null
    builtPlayerDecoderMode=null
    // 释放**不能**放到 io 线程：`ExoPlayerImpl.release()` 第一句就是 `verifyApplicationThread()`，
    // 线程不符直接抛 `Player is accessed on the wrong thread`——早先那版把它裹在 `runCatching`
    // 里，异常被吞掉，旧播放器连同播放线程、解码器一起泄漏，还会和新播放器抢同一颗解码器。
    //
    // 留在主线程又不卡的办法是分两步：先 `stop()` + `clearMediaItems()`（只走内部消息，
    // 把 Renderer 上的 MediaCodec / AudioTrack 卸掉），再 `release()`（这时只剩线程收尾）。
    // 实测 `release()` 那 0.4–2 秒的大头就是等 Renderer 收干净，先 stop 之后基本归零。
    // 重入不需要额外标志位：`player` 上面已经置空，第二次点进来 `val old=player ?: return` 直接返回。
    val ticket=++generation
    val started=android.os.SystemClock.elapsedRealtime()
    runCatching { old.stop(); old.clearMediaItems() }
    runCatching { old.release() }
    android.util.Log.i("HGTV","Old player released in ${android.os.SystemClock.elapsedRealtime()-started}ms")
    // 这期间用户可能已经返回 / 换了别的剧：那一次页面重建会把 generation 推走。
    if(isDestroyed || generation!=ticket) return
    playEpisode(episodeIndex,resumeAt,resumePlay)
}

/**
 * 设置页改了解码档位时，播放器实例仍在后台保留。
 *
 * `detachPlayerSurface()` 为了下次起播更快，不会 release 播放器；但渲染器和解码器选择器
 * 是构造期定下的。用户「播放中切软解 → 退出到设置切回硬解 → 再进播放」时，
 * 不在这里放掉旧实例，就会继续复用软件渲染器，所有视频都表现为 4005。
 */
internal fun TvMainActivity.releasePlayerForDecoderMismatch() {
    val old=player ?: return
    val built=builtPlayerDecoderMode
    if(built==playbackDecoderMode) return
    android.util.Log.w(
        "HGTV",
        "Releasing player for decoder mismatch built=${built?.name ?: "unknown"}"+
            " requested=${playbackDecoderMode.name}",
    )
    playerListener?.let { old.removeListener(it) }; playerListener=null
    playerView?.player=null
    playerBuildEpoch++
    player=null
    configuredPlayer=null
    builtPlayerDecoderMode=null
    playbackReady=false
    runCatching { old.stop(); old.clearMediaItems(); old.release() }
}
