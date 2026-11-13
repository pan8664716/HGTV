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
private const val TAG_LANDSCAPE_SETTING_ROW = "landscape-setting-row"
private const val TAG_LANDSCAPE_SETTING_SPACER = "landscape-setting-spacer"
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
    private val library by lazy { PhoneServices.library(this) }
    private val settingsController by lazy { SettingsController(this, library) }
    private var page = PhoneSettingsPage.PLAYBACK
    private val syncHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lanSyncController by lazy { LanSyncController(this, library, scope) }
    private var lanSyncJob: Job? = null
    private var lanSyncDialog: PhoneDialog? = null
    private var lanSyncStep: LanSyncController.Step? = null
    private var lanSyncStatus: TextView? = null

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

    // ---------------------------------------------------------------- 横屏 TV 式布局

    /** 与 TV 设置页同一种结构：整宽面板 + 面板标题/说明 + 两列或四列设置格。 */
    private fun landscapePanel(title: String, subtitle: String): LinearLayout {
        val panel=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(settingsDp(12),settingsDp(10),settingsDp(12),settingsDp(8))
            applySoft(SoftStyle.RAISED,PhoneStyle.surface,PhoneStyle.RADIUS_CARD,3f)
        }
        panel.addView(TextView(this).apply {
            text=title
            setTextColor(PhoneStyle.text)
            settingsTextSize(13)
            setTypeface(typeface,Typeface.BOLD)
            setPadding(settingsDp(2),0,settingsDp(2),0)
        })
        panel.addView(TextView(this).apply {
            text=subtitle
            setTextColor(PhoneStyle.textDim)
            settingsTextSize(11)
            setPadding(settingsDp(2),settingsDp(3),settingsDp(2),settingsDp(8))
        })
        container.addView(panel,LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin=settingsDp(8) })
        sectionHost=panel
        return panel
    }

    /** 横屏设置格的两列流式容器：每行最多两个按钮，单按钮也固定占半行。 */
    private fun landscapeLine(panel: LinearLayout): LinearLayout {
        val flow=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        panel.addView(flow,LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin=settingsDp(6) })
        return flow
    }

    private fun landscapeCell(flow: LinearLayout,view: View) {
        val lastRow=flow.getChildAt(flow.childCount-1) as? LinearLayout
        val spacer=lastRow?.findViewWithTag<View>(TAG_LANDSCAPE_SETTING_SPACER)
        if(lastRow!=null && spacer!=null) {
            lastRow.removeView(spacer)
            lastRow.addView(view,LinearLayout.LayoutParams(
                0,settingRowHeight(),1f
            ).apply { leftMargin=settingsDp(6) })
            return
        }
        val row=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            tag=TAG_LANDSCAPE_SETTING_ROW
        }
        flow.addView(row,LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { if(flow.childCount>1) topMargin=settingsDp(6) })
        row.addView(view,LinearLayout.LayoutParams(0,settingRowHeight(),1f))
        row.addView(
            Space(this).apply { tag=TAG_LANDSCAPE_SETTING_SPACER },
            LinearLayout.LayoutParams(0,1,1f),
        )
    }

    /** 横屏双栏：左、右两列各自纵向排列。 */
    private fun landscapeColumns(panel: LinearLayout): Pair<LinearLayout,LinearLayout> {
        val row=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.TOP
        }
        val left=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        val right=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        row.addView(left,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        row.addView(right,LinearLayout.LayoutParams(
            0,ViewGroup.LayoutParams.WRAP_CONTENT,1f
        ).apply { leftMargin=settingsDp(6) })
        panel.addView(row,LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        return left to right
    }

    private fun landscapeColumnCell(column: LinearLayout,view: View) {
        column.addView(view,LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,settingRowHeight()
        ).apply { if(column.childCount>0) topMargin=settingsDp(6) })
    }

    private fun landscapeSetting(title: String,value: String,onClick: ()->Unit): View =
        LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            setPadding(settingsDp(12),0,settingsDp(10),0)
            applySoft(SoftStyle.RAISED,PhoneStyle.surfaceHigh,PhoneStyle.RADIUS_PILL,4f)
            addView(TextView(this@PhoneSettingsActivity).apply {
                text=title
                setTextColor(PhoneStyle.text)
                settingsTextSize(13)
                maxLines=1
                ellipsize=android.text.TextUtils.TruncateAt.END
            },LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
            addView(TextView(this@PhoneSettingsActivity).apply {
                text=value
                setTextColor(PhoneStyle.textDim)
                settingsTextSize(11)
                maxLines=1
                ellipsize=android.text.TextUtils.TruncateAt.END
            })
            setOnClickListener { onClick() }
        }

    private fun landscapeSwitch(title: String,checked: Boolean,onToggle:(Boolean)->Unit): View =
        PhoneSwitchRow(
            this,title,checked,
            densityScale=SETTINGS_DENSITY_SCALE,
            textScale=SETTINGS_TEXT_SCALE,
            onToggle=onToggle,
        )

    private fun landscapeSlider(
        title: String,min: Float,max: Float,step: Float,value: Float,
        format:(Float)->String,onValue:(Float)->Unit,
    ): View = PhoneSettingSlider(
        this,title,min,max,step,value,onValue,format,
        densityScale=SETTINGS_DENSITY_SCALE,
        textScale=SETTINGS_TEXT_SCALE,
    )

    private fun renderLandscapePlayback() {
        val pref=landscapePanel("播放偏好","所有剧集共用，播放中也可调整")
        landscapeLine(pref).also { line ->
            landscapeCell(line,landscapeSetting("默认清晰度","${library.maxQuality}P") {
                val options=PlaybackQuality.options
                pick("默认清晰度",options.map { "$it P" },options.indexOf(library.maxQuality).coerceAtLeast(0)) { i ->
                    library.maxQuality=options[i]; render()
                }
            })
            landscapeCell(line,landscapeSetting("默认倍速",PlaybackSpeed.label(library.playbackSpeed)) {
                val options=PlaybackSpeed.phoneOptions
                pick("默认倍速",options.map { PlaybackSpeed.label(it) },options.indexOf(library.playbackSpeed).coerceAtLeast(0)) { i ->
                    library.playbackSpeed=options[i]; render()
                }
            })
        }
        landscapeLine(pref).also { line ->
            landscapeCell(line,landscapeSetting("画面模式",library.frameMode.label) {
                val options=VideoFrameMode.entries
                pick("画面模式",options.map { it.label },options.indexOf(library.frameMode)) { i ->
                    library.frameMode=options[i]; render()
                }
            })
            landscapeCell(line,landscapeSetting("视频解码",library.decoderMode.label) {
                val options=VideoDecoderMode.entries
                pick("视频解码",options.map { it.label },options.indexOf(library.decoderMode)) { i ->
                    library.decoderMode=options[i]; render()
                }
            })
        }
        landscapeLine(pref).also { line ->
            landscapeCell(line,landscapeSlider("画面缩放",1f,100f,1f,library.videoScale,
                { "${it.roundToInt()}%" }) { library.videoScale=it })
            landscapeCell(line,landscapeSwitch("自动连播",library.autoNext) {
                library.autoNext=it; render()
            })
        }
        landscapeLine(pref).also { line ->
            landscapeCell(line,landscapeSlider("快进快退",2f,20f,1f,library.seekStep.toFloat(),
                { "${it.roundToInt()} 秒" }) { library.seekStep=it.roundToInt() })
        }

        val display=landscapePanel("界面信息","播放信息显示与自动收起")
        landscapeLine(display).also { line ->
            landscapeCell(line,landscapeSwitch("剧名",library.playerTitle) { library.playerTitle=it; render() })
            landscapeCell(line,landscapeSwitch("剧集",library.playerEpisode) { library.playerEpisode=it; render() })
            landscapeCell(line,landscapeSwitch("网速",library.playerSpeed) { library.playerSpeed=it; render() })
            landscapeCell(line,landscapeSwitch("时间",library.playerClock) { library.playerClock=it; render() })
        }
        landscapeLine(display).also { line ->
            landscapeCell(line,landscapeSlider("自动收起时间",
                PLAYER_PANEL_TIMEOUT_RANGE.first.toFloat(),PLAYER_PANEL_TIMEOUT_RANGE.last.toFloat(),1f,
                library.playerPanelTimeout.toFloat(),{ "${it.roundToInt()} 秒" }) {
                library.playerPanelTimeout=it.roundToInt()
            })
        }

        val danmaku=landscapePanel("弹幕设置","显示方式 / 速度 / 聚合 / 特效 / 字号 / 透明度")
        val (left,right)=landscapeColumns(danmaku)
        landscapeColumnCell(left,landscapeSetting("显示方式",library.danmakuMode.label) {
                val options=DanmakuMode.entries
                pick("显示方式",options.map { it.label },options.indexOf(library.danmakuMode)) { i ->
                    library.danmakuMode=options[i]; render()
                }
            })
        landscapeColumnCell(left,landscapeSetting("弹幕速度",library.danmakuSpeed.label) {
                val options=DanmakuSpeed.entries
                pick("弹幕速度",options.map { it.label },options.indexOf(library.danmakuSpeed)) { i ->
                    library.danmakuSpeed=options[i]; render()
                }
            })
        landscapeColumnCell(left,landscapeSwitch("相同弹幕聚合",library.danmakuMerge) {
                library.danmakuMerge=it; render()
            })
        landscapeColumnCell(left,landscapeSwitch("开启弹幕特效",library.danmakuEffects) {
                library.danmakuEffects=it; render()
            })
        landscapeColumnCell(right,landscapeSlider("聚合条数",
                DANMAKU_MERGE_MIN_RANGE.first.toFloat(),DANMAKU_MERGE_MIN_RANGE.last.toFloat(),1f,
                library.danmakuMergeMin.toFloat(),{ "${it.roundToInt()} 条" }) {
                library.danmakuMergeMin=it.roundToInt()
            })
        landscapeColumnCell(right,landscapeSlider("弹幕大小",
                PhoneDanmakuSize.MIN_PERCENT.toFloat(),PhoneDanmakuSize.MAX_PERCENT.toFloat(),
                PhoneDanmakuSize.STEP_PERCENT.toFloat(),PhoneDanmakuSize.percent(this).toFloat(),
                { PhoneDanmakuSize.label(it) }) { PhoneDanmakuSize.set(this,it) })
        landscapeColumnCell(right,landscapeSlider("弹幕透明度",
                DanmakuStyle.ALPHA_MIN,DanmakuStyle.ALPHA_MAX,DanmakuStyle.ALPHA_STEP,
                library.danmakuAlpha,{ DanmakuStyle.percent(it) }) { library.danmakuAlpha=DanmakuStyle.alpha(it) })
    }

    private fun renderLandscapeAppearance() {
        val layout=landscapePanel("布局启动","UI大小、列表排版与推荐页滑动")
        landscapeLine(layout).also { line ->
            landscapeCell(line,landscapeSetting("UI大小",PhoneUiScale.label(PhoneUiScale.value)) {
                val options=PhoneUiScale.sizes
                pick("UI大小",options.map { it.label },
                    options.indexOf(PhoneUiScale.sizeOf(PhoneUiScale.value))) { i ->
                    PhoneUiScale.set(this,options[i].scale)
                    recreate()
                }
            })
            val base=library.gridColumns.coerceIn(1,3)
            landscapeCell(line,landscapeSetting("列表每行","${base+3} 张") {
                val options=listOf(1,2,3)
                pick("海报列表每行几张",
                    options.map { "竖屏 $it 张 · 横屏 ${it+3} 张" },
                    options.indexOf(base).coerceAtLeast(0)) { i ->
                    library.gridColumns=options[i]; render()
                }
            })
        }
        landscapeLine(layout).also { line ->
            landscapeCell(line,landscapeSwitch("推荐页左右滑动",library.recommendSwipe) {
                library.recommendSwipe=it; render()
            })
        }

        val appearance=landscapePanel("主题外观","${TvTheme.entries.size} 套新拟态材质，选中即生效")
        themeGrid()
        appearance.addView(TextView(this).apply {
            text="当前：${PhoneStyle.theme.label} · ${PhoneStyle.theme.scene}"
            setTextColor(PhoneStyle.textDim)
            phoneSize(11)
            setPadding(dp(4),dp(6),dp(4),0)
        },LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun renderLandscapeRecommend() {
        val scope=landscapePanel("推荐设置","推荐受众")
        landscapeLine(scope).also { line ->
            landscapeCell(line,landscapeSetting("受众",genderLabel(library.recommendGender)) {
                pick("推荐受众",listOf("不限","男频","女频"),genderIndex(library.recommendGender)) { i ->
                    library.recommendGender=listOf("","male","female")[i]; render()
                }
            })
        }

        val content=landscapePanel("内容管理与来源","恢复隐藏剧集、搜索接口与选集分页")
        landscapeLine(content).also { line ->
            landscapeCell(line,landscapeSetting("恢复隐藏的剧集","${library.hidden().size} 部") {
                val count=library.hidden().size
                if(count==0) { toast("没有被隐藏的剧集"); return@landscapeSetting }
                confirm("恢复隐藏的剧集","把首页隐藏过的 $count 部剧全部放回列表？") {
                    library.unhideAll(); toast("已恢复 $count 部"); render()
                }
            })
            landscapeCell(line,landscapeSetting("搜索源",library.searchSource.label) {
                val options=SearchSource.entries
                pick("搜索源",options.map { "${it.label}　${it.hint}" },options.indexOf(library.searchSource)) { i ->
                    library.searchSource=options[i]; render()
                }
            })
        }
        landscapeLine(content).also { line ->
            landscapeCell(line,landscapeSetting("选集每页","${library.episodePageSize} 集") {
                val options=EpisodePageSize.phoneOptions
                pick("选集每页（每行 5 个）",options.map { "$it 集" },
                    options.indexOf(library.episodePageSize).coerceAtLeast(0)) { i ->
                    library.episodePageSize=options[i]; render()
                }
            })
        }
    }

    private fun renderLandscapeSystem() {
        val data=landscapePanel("数据维护","收藏与进度存在本机，可导出、同步与恢复")
        landscapeLine(data).also { line ->
            landscapeCell(line,landscapeSetting("导出备份","存成 JSON 文件") { exportBackup() })
            landscapeCell(line,landscapeSetting("从备份恢复","合并收藏、记录与设置") { importBackup() })
        }
        landscapeLine(data).also { line ->
            landscapeCell(line,landscapeSetting("清空应用缓存","恢复成刚安装的样子") {
                confirm("清空应用缓存",
                    "用于反复无法登录、账号状态异常、页面数据错乱等情况：清空后应用会重新注册设备、重新生成标识。\n\n" +
                        "注意：本机的收藏、观看记录、搜索历史与设置也会一并清空，且无法找回。如需保留，请先导出备份。\n\n清空完成后应用会自动重启。",
                    danger=true) { performReset() }
            })
            landscapeCell(line,landscapeSetting("恢复默认设置","只重置偏好") {
                confirm("恢复默认设置","把播放、弹幕、推荐、外观相关的偏好恢复成出厂值，收藏与历史不受影响。") {
                    restoreDefaults()
                }
            })
        }
        landscapeLine(data).also { line ->
            landscapeCell(line,landscapeSetting("同步其他设备数据","局域网 NSD 查找设备") { showLanLibrarySync() })
        }

        val perf=landscapePanel("设备与性能","动画开销、现场面板与设备形态")
        landscapeLine(perf).also { line ->
            landscapeCell(line,landscapeSwitch("减少动画优化性能",library.reduceAnim) {
                library.reduceAnim=it; render()
            })
            landscapeCell(line,landscapeSwitch("调试模式",library.debugMode) {
                library.debugMode=it; render()
            })
        }
        landscapeLine(perf).also { line ->
            landscapeCell(line,landscapeSetting("关于","版本 ${appVersionName()}") { showAboutDialog() })
            val detected=runCatching { detectDeviceForm().form }.getOrDefault(DeviceForm.UNKNOWN)
            landscapeCell(line,landscapeSetting("设备类型",deviceFormLabel(detected)) {
                pick("设备类型（切换后自动重新进入）",DeviceForm.pickerLabels(detected),
                    DeviceForm.optionIndex(library.deviceForm)) { i ->
                    val selected=DeviceForm.OPTIONS[i]
                    if(selected==library.deviceForm) { render(); return@pick }
                    if(!library.setDeviceFormNow(selected)) { toast("设备类型保存失败，请重试"); render(); return@pick }
                    AppRelaunch.switchDeviceForm(this)
                }
            })
        }
    }

    // —— 一、播放交互 ——

    /**
     * 手机端统一的行渲染器：竖屏和横屏都走这一条。
     */
    private fun renderSpecs(specs: List<SettingRowSpec>) {
        specs.forEach { spec ->
            when (spec) {
                is SettingRowSpec.Section -> section(spec.title)
                is SettingRowSpec.ThemeGrid -> themeGrid()
                is SettingRowSpec.Choice -> row(spec.title, spec.value) {
                    pick(spec.title, spec.options, spec.options.indexOf(spec.value).coerceAtLeast(0)) { index ->
                        applyChoice(spec.id, index)
                        render()
                    }
                }
                is SettingRowSpec.Switch -> switch(spec.title, spec.checked) { checked ->
                    applySwitch(spec.id, checked)
                    render()
                }
                is SettingRowSpec.Slider -> slider(
                    spec.title, spec.min, spec.max, spec.step, spec.value,
                    { valueLabelFor(spec.id, it) },
                ) { value ->
                    applySlider(spec.id, value)
                }
                is SettingRowSpec.Action -> action(spec.title, spec.subtitle) {
                    applyAction(spec.id)
                }
            }
        }
    }

    private fun renderUnifiedPlayback() {
        renderSpecs(
            listOf(
                SettingRowSpec.Section("播放偏好"),
                SettingRowSpec.Choice(
                    SettingsController.ID_QUALITY,
                    "默认清晰度",
                    "${library.maxQuality}P",
                    PlaybackQuality.options.map { "${it}P" },
                ),
                SettingRowSpec.Choice(
                    SettingsController.ID_SPEED,
                    "默认倍速",
                    PlaybackSpeed.label(library.playbackSpeed),
                    PlaybackSpeed.phoneOptions.map(PlaybackSpeed::label),
                ),
                SettingRowSpec.Choice(
                    SettingsController.ID_FRAME,
                    "画面模式",
                    library.frameMode.label,
                    VideoFrameMode.entries.map { it.label },
                ),
                SettingRowSpec.Choice(
                    SettingsController.ID_DECODER,
                    "视频解码",
                    library.decoderMode.label,
                    VideoDecoderMode.entries.map { it.label },
                ),
                SettingRowSpec.Slider(
                    SettingsController.ID_VIDEO_SCALE,
                    "画面缩放",
                    library.videoScale,
                    1f,
                    100f,
                    1f,
                    "${library.videoScale.roundToInt()}%",
                ),
                SettingRowSpec.Switch("playback.autoNext", "自动连播", library.autoNext),
                SettingRowSpec.Slider(
                    "playback.seekStep",
                    "快进快退",
                    library.seekStep.toFloat(),
                    2f,
                    20f,
                    1f,
                    "${library.seekStep} 秒",
                ),
                SettingRowSpec.Section("播放显示"),
                SettingRowSpec.Switch("play.title", "剧名", library.playerTitle),
                SettingRowSpec.Switch("play.episode", "剧集", library.playerEpisode),
                SettingRowSpec.Switch("play.speed", "网速", library.playerSpeed),
                SettingRowSpec.Switch("play.clock", "时间", library.playerClock),
                SettingRowSpec.Slider(
                    "play.panelTimeout",
                    "自动收起时间",
                    library.playerPanelTimeout.toFloat(),
                    PLAYER_PANEL_TIMEOUT_RANGE.first.toFloat(),
                    PLAYER_PANEL_TIMEOUT_RANGE.last.toFloat(),
                    1f,
                    "${library.playerPanelTimeout} 秒",
                ),
                SettingRowSpec.Section("弹幕设置"),
                SettingRowSpec.Choice(
                    SettingsController.ID_DANMAKU_MODE,
                    "显示方式",
                    library.danmakuMode.label,
                    DanmakuMode.entries.map { it.label },
                ),
                SettingRowSpec.Choice(
                    SettingsController.ID_DANMAKU_SPEED,
                    "弹幕速度",
                    library.danmakuSpeed.label,
                    DanmakuSpeed.entries.map { it.label },
                ),
                SettingRowSpec.Switch(
                    SettingsController.ID_DANMAKU_MERGE,
                    "相同弹幕聚合",
                    library.danmakuMerge,
                ),
                SettingRowSpec.Switch(
                    SettingsController.ID_DANMAKU_EFFECTS,
                    "开启弹幕特效",
                    library.danmakuEffects,
                ),
                SettingRowSpec.Slider(
                    SettingsController.ID_DANMAKU_MERGE_MIN,
                    "聚合条数",
                    library.danmakuMergeMin.toFloat(),
                    DANMAKU_MERGE_MIN_RANGE.first.toFloat(),
                    DANMAKU_MERGE_MIN_RANGE.last.toFloat(),
                    1f,
                    "${library.danmakuMergeMin} 条",
                ),
                SettingRowSpec.Slider(
                    "danmaku.phoneScale",
                    "弹幕大小",
                    PhoneDanmakuSize.percent(this).toFloat(),
                    PhoneDanmakuSize.MIN_PERCENT.toFloat(),
                    PhoneDanmakuSize.MAX_PERCENT.toFloat(),
                    PhoneDanmakuSize.STEP_PERCENT.toFloat(),
                    PhoneDanmakuSize.label(PhoneDanmakuSize.percent(this).toFloat()),
                ),
                SettingRowSpec.Slider(
                    SettingsController.ID_DANMAKU_ALPHA,
                    "弹幕透明度",
                    library.danmakuAlpha,
                    DanmakuStyle.ALPHA_MIN,
                    DanmakuStyle.ALPHA_MAX,
                    DanmakuStyle.ALPHA_STEP,
                    DanmakuStyle.percent(library.danmakuAlpha),
                ),
            )
        )
    }

    private fun renderUnifiedAppearance() {
        val scale = PhoneUiScale.sizeOf(PhoneUiScale.value)
        renderSpecs(
            listOf(
                SettingRowSpec.Section("外观与布局"),
                SettingRowSpec.Choice(
                    "appearance.uiScale",
                    "UI大小",
                    scale.label,
                    PhoneUiScale.sizes.map { it.label },
                ),
                SettingRowSpec.Choice(
                    "appearance.columns",
                    "列表每行",
                    "${library.gridColumns + 3} 张",
                    listOf("竖屏 1 张 · 横屏 4 张", "竖屏 2 张 · 横屏 5 张", "竖屏 3 张 · 横屏 6 张"),
                ),
                SettingRowSpec.Switch(
                    SettingsController.ID_RECOMMEND_SWIPE,
                    "推荐页左右滑动",
                    library.recommendSwipe,
                ),
                SettingRowSpec.Section("主题外观"),
                SettingRowSpec.ThemeGrid(selected = PhoneStyle.theme.storedValue),
            )
        )
    }

    private fun renderUnifiedRecommend() {
        renderSpecs(
            listOf(
                SettingRowSpec.Section("推荐设置"),
                SettingRowSpec.Choice(
                    SettingsController.ID_RECOMMEND_GENDER,
                    "受众",
                    genderLabel(library.recommendGender),
                    listOf("不限", "男频", "女频"),
                ),
                SettingRowSpec.Section("内容管理与来源"),
                SettingRowSpec.Action("recommend.unhide", "恢复隐藏的剧集", "${library.hidden().size} 部"),
                SettingRowSpec.Choice(
                    SettingsController.ID_SEARCH_SOURCE,
                    "搜索源",
                    library.searchSource.label,
                    SearchSource.entries.map { it.label },
                ),
                SettingRowSpec.Choice(
                    "recommend.episodePage",
                    "选集每页",
                    "${library.episodePageSize} 集",
                    EpisodePageSize.phoneOptions.map { "$it 集" },
                ),
            )
        )
    }

    private fun renderUnifiedSystem() {
        renderSpecs(
            listOf(
                SettingRowSpec.Section("数据维护"),
                SettingRowSpec.Action(SettingsController.ID_EXPORT_BACKUP, "导出备份", "存成 JSON 文件"),
                SettingRowSpec.Action(SettingsController.ID_IMPORT_BACKUP, "从备份恢复", "合并收藏、记录与设置"),
                SettingRowSpec.Action(SettingsController.ID_RESET_APP, "清空应用缓存", "恢复成刚安装的样子"),
                SettingRowSpec.Action(SettingsController.ID_RESTORE_DEFAULTS, "恢复默认设置", "只重置偏好"),
                SettingRowSpec.Action(SettingsController.ID_LAN_SYNC, "同步其他设备数据", "局域网 NSD 查找设备"),
                SettingRowSpec.Section("设备与性能"),
                SettingRowSpec.Switch(SettingsController.ID_REDUCE_ANIM, "减少动画优化性能", library.reduceAnim),
                SettingRowSpec.Switch(SettingsController.ID_DEBUG_MODE, "调试模式", library.debugMode),
                SettingRowSpec.Action("system.about", "关于", "版本 ${appVersionName()}"),
                SettingRowSpec.Action(SettingsController.ID_DEVICE_FORM, "设备类型", deviceFormLabel(
                    runCatching { detectDeviceForm().form }.getOrDefault(DeviceForm.UNKNOWN)
                )),
            )
        )
    }

    private fun applyChoice(id: String, index: Int) {
        when (id) {
            SettingsController.ID_QUALITY -> settingsController.setQuality(PlaybackQuality.options[index])
            SettingsController.ID_SPEED -> settingsController.setSpeed(PlaybackSpeed.phoneOptions[index])
            SettingsController.ID_FRAME -> settingsController.setFrame(VideoFrameMode.entries[index])
            SettingsController.ID_DECODER -> settingsController.setDecoder(VideoDecoderMode.entries[index])
            SettingsController.ID_DANMAKU_MODE -> settingsController.setDanmakuMode(DanmakuMode.entries[index])
            SettingsController.ID_DANMAKU_SPEED -> settingsController.setDanmakuSpeed(DanmakuSpeed.entries[index])
            SettingsController.ID_RECOMMEND_GENDER -> settingsController.setRecommendGender(
                listOf("", "male", "female")[index]
            )
            SettingsController.ID_SEARCH_SOURCE -> settingsController.setSearchSource(SearchSource.entries[index])
            "appearance.uiScale" -> {
                PhoneUiScale.set(this, PhoneUiScale.sizes[index].scale)
                recreate()
            }
            "appearance.columns" -> library.gridColumns = index + 1
            "recommend.episodePage" -> library.episodePageSize = EpisodePageSize.phoneOptions[index]
        }
    }

    private fun applySwitch(id: String, checked: Boolean) {
        when (id) {
            SettingsController.ID_DANMAKU_MERGE -> settingsController.setDanmakuMerge(checked)
            SettingsController.ID_DANMAKU_EFFECTS -> settingsController.setDanmakuEffects(checked)
            SettingsController.ID_RECOMMEND_SWIPE -> settingsController.setRecommendSwipe(checked)
            SettingsController.ID_DEBUG_MODE -> settingsController.setDebugMode(checked)
            SettingsController.ID_REDUCE_ANIM -> settingsController.setReduceAnim(checked)
            "playback.autoNext" -> library.autoNext = checked
            "play.title" -> library.playerTitle = checked
            "play.episode" -> library.playerEpisode = checked
            "play.speed" -> library.playerSpeed = checked
            "play.clock" -> library.playerClock = checked
        }
    }

    private fun applySlider(id: String, value: Float) {
        when (id) {
            SettingsController.ID_VIDEO_SCALE -> settingsController.setVideoScale(value)
            SettingsController.ID_DANMAKU_MERGE_MIN -> settingsController.setDanmakuMergeMin(value.roundToInt())
            SettingsController.ID_DANMAKU_ALPHA -> settingsController.setDanmakuAlpha(DanmakuStyle.alpha(value))
            "playback.seekStep" -> library.seekStep = value.roundToInt()
            "play.panelTimeout" -> library.playerPanelTimeout = value.roundToInt()
            "danmaku.phoneScale" -> PhoneDanmakuSize.set(this, value)
        }
    }

    private fun applyAction(id: String) {
        when (id) {
            SettingsController.ID_EXPORT_BACKUP -> exportBackup()
            SettingsController.ID_IMPORT_BACKUP -> importBackup()
            SettingsController.ID_RESET_APP -> confirm(
                "清空应用缓存",
                "本机的收藏、观看记录、搜索历史与设置也会一并清空，且无法找回。清空完成后应用会自动重启。",
                danger = true,
            ) { performReset() }
            SettingsController.ID_RESTORE_DEFAULTS -> confirm(
                "恢复默认设置",
                "把播放、弹幕、推荐、外观相关的偏好恢复成出厂值，收藏与历史不受影响。",
            ) {
                settingsController.restoreDefaults()
                render(resetScroll = true)
            }
            SettingsController.ID_LAN_SYNC -> showLanLibrarySync()
            "system.about" -> showAboutDialog()
            SettingsController.ID_DEVICE_FORM -> {
                val detected = runCatching { detectDeviceForm().form }.getOrDefault(DeviceForm.UNKNOWN)
                pick(
                    "设备类型（切换后自动重新进入）",
                    DeviceForm.pickerLabels(detected),
                    DeviceForm.optionIndex(library.deviceForm),
                ) { index ->
                    val selected = DeviceForm.OPTIONS[index]
                    if (selected != library.deviceForm && library.setDeviceFormNow(selected)) {
                        AppRelaunch.switchDeviceForm(this)
                    } else {
                        render()
                    }
                }
            }
            "recommend.unhide" -> {
                val count = library.hidden().size
                if (count == 0) toast("没有被隐藏的剧集")
                else confirm("恢复隐藏的剧集", "把首页隐藏过的 $count 部剧全部放回列表？") {
                    library.unhideAll()
                    toast("已恢复 $count 部")
                    render()
                }
            }
        }
    }

    private fun valueLabelFor(id: String, value: Float): String = when (id) {
        SettingsController.ID_VIDEO_SCALE -> "${value.roundToInt()}%"
        SettingsController.ID_DANMAKU_MERGE_MIN -> "${value.roundToInt()} 条"
        SettingsController.ID_DANMAKU_ALPHA -> DanmakuStyle.percent(value)
        "playback.seekStep" -> "${value.roundToInt()} 秒"
        "play.panelTimeout" -> "${value.roundToInt()} 秒"
        "danmaku.phoneScale" -> PhoneDanmakuSize.label(value)
        else -> value.roundToInt().toString()
    }

    private fun renderPlayback() {
        section("播放偏好")

        row("默认清晰度", "${library.maxQuality}P") {
            val options = PlaybackQuality.options
            pick("默认清晰度", options.map { "$it P" },
                options.indexOf(library.maxQuality).coerceAtLeast(0)) { index ->
                library.maxQuality = options[index]
                render()
            }
        }
        row("默认倍速", PlaybackSpeed.label(library.playbackSpeed)) {
            // 手机 / 平板比电视多一档 3×（见 `PlaybackSpeed.phoneOptions`）。
            val options = PlaybackSpeed.phoneOptions
            pick("默认倍速", options.map { PlaybackSpeed.label(it) },
                options.indexOf(library.playbackSpeed).coerceAtLeast(0)) { index ->
                library.playbackSpeed = options[index]
                render()
            }
        }
        row("画面模式", library.frameMode.label) {
            val options = VideoFrameMode.entries
            pick("画面模式", options.map { it.label }, options.indexOf(library.frameMode)) { index ->
                library.frameMode = options[index]
                render()
            }
        }
        // 解码方式在**建播放器时**生效：改完下次进播放页（或下次起播）按新的一档重建。
        row("视频解码", library.decoderMode.label) {
            val options = VideoDecoderMode.entries
            pick("视频解码", options.map { it.label }, options.indexOf(library.decoderMode)) { index ->
                library.decoderMode = options[index]
                render()
            }
        }
        hint("硬件省电、4K 更稳；软件兼容性更好，花屏、绿屏、有声无画时换它。改完下一次起播生效。")

        slider("画面缩放", 1f, 100f, 1f, library.videoScale, { "${it.roundToInt()}%" }) {
            library.videoScale = it
        }
        switch("自动连播", library.autoNext) {
            library.autoNext = it
            render()
        }
        slider("快进快退", 2f, 20f, 1f, library.seekStep.toFloat(), { "${it.roundToInt()} 秒" }) {
            library.seekStep = it.roundToInt()
        }
        section("界面信息")
        hint("画面顶部常驻信息，默认全关。依次为剧名、剧集、网速、时间。")
        switch("剧名", library.playerTitle) { library.playerTitle = it; render() }
        switch("剧集", library.playerEpisode) { library.playerEpisode = it; render() }
        switch("网速", library.playerSpeed) { library.playerSpeed = it; render() }
        switch("时间", library.playerClock) { library.playerClock = it; render() }

        slider("自动消失", PLAYER_PANEL_TIMEOUT_RANGE.first.toFloat(),
            PLAYER_PANEL_TIMEOUT_RANGE.last.toFloat(), 1f,
            library.playerPanelTimeout.toFloat(), { "${it.roundToInt()} 秒" }) {
            library.playerPanelTimeout = it.roundToInt()
        }
        hint("播放界面上的标题、进度条与设置/选集面板无操作后自动收起，点一下画面恢复。")
    }

    // —— 一、播放交互：弹幕 ——

    private fun renderDanmaku() {
        section("弹幕")
        hint("显示方式、聚合与特效共用电视端设置；字号按手机阅读单独调整，改完回到播放页即生效。")
        row("显示方式", library.danmakuMode.label) {
            val options = DanmakuMode.entries
            pick("显示方式", options.map { it.label }, options.indexOf(library.danmakuMode)) { index ->
                library.danmakuMode = options[index]
                render()
            }
        }
        row("弹幕速度", library.danmakuSpeed.label) {
            val options = DanmakuSpeed.entries
            pick("弹幕速度", options.map { it.label }, options.indexOf(library.danmakuSpeed)) { index ->
                library.danmakuSpeed = options[index]
                render()
            }
        }
        switch("相同弹幕聚合", library.danmakuMerge) {
            library.danmakuMerge = it
            render()
        }
        switch("开启弹幕特效", library.danmakuEffects) {
            library.danmakuEffects = it
            render()
        }
        slider("聚合条数", DANMAKU_MERGE_MIN_RANGE.first.toFloat(),
            DANMAKU_MERGE_MIN_RANGE.last.toFloat(), 1f,
            library.danmakuMergeMin.toFloat(), { "${it.roundToInt()} 条" }) {
            library.danmakuMergeMin = it.roundToInt()
        }
        slider("弹幕大小", PhoneDanmakuSize.MIN_PERCENT.toFloat(),
            PhoneDanmakuSize.MAX_PERCENT.toFloat(), PhoneDanmakuSize.STEP_PERCENT.toFloat(),
            PhoneDanmakuSize.percent(this).toFloat(), { PhoneDanmakuSize.label(it) }) {
            PhoneDanmakuSize.set(this, it)
        }
        slider("弹幕透明度", DanmakuStyle.ALPHA_MIN, DanmakuStyle.ALPHA_MAX, DanmakuStyle.ALPHA_STEP,
            library.danmakuAlpha, { DanmakuStyle.percent(it) }) {
            library.danmakuAlpha = DanmakuStyle.alpha(it)
        }
    }

    // —— 二、外观导航 ——

    private fun renderAppearance() {
        // 「外观与布局」这一组把最常调的三项放在最上面（UI 大小与列表布局放在一起），
        // 二十套主题网格排在它们之后。
        section("外观与布局")
        row("UI大小", PhoneUiScale.label(PhoneUiScale.value)) {
            val options = PhoneUiScale.sizes
            pick(
                "UI大小",
                options.map { it.label },
                options.indexOf(PhoneUiScale.sizeOf(PhoneUiScale.value)),
            ) { index ->
                PhoneUiScale.set(this, options[index].scale)
                recreate()
            }
        }
        val baseColumns = library.gridColumns.coerceIn(1, 3)
        val shownColumns = baseColumns + if (landscape) 3 else 0
        row("列表每行", "$shownColumns 张") {
            val options = listOf(1, 2, 3)
            pick("海报列表每行几张",
                options.map { "竖屏 $it 张 · 横屏 ${it + 3} 张" },
                options.indexOf(baseColumns).coerceAtLeast(0)) { index ->
                library.gridColumns = options[index]
                render()
            }
        }
        hint("竖屏可选 1–3 张；横屏对应显示 4–6 张。")
        // 推荐页三个体裁（漫剧 / 真人 / AI 剧）之间能不能用手指左右划。
        // 关掉之后只剩顶部那三个体裁标签换流——竖屏短剧一屏一集，横滑误触太常见。
        switch("推荐页左右滑动", library.recommendSwipe) {
            library.recommendSwipe = it
            render()
        }
        hint("关闭后推荐页只能用顶部体裁标签换流，手指左右划不会整页翻走。")

        // 主题放在最后：二十套色卡占的地方最大，日常不需要频繁改。
        hint("当前：${PhoneStyle.theme.label} · ${PhoneStyle.theme.scene}　（${TvTheme.entries.size} 套，选中即生效）")
        if (landscape) section("主题")
        themeGrid()
    }

    /** 二十套主题：两列一行，手机上名称与色点都能完整显示。 */
    private fun themeGrid() {
        val columns = if(landscape) 5 else 2
        val chipHeight = settingsDp(if (landscape) 34 else 38)
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = TAG_THEME_GRID
        }
        TvTheme.entries.chunked(columns).forEach { line ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, settingsDp(4), 0, 0)
            }
            repeat(columns) { index ->
                val option = line.getOrNull(index)
                if (option == null) {
                    row.addView(View(this), LinearLayout.LayoutParams(0, chipHeight, 1f).apply {
                        if (index > 0) leftMargin = settingsDp(6)
                    })
                    return@repeat
                }
                val active = option == PhoneStyle.theme
                val chip = TextView(this).apply {
                    text = SpannableString("●  ${option.label}").apply {
                        setSpan(ForegroundColorSpan(option.accent), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(RelativeSizeSpan(.72f), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    settingsTextSize(if (landscape) 12 else 13)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(settingsDp(14), 0, settingsDp(10), 0)
                    setTextColor(if (active) PhoneStyle.accent else PhoneStyle.text)
                    applySoft(
                        if (active) SoftStyle.INSET else SoftStyle.RAISED,
                        PhoneStyle.surface, PhoneStyle.RADIUS_PILL, 4f
                    )
                    contentDescription = option.label + "，" + option.scene +
                        if (active) "，当前主题" else ""
                    setOnClickListener { switchTheme(option) }
                }
                row.addView(chip, LinearLayout.LayoutParams(0, chipHeight, 1f).apply {
                    if (index > 0) leftMargin = settingsDp(6)
                })
            }
            grid.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        targetHost().addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = settingsDp(4) })
    }

    /**
     * 换主题：落盘 → 换色（顺带丢掉柔光位图缓存）→ 重建这一页。
     *
     * 与 TV 的 `switchTheme` 同一套动作。整页重建不能省：已建好的 View 上挂着旧材质的
     * Drawable，只改静态色值不会作用到它们。
     */
    private fun switchTheme(option: TvTheme) {
        if (option == PhoneStyle.theme) return
        TvThemeStore.save(this, option)
        PhoneStyle.applyTheme(option)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(PhoneStyle.background))
        recreate()
    }

    // —— 三、内容推荐 ——

    private fun renderRecommend() {
        section("推荐设置")
        row("受众", genderLabel(library.recommendGender)) {
            pick("推荐受众", listOf("不限", "男频", "女频"), genderIndex(library.recommendGender)) { index ->
                library.recommendGender = listOf("", "male", "female")[index]
                render()
            }
        }

        section("内容管理与来源")
        row("选集每页", "${library.episodePageSize} 集") {
            val options = EpisodePageSize.phoneOptions
            pick("选集每页（每行 5 个）", options.map { "$it 集" },
                options.indexOf(library.episodePageSize).coerceAtLeast(0)) { index ->
                library.episodePageSize = options[index]
                render()
            }
        }
        // 「分页边缘翻页」不再放在这里：手机上它读起来像是「推荐页能不能左右翻」，
        // 实际管的是选集网格的边缘翻页。这一项已挪到「外观导航」，并正名为
        // 「推荐页左右滑动」（见 renderAppearance）；选集边缘翻页在手机上保持开启。
        row("搜索源", library.searchSource.label) {
            val options = SearchSource.entries
            pick("搜索源", options.map { "${it.label}　${it.hint}" },
                options.indexOf(library.searchSource)) { index ->
                library.searchSource = options[index]
                render()
            }
        }
        action("恢复隐藏的剧集", "${library.hidden().size} 部") {
            val count = library.hidden().size
            if (count == 0) { toast("没有被隐藏的剧集"); return@action }
            confirm("恢复隐藏的剧集", "把首页隐藏过的 $count 部剧全部放回列表？") {
                library.unhideAll()
                toast("已恢复 $count 部")
                render()
            }
        }
    }

    // —— 四、系统高级 ——

    private fun renderSystem() {
        section("设备与性能")
        action("关于", "版本与项目信息") { showAboutDialog() }
        val detected = runCatching { detectDeviceForm().form }.getOrDefault(DeviceForm.UNKNOWN)
        row("设备类型", deviceFormLabel(detected)) {
            pick(
                "设备类型（切换后自动重新进入）",
                DeviceForm.pickerLabels(detected),
                DeviceForm.optionIndex(library.deviceForm),
            ) { index ->
                val selected = DeviceForm.OPTIONS[index]
                if (selected == library.deviceForm) {
                    render()
                    return@pick
                }
                if (!library.setDeviceFormNow(selected)) {
                    toast("设备类型保存失败，请重试")
                    render()
                    return@pick
                }
                toast("正在切换到 ${DeviceForm.shortLabel(selected)} 界面")
                AppRelaunch.switchDeviceForm(this)
            }
        }
        hint("用于强制进入手机、平板或电视界面，切换后自动重新进入。")
        switch("减少动画优化性能", library.reduceAnim) {
            library.reduceAnim = it
            render()
        }
        switch("调试模式", library.debugMode) {
            library.debugMode = it
            render()
        }

        section("数据维护")
        action("导出备份", "存成一份 JSON 文件") { exportBackup() }
        action("同步其他设备数据", "通过局域网 NSD 查找设备") { showLanLibrarySync() }
        action("从备份恢复", "合并收藏、记录与设置") { importBackup() }

        section("危险操作")
        action("清空应用缓存", "恢复成刚安装的样子") {
            confirm("清空应用缓存",
                "用于反复无法登录、账号状态异常、页面数据错乱等情况：清空后应用会重新注册设备、重新生成标识。\n\n" +
                    "注意：本机的收藏、观看记录、搜索历史与设置也会一并清空，且无法找回。" +
                    "如需保留，请先「导出备份」。\n\n清空完成后应用会自动重启。",
                danger = true) { performReset() }
        }
        action("恢复默认设置", "只重置偏好，收藏与记录不动") {
            confirm("恢复默认设置", "把播放、弹幕、推荐、外观相关的偏好恢复成出厂值，收藏与历史不受影响。") {
                restoreDefaults()
            }
        }

    }

    private fun appVersionName(): String = appVersionNameImpl()

    private fun showAboutDialog() {
        PhoneDialog(this, "关于")
            .message("版本号：${appVersionName()}")
            .message("GitHub：${ProjectInfo.GITHUB}")
            .message("更新请前往项目页面查看项目更新。")
            .button("关闭") {}
            .show()
    }

    /** 出厂值：与 `BackupSettings` 的默认参数逐项对齐。 */
    private fun restoreDefaults() {
        runCatching {
            settingsController.restoreDefaults()
            library.autoNext = true
            library.seekStep = 10
            library.autoFullscreen = false
            library.playerTitle = false
            library.playerEpisode = false
            library.playerClock = false
            library.playerSpeed = false
            library.playerPanelTimeout = 6
            PhoneDanmakuSize.set(this, PhoneDanmakuSize.DEFAULT_PERCENT)
            library.launchRecommend = true
            library.episodePageSize = 20
            library.episodeEdgeTurn = true
        }
        PhoneUiScale.reset(this)
        PhoneStyle.applyTheme(TvTheme.DEEP_SEA_BLUE)
        toast("已恢复默认设置")
        recreate()
    }

    // ---------------------------------------------------------------- 备份 / 恢复

    /**
     * 导出：走系统的「创建文档」。
     *
     * 与 TV 的「手机备份与恢复」（局域网把 JSON 传给手机）不同——手机上导出再传给自己没有意义，
     * 因此这里直接落到用户选的文件里（下载目录 / 网盘 / 聊天工具都行）。
     * 用的是 SAF，不需要任何存储权限，4.4 起就有。
     */
    private fun exportBackup() {
        val encoded = runCatching { LibraryBackup.encode(library.snapshot()) }.getOrNull()
        if (encoded == null) { toast("本机记录暂时无法导出"); return }
        pendingExport = encoded
        val name = "hgtv-backup-${System.currentTimeMillis() / 1000}.json"
        runCatching {
            startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json")
                    .putExtra(Intent.EXTRA_TITLE, name),
                REQUEST_EXPORT
            )
        }.onFailure { toast("这台设备没有文件选择器") }
    }

    /** 恢复：选一个 JSON，解码后按 TV 的同一套规则合并（设置一起带回来）。 */
    private fun importBackup() {
        runCatching {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*"),
                REQUEST_IMPORT
            )
        }.onFailure { toast("这台设备没有文件选择器") }
    }

    // ---------------------------------------------------------------- 局域网同步

    private fun closeLanSyncSession() {
        lanSyncJob?.cancel()
        lanSyncJob = null
        lanSyncDialog?.dismiss()
        lanSyncDialog = null
        lanSyncStatus = null
        lanSyncStep = null
        lanSyncController.reset()
    }

    private fun showLanLibrarySync() {
        closeLanSyncSession()
        PhoneDialog(this, "同步其他设备数据")
            .message("请选择这台设备在本次同步中的角色。")
            .choices(
                listOf(
                    "我要同步其他设备的数据",
                    "其他设备要同步我的数据",
                ),
            ) { index -> startLanSyncRole(sourceOnly = index == 1) }
            .button("取消")
            .show()
    }

    private fun startLanSyncRole(sourceOnly: Boolean) {
        closeLanSyncSession()
        lanSyncJob = scope.launch {
            lanSyncController.state.collect { state -> renderLanSync(state) }
        }
        if (sourceOnly) lanSyncController.startSource() else lanSyncController.startReceiver()
    }

    private fun showLanSyncNoPeers() {
        PhoneDialog(this, "未发现同步设备")
            .message("请确认两台设备连接同一局域网，并同时打开「同步其他设备数据」。")
            .button("重新查找") { startLanSyncRole(sourceOnly = false) }
            .button("取消")
            .show()
    }

    private fun showLanSyncRestoreConfirm() {
        var login = true
        var device = true
        var content = true
        var settings = true
        fun syncSwitch(label: String, checked: Boolean, onToggle: (Boolean) -> Unit): PhoneSwitchRow =
            PhoneSwitchRow(this, label, checked, onToggle = onToggle).apply {
                minimumHeight = dp(if (landscape) 36 else 42)
            }
        val dialog = PhoneDialog(this, "选择要同步的数据")
            .message("登录、设备与设置会覆盖本机；播放和收藏等记录会合并，并保留进度更高的一项。")
            .content(syncSwitch("登录信息", true) { login = it })
            .content(syncSwitch("设备参数", true) { device = it })
            .content(syncSwitch("播放、收藏等记录", true) { content = it })
            .content(syncSwitch("设置信息", true) { settings = it })
        dialog.buttonsInRow()
        dialog.button("取消")
        dialog.button("同步所选", primary = true) {
            val selection = DeviceSyncSelection(
                login = login,
                device = device,
                content = content,
                settings = settings,
            )
            if (!selection.hasAny) {
                toast("请至少选择一类同步内容")
            } else {
                lanSyncController.apply(selection) { saved ->
                    if (!saved) toast("部分数据未能写入本机，请重试")
                }
            }
        }
        dialog.show()
    }

    private fun renderLanSync(state: LanSyncController.State) {
        when (state.step) {
            LanSyncController.Step.IDLE -> Unit
            LanSyncController.Step.SERVING, LanSyncController.Step.DISCOVERING -> {
                if (lanSyncStep != state.step) {
                    lanSyncDialog?.dismiss()
                    lanSyncStep = state.step
                    val text = TextView(this).apply {
                        setTextColor(PhoneStyle.textDim)
                        phoneSize(if (landscape) 11 else 13)
                        setPadding(dp(4), dp(4), dp(4), dp(4))
                    }
                    lanSyncStatus = text
                    val dialog = PhoneDialog(
                        this,
                        if (state.role == LanSyncController.Role.SOURCE) "本机作为同步源" else "同步其他设备数据",
                    ).message(
                        if (state.role == LanSyncController.Role.SOURCE) {
                            "本机将一直出现在同一局域网的设备列表中。请保持此页面打开。"
                        } else {
                            "正在通过局域网查找其他设备。请在另一台设备上也打开这个功能。"
                        }
                    ).content(text)
                    dialog.button("取消")
                    dialog.show { if (lanSyncDialog === dialog) closeLanSyncSession() }
                    lanSyncDialog = dialog
                }
                lanSyncStatus?.text = state.message.ifBlank {
                    if (state.role == LanSyncController.Role.SOURCE) "正在开启同步源…" else "正在查找设备…"
                }
            }
            LanSyncController.Step.PEERS -> {
                if (lanSyncStep == state.step) return
                lanSyncStep = state.step
                lanSyncDialog?.dismiss()
                lanSyncDialog = null
                val peers = state.peers
                PhoneDialog(this, "选择同步设备")
                    .message("读取登录信息、设备参数、内容数据和全部设置。读取后仍需在本机确认，确认后会覆盖本机登录与设备身份。")
                    .choices(peers.map { "${it.displayName}  ·  ${it.host}" }) { index ->
                        peers.getOrNull(index)?.let(lanSyncController::loadPeer)
                    }
                    .button("重新查找") { startLanSyncRole(sourceOnly = false) }
                    .button("取消") { closeLanSyncSession() }
                    .show()
            }
            LanSyncController.Step.LOADING, LanSyncController.Step.APPLYING -> {
                if (lanSyncStep == state.step) return
                lanSyncStep = state.step
                lanSyncDialog?.dismiss()
                val dialog = PhoneDialog(
                    this,
                    if (state.step == LanSyncController.Step.LOADING) "正在同步" else "正在应用同步数据",
                ).message(state.message).button("取消") { closeLanSyncSession() }
                lanSyncDialog = dialog
                dialog.show()
            }
            LanSyncController.Step.READY_TO_APPLY -> {
                if (lanSyncStep == state.step) return
                lanSyncStep = state.step
                lanSyncDialog?.dismiss()
                lanSyncDialog = null
                showLanSyncRestoreConfirm()
            }
            LanSyncController.Step.ERROR -> {
                lanSyncDialog?.dismiss()
                lanSyncDialog = null
                if (state.message == "没有发现同步设备") {
                    closeLanSyncSession()
                    showLanSyncNoPeers()
                } else {
                    lanSyncStatus?.text = state.error
                    if (state.error.isNotBlank() && state.step != LanSyncController.Step.DISCOVERING) {
                        toast(state.error)
                    }
                }
            }
            LanSyncController.Step.DONE -> {
                lanSyncDialog?.dismiss()
                lanSyncDialog = null
                toast("同步完成，正在重启应用")
                syncHandler.postDelayed({
                    if (!isDestroyed) AppRelaunch.relaunchAndExit(this)
                }, 800L)
            }
        }
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

    // ---------------------------------------------------------------- 清空

    /**
     * 清空本机数据：与 TV 的 `performLocalReset` 同一条路（`LocalReset`）。
     *
     * 手机上少两步：不释放播放器（这个页面没有播放器）、不弹存储权限（targetSdk 29 的
     * 传统存储模式下 `LocalReset.wipe` 已经覆盖沙盒与外部私有目录；`/sdcard/.hgtv` 删不掉时
     * 会留在 `.reset-pending` 标记后面，下次启动不会再被捞回来）。
     */
    private fun performReset() {
        LocalReset.resetting = true
        runCatching { library.discardPendingWrites() }
        Thread({
            runCatching { LocalReset.wipe(this) }
            runCatching { Thread.sleep(350) }
            runOnUiThread {
                runCatching { LocalReset.wipe(this) }
                LocalReset.markPendingReset(this)
                LocalReset.relaunchAndExit(this)
            }
        }, "hgtv-phone-reset").start()
    }

    // ---------------------------------------------------------------- 控件

    private fun targetHost(): LinearLayout = sectionHost ?: container
    private fun settingsDp(value: Number): Int = settingsDpImpl(value)
    private fun TextView.settingsTextSize(value: Number): TextView =
        settingsTextSizeImpl(value)
    private fun settingRowHeight(): Int = settingRowHeightImpl()
    private fun settingRowGap(): Int = settingRowGapImpl()

    private fun section(title: String) {
        if (landscape) {
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                tag = anchorKey("section", title)
                setPadding(settingsDp(12), settingsDp(10), settingsDp(12), settingsDp(6))
                applySoft(SoftStyle.RAISED, PhoneStyle.surface, PhoneStyle.RADIUS_CARD, 3f)
            }
            panel.addView(TextView(this).apply {
                text = title
                setTextColor(PhoneStyle.text)
                settingsTextSize(13)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(settingsDp(2), 0, settingsDp(2), settingsDp(6))
            })
            container.addView(panel, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = settingsDp(8) })
            sectionHost = panel
            return
        }
        sectionHost = null
        container.addView(TextView(this).apply {
            tag = anchorKey("section", title)
            text = title
            setTextColor(PhoneStyle.textDim)
            settingsTextSize(12)
            setPadding(settingsDp(6), settingsDp(20), settingsDp(6), settingsDp(6))
        })
    }

    private fun hint(text: String) {
        targetHost().addView(TextView(this).apply {
            this.text = text
            setTextColor(PhoneStyle.textFaint)
            settingsTextSize(if (landscape) 11 else 12)
            setPadding(
                settingsDp(6),
                settingsDp(if (landscape) 1 else 2),
                settingsDp(6),
                settingsDp(if (landscape) 3 else 4),
            )
        })
    }

    /** 一行设置：与电视端设置按钮同一种胶囊行，左项名、右读数。 */
    private fun row(title: String, value: String, onClick: () -> Unit) {
        val line = LinearLayout(this).apply {
            tag = anchorKey("row", title)
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(settingsDp(14), 0, settingsDp(12), 0)
            applySoft(SoftStyle.RAISED, PhoneStyle.surface, PhoneStyle.RADIUS_PILL, 4f)
            addView(TextView(this@PhoneSettingsActivity).apply {
                text = title
                setTextColor(PhoneStyle.text)
                settingsTextSize(if (landscape) 14 else 15)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@PhoneSettingsActivity).apply {
                text = value
                setTextColor(PhoneStyle.textDim)
                settingsTextSize(if (landscape) 12 else 13)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(TextView(this@PhoneSettingsActivity).apply {
                text = "›"
                setTextColor(PhoneStyle.textFaint)
                settingsTextSize(if (landscape) 13 else 14)
                setPadding(settingsDp(6), 0, 0, 0)
            })
            setOnClickListener { onClick() }
        }
        targetHost().addView(line, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, settingRowHeight()
        ).apply { bottomMargin = settingRowGap() })
    }

    /** 整行开关：与电视端同一枚自绘轨道，点整行切换。 */
    private fun switch(title: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
        val line = PhoneSwitchRow(
            this, title, checked,
            densityScale=SETTINGS_DENSITY_SCALE,
            textScale=SETTINGS_TEXT_SCALE,
            onToggle=onToggle,
        ).apply {
            tag = anchorKey("row", title)
        }
        targetHost().addView(line, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, settingRowHeight()
        ).apply { bottomMargin = settingRowGap() })
    }

    /** 动作行：与电视端设置项同一种单行胶囊，右侧小字说明去向。 */
    private fun action(title: String, subtitle: String, onClick: () -> Unit) {
        row(title, subtitle, onClick)
    }

    /** 滑动条与电视端同一种整行控件：项名、轨道、读数在一块胶囊底板里。 */
    private fun slider(
        title: String, min: Float, max: Float, step: Float, value: Float,
        format: (Float) -> String,
        onChange: (Float) -> Unit
    ) {
        val control = PhoneSettingSlider(
            this, title, min, max, step, value, onChange, format,
            densityScale=SETTINGS_DENSITY_SCALE,
            textScale=SETTINGS_TEXT_SCALE,
        ).apply {
            tag = anchorKey("row", title)
        }
        targetHost().addView(control, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, settingRowHeight()
        ).apply { bottomMargin = settingRowGap() })
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
        PhoneDialog(this, title)
            .choices(labels, selected, onPick)
            .button("取消")
            .show()
    }

    private fun confirm(title: String, message: String, danger: Boolean = false, onOk: () -> Unit) {
        PhoneDialog(this, title)
            .message(message)
            .button("取消")
            .button(if (danger) "清空" else "确定", primary = true, danger = danger, run = onOk)
            .show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

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
        closeLanSyncSession()
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
}
