// SPDX-License-Identifier: GPL-3.0-only
@file:JvmName("MainActivityPlaybackKt")
@file:JvmMultifileClass
package com.hgtv.star.tv

import com.hgtv.star.shared.compat.hasInternet
import com.hgtv.star.shared.compat.isMeteredConnection

import android.app.Activity
import android.view.*
import android.widget.*
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.mediacodec.MediaCodecRenderer
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.ui.PlayerView
import com.hgtv.star.core.*
import com.hgtv.star.shared.data.WatchProgress
import com.hgtv.star.shared.data.PlaybackCapability
import com.hgtv.star.shared.data.AppRuntime
import com.hgtv.star.shared.media.VideoDataSource
import com.hgtv.star.shared.compat.setVectorIcon
import com.hgtv.star.tv.player.PlaybackMenu
import com.hgtv.star.shared.media.MediaSessionHost
import com.hgtv.star.tv.TvDialog
import com.hgtv.star.shared.compat.attachedToWindow
import com.hgtv.star.shared.architecture.network
import com.hgtv.star.shared.debug.NetSpeed
import com.hgtv.star.tv.R
fun MainActivity.hidePlaybackOverlay() {
    dismissPlaybackMenu(); controls.visibility=View.GONE; controls.clearFocus(); hud.visibility=View.GONE
    // 方向键开关放在 [dismissPlaybackMenu] **之后**归零：那个函数会把 `panel` 还原成
    // [playError]（出错页的按钮要能被焦点选中），这里既然连控制条都收了，就一定是 false。
    panel=false
    // 顶部那条信息跟着控制条一起收：浮层收起之后，四项回到「播放显示」里用户选的值
    // （默认全关，见 [syncPlaybackInfo]）。两页都要同步——播放页收起控制条时
    // 那四项也不该继续常驻，否则「默认全不选」就落不实。
    syncPlaybackInfo()
    // 三块浮层都收了，自动消失的定时器也跟着作废（留着只会在几百毫秒后再跑一次空动作）。
    // 放在最后：此刻 `hud` 已经是 GONE，`scheduleOverlayAutoHide()` 里的判据也不会再把它排上。
    cancelOverlayAutoHide()
}
/**
 * 播放页三块浮层（播放设置 / 选集面板 / 底部控制条）的「无操作自动消失」计时：从**现在**开始重新数。
 *
 * 这几种时候调它：面板或控制条刚打开、用户按了一下（方向键 / 确定 / 触摸）、
 * 拖动了进度条或滑块。规则是「最后一次操作之后再过 N 秒」，因此每次都是先取消再重排——
 * 不能只排不管，那样第二次操作就白按了。
 *
 * 三块浮层都不在时什么都不做（顺手把上一次的定时器清掉）。播放设置开着时控制条本来就是收着的
 * （见 `showPlaybackMenu`），因此这里按「菜单 → 选集 → 控制条」的优先级只看一处就够。
 */
fun MainActivity.scheduleOverlayAutoHide() {
    main.removeCallbacks(overlayAutoHide)
    val visible=playbackMenu!=null || episodePanel!=null || hudShown()
    if(!visible) return
    // 设置还没读出来时用出厂值：冷启动直接进播放页时 `library` 可能仍为空。
    val seconds=runCatching { library.playerPanelTimeout }.getOrDefault(PLAYER_PANEL_TIMEOUT_DEFAULT)
    main.postDelayed(overlayAutoHide,(seconds*1000L).coerceAtLeast(1000L))
}

/** 浮层收起（选择完成、返回键、自动消失）时把定时器一并停掉。 */
fun MainActivity.cancelOverlayAutoHide() = main.removeCallbacks(overlayAutoHide)

/**
 * 底部控制条此刻是不是露着（推荐页也算：手机 / 平板那一页用的是同一条控制条）。
 *
 * 「算不算露着」按页面分两种口径：
 * * 正式播放：只要露着就算——暂停、出错文案之外的任何状态都要参与自动收起；
 * * 推荐页：**只有触屏**才算。电视上那一页根本没有这条控制条（信息在常显的底部浮层里，
 *   见 `attachRecommendOverlay`），`touchArmed` 为 false 时这里也就不会误判成「露着」。
 *
 * `hud` 是 lateinit：冷启动还没建播放层时读它会抛，因此用一次受捕的读取兜住
 *（与 [playbackOverlayShown] 同一套写法）。
 */
fun MainActivity.hudShown(): Boolean = runCatching {
    (screen=="player" || (screen=="recommend" && touchArmed)) &&
        !panel && hud.visibility==View.VISIBLE
}.getOrDefault(false)
/**
 * 当前这一屏是不是播放页：正式播放与推荐播放。
 *
 * 两条看门狗（起播不出帧 / 播着播着停帧）过去只认 `screen=="player"`，
 * 而推荐页跑的是同一套播放内核，同样会出现「有声音、没画面」——那一页上这个故障
 * 恰恰最显眼（整屏都是视频）。因此「要不要盯」与「算不算离开播放页」统一走这一个判据，
 * 免得以后再出现一处改了、另一处没跟上。
 */
fun MainActivity.onPlaybackPage(): Boolean = screen=="player" || screen=="recommend"

/**
 * 屏幕常亮的**唯一判据**。
 *
 * 早先是 `playEpisode` 里无条件 `addFlags`，只在 `requestPlayback(false)` / `stopForSleep` /
 * 离开播放页三处 `clearFlags`。漏掉的正是最要命的两种：
 *
 * 1. **暂停后走开**——包括从后台回来的那次 `playEpisode(..., autoplay=false)`：
 *    画面停在某一帧、`playWhenReady=false`，屏幕却一直亮到用户动手；
 * 2. **停在错误页上**——`playerFailure` 只 `player?.pause()`，从不碰这个 flag。
 *    用户看完「解码失败」放下遥控器，电视一整夜不黑屏。
 *
 * OLED / 等离子面板上，静态标题条 + 静态错误文案就是清晰的烧屏痕迹；LCD 上是背光寿命。
 *
 * 判据只看「此刻真的在推进」：前台 + 在播放页 + 没出错 + 没被定时停 + `playWhenReady`。
 * 缓冲中 `playWhenReady` 仍为 true，因此缓冲不会掉到息屏——那正是最不该黑屏的时候。
 *
 * **所有 add/clear 都必须走这里**：分散在两处各写一次，迟早又会漏掉一条新路径。
 */
fun MainActivity.syncKeepScreenOn() {
    val on=foreground && onPlaybackPage() && !playError && !sleepStopped &&
        player?.playWhenReady==true
    if(on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    // 屏幕常亮只管屏幕，不管 CPU，也不管 Wi-Fi 省电。这两件事与「是否在播」是同一个判据，
    // 因此在这里一起驱动（见 `PlaybackPower` 的类注释：分散在两处各写一次迟早会漏）。
    if(on) playbackPower.acquire() else playbackPower.release()
}

fun MainActivity.showHud() {
    // 设置菜单打开时底部控制条保持收起，避免和菜单抢同一块画面。
    if(playbackMenu!=null) return
    // 推荐页：只有触屏设备才露这条控制条（它上面的那排按钮本来也只在触屏上存在）。
    // 电视上推荐页从来没有控制条——那一页的信息在底部浮层里，结构保持不变。
    if(screen!="player" && !(screen=="recommend" && touchArmed)) return
    hud.visibility=View.VISIBLE; updatePlaybackText(); cancelOverlayAutoHide()
    syncPlaybackInfo()
    // 控制条：**不看是否在播**——暂停时同样按设置里的秒数收起
    //（原来只在 `isPlaying` 时排定时器，一暂停控制条就永久留在画面上，
    // 暂停久了一张静态的进度条一直压在画面底部）。
    //
    // 判据只写「在播放页就排」，不再看 `panel`：只要这一下是**唤出控制条 / 进度条**，
    // 就一律归到同一个计时器里数。出错那一版控制条（`playError`，上面压着错误文案）
    // 由定时器自己的判据挡掉——到点什么都不做，等于没排。这样「唤出 = 开始计时」
    // 只有一条规则，以后再加唤出路径也不会漏掉计时的分支。
    //
    // 推荐页同样走这一条：手机 / 平板上那一页的控制条与正式播放是同一条，
    // 到了上面那一行「触屏才算」的判据之外没有别的情形，因此这里不再按页面分支。
    // 上下换剧、重新进推荐页都会再唤一次（见 `startRecommendEpisode`），计时随之重排。
    scheduleOverlayAutoHide()
}
fun MainActivity.updatePlaybackText() {
    // 触屏设备上控制条多一排按钮，播放键的文案也要跟着播放状态改（电视上为空操作）。
    updateTouchPlayLabel()
    // 顶部那格「第 x / 共 N 集」跟着当前集号走：无感切集不走 `playEpisode`，
    // 只在这里对一次，任何换集路径（含片尾自动推进）都不会留下上一集的集号。
    syncPlaybackEpisodeLabel()
    // 推荐页（触屏）用的是**同一条控制条**，读数与状态项这一整段逻辑完全一样，
    // 因此这里不再只认 `player`——否则唤出来的控制条上进度与「清晰度 / 倍速 / 画面」都是空的。
    // 电视上的推荐页仍然走它自己的浮层刷新（`updateRecommendOverlay`），这一条不会被执行。
    if(screen!="player" && !(screen=="recommend" && touchArmed)) return
    // 手指正在拖进度条：这一段时间进度与时间由手指决定，每秒一次的刷新不能把它顶回去。
    if(scrubbing) return
    // 推荐页拖的是同一条进度条，它的「拖动中」标记在 `onRecommendScrub` 里（见那里）。
    if(screen=="recommend" && recommendScrubbing) return
    // 播放键图标与选项行的当前值：爆米花把状态放在图标和选项文字里，不额外占一行。
    transportIcon?.setVectorIcon(if(player?.playWhenReady==true) R.drawable.ic_player_pause else R.drawable.ic_player_play)
    qualityOption?.text="清晰度 "+if(quality.isBlank()) "${playbackMaxQuality}P" else quality
    speedOption?.text="倍速 "+PlaybackSpeed.label(if(playbackReady) player?.playbackParameters?.speed ?: library.playbackSpeed else library.playbackSpeed)
    frameOption?.text="画面模式 "+library.frameMode.label
    // 状态行只放显示方式：弹幕条数放在「设置 → 弹幕显示」那一行，
    // 播放界面的这一排已经够长，再加数字会被屏宽裁掉。
    danmakuOption?.text="弹幕 "+library.danmakuMode.label
    sleepOption?.text="定时 "+sleepTimer.label()
    syncBufferingDots()
    if(playError) return
    if(sleepStopped) { playbackText.text="定时停止 · 按播放继续"; return }
    if(!playbackReady) { playbackText.text=""; return }
    val p=player ?: return
    val duration=p.duration.coerceAtLeast(0); val position=pendingSeek ?: p.currentPosition
    val span="${formatSpan(position)} / ${formatSpan(duration)}"
    playbackText.text=if(p.playbackState==Player.STATE_ENDED) "本集已结束 · $span" else span
    progressBar.progress=if(duration>0) position.toFloat()/duration else 0f
    progressBar.bufferedProgress=if(duration>0) {
        p.bufferedPosition.coerceIn(0,duration).toFloat()/duration
    } else 0f
}
/**
 * 顶部信息条里的集数读数：`第 3 / 24 集`。
 *
 * 集号与总集数都从 `detail` + [episodeIndex] 现算，**不缓存**在调用点——
 * 片尾无感切集（[onEpisodeAdvanced]）换的是播放器的 period，不走 [playEpisode]，
 * 早先只有 `playEpisode` 写这一格，于是自动连播之后界面上还挂着上一集的集号。
 *
 * 内容没变就不 `setText`：这条每秒都会跟着 tick 调一次，重复设置会让整条重排。
 * [onEpisodeAdvanced] 与 [playEpisode] 里可以显式传 `data`，其余路径不传就用当前的 `detail`。
 */
fun MainActivity.syncPlaybackEpisodeLabel(data: Detail? = detail) {
    val view=playbackEpisode ?: return
    // 推荐页不显示「第 x / y 集」；正式播放页仍按「播放显示」开关正常显示。
    if(screen=="recommend") {
        view.visibility=View.GONE
        return
    }
    val total=data?.episodes?.size ?: 0
    if(total<=0) return
    val key="${episodeIndex}/${total}"
    if(view.tag==key) return
    view.tag=key
    // 集数文案只有一份（`:core` 的 `PlaybackInfoLine`）：手机端信息条读的是同一段格式。
    view.text=com.hgtv.star.core.PlaybackInfoLine.episode(episodeIndex,total)
}
fun MainActivity.requestPlayback(play: Boolean) {
    requestedAutoplay=play; pausedForLifecycle=!play
    if(play) sleepStopped=false
    if(!play) { player?.pause(); retryAutoplay=false; saveProgress() }
    // 错误页上按「播放」＝一次手动重试：与错误页那颗按钮走**同一条**还款路径
    //（`resetBudgetsForManualRetry`），否则两个入口的额度语义不一样：
    // 按钮还三份、这里只还 session 那一份，自动重试用尽之后按播放就只会闪回同一句提示。
    else if(playError) {
        resetBudgetsForManualRetry()
        // 与错误页「立即重试」走同一条：按播放键也必须先作废当前集的地址 /
        // 索引 / 首块并换请求 device_id，否则会原样命中刚才失败的缓存。
        prepareFreshPlaybackRetry()
        playEpisode(episodeIndex,retryPosition,true,recovering=true)
        return
    }
    else { if(player?.playbackState==Player.STATE_ENDED) player?.seekTo(0); player?.play() }
    // 屏幕常亮由状态推导，不再在这里 add/clear（见 [syncKeepScreenOn]）：
    // `play` 与「真的在播」不是同一件事——出错页、定时停、后台回来这三条都会不一样。
    syncKeepScreenOn()
    showHud(); updatePlaybackText(); syncMediaSession()
}
fun MainActivity.togglePlayback()=requestPlayback(!(if(playbackReady) player?.playWhenReady ?: requestedAutoplay else requestedAutoplay))
fun MainActivity.seekTo(position: Long) {
    val p=player ?: return; if(!playbackReady || p.duration<=0) return
    main.removeCallbacks(seekRunnable); pendingSeek=null
    p.seekTo(position.coerceIn(0,(p.duration-500).coerceAtLeast(0))); updatePlaybackText(); syncMediaSession(); showHud()
}
fun MainActivity.skipEpisode(direction: Int) {
    if(!foreground || (screen!="player" && screen!="recommend")) return
    val count=detail?.episodes?.size ?: return
    val target=episodeIndex+direction
    if(target in 0 until count) playEpisode(target,clearDanmakuOnSwitch=true)
}
/**
 * 媒体会话懒创建：启动阶段不碰系统 `media_session`。
 *
 * 虚拟容器（SandVXposed 等）会把 `ISessionManager.createSession` 的返回值代理成 null，
 * 而平台 `MediaSession` 构造函数不判空，直接在 `mBinder.getController()` 抛 NPE，
 * 顺着 `onCreate` 变成「Unable to start activity」闪退。这里建不出来就记住失败，
 * 之后静默跳过——遥控器媒体键只是附加能力，不影响播放。
 */
fun MainActivity.ensureMediaSession(): MediaSessionHost? {
    mediaSession?.let { return it }
    if(mediaSessionFailed) return null
    val created=MediaSessionHost.create(this,
        { if(foreground && (screen=="player" || screen=="recommend")) requestPlayback(true) },
        { if(foreground && (screen=="player" || screen=="recommend")) requestPlayback(false) },
        { position -> if(foreground && (screen=="player" || screen=="recommend")) seekTo(position) },
        { direction -> if(screen=="recommend") switchRecommend(direction) else skipEpisode(direction) },
        { if(foreground && screen=="player") returnToDetail() })
    if(created==null) mediaSessionFailed=true else mediaSession=created
    return created
}
fun MainActivity.syncMediaSession() {
    if(!foreground || (screen!="player" && screen!="recommend")) { mediaSession?.deactivate(); return }
    val data=detail ?: return; val p=player
    val session=ensureMediaSession() ?: return
    val state=when {
        playError -> android.support.v4.media.session.PlaybackStateCompat.STATE_ERROR
        sleepStopped || !requestedAutoplay || (p!=null && !p.playWhenReady) -> android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED
        p==null || !playbackReady || p.playbackState==Player.STATE_BUFFERING -> android.support.v4.media.session.PlaybackStateCompat.STATE_BUFFERING
        p.playbackState==Player.STATE_ENDED -> android.support.v4.media.session.PlaybackStateCompat.STATE_STOPPED
        p.isPlaying -> android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING
        else -> android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED
    }
    session.update(data.series.title,episodeIndex,p?.duration?.coerceAtLeast(0) ?: 0,currentPosition(),library.playbackSpeed,state,episodeIndex>0,episodeIndex<data.episodes.lastIndex)
}
fun MainActivity.stopForSleep() {
    dropQueuedEpisode()
    sleepStopped=true; requestedAutoplay=false; retryAutoplay=false; pausedForLifecycle=true
    sleepChoice=0
    cancelRecovery(); player?.pause(); saveProgress()
    syncKeepScreenOn()
    playbackText.text="定时停止已生效 · 观看位置已保存 · 按播放可继续"
    showHud(); if(playbackMenu!=null) showPlaybackMenu(sleepRow(library.danmakuMode.showsStyle)); syncMediaSession()

}
/** 定时项目的 8 个选项：关闭排在最前，选中态跟随本次观看已生效的定时。 */
fun MainActivity.sleepChoices(menu: PlaybackMenu): List<PlaybackMenu.Choice> {
    val current=if(sleepTimer.active) sleepChoice else 0
    return SleepTimer.LABELS.mapIndexed { index,label ->
        PlaybackMenu.Choice(label,current==index) {
            sleepChoice=index
            // 档位与分钟的对应关系只有一份（`core/SleepTimer.applyChoice`），两端同源。
            SleepTimer.applyChoice(sleepTimer,index)
            // 定时生效期间不放下一集进队列（见 [enqueuePreparedNext]）：已经在队列里的先摘掉，
            // 否则片尾 ExoPlayer 会自己推进，界面来不及在片尾那一下停住。
            if(index!=0) dropQueuedEpisode()
            menu.select(sleepRow(library.danmakuMode.showsStyle),index); updatePlaybackText()
        }
    }
}
/**
 * 画面模式 → ExoPlayer 的 `RESIZE_MODE_*`，**电视端唯一的一处绑定**。
 *
 * 「用户选了什么」到「最终怎么摆」的判定在 `:core`（[com.hgtv.star.core.VideoFrameMode.toFit]），
 * 与手机端同源；这里只负责把那个结果换成平台常量。电视是横屏设备，不存在竖屏片源，
 * 因此不传方向（默认 `false`）。
 */
