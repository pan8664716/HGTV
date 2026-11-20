// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.app.Activity
import android.graphics.Typeface
import android.view.*
import android.widget.*
import android.text.TextUtils
import com.hgtv.star.core.*
import com.hgtv.star.tv.ButtonSize
import com.hgtv.star.tv.player.EpisodeGrid
import com.hgtv.star.tv.list.HomeScreen
import com.hgtv.star.tv.TvButton
import com.hgtv.star.tv.TvDialog
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.tv.player.RelatedPanel
import com.hgtv.star.tv.roundCorners
import com.hgtv.star.tv.list.showCatalog
import com.hgtv.star.tv.tvScrollHost
import com.hgtv.star.shared.debug.DebugFaults
import com.hgtv.star.shared.ui.authorColor
import com.hgtv.star.tv.R

// 本文件是从 TvMainActivity.kt 拆出来的「详情页：简介、选集、相关推荐」这一块。
// 这些成员仍然属于 TvMainActivity，只是以扩展成员（TvMainActivity.xxx）的形式放在这里，
// 逻辑与拆之前逐行一致；改这里等于改 Activity 本体。

/** 详情页内存缓存最多留几部（见 [TvMainActivity.detailMemo]）。 */
private const val DETAIL_MEMO_LIMIT=12

fun TvMainActivity.openDetail(series: Series,resume: Boolean=false,autoplay: Boolean=true) {
    pendingDetail=series; detailAutoplay=autoplay
    // 从任何页面进详情都算「不是推荐那条路」：之后的正式播放返回键回详情页。
    playbackFromRecommend=false
    invalidatePage(); releaseCatalogViews(); screen="detail"; directPlayback=resume; detail=null
    relatedOpen=false
    relatedWorks=null; relatedLoading=false; relatedFailed=false; relatedFor=""
    relatedToken.incrementAndGet(); relatedJob?.cancel(true); relatedJob=null
    authorOpen=false
    authorWorks=null; authorLoading=false; authorFailed=false; authorFor=""
    authorToken.incrementAndGet(); authorJob?.cancel(true); authorJob=null
    detailAuthorLoading=false
    detailAuthorToken++
    val body=base(series.title)
    // **内存缓存优先**：这一部刚看过（本进程内）就直接铺，连「加载中」那一屏都不出现。
    // 进详情每次都先闪一屏三点、再换成内容，用户读到的就是「每次都要加载」。
    val memo=detailMemo[series.id]
    if(memo!=null) {
        detail=memo
        episodeIndex=resumeTargetFor(memo.series.id,memo.episodes).index
        showDetail(false)
        hideCenterLoading()
    } else {
        // 没有缓存才等待，且等待放在**屏幕正中**（与进入第一屏同一套三点）：
        // 在内容区插一行占位会让页面结构先长出来再被换掉，看着像闪一下。
        showCenterLoading("正在加载…")
    }
    detailController.clear()
    detailController.load(
        series=series,
        onCached={cached ->
            // 已经从内存缓存铺过了就不再拿磁盘那一份覆盖（磁盘可能更旧）。
            if(detail==null && screen=="detail") {
                detail=cached
                episodeIndex=resumeTargetFor(cached.series.id,cached.episodes).index
                hideCenterLoading()
                showDetail(false)
            }
        },
        onLoaded={result,target,changed ->
            if(screen!="detail" || result.series.id!=series.id) return@load
            detail=result
            // 拿到新的一份就记进内存缓存：同一部剧短时间内再点进来不再走网络等待。
            detailMemo.lruPut(DETAIL_MEMO_LIMIT,result.series.id,result)
            hideCenterLoading()
            clearDebugProblem()
            episodeIndex=target.index
            if(resume && !result.upcoming) {
                // 已经看完当前季时，历史卡片只打开简介，不再把最后一集从头自动播一遍。
                // 手动点「播放」仍然可以重看。
                val localFinished=library.progress(series.id)?.isFinished(result.episodes.size)==true
                val accountFinished=accountData?.history
                    ?.firstOrNull { it.series.id==series.id }
                    ?.isFinished()==true
                if(localFinished || accountFinished) {
                    directPlayback=false
                    showDetail(false)
                } else {
                    playEpisode(target.index,target.position,autoplay=detailAutoplay,clearDanmakuOnSwitch=true)
                }
            } else if(changed) {
                showDetail(false)
            }
        },
        onError={problem ->
            hideCenterLoading()
            // 屏上已经有内容（内存 / 磁盘缓存）时，后台那次刷新失败**不打扰**：
            // 用户看到的是一整页，多一句「刷新失败」只是噪音；真没内容才走错误页。
            if(detail?.series?.id!=series.id) {
                error(body,problem,stage="content_detail") { openDetail(series,resume,detailAutoplay) }
            }
        },
        loadDetail={
            if(BuildConfig.DEBUG && DebugFaults.mode(this)==DebugFaults.Mode.CONTENT_FAIL)
                throw java.io.IOException("调试注入：详情接口不可用")
            repository.detail(series.id,includeAuthor=false)
        },
    )
}

/**
 * 从历史记录直接进入播放页。
 *
 * 这里不能复用 [openDetail] 再在加载完成后起播：那样会先建立详情页，用户会看到
 * 详情页闪一下再跳到播放器。直进路径先把页面切成播放页的加载态，详情落地后直接
 * 调用 [playEpisode]；只有当前季已经看完、或详情显示未上架时，才退回详情页。
 */
fun TvMainActivity.openDirectPlayback(series: Series,autoplay: Boolean=true) {
    pendingDetail=series; detailAutoplay=autoplay
    playbackFromRecommend=false
    invalidatePage(); releaseCatalogViews()
    screen="player"; directPlayback=true; detail=null
    relatedOpen=false
    relatedWorks=null; relatedLoading=false; relatedFailed=false; relatedFor=""
    relatedToken.incrementAndGet(); relatedJob?.cancel(true); relatedJob=null
    authorOpen=false
    authorWorks=null; authorLoading=false; authorFailed=false; authorFor=""
    authorToken.incrementAndGet(); authorJob?.cancel(true); authorJob=null
    detailAuthorLoading=false
    detailAuthorToken++
    root.removeAllViews()
    root.setBackgroundColor(android.graphics.Color.BLACK)
    showCenterLoading("正在加载《${series.title}》…")
    detailController.clear()

    var started=false
    fun start(result: Detail) {
        if(started || screen!="player" || result.series.id!=series.id) return
        started=true
        detail=result
        detailMemo.lruPut(DETAIL_MEMO_LIMIT,result.series.id,result)
        hideCenterLoading()
        clearDebugProblem()
        val target=resumeTargetFor(result.series.id,result.episodes)
        episodeIndex=target.index
        val localFinished=library.progress(series.id)?.isFinished(result.episodes.size)==true
        val accountFinished=accountData?.history
            ?.firstOrNull { it.series.id==series.id }
            ?.isFinished()==true
        if(result.upcoming || localFinished || accountFinished) {
            // 未上架 / 已看完的当前季没有可自动续播的目标，交给详情页。
            directPlayback=false
            showDetail(false)
        } else {
            playEpisode(target.index,target.position,autoplay=detailAutoplay,clearDanmakuOnSwitch=true)
        }
    }

    detailController.load(
        series=series,
        onCached={cached ->
            // 本地缓存有完整分集时可以先起播，网络返回后再静默更新缓存。
            if(cached.episodes.isNotEmpty() && !cached.upcoming) start(cached)
        },
        onLoaded={result,_,_ -> start(result) },
        onError={problem ->
            if(detail?.series?.id!=series.id) {
                hideCenterLoading()
                val body=base(series.title)
                error(body,problem,stage="content_detail") { openDirectPlayback(series,autoplay) }
            }
        },
        loadDetail={
            if(BuildConfig.DEBUG && DebugFaults.mode(this)==DebugFaults.Mode.CONTENT_FAIL)
                throw java.io.IOException("调试注入：详情接口不可用")
            repository.detail(series.id,includeAuthor=false)
        },
    )
}

/** 详情主体一致时不重建整页；作者由 [detailAuthorRefresh] 单独补。 */
private fun sameDetailContent(a: Detail,b: Detail): Boolean =
    a.episodes==b.episodes && a.upcoming==b.upcoming && a.reserved==b.reserved &&
        a.series.copy(author="")==b.series.copy(author="")

/**
 * 作者只是附加信息：主体详情先上屏，再在后台补一次 `multi-detail`。
 * 结果落地后就地更新信息行，并让「作者作品」入口跟着出现。
 */
fun TvMainActivity.loadDetailAuthor(current: Detail) {
    detailController.loadAuthor(current)
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
fun TvMainActivity.resumeTargetFor(seriesId: String,episodes: List<String>): ResumeTarget {
    return detailController.resumeTarget(seriesId,episodes)
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
fun TvMainActivity.prefetchRelated(id: String) {
    detailController.prefetchRelated(id,detail?.series?.title.orEmpty())
}
/**
 * 点「相关剧集」时调用：预取还没开始（或上一次失败了）才补一次请求。
 * 正常路径下这里什么都不做——请求早在详情上屏时就已经发出去了。
 */
fun TvMainActivity.ensureRelated(id: String) {
    detailController.ensureRelated(id,detail?.series?.title.orEmpty())
}
/**
 * 后台预取「作者作品」（详情内容已经上屏之后才调用）。
 *
 * 与 [prefetchRelated] 同一套写法与理由：不阻塞界面、同一作者不重复请求、
 * 单独持有 [authorToken] + [authorJob]（不走 [work]，否则会被本页重排取消掉）。
 *
 * 差别在代价：相关剧集一次请求就够，作者作品要按页搜（见 [ContentRepository.authorWorks]）
 * 再按家族补季，一共要 5~10 秒，**更不能放在详情页的关键路径上**；
 * 因此它分两段交付：搜索完先铺一批卡片，补季完再就地换成完整的那一份。
 *
 * [series] 是「正在看的那一部」：它只用来定缓存键与补季的家族基名
 *（顺序不再由客户端算，见 `ContentRepository.authorWorks`），仍要一起传下去。
 */
fun TvMainActivity.prefetchAuthorWorks(id: String, series: Series) {
    detailController.prefetchAuthorWorks(series)
}
/** 点「作者作品」时调用：预取还没开始（或上一次失败了）才补一次请求。 */
fun TvMainActivity.ensureAuthorWorks(id: String, series: Series) {
    detailController.ensureAuthorWorks(series)
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
fun TvMainActivity.buildRelatedRow(
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
 * 「作者作品」的表头：**圆形头像 + 作者名**（下面再一行作品数）。
 *
 * 平台没有作者头像（详情只给一个 `author` 字符串，没有 `author_id` / `avatar`），
 * 因此头像用**名字首字的圆形色块**：同一作者每次进页面都是同一个颜色（按名字取色），
 * 换一部作品也不会变——用户读起来仍然是「一个人」，而不是一块随机占位。
 *
 * 圆是 `GradientDrawable(OVAL)` 画的实体圆，不依赖 API 21 的 outline 裁剪，
 * `minSdk 19` 上同样是正圆（与「我的」页那张头像的兼容处理同源，见 `clipToCircleCompat`）。
 */
private fun TvMainActivity.authorHead(name: String,hint: String,loading: Boolean=false): LinearLayout {
    val head=row().apply { setPadding(pageInset(),dp(24),pageInset(),dp(8)) }
    val size=dp(52)
    val avatar=text(name.take(1),22f,android.graphics.Color.WHITE).apply {
        typeface=Typeface.DEFAULT_BOLD
        gravity=Gravity.CENTER
        isFocusable=false
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        background=android.graphics.drawable.GradientDrawable().apply {
            shape=android.graphics.drawable.GradientDrawable.OVAL
            setColor(authorColor(name))
        }
    }
    head.addView(avatar,lp(size,size))
    val texts=column()
    // 名字与「等待中的三点」同一行：三点跟在作者名后面，而不是在头像下面另起一行——
    // 另起一行会让这一块时高时矮（点出来多一块、数据到了又收回去）。
    val nameRow=row()
    // 名字吃掉剩下的宽度（长名字用省略号收尾）：给 wrap_content 的话，名字一长就把
    // 后面那三点顶出这一行。
    nameRow.addView(text(name,20f,TvStyle.textStrong).apply {
        setTypeface(null,Typeface.BOLD)
        maxLines=1
        ellipsize=TextUtils.TruncateAt.END
    },lp(0,-2).apply { weight=1f })
    if(loading) {
        nameRow.addView(
            com.hgtv.star.shared.ui.LoadingDotsView(this),
            lp(-2,-2).apply { leftMargin=dp(TvStyle.GAP_INLINE) },
        )
    }
    texts.addView(nameRow,lp(-1,-2))
    texts.addView(text(hint,14f,TvStyle.muted).apply { setPadding(0,dp(4),0,0) })
    gapInto(head,texts,lp(0,-2).apply { weight=1f },TvStyle.GAP_INLINE+4)
    return head
}
fun TvMainActivity.showDetail(focusEpisode: Boolean, focusTarget: View?=null) {
    if(tab==TAB_HOME && collection==null) homeFocusRequested=false
    invalidatePage(); releaseCatalogViews(); screen="detail"; val data=detail ?: return showCatalog()
    // 详情页不留页头：顶部是完整竖版海报 + 剧集信息（78dp 栅格内），
    // 下方选集网格整幅贴底，与播放器选集面板同一份实现。
    root.removeAllViews(); root.setBackgroundColor(bg)
    val container=column().apply { clipChildren=false; clipToPadding=false }
    root.addView(container,FrameLayout.LayoutParams(-1,-1))
    reattachDebugOverlay()
    // `isFillViewport = true`：内容不足一屏时把子内容撑到整屏高，下面那条弹簧（weight=1）
    // 才有空间可分——选集因此**贴底**摆放，行数变多时是往上长（这正是「选集区向上提高」），
    // 而不是挂在信息区下面、把空白留在页面底部。
    val scroll=ScrollView(this).apply { isVerticalScrollBarEnabled=false; clipChildren=false; clipToPadding=false; isFillViewport=true } // focus-bleed-exempt: 边界就是整屏宽，最近的焦点控件（操作按钮 / 选集外壳）离屏幕边缘 ≥71dp
    val content=column().apply { clipChildren=false; clipToPadding=false }
    scroll.addView(content); container.addView(tvScrollHost(scroll),lp(-1,0).apply { weight=1f })
    val body=column().apply {
        clipChildren=false; clipToPadding=false
        setPadding(pageInset(),dp(18),pageInset(),dp(14))
    }
    // 顶部：完整竖版海报 + 剧集信息左右分栏。
    // 封面按原始比例 FIT_CENTER 整张显示（接口封面约 7:10），不再铺满裁切；
    // 文字改到海报右侧的纯色页面上，因此也不需要压暗层或文字投影。
    //
    // **海报高度 = 信息列高度**（标题 + 集数作者行 + 简介 + 按钮行），收起与展开「相关剧集」时
    // 都是同一个高度：早先收起时海报按「屏高 − 选集块」铺满，一点「相关剧集」又缩回信息列高度，
    // 同一页里海报跳一下；现在不再为选集预留固定高度，海报只按右侧文字定高，
    // 省下来的竖向空间全部交给下面的选集区（见函数末尾的 [fitPoster]）。
    // 屏高用原始像素：下面的 dp() 也是像素，两边同一坐标系。
    val screenHeight=resources.displayMetrics.heightPixels
    // 内容宽：海报的宽度上限按它反推（信息列再窄也要留住标题与按钮）。
    val contentWidth=(resources.displayMetrics.widthPixels-2*pageInset()).coerceAtLeast(1)
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
    // 首帧占位：先按 240dp 高挂上去，第一帧量完信息列再改成与它等高（见 [fitPoster]）。
    // 不用 0 宽占位：那一帧信息列会按整行宽去折行，标题行数与最终不符，白闪一次。
    hero.addView(posterBox,lp((dp(240)*7f/10f).toInt()+2*posterBand,dp(240)).apply { rightMargin=dp(44) })
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
    val authorSpace=Space(this)
    val authorView=text(byline,14f,TvStyle.muted).apply {
        maxLines=1; ellipsize=TextUtils.TruncateAt.END
    }
    meta.addView(authorSpace,lp(0,1).apply { weight=1f })
    meta.addView(authorView)
    fun applyAuthor(name: String) {
        val shown=name.trim()
        authorSpace.visibility=if(shown.isEmpty()) View.GONE else View.VISIBLE
        authorView.visibility=authorSpace.visibility
        authorView.text=shown
    }
    applyAuthor(byline)
    info.addView(meta)
    val description=data.series.description.ifBlank { if(upcoming) "尚未上线，预约后开播时提醒你" else "选择剧集开始观看" }
    // 简介固定三行：这一页不再有「完整简介」按钮（用户要求去掉），也就没有可展开的地方。
    // 行数写死而不是放开折行：海报高度跟着信息列走，简介一长海报就会跟着拔高，
    // 反而把下面的选集挤出去；三行足够读清这一部讲什么，超出部分省略号收尾。
    val synopsis=text(description,16f,TvStyle.textDim).apply { maxLines=3; ellipsize=TextUtils.TruncateAt.END }
    info.addView(synopsis)
    // 按钮行四边各留 FOCUS_BLEED，放大后的焦点底色才不会在这一行里被切平；
    // 行本身用负外边距抵回去，按钮的位置与原来完全一致（仍从内容线开始）。
    val actions=row().apply {
        setPadding(dp(TvStyle.FOCUS_BLEED),dp(TvStyle.GAP_BLOCK),dp(TvStyle.FOCUS_BLEED),dp(TvStyle.FOCUS_BLEED))
    }
    // 这一行的入口（播放 / 收藏 / 相关剧集）**等宽**：按钮文字长短不一
    // （「继续第 12 集」与「收藏」差一倍），按内容宽度排出来这一行会参差不齐。
    //
    // 用 weight=1 让父容器等分，**不要自己按屏宽算像素**：这一行在海报右侧的信息列里，
    // 可用宽度是「屏宽 − 两侧边距 − 海报宽 − 间距」，按整屏算会直接溢出屏幕（踩过一次）。
    // 文字放不下时 TvButton 自己省略号收尾。
    // 等宽之后每个按钮的可用宽度只有整行的四分之一，HERO 档的 36dp 内边距会把
    // 「继续第 12 集」挤成省略号，因此这一行单独把左右内边距收到 14dp（字号与高度不变）。
    val actionPad=TvStyle.GAP_INLINE+2
    // 宽屏：按钮行在信息列里；窄屏（720P 的 640dp 宽这类）：信息列只剩两百来 dp，
    // 等宽按钮每个不到 70dp，文案全成省略号——这时把整行挪到海报下方、按整行宽度排。
    val narrowActions=widthDp()<900
    if(!narrowActions) info.addView(actions,lp(-1,-2).apply { leftMargin=-dp(TvStyle.FOCUS_BLEED); rightMargin=-dp(TvStyle.FOCUS_BLEED) })
    val progress=library.progress(data.series.id)
    // 播放按钮的**文案固定为「播放」**，不随状态变（曾按状态显示「开始观看 / 继续第 N 集 / 重新观看」，
    // 用户要求统一成一个词）。续播目标仍然照算——`ResumePlayback.target` 处理了
    // 「上一集看完 → 从下一集开头」「看到一半 → 回到那一集的那个位置」两种情况，
    // 文案与动作因此仍然同源，只是不再把集数写在按钮上。
    val target=resumeTargetFor(data.series.id,data.episodes)
    // 这一行按钮同档同高（56dp），不再出现「主按钮 56、旁边两个 48」的高低差；
    // 强调色仍然只给主操作，尺寸与语义分开管。
    // 播放按钮不再用 PRIMARY（常亮的亮面板）：简介页这一行是几个平级入口，
    // 只有一个亮着会让「收藏 / 相关剧集」看起来像次要操作。
    // 按钮统一常规档 + 等宽，靠位置与文案区分主次。
    //
    // 每枚按钮都等分剩余宽度：去掉「完整简介」之后这一行是三枚（未上架时两枚），
    // 每一枚因此更宽，原来为了塞下四个而收窄的内边距也不必再压。
    // 未上架的剧：主按钮换成「预约 / 已预约」。
    // 状态**本地「最后已知」优先**：用户自己点过的那一次就是最可信的（见 `Library.reservedKnown`），
    // 接口的 `online_subscribed` 只在本地没有记录时兜底——它刚写入后还会回 0，
    // 按它画就会把「已预约」显示成「预约」（用户报的就是这一条）。
    // 点击先改本地、再发请求；请求失败就把按钮翻回去并提示，用户不会看到「点了却没预约上」。
    val reserved=library.reserved(data.series.id).takeIf { library.reservedKnown(data.series.id) } ?: data.reserved
    // 下方那一块（选集 / 相关剧集）的重铺函数：按钮行要能调到它，而它要用到下面才建好的容器，
    // 因此先用一个可空占位，等容器建好之后再填进去（见下面 [bottom] 那一段）。
    var rebuildBottom: (Boolean)->Unit={}
    lateinit var play: TvButton
    play=addButton(actions,if(upcoming) (if(reserved) "已预约" else "预约") else "播放",
        size=ButtonSize.HERO,weight=1f,pad=actionPad,
        icon=if(upcoming) R.drawable.ic_action_reserve else R.drawable.ic_action_play) {
        if(upcoming) reserveSeries(data.series,play,reserved)
        else playEpisode(target.index,target.position,clearDanmakuOnSwitch=true)
    }
    // 未上架的剧**不摆收藏**：它没有分集，收藏更新检查（`FavoriteUpdate.observe` 要求集数 > 0）
    // 一按就会抛 `IllegalArgumentException`——线上表现为点「收藏」直接闪退。
    // 这类剧只有「预约」这一个动作，少一个按钮也不影响这一行的排布（等宽按剩余三枚均分）。
    // 按钮行的最后一枚：往下要进下方那一块（选集 / 相关剧集），因此先记下来。
    var authorButton: TvButton?=null
    lateinit var lastAction: TvButton
    lastAction=play
    if(!upcoming) {
        lateinit var favorite: TvButton
        favorite=addButton(actions,if(library.favorite(data.series.id)) "已收藏" else "收藏",size=ButtonSize.HERO,weight=1f,pad=actionPad,icon=R.drawable.ic_action_favorite) {
            favoriteMonitor.stop()
            val saved=library.toggle(data.series)
            syncFavorite(data.series,saved)
            if(saved) library.observeFavorite(data.series.id,data.episodes.size,System.currentTimeMillis(),acknowledge=true)
            favorite.text=if(saved) "已收藏" else "收藏"
        }
        lastAction=favorite
    }
    // [relatedButton] 的点击里要把作者按钮翻回「作者作品」（两栏互斥），
    // 而作者按钮可能因为作者晚到，稍后才动态插入。
    lateinit var relatedButton: TvButton
    relatedButton=addButton(actions,if(relatedOpen) "收起相关" else "相关剧集",size=ButtonSize.HERO,weight=1f,pad=actionPad,icon=R.drawable.ic_action_related) {
        relatedOpen=!relatedOpen
        // 两栏互斥：展开相关剧集时收起作者作品（下面那一块只有一处能摆）。
        if(relatedOpen) authorOpen=false
        // **只重铺下方那一块**，不整页重建：整页重建会让海报从首帧占位回填一次、
        // 封面重新解码一次，页面就会闪一下（见下面 [bottom] 的说明）。
        relatedButton.text=if(relatedOpen) "收起相关" else "相关剧集"
        relatedButton.contentDescription=relatedButton.text.toString()
        authorButton?.let { authorEntry ->
            authorEntry.text=if(authorOpen) "收起作者" else "作者作品"
            authorEntry.contentDescription=authorEntry.text.toString()
        }
        // 先发请求再重铺：`prefetchRelated` 会**同步**把 `relatedLoading` 置真，
        // 因此这一次重铺就能直接画出三点；反过来的话会先闪一下「这一部暂时没有相关剧集」。
        if(relatedOpen) ensureRelated(data.series.id)
        rebuildBottom(true)
    }
    /**
     * 「作者作品」：作者名来自详情（`multi-detail` 的 `author`，见 [Series.author]）。
     *
     * 首次详情可能先上屏、作者随后由 `multi-detail` 补回。旧实现只在建页那一刻判断作者，
     * 晚到的作者只更新文字，不会补按钮；用户必须退出再进一次才能看到入口。
     * 这里允许按钮在作者到达时动态插入，并沿用同一套点击与焦点逻辑。
     */
    fun installAuthorButton(rawName: String) {
        val authorName=rawName.trim()
        if(authorName.isEmpty()) return
        authorButton?.let { existing ->
            existing.text=if(authorOpen) "收起作者" else "作者作品"
            existing.contentDescription=existing.text.toString()
            return
        }
        // 图标沿用工具栏那枚人像（`ic_toolbar_profile`）：作者作品也是「一个人」的入口，
        // 不另造一份一模一样的图形资源。
        // 先声明再赋值：按钮自己的点击里要改自己的文案，不能写在初始化的 lambda 里。
        lateinit var authorView: TvButton
        authorView=addButton(actions,if(authorOpen) "收起作者" else "作者作品",size=ButtonSize.HERO,weight=1f,pad=actionPad,icon=R.drawable.ic_toolbar_profile) {
            authorOpen=!authorOpen
            if(authorOpen) relatedOpen=false
            authorView.text=if(authorOpen) "收起作者" else "作者作品"
            authorView.contentDescription=authorView.text.toString()
            relatedButton.text=if(relatedOpen) "收起相关" else "相关剧集"
            relatedButton.contentDescription=relatedButton.text.toString()
            // 同上：先发请求（同步置 `authorWorksLoading`），再重铺，点开就能看到三点在跑。
            if(authorOpen) ensureAuthorWorks(data.series.id,detail?.series ?: data.series)
            rebuildBottom(true)
        }
        authorButton=authorView
        // 这之后按钮行的最后一枚是「作者作品」：按下键由它往下走（见 [buildBottom]）。
        lastAction=authorView
        // 晚插入时 buildBottom 已经跑过，沿用「相关剧集」当前的下键目标即可。
        authorView.nextFocusDownId=relatedButton.nextFocusDownId
    }
    installAuthorButton(data.series.author)
    detailAuthorRefresh={ name ->
        applyAuthor(name)
        installAuthorButton(name)
    }
    body.addView(hero,lp(-1,-2))
    if(narrowActions) body.addView(actions,lp(-1,-2).apply { leftMargin=-dp(TvStyle.FOCUS_BLEED); rightMargin=-dp(TvStyle.FOCUS_BLEED) })
    content.addView(body,lp(-1,-2))
    // 详情页没有页头，上下移动只能靠按钮行与下方那一块（选集 / 相关剧集）互相指：
    // 按钮行里最后一枚（收藏 / 预约）与「相关剧集」按下键进下方那一块，下方第一行的上键回到按钮行。
    relatedButton.nextFocusDownId=relatedButton.id
    // 弹簧：内容不足一屏时把选集推到屏幕底部，而不是在页面底部留一块空白。
    //
    // 最小高度比屏高再留一点余量（两档焦点余量 12dp×2）：内容只要**不比视口高**，
    // 页面就没有可滚的余量，焦点落到选集最后一行时整页不会往下滑一小截——
    // 这正是「五行的最后一行选中时页面还是会动一下」的来源。
    // 选集块本身也按这个视口钉住高度（见下面 `episodes.maxBlockHeight`）。
    val viewportHeight=(screenHeight-dp(2*TvStyle.FOCUS_BLEED_VERTICAL)).coerceAtLeast(1)
    content.minimumHeight=viewportHeight
    val spring=Space(this)
    content.addView(spring,lp(-1,0).apply { weight=1f })
    // 下方这一整块：默认是选集网格，点开「相关剧集」之后换成横向的相关作品列表。
    // 两者互斥——相关剧集展开时不显示选集，收起时恢复选集。
    //
    // **这一块单独装在一个容器里，切换时只重铺它**：早先点「相关剧集 / 收起相关」走的是
    // `showDetail()` 整页重建——海报要从首帧 240dp 占位回填到信息列高度、封面要重新解码一次，
    // 两件事都落在同一帧上，页面就会闪一下。现在上面的海报、信息列、按钮行一个像素都不动，
    // 只把下面这一块换掉（见 [buildBottom]）。
    val bottom=column().apply { clipChildren=false; clipToPadding=false }
    content.addView(bottom,lp(-1,-2))
    /** 当前这一屏的选集网格：展开相关剧集时是 null（那一块里没有网格）。 */
    var episodes: EpisodeGrid?=null
    /**
     * **一屏放下**：选集块的高度 = 视口高 − 上方信息区（海报那一块），
     * 正好填满剩下的空间，页面因此没有可滚余量（见 `EpisodeGrid.maxBlockHeight`）。
     *
     * 信息区高度会变（海报按信息列回填、标题折行、窄屏按钮行挪到海报下方），
     * 因此挂一个布局回调持续回填，而不是只在首帧量一次——只量一次的话，
     * 信息区后来变高 20dp，选集块就多出 20dp 可滚距离，焦点落到最后一行时页面又会滑。
     * 展开「相关剧集」时下面没有网格（[episodes] 为 null），这里自然什么都不做。
     */
    fun syncEpisodeRoom() {
        val grid=episodes ?: return
        val room=viewportHeight-body.height
        if(room>0 && room!=grid.maxBlockHeight) grid.maxBlockHeight=room
    }
    body.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> syncEpisodeRoom() }
    // 首帧落焦只做一次（从榜单点进来先落在「播放」上）；之后重铺下方那一块不动焦点，
    // 收起相关剧集时焦点仍然停在「收起相关 / 相关剧集」按钮上。
    var bottomBuilt=false
    /**
     * 重铺下方那一块。
     *
     * [focusFirst] = 铺完之后把焦点落到第一张相关卡片 / 当前集上：点「相关剧集」、
     * 相关剧集结果落地时用 true；收起回选集时传 false，焦点留在按钮行不动。
     */
    fun buildBottom(focusFirst: Boolean) {
        val initial=!bottomBuilt
        bottomBuilt=true
        bottom.removeAllViews()
        episodes=null
        // **作者作品贴在上方信息区下面**，不能被弹簧推到屏幕底部：那一栏只有头像 + 一行状态 +
        // 几排卡片，贴底会让头像先出现在最下面、数据到了再往上跳一截（用户读到的就是
        // 「头像姓名先跑到底部，然后又弹上来」）。选集与相关剧集仍然贴底，所以弹簧只在
        // 作者作品展开时收起来。
        (spring.layoutParams as? LinearLayout.LayoutParams)?.let { p ->
            val weight=if(authorOpen) 0f else 1f
            if(p.weight!=weight) {
                p.weight=weight
                spring.layoutParams=p
            }
        }
        if(relatedOpen) {
            val works=relatedWorks
            if(relatedLoading || (works==null && !relatedFailed)) {
                // 等待统一用三点动画（与列表加载 / 启动页同一个控件），不再只留一行静止的字。
                val holder=row().apply { setPadding(pageInset(),dp(24),pageInset(),dp(18)) }
                holder.addView(
                    com.hgtv.star.shared.ui.LoadingDotsView(this).apply { label="正在查找同系列…" },
                    lp(-2,-2),
                )
                bottom.addView(holder,lp(-1,-2))
            } else if(relatedFailed) {
                // 失败给一个可重试的入口，而不是静默空列表。
                val retry=row().apply { setPadding(pageInset(),dp(24),pageInset(),dp(18)) }
                val label=text("相关剧集加载失败",16f,TvStyle.textDim).apply { setPadding(0,0,dp(TvStyle.GAP_BLOCK),0) }
                retry.addView(label,lp(-2,-2))
                addButton(retry,"重试") { prefetchRelated(data.series.id) }
                bottom.addView(retry,lp(-1,-2))
            } else if(works==null || works.items.isEmpty()) {
                // 接口没给相关剧集（或这一部确实没有）：如实说明，不留一块空白。
                bottom.addView(text("这一部暂时没有相关剧集",16f,TvStyle.textDim).apply {
                    setPadding(pageInset(),dp(28),pageInset(),dp(18))
                },lp(-1,-2))
            } else {
                // 同系列各季排在前面并标注「第 N 季」（正在看的那一季写「正在看」），
                // 之后是接口给的其他相关作品（状态行用它自己的角标）。标题按有没有同系列区分。
                val related=buildRelatedRow(posterBox,works,data.series,{ openDetail(it) })
                val host=row().apply { setPadding(pageInset()-dp(TvStyle.FOCUS_BLEED),0,pageInset()-dp(TvStyle.FOCUS_BLEED),0) }
                host.addView(related,lp(-1,-2))
                bottom.addView(host,lp(-1,-2))
                related.post {
                    related.firstCard()?.let { card ->
                        // 按钮行按下键落到相关作品的第一张卡；卡片按上键回到「相关剧集」按钮。
                        lastAction.nextFocusDownId=card.id
                        relatedButton.nextFocusDownId=card.id
                        card.nextFocusUpId=relatedButton.id
                        if(focusFirst) card.requestFocus()
                    }
                }
            }
        } else if(authorOpen) {
            // 作者作品：上面是圆形头像 + 作者名，下面是作者名下全部作品的卡片行。
            // 摆法与「相关剧集」那一栏一致（同一个 [HomeScreen] 卡片行），只是数据来源不同：
            // 相关剧集来自详情页的「相关作品」接口，这里来自按作者名的全站搜索
            //（见 `ContentRepository.authorWorks`）。
            val currentSeries=detail?.series ?: data.series
            val name=currentSeries.author.trim()
            val works=authorWorks
            // 只有「已经为**这个名字**发过请求」才算在加载。名字为空、或者压根没发过请求时
            // 不能停在「正在查找…」上——那是一个永远不会结束的等待（请求根本没发出去）。
            val requested=name.isNotEmpty() && authorFor==name
            val loading=authorLoading || (works==null && !authorFailed && requested)
            // 头像下面那一行小字就是这一栏的状态行：加载中 / 失败 / 只有一部 / 共 N 部，
            // 因此下面**不再另起一行**重复同一句话。
            val hint=when {
                name.isEmpty() -> "没能取到作者信息"
                loading -> "正在查找该作者的作品…"
                authorFailed -> "没能取到该作者的作品"
                (works?.size ?: 0)<=1 -> "这一位暂时只有这一部作品"
                // 顺序就是服务端给的顺序（直连是最热序、搜索兜底是相关度序），
                // 客户端不重排，因此这里只报条数，不再声称某种排序口径。
                else -> "共 ${works?.size ?: 0} 部作品"
            }
            // 三点直接跟在作者名后面（见 [authorHead] 的 `loading`），不再单独占一行。
            bottom.addView(authorHead(name,hint,loading),lp(-1,-2))
            if(loading) {
                // 还没有卡片可落焦：按下键原地不动，别把焦点丢到页面外。
                lastAction.nextFocusDownId=lastAction.id
                relatedButton.nextFocusDownId=relatedButton.id
                if(focusFirst || initial) play.requestFocus()
            } else if(authorFailed) {
                // 失败给一个可重试的入口，而不是静默空列表。
                val retry=row().apply { setPadding(pageInset(),dp(8),pageInset(),dp(18)) }
                addButton(retry,"重试") { prefetchAuthorWorks(data.series.id,data.series) }
                bottom.addView(retry,lp(-1,-2))
                lastAction.nextFocusDownId=lastAction.id
                relatedButton.nextFocusDownId=relatedButton.id
                if(focusFirst || initial) play.requestFocus()
            } else {
                // core 返回的表已按相关度排好：同系列整组在最前、组内按季号升序，
                // 之后是题材逐渐变远的作品。这里只把「正在看的这一部」从它那一组里提到第一张
                //（它的其它季仍然紧跟其后），其余顺序一律不动——界面不再自己排一遍。
                val items=listOf(currentSeries)+works.orEmpty().filter { it.id!=currentSeries.id }
                val shelf=HomeScreen.Shelf("author","全部作品",
                    items.map { item -> HomeScreen.Entry(item,if(item.upcoming) item.notice else "") })
                val authorRow=HomeScreen(this,posterBox,pageInset(),::loadCover,
                    { item,_ -> openDetail(item) },{ item,anchor,shelfView -> quickActions(item,anchor,shelfView) },{},
                    columns=library.homeColumns)
                authorRow.renderCompact(listOf(shelf),"",false)
                val host=row().apply {
                    setPadding(pageInset()-dp(TvStyle.FOCUS_BLEED),0,pageInset()-dp(TvStyle.FOCUS_BLEED),0)
                }
                host.addView(authorRow,lp(-1,-2))
                bottom.addView(host,lp(-1,-2))
                authorRow.post {
                    authorRow.firstCard()?.let { card ->
                        // 按钮行按下键落到作者作品的第一张卡；卡片按上键回到「作者作品」按钮。
                        lastAction.nextFocusDownId=card.id
                        relatedButton.nextFocusDownId=card.id
                        card.nextFocusUpId=authorButton?.id ?: relatedButton.id
                        if(focusFirst) card.requestFocus()
                    }
                }
            }
        } else if(upcoming) {
            // 未上架：**没有选集可摆**。这里给一行说明，按钮行照常可用（预约 / 相关剧集）。
            // 早先没有这一支，空分集会在下面建出一个 0 格的 `EpisodeGrid`，焦点链也随之断掉。
            bottom.addView(text(
                data.series.notice.ifBlank { "尚未上线" } + " · 预约后开播时会提醒你",
                16f,TvStyle.textDim
            ).apply { setPadding(pageInset(),dp(28),pageInset(),dp(18)) },lp(-1,-2))
            lastAction.nextFocusDownId=lastAction.id
            relatedButton.nextFocusDownId=relatedButton.id
            if(focusFirst || initial) play.requestFocus()
        } else {
            // 选集与播放界面共用同一个 EpisodeGrid：这里整幅贴在页面末尾（不加页面栅格内边距），
            // 标题行、集数行、单元格尺寸与播放器弹窗一致，翻页也是同一套四向逻辑。
            // 第一页第一行按上时网格没有上一页可翻，EpisodeGrid.turn() 返回 false 把按键交回焦点系统，
            // 焦点因此仍然能回到上面的「播放 / 收藏 / 相关剧集」这一行（详情页不留页头，上面只有这一行）。
            val grid=EpisodeGrid(this,data.episodes.size,episodeIndex,player=false,
                { index -> playEpisode(
                    index,
                    if(progress?.episodeId==data.episodes[index] && !progress.completed) progress.position else 0,
                    clearDanmakuOnSwitch=true,
                ) },
                pageSize=library.episodePageSize,edgeTurn=library.episodeEdgeTurn)
            episodes=grid
            // 简介页的选集不套背板：它就是页面里的一块内容，底板会读成「一个可以按进去的大盒子」。
            // 播放界面里同一份网格仍然用浮层渐变（播放界面不套新拟态，见 EpisodeGrid 的注释）。
            // 选集整幅贴底，但左右要与页面内容线对齐：网格自己的横向内边距只留给播放界面
            // （那是整幅压屏的浮层），简介页用一个补回焦点余量的外壳把它托到内容线上，
            // 「选集」这一行与第一格于是和上面的海报、按钮同一条竖线。
            val episodeHost=row().apply { setPadding(pageInset()-dp(TvStyle.FOCUS_BLEED),0,pageInset()-dp(TvStyle.FOCUS_BLEED),0) }
            episodeHost.addView(grid,lp(-1,-2))
            bottom.addView(episodeHost,lp(-1,-2))
            // 按钮行按下键先进**分页行**（「1-20 / 21-40 …」），分页行按下进网格第一格；
            // 只有一页时没有分页行，直接进网格。网格第一行按上回到分页行，分页行按上回按钮行。
            val pagerTop=grid.pagerTop(relatedButton)
            val firstCell=grid.firstCell()
            val downTarget=pagerTop ?: firstCell
            lastAction.nextFocusDownId=(downTarget?.id ?: lastAction.id)
            relatedButton.nextFocusDownId=(downTarget?.id ?: relatedButton.id)
            firstCell?.nextFocusUpId=relatedButton.id
            if(focusFirst) grid.focusCurrent() else if(initial) play.requestFocus()
        }
        syncEpisodeRoom()
        bottom.post { syncEpisodeRoom() }
        // 收起相关剧集时网格的 render() 会把焦点抓到当前集上，这里把焦点还给按钮行——
        // 用户按的是「收起相关」，焦点就该还留在这枚按钮上。
        if(!focusFirst && !initial) relatedButton.requestFocus()
    }
    rebuildBottom={ focusFirst -> buildBottom(focusFirst) }
    // 相关剧集结果落地时（见 `prefetchRelated`）就地重铺这一块，不整页重建。
    detailBottomRefresh={ buildBottom(true) }
    buildBottom(focusEpisode)
    // 海报高度 = 信息列高度（标题 / 集数作者行 / 简介 / 按钮行的底边），**收起与展开相关剧集都一样**：
    // 早先收起时按「屏高 − 选集块」铺满，一点「相关剧集」就缩回信息列高度，同一页里海报会跳一下；
    // 现在两种状态共用同一个高度，选集区也不再有「为海报让位」的固定预算（见函数开头的说明）。
    //
    // 宽度跟着高度按 7:10 一起缩：FIT_CENTER 不会拉伸，比例不对时封面两侧会留出空白。
    // 信息列高度要等布局完成才量得准，因此首帧之后回填；信息列自己也会因为海报变宽而变窄、
    // 标题多折一行而变高，所以再挂一个布局回调跟着改——每次只会让海报更高，
    // 高度封在「内容宽 45%」那一档，循环必然收敛。
    var posterFits=0
    var posterFitPending=false
    fun fitPoster() {
        val target=info.height
        if(target<=0) return
        val maxWidth=(contentWidth*0.45f).toInt()
        var height=target
        var width=(height*7f/10f).toInt()+2*posterBand
        if(width>maxWidth) {
            // 简介很长 / 标题折得多时海报先让位，否则右边这一列会被挤到没有宽度。
            height=((maxWidth-2*posterBand).coerceAtLeast(dp(60))*10f/7f).toInt()
            width=(height*7f/10f).toInt()+2*posterBand
        }
        val params=posterBox.layoutParams as LinearLayout.LayoutParams
        if(params.height==height && params.width==width) return
        params.height=height; params.width=width
        posterBox.layoutParams=params
    }
    // 信息列高度变了（海报宽度影响折行、窄屏上按钮行挪到海报下方）就跟着重算，最多几轮。
    // 改尺寸这件事推到下一帧：在 onLayout 里直接 requestLayout 会被框架当成非法重入。
    info.addOnLayoutChangeListener { _,_ ,_,_,_,_,_,_,_ ->
        if(posterFitPending || posterFits>=6) return@addOnLayoutChangeListener
        posterFitPending=true
        hero.post {
            posterFitPending=false
            if(posterFits<6) { posterFits++; fitPoster() }
        }
    }
    hero.post { fitPoster() }
    if(focusTarget!=null) focusTarget.post { focusTarget.requestFocus() }
}
/**
 * 取本集弹幕（原 App 播放时看到的那一份，见 [ContentRepository.danmaku]）。
 *
 * 只有播放画面上的弹幕层会用到它：取不到、这一集没有、平台不再返回，都只是「没有弹幕」，
 * 不弹提示、不影响播放。换集时的「作废在途请求」由 `DanmakuLoader`（`:shared`）的代次负责，
 * 旧剧的弹幕不会接到新一集上。
 */

/**
 * 「预约 / 已预约」：先改本地、再发请求，失败翻回去。
 *
 * 为什么先改：预约是一次**写入**，平台偶尔回 `101144 HIT_SHARK_RULE`（风控短时限流，
 * 见 `ContentRepository.accountReserve`），那一次会自己退避重试；让用户盯着一个没反应的
 * 按钮等几秒，比「先亮起来、失败再翻回去」难受得多。翻回去时给一句提示，状态不会骗人。
 *
 * 未登录时**只记本机**：不发那条注定失败的写请求，按钮状态照常切换（登录后以账号侧为准）。
 *
 * [current] 是**按钮上正显示着的那个状态**，由调用方传进来：切换要基于「用户看到的」，
 * 而不是再读一次本机表——本机还没有记录（首次进页面）时 `library.reserved` 是 false，
 * 拿它去取反会把「已预约」又发一次预约请求。
 */
fun TvMainActivity.reserveSeries(series: Series, button: TvButton, current: Boolean = library.reserved(series.id)) {
    val target=!current
    fun paint(reserved: Boolean) {
        button.text=if(reserved) "已预约" else "预约"
        button.contentDescription=button.text.toString()
    }
    // **先落本机**：预约是一份「开播提醒我」的清单，没登录也该记得住——按钮状态跟着变，
    // 退出重进这一页还在。未登录时只写本机、不发那条注定失败的写请求。
    library.setReserved(series.id,target); paint(target)
    applyAccountReserve(series,target)
    if(!repository.signedIn) {
        tvTools.toast(if(target) "已预约 · 仅保存在本机" else "已取消预约")
        return
    }
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
