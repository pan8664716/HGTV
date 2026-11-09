// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.app.Activity
import android.view.*
import android.widget.*
import com.hgtv.star.core.*
import com.hgtv.star.tv.list.seriesList
import com.hgtv.star.tv.ButtonSize
import com.hgtv.star.tv.ButtonTone
import com.hgtv.star.tv.TvButton
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.tv.list.showCatalog
import com.hgtv.star.shared.data.WatchProgress
import com.hgtv.star.tv.R

// 本文件是从 MainActivity.kt 拆出来的「收藏 / 进度的账号侧同步（本机改动推到账号，账号数据落到本机）」这一块。
// 这些成员仍然属于 MainActivity，只是以扩展成员（MainActivity.xxx）的形式放在这里，
// 逻辑与拆之前逐行一致；改这里等于改 Activity 本体。

/**
 * [MainActivity.historyPushedAt] 的条目上限。
 *
 * 那张表只记「这份进度已经推回云端」，不是业务数据：超过上限整表清掉即可，
 * 最坏的结果是同一份进度多推一次（平台侧本来就是幂等覆盖）。
 */
private const val HISTORY_PUSHED_LIMIT=300

/**
 * 本机观看进度被账号那一份改写之后，重画**读本机进度**的那几处。
 *
 * 首页历史栏与历史集合页读的都是 `library.history()`，账号更超前时那份数据刚被
 * [reconcileHistory] 就地改写；不重画的话用户得先退出再进来才看得到新集数——
 * 而那正是「首页还是显示 200 集」这个反馈的另一半。
 * 「我的 → 历史」读的是账号那份，由 [refreshAccountViews] 自己管，这里不管。
 */
fun MainActivity.refreshLocalHistoryViews() {
    if(screen!="catalog") return
    when {
        // 历史集合页：内容就是本机 + 账号合并出来的，整页重铺一次。
        collection=="history" -> { catalogFocus=""; showCatalog() }
        // 首页：只有历史栏读本机进度，重建剧架（结构没变时就地刷新，不会闪）。
        tab==TAB_HOME -> refreshLibraryCards("",true)
    }
}

fun MainActivity.syncFavorite(series: Series,added: Boolean) {
    accountController.favorite(
        series = series,
        added = added,
        onNeedSignIn = { tvTools.toast(SIGN_IN_TOAST) },
        onSyncError = {
            tvTools.toast(if(added) "收藏同步失败，稍后再试" else "取消收藏同步失败，稍后再试")
        },
    )
    refreshAccountListIfVisible()
}
/** 上传一条观看进度；[episodeCount] 只用于云端「全 N 集」的展示。 */
fun MainActivity.syncProgress(progress: WatchProgress,episodeCount: Int=0) {
    accountController.progress(progress,episodeCount)
    refreshAccountListIfVisible()
}
/** 删除云端观看历史（`read_history/update` 软删）。 */
fun MainActivity.syncHistoryDelete(series: Series) {
    accountController.historyDeleted(series)
    refreshAccountListIfVisible()
}
/**
 * 账号数据的内存那一份换掉，**同时落盘**。
 *
 * 本机改动（收藏 / 取消收藏、进度上传、删除历史）会就地改 [accountData]，
 * 这些改动也是「账号侧的真实状态」，因此要和刷新结果一样进缓存——
 * 否则重启之后先铺出来的是改动之前的那一份，用户会看到刚取消的收藏又回来了。
 *
 * 传 null 表示「没有账号数据可改」（未登录或还没拉到），此时什么都不做。
 */
fun MainActivity.storeAccount(data: AccountData?) {
    accountController.store(data)
}

/** 一次历史对账的结果：账号侧那份（[data]）与本机更超前、需要推回云端的那些（[uploads]）。 */
class HistoryMerge(var data: AccountData,val uploads: List<WatchProgress>) {
    /** 本机进度被账号那一份改写过多少条（界面据此重建首页历史栏）。 */
    var localWrites=0
        set
}

/**
 * 本机进度与账号历史**对账**：同一部剧只留更超前的那一份，并且两边都写成它。
 *
 * ## 为什么必须在这里做
 *
 * 历史有两个来源：本机进度（首页历史栏、详情页续播读它）与账号历史（「我的 → 历史」、
 * 历史集合页读它）。原先两边各写各的：机器 1 看到第 200 集、机器 2 看到第 400 集，
 * 回到机器 1 首页还是 200，进「我的」有时 400 有时 200——取决于账号侧这一栏有没有刷新回来。
 * 用户读到的就是「两个历史在打架」。
 *
 * ## 规则
 *
 * 逐条比「谁更超前」（[WatchSync.ahead]，时间差有死区、集号更大优先）：
 * * 账号更超前 → 用账号那一份改写本机进度（[WatchSync.toLocal]），首页与续播立刻跟上；
 * * 本机更超前 → 用本机那一份改写账号那一行（[WatchSync.toAccount]），「我的 → 历史」不再显示旧集数；
 *   同时把这条本机进度交给调用方推回云端（[HistoryMerge.uploads]），第三台设备不会又看到旧的。
 *
 * 本机删过的剧（[Library.historyDeleted]）在调用前已经滤掉，这里不再处理；
 * 本机没有进度的剧原样保留账号那一份（这台设备从没看过，没有可比的）。
 */
fun MainActivity.reconcileHistory(data: AccountData): HistoryMerge {
    // 计算整段在 `:core` 的 `HistoryReconcile`（手机端用的是同一份）：
    // 「谁更超前、哪几条要写回本机、哪几条要推回云端」这条判断只写一次。
    val outcome=com.hgtv.star.core.HistoryReconcile.run(
        data,
        library.history(),
        System.currentTimeMillis()
    )
    val result=HistoryMerge(outcome.data,outcome.uploads)
    outcome.writes.forEach { pulled ->
        library.save(pulled); result.localWrites++
    }
    return result
}

/**
 * 把「本机更超前」的那几条推回云端。
 *
 * 只在**本机确实更超前**时调用（见 [reconcileHistory] 的 uploads）。这里按剧去重、
 * 同一个时间戳只推一次：账号数据每次进「我的」页都会刷新一遍，不记一下就会反复上传
 * 同一份进度（平台那边每次都是一次写请求，还会改写 `last_watch_time`）。
 */
fun MainActivity.pushHistoryAhead(rows: List<WatchProgress>) {
    if(rows.isEmpty()) return
    rows.forEach { progress ->
        val key=progress.series.id
        if(historyPushedAt[key]==progress.updatedAt) return@forEach
        if(historyPushedAt.size>=HISTORY_PUSHED_LIMIT) historyPushedAt.clear()
        historyPushedAt[key]=progress.updatedAt
        val count=progress.series.episodeCount
        io.submit {
            val ok=repository.accountUploadProgress(key,progress.episodeId,progress.episodeIndex,
                progress.position,progress.duration,count)
            if(ok) return@submit
            // 失败就忘掉这次去重标记：下次刷新还有机会补上。
            main.post { if(!isDestroyed && historyPushedAt[key]==progress.updatedAt) historyPushedAt.remove(key) }
        }
    }
}
/**
 * 本机改动就地写回账号侧缓存：收藏加 / 删。
 *
 * 加进来的那条用本机的 [Series]（封面、简介都齐），因此「我的」页与首页不必再联网补元数据。
 * 已经在列表里的剧不重复加，只是保持原样——云端本来就是幂等的。
 */
fun MainActivity.applyAccountFavorite(series: Series,added: Boolean) {
    val data=accountData ?: return
    val rows=data.favorites
    val next=if(added) {
        if(rows.any { it.series.id==series.id }) rows
        else listOf(AccountFavorite(series,System.currentTimeMillis(),0))+rows
    } else rows.filterNot { it.series.id==series.id }
    if(next===rows) return
    storeAccount(data.copy(favorites=next))
    refreshAccountListIfVisible()
}
/**
 * 本机改动就地写回账号侧缓存：预约 / 取消预约。
 *
 * 「我的 → 预约」读的是 [accountData] 的 `reserves`，只改本机那张表的话，
 * 切回「我的」页会看到刚预约的剧不在里面（或取消掉的还在）。
 */
fun MainActivity.applyAccountReserve(series: Series, reserved: Boolean) {
    val data=accountData ?: return
    val rows=data.reserves
    val next=if(reserved) {
        if(rows.any { it.series.id==series.id }) rows
        else listOf(AccountReserve(series.copy(upcoming=true),System.currentTimeMillis(),false))+rows
    } else rows.filterNot { it.series.id==series.id }
    if(next.size==rows.size && next.indices.all { next[it]===rows[it] }) return
    storeAccount(data.copy(reserves=next))
    refreshAccountListIfVisible()
}
/** 本机改动就地写回账号侧缓存：观看进度新增 / 更新（同一部剧只留最新一条，排在最前）。 */
fun MainActivity.applyAccountHistory(progress: WatchProgress,episodeCount: Int) {
    val data=accountData ?: return
    // 集内位置要一起带上来：少了它，「我的 → 历史」那张卡只有「第 12 集」，
    // 与本机首页那一行（「第 12 集 · 03:21」）对不上；对账时还会被当成「两边不一致」。
    val row=AccountHistory(progress.series,progress.episodeIndex,progress.episodeId,progress.updatedAt,
        episodeCount,progress.position)
    storeAccount(data.copy(history=listOf(row)+data.history.filterNot { it.series.id==progress.series.id }))
    refreshAccountListIfVisible()
}
/** 本机改动就地写回账号侧缓存：删除一条观看历史。 */
fun MainActivity.applyAccountHistoryDelete(series: Series) {
    val data=accountData ?: return
    val next=data.history.filterNot { it.series.id==series.id }
    if(next.size==data.history.size) return
    storeAccount(data.copy(history=next))
    refreshAccountListIfVisible()
}
/**
 * 「我的」页正显示着的时候，账号侧列表被本机改动改过，就地重建那一页。
 *
 * 与 [refreshLibraryCards] 的集合页分支同一套做法：全量重铺、尽量把焦点留在原位
 * （删掉的正是当前那张卡时才退到同一下标的下一条）。首页与集合页不用管——
 * 它们下次重建时会自己读新的 [accountData]。
 */
fun MainActivity.refreshAccountListIfVisible() {
    if(screen!="catalog" || tab!=TAB_PROFILE) return
    val index=gridItems.map { it.id }.indexOf(catalogFocus).coerceAtLeast(0)
    catalog=accountItems(accountTab); page=1; hasMore=false
    catalogFocus=catalog.getOrNull(index.coerceAtMost((catalog.size-1).coerceAtLeast(0)))?.id.orEmpty()
    rememberScroll(catalogPageKey,catalogScroll?.scrollY ?: 0)
    artwork.cancelPage()
    // cancelPage() 把**所有**绑定过的 ImageView 都清空了，包括不在内容区里的头像
    // （见 [profileAvatar]）。这一次只重建内容区，头像没人重新加载就会变空白，
    // 因此这里补一次：把账号头像重新挂上。
    profileAvatar?.let { avatar ->
        accountData?.profile?.avatar?.takeIf { it.isNotBlank() }?.let { loadCover(avatar,it) }
    }
    catalogBody?.let { body -> body.removeAllViews(); seriesList(body,false) }
}
/**
 * 现在还有没有可清空的观看记录（本机进度、账号侧内存那份、或磁盘快照里未删过的条目）。
 *
 * 磁盘快照也算一份：进程刚起来、[accountData] 还没拉回来时，那一栏铺的就是快照，
 * 此时历史页上明明有卡片，按钮却不出现（或反过来）都是错的。
 */
fun MainActivity.historyHasRecords(): Boolean {
    return accountController.hasHistoryRecords()
}

/**
 * 「清空全部记录」按钮：历史集合页放在顶部导航栏里，「我的 → 历史」放在身份行里（退出 / 刷新之后）。
 *
 * 两处共用这一份：文案、危险色、图标、二次确认的话术因此永远一致。
 * 没有可清空的记录时返回 null（调用方据此不摆这一枚）。
 *
 * 二次确认弹窗的锚点用**按钮自己**：清空之后页面会重建，锚点必须是新页面里的控件——
 * 这里传进去的是旧按钮，重建后它已经离树，关弹窗时 `requestFocus` 会落空（不报错，只是焦点没了）。
 * 因此调用方在重建后由 `historyClear` 接住焦点（见 `SeriesGrid.wireGridFocus`）。
 */
fun MainActivity.historyClearButton(parent: LinearLayout,size: ButtonSize=ButtonSize.NORMAL,gap: Int=TvStyle.GAP_BLOCK): TvButton? {
    if(!historyHasRecords()) return null
    lateinit var button: TvButton
    button=addButton(parent,"清空全部记录",tone=ButtonTone.DANGER,size=size,gap=gap,
        icon=R.drawable.ic_menu_trash) {
        tvTools.confirm("清空全部观看记录",
            "本机与账号里的观看记录都会被删除，收藏与稍后观看不受影响。",button,danger=true) {
            clearAllHistory(button)
        }
    }.apply { minimumWidth=dp(if(size==ButtonSize.COMPACT) 150 else 180) }
    historyClear=button
    return button
}

/**
 * 彻底清空观看记录：本机、账号缓存、云端三处一起清。
 *
 * 为什么要有它：历史那一栏是「本机进度 + 账号侧历史」合并出来的，逐条删只能删到眼前看得见的那几条，
 * 手机上看过、这台设备上没看过的那些**只能从云端删**；一条条长按删掉几十条不是人干的事。
 *
 * 三处都要动，少一处就是「清空之后过一会儿又冒出来」：
 * 1. 本机 [Library.clearHistory]：清进度、清 watched，并留下墓碑；
 * 2. 账号缓存：内存里的 [accountData] 与磁盘快照里的那一栏都清空，
 *    并把它们的 id 全部记进墓碑（本机没有进度的那一批只能这样挡住）；
 * 3. 云端：[ContentRepository.accountClearHistory] **先拉云端全量 id 再删**，
 *    删完回读校验，还有余量就重试。
 *
 * 关键点：云端那一步**不再只删本机记得的 id**。账号侧缓存只覆盖这台设备上一次拉到的那一批，
 * 手机上有、这里没拉到的记录从来没进过 [accountData]，只删本机那几条，云端会剩下一大截。
 *
 * 云端失败不回滚本机：本机已经清干净、墓碑也已经落盘，下次刷新不会把它们合并回来；
 * 只是手机上还能看到，因此失败时给一句提示，由用户决定要不要再试。
 */
fun MainActivity.clearAllHistory(anchor: View?) {
    if(!accountController.hasHistoryRecords()) {
        tvTools.info("清空观看记录","本机与账号里都没有可清空的观看记录。",anchor)
        return
    }
    val result=accountController.clearAllHistory { ok ->
        if(!ok && !isDestroyed) tvTools.toast("本机已清空；云端还有记录没删掉，请稍后重试")
    }
    // 详情页的「继续观看」也要跟着失效：缓存里那几条已经清了，重进详情不该再从旧位置接。
    catalogFocus=""
    when {
        collection=="history" -> showCatalog()
        tab==TAB_PROFILE && accountTab=="history" -> refreshAccountListIfVisible()
        tab==TAB_HOME -> refreshLibraryCards("",true)
        else -> if(screen=="catalog") showCatalog()
    }
    val ids=(result.localIds+result.knownIds).distinct()
    showHint("已清空 ${ids.size} 条观看记录")
}

/**
 * 删除一条本机观看记录：选中即删，不再二次确认。
 *
 * 菜单里这一项本身就是明确的删除意图（要先长按 / 按菜单键、再把焦点移到「删除记录」、
 * 最后按确认），再叠一层弹窗只是把遥控器上的操作变成三次。首页历史剧架的「删除记录」
 * 首页历史剧架与历史集合页的「删除记录」共用这一份实现，两条路径行为一致。
 *
 * 删完就地刷新所在页面——首页重建剧架（历史栏少一部，空了整栏消失并保留相邻卡片的焦点），
 * 集合页重新按本机列表取页。只删观看进度：收藏、稍后观看、已隐藏的剧集都不动。
 */
fun MainActivity.deleteHistoryRecord(series: Series,anchor: View?) {
    library.removeHistory(series.id)
    // [syncHistoryDelete] 里的 [applyAccountHistoryDelete] 会把这一条从 [accountData] 摘掉，
    // 「我的 → 历史」那一栏读的就是它——因此那一页由 [refreshAccountListIfVisible] 自己重建，
    // 这里不要再走 [refreshLibraryCards]（那是首页剧架 / 集合页的路径）。
    syncHistoryDelete(series)
    when {
        collection=="history" -> { catalogFocus=""; showCatalog() }
        tab==TAB_PROFILE -> refreshAccountListIfVisible()
        else -> refreshLibraryCards(series.id,true)
    }
}
