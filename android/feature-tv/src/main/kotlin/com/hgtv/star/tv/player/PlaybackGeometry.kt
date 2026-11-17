// SPDX-License-Identifier: GPL-3.0-only
@file:JvmName("MainActivityPlaybackKt")
@file:JvmMultifileClass
package com.hgtv.star.tv

import com.hgtv.star.shared.compat.hasInternet
import com.hgtv.star.shared.compat.isMeteredConnection

import android.app.Activity
import android.view.*
import android.widget.*
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.mediacodec.MediaCodecRenderer
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.ui.PlayerView
import com.hgtv.star.core.*
import com.hgtv.star.shared.data.WatchProgress
import com.hgtv.star.shared.data.PlaybackCapability
import com.hgtv.star.shared.data.AppRuntime
import com.hgtv.star.shared.media.VideoDataSource
import com.hgtv.star.shared.compat.setVectorIcon
import com.hgtv.star.tv.player.PlaybackMenu
import com.hgtv.star.shared.media.MediaSessionHost
import com.hgtv.star.tv.TvDialog
import com.hgtv.star.shared.compat.attachedToWindow
import com.hgtv.star.shared.architecture.network
import com.hgtv.star.shared.debug.NetSpeed
import com.hgtv.star.tv.R
fun com.hgtv.star.core.VideoFit.resizeMode()=when(this) {
    com.hgtv.star.core.VideoFit.FIT -> com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
    com.hgtv.star.core.VideoFit.ZOOM -> com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    com.hgtv.star.core.VideoFit.FILL -> com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
}
fun TvMainActivity.frameResizeMode()=library.frameMode.toFit().resizeMode()
/**
 * 「竖屏短剧全屏」**这台设备上能不能用**：只有手机 / 平板可用，电视不可用。
 *
 * 形态来自 [deviceMode]：只认用户首次启动时选择的 phone / tablet / tv。
 *
 * 为什么电视上不给用：电视是**遥控器 + 固定 16:9 大屏**，播放页的画面几何由
 * `applyVideoGeometry()` 统一算，竖屏片已经按「等比铺满、不裁内容」摆放；
 * 再叠一层整屏旋转只会把标题条、控制条、弹幕一起转过去，在 3 米外看是斜的。
 * 这一项留给真的会竖着拿、或系统不转屏的手机 / 平板用。
 *
 * 因此设置页在电视上把它画成不可用（见 `MainActivitySettings` 里那一格），
 * 播放层也在这里挡住——同一份设置从手机同步到电视时，存下来的值不会在电视上偷偷生效。
 *
 * 未选择（旧数据尚未迁移）同样按「不可用」处理；正常启动会先要求用户明确选择。
 */
fun TvMainActivity.autoFullscreenAvailable(): Boolean = when(deviceMode()) {
    com.hgtv.star.shared.compat.DeviceForm.PHONE,
    com.hgtv.star.shared.compat.DeviceForm.TABLET -> true
    else -> false
}

/**
 * 「竖屏短剧全屏」要不要把画面**转 90°**。
 *
 * 需求的原话是「屏幕是 16:9、短剧是 9:16，把短剧旋转一下就可以自动匹配全屏」——
 * 也就是**画面转向**，不是放大裁切：竖屏片转 90° 之后就是 16:9，
 * 正好铺满屏幕，一个像素都不用裁。
 *
 * 只在这三种情况同时成立时才转：
 * 1. 开关打开；
 * 2. 片源是竖屏（高 > 宽），也就是短剧；
 * 3. 屏幕是横屏（宽 > 高），也就是横放的平板 / 手机。
 *
 * 屏幕本身是竖的时候（平板竖着拿、分屏）不转：那时竖屏片本来就是满的，
 * 转过去反而变成两边黑边。
 */
fun TvMainActivity.autoFullscreenRotated(): Boolean {
    if(!library.autoFullscreen) return false
    // 只有手机 / 平板能用（见 [autoFullscreenAvailable]）：电视上这个开关存着也不生效。
    if(!autoFullscreenAvailable()) return false
    // 视频必须画在 TextureView 上才转得动：SurfaceView 是独立图层，父级旋转不会作用到它，
    // 硬转会得到一张拉扁的画面。这一条是兜底（开关打开时建的就是 TextureView 那一版）。
    if(playerView?.videoSurfaceView !is android.view.TextureView) return false
    val videoW=videoWidth; val videoH=videoHeight
    if(videoW<=0 || videoH<=0 || videoW>=videoH) return false
    val screenW=root.width; val screenH=root.height
    if(screenW<=0 || screenH<=0) return false
    return screenW>screenH
}

/**
 * 转**哪一边**：+90（顺时针）还是 −90（逆时针）。
 *
 * ## 为什么需要分方向
 *
 * 竖屏短剧转 90° 铺满横屏之后，「片子的上边」只剩两个可能：屏幕左边、屏幕右边。
 * 哪种才是「正的」，取决于用户此刻是**怎样把手机横过来**的——也就是系统报的
 * `Display.rotation`：
 *
 * | `Display.rotation` | 机身 | 机身的「上边」现在在哪 | 片子该往哪转 |
 * |---|---|---|---|
 * | `ROTATION_90`  | 逆时针转了 90°（最常见：手机横过来、上边朝左） | 屏幕左侧 | **−90°**（逆时针，片子的上边跟着朝左） |
 * | `ROTATION_270` | 顺时针转了 90°（上边朝右）               | 屏幕右侧 | **+90°**（顺时针） |
 * | `ROTATION_0` / `180` | 电视、原生横屏的平板、显示器 | 就是屏幕上边 | +90°（一直以来的行为，不动） |
 *
 * 施工时间顺序上踩过的坑：原来无条件 `+90f`，在手机上（`ROTATION_90`）得到的是
 * 片子的上边朝**右**——与用户手上那台手机的方向正好相反，读起来就是「上下颠倒」。
 * 电视是 `ROTATION_0`，`+90f` 一直是对的，因此这里只对 `ROTATION_90` 取负，
 * 电视与横屏平板的行为一个像素都不变。
 *
 * API 30 起 `Activity.display` 取代了 `windowManager.defaultDisplay`（后者已废弃）；
 * 两条路都包在 `runCatching` 里：拿不到就按 `ROTATION_0`（电视的默认行为）处理。
 */
fun TvMainActivity.fullscreenSpinDirection(): Float {
    // 用户选择电视时恒为横屏 `ROTATION_0`，直接用 `+90`。
    // 电视上这一项现在不可用（见 [autoFullscreenAvailable]），走不到这里；
    // 留着这一档是为了手动选「电视」之后再切回手机 / 平板时方向仍然有确定值。
    if(deviceMode()==com.hgtv.star.shared.compat.DeviceForm.TV) return 90f
    val rotation=runCatching {
        if(android.os.Build.VERSION.SDK_INT>=30) display?.rotation
        else @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation
    }.getOrNull() ?: Surface.ROTATION_0
    return if(rotation==Surface.ROTATION_90) -90f else 90f
}
/**
 * 画面几何：**缩放比例 + 是否旋转**，一次算完一起落到 [playerView] 上。
 *
 * 两者必须一起算：旋转后画面的「宽」其实是屏幕的高，缩放百分比要作用在旋转之后的边上。
 *
 * - 不转：`宽 = 屏宽 × 比例`、`高 = 屏高 × 比例`，居中；
 * - 转 90°：`宽 = 屏高 × 比例`、`高 = 屏宽 × 比例`，同样居中。旋转是绕自身中心的，
 *   因此这个「横过来」的框转动 90° 之后，正好落在屏幕的 `屏宽 × 屏高` 上；
 * - 100% 时不做任何乘法，直接铺满（与改动前完全一致）。
 *
 * 转的方向交给 [fullscreenSpinDirection]（手机 `−90`、电视 / 横屏平板 `+90`）：
 * 框的尺寸对两个方向是对称的，因此换方向不需要动这里的任何算式。
 */
fun TvMainActivity.applyVideoGeometry(target: PlayerView?=playerView) {
    val view=target ?: return
    val params=view.layoutParams as? FrameLayout.LayoutParams ?: return
    val rootW=root.width; val rootH=root.height
    if(rootW<=0 || rootH<=0) return
    val percent=library.videoScale.coerceIn(1f,100f)
    val rotated=autoFullscreenRotated()
    // 旋转后要按「屏幕的高」当宽、「屏幕的宽」当高来排版。
    val baseW=if(rotated) rootH else rootW
    val baseH=if(rotated) rootW else rootH
    val width=if(percent>=100f) baseW else (baseW*percent/100f).toInt().coerceAtLeast(1)
    val height=if(percent>=100f) baseH else (baseH*percent/100f).toInt().coerceAtLeast(1)
    val rotation=if(rotated) fullscreenSpinDirection() else 0f
    // 界面那一层跟着画面一起转（见 [applyOverlayGeometry]）：两者必须同一次落下去，
    // 否则会出现「画面转了、控制条还横着」的那一两帧。
    //
    // **必须放在下面那条提前返回之前**：画面这一层没变、界面那一层可能变了
    //（例如刚从别的页面回来、`overlayLayer` 刚建好还没摆过），
    // 提前返回会把那一层漏掉，表现就是「转了画面、界面没转」。
    applyOverlayGeometry()
    if(params.width==width && params.height==height &&
        params.gravity==Gravity.CENTER && view.rotation==rotation
    ) return
    params.width=width; params.height=height; params.gravity=Gravity.CENTER
    view.rotation=rotation
    view.layoutParams=params
}
/**
 * 画面之外那一层（弹幕 / 手势层 / 顶部信息条 / 底部控制条）的几何。
 *
 * 与 [applyVideoGeometry] 同一套算式，**只有一处不同：不乘画面缩放的比例**。
 *
 * 缩放的语义是「只缩画面，弹幕照旧铺满整屏」（见 [applyVideoScale] 的说明）：
 * 把控制条也按 50% 摆到中间，用户就点不到、也看不清了。因此这里恒按 100% 排版，
 * 只在「竖屏短剧全屏」要求旋转时，才和画面取同一个角度、同一组对调过的宽高。
 *
 * 转动方向必须与画面**完全一致**（都走 [fullscreenSpinDirection]）：
 * 两个方向反了的话，界面会对着画面的反面，读起来是倒的。
 */
fun TvMainActivity.applyOverlayGeometry() {
    val view=overlayLayer ?: return
    val params=view.layoutParams as? FrameLayout.LayoutParams ?: return
    val rootW=root.width; val rootH=root.height
    if(rootW<=0 || rootH<=0) return
    val rotated=autoFullscreenRotated()
    val width=if(rotated) rootH else rootW
    val height=if(rotated) rootW else rootH
    val rotation=if(rotated) fullscreenSpinDirection() else 0f
    if(params.width==width && params.height==height &&
        params.gravity==Gravity.CENTER && view.rotation==rotation
    ) return
    params.width=width; params.height=height; params.gravity=Gravity.CENTER
    view.rotation=rotation
    view.layoutParams=params
}
/**
 * 画面模式落到播放器上。
 *
 * **竖屏短剧全屏只靠旋转实现，不改画面模式**：旋转之后那个框已经是 16:9，
 * 「完整画面」就能铺满，用户自己选的档位（完整 / 等比铺满 / 拉伸）语义保持原样。
 */
fun TvMainActivity.applyResizeMode(target: PlayerView?=playerView) {
    target?.resizeMode=frameResizeMode()
    // 旋转 / 缩放与画面模式是同一次「这一集怎么摆」的决策：片源尺寸一变，
    // 旋转与否可能跟着变（竖屏剧 ⟷ 横屏剧），这里一并落下去。
    applyVideoGeometry(target)
}
/**
 * 画面缩放 / 竖屏短剧全屏：两者的落点都是 [playerView] 这一层的几何，
 * 因此统一交给 [applyVideoGeometry]（旋转与缩放一起算，缺一个都会排错边）。
 *
 * 弹幕层（[danmakuView]）**不受影响**：它仍然占满整屏、按整屏坐标算轨道，
 * 所以把画面缩到 50%（或转 90°）时，弹幕照旧铺满屏幕横着滚。
 */
fun TvMainActivity.applyVideoScale() = applyVideoGeometry()
/**
 * 此刻屏幕上有没有一块**唤出来的浮层**（控制条 / 播放设置 / 选集 / 各种选择器）。
 *
 * 「唤出」的口径统一在这里：[hud] 露着（电视上按确定 / 菜单，触屏上轻点画面都走这条）、
 * 播放设置面板开着、选集面板开着、或者某个选择器弹窗开着。四项信息的临时全显
 * （见 [syncPlaybackInfo]）与「推荐页顶部这条要不要跟着控制条走」都以它为准，
 * 免得两处各判一半、出现「菜单开着但标题没出来」。
 *
 * `hud` 是 lateinit：扩展函数里读不到 `isInitialized`（那是背板字段），
 * 冷启动还没建播放层时读它会抛，因此用一次受捕的读取兜住。
 */
fun TvMainActivity.playbackOverlayShown(): Boolean {
    if(playbackMenu!=null || episodePanel!=null || speedDialog!=null || finishedPanel!=null) return true
    return runCatching { hud.visibility==View.VISIBLE }.getOrDefault(false)
}

/**
 * 播放画面顶部那一条信息（时间 / 剧名 / 集数 / 网速）的显隐与读数刷新。
 *
 * 两条规则叠在一起：
 *
 * 1. **平时**按设置页「播放显示」那四枚开关走（默认四项全关，画面上什么都不挂）；
 * 2. **唤出任何一块浮层时四项全显**——轻点画面出现控制条、按菜单开播放设置、开选集、
 *    开各种选择器，这一刻用户正在「读这一集是什么、播到哪儿」，四项就是这块上下文，
 *    因此**不受那四枚开关限制**；浮层收起后再回到开关的值。
 *
 * 推荐页：顶部这条跟着控制条一起显隐（轻点唤出、收起时一起收）。那一页的本体是整屏连播，
 * 常驻一条标题会让「一滑一部」的观感变成「在一个播放器里」；电视上的推荐页没有控制条，
 * 剧名在底部浮层里，因此整条不显示。
 */
fun TvMainActivity.syncPlaybackInfo() {
    val bar=playbackInfo
    if(bar!=null) {
        // 浮层露着 = 四项全显（见函数注释第 2 条）。
        val allOn=playbackOverlayShown()
        val shown=screen=="player" || (screen=="recommend" && touchArmed && allOn)
        playbackTitle.visibility=if(allOn || library.playerTitle) View.VISIBLE else View.GONE
        // 推荐页顶部条只显示剧名 / 时间 / 网速；集数读数在底部推荐浮层的标题后面。
        playbackEpisode?.visibility=if(screen!="recommend" && (allOn || library.playerEpisode)) {
            View.VISIBLE
        } else {
            View.GONE
        }
        playerClock?.visibility=if(allOn || library.playerClock) View.VISIBLE else View.GONE
        playbackSpeedText?.visibility=if(allOn || library.playerSpeed) View.VISIBLE else View.GONE
        // **这一行只在真的有一项要显示时才出现。**
        //
        // 它的背景是「50% 黑 → 透明」的竖向渐变（见 [buildPlaybackLayer]），那是给白字垫底用的。
        // 四项默认全关，早先这里只判 `screen=="player"`：播放页上这一行于是**常驻但空着**，
        // 底色却照画——画面顶部 96dp 平白多出一条越来越淡的暗带。
        // 用户反馈的「全屏顶部有一些黑雾，需要仔细对比才能发现」就是它：
        // 渐变很缓，暗场里完全看不出，亮场里才显形，所以只能靠对比发现。
        val anyItem=playbackTitle.visibility==View.VISIBLE ||
            playbackEpisode?.visibility==View.VISIBLE ||
            playerClock?.visibility==View.VISIBLE ||
            playbackSpeedText?.visibility==View.VISIBLE
        bar.visibility=if(shown && anyItem) View.VISIBLE else View.GONE
        // 调试面板是**悬浮**在最上层的一块，不参与任何布局：
        // 这里不按它让位、不改内边距，否则顶部这条会被压窄（用户要求「不要挤压任何元素」）。
        // 面板盖住哪一格就盖住哪一格，关掉即恢复。
    }
    applyDanmakuTopProtection()
    updatePlaybackInfoReadings()
}

/**
 * 弹幕首行避开顶部四项播放显示。
 *
 * `topTextAnchorPx` 对齐的是文字实际顶边，不是轨道框顶边，因此 2dp 的 gap 在
 * 不同字号 / 行高下仍稳定。关闭开关或顶部条没显示时清掉锚点，回到原来的
 * `topInsetPx + topGapDp` 轨道。
 */
internal fun TvMainActivity.applyDanmakuTopProtection() {
    val view=danmakuView ?: return
    val bar=playbackInfo
    val active=screen=="player" &&
        library.danmakuAvoidPlaybackInfo &&
        bar?.visibility==View.VISIBLE &&
        bar.height>0
    view.topTextAnchorPx=if(active) bar.bottom+dp(2) else null
}
/** 只换字、不动布局：时间每分钟一格，网速每一秒一格（见每秒一次的 tick）。 */
fun TvMainActivity.updatePlaybackInfoReadings() {
    playerClock?.takeIf { it.visibility==View.VISIBLE }?.let { clock ->
        val calendar=java.util.Calendar.getInstance()
        // 时钟文案只有一份（`:core` 的 `PlaybackInfoLine`）：与手机端同一段格式。
        val now=com.hgtv.star.core.PlaybackInfoLine.clock(
            calendar.get(java.util.Calendar.HOUR_OF_DAY),calendar.get(java.util.Calendar.MINUTE)
        )
        if(clock.text.toString()!=now) clock.text=now
    }
    playbackSpeedText?.takeIf { it.visibility==View.VISIBLE }?.let { speed ->
        val text=NetSpeed.line()
        if(speed.text.toString()!=text) speed.text=text
    }
}
fun TvMainActivity.showFramePicker(anchor: TextView,inPlayer: Boolean) {
    tvTools.choose("全局画面模式",listOf("完整画面 · 保留比例与全部内容","等比铺满 · 会裁掉部分画面与字幕","拉伸铺满 · 画面比例会改变"),anchor,{ index ->
        library.frameMode=VideoFrameMode.entries[index]; applyResizeMode()
        if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "画面 " else "画面模式  ·  ")+library.frameMode.label
    })
}
/**
 * 视频解码方式：硬件解码 / 软件解码。
 *
 * 说明文字写清「什么时候该换」——这两档在正常情况下看不出区别，
 * 只有遇到花屏、绿屏、音画不同步、黑屏有声时才有意义。
 * [inPlayer] 为真时改完立刻重建播放器（[applyDecoderMode]），否则只落盘、下次起播生效。
 */
fun TvMainActivity.showDecoderPicker(anchor: TextView,inPlayer: Boolean) {
    val labels=listOf("硬件解码 · 省电，4K 更稳（默认）","软件解码 · 兼容性好，花屏 / 绿屏 / 音画不同步时用")
    tvTools.choose("视频解码",labels,anchor,{ index ->
        val selected=VideoDecoderMode.entries[index]
        val changed=playbackDecoderMode!=selected
        library.decoderMode=selected
        // 用户手动改解码方式：撤销自动切换、归还「切软解」的预算（不动清晰度上限）。
        playbackCoordinator.onManualDecoderChange()
        if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "解码 " else "视频解码  ·  ")+selected.label
        if(inPlayer && changed) applyDecoderMode() else if(inPlayer) updatePlaybackText()
    })
}
/**
 * 每行卡片数：4 / 5 / 6 / 7 / 8（全局）。
 *
 * 列数变少时卡片变宽，封面与卡片文字一起按比例放大（见 `HomeScreen.textScale` /
 * `SeriesGrid.gridTextScale`）。首页剧架与所有卡片栅格共用这一份设置。
 * 改完只落盘，回到列表时按新列数重建；正在看的页面不受影响。
 */
fun TvMainActivity.showHomeColumnsPicker(anchor: TextView) {
    val options=listOf(4,5,6,7,8)
    val labels=listOf("4 张 · 大卡片","5 张 · 中号卡片","6 张 · 紧凑（默认）","7 张 · 更紧凑","8 张 · 最紧凑")
    val selected=options.indexOf(library.homeColumns).coerceAtLeast(0)
    if(homeColumnsDialog!=null) return
    val picker=TvDialog(this,"每行卡片").choices(labels,selected) { index ->
        library.homeColumns=options[index]
        anchor.text="每行卡片  ·  ${library.homeColumns} 张"
    }
    picker.button("取消", icon=R.drawable.ic_menu_close)
    homeColumnsDialog=picker
    picker.show(anchor) {
        homeColumnsDialog=null
        if(anchor.attachedToWindow) anchor.requestFocus()
    }
}
/**
 * 「返回顶部」浮层的显隐档位文案（设置页那一格的读数与选择弹窗共用）。
 *
 * 未知值按「始终显示」读：磁盘 / 备份里的坏值在解码时就已归一，这里只是兜底，
 * 保证设置页永远显示一个真能选中的档位。
 */
fun backToTopLabel(mode: String): String=com.hgtv.star.core.BackToTopPolicy.label(mode)
/**
 * 设置格上显示的那一段读数（**短**）：一格只有半屏宽，值最多占一半，
 * 完整文案会被省略号吃掉尾巴，读出来就成了「下拉 8 行列表…」。
 * 弹窗里的选项仍用 [backToTopLabel] 的完整说法。
 */
fun backToTopShort(mode: String): String=com.hgtv.star.core.BackToTopPolicy.shortLabel(mode)
/**
 * 列表页「回到顶部」浮层的显隐方式：始终显示 / 下拉 5 行后显示 / 下拉 8 行后显示 / 始终不显示。
 *
 * 只影响**列表页**那枚浮层（榜单 / 分类 / 集合 / 搜索 / 我的）；首页剧架、详情页、
 * 播放页本来就不挂它（见 `updateBackToTop`）。
 *
 * 「下拉 N 行」按**列表滚过的行数**算：一行的高度取 `rowStride()`（含行间距），
 * 因此与设备分辨率、每行几张卡无关，用户读到的是「往下翻了几行」。
 * 改完只落盘，回到列表按新档位重算显隐，不需要退出应用。
 */
fun TvMainActivity.showBackToTopPicker(anchor: TextView) {
    val options=listOf(BACK_TO_TOP_ALWAYS,BACK_TO_TOP_ROWS5,BACK_TO_TOP_ROWS8,BACK_TO_TOP_NEVER)
    val labels=options.map(::backToTopLabel)
    val selected=options.indexOf(library.backToTop).coerceAtLeast(0)
    if(backToTopDialog!=null) return
    val picker=TvDialog(this,"返回顶部按钮").choices(labels,selected) { index ->
        library.backToTop=options[index]
        anchor.text="返回顶部  ·  "+backToTopShort(options[index])
    }
    picker.button("取消", icon=R.drawable.ic_menu_close)
    backToTopDialog=picker
    picker.show(anchor) {
        backToTopDialog=null
        if(anchor.attachedToWindow) anchor.requestFocus()
    }
}
/**
 * 选集分页数量：每页 10 / 20 / 30 / 40 / 50 集（默认 20）。
 *
 * 列数固定 10，因此一页就是 1..5 行；简介页与播放界面的选集面板共用同一份设置
 *（见 `EpisodeGrid` 的 `pageSize`），两处分段永远一致。
 * 改完只落盘：下一次进简介页、或下一次打开选集面板时按新页长摆格子。
 */
fun TvMainActivity.showEpisodePageSizePicker(anchor: TextView) {
    val options=listOf(10,20,30,40,50)
    val labels=listOf("每页 10 集 · 一屏一行","每页 20 集 · 一屏两行（默认）",
        "每页 30 集 · 一屏三行","每页 40 集 · 一屏四行","每页 50 集 · 一屏五行")
    tvTools.choose("选集分页数量",labels,anchor,{ index ->
        val size=options[index]
        library.episodePageSize=size
        anchor.text="选集分页  ·  $size 集"
    })
}
/**
 * 弹幕显示方式：0 行到 6 行。
 *
 * 排列顺序与每一档的说明都取自 [DanmakuMode.entries]（`hint`），设置页与播放设置面板不再各写一份文案——
 * 档位顺序是需求里定死的一条验收项，抄成三份迟早会走样。
 *
 * 弹幕数据本身就是原 App 播放时用的那一份（见 [ContentRepository.danmaku]）。
 *
 * [onChanged] 在档位真的变了之后回调一次——设置页用它就地收起 / 放出「弹幕速度」「弹幕大小」
 * 「弹幕透明度」三行（[DanmakuMode.showsStyle]）；播放设置面板走的是整块重建，不需要这个回调。
 */
fun TvMainActivity.showDanmakuPicker(anchor: TextView,inPlayer: Boolean,onChanged: ()->Unit={}) {
    tvTools.choose("弹幕显示方式",DanmakuMode.entries.map { it.optionHint },anchor,{ index ->
        val selected=DanmakuMode.entries[index]
        val changed=library.danmakuMode!=selected
        library.danmakuMode=selected; danmakuView?.mode=selected
        if(anchor.tag!="playback-settings") anchor.text=danmakuLabel(if(inPlayer) "弹幕 " else "弹幕显示  ·  ")
        if(changed) onChanged()
    })
}
/**
 * 弹幕移动速度：慢速 / 标准 / 快速。
 *
 * 三档只改「整体多快」：弹幕层里所有弹幕同速（见 [com.hgtv.star.shared.media.DanmakuView]），
 * 换档不会让某一条追上前面那一条——快的档位只是让同一屏通过得更多、同一条轨道排得更密。
 * [onChanged] 在档位真的变了之后回调一次（设置页用它刷新那一行文案）。
 */
fun TvMainActivity.showDanmakuSpeedPicker(anchor: TextView,inPlayer: Boolean,onChanged: ()->Unit={}) {
    tvTools.choose("弹幕移动速度",DanmakuSpeed.entries.map { it.hint },anchor,{ index ->
        val selected=DanmakuSpeed.entries[index]
        val changed=library.danmakuSpeed!=selected
        library.danmakuSpeed=selected; danmakuView?.speed=selected
        if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "弹幕速度 " else "弹幕速度  ·  ")+selected.label
        if(changed) onChanged()
    })
}
/**
 * 「弹幕显示」那一行的文案：显示方式 + 最近一集实际取到多少条。
 *
 * 平台只对部分剧集放开匿名弹幕，屏幕上「一条都没有」和「功能没生效」从画面上分不出来，
 * 这一行就是给这件事一个明确的读数（播放界面那排状态项太窄，放不下数字）。
 */
fun TvMainActivity.danmakuLabel(prefix: String)=prefix+library.danmakuMode.optionLabel+
    if(danmakuCount<0) "" else "（上一集 ${if(danmakuCount>0) "$danmakuCount 条" else "无弹幕"}）"
fun TvMainActivity.seek(direction: Int,repeat: Int) {
    val p=player ?: return; if(!playbackReady) return
    val duration=p.duration; if(duration<=0) return
    // 单次步长、连按加速与片尾保护都在 `:core`（[SeekStep]）：手机端读的是同一份，
    // 两端「连按几下、每次多少秒、停在哪」不会再各调各的。
    // 这里把按键的 repeat 计数（首次为 0）换算成「第几次操作」（首次为 1）。
    val step=SeekStep.stepMs(library.seekStep,repeat+1)
    pendingSeek=SeekStep.target(pendingSeek ?: p.currentPosition,direction,step,duration)
    main.removeCallbacks(seekRunnable); main.postDelayed(seekRunnable,250); updatePlaybackText(); showHud()
}
fun TvMainActivity.dismissPlaybackMenu() {
    // 倍速滑杆可能还在 500ms 等待窗口里：关面板前先落地，不能让用户刚调好的值丢掉。
    playbackMenu?.flushPendingSliders()
    playbackMenu?.let { menu -> if(menu.hasFocus()) menu.clearFocus() }
    // 面板现在是「外壳 → 滚动容器 → 面板」三层，直接 removeView(menu) 摘不掉任何东西
    // （menu 的父级是滚动容器），浮层会留在画面上。这里按最外层摘。
    playbackMenuShell?.let { shell -> (shell.parent as? ViewGroup)?.removeView(shell) }
    playbackMenuShell=null
    playbackMenu=null
    // **焦点交还播放界面**：`panel` 是「方向键交给浮层焦点链」的开关（见 `dispatchKeyEvent`），
    // 打开菜单时置真，收起时必须一起放开——否则方向键 / 确定键都被 `if(!panel)` 挡掉，
    // 表现就是「浮层关了、控制条还亮着、左右键全都不好使，按返回才恢复」。
    //
    // 出错页是例外：那一版的按钮本身要能按，焦点得留在它上面，因此按 [playError] 判定。
    panel=playError
    // 菜单已经不在屏幕上：那一位「是被哪一下菜单键按开的」也随之作废，
    // 否则下一次菜单键的抬起会被这条旧标记吞掉（用户会觉得「按了菜单没反应」）。
    playbackMenuOpenPress=false
    // 菜单收起：推荐页那块信息浮层回到画面上（正式播放里它是 null，空操作；
    // 触屏的推荐页不建这块浮层，同样是空操作——它上面盖着的是同一条控制条）。
    recommendOverlay?.visibility=View.VISIBLE
    // 菜单收起 = 浮层不再「唤出」：顶部四项回到「播放显示」里用户选的值
    // （菜单里的空间重新还给画面，见 [syncPlaybackInfo]）。
    syncPlaybackInfo()
    // 重排计时器必须放在**最后**：判据要按「菜单已经摘掉之后还剩哪些浮层」算。
    // 放在前面的话 `playbackMenu` 还没清空，等于给一块已经不存在的面板数秒；
    // 菜单关掉、控制条还在时该继续给控制条数着，因此这里是「重排」而不是「取消」。
    scheduleOverlayAutoHide()
}
/**
 * 遥控器「上」（或菜单键）打开底部设置面板：
 * 清晰度 / 倍速 / 画面模式 / 弹幕 / 弹幕大小 / 弹幕透明度 / 定时。
 *
 * 「弹幕大小 / 弹幕透明度」只在弹幕可见时存在：隐藏档下整层不画、也不发请求，
 * 这两项改完看不到任何变化，因此那两行**直接不添加**（行号见 [sleepRow]）。
 *
 * 功能竖排、选项横排，上下换功能、左右换选项，确认立即生效，返回收起整个浮层。
 * 面板高度固定为 [com.hgtv.star.tv.TvStyle.PLAYER_PANEL_HEIGHT]（与选集面板同高），
 * 装不下的行留在滚动容器里，遥控器焦点走到哪一行
 * 就自动把哪一行带进可视区（ScrollView 自己的 `requestChildFocus` 行为），因此所有行都能按到。
 *
 * [focusRow] 与 [focusChoice] 用来在改清晰度重建菜单、或点 HUD 状态行时定位焦点；
 * [focusChoice] 为负数时落在该项目当前选中的选项上。
 */
