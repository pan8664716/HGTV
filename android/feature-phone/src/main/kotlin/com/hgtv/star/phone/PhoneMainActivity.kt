// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.hgtv.star.shared.ui.UiText
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.hgtv.star.phone.ui.PhoneColor
import com.hgtv.star.phone.ui.PhoneMainTabSwipeTarget
import com.hgtv.star.phone.ui.PhonePagerMotion
import com.hgtv.star.phone.ui.PhoneStyle
import com.hgtv.star.phone.ui.PhoneTabBar
import com.hgtv.star.phone.ui.PhoneUiScale
import com.hgtv.star.phone.ui.applyPhoneNavigationBar
import com.hgtv.star.phone.ui.bindPhoneInsets
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.phoneEdgeToEdge
import com.hgtv.star.phone.ui.phoneBottomInsetPx
import com.hgtv.star.phone.ui.phoneNavigationBarUsesLightIcons
import com.hgtv.star.phone.ui.phoneTabBarHeightPx
import com.hgtv.star.phone.page.PhoneCategoryPage
import com.hgtv.star.phone.page.PhoneMinePage
import com.hgtv.star.phone.page.PhoneRankPage
import com.hgtv.star.phone.page.PhoneRecommendPage
import com.hgtv.star.shared.architecture.AppExit
import com.hgtv.star.shared.debug.PlaybackStartupTrace
import com.hgtv.star.shared.debug.StartupDeferredTasks
import com.hgtv.star.shared.debug.StartupTrace
import com.hgtv.star.shared.update.AppUpdateController

/**
 * 手机 / 平板端主界面。
 *
 * 结构照官方 `MainFragmentActivity`：全屏内容 + 浮在底部的胶囊导航栏。
 * 页面用「懒创建 + 保留实例」，切回时保留滚动位置。
 */
class PhoneMainActivity : PhoneActivity() {

    private companion object {
        /** 重建（改分辨率 / dpi / 主题）后回到原来那一页。 */
        const val STATE_TAB = "phone_main_tab"
        /** 底部导航同一格连点两次的判定窗口。 */
        const val TAB_DOUBLE_TAP_MS = com.hgtv.star.core.AppConfig.Ui.TAB_DOUBLE_TAP_MS
        /** 主界面两次返回退出的判定窗口。 */
        const val EXIT_DOUBLE_BACK_MS = com.hgtv.star.core.AppConfig.Ui.EXIT_DOUBLE_BACK_MS

        /**
         * 重建前记下的页号。
         *
         * `recreate()` 由 `onConfigurationChanged` 触发时，系统不保证走到
         * `onSaveInstanceState`（`wm size` 改档就是这一条路）；只靠 Bundle 会退回第 0 页。
         * 这里在同一进程里补一份，`onCreate` 取到后立刻清掉，冷启动不受影响。
         */
        var pendingTab = -1
        /** 推荐页重建前选中的体裁，避免转屏后总是回到漫剧。 */
        var pendingRecommendGenre: String? = null
        /** 推荐页重建前正在看的剧，避免横竖屏切换被当成冷启动而跳到下一部。 */
        var pendingRecommendSeriesId: String? = null
        /** 推荐页重建前的播放位置；只有重新 prepare 时才用于续播。 */
        var pendingRecommendPositionMs: Long = -1L
        var pendingCategoryState: PhoneCategoryPage.State? = null
        var pendingRankState: PhoneRankPage.State? = null
        var pendingMineState: PhoneMinePage.State? = null
    }

    private lateinit var content: FrameLayout
    private lateinit var tabBar: PhoneTabBar
    private var current = -1
    private val pages = arrayOfNulls<View>(4)
    private var lastTabTapIndex = -1
    private var lastTabTapAt = 0L
    private var lastBackAt = 0L
    private var mainSwipeTo = -1
    private var mainSwipeOutgoing: View? = null
    private var mainSwipeIncoming: View? = null
    private var mainSwipeGeneration = 0
    /**
     * 建这一屏时用的主题：从设置页回来时如果主题变了，整屏重建一次。
     *
     * 在 [onCreate] 里 `PhoneStyle.ensure` **之后**赋值：属性初始化跑在建视图之前，
     * 那时主题还没读盘，拿到的会是默认值，导致每次启动都白重建一次。
     */
    private var builtTheme = -1
    /** 建这一屏时用的字体 / 控件倍率；从设置页回来变了就整屏重建。 */
    private var builtUiScale = PhoneUiScale.default
    /** 主页在后台时遇到尺寸变化：等回到前台再重建，避免拆掉播放页正在借用的 Surface。 */
    private var rebuildOnResume = false
    /** 主界面是否在前台：只有推荐流真的在播时才保持屏幕常亮。 */
    private var resumed = false
    private var landscape = false
    /** 底栏已经为系统导航区留出的高度；-1 表示还没拿到窗口 inset。 */
    private var tabBarSystemInset = -1

    /** 调试面板（设置 → 调试模式）：与 TV 一样挂在 root 上，跨 tab 存活。 */
    private var debugOverlay: com.hgtv.star.phone.ui.PhoneDebugOverlay? = null
    /** 面板挂在哪：整窗的 root（不随 tab 重建）。 */
    private lateinit var debugRoot: FrameLayout
    /** 设备注册完成后的页面刷新入口；Activity 销毁时必须注销，避免持有旧视图。 */
    private var registrationCleanup: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val traceStarted=StartupTrace.now()
        StartupTrace.mark("phoneMain.onCreate.start")
        PlaybackStartupTrace.point("phoneMain.onCreate")
        super.onCreate(savedInstanceState)
        android.util.Log.w("HGTV-BOOT", "phone.TvMainActivity onCreate")
        StartupTrace.section("phoneMain.style") { PhoneStyle.ensure(this) }
        landscape = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        builtTheme = PhoneStyle.theme.ordinal
        builtUiScale = PhoneUiScale.value
        phoneEdgeToEdge()
        // 先把系统栏颜色和图标统一到首帧主题，避免窗口第一次绘制时
        // 状态栏透明、导航栏仍是主题默认色，等 applySystemBars 才恢复。
        applyPhoneNavigationBar(phoneNavigationBarUsesLightIcons())
        AppUpdateController.get(this).check()

        val root = FrameLayout(this).apply { setBackgroundColor(PhoneColor.BG) }

        content = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(content)

        tabBar = PhoneTabBar(this, landscape).apply {
            onSelect = { index ->
                val now = SystemClock.uptimeMillis()
                val repeated = index == lastTabTapIndex &&
                    now - lastTabTapAt <= TAB_DOUBLE_TAP_MS
                lastTabTapIndex = index
                lastTabTapAt = now
                if (repeated) {
                    lastTabTapIndex = -1
                    lastTabTapAt = 0L
                    scrollPageToTop(index)
                } else {
                    show(index)
                }
            }
        }
        // 导航条底板透明，四枚圆形入口固定不动；页面底部安全距离统一由
        // tabBarInset() 提供，避免列表最后一行被导航或系统导航条压住。
        root.addView(tabBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            phoneTabBarHeightPx(),
            Gravity.BOTTOM
        ))

        setContentView(root)
        root.bindPhoneInsets { bottom -> applySystemBottomInset(bottom) }
        root.post { StartupTrace.mark("phoneMain.shell.firstFrame") }
        // 播放页竖屏要借推荐页的 Surface 出画面（见 `PhoneVideoPage.underlay`）：
        // 把「收起 / 恢复下层控制层」的入口登记给它，否则会看到两套标题、两条进度条。
        PhoneSession.feedChrome = { visible ->
            tabBar.visibility = if (visible) View.VISIBLE else View.GONE
            (pages.getOrNull(current) as? PhoneRecommendPage)?.setUnderlayMode(!visible)
            // 调试面板也压在同一块画面上：播放页自己有一份，这里不能留两份。
            debugOverlay?.visibility =
                if (!visible) View.GONE
                else if (runCatching { PhoneServices.library(this).debugMode }.getOrDefault(false)) View.VISIBLE
                else View.GONE
        }
        // 重建（改分辨率 / dpi / 主题）后回到原来那一页；冷启动才按设置决定首页。
        // 「打开应用进入推荐」（设置 → 内容推荐）：关掉时落到分类页。
        // 与 TV 的 `library.launchRecommend` 同一份落盘值。
        // 进程内的那一份优先：`wm size` 触发的 recreate 可能带着上一次（更早）的
        // Bundle 回来，那个值已经过时；pendingTab 才是「这一下重建前」的页号。
        val restored = pendingTab.takeIf { it in pages.indices }
            ?: savedInstanceState?.getInt(STATE_TAB, -1)?.takeIf { it in pages.indices }
        pendingTab = -1
        debugRoot = root
        registrationCleanup = PhoneIdentity.onRegistrationFinished { outcome ->
            if(outcome==com.hgtv.star.shared.data.DeviceRegistration.Outcome.REGISTERED) {
                (pages.getOrNull(0) as? PhoneRecommendPage)?.onIdentityReady()
            }
        }
        // 书库读完那一刻把**身份 / 凭据 / 注册 Cookie** 灌进仓库（电视端 `applyAccount()` 的手机版）。
        // 不灌的话 `repository.signedIn` 永远是 false：登录了也不会上传收藏与进度、搜索按匿名设备请求。
        val lib = PhoneServices.library(this)
        PhoneIdentity.apply(this, lib)
        if(lib.isLoaded) {
            StartupTrace.mark("phoneMain.library.already")
            // 后台预加载已经完成：这里直接建首屏。
            runCatching { com.hgtv.star.shared.data.SessionBootstrap.onAppStart() }
            if(current==0) warmStartupPlayer(lib)
        } else {
            val libraryStarted=StartupTrace.now()
            StartupTrace.mark("phoneMain.library.start")
            lib.loadAsync(
                onFailure = {
                    StartupTrace.done("phoneMain.library.failed",libraryStarted)
                },
                onReady = {
                    StartupTrace.done("phoneMain.library.ready",libraryStarted)
                    PhoneIdentity.apply(this, lib)
                    // 冷启动那一次 boot 心跳：凭据此刻才从书库灌进仓库，早打等于带半成品身份去问
                    //（与电视端 `MainActivityLibrary` 里那一句同一条时序）。
                    runCatching { com.hgtv.star.shared.data.SessionBootstrap.onAppStart() }
                    if(current==0) warmStartupPlayer(lib)
                },
            )
        }
        // 首屏只等窗口和本地快照；书库读盘与设备注册都在后台继续。
        revealPhoneStartup(restored)
        StartupTrace.done("phoneMain.onCreate",traceStarted)
    }

    override fun rebuildOnSizeChange(newConfig: android.content.res.Configuration): Boolean {
        finishMainTabSwipe()
        rememberPageStates()
        if (!resumed) {
            rebuildOnResume = true
            return false
        }
        // 推荐页的视频 Surface 不能跟着 Activity 一起销毁：横竖屏只让视图原地重排，
        // 底栏尺寸单独刷新。其他页面仍按原逻辑重建。
        if (current == 0) {
            landscape = newConfig.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
            tabBar.setCompact(landscape)
            applyTabBarHeight(tabBarSystemInset.coerceAtLeast(0))
            applyStatusBarFor(0)
            if (Build.VERSION.SDK_INT >= 20) window.decorView.requestApplyInsets()
            return false
        }
        pendingTab = current
        return true
    }

    private fun rememberPageStates() {
        finishMainTabSwipe()
        pendingRecommendGenre = null
        pendingRecommendSeriesId = null
        pendingRecommendPositionMs = -1L
        (pages.getOrNull(0) as? PhoneRecommendPage)?.let { page ->
            pendingRecommendGenre = page.currentGenreKey()
            pendingRecommendSeriesId = page.currentSeriesId()
            pendingRecommendPositionMs = page.currentPositionMs()
        }
        pendingCategoryState =
            (pages.getOrNull(1) as? PhoneCategoryPage)?.snapshotState()
        pendingRankState =
            (pages.getOrNull(2) as? PhoneRankPage)?.snapshotState()
        pendingMineState =
            (pages.getOrNull(3) as? PhoneMinePage)?.snapshotState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        finishMainTabSwipe()
        super.onSaveInstanceState(outState)
        if (current >= 0) outState.putInt(STATE_TAB, current)
    }

    override fun onBackPressed() {
        finishMainTabSwipe()
        val now = SystemClock.uptimeMillis()
        if (now - lastBackAt <= EXIT_DOUBLE_BACK_MS) {
            lastBackAt = 0L
            AppExit.exit(this) {
                (pages.getOrNull(current) as? PhoneRecommendPage)?.onHidden()
                PhoneServices.releaseForExit()
                PhoneSession.reset()
                PhoneServices.library(this).flush()
                runCatching { com.hgtv.star.shared.data.SessionBootstrap.shutdown() }
                PhoneDebugReport.resetSamples()
            }
            return
        }
        lastBackAt = now
        Toast.makeText(this, UiText.get(com.hgtv.star.shared.R.string.ui_0036), Toast.LENGTH_SHORT).show()
    }

    /** 放行首屏。只执行一次；书库或注册失败也继续显示页面骨架。 */
    private fun revealPhoneStartup(restored: Int?) {
        val startedAt=StartupTrace.now()
        StartupTrace.mark("phoneMain.firstContent.start")
        PlaybackStartupTrace.point("phoneMain.firstContent.start")
        content.visibility = View.VISIBLE
        tabBar.visibility = View.VISIBLE
        val lib = PhoneServices.library(this)
        val start = restored ?: if (lib.isLoaded && !lib.launchRecommend) 1 else 0
        if (current < 0) show(start)
        // 与 TV 一样在进入分类页之前就把默认分类准备好；用户点分类时直接铺结果，
        // 不再现场创建页面并重新发第一页请求。
        debugRoot.post {
            StartupTrace.done("phoneMain.firstContent.render",startedAt)
            StartupTrace.mark("phoneMain.firstContent.firstFrame")
            PlaybackStartupTrace.point("phoneMain.firstContent.firstFrame")
            Thread({
                StartupTrace.section("phoneMain.servicesWarm") {
                    PhoneServices.warm(applicationContext)
                }
            }, "hgtv-phone-network-warm").apply { isDaemon = true }.start()
            if(!isFinishing && !isDestroyed && current==0) warmStartupPlayer(lib)
            // 分类页不是首帧依赖。等推荐首帧或 12 秒兜底后再建，避免和推荐 / 注册抢带宽。
            StartupDeferredTasks.schedule(
                "phone.categoryPreload",
                com.hgtv.star.core.AppConfig.Startup.DEFERRED_TASK_TIMEOUT_MS,
            ) {
                debugRoot.post {
                    if(!isFinishing && !isDestroyed) preloadCategoryPage()
                }
            }
        }
        // 首次进入提示「字号 / 每行卡片数可以在设置里调」。等首屏画出来再弹，
        // 否则窗口还没 attach，对话框会挂在一个空 Activity 上。
        debugRoot.post { if (!isFinishing && !isDestroyed) PhoneFirstRunTip.ensure(this) }
    }

    private fun preloadCategoryPage() {
        if(pages[1]!=null) return
        pages[1]=createPage(1)
    }

    private fun warmStartupPlayer(lib: com.hgtv.star.shared.data.Library) {
        if(!lib.isLoaded || current!=0) return
        PhoneServices.warmPlayer(this,lib.decoderMode)
        PlaybackStartupTrace.point("player.prewarm")
    }

    private fun show(index: Int) {
        finishMainTabSwipe()
        android.util.Log.i("HGTV-PAGE", "show index=$index current=$current pages=${pages.map { it?.javaClass?.simpleName }}")
        // 状态栏跟着 tab 走：推荐页是满屏视频，顶部不挂状态栏；其余三页是列表，照常显示。
        // 放在 `index == current` 早退之前：切回同一个 tab（双击回到顶部）时也要对一次。
        applyStatusBarFor(index)
        if (index == current) {
            pages.getOrNull(index)?.let(::resetPagePosition)
            return
        }
        pages.getOrNull(current)?.let { old ->
            if (old is PhoneRecommendPage) old.onHidden()
        }
        // 直接清空容器再挂目标页：只 removeView 旧页时，若它的 parent 判断落空
        // （重建、attach 时序）就会留下两层页面叠在一起，用户看到的是上一页。
        content.removeAllViews()

        val page = pages[index] ?: createPage(index).also { pages[index] = it }
        resetPagePosition(page)
        content.addView(page, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        current = index
        applyPageBottomInsets()
        android.util.Log.i("HGTV-PAGE", "shown index=$index parent=${page.parent} attached=${page.isAttachedToWindow}")
        // tab 的选中态以「实际显示的页」为准：两边一旦不同步，用户点 tab 会被当成
        // 「已经在这一页」而不响应。
        tabBar.setSelected(index)
        // 推荐页的视频流：切走时停解码器，切回时继续播。
        if (page is PhoneRecommendPage) page.onShown()
        // 分类页的小分类请求可能在切走时被取消，切回时补拉缺失的面板。
        if (page is PhoneCategoryPage) page.onShown()
        syncKeepScreenOn()
        // 调试面板的「页面」字段跟着 tab 走。
        if (::debugRoot.isInitialized) syncDebugOverlay()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) finishMainTabSwipe()
        return super.dispatchTouchEvent(ev)
    }

    private fun canStartMainTabSwipe(direction: Int): Boolean {
        val target = current + direction
        return current in pages.indices && target in pages.indices
    }

    private fun startMainTabSwipe(direction: Int) {
        finishMainTabSwipe()
        if (!canStartMainTabSwipe(direction)) return
        val from = current
        val to = from + direction
        val outgoing = (content.getChildAt(0) as? View) ?: pages.getOrNull(from) ?: return
        val incoming = pages[to] ?: createPage(to).also { pages[to] = it }
        val width = content.width.coerceAtLeast(1)
        if (reduceMainTabAnimation()) {
            resetPagePosition(outgoing)
            show(to)
            return
        }
        resetPagePosition(outgoing)
        resetPagePosition(incoming)
        (incoming.parent as? ViewGroup)?.removeView(incoming)
        incoming.translationX = direction * width.toFloat()
        content.addView(incoming, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        content.bringChildToFront(incoming)
        applyPageBottomInsets()
        if (outgoing is PhoneRecommendPage) outgoing.onHidden()
        when (incoming) {
            is PhoneRecommendPage -> incoming.onShown()
            is PhoneCategoryPage -> incoming.onShown()
        }

        mainSwipeTo = to
        mainSwipeOutgoing = outgoing
        mainSwipeIncoming = incoming
        val generation = ++mainSwipeGeneration
        var focusSent = false
        val duration = PhonePagerMotion.duration(
            distancePx = width.toFloat(),
            viewportPx = width,
        )
        outgoing.animate()
            .translationX(-direction * width.toFloat())
            .setDuration(duration)
            .setInterpolator(PhonePagerMotion.interpolator)
            .start()
        incoming.animate()
            .translationX(0f)
            .setDuration(duration)
            .setInterpolator(PhonePagerMotion.interpolator)
            .setUpdateListener {
                if (generation != mainSwipeGeneration || focusSent) return@setUpdateListener
                val progress =
                    (1f - kotlin.math.abs(incoming.translationX) / width.toFloat())
                        .coerceIn(0f, 1f)
                if (progress >= PhonePagerMotion.FOCUS_SWITCH_PROGRESS) {
                    focusSent = true
                    tabBar.setSelected(to)
                    applyStatusBarFor(to)
                }
            }
            .withEndAction {
                if (generation == mainSwipeGeneration) finishMainTabSwipe()
            }
            .start()
    }

    private fun finishMainTabSwipe() {
        val to = mainSwipeTo
        if (to < 0) return
        val incoming = mainSwipeIncoming
        val outgoing = mainSwipeOutgoing
        mainSwipeGeneration++
        outgoing?.animate()?.setUpdateListener(null)
        outgoing?.animate()?.cancel()
        incoming?.animate()?.setUpdateListener(null)
        incoming?.animate()?.cancel()
        outgoing?.translationX = 0f
        if (outgoing?.parent === content) content.removeView(outgoing)
        incoming?.translationX = 0f
        if (incoming != null) pages[to] = incoming
        current = to
        mainSwipeTo = -1
        mainSwipeOutgoing = null
        mainSwipeIncoming = null
        tabBar.setSelected(to)
        applyStatusBarFor(to)
        applyPageBottomInsets()
        syncKeepScreenOn()
        if (::debugRoot.isInitialized) syncDebugOverlay()
    }

    private fun reduceMainTabAnimation(): Boolean = runCatching {
        PhoneServices.library(this).takeIf { it.isLoaded }?.reduceAnim == true
    }.getOrDefault(false)

    private fun resetPagePosition(page: View) {
        page.animate().setUpdateListener(null)
        page.animate().cancel()
        page.translationX = 0f
    }

    /**
     * 按当前 tab 决定状态栏显不显示。
     *
     * 推荐页是一整屏竖屏视频（和抖音那类信息流一样），状态栏压在上面既挡画面、
     * 又让顶部的体裁标签下移一截；分类 / 榜单 / 我的 是普通列表页，状态栏照常。
     *
     * 只动状态栏，导航栏保持可见：底部 tab 栏本来就给导航条留了白，
     * 一起藏掉会让「我的」这类页面少一截安全距离。
     */
    private fun applyStatusBarFor(index: Int) {
        if (landscape) {
            applyLandscapeImmersive()
            return
        }
        applySystemBars(
            immersive = index == 0,
            hideNavigation = false,
            lightNavigationIcons = index != 0 && phoneNavigationBarUsesLightIcons(),
        )
    }

    private fun applySystemBottomInset(bottom: Int) {
        if (bottom == tabBarSystemInset) return
        tabBarSystemInset = bottom
        applyTabBarHeight(bottom)
        applyPageBottomInsets()
    }

    private fun applyPageBottomInsets() {
        val bottom = phoneBottomInsetPx()
        (pages.getOrNull(0) as? PhoneRecommendPage)?.applySystemBottomInset(bottom)
        (pages.getOrNull(1) as? PhoneCategoryPage)?.applySystemBottomInset(bottom)
        (pages.getOrNull(2) as? PhoneRankPage)?.applySystemBottomInset(bottom)
        (pages.getOrNull(3) as? PhoneMinePage)?.applySystemBottomInset(bottom)
    }

    private fun applyTabBarHeight(bottomInset: Int) {
        tabBar.setPadding(0, 0, 0, bottomInset)
        (tabBar.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
            params.height = phoneTabBarHeightPx() + bottomInset
            tabBar.layoutParams = params
        }
    }

    @Suppress("DEPRECATION")
    private fun applySystemBars(
        immersive: Boolean,
        hideNavigation: Boolean,
        lightNavigationIcons: Boolean = false,
    ) {
        // Android 17 上仅设置旧的全屏标志可能仍保留状态栏底板。
        // 先把窗口铺到整个屏幕，再关闭 decor fit，最后应用系统栏显隐。
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        if (immersive || hideNavigation) {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        if (Build.VERSION.SDK_INT >= 21) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = if (hideNavigation) Color.BLACK else Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        }
        window.decorView.systemUiVisibility = when {
            hideNavigation -> (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
            immersive -> (
                // 隐藏状态栏并让内容铺到它的位置（`phoneEdgeToEdge()` 已经打开了 LAYOUT_FULLSCREEN）。
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                )
            else -> View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        applyPhoneNavigationBar(if (hideNavigation) false else lightNavigationIcons)
        if (hideNavigation) window.navigationBarColor = Color.BLACK
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let { controller ->
                if (immersive || hideNavigation) {
                    controller.hide(android.view.WindowInsets.Type.statusBars())
                } else {
                    controller.show(android.view.WindowInsets.Type.statusBars())
                }
                if (hideNavigation) {
                    controller.hide(android.view.WindowInsets.Type.navigationBars())
                } else {
                    controller.show(android.view.WindowInsets.Type.navigationBars())
                }
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (!hideNavigation) {
                    controller.setSystemBarsAppearance(
                        if (lightNavigationIcons) {
                            android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                        } else {
                            0
                        },
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    )
                }
            }
        }
        window.setBackgroundDrawable(ColorDrawable(PhoneColor.BG))
        if (::content.isInitialized) {
            content.requestLayout()
            if (Build.VERSION.SDK_INT >= 20) window.decorView.requestApplyInsets()
        }
    }

    /** 横屏主页隐藏系统状态栏与导航栏，内容直接铺满窗口。 */
    private fun applyLandscapeImmersive() {
        applySystemBars(
            immersive = true,
            hideNavigation = true,
            lightNavigationIcons = false,
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && current >= 0) {
            applyStatusBarFor(current)
            syncKeepScreenOn()
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (rebuildOnResume) {
            rebuildOnResume = false
            rememberPageStates()
            pendingTab = current
            recreate()
            return
        }
        // 设置页可能换了主题（二十套配色）：已经建好的控件上挂着上一套材质的 Drawable，
        // 只改静态色值不会作用到它们，因此这里重建一次（TV 的 `switchTheme` 同样要重建）。
        if (PhoneStyle.theme.ordinal != builtTheme) {
            rememberPageStates()
            pendingTab = current
            recreate()
            return
        }
        if (PhoneUiScale.value != builtUiScale) {
            rememberPageStates()
            pendingTab = current
            recreate()
            return
        }
        // 回到前台：补一次会话心跳并重排 10 分钟（电视端 `onForeground()` 的同一条路）。
        runCatching { com.hgtv.star.shared.data.SessionBootstrap.onForeground() }
        // 从播放页 / 搜索页返回：推荐页的解码器在离开时被释放了，这里重建并接着播。
        (pages.getOrNull(current) as? PhoneRecommendPage)?.onShown()
        // 「我的」页：登录状态、账号侧栏目、收藏更新都可能已经变了，回来时重铺一次。
        (pages.getOrNull(current) as? PhoneMinePage)?.onShown()
        // 分类 / 榜单也可能保留着上一档列宽；回来时统一按设置重新对齐一次。
        (pages.getOrNull(current) as? PhoneCategoryPage)?.onShown()
        (pages.getOrNull(current) as? PhoneRankPage)?.onShown()
        // 调试模式：在设置页里开关过，回到主界面立刻生效（与播放页同一份实现）。
        syncDebugOverlay()
        syncKeepScreenOn()
        // 从播放页 / 设置页回来：系统栏状态可能被别的窗口改过（播放页是全沉浸的），
        // 这里按当前 tab 再对一次，避免「回到推荐页却挂着状态栏」。
        if (current >= 0) applyStatusBarFor(current)
    }

    override fun onDestroy() {
        registrationCleanup?.invoke()
        registrationCleanup = null
        (pages.getOrNull(1) as? PhoneCategoryPage)?.release()
        debugRoot.removeCallbacks(debugTicker)
        PhoneDebugReport.resetSamples()
        // 入口只属于本实例：留着会在重建后的新界面上被旧闭包改到旧控件。
        if (PhoneSession.feedChrome != null) {
            PhoneSession.feedChrome = null
            PhoneSession.resetFeedChrome()
        }
        // 本实例的视图要没了：借画面的入口与「输出在哪一块」都得清掉，
        // 否则播放页会拿着一个已经不在窗口上的 Surface 继续出画面（一块黑）。
        PhoneSession.bindFeedSurface(null)
        PhoneSession.feedVideoOffset = null
        PhoneSession.feedVideoResize = null
        PhoneSession.feedVideoScale = null
        PhoneSession.underlayActive = false
        super.onDestroy()
    }

    override fun onPause() {
        finishMainTabSwipe()
        super.onPause()
        resumed = false
        syncKeepScreenOn()
        // 跳去别的页面（播放页、搜索页）时释放解码器，否则会听到两条音轨。
        // 交给播放页的那一路不受影响：交接窗口内 `onHidden` 不会按停（见 `PhoneFeedView.onHidden`）。
        (pages.getOrNull(current) as? PhoneRecommendPage)?.onHidden()
    }

    override fun onStop() {
        super.onStop()
        // 兜底：交接窗口还开着、而这一路**仍归推荐页**，说明接手方没接上
        //（例如按了返回又立刻退到后台）。这时把它按停，别在后台继续出声。
        if (PhoneSession.inHandoff() && PhoneSession.owner == PhoneSession.Owner.FEED) {
            PhoneSession.endHandoff()
            PhoneServices.peekPlayer()?.pause()
        }
    }

    /**
     * 主界面的调试面板：**接口与登录失败**主要发生在这几页，用户报「搜不到」时靠它定位。
     *
     * 与播放页那块是同一个类、同一套 TV 对齐字段；这里没有播放现场，固定显示页面、
     * 设备、形态、解码、性能、TLS 与近况。电视上的同类面板也是这个分工。
     */
    internal fun syncDebugOverlay() {
        val lib = runCatching { PhoneServices.library(this) }.getOrNull() ?: return
        if (!lib.isLoaded) {
            // 书库还在读盘：半秒后再试一次。早先这里直接 return，而刷新只由 `onResume`
            // 触发——冷启动时正好读不完，整块面板就再也不出现了。
            debugRoot.removeCallbacks(debugTicker)
            debugRoot.postDelayed(
                debugTicker,
                com.hgtv.star.core.AppConfig.Ui.DEBUG_TICK_MS / 2,
            )
            return
        }
        val on = runCatching { lib.debugMode }.getOrDefault(false)
        com.hgtv.star.shared.debug.DebugLog.enabled = on
        if (!on) {
            if (debugOverlay?.visibility == View.VISIBLE) PhoneDebugReport.resetSamples()
            debugOverlay?.visibility = View.GONE
            return
        }
        com.hgtv.star.shared.debug.debugSink = { tag, line ->
            com.hgtv.star.shared.debug.DebugLog.record(tag, line)
        }
        val overlay = debugOverlay ?: com.hgtv.star.phone.ui.PhoneDebugOverlay(this).also {
            debugOverlay = it
            com.hgtv.star.phone.ui.PhoneDebugOverlay.place(it, debugRoot, statusBarHeight() + dp(4))
        }
        val page = arrayOf(UiText.get(com.hgtv.star.shared.R.string.ui_0320), UiText.get(com.hgtv.star.shared.R.string.ui_0303), UiText.get(com.hgtv.star.shared.R.string.ui_0324), UiText.get(com.hgtv.star.shared.R.string.ui_0317)).getOrNull(current) ?: "?"
        overlay.update(
            PhoneDebugReport.lines(
                this, page, lib,
                extras = listOf(
                    UiText.get(com.hgtv.star.shared.R.string.ui_0365) to
                        UiText.get(com.hgtv.star.shared.R.string.ui_0295, lib.maxQuality),
                ),
                recent = 8,
            )
        )
        PhoneDebugReport.sampleAsync(this) {
            if (!isFinishing && !isDestroyed) syncDebugOverlay()
        }
        // 「近况」是持续写入的（接口失败、登录失败），面板要跟着刷。
        // 与 TV 一样每秒一次：遍历 40 条环形缓冲再拼几行字符串，代价可以忽略。
        debugRoot.removeCallbacks(debugTicker)
            debugRoot.postDelayed(
                debugTicker,
                com.hgtv.star.core.AppConfig.Ui.DEBUG_TICK_MS,
            )
    }

    /** 面板的每秒刷新；关掉调试模式后不再排队（[syncDebugOverlay] 会提前返回）。 */
    private val debugTicker = Runnable { syncDebugOverlay() }

    private fun createPage(index: Int): View {
        val page: View = when (index) {
            0 -> {
                val genre = pendingRecommendGenre
                val seriesId = pendingRecommendSeriesId
                val positionMs = pendingRecommendPositionMs
                pendingRecommendGenre = null
                pendingRecommendSeriesId = null
                pendingRecommendPositionMs = -1L
                PhoneRecommendPage(this, genre, seriesId, positionMs).apply {
                    onPlayStateChanged = { syncKeepScreenOn() }
                }
            }
            1 -> {
                val state = pendingCategoryState
                pendingCategoryState = null
                PhoneCategoryPage(this, state)
            }
            2 -> {
                val state = pendingRankState
                pendingRankState = null
                PhoneRankPage(this, state)
            }
            else -> {
                val state = pendingMineState
                pendingMineState = null
                PhoneMinePage(this, state)
            }
        }
        (page as? PhoneMainTabSwipeTarget)?.apply {
            canSwipeToMainTab = { direction ->
                finishMainTabSwipe()
                pages.getOrNull(current) === page && canStartMainTabSwipe(direction)
            }
            onSwipeToMainTab = { direction ->
                finishMainTabSwipe()
                if (pages.getOrNull(current) === page) startMainTabSwipe(direction)
            }
        }
        return page
    }

    /**
     * 推荐流播放时保持屏幕常亮。
     *
     * 这句与正式播放页各管各的窗口：推荐页交给播放页时先按暂停 / 交接更新，
     * 播放页自己的 `onResume` 再接手，不会把「只停在推荐页暂停画面」误当成正在播。
     */
    private fun syncKeepScreenOn() {
        val playing = resumed &&
            (pages.getOrNull(current) as? PhoneRecommendPage)?.isPlayingNow() == true
        PhonePlayerViewSupport.keepScreenOn(window, playing)
    }

    /** 双击底部当前导航按钮：回到这一页列表的第一条。 */
    private fun scrollPageToTop(index: Int) {
        finishMainTabSwipe()
        when (val page = pages.getOrNull(index)) {
            is PhoneRecommendPage -> page.scrollToTop()
            is PhoneCategoryPage -> page.scrollToTop()
            is PhoneRankPage -> page.scrollToTop()
            is PhoneMinePage -> page.scrollToTop()
            else -> Unit
        }
    }

    /**
     * 内容顶到状态栏后面（沉浸式），状态栏本身透明。
     *
     * 4.4 只有 `FLAG_TRANSLUCENT_STATUS`，5.0+ 才有 `statusBarColor`。
     */
    private fun applyEdgeToEdge() {
        runCatching {
            if (Build.VERSION.SDK_INT >= 21) {
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                window.statusBarColor = Color.TRANSPARENT
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            } else if (Build.VERSION.SDK_INT >= 19) {
                window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            }
        }
    }

    /** 状态栏高度；取不到时按 24dp 兜底。 */
    fun statusBarHeight(): Int {
        if (landscape) return 0
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }
}
