// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.hgtv.star.shared.ui.UiText
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
import com.hgtv.star.phone.ui.PhoneStyle
import com.hgtv.star.phone.ui.PhoneUiScale
import com.hgtv.star.shared.compat.DeviceForm
import com.hgtv.star.shared.feature.settings.SettingRowSpec
import com.hgtv.star.shared.feature.settings.SettingsController
import kotlin.math.roundToInt

internal fun PhoneSettingsActivity.renderSpecs(specs: List<SettingRowSpec>) {
    specs.forEach { spec ->
        when (spec) {
            is SettingRowSpec.Section -> sectionImpl(spec.title)
            is SettingRowSpec.ThemeGrid -> themeGrid()
            is SettingRowSpec.Choice -> rowImpl(spec.title, spec.value) {
                pickImpl(
                    spec.title,
                    spec.options,
                    spec.options.indexOf(spec.value).coerceAtLeast(0),
                ) { index ->
                    applyChoiceImpl(spec.id, index)
                    renderSettings()
                }
            }
            is SettingRowSpec.Switch -> switchImpl(spec.title, spec.checked) { checked ->
                applySwitchImpl(spec.id, checked)
                renderSettings()
            }
            is SettingRowSpec.Slider -> sliderImpl(
                spec.title, spec.min, spec.max, spec.step, spec.value,
                { valueLabelForImpl(spec.id, it) },
            ) { value ->
                applySliderImpl(spec.id, value)
            }
            is SettingRowSpec.Action -> actionImpl(spec.title, spec.subtitle) {
                applyActionImpl(spec.id)
            }
        }
    }
}

internal fun PhoneSettingsActivity.renderUnifiedPlayback() {
    renderSpecs(
        listOf(
            SettingRowSpec.Section("播放偏好"),
            SettingRowSpec.Choice(
                SettingsController.ID_QUALITY,
                "默认清晰度",
                "${settingsLibrary.maxQuality}P",
                PlaybackQuality.options.map { "${it}P" },
            ),
            SettingRowSpec.Slider(
                SettingsController.ID_SPEED,
                "默认倍速",
                settingsLibrary.playbackSpeed,
                PlaybackSpeed.MIN,
                PlaybackSpeed.MAX,
                PlaybackSpeed.STEP,
                PlaybackSpeed.label(settingsLibrary.playbackSpeed),
            ),
            SettingRowSpec.Choice(
                SettingsController.ID_FRAME,
                "画面模式",
                settingsLibrary.frameMode.label,
                VideoFrameMode.entries.map { it.label },
            ),
            SettingRowSpec.Choice(
                SettingsController.ID_DECODER,
                "视频解码",
                settingsLibrary.decoderMode.label,
                VideoDecoderMode.entries.map { it.label },
            ),
            SettingRowSpec.Slider(
                SettingsController.ID_VIDEO_SCALE,
                "画面缩放",
                settingsLibrary.videoScale,
                1f,
                100f,
                1f,
                "${settingsLibrary.videoScale.roundToInt()}%",
            ),
            SettingRowSpec.Slider(
                "playback.seekStep",
                "快进快退",
                settingsLibrary.seekStep.toFloat(),
                2f,
                20f,
                1f,
                "${settingsLibrary.seekStep} 秒",
            ),
            SettingRowSpec.Section("播放显示"),
            SettingRowSpec.Switch("play.title", "剧名", settingsLibrary.playerTitle),
            SettingRowSpec.Switch("play.episode", "剧集", settingsLibrary.playerEpisode),
            SettingRowSpec.Switch("play.speed", "网速", settingsLibrary.playerSpeed),
            SettingRowSpec.Switch("play.clock", "时间", settingsLibrary.playerClock),
            SettingRowSpec.Slider(
                "play.panelTimeout",
                "自动收起时间",
                settingsLibrary.playerPanelTimeout.toFloat(),
                PLAYER_PANEL_TIMEOUT_RANGE.first.toFloat(),
                PLAYER_PANEL_TIMEOUT_RANGE.last.toFloat(),
                1f,
                "${settingsLibrary.playerPanelTimeout} 秒",
            ),
            SettingRowSpec.Section("弹幕设置"),
            SettingRowSpec.Choice(
                SettingsController.ID_DANMAKU_MODE,
                "显示方式",
                settingsLibrary.danmakuMode.optionLabel,
                DanmakuMode.entries.map { it.optionLabel },
            ),
            SettingRowSpec.Choice(
                SettingsController.ID_DANMAKU_SPEED,
                "弹幕速度",
                settingsLibrary.danmakuSpeed.label,
                DanmakuSpeed.entries.map { it.label },
            ),
            SettingRowSpec.Switch(
                SettingsController.ID_DANMAKU_MERGE,
                "相同弹幕聚合",
                settingsLibrary.danmakuMerge,
            ),
            SettingRowSpec.Switch(
                SettingsController.ID_DANMAKU_EFFECTS,
                "开启弹幕特效",
                settingsLibrary.danmakuEffects,
            ),
            SettingRowSpec.Slider(
                SettingsController.ID_DANMAKU_MERGE_MIN,
                "聚合条数",
                settingsLibrary.danmakuMergeMin.toFloat(),
                DANMAKU_MERGE_MIN_RANGE.first.toFloat(),
                DANMAKU_MERGE_MIN_RANGE.last.toFloat(),
                1f,
                "${settingsLibrary.danmakuMergeMin} 条",
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
                settingsLibrary.danmakuAlpha,
                DanmakuStyle.ALPHA_MIN,
                DanmakuStyle.ALPHA_MAX,
                DanmakuStyle.ALPHA_STEP,
                DanmakuStyle.percent(settingsLibrary.danmakuAlpha),
            ),
        )
    )
}

internal fun PhoneSettingsActivity.renderUnifiedAppearance() {
    val scale = PhoneUiScale.sizeOf(PhoneUiScale.value)
    renderSpecs(
        listOf(
            SettingRowSpec.Section("外观与布局"),
            SettingRowSpec.Choice(
                SettingsController.ID_LAUNCH_TAB,
                "打开应用进入",
                launchTabLabel(settingsLibrary.phoneLaunchTab),
                com.hgtv.star.core.AppConfig.Navigation.PHONE_TAB_KEYS.map(::launchTabLabel),
            ),
            SettingRowSpec.Choice(
                "appearance.uiScale",
                "UI大小",
                scale.label,
                PhoneUiScale.sizes.map { it.label },
            ),
            SettingRowSpec.Choice(
                "appearance.columns",
                "列表每行",
                "${settingsLibrary.gridColumns + 3} 张",
                listOf(UiText.get(com.hgtv.star.shared.R.string.ui_0337), UiText.get(com.hgtv.star.shared.R.string.ui_0338), UiText.get(com.hgtv.star.shared.R.string.ui_0339)),
            ),
            SettingRowSpec.Switch(
                SettingsController.ID_RECOMMEND_SWIPE,
                "页面左右滑动",
                settingsLibrary.recommendSwipe,
            ),
            SettingRowSpec.Section("主题外观"),
            SettingRowSpec.ThemeGrid(selected = PhoneStyle.theme.storedValue),
        )
    )
}

internal fun PhoneSettingsActivity.renderUnifiedRecommend() {
    renderSpecs(
        listOf(
            SettingRowSpec.Section("推荐设置"),
            SettingRowSpec.Choice(
                SettingsController.ID_RECOMMEND_GENDER,
                "受众",
                genderLabelImpl(settingsLibrary.recommendGender),
                listOf(UiText.get(com.hgtv.star.shared.R.string.ui_0296), UiText.get(com.hgtv.star.shared.R.string.ui_0333), UiText.get(com.hgtv.star.shared.R.string.ui_0306)),
            ),
            SettingRowSpec.Section("内容管理与来源"),
            SettingRowSpec.Action(
                "recommend.unhide",
                "恢复隐藏的剧集",
                "${settingsLibrary.hidden().size} 部",
            ),
            SettingRowSpec.Choice(
                SettingsController.ID_SEARCH_SOURCE,
                "搜索源",
                settingsLibrary.searchSource.label,
                SearchSource.entries.map { it.label },
            ),
            SettingRowSpec.Choice(
                "recommend.episodePage",
                "选集每页",
                "${settingsLibrary.episodePageSize} 集",
                EpisodePageSize.phoneOptions.map { "$it 集" },
            ),
        )
    )
}

internal fun PhoneSettingsActivity.renderUnifiedSystem() {
    val deviceLabel = DeviceForm.ofStored(settingsLibrary.deviceForm)?.label ?: "未选择"
    renderSpecs(
        listOf(
            SettingRowSpec.Section("数据维护"),
            SettingRowSpec.Action(SettingsController.ID_EXPORT_BACKUP, "导出备份", "存成 JSON 文件"),
            SettingRowSpec.Action(SettingsController.ID_IMPORT_BACKUP, "从备份恢复", "合并收藏、记录与设置"),
            SettingRowSpec.Action(SettingsController.ID_RESET_APP, "清空应用缓存", "恢复成刚安装的样子"),
            SettingRowSpec.Action(SettingsController.ID_RESTORE_DEFAULTS, "恢复默认设置", "只重置偏好"),
            SettingRowSpec.Action(SettingsController.ID_LAN_SYNC, "同步其他设备数据", "局域网 NSD 查找设备"),
            SettingRowSpec.Section("设备与性能"),
            SettingRowSpec.Switch(
                SettingsController.ID_REDUCE_ANIM,
                "减少动画优化性能",
                settingsLibrary.reduceAnim,
            ),
            SettingRowSpec.Switch(SettingsController.ID_DEBUG_MODE, "调试模式", settingsLibrary.debugMode),
            SettingRowSpec.Action("system.about", "关于", "版本 ${appVersionName()}"),
            SettingRowSpec.Action(SettingsController.ID_DEVICE_FORM, "设备类型", deviceLabel),
        )
    )
}

internal fun PhoneSettingsActivity.applyActionImpl(id: String) {
    when (id) {
        SettingsController.ID_EXPORT_BACKUP -> exportBackup()
        SettingsController.ID_IMPORT_BACKUP -> importBackup()
        SettingsController.ID_RESET_APP -> confirmImpl(
            "清空应用缓存",
            "本机的收藏、观看记录、搜索历史与设置也会一并清空，且无法找回。清空完成后应用会自动重启。",
            true,
        ) { performReset() }
        SettingsController.ID_RESTORE_DEFAULTS -> confirmImpl(
            "恢复默认设置",
            "把播放、弹幕、推荐、外观相关的偏好恢复成出厂值，收藏与历史不受影响。",
            false,
        ) {
            settingsControllerInstance.restoreDefaults()
            renderSettings(resetScroll = true)
        }
        SettingsController.ID_LAN_SYNC -> showLanLibrarySync()
        "system.about" -> showAboutDialog()
        SettingsController.ID_DEVICE_FORM -> openDeviceFormSelector()
        "recommend.unhide" -> {
            val count = settingsLibrary.hidden().size
            if (count == 0) {
                toastImpl("没有被隐藏的剧集")
            } else {
                confirmImpl(
                    "恢复隐藏的剧集",
                    "把首页隐藏过的 $count 部剧全部放回列表？",
                    false,
                ) {
                    settingsLibrary.unhideAll()
                    toastImpl("已恢复 $count 部")
                    renderSettings()
                }
            }
        }
    }
}

internal fun PhoneSettingsActivity.valueLabelForImpl(id: String, value: Float): String = when (id) {
    SettingsController.ID_SPEED -> PlaybackSpeed.label(value)
    SettingsController.ID_VIDEO_SCALE -> "${value.roundToInt()}%"
    SettingsController.ID_DANMAKU_MODE -> DanmakuMode.fromSlider(value).optionLabel
    SettingsController.ID_DANMAKU_MERGE_MIN -> "${value.roundToInt()} 条"
    SettingsController.ID_DANMAKU_ALPHA -> DanmakuStyle.percent(value)
    "playback.seekStep" -> "${value.roundToInt()} 秒"
    "play.panelTimeout" -> "${value.roundToInt()} 秒"
    "danmaku.phoneScale" -> PhoneDanmakuSize.label(value)
    else -> value.roundToInt().toString()
}
