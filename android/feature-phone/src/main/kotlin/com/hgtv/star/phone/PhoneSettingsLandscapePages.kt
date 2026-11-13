// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.hgtv.star.core.DANMAKU_MERGE_MIN_RANGE
import com.hgtv.star.core.DanmakuMode
import com.hgtv.star.core.DanmakuSpeed
import com.hgtv.star.core.DanmakuStyle
import com.hgtv.star.core.PLAYER_PANEL_TIMEOUT_RANGE
import com.hgtv.star.core.PlaybackQuality
import com.hgtv.star.core.PlaybackSpeed
import com.hgtv.star.core.VideoDecoderMode
import com.hgtv.star.core.VideoFrameMode
import com.hgtv.star.phone.ui.PhoneStyle
import com.hgtv.star.phone.ui.PhoneUiScale
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.phoneSize
import com.hgtv.star.shared.ui.TvTheme
import kotlin.math.roundToInt

internal fun PhoneSettingsActivity.renderLandscapePlayback() {
    val pref=landscapePanel("播放偏好","所有剧集共用，播放中也可调整")
    landscapeLine(pref).also { line ->
        landscapeCell(line,landscapeSetting("默认清晰度","${settingsLibrary.maxQuality}P") {
            val options=PlaybackQuality.options
            pickImpl("默认清晰度",options.map { "$it P" },options.indexOf(settingsLibrary.maxQuality).coerceAtLeast(0)) { i ->
                settingsLibrary.maxQuality=options[i]; renderSettings()
            }
        })
        landscapeCell(line,landscapeSetting("默认倍速",PlaybackSpeed.label(settingsLibrary.playbackSpeed)) {
            val options=PlaybackSpeed.phoneOptions
            pickImpl("默认倍速",options.map { PlaybackSpeed.label(it) },options.indexOf(settingsLibrary.playbackSpeed).coerceAtLeast(0)) { i ->
                settingsLibrary.playbackSpeed=options[i]; renderSettings()
            }
        })
    }
    landscapeLine(pref).also { line ->
        landscapeCell(line,landscapeSetting("画面模式",settingsLibrary.frameMode.label) {
            val options=VideoFrameMode.entries
            pickImpl("画面模式",options.map { it.label },options.indexOf(settingsLibrary.frameMode)) { i ->
                settingsLibrary.frameMode=options[i]; renderSettings()
            }
        })
        landscapeCell(line,landscapeSetting("视频解码",settingsLibrary.decoderMode.label) {
            val options=VideoDecoderMode.entries
            pickImpl("视频解码",options.map { it.label },options.indexOf(settingsLibrary.decoderMode)) { i ->
                settingsLibrary.decoderMode=options[i]; renderSettings()
            }
        })
    }
    landscapeLine(pref).also { line ->
        landscapeCell(line,landscapeSlider("画面缩放",1f,100f,1f,settingsLibrary.videoScale,
            { "${it.roundToInt()}%" }) { settingsLibrary.videoScale=it })
        landscapeCell(line,landscapeSwitch("自动连播",settingsLibrary.autoNext) {
            settingsLibrary.autoNext=it; renderSettings()
        })
    }
    landscapeLine(pref).also { line ->
        landscapeCell(line,landscapeSlider("快进快退",2f,20f,1f,settingsLibrary.seekStep.toFloat(),
            { "${it.roundToInt()} 秒" }) { settingsLibrary.seekStep=it.roundToInt() })
    }

    val display=landscapePanel("界面信息","播放信息显示与自动收起")
    landscapeLine(display).also { line ->
        landscapeCell(line,landscapeSwitch("剧名",settingsLibrary.playerTitle) {
            settingsLibrary.playerTitle=it; renderSettings()
        })
        landscapeCell(line,landscapeSwitch("剧集",settingsLibrary.playerEpisode) {
            settingsLibrary.playerEpisode=it; renderSettings()
        })
        landscapeCell(line,landscapeSwitch("网速",settingsLibrary.playerSpeed) {
            settingsLibrary.playerSpeed=it; renderSettings()
        })
        landscapeCell(line,landscapeSwitch("时间",settingsLibrary.playerClock) {
            settingsLibrary.playerClock=it; renderSettings()
        })
    }
    landscapeLine(display).also { line ->
        landscapeCell(line,landscapeSlider("自动收起时间",
            PLAYER_PANEL_TIMEOUT_RANGE.first.toFloat(),PLAYER_PANEL_TIMEOUT_RANGE.last.toFloat(),1f,
            settingsLibrary.playerPanelTimeout.toFloat(),{ "${it.roundToInt()} 秒" }) {
            settingsLibrary.playerPanelTimeout=it.roundToInt()
        })
    }

    val danmaku=landscapePanel("弹幕设置","显示方式 / 速度 / 聚合 / 特效 / 字号 / 透明度")
    val (left,right)=landscapeColumns(danmaku)
    landscapeColumnCell(left,landscapeSetting("显示方式",settingsLibrary.danmakuMode.label) {
        val options=DanmakuMode.entries
        pickImpl("显示方式",options.map { it.label },options.indexOf(settingsLibrary.danmakuMode)) { i ->
            settingsLibrary.danmakuMode=options[i]; renderSettings()
        }
    })
    landscapeColumnCell(left,landscapeSetting("弹幕速度",settingsLibrary.danmakuSpeed.label) {
        val options=DanmakuSpeed.entries
        pickImpl("弹幕速度",options.map { it.label },options.indexOf(settingsLibrary.danmakuSpeed)) { i ->
            settingsLibrary.danmakuSpeed=options[i]; renderSettings()
        }
    })
    landscapeColumnCell(left,landscapeSwitch("相同弹幕聚合",settingsLibrary.danmakuMerge) {
        settingsLibrary.danmakuMerge=it; renderSettings()
    })
    landscapeColumnCell(left,landscapeSwitch("开启弹幕特效",settingsLibrary.danmakuEffects) {
        settingsLibrary.danmakuEffects=it; renderSettings()
    })
    landscapeColumnCell(right,landscapeSlider("聚合条数",
        DANMAKU_MERGE_MIN_RANGE.first.toFloat(),DANMAKU_MERGE_MIN_RANGE.last.toFloat(),1f,
        settingsLibrary.danmakuMergeMin.toFloat(),{ "${it.roundToInt()} 条" }) {
        settingsLibrary.danmakuMergeMin=it.roundToInt()
    })
    landscapeColumnCell(right,landscapeSlider("弹幕大小",
        PhoneDanmakuSize.MIN_PERCENT.toFloat(),PhoneDanmakuSize.MAX_PERCENT.toFloat(),
        PhoneDanmakuSize.STEP_PERCENT.toFloat(),PhoneDanmakuSize.percent(this).toFloat(),
        { PhoneDanmakuSize.label(it) }) { PhoneDanmakuSize.set(this,it) })
    landscapeColumnCell(right,landscapeSlider("弹幕透明度",
        DanmakuStyle.ALPHA_MIN,DanmakuStyle.ALPHA_MAX,DanmakuStyle.ALPHA_STEP,
        settingsLibrary.danmakuAlpha,{ DanmakuStyle.percent(it) }) {
        settingsLibrary.danmakuAlpha=DanmakuStyle.alpha(it)
    })
}

internal fun PhoneSettingsActivity.renderLandscapeAppearance() {
    val layout=landscapePanel("布局启动","UI大小、列表排版与推荐页滑动")
    landscapeLine(layout).also { line ->
        landscapeCell(line,landscapeSetting("UI大小",PhoneUiScale.label(PhoneUiScale.value)) {
            val options=PhoneUiScale.sizes
            pickImpl("UI大小",options.map { it.label },
                options.indexOf(PhoneUiScale.sizeOf(PhoneUiScale.value))) { i ->
                PhoneUiScale.set(this,options[i].scale)
                recreate()
            }
        })
        val base=settingsLibrary.gridColumns.coerceIn(1,3)
        landscapeCell(line,landscapeSetting("列表每行","${base+3} 张") {
            val options=listOf(1,2,3)
            pickImpl("海报列表每行几张",
                options.map { "竖屏 $it 张 · 横屏 ${it+3} 张" },
                options.indexOf(base).coerceAtLeast(0)) { i ->
                settingsLibrary.gridColumns=options[i]; renderSettings()
            }
        })
    }
    landscapeLine(layout).also { line ->
        landscapeCell(line,landscapeSwitch("推荐页左右滑动",settingsLibrary.recommendSwipe) {
            settingsLibrary.recommendSwipe=it; renderSettings()
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
