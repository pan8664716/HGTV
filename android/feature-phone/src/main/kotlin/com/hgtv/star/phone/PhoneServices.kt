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
     * 正式播放页当前接手的播放器。
     *
     * 推荐页使用有上限的播放器池；当前活动实例会通过 [activate] 登记到这里，
     * 因此正式播放页仍能原样接手，而相邻页的独立播放器不会被全局字段覆盖。
     *
     * 必须在主线程调用（`ExoPlayer.Builder().build()` 要求）。
     */
    @Volatile private var exo: ExoPlayer? = null
    private var warmPlayerScheduled=false

    /**
     * 建播放器，并把「视频解码」那一档装进去。
     *
     * 解码方式只能在**建渲染器时**定死，而推荐池会复用实例；不能为了换一档就重建它。
     * 这里走的是「选择器读一个可变值」：
     * [PhoneDecoder.mode] 由每次取播放器时按设置写入，渲染器在下一次初始化解码器时读到的
     * 就是新值——效果与 TV 的「改完下一次起播生效」一致，且不会把正在用它的信息流打断。
     */
    fun player(context: Context, decoder: com.hgtv.star.core.VideoDecoderMode? = null): ExoPlayer {
        return exo ?: synchronized(this) {
            exo ?: newPlayer(context, decoder).also { exo = it }
        }
    }

    /**
     * 建一台不自动成为进程主播放器的实例，供推荐页播放器池使用。
     *
     * 池内当前页会把实例登记到 [activate]；相邻页的播放器只在本页池里存在，
     * 不参与正式播放页的全局接手，回收时也不会误伤正在播放的那一台。
     */
    fun newPlayer(
        context: Context,
        decoder: com.hgtv.star.core.VideoDecoderMode? = null,
        handleAudioFocus: Boolean = true,
    ): ExoPlayer {
        if (decoder != null) PhoneDecoder.mode = decoder
        PhoneDecoder.bind(context)
        return ExoPlayer.Builder(context.applicationContext)
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
            .setAudioAttributes(phoneAudioAttributes(), handleAudioFocus)
            .setHandleAudioBecomingNoisy(true)
            // 后台 / 锁屏继续放时保住 CPU（与电视端 `setWakeMode` 同一条）：
            // 不保的话，锁屏几十秒后连接就断，回来要重走 DNS + TLS + 取地址。
            .apply { runCatching { setWakeMode(com.google.android.exoplayer2.C.WAKE_MODE_NETWORK) } }
            .build()
            .also { player ->
                // 解码现场跟着实例走；池内播放器同样需要这份现场。
                runCatching { metrics.attach(player) }
                PlaybackStartupTrace.point("player.built")
            }
    }

    /**
     * 相邻预渲染播放器必须关闭音频焦点，否则 0 音量播放也会抢走当前集的焦点，
     * 系统会把正在播放的实例暂停。真正切为当前集时再调用 [enableAudioFocus]。
     */
    fun enableAudioFocus(player: ExoPlayer, enabled: Boolean) {
        runCatching { player.setAudioAttributes(phoneAudioAttributes(), enabled) }
    }

    private fun phoneAudioAttributes() =
        com.google.android.exoplayer2.audio.AudioAttributes.Builder()
            .setUsage(com.google.android.exoplayer2.C.USAGE_MEDIA)
            .setContentType(com.google.android.exoplayer2.C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

    /**
     * 把推荐页池中的当前播放器登记为正式播放页可接手的实例。
     *
     * 只切换“当前”指针，不释放旧实例：旧实例可能仍是推荐池里的相邻页，
     * 由池按距离统一回收。
     */
    fun activate(player: ExoPlayer) {
        synchronized(this) {
            exo = player
        }
        runCatching { metrics.attach(player) }
    }

    /** 池内播放器被回收时清掉全局当前指针；只清同一实例，不影响新接手的播放器。 */
    fun deactivate(player: ExoPlayer) {
        synchronized(this) {
            if (exo === player) exo = null
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

    /**
     * 页面退出时清掉 SurfaceView 保留的最后一帧。
     *
     * 正式播放页会复用两个 PlayerView。若只解绑 player，keep-content 会让旧集的
     * 最后一帧留在 Surface 上，下一次复用时就会在首帧前露出上一集。
     */
    fun detachAndClear(view: com.google.android.exoplayer2.ui.PlayerView?) {
        if (view == null || view.player !== exo) return
        view.setKeepContentOnPlayerReset(false)
        view.player = null
        view.setKeepContentOnPlayerReset(true)
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
                    val platform=com.hgtv.star.shared.data.PlaybackCapability
                        .platformHevcDecoders(true)
                    platform.ifEmpty {
                        com.hgtv.star.shared.data.PlaybackCapability.relaxedTunneledHevcDecoders()
                    }
                } else hardware
            }
            com.hgtv.star.core.VideoDecoderMode.SOFTWARE -> {
                val software=all.filter { it.softwareOnly }
                if(software.isNotEmpty()) software
                else if(!secure && !tunnel && mimeType.equals("video/hevc",ignoreCase=true)) {
                    com.hgtv.star.shared.data.PlaybackCapability.platformHevcDecoders(false)
                } else software
            }
        }
    }
}
