// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import com.hgtv.star.phone.ui.PhoneStyle
import com.hgtv.star.phone.ui.phoneEdgeToEdge

internal fun PhonePlayerActivity.initializePlayerActivity(tEnter: Long) {
    // 主题：播放页可能被系统单独拉起（最近任务 / 深链），不保证经过主页面。
    PhoneStyle.ensure(this)
    phoneEdgeToEdge()
    val seriesId = intent.getStringExtra(PhonePlayerActivity.EXTRA_SERIES_ID).orEmpty()
    if (seriesId.isBlank()) {
        finish()
        return
    }
    PhoneSession.playerForeground = true

    listenerPendingFullscreen =
        intent.getBooleanExtra(PhonePlayerActivity.EXTRA_FULLSCREEN, false)
    playerLandscapeModeSlot =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    // **播放页不跟随手机姿态**：进来就锁竖屏，只有用户点「全屏」按钮才转横屏
    // （那个入口带 EXTRA_FULLSCREEN，下面自己会锁横屏）。
    //
    // 方向交给系统策略（USER）：**跟着系统的「自动旋转屏幕」开关走**。
    // 开着 → 竖着拿就是竖屏、横着拿就是全屏；关着 → 停在进入时的方向，
    // 只有用户点「全屏」才转（见 enterFullscreen）。
    // pendingFullscreen 是用户从推荐页点全屏进来的，拿到实际片源尺寸后再决定全屏方向。
    if (!listenerPendingFullscreen) {
        requestOrientation(ActivityInfo.SCREEN_ORIENTATION_USER)
    }
    // 全屏是明确的「把画面独立出来」：不借下层推荐页的 Surface。
    // 下层可能已经停在下一页，或因为转屏换了布局；继续借会透出另一条剧，
    // 甚至让两个界面抢同一个视频输出。全屏路径始终使用本页自己的 PlayerView。
    playerFeedUnderlaySlot =
        !listenerPendingFullscreen && canBorrowFeedSurface(seriesId)

    buildUi()
    val tUi = android.os.SystemClock.elapsedRealtime()
    // 借画面时把下层的标题、进度条、导航栏收起来：本页自己画一份。
    if (playerFeedUnderlay) {
        playerFeedChromeHeld = true
        PhoneSession.hideFeedChrome()
    }
    load(seriesId)
    if (com.hgtv.star.shared.BuildConfig.DEBUG) {
        // 进场耗时分段。用户在「推荐 → 正式」这一步读到的那一下卡顿就是这里的时间：
        // ui 是建整页（四页视频 + 面板），chrome 是收起下层控制层，
        // 两个都跑在主线程上，期间下层那块 Surface 的合成会被一起拖住。
        android.util.Log.i(
            "HGTV-PHONE",
            "enter timing ui=${tUi - tEnter}ms chrome=${android.os.SystemClock.elapsedRealtime() - tUi}ms " +
                "underlay=$playerFeedUnderlay at=$tEnter shown=${android.os.SystemClock.elapsedRealtime()}"
        )
    }
}

/**
 * 只有播放页要放的集与会话里的集一致时，才借推荐页的 Surface。
 *
 * 集号不一致时下层画面已经不代表本页内容。部分手机 / 平板上，透明播放页
 * 无法稳定合成下层 SurfaceView，表现就是声音正常、画面全黑；这种情况下
 * 必须从建页开始就使用本页自己的 PlayerView。
 */
private fun PhonePlayerActivity.canBorrowFeedSurface(seriesId: String): Boolean {
    if (Build.VERSION.SDK_INT < 28) return false
    if (!intent.getBooleanExtra(PhonePlayerActivity.EXTRA_RECOMMEND, false)) return false
    if (!PhoneSession.feedUnderlayUsable(seriesId)) return false
    val wanted = intent.getIntExtra(PhonePlayerActivity.EXTRA_EPISODE, -1)
    return wanted < 0 || wanted == PhoneSession.index
}
