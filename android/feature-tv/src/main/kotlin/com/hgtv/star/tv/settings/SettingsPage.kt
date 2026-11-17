// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.app.Activity
import android.graphics.Typeface
import android.view.*
import android.widget.*
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import com.google.android.exoplayer2.ui.PlayerView
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
import com.hgtv.star.shared.ui.TvTheme
import com.hgtv.star.tv.list.showCatalog
import com.hgtv.star.tv.findTagged
import com.hgtv.star.shared.compat.attachedToWindow
import com.hgtv.star.shared.compat.DeviceForm
import com.hgtv.star.shared.compat.DeviceFormSelection
import com.hgtv.star.shared.compat.lowEndTv
import com.hgtv.star.shared.ProjectInfo
import com.hgtv.star.tv.R


fun TvMainActivity.settings(parent: LinearLayout) {
    val active=settingsTab.coerceIn(0,SETTINGS_TABS.lastIndex)
    settingsTab=active
    parent.addView(text("偏好自动保存，下一部剧继续使用。",14f,TvStyle.muted).apply { setPadding(0,dp(10),0,dp(20)) })
    val groupBand=dp(TvStyle.SOFT_BAND_LARGE)
    fun groupPanel(group: LinearLayout) {
        group.setPadding(dp(24)+groupBand,dp(20)+groupBand,dp(24)+groupBand,dp(20)+groupBand)
        group.background=softPanel(TvStyle.surfaceHigh,TvStyle.RADIUS_DIALOG,band=TvStyle.SOFT_BAND_LARGE,depth=1.05f)
    }
    fun pageColumn(index: Int)=column().apply {
        clipChildren=false; clipToPadding=false
        tag=SETTINGS_TAG_PAGE_PREFIX+index
        visibility=if(index==active) View.VISIBLE else View.GONE
    }
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
    fun setting(group: LinearLayout,label: String,action: ()->Unit): TvButton {
        val view=TvSettingButton(this,label,ButtonSize.NORMAL,ButtonTone.SECONDARY,action=action)
        group.addView(view,lp(-1,-2).apply { if(group.childCount>1) topMargin=dp(TvStyle.GAP_INLINE) })
        return view
    }
    fun switchSetting(group: LinearLayout,view: TvSwitchRow): TvSwitchRow {
        group.addView(view,lp(-1,-2).apply { if(group.childCount>1) topMargin=dp(TvStyle.GAP_INLINE) })
        return view
    }
    fun lineRow(group: LinearLayout,gap: Int=TvStyle.GAP_INLINE): LinearLayout {
        val line=row().apply { clipChildren=false; clipToPadding=false }
        group.addView(line,lp(-1,-2).apply { if(group.childCount>1) topMargin=dp(gap) })
        return line
    }
    fun padLine(line: LinearLayout,count: Int) {
        repeat(count) { index ->
            line.addView(Space(this),lp(0,1).apply {
                weight=1f
                if(line.childCount>1) leftMargin=dp(TvStyle.GAP_INLINE)
            })
        }
    }
    fun fullSlider(
        group: LinearLayout,label: String,min: Float,max: Float,step: Float,value: Float,
        format: (Float)->String = DanmakuStyle::percent,
        onValue: (Float)->Unit
    ): SettingSlider {
        val slider=SettingSlider(this,label,min,max,step,value,onValue,format)
        group.addView(slider,lp(-1,-2).apply { topMargin=dp(TvStyle.GAP_INLINE) })
        return slider
    }
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
                view.nextFocusDownId=(below?.let { it.getOrNull(c) ?: it.last() } ?: view).id
            }
        }
    }

    fun sidePanel(container: LinearLayout): LinearLayout {
        val group=column()
        groupPanel(group)
        container.addView(group,lp(0,-2).apply {
            weight=1f
            if(container.childCount>1) leftMargin=dp(TvStyle.GAP_GROUP)
        })
        return group
    }
    fun cellSetting(line: LinearLayout,label: String,action: ()->Unit): TvButton {
        val view=TvSettingButton(this,label,ButtonSize.NORMAL,ButtonTone.SECONDARY,action=action)
        line.addView(view,lp(0,-2).apply { weight=1f; if(line.childCount>0) leftMargin=dp(TvStyle.GAP_INLINE) })
        return view
    }
    fun cellSlider(
        line: LinearLayout,label: String,min: Float,max: Float,step: Float,value: Float,
        format: (Float)->String,
        commitDelayMs: Long=0L,
        onValue: (Float)->Unit
    ): SettingSlider {
        val slider=SettingSlider(this,label,min,max,step,value,onValue,format,commitDelayMs)
        line.addView(slider,lp(0,-2).apply { weight=1f; if(line.childCount>0) leftMargin=dp(TvStyle.GAP_INLINE) })
        return slider
    }
    fun cellSwitch(line: LinearLayout,view: TvSwitchRow): TvSwitchRow {
        line.addView(view,lp(0,-2).apply { weight=1f; if(line.childCount>0) leftMargin=dp(TvStyle.GAP_INLINE) })
        return view
    }
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
    var rewireSidePanels: ()->Unit={}

    val playbackPage=pageColumn(0)
    parent.addView(playbackPage,lp(-1,-2))
    val playback=panel(playbackPage,gap=TvStyle.GAP_INLINE)
    heading(playback,"播放偏好","所有剧集共用，播放中也可调整")
    // 每一行左选项、右滑杆：同一行共用顶边与高度，右列天然与左侧按钮对齐。
    val prefRow1=lineRow(playback)
    lateinit var qualityButton: TvButton
    qualityButton=cellSetting(prefRow1,"默认清晰度  ·  ${library.maxQuality}P") { showQualityPicker(qualityButton,false) }
    val defaultSpeed=cellSlider(
        prefRow1,"默认倍速",PlaybackSpeed.MIN,PlaybackSpeed.MAX,PlaybackSpeed.STEP,library.playbackSpeed,
        format=PlaybackSpeed::label,
        commitDelayMs=500L,
    ) { value ->
        library.playbackSpeed=value
        if(onPlaybackPage()) {
            player?.setPlaybackSpeed(value)
            updatePlaybackText()
        }
    }

    val prefRow2=lineRow(playback)
    lateinit var frame: TvButton
    frame=cellSetting(prefRow2,"画面模式  ·  ${library.frameMode.label}") { showFramePicker(frame,false) }
    val scaleSlider=cellSlider(
        prefRow2,"画面缩放",1f,100f,1f,library.videoScale,
        format={ "${it.toInt()}%" }
    ) { value ->
        library.videoScale=value
        if(onPlaybackPage()) applyVideoScale()
    }

    val prefRow3=lineRow(playback)
    lateinit var decoderButton: TvButton
    decoderButton=cellSetting(prefRow3,"视频解码  ·  ${library.decoderMode.label}") { showDecoderPicker(decoderButton,false) }
    val seekSlider=cellSlider(
        prefRow3,"快进快退",2f,20f,1f,library.seekStep.toFloat(),
        format={ "${it.toInt()} 秒" }
    ) { value -> library.seekStep=value.toInt() }

    val prefRow4=lineRow(playback)
    val autoNextRow=cellSwitch(prefRow4,TvSwitchRow(this,"自动连播",checked=library.autoNext,onToggle={ on ->
        library.autoNext=on
        if(!on) dropQueuedEpisode()
    }))
    padLine(prefRow4,1)

    track(0,qualityButton,defaultSpeed)
    track(0,frame,scaleSlider)
    track(0,decoderButton,seekSlider)
    track(0,autoNextRow)

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
    playInfoPanel.addView(text(
        "播放设置、选集面板、底部控制条无操作后自动收起，与按返回键相同。",
        14f,TvStyle.muted
    ).apply { setPadding(0,dp(TvStyle.GAP_INLINE),0,0) },lp(-1,-2))
    val panelTimeoutLine=lineRow(playInfoPanel)
    val panelTimeout=cellSlider(
        panelTimeoutLine,"播放界面自动消失",
        PLAYER_PANEL_TIMEOUT_RANGE.first.toFloat(),PLAYER_PANEL_TIMEOUT_RANGE.last.toFloat(),1f,
        library.playerPanelTimeout.toFloat(),
        format={ "${it.toInt()} 秒" }
    ) { value -> library.playerPanelTimeout=value.toInt() }
    padLine(panelTimeoutLine,1)
    track(0,panelTimeout)

    val mid=row().apply { gravity=Gravity.TOP; clipChildren=false; clipToPadding=false }
    playbackPage.addView(mid,lp(-1,-2).apply { topMargin=dp(TvStyle.GAP_GROUP) })
    val headHeight=dp(TvStyle.BUTTON_COMPACT+2*TvStyle.SOFT_BAND)
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
    val keys=sidePanel(mid)
    var restoreAction: ()->Unit={}
    val restore=IconButton(
        this,R.drawable.ic_action_restore,restoreKeysLabel(),TvStyle.BUTTON_COMPACT
    ) { restoreAction() }
    panelHead(keys,"按键设置","播放界面：六个键各做什么",restore)
    var keyCells: List<TvButton> = emptyList()
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
    restoreAction={
        library.keyMap=KeyMap.DEFAULT
        refreshKeyLabels()
        showHint("已恢复默认按键")
    }
    refreshKeyLabels()
    val danmaku=sidePanel(mid)
    lateinit var danmakuSetting: SettingSlider
    lateinit var danmakuSpeedSetting: TvButton
    lateinit var danmakuScaleRow: SettingSlider
    lateinit var danmakuAlphaRow: SettingSlider
    panelHead(danmaku,"弹幕设置","显示方式 / 速度 / 聚合 / 特效 / 字号 / 透明度 / 顶部避让")
    danmakuSetting=fullSlider(
        danmaku,"弹幕显示",
        0f,DanmakuMode.entries.lastIndex.toFloat(),1f,library.danmakuMode.sliderValue,
        format={ DanmakuMode.fromSlider(it).optionLabel },
    ) { value ->
        val selected=DanmakuMode.fromSlider(value)
        library.danmakuMode=selected
        danmakuView?.mode=selected
    }
    danmakuSpeedSetting=setting(danmaku,"弹幕速度  ·  ${library.danmakuSpeed.label}") {
        showDanmakuSpeedPicker(danmakuSpeedSetting,false)
    }
    val danmakuMergeRow=switchSetting(danmaku,TvSwitchRow(this,"相同弹幕聚合",checked=library.danmakuMerge,onToggle={ on ->
        library.danmakuMerge=on
        danmakuView?.mergeRepeats=on
    }))
    val danmakuEffectsRow=switchSetting(danmaku,TvSwitchRow(this,"开启弹幕特效",checked=library.danmakuEffects,onToggle={ on ->
        library.danmakuEffects=on
        danmakuView?.effectsEnabled=on
    }))
    val danmakuAvoidInfoRow=switchSetting(danmaku,TvSwitchRow(
        this,
        "避开顶部播放显示",
        checked=library.danmakuAvoidPlaybackInfo,
        onToggle={ on ->
            library.danmakuAvoidPlaybackInfo=on
            applyDanmakuTopProtection()
        },
    ))
    val danmakuMergeMinRow=fullSlider(
        danmaku,"聚合条数",
        DANMAKU_MERGE_MIN_RANGE.first.toFloat(),DANMAKU_MERGE_MIN_RANGE.last.toFloat(),1f,
        library.danmakuMergeMin.toFloat(),
        format={ "${it.toInt()} 条" }
    ) { value ->
        library.danmakuMergeMin=value.toInt(); danmakuView?.mergeMinCount=value.toInt()
    }
    danmakuScaleRow=fullSlider(danmaku,"弹幕大小",DanmakuStyle.SCALE_MIN,DanmakuStyle.SCALE_MAX,DanmakuStyle.SCALE_STEP,library.danmakuScale) { value ->
        library.danmakuScale=value; danmakuView?.textScale=value
    }
    danmakuAlphaRow=fullSlider(danmaku,"弹幕透明度",DanmakuStyle.ALPHA_MIN,DanmakuStyle.ALPHA_MAX,DanmakuStyle.ALPHA_STEP,library.danmakuAlpha) { value ->
        library.danmakuAlpha=value; danmakuView?.textAlpha=value
    }
    equalHeights(mid,listOf(keys,danmaku))
    val danmakuColumn=listOf<View>(
        danmakuSetting,
        danmakuSpeedSetting,
        danmakuMergeRow,
        danmakuEffectsRow,
        danmakuAvoidInfoRow,
        danmakuMergeMinRow,
        danmakuScaleRow,
        danmakuAlphaRow,
    )
    val keyColumn=listOf<View>(restore)+keyCells
    keyColumn.forEachIndexed { i,v -> track(0,*listOfNotNull(v,danmakuColumn.getOrNull(i)).toTypedArray()) }
    danmakuColumn.drop(keyColumn.size).forEach { track(0,it) }
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

    val appearancePage=pageColumn(1)
    parent.addView(appearancePage,lp(-1,-2))
    val layoutPanel=panel(appearancePage,gap=TvStyle.GAP_INLINE)
    heading(layoutPanel,"布局启动","列表怎么排、历史点击、默认输入法、启动落点与回到顶部")
    val layoutRow=lineRow(layoutPanel)
    lateinit var homeColumnsButton: TvButton
    homeColumnsButton=cellSetting(layoutRow,"每行卡片  ·  ${library.homeColumns} 张") { showHomeColumnsPicker(homeColumnsButton) }
    val launchRecommendRow=cellSwitch(layoutRow,TvSwitchRow(this,"打开应用进入推荐",checked=library.launchRecommend,onToggle={ on ->
        library.launchRecommend=on
        settingsPendingFocus=SETTINGS_TAG_LAUNCH_RECOMMEND
        showCatalog()
    }).apply { tag=SETTINGS_TAG_LAUNCH_RECOMMEND })
    track(1,homeColumnsButton,launchRecommendRow)
    val backToTopRow=lineRow(layoutPanel)
    lateinit var backToTopButton: TvButton
    backToTopButton=cellSetting(backToTopRow,"返回顶部  ·  "+backToTopShort(library.backToTop)) {
        showBackToTopPicker(backToTopButton)
    }
    backToTopButton.tag=SETTINGS_TAG_BACK_TO_TOP
    val historyCardSwitch=cellSwitch(backToTopRow,TvSwitchRow(this,"优先进入简介",checked=library.historyPreferDetail,onToggle={ on ->
        library.historyPreferDetail=on
    }))
    track(1,backToTopButton,historyCardSwitch)
    val keyboardRow=lineRow(layoutPanel)
    lateinit var defaultKeyboardButton: TvButton
    defaultKeyboardButton=cellSetting(keyboardRow,"默认输入法  ·  ${library.searchKeyboard.label}") {
        showDefaultKeyboardPicker(defaultKeyboardButton)
    }
    padLine(keyboardRow,1)
    track(1,defaultKeyboardButton)

    val navPanel=panel(appearancePage)
    heading(navPanel,"导航显隐","关掉的入口不建控件、关掉的栏目不铺首页，设置始终显示")
    val featureEntries=NAV_ENTRIES+HOME_ENTRIES
    featureEntries.chunked(NAV_GRID_COLUMNS).forEachIndexed { rowIndex,line ->
        val navRow=lineRow(navPanel,gap=if(rowIndex==0) TvStyle.GAP_INLINE else TvStyle.GAP_TIGHT)
        val cells=line.mapIndexed { index,(key,name) ->
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

    val appearance=panel(appearancePage)
    heading(appearance,"主题外观","${TvTheme.entries.size} 套新拟态材质，选中即生效")
    val themeGrid=column().apply { clipChildren=false; clipToPadding=false }
    appearance.addView(themeGrid,lp(-1,-2))
    themeRows.clear()
    TvTheme.entries.chunked(THEME_GRID_COLUMNS).forEachIndexed { rowIndex,line ->
        val row=row().apply { if(rowIndex>0) setPadding(0,dp(TvStyle.GAP_INLINE),0,0) }
        line.forEachIndexed { index,option ->
            val chip=TvButton(this,option.label,ButtonSize.COMPACT,ButtonTone.SECONDARY,action={ switchTheme(option) })
            chip.text=SpannableString("●  ${option.label}").apply {
                setSpan(ForegroundColorSpan(option.accent),0,1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(RelativeSizeSpan(.72f),0,1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            chip.setSelectedState(option==TvStyle.theme)
            chip.contentDescription=option.label+"，"+option.scene+if(option==TvStyle.theme) "，当前主题" else ""
            row.addView(chip,lp(0,-2).apply { weight=1f; if(index>0) leftMargin=dp(TvStyle.GAP_INLINE) })
            themeRows[option]=chip
        }
        padLine(row,THEME_GRID_COLUMNS-line.size)
        track(1,*line.mapNotNull { themeRows[it] }.toTypedArray())
        themeGrid.addView(row,lp(-1,-2))
    }
    appearance.addView(
        text("当前：${TvStyle.theme.label} · ${TvStyle.theme.scene}",13f,TvStyle.textDim)
            .apply { setPadding(0,dp(TvStyle.GAP_BLOCK),0,0) },
        lp(-1,-2)
    )

    val recommendPage=pageColumn(2)
    parent.addView(recommendPage,lp(-1,-2))
    val scope=panel(recommendPage,gap=TvStyle.GAP_INLINE)
    heading(scope,"推荐范围","推荐页从这里取内容：大类至少留一个，受众可不限，可只保留横屏短剧")
    val scopeSummary=text("",13f,TvStyle.textDim)
    fun scopeLabel(): String {
        val on=CategoryGenres.recommendable.filter { library.recommendGenreEnabled(it.id.orEmpty()) }
        val parts=on.map { it.label }.toMutableList()
        if(library.recommendGender.isNotEmpty()) {
            val label=recommendGenderOptions?.firstOrNull { it.id==library.recommendGender }?.label
            parts+=if(label!=null) "受众 $label" else "已限受众"
        }
        if(library.recommendHorizontalOnly) parts+="仅横屏"
        return "当前："+parts.joinToString(" · ")
    }
    fun refreshScopeLabel() {
        val label=scopeLabel()
        scopeSummary.text=label; scopeSummary.contentDescription=label
    }
    val horizontalOnlyRow=switchSetting(scope,TvSwitchRow(
        this,
        "只推荐横屏短剧",
        checked=library.recommendHorizontalOnly,
        onToggle={ on ->
            library.recommendHorizontalOnly=on
            invalidateRecommendForFilterChange()
            refreshScopeLabel()
            showHint(if(on) "已开启：推荐页切换为横屏推荐流" else "已关闭：推荐页恢复默认推荐流")
        },
    ))
    val scopeRow=lineRow(scope)
    val scopeCells=mutableListOf<TvSwitchRow>()
    CategoryGenres.recommendable.forEachIndexed { index,genre ->
        val id=genre.id.orEmpty()
        val cell=TvSwitchRow(this,genre.label,checked=library.recommendGenreEnabled(id),onToggle=switch@{ on ->
            if(!on && library.recommendGenreOff.size+1>=CategoryGenres.recommendableIds.size) {
                scopeCells.getOrNull(index)?.setChecked(true)
                showHint("至少保留一个推荐大类")
                return@switch
            }
            library.setRecommendGenre(id,on)
            refreshScopeLabel()
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
    val genderRow=row().apply {
        clipChildren=false; clipToPadding=false
        visibility=View.GONE
    }
    scope.addView(genderRow,lp(-1,-2).apply { topMargin=dp(TvStyle.GAP_INLINE) })
    val genderCells=mutableListOf<TvButton>()
    val genderIds=mutableListOf<String>()
    fun refreshGenderChips() {
        val selected=library.recommendGender
        genderCells.forEachIndexed { index,chip ->
            (chip as? ToggleChip)?.setActive(genderIds.getOrNull(index)==selected)
        }
    }
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
        val rows=pageRows[2]
        val genreRow=rows.indexOfFirst { line -> line.firstOrNull()===scopeCells.firstOrNull() }
        rows.add(if(genreRow>=0) genreRow+1 else rows.size,genderCells.toMutableList<View>())
        wireRows(2)
        refreshSettingsTabLanding()
        refreshScopeLabel()
    }
    scope.addView(scopeSummary,lp(-1,-2).apply { topMargin=dp(TvStyle.GAP_BLOCK) })
    refreshScopeLabel()
    track(2,horizontalOnlyRow)
    track(2,*scopeCells.toTypedArray())
    loadGenderOptions { options -> buildGenderRow(options) }

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
    val contentRow2=lineRow(content)
    lateinit var pageSizeButton: TvButton
    pageSizeButton=cellSetting(contentRow2,"选集分页  ·  每页 ${library.episodePageSize} 集") {
        showEpisodePageSizePicker(pageSizeButton)
    }
    val edgeTurnRow=cellSwitch(contentRow2,TvSwitchRow(this,"分页边缘翻页",checked=library.episodeEdgeTurn,onToggle={ on ->
        library.episodeEdgeTurn=on
    }))
    track(2,pageSizeButton,edgeTurnRow)

    val systemPage=pageColumn(3)
    parent.addView(systemPage,lp(-1,-2))
    val data=panel(systemPage,gap=TvStyle.GAP_INLINE)
    heading(data,"数据维护","收藏与进度存在本机，可备份到手机或通过局域网同步其他设备")
    val dataRow=lineRow(data)
    lateinit var backup: TvButton
    backup=cellSetting(dataRow,"手机备份与恢复") {
        runCatching { LibraryBackup.encode(library.snapshot()) }.onSuccess { encoded ->
            tvTools.libraryTransfer(encoded,backup) { payload,restoreSettings ->
                applyLibraryBackup(payload,restoreSettings)
            }
        }.onFailure { tvTools.info("无法生成备份","本机记录暂时无法导出，请重试。",backup) }
    }
    lateinit var lanSync: TvButton
    lanSync=cellSetting(dataRow,"同步其他设备数据") { showLanLibrarySync(lanSync) }
    track(3,backup,lanSync)
    val resetRow=lineRow(data)
    lateinit var reset: TvButton
    reset=cellSetting(resetRow,resetLabel()) { clearLocalData(reset) }
    padLine(resetRow,1)
    track(3,reset)

    val forcedReduce=lowEndTv()
    val perf=panel(systemPage)
    heading(perf,"设备与性能",
        if(forcedReduce) "低配设备固定开启「减少动画」，不可更改" else "动画开销与现场面板")
    val perfRow1=lineRow(perf)
    val reduceRow=TvSwitchRow(this,"减少动画优化性能",checked=forcedReduce||library.reduceAnim,onToggle={ on ->
        library.reduceAnim=on
        applyPerformanceProfile()
        settingsPendingFocus=SETTINGS_TAG_REDUCE_ANIM
        showCatalog()
    }).apply { tag=SETTINGS_TAG_REDUCE_ANIM }
    if(forcedReduce) {
        reduceRow.isEnabled=false
        reduceRow.isFocusable=false
        reduceRow.isFocusableInTouchMode=false
        reduceRow.refresh()
        reduceRow.alpha=.72f
        reduceRow.contentDescription="减少动画优化性能，低配设备固定开启，不可更改"
    }
    cellSwitch(perfRow1,reduceRow)
    val debugRow=cellSwitch(perfRow1,TvSwitchRow(this,"调试模式",checked=library.debugMode,onToggle={ on ->
        library.debugMode=on
        syncDebugOverlay()
    })).apply { tag=SETTINGS_TAG_DEBUG }
    track(3,reduceRow,debugRow)
    val deviceLine=lineRow(perf)
    lateinit var aboutButton: TvButton
    aboutButton=cellSetting(deviceLine,"关于") { showAboutDialog(aboutButton) }
    lateinit var deviceType: TvButton
    deviceType=cellSetting(deviceLine,"设备类型  ·  ${deviceFormSettingLabel()}") {
        openDeviceFormSelector()
    }
    track(3,aboutButton,deviceType)

    SETTINGS_TABS.indices.forEach { wireRows(it) }
    rewireSidePanels()
    settingsTopRows={ index -> pageRows.getOrNull(index)?.firstOrNull { it.isNotEmpty() }.orEmpty() }
    parent.addView(text("HGTV  /  ${AppVersion.name}    ·    收藏与进度保存在本机",12f,muted).apply { setPadding(dp(12),dp(20),0,dp(10)) })
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

private fun TvMainActivity.showAboutDialog(anchor: View) {
    val dialog=TvDialog(this,"关于")
    dialog.message("版本号：${AppVersion.name}")
    dialog.message("GitHub：${ProjectInfo.GITHUB}")
    dialog.message("更新请前往项目页面查看项目更新。")
    dialog.button("关闭") {}
    dialog.show(anchor)
}

private fun TvMainActivity.deviceFormSettingLabel(): String {
    return DeviceForm.ofStored(library.deviceForm)?.label ?: "未选择"
}

private fun TvMainActivity.openDeviceFormSelector() {
    startActivityForResult(
        DeviceFormSelection.intent(
            this,
            firstRun=false,
            currentForm=DeviceForm.ofStored(library.deviceForm)?.let { DeviceForm.storedValue(it) },
            tvStyle=true,
        ),
        REQUEST_DEVICE_FORM,
    )
}

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
fun TvMainActivity.switchSettingsTab(index: Int,focusContent: Boolean=false) {
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
    if(focusContent) {
        val first=findTagged(root,SETTINGS_TAG_PAGE_PREFIX+index)?.let(::firstFocusableIn)
        if(first!=null) { focusHead(first); return }
    }
    settingsTabChips.getOrNull(index)?.takeIf { it.attachedToWindow }?.requestFocus()
}
fun TvMainActivity.refreshSettingsTabLanding() {
    val active=settingsTab.coerceIn(0,SETTINGS_TABS.lastIndex)
    SETTINGS_TABS.indices.forEach { index ->
        val chip=settingsTabChips.getOrNull(index) ?: return@forEach
        val first=findTagged(root,SETTINGS_TAG_PAGE_PREFIX+index)?.let(::firstFocusableIn) ?: return@forEach
        chip.nextFocusDownId=first.id
        val top=settingsTopRows(index)
        (top.ifEmpty { listOf(first) }).forEach { it.nextFocusUpId=chip.id }
    }
    val landing=findTagged(root,SETTINGS_TAG_PAGE_PREFIX+active)?.let(::firstFocusableIn) ?: return
    navIcons().forEach { it.nextFocusDownId=landing.id }
    findTagged(root,"nav:recommend")?.let { it.nextFocusDownId=landing.id }
}
private fun firstFocusableIn(view: View): View? {
    if(view.visibility!=View.VISIBLE) return null
    if(view !is ViewGroup) return if(view.isFocusable) view else null
    for(index in 0 until view.childCount) firstFocusableIn(view.getChildAt(index))?.let { return it }
    return null
}
fun TvMainActivity.showHint(message: String) {
    if(message.isBlank()) return
    runCatching { tvTools.toast(message) }
}

