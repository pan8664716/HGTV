// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import com.hgtv.star.tv.list.gridFocusIndex
import com.hgtv.star.tv.list.nearGridEnd
import com.hgtv.star.tv.list.prefetchNextPage
import com.hgtv.star.tv.list.updateBackToTop

/** 记住一页列表的滚动位置；超出 [SCROLL_MEMORY] 条就丢掉最早的一条。 */
internal fun TvMainActivity.rememberScrollImpl(key: String,value: Int) {
    if(key.isBlank()) return
    if(key !in scrollPositions && scrollPositions.size>=SCROLL_MEMORY) scrollPositions.keys.firstOrNull()?.let(scrollPositions::remove)
    scrollPositions[key]=value
}

/** 把一行页头登记进「随滚动收起」的区域。 */
internal fun TvMainActivity.registerTopAreaRowImpl(row: View) {
    topArea+=row
    val restore=row.layoutParams?.height ?: LinearLayout.LayoutParams.WRAP_CONTENT
    topAreaRestore[row]=restore
    if(topAreaHidden) {
        // 页头正收着，这一行也必须立刻收起来：先量出展开高度当基准，再按收起状态摆好，
        // 中间不留一帧展开态，否则重建时页头会闪一下。
        expandedHeightOf(row)
        applyTopAreaState(row,true)
    } else {
        // 布局完成后再量一次真实高度：收起动画的起点、展开动画的终点都按它算。
        row.post { if(topArea.contains(row) && row.height>0) topAreaHeights[row]=row.height }
    }
}

/**
 * 列表滚动 → 页头收展。
 *
 * 收起用高度动画而不是 translationY：收掉之后列表真的多占这一条高度，顶部不会留一条空白，
 * 也不会出现「按钮浮在列表上面」的叠层。只有列表回到最上边、或者焦点回到页头里的控件时才展开：
 * 列表中间向上滚只保持当前状态，不会半路把页头弹回来。收起 / 展开之后有一小段静默期，
 * 避开视口变化引起的 scrollY 回夹，否则会在底部来回抖。
 */
internal fun TvMainActivity.onListScrollImpl(y: Int,force: Boolean) {
    // 静默期里只更新位置、不改收展状态：这期间的滚动是位置恢复 / 内容夹取，不是用户滚的。
    if(!force && android.os.SystemClock.elapsedRealtime()<topAreaSettleUntil) { listScrollY=y; updateBackToTop(); return }
    listScrollY=y
    // 低内存时被摘掉的封面：滚回视口就挂回来（`suspendedCovers == 0` 时是一次整数比较）。
    resumeVisibleCovers()
    setTopAreaHidden(headerShouldHide(y),animate=!force)
    updateBackToTop()
    // 手指 / 遥控滚到离底部还有小半屏时就先把下一页取回来：
    // 真滚到底的时候内容已经在手上，接上是瞬间的，用户看不到「正在加载」。
    // 触发点统一交给 [nearGridEnd]（按「还剩几行」算）：这里只管页头收展。
    if(nearGridEnd()) prefetchNextPage()
}

/**
 * 把页头的收 / 展状态按**当前位置与焦点**重算一次。
 *
 * 页头状态是「位置 + 焦点」的纯函数（见 [onListScrollImpl]）：任何可能改变这两者的事件
 * 之后都该重算一次，否则状态会与事实脱节——**焦点移动**就是最容易被漏掉的那一类：
 * 焦点从第二行回到第一行时列表被 `reveal` 滚回顶部，那次滚动常常落在静默期或
 * `restoring` 里被吞掉，于是「位置已经回到顶部、页头却还收着」或者反过来。
 * 用户读到的就是「按上键页头反而隐藏」这种**状态反过来**的现象。
 */
internal fun TvMainActivity.syncHeaderStateImpl() {
    if(screen!="catalog" || topArea.isEmpty()) return
    val y=homeScreen?.scrollY() ?: catalogScroll?.scrollY ?: return
    listScrollY=y
    setTopAreaHidden(headerShouldHide(y),animate=false)
    updateBackToTop()
}

/**
 * 页头该不该收起——**位置与焦点的纯函数**。
 *
 * 两条判据任一成立就收起：
 *
 * 1. **列表滚过顶部的余量**（`y > HEADER_SHOW_THRESHOLD`）：向下浏览时把页头让开，
 *    这是它本来的职责（滚回最上边就展开）；
 * 2. **焦点已经离开第一栏**（`focusedContentRow() >= 1`）：首页的栏高是按「一屏放得下一栏」
 *    算的，焦点走到第二栏时往往**位置还是 0**（第二栏的下半截本来就在屏幕上），
 *    只看位置会判成「该展开」，而用户看到的是「焦点明明已经往下走了、页头却还占着地方」。
 *
 * 例外：焦点在页头里时**永远展开**——收着的话那一行高度是 0，焦点会停在一个看不见的控件上。
 */
internal fun TvMainActivity.headerShouldHideImpl(y: Int): Boolean {
    if(topAreaHasFocus()) return false
    if(y>dp(HEADER_SHOW_THRESHOLD)) return true
    if(!TvStyle.animate) return false
    return focusedContentRow()>=1
}

/**
 * 焦点当前落在内容区的第几行（从 0 起）；焦点不在内容上时返回 -1。
 *
 * 两种内容分别算：首页是剧架（[com.hgtv.star.tv.list.HomeScreen.focusedRowIndex]），
 * 其余页面是卡片栅格（[gridFocusIndex] 除以列数）。两者都按「第 1 行是页头正下方那一行」算。
 */
private fun TvMainActivity.focusedContentRow(): Int {
    homeScreen?.let { home -> return home.focusedRowIndex() }
    val index=gridFocusIndex() ?: return -1
    return index/gridColumns
}

/** 列表是不是已经滚到距底部 [slack] 像素以内。没有栅格（首页剧架、设置页）时永远为 false。 */
internal fun TvMainActivity.gridAtBottomImpl(slack: Int): Boolean {
    val scroll=gridScroll ?: return false
    val list=gridList ?: return false
    if(scroll.height<=0 || list.height<=0) return false
    return scroll.scrollY+scroll.height+slack>=list.height
}

private fun TvMainActivity.topAreaHasFocus()=topArea.any { it.findFocus()!=null }

/**
 * 一行页头展开时的高度。工具条这类固定高度的行直接用 LayoutParams，
 * 其余（搜索行、榜单标题行、集合页标题行）按页面栅格宽度量一次自然高度，不用等布局。
 */
private fun TvMainActivity.expandedHeightOf(row: View): Int {
    val restore=topAreaRestore[row] ?: LinearLayout.LayoutParams.WRAP_CONTENT
    topAreaHeights[row]?.takeIf { it>0 }?.let { return it }
    if(restore>0) { topAreaHeights[row]=restore; return restore }
    // 收起状态不影响这次测量：量的是内容在页面栅格宽度下的自然高度。
    val width=if(row.width>0) row.width
        else (resources.displayMetrics.widthPixels-2*(pageInset()-pageBleed())).coerceAtLeast(1)
    row.measure(
        View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED)
    )
    val measured=row.measuredHeight
    if(measured>0) topAreaHeights[row]=measured
    // 量不出来（内容还没建好）就退回当前高度：这一行不做动画、直接到位，不会朝 0 缩。
    return measured.takeIf { it>0 } ?: row.height
}

/** 把一行页头直接摆到目标状态（不排队动画）：收起 = 高度归零 + 淡出 + 打开裁剪；展开反之。 */
private fun TvMainActivity.applyTopAreaState(row: View,hide: Boolean) {
    (row as? ViewGroup)?.let { group -> group.clipChildren=hide; group.clipToPadding=hide }
    row.animate().cancel()
    row.alpha=if(hide) 0f else 1f
    val restore=topAreaRestore[row] ?: LinearLayout.LayoutParams.WRAP_CONTENT
    row.layoutParams=row.layoutParams.apply { height=if(hide) 0 else restore }
}

internal fun TvMainActivity.setTopAreaHiddenImpl(hide: Boolean,animate: Boolean) {
    if(topAreaHidden==hide || topArea.isEmpty()) return
    topAreaHidden=hide
    // 搜索页：页头收起会把提示词那一行压成 0 高，而输入框的 `nextFocusDownId` 还指着
    // 那些芯片——按回车时 `focusSearch` 会把它交回来、`requestFocus` 却必然失败，
    // 于是 `TextView` 抛「focus search returned a view that wasn't able to take focus!」。
    // 收展状态一变就按当前实际可聚焦情况重接一次。
    if(tab==TAB_SEARCH) wireSearchHeadDown(suggestButtons.firstOrNull())
    // [animate] 为 false 时直接到位：用于「按当前滚动位置把状态摆正」那一次
    // （进页面 / 恢复位置之后），那时做动画反而会让页头闪一下。
    // 首页是**未窗口化**的一整棵树（7 栏 ≈ 77 张卡 ≈ 800 个 View）：高度动画每帧都要
    // 整页 relayout，200ms = 12 次全页布局，正好叠在「按住下键滚动 → 页头收起」这个
    // 最高频动作上。这里直接到位，只丢一点动画感；栅格路径已经窗口化（约 30 个 View），
    // 照旧做动画。
    val span=if(animate && TvStyle.animate && homeScreen==null) TvStyle.DURATION else 0L
    topAreaSettleUntil=android.os.SystemClock.elapsedRealtime()+span+120
    topArea.forEach { row ->
        val from=row.height
        val to=if(hide) 0 else expandedHeightOf(row)
        // 先摘掉再取消：被取消的那条动画结束回调不会再回头改这一行的高度。
        topAreaAnimators.remove(row)?.cancel()
        row.animate().cancel()
        row.animate().alpha(if(hide) 0f else 1f).setDuration(span).start()
        // 收起过程中不再让子控件画到行外，展开时立刻恢复（焦点描边要继续画得出来）。
        (row as? ViewGroup)?.let { group -> group.clipChildren=hide; group.clipToPadding=hide }
        // 低内存设备（TvStyle.animate=false）直接到位，不排队动画；没有高度差时也一样。
        if(span==0L || from==to) { settleTopAreaRow(row,hide); return@forEach }
        topAreaAnimators[row]=ValueAnimator.ofInt(from,to).apply {
            duration=span
            interpolator=DecelerateInterpolator()
            addUpdateListener { animation ->
                row.layoutParams=row.layoutParams.apply { height=animation.animatedValue as Int }
            }
            addListener(object: AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // 摘掉自己这一步必须是原子的：这条回调也可能由 cancel() 同步触发。
                    if(topAreaAnimators.remove(row)===this@apply) settleTopAreaRow(row,hide)
                }
            })
            start()
        }
    }
}

/** 动画结束：收起的行高度归零，展开的行还原成登记时的 LayoutParams 高度。 */
private fun TvMainActivity.settleTopAreaRow(row: View,hide: Boolean) {
    applyTopAreaState(row,hide)
    // 展开到位后重新量一次真实高度，作为下一次收起 / 展开的基准。
    if(!hide) row.post { if(topArea.contains(row) && row.height>0) topAreaHeights[row]=row.height }
}
