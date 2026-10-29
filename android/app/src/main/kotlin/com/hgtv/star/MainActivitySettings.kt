// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import android.app.Activity
import android.graphics.Typeface
import android.view.*
import android.widget.*
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import com.google.android.exoplayer2.*
import com.hgtv.star.core.*
import com.hgtv.star.tv.ButtonSize
import com.hgtv.star.tv.ButtonTone
import com.hgtv.star.tv.SettingSlider
import com.hgtv.star.tv.IconButton
import com.hgtv.star.tv.ToggleChip
import com.hgtv.star.tv.TvButton
import com.hgtv.star.tv.TvDialog
import com.hgtv.star.tv.TvSettingButton
import com.hgtv.star.tv.TvSwitchRow
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.tv.TvTheme
import com.hgtv.star.list.showCatalog
import com.hgtv.star.findTagged
import com.hgtv.star.compat.attachedToWindow
import com.hgtv.star.compat.lowEndTv

// 本文件是从 MainActivity.kt 拆出来的「设置页：外观主题、播放偏好、账号与关于」这一块。
// 这些成员仍然属于 MainActivity，只是以扩展成员（MainActivity.xxx）的形式放在这里，
// 逻辑与拆之前逐行一致；改这里等于改 Activity 本体。

internal fun MainActivity.settings(parent: LinearLayout) {
    // —— 四个大项 ——
    //
    // 设置页按 [SETTINGS_TABS] 分成四块：播放交互 / 外观导航 / 内容推荐 / 系统高级。
    // 大项的芯片在**顶部导航栏**里（见 `ListPage.showCatalog` 的 TAB_SETTINGS 那一段），
    // 这一页只负责四块内容本身，因此这里不再重复一行「设置」标题。
    //
    // 四块内容**一次全建好**，只有当前那一个可见，其余 `GONE`：
    // 切换大项因此只是换一次可见性——不重建页面、不重新联网、不动已经调好的滑块，
    // 而 `GONE` 的子树既不测量也不参与焦点，四块同时存在不会互相抢焦点。
    val active=settingsTab.coerceIn(0,SETTINGS_TABS.lastIndex)
    settingsTab=active
    parent.addView(text("偏好自动保存，下一部剧继续使用。",14f,TvStyle.muted).apply { setPadding(0,dp(10),0,dp(20)) })
    val groupBand=dp(TvStyle.SOFT_BAND_LARGE)
    /** 一块设置面板：大号凸起底板，内容四周留出柔光带。 */
    fun groupPanel(group: LinearLayout) {
        group.setPadding(dp(24)+groupBand,dp(20)+groupBand,dp(24)+groupBand,dp(20)+groupBand)
        group.background=softPanel(TvStyle.surfaceHigh,TvStyle.RADIUS_DIALOG,band=TvStyle.SOFT_BAND_LARGE,depth=1.05f)
    }
    /** 一个大项的内容列（带 `settings-page-<n>` 标记，切大项按它取块、按它找回焦点）。 */
    fun pageColumn(index: Int)=column().apply {
        clipChildren=false; clipToPadding=false
        tag=SETTINGS_TAG_PAGE_PREFIX+index
        visibility=if(index==active) View.VISIBLE else View.GONE
    }
    /** 大项里的一块整宽面板；[gap] 是它与上一块之间的距离。 */
    fun panel(page: LinearLayout,gap: Int=TvStyle.GAP_GROUP): LinearLayout {
        val group=column()
        groupPanel(group)
        page.addView(group,lp(-1,-2).apply { topMargin=dp(gap) })
        return group
    }
    fun heading(group: LinearLayout,title: String,subtitle: String) {
        group.addView(text(title,18f,TvStyle.text).apply { setTypeface(null,Typeface.BOLD) },lp(-2,-2))
        group.addView(text(subtitle,14f,TvStyle.muted).apply { setPadding(0,dp(TvStyle.GAP_TIGHT),0,dp(TvStyle.GAP_BLOCK)) })
    }
    /**
     * 设置项就是整行左对齐的常规按钮：高度、圆角、焦点反馈与全站按钮完全一致，只是文字靠左。
     * 行距由 GAP_INLINE 统一给出，不再靠每一项自己写 bottomMargin。
     */
    fun setting(group: LinearLayout,label: String,action: ()->Unit): TvButton {
        val view=TvSettingButton(this,label,ButtonSize.NORMAL,ButtonTone.SECONDARY,action=action)
        group.addView(view,lp(-1,-2).apply { if(group.childCount>1) topMargin=dp(TvStyle.GAP_INLINE) })
        return view
    }
    /**
     * 设置项里的整行开关（左名称 + 右滑块）：与 [setting] 同一行高、同一行距。
     *
     * [TvSwitchRow] 本来就继承 [TvSettingButton] 的常规档，因此整栏里「按钮」与「开关」
     * 看起来是同一块料，不会出现一行高一行矮。
     */
    fun switchSetting(group: LinearLayout,view: TvSwitchRow): TvSwitchRow {
        group.addView(view,lp(-1,-2).apply { if(group.childCount>1) topMargin=dp(TvStyle.GAP_INLINE) })
        return view
    }
    /** 面板里的一行（横向）：上下间距由调用方给，行内格距统一 GAP_INLINE。 */
    fun lineRow(group: LinearLayout,gap: Int=TvStyle.GAP_INLINE): LinearLayout {
        val line=row().apply { clipChildren=false; clipToPadding=false }
        group.addView(line,lp(-1,-2).apply { if(group.childCount>1) topMargin=dp(gap) })
        return line
    }
    /** 空的列位：末行不满时占住列宽，前面那一枚不会被拉成半屏宽。 */
    fun padLine(line: LinearLayout,count: Int) {
        repeat(count) { index ->
            line.addView(Space(this),lp(0,1).apply {
                weight=1f
                if(line.childCount>1) leftMargin=dp(TvStyle.GAP_INLINE)
            })
        }
    }
    /**
     * 整栏宽的一行：项名与滑轨**合成同一枚控件**（[SettingSlider]）。
     *
     * 高度与上面那排按钮同一档（[TvStyle.BUTTON_NORMAL] + 两条柔光带 = 60dp），
     * 项名的左边缘、右侧读数所在的右边缘都与同一栏的设置项落在同一条竖线上——
     * 因此这一栏里「按钮 / 开关 / 滑动条」读起来是同一种行，中间不会多出一条缝。
     *
     * 与播放设置面板里的弹幕两条共用同一份本机偏好，因此设置页调好之后
     * 进播放界面立刻就是新的字号 / 透明度。
     */
    fun fullSlider(
        group: LinearLayout,label: String,min: Float,max: Float,step: Float,value: Float,
        format: (Float)->String = DanmakuStyle::percent,
        onValue: (Float)->Unit
    ): SettingSlider {
        // 整行就是一枚控件（[SettingSlider]）：项名画在底板内部左段、滑轨接在右段，
        // 不再「外面一行、左边一条文字、右边一条滑轨」。行高、左右内边距、焦点反馈
        // 与同一栏的按钮 / 开关完全一致，因此一栏里三种控件看起来是同一块料。
        val slider=SettingSlider(this,label,min,max,step,value,onValue,format)
        group.addView(slider,lp(-1,-2).apply { topMargin=dp(TvStyle.GAP_INLINE) })
        return slider
    }
    /**
     * 每个大项里的行（自上而下），只用一件事：串上下左右的焦点出口。
     *
     * 以前这一页是「两栏并排、每栏逐行对齐」，焦点出口要一行一行手写，改一处布局就得
     * 把几十条 `nextFocus*Id` 跟着改一遍。现在按大项分块之后，块内都是**整宽的行**，
     * 于是出口全部交给 [wireRows] 按「行号 + 列号」统一算：行内左右相连、行间上下同列。
     */
    val pageRows=Array(SETTINGS_TABS.size) { mutableListOf<MutableList<View>>() }
    fun track(index: Int,vararg views: View) {
        pageRows[index].add(views.filter { it.isFocusable }.toMutableList())
    }
    fun wireRows(index: Int) {
        val rows=pageRows[index]
        rows.forEachIndexed { r,line ->
            if(line.isEmpty()) return@forEachIndexed
            val above=rows.getOrNull(r-1)?.takeIf { it.isNotEmpty() }
            val below=rows.getOrNull(r+1)?.takeIf { it.isNotEmpty() }
            line.forEachIndexed { c,view ->
                view.nextFocusLeftId=(line.getOrNull(c-1) ?: view).id
                view.nextFocusRightId=(line.getOrNull(c+1) ?: view).id
                view.nextFocusUpId=(above?.let { it.getOrNull(c) ?: it.last() } ?: view).id
                // 最后一行按下停在原地：再按也不会跳到大项外面去。
                view.nextFocusDownId=(below?.let { it.getOrNull(c) ?: it.last() } ?: view).id
            }
        }
    }

    /** 两栏并排：左右各一块面板，各自带柔光带；两栏之间用 GAP_GROUP。 */
    fun sidePanel(container: LinearLayout): LinearLayout {
        val group=column()
        groupPanel(group)
        container.addView(group,lp(0,-2).apply {
            weight=1f
            if(container.childCount>1) leftMargin=dp(TvStyle.GAP_GROUP)
        })
        return group
    }
    /** 两列网格里的一格（按钮）：宽度交给权重，同面板的格子左右边缘因此对齐。 */
    fun cellSetting(line: LinearLayout,label: String,action: ()->Unit): TvButton {
        val view=TvSettingButton(this,label,ButtonSize.NORMAL,ButtonTone.SECONDARY,action=action)
        line.addView(view,lp(0,-2).apply { weight=1f; if(line.childCount>0) leftMargin=dp(TvStyle.GAP_INLINE) })
        return view
    }
    /**
     * 两列网格里的一格（滑动条）：与 [cellSetting] 同宽同位。
     *
     * 与 [fullSlider] 的区别只有「占多少宽」：这一条按权重占半行，因此可以和另一格并排。
     * 轨道本身有 `minTrack` 保底（见 `SettingSlider.trackBounds`），半行宽下仍然拖得准。
     */
    fun cellSlider(
        line: LinearLayout,label: String,min: Float,max: Float,step: Float,value: Float,
        format: (Float)->String,onValue: (Float)->Unit
    ): SettingSlider {
        val slider=SettingSlider(this,label,min,max,step,value,onValue,format)
        line.addView(slider,lp(0,-2).apply { weight=1f; if(line.childCount>0) leftMargin=dp(TvStyle.GAP_INLINE) })
        return slider
    }
    /** 两列网格里的一格（开关）：与 [cellSetting] 同宽同位。 */
    fun cellSwitch(line: LinearLayout,view: TvSwitchRow): TvSwitchRow {
        line.addView(view,lp(0,-2).apply { weight=1f; if(line.childCount>0) leftMargin=dp(TvStyle.GAP_INLINE) })
        return view
    }
    /**
     * 两栏并排时把两块面板的底板拉成**同高**。
     *
     * 两栏的行业数往往不同（按键七行、弹幕五行），直接 wrap_content 会让两块底板一高一低，
     * 看起来像矮的那块缺了一角。只在首次布局后量一次：之后两栏内容都不增删行。
     */
    fun equalHeights(container: LinearLayout,panels: List<LinearLayout>) {
        container.addOnLayoutChangeListener(object: View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View,left: Int,top: Int,right: Int,bottom: Int,oldLeft: Int,oldTop: Int,oldRight: Int,oldBottom: Int) {
                val height=panels.maxOfOrNull { it.height } ?: 0
                if(height<=0) return
                v.removeOnLayoutChangeListener(this)
                panels.forEach { panel ->
                    val params=panel.layoutParams as? LinearLayout.LayoutParams ?: return@forEach
                    if(params.height!=height) { params.height=height; panel.layoutParams=params }
                }
            }
        })
    }
    /**
     * 并排两栏的竖向出口（在两栏建完之后赋值，见「按键设置 ｜ 弹幕设置」那一段）。
     *
     * [wireRows] 按「行号 + 列号」算上下左右，前提是各行格数对齐；并排两栏的行业数不同
     * （左栏七行、右栏五行），按行号算会把左栏第五行之后的「下 / 上」串到右栏去。因此那一段
     * 自己把两条竖链重新指一遍，并在这里交给「焦点串联」在 [wireRows] 之后调用。
     */
    var rewireSidePanels: ()->Unit={}

    // ══════════════════ 一、播放交互 ══════════════════
    //
    // 四块：播放偏好（两列）/ 播放显示（一行四格）/ 按键设置与弹幕设置（左右并排）。
    // 顺序按「看剧时最常改的在前」：先定清晰度与倍速，再看画面上显示什么，
    // 最后是并排的两栏——左栏改遥控器键位，右栏调弹幕。
    val playbackPage=pageColumn(0)
    parent.addView(playbackPage,lp(-1,-2))
    // —— 播放偏好：四行两列，全部两格一行 ——
    //
    // 六个「点开选一项」的设置 + 两枚滑块 + 两个开关，两两一行排成四行：
    //   清晰度 / 倍速、画面模式 / 解码、竖屏短剧全屏 / 画面缩放、自动连播 / 快进快退。
    // 左列是「开关」，右列是「数值与画面」，用户扫一列就能改完一类。
    //
    // 滑块也从整宽改成半行：轨道有 `SettingSlider.minTrack` 保底 96dp，
    // 半行宽下百分比与秒数仍然拨得准，面板高度却省掉两条整宽行。
    val playback=panel(playbackPage,gap=TvStyle.GAP_INLINE)
    heading(playback,"播放偏好","所有剧集共用，播放中也可调整")
    val prefRow1=lineRow(playback)
    lateinit var qualityButton: TvButton
    qualityButton=cellSetting(prefRow1,"默认清晰度  ·  ${library.maxQuality}P") { showQualityPicker(qualityButton,false) }
    lateinit var defaultSpeed: TvButton
    defaultSpeed=cellSetting(prefRow1,"默认倍速  ·  ${PlaybackSpeed.label(library.playbackSpeed)}") { showSpeedPicker(defaultSpeed,false) }
    val prefRow2=lineRow(playback)
    lateinit var frame: TvButton
    frame=cellSetting(prefRow2,"画面模式  ·  ${library.frameMode.label}") { showFramePicker(frame,false) }
    // 视频解码：硬件 / 软件。硬件省电、4K 更稳；软件兼容性更好，花屏、绿屏、
    // 音画不同步、黑屏有声这类片源问题通常能绕过。改完下一次起播生效；
    // 正在播放时从播放设置面板改，会当场重建播放器并从当前位置续播。
    lateinit var decoderButton: TvButton
    decoderButton=cellSetting(prefRow2,"视频解码  ·  ${library.decoderMode.label}") { showDecoderPicker(decoderButton,false) }
    // —— 下面两行是「左开关 / 右滑块」——
    // 左列：竖屏短剧全屏、自动连播（都是开关）；
    // 右列：画面缩放、快进快退时长（都是「画面与手感」，调完立刻能感觉到）。
    // 早先画面缩放是一条整宽滑块，右半边全空；现在与另一枚滑块对齐成两列，
    // 面板高度从四条整宽行压到两条，滑轨也仍然有半行宽（`SettingSlider.minTrack` 保底 96dp）。
    val prefRow3=lineRow(playback)
    // 竖屏短剧全屏（左）：竖屏 9:16 放到 16:9 电视上只占中间一条，打开这一项把**画面本身转 90°**，
    // 铺满屏幕、不裁内容（见 `applyVideoGeometry`）。横屏片源不受影响。
    //
    // **只在手机 / 平板上可用**（见 [autoFullscreenAvailable]）：电视是遥控器 + 固定 16:9 大屏，
    // 竖向画面已经由 `applyVideoGeometry()` 按「等比铺满、不裁内容」摆好，再整屏转一层
    // 只会让标题条、控制条、弹幕一起歪过去。这一项是留给会竖着拿 / 系统不转屏的手机、平板的。
    // 形态判定与「设备类型」那一格同源（自动判定 = `detectDeviceForm()` 的打分，
    // 手动选过就以用户选的为准），因此用户在设置里改完「设备类型」重建这一屏之后，
    // 这一格的可用性立刻跟着变。
    val fullscreenAvailable=autoFullscreenAvailable()
    val fullscreenRow=cellSwitch(prefRow3,TvSwitchRow(this,
        // 说明写在**项名里**，不用 `·` 分隔：开关这一款只画项名，`·` 后面那段是给
        // 「点开选一项」的按钮用的（见 `TvSettingButton.applyLabel`），放在这里会看不见。
        if(fullscreenAvailable) "竖屏短剧全屏" else "竖屏短剧全屏（手机、平板可用）",
        // 不可用时**显示为关**：存的值原样留着（换回电视就该恢复），但这一格读起来必须是
        // 「现在没开、也开不了」，而不是「开着却动不了」。
        checked=fullscreenAvailable && library.autoFullscreen,
        onToggle={ on ->
            library.autoFullscreen=on
            // 改完立即落盘。播放层用的「视频画在哪种视图上」是在建 PlayerView 那一刻定的，
            // 因此正在播放时只能重建一次播放层（换集入口就在 Activity 上，带上当前位置续播）。
            if(onPlaybackPage()) playEpisode(episodeIndex,currentPosition(),player?.playWhenReady ?: requestedAutoplay)
        }).apply { tag=SETTINGS_TAG_AUTO_FULLSCREEN })
    if(!fullscreenAvailable) {
        // 与「减少动画」那一格同一套禁用写法（见下面 `reduceRow`）：不进焦点链 + 不响应点击 +
        // 禁用配色，再抬一点整体透明度——3 米外仍要读得清「有这一项、只是这台设备用不上」。
        fullscreenRow.isEnabled=false
        fullscreenRow.isFocusable=false
        fullscreenRow.isFocusableInTouchMode=false
        fullscreenRow.refresh()
        fullscreenRow.alpha=.72f
        fullscreenRow.contentDescription="竖屏短剧全屏，仅手机、平板可用，当前设备类型下不可更改"
    }
    // 画面缩放（右）：**只缩播放画面本身**，弹幕层仍然是整屏坐标（见 `applyVideoScale`）。
    // 1..100 就是百分比读数，与「弹幕大小」共用同一种滑动条。
    val scaleSlider=cellSlider(
        prefRow3,"画面缩放",1f,100f,1f,library.videoScale,
        format={ "${it.toInt()}%" }
    ) { value ->
        library.videoScale=value
        if(onPlaybackPage()) applyVideoScale()
    }
    val prefRow4=lineRow(playback)
    // 自动连播（左）：关掉时把已经挂进队列的下一集摘掉，否则它还会在片尾自己播下去。
    val autoNextRow=cellSwitch(prefRow4,TvSwitchRow(this,"自动连播",checked=library.autoNext,onToggle={ on ->
        library.autoNext=on
        if(!on) dropQueuedEpisode()
    }))
    // 快进快退时长（右）：播放界面按左右键单次跳过的秒数，2..20 秒。
    // 连按加速按同一比例走（见 `MainActivity.seek`）：10 秒档是「连按 5 次后每次 30 秒」，
    // 20 秒档就是 60 秒，2 秒档就是 6 秒——改档不会让连按手感错位。
    val seekSlider=cellSlider(
        prefRow4,"快进快退",2f,20f,1f,library.seekStep.toFloat(),
        format={ "${it.toInt()} 秒" }
    ) { value -> library.seekStep=value.toInt() }
    track(0,qualityButton,defaultSpeed)
    track(0,frame,decoderButton)
    track(0,fullscreenRow,scaleSlider)
    track(0,autoNextRow,seekSlider)

    // —— 播放显示：画面左上角那一条里的四项 ——
    // 剧集标题 / 当前集数 / 时间 / 网速四项各自开关，排成一行四格，**默认四格全关**。
    // 关掉不等于看不见：轻点画面 / 唤出任何一块播放菜单时，四项临时全部显示
    //（用户正在读「这是哪一集、播到哪儿」），收起之后再回到这里的选择——见 `syncPlaybackInfo`。
    // 它们只决定「常驻的那一条要不要显示这一块」，不改变页面结构，因此改完立刻落盘即可，
    // 不重建整页：回到播放页时 `syncPlaybackInfo` 会按新设置摆一次。
    val playInfoPanel=panel(playbackPage)
    heading(playInfoPanel,"播放显示","默认全关 · 唤出菜单或轻点时四项全部显示")
    fun playInfoOn(key: String)=when(key) {
        "title" -> library.playerTitle
        "episode" -> library.playerEpisode
        "clock" -> library.playerClock
        else -> library.playerSpeed
    }
    fun setPlayInfo(key: String,on: Boolean) {
        when(key) {
            "title" -> library.playerTitle=on
            "episode" -> library.playerEpisode=on
            "clock" -> library.playerClock=on
            else -> library.playerSpeed=on
        }
        syncPlaybackInfo()
    }
    val playRow=lineRow(playInfoPanel)
    val playCells=PLAY_INFO_ENTRIES.mapIndexed { index,(key,name) ->
        val cell=TvSwitchRow(this,name,checked=playInfoOn(key),onToggle={ on -> setPlayInfo(key,on) })
        cell.tag=SETTINGS_TAG_PLAY_PREFIX+key
        playRow.addView(cell,lp(0,-2).apply { weight=1f; if(index>0) leftMargin=dp(TvStyle.GAP_INLINE) })
        cell
    }
    track(0,*playCells.toTypedArray())
    // —— 播放界面自动消失：播放设置菜单 / 选集面板 / 底部控制条「无操作几秒收起」——
    // 到点之后与按返回键完全一样：播放设置收起、选集面板收起并把焦点还给控制条 / 下拉箭头、
    // 控制条收起（含暂停状态）。计时从**最后一次操作**算起（方向键、确定、触摸都算），
    // 因此调到「15 秒」也不会在用户正按键的时候被收走。
    //
    // 项名前先放一行小字：滑块上只有一个读数，读不出「收起来的是哪几块、收了之后会怎样」，
    // 而这两句正是用户调这一项时最需要先看到的（见需求「上面加一行小字解释」）。
    playInfoPanel.addView(text(
        "播放设置、选集面板、底部控制条无操作后自动收起，与按返回键相同。",
        14f,TvStyle.muted
    ).apply { setPadding(0,dp(TvStyle.GAP_INLINE),0,0) },lp(-1,-2))
    val panelTimeout=fullSlider(
        playInfoPanel,"播放界面自动消失",
        PLAYER_PANEL_TIMEOUT_RANGE.first.toFloat(),PLAYER_PANEL_TIMEOUT_RANGE.last.toFloat(),1f,
        library.playerPanelTimeout.toFloat(),
        format={ "${it.toInt()} 秒" }
    ) { value -> library.playerPanelTimeout=value.toInt() }
    track(0,panelTimeout)

    // —— 按键设置 ｜ 弹幕设置：左右并排 ——
    //
    // 两块都只作用于「看剧时」，行数也相近（七行 / 五行），并排之后这一大项不再
    // 一路往下拉长：**左栏改键位、右栏调弹幕**，一眼能同时看到。
    //
    // 两栏的头部同高（标题行 [headHeight] + 两行说明），因此下面第一行设置落在同一条横线上。
    val mid=row().apply { gravity=Gravity.TOP; clipChildren=false; clipToPadding=false }
    playbackPage.addView(mid,lp(-1,-2).apply { topMargin=dp(TvStyle.GAP_GROUP) })
    val headHeight=dp(TvStyle.BUTTON_COMPACT+2*TvStyle.SOFT_BAND)
    /** 面板头部：标题一行 + 说明两行；[trailing] 是右侧那枚圆钮（「恢复默认」）。 */
    fun panelHead(group: LinearLayout,title: String,subtitle: String,trailing: View?=null) {
        val head=row().apply { gravity=Gravity.CENTER_VERTICAL }
        head.addView(text(title,18f,TvStyle.text).apply { setTypeface(null,Typeface.BOLD) },lp(0,-2).apply { weight=1f })
        if(trailing!=null) head.addView(trailing,lp(-2,-2))
        group.addView(head,lp(-1,headHeight))
        group.addView(text(subtitle,14f,TvStyle.muted).apply {
            minLines=2; maxLines=2; ellipsize=TextUtils.TruncateAt.END
            setPadding(0,dp(TvStyle.GAP_TIGHT),0,dp(TvStyle.GAP_BLOCK))
        },lp(-1,-2))
    }
    // 按键（左）：标题右侧「恢复默认」圆钮 + 六行设置项（上 / 下 / 左 / 右 / 菜单 / 确定）。
    //
    // 作用域是**播放界面**：浏览页面里上下左右就是焦点移动，是遥控器的基本手感，不能改；
    // 返回键也不在其中——它在任何绑定下都必须是退出播放的出口，否则用户可能把自己锁在播放界面里。
    // 这两条边界写在 [com.hgtv.star.core.KeyMap] 的注释里，改这里之前先读那一段。
    val keys=sidePanel(mid)
    // 动作先挂一个空壳：真正要做的事（恢复绑定 + 就地刷新六格文案）写在下面
    // [refreshKeyLabels] 之后——Kotlin 的局部函数不能前向引用，圆钮又要建在头部里。
    var restoreAction: ()->Unit={}
    val restore=IconButton(
        this,R.drawable.ic_action_restore,restoreKeysLabel(),TvStyle.BUTTON_COMPACT
    ) { restoreAction() }
    panelHead(keys,"按键设置","播放界面：六个键各做什么",restore)
    var keyCells: List<TvButton> = emptyList()
    /** 六格的文案：改完一个键就地刷新，不重建整页（焦点仍停在刚点的那一格上）。 */
    fun refreshKeyLabels() {
        keyCells.forEachIndexed { index,cell ->
            val label=keyRowLabel(PlayerKey.entries[index])
            cell.setLabel(label); cell.contentDescription=label
        }
        restore.contentDescription=restoreKeysLabel()
    }
    fun keyCell(key: PlayerKey): TvButton {
        val cell=setting(keys,"") {}
        cell.setOnClickListener { showKeyActionPicker(key,cell) { refreshKeyLabels() } }
        return cell
    }
    keyCells=PlayerKey.entries.map { keyCell(it) }
    // 「恢复默认按键」：改回出厂绑定（上 / 菜单 = 播放设置、下 = 选集、左 = 快退、
    // 右 = 快进、确定 = 播放 / 暂停）。没有文字读数，当前是否默认写在无障碍描述里。
    restoreAction={
        library.keyMap=KeyMap.DEFAULT
        refreshKeyLabels()
        showHint("已恢复默认按键")
    }
    refreshKeyLabels()
    // 弹幕（右）：显示方式 / 速度 / 聚合 / 字号 / 透明度。五行**始终显示**（不看是否隐藏弹幕）：
    // 设置页是**配置**而不是预览，用户先把字号调好、再决定要不要显示弹幕是常见顺序，
    // 一选隐藏就整栏塌下去，反而像「这些设置丢了」。
    val danmaku=sidePanel(mid)
    lateinit var danmakuSetting: TvButton
    lateinit var danmakuSpeedSetting: TvButton
    lateinit var danmakuScaleRow: SettingSlider
    lateinit var danmakuAlphaRow: SettingSlider
    panelHead(danmaku,"弹幕设置","显示方式 / 速度 / 聚合 / 字号 / 透明度")
    danmakuSetting=setting(danmaku,danmakuLabel("弹幕显示  ·  ")) {}
    danmakuSetting.setOnClickListener { showDanmakuPicker(danmakuSetting,false) }
    danmakuSpeedSetting=setting(danmaku,"弹幕速度  ·  ${library.danmakuSpeed.label}") {
        showDanmakuSpeedPicker(danmakuSpeedSetting,false)
    }
    // 相同弹幕聚合：默认开启，与之前的行为一致；关闭后逐条原样滚动，不再合并、不再带 ×N。
    val danmakuMergeRow=switchSetting(danmaku,TvSwitchRow(this,"相同弹幕聚合",checked=library.danmakuMerge,onToggle={ on ->
        library.danmakuMerge=on
        danmakuView?.mergeRepeats=on
    }))
    // 聚合条数：同一句在聚合窗口内达到几条才合并（2..10，默认 2 = 出现两次就合并）。
    // 不到这个条数的照常逐条滚过去、不带 ×N、也不进中央；达到才合成一条。
    // 关掉「相同弹幕聚合」时这一行仍然显示：设置页是配置页，先把阈值调好再打开聚合是常见顺序。
    val danmakuMergeMinRow=fullSlider(
        danmaku,"聚合条数",
        DANMAKU_MERGE_MIN_RANGE.first.toFloat(),DANMAKU_MERGE_MIN_RANGE.last.toFloat(),1f,
        library.danmakuMergeMin.toFloat(),
        format={ "${it.toInt()} 条" }
    ) { value ->
        library.danmakuMergeMin=value.toInt(); danmakuView?.mergeMinCount=value.toInt()
    }
    // 弹幕大小：整行一条滑块（半屏宽仍足够拨准，读数在轨道右端）。
    danmakuScaleRow=fullSlider(danmaku,"弹幕大小",DanmakuStyle.SCALE_MIN,DanmakuStyle.SCALE_MAX,DanmakuStyle.SCALE_STEP,library.danmakuScale) { value ->
        library.danmakuScale=value; danmakuView?.textScale=value
    }
    // 弹幕透明度：整行一条滑块。
    danmakuAlphaRow=fullSlider(danmaku,"弹幕透明度",DanmakuStyle.ALPHA_MIN,DanmakuStyle.ALPHA_MAX,DanmakuStyle.ALPHA_STEP,library.danmakuAlpha) { value ->
        library.danmakuAlpha=value; danmakuView?.textAlpha=value
    }
    // 两栏底板同高：左栏多一行，右边补齐，底边落在同一条横线上。
    equalHeights(mid,listOf(keys,danmaku))
    // 行登记：两栏按「行号」并成一行，左栏是「恢复默认」+ 六行按键、右栏是弹幕六行。
    val danmakuColumn=listOf<View>(danmakuSetting,danmakuSpeedSetting,danmakuMergeRow,danmakuMergeMinRow,danmakuScaleRow,danmakuAlphaRow)
    val keyColumn=listOf<View>(restore)+keyCells
    keyColumn.forEachIndexed { i,v -> track(0,*listOfNotNull(v,danmakuColumn.getOrNull(i)).toTypedArray()) }
    danmakuColumn.drop(keyColumn.size).forEach { track(0,it) }
    /**
     * 并排两栏的竖向出口要**各自成链**，不能按「行号 + 列号」算。
     *
     * 左栏七行、右栏六行：按行号算的话，左栏第六行之后的「上 / 下」会串到右栏去。
     * 这里把两条链重新指一遍，并把行号对齐的六行横向接通。
     */
    rewireSidePanels={
        keyColumn.forEachIndexed { i,v ->
            v.nextFocusUpId=if(i==0) playCells.first().id else keyColumn[i-1].id
            v.nextFocusDownId=keyColumn.getOrNull(i+1)?.id ?: v.id
            v.nextFocusLeftId=v.id
        }
        danmakuColumn.forEachIndexed { i,v ->
            v.nextFocusUpId=if(i==0) playCells.last().id else danmakuColumn[i-1].id
            v.nextFocusDownId=danmakuColumn.getOrNull(i+1)?.id ?: v.id
            v.nextFocusRightId=v.id
        }
        keyColumn.forEachIndexed { i,left ->
            val right=danmakuColumn.getOrNull(i) ?: return@forEachIndexed
            left.nextFocusRightId=right.id
            right.nextFocusLeftId=left.id
        }
    }

    // ══════════════════ 二、外观导航 ══════════════════
    //
    // 三块：布局启动（两列）/ 导航显隐（四列两行）/ 主题外观（四列五行）。
    val appearancePage=pageColumn(1)
    parent.addView(appearancePage,lp(-1,-2))
    // —— 布局启动：两列 ——
    // 「每行卡片」是列表排版，「打开应用进入推荐」是启动落点、「返回顶部」是列表页那枚浮层
    // 何时出现，三件都是「一进应用 / 一进列表就看到」的设置。
    val layoutPanel=panel(appearancePage,gap=TvStyle.GAP_INLINE)
    heading(layoutPanel,"布局启动","列表怎么排、进应用先看哪一页、回到顶部何时出现")
    val layoutRow=lineRow(layoutPanel)
    // 每行卡片数（全局）：4 / 5 / 6 / 7 / 8。首页剧架、榜单 / 分类 / 搜索 / 收藏 / 历史 / 我的 共用。
    // 列数变少时卡片变宽，封面与卡片文字一起放大（见 HomeScreen.slotWidth / textScale
    // 与 SeriesGrid.gridTextScale）。改完回到列表即按新列数排版。
    lateinit var homeColumnsButton: TvButton
    homeColumnsButton=cellSetting(layoutRow,"每行卡片  ·  ${library.homeColumns} 张") { showHomeColumnsPicker(homeColumnsButton) }
    // 打开应用是否进入推荐：与「导航显隐 → 推荐」联动（见 Library.setNavHidden / launchRecommend）。
    // 关掉「推荐」显示时这一项会被一起关掉；反过来打开这一项时，导航栏那枚入口也会恢复。
    val launchRecommendRow=cellSwitch(layoutRow,TvSwitchRow(this,"打开应用进入推荐",checked=library.launchRecommend,onToggle={ on ->
        library.launchRecommend=on
        // 打开这一项时会把导航栏那枚推荐入口一起恢复（见 Library.launchRecommend），
        // 「导航显隐」那一格的状态跟着变了，因此整页重建一次，并把焦点按回这一行。
        settingsPendingFocus=SETTINGS_TAG_LAUNCH_RECOMMEND
        showCatalog()
    }).apply { tag=SETTINGS_TAG_LAUNCH_RECOMMEND })
    track(1,homeColumnsButton,launchRecommendRow)
    // 返回顶部按钮：与上面两格**同宽同高**——同一套 [cellSetting]，右半边留空（[padLine]）。
    // 整宽一行虽然不折行，但宽度是上面那两格的两倍，一栏里会读成「这一项更大」，
    // 焦点框跳过去时也像换了一个档位；因此宁可让读数取短说法（见 `backToTopShort`）。
    val backToTopRow=lineRow(layoutPanel)
    lateinit var backToTopButton: TvButton
    backToTopButton=cellSetting(backToTopRow,"返回顶部  ·  "+backToTopShort(library.backToTop)) {
        showBackToTopPicker(backToTopButton)
    }
    padLine(backToTopRow,1)
    backToTopButton.tag=SETTINGS_TAG_BACK_TO_TOP
    track(1,backToTopButton)

    // —— 导航显隐：十二枚开关排成一张四列网格（三整行）——
    // 前五枚是导航栏的圆形入口（推荐 / 搜索 / 分类 / 榜单 / 我的），关掉那一页就不建它；
    // 后七枚是首页的栏目（本机记录三栏 + 热播四栏），关掉首页就不铺那一栏。
    //
    // 两类开关放在同一张网格里，是因为它们回答的是同一个问题——「这个入口 / 这一栏要不要显示」；
    // 分成两个小标题、两张网格只会让同一件事读成两套体系，还白占一屏高度。
    // 排成四列正好三整行（4 + 4 + 4），末行不留空位。
    //
    // 设置不在这一组里，始终显示（关掉设置就再也回不来）。
    val navPanel=panel(appearancePage)
    heading(navPanel,"导航显隐","关掉的入口不建控件、关掉的栏目不铺首页，设置始终显示")
    val featureEntries=NAV_ENTRIES+HOME_ENTRIES
    featureEntries.chunked(NAV_GRID_COLUMNS).forEachIndexed { rowIndex,line ->
        // 同一张网格里的行间距比普通行距小一档：八枚开关是一整块，不该读成两段。
        val navRow=lineRow(navPanel,gap=if(rowIndex==0) TvStyle.GAP_INLINE else TvStyle.GAP_TIGHT)
        val cells=line.mapIndexed { index,(key,name) ->
            // 两类的落盘与副作用不同：导航入口要重建整页（那一枚建 / 不建），
            // 首页栏目只是首页少铺一栏，就地落盘、回到首页即生效。
            val isNav=key in NAV_KEYS
            val cell=TvSwitchRow(this,name,
                checked=if(isNav) key !in library.hiddenNav else key !in library.hiddenHome,
                onToggle={ shown ->
                    if(isNav) {
                        library.setNavHidden(key,!shown)
                        settingsPendingFocus=SETTINGS_TAG_NAV_PREFIX+key
                        showCatalog()
                    } else {
                        library.setHomeHidden(key,!shown)
                    }
                })
            cell.tag=(if(isNav) SETTINGS_TAG_NAV_PREFIX else SETTINGS_TAG_HOME_PREFIX)+key
            navRow.addView(cell,lp(0,-2).apply {
                weight=1f
                if(index>0) leftMargin=dp(TvStyle.GAP_INLINE)
            })
            cell
        }
        padLine(navRow,NAV_GRID_COLUMNS-line.size)
        track(1,*cells.toTypedArray())
    }

    // —— 主题外观 ——
    // 20 套配色排成 5 行；芯片就是全站统一的按钮（紧凑档），
    // 前置一枚该主题的强调色圆点，选中态是「按进去 + 焦点环」，凹下去的那一枚就是当前主题。
    val appearance=panel(appearancePage)
    heading(appearance,"主题外观","${TvTheme.entries.size} 套新拟态材质，选中即生效")
    val themeGrid=column().apply { clipChildren=false; clipToPadding=false }
    appearance.addView(themeGrid,lp(-1,-2))
    themeRows.clear()
    TvTheme.entries.chunked(THEME_GRID_COLUMNS).forEachIndexed { rowIndex,line ->
        val row=row().apply { if(rowIndex>0) setPadding(0,dp(TvStyle.GAP_INLINE),0,0) }
        line.forEachIndexed { index,option ->
            val chip=TvButton(this,option.label,ButtonSize.COMPACT,ButtonTone.SECONDARY,action={ switchTheme(option) })
            // 与网页的 theme-btn 一样：芯片前面一枚主题强调色圆点，字号收到 0.72，
            // 名称仍然保持正文大小；圆点只是识别色，不参与焦点态。
            chip.text=SpannableString("●  ${option.label}").apply {
                setSpan(ForegroundColorSpan(option.accent),0,1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(RelativeSizeSpan(.72f),0,1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            chip.setSelectedState(option==TvStyle.theme)
            chip.contentDescription=option.label+"，"+option.scene+if(option==TvStyle.theme) "，当前主题" else ""
            row.addView(chip,lp(0,-2).apply { weight=1f; if(index>0) leftMargin=dp(TvStyle.GAP_INLINE) })
            themeRows[option]=chip
        }
        // 最后一行不满时用空位占住列宽，芯片不会被拉成半屏宽。
        padLine(row,THEME_GRID_COLUMNS-line.size)
        track(1,*line.mapNotNull { themeRows[it] }.toTypedArray())
        themeGrid.addView(row,lp(-1,-2))
    }
    appearance.addView(
        text("当前：${TvStyle.theme.label} · ${TvStyle.theme.scene}",13f,TvStyle.textDim)
            .apply { setPadding(0,dp(TvStyle.GAP_BLOCK),0,0) },
        lp(-1,-2)
    )

    // ══════════════════ 三、内容推荐 ══════════════════
    //
    // 两块：推荐范围 / 内容管理与来源（两列）。
    val recommendPage=pageColumn(2)
    parent.addView(recommendPage,lp(-1,-2))
    // —— 推荐范围 ——
    // 推荐页取的是分类接口的「全部推荐」流，默认把三类混在一起（与旧版完全一致）。
    // 只想刷某一类（例如只刷漫剧）时，在这里把另外两类关掉：关掉的类不再进请求
    //（见 `Library.recommendGenres`），上下换剧就只在剩下的大类里切。
    //
    // **至少保留一类**：三类全关等于「没有推荐范围」，与「全部体裁」的语义正好相反，
    // 读起来容易混淆；因此关最后一类会被拦下，并给一句提示、开关按回「开」。
    //
    // 改范围**不重置进度**：换的是「取哪几类」，不是「刷到哪儿」。
    val scope=panel(recommendPage,gap=TvStyle.GAP_INLINE)
    heading(scope,"推荐范围","推荐页从这里取内容：大类至少留一个，受众可不限")
    val scopeSummary=text("",13f,TvStyle.textDim)
    /** 当前范围的读数：三枚开关的状态一眼能看出，但「生效的是哪几类」需要一句能读的话。 */
    fun scopeLabel(): String {
        val on=CategoryGenres.recommendable.filter { library.recommendGenreEnabled(it.id.orEmpty()) }
        val parts=on.map { it.label }.toMutableList()
        // 受众：只有选过才写进读数，并与大类用同一个分隔符。
        if(library.recommendGender.isNotEmpty()) {
            val label=recommendGenderOptions?.firstOrNull { it.id==library.recommendGender }?.label
            parts+=if(label!=null) "受众 $label" else "已限受众"
        }
        return "当前："+parts.joinToString(" · ")
    }
    fun refreshScopeLabel() {
        val label=scopeLabel()
        scopeSummary.text=label; scopeSummary.contentDescription=label
    }
    val scopeRow=lineRow(scope)
    val scopeCells=mutableListOf<TvSwitchRow>()
    CategoryGenres.recommendable.forEachIndexed { index,genre ->
        val id=genre.id.orEmpty()
        val cell=TvSwitchRow(this,genre.label,checked=library.recommendGenreEnabled(id),onToggle=switch@{ on ->
            // 关掉最后一类：直接按回「开」并说明原因。`Library` 那边也会挡（它要求至少留一类），
            // 这里先拦一次是为了让开关不会「点下去没反应」。
            if(!on && library.recommendGenreOff.size+1>=CategoryGenres.recommendableIds.size) {
                scopeCells.getOrNull(index)?.setChecked(true)
                showHint("至少保留一个推荐大类")
                return@switch
            }
            library.setRecommendGenre(id,on)
            refreshScopeLabel()
            // 换范围只换「取哪几类」，不动「刷到哪儿」：
            // - `session_id` 绑着旧体裁那一条流，留着它新范围不生效，所以只清 session；
            // - 位置（offset）与「推过哪些」都留着——下次进推荐从当前位置接着取新范围的内容。
            val resume=library.recommendCursor.substringAfter('|',"0").toIntOrNull()?.coerceAtLeast(0) ?: 0
            recommendSession=""
            recommendNext=resume
            library.recommendCursor=if(resume>0) "|$resume" else ""
            showHint("推荐范围已更新，下次进推荐生效")
        })
        cell.tag=SETTINGS_TAG_GENRE_PREFIX+id
        scopeRow.addView(cell,lp(0,-2).apply { weight=1f; if(index>0) leftMargin=dp(TvStyle.GAP_INLINE) })
        scopeCells+=cell
    }
    // —— 受众（男频 / 女频）：单选一行 ——
    // 与大类不同，受众是**互斥**的：同时开「男频 + 女频」等于不筛，读起来像两项都选了、
    // 实际什么都没限。因此这里用一组芯片：全部 / 男频 / 女频，选中哪一枚就带哪一个 `gender`。
    //
    // 为什么不写死标识：可选值与它们的 `selector_item_id` 是接口 `selector_rows` 给的
    //（见 `Library.recommendGender`），代码里猜一个串，猜错就是「一条都推不出来」。
    // 因此这一行先空着（`GONE`），等面板取回来再建（见 [loadGenderOptions]）；
    // 取不到就整行不出现，宁可少一个开关。
    val genderRow=row().apply {
        clipChildren=false; clipToPadding=false
        visibility=View.GONE
    }
    scope.addView(genderRow,lp(-1,-2).apply { topMargin=dp(TvStyle.GAP_INLINE) })
    val genderCells=mutableListOf<TvButton>()
    val genderIds=mutableListOf<String>()
    /** 把芯片的选中态按当前设置重画一遍（点完、或面板回来时都会调）。 */
    fun refreshGenderChips() {
        val selected=library.recommendGender
        genderCells.forEachIndexed { index,chip ->
            (chip as? ToggleChip)?.setActive(genderIds.getOrNull(index)==selected)
        }
    }
    /**
     * 换受众：与换大类同一条规则——只清 `session_id`，位置（offset）与「推过哪些」都留着。
     *
     * 清 `session` 是因为游标绑着上一条流；不清位置是为了不让用户又被送回列表开头
     *（早先换范围把游标一起清零，正是「重新开始推荐、列表又被推一遍」的来源）。
     */
    fun applyRecommendGender(id: String) {
        if(library.recommendGender==id) { refreshGenderChips(); return }
        library.recommendGender=id
        refreshGenderChips()
        refreshScopeLabel()
        val resume=library.recommendCursor.substringAfter('|',"0").toIntOrNull()?.coerceAtLeast(0) ?: 0
        recommendSession=""
        recommendNext=resume
        library.recommendCursor=if(resume>0) "|$resume" else ""
        showHint("推荐范围已更新，下次进推荐生效")
    }
    /**
     * 建这一行的芯片：第一枚「全部」（= 不传 `gender`），之后按接口给的顺序。
     *
     * 只在第一次拿到面板时建一次；之后再调直接返回（设置页重建会重新走一遍）。
     */
    fun buildGenderRow(options: List<CategoryFilterOption>) {
        if(genderCells.isNotEmpty() || options.isEmpty()) return
        val entries=listOf("" to "全部")+options.map { it.id to it.label }
        entries.forEachIndexed { index,(id,label) ->
            val chip=ToggleChip(this,label,library.recommendGender==id,ButtonSize.NORMAL) {
                applyRecommendGender(id)
            }
            genderRow.addView(chip,lp(0,-2).apply { weight=1f; if(index>0) leftMargin=dp(TvStyle.GAP_INLINE) })
            genderCells+=chip
            genderIds+=id
        }
        genderRow.visibility=View.VISIBLE
        // 受众芯片是异步建出来的：它固定插在「大类那一行」与「内容管理」之间。
        // 插入位置按**大类那一行**现找，不按下标写死——面板已缓存时这一行是同步建好的，
        // 下标会不一样，写死就插错位置，上下左右会串成「受众在大类上面」。
        val rows=pageRows[2]
        val genreRow=rows.indexOfFirst { line -> line.firstOrNull()===scopeCells.firstOrNull() }
        rows.add(if(genreRow>=0) genreRow+1 else rows.size,genderCells.toMutableList<View>())
        wireRows(2)
        // [wireRows] 会把每一页的第一行 up 又指回自己；受众芯片是异步到的，
        // 可能晚于页面那一次 [refreshSettingsTabLanding]，因此这里补一次，
        // 否则「AI剧 / 真人剧」按上仍旧停在原地。
        refreshSettingsTabLanding()
        // 面板回来之前那句读数只能写「已限受众」（不知道中文名），现在能写全了。
        refreshScopeLabel()
    }
    scope.addView(scopeSummary,lp(-1,-2).apply { topMargin=dp(TvStyle.GAP_BLOCK) })
    refreshScopeLabel()
    // 先登记大类那一行，再取受众：受众芯片插进 [pageRows] 时要按它找位置（见 [buildGenderRow]）。
    track(2,*scopeCells.toTypedArray())
    // 面板取回来之后补建这一行（已缓存时这里同步就建好）。
    loadGenderOptions { options -> buildGenderRow(options) }

    // —— 内容管理与内容来源：一块面板、两列 ——
    //
    // 两件回答的是同一件事——**这一份内容从哪儿来**：
    // 一个是把首页隐藏过的剧恢复回来（本机这份名单里有什么），
    // 一个是搜索页走哪个接口取结果（远端那份内容从哪儿取）。
    // 并排一行之后，这一大项不再上下堆两块面板，省下一块的高度，也方便对照着改。
    //
    // 恢复隐藏的剧集：首页卡片上的「隐藏」把剧收进了这一份名单，恢复也在这里。
    // 搜索源：只影响搜索页（结果、联想词、热搜词），分类 / 榜单 / 详情 / 播放仍然走 App 接口。
    // App 接口结果全、能一直翻页，但要求这台设备在服务端注册过；官网免登录，一次 10 条按页翻。
    val content=panel(recommendPage)
    heading(content,"内容管理与来源","恢复隐藏的剧集、换搜索接口、选集分页与翻页")
    val contentRow=lineRow(content)
    lateinit var hidden: TvButton
    hidden=cellSetting(contentRow,"恢复隐藏的剧集  ·  ${library.hidden().size} 部") {
        library.unhideAll(); hidden.text="恢复隐藏的剧集  ·  0 部"
    }
    lateinit var searchSourceButton: TvButton
    searchSourceButton=cellSetting(contentRow,"搜索源  ·  ${library.searchSource.label}") {
        showSearchSourcePicker(searchSourceButton)
    }
    track(2,hidden,searchSourceButton)
    // 选集分页：简介页与播放面板的选集网格共用一个分段（见 `EpisodeGrid`），
    // 改完下次进简介页 / 打开选集面板即按新页长摆格子，已经摆好的那一屏不重建。
    val contentRow2=lineRow(content)
    lateinit var pageSizeButton: TvButton
    pageSizeButton=cellSetting(contentRow2,"选集分页  ·  每页 ${library.episodePageSize} 集") {
        showEpisodePageSizePicker(pageSizeButton)
    }
    // 分页边缘翻页：关掉之后选集网格到边缘不再翻页，改成右端按右选下一集、左端按左选上一集
    //（见 `EpisodeGrid.pageKey`）。与分页集数同一行，两件都是「选集怎么翻」的设置。
    val edgeTurnRow=cellSwitch(contentRow2,TvSwitchRow(this,"分页边缘翻页",checked=library.episodeEdgeTurn,onToggle={ on ->
        library.episodeEdgeTurn=on
    }))
    track(2,pageSizeButton,edgeTurnRow)

    // ══════════════════ 四、系统高级 ══════════════════
    //
    // 两块：数据维护（两列）/ 设备与性能（两列）。
    val systemPage=pageColumn(3)
    parent.addView(systemPage,lp(-1,-2))
    // —— 数据维护：两列 ——
    // 「手机备份与恢复」与「清空应用缓存」相邻：清之前先导出备份是最自然的顺序，
    // 两列并排之后这一块只占一行。
    val data=panel(systemPage,gap=TvStyle.GAP_INLINE)
    heading(data,"数据维护","收藏与进度存在本机，可导出到手机")
    val dataRow=lineRow(data)
    lateinit var backup: TvButton
    backup=cellSetting(dataRow,"手机备份与恢复") {
        runCatching { LibraryBackup.encode(library.snapshot()) }.onSuccess { encoded ->
            tvTools.libraryTransfer(encoded,backup) { payload,restoreSettings ->
                favoriteMonitor.stop(); library.restore(payload,restoreSettings)
                // 备份里可能带着「减少动画」（见 BackupSettings.reduceAnim）：
                // 恢复完立刻按新设置重算档位，否则要等到下次启动才生效。
                applyPerformanceProfile()
                tabState.clear(); tabFocus.clear(); catalogFocus=""; page=1
                showCatalog(); favoriteMonitor.check()
                showHint("记录已合并恢复")
            }
        }.onFailure { tvTools.info("无法生成备份","本机记录暂时无法导出，请重试。",backup) }
    }
    // 「清空应用缓存」：把本机恢复成第一次安装的样子（收藏 / 记录 / 设置 / 设备身份 / 会话，
    // 以及外部存储里那两份镜像一起删）。这是唯一一个不可撤销的操作。
    // 名字用「缓存」是用户能理解的叫法，确认框里会写清收藏与记录也会一起清掉。
    lateinit var reset: TvButton
    reset=cellSetting(dataRow,resetLabel()) { clearLocalData(reset) }
    track(3,backup,reset)

    // —— 设备与性能：两列 ——
    //
    // 「设备形态」的自动判定、「减少动画」的动画开销、「调试模式」的现场面板，
    // 回答的是同一件事——**这一台机器怎么跑**，因此合成一块两列网格，不再各占一块面板：
    //   设备类型 ｜ 减少动画优化性能
    //   调试模式 ｜（空位）
    //
    // 低配设备上「减少动画」那一格**照样建**（见下面），只是做成不可更改：
    // 那一格本身要能读出「这一台机器已经固定开着」，整块网格的版式也不会因为
    // 少一格而在两种机型上长得不一样。
    val forcedReduce=lowEndTv()
    val perf=panel(systemPage)
    heading(perf,"设备与性能",
        if(forcedReduce) "低配设备固定开启「减少动画」，不可更改" else "设备形态、动画开销与现场面板")
    val perfRow1=lineRow(perf)
    // 形态判定（见 `compat/DeviceForm.kt`）本身是打分的，遇到谎报能力的盒子 / 触屏一体机
    // 总有判错的可能；这一格把最终决定权交给用户，选完立刻按新形态重建这一屏。
    // 标签里直接写出「自动判定现在是什么」，用户不用点开弹窗就知道系统认成了什么。
    fun deviceFormLabel(): String {
        val mode=deviceMode()
        return when(library.deviceForm) {
            "phone","tablet","tv" -> "设备类型  ·  ${mode.label}（手动）"
            else -> "设备类型  ·  自动（${mode.label}）"
        }
    }
    lateinit var deviceFormButton: TvButton
    deviceFormButton=cellSetting(perfRow1,deviceFormLabel()) { showDeviceFormPicker(deviceFormButton) }
    deviceFormButton.tag=SETTINGS_TAG_DEVICE_FORM
    // 「减少动画优化性能」：**两种机型都建**，低配机上做成不可更改的读数。
    //
    // 低配机（2GB 档电视）本来就强制按低配档跑（见 compat/Platform.kt 的 lowEndTv）：
    // 关掉焦点缩放、只给焦点卡画柔光位图、不画海报井暗角、不跑马灯、跨栏不做平滑滚动。
    // 以前这一格在低配上整格不建，用户反馈「设置项少了、不知道该不该开」——
    // 现在照样摆出来并显示为**已开启**，只是不参与焦点、点不动，配色走禁用态。
    // 非低配用户遇到发热 / 掉帧时可以自己打开它，效果与低配机完全一致。
    val reduceRow=TvSwitchRow(this,"减少动画优化性能",checked=forcedReduce||library.reduceAnim,onToggle={ on ->
        library.reduceAnim=on
        // 先落档位，再重建：已有卡片在构造时就把「是否跑马灯」捉了一份
        // （见 PosterCard.marqueeOn），只改静态开关不会作用到已经建好的那一屏。
        applyPerformanceProfile()
        settingsPendingFocus=SETTINGS_TAG_REDUCE_ANIM
        showCatalog()
    }).apply { tag=SETTINGS_TAG_REDUCE_ANIM }
    if(forcedReduce) {
        // 不可更改 = 不进焦点链 + 不响应点击。`isEnabled=false` 让 [paintButton] 走禁用配色
        // （次文字色 + 压平的底板），这里再把整体透明度提到 0.72：3 米外仍要读得清
        // 「这一项是开着的、只是不能动」，而不是一条看不清的淡字。
        reduceRow.isEnabled=false
        reduceRow.isFocusable=false
        reduceRow.isFocusableInTouchMode=false
        reduceRow.refresh()
        reduceRow.alpha=.72f
        reduceRow.contentDescription="减少动画优化性能，低配设备固定开启，不可更改"
    }
    cellSwitch(perfRow1,reduceRow)
    val perfRow2=lineRow(perf)
    // 调试模式：打开后各页面右上角常驻一块现场面板（播放信息 / 接口与登录的近况），
    // 用户反馈问题拍一张照就能定位。默认关闭；关掉时面板立刻摘掉。
    val debugRow=cellSwitch(perfRow2,TvSwitchRow(this,"调试模式",checked=library.debugMode,onToggle={ on ->
        library.debugMode=on
        syncDebugOverlay()
    })).apply { tag=SETTINGS_TAG_DEBUG }
    // 末行只占左格：右格留空，调试模式不会被拉成整宽。
    padLine(perfRow2,1)
    // 低配上「减少动画」不可聚焦，[track] 会把它过滤掉，这一行于是只有「设备类型」一格，
    // 竖向出口自然接到下一行的「调试模式」上（见 [wireRows]）。
    track(3,deviceFormButton,reduceRow)
    track(3,debugRow)

    // —— 焦点串联 ——
    // 每个大项内部：行内左右相连、行间上下同列（末行不满时落到那一行的最后一格）。
    SETTINGS_TABS.indices.forEach { wireRows(it) }
    // 并排的两栏（按键 / 弹幕）行数不同，竖向出口各自成链，不能按行号算（见 [rewireSidePanels]）。
    rewireSidePanels()
    // 每个大项**最上面那一行**登记给 [settingsTopRows]：那一行的每一格按上都要回导航栏芯片。
    // [wireRows] 给第一行设的 up 是「自己」（行上方没有东西），这里补一条真出口。
    settingsTopRows={ index -> pageRows.getOrNull(index)?.firstOrNull { it.isNotEmpty() }.orEmpty() }
    parent.addView(text("HGTV  /  ${BuildConfig.VERSION_NAME}    ·    收藏与进度保存在本机",12f,muted).apply { setPadding(dp(12),dp(20),0,dp(10)) })
    // —— 落点与「芯片 ↔ 内容」的出口，全部推到下一帧 ——
    //
    // 这一帧里 `parent`（滚动内容）还没挂进视图树：调用方是「先建内容、再把滚动容器加进去」
    // （见 `ListPage.showCatalog` 的 TAB_SETTINGS 那一段）。此刻 `findTagged(root, …)`
    // 一块内容都查不到，芯片的「按下」会接空、落点也只能退回芯片本身。
    // 推到下一帧之后两件事都成立：整棵树挂好了、尺寸也量过了，接上的出口立刻可用。
    //
    // 落点优先级：
    // 1. [settingsPendingFocus]：整页重建前记下的那一行（改开关 / 换设备形态 / 恢复备份）；
    // 2. 当前大项芯片：换主题、改大项之外的偏好之后，用户仍停在原处；
    // 3. 当前大项第一行：整排圆形入口被关掉时页头没有可聚焦控件，落进内容区。
    // 已经有焦点（例如从导航栏点进来，`ListPage` 已把焦点放到入口上）时不抢。
    parent.post {
        refreshSettingsTabLanding()
        val pending=settingsPendingFocus
        if(pending!=null) {
            settingsPendingFocus=null
            val target=findTagged(root,pending)
            if(target!=null) {
                val page=settingsPageIndexOf(target)
                if(page!=null && page!=settingsTab) switchSettingsTab(page)
                target.takeIf { it.attachedToWindow }?.let(::focusHead)
                return@post
            }
        }
        if(root.findFocus()==null) {
            val landing=findTagged(root,SETTINGS_TAG_PAGE_PREFIX+settingsTab)?.let(::firstFocusableIn)
            (settingsTabChips.getOrNull(settingsTab) ?: landing)?.takeIf { it.attachedToWindow }?.requestFocus()
        }
    }
}
/**
 * 某一枚控件挂在**哪个大项**的内容块里（按祖先上的 `settings-page-<下标>` 认）。
 *
 * 整页重建后要把焦点落回原来那一行，而那一行可能不在当前大项里
 * （例如在「外观导航」里改了「打开应用进入推荐」，重建后还停在「播放交互」上）。
 */
private fun settingsPageIndexOf(view: View): Int? {
    var node: View?=view
    while(node!=null) {
        val tag=node.tag as? String
        if(tag!=null && tag.startsWith(SETTINGS_TAG_PAGE_PREFIX)) {
            return tag.removePrefix(SETTINGS_TAG_PAGE_PREFIX).toIntOrNull()
        }
        node=node.parent as? View
    }
    return null
}
/**
 * 切换设置页的大项（播放交互 / 外观导航 / 内容推荐 / 系统高级）。
 *
 * **只换可见性**：四块内容在进这一页时就全部建好了（见 `MainActivity.settings`），
 * 因此这里不重建页面、不重新联网，滚动位置回到顶部、焦点留在刚点的那枚芯片上，
 * 正在调的那一块原样保留（弹窗也还开着）。
 */
internal fun MainActivity.switchSettingsTab(index: Int,focusContent: Boolean=false) {
    if(index !in SETTINGS_TABS.indices) return
    if(index!=settingsTab) {
        settingsTab=index
        settingsTabChips.forEachIndexed { i,chip ->
            (chip as? ToggleChip)?.setActive(i==index)
            chip.contentDescription=SETTINGS_TABS[i]+if(i==index) "，当前分区" else ""
        }
        SETTINGS_TABS.indices.forEach { i ->
            findTagged(root,SETTINGS_TAG_PAGE_PREFIX+i)?.visibility=if(i==index) View.VISIBLE else View.GONE
        }
        settingsScroll?.scrollTo(0,0)
        refreshSettingsTabLanding()
    }
    // 焦点去向：点芯片切换（或按确定）时留在芯片上——用户还在挑大项；
    // 在芯片上按「下」时进这一块的第一行（[focusContent]），那一下本来就是要往内容里走。
    if(focusContent) {
        val first=findTagged(root,SETTINGS_TAG_PAGE_PREFIX+index)?.let(::firstFocusableIn)
        if(first!=null) { focusHead(first); return }
    }
    settingsTabChips.getOrNull(index)?.takeIf { it.attachedToWindow }?.requestFocus()
}
/**
 * 大项芯片与内容块之间的上下出口。
 *
 * 每个芯片的「按下」落在**它自己那一块**的第一行、每一块的第一行「按上」回它自己的芯片；
 * 顶部圆形入口的「按下」落到**当前**大项的第一行（切了大项之后要重算一次）。
 * `GONE` 的块里没有可聚焦控件，因此这里一律按可见性过滤（[firstFocusableIn]）。
 */
internal fun MainActivity.refreshSettingsTabLanding() {
    val active=settingsTab.coerceIn(0,SETTINGS_TABS.lastIndex)
    SETTINGS_TABS.indices.forEach { index ->
        val chip=settingsTabChips.getOrNull(index) ?: return@forEach
        val first=findTagged(root,SETTINGS_TAG_PAGE_PREFIX+index)?.let(::firstFocusableIn) ?: return@forEach
        chip.nextFocusDownId=first.id
        // 这一页**最上面那一行**的每一格都回芯片，不只是第一格：
        // 推荐范围那一行有三枚开关，只改第一格就会出现「漫剧能上、AI剧 / 真人剧上不去」。
        // [settingsTopRows] 由 `MainActivity.settings()` 建完页面后赋值；
        // 页面还没建（或已重建）时退回只修第一格。
        val top=settingsTopRows(index)
        (top.ifEmpty { listOf(first) }).forEach { it.nextFocusUpId=chip.id }
    }
    val landing=findTagged(root,SETTINGS_TAG_PAGE_PREFIX+active)?.let(::firstFocusableIn) ?: return
    navIcons().forEach { it.nextFocusDownId=landing.id }
    findTagged(root,"nav:recommend")?.let { it.nextFocusDownId=landing.id }
}
/** 容器里第一枚可聚焦控件（深度优先，只看可见的叶子）。 */
private fun firstFocusableIn(view: View): View? {
    if(view.visibility!=View.VISIBLE) return null
    if(view !is ViewGroup) return if(view.isFocusable) view else null
    for(index in 0 until view.childCount) firstFocusableIn(view.getChildAt(index))?.let { return it }
    return null
}
/**
 * 页面内提示（替代 Toast）。
 *
 * 系统 Toast 是独立于界面的一层浮窗，样式与字号不受本应用控制，电视上还会盖在画面正中；
 * 需要给一句「刚刚发生了什么」时改用它——页头下面一行淡字，不参与焦点、几秒后自己消失。
 */
internal fun MainActivity.showHint(message: String) {
    catalogHint=message
    catalogHintView?.let { view -> view.text=message; view.visibility=View.VISIBLE }
    main.removeCallbacks(hideHint)
    main.postDelayed(hideHint,4000)
}
/**
 * 搜索：**不分内容类型**（顶部那两个「漫剧 / 短剧」开关已经去掉）。
 * 漫剧与短剧 / 真人剧的搜索结果合并去重后一起显示，由 [ContentRepository.search] 负责。
 */
/**
 * 搜索页的搜索控件：**整条都在导航栏里**（搜索框 + 搜索 / 历史）。
 *
 * 用户从别的页面按「搜索」图标进来就是要打字，输入框再占页面第一条会白花一屏的高度；
 * 放进导航栏之后页面第一行就是提示词 / 热搜词，输入框与它上下相邻，方向键一步就到。
 *
 * 输入框吃掉导航栏中间的弹性空间（`weight=1`），因此它总是这一行里最宽的一件；
 * 右边两个按钮按内容宽度排，圆形入口跟在最后。
 */

/**
 * 「设备类型」弹窗：自动 / 手机 / 平板 / 电视。
 *
 * 为什么需要这个手动档：形态判定（见 `compat/DeviceForm.kt`）是打分的，遇到谎报能力位的
 * 国产盒子、或者「电视 + 触摸屏」这种一体机，总有判不到的时候。用户点错了地方
 * （比如手机版只在电视上打开、旋转方向反了），手边必须有一个能立刻改回来的开关，
 * 而不是等下一次发版把阈值再调一遍。
 *
 * 四档的差异：
 * - **自动**：按打分判定的结果跑（标签里直接写出当前判成了什么）；
 * - **手机 / 平板**：一律点亮触屏适配（手势层 + 底部控制条 + 上滑下拉换剧），
 *   旋转方向按屏幕方向走（手机 `−90`、横屏平板 `+90`）；
 * - **电视**：一律按遥控器跑——不建手势层、再有点上去也不点亮，
 *   竖屏短剧全屏固定顺时针 `+90`。
 *
 * 改完必须**整页重建**：手势层、底部控制条、播放层的旋转方向都是建页面时定的，
 * 就地改字不会让它们跟着变。重建后焦点按 [SETTINGS_TAG_DEVICE_FORM] 落回这一格。
 */
internal fun MainActivity.showDeviceFormPicker(anchor: TextView) {
    if(deviceFormDialog!=null) return
    val options=listOf("auto","phone","tablet","tv")
    val detected=deviceMode()
    val labels=listOf(
        "自动判定  ·  当前：${detected.label}",
        "手机  ·  触屏手势 + 竖屏旋转",
        "平板  ·  触屏手势 + 横屏旋转",
        "电视  ·  遥控器 + 顺时针旋转"
    )
    val selected=options.indexOf(library.deviceForm).coerceAtLeast(0)
    val picker=TvDialog(this,"设备类型")
    picker.choices(labels,selected) { index ->
        val value=options[index]
        if(value==library.deviceForm) return@choices
        library.deviceForm=value
        // 先按新形态把触屏 / 不触屏定下来，再重建这一屏。
        applyDeviceForm()
        settingsPendingFocus=SETTINGS_TAG_DEVICE_FORM
        // 重建推到下一帧：这一下正处在弹窗自己的 dismiss 里，
        // 同一帧里连弹窗一起改建视图树容易和窗口动画抢一帧。
        main.post { showCatalog() }
    }
    picker.button("取消", icon=R.drawable.ic_menu_close)
    deviceFormDialog=picker
    picker.show(anchor) {
        deviceFormDialog=null
        // 整页已经重建过时这一枚已经不在窗口上，别去抢新页面的焦点。
        if(anchor.attachedToWindow) anchor.requestFocus()
    }
}
