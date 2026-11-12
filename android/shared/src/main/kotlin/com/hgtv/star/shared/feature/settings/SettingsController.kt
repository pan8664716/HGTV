// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.feature.settings

import android.content.Context
import com.hgtv.star.core.BackupData
import com.hgtv.star.core.DanmakuMode
import com.hgtv.star.core.DanmakuSpeed
import com.hgtv.star.core.DanmakuStyle
import com.hgtv.star.core.LibraryBackup
import com.hgtv.star.core.PlaybackQuality
import com.hgtv.star.core.PlaybackSpeed
import com.hgtv.star.core.SearchSource
import com.hgtv.star.core.SearchKeyboard
import com.hgtv.star.core.VideoDecoderMode
import com.hgtv.star.core.VideoFrameMode
import com.hgtv.star.shared.data.Library
import com.hgtv.star.shared.ui.TvTheme
import com.hgtv.star.shared.ui.TvThemeStore

/**
 * 设置页的共享读写入口。
 *
 * 页面仍决定“显示哪些行”，但读取当前值、写回落盘值、恢复默认和备份恢复都从这里走。
 */
class SettingsController(
    private val context: Context,
    private val library: Library,
) {
    fun playbackRows(phone: Boolean): List<SettingRowSpec> {
        val speeds = if (phone) PlaybackSpeed.phoneOptions else PlaybackSpeed.options
        val frames = VideoFrameMode.entries
        val decoders = VideoDecoderMode.entries
        val modes = DanmakuMode.entries
        val danmakuSpeeds = DanmakuSpeed.entries
        return buildList {
            add(SettingRowSpec.Section("播放"))
            add(
                SettingRowSpec.Choice(
                    id = ID_QUALITY,
                    title = "默认清晰度",
                    value = "${library.maxQuality}P",
                    options = PlaybackQuality.options.map { "${it}P" },
                )
            )
            add(
                SettingRowSpec.Choice(
                    id = ID_SPEED,
                    title = "默认倍速",
                    value = PlaybackSpeed.label(library.playbackSpeed),
                    options = speeds.map(PlaybackSpeed::label),
                )
            )
            add(
                SettingRowSpec.Choice(
                    id = ID_FRAME,
                    title = "画面模式",
                    value = library.frameMode.label,
                    options = frames.map { it.label },
                )
            )
            add(
                SettingRowSpec.Choice(
                    id = ID_DECODER,
                    title = "视频解码",
                    value = library.decoderMode.label,
                    options = decoders.map { it.label },
                )
            )
            add(
                SettingRowSpec.Slider(
                    id = ID_VIDEO_SCALE,
                    title = "画面缩放",
                    value = library.videoScale,
                    min = 1f,
                    max = 100f,
                    step = 1f,
                    valueLabel = "${library.videoScale.toInt()}%",
                )
            )
            add(SettingRowSpec.Section("弹幕"))
            add(
                SettingRowSpec.Choice(
                    id = ID_DANMAKU_MODE,
                    title = "显示方式",
                    value = library.danmakuMode.label,
                    options = modes.map { it.label },
                )
            )
            add(
                SettingRowSpec.Choice(
                    id = ID_DANMAKU_SPEED,
                    title = "弹幕速度",
                    value = library.danmakuSpeed.label,
                    options = danmakuSpeeds.map { it.label },
                )
            )
            add(SettingRowSpec.Switch(ID_DANMAKU_MERGE, "相同弹幕聚合", library.danmakuMerge))
            add(SettingRowSpec.Switch(ID_DANMAKU_EFFECTS, "开启弹幕特效", library.danmakuEffects))
            add(
                SettingRowSpec.Slider(
                    id = ID_DANMAKU_MERGE_MIN,
                    title = "聚合阈值",
                    value = library.danmakuMergeMin.toFloat(),
                    min = com.hgtv.star.core.DANMAKU_MERGE_MIN_RANGE.first.toFloat(),
                    max = com.hgtv.star.core.DANMAKU_MERGE_MIN_RANGE.last.toFloat(),
                    step = 1f,
                    valueLabel = "${library.danmakuMergeMin} 条",
                )
            )
            add(
                SettingRowSpec.Slider(
                    id = ID_DANMAKU_SCALE,
                    title = "弹幕大小",
                    value = library.danmakuScale,
                    min = DanmakuStyle.SCALE_MIN,
                    max = DanmakuStyle.SCALE_MAX,
                    step = DanmakuStyle.SCALE_STEP,
                    valueLabel = "${(library.danmakuScale * 100).toInt()}%",
                )
            )
            add(
                SettingRowSpec.Slider(
                    id = ID_DANMAKU_ALPHA,
                    title = "弹幕透明度",
                    value = library.danmakuAlpha,
                    min = DanmakuStyle.ALPHA_MIN,
                    max = DanmakuStyle.ALPHA_MAX,
                    step = DanmakuStyle.ALPHA_STEP,
                    valueLabel = DanmakuStyle.percent(library.danmakuAlpha),
                )
            )
        }
    }

    fun appearanceRows(phone: Boolean): List<SettingRowSpec> = buildList {
        add(SettingRowSpec.Section("外观与布局"))
        add(SettingRowSpec.ThemeGrid(selected = TvThemeStore.load(context).storedValue))
        add(
            SettingRowSpec.Slider(
                id = ID_GRID_COLUMNS,
                title = "每行卡片",
                value = library.homeColumns.toFloat(),
                min = 4f,
                max = 8f,
                step = 1f,
                valueLabel = "${library.homeColumns} 张",
            )
        )
        if (phone) {
            add(
                SettingRowSpec.Switch(
                    id = ID_RECOMMEND_SWIPE,
                    title = "推荐页左右滑动",
                    checked = library.recommendSwipe,
                )
            )
        }
    }

    fun recommendRows(): List<SettingRowSpec> = buildList {
        add(SettingRowSpec.Section("内容推荐"))
        add(
            SettingRowSpec.Choice(
                id = ID_RECOMMEND_GENDER,
                title = "推荐受众",
                value = when (library.recommendGender) {
                    "male" -> "男频"
                    "female" -> "女频"
                    else -> "不限"
                },
                options = listOf("不限", "男频", "女频"),
            )
        )
        add(
            SettingRowSpec.Choice(
                id = ID_SEARCH_SOURCE,
                title = "搜索源",
                value = library.searchSource.label,
                options = SearchSource.entries.map { it.label },
            )
        )
    }

    fun systemRows(phone: Boolean): List<SettingRowSpec> = buildList {
        add(SettingRowSpec.Section("系统高级"))
        add(SettingRowSpec.Action(ID_DEVICE_FORM, "使用设备", library.deviceForm))
        add(SettingRowSpec.Action(ID_LAN_SYNC, "同步其他设备数据", "局域网 NSD 查找设备"))
        add(SettingRowSpec.Action(ID_EXPORT_BACKUP, "导出本机记录", "生成备份文件"))
        add(SettingRowSpec.Action(ID_IMPORT_BACKUP, "导入本机记录", "合并恢复"))
        add(SettingRowSpec.Action(ID_RESTORE_DEFAULTS, "恢复默认设置", ""))
        add(SettingRowSpec.Action(ID_RESET_APP, "清空应用缓存", "重启后生效"))
        add(SettingRowSpec.Switch(ID_DEBUG_MODE, "调试模式", library.debugMode))
        if (!phone) add(SettingRowSpec.Switch(ID_REDUCE_ANIM, "减少动画优化性能", library.reduceAnim))
    }

    fun setQuality(value: Int) { library.maxQuality = PlaybackQuality.normalize(value) }
    fun setSpeed(value: Float) { library.playbackSpeed = value }
    fun setFrame(value: VideoFrameMode) { library.frameMode = value }
    fun setDecoder(value: VideoDecoderMode) { library.decoderMode = value }
    fun setVideoScale(value: Float) { library.videoScale = value.coerceIn(1f, 100f) }
    fun setDanmakuMode(value: DanmakuMode) { library.danmakuMode = value }
    fun setDanmakuSpeed(value: DanmakuSpeed) { library.danmakuSpeed = value }
    fun setDanmakuMerge(value: Boolean) { library.danmakuMerge = value }
    fun setDanmakuEffects(value: Boolean) { library.danmakuEffects = value }
    fun setDanmakuMergeMin(value: Int) { library.danmakuMergeMin = value }
    fun setDanmakuScale(value: Float) { library.danmakuScale = value }
    fun setDanmakuAlpha(value: Float) { library.danmakuAlpha = value }
    fun setGridColumns(value: Int) { library.homeColumns = value.coerceIn(4, 8) }
    fun setRecommendSwipe(value: Boolean) { library.recommendSwipe = value }
    fun setRecommendGender(value: String) { library.recommendGender = value }
    fun setSearchSource(value: SearchSource) { library.searchSource = value }
    fun setDebugMode(value: Boolean) { library.debugMode = value }
    fun setReduceAnim(value: Boolean) { library.reduceAnim = value }
    fun setDeviceForm(value: String) { library.deviceForm = value }
    fun setTheme(value: TvTheme) { TvThemeStore.save(context, value) }

    fun exportBackup(): String = LibraryBackup.encode(library.snapshot())
    fun importBackup(text: String): BackupData {
        val backup = LibraryBackup.decode(text)
        library.restore(backup, true)
        return backup
    }

    fun restoreDefaults() {
        library.maxQuality = 1080
        library.playbackSpeed = 1f
        library.frameMode = VideoFrameMode.FIT
        library.decoderMode = VideoDecoderMode.HARDWARE
        library.videoScale = 100f
        library.danmakuMode = DanmakuMode.DEFAULT
        library.danmakuSpeed = DanmakuSpeed.NORMAL
        library.danmakuMerge = true
        library.danmakuMergeMin = 2
        library.danmakuScale = 1f
        library.danmakuAlpha = 1f
        library.homeColumns = 6
        library.recommendSwipe = true
        library.recommendGender = ""
        library.searchSource = SearchSource.APP
        library.searchKeyboard = SearchKeyboard.SYSTEM
        library.debugMode = false
        library.reduceAnim = false
        TvThemeStore.save(context, TvTheme.DEEP_SEA_BLUE)
    }

    companion object {
        const val ID_QUALITY = "playback.quality"
        const val ID_SPEED = "playback.speed"
        const val ID_FRAME = "playback.frame"
        const val ID_DECODER = "playback.decoder"
        const val ID_VIDEO_SCALE = "playback.scale"
        const val ID_DANMAKU_MODE = "danmaku.mode"
        const val ID_DANMAKU_SPEED = "danmaku.speed"
        const val ID_DANMAKU_MERGE = "danmaku.merge"
        const val ID_DANMAKU_EFFECTS = "danmaku.effects"
        const val ID_DANMAKU_MERGE_MIN = "danmaku.merge.min"
        const val ID_DANMAKU_SCALE = "danmaku.scale"
        const val ID_DANMAKU_ALPHA = "danmaku.alpha"
        const val ID_GRID_COLUMNS = "layout.columns"
        const val ID_RECOMMEND_SWIPE = "recommend.swipe"
        const val ID_RECOMMEND_GENDER = "recommend.gender"
        const val ID_SEARCH_SOURCE = "search.source"
        const val ID_DEVICE_FORM = "system.deviceForm"
        const val ID_LAN_SYNC = "system.lanSync"
        const val ID_EXPORT_BACKUP = "system.export"
        const val ID_IMPORT_BACKUP = "system.import"
        const val ID_RESTORE_DEFAULTS = "system.restoreDefaults"
        const val ID_RESET_APP = "system.reset"
        const val ID_DEBUG_MODE = "system.debug"
        const val ID_REDUCE_ANIM = "system.reduceAnim"
    }
}
