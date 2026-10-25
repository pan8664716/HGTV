// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import android.app.Activity
import android.graphics.Typeface
import android.view.*
import android.widget.*
import android.text.TextUtils
import com.google.android.exoplayer2.*
import com.hgtv.star.core.*
import com.hgtv.star.tv.ButtonSize
import com.hgtv.star.player.EpisodeGrid
import com.hgtv.star.list.HomeScreen
import com.hgtv.star.tv.TvButton
import com.hgtv.star.tv.TvDialog
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.player.RelatedPanel
import com.hgtv.star.tv.roundCorners
import com.hgtv.star.list.showCatalog
import com.hgtv.star.tv.tvScrollHost

// 本文件是从 MainActivity.kt 拆出来的「详情页：简介、选集、相关推荐」这一块。
// 这些成员仍然属于 MainActivity，只是以扩展成员（MainActivity.xxx）的形式放在这里，
// 逻辑与拆之前逐行一致；改这里等于改 Activity 本体。

internal fun MainActivity.openDetail(series: Series,resume: Boolean=false,autoplay: Boolean=true) {
    pendingDetail=series; detailAutoplay=autoplay
    // 从任何页面进详情都算「不是推荐那条路」：之后的正式播放返回键回详情页。
    playbackFromRecommend=false
    invalidatePage(); releaseCatalogViews(); screen="detail"; directPlayback=resume; detail=null
    synopsisExpanded=false; relatedOpen=false
    relatedWorks=null; relatedLoading=false; relatedFailed=false; relatedFor=""
    relatedToken.incrementAndGet(); relatedJob?.cancel(true); relatedJob=null
    val body=base(series.title); message(body,"正在加载剧集…")
    work({
        // 调试注入（见 [DebugFaults]）：把错误页那条上报链路（`content_detail`）变成可复现的一条命令。
        // 正式包里 BuildConfig.DEBUG 是常量 false，整段被 R8 删掉。
        if(BuildConfig.DEBUG && DebugFaults.mode(this)==DebugFaults.Mode.CONTENT_FAIL)
            throw java.io.IOException("调试注入：详情接口不可用")
        repository.detail(series.id)
    }, { loaded ->
        // 预告文案：详情接口只给「预告 / 全0集」这一档，**「预计11月6日上线」只有列表 / 搜索卡有**。
        // 从卡片点进来时把那一句带进来（它比「预告」有用得多），详情接口自己有更具体的就用自己的。
        val notice=series.notice.takeIf { it.contains("上线") } ?: loaded.series.notice
        val result=if(loaded.upcoming && notice!=loaded.series.notice)
            loaded.copy(series=loaded.series.copy(notice=notice)) else loaded
        detail=result
        // 详情拿到手 = 上一次的错误页已经过去，面板上的「详情加载失败」擦掉。
        clearDebugProblem()
        // 预告态没有分集：`observeFavorite` 的集数必须 > 0，传 0 会抛异常（见 `FavoriteUpdate.observe`）。
        if(result.episodes.isNotEmpty()) library.observeFavorite(series.id,result.episodes.size,System.currentTimeMillis(),acknowledge=true)
        // 未上架：把接口给的预约状态落进本机（下次进页面不必等这次请求就能画对按钮）。
        if(result.upcoming) library.setReserved(series.id,result.reserved)
        // 内容已经在屏幕上（下面这一句就渲染），相关剧集丢后台去查：用户点开时多半已经就绪。
        prefetchRelated(series.id)
        val target=resumeTargetFor(series.id,result.episodes)
        episodeIndex=target.index
        // 未上架的剧没有分集，**不能**走「直接播放」那条路（那会去取一个空的 vid）：
        // 从历史 / 首页的「继续观看」点进来时也一律落到预告详情页。
        if(resume && !result.upcoming) playEpisode(target.index,target.position,autoplay=detailAutoplay) else showDetail(false)
    }, { problem -> error(body,problem,stage="content_detail") { openDetail(series,resume,detailAutoplay) } })
}
/**
 * 续播目标：**本机进度优先，账号侧的历史兜底**。
 *
 * 用户换了一台设备（或刚登录账号）：这台机器上没有任何本机进度，只有账号侧知道
 * 「看到第几集、那一集看到第几秒」。不读账号那一条，点进去就只能从第 1 集重新开始——
 * 而用户读到的正是「同步过来的历史不算数」。
 *
 * 本机有记录时一律以本机为准：它更细（还知道整剧是否已看完），也是这台设备上的真实状态；
 * 账号那条只在标识对不上本机这一版选集时提供「第几集」这个兜底下标。
 */
internal fun MainActivity.resumeTargetFor(seriesId: String,episodes: List<String>): ResumeTarget {
    if(episodes.isEmpty()) return ResumeTarget(0,0)
    val local=library.progress(seriesId)
    // 本机删过的记录不再当「续播依据」：否则删完记录再进详情页，仍然从那一集接着放。
    val account=accountData?.history?.firstOrNull { it.series.id==seriesId && !library.historyDeleted(seriesId) }
    return ResumePlayback.target(
        episodes,
        local?.episodeId ?: account?.episodeId,
        local?.position ?: account?.position ?: 0L,
        local?.completed==true,
        account?.episodeIndex ?: -1
    )
}
/**
 * 详情页。
 *
 * [relatedOpen] 为真时走「相关剧集」布局：海报高度收到与「开始观看」底边平齐，
 * 下方的选集整块换成横向的相关剧集列表。[focusTarget] 用来在重排之后把焦点按回刚点的按钮。
 */
/**
 * 后台预取相关剧集（详情内容已经上屏之后才调用）。
 *
 * 剧名随请求一起传下去：筛选同系列靠它（见 [ContentRepository.related]），
 * 界面手里已经有 [detail]，不必让 core 再取一次详情页。
 *
 * 三个要点：
 *
 * 1. **不阻塞界面**：内容先渲染，这一条在 [io] 线程池上慢慢跑，用户看不到任何等待；
 * 2. **同一部剧不重复请求**：已经在跑或已经取到（[relatedFor]）就直接返回，
 *    因此用户点「相关剧集」时不会再发一次请求——取好了直接铺，还在途中就等它落地；
 * 3. **不走 [work]**：`work` 会取消上一次在途的请求（详情页重排、切集都会触发），
 *    而相关剧集是「同一部剧的附加内容」，被自己页面的重排取消掉就会永远停在加载态。
 *    这里单独持有 [relatedToken] + [relatedJob]：新剧作废旧剧，
 *    结果落地前再确认「还是同一部剧、还停在详情页」——中途换了剧或退出去，结果直接丢掉。
 */
internal fun MainActivity.prefetchRelated(id: String) {
    if(relatedFor==id && (relatedWorks!=null || relatedLoading)) return
    val token=relatedToken.incrementAndGet()
    relatedJob?.cancel(true)
    relatedFor=id; relatedLoading=true; relatedFailed=false
    relatedJob=io.submit {
        val result=runCatching { repository.related(id,detail?.series?.title.orEmpty()) }
        main.post {
            if(isDestroyed || relatedToken.get()!=token) return@post
            relatedLoading=false
            result.onSuccess { works -> relatedWorks=works }.onFailure { relatedFailed=true }
            // 用户此刻正好停在「相关剧集」这一栏里，就地铺上；否则什么也不用做。
            if(relatedOpen && screen=="detail" && detail?.series?.id==id) showDetail(false)
        }
    }
}
/**
 * 点「相关剧集」时调用：预取还没开始（或上一次失败了）才补一次请求。
 * 正常路径下这里什么都不做——请求早在详情上屏时就已经发出去了。
 */
internal fun MainActivity.ensureRelated(id: String) {
    if(relatedWorks!=null) return
    if(relatedLoading && relatedFor==id) return
    prefetchRelated(id)
}
/**
 * 「相关剧集」那一栏的卡片行：详情页与播放器的「换一部接着看」浮层**共用同一份铺法**。
 *
 * 用户的诉求就是两处看起来一样，所以这里不再各写一份：标题、季号角标、状态行、
 * 卡片的横滚与焦点串联都由这一处决定。
 *
 * [top] 是卡片行上方的控件（焦点在第一行按上键时落回它）；[current] 是正在看的那一部，
 * 它的卡片角标写「正在看」（不从列表里去掉——详情页要的就是「你就在这一部里」这一眼）。
 */
internal fun MainActivity.buildRelatedRow(
    top: View,
    works: RelatedWorks,
    current: Series,
    loadInto: (Series)->Unit,
    title: String?=null,
    requestFocus: Boolean=false
): HomeScreen {
    val related=HomeScreen(this,top,pageInset(),::loadCover,
        { item,_ -> loadInto(item) },{ item,anchor,shelf -> quickActions(item,anchor,shelf) },{},
        columns=library.homeColumns)
    val heading=title ?: if(works.seasons.size>1) "同系列 ${works.seasons.size} 季 · 相关剧集" else "相关剧集"
    related.renderCompact(listOf(HomeScreen.Shelf("related",heading,
        // 季号统一由卡片右上角画（见 `PosterCard` / `SeriesSeasons.badge`，优先用接口给的字段），
        // 这里不再往右下角塞「第 N 季」；「正在看」也由卡片自己的状态表达（进度条 + 状态行）。
        // 未上架的那一季第二行显示上线时间（「预计11月6日上线」），其余仍留空。
        works.items.map { item -> HomeScreen.Entry(item,if(item.upcoming) item.notice else "") })),"",requestFocus)
    return related
}
/**
 * 全剧播完：按「相关剧集」那一份数据给出下一部的推荐（见 `MainActivityPlayer` 的播完分支）。
 *
 * 摆法与简介页的「相关剧集」完全一致：底部弹出、贴底整宽的一栏卡片（[RelatedPanel]），
 * 背板是往上渐隐的透明阴影，上方的画面不压暗。用户的诉求就是「和简介页那个一样」，
 * 因此卡片行直接用 [buildRelatedRow] 那一份实现。
 *
 * 数据与详情页共用：[relatedWorks] 已经取好（从详情页点进来播放时就是这种情况）就直接铺；
 * 没有（从历史里直接开播、或那一次取失败）就先摆一句「正在找」，取到再换成卡片。
 */
internal fun MainActivity.showSeriesFinished(series: Series) {
    if(screen!="player" || finishedPanel!=null) return
    val cached=relatedWorks?.takeIf { relatedFor==series.id }
    if(cached!=null) {
        // 已经取过（从详情页点进来播放就是这种）：取到什么就摆什么，不再发第二次请求。
        if(cached.items.isNotEmpty()) showFinishedStrip(series,cached)
        else finishedStripMessage(series,"暂时没有找到相关剧集。")
        return
    }
    val waiting=TvDialog(this,"全剧已播完").message("《${series.title}》已经看完，正在找相关剧集…")
    waiting.button("关闭", icon=R.drawable.ic_menu_close)
    finishedNotice=waiting
    waiting.show(currentFocus) { if(finishedNotice===waiting) finishedNotice=null }
    val token=relatedToken.incrementAndGet()
    relatedJob?.cancel(true); relatedFor=series.id; relatedLoading=true; relatedFailed=false
    relatedJob=io.submit {
        val result=runCatching { repository.related(series.id,series.title) }
        main.post {
            if(isDestroyed || relatedToken.get()!=token) return@post
            relatedLoading=false
            result.onSuccess { works -> relatedWorks=works }.onFailure { relatedFailed=true }
            // 用户可能已经关掉这一层或退出了播放界面：那样就什么都不做。
            if(screen!="player" || finishedNotice!==waiting) return@post
            waiting.dismiss()
            val works=relatedWorks
            if(works!=null && works.items.isNotEmpty()) showFinishedStrip(series,works)
            else finishedStripMessage(series,if(relatedFailed) "相关剧集没能取到，可以稍后再看。" else "暂时没有找到相关剧集。")
        }
    }
}
/**
 * 底部弹出「换一部接着看」那一栏卡片。
 *
 * 焦点落在第一张卡上，方向键在卡片间横移；按返回收起（对话框默认行为），
 * 收起后把焦点还给播放界面（与选集面板同一套收尾）。
 */
private fun MainActivity.showFinishedStrip(series: Series,works: RelatedWorks) {
    if(screen!="player" || finishedPanel!=null) return
    val body=column().apply {
        // 透明阴影背板：往上渐隐，底边最深；画面在它上方保持原样（见 [RelatedPanel]）。
        background=TvStyle.overlayPanel()
        clipChildren=false
        clipToPadding=false
        setPadding(pageInset(),dp(18),pageInset(),dp(18))
    }
    body.addView(
        text("《${series.title}》已经看完 · 换一部接着看",18f,TvStyle.text).apply {
            setPadding(0,0,0,dp(TvStyle.GAP_TIGHT))
        },
        lp(-1,-2)
    )
    val row=buildRelatedRow(body,works,series,{ target ->
        // 从播放器离开要走 [detachPlayerSurface]（画面、传输、弹幕层一起收，实例保留），
        // 否则新页面盖上来时旧解码器还占着（与返回键那条路同一套做法）。
        finishedPanel?.dismiss()
        detachPlayerSurface()
        openDetail(target)
    })
    body.addView(row,lp(-1,-2))
    val next=RelatedPanel(this,body) {
        finishedPanel=null
        // 收起之后焦点还给播放界面：还在播放页（不是点了卡片去了别处）时把控制条亮出来。
        if(screen=="player" && foreground) showHud()
    }
    finishedPanel=next
    next.show()
    row.post { row.firstCard()?.requestFocus() }
}
/** 相关剧集取不到、或这一部确实没有：底部同样弹一层，只放一句说明与「关闭」。 */
private fun MainActivity.finishedStripMessage(series: Series,message: String) {
    if(screen!="player" || finishedPanel!=null) return
    val body=column().apply {
        background=TvStyle.overlayPanel()
        clipChildren=false
        clipToPadding=false
        setPadding(pageInset(),dp(18),pageInset(),dp(20))
    }
    body.addView(text("《${series.title}》已经看完",18f,TvStyle.text),lp(-1,-2))
    body.addView(
        text(message,16f,TvStyle.textDim).apply { setPadding(0,dp(TvStyle.GAP_TIGHT),0,dp(TvStyle.GAP_INLINE)) },
        lp(-1,-2)
    )
    val row=row()
    val close=addButton(row,"关闭",size=ButtonSize.NORMAL) { finishedPanel?.dismiss() }
    body.addView(row,lp(-1,-2))
    val next=RelatedPanel(this,body) { finishedPanel=null }
    finishedPanel=next
    next.show()
    row.post { close.requestFocus() }
}
internal fun MainActivity.showDetail(focusEpisode: Boolean, focusTarget: View?=null) {
    invalidatePage(); releaseCatalogViews(); screen="detail"; val data=detail ?: return showCatalog()
    // 详情页不留页头：顶部是完整竖版海报 + 剧集信息（78dp 栅格内），
    // 下方选集网格整幅贴底，与播放器选集面板同一份实现。
    root.removeAllViews(); root.setBackgroundColor(bg)
    val container=column().apply { clipChildren=false; clipToPadding=false }
    root.addView(container,FrameLayout.LayoutParams(-1,-1))
    reattachDebugOverlay()
    val scroll=ScrollView(this).apply { isVerticalScrollBarEnabled=false; clipChildren=false; clipToPadding=false } // focus-bleed-exempt: 边界就是整屏宽，最近的焦点控件（操作按钮 / 选集外壳）离屏幕边缘 ≥71dp
    val content=column().apply { clipChildren=false; clipToPadding=false }
    scroll.addView(content); container.addView(tvScrollHost(scroll),lp(-1,0).apply { weight=1f })
    val body=column().apply {
        clipChildren=false; clipToPadding=false
        setPadding(pageInset(),dp(18),pageInset(),dp(14))
    }
    // 顶部：完整竖版海报 + 剧集信息左右分栏。
    // 封面按原始比例 FIT_CENTER 整张显示（接口封面约 7:10），不再铺满裁切；
    // 文字改到海报右侧的纯色页面上，因此也不需要压暗层或文字投影。
    // 海报高度吃掉「屏高 − 选集块 − 页面内边距」的剩余空间，整页因此铺满屏幕，
    // 底部不会留出一块空白；估算偏差由下面的弹簧 Space 吸收，内容过高时才滚动。
    // 屏高用原始像素：下面的 dp() 也是像素，两边同一坐标系。
    val screenHeight=resources.displayMetrics.heightPixels
    // 选集网格的单元格高度含柔光带，因此整块比改版前高约 30dp；预留给它的空间跟着加。
    // 分页行（「1-20 / 21-40 …」）也占一行（芯片 30dp + 上下柔光 + 与集数行的间距），
    // 这里一并预留，否则海报会按旧尺寸铺满、把分页行挤出首屏。
    // 未上架没有选集：不再为它预留 200 多 dp，否则页面底部会空出一大块。
    val episodesHeight=if(data.upcoming) dp(96) else dp(206+2*TvStyle.SOFT_BAND+20+56)
    // 海报高度取「屏高 − 选集块 − 内边距」，再夹在两个上限之间：
    //
    // * 高度上限：屏高的 72%（原来的规则）；
    // * 宽度上限：内容宽的 42%（新加）。720P / 640dp 宽这种小屏上，海报若还按高度铺满，
    //   右侧信息列会被挤到放不下标题与四个按钮——按宽度反推一个高度上限，让海报先让步；
    // * 下限：240dp，但**不能超过上限**。早先这里把 240dp 写死成下界，
    //   而 `coerceIn(min,max)` 在 min > max 时会直接抛
    //   `IllegalArgumentException: Cannot coerce value to an empty range`——
    //   屏高换算成 dp 不足 333dp（例如 1080×1920 @ 540dpi = 320dp）时一进详情页就崩。
    val contentWidth=(resources.displayMetrics.widthPixels-2*pageInset()).coerceAtLeast(1)
    val maxPosterByHeight=(screenHeight*.72f).toInt()
    val maxPosterByWidth=(((contentWidth*0.42f)-2*dp(TvStyle.SOFT_BAND))/0.7f).toInt()
    val posterCeiling=minOf(maxPosterByHeight,maxPosterByWidth).coerceAtLeast(1)
    val posterFloor=minOf(dp(240),posterCeiling)
    val posterHeight=(screenHeight-dp(32)-episodesHeight).coerceIn(posterFloor,posterCeiling)
    val posterWidth=(posterHeight*7f/10f).toInt()+2*dp(TvStyle.SOFT_BAND)
    val hero=row().apply { gravity=Gravity.TOP; clipChildren=false; clipToPadding=false }
    // 海报卡座：一块凸起底板，海报按柔光带内缩嵌在里面，不再是「描边 + 通栏图」。
    val posterBand=dp(TvStyle.SOFT_BAND)
    val posterBox=FrameLayout(this).apply {
        background=softPanel(TvStyle.surfaceHigh,TvStyle.RADIUS_CARD,depth=1.15f)
        clipChildren=true; clipToPadding=true
        setPadding(posterBand,posterBand,posterBand,posterBand)
    }
    roundCorners(posterBox,TvStyle.RADIUS_CARD)
    val backdrop=ImageView(this).apply {
        scaleType=ImageView.ScaleType.FIT_CENTER
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    roundCorners(backdrop,TvStyle.RADIUS_POSTER)
    posterBox.addView(backdrop,FrameLayout.LayoutParams(-1,-1))
    loadCover(backdrop,data.series.cover)
    hero.addView(posterBox,lp(posterWidth,posterHeight).apply { rightMargin=dp(44) })
    val info=column().apply {
        clipChildren=false; clipToPadding=false
        setPadding(0,dp(4),0,0)
    }
    hero.addView(info,lp(0,-2).apply { weight=1f })
    info.addView(text(data.series.title,34f).apply {
        setTypeface(null,Typeface.BOLD); maxLines=2; ellipsize=TextUtils.TruncateAt.END
    })
    val meta=row().apply { setPadding(0,dp(14),0,dp(14)) }
    meta.addView(text("★",16f,TvStyle.tips))
    // 未上架的剧没有集数：这一格换成上线时间（「预计11月6日上线」），取不到就写「即将上线」。
    val upcoming=data.upcoming
    meta.addView(text(
        if(upcoming) data.series.notice.ifBlank { "即将上线" } else EpisodeLabel.of(data.episodes.size),
        16f,TvStyle.textDim
    ).apply { setPadding(dp(8),0,dp(16),0) })
    meta.addView(text(data.series.tags,16f,TvStyle.textDim).apply {
        maxLines=1; ellipsize=TextUtils.TruncateAt.END
    })
    // 作者 / 版权方：来自 App 的 `multi-detail` 接口（列表接口不带这一项）。
    // 放在信息行最右侧（与卡片上的位置一致——集数在左、作者在右），取不到时整格不占位。
    val byline=data.series.author.trim()
    if(byline.isNotEmpty()) {
        meta.addView(Space(this),lp(0,1).apply { weight=1f })
        meta.addView(text(byline,14f,TvStyle.muted).apply {
            maxLines=1; ellipsize=TextUtils.TruncateAt.END
        })
    }
    info.addView(meta)
    val description=data.series.description.ifBlank { if(upcoming) "尚未上线，预约后开播时提醒你" else "选择剧集开始观看" }
    val synopsis=text(description,16f,TvStyle.textDim).apply { maxLines=if(synopsisExpanded) 20 else 2; ellipsize=TextUtils.TruncateAt.END }
    info.addView(synopsis)
    // 按钮行四边各留 FOCUS_BLEED，放大后的焦点底色才不会在这一行里被切平；
    // 行本身用负外边距抵回去，按钮的位置与原来完全一致（仍从内容线开始）。
    val actions=row().apply {
        setPadding(dp(TvStyle.FOCUS_BLEED),dp(TvStyle.GAP_BLOCK),dp(TvStyle.FOCUS_BLEED),dp(TvStyle.FOCUS_BLEED))
    }
    // 四个入口（播放 / 收藏 / 完整简介 / 相关剧集）**等宽**：按钮文字长短不一
    // （「继续第 12 集」与「收藏」差一倍），按内容宽度排出来这一行会参差不齐。
    //
    // 用 weight=1 让父容器等分，**不要自己按屏宽算像素**：这一行在海报右侧的信息列里，
    // 可用宽度是「屏宽 − 两侧边距 − 海报宽 − 间距」，按整屏算会直接溢出屏幕（踩过一次）。
    // 文字放不下时 TvButton 自己省略号收尾。
    // 等宽之后每个按钮的可用宽度只有整行的四分之一，HERO 档的 36dp 内边距会把
    // 「继续第 12 集」挤成省略号，因此这一行单独把左右内边距收到 14dp（字号与高度不变）。
    val actionPad=TvStyle.GAP_INLINE+2
    // 宽屏：四个按钮在信息列里；窄屏（720P 的 640dp 宽这类）：信息列只剩两百来 dp，
    // 四个等宽按钮每个不到 70dp，文案全成省略号——这时把整行挪到海报下方、按整行宽度排。
    val narrowActions=widthDp()<900
    if(!narrowActions) info.addView(actions,lp(-1,-2).apply { leftMargin=-dp(TvStyle.FOCUS_BLEED); rightMargin=-dp(TvStyle.FOCUS_BLEED) })
    val progress=library.progress(data.series.id)
    val watched=library.watched(data.series.id)
    // 播放按钮的**文案固定为「播放」**，不随状态变（曾按状态显示「开始观看 / 继续第 N 集 / 重新观看」，
    // 用户要求统一成一个词）。续播目标仍然照算——`ResumePlayback.target` 处理了
    // 「上一集看完 → 从下一集开头」「看到一半 → 回到那一集的那个位置」两种情况，
    // 文案与动作因此仍然同源，只是不再把集数写在按钮上。
    val target=resumeTargetFor(data.series.id,data.episodes)
    // 这一行的三个按钮同档同高（56dp），不再出现「主按钮 56、旁边两个 48」的高低差；
    // 强调色仍然只给主操作，尺寸与语义分开管。
    // 播放按钮不再用 PRIMARY（常亮的亮面板）：简介页这一行是四个平级入口，
    // 只有一个亮着会让「收藏 / 完整简介 / 相关剧集」看起来像次要操作。
    // 四个按钮统一常规档 + 等宽，靠位置与文案区分主次。
    // 未上架的剧：主按钮换成「预约 / 已预约」。
    // 状态先按详情接口的 `online_subscribed` 画，点击先改本地、再发请求；
    // 请求失败就把按钮翻回去并提示，用户不会看到「点了却没预约上」。
    val reserved=library.reserved(data.series.id).takeIf { library.reservedKnown(data.series.id) } ?: data.reserved
    lateinit var play: TvButton
    play=addButton(actions,if(upcoming) (if(reserved) "已预约" else "预约") else "播放",
        size=ButtonSize.HERO,weight=1f,pad=actionPad,
        icon=if(upcoming) R.drawable.ic_action_reserve else R.drawable.ic_action_play) {
        if(upcoming) reserveSeries(data.series,play) else if(watched) playEpisode(0,0) else playEpisode(target.index,target.position)
    }
    // 未上架的剧**不摆收藏**：它没有分集，收藏更新检查（`FavoriteUpdate.observe` 要求集数 > 0）
    // 一按就会抛 `IllegalArgumentException`——线上表现为点「收藏」直接闪退。
    // 这类剧只有「预约」这一个动作，少一个按钮也不影响这一行的排布（等宽按剩余三枚均分）。
    if(!upcoming) {
        lateinit var favorite: TvButton
        favorite=addButton(actions,if(library.favorite(data.series.id)) "已收藏" else "收藏",size=ButtonSize.HERO,weight=1f,pad=actionPad,icon=R.drawable.ic_action_favorite) {
            favoriteMonitor.stop()
            val saved=library.toggle(data.series)
            syncFavorite(data.series,saved)
            if(saved) library.observeFavorite(data.series.id,data.episodes.size,System.currentTimeMillis(),acknowledge=true)
            favorite.text=if(saved) "已收藏" else "收藏"
        }
    }
    lateinit var synopsisButton: TvButton
    synopsisButton=addButton(actions,if(synopsisExpanded) "收起简介" else "完整简介",size=ButtonSize.HERO,weight=1f,pad=actionPad,icon=R.drawable.ic_action_synopsis) {
        synopsisExpanded=!synopsisExpanded
        synopsis.maxLines=if(synopsisExpanded) 20 else 2
        synopsisButton.text=if(synopsisExpanded) "收起简介" else "完整简介"
        synopsisButton.post { synopsisButton.requestRectangleOnScreen(android.graphics.Rect(0,0,synopsisButton.width,synopsisButton.height),false) }
    }
    // 「相关剧集」排在「完整简介」之后：点开时海报缩到与「开始观看」底边平齐，
    // 下方的选集整块换成横向的相关作品；再点一次收起，回到原样。
    lateinit var relatedButton: TvButton
    relatedButton=addButton(actions,if(relatedOpen) "收起相关" else "相关剧集",size=ButtonSize.HERO,weight=1f,pad=actionPad,icon=R.drawable.ic_action_related) {
        relatedOpen=!relatedOpen
        showDetail(false,relatedButton)
        if(relatedOpen) ensureRelated(data.series.id)
    }
    body.addView(hero,lp(-1,-2))
    if(narrowActions) body.addView(actions,lp(-1,-2).apply { leftMargin=-dp(TvStyle.FOCUS_BLEED); rightMargin=-dp(TvStyle.FOCUS_BLEED) })
    content.addView(body,lp(-1,-2))
    // 详情页没有页头，上下移动只能靠按钮行与下方那一块（选集 / 相关剧集）互相指：
    // 按钮行里「相关剧集」与「完整简介」按下键进下方那一块，下方第一行的上键回到按钮行。
    relatedButton.nextFocusDownId=relatedButton.id
    // 弹簧：内容不足一屏时把选集推到屏幕底部，而不是在页面底部留一块空白。
    content.minimumHeight=screenHeight
    content.addView(Space(this),lp(-1,0).apply { weight=1f })
    // 下方这一整块：默认是选集网格，点开「相关剧集」之后换成横向的相关作品列表。
    // 两者互斥——相关剧集展开时不显示选集，收起时恢复选集。
    if(relatedOpen) {
        val works=relatedWorks
        if(relatedLoading || (works==null && !relatedFailed)) {
            content.addView(text("正在查找同系列…",16f,TvStyle.textDim).apply {
                setPadding(pageInset(),dp(28),pageInset(),dp(18))
            },lp(-1,-2))
        } else if(relatedFailed) {
            // 失败给一个可重试的入口，而不是静默空列表。
            val retry=row().apply { setPadding(pageInset(),dp(24),pageInset(),dp(18)) }
            val label=text("相关剧集加载失败",16f,TvStyle.textDim).apply { setPadding(0,0,dp(TvStyle.GAP_BLOCK),0) }
            retry.addView(label,lp(-2,-2))
            addButton(retry,"重试") { prefetchRelated(data.series.id) }
            content.addView(retry,lp(-1,-2))
        } else if(works==null || works.items.isEmpty()) {
            // 接口没给相关剧集（或这一部确实没有）：如实说明，不留一块空白。
            content.addView(text("这一部暂时没有相关剧集",16f,TvStyle.textDim).apply {
                setPadding(pageInset(),dp(28),pageInset(),dp(18))
            },lp(-1,-2))
        } else {
            // 同系列各季排在前面并标注「第 N 季」（正在看的那一季写「正在看」），
            // 之后是接口给的其他相关作品（状态行用它自己的角标）。标题按有没有同系列区分。
            val related=buildRelatedRow(posterBox,works,data.series,{ openDetail(it) })
            val host=row().apply { setPadding(pageInset()-dp(TvStyle.FOCUS_BLEED),0,pageInset()-dp(TvStyle.FOCUS_BLEED),0) }
            host.addView(related,lp(-1,-2))
            content.addView(host,lp(-1,-2))
            related.post {
                related.firstCard()?.let { card ->
                    // 按钮行按下键落到相关作品的第一张卡；卡片按上键回到「相关剧集」按钮。
                    synopsisButton.nextFocusDownId=card.id
                    relatedButton.nextFocusDownId=card.id
                    card.nextFocusUpId=relatedButton.id
                    if(focusTarget==null) card.requestFocus()
                }
            }
        }
    } else if(upcoming) {
        // 未上架：**没有选集可摆**。这里给一行说明，按钮行照常可用（预约 / 收藏 / 简介 / 相关）。
        // 早先没有这一支，空分集会在下面建出一个 0 格的 `EpisodeGrid`，焦点链也随之断掉。
        content.addView(text(
            data.series.notice.ifBlank { "尚未上线" } + " · 预约后开播时会提醒你",
            16f,TvStyle.textDim
        ).apply { setPadding(pageInset(),dp(28),pageInset(),dp(18)) },lp(-1,-2))
        synopsisButton.nextFocusDownId=synopsisButton.id
        relatedButton.nextFocusDownId=relatedButton.id
        play.requestFocus()
    } else {
        // 选集与播放界面共用同一个 EpisodeGrid：这里整幅贴在页面末尾（不加页面栅格内边距），
        // 标题行、集数行、单元格尺寸与播放器弹窗一致，翻页也是同一套四向逻辑。
        // 第一页第一行按上时网格没有上一页可翻，EpisodeGrid.turn() 返回 false 把按键交回焦点系统，
        // 焦点因此仍然能回到上面的「继续观看 / 收藏 / 完整简介」这一行（详情页不留页头，上面只有这一行）。
        val episodes=EpisodeGrid(this,data.episodes.size,episodeIndex,player=false,
            { index -> playEpisode(index,if(progress?.episodeId==data.episodes[index] && !progress.completed) progress.position else 0) })
        // 简介页的选集不套背板：它就是页面里的一块内容，底板会读成「一个可以按进去的大盒子」。
        // 播放界面里同一份网格仍然用浮层渐变（播放界面不套新拟态，见 EpisodeGrid 的注释）。
        // 选集整幅贴底，但左右要与页面内容线对齐：网格自己的横向内边距只留给播放界面
        // （那是整幅压屏的浮层），简介页用一个补回焦点余量的外壳把它托到内容线上，
        // 「选集」这一行与第一格于是和上面的海报、按钮同一条竖线。
        val episodeHost=row().apply { setPadding(pageInset()-dp(TvStyle.FOCUS_BLEED),0,pageInset()-dp(TvStyle.FOCUS_BLEED),0) }
        episodeHost.addView(episodes,lp(-1,-2))
        content.addView(episodeHost,lp(-1,-2))
        // 按钮行按下键先进**分页行**（「1-20 / 21-40 …」），分页行按下进网格第一格；
        // 只有一页时没有分页行，直接进网格。网格第一行按上回到分页行，分页行按上回按钮行。
        val pagerTop=episodes.pagerTop(relatedButton)
        val firstCell=episodes.firstCell()
        val downTarget=pagerTop ?: firstCell
        synopsisButton.nextFocusDownId=(downTarget?.id ?: synopsisButton.id)
        relatedButton.nextFocusDownId=(downTarget?.id ?: relatedButton.id)
        firstCell?.nextFocusUpId=relatedButton.id
        if(focusEpisode) episodes.focusCurrent() else play.requestFocus()
    }
    // 海报高度：相关剧集展开时与右边按钮行的底边平齐（= 信息区的高度），
    // 收起时按原来的算法铺满剩余屏高。信息区高度要等布局完成才量得准，因此在这里回填。
    // 宽度跟着高度按 7:10 一起缩，否则封面会在变矮的卡座里左右留出两条空白（FIT_CENTER 不会拉伸）。
    fun fitPoster(attempt: Int=0) {
        val target=if(relatedOpen) info.height else posterHeight
        // 首帧里信息区可能还没量完（height==0）：再等一帧，最多两次，避免海报停在错误高度上。
        if(target<=0 && attempt<2) { hero.post { fitPoster(attempt+1) }; return }
        if(target<=0 || target==posterBox.layoutParams.height) return
        val width=(target*7f/10f).toInt()+2*posterBand
        posterBox.layoutParams=(posterBox.layoutParams as LinearLayout.LayoutParams).apply {
            height=target; this.width=width
        }
        posterBox.requestLayout()
    }
    hero.post { fitPoster() }
    if(focusTarget!=null) focusTarget.post { focusTarget.requestFocus() }
}
/**
 * 取本集弹幕（原 App 播放时看到的那一份，见 [ContentRepository.danmaku]）。
 *
 * 只有播放画面上的弹幕层会用到它：取不到、这一集没有、平台不再返回，都只是「没有弹幕」，
 * 不弹提示、不影响播放。[danmakuToken] 每换一集自增一次，
 * 在途的窗口循环发现 token 变了就停手，旧剧的弹幕不会接到新一集上。
 */

/**
 * 「预约 / 已预约」：先改本地、再发请求，失败翻回去。
 *
 * 为什么先改：预约是一次**写入**，平台偶尔回 `101144 HIT_SHARK_RULE`（风控短时限流，
 * 见 `ContentRepository.accountReserve`），那一次会自己退避重试；让用户盯着一个没反应的
 * 按钮等几秒，比「先亮起来、失败再翻回去」难受得多。翻回去时给一句提示，状态不会骗人。
 *
 * 未登录时不改状态：预约是账号侧数据，没有 `sessionid` 平台只会回失败。
 */
internal fun MainActivity.reserveSeries(series: Series, button: TvButton) {
    // 预约是账号侧数据（要 `sessionid`）：没登录时不改按钮状态，只提示一句。
    // 用专门的文案而不是通用的 [SIGN_IN_TOAST]：这里用户点的是「预约」，
    // 提示要直接回答「为什么点了没反应」。
    if(!repository.signedIn) { tvTools.toast("登录后才可以添加预约"); return }
    val target=!library.reserved(series.id)
    fun paint(reserved: Boolean) {
        button.text=if(reserved) "已预约" else "预约"
        button.contentDescription=button.text.toString()
    }
    library.setReserved(series.id,target); paint(target)
    applyAccountReserve(series,target)
    io.submit {
        val ok=repository.accountReserve(series.id,target)
        main.post {
            if(isDestroyed) return@post
            if(ok) {
                showHint(if(target) "已预约 · 开播时提醒你" else "已取消预约")
            } else {
                library.setReserved(series.id,!target); paint(!target)
                applyAccountReserve(series,!target)
                tvTools.toast(if(target) "预约失败，稍后再试" else "取消预约失败，稍后再试")
            }
        }
    }
}
