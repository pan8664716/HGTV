// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.hgtv.star.phone.ui.dp

internal fun PhonePlayerActivity.buildPortrait() {
    playerPortrait = FrameLayout(this)
    // 两页：ghost 在下、当前页在上。拖动时两页一起平移，滑到位后交换引用。
    swipeGhostPage = makePage()
    swipePlayerPage = makePage()
    playerPortrait.addView(swipeGhostPage, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    ))
    playerPortrait.addView(playerPage, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    ))
    swipeGhostPage.visibility = View.GONE
    wirePage(playerPage)
    wirePage(swipeGhostPage)

    playerPortraitTop = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(12), 0)
    }
    playerPortrait.addView(playerPortraitTop, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // 沉浸式：状态栏不占位，只留一小段呼吸位（早先是 statusBar() + dp(6)）。
        topMargin = dp(12)
    })
    playerPortraitTop.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
        if (bottom - top > 0) {
            playerPortraitTopBottom = bottom
            syncDanmakuTopInset()
        }
    }
    root.addView(playerPortrait, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    ))
}
