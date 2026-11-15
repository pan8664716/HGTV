// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.content.Context
import android.os.Looper
import android.os.Handler
import com.hgtv.star.core.ContentRepository
import com.hgtv.star.shared.data.AppRuntime
import com.hgtv.star.shared.data.ArtworkLoader
import com.hgtv.star.shared.data.Library
import com.hgtv.star.shared.debug.PlaybackStartupTrace
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.ExoPlayer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 手机端的进程级服务入口。
 *
 * 仓库 / 书库 / 封面加载器统一委托给 [AppRuntime]，与 TV 共用同一套实例与生命周期；
 * 本对象只保留手机端独有的播放器、解码器与网络日志接线。
 */
object PhoneServices {

    init {
        PhoneNetDebug.install()
    }

    fun repository(context: Context): ContentRepository = AppRuntime.repository(context)

    fun library(context: Context): Library = AppRuntime.library(context)

    fun artwork(context: Context): ArtworkLoader = AppRuntime.artwork(context)

    /**
     * 等书库读盘完成再返回。
     *
     * `Library` 的所有读取接口（`history` / `favorites` / `progress` / `hiddenNav` …）
     * 都走 `state()`，没读完就抛 `IllegalStateException: Load the local library before accessing it`。
     * TV 端是在 `loadAsync` 的回调里才建界面，所以碰不到这个坑；
     * 手机端页面是「先建视图、再填数据」，必须先在这里等一次。
     *
     * 失败也返回实例：读盘失败时书库是空表，界面显示「还没有记录」比崩掉好。
     */
    suspend fun awaitLibrary(context: Context): Library {
        val lib = library(context)
        if (lib.isLoaded) return lib
        return suspendCancellableCoroutine { cont ->
            lib.loadAsync(
                onFailure = { if (cont.isActive) cont.resume(lib) },
                onReady = { if (cont.isActive) cont.resume(lib) },
            )
        }
    }

    /**
     * **进程级唯一的播放器**。
     *
     * 之前每个页面各建一个 `ExoPlayer`：信息流三个体裁三个、播放页一个，一共四个解码器实例。
     * 后果是「切换页面后另一条流还在响」「点进播放页从头开始」「偶发状态错乱」——
     * 都来自多个播放器各记各的状态。现在全进程只有一个，各页面只是把自己的
     * `PlayerView` 绑上去 / 解下来，切页不再重建解码器。
     *
     * 必须在主线程调用（`ExoPlayer.Builder().build()` 要求）。
     */
    @Volatile private var exo: ExoPlayer? = null
    private var warmPlayerScheduled=false

    /**
     * 建播放器，并把「视频解码」那一档装进去。
     *
     * 解码方式只能在**建渲染器时**定死，而播放器是进程单例（见上面的类注释：多实例会
     * 抢解码器、留两条音轨），因此不能为了换一档就重建它。这里走的是「选择器读一个可变值」：
     * [PhoneDecoder.mode] 由每次取播放器时按设置写入，渲染器在下一次初始化解码器时读到的
     * 就是新值——效果与 TV 的「改完下一次起播生效」一致，且不会把正在用它的信息流打断。
     */
    fun player(context: Context, decoder: com.hgtv.star.core.VideoDecoderMode? = null): ExoPlayer {
        if (decoder != null) PhoneDecoder.mode = decoder
        PhoneDecoder.bind(context)
        return exo ?: synchronized(this) {
            exo ?: ExoPlayer.Builder(context.applicationContext)
                .setLooper(Looper.getMainLooper())
                .setLoadControl(phoneLoadControl())
                .setRenderersFactory(
                    com.google.android.exoplayer2.DefaultRenderersFactory(context.applicationContext)
                        .setMediaCodecSelector(PhoneDecoder.selector)
                        // 解码器兜底：首选解码器初始化失败时自动换一颗（与电视端
                        // `TvRenderersFactory.setEnableDecoderFallback(true)` 同一条）。
                        // 少了它，片源的编码稍微偏门一点就是「有声音没画面」，且不会自愈。
                        .setEnableDecoderFallback(true)
                )
                // 音频焦点与「拔耳机就停」：手机上这两条比电视重要得多。
                // * `USAGE_MEDIA` + `AUDIO_CONTENT_TYPE_MOVIE`：来电、通知、其它应用播放时
                //   系统才知道该怎么让路（暂停 / 压低音量），否则就是两条声音叠着响。
                // * `setHandleAudioBecomingNoisy`：拔耳机 / 断蓝牙那一刻自动暂停，
                //   不暂停就会从外放突然放出来——公共场合非常难堪。
                .setAudioAttributes(
                    com.google.android.exoplayer2.audio.AudioAttributes.Builder()
                        .setUsage(com.google.android.exoplayer2.C.USAGE_MEDIA)
                        .setContentType(com.google.android.exoplayer2.C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true
                )
                .setHandleAudioBecomingNoisy(true)
                // 后台 / 锁屏继续放时保住 CPU（与电视端 `setWakeMode` 同一条）：
                // 不保的话，锁屏几十秒后连接就断，回来要重走 DNS + TLS + 取地址。
                .apply { runCatching { setWakeMode(com.google.android.exoplayer2.C.WAKE_MODE_NETWORK) } }
                .build()
                .also { player ->
                    // 解码现场**在这里挂**：播放器是进程单例，解码器初始化事件只会发生一次；
                    // 挂在某个 Activity 上就会漏掉「推荐页预热好、播放页接手」的那一集
                    //（交接时解码器早就初始化完了，再挂监听收不到任何事件）。
                    runCatching { metrics.attach(player) }
                    exo = player
                    PlaybackStartupTrace.point("player.built")
                }
        }
    }

    /**
     * 首屏之后的播放器预热。
     *
     * ExoPlayer 必须在主线程构建，但构建会扫描 MediaCodec；放到首屏动画之后再跑，
     * 可以把它与推荐、注册和详情请求重叠，而不是等视频索引准备完才串行创建。
     */
    fun warmPlayer(
        context: Context,
        decoder: com.hgtv.star.core.VideoDecoderMode? = null,
        delayMs: Long = 120L,
    ) {
        val app=context.applicationContext
        synchronized(this) {
            if(exo!=null || warmPlayerScheduled) return
            warmPlayerScheduled=true
        }
        Handler(Looper.getMainLooper()).postDelayed({
            var shouldBuild=false
            synchronized(this) {
                warmPlayerScheduled=false
                shouldBuild=exo==null
            }
            if(shouldBuild) {
                runCatching { player(app,decoder) }
                    .onFailure { problem ->
                        android.util.Log.w("HGTV-PHONE","播放器预热失败 ${problem.javaClass.simpleName}",problem)
                    }
            }
        },delayMs.coerceAtLeast(0L))
    }

    /**
     * 手机端统一缓冲档位。
     *
     * ExoPlayer 默认是 50 秒最小/最大缓冲、2.5 秒起播、5 秒重缓冲；这里改成
     * 15–30 秒缓冲、1.5 秒起播、2.5 秒恢复。只调整 LoadControl，不替换播放器、
     * Surface 或换源逻辑；字节上限只做保护，时间仍是主目标。
     */
    private fun phoneLoadControl(): DefaultLoadControl =
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000,
                30_000,
                1_500,
                2_500,
            )
            .setTargetBufferBytes(16 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

    /** 把播放器从某个 `PlayerView` 上解下来（切页时调，别 release）。 */
    fun detach(view: com.google.android.exoplayer2.ui.PlayerView?) {
        if (view != null && view.player === exo) view.player = null
    }

    /** 用户主动退出：释放进程级播放器，随后由 [com.hgtv.star.shared.architecture.AppExit] 结束进程。 */
    fun releaseForExit() {
        synchronized(this) {
            runCatching { exo?.release() }
            exo=null
            metrics.reset()
        }
    }

    /**
     * 看一眼现在的播放器，**不新建**。
     *
     * 给「接手」用：判断进程里是不是已经有一路在放。用 [player] 会顺手建一个空播放器，
     * 那等于凭空多一次解码器初始化。
     */
    fun peekPlayer(): ExoPlayer? = exo

    /**
     * 解码现场（`:shared` 的 [PlaybackMetrics]）：解码器名、初始化耗时、编码格式、丢帧、欠载。
     *
     * 进程级、只读：调试面板每秒读一次，不做任何 IO。放这里而不是各页各建一份，
     * 是因为「解码器初始化」这件事只发生一次，而读它的界面有好几个。
     */
    val metrics = com.hgtv.star.shared.media.PlaybackMetrics()

    /** 当前装进播放器的那一档解码方式（只用于日志 / 排查）。 */
    val decoderMode: com.hgtv.star.core.VideoDecoderMode get() = PhoneDecoder.mode

    /** 预热：把冷启动最贵的一次握手挪到用户还在看首屏骨架的时候。 */
    fun warm(context: Context) {
        runCatching { AppRuntime.warm(context) }
    }
}

/**
 * 视频解码方式的选择器：**每次取播放器时**把用户设置写进 [mode]。
 *
 * 与 TV 的 `decoderSelector`（`MainActivityPlayer.kt`）同一条规则：音频不受影响，
 * 视频按档位筛——硬件档只给非软解的实现，软件档只给软解的实现。
 *
 * 筛空不在这里兜底：硬解一颗都没有时 ExoPlayer 会抛 `DecoderQueryException`，
 * 由播放页的错误处理提示用户换软解（与 TV 一致，不静默改档）。
 */
object PhoneDecoder {
    @Volatile var mode: com.hgtv.star.core.VideoDecoderMode =
        com.hgtv.star.core.VideoDecoderMode.HARDWARE

    /**
     * 故障注入要读应用私有目录，选择器本身拿不到 `Context`：由 [PhoneServices.player] 每次写入。
     */
    @Volatile private var app: android.content.Context? = null

    internal fun bind(context: android.content.Context) { app = context.applicationContext }

    val selector = com.google.android.exoplayer2.mediacodec.MediaCodecSelector { mimeType, secure, tunnel ->
        val all = com.google.android.exoplayer2.mediacodec.MediaCodecSelector.DEFAULT
            .getDecoderInfos(mimeType, secure, tunnel)
        // 调试包的故障注入（与电视端同一个开关文件，见 `:shared` 的 `DebugFaults`）：
        // 「这台机器的解码器恰好不行」在开发机上几乎碰不到，没有它，自愈链路就只能靠线上用户验证。
        // 正式包里 `BuildConfig.DEBUG` 是常量 false，整段会被 R8 删掉。
        if (com.hgtv.star.shared.BuildConfig.DEBUG) {
            when (com.hgtv.star.shared.debug.DebugFaults.mode(app ?: return@MediaCodecSelector all)) {
                com.hgtv.star.shared.debug.DebugFaults.Mode.DECODER_NONE -> return@MediaCodecSelector emptyList()
                com.hgtv.star.shared.debug.DebugFaults.Mode.DECODER_BOGUS -> {
                    if (mimeType.startsWith("video/")) {
                        return@MediaCodecSelector listOf(
                            com.google.android.exoplayer2.mediacodec.MediaCodecInfo.newInstance(
                                "hgtv.bogus.decoder", "video/hevc", "video/hevc",
                                null, false, false, false, true, false
                            )
                        )
                    }
                }
                else -> Unit
            }
        }
        if (!mimeType.startsWith("video/")) all
        else when (mode) {
            com.hgtv.star.core.VideoDecoderMode.HARDWARE -> {
                val hardware=all.filter { !it.softwareOnly }
                if(hardware.isNotEmpty()) hardware
                else if(!secure && !tunnel && mimeType.equals("video/hevc",ignoreCase=true)) {
                    com.hgtv.star.shared.data.PlaybackCapability
                        .relaxedTunneledHevcDecoders()
                } else hardware
            }
            com.hgtv.star.core.VideoDecoderMode.SOFTWARE -> all.filter { it.softwareOnly }
        }
    }
}
