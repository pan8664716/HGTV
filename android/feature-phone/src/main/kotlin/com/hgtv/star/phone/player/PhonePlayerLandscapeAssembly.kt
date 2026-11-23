// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.hgtv.star.shared.ui.UiText
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.SeekBar
import com.hgtv.star.core.PlaybackSpeed
import com.hgtv.star.phone.ui.PhoneGlyph
import com.hgtv.star.phone.ui.PhoneStyle
import com.hgtv.star.phone.ui.roundedBackground
import com.hgtv.star.phone.ui.bottomScrim
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.phoneSize

/** 横屏装配：相邻集页、视频、弹幕和透明手势层。 */
internal fun PhonePlayerActivity.buildLandscapeBase() {
    val landscape = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
    val initialPage = LandscapePlayerPage(this)
    playerLandscapePager = VerticalLandscapePager(this)
    playerLandscapePagerAdapter = LandscapePlayerPagerAdapter(
        this,
        initialPage,
    )
    playerLandscapePager.adapter = playerLandscapePagerAdapter
    installLandscapePlayerPagerCallbacks()
    landscape.addView(playerLandscapePager, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    ))
    playerLandscapeContainer = landscape
    playerLandscapeVideoSlot = initialPage.video
    playerLandDanmakuSlot = initialPage.danmaku

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
    top.addView(more, android.widget.LinearLayout.LayoutParams(dp(24), dp(24)))
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
            text = UiText.get(com.hgtv.star.shared.R.string.ui_0031)
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

/** 横屏底栏：进度条、播放设置摘要、播放控制栏与快捷入口。 */
internal fun PhonePlayerActivity.buildLandscapeBottomControls() {
    // 底部三行（自上而下）：进度条 → 设置摘要 → 控制栏 + 功能按钮
    val bottom = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding(dp(16), dp(2), dp(16), dp(4))
        background = bottomScrim(215)
    }

    // ── 第一行：进度条（不再放播放键，播放控制统一挪到下面的控制栏）──
    val line1 = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

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

            override fun onStartTrackingTouch(sb: SeekBar) {
                if (!playerControlsLocked) playerDragging = true
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                playerDragging = false
                if (playerControlsLocked) return
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

    // ── 第二行：播放设置摘要，紧贴进度条下方（画面 / 缩放 / 解码 / 定时等）──
    //
    // 早先它和右侧四颗按钮挤在同一行，为了给按钮腾位置只能写一半就省略号。
    // 单独一行之后可以完整显示，控制栏也才有横向空间。
    val settingsInfo = TextView(this).apply {
        setTextColor(PhoneStyle.playerDim)
        phoneSize(11)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(dp(2), dp(2), 0, dp(1))
        text = ""
    }
    bottom.addView(settingsInfo, android.widget.LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ))

    // ── 第三行：播放控制栏（左）+ 倍速 / 画质 / 弹幕 / 选集 / 锁定（右）──
    val line2 = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /**
     * 控制栏按钮：图标居中放在一个方形热区里，点击区域比图标本身大，横屏下更好按。
     */
    fun transportButton(
        kind: PhoneGlyph.Kind,
        boxDp: Int,
        iconDp: Int,
        onClick: () -> Unit,
    ): Pair<FrameLayout, PhoneGlyph> {
        val glyph = PhoneGlyph(this, kind).apply { tint = Color.WHITE }
        val box = FrameLayout(this).apply {
            addView(glyph, FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER))
            setOnClickListener { if (!playerControlsLocked) onClick() }
        }
        return box to glyph
    }

    val (prevBox, _) = transportButton(PhoneGlyph.Kind.PREV_EPISODE, 20, 10) {
        val count = detail?.episodes?.size ?: 0
        if (index > 0 && index - 1 < count) play(index - 1, 0L, clearDanmakuOnSwitch = true)
    }
    val (rewBox, _) = transportButton(PhoneGlyph.Kind.REWIND, 20, 10) {
        seekBy(-10_000L)
    }
    val (playBox, playGlyph) = transportButton(PhoneGlyph.Kind.PAUSE, 24, 12) {
        togglePlay()
    }
    val (ffwdBox, _) = transportButton(PhoneGlyph.Kind.FORWARD, 20, 10) {
        seekBy(10_000L)
    }
    val (nextBox, _) = transportButton(PhoneGlyph.Kind.NEXT_EPISODE, 20, 10) {
        val count = detail?.episodes?.size ?: 0
        if (index >= 0 && index + 1 < count) play(index + 1, 0L, clearDanmakuOnSwitch = true)
    }
    line2.addView(prevBox, android.widget.LinearLayout.LayoutParams(dp(20), dp(20)))
    line2.addView(rewBox, android.widget.LinearLayout.LayoutParams(dp(20), dp(20)))
    line2.addView(playBox, android.widget.LinearLayout.LayoutParams(dp(24), dp(24)))
    line2.addView(ffwdBox, android.widget.LinearLayout.LayoutParams(dp(20), dp(20)))
    line2.addView(nextBox, android.widget.LinearLayout.LayoutParams(dp(20), dp(20)))

    // 中间弹性空隙：控制栏贴左，功能按钮贴右。
    line2.addView(android.view.View(this), android.widget.LinearLayout.LayoutParams(
        0, 1, 1f
    ))

    // 倍速与弹幕一样，点一次就换下一档；要细调仍可长按画面中间打开播放设置。
    val speed = PhonePlayerViewSupport.chip(this, PlaybackSpeed.label(sessionUserSpeed)) { cycleSpeed() }
    val quality = PhonePlayerViewSupport.chip(this, "1080P") { openSettings() }
    // 弹幕快捷开关（与电视播放页底部那颗「弹幕 全屏」同一职责）：
    // 电视上是遥控器一按就开菜单；手机上一次点击直接切下一档更省事，
    // 想细调（速度 / 字号 / 透明度）仍然长按中间开播放设置。
    val danmaku = PhonePlayerViewSupport.chip(this, "弹幕") { cycleDanmaku() }
    val episode = PhonePlayerViewSupport.chip(this, "选集") { openEpisodes() }
    val (lock, lockGlyph) = PhonePlayerViewSupport.lockButton(
        this,
        PhonePlayerViewSupport.LANDSCAPE_CHIP_HEIGHT_DP.toFloat(),
    ) {
        togglePlaybackLock()
    }
    line2.addView(speed, PhonePlayerViewSupport.chipLp(this, 0))
    line2.addView(quality, PhonePlayerViewSupport.chipLp(this, 6))
    line2.addView(danmaku, PhonePlayerViewSupport.chipLp(this, 6))
    line2.addView(episode, PhonePlayerViewSupport.chipLp(this, 6))
    line2.addView(lock, android.widget.LinearLayout.LayoutParams(
        dp(PhonePlayerViewSupport.LANDSCAPE_CHIP_HEIGHT_DP),
        dp(PhonePlayerViewSupport.LANDSCAPE_CHIP_HEIGHT_DP),
    ).apply { leftMargin = dp(6) })
    bottom.addView(line2, android.widget.LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(2) })

    val landscape = playerLandscapeContainer
    landscape.addView(bottom, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
    ))
    landscape.visibility = View.GONE
    root.addView(landscape, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    ))
    playerLandBottomSlot = bottom
    // 播放 / 暂停图标现在在控制栏里，`syncPlayGlyph` 更新的是这一颗。
    playerLandPlaySlot = playGlyph
    progressLandCurrentSlot = current
    progressLandSeekSlot = seek
    progressLandTotalSlot = total
    playerLandSettingsInfoSlot = settingsInfo
    chipSpeedSlot = speed
    chipQualitySlot = quality
    chipDanmakuSlot = danmaku
    chipEpisodeSlot = episode
    playerLandLockSlot = lock
    playerLandLockGlyphSlot = lockGlyph
}

/**
 * 控制条的快退 / 快进（横竖屏共用）：按 10 秒步进，夹在 `[0, duration]` 之间。
 *
 * 不用 `seekTo` 的拖拽路径：那里要经过进度条回调与 `playerDragging` 状态，
 * 按钮点击是离散动作，直接定位更稳。
 */
internal fun PhonePlayerActivity.seekBy(deltaMs: Long) {
    val exo = player ?: return
    val duration = runCatching { exo.duration }.getOrDefault(0L)
    if (duration <= 0L) return
    val target = (exo.currentPosition + deltaMs).coerceIn(0L, duration)
    runCatching { exo.seekTo(target) }
    syncProgressBarsNow()
}
