// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv.list
import android.app.Activity
import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*
import com.hgtv.star.core.*
import com.hgtv.star.shared.compat.onScrollYChanged
import com.hgtv.star.shared.compat.attachedToWindow
import com.hgtv.star.tv.ButtonSize
import com.hgtv.star.tv.IconButton
import com.hgtv.star.tv.ToggleChip
import com.hgtv.star.tv.openRecommend
import com.hgtv.star.tv.findTagged
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.tv.focusBleed
import com.hgtv.star.tv.tvScrollHost
import com.hgtv.star.tv.ACCOUNT_TABS
import com.hgtv.star.tv.CHIP_ROW_HEIGHT
import com.hgtv.star.tv.RANK_NAV_ACTION_GAP
import com.hgtv.star.tv.FILTER_ROW_HEIGHT
import com.hgtv.star.tv.gridColumns
import com.hgtv.star.tv.LOAD_MORE
import com.hgtv.star.tv.TvMainActivity
import com.hgtv.star.tv.TAB_CATEGORY
import com.hgtv.star.tv.TAB_HOME
import com.hgtv.star.tv.TAB_PROFILE
import com.hgtv.star.tv.TAB_RANK
import com.hgtv.star.tv.TAB_SEARCH
import com.hgtv.star.tv.TAB_SETTINGS
import com.hgtv.star.tv.accountItems
import com.hgtv.star.tv.accountNotice
import com.hgtv.star.tv.registerDeviceOnce
import com.hgtv.star.tv.rememberCategoryState
import com.hgtv.star.tv.rememberRankState
import com.hgtv.star.tv.addButton
import com.hgtv.star.tv.appendCatalog
import com.hgtv.star.tv.autoAppendCatalog
import com.hgtv.star.tv.ACCOUNT_LOGIN_MODES
import com.hgtv.star.tv.base
import com.hgtv.star.tv.noteDebugProblem
import com.hgtv.star.tv.clearDebugProblem
import com.hgtv.star.tv.chipControl
import com.hgtv.star.tv.column
import com.hgtv.star.tv.dp
import com.hgtv.star.tv.firstHeadControl
import com.hgtv.star.tv.categoryDescription
import com.hgtv.star.tv.categoryFilterWidth
import com.hgtv.star.tv.fitSearchRow
import com.hgtv.star.tv.focusHead
import com.hgtv.star.tv.fullList
import com.hgtv.star.tv.gapInto
import com.hgtv.star.shared.data.NAV_KEYS
import com.hgtv.star.shared.data.account
import com.hgtv.star.shared.data.NAV_KEY_RECOMMEND
import com.hgtv.star.tv.gridContentWidth
import com.hgtv.star.tv.homeShelves
import com.hgtv.star.tv.inlineHeadChips
import com.hgtv.star.tv.invalidatePage
import com.hgtv.star.tv.loadAccount
import com.hgtv.star.tv.loadHotSearches
import com.hgtv.star.tv.localCatalog
import com.hgtv.star.tv.localItems
import com.hgtv.star.tv.loading
import com.hgtv.star.tv.loadingView
import com.hgtv.star.tv.lp
import com.hgtv.star.tv.message
import com.hgtv.star.tv.historyClearButton
import com.hgtv.star.tv.navEntry
import com.hgtv.star.tv.navIcons
import com.hgtv.star.tv.pageInset
import com.hgtv.star.tv.pageBleed
import com.hgtv.star.tv.pageTitle
import com.hgtv.star.tv.profileHead
import com.hgtv.star.tv.profileHeadControl
import com.hgtv.star.tv.releaseCatalogViews
import com.hgtv.star.tv.renderHome
import com.hgtv.star.tv.restoreChipFocus
import com.hgtv.star.tv.row
import com.hgtv.star.tv.scheduleSuggestions
import com.hgtv.star.tv.searchBar
import com.hgtv.star.tv.HongguoKeyboard
import com.hgtv.star.tv.hongguoKeyboardMode
import com.hgtv.star.tv.renderHongguoSideList
import com.hgtv.star.tv.wireHongguoKeyboardFocus
import com.hgtv.star.tv.appendHongguoDraft
import com.hgtv.star.tv.setHongguoDraft
import com.hgtv.star.tv.searchHongguoDraft
import com.hgtv.star.tv.setSearchKeyboard
import com.hgtv.star.tv.hideSystemKeyboard
import com.hgtv.star.tv.settings
import com.hgtv.star.tv.SETTINGS_TABS
import com.hgtv.star.tv.SETTINGS_TAG_TAB_PREFIX
import com.hgtv.star.tv.SETTINGS_CHIP_INLINE_MIN_WIDTH
import com.hgtv.star.tv.SUGGEST_COUNT
import com.hgtv.star.tv.SUGGEST_DEBOUNCE
import com.hgtv.star.tv.widthDp
import com.hgtv.star.tv.switchSettingsTab
import com.hgtv.star.tv.showHint
import com.hgtv.star.tv.selectLoginMode
import com.hgtv.star.tv.switchAccountTab
import com.hgtv.star.tv.selectCategoryFilter
import com.hgtv.star.tv.switchRankBoard
import com.hgtv.star.tv.switchTab
import com.hgtv.star.tv.text
import com.hgtv.star.tv.wireChipRow
import com.hgtv.star.tv.wireHeadNavUp
import com.hgtv.star.tv.work
import com.hgtv.star.tv.error
import com.hgtv.star.tv.bindUpdateDot
import com.hgtv.star.tv.showUpdateDialog


internal fun equalChipWidths(chips: List<View>) {
    if(chips.size<2) return
    val spec=View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED)
    val widest=chips.maxOf { chip -> chip.measure(spec,spec); chip.measuredWidth }
    if(widest<=0) return
    chips.forEach { chip ->
        val params=chip.layoutParams as? LinearLayout.LayoutParams ?: return@forEach
        if(params.width==widest) return@forEach
        params.width=widest
        chip.layoutParams=params
    }
}
fun TvMainActivity.showCatalog(
    load: Boolean=false,
    focusNav: Boolean=false,
    focusType: Boolean=false,
    keepChip: Boolean=false,
    keepCatalog: Boolean=false,
) {
    if(screen=="recommend") return
    if(!load && !keepChip) { focusFirstCardOnLanding=false; chipFocusKey=null }
    initialCatalogPending=false; restoreStoppedPage=false; pendingDetail=null
    val hongguo=tab==TAB_SEARCH && collection==null && hongguoKeyboardMode()
    if(hongguo) { topArea.clear(); topAreaHidden=false; keepTopAreaState=false }
    val appending=load && appendCatalog() && page>1 && catalog.isNotEmpty()
    val home=tab==TAB_HOME && collection==null && page==1
    if(load) {
        if(home) {
            val cached=RankBoard.entries.mapNotNull { board -> library.cachedHome(board)?.let { board to it } }.toMap()
            homeFeed=LinkedHashMap(cached)
            homeFromCache=cached.isNotEmpty()
            catalog=emptyList(); hasMore=false; ranking=null
        } else if(!appending && chipFocusKey==null && !keepCatalog) { catalog=emptyList(); hasMore=false; ranking=null }
    }
    if(!keepTopAreaState) prefetchChain=0
    invalidatePage(); screen="catalog"; releaseCatalogViews(); val container=base()
    val ownsChip=when(tab) {
        TAB_RANK -> chipFocusKey?.startsWith("board:")==true
        TAB_CATEGORY -> chipFocusKey?.startsWith("filter:")==true
        TAB_PROFILE -> chipFocusKey?.startsWith("account:")==true
        else -> false
    }
    if(!ownsChip) chipFocusKey=null
    if(foreground && library.isLoaded && !favoriteMonitor.running) main.post { if(foreground && screen=="catalog") favoriteMonitor.check() }
    val top=row().apply {
        setPadding(pageBleed(),0,pageBleed()-dp(TvStyle.SOFT_BAND),0)
    }
    val searchPage=tab==TAB_SEARCH && collection==null
    hongguoSwitch=null
    if(!searchPage || !hongguo) top.addView(text(pageTitle(),30f,TvStyle.textStrong).apply {
        setTypeface(typeface,Typeface.BOLD)
        setPadding(0,0,dp(4),0)
    },lp(-2,-2))
    val filterSlot=row()
    if(!searchPage) gapInto(top,filterSlot,lp(-2,-2),TvStyle.GAP_GROUP)
    if(tab==TAB_SEARCH) searchBar(top,query.isBlank() && !load && !focusNav)
    val topSpacer=if(tab==TAB_SEARCH) null else Space(this).also {
        top.addView(it,lp(0,1).apply { weight=1f })
    }
    var historyFilterEnd: View?=null
    if(collection=="history") {
        historyClearInline=true
        historyFilterButtons.clear()
        var previous: View?=null
        listOf<Pair<Boolean,String>>(true to "已看完",false to "未看完").forEach { (value,label) ->
            val chip=ToggleChip(this,label,accountHistoryFilter==value,ButtonSize.COMPACT) {
                accountHistoryFilter=if(accountHistoryFilter==value) null else value
                historyFilterButtons.forEach { (state,view) -> (view as? ToggleChip)?.setActive(accountHistoryFilter==state) }
                showCatalog()
            }
            historyFilterButtons[value]=chip
            gapInto(filterSlot,chip,lp(-2,-2),TvStyle.GAP_INLINE)
            previous?.let { prev ->
                prev.nextFocusRightId=chip.id
                chip.nextFocusLeftId=prev.id
            }
            previous=chip
            historyFilterEnd=chip
        }
        historyFilterButtons[true]?.let { first -> first.nextFocusLeftId=first.id }
    }
    if(collection=="history") {
        historyClearButton(top,ButtonSize.COMPACT,gap=TvStyle.GAP_BLOCK)
    }
    val recommendShown=!library.navHidden(NAV_KEY_RECOMMEND)
    val recommendIcon=if(recommendShown)
        IconButton(this,com.hgtv.star.tv.R.drawable.ic_toolbar_recommend,"推荐") { openRecommend() }
    else null
    recommendIcon?.tag="nav:recommend"
    this.recommendIcon=recommendIcon
    val recommendHolder=if(recommendIcon!=null) FrameLayout(this).apply {
        clipChildren=false; clipToPadding=false
        addView(recommendIcon,FrameLayout.LayoutParams(-2,-2))
    } else null
    if(recommendHolder!=null) gapInto(top,recommendHolder,lp(-2,-2),TvStyle.GAP_TIGHT)
    val entries=listOf(
        "search" to Triple(com.hgtv.star.tv.R.drawable.ic_toolbar_search,"搜索",{ switchTab(TAB_SEARCH) }),
        "category" to Triple(com.hgtv.star.tv.R.drawable.ic_toolbar_category,"分类",{ switchTab(TAB_CATEGORY) }),
        "rank" to Triple(com.hgtv.star.tv.R.drawable.ic_toolbar_rank,"榜单",{ switchTab(TAB_RANK) }),
        "settings" to Triple(com.hgtv.star.tv.R.drawable.ic_toolbar_settings,"设置",{ switchTab(TAB_SETTINGS) }),
        "profile" to Triple(com.hgtv.star.tv.R.drawable.ic_toolbar_profile,"我的",{ switchTab(TAB_PROFILE) })
    )
    val hiddenNav=library.hiddenNav
    entries.forEachIndexed { index,entry ->
        if(entry.first in hiddenNav && entry.first in NAV_KEYS) return@forEachIndexed
        val item=IconButton(this,entry.second.first,entry.second.second) { entry.second.third() }
        val active=index+1==tab
        item.setActive(active)
        val dot=View(this).apply {
            background=GradientDrawable().apply {
                shape=GradientDrawable.OVAL
                setColor(TvStyle.accent)
            }
            visibility=if(active) View.VISIBLE else View.GONE
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val holder=FrameLayout(this).apply { clipChildren=false; clipToPadding=false }
        holder.addView(item,FrameLayout.LayoutParams(-2,-2))
        dot.translationY=dp(7).toFloat()
        holder.addView(dot,FrameLayout.LayoutParams(dp(6),dp(6),Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        nav+=item
        navKeyed[entry.first]=item
        gapInto(top,holder,lp(-2,-2),TvStyle.GAP_TIGHT)
    }
    val updateButton=IconButton(this,com.hgtv.star.tv.R.drawable.ic_toolbar_update,"更新") {
        showUpdateDialog(updateIcon)
    }
    updateButton.tag="nav:update"
    val updateHolder=FrameLayout(this).apply {
        clipChildren=false; clipToPadding=false
        addView(updateButton,FrameLayout.LayoutParams(-2,-2))
    }
    val updateDot=View(this).apply {
        background=GradientDrawable().apply {
            shape=GradientDrawable.OVAL
            setColor(0xFFE5484D.toInt())
        }
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    updateHolder.addView(updateDot,FrameLayout.LayoutParams(dp(7),dp(7),Gravity.END or Gravity.TOP).apply {
        topMargin=dp(4)
        rightMargin=dp(4)
    })
    this.updateIcon=updateButton
    this.updateDot=updateDot
    bindUpdateDot()
    gapInto(top,updateHolder,lp(-2,-2),TvStyle.GAP_TIGHT)
    val updateLeft=nav.lastOrNull() ?: recommendIcon
    if(updateLeft!=null) {
        updateLeft.nextFocusRightId=updateButton.id
        updateButton.nextFocusLeftId=updateLeft.id
    } else {
        updateButton.nextFocusLeftId=updateButton.id
    }
    navIcons().forEach { it.nextFocusUpId=it.id }
    if(recommendIcon!=null && recommendHolder!=null) run {
        val right=nav.firstOrNull() ?: updateIcon
        val own=top.indexOfChild(recommendHolder)
        val left=(own-1 downTo 0).firstNotNullOfOrNull { index -> lastFocusable(top.getChildAt(index)) }
            ?: recommendIcon
        recommendIcon.nextFocusUpId=recommendIcon.id
        recommendIcon.nextFocusRightId=right?.id ?: recommendIcon.id
        if(right!=null) right.nextFocusLeftId=recommendIcon.id
        recommendIcon.nextFocusLeftId=left.id
        if(left!==recommendIcon) left.nextFocusRightId=recommendIcon.id
    }
    if(collection=="history") {
        val afterClear=updateIcon ?: recommendIcon ?: nav.firstOrNull()
        val right=historyClear ?: afterClear
        historyFilterEnd?.nextFocusRightId=right?.id ?: historyFilterEnd.id
        historyClear?.let { clear ->
            clear.nextFocusLeftId=(historyFilterEnd ?: clear).id
            clear.nextFocusRightId=afterClear?.id ?: clear.id
            clear.nextFocusUpId=clear.id
        }
        if(historyClear==null) afterClear?.nextFocusLeftId=(historyFilterEnd ?: afterClear).id
    }
    container.addView(top,lp(-1,dp(TvStyle.TOOLBAR_HEIGHT)))
    if(!hongguo) registerTopAreaRow(top)
    if(tab==TAB_SEARCH) wireHeadNavUp()
    if(tab==TAB_SEARCH) fitSearchRow(top)
    if(tab==TAB_PROFILE) {
        if(library.account().takeIf { it.configured }==null) {
            val modeChips=mutableListOf<View>()
            loginModeChips.clear()
            ACCOUNT_LOGIN_MODES.forEach { (mode,label) ->
                val chip=ToggleChip(this,label,loginMode==mode) { selectLoginMode(mode,loginModeChips[mode]) }
                loginModeChips[mode]=chip
                gapInto(filterSlot,chip,lp(-2,-2),TvStyle.GAP_INLINE)
                modeChips+=chip
            }
            equalChipWidths(modeChips)
            wireChipRow(modeChips,{ modeChips[it] },updateIcon ?: recommendIcon ?: nav.firstOrNull())
            val panelHost=column().apply {
                visibility=View.GONE
                gravity=Gravity.CENTER_HORIZONTAL
                clipChildren=false
                clipToPadding=false
            }
            accountPanelHost=panelHost
            val stage=FrameLayout(this).apply {
                clipChildren=false
                clipToPadding=false
                addView(panelHost,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
            }
            container.addView(stage,lp(-1,0).apply { weight=1f })
            selectLoginMode(loginMode,loginModeChips[loginMode] ?: modeChips.firstOrNull())
            val landing=loginModeChips[loginMode]
            if(landing!=null) landing.post {
                if(!landing.attachedToWindow) return@post
                if(root.findFocus()==null) focusHead(landing)
            }
            return
        }
        val headRow=row().apply { setPadding(pageBleed(),0,pageBleed()-dp(TvStyle.SOFT_BAND),dp(4)) }
        profileHead(headRow)
        profileHeadRow=headRow
        container.addView(headRow); registerTopAreaRow(headRow)
        val panelHost=column().apply { visibility=View.GONE }
        accountPanelHost=panelHost
        container.addView(panelHost,lp(-1,-2).apply { topMargin=dp(TvStyle.GAP_INLINE) })
    }
    if(tab==TAB_SETTINGS) {
        val inlineTabs=inlineHeadChips() && widthDp()>=SETTINGS_CHIP_INLINE_MIN_WIDTH
        val chipHost=if(inlineTabs) filterSlot else row().apply {
            setPadding(pageBleed(),dp(4),pageBleed(),dp(4))
            gravity=Gravity.CENTER_VERTICAL
        }
        settingsTabChips.clear()
        val chips=mutableListOf<View>()
        SETTINGS_TABS.forEachIndexed { index,label ->
            val chip=ToggleChip(this,label,settingsTab==index,ButtonSize.CHIP) { switchSettingsTab(index) }
            chip.tag=SETTINGS_TAG_TAB_PREFIX+index
            chip.contentDescription=label+if(settingsTab==index) "，当前分区" else ""
            gapInto(chipHost,chip,lp(-2,-2),TvStyle.GAP_INLINE)
            settingsTabChips+=chip
            chips+=chip
        }
        wireChipRow(
            chips,{ chips[it] },if(inlineTabs) updateIcon ?: recommendIcon ?: nav.firstOrNull() else null,
            onDown={ index ->
                if(settingsTab!=index) { switchSettingsTab(index,focusContent=true); true } else false
            }
        )
        if(!inlineTabs) {
            container.addView(chipHost)
            registerTopAreaRow(chipHost)
        }
        val scroll=ScrollView(this).focusBleed(); val body=column(); scroll.addView(body); container.addView(tvScrollHost(scroll),lp(-1,0).apply { weight=1f })
        listScrollY=scroll.scrollY
        settingsScroll=scroll
        scroll.onScrollYChanged { y -> onListScroll(y) }
        settings(body)
        if(focusNav) navEntry()?.requestFocus()
        return
    }
    if(tab==TAB_PROFILE) {
        val chips=mutableListOf<View>()
        ACCOUNT_TABS.forEach { entry ->
            val chip=ToggleChip(this,entry.second,accountTab==entry.first) { switchAccountTab(entry.first) }
            accountButtons[entry.first]=chip
            chips+=chip
        }
        val afterChips=if(inlineHeadChips()) updateIcon ?: recommendIcon ?: nav.firstOrNull() else null
        wireChipRow(chips,{ chips[it] },afterChips)
        if(inlineHeadChips()) {
            chips.forEach { gapInto(filterSlot,it,lp(-2,-2),TvStyle.GAP_INLINE) }
        } else {
            val chipRow=row().apply { setPadding(pageBleed(),0,pageBleed(),dp(4)) }
            chips.forEach { gapInto(chipRow,it,lp(-2,-2)) }
            container.addView(chipRow); registerTopAreaRow(chipRow)
        }
        val headDown=profileHeadControl()?.id ?: accountButtons.getValue(accountTab).id
        navIcons().forEach { it.nextFocusDownId=headDown }
        findTagged(root,"nav:recommend")?.let { it.nextFocusDownId=headDown }
        accountButtons.values.forEach { it.nextFocusDownId=headDown }
        restoreChipFocus("account:")
    }
    if(tab==TAB_RANK) {
        // 与分类页的体裁芯片使用同一父级起点；横向不能再叠加 pageBleed。
        val boards=row().apply { setPadding(0,0,0,dp(4)) }
        val boardScroll=HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled=false
            isFillViewport=false
            clipChildren=false; clipToPadding=false
            overScrollMode=View.OVER_SCROLL_NEVER
            addView(boards)
        }
        val boardViewport=ClipFrameLayout(this).apply {
            clipChildren=true; clipToPadding=true
            addView(boardScroll,FrameLayout.LayoutParams(-1,-1))
        }
        val boardChips=mutableListOf<View>()
        RankBoard.navEntries.forEach { board ->
            val chip=ToggleChip(this,board.label,rankBoard==board) { switchRankBoard(board) }
            boardButtons[board]=chip
            boardChips+=chip
            gapInto(boards,chip,lp(-2,-2))
        }
        // 视口占满标题与右侧圆形入口之间的固定剩余宽度，芯片只在内部向右滚动。
        topSpacer?.let { spacer ->
            (spacer.layoutParams as? LinearLayout.LayoutParams)?.apply {
                width=0
                weight=0f
                spacer.layoutParams=this
            }
        }
        (filterSlot.layoutParams as? LinearLayout.LayoutParams)?.apply {
            width=0
            weight=1f
            rightMargin=dp(RANK_NAV_ACTION_GAP)
            filterSlot.layoutParams=this
        }
        val afterBoards=updateIcon ?: recommendIcon ?: nav.firstOrNull()
        wireChipRow(boardChips,{ boardChips[it] },afterBoards)
        filterSlot.addView(
            boardViewport,
            lp(-1,dp(CHIP_ROW_HEIGHT)),
        )
        navIcons().forEach { it.nextFocusDownId=boardButtons.getValue(rankBoard).id }
        boardButtons.values.forEach { it.nextFocusDownId=it.id }
        restoreChipFocus("board:")
    }
    if(tab==TAB_SEARCH && !hongguo) {
        val hintScroll=HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled=false
            clipChildren=false; clipToPadding=false
            isFillViewport=true
            overScrollMode=View.OVER_SCROLL_NEVER
        }
        val hintLine=row().apply { gravity=Gravity.CENTER_VERTICAL }
        hintScroll.addView(hintLine)
        val hintViewport=ClipFrameLayout(this).apply {
            addView(hintScroll,FrameLayout.LayoutParams(-1,-1))
            visibility=View.GONE
        }
        container.addView(
            hintViewport,
            lp(gridContentWidth(),dp(CHIP_ROW_HEIGHT)).apply { leftMargin=pageBleed() }
        )
        registerTopAreaRow(hintViewport)
        suggestRow=hintScroll; suggestLine=hintLine
        suggestViewport=hintViewport
        if(query.isBlank()) loadHotSearches() else scheduleSuggestions(query)
    } else if(tab==TAB_CATEGORY) {
        val genreChips=mutableListOf<View>()
        run {
            val genreGroup=row()
            val buttons=categoryFilterButtons.getOrPut(CategorySelection.TYPE_GENRE) { mutableMapOf() }
            buttons.clear()
            CategoryGenres.options.forEach { option ->
                val chip=ToggleChip(this,option.label,categorySelection.genre==option.id,ButtonSize.NORMAL) {
                    selectCategoryFilter(CategorySelection.TYPE_GENRE,option.id)
                }
                buttons[option.id.orEmpty()]=chip
                genreChips+=chip
                gapInto(genreGroup,chip,lp(-2,-2))
            }
            filterSlot.addView(genreGroup)
            wireChipRow(genreChips,{ genreChips[it] },updateIcon ?: recommendIcon ?: nav.firstOrNull())
        }
        val panel=categoryPanel
        val rowChips=mutableListOf<MutableList<View>>()
        if(panel==null) {
            val tip=loadingView(32)
            container.addView(
                tip,
                lp(-1,dp(32)).apply { leftMargin=pageBleed(); rightMargin=pageBleed() }
            )
            registerTopAreaRow(tip)
        } else {
            val filterRows=panel.visibleRows()
            filterRows.forEach { filterRow ->
                val rowWidth=categoryFilterWidth()
                val labelWidth=dp(46)
                val line=FrameLayout(this).apply {
                    clipChildren=true; clipToPadding=true
                }
                val title=filterRow.title.removePrefix("全部").ifBlank { filterRow.title }
                val label=text(title,12f,muted).apply {
                    gravity=Gravity.CENTER_VERTICAL
                    maxLines=1
                    ellipsize=android.text.TextUtils.TruncateAt.END
                    setPadding(0,0,dp(6),0)
                }
                line.addView(label,FrameLayout.LayoutParams(labelWidth,-1))
                val chipsLine=row().apply { gravity=Gravity.CENTER_VERTICAL }
                val chips=mutableListOf<View>()
                val buttons=categoryFilterButtons.getOrPut(filterRow.type) { mutableMapOf() }
                buttons.clear()
                run {
                    val active=categorySelection.valueOf(filterRow.type)==null
                    val chip=ToggleChip(this,"全部",active,ButtonSize.DENSE) {
                        selectCategoryFilter(filterRow.type,null)
                    }
                    buttons[""]=chip
                    chips+=chip
                    gapInto(chipsLine,chip,lp(-2,-2),6)
                }
                filterRow.options.forEach { option ->
                    val active=categorySelection.valueOf(filterRow.type)==option.id
                    val chip=ToggleChip(this,option.label,active,ButtonSize.DENSE) {
                        selectCategoryFilter(filterRow.type,option.id)
                    }
                    buttons[option.id]=chip
                    chips+=chip
                    gapInto(chipsLine,chip,lp(-2,-2),6)
                }
                val scroll=HorizontalScrollView(this).apply {
                    isHorizontalScrollBarEnabled=false
                    clipChildren=true; clipToPadding=true
                    overScrollMode=View.OVER_SCROLL_NEVER
                    isFillViewport=true
                }
                scroll.addView(chipsLine)
                val viewport=ClipFrameLayout(this)
                viewport.addView(scroll,FrameLayout.LayoutParams(-1,-1))
                line.addView(
                    viewport,
                    FrameLayout.LayoutParams(rowWidth-labelWidth,-1).apply { leftMargin=labelWidth }
                )
                container.addView(
                    line,
                    lp(rowWidth,dp(FILTER_ROW_HEIGHT)).apply { leftMargin=pageBleed() }
                )
                registerTopAreaRow(line)
                rowChips+=chips
            }
        }
        val firstFilter=rowChips.firstOrNull()?.firstOrNull()
        genreChips.forEach { chip -> firstFilter?.let { chip.nextFocusDownId=it.id } ?: run { chip.nextFocusDownId=chip.id } }
        rowChips.forEachIndexed { index, chips ->
            val up=if(index==0) genreChips.firstOrNull() else rowChips.getOrNull(index-1)?.firstOrNull()
            wireChipRow(chips,{ up ?: chips[it] })
            chips.forEach { chip ->
                chip.nextFocusDownId=rowChips.getOrNull(index+1)?.firstOrNull()?.id ?: chip.id
            }
        }
        val down=categoryFilterButtons[CategorySelection.TYPE_GENRE]?.get(categorySelection.genre.orEmpty())
            ?: genreChips.firstOrNull()
        if(down!=null) {
            navIcons().forEach { it.nextFocusDownId=down.id }
            findTagged(root,"nav:recommend")?.let { it.nextFocusDownId=down.id }
        }
        restoreChipFocus("filter:")
    } else if(tab==TAB_RANK) {
        val subtitle=text(ranking?.updatedText.orEmpty(),13f,muted)
            .apply {
                setPadding(pageBleed(),0,pageBleed(),dp(8))
                visibility=if(text.isBlank()) View.GONE else View.VISIBLE
            }
        rankSubtitle=subtitle
        container.addView(subtitle); registerTopAreaRow(subtitle)
    }
    val body=column(); catalogBody=body
    if(hongguo) {
        val columns=row().apply { gravity=Gravity.TOP }
        val keyboardColumn=column()
        val sideColumn=column().apply {
            setPadding(dp(TvStyle.GAP_TIGHT),0,dp(TvStyle.GAP_TIGHT),dp(TvStyle.GAP_INLINE))
        }
        val resultsColumn=column()
        val keyboard=HongguoKeyboard(this,
            onLetter={ letter -> appendHongguoDraft(letter) },
            onDelete={ setHongguoDraft(hongguoDraft.dropLast(1)) },
            onClear={ setHongguoDraft("") },
            onSearch={ searchHongguoDraft() },
            onPhoneInput={
                tvTools.phoneInput(hongguoKeyboard?.view) { word ->
                    showHint("已从手机收到「$word」")
                    setHongguoDraft(word)
                    searchHongguoDraft()
                }
            },
            onSwitchKeyboard={ setSearchKeyboard(false) })
        keyboard.draft=hongguoDraft
        keyboardColumn.addView(keyboard.view,LinearLayout.LayoutParams(-1,-1))
        val sideScroll=ScrollView(this).apply {
            isVerticalScrollBarEnabled=false
            clipChildren=false; clipToPadding=false
            val bleed=dp(TvStyle.FOCUS_BLEED)
            setPadding(bleed,dp(2),bleed,dp(TvStyle.FOCUS_BLEED_VERTICAL))
        }
        val sideList=column()
        sideScroll.addView(sideList)
        sideColumn.addView(tvScrollHost(sideScroll),LinearLayout.LayoutParams(-1,-1))
        columns.addView(keyboardColumn,LinearLayout.LayoutParams(0,-1,2f))
        columns.addView(View(this).apply { setBackgroundColor(TvStyle.separator) },LinearLayout.LayoutParams(dp(1),-1))
        columns.addView(sideColumn,LinearLayout.LayoutParams(0,-1,1f))
        columns.addView(View(this).apply { setBackgroundColor(TvStyle.separator) },LinearLayout.LayoutParams(dp(1),-1))
        columns.addView(resultsColumn,LinearLayout.LayoutParams(0,-1,4f))
        container.addView(columns,lp(-1,0).apply {
            weight=1f
            leftMargin=-(pageInset()-pageBleed())
        })
        hongguoKeyboard=keyboard
        hongguoHistoryColumn=sideList
        hongguoResultsColumn=resultsColumn
        resultsColumn.addView(body,LinearLayout.LayoutParams(-1,0).apply { weight=1f })
        searchController.refreshHistory()
        renderHongguoSideList()
        if(hongguoDraft.isNotBlank()) searchController.scheduleSuggestions(hongguoDraft,SUGGEST_COUNT,SUGGEST_DEBOUNCE)
        keyboard.view.post { wireHongguoKeyboardFocus() }
        keyboard.view.post { if(hongguoKeyboardMode() && screen=="catalog" && tab==TAB_SEARCH) hideSystemKeyboard() }
    } else container.addView(body,lp(-1,0).apply { weight=1f })
    if(localCatalog()) {
        catalog=localItems(); ranking=null; page=1; hasMore=false
        if(collection!=null && accountData==null && accountError==null) loadAccount()
    }
    if(load && !localCatalog()) {
        if(page==1) catalogPageOneFresh=false
        val stale=(chipFocusKey!=null || keepCatalog) && appendCatalog() && catalog.isNotEmpty()
        val holdChip=chipFocusKey!=null
        if(appending) seriesList(body,focusNav,focusType,loadingMore=true)
        else if(home) seriesList(body,focusNav,focusType,loading=true)
        else if(stale) seriesList(body,if(holdChip) false else focusNav,focusType,keepScroll=scrollPositions[catalogPageKey])
        else {
            loading(body)
            val chip=chipControl(chipFocusKey)
            if(chip!=null) chip.requestFocus()
            else if(focusType || focusFirstCardOnLanding) firstHeadControl()?.requestFocus()
        }
        val requestTab=tab; val requestPage=page; val requestQuery=query; val requestBoard=rankBoard
        val requestSource=searchController.state.value.source
        val requestCategory=categorySelection
        val needPanel=categoryPanel==null
        val panelRef=java.util.concurrent.atomic.AtomicReference<CategoryPanel?>()
        work({
            when(requestTab) {
                TAB_HOME -> repository.homeFeed()
                TAB_RANK -> mapOf(requestBoard to repository.rank(requestPage,requestBoard))
                TAB_CATEGORY -> {
                    val feed=repository.categoryFeed(requestCategory,requestPage,needPanel)
                    panelRef.set(feed.panel)
                    mapOf(requestBoard to feed.page)
                }
                else -> {
                    if(!library.deviceRegistered()) registerDeviceOnce()
                    mapOf(requestBoard to searchController.pageBlocking(requestQuery,requestPage,requestSource))
                }
            }
        }, { result ->
            if(requestTab==TAB_HOME) {
                val first=homeFeed.isEmpty()
                clearDebugProblem()
                homeFeed=LinkedHashMap(result)
                result.forEach { (board,page) ->
                    library.cacheHome(board,page.items,page.hasMore,page.ranking)
                }
                val keepNav=nav.any { it.hasFocus() }
                val homeView=homeScreen
                focusFirstCardOnLanding=false
                if(first || homeView==null) {
                    body.removeAllViews(); seriesList(body,keepNav || focusNav)
                } else {
                    val shelves=homeShelves()
                    val requestHomeFocus=!keepNav && !focusNav && !homeFocusRequested
                    val structural=shelves.flatMap { shelf ->
                        shelf.entries.map { "${shelf.key}:${it.series.id}" } +
                            listOfNotNull(shelf.more?.let { "${shelf.key}:more" })
                    }.toSet()
                    if(structural==homeView.cardKeys()) {
                        homeView.refreshEntries(shelves)
                        if(requestHomeFocus && !homeView.hasFocus()) {
                            homeView.firstCard()?.requestFocus()
                            if(homeView.firstCard()!=null) homeFocusRequested=true
                        }
                    } else {
                        homeView.updateShelves(shelves,requestFocus=homeView.hasFocus() || requestHomeFocus)
                        if(requestHomeFocus && homeView.firstCard()!=null) homeFocusRequested=true
                    }
                }
                return@work
            }
            val page=result[requestBoard] ?: CatalogPage(emptyList(),false)
            if(requestTab==TAB_CATEGORY) {
                val fetched=panelRef.get()
                if(fetched!=null && tab==TAB_CATEGORY && categorySelection==requestCategory) {
                    val firstPanel=categoryPanel==null
                    categoryPanel=fetched
                    if(firstPanel) {
                        catalog=page.items; ranking=page.ranking; hasMore=page.hasMore
                        rememberCategoryState(requestCategory)
                        showCatalog(load=false,keepChip=true)
                        chipFocusKey=null
                        return@work
                    }
                }
            }
            if(appending) {
                val added=page.items.filterNot { item -> catalog.any { it.id==item.id } }
                val wanted=moreFocus; moreFocus=null
                catalog=catalog+added; ranking=mergeRanking(ranking,page.ranking); hasMore=page.hasMore
                if(requestPage==1) catalogPageOneFresh=true
                setRankSubtitle(ranking?.updatedText.orEmpty())
                if(gridList!=null && catalogBody===body) appendGridItems(added,wanted)
                else { body.removeAllViews(); seriesList(body,false) }
            } else {
                catalog=page.items; ranking=page.ranking; hasMore=page.hasMore
                if(requestPage==1) catalogPageOneFresh=true
                clearDebugProblem()
                setRankSubtitle(ranking?.updatedText.orEmpty())
                val landFirst=focusFirstCardOnLanding; focusFirstCardOnLanding=false
                val holdChip=chipFocusKey!=null
                val keepNav=!holdChip && !landFirst && nav.any { it.hasFocus() }
                body.removeAllViews(); seriesList(body,if(holdChip) false else keepNav || focusNav,keepFocus=holdChip)
                chipFocusKey=null
                if(holdChip) prefetchNextPage()
            }
            if(requestTab==TAB_CATEGORY && tab==TAB_CATEGORY && categorySelection==requestCategory) {
                rememberCategoryState(requestCategory)
            }
            if(requestTab==TAB_RANK && tab==TAB_RANK && rankBoard==requestBoard) {
                rememberRankState(requestBoard)
            }
            if(requestTab==TAB_SEARCH && requestPage==1 && SearchDiag.last?.degraded==true) {
                showHint("App 搜索暂不可用，已改用官网搜索")
            }
        }, { problem ->
            if(requestTab==TAB_HOME) {
                if(homeFeed.isNotEmpty()) { showHint("热门暂时取不到，仍在显示上次内容"); return@work }
                body.removeAllViews(); error(body,problem,stage="content_home") { showCatalog(true) }
                return@work
            }
            if(appending) {
                page=requestPage-1; moreFocus=LOAD_MORE
                setRankSubtitle("加载更多失败 · 当前显示 ${catalog.size} 部")
                noteDebugProblem("加载更多失败 · 第 ${requestPage} 页 · 已显示 ${catalog.size} 部")
                body.removeAllViews(); seriesList(body,false)
                return@work
            }
            if(keepCatalog && catalog.isNotEmpty()) {
                setRankSubtitle("刷新失败 · 仍显示上次榜单")
                showHint("榜单刷新失败，仍显示上次内容")
                return@work
            }
            catalog=emptyList(); hasMore=false; ranking=null
            setRankSubtitle(if(tab==TAB_SEARCH) "搜索失败" else "榜单加载失败")
            if(problem is SearchSessionExpiredException) {
                page=1; catalogFocus=""
                showHint("搜索结果已过期，已回到第 1 页刷新")
                showCatalog(true)
            } else {
                body.removeAllViews()
                val retry=error(body,problem,stage=if(tab==TAB_SEARCH) "content_search" else "content_rank") { showCatalog(true) }
                chipControl(chipFocusKey)?.let { chip ->
                    chip.nextFocusDownId=retry.id
                    retry.nextFocusUpId=chip.id
                    chip.requestFocus()
                }
                chipFocusKey=null
            }
        })
    } else seriesList(body,focusNav,focusType)
}
