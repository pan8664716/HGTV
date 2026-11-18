// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.os.Bundle
import com.hgtv.star.shared.architecture.network
import com.hgtv.star.shared.compat.attachedToWindow
import com.hgtv.star.shared.compat.connectivityManager
import com.hgtv.star.shared.compat.isMeteredConnection
import com.hgtv.star.shared.data.AppRuntime
import com.hgtv.star.shared.data.FavoriteMonitor
import com.hgtv.star.shared.debug.DebugFaults
import com.hgtv.star.shared.debug.DebugLog
import com.hgtv.star.shared.debug.StartupTrace
import com.hgtv.star.shared.ui.TvThemeStore
import com.hgtv.star.tv.list.positionBackToTop
import kotlinx.coroutines.launch

/**
 * 第二阶段的完整主界面初始化。
 *
 * 放在首帧之后执行，避免 `TvMainActivity` 窗口已经出现、但主线程还在做主题读取、
 * 运行时创建和网络监听，用户只能看到窗口背景里的静态三点。
 */
internal fun TvMainActivity.initializeMainAfterFirstFrameImpl(state: Bundle?) {
    val initializeStarted=StartupTrace.now()
    StartupTrace.mark("main.initialize.start")
    // 版本号只有 application 模块的 BuildConfig 才有，这里从包信息读一次（见 AppVersion）。
    StartupTrace.section("main.version") { AppVersion.init(this) }
    // 主题要在建视图之前定下来：先落盘偏好 → 换色 → 丢掉柔光位图缓存（里面烤着上一套材质的投影）。
    // 窗口底色也跟着换，启动那一帧、页面切换的空档都不会闪出另一套材质。
    val themeStarted=StartupTrace.now()
    TvStyle.applyTheme(TvThemeStore.load(this))
    applyWindowBackground()
    StartupTrace.done("main.theme",themeStarted)
    // 性能档位：低配设备保留全部视觉层级，只做四件「纯减负」的事
    // （关焦点缩放 / 只给焦点卡画柔光位图 / 不画海报井暗角 / 柔光收紧一档）。
    //
    // 这里只按**设备**判据先摆好第一帧；本机库里那份「减少动画」偏好要等
    // `loadLibrary()` 读回来，由 [applyPerformanceProfile] 再算一次（见那里）。
    //
    // 判据用 [lowEndTv]，不是 `isLowRamDevice()`：后者来自 `ro.config.low_ram`，
    // 2GB 的电视盒子几乎一律是 false，只看它整个应用就按高端机跑
    // （这正是「按一下方向键就顿一下」的根源，见 compat/Platform.kt 的说明）。
    applyPerformanceProfile()
    val runtimeStarted=StartupTrace.now()
    val libraryStarted=StartupTrace.now()
    library=AppRuntime.library(this)
    StartupTrace.done("main.runtime.library",libraryStarted)
    val artworkStarted=StartupTrace.now()
    artwork=AppRuntime.artwork(this)
    StartupTrace.done("main.runtime.artwork",artworkStarted)
    tvTools=TvTools(this)
    StartupTrace.done("main.runtime",runtimeStarted)
    appScope.launch {
        StartupTrace.mark("main.collector.detail.start")
        detailController.state.collect { state ->
            if(state.seriesId.isBlank()) return@collect
            val detailState=state.detail
            if(detailState!=null && detail?.series?.id==state.seriesId) {
                detail=detailState
                if(screen=="detail" && !detailState.series.author.isNullOrBlank()) {
                    detailAuthorRefresh?.invoke(detailState.series.author)
                }
            }
            relatedFor=state.relatedFor
            relatedWorks=state.related
            relatedLoading=state.relatedLoading
            relatedFailed=state.relatedError!=null
            authorFor=state.authorFor
            authorWorks=state.authorWorks
            authorLoading=state.authorWorksLoading
            authorFailed=state.authorWorksError!=null
            detailAuthorLoading=state.authorLoading
            // **作者作品也要跟着重铺**：原来这里只认 `relatedOpen`，于是点开「作者作品」之后
            // 数据明明回来了、那一栏却还停在「正在查找该作者的作品…」上——用户读到的就是
            // 「一直没有结果返回」（其实请求早就完成了，只是没人重画）。
            if((relatedOpen || authorOpen) && screen=="detail" && detail?.series?.id==state.seriesId) {
                detailBottomRefresh?.invoke()
            }
            if(onPlaybackPage()) playerStripRefresh?.invoke()
        }
    }
    StartupTrace.mark("main.collectors.launched")
    appScope.launch {
        updateController.state.collect { state ->
            updateState=state
            bindUpdateEntry()
        }
    }
    updateController.check()
    // 内存吃紧时由界面把**视口之外**的封面从 ImageView 上摘掉：LRU 的 evictAll 只解除
    // 自己的引用，位图挂在 View 上就仍然活着——首页常驻 77 张（约 44MB）正是这一份。
    artwork.releaseHiddenCovers=initializationArtworkReleaseCallback
    // 调试模式上次开着的话，从这一刻起就把网络 / 接口的日志记进那块面板：
    // 启动阶段的那几十条（TLS、设备注册、首页接口）正是排查「打开就空白」要看的东西，
    // 等到第一页建好再打开就全错过了。
    DebugLog.enabled=runCatching { library.debugMode }.getOrDefault(false)
    // 掉线广播：心跳确认会话失效后发过来（见 [initializationSessionExpiredReceiver]）。
    // targetSdk 29 上显式广播（`setPackage`）不需要 RECEIVER_NOT_EXPORTED 那一套。
    runCatching {
        registerReceiver(
            initializationSessionExpiredReceiver,
            android.content.IntentFilter(com.hgtv.star.shared.data.SessionEvents.ACTION_SESSION_EXPIRED)
        )
    }
    // 封面加载的计时汇总写到 filesDir：真机上抓 logcat 要连电脑、环形缓冲还会把
    // 前面的行冲掉，而「按住下键走几百张」之后那份汇总正是要看的东西。
    // 每次启动**清空重写**，因此文件里永远是最近这一次运行的数据。
    val cleanupStarted=StartupTrace.now()
    if(com.hgtv.star.tv.BuildConfig.DEBUG) {
        val timingFile=java.io.File(filesDir,"artwork-timing.txt")
        runCatching { timingFile.writeText("") }
        artwork.attachTiming(timingFile)
    } else {
        // release **不生成**这个文件：它是调试用的计时汇总（真机上抓 logcat 不方便时看它）。
        // 这里顺手把调试版留下的那一份删掉——从 debug 换到 release 覆盖安装时它会留在
        // `filesDir` 里，既没用又占地方。
        runCatching { java.io.File(filesDir,"artwork-timing.txt").delete() }
        runCatching { java.io.File(filesDir,"verbose").deleteRecursively() }
    }
    StartupTrace.done("main.debugCleanup",cleanupStarted)
    root.setBackgroundColor(bg)
    root.post { StartupTrace.mark("main.shell.firstFrame") }
    // 轻量壳已经出过一帧；现在换成带书库状态的正式加载页。
    if(library.isLoaded) {
        StartupTrace.mark("main.startupLoading.skip")
    } else {
        StartupTrace.section("main.showStartupLoading") { showStartupLoading() }
    }
    // 画面缩放按整屏尺寸算：屏幕一变（换分辨率 / 投屏）就重算一次，
    // 否则缩小后的画面会停在旧尺寸上、四周露出一圈黑边。
    root.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ ->
        if(onPlaybackPage()) { applyVideoScale(); applyResizeMode() }
        // 「回到顶部」浮层的位置只由屏幕尺寸决定（见 list/BackToTop.kt）：
        // 换分辨率 / 投屏 / 旋转之后要按新尺寸重摆一次，否则它还停在旧屏幕的右下角。
        positionBackToTop()
    }
    // 用户选择的形态（手机 / 平板 / 电视）要在建第一屏之前应用：否则第一屏、第一次点按之前
    // 建出来的推荐页浮层 / 播放页控制条都是电视形态，点一下之后整块重建成触屏形态，
    // 两次看到的样子不一样（见 [applyDeviceForm]）。
    applyDeviceForm()
    // 焦点回到页头（品牌 / 分类开关 / 顶部入口）时立刻展开，
    // 否则收起的页头高度是 0，焦点会落在看不见的按钮上。
    root.viewTreeObserver.addOnGlobalFocusChangeListener { _,newFocus ->
        if(topAreaHidden && newFocus!=null && topArea.any { it===newFocus || it.findFocus()!=null }) setTopAreaHidden(false)
        // 记住最近一次焦点落在哪个控件上：右下角「回到顶部」的上 / 左要按回**用户离开列表时
        // 那一张卡**（见 list/BackToTop.kt 的 wireBackToTop）。浮层自己拿到焦点时不覆盖。
        if(newFocus!=null && newFocus!==backToTopView && newFocus.attachedToWindow) backToTopReturn=newFocus.id
        // 焦点移动常会带动列表滚动（reveal 把焦点那一行滚进视口），而那次滚动可能落在
        // 静默期 / `restoring` 里不被上报。等一帧再按真实位置把页头状态摆正（见 [syncHeaderState]）。
        root.post { syncHeaderState() }
    }
    val networkStarted=StartupTrace.now()
    connectivity=connectivityManager() ?: throw IllegalStateException("系统缺少网络服务")
    // 基准值：注册之前先读一次。回调是异步投递的，`registerDefaultNetworkCallback` 会
    // 立刻把**当前**状态回调一遍；基准留成 false 的话，那次就会被读成「刚恢复」，
    // 冷启动正好卡在取地址上时白重来一次。
    networkWasUp=networkAvailable()
    runCatching {
        initializationNetworkWatch=com.hgtv.star.shared.compat.NetworkWatcher.watch(this) {
            main.post {
                // 面板上的「网络」是 5 秒缓存（见 DebugEnv）：切网要当场作废，
                // 否则用户看着面板会以为还挂在旧网络上。
                DebugEnv.invalidate()
                // 「没网 → 有网」这个**边沿**才值得动作：`onCapabilitiesChanged` 会以秒级频率
                // 回调，只看「现在有网」会在每个回调上重来一次起播。
                val up=networkAvailable()
                val restored=up && !networkWasUp
                networkWasUp=up
                if(connectivity.isMeteredConnection() || !up) cancelPrefetch()
                // 起播正卡在「正在获取播放地址…」：网络回来的这一刻就把在途那一发作废、立刻重取。
                //
                // 不做这一步的话，用户要等 okhttp 自己超时才看到下一步——哪怕网络早就好了。
                // 现场：断网切集 → 2 秒后联网，界面一动不动 45 秒（`callTimeout`）才开始自愈，
                // 首帧在 49.6 秒。取地址现在每条实际链接有独立的 20 秒超时
                //（见 `ContentRepository.ADDRESS_ATTEMPT_TIMEOUT_MS`），但那是**兜底**：
                // 能当场知道网络回来了，就不该白白等满。
                if(restored) refetchAddressOnNetwork()
                if(foreground && screen=="player" && playError && autoRecovery) {
                    // 网络**刚回来**、而自动重试正排着等待：把那段等待压掉。
                    //
                    // 那 3 / 6 / 12 秒是为了躲开「平台还在抖」留的间隔，网络真正回来之后
                    // 它只是白等——用户眼里就是「网都好了还停在错误页上数秒」。
                    // 只在**恢复这个边沿**上压缩：只看 `retryPending` 的话，一个「接口在、
                    // 但其实上不了网」的假网络会把 3 次自动重试在十几秒里一次烧光。
                    if(restored && retryPending) {
                        main.removeCallbacks(retryRunnable); retryPending=false
                        retryRunnable.run()
                    } else scheduleRecovery()
                }
            }
        }
    }
    StartupTrace.done("main.networkSetup",networkStarted)
    val favoriteStarted=StartupTrace.now()
    favoriteMonitor=FavoriteMonitor(library,repository) { refreshFavoriteLabels() }
    StartupTrace.done("main.favoriteMonitor",favoriteStarted)
    // 媒体会话不在这里创建：启动即建会把容器/ROM 的 media_session 故障放大成闪退，
    // 第一次同步播放状态时再按需创建（见 ensureMediaSession）。
    // Android 6–9 还要一次外部存储授权，会话镜像（设备身份 + sessionid）才写得出去——
    // 有了它，卸载重装才不必重新走一轮「注册 → 登录 → 发短信」。其他版本这里是空操作。
    StartupTrace.section("main.requestMirrorStorage") { requestMirrorStorage() }
    // 调试包的启动期故障注入（见 [DebugFaults]）：正式包里 BuildConfig.DEBUG 是常量 false，
    // 整段被 R8 删掉。
    if(com.hgtv.star.tv.BuildConfig.DEBUG) main.postDelayed({
        when(DebugFaults.startupFault(this)) {
            // 主线程卡死 60 秒。ANR 阈值是 5 秒，但模拟器/部分 ROM 会用
            // `ro.hw_timeout_multiplier` 把它放大（这台是 5 倍 = 25 秒），所以留足余量。
            // 注意：**必须同时有输入事件在排队**，系统才会判 ANR——注入期间持续按方向键。
            DebugFaults.Mode.ANR -> Thread.sleep(60_000)
            // 直接让 native 段崩一次（SIGSEGV）：验证系统的 tombstone / 崩溃现场这条链路。
            DebugFaults.Mode.NATIVE_CRASH -> {
                val crash=(Class.forName("java.lang.Long").getField("TYPE").get(null) as Class<*>)
                crash.cast(null)
            }
            else -> Unit
        }
    },4000)
    // 预建到内容接口的连接（DNS + TCP + TLS + 验链），见 [ContentRepository.preconnect]。
    // 放在 loadLibrary 之前、`network` 车道上：推荐页那次请求大约在 1 秒后发出，
    // 那时连接已经在池子里，只剩一个 RTT。失败静默，不影响任何功能。
    //
    // 走协程而不是 `io.execute {}`：它挂在 [appScope] 下，页面销毁时一起取消，
    // 不会出现「Activity 已经没了、这条线程还在建连」。
    appScope.network { runCatching { repository.preconnect() } }
    StartupTrace.mark("main.preconnect.queued")
    mainInitialized=true
    if(foreground) {
        main.removeCallbacks(initializationTick)
        main.post(initializationTick)
    }
    loadLibrary()
    StartupTrace.done("main.initialize",initializeStarted)
}
