// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.graphics.drawable.ColorDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.hgtv.star.core.DANMAKU_MERGE_MIN_RANGE
import com.hgtv.star.core.DanmakuMode
import com.hgtv.star.core.DanmakuSpeed
import com.hgtv.star.core.DanmakuStyle
import com.hgtv.star.core.EpisodePageSize
import com.hgtv.star.core.PLAYER_PANEL_TIMEOUT_RANGE
import com.hgtv.star.core.PlaybackQuality
import com.hgtv.star.core.PlaybackSpeed
import com.hgtv.star.core.SearchSource
import com.hgtv.star.core.VideoDecoderMode
import com.hgtv.star.core.VideoFrameMode
import com.hgtv.star.phone.ui.PhoneSoft.applySoft
import com.hgtv.star.phone.ui.PhoneStyle
import com.hgtv.star.phone.ui.PhoneUiScale
import com.hgtv.star.shared.compat.DeviceForm
import com.hgtv.star.shared.ui.SoftStyle
import com.hgtv.star.shared.ui.TvTheme
import com.hgtv.star.shared.ui.TvThemeStore
import kotlin.math.roundToInt

internal fun PhoneSettingsActivity.renderPlayback() {
    sectionImpl("播放偏好")

    rowImpl("默认清晰度", "${settingsLibrary.maxQuality}P") {
        val options = PlaybackQuality.options
        pickImpl("默认清晰度", options.map { "$it P" },
            options.indexOf(settingsLibrary.maxQuality).coerceAtLeast(0)) { index ->
            settingsLibrary.maxQuality = options[index]
            renderSettings()
        }
    }
    sliderImpl(
        "默认倍速",
        PlaybackSpeed.MIN,
        PlaybackSpeed.MAX,
        PlaybackSpeed.STEP,
        settingsLibrary.playbackSpeed,
        PlaybackSpeed::label,
    ) { value ->
        settingsLibrary.playbackSpeed = value
    }
    rowImpl("画面模式", settingsLibrary.frameMode.label) {
        val options = VideoFrameMode.entries
        pickImpl("画面模式", options.map { it.label }, options.indexOf(settingsLibrary.frameMode)) { index ->
            settingsLibrary.frameMode = options[index]
            renderSettings()
        }
    }
    // 解码方式在**建播放器时**生效：改完下次进播放页（或下次起播）按新的一档重建。
    rowImpl("视频解码", settingsLibrary.decoderMode.label) {
        val options = VideoDecoderMode.entries
        pickImpl("视频解码", options.map { it.label }, options.indexOf(settingsLibrary.decoderMode)) { index ->
            settingsLibrary.decoderMode = options[index]
            renderSettings()
        }
    }
    hintImpl("硬件省电、4K 更稳；软件兼容性更好，花屏、绿屏、有声无画时换它。改完下一次起播生效。")

    sliderImpl("画面缩放", 1f, 100f, 1f, settingsLibrary.videoScale, { "${it.roundToInt()}%" }) {
        settingsLibrary.videoScale = it
    }
    switchImpl("自动连播", settingsLibrary.autoNext) {
        settingsLibrary.autoNext = it
        renderSettings()
    }
    sliderImpl("快进快退", 2f, 20f, 1f, settingsLibrary.seekStep.toFloat(), { "${it.roundToInt()} 秒" }) {
        settingsLibrary.seekStep = it.roundToInt()
    }
    sectionImpl("界面信息")
    hintImpl("画面顶部常驻信息，默认全关。依次为剧名、剧集、网速、时间。")
    switchImpl("剧名", settingsLibrary.playerTitle) { settingsLibrary.playerTitle = it; renderSettings() }
    switchImpl("剧集", settingsLibrary.playerEpisode) { settingsLibrary.playerEpisode = it; renderSettings() }
    switchImpl("网速", settingsLibrary.playerSpeed) { settingsLibrary.playerSpeed = it; renderSettings() }
    switchImpl("时间", settingsLibrary.playerClock) { settingsLibrary.playerClock = it; renderSettings() }

    sliderImpl("自动消失", PLAYER_PANEL_TIMEOUT_RANGE.first.toFloat(),
        PLAYER_PANEL_TIMEOUT_RANGE.last.toFloat(), 1f,
        settingsLibrary.playerPanelTimeout.toFloat(), { "${it.roundToInt()} 秒" }) {
        settingsLibrary.playerPanelTimeout = it.roundToInt()
    }
    hintImpl("播放界面上的标题、进度条与设置/选集面板无操作后自动收起，点一下画面恢复。")
}

internal fun PhoneSettingsActivity.renderDanmaku() {
    sectionImpl("弹幕")
    hintImpl("显示方式、聚合与特效共用电视端设置；字号按手机阅读单独调整，改完回到播放页即生效。")
    sliderImpl("显示方式", 0f, DanmakuMode.entries.lastIndex.toFloat(), 1f,
        settingsLibrary.danmakuMode.sliderValue, { DanmakuMode.fromSlider(it).optionLabel }) {
        settingsLibrary.danmakuMode = DanmakuMode.fromSlider(it)
    }
    rowImpl("弹幕速度", settingsLibrary.danmakuSpeed.label) {
        val options = DanmakuSpeed.entries
        pickImpl("弹幕速度", options.map { it.label }, options.indexOf(settingsLibrary.danmakuSpeed)) { index ->
            settingsLibrary.danmakuSpeed = options[index]
            renderSettings()
        }
    }
    switchImpl("相同弹幕聚合", settingsLibrary.danmakuMerge) {
        settingsLibrary.danmakuMerge = it
        renderSettings()
    }
    switchImpl("开启弹幕特效", settingsLibrary.danmakuEffects) {
        settingsLibrary.danmakuEffects = it
        renderSettings()
    }
    sliderImpl("聚合条数", DANMAKU_MERGE_MIN_RANGE.first.toFloat(),
        DANMAKU_MERGE_MIN_RANGE.last.toFloat(), 1f,
        settingsLibrary.danmakuMergeMin.toFloat(), { "${it.roundToInt()} 条" }) {
        settingsLibrary.danmakuMergeMin = it.roundToInt()
    }
    sliderImpl("弹幕大小", PhoneDanmakuSize.MIN_PERCENT.toFloat(),
        PhoneDanmakuSize.MAX_PERCENT.toFloat(), PhoneDanmakuSize.STEP_PERCENT.toFloat(),
        PhoneDanmakuSize.percent(this).toFloat(), { PhoneDanmakuSize.label(it) }) {
        PhoneDanmakuSize.set(this, it)
    }
    sliderImpl("弹幕透明度", DanmakuStyle.ALPHA_MIN, DanmakuStyle.ALPHA_MAX, DanmakuStyle.ALPHA_STEP,
        settingsLibrary.danmakuAlpha, { DanmakuStyle.percent(it) }) {
        settingsLibrary.danmakuAlpha = DanmakuStyle.alpha(it)
    }
}

internal fun PhoneSettingsActivity.renderAppearance() {
    // 「外观与布局」这一组把最常调的三项放在最上面（UI 大小与列表布局放在一起），
    // 二十套主题网格排在它们之后。
    sectionImpl("外观与布局")
    rowImpl("UI大小", PhoneUiScale.label(PhoneUiScale.value)) {
        val options = PhoneUiScale.sizes
        pickImpl(
            "UI大小",
            options.map { it.label },
            options.indexOf(PhoneUiScale.sizeOf(PhoneUiScale.value)),
        ) { index ->
            PhoneUiScale.set(this, options[index].scale)
            recreate()
        }
    }
    val baseColumns = settingsLibrary.gridColumns.coerceIn(1, 3)
    val shownColumns = baseColumns + if (settingsLandscape) 3 else 0
    rowImpl("列表每行", "$shownColumns 张") {
        val options = listOf(1, 2, 3)
        pickImpl("海报列表每行几张",
            options.map { "竖屏 $it 张 · 横屏 ${it + 3} 张" },
            options.indexOf(baseColumns).coerceAtLeast(0)) { index ->
            settingsLibrary.gridColumns = options[index]
            renderSettings()
        }
    }
    hintImpl("竖屏可选 1–3 张；横屏对应显示 4–6 张。")
    // 推荐、分类和榜单页能不能用手指左右划着切换顶部标签。
    // 关掉之后只剩点标签切换，避免竖屏短剧场景里的横滑误触。
    switchImpl("页面左右滑动", settingsLibrary.recommendSwipe) {
        settingsLibrary.recommendSwipe = it
        renderSettings()
    }
    hintImpl("关闭后推荐、分类、榜单和我的只能用顶部标签切换，手指左右划不会整页翻走。")

    // 主题放在最后：二十套色卡占的地方最大，日常不需要频繁改。
    hintImpl("当前：${PhoneStyle.theme.label} · ${PhoneStyle.theme.scene}　（${TvTheme.entries.size} 套，选中即生效）")
    if (settingsLandscape) sectionImpl("主题")
    themeGrid()
}

/** 二十套主题：两列一行，手机上名称与色点都能完整显示。 */
internal fun PhoneSettingsActivity.themeGrid() {
    val columns = if(settingsLandscape) 5 else 2
    val chipHeight = settingsDpImpl(if (settingsLandscape) 34 else 38)
    val grid = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        tag = PhoneSettingsActivity.TAG_THEME_GRID
    }
    TvTheme.entries.chunked(columns).forEach { line ->
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, settingsDpImpl(4), 0, 0)
        }
        repeat(columns) { index ->
            val option = line.getOrNull(index)
            if (option == null) {
                row.addView(View(this), LinearLayout.LayoutParams(0, chipHeight, 1f).apply {
                    if (index > 0) leftMargin = settingsDpImpl(6)
                })
                return@repeat
            }
            val active = option == PhoneStyle.theme
            val chip = TextView(this).apply {
                text = SpannableString("●  ${option.label}").apply {
                    setSpan(ForegroundColorSpan(option.accent), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(RelativeSizeSpan(.72f), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                settingsTextSizeImpl(if (settingsLandscape) 12 else 13)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(settingsDpImpl(14), 0, settingsDpImpl(10), 0)
                setTextColor(if (active) PhoneStyle.accent else PhoneStyle.text)
                applySoft(
                    if (active) SoftStyle.INSET else SoftStyle.RAISED,
                    PhoneStyle.surface, PhoneStyle.RADIUS_PILL, 4f
                )
                contentDescription = option.label + "，" + option.scene +
                    if (active) "，当前主题" else ""
                setOnClickListener { switchThemeImpl(option) }
            }
            row.addView(chip, LinearLayout.LayoutParams(0, chipHeight, 1f).apply {
                if (index > 0) leftMargin = settingsDpImpl(6)
            })
        }
        grid.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }
    targetHostImpl().addView(grid, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = settingsDpImpl(4) })
}

/**
 * 换主题：落盘 → 换色（顺带丢掉柔光位图缓存）→ 重建这一页。
 *
 * 与 TV 的 switchTheme 同一套动作。整页重建不能省：已建好的 View 上挂着旧材质的
 * Drawable，只改静态色值不会作用到它们。
 */
private fun PhoneSettingsActivity.switchThemeImpl(option: TvTheme) {
    if (option == PhoneStyle.theme) return
    TvThemeStore.save(this, option)
    PhoneStyle.applyTheme(option)
    window.setBackgroundDrawable(ColorDrawable(PhoneStyle.background))
    recreate()
}

internal fun PhoneSettingsActivity.renderRecommend() {
    sectionImpl("推荐设置")
    rowImpl("受众", genderLabelImpl(settingsLibrary.recommendGender)) {
        pickImpl("推荐受众", listOf("不限", "男频", "女频"),
            genderIndexImpl(settingsLibrary.recommendGender)) { index ->
            settingsLibrary.recommendGender = listOf("", "male", "female")[index]
            renderSettings()
        }
    }

    sectionImpl("内容管理与来源")
    rowImpl("选集每页", "${settingsLibrary.episodePageSize} 集") {
        val options = EpisodePageSize.phoneOptions
        pickImpl("选集每页（每行 5 个）", options.map { "$it 集" },
            options.indexOf(settingsLibrary.episodePageSize).coerceAtLeast(0)) { index ->
            settingsLibrary.episodePageSize = options[index]
            renderSettings()
        }
    }
    // 「分页边缘翻页」不再放在这里：手机上它读起来像是「推荐页能不能左右翻」，
    // 实际管的是选集网格的边缘翻页。这一项已挪到「外观导航」，并正名为
    // 「推荐页左右滑动」（见 renderAppearance）；选集边缘翻页在手机上保持开启。
    rowImpl("搜索源", settingsLibrary.searchSource.label) {
        val options = SearchSource.entries
        pickImpl("搜索源", options.map { "${it.label}　${it.hint}" },
            options.indexOf(settingsLibrary.searchSource)) { index ->
            settingsLibrary.searchSource = options[index]
            renderSettings()
        }
    }
    actionImpl("恢复隐藏的剧集", "${settingsLibrary.hidden().size} 部") {
        val count = settingsLibrary.hidden().size
        if (count == 0) { toastImpl("没有被隐藏的剧集"); return@actionImpl }
        confirmImpl("恢复隐藏的剧集", "把首页隐藏过的 $count 部剧全部放回列表？", false) {
            settingsLibrary.unhideAll()
            toastImpl("已恢复 $count 部")
            renderSettings()
        }
    }
}

internal fun PhoneSettingsActivity.renderSystem() {
    sectionImpl("设备与性能")
    actionImpl("关于", "版本与项目信息") { showAboutDialog() }
    actionImpl(
        "检查更新",
        if(settingsUpdateState.hasUpdate) "发现新版本" else "手动检查",
        badge=settingsUpdateState.hasUpdate,
    ) { showUpdateDialog() }
    val deviceLabel = DeviceForm.ofStored(settingsLibrary.deviceForm)?.label ?: "未选择"
    rowImpl("设备类型", deviceLabel) {
        openDeviceFormSelector()
    }
    hintImpl("用于选择手机、平板或电视界面，切换后自动重新进入。")
    switchImpl("减少动画优化性能", settingsLibrary.reduceAnim) {
        settingsLibrary.reduceAnim = it
        renderSettings()
    }
    switchImpl("调试模式", settingsLibrary.debugMode) {
        settingsLibrary.debugMode = it
        renderSettings()
    }

    sectionImpl("数据维护")
    actionImpl("导出备份", "存成一份 JSON 文件") { exportBackup() }
    actionImpl("同步其他设备数据", "通过局域网 NSD 查找设备") { showLanLibrarySync() }
    actionImpl("从备份恢复", "合并收藏、记录与设置") { importBackup() }

    sectionImpl("危险操作")
    actionImpl("清空应用缓存", "恢复成刚安装的样子") {
        confirmImpl("清空应用缓存",
            "用于反复无法登录、账号状态异常、页面数据错乱等情况：清空后应用会重新注册设备、重新生成标识。\n\n" +
                "注意：本机的收藏、观看记录、搜索历史与设置也会一并清空，且无法找回。" +
                "如需保留，请先「导出备份」。\n\n清空完成后应用会自动重启。",
            true) { performReset() }
    }
    actionImpl("恢复默认设置", "只重置偏好，收藏与记录不动") {
        confirmImpl(
            "恢复默认设置",
            "把播放、弹幕、推荐、外观相关的偏好恢复成出厂值，收藏与历史不受影响。",
            false,
        ) {
            restoreDefaults()
        }
    }
}
