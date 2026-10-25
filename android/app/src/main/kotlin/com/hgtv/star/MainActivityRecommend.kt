// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.google.android.exoplayer2.Player
import com.hgtv.star.compat.isMeteredConnection
import com.hgtv.star.core.CategorySelection
import com.hgtv.star.core.ContentRepository
import com.hgtv.star.core.Detail
import com.hgtv.star.core.RemoteVideo
import com.hgtv.star.core.Series
import com.hgtv.star.tv.ButtonSize
import com.hgtv.star.tv.ButtonTone
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.list.showCatalog

// 本文件是「推荐」这一条独立的播放链路：抖音式的上下换剧、确定进正片、菜单开播放设置、
// 返回回主页面。它复用播放内核（playEpisode / 播放器 / 弹幕 / 播放设置菜单），
// 只在四件事上与正式播放不同：
//
// 1. [MainActivity.screen] 是 `recommend` 而不是 `player`——返回键回主页面、上下键换剧；
// 2. **不写观看历史**（见 `saveProgress` 的守卫）：只有进入正式播放那一刻才写；
// 3. 不挂「下一集」队列（见 `maybePrefetch`）：一集播完直接进正片，由正片接着播下一集；
// 4. 没有可聚焦控件：所有方向键都由 `dispatchKeyEvent` 消化，焦点不会跑出画面。

/** 推荐流一次取多少（分类接口的「全部推荐」流第一页就是 20 条，之后每页 100 条）。 */
internal const val RECOMMEND_SKIP_LIMIT=4
/**
 * 推荐流只取**漫剧**这一条体裁（接口的 `genre`）。
 *
 * 不带 `genre` 时接口回的是「全部体裁」混排：真人短剧 / AI 剧会一起进来，
 * 推荐页播的就不是用户想看的漫剧。带上之后仍然是同一条「全部推荐」流
 *（不传 `sort`），只是把体裁钉在漫剧上，见 `ContentRepository.categoryApi`。
 */
internal const val RECOMMEND_GENRE="comic_series"
/** 切换过渡：黑场淡入（把上一部收住）/ 新画面淡出（把这一部放出来）/ 最长遮挡。 */
private const val COVER_IN_MS=120L
private const val COVER_OUT_MS=260L
private const val COVER_MAX_MS=2500L

/**
 * 打开推荐页并自动播放一部推荐。
 *
 * 从任何页面都能进（导航栏里紧挨搜索按钮的那枚圆形「推荐」入口、冷启动）。当前已经在推荐页、
 * 而且列表已经到手时什么都不做——重复按入口不该把正在看的这一部换掉。
 *
 * 每次进来都取**新的一批**：从本机落盘的续流游标（`session_id` + `next_offset`）接着往下取，
 * 所以退出再进、甚至杀进程重开，看到的都是新的推荐，而不是同一套列表。
 */
internal fun MainActivity.openRecommend() {
    // 冷启动那一次只消费一次：即使下面早退（已经在推荐页），也不能留着这个标记，
    // 否则下次回到前台又会被它拽进推荐页。
    initialCatalogPending=false
    // 已经在推荐页：列表非空（或请求在途）就什么都不做——重复按入口不该把正在看的这一部换掉。
    // 例外是「列表空、也没有请求在途」（上一次取推荐失败了，整屏只有一行提示）：
    // 这时按入口 / 按确定都应该是「重试」，不能被这一条早退挡住。
    if(screen=="recommend" && (recommendItems.isNotEmpty() || recommendLoading)) return
    // 每次进推荐页都从**本机记下的游标**接着往下取一页新的（见 [recommendSession] / [recommendNext]）：
    // 推荐流是有状态的，`session_id` 传空 + `offset=0` 会让服务端永远回同一套默认列表。
    // 同一次运行里的上下切换仍然是一条连续的流（不会边刷边重拉）。
    detail=null
    invalidatePage()
    detachPlayerSurface()
    releaseCatalogViews()
    tvTools.close()
    feedbackOpen=false
    collection=null
    directPlayback=false
    initialCatalogPending=false
    restoreStoppedPage=false
    playbackFromRecommend=false
    recommendHistoryPending=false; recommendPlaybackFailures=0
    recommendToken.incrementAndGet(); recommendPageToken.incrementAndGet()
    screen="recommend"
    recommendItems=emptyList(); recommendIndex=0; recommendPage=0; recommendHasMore=true
    recommendLoading=false; recommendDetails.clear(); closeRecommendCache()
    // 起点来自落盘的游标（`session_id|next_offset`）：上次刷到哪儿，这次就从哪儿往下取。
    val cursor=library.recommendCursor
    recommendSession=cursor.substringBefore('|')
    recommendNext=cursor.substringAfter('|',"0").toIntOrNull()?.coerceAtLeast(0) ?: 0
    showRecommendMessage("正在加载推荐…")
    loadRecommendPage(append=false) { playRecommendAt(0) }
}

/**
 * 丢掉所有预热好的相邻剧集（关闭画面、退出应用、内存吃紧时）。
 *
 * 推荐列表每次进页面都会重新取一页，这几份预热只在当前这一屏有意义；一旦离开推荐页
 * 就不再需要，留着只会白占内存（每部约 1MB）。
 */
internal fun MainActivity.closeRecommendCache() {
    recommendWarmToken.incrementAndGet()
    recommendWarmJob?.cancel(true); recommendWarmJob=null; recommendWarming=""
    recommendPrepared.values.forEach { runCatching { it.close() } }
    recommendPrepared.clear()
    recommendDetailLoading.clear()
}

/**
 * 取一页推荐流。
 *
 * [append] 为假是首次进入（重置列表），为真在末尾接一页（上下键走到最后一部时补货）。
 * 结果落地前会核对令牌与当前页面：中途退出 / 重新进入，旧结果直接丢掉。
 */
internal fun MainActivity.loadRecommendPage(append: Boolean,fromStart: Boolean=false,onReady: () -> Unit) {
    // 这几种情况都**发不出请求**：直接回调，让调用方按手里的列表继续（环绕 / 给提示），
    // 否则上下键走到末尾又恰好碰上补页，就会「按了没反应」。
    if(recommendLoading) { onReady(); return }
    if(append && !recommendHasMore) { onReady(); return }
    val page=if(append) recommendPage+1 else 1
    if(page>10) { onReady(); return }
    recommendLoading=true
    val token=recommendPageToken.incrementAndGet()
    // 请求从游标位置开始（首次进入 = 落盘游标，补页 = 上一页的 `next_offset`）。
    val session=recommendSession
    val offset=recommendNext
    io.submit {
        // 分类 / 推荐接口要签名，签名里的设备身份必须是**平台注册过**的那一份
        // （与播放取地址、搜索同一道门槛）。注册在途时 registerDeviceOnce 会在锁上等，
        // 不会重复发请求；冷启动第一次进推荐因此不会因为「还没注册」而空手回来。
        if(!library.deviceRegistered()) registerDeviceOnce()
        val result=runCatching {
            repository.recommendFeed(CategorySelection(genre=RECOMMEND_GENRE),session,offset)
        }
        main.post {
            if(isDestroyed || token!=recommendPageToken.get() || screen!="recommend") return@post
            recommendLoading=false
            result.onSuccess { feed ->
                // 只留横屏：接口没有「只要横屏」的筛选参数（见 [Series.vertical] 的说明），
                // 漫剧这条流里仍会混进少量竖屏短剧，客户端在这里按 `vertical` 筛掉。
                val fresh=feed.page.items.filterNot { it.id in library.hidden() }.filterNot { it.vertical }
                // 游标前进并立刻落盘：下次进推荐（哪怕换了进程）都接着这里往下取，
                // 而不是回到 `offset=0` 那套永远相同的默认列表。
                recommendSession=feed.sessionId
                recommendNext=feed.nextOffset
                library.recommendCursor=feed.sessionId+"|"+feed.nextOffset
                recommendItems=if(append) (recommendItems+fresh).distinctBy { it.id } else fresh
                recommendPage=page; recommendHasMore=feed.page.hasMore
                // 这一页筛完一条不剩（整页都是竖屏）而且后面还有：接着取下一页，
                // 不能让用户看到「推荐暂时取不到」——那只是这一页没货，不是流断了。
                if(fresh.isEmpty() && feed.page.hasMore && page<10) {
                    loadRecommendPage(append,fromStart,onReady)
                    return@onSuccess
                }
                // 游标已经走到流尾、这一页又没货：从流头重来一次（只重来一次，避免死循环）。
                if(recommendItems.isEmpty() && !fromStart && offset>0) {
                    recommendSession=""; recommendNext=0; library.recommendCursor=""
                    loadRecommendPage(false,true,onReady)
                    return@onSuccess
                }
                if(recommendItems.isEmpty()) {
                    showRecommendMessage("推荐暂时取不到\n按确定键重试，按返回键回主页面")
                    return@onSuccess
                }
                // 已经退到后台（[onStop] 里 restoreStoppedPage 已置位）：列表留着，
                // 但不在后台起播——回到前台由 onResume 接着播，免得用户切走之后声音还在响。
                if(!foreground) return@onSuccess
                onReady()
            }.onFailure { problem ->
                android.util.Log.w("HGTV","Recommend feed failed page=$page",problem)
                if(recommendItems.isEmpty()) showRecommendMessage("推荐暂时取不到\n按确定键重试，按返回键回主页面")
                else { showRecommendToast("后面的推荐暂时取不到"); onReady() }
            }
        }
    }
}

/** 整屏一行说明（首次进入 / 取不到推荐时用；此时还没有播放画面）。 */
internal fun MainActivity.showRecommendMessage(message: String) {
    // 走到「整屏只有一行字」就说明这一屏没有画面：把播放器那一层收掉再清 root，
    // 否则换剧 / 补页失败时会留下「画面没了、声音还在放」。`screen` 不动（仍是 recommend），
    // 因此返回键照样回主页面、也不会写历史。
    dismissRecommendOverlay()
    dismissRecommendCover()
    if(playerView!=null) detachPlayerSurface()
    root.removeAllViews(); root.setBackgroundColor(Color.BLACK)
    val label=text(message,20f,TvStyle.playerText).apply {
        gravity=Gravity.CENTER
        setLineSpacing(dp(6).toFloat(),1f)
    }
    root.addView(label,FrameLayout.LayoutParams(-1,-1))
    // 整棵树被换掉了：调试模式下面板照旧重挂一次（见 `DebugOverlay.kt`）。
    reattachDebugOverlay()
}

/** 播放画面上的一行提示（已经有画面时用，不打断正在播的这一部）。 */
internal fun MainActivity.showRecommendToast(message: String) {
    recommendNotice=message
    recommendNoticeAt=android.os.SystemClock.elapsedRealtime()
    updateRecommendOverlay()
}

/**
 * 播放推荐列表里的第 [index] 部。
 *
 * 详情有缓存就直接起播；没有就先整屏 / 浮层显示「正在加载《剧名》…」，取到再起播。
 * 取不到时自动跳到下一部（最多连跳 [RECOMMEND_SKIP_LIMIT] 部），不把用户卡在一屏错误上。
 */
internal fun MainActivity.playRecommendAt(index: Int,position: Long=0L,direction: Int=0,skipped: Int=0,autoplay: Boolean=true) {
    if(screen!="recommend" || recommendItems.isEmpty()) return
    val safe=index.coerceIn(0,recommendItems.lastIndex)
    recommendIndex=safe; recommendShift=direction
    val series=recommendItems[safe]
    // 每一次「切到某一部」都换一次令牌：上一次那部还在取详情时，它的结果回来就作废。
    // （缓存命中这条早退路径也必须换，否则上一次在途的结果会把刚切过来的这一部顶掉。）
    val token=recommendToken.incrementAndGet()
    val cached=recommendDetails[series.id]
    if(cached!=null) { startRecommendEpisode(cached,position,autoplay); return }
    if(playerView==null) showRecommendMessage("正在加载《${series.title}》…")
    else {
        // 换剧那一下先把上一部停住：详情要几百毫秒才回来，这段时间里画面与声音
        // 若还是上一部，用户读到的就是「按了下键没换台」。
        player?.pause()
        recommendTitle?.text="正在加载《${series.title}》…"
        recommendMeta?.text=""
    }
    io.submit {
        val result=runCatching { repository.detail(series.id) }
        main.post {
            if(isDestroyed || token!=recommendToken.get() || screen!="recommend") return@post
            result.onSuccess { data ->
                if(data.episodes.isEmpty()) {
                    // 预告 / 未上架：没有分集可播，直接跳过（推荐流里偶尔会混进这种）。
                    skipRecommend(series,safe,direction,skipped)
                    return@post
                }
                recommendDetails[series.id]=data
                // 同上：后台只把详情收进缓存，起播留给回到前台那一次。
                if(!foreground) return@post
                startRecommendEpisode(data,position,autoplay)
            }.onFailure { problem ->
                android.util.Log.w("HGTV","Recommend detail failed id=${series.id}",problem)
                skipRecommend(series,safe,direction,skipped)
            }
        }
    }
}

/** 这一部放不了：跳到下一部；连跳太多次就停在一句提示上，不无限空转。 */
private fun MainActivity.skipRecommend(series: Series,index: Int,direction: Int,skipped: Int) {
    if(skipped>=RECOMMEND_SKIP_LIMIT) {
        showRecommendMessage("《${series.title}》暂时无法播放\n按确定键重试，按返回键回主页面")
        return
    }
    val next=index+1
    when {
        next in recommendItems.indices -> playRecommendAt(next,0L,1,skipped+1)
        // 末尾还没补过页：先补一页再往下走。补页发不出去 / 失败时回调会立刻到，
        // 那时列表没变长，就环绕回第一部，别让用户停在一屏「放不了」上。
        recommendHasMore -> {
            // 补页回调可能来得比用户下一手还晚：起播令牌变了（已经换了另一部 / 进了正片）
            // 就说明这一下补页的意图已经过时，直接放弃，别再把人拽回列表开头。
            val guard=recommendToken.get()
            loadRecommendPage(true) {
                if(recommendToken.get()!=guard) return@loadRecommendPage
                val target=index+1
                if(target in recommendItems.indices) playRecommendAt(target,0L,1,skipped+1)
                else if(recommendItems.isNotEmpty()) playRecommendAt(0,0L,1,skipped+1)
                else showRecommendMessage("暂时没有可播放的推荐\n按确定键重试，按返回键回主页面")
            }
        }
        recommendItems.isNotEmpty() -> playRecommendAt(0,0L,1,skipped+1)
        else -> showRecommendMessage("暂时没有可播放的推荐\n按确定键重试，按返回键回主页面")
    }
}

/**
 * 真正起播一集。
 *
 * 从第 1 集开始（推荐是「随便看看」，不从上次的进度续）；
 * `playEpisode` 内部会按进入时的 `screen` 决定留在 `recommend`（见那里的 `recommendEntry`），
 * 因此这里不需要再改回来。
 */
private fun MainActivity.startRecommendEpisode(data: Detail,position: Long,autoplay: Boolean=true) {
    detail=data
    recommendHistoryPending=false
    // 换到另一部：上一次「画面丢了自动重建」的额度重新给满（见 [recoverRecommendPicture]）。
    // 不清的话，看过一部要重建两回的片子之后，后面每一部都只剩一次机会。
    pictureRecoveries=0; pictureRecoveryAt=0L
    // 相邻那一部的首集如果已经预热好，直接把它交给播放内核：不用再取地址、读索引、
    // 拉首批数据，切换就是「换一帧」而不是「等一次网络」。取走之后所有权归播放器。
    val key=preparedKey(data)
    val fromStart=position<=0L
    // 这一部正好在预热、而且这次要从头起播：取消那次预热，由下面 `playEpisode` 自己取
    //（否则同一集会被拉两遍，预热结果回来时也已经没人要了）。
    // 从后台回来按原位置续播时不动它：那一份仍然有用。
    if(fromStart && key.isNotEmpty() && recommendWarming==key) {
        recommendWarmToken.incrementAndGet()
        recommendWarmJob?.cancel(true); recommendWarmJob=null; recommendWarming=""
    }
    val prepared=if(fromStart) recommendPrepared.remove(key) else null
    // 连续失败计数**不在这里**归零：起播（取地址成功）不等于能放，
    // 真正出画面之后才清零（见播放监听器的 READY 分支），否则整条推荐都坏掉时
    // 计数永远只有 1，会一直往下换、停不下来。
    // 过渡黑场必须先**完全不透明**再换源：命中预热缓存时换源是瞬时的，
    // 淡入还没走完就 reset 媒体，旧画面会带着半透明露出来（正是那一下拉伸）。
    recommendCover?.let { cover -> cover.animate().cancel(); cover.alpha=1f }
    // 从后台回来那一次不自动播：画面停在那一帧，浮层上写「已暂停」，按播放键继续。
    playEpisode(0,position.coerceAtLeast(0),autoplay=autoplay,prepared=prepared)
    screen="recommend"
    hud.visibility=View.GONE
    playbackTopBar?.visibility=View.GONE
    // 底部浮层由 [playEpisode] 收尾统一挂（重建整棵树与复用播放器两条路都会走到那里，
    // 它按新的 `detail.series` 重挂，并带上这一下的滑动方向动画）；这里只把数值刷上去。
    updateRecommendOverlay()
    // 这一部已经在放了：把上下相邻的两部预热起来（详情 + 首集数据），
    // 用户按上下键时就不用再等网络。放在起播之后，不和当前这一部抢带宽。
    prefetchRecommendNeighbors(recommendIndex)
}

/** 预热用的缓存键：`集id:清晰度`。换清晰度之后旧的那份自然对不上，会被丢掉。 */
internal fun MainActivity.preparedKey(data: Detail): String {
    val id=data.episodes.firstOrNull() ?: return ""
    return "$id:${library.maxQuality}"
}

/**
 * 把推荐列表里第 [index] 部上下相邻的两部预热起来。
 *
 * 分两步，都是为了「按上下键立刻换台」：
 * 1. 详情（分集表）：一部几百毫秒，先取回来放进 [recommendDetails]；
 * 2. 首集数据：详情到手后取播放地址并把索引 / 前 1MB 拉下来（[RemoteVideo.warm]），
 *    放进 [recommendPrepared]，起播时直接交给播放器。
 *
 * 同一时刻只预热一部（[recommendWarming]）：两条流同时拉会互相抢带宽，
 * 结果反而是两部都慢。下一部优先（用户往下刷的概率远高于往回）。
 */
internal fun MainActivity.prefetchRecommendNeighbors(index: Int) {
    if(screen!="recommend") return
    listOf(index+1,index-1).forEach { i ->
        val series=recommendItems.getOrNull(i) ?: return@forEach
        val cached=recommendDetails[series.id]
        if(cached!=null) { warmRecommendDetail(cached); return@forEach }
        if(series.id in recommendDetailLoading) return@forEach
        recommendDetailLoading+=series.id
        io.submit {
            val result=runCatching { repository.detail(series.id) }
            main.post {
                recommendDetailLoading-=series.id
                // 预热**不认起播令牌**：它本来就是为「下一次切换」准备的，中途换剧不该把
                // 已经取回来的详情丢掉。只认「还在推荐页」这一条。
                if(isDestroyed || screen!="recommend") return@post
                result.onSuccess { data ->
                    if(data.episodes.isEmpty()) return@onSuccess
                    recommendDetails[series.id]=data
                    // 只有它仍然挨着当前这一部时才预热：用户可能已经连着换了好几部。
                    val position=recommendItems.indexOfFirst { it.id==series.id }
                    if(position>=0 && position-recommendIndex in -1..1) {
                        warmRecommendDetail(data)
                    }
                }
            }
        }
    }
}

/** 取首集地址并预热（索引 + 前 1MB）。已经有同一份、或正在预热同一份时直接跳过。 */
internal fun MainActivity.warmRecommendDetail(data: Detail) {
    if(screen!="recommend") return
    val key=preparedKey(data)
    if(key.isEmpty()) return
    if(recommendPrepared.containsKey(key) || recommendWarming==key) return
    if(recommendWarming.isNotEmpty()) return
    if(connectivity.isMeteredConnection() || !networkAvailable()) return
    recommendWarming=key
    val token=recommendWarmToken.incrementAndGet()
    val quality=library.maxQuality
    val http=com.hgtv.star.data.PlaybackTransport.client
    val source=ContentRepository(http)
    source.credentials=repository.credentials
    source.inheritDevice(repository)
    source.deviceLevel=repository.deviceLevel
    recommendWarmJob=prefetchWorker.submit {
        var remote: RemoteVideo?=null
        try {
            if(!library.deviceRegistered()) { registerDeviceOnce(); source.inheritDevice(repository) }
            val prepared=RemoteVideo(http,source.stream(data.episodes.first(),quality)).also { remote=it; it.warm() }
            main.post {
                // 这一次预热已经被取消 / 换了目标：结果没人要，当场关掉（那 1MB 与它的连接）。
                if(token!=recommendWarmToken.get()) { runCatching { prepared.close() }; return@post }
                if(recommendWarming==key) recommendWarming=""
                if(isDestroyed || screen!="recommend") { runCatching { prepared.close() }; return@post }
                recommendPrepared[key]=prepared
                // 只留最近 3 部（约 3MB）：再多也没有意义，用户回不到那么远。
                while(recommendPrepared.size>3) {
                    val eldest=recommendPrepared.entries.first()
                    recommendPrepared.remove(eldest.key)
                    runCatching { eldest.value.close() }
                }
                // 另一侧的邻居刚才可能因为「同一时刻只预热一部」被跳过：这里补上。
                prefetchRecommendNeighbors(recommendIndex)
            }
        } catch(_: Exception) {
            remote?.close()
            main.post { if(token==recommendWarmToken.get() && recommendWarming==key) recommendWarming="" }
        }
    }
}

/**
 * 换剧那一下把画面盖黑（需求：上下切换要有过渡，且不能看到拉伸帧）。
 *
 * 时机是**按键那一刻**，不是新源准备好之后：先把上一部淡出成黑，再换源、等新源第一帧，
 * 由 [revealRecommendCover] 淡回来。这样整段过渡只有「暗 → 亮」，中间不会出现
 * 「上一部的画面被新比例拉一下」那一帧。
 *
 * 这一层是普通 View（画在窗口层里），不是 SurfaceView，因此它的 alpha 动画在电视盒子
 * 上也一定生效；盖住期间不参与焦点、不拦截按键（推荐页的按键本来都由 `dispatchKeyEvent` 消化）。
 */
internal fun MainActivity.showRecommendCover() {
    if(screen!="recommend") return
    val token=++recommendCoverToken
    recommendCoverReveal?.let { main.removeCallbacks(it) }
    recommendCoverReveal=null
    val existing=recommendCover
    if(existing!=null) {
        // 已经盖着（连着换剧 / 上一部还在取详情）：保持黑，只取消上一次排好的淡出。
        // 画面层可能在两次换剧之间被重建过（整棵树换掉），这时把这一层重新挂回去。
        if(existing.parent==null) attachRecommendCoverView(existing)
        existing.animate().cancel()
        existing.alpha=1f
    } else {
        val cover=View(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable=false; isClickable=false
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            alpha=0f
        }
        attachRecommendCoverView(cover)
        recommendCover=cover
        if(TvStyle.animate) cover.animate().alpha(1f).setDuration(COVER_IN_MS).start() else cover.alpha=1f
    }
    recommendCoverShownAt=android.os.SystemClock.elapsedRealtime()
    // 兜底：新源一直没出帧（网络慢 / 这一部放不了）时也不能永远挡在黑屏后面。
    val reveal=Runnable { if(token==recommendCoverToken) revealRecommendCover() }
    recommendCoverReveal=reveal
    main.postDelayed(reveal,COVER_MAX_MS)
}

/**
 * 推荐页「有声音、没画面」的自愈：就地重建播放层。
 *
 * 触发点是两条看门狗（见 `PlaybackDiagnostics.kt` 的 `armPlaybackWatchdog` /
 * `armFrameStallWatchdog`）：起播后迟迟不出帧，或播着播着不再出帧。这两条路**都不抛异常**，
 * [playerFailure] 收不到，界面上也不会挂出错误页，用户看到的就是「黑屏、有声音、
 * 快进快退都不好使」——拖动进度条同样要解码器吐帧，它已经不吐了，所以拖也没用。
 *
 * 修法与用户自己试出来的完全一致：进正片、换一集，画面就回来了。那一步的差别不是换了片源，
 * 而是**整棵树重建**——换掉 `PlayerView` / Surface、放掉 MediaCodec 再重建
 *（[playEpisode] 的 `recovering=true` 正是走这条路）。因此这里在原位做同一件事：
 * 不跳剧、不写历史、不改 `screen`，从当前位置续播。
 *
 * 不无限重建：同一部片最多 [PICTURE_RECOVERY_LIMIT] 次、两次之间至少隔
 * [PICTURE_RECOVERY_COOLDOWN_MS]。真的解不出画面的片源，重建几次都是黑的，
 * 那种情形应当交给「换下一部」，而不是每 12 秒闪一下。
 */
private const val PICTURE_RECOVERY_LIMIT=2
private const val PICTURE_RECOVERY_COOLDOWN_MS=20_000L

internal fun MainActivity.recoverRecommendPicture(reason: String) {
    // 只在推荐页动手：正式播放页有自己的错误界面与「重试 / 换清晰度」，不该被静默重建。
    if(screen!="recommend" || !foreground || playError) return
    val data=detail ?: return
    if(episodeIndex !in data.episodes.indices) return
    val p=player ?: return
    val now=android.os.SystemClock.elapsedRealtime()
    if(pictureRecoveries>=PICTURE_RECOVERY_LIMIT || (pictureRecoveryAt!=0L && now-pictureRecoveryAt<PICTURE_RECOVERY_COOLDOWN_MS)) return
    pictureRecoveryAt=now; pictureRecoveries++
    val position=currentPosition()
    val autoplay=p.playWhenReady
    android.util.Log.w("HGTV","Recommend picture lost ($reason) episode=${episodeIndex+1} at=${position}ms try=$pictureRecoveries")
    // 下面这一下会把画面层整个换掉：先盖黑，别让旧画面按新比例被拉一下再消失。
    showRecommendCover()
    playEpisode(episodeIndex,position,autoplay,recovering=true)
}

/**
 * 新一集的**第一帧已经上屏**：把黑场淡出，露出画面。
 *
 * 由播放监听器的 `onRenderedFirstFrame` 调用（见 MainActivityPlayer），因此「淡出」与
 * 「真的能看见画面」是同一时刻，不会出现「黑场淡完还是黑的、再等半秒才出画面」。
 */
internal fun MainActivity.revealRecommendCover() {
    val cover=recommendCover ?: return
    recommendCoverReveal?.let { main.removeCallbacks(it) }
    recommendCoverReveal=null
    recommendCoverToken++
    cover.animate().cancel()
    if(!TvStyle.animate) { dismissRecommendCover(); return }
    cover.animate().alpha(0f).setDuration(COVER_OUT_MS).withEndAction {
        if(recommendCover===cover) dismissRecommendCover()
    }.start()
}

/**
 * 把过渡黑场挂到当前这棵树里：插在底部信息浮层**下面**。
 *
 * 因此过渡期间剧名 / 集数仍然看得见，只是画面在黑场里换；浮层自身不受影响。
 */
private fun MainActivity.attachRecommendCoverView(cover: View) {
    (cover.parent as? android.view.ViewGroup)?.removeView(cover)
    val overlay=recommendOverlay
    val index=if(overlay!=null && overlay.parent===root) root.indexOfChild(overlay) else root.childCount
    root.addView(cover,index.coerceIn(0,root.childCount),FrameLayout.LayoutParams(-1,-1))
}

/**
 * 画面层被重建之后（`playEpisode` 整棵树换掉）把黑场补回来。
 *
 * 换剧走的是「复用播放器」那条路，通常不会重建；但换清晰度 / 换解码方式 / 出错重试会。
 * 少了这一步，过渡中途重建就会露出正在被拉伸的旧画面。
 */
internal fun MainActivity.reattachRecommendCover() {
    val cover=recommendCover ?: return
    if(cover.parent!=null) return
    attachRecommendCoverView(cover)
    cover.animate().cancel(); cover.alpha=1f
}

/** 立刻收掉过渡黑场（离开推荐页、切到整屏提示、重建画面时用）。 */
internal fun MainActivity.dismissRecommendCover() {
    recommendCoverToken++
    recommendCoverReveal?.let { main.removeCallbacks(it) }
    recommendCoverReveal=null
    recommendCover?.let { cover ->
        cover.animate().cancel()
        (cover.parent as? android.view.ViewGroup)?.removeView(cover)
    }
    recommendCover=null
}

/**
 * 推荐页进度条的拖动：拖动期间只改显示，抬手才真跳一次。
 *
 * 为什么不在 ACTION_MOVE 里逐帧 `seekTo`：与正式播放同一条理由（见 [onScrubProgress]）——
 * 连续几十次 seek 会让 ExoPlayer 反复 reset 渲染管线，画面直接卡死。
 *
 * 抬手这一下同时清掉遥控器「左右键快进退」的合并窗（`seekRunnable` / `pendingSeek`）：
 * 两条路径同时挂着一个待跳位置时，最后落地的那个会是用户没按过的那一个。
 */
internal fun MainActivity.onRecommendScrub(fraction: Float,finished: Boolean) {
    val p=player ?: return
    val duration=p.duration
    if(duration<=0 || !playbackReady) return
    val target=(duration*fraction).toLong().coerceIn(0,(duration-500).coerceAtLeast(0))
    recommendScrubbing=!finished
    recommendProgress?.progress=fraction
    recommendSpan?.let { line ->
        val text="${formatTime(target)} / ${formatTime(duration)}"
        if(line.text.toString()!=text) line.text=text
    }
    if(!finished) return
    main.removeCallbacks(seekRunnable); pendingSeek=null
    p.seekTo(target)
    updateRecommendOverlay()
}

/** 推荐页底部那块信息浮层：进度 + 剧名 + 集数 + 操作说明。 */
internal fun MainActivity.attachRecommendOverlay(series: Series) {
    dismissRecommendOverlay()
    val bar=column().apply {
        setPadding(dp(78),dp(28),dp(78),dp(56))
        background=TvStyle.gradient(Color.TRANSPARENT,0xCC000000.toInt())
        clipChildren=false
    }
    // 进度那一行：与正式播放的底部控制条同一套——`SeekIndicator` + 右侧 `00:04 / 01:08`，
    // 连左右内边距（右侧 26、时间不占权重）都一样，两页看上去是同一条。
    // 电视上这一行也在：这不是触屏专属的东西，遥控器快进同样要知道播到哪儿。
    // 与集数那一行之间留一格：集数在上、进度在下，两行读起来是一组读数。
    val transport=row().apply { setPadding(0,dp(12),0,0) }
    val progress=com.hgtv.star.tv.SeekIndicator(this).apply {
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable=false
        progress=0f
    }
    transport.addView(progress,lp(0,dp(43)).apply { weight=1f; rightMargin=dp(26) })
    // 与正式播放一样可拖：拖动期间只改显示，抬手才真跳（见 [onRecommendScrub]）。
    progress.onScrub={ fraction,finished -> onRecommendScrub(fraction,finished) }
    val span=text("",18f,TvStyle.playerText).apply { setShadowLayer(4f,0f,1f,0x80000000.toInt()) }
    transport.addView(span,lp(-2,-2))
    recommendProgress=progress; recommendSpan=span

    val title=text(series.title,32f,TvStyle.playerText).apply {
        setTypeface(typeface,Typeface.BOLD)
        maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END
        setShadowLayer(6f,0f,2f,0x99000000.toInt())
    }
    val meta=text("",18f,TvStyle.playerDim).apply {
        setPadding(0,dp(8),0,0)
        setShadowLayer(5f,0f,2f,0x99000000.toInt())
    }
    val hint=text("确定 追剧 · 上下 换剧 · 左右 快进退 · 菜单 播放设置 · 返回 主页面",16f,TvStyle.playerDim).apply {
        setPadding(0,dp(14),0,0)
        setShadowLayer(5f,0f,2f,0x99000000.toInt())
    }
    // 自上而下的阅读顺序：剧名 → 看到第几集 → 播到哪儿 → 怎么操作。
    // 进度条夹在「第 x 集」与「提示」之间：它属于上一行的读数，不该跑到剧名上面去，
    // 也不该压在操作提示下面（那里是「怎么按」，不是「播到哪儿」）。
    bar.addView(title,lp(-1,-2))
    bar.addView(meta,lp(-1,-2))
    bar.addView(transport,lp(-1,-2))
    bar.addView(hint,lp(-1,-2))
    // 触屏设备：遥控器那行提示用不上，换成手势说明，并把「看正片 / 暂停 / 换一部」
    // 三件事做成按钮——这一页没有控制条，手指除了轻点看浮层之外本来什么都做不了。
    // 电视上 `touchArmed` 恒为 false，这一整块不存在，浮层高度与今天完全一致。
    if(touchArmed) {
        // 这一排显示的时候，遥控器左右不是快进退而是「选按钮」（见 `dispatchKeyEvent`），
        // 因此提示行要把两套操作都说清楚：手上用点、遥控器用键，谁也不猜。
        hint.text="轻点 显隐 · 上下 换剧 · 确定 追剧 · 菜单 播放设置 · 左右 选按钮 · 返回 收起"
        val actions=row().apply { setPadding(0,dp(18),0,0) }
        addButton(actions,"看正片",tone=ButtonTone.PRIMARY,size=ButtonSize.COMPACT,weight=1f,icon=R.drawable.ic_player_play) { enterFormalPlayback() }
        addButton(actions,"暂停",size=ButtonSize.COMPACT,weight=1f,icon=R.drawable.ic_player_pause) { togglePlayback() }
        addButton(actions,"换一部",size=ButtonSize.COMPACT,weight=1f,icon=R.drawable.ic_player_forward) { switchRecommend(1) }
        bar.addView(actions,lp(-1,-2))
        recommendActions=actions
    }
    recommendTitle=title; recommendMeta=meta; recommendOverlay=bar
    // 新挂的浮层默认是显示的：收起状态只属于上一块浮层，换剧 / 进页面都重新露出来。
    recommendBarHidden=false
    // 播放设置开着时浮层让位（菜单贴底占了下三分之一），收起菜单时再露出来。
    bar.visibility=if(playbackMenu!=null) View.GONE else View.VISIBLE
    root.addView(bar,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
    // 首帧就把内容填满：集数、时间、进度都在这一刻算出来，不等下一次每秒 tick。
    // 上一版这里是空的——刚进推荐页时浮层比轻点唤出来的那一次「少一行字」，
    // 两条路看上去像两块不同的东西（用户反馈的就是这一条）。
    updateRecommendOverlay()
    // 换剧时从切换方向滑进来一点：上下键那一下读起来才是「换了一部」，而不是文字原地跳变。
    // 这个位移只服务「换剧」这一下，用完即清零：否则下一次进推荐页（不是换剧）
    // 也会莫名其妙地再滑一次，与轻点唤出来的那一块又不一样了。
    val shift=recommendShift
    recommendShift=0
    if(TvStyle.animate && shift!=0) {
        bar.translationY=dp(24).toFloat()*shift
        bar.alpha=0f
        bar.animate().translationY(0f).alpha(1f).setDuration(TvStyle.DURATION).start()
    }
}

internal fun MainActivity.dismissRecommendOverlay() {
    recommendOverlay?.let { overlay -> (overlay.parent as? android.view.ViewGroup)?.removeView(overlay) }
    recommendOverlay=null; recommendTitle=null; recommendMeta=null
    recommendActions=null; recommendBarHidden=false
    // 进度条是浮层的子控件，随浮层一起消失；引用一并清掉，免得下一帧刷到已摘下的控件。
    recommendProgress=null; recommendSpan=null
}

/** 刷新浮层上的集数 / 进度那一行。 */
internal fun MainActivity.updateRecommendOverlay() {
    val data=detail ?: return
    if(screen!="recommend") return
    val p=player
    // 手指正在拖：这一秒的刷新不能把进度条与时间顶回播放器报的位置（同正式播放的 `scrubbing`）。
    if(recommendScrubbing) return
    // 进度与时间先刷：与正式播放同一套读数（拖动合并窗里显示目标位置）。
    val duration=p?.duration?.coerceAtLeast(0) ?: 0L
    val position=pendingSeek ?: p?.currentPosition ?: 0L
    recommendProgress?.progress=if(duration>0) position.toFloat()/duration else 0f
    recommendSpan?.let { line ->
        val text=if(duration>0) "${formatTime(position)} / ${formatTime(duration)}" else "--:-- / --:--"
        if(line.text.toString()!=text) line.text=text
    }
    val meta=recommendMeta ?: return
    val now=android.os.SystemClock.elapsedRealtime()
    val notice=if(recommendNotice.isNotEmpty() && now-recommendNoticeAt<3000) recommendNotice else ""
    val text=if(notice.isNotEmpty()) notice else {
        // 左右键跳转有 250ms 的合并窗（见 [seekRecommend]）：这一段时间里显示目标位置，
        // 而不是还没跳过去的旧位置。
        // 时间已经挪到进度条右边那一段（与正式播放一致），这一行只说集数与状态。
        val paused=if(playbackReady && p?.playWhenReady!=true) "已暂停 · " else ""
        "${paused}第 ${episodeIndex+1} / ${data.episodes.size} 集"
    }
    if(meta.text.toString()!=text) meta.text=text
}

/**
 * 上下键换剧。
 *
 * 走到列表两端时：还有下一页就先补一页再往下走；没有就**环绕**（抖音式的无限流，
 * 用户不会在最后一部上按到「没反应」）。
 */
internal fun MainActivity.switchRecommend(direction: Int) {
    if(screen!="recommend") return
    // 用户自己换了一部：上一次那部的失败不再累计。
    recommendPlaybackFailures=0
    if(playbackMenu!=null) hidePlaybackOverlay()
    if(recommendItems.isEmpty()) { openRecommend(); return }
    // 这一下按键就是过渡的起点：先把画面淡成黑，再去取 / 换下一部（见 [showRecommendCover]）。
    if(playerView!=null) showRecommendCover()
    val from=recommendIndex
    val next=from+direction
    when {
        next in recommendItems.indices -> playRecommendAt(next,0L,direction)
        // 走到末尾：先补一页再往下走。补页发不出去 / 失败时回调会立刻到，
        // 这时列表没变长，就环绕回第一部（抖音式的无限流，不会停在「按了没反应」上）。
        direction>0 && recommendHasMore -> {
            val guard=recommendToken.get()
            loadRecommendPage(true) {
                if(recommendToken.get()!=guard) return@loadRecommendPage
                if(from+1 in recommendItems.indices) playRecommendAt(from+1,0L,1)
                else if(recommendItems.isNotEmpty()) playRecommendAt(0,0L,1)
            }
        }
        else -> playRecommendAt(if(direction>0) 0 else recommendItems.lastIndex,0L,direction)
    }
}

/**
 * 确定键：进入正式播放界面。
 *
 * 这一刻才写观看历史（需求：只有进入正式播放界面才加入历史）。
 * [advanceNext] 为真时是「一集自动播完」那条路：进正片并从下一集接着播。
 */
internal fun MainActivity.enterFormalPlayback(advanceNext: Boolean=false) {
    if(screen!="recommend") return
    val data=detail
    // 没有详情（还在取 / 上一次整屏停在提示上）或画面已经被收掉（连续失败后的重试）：
    // 这一下确定键就是「重试」，重新起播当前这一部。
    if(data==null || playerView==null) {
        // 用户主动按确定 = 重新给一次机会：连续失败计数从这里重新算。
        recommendPlaybackFailures=0
        if(recommendItems.isEmpty()) openRecommend() else playRecommendAt(recommendIndex)
        return
    }
    // 先把状态切到正片：下面 playEpisode 会按进入时的 screen 决定留在哪一页。
    screen="player"
    // 进正片：过渡黑场立刻收掉（画面本来就没断，继续盖着会黑一下）。
    dismissRecommendCover()
    // 记下来源：从这里进的正片，返回键要直接回主页（需求 4），不回简介页。
    playbackFromRecommend=true
    // 在途的相邻预热到此为止：预热线程与正式播放的「下一集预取」是同一条，
    // 留着它会让正片开头的预取排队等它。已经预热好的那几份留着（回推荐页还能用）。
    recommendWarmToken.incrementAndGet()
    recommendWarmJob?.cancel(true); recommendWarmJob=null; recommendWarming=""
    recommendToken.incrementAndGet()
    dismissRecommendOverlay()
    hud.visibility=View.GONE
    playbackTopBar?.visibility=View.GONE
    // 这一页的控件是「推荐页」那一次建的（`playEpisode` 没有重跑），所以手势层 / 可点控制条 /
    // 进度拖动要在这里补一次：`screen` 刚变成 `player`，三处判断才会放行（各自幂等）。
    if(touchArmed) { attachTouchLayer(); attachTouchBar(); wireSeekTouch() }
    // 进正片 = 开始正式观看：立刻把这一集记进历史。
    // 画面还没 READY 时 `saveProgress` 会拒绝写（时长未知），这时挂一个待写标记，
    // 由播放监听器的 READY 分支补上——「进了正片就一定有历史」不依赖时序。
    recommendHistoryPending=true
    saveProgress()
    // 正式开看：与从详情页进播放同一件事——「已看完」标记清掉，首页 / 历史里显示成「看到第几集」。
    library.setWatched(data.series.id,false)
    // 推荐页的「自动连播」只在播放器停在片尾时才有意义：如果这一集其实是**播到一半
    // 被别的路径（看门狗 / 队列故障）判成结束，`episodeIndex+1` 会直接跳掉剩下的内容。
    // 判据用播放器自己的位置，且只放行「确实走到结尾」这一种。
    val atEnd=runCatching {
        val p=player ?: return@runCatching false
        val duration=p.duration.coerceAtLeast(0)
        duration>0 && p.currentPosition>=duration-2_000
    }.getOrDefault(true)
    if(advanceNext && atEnd) {
        if(episodeIndex<data.episodes.lastIndex) {
            // 上面那次 `saveProgress` 已经在「本集已播完」的状态下写了一条 completed 记录
            //（此刻播放器停在片尾、时长已知），因此这里只管起播下一集。
            playEpisode(episodeIndex+1,0L,autoplay=true)
        } else {
            // 最后一集播完：不再自动连播，交给原有的「全剧已播完 → 换一部接着看」。
            requestedAutoplay=false; player?.pause(); showHud(); showSeriesFinished(data.series)
        }
    } else {
        // 停在当前这一集继续看：画面本来就没断，这里只把控制层交还给正式播放的按键。
        // 这一集已经播到结尾（手动按确定时正好赶上片尾）：从头再放一次这一集没有意义，
        // 直接按正片自己的连播规则往下走（有下一集就下一集，最后一集就交给「全剧已播完」）。
        val ended=runCatching { player?.playbackState==Player.STATE_ENDED }.getOrDefault(false)
        when {
            ended && episodeIndex<data.episodes.lastIndex -> playEpisode(episodeIndex+1,0L,autoplay=true)
            ended -> { requestedAutoplay=false; player?.pause(); showHud(); showSeriesFinished(data.series) }
            else -> {
                if(player?.isPlaying!=true && !playError) requestPlayback(true)
                showHud()
            }
        }
    }
}

/**
 * 推荐页的左右键：快退 / 快进（需求 3）。
 *
 * 与正式播放的 [seek] 同一套步长与手感（短按 10 秒、长按 30 秒、250ms 内合并成一次跳转），
 * 区别只在反馈位置：推荐页没有底部控制条，进度写在底部浮层那一行（[showRecommendToast]）。
 * 这里**不调** `showHud()`：那一层在推荐页本来就不该出现（需求：上下键 / 左右键都只服务于
 * 这一屏的刷剧体验，不弹出播放控制）。
 */
internal fun MainActivity.seekRecommend(direction: Int,repeat: Int) {
    if(screen!="recommend") return
    val p=player ?: return
    if(!playbackReady) return
    val duration=p.duration
    if(duration<=0) return
    val step=if(repeat>4) 30000 else 10000
    val target=((pendingSeek ?: p.currentPosition)+direction*step).coerceIn(0,(duration-500).coerceAtLeast(0))
    pendingSeek=target
    main.removeCallbacks(seekRunnable); main.postDelayed(seekRunnable,250)
    updateRecommendOverlay()
    showRecommendToast("${if(direction>0) "快进" else "快退"} ${step/1000} 秒 · ${formatTime(target)}")
}

/**
 * 回主页面（首页）。推荐页返回键与「从推荐进正片后按返回」共用这一条路（需求 4）。
 *
 * 与以前的区别有两点：
 * 1. 首页**一定重新取一次**（[showCatalog] 带 `load=true`）：用户从推荐页退出来，
 *    要看到的是首页最新的一批数据，而不是进来之前那一屏；
 * 2. 推荐列表本身**不清**（会话级缓存）：再点推荐按钮，接着上次那一部往下刷。
 */
internal fun MainActivity.goHomeFromRecommend() {
    recommendToken.incrementAndGet(); recommendPageToken.incrementAndGet()
    recommendPlaybackFailures=0; recommendHistoryPending=false; playbackFromRecommend=false
    dismissRecommendCover()
    // 在途的「取推荐流」这一下作废：它的回调会因为令牌 / 页面不对而丢弃，
    // 这个标记必须在这里一起清掉，否则下次进推荐会一直以为还在加载、什么都不做。
    recommendLoading=false
    // 相邻剧集的预热只在推荐页有意义：回到主页就一起收掉（约 3MB），下次进推荐再重新预热。
    closeRecommendCache()
    dismissRecommendOverlay()
    detail=null
    detachPlayerSurface()
    screen="catalog"
    tab=TAB_HOME; page=1; collection=null; catalogFocus=""
    initialCatalogPending=false; restoreStoppedPage=false
    showCatalog(load=true)
}

/** 推荐页返回键：回主页面（首页）。不写历史、不回详情。 */
internal fun MainActivity.exitRecommendToHome() {
    if(screen!="recommend") return
    goHomeFromRecommend()
}

/**
 * 从推荐进入的正式播放，按返回**直接回主页**（需求 4）。
 *
 * 先按正式播放的规则写一次进度（这一集确实看过了），再走与推荐页返回同一条收尾：
 * 不经过简介页、首页重新取数据。
 */
internal fun MainActivity.leaveRecommendPlaybackToHome() {
    if(screen!="player") return
    sleepTimer.cancel(); sleepChoice=0; sleepStopped=false
    saveProgress(); library.flush(); invalidatePage()
    goHomeFromRecommend()
}
