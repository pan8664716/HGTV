// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.view.View
import com.google.android.exoplayer2.Player
import com.hgtv.star.shared.debug.StartupDeferredTasks

internal fun PhonePlayerActivity.danmakuTarget(): com.hgtv.star.shared.media.DanmakuView? = when {
    playerLandscapeMode && playerLandDanmakuInitialized -> playerLandDanmaku
    playerPageInitialized -> playerPage.danmakuView
    else -> null
}

/**
 * 把本机偏好落到弹幕层上（显示方式 / 字号 / 透明度 / 速度 / 聚合）。
 *
 * 与 TV 的 MainActivityPlayer 建弹幕层那一段**逐项一致**：同一份落盘值、
 * 同一个 DanmakuView，只是手机上字号按 density 走。
 */
internal fun PhonePlayerActivity.applyDanmakuSettings() {
    val views = buildList {
        if (playerEpisodePagerInitialized) {
            playerEpisodePager.pages().forEach { add(it.danmakuView) }
        } else if (playerPageInitialized) {
            add(playerPage.danmakuView)
        }
        if (playerLandscapePagerInitialized) {
            playerLandscapePager.pages().forEach { add(it.danmaku) }
        } else if (playerLandDanmakuInitialized) {
            add(playerLandDanmaku)
        }
    }
    for (view in views.distinct()) {
        applyDanmakuSettingsToView(view)
    }
    // 目标层换过（竖屏 ↔ 横屏、页面重建）就把已经拉到手的弹幕铺回去：
    // 只改设置不铺数据的话，新那一层会一直空着——用户读到的就是「转个屏弹幕就没了」。
    playerDanmakuLoader.reattach()
}

internal fun PhonePlayerActivity.applyDanmakuSettingsToView(
    view: com.hgtv.star.shared.media.DanmakuView,
) {
    val lib = library ?: return
    // 横竖屏两层按**同一个基准**取字号（屏幕长边）：按各自视图高度算的话，
    // 竖屏 1920 与横屏 1080 会差 1.6 倍，切全屏字号明显跳一档。
    view.sizeFollowsLongEdge = true
    view.mode = lib.danmakuMode
    view.textScale = PhoneDanmakuSize.renderScale(this)
    view.textAlpha = lib.danmakuAlpha
    view.speed = lib.danmakuSpeed
    view.mergeRepeats = lib.danmakuMerge
    view.mergeMinCount = lib.danmakuMergeMin
    view.effectsEnabled = lib.danmakuEffects
    // 0 行不能隐藏 SurfaceView：销毁独立 Surface 后，部分机型会留下黑色图层。
    // mode=HIDDEN 会停止绘制并提交透明帧，View 本身必须继续存活。
    view.visibility = View.VISIBLE
}

/**
 * 拉这一集的弹幕。
 *
 * 实现在 :shared 的 DanmakuLoader：与电视端、手机信息流
 * **同一份代码**（同一集不重拉、旧回包丢弃、隐藏时不发请求、边拉边画）。
 */
internal fun PhonePlayerActivity.loadDanmaku(
    episodeId: String,
    seriesId: String,
    clearOnSwitch: Boolean = false,
) {
    // 主动换集立刻停住旧弹幕：load 是延迟调度的，等它落地时缓冲期旧弹幕还在走。
    if (clearOnSwitch) playerDanmakuLoader.clear()
    val run = {
        playerDanmakuLoader.load(episodeId, seriesId, clearOnSwitch = clearOnSwitch)
        val exo = player
        if (exo?.playbackState == Player.STATE_READY && lastFrameAt > 0L) {
            playerDanmakuLoader.onFrameRendered(exo.currentPosition)
        }
    }
    if (StartupDeferredTasks.isPlaybackReady()) {
        run()
    } else {
        StartupDeferredTasks.scheduleLatestOnMain("danmaku.phone.player", 30_000L, run)
    }
}

/** 把播放位置 / 播放状态 / 倍速推给弹幕层（每帧只读，不回调播放器）。 */
internal fun PhonePlayerActivity.syncDanmakuClock(
    positionMs: Long,
    running: Boolean,
    speed: Float,
) {
    playerDanmakuLoader.syncClock(positionMs, running, speed)
}

/** 播放器事件到达时立刻采样，不等每秒 tick。 */
internal fun PhonePlayerActivity.syncDanmakuClockNow() {
    val p = player ?: return
    val sample = runCatching {
        Triple(
            p.currentPosition,
            p.isPlaying && p.playbackState == Player.STATE_READY,
            p.playbackParameters.speed,
        )
    }.getOrNull() ?: return
    syncDanmakuClock(sample.first, sample.second, sample.third)
}

/**
 * 切形态后把这一集的弹幕接到新那一层上。
 *
 * 竖屏与全屏是两个独立的 DanmakuView，数据在视图内部；不重放的话，
 * 竖屏看着有弹幕、一点全屏就空了。位置 / 状态 / 倍速一并带过去，
 * 新那一层从当前进度接着滚，不是从头再放一遍。
 */
internal fun PhonePlayerActivity.replayDanmaku() {
    if (!playerDanmakuLoader.loaded) return
    val target = danmakuTarget() ?: return
    // 同一层不重复重放：applyMode 同步一次、布局完再补一次，两层都重排一遍没有意义。
    if (target === playerDanmakuReplayTarget) return
    val p = player ?: return
    playerDanmakuLoader.replay(p.currentPosition, p.isPlaying, p.playbackParameters.speed)
    playerDanmakuReplayTarget = target
}
