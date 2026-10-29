// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import com.hgtv.star.list.attachBackToTop

import android.app.Activity
import android.graphics.Typeface
import android.view.*
import android.widget.*
import com.hgtv.star.core.*
import com.hgtv.star.MainActivity.DeferredUi
import com.hgtv.star.data.ArtworkLoader
import com.hgtv.star.tv.TvButton
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.list.showCatalog

// 本文件是从 MainActivity.kt 拆出来的「本机库加载、封面取图、异步任务封装与空页 / 错误提示」这一块。
// 这些成员仍然属于 MainActivity，只是以扩展成员（MainActivity.xxx）的形式放在这里，
// 逻辑与拆之前逐行一致；改这里等于改 Activity 本体。

internal fun MainActivity.showLibraryError(problem: Throwable) {
    val body=base(); error(body,problem,stage="library_load") { message(base(),"正在读取本机记录…"); loadLibrary() }
}
internal fun MainActivity.loadLibrary() {
    libraryLoadError=null
    library.loadAsync(onFailure={ problem ->
        if(!isDestroyed) { libraryLoadError=problem; if(foreground) showLibraryError(problem) }
    }) {
        if(!isDestroyed && foreground) {
            applySessionToken(); applyAccount()
            // 本机设置读回来了：把「减少动画」偏好合进性能档位（见 [applyPerformanceProfile]）。
            // 必须在建第一页之前跑——首页那一屏的卡片是按当时的档位建的
            //（跑马灯 / 柔光位图都在构造时定下来）。
            applyPerformanceProfile()
            // 「使用设备」也是本机设置：onCreate 里那一次读到的还是默认的 `auto`，
            // 用户手动选过的那一档到这里才读回来。第一屏马上要建了，必须在这一步之前
            // 把形态重新落一次（触屏手势层 / 底部控制条都按它决定建不建）。
            applyDeviceForm()
            // 凭据就绪之后再起心跳：冷启动那一次 `boot` 心跳必须有完整身份（cookie + 设备档案）。
            com.hgtv.star.data.SessionBootstrap.onAppStart()
            // 冷启动落点是推荐页（需求 1）：本机记录一读起来就直接进推荐、自动起播，
            // 不先闪一下首页。之后从推荐返回主页面不会再把用户跳回推荐（见 [launchRecommendPending]）。
            //
            // 设置里关掉「打开应用进入推荐」（或关掉导航栏那枚推荐入口）之后落点改回首页：
            // 这是同一份偏好的两种说法，冷启动必须跟着它走。
            if(launchRecommendPending) {
                launchRecommendPending=false
                if(library.launchRecommend) openRecommend() else showCatalog(load=true)
            } else showCatalog(load=true)
            favoriteMonitor.check()
            // 账号数据：**先把缓存铺上去**（[applyAccount] 已经把它交给界面），再后台刷一次。
            // 缓存新鲜（[ACCOUNT_FRESH_MS]）时 [loadAccount] 自己会跳过请求。
            if(repository.signedIn) loadAccount()
        }
    }
}
/**
 * 搜索登录态（可选）跟着本机设置走：留空时按匿名设备请求，填上后带着它请求。
 * 设置里改完立刻作用于后面的搜索请求。
 */
internal fun MainActivity.applySessionToken() { repository.sessionToken=library.searchToken.ifBlank { null } }
internal fun MainActivity.base(title: String?=null): LinearLayout {
    root.removeAllViews(); root.setBackgroundColor(bg)
    val container=column().apply { clipChildren=false; clipToPadding=false }
    // 纵向留白保持改造前的 12 个设计像素。
    //
    // 曾经为「电视过扫描」在这里补过 24（合计约 5%）。撤销的原因：那一改会改变
    // 电视上原有的版面留白（一屏行数、首行位置都跟着动），而它只是 P2 级的观感优化，
    // 不值得拿已经调好的版式去换。真要支持过扫描，应该做成设置项（默认关闭），
    // 而不是默认加边距。
    val horizontal=pageInset()-pageBleed(); val vertical=dp(12)
    container.setPadding(horizontal,vertical,horizontal,vertical); root.addView(container,FrameLayout.LayoutParams(-1,-1))
    // 「回到顶部」浮层挂在 root 上：root 刚被清空，这里把它重新挂上（收着，等滚动来定显隐）。
    attachBackToTop()
    // 调试面板同理：整页重建会把它一起清掉，打开调试模式时在这里重挂。
    reattachDebugOverlay()
    if(title!=null) {
        // 二级界面（详情页加载中）不再放返回按钮：返回键与顶部导航就是返回入口，页头只留标题。
        // 页头行补回焦点余量，标题仍从内容线开始。
        val heading=row().apply { setPadding(pageBleed(),0,pageBleed(),dp(6)) }
        heading.addView(text(title,20f).apply { setTypeface(null,Typeface.BOLD) },lp(0,dp(TvStyle.BUTTON_NORMAL)).apply { weight=1f })
        container.addView(heading)
    }
    return container
}
/**
 * 加载一张封面（卡片 / 详情页大图 / 头像都走这里）。
 *
 * 预取不走这个入口——它需要「按整份列表的索引取 URL」，与 View 无关，
 * 因此由 [MainActivity.prefetchAroundWindow] 直接调 `artwork.prefetchWindow`。
 */
internal fun MainActivity.loadCover(view: ImageView,url: String) {
    artwork.load(view,url,coverPriority(view))
}
/**
 * 这张封面该按哪一档排队——**按它此刻是不是真的在屏幕上**分。
 *
 * 物化窗口是「视口上下各多留一行」算出来的（一屏约 17 行 × 6 列 ≈ 102 张），
 * 而真正在屏幕上的只有约 3 行 ≈ 18 张。早先这 102 张全拿 [ArtworkLoader.PRIORITY_VISIBLE]，
 * 于是屏幕上那 18 张要排在另外 84 张**看不见的**后面——
 * 实测 `visQAvg=162ms`、灰框 p50=265ms，而单张只要 11ms。
 *
 * 判据用「有没有和视口相交」：`getLocalVisibleRect` 在没和任何祖先相交时返回 false，
 * 因此窗口上下多留的那几行会落到 [ArtworkLoader.PRIORITY_NEARBY]（它们也是接下来要看的，
 * 只是不在屏幕上）。焦点那一张仍然最高。
 */
internal fun MainActivity.coverPriority(view: View): Int {
    var ancestor: View?=view
    var focused=false
    while(ancestor!=null && ancestor!==root) {
        if(ancestor.isFocused) { focused=true; break }
        ancestor=ancestor.parent as? View
    }
    if(focused) return ArtworkLoader.PRIORITY_FOCUSED
    val rect=android.graphics.Rect()
    return if(view.getGlobalVisibleRect(rect)) ArtworkLoader.PRIORITY_VISIBLE
    else ArtworkLoader.PRIORITY_NEARBY
}
internal fun MainActivity.cancelWork() {
    deferredWork?.discard?.invoke(); deferredWork=null
    workScope?.cancel(); workScope=null
    workJob?.cancel(true); workJob=null
    (io as? java.util.concurrent.ThreadPoolExecutor)?.purge()
}
internal fun MainActivity.invalidatePage() {
    generation++; cancelWork(); artwork.cancelPage()
    // 详情页下方那一块的重铺入口随页面一起作废：旧引用留着会指到已经拆掉的视图树。
    detailBottomRefresh=null
}
internal fun MainActivity.releaseCatalogViews() {
    homeScreen?.let { homePosition=it.capturePosition() }
    // 换榜单 / 换分类时 [catalogPageKey] 还是**上一条列表**的 key（它要到 seriesList 里才重算），
    // 因此这里照记没错：记的是上一条列表自己的浏览位置，用户切回来时还能回到原处。
    catalogScroll?.let { rememberScroll(catalogPageKey,it.scrollY) }
    catalogBody=null
    homeScreen=null; catalogScroll=null
    // 低内存摘封面的计数跟着旧页面一起作废，否则新页面上会一直走「检查是否回视口」那条路。
    suspendedCovers=0
    nav.clear(); navKeyed.clear(); recommendIcon=null; boardButtons.clear(); accountButtons.clear(); profileHeadRow=null; profileAvatar=null
    historyFilterButtons.clear(); reserveFilterButtons.clear()
    // 页面整块重建：内嵌登录面板跟着旧页面一起作废，在途的一次性会话立刻关掉。
    closeAccountPanelSession()
    accountPanelHost=null; accountPanelMode=null; accountPanelTrigger=null
    // 顶部那两枚「二维码登录 / 手机登录」随页面一起丢掉：旧引用留着会让焦点链指向不在树上的控件。
    loginModeChips.clear()
    categoryFilterButtons.clear()
    searchInput=null; searchButton=null; voiceButton=null; searchActions.clear()
    suggestRow=null; suggestViewport=null; suggestLine=null; suggestButtons.clear()
    // 提示词那一行连同在途的请求一起作废：它属于上一页的输入框，换页后不该再往新页面里塞。
    suggestRun.getAndSet(null)?.let { main.removeCallbacks(it) }
    suggestJob?.cancel(true); suggestJob=null
    suggestToken.incrementAndGet()
    rankSubtitle=null; moreButton=null
    favoriteStatus=null; historyClear=null; historyClearInline=false
    // 栅格连同它的追加状态一起作废：在途的预取结果不能再往新页面上接。
    gridPrefetchJob?.cancel(true); gridPrefetchJob=null
    gridList=null; gridScroll=null; gridPaging=null; moreCount=null; pagingHasMore=false
    gridWindow=null; gridTopSpacer=null; gridBottomSpacer=null
    gridItems=emptyList(); gridRowStride=0; gridWindowStart=-1
    // 换页时把窗口里与回收池里的封面一起摘掉：这两处位图不在缓存账上，
    // 只清引用不清 View 的话，换页后的旧图要等到 GC 才走（见 [recycleGridRow]）。
    gridRows.forEach { row -> row.cards.forEach { it.releaseCover() } }
    gridRowPool.forEach { row -> row.cards.forEach { it.releaseCover() } }
    gridRows.clear(); gridRowPool.clear(); gridCardWidth=0; gridGap=0; gridPosterHeight=0
    moreInFlight=false; moreInFlightPage=0; moreVisible=false; moreFocusWanted=false; moreFailed=false
    // 页头收展状态：同一页原地追加（加载更多）保留，其余换页都从展开态开始。
    val keepTopArea=keepTopAreaState
    keepTopAreaState=false
    if(!keepTopArea) topAreaHidden=false
    // 运行中的页头动画先快照、再清表、最后取消。
    // ValueAnimator.cancel() 会**同步**回调 onAnimationEnd，而那条回调会从这张表里摘掉自己；
    // 边遍历这张表边取消就是 ConcurrentModificationException——页头有两行以上
    // （搜索行 / 榜单标题行 / 集合页标题行）时必现，而收展动画那 200ms 里
    // 「展示更多」「刷新榜单」、切分类、进详情都会重建页面，表现出来就是偶发崩溃。
    val running=topAreaAnimators.values.toList()
    topAreaAnimators.clear()
    running.forEach { it.cancel() }
    topArea.clear(); topAreaHeights.clear(); topAreaRestore.clear()
}
internal fun <T> MainActivity.work(action: ()->T,done: (T)->Unit,failed: (Throwable)->Unit) {
    cancelWork()
    val ticket=generation; val scope=RequestScope(); workScope=scope
    workJob=io.submit {
        try {
            val result=scope.run(action)
            main.post {
                val discard={ if(result is RemoteVideo) result.close(); Unit }
                if(isDestroyed || generation!=ticket) discard()
                else if(foreground) done(result)
                else { deferredWork?.discard?.invoke(); deferredWork=DeferredUi({ done(result) },discard) }
            }
        } catch(e: Exception) {
            main.post {
                if(!isDestroyed && generation==ticket) {
                    if(foreground) failed(e) else deferredWork=DeferredUi({ failed(e) })
                }
            }
        }
    }
}
/**
 * 错误页的标题（也是 logcat 里那条现场行的标题）：**按异常类型给常量**。
 *
 * 不能把 message 拼进来——那里面带主机名、带 HTTP 码、带每次不同的中文，
 * 每台设备都会各自变成一条 issue。
 */
private fun contentErrorTitle(problem: Throwable): String = when(problem) {
    is java.net.UnknownHostException -> "内容加载失败：无法连接服务器"
    is java.net.SocketTimeoutException -> "内容加载失败：连接超时"
    is SignInRequiredException -> "内容加载失败：需要先导入账号"
    is SearchSessionExpiredException -> "内容加载失败：搜索会话过期"
    is DeviceNotRegisteredException -> "内容加载失败：设备正在初始化"
    else -> "内容加载失败"
}

/** 详情那一行：给用户看的短句，同时作为上报附件。 */
private fun contentErrorDetail(problem: Throwable): String =
    if(problem is java.net.UnknownHostException) "无法连接内容网站"
    else if(problem is java.net.SocketTimeoutException) "连接超时"
    else if(problem is DeviceNotRegisteredException) "设备正在初始化，请稍后重试"
    else problem.message.orEmpty().replace(Regex("https?://\\S+"),"[地址]").take(160)

internal fun MainActivity.message(parent: LinearLayout,label: String) { parent.addView(text(label,17f,muted).apply { setPadding(pageBleed(),dp(28),pageBleed(),dp(18)) }) }
/**
 * 加载失败时的说明 + 「重试」按钮；返回那枚按钮，调用方可以把它接进自己的焦点链。
 *
 * **这是全站唯一的错误页出口**（首页 / 榜单 / 搜索 / 详情 / 本机记录都走它），
 * 因此上报也放在这里，一处覆盖五条最常见的「打不开」反馈。
 *
 * [stage] 只用来区分是哪一页（`content_home` / `content_rank` / `content_search` /
 * `content_detail` / `library_load`）——它决定 issue 的分组，必须稳定。
 */
internal fun MainActivity.error(parent: LinearLayout,problem: Throwable,stage: String,retry: ()->Unit): TvButton {
    // 上报：错误页出现 = 用户真的什么都看不到了，属于「可感知的失败」。
    // 消息是常量（见 Diagnostics 的聚合说明），具体原因走 detail。
    Diagnostics.fail(stage,contentErrorTitle(problem),contentErrorDetail(problem),problem)
    // 右上角调试面板单独记一条「这一页为什么空」：上报那条会去重，面板这条每次刷新都能看到。
    notePageFailure(stage,problem)
    // 设备还没注册上不是网络问题：这时候让用户去检查路由器只会把问题推远一步。
    message(parent,if(problem is DeviceNotRegisteredException) "设备正在初始化，请稍后重试。" else "暂时无法加载，请检查网络后重试。")
    val detail=contentErrorDetail(problem)
    parent.addView(text(detail,13f,muted).apply { setPadding(pageBleed(),0,pageBleed(),0) })
    // 重试按钮与上面的提示文字共用同一条内容线：纵向间距由 addButton 给，横向自己补回焦点余量。
    return addButton(parent,"重试",onClick=retry).apply { (layoutParams as LinearLayout.LayoutParams).leftMargin=pageBleed(); requestFocus() }
}
