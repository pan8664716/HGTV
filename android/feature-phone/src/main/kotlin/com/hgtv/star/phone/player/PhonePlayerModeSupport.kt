// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.view.View
import com.hgtv.star.core.VideoFrameMode
import com.hgtv.star.core.toFit
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.resizeMode

/**
 * 新形态那一层的入场过渡：淡入 + 从 97% 放到原尺寸。
 *
 * 为什么需要它：播放页声明了 `configChanges=orientation`，转屏**不重建 Activity**，
 * 系统因此也不给任何窗口转场——applyMode() 一执行，竖屏那一整套就换成横屏那一整套，
 * 中间一帧过渡都没有，读起来就是「啪」一下。
 *
 * 为什么缩放只给 3%：横屏那一层里是 PlayerView（SurfaceView），放大倍数一大，
 * 边缘会露出黑边、部分机型上还会闪一下。这里靠淡入撑过渡，缩放只做一点点「推进感」。
 */
internal fun PhonePlayerActivity.animateModeEnter(view: View) {
    view.animate().cancel()
    view.alpha = 0f
    view.scaleX = 0.97f
    view.scaleY = 0.97f
    view.animate().alpha(1f).scaleX(1f).scaleY(1f)
        .setDuration(PhonePlayerActivity.MODE_TRANSITION_MS)
        .setInterpolator(android.view.animation.DecelerateInterpolator())
        .start()
}

/**
 * 把竖屏弹幕的顶部起点对到**顶部标题栏下沿**。
 *
 * DanmakuView 默认让开 104dp，那是照电视横屏的标题条（96dp）定的；竖屏播放页的
 * 标题栏按 phoneScale 算，和这个数对不上，滚动弹幕就会贴着屏幕顶边、压在剧名上。
 * 这里用实测的标题栏下沿，再留 8dp 呼吸位；横屏照旧交回默认值（那边标题条本来
 * 就是 96dp 那一档）。
 */
internal fun PhonePlayerActivity.syncDanmakuTopInset() {
    if (!playerPageInitialized) return
    if (playerLandscapeMode) {
        // 横屏：让开顶部那条「播放显示」（landInfoRow）。它的下沿要实测——
        // 字号跟着 PhoneUiScale 走，写死一个 dp 值在放大档上会被弹幕压住。
        if (!playerLandInfoRowInitialized || !playerLandDanmakuInitialized) return
        // 只留一点点缝（用户要求）：8dp 在 1080P 上是 24px，加上弹幕轨道自身的
        // 行内留白，第一行弹幕离信息行看着像隔了一整行。2dp 就够。
        if (playerLandInfoRowBottom > 0) {
            playerLandDanmaku.topInsetPx = playerLandInfoRowBottom + dp(2)
        }
        return
    }
    if (playerPortraitTopBottom <= 0) return
    playerPage.danmakuView.topInsetPx = playerPortraitTopBottom + dp(8)
}

/** 横屏也按用户的「画面模式」落画面，不按片源方向强制放大。 */
internal fun PhonePlayerActivity.applyLandscapeResize() {
    // 竖屏片源不再因为方向被强制 ZOOM：那会把 9:16 的画面裁成中间一条，
    // 换集时尤其明显。用户想铺满可以自己选「等比铺满」。
    playerLandscapeVideo.resizeMode =
        (library?.frameMode ?: VideoFrameMode.FIT).toFit().resizeMode()
}
