// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.view.View
import com.google.android.exoplayer2.Player
import com.hgtv.star.phone.ui.PhoneGlyph

internal fun PhonePlayerActivity.togglePlay() {
    if (playerControlsLocked) return
    val exo = player ?: return
    // 用用户意图（playWhenReady）而不是 isPlaying 判断：缓冲、seek、解码器重建期间
    // isPlaying 都可能短暂为 false，拿它判断会把“暂停”又点成“播放”。
    val pause = exo.playWhenReady
    android.util.Log.i(
        "HGTV-PHONE",
        "toggle ${if (pause) "pause" else "resume"} state=${exo.playbackState} " +
            "pwr=${exo.playWhenReady} playing=${exo.isPlaying}",
    )
    if (pause) {
        exo.pause()
        // 用户自己暂停：不是卡死，撤掉看门狗（恢复播放时重新装）。
        disarmPictureWatchdog()
    } else {
        if (exo.playbackState == Player.STATE_IDLE) runCatching { exo.prepare() }
        exo.play()
        // 暂停期间 Surface 可能被 ViewPager2 / 系统回收。恢复后强制确认当前页输出面，
        // 避免声音恢复但画面一直黑着。
        scheduleCurrentPlayerSurfaceRebind(baselineFrameAt = lastFrameAt)
        armPictureWatchdog()
    }
    showCenter(!exo.playWhenReady)
    syncPlayGlyph()
    syncBufferingUi(exo.playbackState)
    syncKeepScreenOn()
}

/**
 * 锁定后只保留“单击显隐信息”和解锁按钮。
 *
 * 竖屏与横屏共用这一份状态；锁定期间播放、进度、长按、选集、翻页入口都在各自入口处拦截。
 */
internal fun PhonePlayerActivity.togglePlaybackLock() {
    playerControlsLocked = !playerControlsLocked
    if (playerControlsLocked) {
        endTempSpeed()
        playerDragging = false
        progressDragging = false
        if (playerSheet.isOpen) playerSheet.dismiss()
        if (playerLandPanel != null) dismissLandscapeSettings()
        clearSeekHint()
        hideCenter()
    }
    syncPlaybackLockUi()
    scheduleChromeHide()
}

internal fun PhonePlayerActivity.syncPlaybackLockUi() {
    val locked = playerControlsLocked
    if (playerPageInitialized) {
        playerPage.setPlaybackLocked(locked)
        if (playerEpisodePagerInitialized) {
            playerEpisodePager.gesturesEnabled = !locked
            playerEpisodePager.pages().forEach { it.setPlaybackLocked(locked) }
        }
    }
    if (playerLandscapePagerInitialized) {
        playerLandscapePager.gesturesEnabled = !locked
    }
    if (playerLandLockInitialized) {
        PhonePlayerViewSupport.styleLockButton(playerLandLockSlot, playerLandLockGlyphSlot, locked)
    }
    if (playerLandBottomInitialized) {
        progressLandSeek.isEnabled = !locked
        progressLandSeek.alpha = if (locked) .55f else 1f
        playerLandPlay.tint = if (locked) 0x66FFFFFF else android.graphics.Color.WHITE
        listOf(chipSpeed, chipQuality, chipDanmaku, chipEpisode).forEach { chip ->
            chip.isEnabled = !locked
            chip.alpha = if (locked) .45f else 1f
        }
    }
}

/**
 * 缓冲指示的显隐（与电视端的 suppressBufferingUi / restoreBufferingUi 同一套规则）。
 *
 * 三个条件缺一不可：正在缓冲、没有处于「无感切集」的压制窗口、用户没有主动暂停。
 * 最后一条尤其重要：暂停后播放器也会落进 BUFFERING，那时转圈会让人以为「暂停没生效」。
 */
internal fun PhonePlayerActivity.syncBufferingUi(state: Int) {
    val label = if (state == Player.STATE_BUFFERING && player?.playWhenReady == true) {
        "正在缓冲…"
    } else {
        null
    }
    setBufferingLabel(label)
}

/** 统一的加载显隐入口：文案与三点动画一起显示，不再写到进度条上。 */
internal fun PhonePlayerActivity.setBufferingLabel(label: String?) {
    if (!playerBufferingInitialized) return
    val now = android.os.SystemClock.elapsedRealtime()
    val show = label != null && now >= playerBufferingMutedUntil && errorCard == null
    playerBufferingDots.label = label.orEmpty()
    playerBufferingBox.visibility = if (show) View.VISIBLE else View.GONE
    if (show) playerBufferingBox.bringToFront()
}

/**
 * 无感切集：压住缓冲指示 1.2 秒。
 *
 * 不压的话，ExoPlayer 从队列推进到下一集时会先报 BUFFERING，屏幕上闪一个转圈——
 * 而实际上画面一直有（停在上一集最后一帧），那个转圈纯属噪音。
 */
internal fun PhonePlayerActivity.muteBufferingUi() {
    playerBufferingMutedUntil = android.os.SystemClock.elapsedRealtime() + 1_200L
    playerBufferingBox.visibility = View.GONE
    root.postDelayed({
        if (!isFinishing && !isDestroyed) {
            syncBufferingUi(player?.playbackState ?: Player.STATE_IDLE)
        }
    }, 1_200L)
}

internal fun PhonePlayerActivity.syncPlayGlyph() {
    val wantsPlay = player?.playWhenReady == true
    playerLandPlay.kind = if (wantsPlay) PhoneGlyph.Kind.PAUSE else PhoneGlyph.Kind.PLAY
}

/** 屏幕中心的播放 / 暂停：暂停时一直亮着，播放中闪一下。 */
private fun PhonePlayerActivity.showCenter(paused: Boolean) {
    playerCenterGlyph.kind = if (paused) PhoneGlyph.Kind.PLAY else PhoneGlyph.Kind.PAUSE
    playerCenterBox.visibility = View.VISIBLE
    playerCenterBox.bringToFront()
    if (!paused) {
        playerCenterBox.postDelayed({
            if (player?.isPlaying == true) playerCenterBox.visibility = View.GONE
        }, 700)
    }
}

internal fun PhonePlayerActivity.hideCenter() {
    playerCenterBox.visibility = View.GONE
}

internal fun PhonePlayerActivity.beginTempSpeed() {
    if (playerControlsLocked) return
    if (playerTempSpeed) return
    playerTempSpeed = true
    player?.setPlaybackSpeed(2f)
    playerHintSticky = true
    hint.text = "2 倍速播放中"
    hint.visibility = View.VISIBLE
    hint.bringToFront()
}

internal fun PhonePlayerActivity.endTempSpeed() {
    if (!playerTempSpeed) return
    playerTempSpeed = false
    player?.setPlaybackSpeed(chipUserSpeed)
    playerHintSticky = false
    hint.visibility = View.GONE
}

/**
 * 横滑拖进度：**进度条与时间跟着手指实时走**，松手才真正 seek。
 *
 * 只更新显示、不 seek：拖动过程中每一帧都 seek 会让解码器反复重开，
 * 画面卡成一片。松手一次性跳过去，体验与 TV 端的「拖进度条」一致。
 */
internal fun PhonePlayerActivity.dragTo(deltaFraction: Float) {
    if (playerControlsLocked) return
    val duration = player?.duration ?: 0L
    if (duration <= 0) return
    playerSeekTarget =
        (playerSeekFrom + deltaFraction * duration * 1.2f).toLong().coerceIn(0, duration)
    val permille = (playerSeekTarget * 1000 / duration).toInt().coerceIn(0, 1000)
    // 横屏：底部的进度条 + 左侧当前时间
    progressLandSeek.progress = permille
    progressLandCurrent.text = clockForControls(playerSeekTarget)
    progressLandTotal.text = clockForControls(duration)
    // 竖屏：底部信息栏里那条进度条
    playerPage.setProgress(permille / 1000f)
    // 两种形态都有的上方读数
    hint.text = "${clockForControls(playerSeekTarget)} / ${clockForControls(duration)}"
    hint.visibility = View.VISIBLE
    hint.bringToFront()
}

internal fun PhonePlayerActivity.showSeekHint(fraction: Float) {
    val duration = player?.duration ?: 0L
    if (duration <= 0) return
    hint.text =
        "${clockForControls((duration * fraction).toLong())} / ${clockForControls(duration)}"
    hint.visibility = View.VISIBLE
}

internal fun PhonePlayerActivity.clearSeekHint() {
    if (!playerHintSticky) hint.visibility = View.GONE
}
