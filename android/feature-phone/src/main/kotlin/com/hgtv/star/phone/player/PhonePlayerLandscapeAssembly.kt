// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.SeekBar
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.PlayerView
import com.hgtv.star.core.DanmakuMode
import com.hgtv.star.core.PlaybackSpeed
import com.hgtv.star.phone.ui.PhoneTouch
import com.hgtv.star.phone.ui.PhoneGlyph
import com.hgtv.star.phone.ui.PhoneStyle
import com.hgtv.star.phone.ui.roundedBackground
import com.hgtv.star.phone.ui.bottomScrim
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
        PhonePlayerViewSupport.useOpaqueVideoSurface(this)
    }
    landscape.addView(video, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    ))

    // 弹幕压在画面之上、手势层之下（与竖屏 `PhoneVideoPage` 里的叠放顺序一致）。
    val danmaku = com.hgtv.star.shared.media.DanmakuView(this).apply {
        mode = DanmakuMode.DEFAULT
        minTextSizeDp = 4f
        topGapDp = 0f
        // 与竖屏那一层同一个基准字号（见 `applyDanmakuSettings`）。
        sizeFollowsLongEdge = true
        setClock(0, false)
    }
    landscape.addView(danmaku, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    ))
    // 弹幕可见时黑边在弹幕 Surface 内绘制；这层普通 View 只在弹幕不可见时兜底。
    val letterbox = PhoneLetterboxMask(this)
    landscape.addView(letterbox, FrameLayout.LayoutParams(
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
    playerLandLetterboxSlot = letterbox
    danmaku.onRenderStateChangedListener = { syncLandscapeLetterboxImpl() }
    video.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        if (playerLandscapeVideoInitialized && playerLandLetterboxInitialized) {
            syncLandscapeLetterboxImpl()
        }
    }
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

/** 横屏底栏：播放 / 进度、常驻摘要和快捷入口。 */
internal fun PhonePlayerActivity.buildLandscapeBottomControls() {
    // 底部两行
    val bottom = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(12))
        background = bottomScrim(215)
    }

    val line1 = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val play = PhoneGlyph(this, PhoneGlyph.Kind.PAUSE).apply { tint = Color.WHITE }
    val playBox = FrameLayout(this).apply {
        addView(play, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        setOnClickListener { togglePlay() }
    }
    line1.addView(playBox, android.widget.LinearLayout.LayoutParams(dp(34), dp(34)))

    val current = TextView(this).apply {
        setTextColor(PhoneStyle.playerText)
        phoneSize(12)
        text = "00:00"
        setPadding(0, 0, dp(8), 0)
    }
    line1.addView(current)

    val seek = SeekBar(this).apply {
        max = 1000
        PhonePlayerViewSupport.tintSeekBar(this)
        setPadding(0, 0, 0, 0)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                val duration = player?.duration ?: 0L
                if (duration > 0) current.text = debugClock(duration * value / 1000)
            }

            override fun onStartTrackingTouch(sb: SeekBar) { playerDragging = true }

            override fun onStopTrackingTouch(sb: SeekBar) {
                playerDragging = false
                val duration = player?.duration ?: 0L
                if (duration > 0) player?.seekTo(duration * sb.progress / 1000)
            }
        })
    }
    line1.addView(seek, android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

    val total = TextView(this).apply {
        setTextColor(PhoneStyle.playerDim)
        phoneSize(12)
        text = "00:00"
        setPadding(dp(8), 0, 0, 0)
    }
    line1.addView(total)
    bottom.addView(line1, android.widget.LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ))

    // 进度条下面**只有一行**：左边是常驻摘要，右边是四颗入口。
    //
    // 早先是两行（摘要独占一行 + 四颗按钮另起一行），在 1080 高的横屏上，
    // 底栏加起来吃掉 150px 画面，而且两行之间没有视觉联系——用户看到的是
    // 「一堆字」和「一排按钮」两块互不相干的东西。并成一行之后：
    //
    // ```
    // ─────────────●──────────────────────────   ← 进度条
    // 完整画面 · 缩放100% · 硬件解码   [1.0x][1080P][弹幕][选集 1]
    // ```
    //
    // 摘要只写**四颗按钮上没有的东西**（画面 / 缩放 / 解码 / 定时），
    // 清晰度、倍速、选集、弹幕已经由右侧按钮自己显示当前值，
    // 再在左边重复一遍既是冗余、也会把这一行挤到省略号。
    val line2 = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, 0)
    }
    val settingsInfo = TextView(this).apply {
        setTextColor(PhoneStyle.playerDim)
        phoneSize(11)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(dp(2), 0, dp(10), 0)
        text = ""
    }
    line2.addView(settingsInfo, android.widget.LinearLayout.LayoutParams(
        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
    ))

    // 倍速与弹幕一样，点一次就换下一档；要细调仍可长按画面中间打开播放设置。
    val speed = PhonePlayerViewSupport.chip(this, PlaybackSpeed.label(sessionUserSpeed)) { cycleSpeed() }
    val quality = PhonePlayerViewSupport.chip(this, "1080P") { openSettings() }
    // 弹幕快捷开关（与电视播放页底部那颗「弹幕 全屏」同一职责）：
    // 电视上是遥控器一按就开菜单；手机上一次点击直接切下一档更省事，
    // 想细调（速度 / 字号 / 透明度）仍然长按中间开播放设置。
    val danmaku = PhonePlayerViewSupport.chip(this, "弹幕") { cycleDanmaku() }
    val episode = PhonePlayerViewSupport.chip(this, "选集 1") { openEpisodes() }
    line2.addView(speed, PhonePlayerViewSupport.chipLp(this,0))
    line2.addView(quality, PhonePlayerViewSupport.chipLp(this,6))
    line2.addView(danmaku, PhonePlayerViewSupport.chipLp(this,6))
    line2.addView(episode, PhonePlayerViewSupport.chipLp(this,6))
    bottom.addView(line2, android.widget.LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ))

    val landscape = playerLandscapeContainer
    landscape.addView(bottom, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
    ))
    landscape.visibility = View.GONE
    root.addView(landscape, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    ))
    playerLandBottomSlot = bottom
    playerLandPlaySlot = play
    progressLandCurrentSlot = current
    progressLandSeekSlot = seek
    progressLandTotalSlot = total
    playerLandSettingsInfoSlot = settingsInfo
    chipSpeedSlot = speed
    chipQualitySlot = quality
    chipDanmakuSlot = danmaku
    chipEpisodeSlot = episode
}
