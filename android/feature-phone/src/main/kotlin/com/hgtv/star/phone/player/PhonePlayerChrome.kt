// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.view.View
import android.view.MotionEvent

internal fun PhonePlayerActivity.applyChrome() {
    if (playerLandscapeMode) {
        playerLandTop.visibility = if (playerChromeVisible) View.VISIBLE else View.GONE
        playerLandBottom.visibility = if (playerChromeVisible) View.VISIBLE else View.GONE
        placeLandscapeFullscreenImpl()
    } else {
        if (playerEpisodePagerInitialized) {
            playerEpisodePager.pages().forEach { it.setChromeVisible(playerChromeVisible) }
        } else if (playerPageInitialized) {
            playerPage.setChromeVisible(playerChromeVisible)
        }
        // portraitTop 只装「播放显示」那一行，它的显隐由 syncPlayInfo() 按设置决定：
        // 勾了任意一项就常驻，四项全没勾才跟着控制层收起来。
    }
    if (!playerChromeVisible) hideCenter()
    syncPlayInfoImpl()
}

internal fun PhonePlayerActivity.setChromeVisible(visible: Boolean) {
    if (playerChromeVisible == visible) {
        applyChrome()
        if (visible) scheduleChromeHide()
        return
    }
    playerChromeVisibleSlot = visible
    applyChrome()
    if (visible) {
        scheduleChromeHide()
    } else {
        relatedChromeEpoch++
        if (playerRootInitialized) root.removeCallbacks(relatedChromeHideRunnable)
    }
}

/**
 * 播放控制信息「无操作几秒后自动隐藏」（设置 → 播放界面，2–15 秒）。
 *
 * 与 TV 的 playerPanelTimeout 同一份落盘值：计时从**最后一次操作**算起，
 * 到点后收起播放设置 / 选集面板和整层标题、进度条；任何触摸都会重新显示并重新计时
 * （见 dispatchTouchEvent）。相关剧集浮层是浏览层，不排自动隐藏。
 */
internal fun PhonePlayerActivity.scheduleChromeHide() {
    if (!playerChromeVisible || relatedState.relatedSheetOpen) {
        relatedChromeEpoch++
        if (playerRootInitialized) root.removeCallbacks(relatedChromeHideRunnable)
        return
    }
    val seconds = library?.playerPanelTimeout ?:
        com.hgtv.star.core.AppConfig.Library.PLAYER_PANEL_TIMEOUT_DEFAULT_SECONDS
    relatedChromeEpoch++
    playerChromeHideAt = System.currentTimeMillis() + seconds * 1000L
    root.removeCallbacks(relatedChromeHideRunnable)
    root.postDelayed(relatedChromeHideRunnable, seconds * 1000L)
}

/** 自动收起的时长（毫秒）；设置没读到时按 TV 的出厂值 6 秒。 */
internal fun PhonePlayerActivity.chromeTimeoutMs(): Long =
    ((library?.playerPanelTimeout ?:
        com.hgtv.star.core.AppConfig.Library.PLAYER_PANEL_TIMEOUT_DEFAULT_SECONDS) * 1000L)

/**
 * 面板打开期间，**每一次触摸**都把自动收起的计时往后推。
 *
 * 按下时重新排队；拖动（MOVE）与抬手（UP）只改截止时刻——否则拖一条滑块拖了七八秒，
 * 面板会在手指还按着的时候被收走。MOVE 不重新排队，避免一帧一个 post。
 */
internal fun PhonePlayerActivity.handleChromeTouchEvent(ev: MotionEvent) {
    if (playerChromeVisible && !relatedState.relatedSheetOpen) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> scheduleChromeHide()
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP -> {
                if (playerRootInitialized) {
                    playerChromeHideAt = System.currentTimeMillis() + chromeTimeoutMs()
                    root.removeCallbacks(relatedChromeHideRunnable)
                    root.postDelayed(relatedChromeHideRunnable, chromeTimeoutMs())
                }
            }
        }
    }
}
