// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Player

internal fun PhoneFeedView.adoptPlayingImpl(
    forceRebind: Boolean = false,
    clearDanmakuOnSwitch: Boolean = false,
): Boolean {
    // 会话属于另一条体裁流：这一路不是本页的，不能接。
    if (!feedSessionBelongsToThisFeed()) return false
    val series = feedItems.getOrNull(feedPlayingIndex) ?: return false
    // 只看「这一路还活着」：切页只换了 Surface，媒体源与解码器一直在跑，接上去就能出帧。
    // 只有真实 Range 失败时才由正常错误路径换地址。
    if (!PhoneSession.live(series.id)) return false
    val d = PhoneSession.detail ?: return false
    fun episodeIdOf(player: ExoPlayer?): String =
        player?.let { runCatching { it.currentMediaItem?.mediaId }.getOrNull() }.orEmpty()
    fun belongsToSession(player: ExoPlayer?): Boolean =
        player?.isFeedPlayerAlive() == true && episodeIdOf(player) in d.episodes
    // 播放器可能不在本视图的字段上：本视图刚被重建（转屏、体裁重铺、Activity 重建），
    // 但进程里那一台还在放。这时照样能接——这正是「退回来内容不断」要覆盖的场景。
    // 正式页换集会激活相邻集播放器，此时本页字段仍指向进入正式页时的旧实例；
    // 必须先接进程里真正匹配当前剧集的那一台，否则返回后会接错实例并出现双音轨。
    val processPlayer = PhoneServices.peekPlayer()
    val processOwnsSession = PhoneSession.owner == PhoneSession.Owner.PLAYER &&
        processPlayer?.isFeedPlayerAlive() == true
    val exo = processPlayer?.takeIf { belongsToSession(it) || processOwnsSession }
        ?: feedPlayer?.takeIf(::belongsToSession)
        ?: feedPlayer?.takeIf { it.isFeedPlayerAlive() }
        ?: processPlayer?.takeIf { it.isFeedPlayerAlive() }
        ?: return false
    // 接上新实例之前先彻底静音旧实例。池槽随后会释放它，但不能把双音轨留到释放任务之后。
    feedPlayer?.takeIf { it !== exo }?.let { stale ->
        runCatching {
            stale.volume = 0f
            stale.playWhenReady = false
            stale.pause()
        }
        PhoneServices.enableAudioFocus(stale, false)
    }
    if (com.hgtv.star.shared.BuildConfig.DEBUG) {
        // 接手前后的读数：位置有没有被谁清零 / 播放列表有没有被谁改过，
        // 这行是最直接的证据（用户报「切一次就从头」时先看它）。
        android.util.Log.i(
            "HGTV-PHONE",
            "adopt begin pos=${runCatching { exo.currentPosition }.getOrDefault(-1)} " +
                "state=${exo.playbackState} pwr=${exo.playWhenReady} " +
                "items=${exo.mediaItemCount} cur=${exo.currentMediaItemIndex} " +
                "underlay=${PhoneSession.underlayActive} sessionPos=${PhoneSession.positionMs} " +
                "process=${processPlayer === exo} feed=${feedPlayer === exo}"
        )
    }
    // IDLE 不等于接不上：`swapMediaSource` 是「先 stop 再挂源」，挂完到 prepare 之间
    // 播放器就是 IDLE（播放页原地换地址、片尾交接都落在这个窗口里）。这时播放器上
    // 挂着的**就是这一集**，接过来补一次 prepare 即可；判成接不上就会重新取地址、
    // 重新 prepare，用户读到的又是一次「退回来内容断了」。
    var idleNeedsPrepare = false
    if (exo.playbackState == Player.STATE_IDLE) {
        val wantedId = episodeIdOf(exo).ifEmpty {
            d.episodes.getOrNull(PhoneSession.index.takeIf { it >= 0 } ?: 0).orEmpty()
        }
        val onPlayer = episodeIdOf(exo)
        if (wantedId.isEmpty() || onPlayer != wantedId) return false
        idleNeedsPrepare = true
    }
    feedPlayerSlot = exo

    val episodeIndex = d.episodes.indexOf(episodeIdOf(exo)).takeIf { it >= 0 }
        ?: PhoneSession.index.takeIf { it >= 0 } ?: 0
    // 集号 / 标题按会话里那一集重新刷一遍（播放页里可能已经换过集）。
    feedCurrentEpisodeIndex[feedPlayingIndex] = episodeIndex
    feedCurrentEpisodeIds[feedPlayingIndex] = d.episodes.getOrNull(episodeIndex).orEmpty()
    feedPlayingDetailSlot = d
    adoptPooledPlayerSlot(
        index = feedPlayingIndex,
        player = exo,
        seriesId = series.id,
        episodeId = feedCurrentEpisodeIds[feedPlayingIndex].orEmpty(),
        detail = d,
        episodeIndex = episodeIndex,
        remote = PhoneSession.remote,
    )
    pausePooledPlayersExcept(exo)

    // 播放列表里可能还挂着「下一集」（播放页预热好的那一项）。
    // 信息流的语义是「一集播完 → 进正式播放页」，留着它 ExoPlayer 会自己无感推进，
    // 于是信息流里悄悄换了集、集号却没变。这里只保留当前这一项。
    // 只摘「当前项之后」的那些：如果已经推进到第 2 项（片尾刚切过去），
    // 摘掉它等于把正在放的那一项删了，播放器会直接跳到结尾。
    runCatching {
        // `currentMediaItemIndex` 在「源刚挂上、还没 prepare」时可能是 -1：不夹一下，
        // `removeMediaItems(0, count)` 会把正在放的那一项也一起摘掉，播放器直接跳到结尾。
        val current = exo.currentMediaItemIndex.coerceAtLeast(0)
        if (exo.mediaItemCount > current + 1) {
            exo.removeMediaItems(current + 1, exo.mediaItemCount)
        }
    }
    // 列表里的下一项已经摘掉了，「排好了」这个标志必须跟着清：留着它的话，
    // 本页片尾那一下会被当成「播放器会自己推进」而什么都不做，用户看到的就是「放完停住」。
    PhoneSession.nextQueued = false
    // 那一项已经从播放列表里摘掉，它捕获的 `RemoteVideo` 也就没人用了：一起关掉。
    // **但片尾预热的那一份要留着**：它不属于播放列表，是给「片尾自动进正片」接手用的。
    if (PhoneSession.nextEpisodeId.isEmpty()) PhoneSession.clearNext()

    // 页面可能刚好不在可见区（退回来时列表还在恢复布局 / Activity 刚重建）：
    // 这时**照样接管**，Surface 交给 rebindCurrentPage 在页面绑上来之后挂——
    // 早先这里直接返回 false，于是又走一遍取详情、取地址、prepare，用户看到的就是「重新加载」。
    val page = feedPageAt(feedPlayingIndex)
        ?: feedBoundPage?.takeIf {
            feedList.getChildAdapterPosition(it) == feedPlayingIndex
        }
    if (page != null) {
        attachPageForSurface(
            exo,
            page,
            series,
            episodeIndex,
            forceRebind,
            clearDanmakuOnSwitch,
        )
    }

    // 重新挂监听：`onHidden` 里摘掉了，摘掉是为了不让隐藏期间的回调改界面。
    attachFeedListener(exo, feedPlayingIndex)
    // 倍速以**书库设置**为准：用户可能刚在播放页里把 1.5 调成了 2（那一侧会落盘），
    // 本页字段还是进来时的旧值，直接写回去就是一次「速度突变」。
    feedUserSpeed = feedLibrary?.playbackSpeed ?: feedUserSpeed
    exo.setPlaybackSpeed(feedUserSpeed)
    // 会话已经被信息流接手：播放页的 onDestroy 因此在收尾时不再 pause（见 detachForHandoff）。
    PhoneSession.takeOver(PhoneSession.Owner.FEED)
    // 用户是在播放页里按的暂停就保持暂停，别替他恢复播放。
    exo.playWhenReady = PhoneSession.resumeOnAdopt
    // 暂停状态跟着走：不然片尾判定（`paused` 为 false）会在暂停的画面上误触发换集。
    feedPaused = !exo.playWhenReady
    // 接手的实例可能本来就在播，`playWhenReady` 没有发生 true/false 变化，监听器不会回调。
    notifyPlayStateChangedImpl()
    // 进度条立刻对齐到接手的那一秒，不要等 500ms 后的第一个 tick。
    val dur = exo.duration
    if (page != null && dur > 0) {
        page.setProgress(exo.currentPosition.toFloat() / dur)
        page.setBufferedProgress(exo.bufferedPosition.toFloat() / dur)
    }
    if (page != null) {
        syncDanmakuClock(
            page,
            feedPlayingIndex,
            exo.currentPosition,
            exo.isPlaying && exo.playbackState == Player.STATE_READY,
            exo.playbackParameters.speed,
        )
    }
    // 画面已经回到本页（无论刚才是不是借出去的）：之后的重挂都按常规走。
    PhoneSession.underlayActive = false
    // 源挂上了但还没 prepare 的那一种：在这里补上，并把位置对回会话记下的那一秒。
    // 换源时 `PlayerView` 保留了上一帧（`setKeepContentOnPlayerReset`），画面不会黑一下。
    if (idleNeedsPrepare) {
        exo.prepare()
        val at = PhoneSession.positionMs
        if (at > 0) exo.seekTo(at)
    }
    startProgressTicker()
    // 接上之后兜一次「在走」：Surface 换宿主之后偶尔会停在 READY + playWhenReady 却不推进。
    // 只补一次播放状态，不重设媒体源、不 seek。
    feedList.postDelayed({ keepLivePlaybackImpl() }, 1_500L)
    android.util.Log.i(
        "HGTV-PHONE",
        "feed adopt series=${series.id} episode=${episodeIndex + 1} " +
            "pos=${exo.currentPosition} page=${page != null}"
    )
    return true
}
