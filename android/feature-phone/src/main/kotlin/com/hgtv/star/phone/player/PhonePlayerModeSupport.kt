// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.view.View
import com.hgtv.star.core.VideoFrameMode
import com.hgtv.star.core.toFit
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.resizeMode
import com.hgtv.star.shared.media.DanmakuView

internal const val PHONE_PORTRAIT_DANMAKU_TOP_GAP_DP=16f

/**
 * 手机竖屏“顶部信息栏”和弹幕共用的落点。
 *
 * 正式播放页与推荐流顶部栏高度相同，但过去推荐流用文字锚点、正式页用
 * `topInset + topGap`，两边首行位置不一致。统一走这一处后：
 * 轨道顶边 = 顶栏底边 + 8dp，文字再吃 DanmakuView 自己的 16dp 呼吸位。
 */
internal fun DanmakuView.alignBelowPhonePortraitChrome(bottomPx: Int, infoGapPx: Int) {
    topTextAnchorPx=null
    topGapDp=PHONE_PORTRAIT_DANMAKU_TOP_GAP_DP
    topInsetPx=bottomPx.coerceAtLeast(0)+infoGapPx.coerceAtLeast(0)
}

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
 * 手机弹幕从顶部播放信息下方开始；电视不设置动态避让，使用共享层的统一顶部呼吸位。
 */
internal fun PhonePlayerActivity.syncDanmakuTopInset() {
    if (!playerPageInitialized) return
    if (playerLandscapeMode) {
        if (!playerLandInfoRowInitialized || !playerLandDanmakuInitialized) return
        if (playerLandInfoRowBottom > 0) {
            playerLandDanmaku.topInsetPx = playerLandInfoRowBottom + dp(2)
        }
        return
    }
    if (playerPortraitTopBottom <= 0) return
    playerPage.danmakuView.alignBelowPhonePortraitChrome(
        bottomPx=playerPortraitTopBottom,
        infoGapPx=dp(8),
    )
}

/** 横屏也按用户的「画面模式」落画面，不按片源方向强制放大。 */
internal fun PhonePlayerActivity.applyLandscapeResize() {
    // 竖屏片源不再因为方向被强制 ZOOM：那会把 9:16 的画面裁成中间一条，
    // 换集时尤其明显。用户想铺满可以自己选「等比铺满」。
    playerLandscapeVideo.resizeMode =
        (library?.frameMode ?: VideoFrameMode.FIT).toFit().resizeMode()
    syncLandscapeLetterboxImpl()
}
