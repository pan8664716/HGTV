// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data

import com.hgtv.star.core.BACK_TO_TOP_DEFAULT
import com.hgtv.star.core.BACK_TO_TOP_MODES
import com.hgtv.star.core.EpisodePageSize
import com.hgtv.star.core.PLAYER_PANEL_TIMEOUT_RANGE
import com.hgtv.star.core.PlaybackSpeed
import com.hgtv.star.core.SearchKeyboard
import com.hgtv.star.core.SearchSource
import com.hgtv.star.core.VideoDecoderMode
import com.hgtv.star.core.VideoFrameMode

internal fun Library.autoNextSetting(): Boolean = settingsState().settings.autoNext

internal fun Library.setAutoNextSetting(value: Boolean) {
    if (value != autoNext) {
        settingsState().settings = settingsState().settings.copy(autoNext = value)
        putSetting("autoNext", value)
    }
}

internal fun Library.playbackSpeedSetting(): Float = settingsState().settings.speed

internal fun Library.setPlaybackSpeedSetting(value: Float) {
    val next = PlaybackSpeed.normalize(value)
    if (next != playbackSpeed) {
        settingsState().settings = settingsState().settings.copy(speed = next)
        putSetting("playbackSpeed", next)
    }
}

internal fun Library.frameModeSetting(): VideoFrameMode = settingsState().settings.frame

internal fun Library.setFrameModeSetting(value: VideoFrameMode) {
    if (value != frameMode) {
        settingsState().settings = settingsState().settings.copy(frame = value)
        putSetting("frameMode", value.name)
    }
}

/**
 * 视频解码方式：硬件解码（默认）/ 软件解码。
 *
 * 只在**新建播放器**时读取；改档之后由 `applyDecoderMode` 立刻重建一次播放器，
 * 因此正在看的这一集也会马上换过去，不需要退出播放界面。
 */
internal fun Library.decoderModeSetting(): VideoDecoderMode = settingsState().settings.decoder

internal fun Library.setDecoderModeSetting(value: VideoDecoderMode) {
    if (value != decoderMode) {
        settingsState().settings = settingsState().settings.copy(decoder = value)
        putSetting("decoderMode", value.name)
    }
}

/**
 * 搜索页的数据源（App 接口 / 官网）。
 *
 * 只影响搜索（结果、联想词、热搜词），改完下一次搜索立刻生效；
 * 已经显示着的那一页结果不会被就地替换——用户按「搜索」才换。
 */
internal fun Library.searchSourceSetting(): SearchSource = settingsState().settings.searchSource

internal fun Library.setSearchSourceSetting(value: SearchSource) {
    if (value != searchSource) {
        settingsState().settings = settingsState().settings.copy(searchSource = value)
        putSetting("searchSource", value.storedValue)
    }
}

/** 电视搜索页的键盘选择，默认自绘键盘。 */
internal fun Library.searchKeyboardSetting(): SearchKeyboard = settingsState().settings.searchKeyboard

internal fun Library.setSearchKeyboardSetting(value: SearchKeyboard) {
    if (value != searchKeyboard) {
        settingsState().settings = settingsState().settings.copy(searchKeyboard = value)
        putSetting("searchKeyboard", value.storedValue)
    }
}

/**
 * 每一行放几张海报卡：4 / 5 / 6 / 7 / 8（默认 6，与旧版一致）。
 *
 * **全局生效**：首页剧架、榜单 / 分类 / 搜索 / 收藏 / 历史 / 我的、详情页相关剧集
 * 共用这一份设置；越界值一律夹回 4..6。
 */
internal fun Library.homeColumnsSetting(): Int = settingsState().settings.homeColumns.coerceIn(4, 8)

internal fun Library.setHomeColumnsSetting(value: Int) {
    val next = value.coerceIn(4, 8)
    if (next != homeColumns) {
        settingsState().settings = settingsState().settings.copy(homeColumns = next)
        putSetting("homeColumns", next)
    }
}

/**
 * 手机 / 平板的海报列表基准列数（1–3，默认 2）。
 *
 * 与电视的 [homeColumns]（4..8）是两个键：手机上 2 列是「封面够大、标题还能读全」
 * 的默认档；横屏实际显示时映射为 4–6 列。
 * 坏值夹进 1..3，设置页不会出现一个选不中的状态。
 */
internal fun Library.gridColumnsSetting(): Int = settingsState().settings.gridColumns.coerceIn(1, 3)

internal fun Library.setGridColumnsSetting(value: Int) {
    val next = value.coerceIn(1, 3)
    val current = settingsState().settings.gridColumns
    if (next != current) {
        settingsState().settings = settingsState().settings.copy(gridColumns = next)
        putSetting("gridColumns", next)
    }
}

/**
 * 列表页「回到顶部」浮层的显隐方式（[BACK_TO_TOP_MODES] 四档，默认始终显示）。
 *
 * 只管**列表页**那枚浮层：首页剧架、详情页、播放页本来就不挂它（见 `updateBackToTop`）。
 * 坏值一律回落到「始终显示」，设置页因此不会出现一个选不中任何一项的状态。
 */
internal fun Library.backToTopSetting(): String =
    settingsState().settings.backToTop.takeIf { it in BACK_TO_TOP_MODES } ?: BACK_TO_TOP_DEFAULT

internal fun Library.setBackToTopSetting(value: String) {
    val next = value.takeIf { it in BACK_TO_TOP_MODES } ?: BACK_TO_TOP_DEFAULT
    if (next != backToTop) {
        settingsState().settings = settingsState().settings.copy(backToTop = next)
        putSetting("backToTop", next)
    }
}

/**
 * 播放画面左上角常显的四项信息：剧集标题 / 当前集数 / 时间 / 网速。
 *
 * 四项各自独立开关（设置页「界面设置 → 播放显示」）。前两项与时间默认开启，
 * 网速默认关闭——它是排障用的读数，平时不该占着画面。
 */
internal fun Library.playerTitleSetting(): Boolean = settingsState().settings.playerTitle

internal fun Library.setPlayerTitleSetting(value: Boolean) {
    if (value != playerTitle) {
        settingsState().settings = settingsState().settings.copy(playerTitle = value)
        putSetting("playerTitle", value)
    }
}

internal fun Library.playerEpisodeSetting(): Boolean = settingsState().settings.playerEpisode

internal fun Library.setPlayerEpisodeSetting(value: Boolean) {
    if (value != playerEpisode) {
        settingsState().settings = settingsState().settings.copy(playerEpisode = value)
        putSetting("playerEpisode", value)
    }
}

internal fun Library.playerClockSetting(): Boolean = settingsState().settings.playerClock

internal fun Library.setPlayerClockSetting(value: Boolean) {
    if (value != playerClock) {
        settingsState().settings = settingsState().settings.copy(playerClock = value)
        putSetting("playerClock", value)
    }
}

internal fun Library.playerSpeedSetting(): Boolean = settingsState().settings.playerSpeed

internal fun Library.setPlayerSpeedSetting(value: Boolean) {
    if (value != playerSpeed) {
        settingsState().settings = settingsState().settings.copy(playerSpeed = value)
        putSetting("playerSpeed", value)
    }
}

/**
 * 播放设置 / 选集面板「无操作几秒自动消失」（[PLAYER_PANEL_TIMEOUT_RANGE]，默认 6 秒）。
 *
 * 到点之后的行为与按返回键一致：播放设置收起、选集面板收起并把焦点还回控制条。
 */
internal fun Library.playerPanelTimeoutSetting(): Int = settingsState().settings.playerPanelTimeout

internal fun Library.setPlayerPanelTimeoutSetting(value: Int) {
    val next = value.coerceIn(PLAYER_PANEL_TIMEOUT_RANGE)
    if (next != playerPanelTimeout) {
        settingsState().settings = settingsState().settings.copy(playerPanelTimeout = next)
        putSetting("playerPanelTimeout", next)
    }
}

/**
 * 快进 / 快退单次跳过的秒数（2..20，默认 10）。
 *
 * 播放界面按左右键时的跳跃步长（见 `com.hgtv.star.core.KeyMap.SEEK_FORWARD`）。
 * 长按连按的加速（5 次之后每次 30 秒）仍然按固定档走，这一项只决定**单次**的步长，
 * 因此调到 20 秒时连按依然是先 20 秒、之后按连按规则加快。
 */
internal fun Library.seekStepSetting(): Int = settingsState().settings.seekStep

internal fun Library.setSeekStepSetting(value: Int) {
    val next = value.coerceIn(2, 20)
    if (next != seekStep) {
        settingsState().settings = settingsState().settings.copy(seekStep = next)
        putSetting("seekStep", next)
    }
}

/**
 * 画面缩放百分比（1..100，默认 100 = 铺满）。
 *
 * 只缩播放画面本身（`playerView` 的框），弹幕层仍然是整屏坐标，
 * 因此缩小画面时弹幕照旧铺满屏幕（见 `applyVideoScale`）。
 */
internal fun Library.videoScaleSetting(): Float = settingsState().settings.videoScale.coerceIn(1f, 100f)

internal fun Library.setVideoScaleSetting(value: Float) {
    val next = value.coerceIn(1f, 100f).let { Math.round(it).toFloat() }
    if (next != videoScale) {
        settingsState().settings = settingsState().settings.copy(videoScale = next)
        putSetting("videoScale", next)
    }
}

/**
 * 竖屏短剧全屏（默认关闭）。
 *
 * 打开后，**竖屏片源**（短剧）在横屏电视上会被**转 90°**：9:16 旋转后就是 16:9，
 * 正好铺满屏幕、不裁掉任何内容（见 `autoFullscreenRotated`）。横屏片源不受影响，
 * 仍然用用户在「画面模式」里选的那一档。
 */
internal fun Library.autoFullscreenSetting(): Boolean = settingsState().settings.autoFullscreen

internal fun Library.setAutoFullscreenSetting(value: Boolean) {
    if (value != autoFullscreen) {
        settingsState().settings = settingsState().settings.copy(autoFullscreen = value)
        putSetting("autoFullscreen", value)
    }
}

/**
 * 选集一页放多少集（10 / 20 / 30 / 40 / 50，默认 20）。
 *
 * 简介页与播放界面的选集面板共用这一份：它决定分页行怎么切段
 *（`1-20 / 21-40 …`）、网格铺几行（固定 10 列 × 一页集数 / 10 行）。
 * 落盘即生效——下次打开选集就是新的分段，不需要重启。
 */
internal fun Library.episodePageSizeSetting(): Int = settingsState().settings.episodePageSize

internal fun Library.setEpisodePageSizeSetting(value: Int) {
    val next = EpisodePageSize.normalize(value)
    if (next != episodePageSize) {
        settingsState().settings = settingsState().settings.copy(episodePageSize = next)
        putSetting("episodePageSize", next)
    }
}

/**
 * 分页边缘翻页（默认开启）。
 *
 * 开启：选集网格左右到本行两端、下到最后一行时**翻页**（跳到相邻那一页）。
 * 关闭：边缘不再翻页，右端按右键选下一集、左端按左键选上一集（跨页自动跟过去），
 * 换页改由分页行自己选（见 `EpisodeGrid.pageKey`）。
 *
 * 落盘即生效：下一次进简介页 / 打开选集面板就按新值走，已经摆好的那一屏不重建。
 */
internal fun Library.episodeEdgeTurnSetting(): Boolean = settingsState().settings.episodeEdgeTurn

internal fun Library.setEpisodeEdgeTurnSetting(value: Boolean) {
    if (value != episodeEdgeTurn) {
        settingsState().settings = settingsState().settings.copy(episodeEdgeTurn = value)
        putSetting("episodeEdgeTurn", value)
    }
}

/**
 * 用户主动要求「减少动画、优先流畅」（默认关闭）。
 *
 * 与设备判据是**或**的关系：低配机一律按低配档跑，与这一项无关；
 * 非低配机打开它之后，焦点缩放动画、柔光位图、海报井暗角与跑马灯全部关掉，
 * 效果与低配机完全一致（见 `TvMainActivity.applyPerformanceProfile`）。
 *
 * 改完要整页重建一次才彻底生效：卡片在构造时就把「是否跑马灯」捉了一份
 * （见 `PosterCard.marqueeOn`）。
 */
internal fun Library.reduceAnimSetting(): Boolean = settingsState().settings.reduceAnim

internal fun Library.setReduceAnimSetting(value: Boolean) {
    if (value != reduceAnim) {
        settingsState().settings = settingsState().settings.copy(reduceAnim = value)
        putSetting("reduceAnim", value)
    }
}
