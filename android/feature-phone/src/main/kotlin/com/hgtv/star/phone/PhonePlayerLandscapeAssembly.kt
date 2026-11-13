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
import com.hgtv.star.phone.ui.PhoneGlyph
import com.hgtv.star.phone.ui.roundedBackground
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

/** 横屏常驻信息行、设置入口和竖版片源全屏入口。 */
internal fun PhonePlayerActivity.buildLandscapeTopControls() {
    /*
     * 横屏的「播放显示」：与竖屏同一套左右两段（剧名·集数 / 网速·时间）。
     *
     * 它**独立于控制层**：勾了任意一项就常驻（用户要求「全屏也要显示」），
     * 因此不能挂在 `landTop` 里——那一层会跟着控制层一起收起来。
     * 位置贴屏幕顶（`dp(10)`），比竖屏那条更靠上；`landTop` 让出它的高度。
     */
    val left = TextView(this).apply {
        setTextColor(Color.WHITE)
        phoneSize(13)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setShadowLayer(2f, 0f, 1f, 0x99000000.toInt())
    }
    val right = TextView(this).apply {
        setTextColor(Color.WHITE)
        phoneSize(12)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(dp(10), 0, 0, 0)
        setShadowLayer(2f, 0f, 1f, 0x99000000.toInt())
    }
    val info = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        addView(left, android.widget.LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        addView(right, android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }
    val landscape = playerLandscapeContainer
    landscape.addView(info, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        Gravity.TOP or Gravity.START
    ).apply {
        topMargin = dp(10)
        leftMargin = dp(16)
        rightMargin = dp(16)
    })
    // 量出它的下沿交给弹幕层：横屏弹幕从这一行之下才开始铺（见 `syncDanmakuTopInset`）。
    // 收起（GONE）时高度为 0，那一帧不采信，否则弹幕会窜回屏幕顶部。
    info.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
        if (bottom - top > 0) {
            playerLandInfoRowBottom = bottom
            syncDanmakuTopInset()
        }
    }
    playerLandInfoLeftSlot = left
    playerLandInfoRightSlot = right
    playerLandInfoRowSlot = info

    /*
     * 顶部第二行：**只剩播放设置按钮**（⋮），靠右。
     *
     * 剧名与集数已经在上面那条信息里了（`landInfoRow` 左半），这里再放一遍是重复。
     * 这一行仍属控制层：清屏时跟 `landBottom` 一起收起；信息行则常驻。
     */
    val top = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        setPadding(0, 0, dp(12), 0)
    }
    val more = FrameLayout(this).apply {
        // 尺寸收一档（用户要求）：36 → 28dp、图标 22 → 18dp。
        // 它贴在信息行下方的右侧，是辅助入口，不该比画面元素更显眼。
        addView(PhoneGlyph(this@buildLandscapeTopControls, PhoneGlyph.Kind.MORE).apply {
            tint = Color.WHITE
        }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        setOnClickListener { openSettings() }
    }
    top.addView(more, android.widget.LinearLayout.LayoutParams(dp(28), dp(28)))
    landscape.addView(top, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
    ).apply {
        // 紧接在常驻的 `landInfoRow`（贴顶 dp(10) + 一行字）下面。
        topMargin = dp(32)
    })
    playerLandTopSlot = top

    /*
     * 横屏里的竖版短剧仍然有左右黑边。这里补一颗「全屏」入口，
     * 点击后切回竖屏播放，让竖版画面真正铺满屏幕。
     *
     * 它压在画面与手势层之上，但在底部控制层之下；位置按视频实际显示范围
     * 和底栏上沿计算，避免像推荐页那样被底栏遮住。
     */
    val fullscreen = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(5), dp(1), dp(5), dp(1))
        background = roundedBackground(0x33FFFFFF, dp(8).toFloat())
        addView(PhoneGlyph(this@buildLandscapeTopControls, PhoneGlyph.Kind.FULLSCREEN).apply {
            tint = Color.WHITE
        }, android.widget.LinearLayout.LayoutParams(dp(10), dp(10)))
        addView(TextView(this@buildLandscapeTopControls).apply {
            text = "全屏"
            setTextColor(Color.WHITE)
            phoneSize(9)
            setPadding(dp(3), 0, 0, 0)
        })
        setOnClickListener { enterPortraitFullscreen() }
        visibility = View.GONE
    }
    landscape.addView(fullscreen, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        Gravity.TOP or Gravity.CENTER_HORIZONTAL
    ).apply {
        topMargin = dp(200)
    })
    playerLandFullscreenSlot = fullscreen
}
