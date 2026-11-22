// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Player

/** adoptFromSession 的前置检查；返回 null = 可以接，否则返回不能接的原因（只用于日志）。 */
private fun PhonePlayerActivity.adoptReject(seriesId: String): String? {
    // 只看「这一路还活着」：地址过期不等于接不上。切页只换 Surface，媒体源与解码器
    // 一直在跑，接上去立刻出帧；地址由 reopenCurrentEpisodeInPlace 原地换一条，
    // 位置不动。早先这里按「地址新鲜」判，于是暂停久了再进来就被判成接不上，
    // 走的是重取详情 → 重取地址 → 重新 prepare，用户读到的就是「进播放页重新加载」。
    if (!PhoneSession.live(seriesId)) {
        return when {
            PhoneSession.remote == null -> "no-remote"
            PhoneSession.seriesId != seriesId -> "other-series"
            else -> "no-session"
        }
    }
    val d = PhoneSession.detail ?: return "no-detail"
    if (PhoneSession.remote == null) return "no-remote"
    val sessionIndex = PhoneSession.index
    if (sessionIndex !in d.episodes.indices) return "bad-index"
    val wanted = intent.getIntExtra(PhonePlayerActivity.EXTRA_EPISODE, -1)
    if (wanted >= 0 && wanted != sessionIndex) return "episode-mismatch"
    val wantedPosition = intent.getLongExtra(PhonePlayerActivity.EXTRA_POSITION_MS, -1L)
    if (wantedPosition >= 0 && kotlin.math.abs(PhoneSession.positionMs - wantedPosition) > 1_500L) {
        return "position-mismatch"
    }
    val exo = PhoneServices.peekPlayer() ?: return "no-player"
    // IDLE **不等于**接不上。swapMediaSource 是「先 stop 再挂源」（见 PlayerSourceSwap），
    // 从挂源到 prepare 之间播放器就是 IDLE；推荐页片尾交接、刚起播一屏时都正好落在
    // 这个窗口里。早先这里按 IDLE 直接拒绝，于是每一次都退化成
    // 「重新取详情 → 重新取地址 → 重新 prepare → 从 0 起播」——用户读到的就是
    // 「从推荐页进正片内容断了一下、还从头开始」。
    //
    // 真正的判据是「播放器上挂着的那一项是不是这一集」：是，就接手并替它 prepare。
    if (exo.playbackState == Player.STATE_IDLE) {
        val wantedId = d.episodes.getOrNull(sessionIndex).orEmpty()
        val onPlayer = runCatching { exo.currentMediaItem?.mediaId }.getOrNull().orEmpty()
        if (wantedId.isEmpty() || onPlayer != wantedId) return "idle-no-source"
    }
    return null
}

/**
 * 接手进程级播放器上已经挂好的这一集。
 *
 * 条件（缺一不可）：
 * * 会话里是**同一部剧**，且播放器上挂着的是这一集（IDLE 也行，这里补 prepare）；
 * * 用户没有指定别的集（EXTRA_EPISODE 与会话里那一集一致或没传）。
 *
 * 接上之后**不重新取源**：画面接着播，位置就是用户刚才看到的那一秒。
 */
internal fun PhonePlayerActivity.adoptFromSession(seriesId: String): Boolean {
    val why = adoptReject(seriesId)
    if (why != null) {
        android.util.Log.i(
            "HGTV-PHONE",
            "adopt skip $why episode=${intent.getIntExtra(PhonePlayerActivity.EXTRA_EPISODE, -1)}",
        )
        // 会话不能接（最常见是集号不一致）时，下层推荐页那块 Surface 已经不代表
        // 本页要播的这一集。继续借它会出现“声音在本页、画面留在下层/黑屏”。
        // 立即退回本页自己的 PlayerView，后面的取流 / prepare 才有正确输出端。
        if (playerFeedUnderlay) dropUnderlay()
        return false
    }
    val d = PhoneSession.detail ?: return false
    val video = PhoneSession.remote ?: return false
    val sessionIndex = PhoneSession.index
    // 发布前先取：publish 会把会话里的位置清零（那一份是给「下一次接手」用的）。
    val resumeAt = PhoneSession.positionMs
    val wasIdle = runCatching { PhoneServices.peekPlayer()?.playbackState }
        .getOrNull() == Player.STATE_IDLE
    val exo = PhoneServices.player(this, library?.decoderMode)

    detail = d
    index = sessionIndex
    remote = video
    preparePlayerPager(d, sessionIndex) {
        finishAdoptFromSession(
            seriesId = seriesId,
            d = d,
            video = video,
            sessionIndex = sessionIndex,
            resumeAt = resumeAt,
            wasIdle = wasIdle,
            exo = exo,
        )
    }
    return true
}

private fun PhonePlayerActivity.finishAdoptFromSession(
    seriesId: String,
    d: com.hgtv.star.core.Detail,
    video: com.hgtv.star.core.RemoteVideo,
    sessionIndex: Int,
    resumeAt: Long,
    wasIdle: Boolean,
    exo: ExoPlayer,
) {
    bindEpisodeUiForAdoption(d, sessionIndex)

    player = exo
    // bindEpisodeUi 在 player 赋值之前跑：这里补一次画面尺寸，
    // 否则接手时「全屏」按钮会一直不出现（bind 把已知尺寸清掉了）。
    syncVideoFrameForAdoption()
    if (playerLandscapeMode) {
        playerPage.playerView.player = null
        scheduleCurrentLandscapeAttach()
    } else {
        playerLandscapeVideo.player = null
        // 借下层 Surface：不挂本页的视频层，播放器连输出都没换过。
        if (!(playerFeedUnderlay && attachUnderlay())) {
            dropUnderlay()
            PhoneSession.underlayActive = false
            selectPlayerPagerEpisode(sessionIndex)
            attachCurrentPlayerToPagerPage()
        }
    }
    PhoneSession.publish(d, sessionIndex, video, video.info.quality, PhoneSession.Owner.PLAYER)
    attachPlayerListener(exo)
    // 播放列表里可能还挂着上一轮排好的「下一集」：swapMediaSource 换源时已经把列表清空，
    // 但 nextQueued 这个标志还停在 true。不对一次的话，片尾判定会以为「已经排好了」，
    // 一集放完就停在最后一帧（用户读到的就是「没有自动下一集」）。
    PhoneSession.nextQueued = runCatching {
        // currentMediaItemIndex 在「源刚挂上、还没 prepare」时可能是 -1（无 timeline）：
        // 直接相加会把「只有一项」算成「后面还有一项」，片尾就不再往下走了。
        exo.mediaItemCount > exo.currentMediaItemIndex.coerceAtLeast(0) + 1
    }.getOrDefault(false)
    // 预热好的下一集**留着**：推荐页在片尾前备好的那一份正是这一集要用的，
    // 丢掉等于让本页重新取一次地址（片尾那一下又要黑一下）。
    // 预热节流也跟着归零：上一轮那个「8 秒后再试」的时间戳留着，会让这一集的预热白等一截，
    // 短剧集只有几十秒，等过去就已经到片尾了。
    listenerPrefetchAt = 0
    progressPrefetchingFor = ""
    exo.setPlaybackSpeed(chipUserSpeed)
    // 媒体源是刚挂上、还没 prepare 的那一种（推荐页片尾交接 / 刚起播一屏）：
    // 这里替它 prepare，并把位置对回会话记下的那一秒。**不重新取地址**。
    if (wasIdle) {
        setBufferingLabel("正在缓冲…")
        exo.prepare()
        if (resumeAt > 0) exo.seekTo(resumeAt)
    }
    // 按发起方的意图决定要不要立刻播：
    // * 「观看完整短剧」/ 片尾进正片 → true（信息流那边已经写好）；
    // * 「全屏」按钮 → 沿用信息流里当时是播还是暂停。
    // 这里**不要**硬写 true：用户明明在信息流里暂停了，点全屏进来看见它自己动起来，
    // 就是「暂停没记住」。
    exo.playWhenReady = PhoneSession.resumeOnAdopt
    // 接手完成：交接窗口到此为止，之后再退到后台就正常暂停。
    PhoneSession.endHandoff()
    syncPlayGlyphForAdoption()
    syncChips()
    startTicker()
    armPictureWatchdog()
    if (listenerPendingFullscreen) {
        root.post { enterPendingFullscreenForAdoption() }
    }
    android.util.Log.i(
        "HGTV-PHONE",
        "adopt session series=$seriesId episode=${sessionIndex + 1}",
    )
}
