// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer

/**
 * 构造之后的配置，**只能在主线程调用**。
 *
 * ExoPlayer 2.19.1 里 `addAnalyticsListener` / `setVideoFrameMetadataListener` 开头都是
 * `verifyApplicationThread()`，线程不符就抛 `Player is accessed on the wrong thread`。
 *（`setAudioAttributes` / `setHandleAudioBecomingNoisy` 同样如此，只是它们已经挪到
 * [buildPlayer] 的 Builder 上——构造前的设置不校验线程。）
 * 因此构造留在 io（那半秒是 `MediaCodecList` 扫描），配置回主线程——两边都很短，
 * 谁先到都不影响：这里用 [TvMainActivity.configuredPlayer] 保证同一个实例只配一次
 *（`addAnalyticsListener` 重复调用会把监听挂两遍，诊断数据会翻倍）。
 */
internal fun TvMainActivity.configurePlayer(p: ExoPlayer) {
    // `p!==player`：这个实例已经被 release / 换解码方式拆掉了，不要再往它上面挂监听。
    if(p!==player || p===configuredPlayer) return
    // 诊断监听**正式包也挂**：只记账、不写日志，开销是几个 volatile 赋值。
    p.addAnalyticsListener(playbackDiagnostics())
    // 逐帧心跳：「画面还在走」的唯一硬证据，停滞看门狗靠它判活（见 [playbackFrameClock]）。
    p.setVideoFrameMetadataListener(playbackFrameClock())
    // 播放期间持一个 PARTIAL_WAKE_LOCK：屏幕常亮只管屏幕，CPU 该睡还是会睡。
    //
    // 电视盒子在暂停 / 后台几秒后进入 Doze，回来时连接已断，要重走 DNS + TLS + 取地址
    // ——用户读到的是「暂停一下再回来就放不了了」。`setWakeMode` 必须在应用线程调
    // （`ExoPlayerImpl` 里第一句就是 `verifyApplicationThread()`），因此放在这里而不是
    // [buildPlayer]（那边跑在 io 线程）。需要 `WAKE_LOCK` 权限，见 AndroidManifest。
    runCatching { p.setWakeMode(C.WAKE_MODE_NETWORK) }
    if(com.hgtv.star.tv.BuildConfig.DEBUG) p.addAnalyticsListener(playbackAnalytics())
    configuredPlayer=p
}

/** 把播放器挂到 `PlayerView` 上（主线程）。 */
internal fun TvMainActivity.attachPlayerView(p: ExoPlayer) { playerView?.player=p }

/**
 * 起播前的并行预热：`player` 还没有就先建出来，让「建播放器」与「取地址 + 本地准备」同时在跑。
 *
 * 只在 io 线程调（见 `playEpisode` 的 `work { ... }`）。已经有播放器时是空操作——
 * 复用那条路本来就不重建播放器。
 */
internal fun TvMainActivity.warmPlayer() {
    if(player!=null || playerDisposed) return
    val epoch=playerBuildEpoch
    val mode=playerDecoderMode()
    val profile=resolveTvBufferProfile()
    // 见 [playerBuildLock]：并发的第二条路只等一次，不会建出第二个播放器。
    val built=synchronized(playerBuildLock) {
        if(player!=null || playerDisposed || epoch!=playerBuildEpoch) null
        else buildPlayer(mode,profile).let { candidate ->
            if(player==null && !playerDisposed && epoch==playerBuildEpoch && mode==playerDecoderMode()) {
                player=candidate
                builtPlayerDecoderMode=mode
                builtPlayerBufferProfile=profile
                candidate
            } else {
                // 旧档位的构建可能刚在切档前跑完。ExoPlayer 只能在应用线程 release，
                // 这里先交给主线程；绝不能把它装回 [player]，否则新设置也会继续用旧渲染器。
                android.util.Log.w(
                    "HGTV",
                    "Discarding stale player build mode=$mode epoch=$epoch"+
                        " currentMode=${playerDecoderMode()} currentEpoch=$playerBuildEpoch",
                )
                main.post { runCatching { candidate.release() } }
                null
            }
        }
    } ?: return
    // 配置必须在主线程（见 [configurePlayer]）。这里只是扔回主线程，不等结果：
    // 起播回调本身在主线程，那边会再调一次（幂等），配置不会丢。
    main.post { runCatching { if(!playerDisposed) configurePlayer(built) } }
}

/**
 * 在**独立线程**上开始建播放器，返回一个可以等的句柄。
 *
 * 为什么不直接在 [io] 上建（原来的写法）：那个池子只有 3 条线程，冷启动那一刻
 * 「读详情缓存 / 取推荐流 / 心跳 / 账号」同时在里面排队，建播放器被推后 300~400ms——
 * 日志上读起来像「`buildPlayer` 变慢了」，实际是在等线程。建播放器与网络无关，
 * 单独一条线程才不会排在别人后面。
 *
 * 仍然走 [warmPlayer] 那条加锁路径：与「起播回调里补建」并发时只会建出一个实例
 * （见 [playerBuildLock]）。
 */
fun TvMainActivity.buildPlayerAsync(): java.util.concurrent.Future<*> =
    // 这一处**保留 `Future`**而不是协程：调用方要用 `get(3s)` 带超时地等它
    //（见 `playEpisode` 里 `playerBuild?.get(3, SECONDS)`）。`Job` 没有「等 3 秒就放弃」
    // 的等价写法，硬套 `runBlocking { withTimeout }` 反而多一层阻塞。
    // 线程仍然是 `player` 车道，与改造前那条专用线程同一个预算。
    playerBuildWorker.submit { runCatching { warmPlayer() } }
