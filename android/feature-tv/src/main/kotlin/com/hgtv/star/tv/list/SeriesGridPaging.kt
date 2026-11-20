// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv.list

import android.view.Gravity
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.TextView
import com.hgtv.star.core.*
import com.hgtv.star.shared.ui.LoadingDotsView
import com.hgtv.star.tv.LOAD_MORE
import com.hgtv.star.tv.LOAD_MORE_RETRY_COOLDOWN_MS
import com.hgtv.star.tv.MAX_CATALOG_PAGE
import com.hgtv.star.tv.PREFETCH_PAGES
import com.hgtv.star.tv.PREFETCH_TRIGGER_ITEMS
import com.hgtv.star.tv.TAB_CATEGORY
import com.hgtv.star.tv.TAB_HOME
import com.hgtv.star.tv.TAB_PROFILE
import com.hgtv.star.tv.TAB_RANK
import com.hgtv.star.tv.TAB_SEARCH
import com.hgtv.star.tv.TvButton
import com.hgtv.star.tv.TvMainActivity
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.tv.accountHistoryPaging
import com.hgtv.star.tv.appendCatalog
import com.hgtv.star.tv.autoAppendCatalog
import com.hgtv.star.tv.button
import com.hgtv.star.tv.catalogSelectionInFlight
import com.hgtv.star.tv.dp
import com.hgtv.star.tv.gapInto
import com.hgtv.star.tv.gridColumns
import com.hgtv.star.tv.loadMoreAccountHistory
import com.hgtv.star.tv.lp
import com.hgtv.star.tv.noteDebugProblem
import com.hgtv.star.tv.showHint
import com.hgtv.star.tv.text

fun TvMainActivity.moreLabel(): String = when {
    moreFailed -> "加载失败 · 重试"
    else -> "展示更多 ↓"
}

fun TvMainActivity.loadMore() {
    if(tab==TAB_PROFILE && collection==null && accountTab=="history") {
        loadMoreAccountHistory()
        return
    }
    if(!hasMore || !appendCatalog()) return
    if(gridList==null || gridItems.isEmpty()) {
        moreFocus=null; page++; catalogFocus=""
        keepTopAreaState=true
        showCatalog(true)
        return
    }
    requestNextPage(userInitiated=true)
}

fun TvMainActivity.prefetchNextPage() {
    if(catalogSelectionInFlight()) return
    if(accountHistoryPaging()) {
        if(!hasMore || accountHistoryLoading || gridList==null) return
        if(accountHistoryHalfConsumed()) loadMoreAccountHistory()
        return
    }
    if(workJob?.isDone==false) return
    if(screen!="catalog" || !appendCatalog() || !hasMore || moreInFlight || gridList==null) return
    if(moreFailed) {
        val elapsed=android.os.SystemClock.elapsedRealtime()-moreFailedAt
        if(elapsed<LOAD_MORE_RETRY_COOLDOWN_MS) return
        moreFailed=false
        moreFailedAt=0L
    }
    if(page+1>MAX_CATALOG_PAGE) return
    requestNextPage(userInitiated=false)
}

fun TvMainActivity.applyNextPage(next: Int,result: CatalogPage,userInitiated: Boolean) {
    if(!hasMore || !appendCatalog() || gridList==null) return
    val merged=CatalogPaging.append(catalog,ranking,next,result)
    val added=merged.added
    catalog=merged.items
    ranking=merged.ranking
    hasMore=merged.hasMore
    page=merged.page
    val target=if(userInitiated) added.firstOrNull()?.id ?: LOAD_MORE else null
    if(userInitiated) catalogFocus=added.firstOrNull()?.id.orEmpty()
    appendGridItems(added,target)
    if(hasMore && CatalogPaging.shouldPrefetch(itemsLeftAfterConsumed())) {
        prefetchChain++
        if(prefetchChain<PREFETCH_PAGES) prefetchNextPage()
    } else {
        prefetchChain=0
    }
}

fun TvMainActivity.nearGridEnd(): Boolean {
    if(accountHistoryPaging()) return accountHistoryHalfConsumed()
    if(gridItems.isEmpty()) return false
    return CatalogPaging.shouldPrefetch(itemsLeftAfterConsumed())
}

/** 电视端与手机一致：当前历史已消费到一半时再取下一页 20 条。 */
fun TvMainActivity.accountHistoryHalfConsumed(): Boolean {
    if(!accountHistoryPaging() || gridItems.isEmpty()) return false
    val consumed=gridConsumedIndex()
    return consumed>=0 && consumed+1>=(gridItems.size+1)/2
}

fun TvMainActivity.itemsLeftAfterConsumed(): Int {
    val consumed=gridConsumedIndex()
    if(consumed<0) return gridItems.size
    return (gridItems.size-(consumed+1)).coerceAtLeast(0)
}

fun TvMainActivity.gridConsumedIndex(): Int {
    val focus=gridFocusIndex() ?: -1
    val scroll=gridScroll
    val stride=rowStride()
    val viewport=if(scroll!=null && scroll.height>0 && stride>0) {
        val lastRow=(scroll.scrollY+scroll.height)/stride
        lastRow*gridColumns+gridColumns-1
    } else -1
    return maxOf(focus,viewport)
}

fun TvMainActivity.requestNextPage(userInitiated: Boolean) {
    if(catalogSelectionInFlight()) return
    if(!hasMore || !appendCatalog() || gridList==null) return
    if(moreInFlight) {
        if(userInitiated) { moreVisible=true; moreFocusWanted=true; updateFooter() }
        return
    }
    val refreshFirstPage=tab==TAB_RANK && page==1 && !catalogPageOneFresh
    val next=if(refreshFirstPage) 1 else page+1
    if(next>MAX_CATALOG_PAGE) { hasMore=false; updateFooter(); return }
    moreInFlight=true; moreInFlightPage=next; moreVisible=userInitiated
    moreFocusWanted=userInitiated; moreFailed=false; moreFailedAt=0L
    if(userInitiated) prefetchChain=0
    updateFooter()
    val pageStarted=android.os.SystemClock.elapsedRealtime()
    if(com.hgtv.star.tv.BuildConfig.DEBUG) android.util.Log.i("HGTV-PAGE",
        "请求第 $next 页（已显示 ${gridItems.size} 部 / ${gridRowCount()} 行） 用户触发=$userInitiated")
    val ticket=generation; val board=rankBoard; val category=categorySelection
    val requestTab=tab; val scope=collection; val requestQuery=query
    val requestSource=library.searchSource
    gridPrefetchJob=io.submit {
        val result=runCatching {
            when {
                requestTab==TAB_SEARCH && scope==null -> repository.search(requestQuery,next,requestSource)
                requestTab==TAB_CATEGORY && scope==null -> repository.categoryFeed(category,next,needPanel=false).page
                else -> repository.rank(next,board,ranking)
            }
        }.getOrNull()
        main.post {
            if(isDestroyed || generation!=ticket) return@post
            if(moreInFlightPage==next) { moreInFlight=false; moreVisible=false }
            if(com.hgtv.star.tv.BuildConfig.DEBUG) android.util.Log.i("HGTV-PAGE",
                "第 $next 页返回：${result?.items?.size ?: -1} 部，耗时 "
                + "${android.os.SystemClock.elapsedRealtime()-pageStarted}ms，hasMore=${result?.hasMore}")
            val wantsFocus=moreFocusWanted; moreFocusWanted=false
            if(result==null) {
                if(wantsFocus) showHint("加载更多失败，请检查网络后重试")
                noteDebugProblem("加载更多失败 · 第 ${next} 页 · 已显示 ${gridItems.size} 部")
                moreFailed=true; moreFailedAt=android.os.SystemClock.elapsedRealtime(); updateFooter()
                return@post
            }
            if(scope==collection && requestTab==tab && requestQuery==query && rankBoard==board &&
                (requestTab!=TAB_CATEGORY || categorySelection==category) && screen=="catalog" && gridList!=null)
                applyNextPage(next,result,wantsFocus)
            else updateFooter()
        }
    }
}

fun TvMainActivity.appendGridItems(added: List<Series>,focusTarget: String?) {
    if(gridList==null) return
    val visible=if(tab==TAB_HOME || tab==TAB_RANK || tab==TAB_CATEGORY) added.filterNot { it.id in library.hidden() } else added
    if(visible.isNotEmpty()) {
        gridItems=gridItems+visible
        syncGridWindow()
        rebindGridRows()
    }
    if(pagingHasMore!=hasMore) rebuildPagingRow() else updateFooter()
    wireGridFocus(null,moreButton?.takeIf { it.isFocusable })
    when {
        focusTarget==null -> Unit
        focusTarget==LOAD_MORE -> moreButton?.let { button -> button.requestFocus(); revealRow(gridRowCount()-1,true) }
        else -> gridItems.indexOfFirst { it.id==focusTarget }.takeIf { it>=0 }?.let { index -> focusGridIndex(index,true) }
    }
}

fun TvMainActivity.fillPagingRow(paging: LinearLayout) {
    moreButton=null; moreCount=null
    pagingHasMore=hasMore
    paging.addView(text(moreCountText(),14f,muted).apply { gravity=Gravity.CENTER },lp(dp(200),dp(TvStyle.BUTTON_NORMAL+2*TvStyle.SOFT_BAND)))
    moreCount=paging.getChildAt(0) as? TextView
    if(!hasMore || autoAppendCatalog()) return
    val more=button(moreLabel()) { loadMore() }.apply {
        tag=LOAD_MORE
        nextFocusLeftId=id; nextFocusRightId=id
        setOnKeyListener { _,key,event ->
            if(key==KeyEvent.KEYCODE_DPAD_DOWN && event.action==KeyEvent.ACTION_UP) { loadMore(); true } else false
        }
    }
    gapInto(paging,more,lp(-2,-2))
    moreButton=more
    updateFooter()
}

fun TvMainActivity.rebuildPagingRow() {
    val paging=gridPaging ?: return
    val keepButtonFocus=moreButton!=null && moreButton!!.hasFocus()
    paging.removeAllViews()
    fillPagingRow(paging)
    if(keepButtonFocus && moreButton==null) gridRows.lastOrNull()?.cards?.firstOrNull()?.requestFocus()
}

fun TvMainActivity.updateFooter() {
    moreCount?.text=moreCountText()
    val more=moreButton ?: return
    (more as? TvButton)?.setLabel(moreLabel())
    more.alpha=if(moreVisible && moreInFlight) TvStyle.DISABLED_ALPHA else 1f
    val paging=gridPaging ?: return
    val tag="paging-loading"
    val existing=paging.findViewWithTag<LoadingDotsView>(tag)
    val show=moreVisible && moreInFlight
    when {
        show && existing==null -> paging.addView(
            LoadingDotsView(this).apply { this.tag=tag },
            LinearLayout.LayoutParams(dp(64),dp(18)).apply { leftMargin=dp(TvStyle.GAP_INLINE) }
        )
        !show && existing!=null -> paging.removeView(existing)
    }
}
