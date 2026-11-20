// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv.list

import android.graphics.Canvas
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import com.hgtv.star.core.*
import com.hgtv.star.shared.compat.onScrollYChanged
import com.hgtv.star.tv.*

fun TvMainActivity.setRankSubtitle(value: String) {
    val view=rankSubtitle ?: return
    view.text=value
    view.visibility=if(value.isBlank()) View.GONE else View.VISIBLE
}

fun TvMainActivity.seriesList(body: LinearLayout,focusNav: Boolean,focusType: Boolean=false,loading: Boolean=false,loadingMore: Boolean=false,keepScroll: Int?=null,keepFocus: Boolean=false) {
    val chipHeld=chipFocusKey!=null || keepFocus
    gridList=null; gridScroll=null; gridPaging=null; moreCount=null; pagingHasMore=false
    gridWindow=null; gridTopSpacer=null; gridBottomSpacer=null
    gridItems=emptyList(); gridRowStride=0; gridWindowStart=-1
    // 行和卡片留到 [gridRowPool] 里复用；只摘掉当前窗口的封面位图，页面切换不重建卡片。
    gridRows.toList().forEach(::recycleGridRow)
    gridRows.clear()
    gridWindow=null
    gridTopSpacer=null; gridBottomSpacer=null
    gridCardWidth=0; gridGap=0; gridPosterHeight=0
    gridRestoring=true
    if(tab==TAB_HOME && page==1 && collection==null) { renderHome(body,focusNav,focusType,loading); return }
    if(tab==TAB_PROFILE) {
        catalog=accountItems(accountTab)
        hasMore=collection==null && accountTab=="history" && accountHistoryHasMore
        ranking=null
    }
    val wanted=moreFocus; moreFocus=null
    moreButton=null
    topArea.filter { it.parent==null }.forEach { row ->
        topAreaAnimators.remove(row)?.cancel()
        topAreaHeights.remove(row); topAreaRestore.remove(row)
    }
    topArea.removeAll { it.parent==null }
    if(collection=="favorites") {
        val status=text(favoriteMonitor.status,13f,muted).apply { setPadding(pageBleed(),0,pageBleed(),dp(4)) }
        favoriteStatus=status
        body.addView(status); registerTopAreaRow(status)
    }
    catalogPageKey=buildString {
        append(tab); append(':'); append(collection.orEmpty()); append(':')
        append(if(appendCatalog() || fullList()) 1 else page); append(':')
        if(tab==TAB_CATEGORY) append(categorySelection.key()).append(':')
        if(tab==TAB_PROFILE) append(accountTab).append(':')
        append(query)
    }
    val scroll=ScrollView(this).apply { isFillViewport=false; isVerticalScrollBarEnabled=false }.focusBleed(); catalogScroll=scroll
    val list=column(); scroll.addView(list); body.addView(tvScrollHost(scroll),lp(-1,0).apply { weight=1f })
    listScrollY=scroll.scrollY
    topAreaSettleUntil=android.os.SystemClock.elapsedRealtime()+TvStyle.DURATION+120
    scroll.onScrollYChanged { y ->
        if(catalogScroll!==scroll || gridRestoring) return@onScrollYChanged
        syncGridWindow()
        onListScroll(y)
    }
    val displayed=when {
        fullList() -> catalog
        tab==TAB_HOME || tab==TAB_RANK || tab==TAB_CATEGORY -> catalog.filterNot { it.id in library.hidden() }
        appendCatalog() -> catalog
        else -> catalog.take(30)
    }
    if(displayed.isEmpty()) {
        if(tab==TAB_SEARCH && collection==null && query.isNotBlank() && !loading)
            noteDebugProblem("搜索「${query.take(16)}」· ${SearchDiag.explain()}")
        if(tab==TAB_PROFILE && accountTabWaiting(accountTab)) {
            loading(list)
            focusFirstCardOnLanding=false
            ensureFocusView { chipControl(chipFocusKey) ?: firstHeadControl() ?: navEntry() }
            return
        }
        if(tab==TAB_PROFILE &&
            (accountError!=null || (accountTab!="history" && accountData?.profile==null))) {
            accountNotice(list)
            focusFirstCardOnLanding=false
            ensureFocusView { chipControl(chipFocusKey) ?: firstHeadControl() ?: navEntry() }
            return
        }
        if(loading) loading(list) else message(list,when {
            tab==TAB_SEARCH -> if(hongguoKeyboardMode()) "用左侧键盘输入字符，按「搜索」查看结果" else if(query.isBlank()) "输入关键词后按「搜索」；遥控器用方向键选字，也可外接键盘" else "没有找到相关剧集，换个关键词试试"
            tab==TAB_CATEGORY -> "${categoryDescription()}暂时没有内容"
            collection=="favorites" -> "在剧集详情中选择收藏，喜欢的剧就会出现在这里"
            collection=="history" -> when(accountHistoryFilter) {
                true -> "没有已看完的剧集；再点一次「已看完」可以回到全部"
                false -> "没有未看完的剧集；再点一次「未看完」可以回到全部"
                else -> "播放过的剧集会自动保存在这里"
            }
            tab==TAB_PROFILE -> if(!repository.signedIn) "导入账号参数后，这里会显示云端的内容" else when(accountTab) {
                "favorites" -> "账号里还没有收藏的剧集"
                "history" -> "账号里还没有观看记录"
                "likes" -> "账号里还没有点赞的剧集"
                else -> "账号里还没有预约的剧集"
            }
            else -> "本页没有更多内容"
        })
        if(appendCatalog() && hasMore && !autoAppendCatalog()) addButton(list,moreLabel()) { loadMore() }
        if(autoAppendCatalog() && hasMore) message(list,"向下滚动会自动加载更多")
        focusFirstCardOnLanding=false
        ensureFocusView { hongguoKeyboard?.view?.takeIf { it.childCount>0 }?.let { keyboard ->
            hongguoKeyboard?.focusFirstKey(); keyboard
        } ?: chipControl(chipFocusKey) ?: firstHeadControl() ?: navEntry() }
        return
    }
    val gap=dp(6)
    val gridWidth=if(hongguoKeyboardMode() && tab==TAB_SEARCH && collection==null) {
        (hongguoResultsColumn?.width ?: 0).takeIf { it>0 }?.let {
            (it-2*dp(TvStyle.FOCUS_BLEED)-dp(4)).coerceAtLeast(dp(320))
        } ?: run {
            val room=(resources.displayMetrics.widthPixels-2*(pageInset()-pageBleed())-4*gap).coerceAtLeast(dp(320))
            (room*4f/7f).toInt()
        }
    } else gridContentWidth()
    val columns=gridColumns
    val width=((gridWidth-gap*(columns-1))/columns).toInt()
    val posterHeight=((width-dp(2*TvStyle.SOFT_BAND+8))/TvStyle.POSTER_ASPECT).toInt()
    gridList=list; gridScroll=scroll; gridCardWidth=width; gridGap=gap; gridPosterHeight=posterHeight
    gridItems=displayed
    gridRowStride=0
    gridWindowStart=-1
    val topSpacer=Space(this); val window=column(); val bottomSpacer=Space(this)
    gridTopSpacer=topSpacer; gridWindow=window; gridBottomSpacer=bottomSpacer
    list.addView(topSpacer,lp(-1,0))
    list.addView(window,lp(-1,-2))
    list.addView(bottomSpacer,lp(-1,0))
    syncGridWindow(force=true)
    val paging=row(); paging.gravity=Gravity.CENTER
    var prev: View?=null; var next: View?=null
    if(appendCatalog() || accountHistoryPaging()) {
        gridPaging=paging
        fillPagingRow(paging)
        list.addView(paging,lp(-1,dp(TvStyle.BUTTON_NORMAL+2*TvStyle.SOFT_BAND+TvStyle.GAP_TIGHT)))
        next=moreButton?.takeIf { it.isFocusable }
    } else if(tab==TAB_SEARCH) {
        prev=addButton(paging,"上一页") { if(page>1) { page--; catalogFocus=""; showCatalog() } }.apply { isEnabled=page>1; isFocusable=page>1 }
        gapInto(paging,text("第 $page 页",14f,muted).apply { gravity=Gravity.CENTER },lp(dp(95),dp(TvStyle.BUTTON_NORMAL+2*TvStyle.SOFT_BAND)))
        next=addButton(paging,"下一页") { if(hasMore) { page++; catalogFocus=""; showCatalog() } }.apply { isEnabled=hasMore; isFocusable=hasMore }
        list.addView(paging,lp(-1,dp(TvStyle.BUTTON_NORMAL+2*TvStyle.SOFT_BAND+TvStyle.GAP_TIGHT)))
    }
    wireGridFocus(prev,next)
    val appendedIndex=when {
        wanted==null -> null
        wanted==LOAD_MORE -> null
        else -> gridItems.indexOfFirst { it.id==wanted }.takeIf { it>=0 }
    }
    val savedScroll=keepScroll ?: if(chipHeld) null else scrollPositions[catalogPageKey]
    scroll.post {
        if(catalogScroll!==scroll) return@post
        val fitted=if(columns>=6) minOf(posterHeight,(scroll.height-dp(56)).coerceAtLeast(dp(120))) else posterHeight
        gridPosterHeight=fitted
        gridRowStride=0
        gridRows.forEach { holder -> holder.cards.forEach { card -> card.resizePoster(fitted) } }
        measureRowStride()
        savedScroll?.let { scroll.scrollTo(0,it) }
        syncGridWindow(force=true,focusIndex=gridFocusIndex())
        gridRestoring=false
        syncHeaderState()
    }
    list.postDelayed({ if(catalogScroll===scroll) gridRestoring=false },400)
    when {
        hongguoKeyboardMode() && tab==TAB_SEARCH && collection==null -> {
            val landFirst=hongguoLandFirstCard
            hongguoLandFirstCard=false
            scroll.post {
                if(catalogScroll!==scroll) return@post
                if(landFirst) {
                    ensureFocus { focusGridIndex(0,false) }
                    if(!focusHeld()) hongguoKeyboard?.focusFirstKey()
                } else hongguoKeyboard?.focusFirstKey()
            }
        }
        focusType -> firstHeadControl()?.let { focusHead(it) }
        focusNav -> navEntry()?.let { focusHead(it) }
        else -> scroll.post {
            if(catalogScroll!==scroll) return@post
            if(chipHeld) return@post
            when {
                loadingMore -> ensureFocus { moreButton?.requestFocus() }
                wanted==LOAD_MORE -> {
                    ensureFocus { moreButton?.requestFocus() }
                    revealRow(gridRowCount()-1,false)
                }
                appendedIndex!=null -> ensureFocus { focusGridIndex(appendedIndex,false) }
                else -> ensureFocus { focusGridIndex(gridItems.indexOfFirst { it.id==catalogFocus }.takeIf { it>=0 } ?: 0,false) }
            }
            updateBackToTop()
        }
    }
    if(!chipHeld) prefetchNextPage()
}

internal class ClipFrameLayout(context: android.content.Context) : FrameLayout(context) {
    init {
        clipChildren=true
        clipToPadding=true
    }
    override fun dispatchDraw(canvas: Canvas) {
        val save=canvas.save()
        canvas.clipRect(0,0,width,height)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }
}

internal fun lastFocusable(view: View): View? {
    if(view.visibility!=View.VISIBLE) return null
    if(view is android.view.ViewGroup) {
        for(index in view.childCount-1 downTo 0) {
            lastFocusable(view.getChildAt(index))?.let { return it }
        }
    }
    return if(view.isFocusable) view else null
}

internal fun firstFocusable(view: View): View? {
    if(view.visibility!=View.VISIBLE) return null
    if(view.isFocusable) return view
    if(view !is android.view.ViewGroup) return null
    for(index in 0 until view.childCount) {
        firstFocusable(view.getChildAt(index))?.let { return it }
    }
    return null
}
