// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.google.android.exoplayer2.Player
import com.hgtv.star.compat.isMeteredConnection
import com.hgtv.star.core.CategoryFilterOption
import com.hgtv.star.core.CategorySelection
import com.hgtv.star.core.ContentRepository
import com.hgtv.star.core.Detail
import com.hgtv.star.core.RemoteVideo
import com.hgtv.star.core.Series
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.list.showCatalog
import com.hgtv.star.core.lruPut

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
 * 一次加载里最多连着取多少页。
 *
 * 只有「这一页筛完一条不剩」（整页都是本机隐藏的 / 全是推过的）才会接着取下一页，正常一页就返回。
 *
 * **实测一页要 700~1000ms**（20 条；60 条 940ms、100 条 1s，条数几乎不额外花钱）。
 * 早先这里是 14：一次进推荐最坏要串行等 14 页（≈11s）再换流重来两轮（≈30s），
 * 用户看到的就是「正在加载推荐…」一直转。页数深了本来就全是旧货（服务端在 2000 钳位），
 * 往下挖的性价比极低，因此这里收到 3 页：宁可早一点换一条新流。
 */
private const val RECOMMEND_MAX_CHAIN=3
/**
 * 首屏一次要多少条。
 *
 * 与 20 条同一个价（940ms vs 780ms），但候选是 3 倍：一次就凑出「够新鲜的一页」的概率高得多，
 * 少翻的页数远大于那 160ms。之后补页仍按 20 条。
 */
private const val RECOMMEND_FIRST_LIMIT=60
/**
 * 补页一次要多少条。
 *
 * 同样是 60：一页 20 条与 60 条的耗时只差约 160ms（实测 775ms / 942ms），但「两次补页之间
 * 用户能连刷多少部」直接变成 3 倍。早先 20 条时，一次补页的净新条目只有 13~17 部
 * （补页那 20 条里总有一部分已经刷过 / 同名的被滤掉），于是每刷十几部就撞一次补页——
 * 用户看到的就是「隔十几部黑一下、跳过去一部」。
 */
private const val RECOMMEND_PAGE_LIMIT=60
/**
 * 快刷到这一批末尾还剩这么多部时，提前把下一页取回来（[ensureRecommendLookahead]）。
 *
 * 10 部的提前量：连按十几下时用户从第 N-10 部按到第 N 部可能只有 3~5 秒，而一次补页
 * 实测 0.8~2 秒（网络差时更久）——3 部的余量在「快按」时来不及，所以放宽到 10 部。
 * 代价只是一次更早的后台请求，用户感知不到；换来的是按到末尾那一帧已经有内容。
 */
private const val RECOMMEND_LOOKAHEAD=10
/**
 * 首屏最多等多久（毫秒）。超过它就不再往下凑「一条重复都没有」，把手里这一页照原样放出来。
 *
 * 依据同一组实测：正常一页 0.8s、换流一次 0.8s，3 秒足够走完「取一页 + 换一条流」；
 * 再等下去用户面对的就是一块「正在加载推荐…」的静止画面——那比看到两部看过的更糟。
 */
private const val RECOMMEND_FIRST_BUDGET_MS=3_000L
/**
 * 落盘游标深到这个位置就不再往下取，直接回流头。
 *
 * 服务端在 `next_offset=2000` 上钳位，越靠近它越只剩旧货；而从流头重新采样
 * （新 `session_id`）拿到的分布是一样的、排序是新的（见文档 §3.1）。
 */
private const val RECOMMEND_DEEP_CURSOR=800
/**
 * 「带着 `filter_ids` 失败」的判定门槛（毫秒）。
 *
 * 只有这么快就回来的失败才值得再发一次不带过滤的请求：网关不认这个字段是立刻回错的，
 * 而超时 / 断网那种慢失败重发一遍只是把等待时间翻倍。
 */
private const val RECOMMEND_FILTER_RETRY_MS=1_500L
/**
 * 同一次加载里最多换几次「新流」。
 *
 * 换新流解决的是实测到的两处死水：
 * * 游标撞到服务端天花板——`next_offset` 永远停在 2000，之后每次打开都回同一批；
 * * 一条流刷到后段（`session_id` 只用 20 多分钟就开始衰减）回的全是推过的。
 *
 * 换两次仍然全是旧货，就不再纠缠，交给用户（上下键照样能看）。
 */
private const val RECOMMEND_MAX_RESTARTS=2
/**
 * 切换过渡：黑场淡入（把上一部收住）/ 黑场淡出（把这一部放出来）/ 最长遮挡。
 *
 * 数值与 1.2.4.2 一致：120ms 淡入、260ms 淡出。再短读起来像闪一下，
 * 再长会让连按上下键时按键反应被过渡拖住。
 */
private const val COVER_IN_MS=120L
private const val COVER_OUT_MS=260L
/**
 * 连划时的淡出时长（只用于触屏，见 [revealRecommendCover]）。
 *
 * 电视保持 260ms 不动：遥控器一秒一下，过渡要读得出「换了一部」。
 */
private const val COVER_OUT_FAST_MS=120L
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
    // 上一次停在推荐页那一部（没退出推荐页就又进了一次，例如回到前台）：先把停留时长结掉，
    // 否则「看了一分钟」与「一进来就划走」会混成同一条记录。
    settleRecommendWatch()
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
    collection=null
    directPlayback=false
    initialCatalogPending=false
    restoreStoppedPage=false
    playbackFromRecommend=false
    recommendHistoryPending=false; recommendPlaybackFailures=0
    // 每次进推荐页重建一次兴趣画像：上次会话里从别处看的剧，这次就该算进口味。
    recommendTasteCache=null
    recommendToken.incrementAndGet(); recommendPageToken.incrementAndGet()
    screen="recommend"
    // **本进程里还留着上一次进推荐页的那一列**（用户只是去主页 / 设置页转了一圈又回来）：
    // 直接接着用，不重新取流、不重新取详情、不丢掉预热好的首集。
    //
    // 早先这里无条件清空（`recommendItems=emptyList(); recommendDetails.clear();
    // closeRecommendCache()`），于是「主页 → 推荐」每次都是一次完整冷启动：
    // 取推荐流 1s + 取详情 0.5s + 取地址 + 索引 + 首块——用户读到的就是「缓存没用上」。
    // 而这些数据本来就在内存里，体积也小（一列卡片 + 6 份详情 + 几份首集）。
    val retained=recommendItems.isNotEmpty()
    if(!retained) {
        recommendItems=emptyList(); recommendIndex=0; recommendPage=0; recommendHasMore=true
        recommendDetails.clear(); closeRecommendCache()
    }
    recommendLoading=false
    // 上一次进推荐排的「首帧后取流」作废：这一次会重新排（或直接取）。
    cancelRecommendFeed()
    // 上一轮排队的按键意图与回调一起作废：新的一次进页面不该继承上一次的连按计数。
    recommendWaiters.clear(); recommendAdvancePending=0
    // 起点来自落盘的游标（`session_id|next_offset`）：上次刷到哪儿，这次就从哪儿往下取。
    val cursor=library.recommendCursor
    recommendSession=cursor.substringBefore('|')
    recommendNext=cursor.substringAfter('|',"0").toIntOrNull()?.coerceAtLeast(0) ?: 0
    recommendFirstLoadAt=0L
    // 这一次进页面「放哪一部」，两条路：
    //
    // 1. **本进程里还留着列表**（主页 / 设置页转一圈回来）→ 用这一列里**上一次那一部的下一条**。
    //    列表、详情、预热都还在内存里，所以这一步是「换台」而不是「重新推一部」：不发请求，
    //    直接放缓存里的人 i+1（它的详情与首块正是上一轮放 i 时预热好的）。
    //    早先这里按原下标接着放：用户读到的是「进去、返回、再进去，永远是同一部」。
    // 2. **进程刚起**（冷启动 / 被杀之后重开）→ 读落盘快照，从**上一次停的那一部的下一部**
    //    接着播，**不等网络**。
    //
    // 第 2 条的关键是那一部早就备好了：上一轮在放 i 的时候，窗口预加载已经把 i+1 / i+2
    // 的详情（`DetailCache`）、播放地址、索引、首块 512KB（`PlaybackCache`）都落了盘
    // （见 `SlideCacheManager` 的主方向 3 条 FULL 预热）。所以这条路是「读盘 → 出画面」，
    // 通常 0.7~1.2 秒；取流 / 取详情 / 取地址全都不在首帧路径上。
    //
    // 早先这里直接 `playRecommendAt(0)`：快照第 0 条正是**本轮最早**看过的那一部，
    // 于是每次打开都是同一部（用户看到的「还是 a，还是 a」）。
    // 现在用 `RecommendCache.lastPlayed()` 记住停在哪，再 +1 往后走。
    //
    // 本机隐藏 / 已追完的剧不进列表（与取页时那两道过滤同一套判据，见 `loadRecommendPage`）：
    // 快照是上一轮写的，用户在这之间可能刚把某一部隐藏掉。
    recommendStarted=false
    val hidden=library.hidden()
    val snapshot=if(retained) emptyList() else com.hgtv.star.core.RecommendCache.load().filterNot { it.id in hidden }
    recommendRestored=retained
    if(retained) {
        // 列表 / 详情 / 预热都还在：往后挪一条，放缓存里的下一部（见 [nextRecommendIndex]）。
        // 只换下标，**不重取流**：这一列还是同一条流，重取会把用户刚看过的那几部又排回来。
        val resume=nextRecommendIndex(recommendItems)
        recommendIndex=resume
        android.util.Log.i("HGTV","Recommend retained items=${recommendItems.size} index=$resume "+
            "prepared=${recommendPrepared.size}")
        recommendStarted=true
        playRecommendAt(resume)
    } else if(snapshot.isNotEmpty()) {
        val resume=resumeIndex(snapshot)
        recommendItems=snapshot
        recommendHasMore=true
        recommendRestored=true
        recommendIndex=resume
        recommendStarted=true
        android.util.Log.i("HGTV","Recommend resume items=${snapshot.size} index=$resume "+
            "last=${com.hgtv.star.core.RecommendCache.lastPlayed().takeLast(6)} "+
            "disk=${if(mediaCached(snapshot[resume])) "full" else "partial"}")
        playRecommendAt(resume)
        // 流**先不取**：这一部的详情 / 地址 / 索引 / 首块都在盘上，首帧只等解码。
        // 这时候再插一个取流的 POST 只会和出帧抢那一点带宽与 CPU。等 READY 再取
        // （见 `playEpisode` 的 READY 分支），3 秒还没出画面就按超时取。
        requestRecommendFeedAfterFirstFrame()
    } else showRecommendMessage("正在加载推荐…")
    if(snapshot.isEmpty()) {
        // 没有任何快照（第一次装 / 清过缓存）：整屏只有一行字，这一部只能由流决定。
        loadRecommendPage(append=false) {
            if(isDestroyed || screen!="recommend" || recommendStarted) return@loadRecommendPage
            if(recommendItems.isEmpty()) return@loadRecommendPage
            recommendStarted=true
            android.util.Log.i("HGTV","Recommend cold start play index=0 items=${recommendItems.size}")
            playRecommendAt(0)
        }
    }
}

/**
 * 冷启动续播时把「取推荐流」推迟到首帧之后（[RECOMMEND_FEED_DELAY_MS] 兜底）。
 *
 * 为什么值得推迟：续播的动作是「读盘 + 解码」，一个字节的网络都不需要；
 * 而在这台盒子上解码初始化那 1~2 秒里，主线程与 CPU 都很紧（见 `playEpisode` 里
 * `primePlaybackClasses` 那段）。取流是一次 38KB 的 POST，插在这中间只会把首帧推后。
 *
 * 推迟不会让「新内容」晚太多：续播的首帧正常 0.7~1.2 秒，那时才发；
 * 而它本身就是补在列表末尾的（`recommendRestored` 分支只往后接），用户在看第一部时它早就回来了。
 */
private const val RECOMMEND_FEED_DELAY_MS=3_000L

internal fun MainActivity.requestRecommendFeedAfterFirstFrame() {
    if(recommendFeedPending) return
    recommendFeedPending=true
    val timer=Runnable {
        recommendFeedTimer=null
        if(screen!="recommend") { recommendFeedPending=false; return@Runnable }
        fireRecommendFeed()
    }
    recommendFeedTimer=timer
    main.postDelayed(timer,RECOMMEND_FEED_DELAY_MS)
}

/** 真的去取那一页推荐（首帧已出 / 超时）。只生效一次。 */
internal fun MainActivity.fireRecommendFeed() {
    if(!recommendFeedPending) return
    recommendFeedPending=false
    recommendFeedTimer?.let { main.removeCallbacks(it) }
    recommendFeedTimer=null
    // 空回调：这一次只为把新条目接到列表末尾，不换正在播的这一部。
    loadRecommendPage(append=false) { }
}

internal fun MainActivity.cancelRecommendFeed() {
    recommendFeedPending=false
    recommendFeedTimer?.let { main.removeCallbacks(it) }
    recommendFeedTimer=null
}

/**
 * 冷启动时从哪一条接着播：**上一次停的那一部的下一条**。
 *
 * 三条规则，按顺序：
 *
 * 1. 找到 `lastPlayed()` 在快照里的位置，从它的下一部开始；
 * 2. 往后跳过已经推过的（本机曝光窗口 `recommendSeen`）——上一轮的先播项、
 *    以及上一轮来回划过的那些都在这儿，跳过它们才是「接着往下看」；
 * 3. 走到末尾就**往回找**第一部没推过的；全都推过才退回 0（这一列真的刷穿了）。
 *
 * 不做「+1 之后无条件信任」的原因：用户可能在关掉应用之前已经往回划了两部，
 * 那一部再放一遍就是重复。跳过已推过的正好把这两种情况统一掉。
 */
private fun MainActivity.resumeIndex(snapshot: List<com.hgtv.star.core.Series>): Int {
    val last=com.hgtv.star.core.RecommendCache.lastPlayed()
    val seen=runCatching { library.recommendSeen() }.getOrDefault(emptySet())
    fun fresh(series: com.hgtv.star.core.Series)=
        series.id !in seen && !library.recommendSeenTitle(series.title)
    val start=(snapshot.indexOfFirst { it.id==last }.takeIf { it>=0 }?.plus(1) ?: 0).coerceIn(0,snapshot.lastIndex)
    // 往后找第一条没推过的；**最近 3 条里优先挑索引 + 首块都在盘上的那一条**。
    //
    // 为什么要挑：`i+1` 有地址但没索引 / 首块（用户划得快、预热没跑完就关了应用）时，
    // 起播仍要现拉 moov（400~500ms）+ 首块（900ms）+ 解析（600ms）——正是「打开就卡」。
    // 而 `i+2` 如果已经 FULL 预热过，它读盘就出画面。用户感知是「接着往下看」，
    // 差一条远比多等 2 秒好。只在 [RESUME_CACHE_PROBE] 条之内挑，不会跳到很远。
    val candidates=ArrayList<Int>()
    for(index in start..snapshot.lastIndex) if(fresh(snapshot[index])) candidates+=index
    for(index in candidates.take(RESUME_CACHE_PROBE)) if(mediaCached(snapshot[index])) return index
    candidates.firstOrNull()?.let { return it }
    for(index in 0 until start) if(fresh(snapshot[index])) return index
    return start
}

/**
 * **本进程里还留着列表**时，再进推荐页放哪一条：上一次停的那一部的**下一条**。
 *
 * 与冷启动的 [resumeIndex] 不同，这里不做「跳过已推过的 / 优先挑盘上缓存」那两步：
 * 这一列就是用户刚才看的那一条流，详情与首块都在内存里（上一轮放 i 时预热好的 i+1），
 * 用户对「下一条」的预期就是紧挨着的那一格，跳到 i+3 反而像漏看了一部。
 *
 * 三条规则：
 *
 * 1. `lastPlayed()` 还在这一列里 → 取它的下一个下标；
 * 2. 找不到（位置文件还没落盘 / 列表被别处换过）→ 当前下标 +1；
 * 3. 走出这一列末尾 → 回卷到第一条不是刚才那一部的条目（与下键翻到末尾的回卷一致），
 *    绝不把同一部再放一遍——那正是「进去返回永远是同一部」这个问题的来源。
 */
private fun MainActivity.nextRecommendIndex(items: List<com.hgtv.star.core.Series>): Int {
    if(items.isEmpty()) return 0
    val last=com.hgtv.star.core.RecommendCache.lastPlayed()
    val at=if(last.isEmpty()) -1 else items.indexOfFirst { it.id==last }
    val next=(if(at>=0) at else recommendIndex)+1
    if(next<=items.lastIndex) return next
    // 末尾回卷：挑第一条不是「上一次那一部」的；只有整列就这一部时才回到 0。
    val firstOther=items.indexOfFirst { it.id!=last }
    return if(firstOther>=0) firstOther else 0
}

/** `resumeIndex` 往后挑「已经备好整份」的范围（条）。 */
private const val RESUME_CACHE_PROBE=3

/**
 * 这一部的首集是不是「索引 + 首块」都在盘上（= 起播不需要任何视频网络请求）。
 *
 * 只查文件是否存在，不反序列化（见 [com.hgtv.star.core.PlaybackCache.hasMedia]）。
 */
private fun MainActivity.mediaCached(series: com.hgtv.star.core.Series): Boolean = runCatching {
    val detail=com.hgtv.star.core.DetailCache.load(series.id) ?: return false
    val episode=detail.episodes.firstOrNull() ?: return false
    // 索引键是「集 + 实际档位 + 编码」（见 `StreamInfo.mediaKey`），精确值要取一次地址才知道，
    // 这里只做「盘上有没有」的粗判：任意档位 / 任意编码命中就算有（见 `hasAnyMedia`）。
    // 查不到只是退回更高一级预热，不影响正确性。
    com.hgtv.star.core.PlaybackCache.hasAnyMedia(episode)
}.getOrDefault(false)

/**
 * 丢掉所有预热好的相邻剧集（关闭画面、退出应用、内存吃紧时）。
 *
 * 推荐列表每次进页面都会重新取一页，这几份预热只在当前这一屏有意义；一旦离开推荐页
 * 就不再需要，留着只会白占内存（每部约 1MB）。
 */
internal fun MainActivity.closeRecommendCache() {
    cancelRecommendWarm()
    cancelScheduledPreload()
    cancelRecommendFeed()
    recommendPrepared.values.forEach { runCatching { it.close() } }
    recommendPrepared.clear()
    recommendPreparedIndex.clear(); recommendPreparedAt.clear(); recommendWarmed.clear()
    recommendDetailLoading.clear()
}

/**
 * 取消某一部的在途预热（它正好要被起播 / 已经划过去了）。
 *
 * 三件事一起做：把键从「在途」里摘掉、把线程任务打断、把这次预热自己的取消域关掉。
 * 只做前两件时，那个线程还在 OkHttp 里读 1MB——结果没人要，带宽照样占着。
 */
internal fun MainActivity.cancelRecommendWarm(key: String) {
    recommendWarming.remove(key)
    val warm=recommendWarmJobs.remove(key) ?: return
    warm.future?.cancel(true)
    warm.scope.cancel()
}

/** 取消全部在途预热（离开推荐页 / 进正片 / 退出应用）。 */
internal fun MainActivity.cancelRecommendWarm() {
    val jobs=recommendWarmJobs.values.toList()
    recommendWarmJobs.clear()
    recommendWarming.clear()
    jobs.forEach { warm -> warm.future?.cancel(true); warm.scope.cancel() }
}

/**
 * 取一页推荐流。
 *
 * [append] 为假是首次进入（重置列表），为真在末尾接一页（上下键走到最后一部时补货）。
 * 结果落地前会核对令牌与当前页面：中途退出 / 重新进入，旧结果直接丢掉。
 *
 * [depth] / [restarts] 只给本函数自己递归用：前者数「这一次加载已经连取了几页」，
 * 后者数「这一次加载已经换了几条新流」，两个上限见 [RECOMMEND_MAX_CHAIN] /
 * [RECOMMEND_MAX_RESTARTS]。
 */
internal fun MainActivity.loadRecommendPage(append: Boolean,depth: Int=0,restarts: Int=0,noFilter: Boolean=false,onReady: () -> Unit) {
    // 请求在途：**排队，不立刻回调**。
    //
    // 早先这里是 `{ onReady(); return }`：调用方以为「页取完了、列表就是现在这么长」，
    // 而实际上页还在路上——于是走到末尾的每一次按键都判成「没有下一页」，
    // 环绕回第 0 部，把刚刷过的那批从头重放一遍（用户实测：连按十几下，全是刚才看过的）。
    // 现在把它排进 [recommendWaiters]，由这一次取页结束时统一放出来（见 [settleRecommendLoad]）。
    if(recommendLoading) { recommendWaiters += onReady; return }
    // 这一次加载连着取了十几页都没凑够「没推过的」：这条流大概只剩推过的了，
    // 换一条新流再走一遍；换流次数用完就交给用户（列表里还有旧的，上下键照样能看）。
    //
    // 之所以在这里换流、而不是「这一页八成推过就换」：游标本身就是往下走的，
    // 一页里推过的多并不代表后面没有新的；过早退回流头正是「列表又被推一遍」的来源。
    if(depth>=RECOMMEND_MAX_CHAIN) {
        if(restarts<RECOMMEND_MAX_RESTARTS) {
            restartRecommendStream()
            loadRecommendPage(append,0,restarts+1,noFilter,onReady)
        } else { onReady(); settleRecommendLoad() }
        return
    }
    // 这条流已经到底（`has_more=false`）：不再原地干等，换一条新流接着补货。
    // 早先这里是直接 `onReady()`——用户上下键走到流尾，之后每次补页都是空回调，
    // 看上去就是「刷不动了」。
    if(append && !recommendHasMore) {
        if(restarts>=RECOMMEND_MAX_RESTARTS) { onReady(); settleRecommendLoad(); return }
        restartRecommendStream()
        loadRecommendPage(append,depth+1,restarts+1,noFilter,onReady)
        return
    }
    val page=recommendPage+1
    recommendLoading=true
    val token=recommendPageToken.incrementAndGet()
    // 首屏计时从这里开始（递归进来的 `depth>0` 不再重置）：超过 [RECOMMEND_FIRST_BUDGET_MS]
    // 就不再凑「一条重复都没有」，见下面 `exhausted` 的判定。
    if(!append && depth==0) recommendFirstLoadAt=android.os.SystemClock.elapsedRealtime()
    // 落盘游标已经刷得太深（服务端在 2000 上钳位、越深越只剩旧货）：先回流头再取。
    // 放在读 session/offset 之前，这一取的起点就直接是流头。
    if(!append && depth==0 && recommendNext>=RECOMMEND_DEEP_CURSOR) restartRecommendStream()
    // 请求从游标位置开始（首次进入 = 落盘游标，补页 = 上一页的 `next_offset`）。
    val session=recommendSession
    val offset=recommendNext
    // 首屏一次要得多一些（同一份延迟换 3 倍候选），补页仍按 20 条。
    val limit=if(append) RECOMMEND_PAGE_LIMIT else RECOMMEND_FIRST_LIMIT
    val startedAt=android.os.SystemClock.elapsedRealtime()
    // 首屏那 3~4 秒到底花在哪儿：注册 / 签名 / 网络 / 解析各占多少。
    // 只在调试包里打（正式包被 R8 删掉），与 `Stage ...` 系列同一风格。
    fun mark(name: String) {
        if(com.hgtv.star.BuildConfig.DEBUG) {
            android.util.Log.i("HGTV","Recommend stage $name +${android.os.SystemClock.elapsedRealtime()-startedAt}ms")
        }
    }
    // 兴趣画像只在这里快照一次：它读本机历史 / 收藏，刷推荐的过程中不会变。
    val taste=tasteForRecommend()
    io.submit {
        // 分类 / 推荐接口要签名，签名里的设备身份必须是**平台注册过**的那一份
        // （与播放取地址、搜索同一道门槛）。注册在途时 registerDeviceOnce 会在锁上等，
        // 不会重复发请求；冷启动第一次进推荐因此不会因为「还没注册」而空手回来。
        if(!library.deviceRegistered()) registerDeviceOnce()
        mark("register")
        // 已推过的剧回传给服务端（文档 §2.1 / §4.4 的 `filter_ids`）：这是「不重复」的第一层，
        // 本机窗口只是第二层兜底。服务端不认它时会有下面的空页兜底，不会把推荐页卡死。
        val filters=if(noFilter || recommendFilterUnsupported) "" else library.recommendFilterIds()
        mark("filters")
        val result=runCatching {
            // 全部体裁：真人短剧 / 漫剧 / AI 剧一起进推荐流（不传 `genre`）。
            // 早先这里钉在 `comic_series` 上，实测那条流的内容池只有 193 部，
            // 刷完就只剩重复；放开体裁之后池子深得多（实测 500 条仍未到底）。
            //
            // 设置页「推荐范围」关掉的类不带（见 `Library.recommendGenres`）：三类全开时它给的
            // 是空列表，请求与旧版逐字节一致；只留一两类时才带上 `genre`，换剧就只在这些类里切。
            //
            // 受众（男频 / 女频）是另一个维度：选过就带上 `gender`，没选时为空（不筛）。
            // 标识来自接口自己的 `selector_rows`（见 `Library.recommendGender`），不猜。
            repository.recommendFeed(
                CategorySelection(gender=library.recommendGender.ifEmpty { null }),
                session,offset,limit=limit,genres=library.recommendGenres(),filterIds=filters
            )
        }
        mark("feed")
        main.post {
            mark("post")
            // 这一次的结果作废（退出 / 重进 / 又换了一条流）：排队的按键意图一起丢掉，
            // 否则下次按一下会带着上一次的计数一次跨过头；排队等着的调用方也放掉，
            // 免得它们永远等下去（它们各自的令牌守卫会让动作变成空操作）。
            if(isDestroyed || token!=recommendPageToken.get() || screen!="recommend") {
                recommendAdvancePending=0; settleRecommendLoad(); return@post
            }
            recommendLoading=false
            result.onSuccess { feed ->
                // 带了 `filter_ids` 却一条都没回来：这台设备上服务端不吃这个字段（或把整页都滤光了）。
                // 立刻不带重取一次；连续两次就把这一层永久关掉（见 `Library.recommendFilterIdsOff`）。
                // 它是「锦上添花」的一层，绝不能因为它让推荐页整屏「推荐暂时取不到」。
                if(filters.isNotEmpty() && feed.page.items.isEmpty()) {
                    recommendFilterMiss++
                    if(recommendFilterMiss>=2) { library.recommendFilterIdsOff=true; recommendFilterUnsupported=true }
                    android.util.Log.w("HGTV","Recommend empty page with filter_ids=${filters.length}B, retrying without")
                    // 不带过滤重取的那一次**不加深** `depth`：它不是「又翻了一页」，
                    // 万一撞到链路上限就会白白丢掉落盘的游标（换流会把游标清空）。
                    // 不会死循环：下一次 `filters` 是空串，这条分支进不来。
                    loadRecommendPage(append,depth,restarts,noFilter=true,onReady)
                    return@onSuccess
                }
                // 带了而且正常回来：这个字段在本机可用，之前那两次失败作废。
                if(filters.isNotEmpty()) { recommendFilterMiss=0; recommendFilterFail=0 }
                // 换了推荐范围之后，落盘的位置可能已经越过新范围的池子尾：服务端回空页。
                // 这时不能停在「推荐暂时取不到」，退回流头重取一次（换流次数上限见
                // [RECOMMEND_MAX_RESTARTS]）。只对首次进入这样做——补页时遇到空页就是流尾。
                if(!append && feed.page.items.isEmpty() && offset>0 && restarts<RECOMMEND_MAX_RESTARTS) {
                    restartRecommendStream()
                    loadRecommendPage(append,depth+1,restarts+1,noFilter,onReady)
                    return@onSuccess
                }
                // 本机隐藏的剧 / 已经推过的剧 / 同名（另一季、同 IP 的另一版）全部滤掉。
                // **不再按 `vertical` 筛**：全部体裁里真人短剧占了大头（实测 500 条里 405 条竖屏），
                // 筛掉竖屏等于又只剩漫剧——正是要放开的那个限制。
                //
                // 粒度是文档 §4.1 说的**剧级**：`filter_ids` 与窗口都用 `series_id`，
                // 剧名归一化那一条挡的是「同一部剧换了切片回来」（`series_id` 不同，用户一眼认出）。
                val known=library.recommendSeen()
                val finished=library.recommendFinished()
                // 本次会话列表里已经有的那些也算「推过了」：换页 / 换流回来时不该再出现一遍。
                // 这一份只活在内存里（`recommendItems`），**不**写进曝光窗口——那一份记的是
                // 「真的上过屏」的，见 `playRecommendAt` 里的 `markRecommended`。
                val loaded=recommendItems.map { it.id }.toHashSet()
                val loadedTitles=recommendItems.mapNotNull { com.hgtv.star.core.RecommendFlow.titleKey(it.title).takeIf(String::isNotEmpty) }.toHashSet()
                val candidates=feed.page.items
                    .filterNot { it.id in library.hidden() }
                    // 已经追完的剧不再推（文档 §4.2 硬过滤）：刷到自己刚看完的那一部，
                    // 用户读到的就是「推荐不准」。
                    .filterNot { it.id in finished }
                    .filterNot { it.id in known }
                    .filterNot { it.id in loaded }
                    .filterNot { library.recommendSeenTitle(it.title) }
                    .filterNot { com.hgtv.star.core.RecommendFlow.titleKey(it.title) in loadedTitles }
                // 这条流已经刷不动了：游标不前进（服务端把 `next_offset` 钳在同一个数上）。
                // 这是「在同一页上打转」，只有换一条新流才有新东西。
                //
                // 「这一页大半都推过」**不再触发换流**：游标在往下走，后面还有没推过的；
                // 一看到推过的就退回流头，正好把用户又送回列表开头（见 `Library.recommendSeen`）。
                // 真的整页都推过时，下面「筛完一条不剩就接着取下一页」会继续往后走。
                // `offset==0` 时不算「撞天花板」：那本来就是流头，`next_offset` 前进与否是下一页的事。
                val stalled=offset>0 && feed.nextOffset<=offset
                if(stalled && restarts<RECOMMEND_MAX_RESTARTS) {
                    restartRecommendStream()
                    loadRecommendPage(append,depth+1,restarts+1,noFilter,onReady)
                    return@onSuccess
                }
                // 游标前进并立刻落盘：下次进推荐（哪怕换了进程）都接着这里往下取，
                // 而不是回到 `offset=0` 那套永远相同的默认列表。
                recommendSession=feed.sessionId
                recommendNext=feed.nextOffset
                library.recommendCursor=feed.sessionId+"|"+feed.nextOffset
                // 这一页进列表**不等于**「已经推给用户看过」：一页 20 条里用户可能只看第一条，
                // 把另外 19 条也记成已推，等于白白烧掉 19 部内容（下次进推荐就再也刷不到）。
                // 曝光记录因此放在真正上屏那一刻（见 `playRecommendAt` → `markRecommended`）。
                // 已经无处可退（后面没有了 / 换流次数用完）而这一页一条没推过的都没有——
                // 等于用户把能刷的都刷穿了：宁可把这一页照原样放出来重复几部，
                // 也不要让整屏只剩下「取不到」。
                // 首屏还有一条硬约束：**总等待时间**。超过 [RECOMMEND_FIRST_BUDGET_MS]
                // 就不再往下凑「一条重复都没有」，照原样放出来先播——空转的黑屏比几部看过的更糟。
                val expired=!append && recommendFirstLoadAt!=0L &&
                    android.os.SystemClock.elapsedRealtime()-recommendFirstLoadAt>=RECOMMEND_FIRST_BUDGET_MS
                val exhausted=!feed.page.hasMore || restarts>=RECOMMEND_MAX_RESTARTS || expired
                val shown=if(candidates.isEmpty() && exhausted) {
                    // 刷穿了：照原样放出来重复几部，但「本机隐藏」与「已经追完」永远不进列表。
                    feed.page.items.filterNot { it.id in library.hidden() || it.id in finished }
                } else candidates
                // 本机重排：兴趣小幅上浮 + 同题材配额 / 同剧间距（见 [RecommendArranger]）。
                // 补页时把列表尾部当上下文，页与页的接缝上才不会连着两部同类。
                val arranged=recommendArranger.arrange(shown,taste,if(append) recommendItems.takeLast(20) else emptyList())
                // 一行诊断：这一页取回多少条、滤掉多少、真正进了列表多少。
                // 「每刷十几部就卡一下」如果还出现，看这行就知道是补页太频繁还是这一页几乎全是旧的。
                if(append) {
                    android.util.Log.i("HGTV","Recommend append page=$page raw=${feed.page.items.size} kept=${shown.size} " +
                        "arranged=${arranged.size} total=${recommendItems.size+arranged.size} hasMore=${feed.page.hasMore}")
                }
                recommendItems=when {
                    append -> mergeRecommendPages(recommendItems,arranged)
                    // 「先画后刷」：首屏那一列来自落盘快照（见 [com.hgtv.star.core.RecommendCache]），
                    // 现在新一页回来了——**只往后接**没有出现过的条目，不整页替换。
                    //
                    // 不替换的原因有两个：正在播的那一部就是这一列里的第 0 条，整页换掉
                    // 会让 `recommendIndex` 指到另一部（上下键、曝光记录、相邻预热全部错位）；
                    // 而且整页换会在播着的时候闪一下列表。
                    recommendRestored && recommendItems.isNotEmpty() -> mergeRecommendPages(recommendItems,arranged)
                    else -> arranged
                }
                // 这一页是接在快照后面还是独自成列：下面的日志要如实标出来（`recommendRestored`
                // 马上就要清掉）。
                val fromSnapshot=recommendRestored
                if(!append) com.hgtv.star.core.RecommendCache.save(recommendItems)
                recommendRestored=false
                recommendPage=page; recommendHasMore=feed.page.hasMore
                // 这一页筛完一条不剩（整页都推过）而且后面还有：接着取下一页，
                // 不能让用户看到「推荐暂时取不到」——那只是这一页没货，不是流断了。
                // 首屏预算用完就不再往下走（`expired`）：那时连「照原样放出来」都是空的，
                // 继续翻页只会让黑屏更久，直接落到下面的整屏提示 / 由调用方重试。
                // 首屏有快照兜着（列表已经画出来、也在播）时不再往下追页：
                // 那一列是完整的，用户不缺内容，继续翻页只会白占带宽与 `modelGate`。
                if(shown.isEmpty() && feed.page.hasMore && !expired && recommendItems.isEmpty()) {
                    loadRecommendPage(append,depth+1,restarts,noFilter,onReady)
                    return@onSuccess
                }
                if(recommendItems.isEmpty()) {
                    showRecommendMessage("推荐暂时取不到\n按确定键重试，按返回键回主页面")
                    settleRecommendLoad()
                    return@onSuccess
                }
                // 已经退到后台（[onStop] 里 restoreStoppedPage 已置位）：列表留着，
                // 但不在后台起播——回到前台由 onResume 接着播，免得用户切走之后声音还在响。
                if(!foreground) { settleRecommendLoad(); return@onSuccess }
                // 首屏耗时留一行日志：这条链路串了三段网络（推荐流 → 详情 → 播放地址），
                // 以后再说「进推荐慢」时，先看这一行是多少、翻了几页。
                if(!append) {
                    val spent=if(recommendFirstLoadAt==0L) 0L
                        else android.os.SystemClock.elapsedRealtime()-recommendFirstLoadAt
                    // 这一行量的是**取流**这一段，不是「首屏可看」：
                    // 有落盘快照时画面早在它之前就已经起来了（见 `Recommend resume` 那行），
                    // 把两件事混在一个 `first screen` 里读，就会得出「缓存白读了」这种错误结论。
                    android.util.Log.i("HGTV","Recommend feed page ${spent}ms pages=${depth+1} restarts=$restarts "+
                        "items=${recommendItems.size} source=${if(fromSnapshot) "snapshot" else "network"}")
                }
                onReady()
                settleRecommendLoad()
            }.onFailure { problem ->
                android.util.Log.w("HGTV","Recommend feed failed page=$page filter=${filters.length}B",problem)
                // 带着 `filter_ids` 失败（网关可能不接受这个字段）：不带重试一次。
                // **只认「秒回」的那种失败**：真被网关拒是 1 秒内就回错的；
                // 超时 / 断网那种慢失败再重试一遍，只会让「正在加载推荐」多转一倍时间。
                // 也不把它算进「服务端不认」（那一条只由空页判定）——网络抖一下不该让这层永久关掉。
                val elapsed=android.os.SystemClock.elapsedRealtime()-startedAt
                if(filters.isNotEmpty() && elapsed<RECOMMEND_FILTER_RETRY_MS) {
                    recommendFilterFail++
                    // 连着两次都失败：这一轮不再带（只到这次进程为止，见 `recommendFilterUnsupported`）。
                    if(recommendFilterFail>=2) recommendFilterUnsupported=true
                    loadRecommendPage(append,depth,restarts,noFilter=true,onReady)
                    return@onFailure
                }
                if(filters.isNotEmpty()) recommendFilterUnsupported=true
                if(recommendItems.isEmpty()) showRecommendMessage("推荐暂时取不到\n按确定键重试，按返回键回主页面")
                else { showRecommendToast("后面的推荐暂时取不到"); onReady() }
                settleRecommendLoad()
            }
        }
    }
}

/**
 * 快刷到这一批末尾时，提前把下一页取回来。
 *
 * 为什么需要：补页是有网络的（实测一页 0.8~2s）。以前只在**按到最后一部**那一下才知道
 * 要补页，于是每一次补页都必然是「停在最后一部 → 黑场 → 等网络 → 跳到下一部」——
 * 用户读到的就是「每隔十几部有一个画面没有播放、被略过」。
 * 提前 [RECOMMEND_LOOKAHEAD]（10）部开始取，等真按到末尾时新条目已经在列表里，
 * 切换就是即时的一帧。
 *
 * 只在**用户自己的按键**之后调用（见 `startRecommendEpisode`），不在取页成功里递归调用：
 * 那样一旦某一页一条新内容都筛不出来，就会变成无限次网络往返。
 */
internal fun MainActivity.ensureRecommendLookahead() {
    if(screen!="recommend" || recommendLoading || !recommendHasMore) return
    if(recommendItems.isEmpty()) return
    if(recommendItems.size-recommendIndex-1>RECOMMEND_LOOKAHEAD) return
    val now=android.os.SystemClock.elapsedRealtime()
    // 限流 1.5s：连按上下键时每一下都会走到这里，不该每一下都上去要一页。
    if(now-recommendLookaheadAt<1_500L) return
    recommendLookaheadAt=now
    // 空回调：这一次只为把下一页取到手上，不换剧。取不到也不提示——用户还没按到末尾。
    loadRecommendPage(true) { }
}

/**
 * 一次取页真正结束（成功 / 失败 / 放弃都算）：把补页期间排队的那几下按键放出来。
 *
 * 只在主线程调用；排队的回调自己带令牌守卫，过期的那几个会变成空操作。
 * 排空后再清空列表，因此重复调用无害（正常情况只有一次非空）。
 */
private fun MainActivity.settleRecommendLoad() {
    if(recommendWaiters.isEmpty()) return
    val queued=recommendWaiters.toList()
    recommendWaiters.clear()
    queued.forEach { callback -> runCatching { callback() } }
}

/**
 * 一页推荐的合并：**覆盖式**按剧 id 去重（文档 §6.7 的 `merge`）。
 *
 * 为什么不是简单 `addAll`：首屏请求与预加载 / 补页可能并发回来，两页在接缝上会重叠一条；
 * 直接拼起来就是用户看到的「刚划过的那部又出现了」。已在列表里的那一条**保留旧的**
 * （位置与详情缓存都还对得上），新的一条只在不存在时追加。
 */
private fun mergeRecommendPages(old: List<Series>,new: List<Series>): List<Series> {
    if(new.isEmpty()) return old
    if(old.isEmpty()) return new
    val map=LinkedHashMap<String,Series>(old.size+new.size)
    old.forEach { map[it.id]=it }
    new.forEach { item -> if(!map.containsKey(item.id)) map[item.id]=item }
    return map.values.toList()
}

/**
 * 本机兴趣画像（进推荐页时建一次，之后每页复用）。
 *
 * 读的是本机观看历史与收藏——推荐页自己不写历史，但用户从详情页 / 正式播放看过的那些
 * 都记在里面，正好是「这台机器喜欢什么」最可靠的来源。
 */
private fun MainActivity.tasteForRecommend(): com.hgtv.star.core.RecommendTaste {
    recommendTasteCache?.let { return it }
    val taste=runCatching {
        com.hgtv.star.core.RecommendTaste.of(library.history(),library.favorites())
    }.getOrDefault(com.hgtv.star.core.RecommendTaste())
    recommendTasteCache=taste
    return taste
}

/**
 * 取一次分类接口的筛选面板，只留下「受众」那一行，交给调用方建芯片。
 *
 * 为什么要现取：男频 / 女频的 `selector_item_id` 是平台自己那一套，代码里没有可信的
 * 固定值（`docs/HONGGUO_API.md` 只记了体裁与题材）。猜错一个串的后果是推荐流一条都推不出来，
 * 因此宁可少一个开关，也不写死一个没验证过的标识。
 *
 * 取到之后抄进 [recommendGenderOptions]：同一次运行里再进设置页不再发请求。
 * 请求在途时直接返回（不回调）：这一屏就先不建那一行，下次进来再说。
 */
internal fun MainActivity.loadGenderOptions(onReady: (List<CategoryFilterOption>) -> Unit) {
    recommendGenderOptions?.let { onReady(it); return }
    if(recommendGenderLoading) return
    recommendGenderLoading=true
    io.submit {
        if(!library.deviceRegistered()) registerDeviceOnce()
        val options=runCatching {
            repository.categoryFeed(CategorySelection(),1,needPanel=true)
                .panel?.row(CategorySelection.TYPE_GENDER)?.options
        }.getOrNull()
        main.post {
            recommendGenderLoading=false
            if(options.isNullOrEmpty()) return@post
            recommendGenderOptions=options
            // 存着的那个 id 已经不在可选值里（平台换了标识）：退回「不限」，
            // 否则会一直带着一个接口不认的值去请求。
            if(library.recommendGender.isNotEmpty() && options.none { it.id==library.recommendGender }) {
                library.recommendGender=""
            }
            onReady(options)
        }
    }
}

/**
 * 丢开当前这条推荐流，回到流头重新取（`session_id` 清空 + `offset=0`）。
 *
 * 落盘的游标一起清掉：否则下次进程重开又会从那条已经刷不动的位置取，白换一次流。
 */
private fun MainActivity.restartRecommendStream() {
    recommendSession=""
    recommendNext=0
    library.recommendCursor=""
    recommendHasMore=true
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
    // 画面之外那一层也随这棵树一起没了（见 `overlayLayer`）。
    overlayLayer=null
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
    // 触屏的推荐页没有那块浮层（见 `attachRecommendOverlay`），这条临时提示改挂在手势层上：
    // 否则「快退 10 秒 · 00:12」「后面的推荐暂时取不到」这些反馈在触屏上完全看不见。
    if(touchArmed && recommendOverlay==null) flashSeekHint(message,true,centered=true)
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
    // 换剧方向与时刻：相邻预热按「用户正在走的方向」排优先级（见 [prefetchRecommendNeighbors]）。
    // 方向为 0 时（进页面第一次起播、重试同一部）不动它，沿用上一次的方向。
    if(direction!=0) {
        recommendPrefetchDir=if(direction>0) 1 else -1
        recommendLastSwitchAt=android.os.SystemClock.elapsedRealtime()
    }
    // 双向滑动的窗口决策：速度 / 方向 / 回看三个判定都在这一步更新（见 `core/SlideCache.kt`）。
    // 换剧时先取消上一次排定的窗口预热——它是按旧锚点算的，2 秒后照跑等于拉四条没用的。
    cancelScheduledPreload()
    slideCache.onItemChanged(safe,direction,android.os.SystemClock.elapsedRealtime())
    // 内存吃紧时收窄的窗口在这里放开：那一次 `onTrimMemory` 已经过去了。
    slideCache.resetTrim()
    if(slideCache.mode==com.hgtv.star.core.SlideCacheManager.Mode.FLING && BuildConfig.DEBUG) {
        android.util.Log.i("HGTV-PERF","preloadSkip reason=fling anchor=$safe")
    }
    val series=recommendItems[safe]
    // 这一部真的上屏了：记进剧级曝光窗口（`series_id` + 归一化剧名，7 天）。
    // 这是文档 §6.7 那个 `onExpose` 的等价物——只记**上过屏**的，不记「列表里有」的。
    library.markRecommended(listOf(series.id),listOf(series.title))
    // 换到另一部之前，先把上一部「待了多久 / 播了多久」结掉：那一条信号决定它进 14 天
    // 冷却还是 30 天冷却（见 [settleRecommendWatch]）。同一部重试时不动它。
    if(recommendWatchingId.isNotEmpty() && recommendWatchingId!=series.id) settleRecommendWatch()
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
        // `cached=true`：推荐页起播只要「第 1 集是哪个 vid」。详情页仍然现取（见 `ContentRepository.detail`），
        // 但这里命中落盘缓存就省掉 0.4~0.6s——进程被回收过之后那一次尤其明显。
        val result=runCatching { repository.detail(series.id,cached=true) }
        main.post {
            if(isDestroyed || screen!="recommend") return@post
            result.onSuccess { data ->
                // 详情回来了但用户已经划到别的部：**照样收进缓存**。
                // 早先这里连缓存一起丢掉（`token!=recommendToken` 直接 return），
                // 等于把一次已经付过 0.5s 的请求白扔——划回来又要重新等一次，
                // 连划时「每一步都在等网络」有一半来自这里。
                if(data.episodes.isNotEmpty()) recommendDetails.lruPut(RECOMMEND_DETAIL_LIMIT,series.id,data)
                if(token!=recommendToken.get()) return@post
                if(data.episodes.isEmpty()) {
                    // 预告 / 未上架：没有分集可播，直接跳过（推荐流里偶尔会混进这种）。
                    // 留一行日志：推荐页「一直转圈、一直不播」最常见的成因就是这种条目连着来，
                    // 光看界面只有「正在加载《…》」在换名字，看不出是被跳过的。
                    android.util.Log.w("HGTV","Recommend skip no episodes id=${series.id} title=${data.series.title} count=${data.series.episodeCount} upcoming=${data.upcoming}")
                    skipRecommend(series,safe,direction,skipped)
                    return@post
                }
                // 同上：后台只把详情收进缓存，起播留给回到前台那一次。
                if(!foreground) return@post
                startRecommendEpisode(data,position,autoplay)
            }.onFailure { problem ->
                if(token!=recommendToken.get()) return@post
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
        // 末尾先补一页再往下走（这条流到底了，补页那一侧会换一条新流）。
        // 补页发不出去 / 失败时回调会立刻到，那时列表没变长，就环绕回第一部，
        // 别让用户停在一屏「放不了」上。
        else -> {
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
    }
}

/**
 * 「在屏幕上待多久算看了」的两个门槛（都取小一点的那一个判到就算看了）：
 * 停留 6 秒，或者真的播了 5 秒。
 *
 * 为什么两条：只按停留会误判——详情取不回来时用户也停在一屏「正在加载」上；
 * 只按播放会漏掉「静音看了个开头就换台」那些——播放器还没报出位置。
 * 这两个数值只决定**冷却时长**（14 天 / 30 天），不决定推不推，因此不需要更精细。
 */
private const val WATCH_DWELL_MS=6_000L
private const val WATCH_PLAY_MS=5_000L
/**
 * 「连划」的下限：停留不到这么久就不当成一次「不喜欢」。
 *
 * 手机上一秒划两三部时，每一下都会走到 [settleRecommendWatch]；早先这些全被判成
 * 「一进来就划走」——14 天冷却 + 同类负反馈，一批连划下来等于把好几类都记成「讨厌」，
 * 下一次推荐自然就不准了。上过屏这件事已经由 [markRecommended] 记了 7 天曝光，
 * 这里只负责别把「没看清」当成「不喜欢」。
 */
private const val QUICK_SWIPE_MS=1_200L

/**
 * 结掉「当前这一部在推荐页待了多久」，写进曝光窗口。
 *
 * 这是文档 §2.3 里 `repeat_penalty` 的客户端等价物：系统不告诉我们「这一部你喜不喜欢」，
 * 但「一进来就划走」与「看了几秒」是两种完全不同的信号，冷却时长差一倍。
 * 只延长不缩短（见 [com.hgtv.star.core.RecommendMarks.mark]），因此重复调用无害。
 */
internal fun MainActivity.settleRecommendWatch() {
    val id=recommendWatchingId
    recommendWatchingId=""
    val start=recommendWatchStart
    recommendWatchStart=0L
    if(id.isEmpty() || start==0L) return
    val dwell=android.os.SystemClock.elapsedRealtime()-start
    // 连划：这一部连一秒都没停住，用户没来得及判断。曝光已经记过（7 天），这里不再加码。
    if(dwell<QUICK_SWIPE_MS) return
    val played=runCatching { player?.currentPosition ?: 0L }.getOrDefault(0L)
    if(dwell>=WATCH_DWELL_MS || played>=WATCH_PLAY_MS) {
        library.markRecommendWatched(id)
    } else {
        library.markRecommendSkipped(id)
        // 这一类的实时负反馈只在本会话里生效（见 `RecommendTaste.skipped`）：
        // 同一类连划几部，本次刷推荐就会把它往后放，不用等下一次进页面。
        val series=recommendItems.firstOrNull { it.id==id }
        if(series!=null) recommendTasteCache?.skipped(series)
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
    // 从这里开始计时：换下一部时由 [settleRecommendWatch] 结掉（看了 → 30 天，划走 → 14 天）。
    recommendWatchingId=data.series.id
    recommendWatchStart=android.os.SystemClock.elapsedRealtime()
    // 记住「停在哪儿」：下次进程刚起时从它的**下一部**接着播（见 `openRecommend` 的
    // `resumeIndex`）。写一行剧 id，几字节、后台线程，不从主线程碰 IO。
    com.hgtv.star.core.RecommendCache.rememberPlayed(data.series.id)
    // 换到另一部：上一次「画面丢了自动重建」的额度重新给满（见 [recoverRecommendPicture]）。
    // 不清的话，看过一部要重建两回的片子之后，后面每一部都只剩一次机会。
    pictureRecoveries=0; pictureRecoveryAt=0L
    // 相邻那一部的首集如果已经预热好，直接把它交给播放内核：不用再取地址、读索引、
    // 拉首批数据，切换就是「换一帧」而不是「等一次网络」。取走之后所有权归播放器。
    val key=preparedKey(data)
    val fromStart=position<=0L
    // 这一部正好在预热、而且这次要从头起播：**不取消**它。
    //
    // 早先这里会取消（理由是「同一集会被拉两遍」）。在遥控器上一秒一下的节奏里那是对的：
    // 取消掉的那一份本来也来不及用。但手机上一秒划两三部时，取消就是「每一次都掐掉
    // 一个已经下了一半的预热」——在途的永远完不成，[recommendPrepared] 一直是空的，
    // 于是每一次切换都是冷启动。多花的那 1MB 换的是「划回来时已经在本地」，值得。
    // 真正要取消的是**离开推荐页 / 进正片**那两次（见 [cancelRecommendWarm]）。
    // 唯一的例外是移动网络：那里只开一个预热槽，留着它等于同一集拉两遍（白烧流量），
    // 这时才取消——省流量优先于「划回来更快」。
    if(fromStart && key.isNotEmpty() && runCatching { connectivity.isMeteredConnection() }.getOrDefault(false)) {
        cancelRecommendWarm(key)
    }
    val prepared=if(fromStart) recommendPrepared.remove(key) else null
    // 取走之后下标/时刻表也要跟着摘掉，否则淘汰时会对着一个已经不存在的键算分。
    if(prepared!=null) { recommendPreparedIndex.remove(key); recommendPreparedAt.remove(key) }
    // 起播路径的命中情况：一次换剧到底靠的是内存预热、磁盘索引还是全新拉取。
    // 只在调试包打（`Playback first frame` 那一行只给总时长，看不出钱花在哪儿）。
    if(BuildConfig.DEBUG) {
        // 地址键带 `device_level`（见 `ContentRepository.stream`）；
        // 索引 / 首块键带的是「集 + 实际档位」（见 `StreamInfo.mediaKey`）。
        // 早先这里把两段拼成一个键去查，`meta` / `head` 永远是 0——日志上看着像「缓存没用上」。
        val episode=data.episodes.firstOrNull().orEmpty()
        val urlKey="$episode:${playbackMaxQuality}:${repository.deviceLevel}"
        // 索引键优先用**这一份预热实际取到的**那个（`mediaKey` 用实际档位，可能低于上限）。
        val mediaKey=prepared?.info?.mediaKey(episode) ?: key
        android.util.Log.i("HGTV-PERF","switch prepared=${if(prepared!=null) 1 else 0} "+
            com.hgtv.star.core.PlaybackCache.probe(urlKey,mediaKey)+" level=${recommendWarmed[key] ?: "-"}")
    }
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
    syncPlaybackInfo()
    // 底部浮层由 [playEpisode] 收尾统一挂（重建整棵树与复用播放器两条路都会走到那里，
    // 它按新的 `detail.series` 重挂，并带上这一下的滑动方向动画）；这里只把数值刷上去。
    updateRecommendOverlay()
    // 手机 / 平板：每次进推荐页、每次上下换剧，底部那条信息（剧名 / 第几集 / 进度 / 状态）
    // 都**默认露出来**，不用先点一下画面。电视不走这条——那一页的信息是常显的底部浮层
    // （见 `attachRecommendOverlay`），不需要也不该弹出控制条。
    // 露出来之后按设置里的秒数自动收起（与正式播放同一个计时器，见 `showHud` /
    // `scheduleOverlayAutoHide`）；轻点画面照旧随时能切显隐。上下换剧走到这里会重新唤出，
    // 计时也随之重排——用户每换一部都还能看到「这是哪一部、看到第几集、播到哪儿」。
    if(touchArmed) showHud()
    // 相邻预热不在这里立刻发起：首帧上屏后再排（见播放监听器的 READY 分支与
    // `scheduleRecommendPreload`）。此刻发起会与**这一部自己的首块**抢带宽。
    scheduleRecommendPreload(slideCache.firstFrameDelay(),"switch")
    scheduleRecommendDwell()
    // 例外：这一次是**内存预热命中**（首块都在手里，这一部一个网络请求都不用发），
    // 带宽此刻是空的，顺手把下一部的地址取回来（20KB，不取首块）。
    // 遥控器「一秒一下」的节奏里 2 秒的窗口预热根本来不及跑，这一条是那段时间的兜底。
    if(prepared!=null) {
        val nextIndex=recommendIndex+slideCache.direction
        val next=recommendItems.getOrNull(nextIndex)
        if(next!=null) warmRecommend(next,nextIndex,com.hgtv.star.core.CacheLevel.ADDRESS)
    }
    // 快刷到这一批末尾了：把下一页提前取回来，别让用户在末尾那一下等网络（见 [ensureRecommendLookahead]）。
    ensureRecommendLookahead()
}

/**
 * 预热用的缓存键：`集id:清晰度`。
 *
 * 换清晰度之后旧的那份自然对不上、会被丢掉——档位一变就是**另一个文件**，
 * 沿用旧键会把别的码流交给播放器，也可能把刚预热好的那份顶掉。
 * 全站只有 HEVC 一种编码，键里不再需要编码族那一段。
 */
internal fun MainActivity.preparedKey(data: Detail): String {
    val id=data.episodes.firstOrNull() ?: return ""
    return "$id:${playbackMaxQuality}"
}

/**
 * 把推荐列表里当前这一部**前后相邻的几部**预热起来。
 *
 * 分两步，都是为了「一划就换台」：
 * 1. 详情（分集表）：一部几百毫秒，先取回来放进 [recommendDetails]；
 * 2. 首集数据：详情到手后取播放地址并把索引 / 前 1MB 拉下来（[RemoteVideo.warm]），
 *    放进 [recommendPrepared]，起播时直接交给播放器。
 *
 * ## 为什么按方向、而且不止一部
 *
 * 遥控器的节奏是「按住 → 一秒一下」，预热 ±1 就够：每换一部，新邻居刚好被上一次预热覆盖。
 * 手机 / 平板是**一划一部、一秒能划两三部**：只预热 ±1 时，第二下就落在没有预热的条目上，
 * 于是「每一次切换都在等网络」——观感就是「手机上特别卡」。
 * 因此顺序是「正在走的方向两步 → 反方向一步」，在途上限见 [warmRecommendDetail]。
 */
internal fun MainActivity.prefetchRecommendNeighbors(index: Int,direction: Int=recommendPrefetchDir) {
    if(screen!="recommend") return
    // 窗口与级别由 [SlideCacheManager] 决定（非对称前后窗口 + fling 降级 + 回看提升），
    // 这里只按它给的清单发起。原先写死的「前两步 + 反方向一步」在双向滑动下有两个洞：
    // 回看方向没有货、连划时四条 1MB 互相抢带宽。
    applyRecommendPlan(slideCache.plan(index),"window")
}

/** 同时在途的预热条数上限（WiFi）。再多只会互相抢带宽，当前这一部反而起播更慢。 */
internal const val RECOMMEND_WARM_SLOTS=2
/** 同时在途的预热条数上限（移动网络）：够「下一部」就好，别拿用户流量去猜方向。 */
internal const val RECOMMEND_WARM_SLOTS_METERED=1
/** 预热好的首集最多留几份（每份约 1MB）。6 份 = 连划五六部都还是「一帧就换」。 */
internal const val RECOMMEND_PREPARED_LIMIT=6
/** 同时在途的「相邻详情」请求上限：给当前这一部的详情留出线程（见 `prefetchRecommendNeighbors`）。 */
internal const val DETAIL_PREFETCH_LIMIT=2
/** 推荐页详情缓存留几部：左右相邻来回切够用，再多只是占内存。 */
internal const val RECOMMEND_DETAIL_LIMIT=6

/**
 * 取首集地址并预热（索引 + 前 1MB）。已经有同一份、或正在预热同一份时直接跳过。
 *
 * 三条与「手机比电视卡」直接相关的取舍：
 *
 * * **不再因为「移动网络」整条退出**。早先这里对 `isMeteredConnection()` 直接 return：
 *   电视永远是 WiFi / 有线，所以电视一切正常；手机一用流量，预热**一次都没有**，
 *   每次切换都是「详情 0.5s + 取地址 + 首批数据 1s」的冷启动——那才是触屏上那股卡劲。
 *   用户本来就在放视频流，相邻一部的 1MB 与它同一个量级，省这一下毫无意义。
 *   现在改成「在途条数减到 1」，流量上仍然克制。
 * * **多槽**：单槽时新的一次会被旧的那次挡回去（`if(recommendWarming.isNotEmpty()) return`），
 *   连划几下就再也没有预热落地，[recommendPrepared] 一直是空的。
 * * **取消要连网络一起掐掉**（[cancelRecommendWarm]）：被作废的预热不占带宽。
 */
internal fun MainActivity.warmRecommendDetail(data: Detail,level: com.hgtv.star.core.CacheLevel=com.hgtv.star.core.CacheLevel.FULL,index: Int=-1) {
    if(screen!="recommend") return
    // 推荐首屏还在转圈 / 当前这一部还没出画面：这时候预热只是往 `modelGate` 里排队，
    // 把**正在等的这一次取地址**推到后面去。
    //
    // 实测（2026-10-27，S905L3B / 2G）：冷启动进推荐，`Stage stream` 花了 2049ms，
    // 而这段时间里 `modelGate` 上排着三个请求——正片起播、相邻预热、下一集预取。
    // 三个都是 0.6~1.0 秒的往返，串行加起来正好两秒。
    if(!playbackReady) return
    // 正在放的这一部不预热：它的首集已经在播放器手里（`recommendPrepared` 里那份已被取走），
    // 再 warm 一次等于把同一个 22MB 文件的 moov **再解析一遍**（两万多个样本 + 一次排序），
    // 两条线程各占一个核，起播那一下反而更慢。
    if(data.series.id.isNotEmpty() && data.series.id==recommendItems.getOrNull(recommendIndex)?.id) return
    val key=preparedKey(data)
    if(key.isEmpty()) return
    // 已经到这个级别（或更高）就跳过：ADDRESS 铺过之后 2 秒再升 FULL 时，不必重取一次地址。
    val achieved=recommendWarmed[key]
    if(achieved!=null && achieved>=level) return
    if(key in recommendWarming) return
    if(!networkAvailable()) return
    val metered=runCatching { connectivity.isMeteredConnection() }.getOrDefault(false)
    // 分级并发上限（文档第 7 节）：同一时刻最多 1 个完整预热 + 2 个元数据 + 3 个地址。
    // 低端机网络只有一条通道，多开只会让**当前这一部**的首帧更慢。
    if(!hasWarmSlot(level,metered)) {
        if(BuildConfig.DEBUG) android.util.Log.i("HGTV-PERF","preloadSkip reason=slot level=$level inFlight=${recommendWarming.size}")
        return
    }
    recommendWarming+=key
    val warm=RecommendWarm()
    recommendWarmJobs[key]=warm
    val quality=playbackMaxQuality
    warm.future=recommendWarmWorker.submit {
        var remote: RemoteVideo?=null
        try {
            // client 与仓库都在工作线程上拿：`PlaybackTransport.client` 是懒建的，
            // 第一次触碰要建一个 OkHttpClient（解析系统根证书表）。放在主线程上取，
            // 等于把冷启动那段成本搬到「划一下」的那一帧里。
            val http=com.hgtv.star.data.PlaybackTransport.client
            val source=ContentRepository(http)
            source.credentials=repository.credentials
            source.inheritDevice(repository)
            source.deviceLevel=repository.deviceLevel
            if(!library.deviceRegistered()) { registerDeviceOnce(); source.inheritDevice(repository) }
            // 整段都在这一次预热自己的取消域里：划过去之后 `cancelRecommendWarm` 能立刻
            // 把在途的 HTTP 掐掉（否则那个线程还在读 512KB，结果没人要）。
            val episode=data.episodes.first()
            val prepared=warm.scope.run {
                when(level) {
                    // L3：只取地址（写盘），一个视频字节都不拉。
                    com.hgtv.star.core.CacheLevel.ADDRESS -> {
                        source.stream(episode,quality)
                        null
                    }
                    // L2：地址 + 索引（写盘），不取首块——切过去时磁盘命中，免 880ms 解析。
                    com.hgtv.star.core.CacheLevel.META -> {
                        val info=source.stream(episode,quality)
                        RemoteVideo(http,info,info.mediaKey(episode)).also { remote=it; it.warmMeta() }
                    }
                    // L1：地址 + 索引 + 首块 512KB，留在内存里等切换。
                    else -> {
                        val info=source.stream(episode,quality)
                        RemoteVideo(http,info,info.mediaKey(episode)).also { remote=it; it.warm(512L*1024) }
                    }
                }
            }
            main.post {
                // 只摘掉**自己**那一份：取消之后同一个键可能已经被新的一次预热占了
                // （划回去又划过来），无条件 remove 会把新的那一次从表里抹掉，
                // 它回来时就认不出自己、白拉一遍。
                val current=recommendWarmJobs[key]
                if(current===warm) {
                    recommendWarmJobs.remove(key)
                    recommendWarming-=key
                } else {
                    // 这一次预热已经被取消（它正好被起播了 / 划过去了 / 离开了推荐页）：
                    // 结果没人要，当场关掉那 1MB 与它的连接。
                    runCatching { prepared?.close() }
                    return@post
                }
                if(isDestroyed || screen!="recommend") { runCatching { prepared?.close() }; return@post }
                // 级别只升不降：这一次做到了哪一级，下一次就不会再重复做更低的那一级。
                val previous=recommendWarmed[key]
                if(previous==null || previous<level) recommendWarmed[key]=level
                if(prepared!=null) {
                    recommendPrepared[key]=prepared
                    recommendPreparedIndex[key]=if(index>=0) index else recommendItems.indexOfFirst { it.id==data.series.id }
                    recommendPreparedAt[key]=android.os.SystemClock.elapsedRealtime()
                    evictRecommendPrepared()
                }
                // 空出来的槽位马上补上：连划时总有两条在转（见 [prefetchRecommendNeighbors]）。
                if(recommendWarming.size<RECOMMEND_WARM_SLOTS) prefetchRecommendNeighbors(recommendIndex)
            }
        } catch(_: Exception) {
            remote?.close()
            main.post {
                if(recommendWarmJobs.remove(key)===warm) recommendWarming-=key
            }
        }
    }
}

/**
 * 这一级还有没有并发额度（文档第 7 节的优先级表）。
 *
 * 完整预热最贵（512KB + 一次索引解析），所以只给 1 个名额：多开两个的结果是
 * 三条线程各拿三分之一带宽，谁都不够快。地址预取只有 20KB，可以多开几条。
 */
private fun MainActivity.hasWarmSlot(level: com.hgtv.star.core.CacheLevel,metered: Boolean): Boolean {
    val inFlight=recommendWarming.size
    return when(level) {
        com.hgtv.star.core.CacheLevel.FULL -> inFlight<(if(metered) 1 else RECOMMEND_WARM_SLOTS)
        com.hgtv.star.core.CacheLevel.META -> inFlight<(if(metered) 1 else 3)
        else -> inFlight<(if(metered) 2 else 4)
    }
}

/**
 * 换剧那一下把画面盖黑（需求：上下切换要有过渡，且不能看到拉伸帧）。
 *
 * 时机是**按键那一刻**，不是新源准备好之后：先把上一部淡出成黑，再换源、等新源第一帧，
 * 由 [revealRecommendCover] 淡回来。这样整段过渡只有「暗 → 亮」，中间不会出现
 * 「上一部的画面被新比例拉一下」那一帧。
 *
 * 行为与 1.2.4.2 一致：不抓帧、不翻页，就是一层纯黑淡入淡出。
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
 *
 * 行为与 1.2.4.2 一致：就是一次淡出，不做抓帧翻页。
 */
internal fun MainActivity.revealRecommendCover() {
    val cover=recommendCover ?: return
    recommendCoverReveal?.let { main.removeCallbacks(it) }
    recommendCoverReveal=null
    recommendCoverToken++
    cover.animate().cancel()
    if(!TvStyle.animate) { dismissRecommendCover(); return }
    // 连划（触屏）时淡出收短：一秒划两三部的时候，每一次都在「换源 0.2~0.6s + 淡出 0.26s」
    // 之后才重新看到画面，读起来就是「卡」。淡出只是收尾的柔和度，连划时短一点更跟手。
    // 电视不受影响（`touchArmed` 恒为 false，淡出仍是 260ms，与 1.2.4.2 逐帧一致）。
    val rapid=touchArmed && recommendLastSwitchAt!=0L &&
        android.os.SystemClock.elapsedRealtime()-recommendLastSwitchAt<900L
    val duration=if(rapid) COVER_OUT_FAST_MS else COVER_OUT_MS
    cover.animate().alpha(0f).setDuration(duration).withEndAction {
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
    // 黑场挂在 root 上（**不进画面之外那一层**）：那一层会跟着画面转 90°，
    // 黑场进去也会跟着转，转过去之后四个角会露出没盖住的画面。
    // 它只是一整屏纯黑，挂在 root 上盖住全屏就够了。
    //
    // 插在画面那一层**之前**：画面被黑场盖住，而压在画面上的剧名 / 进度 / 控制条
    // 仍然看得见（过渡期间用户还在看「换到第几部了」）。
    val layer=overlayLayer
    val index=if(layer!=null && layer.parent===root) root.indexOfChild(layer) else root.childCount
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
    // 触屏的推荐页拖的是**控制条上那条进度**（浮层不存在），因此两个读数都要写：
    // 浮层里的（电视）+ 控制条里的（触屏）。两处都是空安全，另一条自然是空操作。
    recommendProgress?.progress=fraction
    recommendSpan?.let { line ->
        val text="${formatTime(target)} / ${formatTime(duration)}"
        if(line.text.toString()!=text) line.text=text
    }
    if(touchArmed) {
        progressBar.progress=fraction
        playbackText.text="${formatSpan(target)} / ${formatSpan(duration)}"
    }
    if(!finished) return
    main.removeCallbacks(seekRunnable); pendingSeek=null
    p.seekTo(target)
    updateRecommendOverlay()
}

/** 推荐页底部那块信息浮层：进度 + 剧名 + 集数 + 操作说明。 */
internal fun MainActivity.attachRecommendOverlay(series: Series) {
    dismissRecommendOverlay()
    // **触屏设备不再建这块浮层**（需求 1）：推荐页改用播放界面那一条控制条
    //（顶部信息条 + 底部进度 / 状态项 / 六枚可点按钮，见 `attachTouchBar` / `showHud`），
    // 轻点画面唤出。浮层如果还留着，它就压在控制条上面，而且那排「看正片 / 暂停 / 换一部」
    // 与六键控制条功能重叠（前者已被用户点名去掉）。
    // 电视上 `touchArmed` 恒为 false，这条提前返回不会执行，浮层与今天逐行一致。
    if(touchArmed) return
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
    recommendTitle=title; recommendMeta=meta; recommendOverlay=bar
    // 播放设置开着时浮层让位（菜单贴底占了下三分之一），收起菜单时再露出来。
    bar.visibility=if(playbackMenu!=null) View.GONE else View.VISIBLE
    // 挂进「画面之外那一层」：竖屏短剧全屏把画面转 90° 时，这块浮层要跟着一起转，
    // 否则剧名 / 进度 / 操作提示会横在转了过来的画面上。电视上不旋转时两者完全等价。
    (overlayLayer ?: root).addView(bar,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
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
    // 进度条是浮层的子控件，随浮层一起消失；引用一并清掉，免得下一帧刷到已摘下的控件。
    recommendProgress=null; recommendSpan=null
}

/** 刷新浮层上的集数 / 进度那一行。 */
internal fun MainActivity.updateRecommendOverlay() {
    val data=detail ?: return
    if(screen!="recommend") return
    val p=player
    // 触屏的推荐页没有浮层：它的读数（进度、时间、集数、清晰度 / 倍速 / 画面 / 弹幕 / 定时）
    // 全在控制条上，走的是与播放界面同一段 `updatePlaybackText`。
    // 放在下面那些空安全的浮层写入之前：这一段是这一页**唯一**会刷新控制条的地方。
    //
    // 控制条收起来的时候不刷那一排读数：每秒一次、写的是看不见的控件，而这条函数正是
    // 每秒 tick 都会走到的地方（触屏上「刷着刷着就有点发涩」有一份来自这里）。
    // 露出来的时候 tick 已经先刷过一遍（见 `tick` 里的 `hud.visibility==VISIBLE`），
    // 轻点唤出那一下由 `showHud` 自己刷，因此这里跳过不会少一次刷新。
    if(touchArmed) {
        syncRecommendEntry()
        if(runCatching { hud.visibility==View.VISIBLE }.getOrDefault(false)) updatePlaybackText()
    }
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
 * 往下走到末尾时：还有下一页就先补一页再接着走（正常情况下这一页已经被
 * [ensureRecommendLookahead] 提前取好了，切换是即时的一帧）；真的取不到新内容就停在
 * 当前这一部并提示，**不环绕回第 1 部**——那会把刚刷过的那批从头重放一遍。
 * 往回按到头才环绕到末尾（往回刷重复一部不算问题，也不会打断观感）。
 */
internal fun MainActivity.switchRecommend(direction: Int) {
    if(screen!="recommend") return
    // 用户自己换了一部：上一次那部的失败不再累计。
    recommendPlaybackFailures=0
    if(playbackMenu!=null) hidePlaybackOverlay()
    if(recommendItems.isEmpty()) { openRecommend(); return }
    val from=recommendIndex
    val next=from+direction
    when {
        // 目标在列表里：这一下按键就是过渡的起点，先把画面盖黑再换（见 [showRecommendCover]）。
        next in recommendItems.indices -> {
            if(playerView!=null) showRecommendCover()
            playRecommendAt(next,0L,direction)
        }
        // 走到末尾：先补一页再往下走。这条流已经到底（`has_more=false`）时，补页那一侧
        // 会换一条新流（见 `loadRecommendPage`）。补页发不出去 / 失败时回调会立刻到，
        // 这时列表没变长，就环绕回第一部（抖音式的无限流，不会停在「按了没反应」上）。
        direction>0 -> {
            val guard=recommendToken.get()
            // 这一下按的是「再往下」，但页还没到：把意图记下来，页回来后一次跨过去。
            recommendAdvancePending++
            val before=recommendItems.size
            // **不盖黑场**：页还没到，盖黑只会让用户对着一块黑屏（这就是「有一个画面没播放
            // 就被略过」的观感来源）。让当前这一部继续播，等页回来、真要换的时候才盖。
            // 正常情况下根本走不到这里——快刷到末尾时 [ensureRecommendLookahead] 已经把页取好了。
            loadRecommendPage(true) {
                if(recommendToken.get()!=guard) return@loadRecommendPage
                val steps=recommendAdvancePending.coerceAtLeast(1)
                recommendAdvancePending=0
                // 页真的长出新条目：按累计的按键次数往前跨（连按 10 下就跳 10 部），
                // 而不是只挪 1 部、也不是回到第 0 部把刚刷过的重放一遍。
                if(recommendItems.size>before) {
                    if(playerView!=null) showRecommendCover()
                    playRecommendAt((from+steps).coerceAtMost(recommendItems.lastIndex),0L,1)
                } else {
                    // 页没长：这条流确实没有新内容了。停在当前这一部并说明，
                    // **绝不环绕回第 1 部**——那正是用户看到的「又把刚才那批刷了一遍」。
                    showRecommendToast("这批推荐刷完了，稍后再来")
                }
            }
        }
        // 往上按到头：环绕到末尾（抖音式的无限流；往回刷重复一部不算问题）。
        else -> {
            if(playerView!=null) showRecommendCover()
            playRecommendAt(recommendItems.lastIndex,0L,direction)
        }
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
    // 在途的相邻预热到此为止：它们拉的是「别的剧」，进了正片就没人要了，
    // 留着只会和正片开头的「下一集预取」抢带宽（两条线程都在拉 1MB）。
    // 已经预热好的那几份留着（回推荐页还能用）。
    cancelRecommendWarm()
    recommendToken.incrementAndGet()
    dismissRecommendOverlay()
    hud.visibility=View.GONE
    syncPlaybackInfo()
    // 这一页的控件是「推荐页」那一次建的（`playEpisode` 没有重跑），手势层 / 底部那一块 /
    // 进度拖动因此在这里重接一次（三者都幂等）。**必须先把推荐页那块摘掉**：
    // 那一块只有一枚剧名（点它进正片），正片里要的是六枚按键，两者内容不同。
    if(touchArmed) { attachTouchLayer(); resetTouchBar(); attachTouchBar(); wireSeekTouch() }
    // 进正片 = 开始正式观看：立刻把这一集记进历史。
    // 画面还没 READY 时 `saveProgress` 会拒绝写（时长未知），这时挂一个待写标记，
    // 由播放监听器的 READY 分支补上——「进了正片就一定有历史」不依赖时序。
    recommendHistoryPending=true
    saveProgress()
    // 正式开看：与从详情页进播放同一件事——「已看完」标记清掉，首页 / 历史里显示成「看到第几集」。
    library.setWatched(data.series.id,false)
    // 按确定进正片 = 明确要看这一部：直接按「看了」记 30 天冷却，并停掉停留计时，
    // 免得随后那次 `goHomeFromRecommend` 又把它记成一次「划走」（取两者更长的那个，本来也不会缩短，
    // 但少写一次盘）。
    recommendWatchingId=""; recommendWatchStart=0L
    library.markRecommendWatched(data.series.id)
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
 * 与正式播放的 [seek] 同一套步长与手感（短按按设置里的「快进快退时长」、连按 3 倍、250ms 内合并成一次跳转）。
 * 反馈位置按设备分：电视上写着底部浮层那一行（[showRecommendToast]），触屏上是一条瞬时提示
 * （同一条 toast 在触屏分支里改挂手势层，见 [showRecommendToast]）。
 * 这里**不调** `showHud()`：遥控器的左右键只服务于刷剧，不该顺手把控制条弹出来；
 * 触屏那边唤出控制条是「轻点画面」的事（见 `MainActivityTouch`）。
 */
internal fun MainActivity.seekRecommend(direction: Int,repeat: Int) {
    if(screen!="recommend") return
    val p=player ?: return
    if(!playbackReady) return
    val duration=p.duration
    if(duration<=0) return
    // 与正片同一份步长（设置页「播放偏好 · 快进快退」）：两页的左右键不能是两套手感。
    val base=library.seekStep*1000L
    val step=if(repeat>4) base*3 else base
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
    // 离开推荐页：把这一部的停留时长结掉（看了 → 30 天冷却，划走 → 14 天）。
    settleRecommendWatch()
    // 补页在途 / 排队的连按意图一起作废：回到首页后它们已经没有去处，
    // 留着会让下次进推荐的第一次按键凭空多跨几部。
    recommendWaiters.clear(); recommendAdvancePending=0
    recommendToken.incrementAndGet(); recommendPageToken.incrementAndGet()
    recommendPlaybackFailures=0; recommendHistoryPending=false; playbackFromRecommend=false
    dismissRecommendCover()
    // 在途的「取推荐流」这一下作废：它的回调会因为令牌 / 页面不对而丢弃，
    // 这个标记必须在这里一起清掉，否则下次进推荐会一直以为还在加载、什么都不做。
    recommendLoading=false
    // 相邻剧集的预热：只留**当前这一部**那一份（约 1MB），其余收掉。
    //
    // 早先这里整份 `closeRecommendCache()`（连 `recommendItems` / `recommendDetails` 一起清），
    // 于是「主页 → 推荐」变成一次完整冷启动——用户读到的就是「缓存没用上」。
    // 现在：列表与详情留在内存（几十 KB），首集只保一份，回来时按原下标接着放，
    // 取地址 / 索引都命中磁盘（见 `PlaybackCache`）。
    cancelRecommendWarm()
    cancelScheduledPreload()
    evictRecommendPrepared(limit=1)
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
