// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.content.Intent
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
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
import android.widget.TextView
import android.widget.Toast
import com.hgtv.star.core.CategoryGenres
import com.hgtv.star.core.DANMAKU_MERGE_MIN_RANGE
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
import com.hgtv.star.shared.compat.DeviceForm
import com.hgtv.star.shared.compat.detectDeviceForm
import com.hgtv.star.shared.data.LocalReset
import com.hgtv.star.shared.architecture.AppRelaunch
import com.hgtv.star.shared.ui.SoftStyle
import com.hgtv.star.shared.ui.TvTheme
import com.hgtv.star.shared.ui.TvThemeStore
import kotlin.math.roundToInt

/** 手机设置页的四个大项，名称和内容边界与电视端 `SETTINGS_TABS` 一致。 */
enum class PhoneSettingsPage(val label: String, val hint: String) {
    PLAYBACK("播放交互", "播放与弹幕"),
    APPEARANCE("外观导航", "主题与列表"),
    RECOMMEND("内容推荐", "推荐与来源"),
    SYSTEM("系统高级", "数据与性能"),
}

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
    /** 外层滚动容器：重建列表时用它保住当前的滚动位置。 */
    private lateinit var scroller: ScrollView
    private val library by lazy { PhoneServices.library(this) }
    private var page = PhoneSettingsPage.PLAYBACK

    /**
     * 设置页重建时恢复到的位置。
     *
     * 只记像素不够：字体或主题换档后每一行的高度会变，同一个 scrollY 已经指着另一项。
     * 这里记「第一项可见设置」的稳定 key 与它相对屏幕顶部的偏移，重建后按同一个 key 找回。
     */
    private data class ScrollAnchor(val key: String, val offset: Int)

    private var pendingScrollAnchor: ScrollAnchor? = null
    private var scrollRestoreListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val storedPage = savedInstanceState?.getString(KEY_PAGE)
            ?: intent.getStringExtra(EXTRA_PAGE)
        page = PhoneSettingsPage.entries.firstOrNull { it.name == storedPage }
            ?: PhoneSettingsPage.PLAYBACK
        PhoneStyle.ensure(this)
        phoneEdgeToEdge()
        savedInstanceState?.let { state ->
            val key = state.getString(KEY_SCROLL_ANCHOR).orEmpty()
            if (key.isNotBlank()) {
                pendingScrollAnchor = ScrollAnchor(key, state.getInt(KEY_SCROLL_OFFSET, 0))
            }
        }

        val root = FrameLayout(this).apply { setBackgroundColor(PhoneStyle.background) }

        val status = statusBarHeight()
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), status + dp(6), dp(16), dp(8))
        }
        bar.addView(TextView(this).apply {
            text = "设置"
            setTextColor(PhoneStyle.text)
            phoneSize(19)
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
        ))

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(2), dp(16), dp(40))
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
        ).apply { topMargin = status + dp(30) })

        setContentView(root)

        if (library.isLoaded) render()
        else library.loadAsync(onFailure = { runOnUiThread { render() } }, onReady = { runOnUiThread { render() } })
    }

    // ---------------------------------------------------------------- 渲染

    private fun render(resetScroll: Boolean = false) {
        // 改一项就整页重建（值是同一份落盘数据，重建最省事），但**滚动位置要保住**：
        // 否则在页面下半部分拨一下开关，视线就被弹回顶部，用户会以为点错了。
        val anchor = if (resetScroll) null else pendingScrollAnchor ?: currentScrollAnchor()
        pendingScrollAnchor = null
        container.removeAllViews()
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
        when (page) {
            PhoneSettingsPage.PLAYBACK -> {
                renderPlayback()
                renderDanmaku()
            }
            PhoneSettingsPage.APPEARANCE -> renderAppearance()
            PhoneSettingsPage.RECOMMEND -> renderRecommend()
            PhoneSettingsPage.SYSTEM -> renderSystem()
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
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = TAG_PAGE_GRID
        }
        PhoneSettingsPage.entries.chunked(2).forEachIndexed { rowIndex, line ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                if (rowIndex > 0) setPadding(0, dp(6), 0, 0)
            }
            line.forEachIndexed { index, option ->
                val active = option == page
                val button = TextView(this).apply {
                    text = option.label
                    phoneSize(14)
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
                row.addView(button, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    if (index > 0) leftMargin = dp(6)
                })
            }
            grid.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        container.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(4) })
    }

    // —— 一、播放交互 ——

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
        section("播放显示")
        hint("画面左上角常驻的几项信息，默认全关。与电视端同一批开关：后两项是「当前时间」与「网速」。")
        switch("剧集标题", library.playerTitle) { library.playerTitle = it; render() }
        switch("当前集数", library.playerEpisode) { library.playerEpisode = it; render() }
        switch("当前时间", library.playerClock) { library.playerClock = it; render() }
        switch("网速", library.playerSpeed) { library.playerSpeed = it; render() }

        section("播放界面")
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
        hint("显示方式与速度共用电视端设置；字号按手机阅读单独调整，改完回到播放页即生效。")
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
        slider("聚合条数", DANMAKU_MERGE_MIN_RANGE.first.toFloat(),
            DANMAKU_MERGE_MIN_RANGE.last.toFloat(), 1f,
            library.danmakuMergeMin.toFloat(), { "${it.roundToInt()} 条" }) {
            library.danmakuMergeMin = it.roundToInt()
        }
        slider("弹幕字体大小", PhoneDanmakuSize.MIN_PERCENT.toFloat(),
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
        // 「外观与布局」这一组把最常调的三项放在最上面（用户要求：列表布局与字体大小放在一起），
        // 二十套主题网格排在它们之后。
        section("外观与布局")
        row("字体大小", PhoneUiScale.label(PhoneUiScale.value)) {
            val options = PhoneUiScale.sizes
            pick(
                "字体大小",
                options.map { it.label },
                options.indexOf(PhoneUiScale.sizeOf(PhoneUiScale.value)),
            ) { index ->
                PhoneUiScale.set(this, options[index].scale)
                recreate()
            }
        }
        row("列表每行", "${library.gridColumns} 张") {
            // 手机竖屏只保留 1 / 2 / 3 三档：默认 2 张，字大或想看清封面可以选 1 张。
            val options = listOf(1, 2, 3)
            pick("海报列表每行几张", options.map { "$it 张" },
                options.indexOf(library.gridColumns).coerceAtLeast(0)) { index ->
                library.gridColumns = options[index]
                render()
            }
        }
        hint("与电视端同一份落盘值，只保留手机竖屏能完整显示的列数。")
        // 推荐页三个体裁（漫剧 / 真人 / AI 剧）之间能不能用手指左右划。
        // 关掉之后只剩顶部那三个体裁标签换流——竖屏短剧一屏一集，横滑误触太常见。
        switch("推荐页左右滑动", library.recommendSwipe) {
            library.recommendSwipe = it
            render()
        }
        hint("关闭后推荐页只能用顶部体裁标签换流，手指左右划不会整页翻走。")

        // 主题放在最后：二十套色卡占的地方最大，日常不需要频繁改。
        hint("当前：${PhoneStyle.theme.label} · ${PhoneStyle.theme.scene}　（${TvTheme.entries.size} 套，选中即生效）")
        themeGrid()
    }

    /** 二十套主题：两列一行，手机上名称与色点都能完整显示。 */
    private fun themeGrid() {
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = TAG_THEME_GRID
        }
        TvTheme.entries.chunked(2).forEach { line ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, 0)
            }
            repeat(2) { index ->
                val option = line.getOrNull(index)
                if (option == null) {
                    row.addView(View(this), LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                        if (index > 0) leftMargin = dp(6)
                    })
                    return@repeat
                }
                val active = option == PhoneStyle.theme
                val chip = TextView(this).apply {
                    text = SpannableString("●  ${option.label}").apply {
                        setSpan(ForegroundColorSpan(option.accent), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(RelativeSizeSpan(.72f), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    phoneSize(13)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), 0, dp(10), 0)
                    setTextColor(if (active) PhoneStyle.accent else PhoneStyle.text)
                    applySoft(
                        if (active) SoftStyle.INSET else SoftStyle.RAISED,
                        PhoneStyle.surface, PhoneStyle.RADIUS_PILL, 4f
                    )
                    contentDescription = option.label + "，" + option.scene +
                        if (active) "，当前主题" else ""
                    setOnClickListener { switchTheme(option) }
                }
                row.addView(chip, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                    if (index > 0) leftMargin = dp(6)
                })
            }
            grid.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        container.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(4) })
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
        section("推荐范围")
        hint("大类至少留一个；受众可不限。改完下次进推荐生效，已刷到的位置保留。")
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, 0)
            tag = "grid:recommendGenres"
        }
        CategoryGenres.recommendable.forEachIndexed { index, genre ->
            val id = genre.id.orEmpty()
            val on = library.recommendGenreEnabled(id)
            val chip = TextView(this).apply {
                text = genre.label
                phoneSize(13)
                gravity = Gravity.CENTER
                setTextColor(if (on) PhoneStyle.accent else PhoneStyle.textDim)
                applySoft(
                    if (on) SoftStyle.INSET else SoftStyle.RAISED,
                    PhoneStyle.surface, PhoneStyle.RADIUS_PILL, 4f
                )
                setOnClickListener {
                    val next = !library.recommendGenreEnabled(id)
                    // 关掉最后一类：直接拦下并说明，否则「没有推荐范围」与「全部体裁」读起来正好相反。
                    if (!next && library.recommendGenreOff.size + 1 >= CategoryGenres.recommendableIds.size) {
                        toast("至少保留一个推荐大类")
                        return@setOnClickListener
                    }
                    library.setRecommendGenre(id, next)
                    render()
                }
            }
            row.addView(chip, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                if (index > 0) leftMargin = dp(6)
            })
        }
        container.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6) })

        row("受众", genderLabel(library.recommendGender)) {
            pick("推荐受众", listOf("不限", "男频", "女频"), genderIndex(library.recommendGender)) { index ->
                library.recommendGender = listOf("", "male", "female")[index]
                render()
            }
        }
        switch("打开应用进入推荐", library.launchRecommend) {
            library.launchRecommend = it
            render()
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
        if (BuildConfig.DEBUG) {
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
                    AppRelaunch.relaunchAndExit(this)
                }
            }
            hint("仅 debug 版本显示：用于强制进入手机、平板或电视界面，切换后自动重新进入。")
        }
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

    /** 出厂值：与 `BackupSettings` 的默认参数逐项对齐。 */
    private fun restoreDefaults() {
        runCatching {
            library.maxQuality = 1080
            library.playbackSpeed = 1f
            library.frameMode = VideoFrameMode.FIT
            library.decoderMode = VideoDecoderMode.HARDWARE
            library.videoScale = 100f
            library.autoNext = true
            library.seekStep = 10
            library.autoFullscreen = false
            library.playerTitle = false
            library.playerEpisode = false
            library.playerClock = false
            library.playerSpeed = false
            library.playerPanelTimeout = 6
            library.danmakuMode = DanmakuMode.FULL
            library.danmakuSpeed = DanmakuSpeed.NORMAL
            library.danmakuMerge = true
            library.danmakuMergeMin = 2
            library.danmakuScale = 1f
            library.danmakuAlpha = 1f
            PhoneDanmakuSize.set(this, PhoneDanmakuSize.DEFAULT_PERCENT)
            library.recommendGender = ""
            library.launchRecommend = true
            library.recommendSwipe = true
            library.episodePageSize = 20
            library.episodeEdgeTurn = true
            library.searchSource = SearchSource.APP
            library.reduceAnim = false
            library.deviceForm = "auto"
        }
        PhoneUiScale.set(this, PhoneUiScale.default)
        TvThemeStore.save(this, TvTheme.DEEP_SEA_BLUE)
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

    private fun section(title: String) {
        container.addView(TextView(this).apply {
            tag = anchorKey("section", title)
            text = title
            setTextColor(PhoneStyle.textDim)
            phoneSize(12)
            setPadding(dp(6), dp(20), dp(6), dp(6))
        })
    }

    private fun hint(text: String) {
        container.addView(TextView(this).apply {
            this.text = text
            setTextColor(PhoneStyle.textFaint)
            phoneSize(12)
            setPadding(dp(6), dp(2), dp(6), dp(4))
        })
    }

    /** 一行设置：与电视端设置按钮同一种胶囊行，左项名、右读数。 */
    private fun row(title: String, value: String, onClick: () -> Unit) {
        val line = LinearLayout(this).apply {
            tag = anchorKey("row", title)
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(12), 0)
            applySoft(SoftStyle.RAISED, PhoneStyle.surface, PhoneStyle.RADIUS_PILL, 4f)
            addView(TextView(this@PhoneSettingsActivity).apply {
                text = title
                setTextColor(PhoneStyle.text)
                phoneSize(15)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@PhoneSettingsActivity).apply {
                text = value
                setTextColor(PhoneStyle.textDim)
                phoneSize(13)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(TextView(this@PhoneSettingsActivity).apply {
                text = "›"
                setTextColor(PhoneStyle.textFaint)
                phoneSize(14)
                setPadding(dp(6), 0, 0, 0)
            })
            setOnClickListener { onClick() }
        }
        container.addView(line, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(42)
        ).apply { bottomMargin = dp(6) })
    }

    /** 整行开关：与电视端同一枚自绘轨道，点整行切换。 */
    private fun switch(title: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
        val line = PhoneSwitchRow(this, title, checked, onToggle).apply {
            tag = anchorKey("row", title)
        }
        container.addView(line, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(42)
        ).apply { bottomMargin = dp(6) })
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
        val control = PhoneSettingSlider(this, title, min, max, step, value, onChange, format).apply {
            tag = anchorKey("row", title)
        }
        container.addView(control, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(42)
        ).apply { bottomMargin = dp(6) })
    }

    private fun anchorKey(kind: String, title: String): String = "$kind:$title"

    private fun deviceFormLabel(detected: DeviceForm): String =
        DeviceForm.ofStored(library.deviceForm)?.label ?: "自动 · ${detected.label}"

    /** 找出当前视口顶部第一枚带稳定 key 的设置项。 */
    private fun currentScrollAnchor(): ScrollAnchor? {
        if (!::scroller.isInitialized || !::container.isInitialized) return null
        val top = scroller.scrollY
        var last: View? = null
        for (index in 0 until container.childCount) {
            val child = container.getChildAt(index)
            val key = child.tag as? String ?: continue
            if (!key.startsWith("section:") && !key.startsWith("row:") &&
                !key.startsWith("grid:")
            ) continue
            last = child
            if (child.bottom > top) return ScrollAnchor(key, child.top - top)
        }
        return last?.let { ScrollAnchor(it.tag as String, it.top - top) }
    }

    /**
     * 新内容完成布局后再滚动。
     *
     * 之前直接在 `post` 里恢复：那一刻新加的行还没 measure，ScrollView 的纵向范围仍是
     * 空的，scrollTo 会被夹回 0。这里等到目标行真正有高度再恢复，并只消费一次。
     */
    private fun restoreScroll(anchor: ScrollAnchor?, resetTop: Boolean = false) {
        scrollRestoreListener?.let { listener ->
            runCatching { scroller.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
        }
        scrollRestoreListener = null
        if (anchor == null && !resetTop) return
        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val observer = scroller.viewTreeObserver
                if (observer.isAlive) observer.removeOnGlobalLayoutListener(this)
                scrollRestoreListener = null
                if (resetTop || anchor == null) {
                    scroller.scrollTo(0, 0)
                    return
                }
                val target = (0 until container.childCount)
                    .map { container.getChildAt(it) }
                    .firstOrNull { it.tag == anchor.key && it.height > 0 }
                scroller.scrollTo(0, target?.let { (it.top - anchor.offset).coerceAtLeast(0) } ?: 0)
            }
        }
        scrollRestoreListener = listener
        scroller.viewTreeObserver.addOnGlobalLayoutListener(listener)
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

    private fun genderLabel(v: String) = when (v) {
        "male" -> "男频"
        "female" -> "女频"
        else -> "不限"
    }

    private fun genderIndex(v: String) = when (v) {
        "male" -> 1
        "female" -> 2
        else -> 0
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_PAGE, page.name)
        currentScrollAnchor()?.let { anchor ->
            outState.putString(KEY_SCROLL_ANCHOR, anchor.key)
            outState.putInt(KEY_SCROLL_OFFSET, anchor.offset)
        }
        super.onSaveInstanceState(outState)
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
