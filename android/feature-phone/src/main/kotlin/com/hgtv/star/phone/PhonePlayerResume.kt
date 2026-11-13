// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

internal fun PhonePlayerActivity.handlePlayerResume() {
    PhoneSession.playerForeground = true
    playerForeground = true
    // 网络恢复时立刻重取地址（与电视端 refetchAddressOnNetwork 同一条）：
    // 手机切 Wi-Fi / 5G 比电视频繁，断网那一下正在取地址的话不该干等退避计时。
    watchNetwork()
    // 上面又压了一层播放页（相关剧集）时，那一层会把自己的视频层挂到同一台播放器上，
    // 借来的这块 Surface 就不是输出端了。回到本页必须把输出抢回来，否则是一片黑。
    restoreUnderlayIfNeeded()
    // 设置页可能刚改过弹幕：回到本页重刷一次，不等下一次换集。
    applyDanmakuSettings()
    // 每秒对齐一次弹幕时钟（见 danmakuTick）。
    root.removeCallbacks(playerDanmakuTick)
    root.post(playerDanmakuTick)
    // 播放显示四项也可能刚在设置页改过：回到本页立刻按新开关重排顶部信息。
    syncPlayInfoForLifecycle()
    // 回到前台：重新装看门狗（暂停过的那一段不该计入「等画面」的时间）。
    if (!isFinishing && !isDestroyed) armPictureWatchdog()
    // 回到前台：把会话重新激活并立刻对一次状态，锁屏上那一条不会停在「已暂停」。
    syncMediaSession()
    syncDebugOverlayForLifecycle()
    syncKeepScreenOn()
    if (playerChromeVisible) scheduleChromeHide()
}
