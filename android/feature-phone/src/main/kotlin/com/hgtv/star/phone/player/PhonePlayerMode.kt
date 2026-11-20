// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.graphics.Color
import android.view.View

internal fun PhonePlayerActivity.applyMode() {
    val frameBaseline = lastFrameAt
    // 竖屏与横屏 ViewPager2 使用不同 Surface；切形态前先摘掉相邻集输出，
    // 等新形态布局完成后统一重挂，避免旧 Surface 被 GONE 后预渲染卡在“已开始”状态。
    pauseAdjacentPlayers()
    if (playerEpisodePagerInitialized) playerEpisodePager.setActive(!playerLandscapeMode)
    if (playerLandscapePagerInitialized) playerLandscapePager.setActive(playerLandscapeMode)
    // 形态一变，面板的宽度与位置就都不对了（横屏是右侧 1/3 抽屉，竖屏是底部整宽），
    // 直接收起，避免留一块尺寸错误的浮层。
    playerSheet.dismiss()
    dismissLandscapeSettingsImpl()
    swipeFeedVideoDy = 0f
    PhoneSession.setFeedVideoOffset(0f)
    if (playerLandscapeMode) {
        playerPortrait.visibility = View.GONE
        playerLandscape.visibility = View.VISIBLE
        // 横屏是一整块不透明黑：下层推荐页被完全盖住，控制层收不收都一样。
        root.setBackgroundColor(Color.BLACK)
        // 一旦进入横屏，本次播放不再借推荐页 Surface。退回竖屏时继续借会把本页 root
        // 再次设为透明，推荐页的标题、导航和弹幕就可能从画面背后透出来。
        dropUnderlay()
        // PlayerView.setPlayer(null) 会清掉它自己绑过的视频 Surface。
        // 必须先解绑旧画面，再挂新画面；反序时旧 View 会把刚挂上的新 Surface
        // 又清掉，解码器随后报 -32，画面就停在切换前的一帧。
        // 竖屏这一层在横屏期间不出画面，而且尺寸马上会从竖屏变横屏。
        // 保留最后一帧的话，回竖屏时那帧旧 buffer 会残留一块在新尺寸的 Surface 上——
        // 用户截图里「一块画面卡在屏幕上」就是这个。先关掉再解绑，它才会清屏。
        val portraitView = if (playerEpisodePagerInitialized) {
            playerEpisodePager.pageAt(index)?.playerView ?: playerPage.playerView
        } else {
            playerPage.playerView
        }
        portraitView.setKeepContentOnPlayerReset(false)
        portraitView.player = null
        portraitView.setKeepContentOnPlayerReset(true)
        // 横屏这一层此后要长期出画面：换集时保留上一帧，避免闪黑。
        selectLandscapePagerEpisode(index)
        playerLandscapeVideo.setKeepContentOnPlayerReset(true)
        scheduleCurrentPlayerSurfaceRebind(frameBaseline)
        // 输出被横屏这一层拿走了：推荐页那边不能再认为「Surface 一直在」。
        PhoneSession.underlayActive = false
        playerUnderlayBound = false
        playerUnderlayNeedsForce = false
        applyLandscapeResize()
        enterImmersive()
    } else {
        playerLandscape.visibility = View.GONE
        playerPortrait.visibility = View.VISIBLE
        // 横屏那一层即将隐藏：先关掉「保留最后一帧」，再解绑。
        // 否则它的 Surface 会带着横屏尺寸的旧帧一起留在窗口里（退出全屏后那块残留）。
        playerLandscapeVideo.setKeepContentOnPlayerReset(false)
        playerLandscapeVideo.player = null
        playerLandscapeVideo.setKeepContentOnPlayerReset(true)
        // 旧输出清完后再挂竖屏要用的那一块。借下层时挂的是推荐页那块
        // Surface（attachUnderlay）；否则直接挂本页 PlayerView。
        val attached = playerFeedUnderlay && attachUnderlay()
        if (attached) {
            // 借下层出画面：本页底板与视频层都透明 / 隐藏。
            root.setBackgroundColor(Color.TRANSPARENT)
        } else {
            // 下层接不上（推荐页被收走、地址过期）：退回到本页自己出画面。
            dropUnderlay()
            // 不再借下层 Surface 时必须恢复整页不透明，避免透明窗口继续透出推荐页。
            root.setBackgroundColor(Color.BLACK)
            if (player != null) scheduleCurrentPlayerSurfaceRebind(frameBaseline)
        }
        // 竖屏同样进沉浸式：**状态栏不占位、不显示**。
        // 早先这里走 exitImmersive()，顶部留着一条状态栏，竖屏短剧的画面被压下去一截，
        // 读到的就是「竖屏播放还挂着状态栏」。导航栏一起藏掉，底部控制层才能贴满屏底。
        enterImmersive()
    }
    playerChromeVisibleSlot = true
    applyVideoScaleImpl()
    // 刚切形态时两个容器可能还没量出尺寸（宽高为 0），按 0 算会让画面变成 1px。
    // 等这一帧布局完再按真实尺寸算一次。
    root.post { applyVideoScaleImpl() }
    // 退出全屏时竖屏容器可能到这一帧才量出新尺寸：再补一次，避免缩放按横屏尺寸算。
    root.postDelayed({ if (!playerLandscapeMode) applyVideoScaleImpl() }, 150L)
    applyChromeForSwipe()
    placeLandscapeFullscreenImpl()
    scheduleChromeHideForMode()
    syncChips()
    syncDanmakuTopInset()
    // 竖屏 / 全屏是两个弹幕层：把这一集已经拉到的数据接到新那一层上，
    // 否则一点全屏就「弹幕没了」（见 replayDanmaku）。
    replayDanmaku()
    rebindAdjacentGhostForMode()
    scheduleAdjacentEpisodeWarmup()
    // 形态切换的过渡：系统在同一个窗口里换布局是没有动画的（configChanges 让
    // Activity 不重建），不加这一层就是「啪」一下换掉。放在所有布局设置之后。
    animateModeEnter(if (playerLandscapeMode) playerLandscape else playerPortrait)
    root.post {
        syncDanmakuTopInset()
        replayDanmaku()
        placeLandscapeFullscreenImpl()
    }
}
