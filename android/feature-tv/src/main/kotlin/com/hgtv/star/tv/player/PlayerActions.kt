// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import com.hgtv.star.shared.ui.UiText
import android.text.TextUtils
import android.view.View
import com.hgtv.star.core.*
import com.hgtv.star.tv.list.HomeScreen
import com.hgtv.star.tv.player.RelatedPanel
import com.hgtv.star.tv.R

// 播放设置面板**第一行那排动作**（相关剧集 / 作者作品 / 加入收藏 / 查看简介）的实现。
// 按钮本身在 `player/PlaybackMenuController.kt` 的 `buildPlaybackMenu` 里挂上去，这里只做「按下去之后」。
//
// 前两项与简介页的「相关剧集 / 作者作品」**共用同一份数据与同一份铺法**：
// 数据是 `prefetchRelated` / `prefetchAuthorWorks` 已经取好的那一份（同一部剧不会拉两次），
// 卡片行是 `buildRelatedRow` / 同一个 [HomeScreen]。区别只在落点——播放页里是一块贴底浮层
// （[RelatedPanel]：不压暗画面，上方那一帧照常亮着），点卡片 = 离开播放页进详情。

/** 播放设置里的「相关剧集」：底部弹出一栏相关作品卡片。 */
fun TvMainActivity.showPlayerRelated() = showPlayerWorksStrip(author = false)

/** 播放设置里的「作者作品」：底部弹出一栏该作者名下的全部作品。 */
fun TvMainActivity.showPlayerAuthorWorks() = showPlayerWorksStrip(author = true)

/**
 * 播放页底部的作品浮层（相关剧集 / 作者作品共用）。
 *
 * 结构只有三层：**一行状态 + 一栏卡片**。状态那一行永远存在（右边是「关闭」），
 * 因此数据还在路上时焦点也有落点，不会出现「浮层开着、按什么都没反应」；
 * 卡片到位之后焦点自动落到第一张，按上回到「关闭」。
 *
 * [author] 为真时走作者作品（数据来自按作者名的全站搜索，比相关剧集慢，因此**分两段交付**：
 * 先铺搜到的那一批，补季完成后再就地换一次），为假时走相关剧集。
 */
private fun TvMainActivity.showPlayerWorksStrip(author: Boolean) {
    // 推荐页（触屏）跑的是同一套播放内核，播放设置也开得出来，因此这里放宽到「在播放页」：
    // 那一页同样有 `detail`，浮层照常铺；只是关掉之后不再亮控制条（那一页没有这条控制条）。
    if(!onPlaybackPage() || finishedPanel!=null) return
    val data=detail ?: return
    val series=data.series
    val name=series.author.trim()
    // 取不到作者就不弹这一层：按空名字去搜只会搜出一批无关作品（与简介页不摆这枚按钮同一条理由）。
    if(author && name.isEmpty()) { tvTools.toast(UiText.get(com.hgtv.star.shared.R.string.ui_0244)); return }
    // 播放设置先收起：浮层要占住下半屏，两块叠在一起会互相压字（与选集面板同一套收尾）。
    dismissPlaybackMenu()
    val body=column().apply {
        // 主题浮层背板：与播放设置 / 选集面板同一块材质（见 [TvStyle.sheet]）。
        background=TvStyle.sheet(this@showPlayerWorksStrip)
        clipChildren=false
        clipToPadding=false
        setPadding(pageInset(),dp(18),pageInset(),dp(18))
    }
    val head=row()
    val status=text("",15f,TvStyle.muted).apply {
        maxLines=1
        ellipsize=TextUtils.TruncateAt.END
    }
    head.addView(status,lp(0,-2).apply { weight=1f })
    // 「重试」只在失败时出现：状态行里塞一枚可重试的入口，比让用户退出再进来强。
    val retry=addButton(head,"重试",size=ButtonSize.COMPACT) { refreshStripData(series,author) }
    retry.visibility=View.GONE
    // 关闭按钮是这一层**恒在的落点**：卡片没到位时焦点停在这里，按上下都不出浮层。
    val close=addButton(head,"关闭",size=ButtonSize.COMPACT,icon=R.drawable.ic_menu_close) { finishedPanel?.dismiss() }
    body.addView(head,lp(-1,-2))
    val slot=column().apply { clipChildren=false; clipToPadding=false }
    body.addView(slot,lp(-1,-2))

    /** 铺一栏卡片：点卡片离开播放页进详情（与「换一部接着看」那条路同一套收尾）。 */
    fun showCards(shelf: HomeScreen.Shelf) {
        val view=HomeScreen(this,close,pageInset(),::loadCover,
            { item,_ ->
                finishedPanel?.dismiss()
                // 离开播放页要走 [detachPlayerSurface]（画面、传输、弹幕层一起收，实例保留），
                // 否则新页面盖上来时旧解码器还占着（与返回键那条路同一套做法）。
                detachPlayerSurface()
                openDetail(item)
            },
            { item,anchor,shelfView -> quickActions(item,anchor,shelfView) },{},
            columns=library.homeColumns)
        view.renderCompact(listOf(shelf),"",false)
        slot.addView(view,lp(-1,-2))
        view.post { view.firstCard()?.requestFocus() }
    }

    fun render() {
        slot.removeAllViews()
        retry.visibility=View.GONE
        if(author) {
            val works=authorWorks.takeIf { authorFor==name }
            // 没为这个名字发过请求就不算「加载中」：否则这一栏永远停在「正在查找…」。
            val loading=authorLoading || (works==null && !authorFailed && name.isNotEmpty() && authorFor==name)
            when {
                name.isEmpty() -> {
                    status.text=UiText.get(com.hgtv.star.shared.R.string.ui_0165)
                    close.requestFocus()
                }
                loading -> {
                    status.text=UiText.get(com.hgtv.star.shared.R.string.ui_0150, name)
                    close.requestFocus()
                }
                works==null && authorFailed -> {
                    status.text=UiText.get(com.hgtv.star.shared.R.string.ui_0164, name)
                    retry.visibility=View.VISIBLE
                    close.requestFocus()
                }
                else -> {
                    // 「正在看的这一部」提到第一张（它的其它季紧跟其后），其余顺序原样不动——
                    // 顺序由服务端给（见 `ContentRepository.authorWorks`），界面不再自己排一遍。
                    val items=listOf(series)+works.orEmpty().filter { it.id!=series.id }
                    status.text=if(items.size<=1) UiText.get(com.hgtv.star.shared.R.string.ui_0009, name) else UiText.get(com.hgtv.star.shared.R.string.ui_0008, name, items.size)
                    if(items.size<=1) close.requestFocus()
                    else showCards(HomeScreen.Shelf("author","全部作品",
                        items.map { item -> HomeScreen.Entry(item,if(item.upcoming) item.notice else "") }))
                }
            }
            return
        }
        val works=relatedWorks.takeIf { relatedFor==series.id }
        val loading=relatedLoading || (works==null && !relatedFailed)
        when {
            loading -> {
                status.text=UiText.get(com.hgtv.star.shared.R.string.ui_0151)
                close.requestFocus()
            }
            works==null && relatedFailed -> {
                status.text=UiText.get(com.hgtv.star.shared.R.string.ui_0192)
                retry.visibility=View.VISIBLE
                close.requestFocus()
            }
            works==null || works.items.isEmpty() -> {
                status.text=UiText.get(com.hgtv.star.shared.R.string.ui_0242)
                close.requestFocus()
            }
            else -> {
                status.text=if(works.seasons.size>1) UiText.get(com.hgtv.star.shared.R.string.ui_0053, works.seasons.size) else UiText.get(com.hgtv.star.shared.R.string.ui_0033, works.items.size)
                // 标题由卡片行自己的 SectionHeading 画（「相关剧集」/「同系列 N 季 · 相关剧集」），
                // 这里不再另起一行重复同一句话。
                val cards=buildRelatedRow(close,works,series,{ target ->
                    finishedPanel?.dismiss()
                    detachPlayerSurface()
                    openDetail(target)
                })
                slot.addView(cards,lp(-1,-2))
                cards.post { cards.firstCard()?.requestFocus() }
            }
        }
    }

    val next=RelatedPanel(this,body) {
        finishedPanel=null
        // 浮层收起：重铺入口随浮层一起作废（旧引用会指到已经拆掉的视图树）。
        playerStripRefresh=null
        // 顶部四项回到「播放显示」里用户选的值（见 [syncPlaybackInfo]）。
        syncPlaybackInfo()
        // 收起之后焦点还给播放界面：还在播放页（不是点了卡片去了别处）时把控制条亮出来。
        if(screen=="player" && foreground) showHud()
    }
    finishedPanel=next
    // 数据落地时就地重铺这一块（见 `prefetchRelated` / `prefetchAuthorWorks`）：
    // 不重建浮层、不动已经摆好的那一帧。
    playerStripRefresh={ render() }
    next.show()
    // 这一层也是唤出来的浮层：顶部四项临时全显。
    syncPlaybackInfo()
    // 先按手里已有的数据铺一次（从详情页进来时多半已经取好了），缺的才发请求。
    render()
    refreshStripData(series,author)
}

/**
 * 缺数据才补一次请求。
 *
 * 已经有结果、或者正在取的时候什么都不做——`prefetch*` 自己带这条判据，
 * 这里再判一次只是让「重试」按钮不必关心它。
 */
private fun TvMainActivity.refreshStripData(series: Series,author: Boolean) {
    if(detail?.series?.id!=series.id) return
    if(author) ensureAuthorWorks(series.id,series) else ensureRelated(series.id)
}

/**
 * 播放设置里的「查看简介」。
 *
 * 用弹窗而不是跳简介页：播放设置是**在播放中**打开的，看简介不该打断这一集。
 * 弹窗带封面 + 剧名 + 一行副标题（作者 / 集数 / 题材），正文就是简介，
 * 关掉之后控制条回到画面上（与其余弹窗同一套收尾）。
 */
fun TvMainActivity.showPlayerIntro() {
    if(!onPlaybackPage()) return
    val data=detail ?: return
    val series=data.series
    dismissPlaybackMenu()
    // 副标题只拼真正有的那几项：缺作者 / 缺题材时不留下「 ·  · 」这种空档。
    val subtitle=buildString {
        if(series.author.isNotBlank()) append("作者 ").append(series.author)
        if(data.episodes.isNotEmpty()) {
            if(isNotEmpty()) append(" · ")
            append("全 ").append(data.episodes.size).append(" 集")
        }
        if(series.tags.isNotBlank()) {
            if(isNotEmpty()) append(" · ")
            append(series.tags)
        }
    }
    val dialog=TvDialog(this,UiText.get(com.hgtv.star.shared.R.string.ui_0204))
    dialog.header(series.title,subtitle,series.cover,::loadCover)
    dialog.message(series.description.ifBlank { UiText.get(com.hgtv.star.shared.R.string.ui_0243) })
    dialog.button(UiText.get(com.hgtv.star.shared.R.string.ui_0035),icon=R.drawable.ic_menu_close)
    dialog.show(null) { if(screen=="player" && foreground) showHud() }
}

/** 「加入收藏 / 已收藏」按钮此刻该写的文案：按本机收藏状态现算，不另存一份。 */
fun TvMainActivity.playerFavoriteLabel(): String =
    if(detail?.series?.id?.let { library.favorite(it) }==true) "已收藏" else "加入收藏"

/**
 * 播放设置里按了收藏：改本机收藏、同步云端，返回**改完之后**按钮该写的文案。
 *
 * 与简介页那枚按钮同一套动作（[library.toggle] + [syncFavorite] + `observeFavorite`），
 * 因此两边读到的状态永远一致；差别只有「改完不重建页面」——面板是缓存复用的，
 * 这里只把那一枚按钮的文案刷掉（见 `PlaybackMenu.actionLabel`）。
 */
fun TvMainActivity.togglePlayerFavorite(): String {
    val data=detail ?: return playerFavoriteLabel()
    // 用户正在看这一部：收藏更新检查这时候去拉详情没有意义，先停下（与简介页一致）。
    favoriteMonitor.stop()
    val saved=library.toggle(data.series)
    recommendPlaybackController.reportEventById(
        data.series.id,
        if(saved) com.hgtv.star.core.RecommendBehavior.Event.FAVORITE
        else com.hgtv.star.core.RecommendBehavior.Event.UNFAVORITE,
        episodeId=data.episodes.getOrNull(episodeIndex).orEmpty(),
        episodeIndex=episodeIndex,
    )
    // 未上架的剧没有分集：`observeFavorite` 的集数必须 > 0，传 0 会抛异常。
    if(saved && data.episodes.isNotEmpty())
        library.observeFavorite(data.series.id,data.episodes.size,System.currentTimeMillis(),acknowledge=true)
    // 先报这一条，再同步云端：没导入凭据时 `syncFavorite` 会再弹一条「需要登录」，
    // 那条比「已加入收藏」重要，让它留在屏幕上（`toast` 后一条覆盖前一条）。
    tvTools.toast(if(saved) UiText.get(com.hgtv.star.shared.R.string.ui_0073) else UiText.get(com.hgtv.star.shared.R.string.ui_0075))
    syncFavorite(data.series,saved)
    return if(saved) "已收藏" else "加入收藏"
}
