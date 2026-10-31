// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import com.hgtv.star.phone.ui.PhoneColor
import com.hgtv.star.phone.ui.PhoneStyle
import com.hgtv.star.phone.ui.PhoneTabBar
import com.hgtv.star.phone.ui.PhoneUiScale
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.navigationBarPx
import com.hgtv.star.phone.ui.phoneEdgeToEdge
import com.hgtv.star.phone.page.PhoneCategoryPage
import com.hgtv.star.phone.page.PhoneMinePage
import com.hgtv.star.phone.page.PhoneRankPage
import com.hgtv.star.phone.page.PhoneRecommendPage

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
        const val TAB_DOUBLE_TAP_MS = 320L
        /** 主界面两次返回退出的判定窗口。 */
        const val EXIT_DOUBLE_BACK_MS = 2_000L

        /**
         * 重建前记下的页号。
         *
         * `recreate()` 由 `onConfigurationChanged` 触发时，系统不保证走到
         * `onSaveInstanceState`（`wm size` 改档就是这一条路）；只靠 Bundle 会退回第 0 页。
         * 这里在同一进程里补一份，`onCreate` 取到后立刻清掉，冷启动不受影响。
         */
        var pendingTab = -1
    }

    private lateinit var content: FrameLayout
    private lateinit var tabBar: PhoneTabBar
    private var current = -1
    private val pages = arrayOfNulls<View>(4)
    private var lastTabTapIndex = -1
    private var lastTabTapAt = 0L
    private var lastBackAt = 0L
    /**
     * 建这一屏时用的主题：从设置页回来时如果主题变了，整屏重建一次。
     *
     * 在 [onCreate] 里 `PhoneStyle.ensure` **之后**赋值：属性初始化跑在建视图之前，
     * 那时主题还没读盘，拿到的会是默认值，导致每次启动都白重建一次。
     */
    private var builtTheme = -1
    /** 建这一屏时用的字体 / 控件倍率；从设置页回来变了就整屏重建。 */
    private var builtUiScale = PhoneUiScale.default
    /** 主界面是否在前台：只有推荐流真的在播时才保持屏幕常亮。 */
    private var resumed = false

    /** 调试面板（设置 → 调试模式）：与 TV 一样挂在 root 上，跨 tab 存活。 */
    private var debugOverlay: com.hgtv.star.phone.ui.PhoneDebugOverlay? = null
    /** 面板挂在哪：整窗的 root（不随 tab 重建）。 */
    private lateinit var debugRoot: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PhoneStyle.ensure(this)
        builtTheme = PhoneStyle.theme.ordinal
        builtUiScale = PhoneUiScale.value
        phoneEdgeToEdge()

        val root = FrameLayout(this).apply { setBackgroundColor(PhoneColor.BG) }

        content = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(content)

        tabBar = PhoneTabBar(this).apply {
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
        // 贴底固定：高度 = 栏本身 + 系统导航条，导航条那一截由 PhoneTabBar 自己留白。
        // 不做悬浮胶囊、不留左右边距——新拟态的层次靠材质，不靠「浮起来」。
        root.addView(tabBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(PhoneColor.TAB_BAR_DP.toInt()),
            Gravity.BOTTOM
        ))

        setContentView(root)
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
        val start = restored ?: runCatching {
            val lib = PhoneServices.library(this)
            if (lib.isLoaded && !lib.launchRecommend) 1 else 0
        }.getOrDefault(0)
        debugRoot = root
        show(start)
        PhoneServices.warm(this)
        // 书库读完那一刻把**身份 / 凭据 / 注册 Cookie** 灌进仓库（电视端 `applyAccount()` 的手机版）。
        // 不灌的话 `repository.signedIn` 永远是 false：登录了也不会上传收藏与进度、搜索按匿名设备请求。
        val lib = PhoneServices.library(this)
        lib.loadAsync(
            onFailure = { PhoneIdentity.apply(this, lib) },
            onReady = {
                PhoneIdentity.apply(this, lib)
                // 冷启动那一次 boot 心跳：凭据此刻才从书库灌进仓库，早打等于带半成品身份去问
                //（与电视端 `MainActivityLibrary` 里那一句同一条时序）。
                runCatching { com.hgtv.star.shared.data.SessionBootstrap.onAppStart() }
            },
        )
    }

    override fun rebuildOnSizeChange(newConfig: android.content.res.Configuration): Boolean {
        if (newConfig.orientation != builtOrientation) return false
        pendingTab = current
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (current >= 0) outState.putInt(STATE_TAB, current)
    }

    override fun onBackPressed() {
        val now = SystemClock.uptimeMillis()
        if (now - lastBackAt <= EXIT_DOUBLE_BACK_MS) {
            lastBackAt = 0L
            super.onBackPressed()
            return
        }
        lastBackAt = now
        Toast.makeText(this, "再按一次返回退出", Toast.LENGTH_SHORT).show()
    }

    private fun show(index: Int) {
        android.util.Log.i("HGTV-PAGE", "show index=$index current=$current pages=${pages.map { it?.javaClass?.simpleName }}")
        if (index == current) return
        pages.getOrNull(current)?.let { old ->
            if (old is PhoneRecommendPage) old.onHidden()
        }
        // 直接清空容器再挂目标页：只 removeView 旧页时，若它的 parent 判断落空
        // （重建、attach 时序）就会留下两层页面叠在一起，用户看到的是上一页。
        content.removeAllViews()

        val page = pages[index] ?: createPage(index).also { pages[index] = it }
        content.addView(page, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        current = index
        android.util.Log.i("HGTV-PAGE", "shown index=$index parent=${page.parent} attached=${page.isAttachedToWindow}")
        // tab 的选中态以「实际显示的页」为准：两边一旦不同步，用户点 tab 会被当成
        // 「已经在这一页」而不响应。
        tabBar.setSelected(index)
        // 推荐页的视频流：切走时停解码器，切回时继续播。
        if (page is PhoneRecommendPage) page.onShown()
        syncKeepScreenOn()
        // 调试面板的「页面」字段跟着 tab 走。
        if (::debugRoot.isInitialized) syncDebugOverlay()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        // 设置页可能换了主题（二十套配色）：已经建好的控件上挂着上一套材质的 Drawable，
        // 只改静态色值不会作用到它们，因此这里重建一次（TV 的 `switchTheme` 同样要重建）。
        if (PhoneStyle.theme.ordinal != builtTheme) {
            pendingTab = current
            recreate()
            return
        }
        if (PhoneUiScale.value != builtUiScale) {
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
    }

    override fun onDestroy() {
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
    private fun syncDebugOverlay() {
        val lib = runCatching { PhoneServices.library(this) }.getOrNull() ?: return
        if (!lib.isLoaded) {
            // 书库还在读盘：半秒后再试一次。早先这里直接 return，而刷新只由 `onResume`
            // 触发——冷启动时正好读不完，整块面板就再也不出现了。
            debugRoot.removeCallbacks(debugTicker)
            debugRoot.postDelayed(debugTicker, 500)
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
        val page = arrayOf("推荐", "分类", "榜单", "我的").getOrNull(current) ?: "?"
        overlay.update(
            PhoneDebugReport.lines(
                this, page, lib,
                extras = listOf("清晰度" to "上限 ${lib.maxQuality}P"),
                recent = 8,
            )
        )
        PhoneDebugReport.sampleAsync(this) {
            if (!isFinishing && !isDestroyed) syncDebugOverlay()
        }
        // 「近况」是持续写入的（接口失败、登录失败），面板要跟着刷。
        // 与 TV 一样每秒一次：遍历 40 条环形缓冲再拼几行字符串，代价可以忽略。
        debugRoot.removeCallbacks(debugTicker)
        debugRoot.postDelayed(debugTicker, 1000)
    }

    /** 面板的每秒刷新；关掉调试模式后不再排队（[syncDebugOverlay] 会提前返回）。 */
    private val debugTicker = Runnable { syncDebugOverlay() }

    private fun createPage(index: Int): View = when (index) {
        0 -> PhoneRecommendPage(this).apply {
            onPlayStateChanged = { syncKeepScreenOn() }
        }
        1 -> PhoneCategoryPage(this)
        2 -> PhoneRankPage(this)
        else -> PhoneMinePage(this)
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
        if (playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** 双击底部当前导航按钮：回到这一页列表的第一条。 */
    private fun scrollPageToTop(index: Int) {
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
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }
}
