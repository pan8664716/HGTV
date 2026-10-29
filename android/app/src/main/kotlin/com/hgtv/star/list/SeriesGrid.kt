// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.list

import android.app.Activity
import android.view.*
import android.widget.*
import com.hgtv.star.core.*
import com.hgtv.star.MainActivity.GridRow
import com.hgtv.star.tv.PosterCard
import com.hgtv.star.tv.TvButton
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.GRID_COLUMNS_DEFAULT
import com.hgtv.star.gridContentWidth
import com.hgtv.star.gridColumns
import com.hgtv.star.wireSearchHeadDown
import com.hgtv.star.noteSearchRowFocused
import com.hgtv.star.GRID_WINDOW_MAX_ROWS
import com.hgtv.star.GRID_WINDOW_MIN_ROWS
import com.hgtv.star.LOAD_MORE
import com.hgtv.star.MAX_CATALOG_PAGE
import com.hgtv.star.PREFETCH_TRIGGER_ROWS
import com.hgtv.star.PREFETCH_ROWS
import com.hgtv.star.PREFETCH_PAGES
import com.hgtv.star.MainActivity
import com.hgtv.star.TAB_CATEGORY
import com.hgtv.star.TAB_HOME
import com.hgtv.star.TAB_PROFILE
import com.hgtv.star.TAB_RANK
import com.hgtv.star.TAB_SEARCH
import com.hgtv.star.appendCatalog
import com.hgtv.star.autoAppendCatalog
import com.hgtv.star.button
import com.hgtv.star.dp
import com.hgtv.star.focusHead
import com.hgtv.star.findTagged
import com.hgtv.star.formatTime
import com.hgtv.star.gapInto
import com.hgtv.star.headFocusTarget
import com.hgtv.star.loadCover
import com.hgtv.star.noteDebugProblem
import com.hgtv.star.lp
import com.hgtv.star.navIcons
import com.hgtv.star.pageInset
import com.hgtv.star.profileHeadControl
import com.hgtv.star.profileHeadControls
import com.hgtv.star.row
import com.hgtv.star.showHint
import com.hgtv.star.text
import com.hgtv.star.compat.attachedToWindow

// 本文件：剧卡栅格的窗口化复用、分页追加、滚动与焦点。
//
// list/ 这个包就是「列表组件」这一块的全部代码，只有三件事：
//   ListPage.kt    页面骨架与唯一入口 [seriesList]（页头、空页、错误、跑数据）
//   SeriesGrid.kt  竖排六列栅格：窗口化、行复用、追加、焦点（本文件）
//   HomeScreen.kt  横排剧架：首页四栏、详情页相关推荐
// 卡片（tv/PosterCard）、点 / 长按动作（[openSeries] / [seriesMenu]）两边共用。
// 这些成员仍然属于 MainActivity，只是以扩展成员（MainActivity.xxx）的形式放在这里，
// 逻辑与拆之前逐行一致；改这里等于改 Activity 本体。

internal fun MainActivity.moreLabel(): String = when {
    moreFailed -> "加载失败 · 重试"
    moreVisible && moreInFlight -> "正在加载更多…"
    else -> "展示更多 ↓"
}
/**
 * 「向下展示更多」。
 *
 * 有活着的栅格就只往列表末尾接（视口上方的像素一个不动），没有栅格（空页）才退回整页重建。
 */
internal fun MainActivity.loadMore() {
    if(!hasMore || !appendCatalog()) return
    if(gridList==null || gridItems.isEmpty()) {
        moreFocus=null; page++; catalogFocus=""
        // 追加是同一页的原地重建：页头保持当前的收 / 展状态，不会因为加载更多又弹回来。
        keepTopAreaState=true
        showCatalog(true)
        return
    }
    requestNextPage(userInitiated=true)
}
/**
 * 静默取下一页：焦点走到倒数两行、或者列表滚到接近底部时触发。
 *
 * 取回来直接接在列表末尾（在视口下方，用户看不见），全程不显示加载态——
 * 这就是「无感」的来源：请求发生在用户看到底部之前。
 */
internal fun MainActivity.prefetchNextPage() {
    // 换榜单 / 换分类的请求还在路上：屏上那份列表属于上一条选择，
    // 拿它去取「下一页」会把新列表的页码算乱（旧列表的追加会先落进 catalog）。
    if(chipFocusKey!=null) return
    // [moreFailed] 之后不再自动重试：断网时滚动事件会把预取变成对着接口猛敲，
    // 等用户自己按下「加载失败 · 重试」再打一次就够了。
    if(screen!="catalog" || !appendCatalog() || !hasMore || moreInFlight || moreFailed || gridList==null) return
    if(page+1>MAX_CATALOG_PAGE) return
    requestNextPage(userInitiated=false)
}
/**
 * 把取回来的一页接在列表末尾。
 *
 * 关键在「只动下面」：先把底部行摘下来，把新行插在它前面再放回去，
 * 视口上方的所有内容一个像素都没动，ScrollView 因此不需要任何位置恢复，
 * 也就不会出现「加载更多之后跳回顶部 / 跳到别处」。
 * [userInitiated] 为 false 表示这是后台预取落地，不抢焦点、不滚动。
 */
internal fun MainActivity.applyNextPage(next: Int,result: CatalogPage,userInitiated: Boolean) {
    if(!hasMore || !appendCatalog() || gridList==null) return
    val added=result.items.filterNot { item -> catalog.any { it.id==item.id } }
    catalog=catalog+added
    ranking=mergeRanking(ranking,result.ranking)
    hasMore=result.hasMore
    page=next
    val target=if(userInitiated) added.firstOrNull()?.id ?: LOAD_MORE else null
    if(userInitiated) catalogFocus=added.firstOrNull()?.id.orEmpty()
    appendGridItems(added,target)
    // 只有用户**真的还站在末尾附近**才继续往后取（判据见 [PREFETCH_TRIGGER_ROWS]）。
    //
    // 这一条以前是 runaway 的发动机：判据按「整份列表的 50%」算，而追加落地之后
    // 用户的位置恰好就是新总量的中点——刚接完就满足条件，于是再取、再落地、再满足，
    // 一路翻到页码上限。按「还剩几行」判之后，追加落地时前面还剩一整页（17 行），
    // 这里自然返回 false，接完就停手。
    if(hasMore && rowsLeftAfterConsumed()<=PREFETCH_TRIGGER_ROWS) {
        prefetchChain++
        if(prefetchChain<PREFETCH_PAGES) prefetchNextPage()
    }
}
/**
 * 用户是不是已经站在列表底部附近：焦点在倒数两行之内，或者视口已经接近列表末尾。
 * 「继续往后预取」与「一次性把整份目录拉下来」的分界线就在这个判断上。
 */
internal fun MainActivity.nearGridEnd(): Boolean {
    if(gridItems.isEmpty()) return false
    // 判据是**还剩几行**（见 [PREFETCH_TRIGGER_ROWS]）：离已加载内容的末尾还有多远。
    //
    // 这里**不能**按「整份列表的比例」算。那套判据在追加落地的瞬间恒为真
    // （用户的新位置正好是新总量的中点），于是每接一页就再满足一次，
    // 一进榜单页就会自己一路翻到底——线上反馈的「跑到 5000 多个」正是它。
    return rowsLeftAfterConsumed()<=PREFETCH_TRIGGER_ROWS
}

/**
 * 已加载内容里，**消费位置之后还剩几行**（用户还没看到的行数）。
 *
 * 消费位置取 [gridConsumedIndex]（焦点位置与视口底部里更靠后的那个）。
 * 焦点还没进栅格（刚进页面、焦点在页头）时按「一屏都没消费」处理，
 * 返回整份列表的行数——此时不该预取，因为用户还在挑榜单 / 挑分类。
 */
internal fun MainActivity.rowsLeftAfterConsumed(): Int {
    val consumed=gridConsumedIndex()
    if(consumed<0) return gridRowCount()
    val left=(gridItems.size-(consumed+1)).coerceAtLeast(0)
    return (left+gridColumns-1)/gridColumns
}

/**
 * 已经「消费」到第几部（0 起的下标；一个都没消费时返回 -1）。
 *
 * 取**焦点位置**与**视口底部**里更靠后的那个：遥控器按住下键时焦点就是进度，
 * 而滚轮 / 触摸滚动时视口底部才是——两种走法都要能触发预取。
 */
internal fun MainActivity.gridConsumedIndex(): Int {
    val focus=gridFocusIndex() ?: -1
    val scroll=gridScroll
    val stride=rowStride()
    val viewport=if(scroll!=null && scroll.height>0 && stride>0) {
        val lastRow=(scroll.scrollY+scroll.height)/stride
        lastRow*gridColumns+gridColumns-1
    } else -1
    return maxOf(focus,viewport)
}
/**
 * 去取第 `page+1` 页。
 *
 * [userInitiated] 为 true 时底部显示「正在加载更多…」，结果落地后焦点落到新行的第一张卡；
 * 静默预取只记在 [moreInFlight] 上，界面上什么都不变。
 * 两者共用同一把锁，所以同一页不会被请求两次；静默预取在途中被用户按下时，
 * 只是把 [moreFocusWanted] 打开，落地时照样把焦点交过去。
 */
internal fun MainActivity.requestNextPage(userInitiated: Boolean) {
    if(chipFocusKey!=null) return
    if(!hasMore || !appendCatalog() || gridList==null) return
    if(moreInFlight) {
        if(userInitiated) { moreVisible=true; moreFocusWanted=true; updateFooter() }
        return
    }
    val next=page+1
    if(next>MAX_CATALOG_PAGE) { hasMore=false; updateFooter(); return }
    moreInFlight=true; moreInFlightPage=next; moreVisible=userInitiated
    moreFocusWanted=userInitiated; moreFailed=false
    // 用户主动要的这一次不算进「连取」的预算里（他自己按的，就该给他）。
    if(userInitiated) prefetchChain=0
    updateFooter()
    val pageStarted=android.os.SystemClock.elapsedRealtime()
    // 只在 debug 包里打：正式包连字符串拼接都不做（诊断用的东西不能拖慢使用）。
    if(com.hgtv.star.BuildConfig.DEBUG) android.util.Log.i("HGTV-PAGE",
        "请求第 $next 页（已显示 ${gridItems.size} 部 / ${gridRowCount()} 行） 用户触发=$userInitiated")
    val ticket=generation; val board=rankBoard; val category=categorySelection
    val requestTab=tab; val scope=collection; val requestQuery=query
    // 搜索源快照：工作线程只拿值，不回读主线程持有的 [library]。
    val requestSource=library.searchSource
    gridPrefetchJob=io.submit {
        // 榜单、分类、搜索都走这条「往下接」的路：第 2 页起接着取同一份列表，
        // 页头与已显示的剧集都不动（搜索结果按同一关键词继续取）。
        val result=runCatching {
            when {
                requestTab==TAB_SEARCH && scope==null -> repository.search(requestQuery,next,requestSource)
                requestTab==TAB_CATEGORY && scope==null -> repository.categoryFeed(category,next,needPanel=false).page
                else -> repository.rank(next,board)
            }
        }.getOrNull()
        main.post {
            if(isDestroyed || generation!=ticket) return@post
            if(moreInFlightPage==next) { moreInFlight=false; moreVisible=false }
            if(com.hgtv.star.BuildConfig.DEBUG) android.util.Log.i("HGTV-PAGE",
                "第 $next 页返回：${result?.items?.size ?: -1} 部，耗时 "
                + "${android.os.SystemClock.elapsedRealtime()-pageStarted}ms，hasMore=${result?.hasMore}")
            val wantsFocus=moreFocusWanted; moreFocusWanted=false
            if(result==null) {
                if(wantsFocus) showHint("加载更多失败，请检查网络后重试")
                // 调试面板要能看出「是第几页失败」：翻页失败时页面上只留一句提示，
                // 用户描述问题往往就是「看到一半不加载了」。
                noteDebugProblem("加载更多失败 · 第 ${next} 页 · 已显示 ${gridItems.size} 部")
                moreFailed=true; updateFooter()
                return@post
            }
            // 只有「还是原来那一页」才接得上；中途切了标签 / 榜单 / 集合，结果直接丢掉。
            if(scope==collection && requestTab==tab && requestQuery==query && rankBoard==board &&
                (requestTab!=TAB_CATEGORY || categorySelection==category) && screen=="catalog" && gridList!=null)
                applyNextPage(next,result,wantsFocus)
            else updateFooter()
        }
    }
}
/**
 * 把新取回的一页接在列表末尾。
 *
 * 窗口化之后这一步**不建任何 View**：只是把整份列表变长，再让两个 spacer 按新的行数
 * 重算高度。视口上方的像素一个不动，滚动位置也不需要（也不应该）做任何恢复。
 * 因此「加载更多」的代价是 O(1)，与已经加载了多少部剧无关。
 */
internal fun MainActivity.appendGridItems(added: List<Series>,focusTarget: String?) {
    if(gridList==null) return
    // 过滤规则必须与 seriesList 里算 displayed 的那一套完全一致，否则 gridItems 与
    // 实际显示的内容会对不上，按索引算出来的焦点落点就会错位。
    val visible=if(tab==TAB_HOME || tab==TAB_RANK || tab==TAB_CATEGORY) added.filterNot { it.id in library.hidden() } else added
    if(visible.isNotEmpty()) {
        // 追加只是把「整份列表」变长：**一个 View 都不用建**。
        // 底部那一行与两个 spacer 都按行数重算，视口上方的像素一个不动。
        gridItems=gridItems+visible
        syncGridWindow()
        // 已经物化的那几行必须**重新绑一次**。
        //
        // 一页 20 部按六列切开，最后一行只有 2 张、后面 4 个槽位是空槽（见 [bindGridRow]）；
        // 新的一页要填进这 4 个空槽。但追加时窗口范围没变，[syncGridWindow] 会走提前返回，
        // 那一行不会重绑——于是那 4 个槽位一直是空槽，用户看到的是
        // 「第 4 行只有 2 张，新内容直接从第 5 行开始」，中间像少了一段。
        // 重绑的代价只有当前物化的那几行（≤ 一屏 + 2 行），与已加载多少无关。
        rebindGridRows()
    }
    // hasMore 可能刚翻成 false（接上的是最后一页）：只有形态真的变了才重建底部这一行。
    // 没变就只改文案——重建会把焦点按着的「展示更多」按钮换掉，焦点会先掉一帧再回来。
    if(pagingHasMore!=hasMore) rebuildPagingRow() else updateFooter()
    wireGridFocus(null,moreButton?.takeIf { it.isFocusable })
    when {
        focusTarget==null -> Unit
        focusTarget==LOAD_MORE -> moreButton?.let { button -> button.requestFocus(); revealRow(gridRowCount()-1,true) }
        else -> gridItems.indexOfFirst { it.id==focusTarget }.takeIf { it>=0 }?.let { index -> focusGridIndex(index,true) }
    }
}
/** 底部一行：已显示进度 + 「展示更多」。hasMore 变成 false 时只留进度文字。 */
internal fun MainActivity.fillPagingRow(paging: LinearLayout) {
    moreButton=null; moreCount=null
    pagingHasMore=hasMore
    paging.addView(text(moreCountText(),14f,muted).apply { gravity=Gravity.CENTER },lp(dp(200),dp(TvStyle.BUTTON_NORMAL+2*TvStyle.SOFT_BAND)))
    moreCount=paging.getChildAt(0) as? TextView
    // 搜索结果只要这一行进度文字：加载由滚动与焦点触发，不需要再按一个按钮。
    if(!hasMore || autoAppendCatalog()) return
    val more=button(moreLabel()) { loadMore() }.apply {
        tag=LOAD_MORE
        nextFocusLeftId=id; nextFocusRightId=id
        // 停在底部按钮上再按下键也是「继续往下看」，不用先按确认。
        setOnKeyListener { _,key,event ->
            if(key==KeyEvent.KEYCODE_DPAD_DOWN && event.action==KeyEvent.ACTION_UP) { loadMore(); true } else false
        }
    }
    gapInto(paging,more,lp(-2,-2))
    moreButton=more
    updateFooter()
}
/** 重建底部那一行（只有 hasMore 翻转时需要，其它情况走 [updateFooter] 就地改文案）。 */
internal fun MainActivity.rebuildPagingRow() {
    val paging=gridPaging ?: return
    val keepButtonFocus=moreButton!=null && moreButton!!.hasFocus()
    paging.removeAllViews()
    fillPagingRow(paging)
    if(keepButtonFocus && moreButton==null) gridRows.lastOrNull()?.cards?.firstOrNull()?.requestFocus()
}
/** 底部文案与按钮状态就地更新：不动子控件，焦点不会掉。 */
internal fun MainActivity.updateFooter() {
    moreCount?.text=moreCountText()
    val more=moreButton ?: return
    (more as? TvButton)?.setLabel(moreLabel())
    more.alpha=if(moreVisible && moreInFlight) TvStyle.DISABLED_ALPHA else 1f
}
/** 栅格里的全部卡片：只包含**当前物化的窗口**那几行（窗口外没有 View）。 */
internal fun MainActivity.gridCards(): List<View> = gridRows.flatMap { holder -> holder.cards }

/** 一行是不是整份列表的最后一行（追加之后会变，所以每次现算）。 */
internal fun MainActivity.isGridTail(holder: GridRow)=holder.index>=gridRowCount()-1

/** 整份列表的行数。 */
internal fun MainActivity.gridRowCount()=if(gridItems.isEmpty()) 0 else (gridItems.size+gridColumns-1)/gridColumns

/**
 * 一行的像素高度（含行间距）。
 *
 * 首帧还没量到时先按海报高度估一个：两个 spacer 必须在第一次布局之前就有高度，
 * 否则 ScrollView 会把恢复位置夹成 0（与「列表还没测量就 scrollTo」是同一个坑）。
 * 量到真实行高之后立刻修正一次，之后每帧都是纯算术。
 */
internal fun MainActivity.rowStride(): Int = if(gridRowStride>0) gridRowStride
    else (gridPosterHeight+dp(2*TvStyle.SOFT_BAND+8)+dp((50*gridTextScale()).toInt())).coerceAtLeast(dp(120))

/**
 * 栅格卡片文字的缩放：与首页剧架同一套算法（6 列为 1.0，列数变少时按卡宽放大）。
 *
 * 卡片宽度在 [ListPage.seriesList] 里算好放进 [gridCardWidth]，这里只做比例换算；
 * 还没量到宽度时返回 1，避免首帧用 0 除。
 */
internal fun MainActivity.gridTextScale(): Float {
    if(gridCardWidth<=0) return 1f
    val gap=dp(6)
    val base=((gridContentWidth()-gap*(GRID_COLUMNS_DEFAULT-1))/GRID_COLUMNS_DEFAULT).toFloat()
    if(base<=0f) return 1f
    // 下限 0.85 给 7 / 8 列：卡片更窄，字号跟着收一点才不会只剩两三个字。
    return (gridCardWidth/base).coerceIn(0.85f,1.45f)
}

/**
 * 重算两个 spacer 的高度。
 *
 * 列表总高度 = 行数 × 行高，与「一行一个 View 全量常驻」时完全一致，
 * 因此滚动条范围、恢复位置、`gridAtBottom` 的判定都不变；
 * 但撑起这个高度只需要两个 Space，不需要真的建出那几百行。
 */
internal fun MainActivity.updateGridSpacers() {
    val stride=rowStride()
    val total=gridRowCount()
    val start=gridWindowStart.coerceAtLeast(0)
    val end=if(gridWindowStart<0) 0 else start+gridRows.size
    setSpacerHeight(gridTopSpacer,start*stride)
    setSpacerHeight(gridBottomSpacer,((total-end).coerceAtLeast(0))*stride)
}

/** 只在高度真的变了才写回：滚动回调每次都会走到这里，无谓的 requestLayout 会多花一帧布局。 */
internal fun MainActivity.setSpacerHeight(spacer: View?,height: Int) {
    val params=spacer?.layoutParams as? LinearLayout.LayoutParams ?: return
    if(params.height==height) return
    params.height=height
    spacer.layoutParams=params
}

/**
 * 只物化视口附近那几行，并把两端进出的行回收进 [gridRowPool]。
 *
 * **整个函数的代价只与窗口大小有关，与已经加载了多少部剧无关**——
 * 这就是「加载几百个卡片之后越来越卡」的根治点。滚动时通常只增删 0~1 行。
 *
 * [focusIndex] 指定「必须可见」的那张卡：焦点在窗口外时（追加后落到新行、
 * 恢复上次焦点）把它所在的行一并纳入，否则请求焦点会落空。
 */
internal fun MainActivity.syncGridWindow(force: Boolean=false,focusIndex: Int?=null) {
    val window=gridWindow ?: return
    val scroll=gridScroll ?: return
    if(gridSyncing) return
    val total=gridRowCount()
    if(total==0) return
    gridSyncing=true
    try { syncGridWindowLocked(force,focusIndex,window,scroll,total) } finally { gridSyncing=false }
}

internal fun MainActivity.syncGridWindowLocked(force: Boolean,focusIndex: Int?,window: LinearLayout,scroll: ScrollView,total: Int) {
    val stride=rowStride()
    val viewport=scroll.height
    // 视口上下各多留一行：焦点放大与「下一页」预取都需要相邻行已经在树上。
    var first=if(viewport<=0) 0 else ((scroll.scrollY-dp(24))/stride-1).coerceAtLeast(0)
    var last=if(viewport<=0) minOf(total-1,GRID_WINDOW_MIN_ROWS-1)
        else ((scroll.scrollY+viewport+dp(24))/stride+1).coerceAtLeast(first).coerceAtMost(total-1)
    focusIndex?.let { index ->
        val row=(index/gridColumns).coerceIn(0,total-1)
        // 焦点行不在视口算出来的窗口里时，**以它为准重开一个窗口**，
        // 而不是把「视口到它」之间的行全拉进来——那可能是几百行。
        // 反过来的写法（first=min、last=max）在「恢复的焦点离视口很远」时会让区间反向，
        // 结果是一个空窗口：spacer 撑出几百行的空白，而焦点目标永远不会被物化。
        if(row<first || row>last) {
            first=row
            last=(row+GRID_WINDOW_MIN_ROWS-1).coerceAtMost(total-1)
        }
    }
    // 硬上限：任何情况下都不物化超过 [GRID_WINDOW_MAX_ROWS] 行。
    if(last-first+1>GRID_WINDOW_MAX_ROWS) {
        val focusRow=focusIndex?.let { it/gridColumns }
        last=when {
            focusRow!=null && focusRow<=first+GRID_WINDOW_MAX_ROWS-1 -> first+GRID_WINDOW_MAX_ROWS-1
            focusRow!=null -> { first=(focusRow-GRID_WINDOW_MAX_ROWS+1).coerceAtLeast(0); first+GRID_WINDOW_MAX_ROWS-1 }
            else -> first+GRID_WINDOW_MAX_ROWS-1
        }
        last=last.coerceAtMost(total-1)
    }
    if(!force && gridWindowStart==first && gridRows.size==last-first+1) { updateGridSpacers(); return }
    // 两端对齐：中间那几行走「只换绑定数据」的复用路径，因此滚动时视口内的卡片不会重建。
    val keepFrom=maxOf(first,gridWindowStart)
    val keepTo=minOf(last,gridWindowStart+gridRows.size-1)
    if(gridRows.isEmpty() || keepFrom>keepTo) {
        gridRows.toList().forEach(::recycleGridRow)
        gridRows.clear(); gridWindowStart=first
    } else {
        // 丢掉前端多出来的行。**每丢一行窗口起点必须跟着 +1**：`gridWindowStart` 是
        // `gridRows[0]` 的行号，删了首行却不推进起点，循环条件就永远成立，
        // 一路删到空表再取 `first()` 就是 `NoSuchElementException`（2026-10-14 的崩溃）。
        while(gridWindowStart<keepFrom && gridRows.isNotEmpty()) {
            recycleGridRow(gridRows.first()); gridWindowStart++
        }
        // 丢掉后端多出来的行：起点不动，只是变短（size 缩小，条件会自然收敛）。
        while(gridRows.isNotEmpty() && gridWindowStart+gridRows.size-1>keepTo) recycleGridRow(gridRows.last())
        if(gridRows.isEmpty()) gridWindowStart=first
    }
    // 补齐到 [first,last]：前端补完再补后端，插进 window 的位置按行号算。
    while(gridWindowStart>first) { gridWindowStart--; materializeGridRow(gridWindowStart,atFront=true) }
    while(gridWindowStart+gridRows.size-1<last) materializeGridRow(gridWindowStart+gridRows.size,atFront=false)
    updateGridSpacers()
    if(gridRowStride<=0) measureRowStride()
    // 低内存时被摘掉的封面：窗口挪动之后可能已经回到视口（见 MainActivity.releaseOffscreenCovers）。
    resumeVisibleCovers()
    // 预取窗口跟着物化窗口走（见 [prefetchAroundWindow]）。
    prefetchAroundWindow()
}
/**
 * 按「消费位置为中心」设定预取窗口：**前后各一屏**的封面。
 *
 * 为什么需要它：窗口化只物化视口附近那几行，而 `load` 只对已经存在的 View 生效——
 * 于是焦点往下走时，图是「那一行被物化之后才开始下」的，用户读到的就是
 * 「焦点走到哪儿才加载那一片、前面一大片灰框只有文字」。
 *
 * 这里按**整份列表的索引**直接取 URL（与有没有 View 无关），因此预取量与焦点速度无关：
 * 焦点再怎么快，前后各一屏的图已经在路上或已在缓存里。
 *
 * 为什么以**焦点为中心**而不是只往下看：用户往回滚时「前面」就变成了「后面」，
 * 只往下预取的话往回走就完全没有预取覆盖了。以消费位置为中心，
 * 两个方向各留一屏，来回走都有覆盖（见 `ArtworkLoader.prefetchWindow`）。
 *
 * 窗口是**有界**的：一次只保留前后各 [PREFETCH_ROWS] 行，滑出窗口的预取任务会被丢掉，
 * 因此飞快划过几百张也不会把队列堆满、把带宽花在再也不看的图上。
 */
internal fun MainActivity.prefetchAroundWindow() {
    if(gridItems.isEmpty() || gridCardWidth<=0) return
    val total=gridRowCount()
    // 中心行只有一个来源：**焦点在哪一行**；焦点不在栅格里（触摸滚动、焦点在页头）时
    // 用视口中心。
    //
    // 这一步是必须的：按住下键时焦点在**视口内**移动（`revealRow` 发现目标行已可见就
    // 什么都不做），列表根本不滚动，因此**物化窗口一直不变**。早先的版本只看物化窗口，
    // 于是每次调用都从「窗口没变」那个 early return 出去，预取窗口**永远不推进**——
    // 等到焦点走到视口边缘、列表终于滚动了，才开始取那一屏的图，那时已经晚了。
    // 用户读到的正是「到了焦点位置还要现加载一下和周围」。
    //
    // 为什么不能像更早那版一样让**调用方**传中心：那样 `syncGridWindow` 传视口底行、
    // 焦点回调传焦点行，两个值交替触发，窗口在 A/B 之间来回翻——
    // 实测 `pfCancelled=2846 / pfQueued=3677`（77% 的预取任务被撤销重排）。
    // 中心只在这里算一次，就不会有两个来源。
    val focusRow=gridFocusIndex()?.let { it/gridColumns }
    val centerRow=focusRow ?: run {
        val stride=rowStride()
        if(stride>0 && gridScroll!=null) (gridScroll!!.scrollY+gridScroll!!.height/2)/stride else 0
    }.coerceIn(0,(total-1).coerceAtLeast(0))
    // 中心行没变就不重算：同一行内左右移动时窗口内容完全一样，而重算一次要遍历
    // 上百张封面并逐个改写 URL、比对队列——那都在 UI 线程上（按键事件里）。
    if(centerRow==prefetchCenterRow) return
    prefetchCenterRow=centerRow
    // 预取窗口的半径：低配收到一半。
    //
    // 低配的内存缓存只有 `堆/5`（192 MB 堆 → 32 MB，RGB_565 约合 110 张），
    // 而 ±12 行 × 6 列 = 150 张：窗口比缓存大，先下的那几张会在用户走到之前就被挤掉，
    // 于是同一张图被重复下载（`dupEnq` 上涨），既费带宽又抢 CPU。
    // 收成 ±6 行（约 78 张）之后窗口能整份装进缓存，预取才是有效的。
    // [TvStyle.animate] 与低配判据同源（见 `MainActivity.onCreate`）。
    val radius=if(TvStyle.animate) PREFETCH_ROWS else 6
    val from=(centerRow-radius).coerceAtLeast(0)
    val to=(centerRow+radius).coerceAtMost(total-1)
    // 按「离中心行由近及远」入队：接下来要看的是紧贴焦点上下那几行，
    // 队列因此从焦点向两边铺开（而不是从上往下）。
    val rows=(from..to).sortedBy { row -> kotlin.math.abs(row-centerRow) }
    val urls=ArrayList<String>(rows.size*gridColumns)
    for(row in rows) {
        for(column in 0 until gridColumns) {
            val item=gridItems.getOrNull(row*gridColumns+column) ?: continue
            if(item.cover.isNotBlank()) urls+=item.cover
        }
    }
    // 尺寸必须与**可见卡量到的那个**逐字节相同，否则预取下的图可见卡命中不了缓存、
    // 又去重下一遍（Key 里带着尺寸，见 `ArtworkLoader.Key`）。
    //
    // 卡片是「外框 → 海报井 → 图片」三层，可见卡量的是最里面那张 ImageView：
    // 卡片内边距 [TvStyle.SOFT_BAND]，海报井再左右各内缩 4dp（见 `PosterCard` 的布局）。
    // 这里照同一套算，得到的数就是 `load()` 量出来的那一个。
    val imageWidth=gridCardWidth-dp(2*TvStyle.SOFT_BAND+8)
    artwork.prefetchWindow(urls,imageWidth,gridPosterHeight)
}

/**
 * 同步量一次行高。
 *
 * 行高 = 海报高度 + 剧名 + 状态行 + 卡片内边距 + 行间距，与卡片里的文字度量有关，
 * 因此不能写死一个常数（系统字号放大时会变）。这里量的是**行容器**：
 * 它已经量过（或者现在就量一次），量与布局走的是同一套规则，比单量一张卡可靠。
 * 量到之后 spacer 的高度与滚动位置一次到位，不必再等异步回调纠偏。
 */
internal fun MainActivity.measureRowStride() {
    val line=gridRows.firstOrNull()?.line ?: return
    if(line.width<=0 && gridCardWidth>0) line.measure(
        View.MeasureSpec.makeMeasureSpec(resources.displayMetrics.widthPixels-2*pageInset(),View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED)
    )
    val measured=(if(line.height>0) line.height else line.measuredHeight)+dp(6)
    if(measured>dp(6) && measured!=gridRowStride) { gridRowStride=measured; updateGridSpacers() }
}

/** 取一个行容器：优先用回收池里的（View 与卡片都复用），池空才新建。 */
internal fun MainActivity.obtainGridRow(): GridRow {
    val columns=gridColumns
    val textScale=gridTextScale()
    // 设置里改了每行张数之后，池里旧行可能还是别的张数：尺寸对不上就丢掉重建，
    // 否则会把一张多余的卡留在行末（不显示但占宽），或者最后一格永远空着。
    while(true) {
        val holder=gridRowPool.removeLastOrNull() ?: break
        if(holder.cards.size==columns && holder.cards.firstOrNull()?.textScale==textScale) return holder
        holder.cards.forEach { it.releaseCover() }
    }
    val line=row().apply { gravity=Gravity.TOP; clipChildren=false; clipToPadding=false }
    val holder=GridRow(line)
    repeat(columns) {
        val card=PosterCard(this,gridPosterHeight,::loadCover,textScale)
        wireGridCard(card)
        line.addView(card,lp(gridCardWidth,-2).apply { if(it<columns-1) rightMargin=gridGap })
        holder.cards+=card
    }
    return holder
}

/** 把一个行容器放进窗口（[atFront] 决定插在最前还是最后）并绑定它那一行的数据。 */
internal fun MainActivity.materializeGridRow(index: Int,atFront: Boolean) {
    val window=gridWindow ?: return
    val holder=obtainGridRow()
    holder.index=index
    // 复用的行可能带着上一页的海报高度（同值不重设，见 PosterCard.resizePoster）。
    holder.cards.forEach { card -> card.resizePoster(gridPosterHeight) }
    bindGridRow(holder)
    // 行间距 6dp：与「一行一个 View」时代一致，spacer 的行高按「行高 + 行间距」算。
    window.addView(holder.line,if(atFront) 0 else window.childCount,lp(-1,-2).apply { bottomMargin=dp(6) })
    if(atFront) gridRows.add(0,holder) else gridRows.add(holder)
}

/**
 * 把一行交回回收池：从窗口摘下来，View 与卡片都留着复用，**但封面位图要摘掉**。
 *
 * 为什么摘：位图只要还挂在 `ImageView` 上就不会被回收，而内存缓存的 LRU 只管自己那份。
 * 行进了回收池就看不见了，却仍然钉着 6 张（约 3.4 MB）——池里 4 行就是 13 MB，
 * 全在缓存之外。线上那次 OOM（缓存 192 MB 撞上 192 MB 堆）里，这一份是压垮它的另一半。
 *
 * 代价：这一行再被复用时要从内存缓存重新取一张（命中就是一次 `setImageBitmap`），
 * 只有缓存刚好把它挤掉时才需要读盘（3~10ms）。比「整机 OOM」便宜得多。
 */
internal fun MainActivity.recycleGridRow(holder: GridRow) {
    gridWindow?.removeView(holder.line)
    gridRows.remove(holder)
    holder.cards.forEach { it.releaseCover() }
    // 池子封顶：滚动时同时进出的行只有个位数，留 4 行足够，
    // 免得极端情况（快速来回甩）把池子撑成第二个「全量列表」。
    if(gridRowPool.size<4) gridRowPool+=holder
}

/** 按 [GridRow.index] 绑定这一行里每张卡的剧集、状态与封面（同值不重设，见 [PosterCard.bind]）。 */
internal fun MainActivity.bindGridRow(holder: GridRow) {
    holder.cards.forEachIndexed { column,card ->
        val index=holder.index*gridColumns+column
        val item=gridItems.getOrNull(index)
        if(item==null) {
            // 最后一行不满时留空槽：卡片留在原位（保持列对齐）但不显示，
            // 封面也清掉——不为一张看不见的卡留住位图。
            card.series=null
            card.visibility=View.INVISIBLE
            card.setCover("")
            return@forEachIndexed
        }
        card.visibility=View.VISIBLE
        bindGridCard(card,item)
    }
}

/**
 * 一张海报卡的**数据绑定**（不含监听器）：监听器在 [wireGridCard] 里只装一次，
 * 因此滚出再滚回来只是换一遍文字与封面，不再新建 View、不再重复挂监听。
 */
internal fun MainActivity.bindGridCard(card: PosterCard,item: Series) {
    val position=if(tab==TAB_RANK) ranking?.positions?.get(item.id) else null
    val rank=position?.rank
    val progress=if(collection=="history") library.progress(item.id) else null
    // 第二行：优先显示「看到哪了 / 收藏更新」这类状态；卡片右下角已经统一是集数，
    // 因此这里不再拿 `item.badge`（那也是「全 N 集」）来顶。
    val statusText=when {
        // 未上架（预告 / 预约中）：第二行显示上线时间，这是这一张卡唯一有用的状态。
        item.upcoming -> item.notice.ifBlank { "即将上线" }
        collection=="favorites" -> library.favoriteLabel(item.id)
        // 榜单偶尔整页没有热度值（服务端 SSR 抽风）：退回榜单名，不再整屏都是「热度暂无」。
        tab==TAB_RANK -> position?.heat?.takeIf { it.isNotBlank() } ?: rankBoard.label
        progress!=null -> if(library.watched(item.id)) "整剧已看完" else "第 ${progress.episodeIndex+1} 集 · ${formatTime(progress.position)}"
        // 历史集合页的**账号侧**条目没有本机进度：集数与集内秒数在 [accountHistoryCard]
        // 写进了 `badge`，这里必须接住，否则第二行会退到类型标签（没标签就整行空白）。
        // 前缀与首页「历史记录」栏一致，用户一眼能分出这条记录来自账号。
        (collection=="history" || (tab==TAB_PROFILE && accountTab=="history")) && item.badge.isNotBlank() -> "账号 · ${item.badge}"
        else -> ""
    }
    // 名次放左上角（榜单页才有）；右下角统一由卡片按 `episodeCount` 画「共 N 集」。
    val rankText=if(tab==TAB_RANK) rank?.let { "第 $it 名" }.orEmpty() else ""
    card.series=item
    card.tag=item.id
    card.visibility=View.VISIBLE
    // 未上架没有集数：右下角改画「预告」，否则那一角空着，卡片看起来像数据缺了。
    card.bind(item,statusText,if(item.upcoming) "预告" else "",
        progress?.takeIf { it.duration>0 }?.let { (it.position*100/it.duration).toInt().coerceIn(0,100) },
        rank!=null && rank<=3,rankText=rankText,preferStatus=collection=="history" || item.upcoming)
}

/**
 * 装一张卡片的监听器：**只在卡片新建时调一次**。
 *
 * 卡片会被复用，因此回调里一律通过 [PosterCard.series] 取「这一张卡现在是谁」，
 * 不捕获某一次的 item。
 */
internal fun MainActivity.wireGridCard(card: PosterCard) {
    card.setOnClickListener { card.series?.let { item -> openSeries(item,collection=="history") } }
    card.setOnLongClickListener { card.series?.let { item -> seriesMenu(item,card) }; true }
    card.setOnKeyListener { _,key,event ->
        val item=card.series
        when {
            key==KeyEvent.KEYCODE_MENU -> {
                if(event.action==KeyEvent.ACTION_UP && item!=null) seriesMenu(item,card)
                true
            }
            // 右下角那枚「回到顶部」：从**本行最后一张卡**按右键过去。
            // 「最后一张」按条目算（不是按列位置）：最后一行不满时它就停在列中间，右键仍然进浮层。
            //
            // 判据只看「本行还有没有卡在右边」，不再要求「这一行必须是屏幕最底下那一行」：
            // 那种几何判据在实机上不可靠——列表底部还有留白 / 底行下面露着一条细边，
            // 用户焦点停在看得见的最后一张卡上按右键却毫无反应（连续两次反馈都是这一条）。
            // 而本行最后一张卡按右键本来**什么都不做**（nextFocusRightId 指回自己），
            // 因此把这一下交给浮层不抢任何已有手势。
            key==KeyEvent.KEYCODE_DPAD_RIGHT && event.action==KeyEvent.ACTION_DOWN -> {
                val holder=gridRows.firstOrNull { it.line===card.parent } ?: return@setOnKeyListener false
                val column=holder.cards.indexOf(card)
                val lastInRow=minOf(gridItems.size-1,(holder.index+1)*gridColumns-1)
                val top=backToTopTarget()
                if(top!=null && holder.index*gridColumns+column==lastInRow) { top.requestFocus(); true } else false
            }
            // 焦点在窗口的上下边界上、而目标行还没物化时，先把它物化再按索引落过去。
            // 已经物化的情况交给 nextFocusUpId / nextFocusDownId（框架自己走，不经过这里）。
            (key==KeyEvent.KEYCODE_DPAD_UP || key==KeyEvent.KEYCODE_DPAD_DOWN) && event.action==KeyEvent.ACTION_DOWN -> {
                val holder=gridRows.firstOrNull { it.line===card.parent } ?: return@setOnKeyListener false
                val column=holder.cards.indexOf(card)
                val row=holder.index+(if(key==KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1)
                val target=row*gridColumns+column
                when {
                    // 第一行再按上键：焦点交回页头那一行（榜单 / 分类芯片、搜索框）。
                    // 页头收着的时候这里顺手把它展开，否则焦点会落在一条高度为 0 的看不见的行上。
                    key==KeyEvent.KEYCODE_DPAD_UP && row<0 -> {
                        val head=headFocusTarget()
                        if(head!=null) { focusHead(head); true } else false
                    }
                    // 目标列在最后一行不满时没有卡片：这一下交回框架，由 nextFocusDownId
                    // 落到「展示更多」上，而不是被吞掉什么都不做。
                    row in 0 until gridRowCount() && target in gridItems.indices -> {
                        // 只有焦点**真的移动了**才吃掉这一下：目标行因为窗口还没物化而落空时，
                        // 交回框架走 nextFocusUpId / nextFocusDownId，不能变成「按了没反应」。
                        focusGridIndex(target,false)
                        gridFocusIndex()==target
                    }
                    // 最后一行再按下键就是「继续往下看」：不经过底部按钮，直接把下一页接上。
                    key==KeyEvent.KEYCODE_DPAD_DOWN && isGridTail(holder) && appendCatalog() && hasMore -> { loadMore(); true }
                    else -> false
                }
            }
            else -> false
        }
    }
    card.setOnFocusChangeListener { _,focused ->
        card.setFocused(focused)
        if(focused) {
            val holder=gridRows.firstOrNull { it.line===card.parent }
            // 搜索页：焦点进到第二行就清空输入框（列表与 [query] 都不动，见 [noteSearchRowFocused]）。
            if(holder!=null) noteSearchRowFocused(holder.index)
            card.series?.let { item ->
                catalogFocus=item.id
                // 被低内存策略摘掉的卡不在这里重挂（它可能刚从视口外滚进来，紧接着的
                // resumeVisibleCovers 会按「真的可见」重新挂上，并带上 FOCUSED 优先级）。
                if(!card.coverSuspended) loadCover(card.image,item.cover)
                // 焦点走到倒数两行就把下一页取回来，用户真按下去时内容已经在手上。
                // 焦点一移动就按「还剩几行」判一次（见 [nearGridEnd]）：
                // 按住下键时这就是唯一会持续触发的时机。
                if(holder!=null) prefetchNextPage()
            }
            if(holder==null) return@setOnFocusChangeListener
            // 焦点一动就把预取窗口挪到新的位置。
            //
            // **这一步不能只挂在滚动回调上**：焦点在视口内上下移动时列表根本不滚动
            // （`revealRow` 发现目标行已经可见就直接返回），滚动回调因此不会触发，
            // 预取窗口会一直停在「上一次真的滚动过」的那个位置。
            // 表现出来就是：焦点慢慢往下走，走到窗口边缘之外时图才开始现取——
            // 用户读到的正是「前半段无感、后面开始一片灰框」。
            prefetchAroundWindow()
            // 焦点在视口内移动时列表不滚动，滚动回调不会触发：这里补一次「摘掉的封面挂回来」。
            resumeVisibleCovers()
            if(!gridRestoring) revealRow(holder.index,true)
        }
    }
}

/** 把某一行带进视口：窗口化之后行号就是坐标，不需要等布局。 */
internal fun MainActivity.revealRow(row: Int,smooth: Boolean) {
    val scroll=gridScroll ?: return
    val stride=rowStride()
    val top=row*stride
    val target=when {
        top<scroll.scrollY -> top
        top+stride>scroll.scrollY+scroll.height -> (top+stride-scroll.height).coerceAtLeast(0)
        else -> return
    }
    if(smooth) scroll.smoothScrollTo(0,target) else scroll.scrollTo(0,target)
}

/**
 * 把焦点按**全局索引**落到某张卡上。
 *
 * 目标行不在当前窗口里时，先把列表滚到那一行、再按新的视口算窗口——
 * 而不是「把窗口拉大到包含它」：后者在「恢复的焦点离视口很远」时会把中间几百行全物化出来。
 * 滚动是同步的（不用平滑动画），因此后面算出来的窗口与坐标都是确定的。
 */
/**
 * 只在**当前没有任何控件持有焦点**时，才把焦点补到 [target] 上。
 *
 * 这是全站「不主动抢焦点」原则的落点工具（见类头那段说明）：列表重建、异步数据落地、
 * 空页兜底这些**不是用户动作**的场合，一律不许把焦点从用户正按着的地方搬走；
 * 但「焦点真的丢了」（控件被摘掉、页面刚建好）时必须补一个确定的落点，
 * 否则系统会把它交给新布局里第一个可聚焦控件，位置不可预期。
 *
 * 返回这次真正拿到焦点的控件（没补就是 null），调用方可以据此决定要不要滚动。
 */
internal fun MainActivity.ensureFocusView(target: () -> View?): View? {
    if(focusHeld()) return null
    val view=target() ?: return null
    view.requestFocus()
    return view.takeIf { it.hasFocus() }
}

/** 同上，给「动作本身不返回控件」的落点用（例如按索引落焦点）。 */
internal fun MainActivity.ensureFocus(action: () -> Unit) {
    if(focusHeld()) return
    action()
}

/** 当前有没有控件**真的**握着焦点（被摘掉的控件不算）。 */
internal fun MainActivity.focusHeld(): Boolean {
    val current=root.findFocus() ?: return false
    return current.attachedToWindow
}

internal fun MainActivity.focusGridIndex(index: Int,smooth: Boolean=true) {
    if(index !in gridItems.indices) return
    val row=index/gridColumns
    val scroll=gridScroll
    if(scroll!=null && (gridRows.none { it.index==row })) {
        val stride=rowStride()
        val top=row*stride
        val target=when {
            top<scroll.scrollY -> top
            top+stride>scroll.scrollY+scroll.height -> (top+stride-scroll.height).coerceAtLeast(0)
            else -> scroll.scrollY
        }
        if(target!=scroll.scrollY) scroll.scrollTo(0,target)
    }
    syncGridWindow(focusIndex=index)
    val holder=gridRows.firstOrNull { it.index==row } ?: return
    val card=holder.cards.getOrNull(index%gridColumns) ?: return
    card.requestFocus()
    if(smooth) revealRow(row,true)
    // 焦点**直接跳到**远处（恢复上次位置、追加后落到新行）时也要挪预取窗口：
    // 那条路径不经过滚动回调，也不经过焦点的逐格移动。
    prefetchAroundWindow()
}

/**
 * 重绑当前窗口那几行（剧集状态变了：收藏 / 历史进度 / 榜单名次刷新）。
 *
 * 窗口化之后「就地更新一张卡」不再按 id 找控件，而是重绑可见的那几十张——
 * 代价恒定，且不会把更新漏在还没物化的行上（它们物化时自然会绑到最新状态）。
 */
internal fun MainActivity.rebindGridRows() {
    gridRows.forEach { holder -> if(holder.line.attachedToWindow) bindGridRow(holder) }
}

/** 当前焦点所在的全局索引；焦点不在栅格里时返回 null。 */
internal fun MainActivity.gridFocusIndex(): Int? {
    gridRows.forEach { holder ->
        val column=holder.cards.indexOfFirst { it.hasFocus() }
        if(column>=0) return holder.index*gridColumns+column
    }
    return null
}

/**
 * 重算栅格的焦点链。
 *
 * 窗口化之后这里只处理**当前物化的那几行**（≤ 一屏 + 2 行），因此代价与已加载数量无关。
 * 窗口上下边界上的「目标行还没物化」由 [wireGridCard] 的按键处理按索引补物化，
 * 因此链在这些边界上写成「指向自己」也不会把焦点卡死。
 */
internal fun MainActivity.wireGridFocus(prev: View?,next: View?) {
    val rows=gridRows.sortedBy { it.index }
    if(rows.isEmpty()) {
        // 内容为空（搜索无结果）时也要把导航栏那几件的「按下」出口重指一次：
        // 早先直接 return，`searchInput.nextFocusDownId` 会**留着上一次指向的提示词芯片**，
        // 而那枚芯片此刻已经 GONE——在输入框按回车就会抛
        // 「focus search returned a view that wasn't able to take focus!」。
        wireSearchHeadDown(null)
        return
    }
    // 栅格第一行按上键回到它正上方那一行（见 [headFocusTarget]）。
    // 历史页两处都有「清空全部记录」（集合页在导航栏、我的页在身份行），
    // 它都在内容上方，因此优先落到它上面；没有它时退回 [headFocusTarget]。
    val headUp=(historyClear ?: headFocusTarget())?.id
    val first=rows.first().cards.firstOrNull()?.id ?: return
    val lastRow=gridRowCount()-1
    rows.forEachIndexed { position,holder ->
        val cards=holder.cards
        cards.forEachIndexed { column,card ->
            card.nextFocusLeftId=cards[(column-1).coerceAtLeast(0)].id
            card.nextFocusRightId=cards[(column+1).coerceAtMost(cards.lastIndex)].id
            card.nextFocusUpId=when {
                position>0 -> rows[position-1].cards[minOf(column,rows[position-1].cards.size-1)].id
                holder.index==0 -> headUp ?: card.id
                else -> card.id
            }
            card.nextFocusDownId=when {
                position<rows.lastIndex -> rows[position+1].cards[minOf(column,rows[position+1].cards.size-1)].id
                holder.index>=lastRow -> next?.takeIf { it.isFocusable }?.id
                    ?: prev?.takeIf { it.isFocusable }?.id ?: card.id
                else -> card.id
            }
        }
    }
    // 导航栏那排圆形入口按下键进内容第一张卡；「我的」页例外——内容上面还夹着身份行，
    // 先落身份行（未登录时那一行里的「导入账号参数」是唯一入口，跳过它就再也够不着）。
    // 分类页例外：上面有多行筛选，先落进第一行（体裁）当前选中的那一枚。
    // 「我的 → 历史」的「清空全部记录」在身份行里，内容正上方就是它，导航栏按下先经过它。
    // 历史集合页那一枚在**导航栏里**（[historyClearInline]），与圆形入口同一行：
    // 那时按下要直接进内容，不能落回同一行里的按钮上。
    val navDown=when {
        historyClear!=null && !historyClearInline -> historyClear!!.id
        tab==TAB_PROFILE -> profileHeadControl()?.id ?: first
        tab==TAB_CATEGORY -> categoryFilterButtons[CategorySelection.TYPE_GENRE]?.get(categorySelection.genre.orEmpty())?.id ?: first
        else -> first
    }
    navIcons().forEach { it.nextFocusDownId=navDown }
    // 导航栏首位那枚「推荐」按钮（不在 nav 里，见 ListPage）：按下进内容，按上停在原地。
    findTagged(root,"nav:recommend")?.let { recommend ->
        recommend.nextFocusDownId=navDown
        recommend.nextFocusUpId=recommend.id
    }
    // 榜单芯片按下键进搜索框（没有）或内容第一张卡。
    boardButtons.values.forEach { it.nextFocusDownId=searchInput?.id ?: first }
    // 分类页的筛选行：行与行的上下键已经在建行时接好，这里只把**最后一行**接到内容第一张卡。
    val lastFilterType=categoryPanel?.rows?.lastOrNull()?.type
    categoryFilterButtons.forEach { (type,buttons) ->
        buttons.values.forEach { chip ->
            if(lastFilterType==null || type==lastFilterType) chip.nextFocusDownId=first
        }
    }
    // 「我的」页的焦点链：芯片 ↓ 身份行 ↓ 内容第一张卡（方向连续，不跳过中间那一行）。
    // 少了这一条芯片就指向自己（建芯片时为了避免「按下键没反应」先设成了自己），
    // 表现出来就是「焦点在收藏 / 历史上按向下，焦点停在原地不动」。
    // 「清空全部记录」在身份行**里面**（退出 / 刷新之后），因此身份行按下不该落到它身上——
    // 那是在同一行里往右跳。身份行按下一律进内容第一张卡。
    val headDown=profileHeadControl()?.id ?: first
    val contentDown=first
    accountButtons.values.forEach { it.nextFocusDownId=headDown }
    profileHeadControls().forEach { control ->
        // 内嵌登录 / 导入面板展开时，身份行的下键要进面板，不能被这里改回内容区。
        if(accountPanelMode==null) control.nextFocusDownId=contentDown
        accountButtons[accountTab]?.let { chip -> control.nextFocusUpId=chip.id }
    }
    // 「我的 → 历史」的「清空全部记录」自己：上回身份行、下进第一张卡、右停住（它在整行最右边）。
    // 导航栏里那一枚不在这里接（它左右是同排的圆形入口，见 ListPage）。
    if(!historyClearInline) historyClear?.let { clear ->
        clear.nextFocusUpId=(accountButtons[accountTab]?.id ?: headDown)
        clear.nextFocusDownId=first
        clear.nextFocusRightId=clear.id
    }
    // 搜索页：输入框与「搜索 / 历史 / 手机输入」都在导航栏里，按下键一律先落进
    // 输入框下面那一行（提示词 / 热搜词），没有那一行时直接进内容第一张卡。
    val hintDown=suggestButtons.firstOrNull()?.id ?: first
    searchInput?.nextFocusDownId=hintDown
    searchButton?.nextFocusDownId=hintDown
    searchActions.forEach { it.nextFocusDownId=hintDown }
    // 提示词 / 热搜芯片按下键进内容第一张卡。芯片是**渲染之后才有的**，
    // 渲染那一刻栅格可能还没铺好（那时它们只能指向自己），因此这里每次重排都补一次。
    suggestButtons.forEach { it.nextFocusDownId=first }
    // 列表最后一行按下键落在底部「展示更多」上，再按一次（确认或下键）就追加下一页。
    moreButton?.nextFocusUpId=rows.last().cards.firstOrNull()?.id ?: first
}
/**
 * 已显示进度。榜单自带总页数，所以能给「N / M 部」；
 * 搜索结果只知道自己追加到第几页，就只报已显示数量，加载完再明确说「全部」。
 */
internal fun MainActivity.moreCountText(): String {
    val total=(ranking?.totalPages ?: 0)*pageSize
    return when {
        !hasMore -> "已显示全部 ${catalog.size} 部"
        total>catalog.size -> "已显示 ${catalog.size} / $total 部"
        else -> "已显示 ${catalog.size} 部"
    }
}
/** 追加一页时把两页的名次与热度合起来，否则先加载的那几页会丢掉名次。 */
internal fun MainActivity.mergeRanking(previous: ComicRanking?,next: ComicRanking?): ComicRanking? = when {
    previous==null -> next
    next==null -> previous
    else -> next.copy(positions=previous.positions+next.positions)
}
