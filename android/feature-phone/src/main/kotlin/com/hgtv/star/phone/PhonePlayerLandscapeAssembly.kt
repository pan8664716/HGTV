// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.PlayerView
import com.hgtv.star.core.DanmakuMode
import com.hgtv.star.phone.ui.PhoneTouch
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.phoneSize

/** 横屏装配：相邻集页、视频、弹幕和透明手势层。 */
internal fun PhonePlayerActivity.buildLandscapeBase() {
    // 相邻集那一页（横屏版）：黑底 + 左上角「标题 第N集」，与竖屏的 ghost 页同一个角色。
    // 加在 landscape 之前，因此它永远在下面，整页滑开才露出来。
    val ghost = FrameLayout(this).apply {
        setBackgroundColor(Color.BLACK)
        visibility = View.GONE
    }
    val ghostTitle = TextView(this).apply {
        setTextColor(Color.WHITE)
        phoneSize(15)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    ghost.addView(ghostTitle, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        Gravity.TOP or Gravity.START
    ).apply {
        topMargin = dp(14)
        leftMargin = dp(16)
    })
    root.addView(ghost, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    ))

    val landscape = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
    val video = PlayerView(this).apply {
        useController = false
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        setShutterBackgroundColor(Color.BLACK)
        setKeepContentOnPlayerReset(true)
    }
    landscape.addView(video, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    ))

    // 弹幕压在画面之上、手势层之下（与竖屏 `PhoneVideoPage` 里的叠放顺序一致）。
    val danmaku = com.hgtv.star.shared.media.DanmakuView(this).apply {
        mode = DanmakuMode.DEFAULT
        minTextSizeDp = 4f
        // 与竖屏那一层同一个基准字号（见 `applyDanmakuSettings`）。
        sizeFollowsLongEdge = true
        setClock(0, false)
    }
    landscape.addView(danmaku, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    ))

    // 手势挂在一块**透明覆盖层**上：横屏没有「信息栏里的按钮」需要让路，
    // 覆盖层可以整屏接管，点击一律先到状态机。
    val touchLayer = View(this)
    touchLayer.setOnTouchListener(
        PhoneTouch(
            host = touchLayer,
            isLandscape = { true },
                onTap = { setChromeVisible(!playerChromeVisibleSlot) },
            onDoubleTap = { togglePlay() },
            // 与竖屏同一套分区（用户要求）：中间 1/3 长按 = 播放设置，
            // 左右 1/3 长按 = 2 倍速。早先横屏不分区，长按哪里都只加速，
            // 用户在全屏里找不到播放设置（只能去点右上角那三个点）。
            onLongPress = { zone ->
                when (zone) {
                    PhoneTouch.Zone.CENTER -> { endTempSpeed(); openSettings() }
                    else -> beginTempSpeed()
                }
            },
            onLongPressEnd = { endTempSpeed() },
            onDragStart = {
                playerDragging = true
                playerSeekFrom = player?.currentPosition ?: 0L
            },
            onDrag = { fraction -> dragTo(fraction) },
            onDragEnd = {
                playerDragging = false
                val duration = player?.duration ?: 0L
                if (duration > 0) player?.seekTo(playerSeekTarget)
                clearSeekHint()
            },
            // 横屏同样支持上下滑换集：竖屏有、横屏没有，用户会以为功能坏了。
            verticalEnabled = true,
                onVerticalStart = { beginSwipeImpl() },
                onVertical = { delta -> dragSwipeImpl(delta) },
                onVerticalEnd = { direction, _ -> endSwipeImpl(direction) },
        )
    )
    landscape.addView(touchLayer, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    ))
    swipeLandscapeGhost = ghost
    swipeLandscapeGhostTitle = ghostTitle
    playerLandscapeContainer = landscape
    playerLandscapeVideoSlot = video
    playerLandDanmakuSlot = danmaku
}
