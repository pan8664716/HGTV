// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import android.app.Activity
import android.view.*
import android.widget.*
import com.hgtv.star.core.*
import com.hgtv.star.list.HomeScreen
import com.hgtv.star.findTagged
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.list.seriesList
import com.hgtv.star.list.rebindGridRows
import com.hgtv.star.list.openSeries
import com.hgtv.star.list.seriesMenu
import com.hgtv.star.list.updateBackToTop
import com.hgtv.star.data.WatchProgress

/** 首页一栏一屏放满的条数：本机记录与榜单都按它截断，也是「查看全部」入口的门槛。 */
private const val SHELF_SIZE=10

// 本文件是从 MainActivity.kt 拆出来的「首页四个剧架与剧卡的快捷菜单（收藏 / 稍后看）」这一块。
// 这些成员仍然属于 MainActivity，只是以扩展成员（MainActivity.xxx）的形式放在这里，
// 逻辑与拆之前逐行一致；改这里等于改 Activity 本体。

internal fun MainActivity.renderHome(body: LinearLayout,focusNav: Boolean,focusType: Boolean,loading: Boolean) {
    homeUpdateCount=library.updatedFavorites()
    // 首页没有自己的顶部入口（品牌是标识），剧卡按上键回到第一枚圆形入口。
    val top=nav.firstOrNull() ?: navIcons().firstOrNull() ?: root
    val home=HomeScreen(this,top,pageInset(),::loadCover,
        { item,resume -> openSeries(item,resume) },{ item,anchor,shelf -> seriesMenu(item,anchor,shelf) },{ catalogFocus=it },
        columns=library.homeColumns)
    homeScreen=home; body.addView(home,lp(-1,0).apply { weight=1f })
    // 首页**不挂**「回到顶部」：它是剧架（横滑 + 纵向滚动），回到顶部没有意义，
    // 而按钮压在最后一栏右下角反而挡住卡片。那枚按钮只属于栅格列表页
    // （榜单 / 分类 / 集合 / 搜索 / 我的，见 [com.hgtv.star.list.updateBackToTop]）。
    // 首页剧架的滚动也走同一套页头收展：向下浏览时把品牌 / 顶部入口收起来。
    //
    // 这里必须和栅格路径（见 [seriesList] 里那段）一样开静默期、并在渲染收尾时
    // **主动把最终位置报回来一次**，否则首页会停在「列表已经回到顶部、页头却还收着」：
    // 顶部导航栏整条不显示（高度 0），用户按上键只落到看不见的图标上，得靠把焦点
    // 挪到第一栏（历史记录）之类能把列表再滚一次的动作才恢复。
    //
    // 成因：位置恢复 / 内容变矮引起的夹取都发生在 `restoring` 期间，那期间的滚动
    // [HomeScreen.onScroll] 一律不上报（它们不是用户滚的）。若这次夹取恰好把列表
    // 拉回顶部（比如剧架变短），页头就永远收着——没有任何后续滚动事件能纠正它。
    // [HomeScreen.onSettled] 补的就是这一次：用 [force] 绕开静默期，按最终位置把状态摆正。
    listScrollY=0
    topAreaSettleUntil=android.os.SystemClock.elapsedRealtime()+TvStyle.DURATION+120
    home.onScroll={ onListScroll(it) }
    home.onSettled={ y ->
        listScrollY=y
        // 按「位置 + 焦点」把状态摆正（不动画）：进页面 / 返回首页时页头不该闪。
        setTopAreaHidden(headerShouldHide(y),animate=false)
        // 位置恢复（「回到首页」把上次的滚动位置带回来）之后，右下角那枚「回到顶部」
        // 也要按真实位置决定显隐：这次恢复不经过 [onListScroll]（它属于「不是用户滚的」）。
        updateBackToTop()
    }
    // 第一栏按上键回页头：先把页头展开再落焦点（[focusHead] 就是干这个的）。
    // 首页没有「自己的入口」（品牌是标识），因此落点是那排圆形入口的第一枚；
    // 一枚都没有（理论上不会）就交回框架，让 nextFocusUpId 兜底。
    // 第一栏按上键回页头。圆形入口整排关掉时页头没有可落的控件，
    // 这一下改成打开「前往」选择器：否则关掉导航栏之后就没有跳到别的页面的手势了。
    home.onHeadUp={
        val target=nav.firstOrNull() ?: navIcons().firstOrNull() ?: navEntry()
        if(target!=null) { focusHead(target); true } else { showNavPicker(home.firstCard()); true }
    }
    // 首页底部不再挂「更多热门」：四个「热播…」剧架的行末「查看全部」直接进对应的榜单页，
    // 底部再放一个全局入口是重复的。
    // 首页没有切换芯片：焦点不在顶部入口上时直接落到第一栏第一张卡。
    val shelves=homeShelves()
    home.render(shelves,catalogFocus,!focusNav,homePosition)
    if(loading && !homeFromCache) showHint("正在更新热门…")
    // **首页自己把账号数据拉起来**：上面那两栏（历史记录 / 我的收藏）的内容一半来自账号侧，
    // 没有它就只剩本机那几条——重装后第一次启动尤其明显（那时本机几乎是空的）。
    //
    // 放在 `body.post` 里而不是直接调：账号数据落地后 [refreshAccountViews] 会重建这一页，
    // 在渲染过程中再进 [showCatalog] 会边建边拆（同 [loadAccount] 里那条注释）。
    // 已经有数据、或正在读、或上一次失败过，[loadAccount] 自己会跳过，不会重复打请求。
    if(repository.signedIn && accountData==null && accountError==null && !accountLoading) {
        body.post { if(!isDestroyed && foreground && screen=="catalog") loadAccount() }
    }
    navIcons().forEach { it.nextFocusDownId=home.firstId() }
    // 导航栏首位那枚「推荐」按钮：按下进第一栏第一张卡，按上停在原地。
    findTagged(root,"nav:recommend")?.let { recommend ->
        recommend.nextFocusDownId=home.firstId()
        recommend.nextFocusUpId=recommend.id
    }
    // 首页没有自己的顶部入口（品牌是标识、不参与焦点链），所以要「把焦点留在页头」时
    // 只能退到第一栏第一张卡：焦点始终有落点，不会凭空消失。
    if(focusNav) (navEntry() ?: home.firstCard())?.requestFocus()
    if(homeFeed.values.any { it.items.isNotEmpty() } && !homeContentLogged) {
        homeContentLogged=true
        body.post { android.util.Log.i("HGTV","Home content ready cached=$homeFromCache elapsedMs=${android.os.SystemClock.elapsedRealtime()-activityStarted}") }
    }
    if(launchStarted>0) {
        val started=launchStarted; launchStarted=0
        body.post { android.util.Log.i("HGTV","Home displayed cached=$homeFromCache elapsedMs=${android.os.SystemClock.elapsedRealtime()-started}") }
    }
}

/**
 * 首页七个栏目，顺序固定：
 *
 * 1. 历史记录 —— 本机观看进度，按最近观看排序；未看完的条目确认后直接续播。
 * 2. 我的收藏 —— 本机收藏，带「更新至第几集」的状态行。
 * 3. 稍后观看 —— 本机稍后观看清单。
 * 4. 热播漫剧 / 5. 热播真人 / 6. 热播AI / 7. 热播上新 —— 榜单接口排出来的四个榜，
 *    与排行榜页的四个榜单**是同一份数据**：行末「查看全部」直接进对应的榜单页。
 *
 * 每栏最多十部；本机没有记录的三栏、远端取不到的榜单都不占位（[HomeScreen.render] 过滤空架子）。
 */
/**
 * 首页「历史记录」栏：本机观看进度在前（有集内秒数、能续播），账号侧的历史补在后面。
 *
 * 账号历史也带「看到第几集」与**集内秒数**（平台给 `vid_index` 与 `current_play_time`），
 * 因此状态行与续播目标都按本机那一条同样的规则算：按确认直接续到那一处。
 * 同一部剧两边都有时以**本机**为准（本机记录更细——还知道这一集是不是已看完，
 * 而且用户是在这台设备上看的）。
 */
internal fun MainActivity.historyRows(local: List<WatchProgress>): List<HomeScreen.Entry> {
    // 历史栏是卡片样式的**唯一例外**：第二行不显示类型标签，而是「第 x 集 · xx:xx」——
    // 这一栏回答的是「我看到哪了」，进度比标签重要得多（见 PosterCard.syncMeta）。
    val rows=local.take(10).map { p -> HomeScreen.Entry(p.series,
        "第 ${p.episodeIndex+1} 集 · ${if(p.completed) "整剧已看完" else formatTime(p.position)}",!p.completed,
        if(p.duration>0) (p.position*100/p.duration).toInt().coerceIn(0,100) else null,
        if(p.duration>0) "${formatTime(p.position)} / ${formatTime(p.duration)}" else "第 ${p.episodeIndex+1} 集") }.toMutableList()
    val seen=rows.map { it.series.id }.toSet()
    // 账号侧那半份滤掉「本机删过」的与已经在本机那半份里出现的（见 [Library.historyDeleted]）。
    accountData?.history?.filterNot { it.series.id in seen || library.historyDeleted(it.series.id) }
        ?.take(10-rows.size)?.forEach { row ->
        // 「第几集 + 看到第几秒」两样都要写出来：只写集数时用户看不出还剩多少没看，
        // 点进去也不知道会从哪儿接着放。
        val position=if(row.position>0) " · ${formatTime(row.position)}" else ""
        rows+=HomeScreen.Entry(row.series,"账号 · 第 ${row.episodeIndex+1} 集$position",true)
    }
    return rows
}
/**
 * 首页「我的收藏」栏：本机收藏在前（带「更新至第几集」状态），账号侧的收藏补在后面。
 *
 * 两边都有的剧只出现一次（以本机那条为准，因为它带更新状态）。
 */
internal fun MainActivity.favoriteRows(local: List<Series>): List<HomeScreen.Entry> {
    val rows=local.take(10).map { HomeScreen.Entry(it,library.favoriteLabel(it.id)) }.toMutableList()
    val seen=rows.map { it.series.id }.toSet()
    accountData?.favorites?.filterNot { it.series.id in seen }?.take(10-rows.size)?.forEach { row ->
        rows+=HomeScreen.Entry(row.series,"账号 · 收藏于 ${shortDate(row.collectTime)}")
    }
    return rows
}
internal fun MainActivity.homeShelves(): List<HomeScreen.Shelf> {
    val history=library.history(); val favorites=library.favorites()
    val later=library.later()
    // 栏目的顺序与名字只有一个来源（[HomeLayout.columns]，有单元测试钉住）；
    // 这里只负责按 key 往每一栏里填内容，某一栏没有内容就返回 null（界面不渲染空架子）。
    // 本机记录那三栏的入口就是行末那张「查看全部」底板（见 [localShelf]），**只要有记录就给**，
    // 与热播四栏同一条规则：底板在，右键就直达二级页。少了它这一栏既没有底板、
    // 行末右键也无处可去，用户读到的就是「首页的历史 / 收藏 / 稍后看进不去二级页」。
    val shelves=mapOf<String,HomeScreen.Shelf?>(
        "history" to localShelf("history",historyRows(history)),
        "favorites" to localShelf("favorites",favoriteRows(favorites)),
        "later" to localShelf("later",later.take(10).map { HomeScreen.Entry(it,"稍后观看 · 长按管理") })
    ) + RankBoard.entries.associate { board -> "rank-${board.storedValue}" to rankShelf(board) }
    // 设置 → 外观导航 → 导航显隐：被关掉的那一栏**不渲染**（连行末「查看全部」底板一起没有）。
    // 本机三栏关掉不动记录本身；热播四栏关掉只是首页不铺那条剧架，榜单页 / 推荐页照旧
    //（那是另一个入口，不由这一枚开关决定）。
    return HomeLayout.columns.filterNot { library.homeHidden(it.key) }.mapNotNull { column -> shelves[column.key] }
}
/**
 * 首页的一栏本机记录（历史记录 / 我的收藏 / 稍后观看）。
 *
 * 行末那张「查看全部」底板就是这一栏进本机记录集合页的入口，但**只在这栏放满一屏时给**：
 * 不足 [SHELF_SIZE] 条时这一栏的内容已经全部在眼前，二级页只是把同一份内容再列一遍，
 * 白白多一个入口；放满一屏（可能还有更多）才给底板，遥控移过去按确认、或手指点一下都能进二级页，
 * 行末再按右键也直达。
 * 底板不挤窄海报：卡片宽度是按「一屏 6 张」定死的，多一张只是让这一行能横向滚动。
 * 热播四栏走的是同一条规则（见 [rankShelf]），两处行为保持一致。
 *
 * 没有记录时返回 null（界面不渲染空架子，也不会只剩一张底板）。
 */
internal fun MainActivity.localShelf(key: String,entries: List<HomeScreen.Entry>): HomeScreen.Shelf? {
    if(entries.isEmpty()) return null
    val more: (() -> Unit)?=if(entries.size>=SHELF_SIZE) ({ showCollection(key) }) else null
    return HomeScreen.Shelf(key,HomeLayout.title(key),entries,more)
}
/**
 * 首页的一个「热播…」栏目：内容是该榜单第 1 页的前十部（已过滤本机隐藏的剧集）。
 *
 * 栏目名与榜单名不一样（见 [RankBoard]）：栏目按用户认得的片库分类写（热播漫剧…），
 * 榜单页里写的是接口那套榜名（HGTV漫剧热播榜…）。
 */
internal fun MainActivity.rankShelf(board: RankBoard): HomeScreen.Shelf? {
    val page=homeFeed[board]
    val items=page?.items.orEmpty().filterNot { it.id in library.hidden() }
    if(items.isEmpty()) return null
    val entries=items.take(SHELF_SIZE).map { HomeScreen.Entry(it,"",time=it.badge) }
    // 行末「查看全部」直接进这一栏对应的榜单页——榜单与它是同一份数据，不是另做一套列表；
    // 行末右键也是同一个动作（与 [localShelf] 那三栏一致）。不足一屏时不给这个入口。
    val more: (() -> Unit)?=if(entries.size>=SHELF_SIZE) ({ switchRankBoard(board,fromHome=true) }) else null
    return HomeScreen.Shelf("rank-${board.storedValue}",board.homeTitle,entries,more)
}
internal fun MainActivity.refreshLibraryCards(changedId: String,removed: Boolean=false) {
    if(screen!="catalog") return
    val home=homeScreen
    if(home!=null) {
        // 先试**就地刷新**：长按菜单里加 / 取消收藏、移出稍后观看只改状态行与那一栏的条目，
        // 走 [HomeScreen.refreshEntries] 一个视图都不用重建，观感上不会有任何跳动。
        // 走 [HomeScreen.updateShelves] 是整块重建（`contents.removeAllViews()` + 重新量海报高度），
        // 观感就是用户说的「画面闪缩一下」。
        val shelves=homeShelves()
        // 结构没变（每一栏的 key 与卡片 key 都在）才就地刷新；多了 / 少了条目就得重建。
        val structural=shelves.flatMap { shelf -> shelf.entries.map { "${shelf.key}:${it.series.id}" } }.toSet()
        val existing=home.cardKeys()
        if(structural==existing) { home.refreshEntries(shelves); return }
        // 结构变了才重建，而且**不抢焦点**（见类头的焦点原则）。
        home.updateShelves(shelves)
        return
    }
    // 首页剧架与排行榜页都直接按「本机隐藏」过滤，所以在这两处隐藏一部剧要就地重画列表
    // （本机记录页里的条目本身就不是热播内容，隐藏那一项在那里不出现）。
    if(removed && (localCatalog() || tab==TAB_HOME || tab==TAB_RANK)) {
        val oldIds=gridItems.map { it.id }; val index=oldIds.indexOf(changedId).coerceAtLeast(0)
        if(localCatalog()) {
            // 集合页是一次全量铺出来的长列表：重取一次即可，没有页码。
            catalog=localItems(); page=1; hasMore=false
        }
        val remaining=if(localCatalog()) catalog else catalog.filterNot { it.id in library.hidden() }
        catalogFocus=remaining.getOrNull(index.coerceAtMost((remaining.size-1).coerceAtLeast(0)))?.id.orEmpty()
        rememberScroll(catalogPageKey,catalogScroll?.scrollY ?: 0)
        artwork.cancelPage()
        catalogBody?.let { body -> body.removeAllViews(); seriesList(body,false) }
    } else {
        // 只是收藏标签变了：重绑当前窗口那几行就够了（代价与已加载数量无关）。
        rebindGridRows()
    }
}
/**
 * 剧卡菜单（长按确认 / 遥控器菜单键）。
 *
 * 有哪些项、什么顺序、文案怎么随状态变，都由 `core` 的 [CardMenu.items] 算出来——
 * 它不依赖 Android，因此「历史上下文里必须有删除记录、本机记录页不出现『不在热播榜中显示』」
 * 这几条规则被单元测试锁住，中间插一项也不会让后面的分支错位。
 *
 * [shelf] 是卡片所在剧架的 key：首页「历史记录」剧架（`shelf=="history"`）与历史集合页
 * （`collection=="history"`）是同一个上下文，两条路径都能就地删掉这一条本机观看记录。
 *
 * 派发按语义动作走（[CardAction]），不按下标——文案随状态变（收藏 / 取消收藏），
 * 动作不变，因此「显示什么」与「执行什么」不会再错位。
 */
/**
 * 这部剧是不是「已收藏」——本机与账号侧任一说有就算。
 *
 * 两个来源都必须问：「我的 → 收藏」那一栏读的是**账号侧**（[accountData]），
 * 而本机收藏是另一份（[Library.favorites]）。导入账号之后两边可能只有一边有，
 * 只看本机就会出现「已经收藏了、菜单却让再收藏一次」。
 */
internal fun MainActivity.isFavorite(id: String): Boolean =
    library.favorite(id) || accountData?.favorites?.any { it.series.id==id }==true

internal fun MainActivity.quickActions(series: Series,anchor: View,shelf: String?=null) {
    // 三个地方都是「观看记录」这个上下文，菜单里都该有「删除记录」：
    // 首页的历史剧架（`shelf=="history"`）、历史集合页（`collection=="history"`）、
    // 以及「我的 → 历史」（`tab==TAB_PROFILE && accountTab=="history"`）。
    // 少了最后那一处，用户只能从首页或集合页删，「我的」页那张卡长按出来没有这一项。
    val inHistory=shelf=="history" || collection=="history" ||
        (tab==TAB_PROFILE && accountTab=="history")
    val items=CardMenu.items(
        hasProgress=library.progress(series.id)!=null,
        // 「是不是已收藏」要问**两个来源**：本机那份，以及账号侧那份。
        // 只查本机的话，「我的 → 收藏」里那些**只存在于账号**的剧会被判成「没收藏」，
        // 菜单上于是出现「收藏这部剧」——点下去本机加一条、云端还是原样，
        // 用户看到的就是「明明已经收藏了还让我收藏」。
        favorite=isFavorite(series.id),
        queued=library.queued(series.id),
        inHistory=inHistory,
        // 「不在热播榜中显示」只对**热播榜**有意义：首页四个热播剧架与排行榜页保留，
        // 本机记录页（收藏 / 历史 / 稍后观看）与详情页的相关剧集都不出现这一项。
        hotList=screen=="catalog" && collection==null && (tab==TAB_RANK || (tab==TAB_HOME && shelf?.startsWith("rank-")==true)))
    // 每项一枚线性图标（顺序与 [CardMenu.items] 一一对应；按动作取，不按下标，
    // 中间插一项也不会整体错位）。「删除记录」「不在热播榜中显示」用危险色。
    val icons = items.map { item ->
        when (item.action) {
            CardAction.RESUME -> R.drawable.ic_menu_play
            CardAction.FAVORITE -> R.drawable.ic_menu_star
            CardAction.LATER -> R.drawable.ic_menu_clock
            CardAction.DETAIL -> R.drawable.ic_menu_info
            CardAction.DELETE_HISTORY -> R.drawable.ic_menu_trash
            CardAction.HIDE_FROM_HOT -> R.drawable.ic_menu_hide
        }
    }
    val danger = items.mapIndexedNotNull { index, item ->
        if (item.action == CardAction.DELETE_HISTORY || item.action == CardAction.HIDE_FROM_HOT) index else null
    }.toSet()
    // 顶部信息区：封面 + 剧名 + 「第N季 · 共N集」（没有的项自动省掉）。
    // 季号走 [SeriesSeasons.badge]：与卡片右上角同一处口径，不在这里另算一遍。
    val season = SeriesSeasons.badge(series)
    val count = series.episodeCount.takeIf { it > 0 }?.let { "共 $it 集" }
    val subtitle = listOfNotNull(season, count).joinToString(" · ")
    tvTools.choose(
        series.title, items.map { it.label }, anchor,
        { index ->
        when(items[index].action) {
            CardAction.RESUME -> openDetail(series,true)
            CardAction.FAVORITE -> {
                // 菜单是按 [isFavorite] 出的文案（本机 + 账号侧任一说已收藏），
                // 因此这里也必须按同一个判断决定「加」还是「删」：
                // 只看本机的 [Library.toggle] 会把「只在账号侧收藏」的剧再加一条到本机，
                // 与菜单上那句「取消收藏」正好相反。
                val removing=isFavorite(series.id)
                if(removing) {
                    // 本机有就删本机；只在账号侧有就只改账号侧缓存。
                    if(library.favorite(series.id)) library.toggle(series)
                    syncFavorite(series,false)
                } else {
                    library.toggle(series)
                    syncFavorite(series,true)
                    favoriteMonitor.check()
                }
                refreshLibraryCards(series.id,removing && collection=="favorites")
            }
            CardAction.LATER -> {
                val added=library.toggleLater(series)
                refreshLibraryCards(series.id,!added && collection=="later")
            }
            CardAction.DETAIL -> openDetail(series)
            CardAction.DELETE_HISTORY -> deleteHistoryRecord(series,anchor)
            CardAction.HIDE_FROM_HOT -> {
                library.hide(series.id)
                refreshLibraryCards(series.id,true)
            }
        }
        },
        subtitle = subtitle,
        cover = series.cover,
        icons = icons,
        danger = danger,
        loadCover = { view, url -> loadCover(view, url) }
    )
}
internal fun MainActivity.refreshFavoriteLabels() {
    if(screen!="catalog" || !library.isLoaded) return
    rebindGridRows()
    favoriteStatus?.text=favoriteMonitor.status
    val home=homeScreen
    if(screen=="catalog" && tab==TAB_HOME && page==1 && collection==null && home!=null && library.updatedFavorites()!=homeUpdateCount) {
        // 首页没有底部按钮了，收藏更新直接就地重建剧架：按卡片 key 恢复选择，
        // 焦点不在剧架上时（比如停在顶部图标上）不抢焦点。
        homeUpdateCount=library.updatedFavorites()
        // 收藏更新只改状态行（「更新至 N 集」），结构不变——因此**就地刷新**，
        // 与 [refreshLibraryCards] 同一套做法：不重建剧架，就不会有「画面闪缩一下」。
        val shelves=homeShelves()
        if(shelves.flatMap { shelf -> shelf.entries.map { "${shelf.key}:${it.series.id}" } }.toSet()==home.cardKeys())
            home.refreshEntries(shelves)
        else home.updateShelves(shelves)
    }
}
/** 窗口底色跟着主题走：启动那一帧、页面之间的空档不会闪出另一套材质。 */
