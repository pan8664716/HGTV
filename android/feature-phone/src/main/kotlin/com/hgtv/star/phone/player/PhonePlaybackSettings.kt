// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.hgtv.star.core.DanmakuMode
import com.hgtv.star.core.DanmakuSpeed
import com.hgtv.star.core.DanmakuStyle
import com.hgtv.star.core.PlaybackQuality
import com.hgtv.star.core.PlaybackSpeed
import com.hgtv.star.core.VideoDecoderMode
import com.hgtv.star.core.VideoFrameMode
import com.hgtv.star.phone.ui.PhoneSheet
import com.hgtv.star.phone.ui.PhoneSwitchRow
import com.hgtv.star.phone.ui.PhoneStyle
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.phoneSize
import com.hgtv.star.phone.ui.roundedBackground
import com.hgtv.star.shared.data.Library

/**
 * 播放设置面板的各行：**推荐信息流与正式播放页共用这一份**。
 *
 * 与电视端的播放设置面板保持一致（清晰度 / 倍速 / 画面模式 / 视频解码 / 画面缩放 /
 * 弹幕显示 / 弹幕速度 / 弹幕大小 / 弹幕透明度）；弹幕字号在手机上使用独立刻度，
 * 其余项目写同一批落盘值。
 *
 * 为什么抽成一个函数：这两处面板以前各写一遍，结果推荐页只做了三项、播放页做了全套——
 * 用户在推荐页长按中间想调字号，发现根本没有那一行。现在只有这一份定义，两边不可能再分叉。
 *
 * 各端的差异只在**回调**里：
 * * 播放页换清晰度要重取地址并续播、换画面模式要立刻改版面；
 * * 信息流换清晰度要重开这一屏、换画面模式只改当前那一页的缩放方式。
 *
 * [perLine] > 0 时选项按每行固定个数折行（横屏 1/3 抽屉里六档倍速一行放不下）。
 */
fun buildPlaybackSettingsRows(
    host: View,
    panel: LinearLayout,
    lib: Library,
    perLine: Int,
    /** 当前倍速：播放页跟着用户这次会话的值，信息流跟书库一致。 */
    speed: Float,
    onSpeed: (Float) -> Unit,
    onQuality: (Int) -> Unit,
    onFrame: (VideoFrameMode) -> Unit,
    onScale: (Float) -> Unit,
    /** 解码方式换档后回调（下一次起播才真正生效，但摘要要立刻跟着变）。 */
    onDecoder: (() -> Unit)? = null,
    /**
     * 「定时关闭」当前选中的是第几项（[com.hgtv.star.core.SleepTimer.LABELS] 的下标）。
     *
     * 定时是**本次观看的会话状态**（`SleepTimer` 不落盘，与 TV 一致）：关掉应用就没了，
     * 明天再打开不会莫名其妙在半小时后停下。手机上看短剧大多是躺床上看，这个开关比电视更常用。
     */
    sleepChoice: Int = 0,
    /** 选定时哪一档。`null` 表示这一处不提供定时（信息流里没有自己的会话计时器）。 */
    onSleep: ((Int) -> Unit)? = null,
    /**
     * 「相关剧集」入口：`null` 表示这一处不提供（信息流里没有详情，也就没有相关推荐）。
     *
     * 电视上它是播放菜单里单独的一格，随时能打开；手机上把它放在设置面板最后一行，
     * 与「定时关闭」挨着——两件事都是「看着看着想做点别的」。
     */
    onRelated: (() -> Unit)? = null,
    onAuthor: (() -> Unit)? = null,
    /** 返回点击后的收藏状态；非空时在动作区显示「加入收藏 / 取消收藏」。 */
    onFavorite: (() -> Boolean)? = null,
    favorite: Boolean = false,
    /**
     * 弹幕四项（显示 / 速度 / 字号 / 透明度）改动后立刻回调。
     *
     * 只写 [Library] 不会动已经在画的 [com.hgtv.star.shared.media.DanmakuView]：
     * 它的字号、透明度、速度、显示方式都是内存里的字段，下次换页 / 重开这一集才重新读设置。
     * 用户改完字号看不到变化，读到的就是「弹幕设置不生效」。两处调用方都在这里
     * 把当前页的弹幕层重刷一遍（见 `PhoneFeedView` / `PhonePlayerActivity`）。
     */
    onDanmaku: (() -> Unit)? = null,
    /** 调试模式切换后立刻刷新当前页面的调试面板。 */
    onDebug: (() -> Unit)? = null,
) {
    if (onRelated != null || onAuthor != null || onFavorite != null) {
        val actions = LinearLayout(host.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, host.context.dp(2), 0, host.context.dp(8))
        }
        fun action(label: String, onTap: () -> Unit): TextView = TextView(host.context).apply {
            text = label
            gravity = Gravity.CENTER
            phoneSize(13)
            setTextColor(PhoneStyle.text)
            setPadding(host.context.dp(6), host.context.dp(10), host.context.dp(6), host.context.dp(10))
            background = roundedBackground(0x18FFFFFF, host.context.dp(8).toFloat())
            isClickable = true
            setOnClickListener { onTap() }
        }
        fun addAction(view: TextView, first: Boolean) {
            actions.addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (!first) leftMargin = host.context.dp(6)
            })
        }
        var index = 0
        onRelated?.let { callback ->
            addAction(action("相关剧集", callback), index++ == 0)
        }
        onAuthor?.let { callback ->
            addAction(action("作者作品", callback), index++ == 0)
        }
        onFavorite?.let { callback ->
            lateinit var button: TextView
            button = action(if (favorite) "取消收藏" else "加入收藏") {
                val now = callback()
                button.text = if (now) "取消收藏" else "加入收藏"
            }
            addAction(button, index++ == 0)
        }
        panel.addView(actions, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    val qualities = PlaybackQuality.options
    panel.addView(
        PhoneSheet.row(host, "清晰度", qualities.map { "${it}P" },
            qualities.indexOf(lib.maxQuality).coerceAtLeast(0), perLine = perLine) { i ->
            if (qualities[i] != lib.maxQuality) {
                lib.maxQuality = qualities[i]
                onQuality(qualities[i])
            }
        }
    )

    // 手机 / 平板比电视多一档 3×（见 `PlaybackSpeed.phoneOptions`），一共七档。
    val speeds = PlaybackSpeed.phoneOptions
    // 推荐页横屏的底部设置面板宽度足够，七档倍速排成一行；竖屏/其它面板仍按 4+3 折行。
    val speedPerLine = if (perLine == 0 && host.context.resources.configuration.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    ) 0 else 4
    // 倍速七档、定时八档：一行排不下（每颗还要写出「1.25×」「90 分钟」这种读数），
    // 因此这两行**固定折行**——倍速 4+3、定时 4+4。
    // 不再跟 [perLine] 走：横屏抽屉按 3 列排会把定时拆成 3+3+2 三行，与竖屏不一致；
    // 倍速按 3 列排则会把第 7 档单独甩到最后一行（那一行只有一颗、被权重拉成整行宽）。
    panel.addView(
        PhoneSheet.row(host, "倍速", speeds.map { PlaybackSpeed.label(it) },
            speeds.indexOf(speed).coerceAtLeast(0), perLine = speedPerLine) { i ->
            lib.playbackSpeed = speeds[i]
            onSpeed(speeds[i])
        }
    )

    val frames = VideoFrameMode.entries
    panel.addView(
        PhoneSheet.row(host, "画面模式", frames.map { it.label },
            frames.indexOf(lib.frameMode).coerceAtLeast(0), perLine = perLine) { i ->
            lib.frameMode = frames[i]
            onFrame(frames[i])
        }
    )

    // 视频解码：与 TV 一样「下一次起播生效」。播放器是进程单例，
    // 因此这里只落盘 + 把选择器换档，不打断正在播的这一路。
    val decoders = VideoDecoderMode.entries
    panel.addView(
        PhoneSheet.row(host, "视频解码", decoders.map { it.label },
            decoders.indexOf(lib.decoderMode).coerceAtLeast(0), perLine = perLine) { i ->
            lib.decoderMode = decoders[i]
            PhoneDecoder.mode = decoders[i]
            onDecoder?.invoke()
        }
    )

    if (onSleep != null) {
        // 定时八档，同样固定折成两行（4+4）：一行塞八个，每颗只剩两个字宽，读不出来。
        panel.addView(
            PhoneSheet.row(host, "定时关闭", com.hgtv.star.core.SleepTimer.LABELS,
                sleepChoice.coerceIn(0, com.hgtv.star.core.SleepTimer.LABELS.lastIndex),
                perLine = 4) { i ->
                onSleep.invoke(i)
            }
        )
    }

    val modes = DanmakuMode.entries
    panel.addView(
        PhoneSheet.row(host, "弹幕显示", modes.map { it.label },
            modes.indexOf(lib.danmakuMode).coerceAtLeast(0), perLine = perLine) { i ->
            lib.danmakuMode = modes[i]
            onDanmaku?.invoke()
        }
    )

    val danmakuSpeeds = DanmakuSpeed.entries
    panel.addView(
        PhoneSheet.row(host, "弹幕速度", danmakuSpeeds.map { it.label },
            danmakuSpeeds.indexOf(lib.danmakuSpeed).coerceAtLeast(0), perLine = perLine) { i ->
            lib.danmakuSpeed = danmakuSpeeds[i]
            onDanmaku?.invoke()
        }
    )

    panel.addView(
        PhoneSheet.sliderRow(host, "弹幕大小",
            PhoneDanmakuSize.MIN_PERCENT.toFloat(), PhoneDanmakuSize.MAX_PERCENT.toFloat(),
            PhoneDanmakuSize.STEP_PERCENT.toFloat(),
            PhoneDanmakuSize.percent(host.context).toFloat(), { PhoneDanmakuSize.label(it) }) { value ->
            PhoneDanmakuSize.set(host.context, value)
            onDanmaku?.invoke()
        }
    )
    panel.addView(
        PhoneSheet.sliderRow(host, "弹幕透明度", DanmakuStyle.ALPHA_MIN, DanmakuStyle.ALPHA_MAX,
            DanmakuStyle.ALPHA_STEP, lib.danmakuAlpha, { DanmakuStyle.percent(it) }) { value ->
            lib.danmakuAlpha = DanmakuStyle.alpha(value)
            onDanmaku?.invoke()
        }
    )

    // 「画面缩放」排在弹幕四项之后：它和上面几项一样是「看着看着想调一下」的画面参数，
    // 放在弹幕那一组之前会把画面类与弹幕类的项交叉打断。播放页与信息流共用这一份，
    // 两边顺序因此永远一致。
    panel.addView(
        PhoneSheet.sliderRow(host, "画面缩放", 1f, 100f, 1f, lib.videoScale,
            { "${Math.round(it)}%" }) { value ->
            lib.videoScale = value
            onScale(value)
        }
    )

    panel.addView(
        PhoneSwitchRow(host.context, "调试模式", lib.debugMode) { on ->
            lib.debugMode = on
            onDebug?.invoke()
        },
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            host.context.dp(42),
        ).apply { topMargin = host.context.dp(10) },
    )
}

