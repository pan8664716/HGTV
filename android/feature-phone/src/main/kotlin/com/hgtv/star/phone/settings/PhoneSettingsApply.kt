// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import com.hgtv.star.core.DanmakuMode
import com.hgtv.star.core.DanmakuSpeed
import com.hgtv.star.core.DanmakuStyle
import com.hgtv.star.core.EpisodePageSize
import com.hgtv.star.core.PlaybackQuality
import com.hgtv.star.core.SearchSource
import com.hgtv.star.core.VideoDecoderMode
import com.hgtv.star.core.VideoFrameMode
import com.hgtv.star.phone.ui.PhoneUiScale
import com.hgtv.star.shared.feature.settings.SettingsController
import kotlin.math.roundToInt

internal fun launchTabLabel(key: String): String = when (key) {
    "category" -> "分类"
    "rank" -> "榜单"
    "mine" -> "我的"
    else -> "首页"
}

internal fun PhoneSettingsActivity.applyChoiceImpl(id: String, index: Int) {
    when (id) {
        SettingsController.ID_QUALITY ->
            settingsControllerInstance.setQuality(PlaybackQuality.options[index])
        SettingsController.ID_FRAME ->
            settingsControllerInstance.setFrame(VideoFrameMode.entries[index])
        SettingsController.ID_DECODER ->
            settingsControllerInstance.setDecoder(VideoDecoderMode.entries[index])
        SettingsController.ID_DANMAKU_MODE ->
            settingsControllerInstance.setDanmakuMode(DanmakuMode.entries[index])
        SettingsController.ID_DANMAKU_SPEED ->
            settingsControllerInstance.setDanmakuSpeed(DanmakuSpeed.entries[index])
        SettingsController.ID_RECOMMEND_GENDER ->
            settingsControllerInstance.setRecommendGender(listOf("", "male", "female")[index])
        SettingsController.ID_SEARCH_SOURCE ->
            settingsControllerInstance.setSearchSource(SearchSource.entries[index])
        "appearance.uiScale" -> {
            PhoneUiScale.set(this, PhoneUiScale.sizes[index].scale)
            recreate()
        }
        SettingsController.ID_LAUNCH_TAB ->
            settingsControllerInstance.setPhoneLaunchTab(
                com.hgtv.star.core.AppConfig.Navigation.PHONE_TAB_KEYS[index],
            )
        "appearance.columns" -> settingsLibrary.gridColumns = index + 1
        "recommend.episodePage" ->
            settingsLibrary.episodePageSize = EpisodePageSize.phoneOptions[index]
    }
}

internal fun PhoneSettingsActivity.applySwitchImpl(id: String, checked: Boolean) {
    when (id) {
        SettingsController.ID_DANMAKU_MERGE ->
            settingsControllerInstance.setDanmakuMerge(checked)
        SettingsController.ID_DANMAKU_EFFECTS ->
            settingsControllerInstance.setDanmakuEffects(checked)
        SettingsController.ID_RECOMMEND_SWIPE ->
            settingsControllerInstance.setRecommendSwipe(checked)
        SettingsController.ID_DEBUG_MODE ->
            settingsControllerInstance.setDebugMode(checked)
        SettingsController.ID_REDUCE_ANIM ->
            settingsControllerInstance.setReduceAnim(checked)
        "play.title" -> settingsLibrary.playerTitle = checked
        "play.episode" -> settingsLibrary.playerEpisode = checked
        "play.speed" -> settingsLibrary.playerSpeed = checked
        "play.clock" -> settingsLibrary.playerClock = checked
    }
}

internal fun PhoneSettingsActivity.applySliderImpl(id: String, value: Float) {
    when (id) {
        SettingsController.ID_SPEED ->
            settingsControllerInstance.setSpeed(value)
        SettingsController.ID_VIDEO_SCALE ->
            settingsControllerInstance.setVideoScale(value)
        SettingsController.ID_DANMAKU_MODE ->
            settingsControllerInstance.setDanmakuMode(DanmakuMode.fromSlider(value))
        SettingsController.ID_DANMAKU_MERGE_MIN ->
            settingsControllerInstance.setDanmakuMergeMin(value.roundToInt())
        SettingsController.ID_DANMAKU_ALPHA ->
            settingsControllerInstance.setDanmakuAlpha(DanmakuStyle.alpha(value))
        "playback.seekStep" -> settingsLibrary.seekStep = value.roundToInt()
        "play.panelTimeout" -> settingsLibrary.playerPanelTimeout = value.roundToInt()
        "danmaku.phoneScale" -> PhoneDanmakuSize.set(this, value)
    }
}
