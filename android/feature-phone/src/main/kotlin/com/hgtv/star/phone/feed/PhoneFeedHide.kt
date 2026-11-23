// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.view.View

internal fun PhoneFeedView.onHiddenImpl(handoff: Boolean = false) {
    settleRecommendWatch()
    finishFeedSwipePreviewImpl()
    clearFeedSwipePauseImpl()
    feedPlayer?.takeIf { it.isFeedPlayerAlive() }?.currentPosition?.let(PhoneSession::markPosition)
    // 浮层挂在 Activity 的 decor 上，不随本页 `removeAllViews` 消失：切 tab / 进播放页
    // 之前必须显式收掉，否则它会一直压在别的页面上。
    closeSheet()
    val already = feedHidden
    feedHiddenSlot = true
    feedList.setActive(false)
    feedPaused = true
    feedChromeEpoch++
    feedList.removeCallbacks(feedChromeHideRunnable)
    // 切 tab / 退到后台时作废本页还在飞的 `playIndex`：详情或取地址晚回来时
    // 不能再 `attach`——那一手会把另一条流刚起播的媒体源顶掉，用户看到的就是
    // “切了 tab，画面又跳回上一个 tab 的那部剧”。
    // 交接给播放页（`handoff` / 交接窗口内）**不能**作废：那一路正是播放页要接的。
    if (!handoff && !PhoneSession.inHandoff()) feedPlayTokenSlot++
    if (handoff) PhoneSession.beginHandoff()
    // 记下「离开时是不是在播」：从后台回来 / 从正片退回来时按它决定要不要自动续播。
    // 用户在信息流里主动暂停过，回来时就不该自己动起来。
    // 交接时**不改**这个值：它已经是调用方（openPlayer 等）按意图写好的。
    if (!already && !handoff) {
        PhoneSession.resumeOnAdopt = feedPlayer?.playWhenReady == true
    }
    detachAllFeedListeners()
    val keepPlayer = if (handoff || PhoneSession.inHandoff() ||
        PhoneSession.owner == PhoneSession.Owner.PLAYER
    ) {
        PhoneServices.peekPlayer() ?: feedPlayer
    } else {
        feedPlayer
    }
    pausePooledPlayersExcept(keepPlayer)
    // 只保留当前页播放器；相邻页的独立实例立刻归还解码器与图形内存。
    // 当前页保留到回到前台，避免短暂切 tab / 退后台后重新建播放器。
    releasePooledPlayersExcept(keepPlayer)
    // 交接窗口内也不暂停：同一个切页动作会被通知两次（自己一次、宿主 `onPause` 一次），
    // 第二次进来时 `handoff` 是 false，但这一路已经在往播放页交了，不能按停。
    // 判据再加一条 `owner`：交接窗口只有 3 秒，用户在播放页里看久了再让本页收起来
    //（转横屏、系统回收视图）时窗口早已过期，只看窗口就会把播放页正在放的那一路按停。
    if (!handoff && !PhoneSession.inHandoff() &&
        PhoneSession.owner != PhoneSession.Owner.PLAYER
    ) {
        keepPlayer?.pause()
    }
    // 把「借 Surface」的入口登记下来：正式播放页竖屏时就用这一块出画面，
    // 从横屏退回竖屏也靠它把输出交回来。只登记一次（重复通知会走到这里）。
    //
    // 反过来，**因为切 tab / 退到后台而收起来**时必须注销：那一页已经不在屏幕上了，
    // 再让播放页借它出画面就是一块黑（那一块 Surface 也只是被系统挂起）。
    // 交接窗口内不注销：宿主 `onPause` 会以 `handoff=false` 再通知一次。
    if (handoff && !already) {
        bindFeedSurfaceForHandoffImpl()
    } else if (!handoff && !PhoneSession.inHandoff() &&
        PhoneSession.owner == PhoneSession.Owner.FEED &&
        feedSessionBelongsToThisFeed()
    ) {
        PhoneSession.bindFeedSurface(null)
        PhoneSession.feedVideoOffset = null
        PhoneSession.feedVideoResize = null
        PhoneSession.feedVideoScale = null
        PhoneSession.underlayActive = false
    }
    // ticker 要停：它读的是**进程级播放器**的位置，
    // 而这时播放器可能已经交给播放页在放另一集了——继续跑就会把别的集的位置
    // 记到这一屏的集号上。
    feedProgressTick?.let { feedList.removeCallbacks(it) }
    feedProgressTick = null
    // 预热与停留确认也停掉：隐藏期间发出去的预热会跟播放页抢带宽，
    // 而停留确认本来就是「这一屏还在前台停着」才成立的事。
    feedPreloadScheduler.cancel()
    feedPendingPlan = null
    feedPlaybackBatchState.reset()
    // 交接时**不解绑 Surface**：SurfaceView 的 surface 在窗口切走时会被系统销毁、
    // 切回来时重建，播放器自己会跟上；这里解绑反而要在回来时重新 `setPlayer`，
    // 那一下会先清屏再挂新的，用户看到的是一次闪黑。
    //
    // 判据不能只看方法参数：同一个交接动作会被通知两次——`launchPlayer` 一次
    //（handoff=true）、宿主 `onPause` 再一次（handoff=false）。第二次进来时
    // `handoff` 是 false，但这一路正在往播放页交，照旧不能解绑。早先漏了这一条，
    // 于是「交出 Surface → 播放页再挂一块新的」中间必然空一帧，就是闪黑。
    val handingOff = handoff || PhoneSession.inHandoff() ||
        PhoneSession.owner != PhoneSession.Owner.FEED
    if (!handingOff) PhoneServices.detach(feedBoundPage?.playerView)
    if (!handingOff) schedulePoolIdleRelease() else cancelPoolIdleRelease()
    // boundPage 保留：回来时要把它重新绑上去。
    feedHint.visibility = View.GONE
    notifyPlayStateChangedImpl()
}
