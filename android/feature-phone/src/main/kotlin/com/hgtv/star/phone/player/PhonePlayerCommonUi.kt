// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.hgtv.star.shared.ui.LoadingDotsView
import com.hgtv.star.phone.ui.PhoneGlyph
import com.hgtv.star.phone.ui.PhoneStyle
import com.hgtv.star.phone.ui.PhoneSheet
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.phoneSize
import com.hgtv.star.phone.ui.roundedBackground

/** 竖横共用的中心提示、缓冲、下一季提示、播放信息行和底部面板。 */
internal fun PhonePlayerActivity.buildPlayerCommonUi() {
    // 中心播放 / 暂停与上方提示挂在 root 上：竖屏、横屏共用同一份，
    // 切换形态时不用各建一套，也不会出现「转屏后图标留在旧的层里」。
    val glyph = PhoneGlyph(this, PhoneGlyph.Kind.PAUSE).apply { tint = Color.WHITE }
    val center = FrameLayout(this).apply {
        // 只留图标本身（用户要求：调小、不要外面那圈全圆底板）。
        // 34 → 24dp；容器 76 → 48dp 仅用于命中/定位，本身透明。
        addView(glyph, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        visibility = View.GONE
    }
    root.addView(center, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
    playerCenterGlyph = glyph
    playerCenterBox = center

    val dots = LoadingDotsView(this)
    val buffering = FrameLayout(this).apply {
        addView(dots, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        visibility = View.GONE
    }
    root.addView(buffering, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
    playerBufferingDots = dots
    playerBufferingBox = buffering

    hint = TextView(this).apply {
        setTextColor(Color.WHITE)
        phoneSize(13)
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(7), dp(14), dp(7))
        background = roundedBackground(0x88000000.toInt(), dp(14).toFloat())
        visibility = View.GONE
    }
    root.addView(hint, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        Gravity.TOP or Gravity.CENTER_HORIZONTAL
    ).apply { topMargin = dp(24) })

    nextSeasonState.nextSeasonNotice = TextView(this).apply {
        setTextColor(PhoneStyle.playerText)
        phoneSize(13)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = roundedBackground(0xCC000000.toInt(), dp(10).toFloat())
        visibility = View.GONE
    }
    root.addView(nextSeasonState.nextSeasonNotice, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        Gravity.BOTTOM or Gravity.START
    ).apply {
        leftMargin = dp(16)
        rightMargin = dp(24)
        bottomMargin = dp(24)
    })

    // 「播放显示」那一行拆成左右两半：左边是剧名 + 集数（靠左、过长省略），
    // 右边是网速 + 当前时间（贴右）。早先四项拼成一条左对齐的读数，
    // 开了时间和网速之后整条往右挤，剧名反而被省略号吃掉。
    val left = TextView(this).apply {
        setTextColor(Color.WHITE)
        phoneSize(14)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(dp(4), 0, 0, 0)
        // 这一行直接压在画面上（没有底板），亮画面里白字会糊：补一层细阴影。
        setShadowLayer(2f, 0f, 1f, 0x99000000.toInt())
    }
    val right = TextView(this).apply {
        setTextColor(Color.WHITE)
        phoneSize(13)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(dp(10), 0, 0, 0)
        setShadowLayer(2f, 0f, 1f, 0x99000000.toInt())
    }
    val row = android.widget.LinearLayout(this).apply {
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
    playerPortraitTop.addView(row, android.widget.LinearLayout.LayoutParams(
        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
    ))
    playerInfoLeftSlot = left
    playerInfoRightSlot = right
    playerInfoRowSlot = row

    val sheet = PhoneSheet(root).apply {
        // **不再给系统导航条留白**（用户规范）：面板贴到屏幕最底边。
        // 点空白处 / 点「关闭」= 「让开，我要看画面」：面板与控制层一起收掉
        // （与电视端菜单打开时收起 HUD、关闭后画面干净同一条规则）。
        // 换集、换清晰度那种程序性收起不走这里，标题与进度条仍然留着。
        onUserDismiss = { setChromeVisible(false) }
        onDismiss = {
            relatedState.relatedSheetOpen = false
            // 关掉相关剧集 / 设置后，从这一刻重新计时；相关剧集作为浏览层
            // 本身不会被自动收起，但“看完这一层”之后仍应回到自动收起。
            if (playerChromeVisibleSlot && rootReady) scheduleChromeHide()
        }
    }
    playerSheetSlot = sheet
}
