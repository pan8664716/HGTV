// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.hgtv.star.phone.ui.PHONE_TOP_BAR_IMMERSIVE_TOP_DP
import com.hgtv.star.phone.ui.PHONE_TOP_BAR_PORTRAIT_ROW_HEIGHT_DP
import com.hgtv.star.phone.ui.dp

internal fun PhonePlayerActivity.buildPortrait() {
    playerPortrait = FrameLayout(this)
    swipePlayerPage = makePage()
    wirePage(playerPage)
    playerEpisodePager = VerticalEpisodePager(this)
    playerEpisodePagerAdapter = PhonePlayerPagerAdapter(this, playerPage)
    playerEpisodePager.adapter = playerEpisodePagerAdapter
    installPlayerPagerCallbacks()
    playerPortrait.addView(playerEpisodePager, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    ))

    playerPortraitTop = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(12), 0)
    }
    playerPortrait.addView(playerPortraitTop, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        dp(PHONE_TOP_BAR_PORTRAIT_ROW_HEIGHT_DP),
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // 与推荐页 / 分类页的沉浸式体裁栏共用同一条中心线：
        // 10dp 顶部留白 + 34dp 主行，播放信息在其中垂直居中。
        topMargin = dp(PHONE_TOP_BAR_IMMERSIVE_TOP_DP)
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
