// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import com.hgtv.star.shared.ui.UiText
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

// 本文件是从 TvMainActivity.kt 拆出来的「收藏 / 进度的账号侧同步（用户动作直写远端，远端结果落本机）」这一块。
// 这些成员仍然属于 TvMainActivity，只是以扩展成员（TvMainActivity.xxx）的形式放在这里，
// 逻辑与拆之前逐行一致；改这里等于改 Activity 本体。

/**
 * 远端历史整表覆盖本机缓存之后，重画**读本机进度**的那几处。
 *
 * 首页历史栏与历史集合页读的都是 `library.history()`；远端同步完成后这里立即重画，
 * 用户不必先退出再进来才看到账号里的最新记录。
 * 「我的 → 历史」读的是账号那份，由 [refreshAccountViews] 自己管，这里不管。
 */
fun TvMainActivity.refreshLocalHistoryViews() {
    if(screen!="catalog") return
    when {
        // 历史集合页：本机缓存已按远端整表覆盖，整页重铺一次。
        collection=="history" -> { catalogFocus=""; showCatalog() }
        // 首页：只有历史栏读本机进度，重建剧架（结构没变时就地刷新，不会闪）。
        tab==TAB_HOME -> refreshLibraryCards("",true)
    }
}

fun TvMainActivity.syncFavorite(series: Series,added: Boolean) {
    accountController.favorite(
        series = series,
        added = added,
        onNeedSignIn = { tvTools.toast(SIGN_IN_TOAST) },
        onSyncError = {
            tvTools.toast(if(added) UiText.get(com.hgtv.star.shared.R.string.ui_0118) else UiText.get(com.hgtv.star.shared.R.string.ui_0048))
        },
    )
    refreshAccountListIfVisible()
}
/** 上传一条观看进度；[episodeCount] 只用于云端「全 N 集」的展示。 */
fun TvMainActivity.syncProgress(progress: WatchProgress,episodeCount: Int=0) {
    accountController.progress(progress,episodeCount)
    refreshAccountListIfVisible()
}
/** 删除云端观看历史（`read_history/update` 软删）。 */
fun TvMainActivity.syncHistoryDelete(series: Series) {
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
fun TvMainActivity.storeAccount(data: AccountData?) {
    accountController.store(data)
}

/**
 * 本机改动就地写回账号侧缓存：收藏加 / 删。
 *
 * 加进来的那条用本机的 [Series]（封面、简介都齐），因此「我的」页与首页不必再联网补元数据。
 * 已经在列表里的剧不重复加，只是保持原样——云端本来就是幂等的。
 */
fun TvMainActivity.applyAccountFavorite(series: Series,added: Boolean) {
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
fun TvMainActivity.applyAccountReserve(series: Series, reserved: Boolean) {
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
fun TvMainActivity.applyAccountHistory(progress: WatchProgress,episodeCount: Int) {
    val data=accountData ?: return
    // 集内位置要一起带上来：少了它，「我的 → 历史」那张卡只有「第 12 集」，
    // 与本机首页那一行（「第 12 集 · 03:21」）对不上。
    val row=AccountHistory(progress.series,progress.episodeIndex,progress.episodeId,progress.updatedAt,
        episodeCount,progress.position)
    storeAccount(data.copy(history=listOf(row)+data.history.filterNot { it.series.id==progress.series.id }))
    refreshAccountListIfVisible()
}
/** 本机改动就地写回账号侧缓存：删除一条观看历史。 */
fun TvMainActivity.applyAccountHistoryDelete(series: Series) {
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
fun TvMainActivity.refreshAccountListIfVisible() {
    if(screen!="catalog" || tab!=TAB_PROFILE) return
    val index=gridItems.map { it.id }.indexOf(catalogFocus).coerceAtLeast(0)
    catalog=accountItems(accountTab); page=1
    hasMore=accountHistoryPaging() && accountHistoryHasMore
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
fun TvMainActivity.historyHasRecords(): Boolean {
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
fun TvMainActivity.historyClearButton(parent: LinearLayout,size: ButtonSize=ButtonSize.NORMAL,gap: Int=TvStyle.GAP_BLOCK): TvButton? {
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
 * 为什么要有它：逐条删只能删到眼前看得见的那几条，
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
 * 云端失败不回滚本机；下次成功刷新仍以远端结果为准。失败时给一句提示，
 * 由用户决定要不要再试。
 */
fun TvMainActivity.clearAllHistory(anchor: View?) {
    if(!accountController.hasHistoryRecords()) {
        tvTools.info("清空观看记录","本机与账号里都没有可清空的观看记录。",anchor)
        return
    }
    // 本机那一半是瞬时的，云端要「拉全量 id → 删 → 回读校验」，往返好几秒。
    // 这段等待必须有反馈：屏幕正中的加载框（浮在整页之上，不占布局位置），云端回来再换成结果。
    // 中途会重建整页，那一层由 `base()` 里的 `reattachCenterLoading()` 补挂回去。
    showCenterLoading("正在清空观看记录…")
    var cleared=0
    val result=accountController.clearAllHistory(onRemoteResult=remote@{ ok ->
        if(isDestroyed) return@remote
        // 成功不报「已清空 N 条」：列表自己会空掉，那就是最清楚的反馈。
        // 失败仍然要说——云端没删干净，用户需要知道要不要再试。
        if(!ok) tvTools.toast(UiText.get(com.hgtv.star.shared.R.string.ui_0138))
        hideCenterLoading()
    })
    cleared=(result.localIds+result.knownIds).distinct().size
    // 详情页的「继续观看」也要跟着失效：缓存里那几条已经清了，重进详情不该再从旧位置接。
    catalogFocus=""
    when {
        collection=="history" -> showCatalog()
        tab==TAB_PROFILE && accountTab=="history" -> refreshAccountListIfVisible()
        tab==TAB_HOME -> refreshLibraryCards("",true)
        else -> if(screen=="catalog") showCatalog()
    }
    // 没有云端那一步（未登录 / 设备未注册）：不会有人回调，当场收掉等待态。
    if(!result.remoteRequested) hideCenterLoading()
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
fun TvMainActivity.deleteHistoryRecord(series: Series,anchor: View?) {
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
