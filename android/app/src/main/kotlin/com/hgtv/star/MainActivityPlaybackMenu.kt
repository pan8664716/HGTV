// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import android.app.Activity
import android.view.*
import android.widget.*
import com.google.android.exoplayer2.*
import com.hgtv.star.core.*
import com.hgtv.star.player.EpisodeGrid
import com.hgtv.star.player.EpisodePanel
import com.hgtv.star.player.PlaybackMenu
import com.hgtv.star.tv.TvDialog
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.tv.tvScrollHost
import com.hgtv.star.data.WatchProgress
import com.hgtv.star.compat.attachedToWindow

// 本文件是从 MainActivity.kt 拆出来的「播放菜单与各类选择器（清晰度 / 倍速 / 画面 / 弹幕 / 定时）、进度保存」这一块。
// 这些成员仍然属于 MainActivity，只是以扩展成员（MainActivity.xxx）的形式放在这里，
// 逻辑与拆之前逐行一致；改这里等于改 Activity 本体。

internal fun MainActivity.showPlaybackMenu(focusRow: Int=0,focusChoice: Int=-1) {
    if((screen!="player" && screen!="recommend") || playError) return
    // 还没有播放层（推荐页停在「取不到推荐」那行字上）：没有可调的东西，这一下菜单键吞掉即可。
    // `hud` 与 `playerView` 是同一个分支里建的，用后者判断在扩展函数里也读得到。
    if(playerView==null) return
    dismissPlaybackMenu()
    // 面板本身**复用**（见 [obtainPlaybackMenu]）：重复打开不再重建三十来枚按钮，
    // 只把各项的选中态按当前偏好同步一遍。这是「打开播放设置时弹幕卡一下」的根因修法。
    val menu=obtainPlaybackMenu()
    // 复用的面板上一轮还挂在旧的外壳里（`dismissPlaybackMenu` 摘的是外壳，不是面板本身）。
    // 不先摘掉就直接 add 进新外壳，会撞上「specified child already has a parent」。
    (menu.parent as? android.view.ViewGroup)?.removeView(menu)
    playbackMenu=menu
    panel=true
    // 设置菜单打开时收起底部控制条：参考界面里菜单就是这一层唯一的浮层。
    cancelOverlayAutoHide()
    hud.visibility=View.GONE
    // 推荐页那块剧名 / 集数浮层也让位：菜单贴底占了下三分之一，两块叠在一起会互相压字。
    recommendOverlay?.visibility=View.GONE
    // 菜单占着下半屏，顶部那条信息（剧名 / 集数）这时留在上面才读得出「在放哪一集」。
    syncPlaybackInfo()
    // 整宽贴在画面下方，**高度取 [TvStyle.PLAYER_PANEL_HEIGHT]**（与选集面板同高），
    // 超出的行靠上下键滚出来。
    // 面板本身就是滚动内容，渐变背板画在外壳上：否则背板会跟着内容一起被拉长，
    // 滚到下半段时面板顶部会变成半黑，而不是「从透明渐渐暗下去」。
    // 这里**不补 FOCUS_BLEED_VERTICAL**，只按豁免标注（见 docs/UI_REDESIGN.md 第 3 条）：
    //   · 横向：外壳整屏宽，菜单自己的左右内边距就是 78dp 内容线，最左 / 最右那枚选项
    //     放大 2.5% 之后离屏幕边还有 ≥68dp，画不到边界；
    //   · 纵向：面板里最高的一件是紧凑档选项（BUTTON_COMPACT + 2 × SOFT_BAND = 52dp），
    //     放大 1.05 倍向上 / 向下各溢出 52 × 2.5% ≈ 1.3dp，下面那 6dp 内边距已经盖住；
    //     而且首行之上还有菜单自己的 24dp 上内边距。
    // 换成 focusBleed() 会把左右内边距一并撑到 7dp，内容线就从 78dp 变成 85dp，
    // 与顶部标题条（78dp）对不齐，因此这里只标注、不补余量。
    val shell=tvScrollHost(ScrollView(this).apply { // focus-bleed-exempt: 外壳整屏宽、左右内容线 78dp；纵向溢出 ≤1.3dp 已由 6dp 内边距覆盖
        isVerticalScrollBarEnabled=false
        isFocusable=false
        clipChildren=false
        clipToPadding=false
        // 上下各留一点余量：焦点放大 1.05 的那圈底色画在行的边界之外，留白不够会被裁掉。
        setPadding(0,dp(TvStyle.SOFT_BAND),0,dp(TvStyle.SOFT_BAND))
        addView(menu,FrameLayout.LayoutParams(-1,-2))
    // 直角（`radius=0`）：这块浮层整宽贴底、压在视频上，圆角只会把上沿切出两个缺口，
    // 露出后面一截画面。与选集面板同一套几何。
    }).apply { background=TvStyle.sheet(this@showPlaybackMenu, alpha=TvStyle.SHEET_ALPHA_VIDEO, radius=0) }
    // 面板自己不再画背板（背板已经在外壳上），否则两层渐变叠起来会比选集面板深一档。
    menu.background=null
    playbackMenuShell=shell
    // 面板高度取 [TvStyle.PLAYER_PANEL_HEIGHT]：要与选集面板**同高**，而选集那边还要放下
    // 「标题 + 集数 + 分页行 + 两行单元格」，因此统一取 300 设计单位（720 画布的三分之一是 240，
    // 那个高度装不下选集，两块浮层就会一高一矮）。
    // 原来写的是 `heightPixels/3`：那是物理像素，不跟设计缩放走，
    // 小屏上内容缩了、底板没缩，菜单行就被裁在面板外（540dpi 上只剩两行可见）。
    // 面板也挂进「画面之外那一层」：竖屏短剧全屏把画面转 90° 时，设置面板跟着转，
    // 否则面板还是横的、贴着屏幕底边，与转过来的画面对不上一个方向。
    // 电视上不旋转，挂在哪里都一样。
    (overlayLayer ?: root).addView(shell,FrameLayout.LayoutParams(-1,dp(TvStyle.PLAYER_PANEL_HEIGHT),Gravity.BOTTOM))
    if(focusChoice<0) menu.focusSelected(focusRow) else menu.focus(focusRow,focusChoice)
    // 无操作自动消失从这里开始数（秒数见设置页「播放显示 → 播放界面自动消失」）。
    // 面板里的每一次按键 / 触摸都会重新排一遍（见 `dispatchKeyEvent` 与 `PlaybackMenu` 的触摸外壳）。
    scheduleOverlayAutoHide()
}

/**
 * 面板的构建（**只在第一次、或行结构变了时跑**）。
 *
 * 这段原来是 `showPlaybackMenu` 的开头：每按一次菜单键就把八九行、三十来枚紧凑档按钮
 * 重新 new 一遍。每一枚 [com.hgtv.star.tv.TvButton] 都要算尺寸、设内边距、
 * 首次绘制时生成柔光位图——全在 UI 线程。电视盒子上这一次要几十到两百毫秒，
 * 视频由 SurfaceView / 解码线程自己走，所以表现就是**弹幕停一下、遥控器迟一拍**。
 *
 * 行结构只有两种形态（弹幕可见时有速度 / 字号 / 透明度三行，隐藏时没有），
 * 因此按 [showsStyle] 缓存一份就够，见 [obtainPlaybackMenu]。
 */
private fun MainActivity.buildPlaybackMenu(showsStyle: Boolean): PlaybackMenu {
    // 先建面板再逐行追加：选项的点击回调要引用面板本身，不能在构造链里读还没赋值的变量。
    // **这里不能 `return menu.addRow(...)`**：那样第一条链之后就返回了，弹幕速度 / 字号 /
    // 透明度、定时、时间全成了死代码——表现就是「播放设置里只有弹幕显示，没有别的弹幕项」。
    val menu=PlaybackMenu(this)
    menu
        .addRow("清晰度",PlaybackQuality.options.mapIndexed { index,value ->
            PlaybackMenu.Choice("${value}P",value==playbackMaxQuality) {
                val changed=library.maxQuality!=value || sessionPlayback.effectiveMaxQuality!=null
                library.maxQuality=value
                // 用户自己选了档位：撤销自动降档与全部预算（见 `SessionPlaybackPolicy`）。
                sessionPlayback.onManualQualityChange()
                // 换清晰度要重新解析播放地址，播放器复用后菜单需要按新状态重建。
                if(changed) { playEpisode(episodeIndex,currentPosition(),player?.playWhenReady ?: requestedAutoplay); showPlaybackMenu(MENU_ROW_QUALITY,index) }
                else { menu.select(MENU_ROW_QUALITY,index); updatePlaybackText() }
            }
        })
        .addRow("倍速",PlaybackSpeed.options.mapIndexed { index,value ->
            PlaybackMenu.Choice(PlaybackSpeed.label(value),value==library.playbackSpeed) {
                library.playbackSpeed=value; player?.setPlaybackSpeed(value)
                menu.select(MENU_ROW_SPEED,index); updatePlaybackText()
            }
        })
        .addRow("画面模式",VideoFrameMode.entries.mapIndexed { index,mode ->
            PlaybackMenu.Choice(mode.label,mode==library.frameMode) {
                library.frameMode=mode; applyResizeMode()
                menu.select(MENU_ROW_FRAME,index); updatePlaybackText()
            }
        })
        // 弹幕显示方式：全屏 / 上方 / 隐藏。选完立刻作用在画面上的弹幕层，并存进本机设置。
        // 解码方式：硬件（省电、4K 稳）/ 软件（兼容性好，花屏、绿屏、音画不同步时用）。
        // 选中会重建播放器并从当前位置续播，画面闪一下属正常。
        .addRow("解码",VideoDecoderMode.entries.mapIndexed { index,mode ->
            PlaybackMenu.Choice(mode.label,mode==playbackDecoderMode) {
                if(playbackDecoderMode!=mode) {
                    library.decoderMode=mode
                    // 撤销自动切换，并把本集「切软解」的预算还回去（用户是知情选择，见策略）。
                    sessionPlayback.onManualDecoderChange()
                    applyDecoderMode()
                }
                else { menu.select(MENU_ROW_DECODER,index); updatePlaybackText() }
            }
        })
        .addRow("弹幕",DanmakuMode.entries.mapIndexed { index,mode ->
            PlaybackMenu.Choice(mode.label,mode==library.danmakuMode) {
                val previous=library.danmakuMode
                library.danmakuMode=mode
                danmakuView?.mode=mode
                // 隐藏档不发请求：从隐藏切回可见时如果这一集还没取过，这里补一次；
                // 已经取过的（raw 还在）只换画法，由 mode 的 setter 重排轨道，不再请求。
                if(previous==DanmakuMode.HIDDEN && mode!=DanmakuMode.HIDDEN && !danmakuLoaded) {
                    detail?.let { data -> data.episodes.getOrNull(episodeIndex)?.let { episode -> loadDanmaku(episode,data.series.id) } }
                } else if(mode!=DanmakuMode.HIDDEN) danmakuView?.invalidate()
                // 档位切换会改变面板的行数（隐藏时没有两条外观项），因此整块重建，
                // 焦点按回刚改的这一行、落在同一个档位上。
                if(previous!=mode) showPlaybackMenu(MENU_ROW_DANMAKU,index)
                else { menu.select(MENU_ROW_DANMAKU,index); updatePlaybackText() }
            }
        })
    // 弹幕的三项可调项：移动速度（三档，选中后整批重排轨道）+ 两项连续外观（字号缩放与不透明度）。
    // 速度排在字号前面：它决定同一条轨道能排多密，是「弹幕挤不挤」的第一观感。
    if(showsStyle) menu
        .addRow("弹幕速度",DanmakuSpeed.entries.mapIndexed { index,option ->
            PlaybackMenu.Choice(option.label,option==library.danmakuSpeed) {
                library.danmakuSpeed=option; danmakuView?.speed=option
                menu.select(MENU_ROW_DANMAKU_SPEED,index); updatePlaybackText()
            }
        })
        .addSlider("弹幕大小",DanmakuStyle.SCALE_MIN,DanmakuStyle.SCALE_MAX,DanmakuStyle.SCALE_STEP,library.danmakuScale) { value ->
            library.danmakuScale=value; danmakuView?.textScale=value
        }
        .addSlider("弹幕透明度",DanmakuStyle.ALPHA_MIN,DanmakuStyle.ALPHA_MAX,DanmakuStyle.ALPHA_STEP,library.danmakuAlpha) { value ->
            library.danmakuAlpha=value; danmakuView?.textAlpha=value
        }
    // 画面缩放：跟在「弹幕透明度」之后、「定时」之前。只缩播放画面本身，
    // 弹幕层不动（弹幕仍然按整屏坐标滚动）。
    // 与弹幕大小 / 透明度是同一种控件（[com.hgtv.star.tv.SettingSlider]），1..100 直接就是百分比。
    menu.addSlider("画面缩放",1f,100f,1f,library.videoScale,format={ "${it.toInt()}%" }) { value ->
        library.videoScale=value
        applyVideoScale()
    }
    menu.addRow("定时",sleepChoices(menu))
    menu.wire()
    return menu
}

/**
 * 取播放设置面板：能复用就复用，行结构变了才重建。
 *
 * 复用是安全的：每个选项的回调都在**点击那一刻**读 `library` 的当前值，
 * 而选中态由 [syncPlaybackMenu] 在挂上去之前刷一遍，因此复用出来的面板与新建的一模一样。
 */
private fun MainActivity.obtainPlaybackMenu(): PlaybackMenu {
    val showsStyle=library.danmakuMode.showsStyle
    // 行结构只由「弹幕外观那三行在不在」决定，因此缓存键就是这一位。
    val key=menuShape(showsStyle)
    val cached=playbackMenuCache
    if(cached!=null && playbackMenuCacheShape==key) {
        syncPlaybackMenu(cached,showsStyle)
        return cached
    }
    // 结构变了（弹幕隐藏 ⟷ 可见、推荐页 ⟷ 正片）或者第一次：旧的那一份行长不对，只能重建。
    dropPlaybackMenuCache()
    // 长按倍速：离开播放页时把速度还回去（手指可能是在这一页按下的，UP 落在了别的页面）。
    setPlaybackBoost(false)
    val menu=buildPlaybackMenu(showsStyle)
    playbackMenuCache=menu
    playbackMenuCacheShape=key
    return menu
}

/** 面板行结构的指纹：`style` = 有弹幕外观三行。 */
private fun menuShape(showsStyle: Boolean)="s${if(showsStyle) 1 else 0}"

/** 把缓存面板的选中态与两条滑块同步到当前偏好：纯配色 / 数值修改，不建视图、不建位图。 */
private fun MainActivity.syncPlaybackMenu(menu: PlaybackMenu,showsStyle: Boolean) {
    menu.sync(MENU_ROW_QUALITY,PlaybackQuality.options.indexOf(playbackMaxQuality))
    menu.sync(MENU_ROW_SPEED,PlaybackSpeed.options.indexOf(library.playbackSpeed))
    menu.sync(MENU_ROW_FRAME,VideoFrameMode.entries.indexOf(library.frameMode))
    menu.sync(MENU_ROW_DECODER,VideoDecoderMode.entries.indexOf(playbackDecoderMode))
    menu.sync(MENU_ROW_DANMAKU,DanmakuMode.entries.indexOf(library.danmakuMode))
    if(showsStyle) {
        menu.sync(MENU_ROW_DANMAKU_SPEED,DanmakuSpeed.entries.indexOf(library.danmakuSpeed))
        menu.sliderValue(MENU_ROW_SCALE,library.danmakuScale)
        menu.sliderValue(MENU_ROW_ALPHA,library.danmakuAlpha)
    }
    menu.sliderValue(videoScaleRow(showsStyle),library.videoScale)
    menu.sync(sleepRow(showsStyle),if(sleepTimer.active) sleepChoice else 0)
}

/**
 * 丢掉缓存（离开播放页、换分辨率、主题变化时）。
 *
 * 面板里的尺寸都是**建那一刻的像素**（内边距、最小宽度、圆角），因此屏幕参数一变
 * 就必须重建；平时只是离开播放页，留着它只会白占几十个 View 的内存。
 */
internal fun MainActivity.dropPlaybackMenuCache() {
    warmPlaybackMenuTask?.let { main.removeCallbacks(it) }
    warmPlaybackMenuTask=null
    playbackMenuCache=null
    playbackMenuCacheShape=null
}

/**
 * 起播之后**在空闲里**先把面板建好、量好、画一遍。
 *
 * 只缓存实例还不够：柔光位图是在第一次 `draw` 时才生成的，第一次打开菜单仍然要付这份钱。
 * 这里把它提前到起播稳定之后的空闲时刻——那时用户还没按菜单键，掉几帧没人看得见；
 * 「第一次打开就卡」因此也不存在。
 *
 * 画到一个几像素的小画布上就够：这一步只是让每个底板的 `draw` 真的被执行一次，
 * 位图本身按控件尺寸生成并进缓存（与画布大小无关）。
 */
internal fun MainActivity.warmPlaybackMenu(delayMs: Long=1500L) {
    if(isDestroyed || !foreground || (screen!="player" && screen!="recommend")) return
    if(playbackMenu!=null || playbackMenuCache!=null || playerView==null) return
    warmPlaybackMenuTask?.let { main.removeCallbacks(it) }
    val task=Runnable {
        warmPlaybackMenuTask=null
        if(isDestroyed || !foreground || (screen!="player" && screen!="recommend")) return@Runnable
        if(playbackMenu!=null || playbackMenuCache!=null) return@Runnable
        val menu=obtainPlaybackMenu()
        val width=root.width.takeIf { it>0 } ?: resources.displayMetrics.widthPixels
        menu.measure(
            View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED)
        )
        menu.layout(0,0,width,menu.measuredHeight.coerceAtLeast(1))
        runCatching {
            val scratch=android.graphics.Bitmap.createBitmap(8,8,android.graphics.Bitmap.Config.ARGB_8888)
            menu.draw(android.graphics.Canvas(scratch))
            scratch.recycle()
        }
    }
    warmPlaybackMenuTask=task
    main.postDelayed(task,delayMs)
}
internal fun MainActivity.showSpeedPicker(anchor: TextView,inPlayer: Boolean=true) {
    if(speedDialog!=null) return
    val speeds=PlaybackSpeed.options
    val selected=speeds.indexOf(library.playbackSpeed)
    // 与播放器菜单同一套选项按钮：整行左对齐的胶囊，当前值用强调色标出。
    val picker=TvDialog(this,"全局默认倍速").choices(speeds.map(PlaybackSpeed::label),selected) { index ->
        library.playbackSpeed=speeds[index]
        if(inPlayer) player?.setPlaybackSpeed(library.playbackSpeed)
        if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "倍速 " else "默认倍速  ·  ")+PlaybackSpeed.label(library.playbackSpeed)
        if(inPlayer) updatePlaybackText()
    }
    picker.button("取消", icon=R.drawable.ic_menu_close)
    speedDialog=picker
    picker.show(anchor) {
        speedDialog=null
        if(anchor.attachedToWindow) { anchor.requestFocus(); if(inPlayer) showHud() }
    }
}
internal fun MainActivity.showQualityPicker(anchor: TextView,inPlayer: Boolean) {
    val options=PlaybackQuality.options
    val picker=TvDialog(this,"全局默认清晰度").choices(options.map { "${it}P" },options.indexOf(library.maxQuality)) { index ->
        val target=options[index]; val changed=library.maxQuality!=target
        library.maxQuality=target
        // 用户手动改全局默认：会话里的自动降档与预算一起作废（见 `SessionPlaybackPolicy`）。
        sessionPlayback.onManualQualityChange()
        if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "清晰度 " else "默认清晰度  ·  ")+"${target}P"
        if(inPlayer && changed) playEpisode(episodeIndex,currentPosition(),player?.playWhenReady ?: requestedAutoplay)
    }
    picker.button("取消", icon=R.drawable.ic_menu_close)
    picker.show(anchor)
}
/**
 * 播放器底部的选集面板（遥控器「↓」打开）。
 *
 * **打开面板不暂停播放**：选集是一层贴底的浮层，画面与声音照常走，用户挑完再切集。
 * 因此这里不动播放状态，关闭时也不需要「恢复播放」——只把焦点还给底部控制条 / 下拉箭头，
 * 并按打开前的可见性恢复浮层。播放位置照旧定期落盘（[saveProgress] 由播放进度回调负责）。
 */
internal fun MainActivity.showEpisodePanel(anchor: View) {
    val data=detail ?: return
    if(episodePanel!=null) return
    val ticket=generation
    val overlayWasVisible=hud.visibility==View.VISIBLE
    // 选集面板也要知道在放哪一集：顶部那条信息是常驻的，这里只要保证它按当前页刷新一遍。
    syncPlaybackInfo()
    // 面板内容与简介页共用同一个 EpisodeGrid，只有宿主不同（贴底弹窗 / 页面末尾），
    // 翻页方式一致：左右到行两端、上下到第一行 / 最后一行到边缘翻页。
    val grid=EpisodeGrid(this,data.episodes.size,episodeIndex,player=true,
        { selected ->
            if(selected==episodeIndex) episodePanel?.dismiss()
            else { episodePanel?.dismiss(); playEpisode(selected) }
        },pageSize=library.episodePageSize,edgeTurn=library.episodeEdgeTurn)
    grid.fillHeight=true
    // 竖屏短剧全屏把画面转 90° 时，选集面板（独立窗口）要按同一个角度摆；
    // 不转的时候传 0f，走的还是原来那条「贴底整宽」的路，电视上一个像素都不变。
    val spin=if(autoFullscreenRotated()) fullscreenSpinDirection() else 0f
    val next=EpisodePanel(this,grid,spin) {
        episodePanel=null
        // 面板收起了（选择完成 / 返回键 / 无操作自动消失）：计时器一并停掉。
        cancelOverlayAutoHide()
        // 收面板之后把控制条按打开前的样子还回去：推荐页（触屏）现在用的是同一条控制条，
        // 因此这里从「只认 player」放宽到「在播放页」（见 [onPlaybackPage]）。
        if(foreground && generation==ticket && onPlaybackPage()) {
            if(anchor.attachedToWindow) anchor.requestFocus()
            if(overlayWasVisible) showHud() else hidePlaybackOverlay()
        }
    }
    episodePanel=next; next.show()
    // 面板开着也算「浮层唤出」：顶部四项临时全显（见 [syncPlaybackInfo]）。
    syncPlaybackInfo()
    // 面板是独立窗口，按键 / 触摸不经过 Activity，因此把「有人在操作」的回调直接挂给它
    // （见 [EpisodePanel] 的 `onActivity`）。无操作到点之后与按返回键一样：面板收起、焦点还回去。
    next.onActivity={ scheduleOverlayAutoHide() }
    scheduleOverlayAutoHide()
}
internal fun MainActivity.formatTime(millis: Long): String { val seconds=(millis.coerceAtLeast(0)/1000); return "%02d:%02d".format(seconds/60,seconds%60) }
/** 播放器时间轴：爆米花固定 hh:mm:ss，短剧也只有几分钟也不会跳字宽。 */
internal fun MainActivity.formatSpan(millis: Long): String {
    val seconds=millis.coerceAtLeast(0)/1000
    return "%02d:%02d:%02d".format(seconds/3600,(seconds%3600)/60,seconds%60)
}
internal fun MainActivity.saveProgress(completed: Boolean=false) {
    if(resetting) return
    // 推荐播放界面**不写观看历史**（需求 3）：只有进入正式播放界面才加入历史。
    // 这一条是全局守卫，因为进度落盘有好几条路径（tick 每 5 秒、onPause、切集、退出），
    // 逐处判断迟早会漏掉一处。
    if(screen=="recommend") return
    val p=player ?: return; val data=detail ?: return; if(!playbackReady || episodeIndex !in data.episodes.indices) return
    val duration=p.duration.coerceAtLeast(0)
    // 时长还没出来（刚换集、刚起播）：先不写。写一条 duration=0 的记录会让
    // 「继续观看」把这一集算成 0 秒，下次从这一集开头重来。
    if(duration<=0) return
    val progress=WatchProgress(data.series,data.episodes[episodeIndex],episodeIndex,p.currentPosition.coerceAtLeast(0),duration,completed || p.playbackState==Player.STATE_ENDED,System.currentTimeMillis())
    library.save(progress)
    syncProgress(progress,data.episodes.size)
    recommendHistoryPending=false
}
/**
 * 离开播放界面：暂停、卸掉画面与这一集的资源，但**保留播放器实例**。
 *
 * 为什么不再 `release()`：`ExoPlayer.Builder(...).build()` 会走
 * `AudioManager.getDevices()` / `MediaCodecList` 这些 Binder 调用，在电视盒子上可以卡几秒，
 * 而它原本跑在主线程（见 `playEpisode` 的 `work` 回调）。线上那条
 * `AudioManager.getDevices()` 的 ANR 就是它。播放器改成进程内复用之后，
 * `build()` 一个进程只发生一次，退出播放再进也不必重建解码器，切换还更快。
 *
 * 真正要销毁实例的只有两处：Activity 销毁（[releasePlayer]）与切换解码方式
 * （`applyDecoderMode`，因为渲染器是构造期定的）。
 *
 * @param keepSession 只是**退到后台**（息屏 / 切到别的应用）时为 true：画面与传输照旧收掉，
 * 但本次播放会话的降级结论（有效清晰度上限 / 预算）保留——`onResume` 会按同一集、
 * 同一位置接着播，拿刚被证明放不了的上限再撞一次只会多一次黑屏 + 一条重复提示
 * （见 [com.hgtv.star.core.SessionPlaybackPolicy.onLeavePlaybackToBackground]）。
 * 真正离开播放页时保持默认 false。
 */
internal fun MainActivity.detachPlayerSurface(keepSession: Boolean = false) {
    // 作废所有认会话号的在途回调（监听器、延迟清理、取地址）。
    playbackSession++
    disarmPlaybackWatchdog(); disarmFrameStallWatchdog()
    // 整棵树马上会被页面重建换掉；调试面板在新页面上由 [reattachDebugOverlay] 重新挂。
    hideDebugOverlay()
    cancelRecovery(); cancelPrefetch(); mediaSession?.deactivate(); tvTools.close()
    dismissPlaybackMenu()
    // 长按倍速：手指可能是在这一页按下的、UP 落在了别的页面（返回键 / 手势条）。
    // 这里把速度还给设置里那一档、把「2 倍速播放中」那块标签摘掉，下一集不会带着 2 倍速起播。
    setPlaybackBoost(false)
    // 离开播放页：播放设置面板的缓存一并丢掉（见 [dropPlaybackMenuCache]）。
    // 面板里三十来个 View 只在播放页有意义，留着只会白占内存。
    dropPlaybackMenuCache()
    val wasForeground=foreground; foreground=false
    val oldPanel=episodePanel; episodePanel=null; oldPanel?.dismiss(); foreground=wasForeground
    speedDialog?.dismiss(); speedDialog=null
    // 「全剧已播完 → 换一部接着看」那一层：离开播放界面（返回 / 息屏 / 进别的剧）时要一起收掉，
    // 否则它会浮在新页面上。
    finishedPanel?.dismiss(); finishedPanel=null
    finishedNotice?.dismiss(); finishedNotice=null
    // 推荐页的信息浮层与切换过渡黑场也属于播放页这一层：离开时一起摘掉，否则会浮在新页面上。
    dismissRecommendOverlay()
    dismissRecommendCover()
    cancelOverlayAutoHide(); main.removeCallbacks(seekRunnable); pendingSeek=null
    restoreBufferingUi()
    playedItemPending=false; playedItemId=""
    playerListener?.let { player?.removeListener(it) }; playerListener=null
    // 先给弹幕层停表并清空这一集：绘制循环不再回调播放器，下面 stop / clearMediaItems
    // 期间即使还有一帧在排队，也只会画到空数据，不会读到播放器的中间状态。
    danmakuView?.let { it.stopClock(); it.clear() }
    // 保留实例，但把解码器与音频输出让出去：`stop()` 会卸载媒体、释放 MediaCodec，
    // 比 `release()` 轻得多，也不会再碰 AudioManager。
    //
    // 列表也一并清空：下次进播放页会整条替换（`setMediaSource`），留着旧列表只会让
    // 已经关闭的 `VideoDataSource` 还挂在播放器上。清空之后旧项不可能再被读，
    // 下面才可以放心关它们持有的视频与传输。
    runCatching { player?.pause(); player?.stop(); player?.clearMediaItems() }
    // 列表已经空了：队列项与上一集的资源现在都可以直接关。
    dropQueuedEpisode(); forceRetirePrevious()
    // 极端情况下没能当场关闭的资源，在这里统一收尾。
    orphanResources.forEach { (v,h) -> v?.let { runCatching { it.close() } }; closeTransport(h) }
    orphanResources.clear()
    video?.close(); video=null; playerView?.player=null; playerView=null; playbackReady=false
    // 离开播放页：本次会话的降级状态（有效上限 / 预算）全部作废。
    // 不清的话，下一部剧会继承上一部降下来的上限——而那是「上一台机器这一刻放不了」的结论，
    // 与这部剧毫无关系。提示去重表同样清掉：新的一部剧该有自己的第一次提示。
    //
    // 退到后台（`keepSession`）是例外：那不是离开这一集，回来的还是同一集同一位置，
    // 结论当然还成立。见 [com.hgtv.star.core.SessionPlaybackPolicy.onLeavePlaybackToBackground]。
    if(keepSession) sessionPlayback.onLeavePlaybackToBackground() else {
        sessionPlayback.onUserManualChange()
        playbackToasts.clear()
        // 会话已经清空，记着「这是哪一部剧的结论」也没有意义了（下次进播放页重新判定）。
        sessionSeriesId=""
    }
    // 在途的弹幕请求作废，弹幕层连同它的轨道一起丢掉。
    danmakuToken.incrementAndGet(); danmakuFor=""; danmakuLoaded=false; danmakuCount=-1; danmakuView=null
    // 顶部那条信息也随播放页一起收掉（root 会被清掉，这里只是让引用不再指向活动视图）。
    playbackInfo=null; playbackEpisode=null; playbackSpeedText=null; playerClock=null
    // 画面之外那一层同理：它随 root 一起没了，引用留着只会让下一次「跟着画面转」
    // 去改一个已经不在树上的 View（见 `applyOverlayGeometry`）。
    overlayLayer=null
    closeTransport(playbackHttp); playbackHttp=null
    // 常亮统一由状态推导：这一刻 `player` 已经 pause、页面也即将换掉，
    // 判据两样都不成立，自然就放开了（见 `syncKeepScreenOn`）。
    syncKeepScreenOn()
}

/** 真正销毁播放器实例：只在 Activity 销毁、清除本地数据与切换解码方式时调用。 */
internal fun MainActivity.releasePlayer() {
    disarmPlaybackWatchdog()
    detachPlayerSurface()
    // 与 [warmPlayer] 的 io 线程抢同一把锁：预热可能正在 `newPlayer()` 途中，
    // 不加锁就会出现「刚 release 完又被预热装回来一个」的孤儿播放器（它已经挂不上 Surface，
    // 却还占着解码器与音频输出）。
    synchronized(playerBuildLock) {
        runCatching { player?.release() }
        player=null
        // 下一个实例要重新配置一次（见 [MainActivity.configuredPlayer]）。
        configuredPlayer=null
        // 置位之后再进 [warmPlayer] 的线程会直接放弃：见 [playerDisposed]。
        playerDisposed=true
    }
}
