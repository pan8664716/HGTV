// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.content.Intent
import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import com.hgtv.star.core.DANMAKU_MERGE_MIN_RANGE
import com.hgtv.star.core.BackupData
import com.hgtv.star.core.DanmakuMode
import com.hgtv.star.core.DanmakuSpeed
import com.hgtv.star.core.DanmakuStyle
import com.hgtv.star.core.EpisodePageSize
import com.hgtv.star.core.LibraryBackup
import com.hgtv.star.core.PLAYER_PANEL_TIMEOUT_RANGE
import com.hgtv.star.core.PlaybackQuality
import com.hgtv.star.core.PlaybackSpeed
import com.hgtv.star.core.SearchSource
import com.hgtv.star.core.VideoDecoderMode
import com.hgtv.star.core.VideoFrameMode
import com.hgtv.star.phone.ui.PhoneDialog
import com.hgtv.star.phone.ui.PhoneSettingSlider
import com.hgtv.star.phone.ui.PhoneSoft.applySoft
import com.hgtv.star.phone.ui.PhoneStyle
import com.hgtv.star.phone.ui.PhoneSwitchRow
import com.hgtv.star.phone.ui.PhoneUiScale
import com.hgtv.star.phone.ui.phoneEdgeToEdge
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.phoneSize
import com.hgtv.star.shared.ProjectInfo
import com.hgtv.star.shared.compat.DeviceForm
import com.hgtv.star.shared.compat.detectDeviceForm
import com.hgtv.star.shared.data.LocalReset
import com.hgtv.star.shared.architecture.AppRelaunch
import com.hgtv.star.shared.feature.lan.LanSyncController
import com.hgtv.star.shared.feature.settings.SettingRowSpec
import com.hgtv.star.shared.feature.settings.SettingsController
import com.hgtv.star.shared.lan.DeviceSyncPackage
import com.hgtv.star.shared.lan.DeviceSyncPayload
import com.hgtv.star.shared.lan.DeviceSyncSelection
import com.hgtv.star.shared.lan.LanLibrarySyncSession
import com.hgtv.star.shared.lan.LanSyncPeer
import com.hgtv.star.shared.lan.lanIpv4Address
import com.hgtv.star.shared.ui.SoftStyle
import com.hgtv.star.shared.ui.TvTheme
import com.hgtv.star.shared.ui.TvThemeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 手机设置页的四个大项，名称和内容边界与电视端 `SETTINGS_TABS` 一致。 */
enum class PhoneSettingsPage(val label: String, val hint: String) {
    PLAYBACK("播放交互", "播放与弹幕"),
    APPEARANCE("外观导航", "主题与列表"),
    RECOMMEND("内容推荐", "推荐与来源"),
    SYSTEM("系统高级", "数据与性能"),
}

private const val LAN_SYNC_SOURCE_TTL_MS = Long.MAX_VALUE
internal const val TAG_LANDSCAPE_SETTING_ROW = "landscape-setting-row"
internal const val TAG_LANDSCAPE_SETTING_SPACER = "landscape-setting-spacer"
internal const val SETTINGS_DENSITY_SCALE = .9f
internal const val SETTINGS_TEXT_SCALE = 1f

/**
 * 设置页：**与 TV 端一一对应**。
 *
 * 电视端 `MainActivitySettings.kt` 里的四大组（播放交互 / 外观导航 / 内容推荐 / 系统高级）
 * 在这里全部重建了一遍，写的是**同一份落盘数据**（`Library` 的各个属性、`TvThemeStore`），
 * 因此在手机上改完，电视端读到的就是同一个值（同一台设备上两端不会同时存在，但备份与
 * 以后的多端同步都不会出现两套设置）。
 *
 * 两端只有**呈现方式**不同：
 *
 * * TV 是「四个大项 + 遥控器焦点」，手机是「四个大项 + 页签」——触摸屏没有焦点，
 *   当前页签与分组标题就是层级；
 * * TV 的「点开选一项」是右侧弹出的选择器，手机用同一套胶囊选项的 `PhoneDialog`；
 * * TV 的滑动条是自绘的 `SettingSlider`，手机用同一结构的 `PhoneSettingSlider`
 *   （手指点按或拖动），读数与区间完全一致。
 *
 * 遥控器按键、顶部导航显隐这类只在电视形态生效的项不在手机上重复列出。
 */
class PhoneSettingsActivity : PhoneActivity() {

    private lateinit var container: LinearLayout
    internal val settingsContainer get()=container
    internal val settingsContainerInitialized get()=::container.isInitialized
    /** 外层滚动容器：重建列表时用它保住当前的滚动位置。 */
    private lateinit var scroller: ScrollView
    internal val settingsScroller get()=scroller
    internal val settingsScrollerInitialized get()=::scroller.isInitialized
    /** 横屏时四个大项移到顶部，和 TV 设置页一致。 */
    private lateinit var pageTabs: LinearLayout
    private var landscape = false
    internal val settingsLandscape get()=landscape
    /** 当前分区面板；竖屏为 null，继续使用原来的平铺列表。 */
    private var sectionHost: LinearLayout? = null
    internal var settingsSectionHost: LinearLayout?
        get()=sectionHost
        set(value) { sectionHost=value }
    private val library by lazy { PhoneServices.library(this) }
    internal val settingsLibrary get()=library
    private val settingsController by lazy { SettingsController(this, library) }
    internal val settingsControllerInstance get()=settingsController
    private var page = PhoneSettingsPage.PLAYBACK
    private val syncHandler = Handler(Looper.getMainLooper())
    internal val settingsSyncHandler get()=syncHandler
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val settingsScope get()=scope
    private val lanSyncController by lazy { LanSyncController(this, library, scope) }
    internal val settingsLanSyncController get()=lanSyncController
    private var lanSyncJob: Job? = null
    internal var settingsLanSyncJob: Job?
        get()=lanSyncJob
        set(value) { lanSyncJob=value }
    private var lanSyncDialog: PhoneDialog? = null
    internal var settingsLanSyncDialog: PhoneDialog?
        get()=lanSyncDialog
        set(value) { lanSyncDialog=value }
    private var lanSyncStep: LanSyncController.Step? = null
    internal var settingsLanSyncStep: LanSyncController.Step?
        get()=lanSyncStep
        set(value) { lanSyncStep=value }
    private var lanSyncStatus: TextView? = null
    internal var settingsLanSyncStatus: TextView?
        get()=lanSyncStatus
        set(value) { lanSyncStatus=value }

    /**
     * 设置页重建时恢复到的位置。
     *
     * 只记像素不够：字体或主题换档后每一行的高度会变，同一个 scrollY 已经指着另一项。
     * 这里记「第一项可见设置」的稳定 key 与它相对屏幕顶部的偏移，重建后按同一个 key 找回。
     */
    private var pendingScrollAnchor: ScrollAnchor? = null
    private var scrollRestoreListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    internal var settingsScrollRestoreListener: ViewTreeObserver.OnGlobalLayoutListener?
        get()=scrollRestoreListener
        set(value) { scrollRestoreListener=value }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val storedPage = savedInstanceState?.getString(KEY_PAGE)
            ?: intent.getStringExtra(EXTRA_PAGE)
        page = PhoneSettingsPage.entries.firstOrNull { it.name == storedPage }
            ?: PhoneSettingsPage.PLAYBACK
        PhoneStyle.ensure(this)
        phoneEdgeToEdge()
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        savedInstanceState?.let { state ->
            val key = state.getString(KEY_SCROLL_ANCHOR).orEmpty()
            if (key.isNotBlank()) {
                pendingScrollAnchor = ScrollAnchor(key, state.getInt(KEY_SCROLL_OFFSET, 0))
            }
        }

        val root = FrameLayout(this).apply { setBackgroundColor(PhoneStyle.background) }

        val status = if (landscape) 0 else statusBarHeight()
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (landscape) {
                // 横屏沉浸式会隐藏状态栏，顶部页签需要留出触屏边缘的呼吸空间。
                setPadding(settingsDp(12), settingsDp(10), settingsDp(12), settingsDp(6))
            } else {
                setPadding(dp(16), status + dp(6), dp(16), dp(8))
            }
        }
        if (!landscape) {
            bar.addView(TextView(this).apply {
                text = "设置"
                setTextColor(PhoneStyle.text)
                phoneSize(19)
                setTypeface(typeface, Typeface.BOLD)
            })
        }
        if (landscape) {
            bar.addView(TextView(this).apply {
                text = "设置"
                setTextColor(PhoneStyle.text)
                phoneSize(13)
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            pageTabs = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            bar.addView(pageTabs, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(12) })
            bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        }
        root.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
        ))

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(if (landscape) 20 else 16),
                dp(2),
                dp(if (landscape) 20 else 16),
                dp(if (landscape) 4 else 40),
            )
        }
        scroller = ScrollView(this).apply {
            isFillViewport = true
            addView(container, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        // 标题栏下沿到第一个设置项之间的留白。
        //
        // 原来滚动区从 `status + dp(52)` 起步、内容顶部再留 `dp(8)`，而标题栏的实际高度
        // 只有 `status + dp(6) + 一行标题 + dp(8)`（1080P 上约 status + 76px）——两者之间
        // 空出 80 多像素，读到的就是「四个大选项上面一大块空白」。这里让滚动区贴住标题栏
        // 下沿（只留 dp(30) 的呼吸位），内容顶部内边距一并收到 dp(2)：
        // 空白从 100 多像素收到 40 多像素，同时滚动时内容也不会在标题下方很远处才开始被裁切。
        root.addView(scroller, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { topMargin = if (landscape) dp(40) else status + dp(30) })

        setContentView(root)
        if (landscape) applyLandscapeImmersive()

        if (library.isLoaded) render()
        else library.loadAsync(onFailure = { runOnUiThread { render() } }, onReady = { runOnUiThread { render() } })
    }

    /** 横屏设置页使用沉浸式：状态栏和底部系统导航都隐藏，只保留一小条分区导航。 */
    @Suppress("DEPRECATION")
    private fun applyLandscapeImmersive() {
        if (!landscape) return
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(
                    android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars()
                )
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        window.decorView.post {
            if (!landscape || isFinishing || isDestroyed) return@post
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                window.insetsController?.hide(
                    android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars()
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (landscape) applyLandscapeImmersive()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && landscape) applyLandscapeImmersive()
    }

    // ---------------------------------------------------------------- 渲染

    private fun render(resetScroll: Boolean = false) {
        // 改一项就整页重建（值是同一份落盘数据，重建最省事），但**滚动位置要保住**：
        // 否则在页面下半部分拨一下开关，视线就被弹回顶部，用户会以为点错了。
        val anchor = if (resetScroll) null else pendingScrollAnchor ?: currentScrollAnchor()
        pendingScrollAnchor = null
        container.removeAllViews()
        sectionHost = null
        if (!library.isLoaded) {
            container.addView(TextView(this).apply {
                text = "设置读取失败"
                setTextColor(PhoneStyle.textFaint)
                phoneSize(13)
                gravity = Gravity.CENTER
                setPadding(0, dp(40), 0, 0)
            })
            return
        }

        renderPageSelector()
        if (landscape) {
            when (page) {
                PhoneSettingsPage.PLAYBACK -> renderLandscapePlayback()
                PhoneSettingsPage.APPEARANCE -> renderLandscapeAppearance()
                PhoneSettingsPage.RECOMMEND -> renderLandscapeRecommend()
                PhoneSettingsPage.SYSTEM -> renderLandscapeSystem()
            }
        } else {
            when (page) {
                PhoneSettingsPage.PLAYBACK -> {
                    renderPlayback()
                    renderDanmaku()
                }
                PhoneSettingsPage.APPEARANCE -> renderAppearance()
                PhoneSettingsPage.RECOMMEND -> renderRecommend()
                PhoneSettingsPage.SYSTEM -> renderSystem()
            }
        }
        container.addView(TextView(this).apply {
            text = "HGTV  ·  收藏与进度保存在本机"
            setTextColor(PhoneStyle.textFaint)
            phoneSize(11)
            setPadding(dp(6), dp(22), dp(6), 0)
        })

        restoreScroll(anchor, resetTop = resetScroll)
    }
    internal fun renderSettings(resetScroll: Boolean = false) = render(resetScroll)

    /** 四个大项排成两列两行，和「我的」页的四个入口使用同一组名称。 */
    private fun renderPageSelector() {
        if (landscape) {
            pageTabs.removeAllViews()
            PhoneSettingsPage.entries.forEachIndexed { index, option ->
                val active = option == page
                pageTabs.addView(TextView(this).apply {
                    text = option.label
                    settingsTextSize(11)
                    gravity = Gravity.CENTER
                    minWidth = settingsDp(78)
                    minHeight = settingsDp(24)
                    includeFontPadding = false
                    setPadding(settingsDp(8), 0, settingsDp(8), 0)
                    setTextColor(if (active) PhoneStyle.accent else PhoneStyle.textDim)
                    applySoft(
                        if (active) SoftStyle.INSET else SoftStyle.RAISED,
                        PhoneStyle.surface, PhoneStyle.RADIUS_PILL, 3f,
                    )
                    contentDescription = option.label + "，" + option.hint +
                        if (active) "，当前分区" else ""
                    setOnClickListener {
                        if (page == option) return@setOnClickListener
                        page = option
                        render(resetScroll = true)
                    }
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, settingsDp(24)
                ).apply {
                    if (index > 0) leftMargin = settingsDp(8)
                })
            }
            return
        }
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = TAG_PAGE_GRID
        }
        PhoneSettingsPage.entries.chunked(2).forEachIndexed { rowIndex, line ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                if (rowIndex > 0) setPadding(0, settingsDp(6), 0, 0)
            }
            line.forEachIndexed { index, option ->
                val active = option == page
                val button = TextView(this).apply {
                    text = option.label
                    settingsTextSize(14)
                    gravity = Gravity.CENTER
                    setTextColor(if (active) PhoneStyle.accent else PhoneStyle.text)
                    applySoft(
                        if (active) SoftStyle.INSET else SoftStyle.RAISED,
                        PhoneStyle.surface, PhoneStyle.RADIUS_CARD, 3f,
                    )
                    contentDescription = option.label + "，" + option.hint +
                        if (active) "，当前页" else ""
                    setOnClickListener {
                        if (page == option) return@setOnClickListener
                        page = option
                        render(resetScroll = true)
                    }
                }
                row.addView(button, LinearLayout.LayoutParams(0, settingsDp(42), 1f).apply {
                    if (index > 0) leftMargin = settingsDp(6)
                })
            }
            grid.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        container.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = settingsDp(4) })
    }

    @Deprecated("Activity 的旧回调；不引 androidx.activity 也能用，见 feature-phone 的依赖说明")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        when (requestCode) {
            REQUEST_EXPORT -> {
                val payload = pendingExport ?: return
                pendingExport = null
                val ok = runCatching {
                    contentResolver.openOutputStream(uri)?.use { it.write(payload.toByteArray()) }
                        ?: error("no stream")
                }.isSuccess
                toast(if (ok) "备份已导出" else "写入失败")
            }
            REQUEST_IMPORT -> {
                val text = runCatching {
                    contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(LibraryBackup.MAX_BYTES + 1)
                        var read = 0
                        while (read < buffer.size) {
                            val n = input.read(buffer, read, buffer.size - read)
                            if (n <= 0) break
                            read += n
                        }
                        String(buffer, 0, read, Charsets.UTF_8)
                    }
                }.getOrNull()
                if (text.isNullOrBlank()) { toast("读不到这个文件"); return }
                val backup = runCatching { LibraryBackup.decode(text) }.getOrNull()
                if (backup == null) { toast("备份格式不对"); return }
                runCatching { library.restore(backup, true) }
                    .onSuccess { toast("记录已合并恢复"); render() }
                    .onFailure { toast("恢复失败") }
            }
        }
    }

    // ---------------------------------------------------------------- 控件

    private fun targetHost(): LinearLayout = targetHostImpl()
    private fun settingsDp(value: Number): Int = settingsDpImpl(value)
    private fun TextView.settingsTextSize(value: Number): TextView =
        settingsTextSizeImpl(value)
    private fun settingRowHeight(): Int = settingRowHeightImpl()
    private fun settingRowGap(): Int = settingRowGapImpl()

    private fun section(title: String) {
        sectionImpl(title)
    }

    private fun hint(text: String) {
        hintImpl(text)
    }

    /** 一行设置：与电视端设置按钮同一种胶囊行，左项名、右读数。 */
    private fun row(title: String, value: String, onClick: () -> Unit) {
        rowImpl(title, value, onClick)
    }

    /** 整行开关：与电视端同一枚自绘轨道，点整行切换。 */
    private fun switch(title: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
        switchImpl(title, checked, onToggle)
    }

    /** 动作行：与电视端设置项同一种单行胶囊，右侧小字说明去向。 */
    private fun action(title: String, subtitle: String, onClick: () -> Unit) {
        actionImpl(title, subtitle, onClick)
    }

    /** 滑动条与电视端同一种整行控件：项名、轨道、读数在一块胶囊底板里。 */
    private fun slider(
        title: String, min: Float, max: Float, step: Float, value: Float,
        format: (Float) -> String,
        onChange: (Float) -> Unit
    ) {
        sliderImpl(title, min, max, step, value, format, onChange)
    }

    private fun anchorKey(kind: String, title: String): String = anchorKeyImpl(kind, title)

    private fun deviceFormLabel(detected: DeviceForm): String =
        DeviceForm.ofStored(library.deviceForm)?.label ?: "自动 · ${detected.label}"

    /** 找出当前视口顶部第一枚带稳定 key 的设置项。 */
    private fun currentScrollAnchor(): ScrollAnchor? {
        return currentScrollAnchorImpl()
    }

    /**
     * 新内容完成布局后再滚动。
     *
     * 之前直接在 `post` 里恢复：那一刻新加的行还没 measure，ScrollView 的纵向范围仍是
     * 空的，scrollTo 会被夹回 0。这里等到目标行真正有高度再恢复，并只消费一次。
     */
    private fun restoreScroll(anchor: ScrollAnchor?, resetTop: Boolean = false) {
        restoreScrollImpl(anchor, resetTop)
    }

    private fun pick(title: String, labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
        pickImpl(title, labels, selected, onPick)
    }

    private fun confirm(title: String, message: String, danger: Boolean = false, onOk: () -> Unit) {
        confirmImpl(title, message, danger, onOk)
    }

    private fun toast(text: String) = toastImpl(text)

    // ---------------------------------------------------------------- 文案

    private fun genderLabel(v: String) = genderLabelImpl(v)

    private fun genderIndex(v: String) = genderIndexImpl(v)

    private fun statusBarHeight(): Int = statusBarHeightImpl()

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_PAGE, page.name)
        currentScrollAnchor()?.let { anchor ->
            outState.putString(KEY_SCROLL_ANCHOR, anchor.key)
            outState.putInt(KEY_SCROLL_OFFSET, anchor.offset)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        closeLanSyncSessionImpl()
        scope.cancel()
        syncHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val REQUEST_EXPORT = 7001
        const val REQUEST_IMPORT = 7002
        const val KEY_SCROLL_ANCHOR = "settings-scroll-anchor"
        const val KEY_SCROLL_OFFSET = "settings-scroll-offset"
        const val KEY_PAGE = "settings-page"
        const val TAG_THEME_GRID = "grid:theme"
        const val TAG_PAGE_GRID = "grid:pages"
        const val EXTRA_PAGE = "settings-page-extra"

        fun open(context: Context, page: PhoneSettingsPage) {
            context.startActivity(Intent(context, PhoneSettingsActivity::class.java).apply {
                putExtra(EXTRA_PAGE, page.name)
            })
        }
    }

    /** 导出前先编好的 JSON：等用户在文件选择器里点完「保存」再写。 */
    private var pendingExport: String? = null
    internal var settingsPendingExport: String?
        get()=pendingExport
        set(value) { pendingExport=value }
}
