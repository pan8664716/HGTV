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
import com.hgtv.star.player.DanmakuSlider
import com.hgtv.star.tv.IconButton
import com.hgtv.star.tv.TvButton
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
    // 页面名「设置」已经进了顶部导航栏，这里不再重复一行标题，只留一句说明。
    parent.addView(text("偏好自动保存，下一部剧继续使用。",14f,TvStyle.muted).apply { setPadding(0,dp(10),0,dp(20)) })
    val groups=row().apply { gravity=Gravity.TOP; clipChildren=false; clipToPadding=false }
    val playback=column().apply { clipChildren=false; clipToPadding=false }
    val device=column().apply { clipChildren=false; clipToPadding=false }
    val groupBand=dp(TvStyle.SOFT_BAND_LARGE)
    /** 一个设置分组：大号凸起面板，内容四周留出柔光带。 */
    fun groupPanel(group: LinearLayout) {
        group.setPadding(dp(24)+groupBand,dp(20)+groupBand,dp(24)+groupBand,dp(20)+groupBand)
        group.background=softPanel(TvStyle.surfaceHigh,TvStyle.RADIUS_DIALOG,band=TvStyle.SOFT_BAND_LARGE,depth=1.05f)
    }
    listOf(playback,device).forEachIndexed { i,group ->
        groupPanel(group)
        groups.addView(group,lp(0,-2).apply { weight=1f; if(i==0) rightMargin=dp(TvStyle.GAP_GROUP) })
    }
    parent.addView(groups)
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
     * 网格里的一格（按钮）：与 [setting] 同一个按钮，宽度交给权重（一行两格）。
     * 行距与格距由外面的行容器给，这里不加 topMargin。
     */
    fun gridSetting(row: LinearLayout,label: String,action: ()->Unit): TvButton {
        val view=TvSettingButton(this,label,ButtonSize.NORMAL,ButtonTone.SECONDARY,action=action)
        row.addView(view,lp(0,-2).apply { weight=1f; if(row.childCount>1) leftMargin=dp(TvStyle.GAP_INLINE) })
        return view
    }
    /**
     * 整栏宽的一行（左标签 + 右滑轨），返回（滑块，这一整行）。
     *
     * 高度与上面那排按钮同一档（[TvStyle.BUTTON_NORMAL] + 两条柔光带 = 60dp），
     * 因此这一栏的三行看起来是同一块料，而不是「按钮 + 两条另一样的东西」。
     *
     * 左侧留白按按钮的文字内边距给（[TvStyle.BUTTON_PAD_NORMAL] + 柔光带 = 34dp）：
     * `TvButton` 的文字正是从这里起排的，所以上下三行的文字左边缘会落在同一条竖线上；
     * 右侧同样留出这段距离，滑轨的百分比读数才不会顶到面板边缘。
     *
     * 与播放设置面板里那两条共用同一个 [DanmakuSlider]、改的是同一份本机偏好，
     * 因此设置页调好之后进播放界面立刻就是新的字号 / 透明度。
     */
    fun fullSlider(group: LinearLayout,label: String,min: Float,max: Float,step: Float,value: Float,onValue: (Float)->Unit): Pair<DanmakuSlider,View> {
        val line=row().apply {
            clipChildren=false; clipToPadding=false; gravity=Gravity.CENTER_VERTICAL
            // 左边与设置项文字同一条线；右边只留「常规按钮内边距 − 紧凑滑块内边距」，
            // 让滑块自己的右内边距补上剩下的那一段——百分比读数正好落在设置项 `>` 的同一条右边缘上。
            setPadding(
                dp(TvStyle.BUTTON_PAD_NORMAL)+dp(TvStyle.SOFT_BAND),0,
                dp(TvStyle.BUTTON_PAD_NORMAL-TvStyle.BUTTON_PAD_COMPACT),0
            )
        }
        line.addView(
            text(label,TvStyle.BUTTON_TEXT_NORMAL,TvStyle.text).apply {
                gravity=Gravity.CENTER_VERTICAL; maxLines=1; ellipsize=TextUtils.TruncateAt.END
            },
            lp(dp(110),-2)
        )
        val slider=DanmakuSlider(this,label,min,max,step,value,onValue)
        line.addView(slider,lp(0,-1).apply { weight=1f })
        group.addView(line,lp(-1,dp(TvStyle.BUTTON_NORMAL+2*TvStyle.SOFT_BAND)).apply { topMargin=dp(TvStyle.GAP_INLINE) })
        return slider to line
    }
    heading(playback,"播放偏好","所有剧集共用，播放中也可调整")
    heading(device,"片单与设备","保管记录，保持应用更新")
    // 这些设置项都是全站统一的常规按钮，声明成 TvButton 才能参与下面几栏的焦点串联。
    // 搜索源：只影响搜索页（结果、联想词、热搜词），分类 / 榜单 / 详情 / 播放仍然走 App 接口。
    // App 接口结果全、能一直翻页，但要求这台设备在服务端注册过；官网免登录，一次 10 条按页翻。
    // 放在「片单与设备」第一项：它是取内容的一条通道，与收藏 / 记录 / 版本这些「这一台机器怎么取数」同类。
    lateinit var searchSourceButton: TvButton
    searchSourceButton=setting(device,"搜索源  ·  ${library.searchSource.label}") {
        showSearchSourcePicker(searchSourceButton)
    }
    lateinit var qualityButton: TvButton
    qualityButton=setting(playback,"默认清晰度  ·  ${library.maxQuality}P") { showQualityPicker(qualityButton,false) }
    lateinit var defaultSpeed: TvButton
    defaultSpeed=setting(playback,"默认倍速  ·  ${PlaybackSpeed.label(library.playbackSpeed)}") { showSpeedPicker(defaultSpeed,false) }
    lateinit var frame: TvButton
    frame=setting(playback,"画面模式  ·  ${library.frameMode.label}") { showFramePicker(frame,false) }
    // 视频解码：硬件 / 软件。硬件省电、4K 更稳；软件兼容性更好，花屏、绿屏、
    // 音画不同步、黑屏有声这类片源问题通常能绕过。改完下一次起播生效；
    // 正在播放时从播放设置面板改，会当场重建播放器并从当前位置续播。
    lateinit var decoderButton: TvButton
    decoderButton=setting(playback,"视频解码  ·  ${library.decoderMode.label}") { showDecoderPicker(decoderButton,false) }
    lateinit var autoNextButton: TvButton
    autoNextButton=setting(playback,"自动连播  ·  ${if(library.autoNext) "开启" else "关闭"}") {
        library.autoNext=!library.autoNext
        // 关掉连播时把已经挂进队列的下一集摘掉，否则它还会在片尾自己播下去。
        if(!library.autoNext) dropQueuedEpisode()
        autoNextButton.text="自动连播  ·  ${if(library.autoNext) "开启" else "关闭"}"
    }
    lateinit var backup: TvButton
    backup=setting(device,"手机备份与恢复") {
        runCatching { LibraryBackup.encode(library.snapshot()) }.onSuccess { encoded ->
            tvTools.libraryTransfer(encoded,backup) { data,restoreSettings ->
                favoriteMonitor.stop(); library.restore(data,restoreSettings)
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
    // 以及外部存储里那两份镜像一起删）。这是唯一一个不可撤销的操作，放在第三项，
    // 与「手机备份与恢复」相邻：清之前先导出备份是最自然的顺序。
    // 名字用「缓存」是用户能理解的叫法，确认框里会写清收藏与记录也会一起清掉。
    lateinit var reset: TvButton
    reset=setting(device,resetLabel()) { clearLocalData(reset) }
    // 「版本与更新」：打开更新弹窗，可以手动检查、下载并交给系统安装。
    // 有新版本时进入应用会主动提示一次；选过「暂不更新」后不再主动提示，
    // 直到装上任何新版本（见 AppUpdater.declined）。
    lateinit var versionRow: TvButton
    versionRow=setting(device,"版本与更新  ·  ${BuildConfig.VERSION_NAME}") {
        appUpdater.show(versionRow)
    }
    lateinit var license: TvButton
    license=setting(device,"开源许可") {
        tvTools.info("开源许可",licenseText(),license)
    }
    // —— 界面设置：单独一栏，两个选项横向排列 ——
    // 「每行卡片」与「恢复隐藏的剧集」都是“列表 / 界面”层面的设置，从原来两栏里抽出来
    // 放在最上面：一个是全局排版，一个是恢复被隐藏的内容，两项并排、各占半栏。
    // 插在说明文字与下面两栏之间（parent 的第 1 个位置）。
    val ui=column().apply { clipChildren=false; clipToPadding=false }
    groupPanel(ui)
    parent.addView(ui,1,lp(-1,-2).apply { bottomMargin=dp(TvStyle.GAP_GROUP) })
    heading(ui,"界面设置","列表排版 / 隐藏内容 / 功能显示 / 调试")
    val uiRow=row().apply { clipChildren=false; clipToPadding=false }
    ui.addView(uiRow,lp(-1,-2))
    // 每行卡片数（全局）：4 / 5 / 6 / 7 / 8。首页剧架、榜单 / 分类 / 搜索 / 收藏 / 历史 / 我的 共用。
    // 列数变少时卡片变宽，封面与卡片文字一起放大（见 HomeScreen.slotWidth / textScale
    // 与 SeriesGrid.gridTextScale）。改完回到列表即按新列数排版。
    lateinit var homeColumnsButton: TvButton
    homeColumnsButton=gridSetting(uiRow,"每行卡片  ·  ${library.homeColumns} 张") { showHomeColumnsPicker(homeColumnsButton) }
    // 中间留一段弹性空白：第一格贴左、第二格贴右，两枚按钮的左右边缘分别与下面
    // 两栏里的设置项对齐（见 [alignUiButtons]）。
    uiRow.addView(Space(this),lp(0,1).apply { weight=1f })
    lateinit var hidden: TvButton
    hidden=gridSetting(uiRow,"恢复隐藏的剧集  ·  ${library.hidden().size} 部") {
        library.unhideAll(); hidden.text="恢复隐藏的剧集  ·  0 部"
    }
    // 调试模式：打开后各页面右上角常驻一块现场面板（播放信息 / 接口与登录的近况），
    // 用户反馈问题拍一张照就能定位。默认关闭；关掉时面板立刻摘掉。
    // 排在「每行卡片 / 恢复隐藏的剧集」之下、导航开关之上：它只影响「屏幕上多不多一块字」，
    // 与排版同属界面层面，但不该挤掉那两格并排的版式。
    //
    // 这一行与「打开应用进入推荐」并排，两格宽度与上面「每行卡片 / 恢复隐藏的剧集」完全一致
    // （见 [alignUiButtons]）：设置页里所有成对的开关都落在同两条竖线上。
    val uiRow2=row().apply { clipChildren=false; clipToPadding=false }
    ui.addView(uiRow2,lp(-1,-2).apply { topMargin=dp(TvStyle.GAP_BLOCK) })
    val debugRow=TvSwitchRow(this,"调试模式",checked=library.debugMode,onToggle={ on ->
        library.debugMode=on
        syncDebugOverlay()
    }).apply { tag=SETTINGS_TAG_DEBUG }
    uiRow2.addView(debugRow,lp(0,-2).apply { weight=1f })
    uiRow2.addView(Space(this),lp(0,1).apply { weight=1f })
    // 打开应用是否进入推荐：与「功能显示 → 推荐」联动（见 Library.setNavHidden / launchRecommend）。
    // 关掉「推荐」显示时这一项会被一起关掉；反过来打开这一项时，导航栏那枚入口也会恢复。
    val launchRecommendRow=TvSwitchRow(this,"打开应用进入推荐",checked=library.launchRecommend,onToggle={ on ->
        library.launchRecommend=on
        // 打开这一项时会把导航栏那枚推荐入口一起恢复（见 Library.launchRecommend），
        // 「功能显示」那一格的状态跟着变了，因此整页重建一次，并把焦点按回这一行。
        settingsPendingFocus=SETTINGS_TAG_LAUNCH_RECOMMEND
        showCatalog()
    }).apply { tag=SETTINGS_TAG_LAUNCH_RECOMMEND }
    uiRow2.addView(launchRecommendRow,lp(0,-2).apply { weight=1f })
    // 「减少动画优化性能」：**只在非低配设备上出现**。
    //
    // 低配机（2GB 档电视）本来就强制按低配档跑（见 compat/Platform.kt 的 lowEndTv），
    // 开关摆在那里永远是「已打开且不能关」，对用户没有意义，因此整行不建。
    // 非低配用户遇到发热 / 掉帧时可以自己打开它，效果与低配机完全一致：
    // 关掉焦点缩放、只给焦点卡画柔光位图、不画海报井暗角、不跑马灯、跨栏不做平滑滚动。
    //
    // 单独一行（不是塞进上面那一行变成三格）：设置页里所有成对开关都落在同两条竖线上，
    // 三格会把左右两条线一起挤歪；这一行只占左格，右格留空位，竖线保持不动。
    val reduceRow: TvSwitchRow?=if(!lowEndTv()) {
        val uiRow3=row().apply { clipChildren=false; clipToPadding=false }
        ui.addView(uiRow3,lp(-1,-2).apply { topMargin=dp(TvStyle.GAP_BLOCK) })
        val reduce=TvSwitchRow(this,"减少动画优化性能",checked=library.reduceAnim,onToggle={ on ->
            library.reduceAnim=on
            // 先落档位，再重建：已有卡片在构造时就把「是否跑马灯」捉了一份
            // （见 PosterCard.marqueeOn），只改静态开关不会作用到已经建好的那一屏。
            applyPerformanceProfile()
            settingsPendingFocus=SETTINGS_TAG_REDUCE_ANIM
            showCatalog()
        }).apply { tag=SETTINGS_TAG_REDUCE_ANIM }
        uiRow3.addView(reduce,lp(0,-2).apply { weight=1f })
        uiRow3.addView(Space(this),lp(0,1).apply { weight=1f })
        reduce
    } else null
    // 功能显示：**七枚开关排成一张四列网格**，每格都是一枚独立的开关（左：名称，右：滑块）。
    // - 前五枚是导航栏的圆形入口（推荐 / 搜索 / 分类 / 榜单 / 我的），关掉那一页就不建它；
    // - 后三枚是首页的本机记录栏目（历史记录 / 我的收藏 / 稍后观看），关掉首页就不铺那一栏。
    //
    // 两类开关放在同一张网格里，是因为它们回答的是同一个问题——「这个入口 / 这一栏要不要显示」；
    // 分成两个小标题、两张网格只会让同一件事读成两套体系，还白占一屏高度。
    // 排成四列正好两整行（4 + 4），末行不留空位。
    //
    // 设置 / 问题反馈不在这一组里，始终显示（关掉设置就再也回不来）。
    // 「推荐」与上面「打开应用进入推荐」联动：关掉它，导航栏里不再有这枚入口，
    // 打开应用也不再自动进推荐（见 Library.setNavHidden）。
    //
    // 关掉导航入口改的是页面级结构（那一枚建 / 不建），必须重建整页；
    // 先把落点记进 [settingsPendingFocus]，重建后按 `settings-nav-<key>` 找回同一格。
    // 关掉首页栏目只影响首页铺栏，就地落盘即可，不必重建。
    ui.addView(
        text("功能显示",15f,TvStyle.textDim).apply { setTypeface(null,Typeface.BOLD) },
        lp(-1,-2).apply { topMargin=dp(TvStyle.GAP_BLOCK) }
    )
    // 一行四枚与「主题外观」的芯片网格同宽（[NAV_GRID_COLUMNS]）：再挤一枚，
    // 720p 画布上每格只剩 1/5 屏，名称会被挤成省略号；四枚一行刚好放得下。
    // 最后一行不满时用空位占住列宽，剩下的那一枚不会被拉成半屏宽。
    val featureEntries=NAV_ENTRIES+HOME_ENTRIES
    val navRows=mutableListOf<List<View>>()
    val navSwitches=featureEntries.chunked(NAV_GRID_COLUMNS).map { line ->
        val navRow=row().apply { clipChildren=false; clipToPadding=false }
        ui.addView(navRow,lp(-1,-2).apply { topMargin=dp(TvStyle.GAP_INLINE) })
        // 四列固定网格：每一列（含末行补的空位）都按列号带同一个左间距，
        // 末行那一枚的宽度与上行第一枚完全相同，左边缘也落在同一条竖线上。
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
        repeat(NAV_GRID_COLUMNS-line.size) { index ->
            navRow.addView(Space(this),lp(0,1).apply {
                weight=1f
                if(line.size+index>0) leftMargin=dp(TvStyle.GAP_INLINE)
            })
        }
        navRows+=cells
        cells
    }.flatten()
    /**
     * 两格不与整栏同宽，而是**和下面两栏里的设置项同宽**：
     * 列宽 =（整行宽 − 栏间距）/ 2，再扣掉栏内左右内边距。
     *
     * 整栏通铺会让每一格半屏宽，看上去比别的设置项长一截；这里按列宽反推、
     * 摆好后校正一次，屏幕宽度变化时也跟着重算（两个按钮的左右边缘因此与
     * 「默认清晰度 / 搜索源」这两行对齐）。
     */
    fun alignUiButtons() {
        val content=groups.width
        if(content<=0) return
        val column=(content-dp(TvStyle.GAP_GROUP))/2
        val target=(column-2*(dp(24)+dp(TvStyle.SOFT_BAND))).coerceAtLeast(dp(120))
        // 五格（每行卡片 / 恢复隐藏的剧集 / 调试模式 / 打开应用进入推荐 / 减少动画）用同一个宽度：
        // 设置页里的成对控件因此落在同两条竖线上。低配设备上没有「减少动画」那一行（见上）。
        listOfNotNull(homeColumnsButton,hidden,debugRow,launchRecommendRow,reduceRow).forEach { button ->
            val params=button.layoutParams as? LinearLayout.LayoutParams ?: return@forEach
            if(params.width!=target || params.weight!=0f) {
                params.width=target
                params.weight=0f
                button.layoutParams=params
            }
        }
    }
    groups.addOnLayoutChangeListener { _,_,_,right,_,_,_,_,_ -> alignUiButtons() }
    // —— 弹幕：单独一栏，栏内四行 ——
    // 上面两列各 5 项，高度相当；弹幕这四项只对弹幕生效，抽出来单独成栏。
    //
    // 弹幕与按键两栏并排：左边弹幕、右边按键，与上面「播放偏好 / 片单与设备」同一条版式。
    // 栏内四项各占一行：显示 / 速度 / 大小 / 透明度。
    val mid=row().apply { gravity=Gravity.TOP; clipChildren=false; clipToPadding=false }
    parent.addView(mid,lp(-1,-2).apply { topMargin=dp(TvStyle.GAP_GROUP) })
    val danmaku=column().apply { clipChildren=false; clipToPadding=false }
    groupPanel(danmaku)
    mid.addView(danmaku,lp(0,-2).apply { weight=1f; rightMargin=dp(TvStyle.GAP_GROUP) })
    /**
     * 并排两栏（弹幕 / 按键设置）共用的头部：标题一行 + 说明两行。
     *
     * 两栏的头部必须**同高**，否则下面每一行都会错开半格：
     * - 按键栏标题右侧有一枚「恢复默认」圆钮（[TvStyle.BUTTON_COMPACT] + 两条柔光带 = 52dp），
     *   它比一行 18sp 文字高，会把那一栏的标题行撑起来；
     * - 两栏说明文字长短不同，行数不同同样会把下面的行推开。
     *
     * 因此这里把标题行钉死成 [headHeight]、说明行钉死成两行（过长省略）。
     * 两栏的第一项设置因此落在同一条横线上，往后每一行都按同一条节奏往下排。
     */
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
    panelHead(danmaku,"弹幕","显示方式 / 速度 / 字号 / 透明度，正在看剧时也能改")
    // 四项各占一整行：弹幕栏与按键栏并排后只剩半屏宽，再并两格会把「项名 + 当前值」
    // 挤成省略号；一行一项读起来也更像设置列表。
    // 弹幕显示方式：第一行。回调里要用的 syncDanmakuStyleRows 在下面才定义
    //（它要引用另外三项），因此这里先用空动作建好这一行，等四项齐了再补上回调。
    lateinit var danmakuSetting: TvButton
    danmakuSetting=setting(danmaku,danmakuLabel("弹幕显示  ·  ")) {}
    // 弹幕移动速度：第二行，三档（慢速 / 标准 / 快速）。
    lateinit var danmakuSpeedSetting: TvButton
    danmakuSpeedSetting=setting(danmaku,"弹幕速度  ·  ${library.danmakuSpeed.label}") {
        showDanmakuSpeedPicker(danmakuSpeedSetting,false)
    }
    // 相同弹幕聚合：默认开启，与之前的行为一致；关闭后逐条原样滚动，不再合并、不再带 ×N。
    lateinit var danmakuMergeSetting: TvButton
    danmakuMergeSetting=setting(danmaku,"相同弹幕聚合  ·  ${if(library.danmakuMerge) "开启" else "关闭"}") {
        library.danmakuMerge=!library.danmakuMerge
        danmakuView?.mergeRepeats=library.danmakuMerge
        danmakuMergeSetting.text="相同弹幕聚合  ·  ${if(library.danmakuMerge) "开启" else "关闭"}"
    }
    // 弹幕大小：整行一条滑块。
    val scaleCell=fullSlider(danmaku,"弹幕大小",DanmakuStyle.SCALE_MIN,DanmakuStyle.SCALE_MAX,DanmakuStyle.SCALE_STEP,library.danmakuScale) { value ->
        library.danmakuScale=value; danmakuView?.textScale=value
    }
    lateinit var danmakuScaleLine: View
    lateinit var danmakuScaleRow: DanmakuSlider
    danmakuScaleRow=scaleCell.first
    danmakuScaleLine=scaleCell.second
    // 弹幕透明度：整行一条滑块。
    val alphaCell=fullSlider(danmaku,"弹幕透明度",DanmakuStyle.ALPHA_MIN,DanmakuStyle.ALPHA_MAX,DanmakuStyle.ALPHA_STEP,library.danmakuAlpha) { value ->
        library.danmakuAlpha=value; danmakuView?.textAlpha=value
    }
    lateinit var danmakuAlphaLine: View
    lateinit var danmakuAlphaRow: DanmakuSlider
    danmakuAlphaRow=alphaCell.first
    danmakuAlphaLine=alphaCell.second
    // 「按键设置」那一栏的六格与「恢复默认」那一行。链的顺序是 弹幕栏 → 按键栏 → 主题网格，
    // 这里先留好引用，等下面建好那一栏再接线（见 [syncDanmakuStyleRows] 与按键栏那一段）。
    var keyCells: List<TvButton> = emptyList()
    var keyRestore: IconButton? = null
    /**
     * 弹幕栏的焦点接线（**四行始终显示**，与是否隐藏弹幕无关）。
     *
     * 以前「隐藏弹幕」会把速度 / 聚合 / 字号 / 透明度四行收起来，理由是「隐藏了改这些看不到效果」。
     * 但设置页是**配置**而不是预览：用户先把字号调好、再决定要不要显示弹幕，
     * 是常见顺序；一选隐藏就整栏塌下去，反而像「这些设置丢了」。
     * 因此这里固定 [shown] = true，四行永远在，焦点链也按完整的五行接。
     * （播放界面里的浮层是另一回事：那里是即时预览，隐藏档下那几行仍然不显示。）
     *
     * 接线要同时做四件事，缺一件都会出问题：
     * 1. 收起的单位是**行**：速度、大小、透明度三行一起 `GONE`——只藏控件会留下空行距；
     * 2. 竖排路径（显示 → 速度 → 大小 → 透明度 → 主题网格，反向逐级回去）按当前档位重指一遍
     *    ——链是单向写死的，切回可见档之后不会自己长回来；
     * 3. 横排出口跟着变：可见时四行按行号接到右边按键栏的前四行，隐藏时只有「弹幕显示」
     *    接到按键栏第一行；
     * 4. 主题网格首行的上行出口跟着变：可见时整行都回到它正上方那条「弹幕透明度」，
     *    隐藏时回到「弹幕显示」；焦点若正停在被收起的那几行上，也一并退回「弹幕显示」。
     */
    fun syncDanmakuStyleRows() {
        // 设置页四行始终显示（见上面的说明）：隐藏弹幕只影响画面，不影响这里的可配置项。
        val shown=true
        danmakuSpeedSetting.visibility=View.VISIBLE
        danmakuMergeSetting.visibility=View.VISIBLE
        danmakuScaleLine.visibility=View.VISIBLE
        danmakuAlphaLine.visibility=View.VISIBLE
        danmakuScaleRow.isFocusable=true
        danmakuAlphaRow.isFocusable=true
        val themeHead=themeRows[TvTheme.entries.first()] ?: danmakuAlphaRow
        // 弹幕栏与按键栏并排（再下面才是主题网格）：弹幕栏的下行出口直接接主题网格，
        // 按键栏的出口留在自己那一列，左右两块之间用左右键相通。
        val keyTail=keyCells.lastOrNull()?.id ?: themeHead.id
        // 弹幕五行竖排：显示 ↓ 速度 ↓ 聚合 ↓ 大小 ↓ 透明度 ↓ 主题网格。
        // 隐藏档下后四行不占位，上下都直接落回「弹幕显示」。
        danmakuSetting.nextFocusDownId=if(shown) danmakuSpeedSetting.id else themeHead.id
        danmakuSpeedSetting.nextFocusDownId=if(shown) danmakuMergeSetting.id else danmakuSetting.id
        danmakuSpeedSetting.nextFocusUpId=danmakuSetting.id
        danmakuMergeSetting.nextFocusUpId=if(shown) danmakuSpeedSetting.id else danmakuSetting.id
        danmakuMergeSetting.nextFocusDownId=if(shown) danmakuScaleRow.id else danmakuSetting.id
        danmakuScaleRow.nextFocusUpId=if(shown) danmakuMergeSetting.id else danmakuSetting.id
        danmakuScaleRow.nextFocusDownId=if(shown) danmakuAlphaRow.id else danmakuSetting.id
        danmakuAlphaRow.nextFocusUpId=if(shown) danmakuScaleRow.id else danmakuSetting.id
        danmakuAlphaRow.nextFocusDownId=themeHead.id
        // 按键六行竖排：第一行往上回到「恢复默认」圆钮，最后一行往下进主题网格。
        keyCells.forEachIndexed { index,cell ->
            cell.nextFocusUpId=keyCells.getOrNull(index-1)?.id ?: (keyRestore?.id ?: themeHead.id)
            cell.nextFocusDownId=keyCells.getOrNull(index+1)?.id ?: themeHead.id
        }
        keyRestore?.nextFocusUpId=license.id
        keyRestore?.nextFocusDownId=keyCells.firstOrNull()?.id ?: themeHead.id
        // 横排：左右两栏按行号相通（弹幕五行 → 按键前五行），其余左右键停在自身。
        danmakuSetting.nextFocusLeftId=danmakuSetting.id
        danmakuSetting.nextFocusRightId=keyCells.getOrNull(0)?.id ?: danmakuSetting.id
        danmakuSpeedSetting.nextFocusLeftId=danmakuSpeedSetting.id
        danmakuSpeedSetting.nextFocusRightId=keyCells.getOrNull(1)?.id ?: danmakuSpeedSetting.id
        danmakuMergeSetting.nextFocusLeftId=danmakuMergeSetting.id
        danmakuMergeSetting.nextFocusRightId=keyCells.getOrNull(2)?.id ?: danmakuMergeSetting.id
        danmakuScaleRow.nextFocusLeftId=danmakuScaleRow.id
        danmakuScaleRow.nextFocusRightId=keyCells.getOrNull(3)?.id ?: danmakuScaleRow.id
        danmakuAlphaRow.nextFocusLeftId=danmakuAlphaRow.id
        danmakuAlphaRow.nextFocusRightId=keyCells.getOrNull(4)?.id ?: danmakuAlphaRow.id
        keyCells.forEachIndexed { index,cell ->
            cell.nextFocusLeftId=when {
                !shown -> if(index==0) danmakuSetting.id else cell.id
                index==0 -> danmakuSetting.id
                index==1 -> danmakuSpeedSetting.id
                index==2 -> danmakuMergeSetting.id
                index==3 -> danmakuScaleRow.id
                index==4 -> danmakuAlphaRow.id
                else -> cell.id
            }
            cell.nextFocusRightId=cell.id
        }
        keyRestore?.nextFocusLeftId=danmakuSetting.id
        keyRestore?.let { it.nextFocusRightId=it.id }
        // 主题网格首行：正上方就是这一栏的最后一行（弹幕透明度；隐藏档下是弹幕显示）。
        // 主题网格第一行的正上方是「按键设置」那一栏的最后一行（那一栏还没建好时退回弹幕栏）。
        TvTheme.entries.chunked(THEME_GRID_COLUMNS).firstOrNull().orEmpty().forEach { option ->
            themeRows[option]?.nextFocusUpId=keyTail
        }
        if(!shown && (danmakuSpeedSetting.hasFocus() || danmakuMergeSetting.hasFocus() || danmakuScaleRow.hasFocus() || danmakuAlphaRow.hasFocus())) danmakuSetting.requestFocus()
    }
    danmakuSetting.setOnClickListener { showDanmakuPicker(danmakuSetting,false) { syncDanmakuStyleRows() } }
    // —— 按键设置：标题右侧「恢复默认」圆钮 + 六行设置项 ——
    // 六个键各占一行（上 / 下 / 左 / 右 / 菜单 / 确定）：每一行都要写出「键名 · 当前动作」，
    // 半屏宽里再并两格只会全都变成省略号。
    //
    // 作用域是**播放界面**：浏览页面里上下左右就是焦点移动，是遥控器的基本手感，不能改；
    // 返回键也不在其中——它在任何绑定下都必须是退出播放的出口，否则用户可能把自己锁在播放界面里。
    // 这两条边界写在 [com.hgtv.star.core.KeyMap] 的注释里，改这里之前先读那一段。
    val keys=column().apply { clipChildren=false; clipToPadding=false }
    groupPanel(keys)
    mid.addView(keys,lp(0,-2).apply { weight=1f })
    // 两栏并排后高度取较高的一栏（按键六行），矮的弹幕栏底部补齐：
    // 两块面板的底板同高，不会一高一低。只在首次布局后量一次，之后不再改。
    mid.addOnLayoutChangeListener(object: View.OnLayoutChangeListener {
        override fun onLayoutChange(v: View,left: Int,top: Int,right: Int,bottom: Int,oldLeft: Int,oldTop: Int,oldRight: Int,oldBottom: Int) {
            val height=maxOf(danmaku.height,keys.height)
            if(height<=0) return
            v.removeOnLayoutChangeListener(this)
            listOf(danmaku,keys).forEach { panel ->
                val params=panel.layoutParams as LinearLayout.LayoutParams
                if(params.height!=height) { params.height=height; panel.layoutParams=params }
            }
        }
    })
    // 标题与「恢复默认」圆钮同一行：恢复是这一栏的辅助操作，放在头部右侧，
    // 不再占一整行——六格改完想回出厂时，视线不用再往下找。
    // 头部与左边「弹幕」那一栏走同一个 [panelHead]：标题行同高、说明行同为两行，
    // 两栏的第一项设置因此对齐在同一条横线上。
    //
    // 动作先挂一个空壳：真正要做的事（恢复绑定 + 就地刷新六格文案）写在下面
    // [refreshKeyLabels] 之后——Kotlin 的局部函数不能前向引用，圆钮又要建在头部里。
    var restoreAction: ()->Unit={}
    val restore=IconButton(
        this,R.drawable.ic_action_restore,restoreKeysLabel(),TvStyle.BUTTON_COMPACT
    ) { restoreAction() }
    keyRestore=restore
    panelHead(keys,"按键设置","播放界面：上下左右 / 菜单键 / 确定键各做什么，改完立刻生效",restore)
    /**
     * 六格 + 「恢复默认」那一行的文案：改完一个键就地刷新，不重建整页。
     *
     * 只改文字（[TvButton.setLabel]），不动视图树：焦点仍停在用户刚点的那一格上，
     * 视线不会因为一次重排而跳走（清晰度 / 倍速那几行也是这么做的）。
     *
     * 它写在 [keyCell] 前面：Kotlin 的局部函数不能前向引用，而每一格的点击回调要用它。
     */
    fun refreshKeyLabels() {
        keyCells.forEachIndexed { index,cell ->
            val label=keyRowLabel(PlayerKey.entries[index])
            cell.setLabel(label); cell.contentDescription=label
        }
        keyRestore?.contentDescription=restoreKeysLabel()
    }
    /**
     * 一格按键：整栏宽的一行，文案是「键名  ·  当前动作」。
     *
     * 点击回调在按钮建好之后才挂：它要引用按钮自己（弹窗关掉时把焦点还给这一格），
     * 与弹幕那一格是同一套写法（见 [gridSetting] 与上面的 `danmakuSetting.setOnClickListener`）。
     */
    fun keyCell(key: PlayerKey): TvButton {
        val cell=setting(keys,"") {}
        cell.setOnClickListener { showKeyActionPicker(key,cell) { refreshKeyLabels() } }
        return cell
    }
    keyCells=PlayerKey.entries.map { keyCell(it) }
    keyCells.forEachIndexed { index,cell ->
        // 六行竖排：上下相邻，左右先停在自身（横向出口由 [syncDanmakuStyleRows] 接到左边弹幕栏）。
        cell.nextFocusLeftId=cell.id
        cell.nextFocusRightId=cell.id
        cell.nextFocusUpId=(keyCells.getOrNull(index-1) ?: cell).id
        cell.nextFocusDownId=(keyCells.getOrNull(index+1) ?: cell).id
    }
    // 「恢复默认按键」：标题右侧的圆形按钮（建在头部里，见上面 [panelHead] 那一段），
    // 改回出厂绑定（上 / 菜单 = 播放设置、下 = 选集、左 = 快退、右 = 快进、
    // 确定 = 播放 / 暂停）。没有文字读数，当前是否默认写在无障碍描述里
    //（[restoreKeysLabel]），恢复后刷新一次。
    restoreAction={
        library.keyMap=KeyMap.DEFAULT
        refreshKeyLabels()
        showHint("已恢复默认按键")
    }
    restore.nextFocusLeftId=danmakuSetting.id
    restore.nextFocusRightId=restore.id
    restore.nextFocusUpId=license.id
    restore.nextFocusDownId=keyCells.firstOrNull()?.id ?: restore.id
    refreshKeyLabels()
    // —— 主题外观：整宽分组 + 一行 THEME_GRID_COLUMNS 个芯片 ——
    // 20 套配色排成 5 行；芯片就是全站统一的按钮（紧凑档），
    // 前置一枚该主题的强调色圆点，选中态是「按进去 + 焦点环」，凹下去的那一枚就是当前主题。
    val appearance=column().apply { clipChildren=false; clipToPadding=false }
    groupPanel(appearance)
    parent.addView(appearance,lp(-1,-2).apply { topMargin=dp(TvStyle.GAP_GROUP) })
    heading(appearance,"主题外观","${TvTheme.entries.size} 套新拟态材质，选中即生效")
    val themeGrid=column().apply { clipChildren=false; clipToPadding=false }
    appearance.addView(themeGrid,lp(-1,-2))
    themeRows.clear()
    TvTheme.entries.chunked(THEME_GRID_COLUMNS).forEachIndexed { rowIndex, line ->
        val row=row().apply { if(rowIndex>0) setPadding(0,dp(TvStyle.GAP_INLINE),0,0) }
        line.forEachIndexed { index, option ->
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
        repeat(THEME_GRID_COLUMNS-line.size) { row.addView(Space(this),lp(0,1).apply { weight=1f }) }
        themeGrid.addView(row,lp(-1,-2))
    }
    appearance.addView(
        text("当前：${TvStyle.theme.label} · ${TvStyle.theme.scene}",13f,TvStyle.textDim)
            .apply { setPadding(0,dp(TvStyle.GAP_BLOCK),0,0) },
        lp(-1,-2)
    )
    // —— 焦点串联 ——
    // 界面栏两格：左右相连，按下分别进下面两列的第一项，按上回顶部入口。
    homeColumnsButton.nextFocusLeftId=homeColumnsButton.id
    homeColumnsButton.nextFocusRightId=hidden.id
    homeColumnsButton.nextFocusUpId=navEntry()?.id ?: homeColumnsButton.id
    homeColumnsButton.nextFocusDownId=debugRow.id
    hidden.nextFocusLeftId=homeColumnsButton.id
    hidden.nextFocusRightId=hidden.id
    hidden.nextFocusUpId=navEntry()?.id ?: hidden.id
    hidden.nextFocusDownId=launchRecommendRow.id
    // 第二行两格（调试模式 / 打开应用进入推荐）：左右相连，上行分别回第一行对应的那一格，
    // 下行分别进「功能显示」那一行的第一格 / 最后一格。
    debugRow.nextFocusLeftId=debugRow.id
    debugRow.nextFocusRightId=launchRecommendRow.id
    debugRow.nextFocusUpId=homeColumnsButton.id
    // 有「减少动画」那一行时，两格按下都先落到它（它就摆在这一行正下方、左格的位置）。
    debugRow.nextFocusDownId=reduceRow?.id ?: navSwitches.firstOrNull()?.id ?: debugRow.id
    launchRecommendRow.nextFocusLeftId=debugRow.id
    launchRecommendRow.nextFocusRightId=launchRecommendRow.id
    launchRecommendRow.nextFocusUpId=hidden.id
    // 按下去第一行的最后一格（与上行「打开应用进入推荐」同一列）。
    launchRecommendRow.nextFocusDownId=reduceRow?.id
        ?: navRows.firstOrNull()?.lastOrNull()?.id ?: launchRecommendRow.id
    // 「减少动画」单独占左格：左右都停在自己身上（右格是空位，没有落点），
    // 上行回同一列的「调试模式」，下行进「功能显示」的第一格。
    reduceRow?.let { row ->
        row.nextFocusLeftId=row.id
        row.nextFocusRightId=row.id
        row.nextFocusUpId=debugRow.id
        row.nextFocusDownId=navSwitches.firstOrNull()?.id ?: row.id
    }
    // 开关每行四枚：行内左右相连（两端停住）；上下按列号对到相邻那一行的同一格，
    // 目标行没有这一列（末行不满）时落到那一行的最后一格。
    // 第一行按上：最后一格回「打开应用进入推荐」，其余回「调试模式」（与上面两格的列位一致）。
    // 末行按下：左半屏两列进左列「播放偏好」第一项，右半屏两列进右列「片单与设备」第一项
    //（末行是四枚整行，没有缺列，不需要「落到最后一格」的兜底）。
    navRows.forEachIndexed { rowIndex,cells ->
        val above=navRows.getOrNull(rowIndex-1)
        val below=navRows.getOrNull(rowIndex+1)
        cells.forEachIndexed { index,cell ->
            cell.nextFocusLeftId=cells.getOrNull(if(index>0) index-1 else index)?.id ?: cell.id
            cell.nextFocusRightId=cells.getOrNull(if(index<cells.lastIndex) index+1 else index)?.id ?: cell.id
            cell.nextFocusUpId=when {
                above!=null -> (above.getOrNull(index) ?: above.last()).id
                reduceRow!=null -> reduceRow.id
                index==cells.lastIndex -> launchRecommendRow.id
                else -> debugRow.id
            }
            cell.nextFocusDownId=when {
                below!=null -> (below.getOrNull(index) ?: below.last()).id
                // 末行按下：左半屏两列进左列「播放偏好」第一项，右半屏两列进右列「片单与设备」第一项。
                index<NAV_GRID_COLUMNS/2 -> qualityButton.id
                else -> searchSourceButton.id
            }
        }
    }
    // 上面两列：列内上下、列间左右按行号对齐（两列各 5 项）；
    // 第一行的上行出口回到「界面设置」里对应的那一格。
    // 末项按下进弹幕那一栏的第一行（左列接「弹幕显示」、右列接「弹幕速度」，与列号对齐）。
    // 弹幕栏内部的上下左右**全部**由 [syncDanmakuStyleRows] 按当前档位指：那三行只是隐藏档下
    // 不占位，链必须先在再按档位改，否则在弹窗里切回可见档之后这几行还是选不中
    //（早先按档位裁剪列内容的写法就栽在这里）。
    val playbackItems=listOf<View>(qualityButton,defaultSpeed,frame,decoderButton,autoNextButton)
    val deviceItems=listOf<View>(searchSourceButton,backup,reset,versionRow,license)
    val columns=listOf(playbackItems,deviceItems)
    columns.forEachIndexed { column,items -> items.forEachIndexed { index,view ->
        // 第一行按上：左列（播放偏好）回到开关网格末行的第一格，右列（片单与设备）
        // 回到开关网格里最后一格，方向上是各自上方最近的那一格。
        view.nextFocusUpId=if(index==0)
            (if(column==0) navRows.last().first() else navSwitches.last()).id
        else items[index-1].id
        // 两列的末项按下：左列进弹幕栏第一行，右列进按键栏头部（两栏现在并排）。
        view.nextFocusDownId=items.getOrNull(index+1)?.id
            ?: (if(column==0) danmakuSetting.id else (keyRestore?.id ?: keyCells.firstOrNull()?.id ?: danmakuSetting.id))
    } }
    // 弹幕与按键两栏并排：第一行两格上行都回到左列末项「自动连播」，
    // 按键栏头部上行回到右列末项「开源许可」。
    // 栏内其余的上下左右出口由 [syncDanmakuStyleRows] 按当前档位指（隐藏时那三行不占位）。
    danmakuSetting.nextFocusUpId=autoNextButton.id
    danmakuSpeedSetting.nextFocusUpId=autoNextButton.id
    keyRestore?.nextFocusUpId=license.id
    columns.forEachIndexed { column,items -> items.forEachIndexed { index,view ->
        view.nextFocusLeftId=(columns.getOrNull(column-1)?.getOrNull(index) ?: view).id
        view.nextFocusRightId=(columns.getOrNull(column+1)?.getOrNull(index) ?: view).id
    } }
    // 主题网格：行内左右相连、行间上下相连，两端停住；第一行往上回到它正上方那一行，
    // 也就是弹幕那一栏的最后一行（可见档 → 「弹幕透明度」、隐藏档 → 「弹幕显示」，
    // 出口按档位现算，见 [syncDanmakuStyleRows]）。
    val themeLines=TvTheme.entries.mapNotNull { themeRows[it] }.chunked(THEME_GRID_COLUMNS)
    themeLines.forEachIndexed { rowIndex, line ->
        line.forEachIndexed { index, chip ->
            chip.nextFocusLeftId=line.getOrNull(index-1)?.id ?: chip.id
            chip.nextFocusRightId=line.getOrNull(index+1)?.id ?: chip.id
            // 第一行的上行出口交给 [syncDanmakuStyleRows] 按当前弹幕档位指过去
            // （整行都在弹幕那一栏下面，因此整行都回到它的最后一行）。
            chip.nextFocusUpId=themeLines.getOrNull(rowIndex-1)?.getOrNull(index)?.id ?: (keyRestore?.id ?: danmakuAlphaRow.id)
            chip.nextFocusDownId=themeLines.getOrNull(rowIndex+1)?.getOrNull(index)?.id ?: chip.id
        }
    }
    // 两列的末项按下都进弹幕栏（见上面的 columns 循环），这里不再单独指右半边。
    syncDanmakuStyleRows()
    navIcons().forEach { it.nextFocusDownId=homeColumnsButton.id }
    // 导航栏首位那枚「推荐」：这一页没有列表，按下键落在第一行设置项上。
    findTagged(root,"nav:recommend")?.let { it.nextFocusDownId=homeColumnsButton.id }
    parent.addView(text("红果TV  /  ${BuildConfig.VERSION_NAME}    ·    收藏与进度保存在本机",12f,muted).apply { setPadding(dp(12),dp(20),0,dp(10)) })
    // 重建后落点：开关那一行整页重建之后焦点回到它自己（见 [settingsPendingFocus]）；
    // 其余情况照旧落在顶部入口。
    val pending=settingsPendingFocus
    if(pending!=null) {
        settingsPendingFocus=null
        val target=findTagged(root,pending)
        if(target!=null) {
            // 刚建好还没测量，这一帧请求焦点会落空：推到下一帧再落。
            root.post { target.takeIf { it.attachedToWindow }?.let(::focusHead) }
            return
        }
    }
    // 圆形入口全关掉时页头没有任何可聚焦控件：落点退到「每行卡片」那一格。
    (navEntry() ?: homeColumnsButton).requestFocus()
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
 * 「开源许可」弹窗正文。
 *
 * 信息按当前仓库写：派生来源、源码仓库、GPL-3.0-only 的许可范围、第三方来源
 * （对照 `NOTICE.md`），以及本机记录的边界。发布地址指向本仓库的 Releases，
 * 不再引用已经不存在的公开镜像仓库。
 *
 * **派生来源这一条与 `NOTICE.md` 第 1 节一一对应**：fork 关系、GPL-3.0 派生、
 * 自 2026-10-06 起在上游基础上修改、上游著作权归其作者。改 `NOTICE.md` 时同步改这里。
 */
internal fun MainActivity.licenseText(): String = listOf(
    "红果TV ${BuildConfig.VERSION_NAME} · GPL-3.0-only",
    "",
    "源码与许可证：github.com/pan8664716/HGTV",
    "  许可证全文见 LICENSE，第三方声明见 NOTICE.md",
    "",
    "派生自 N3urda/hongguoTV（GPL-3.0），自 2026-10-06 起在上游基础上修改；",
    "  上游著作权归其作者，逐项改动见 NOTICE.md 与 docs/CHANGELOG.md。",
    "",
    "内容协议与加密处理移植自 drpys（commit 22261ad，GPL-3.0）。",
    "",
    "第三方组件（完整清单见 NOTICE.md）：",
    "  Kotlin / AndroidX Media3 / OkHttp / ZXing：Apache-2.0",
    "  Bouncy Castle / Microsoft Fluent Emoji：MIT",
    "  desugar_jdk_libs：GPL-2.0 with Classpath Exception",
    "",
    "本机记录（收藏 / 观看进度 / 设置）只保存在这台设备上，不上传。"
).joinToString("\n")
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
