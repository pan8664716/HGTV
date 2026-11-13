// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.Space
import com.google.android.exoplayer2.Player
import com.hgtv.star.shared.compat.isMeteredConnection
import com.hgtv.star.shared.architecture.network
import com.hgtv.star.shared.data.AppRuntime
import com.hgtv.star.shared.debug.StartupTrace
import com.hgtv.star.core.CategoryFilterOption
import com.hgtv.star.core.CategorySelection
import com.hgtv.star.core.Detail
import com.hgtv.star.core.HeatLabel
import com.hgtv.star.core.RemoteVideo
import com.hgtv.star.core.SeekStep
import com.hgtv.star.core.Series
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.tv.list.showCatalog
import com.hgtv.star.core.lruPut
import com.hgtv.star.shared.ui.LoadingDotsView

// 本文件是「推荐」这一条独立的播放链路：抖音式的上下换剧、确定进正片、菜单开播放设置、
// 返回回主页面。它复用播放内核（playEpisode / 播放器 / 弹幕 / 播放设置菜单），
// 只在四件事上与正式播放不同：
//
// 1. [TvMainActivity.screen] 是 `recommend` 而不是 `player`——返回键回主页面、上下键换剧；
// 2. **不写观看历史**（见 `saveProgress` 的守卫）：只有进入正式播放那一刻才写；
// 3. 不挂「下一集」队列（见 `maybePrefetch`）：一集播完直接进正片，由正片接着播下一集；
// 4. 没有可聚焦控件：所有方向键都由 `dispatchKeyEvent` 消化，焦点不会跑出画面。

/** 推荐流一次取多少（分类接口的「全部推荐」流第一页就是 20 条，之后每页 100 条）。 */
const val RECOMMEND_SKIP_LIMIT=4
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
internal const val RECOMMEND_MAX_CHAIN=com.hgtv.star.shared.feed.RecommendFeedPolicy.MAX_PAGES_PER_LOAD
/**
 * 首屏一次要多少条。
 *
 * 与 20 条同一个价（940ms vs 780ms），但候选是 3 倍：一次就凑出「够新鲜的一页」的概率高得多，
 * 少翻的页数远大于那 160ms。之后补页仍按 20 条。
 */
private const val RECOMMEND_FIRST_LIMIT=com.hgtv.star.shared.feed.RecommendFeedPolicy.FIRST_LIMIT
/**
 * 分类接口一页可能因续流、筛选和去重少于首屏目标条数。
 *
 * TV 首屏必须至少凑够这一批，否则缓存与当前列表都会长期停在 3 条：
 * 下一次启动只能在这 3 条里选，表现就是「每次都是同一部」。
 */
internal const val RECOMMEND_FIRST_ITEMS=com.hgtv.star.shared.feed.RecommendFeedPolicy.MIN_ITEMS_PER_LOAD
/**
 * 补页一次要多少条。
 *
 * 同样是 60：一页 20 条与 60 条的耗时只差约 160ms（实测 775ms / 942ms），但「两次补页之间
 * 用户能连刷多少部」直接变成 3 倍。早先 20 条时，一次补页的净新条目只有 13~17 部
 * （补页那 20 条里总有一部分已经刷过 / 同名的被滤掉），于是每刷十几部就撞一次补页——
 * 用户看到的就是「隔十几部黑一下、跳过去一部」。
 */
private const val RECOMMEND_PAGE_LIMIT=com.hgtv.star.shared.feed.RecommendFeedPolicy.PAGE_LIMIT
/**
 * 快刷到这一批末尾还剩这么多部时，提前把下一页取回来（[ensureRecommendLookahead]）。
 *
 * 10 部的提前量：连按十几下时用户从第 N-10 部按到第 N 部可能只有 3~5 秒，而一次补页
 * 实测 0.8~2 秒（网络差时更久）——3 部的余量在「快按」时来不及，所以放宽到 10 部。
 * 代价只是一次更早的后台请求，用户感知不到；换来的是按到末尾那一帧已经有内容。
 */
internal const val RECOMMEND_LOOKAHEAD=10
/**
 * 首屏最多等多久（毫秒）。超过它就不再往下凑「一条重复都没有」，把手里这一页照原样放出来。
 *
 * 依据同一组实测：正常一页 0.8s、换流一次 0.8s，3 秒足够走完「取一页 + 换一条流」；
 * 再等下去用户面对的就是一块「正在加载推荐…」的静止画面——那比看到两部看过的更糟。
 */
private const val RECOMMEND_FIRST_BUDGET_MS=com.hgtv.star.shared.feed.RecommendFeedPolicy.FIRST_BUDGET_MS
/**
 * 落盘游标深到这个位置就不再往下取，直接回流头。
 *
 * 服务端在 `next_offset=2000` 上钳位，越靠近它越只剩旧货；而从流头重新采样
 * （新 `session_id`）拿到的分布是一样的、排序是新的（见文档 §3.1）。
 */
private const val RECOMMEND_DEEP_CURSOR=com.hgtv.star.shared.feed.RecommendFeedPolicy.DEEP_CURSOR
/**
 * 「带着 `filter_ids` 失败」的判定门槛（毫秒）。
 *
 * 只有这么快就回来的失败才值得再发一次不带过滤的请求：网关不认这个字段是立刻回错的，
 * 而超时 / 断网那种慢失败重发一遍只是把等待时间翻倍。
 */
private const val RECOMMEND_FILTER_RETRY_MS=com.hgtv.star.shared.feed.RecommendFeedPolicy.QUICK_FILTER_FAILURE_MS
/**
 * 同一次加载里最多换几次「新流」。
 *
 * 换新流解决的是实测到的两处死水：
 * * 游标撞到服务端天花板——`next_offset` 永远停在 2000，之后每次打开都回同一批；
 * * 一条流刷到后段（`session_id` 只用 20 多分钟就开始衰减）回的全是推过的。
 *
 * 换两次仍然全是旧货，就不再纠缠，交给用户（上下键照样能看）。
 */
internal const val RECOMMEND_MAX_RESTARTS=com.hgtv.star.shared.feed.RecommendFeedPolicy.MAX_RESTARTS

/**
 * TV 的推荐 Store。策略来自共享的 standard 配置，之后页面只读 Store 状态。
 */
fun TvMainActivity.recommendStore(): com.hgtv.star.shared.feed.RecommendStore =
    recommendStoreRef ?: com.hgtv.star.shared.feed.RecommendStore(
        repository = repository,
        library = library,
        // 热度推荐流的独立快照命名空间：官方书包流的快照与游标不再复用。
        policy = com.hgtv.star.shared.feed.RecommendFeedPolicy.standard(
            cacheNamespace = "heat-v1",
        ),
        debug = { line ->
            if(com.hgtv.star.tv.BuildConfig.DEBUG) {
                android.util.Log.i("HGTV", "Recommend $line")
            }
        },
    ).also { recommendStoreRef=it }
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

/** 相邻一部的详情 / 地址 / 首块优先；播放模型批量预取晚一点再跑。 */
internal const val RECOMMEND_BATCH_MODEL_DELAY_MS=3_000L

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
fun TvMainActivity.prefetchRecommendNeighbors(index: Int,direction: Int=recommendPrefetchDir) {
    if(screen!="recommend") return
    // 窗口与级别由 [SlideCacheManager] 决定（非对称前后窗口 + fling 降级 + 回看提升），
    // 这里只按它给的清单发起。原先写死的「前两步 + 反方向一步」在双向滑动下有两个洞：
    // 回看方向没有货、连划时四条 1MB 互相抢带宽。
    applyRecommendPlan(slideCache.plan(index),"window")
}

/** 换剧 / 进正片时作废上一部还没发完的推荐批量预取。 */
fun TvMainActivity.cancelRecommendBatchModels() {
    recommendBatchJob?.cancel(true)
    recommendBatchJob=null
    recommendBatchKey=""
}

/**
 * 推荐页起播后预取 6 个播放模型：
 * 当前剧的下一集 + 后面 5 部不同推荐剧的首集。
 */
fun TvMainActivity.prefetchRecommendBatchModels() {
    if(screen!="recommend") return
    val data=detail ?: return
    val currentNext=data.episodes.getOrNull(episodeIndex+1).orEmpty()
    val candidates=(recommendIndex+1 until recommendItems.size)
        .mapNotNull { recommendItems.getOrNull(it) }
        .filter { it.id!=data.series.id }
        .distinctBy { it.id }
    val key="${data.series.id}:$currentNext:${candidates.take(8).joinToString(",") { it.id }}"
    if(recommendBatchKey==key && recommendBatchJob?.isDone==false) return
    recommendBatchJob?.cancel(true)
    recommendBatchKey=key
    // 主线程先取一份详情快照，后台线程不再直接读写这个 LRU。
    val known=buildMap {
        for(series in candidates) recommendDetails[series.id]?.let { put(series.id,it) }
    }
    recommendBatchJob=recommendWarmWorker.submit {
        val ids=linkedSetOf<String>()
        currentNext.takeIf { it.isNotBlank() }?.let(ids::add)
        try {
            if(!library.deviceRegistered()) registerDeviceOnce()
            val target=if(currentNext.isBlank()) 5 else 6
            for(series in candidates) {
                if(ids.size>=target) break
                val item=known[series.id]
                    ?: runCatching { repository.detail(series.id) }.getOrNull()
                    ?: continue
                val first=item.episodes.firstOrNull() ?: continue
                if(ids.add(first) && known[series.id]==null) {
                    main.post { if(!isDestroyed) recommendDetails.lruPut(RECOMMEND_DETAIL_LIMIT,series.id,item) }
                }
            }
            if(ids.isNotEmpty()) repository.prefetchModels(ids.toList(),data.playback)
            if(BuildConfig.DEBUG) android.util.Log.i("HGTV-PERF","recommend batch models=${ids.size} currentNext=${currentNext.isNotEmpty()}")
        } catch(_: Exception) {
            // 批量模型是后台加速，不影响当前推荐起播。
        } finally {
            main.post {
                if(recommendBatchKey==key) {
                    recommendBatchJob=null
                    recommendBatchKey=""
                }
            }
        }
    }
}

/** 正式播放首帧稳定后，用 preload 在后台补当前剧后 6 集。 */
fun TvMainActivity.prefetchFormalBatchModels() {
    if(screen!="player") return
    val data=detail ?: return
    // 给 preload 一个更宽窗口；仓库会跳过 player 已缓存的当前 6 集，只补后面的 6 集。
    val ids=data.episodes.drop(episodeIndex+1).take(12)
    if(ids.isEmpty()) return
    val key="${data.series.id}:$episodeIndex:${ids.joinToString(",")}"
    if(formalBatchKey==key && formalBatchJob?.isDone==false) return
    formalBatchJob?.cancel(true)
    formalBatchKey=key
    formalBatchJob=recommendWarmWorker.submit {
        try {
            if(!library.deviceRegistered()) registerDeviceOnce()
            repository.prefetchModels(ids,data.playback)
            if(BuildConfig.DEBUG) android.util.Log.i("HGTV-PERF","formal batch models=${ids.size}")
        } catch(_: Exception) {
            // 批量预取失败时，起播仍可走单集兼容兜底。
        } finally {
            main.post {
                if(formalBatchKey==key) {
                    formalBatchJob=null
                    formalBatchKey=""
                }
            }
        }
    }
}

/** 同时在途的预热条数上限（WiFi）。再多只会互相抢带宽，当前这一部反而起播更慢。 */
const val RECOMMEND_WARM_SLOTS=2
/** 同时在途的预热条数上限（移动网络）：够「下一部」就好，别拿用户流量去猜方向。 */
const val RECOMMEND_WARM_SLOTS_METERED=1
/** 预热好的首集最多留几份（每份约 512KB）。8 份 = 连划八部仍然可以直接换源。 */
const val RECOMMEND_PREPARED_LIMIT=8
/** 同时在途的「相邻详情」请求上限：给当前这一部的详情留出线程（见 `prefetchRecommendNeighbors`）。 */
const val DETAIL_PREFETCH_LIMIT=3
/** 推荐页详情缓存留几部：左右相邻来回切够用，再多只是占内存。 */
const val RECOMMEND_DETAIL_LIMIT=8

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
fun TvMainActivity.warmRecommendDetail(data: Detail,level: com.hgtv.star.core.CacheLevel=com.hgtv.star.core.CacheLevel.FULL,index: Int=-1): Boolean {
    if(screen!="recommend") return false
    // 推荐首屏还在转圈 / 当前这一部还没出画面：这时候预热只是往 `modelGate` 里排队，
    // 把**正在等的这一次取地址**推到后面去。
    //
    // 实测（2026-10-27，S905L3B / 2G）：冷启动进推荐，`Stage stream` 花了 2049ms，
    // 而这段时间里 `modelGate` 上排着三个请求——正片起播、相邻预热、下一集预取。
    // 三个都是 0.6~1.0 秒的往返，串行加起来正好两秒。
    if(!playbackReady) return false
    // 正在放的这一部不预热：它的首集已经在播放器手里（`recommendPrepared` 里那份已被取走），
    // 再 warm 一次等于把同一个 22MB 文件的 moov **再解析一遍**（两万多个样本 + 一次排序），
    // 两条线程各占一个核，起播那一下反而更慢。
    if(data.series.id.isNotEmpty() && data.series.id==recommendItems.getOrNull(recommendIndex)?.id) return false
    val key=preparedKey(data)
    if(key.isEmpty()) return false
    // 已经到这个级别（或更高）就跳过：ADDRESS 铺过之后再升 FULL 时，不必重取一次地址。
    val achieved=recommendStore().warmedLevel(key)
    if(achieved!=null && achieved>=level) return false
    if(recommendStore().isWarming(key)) return false
    if(!networkAvailable()) return false
    val metered=runCatching { connectivity.isMeteredConnection() }.getOrDefault(false)
    // 分级并发上限（文档第 7 节）：同一时刻最多 1 个完整预热 + 2 个元数据 + 3 个地址。
    // 低端机网络只有一条通道，多开只会让**当前这一部**的首帧更慢。
    if(!hasWarmSlot(level,metered)) {
        if(BuildConfig.DEBUG) android.util.Log.i("HGTV-PERF","preloadSkip reason=slot level=$level inFlight=${recommendStore().warmingCount()}")
        return false
    }
    recommendStore().beginWarm(key)
    val warm=RecommendWarm()
    recommendWarmJobs[key]=warm
    val quality=playbackMaxQuality
    warm.future=recommendWarmWorker.submit {
        var remote: RemoteVideo?=null
        try {
            // client 与仓库都在工作线程上拿：两者都是懒建 / 进程级共享的，
            // 第一次触碰要建一个 OkHttpClient（解析系统根证书表）。放在主线程上取，
            // 等于把冷启动那段成本搬到「划一下」的那一帧里。
            val http=com.hgtv.star.shared.data.PlaybackTransport.client
            val source=AppRuntime.playbackRepository(this)
            val sourceCoordinator=com.hgtv.star.shared.architecture.PlaybackSourceCoordinator(source,http)
            val warmCoordinator=com.hgtv.star.shared.feed.RecommendWarmCoordinator(source,library,sourceCoordinator)
            if(!library.deviceRegistered()) { registerDeviceOnce(); source.inheritDevice(repository) }
            // 整段都在这一次预热自己的取消域里：划过去之后 `cancelRecommendWarm` 能立刻
            // 把在途的 HTTP 掐掉（否则那个线程还在读 512KB，结果没人要）。
            val prepared=warm.scope.run {
                warmCoordinator.warm(data,data.episodes.first(),quality,level).also { remote=it }
            }
            main.post {
                // 只摘掉**自己**那一份：取消之后同一个键可能已经被新的一次预热占了
                // （划回去又划过来），无条件 remove 会把新的那一次从表里抹掉，
                // 它回来时就认不出自己、白拉一遍。
                val current=recommendWarmJobs[key]
                if(current===warm) {
                    recommendWarmJobs.remove(key)
                    recommendStore().finishWarm(key)
                } else {
                    // 这一次预热已经被取消（它正好被起播了 / 划过去了 / 离开了推荐页）：
                    // 结果没人要，当场关掉那 1MB 与它的连接。
                    runCatching { prepared?.close() }
                    return@post
                }
                if(isDestroyed || screen!="recommend") { runCatching { prepared?.close() }; return@post }
                // 级别只升不降：这一次做到了哪一级，下一次就不会再重复做更低的那一级。
                recommendStore().finishWarm(key,level)
                if(prepared!=null) {
                    recommendPrepared[key]=prepared
                    recommendPreparedIndex[key]=if(index>=0) index else recommendItems.indexOfFirst { it.id==data.series.id }
                    recommendPreparedAt[key]=android.os.SystemClock.elapsedRealtime()
                    evictRecommendPrepared()
                }
                if(BuildConfig.DEBUG) {
                    android.util.Log.i(
                        "HGTV-PERF",
                        "warmDone level=$level index=$index key=$key prepared=${if(prepared!=null) 1 else 0} "+
                            "took=${android.os.SystemClock.elapsedRealtime()-warm.startedAt}ms "+
                            "preparedCount=${recommendPrepared.size}",
                    )
                }
                // 空出来的槽位马上补上：连划时总有两条在转（见 [prefetchRecommendNeighbors]）。
                if(recommendStore().warmingCount()<RECOMMEND_WARM_SLOTS) prefetchRecommendNeighbors(recommendIndex)
            }
        } catch(_: Exception) {
            remote?.close()
            main.post {
                if(recommendWarmJobs.remove(key)===warm) recommendStore().finishWarm(key)
            }
        }
    }
    return true
}

/**
 * 这一级还有没有并发额度（文档第 7 节的优先级表）。
 *
 * 完整预热最贵（512KB + 一次索引解析），所以只给 1 个名额：多开两个的结果是
 * 三条线程各拿三分之一带宽，谁都不够快。地址预取只有 20KB，可以多开几条。
 */
private fun TvMainActivity.hasWarmSlot(level: com.hgtv.star.core.CacheLevel,metered: Boolean): Boolean {
    val inFlight=recommendStore().warmingCount()
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
fun TvMainActivity.showRecommendCover() {
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

fun TvMainActivity.recoverRecommendPicture(reason: String) {
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
fun TvMainActivity.revealRecommendCover() {
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
 * 因此过渡期间剧名仍然看得见，只是画面在黑场里换；浮层自身不受影响。
 */
private fun TvMainActivity.attachRecommendCoverView(cover: View) {
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
fun TvMainActivity.reattachRecommendCover() {
    val cover=recommendCover ?: return
    if(cover.parent!=null) return
    attachRecommendCoverView(cover)
    cover.animate().cancel(); cover.alpha=1f
}

/** 立刻收掉过渡黑场（离开推荐页、切到整屏提示、重建画面时用）。 */
fun TvMainActivity.dismissRecommendCover() {
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
fun TvMainActivity.onRecommendScrub(fraction: Float,finished: Boolean) {
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

/** 推荐页底部那块信息浮层：进度 + 剧名 + 操作说明。 */
fun TvMainActivity.attachRecommendOverlay(series: Series) {
    dismissRecommendOverlay()
    // **触屏设备不再建这块浮层**（需求 1）：推荐页改用播放界面那一条控制条
    //（顶部信息条 + 底部进度 / 状态项 / 六枚可点按钮，见 `attachTouchBar` / `showHud`），
    // 轻点画面唤出。浮层如果还留着，它就压在控制条上面，而且那排「看正片 / 暂停 / 换一部」
    // 与六键控制条功能重叠（前者已被用户点名去掉）。
    // 电视上 `touchArmed` 恒为 false，这条提前返回不会执行，浮层与今天逐行一致。
    if(touchArmed) return
    // 详情接口回来的 `series` **不带推荐流的运营角标**（爆剧 / 新剧 / 热度排名）：
    // 起播之后这块浮层是按 `detail.series` 重挂的（见 `playEpisode`），于是标签整排消失——
    // 用户读到的就是「第一次加载能看到、一开始播就没了」。这里用推荐流里同一部剧那一份
    // 把空字段补齐（详情自己有值就以详情为准）。
    val feed=recommendItems.getOrNull(recommendIndex)
    val info=if(feed!=null && feed.id==series.id) series.copy(
        label=series.label.ifEmpty { feed.label },
        labelBg=series.labelBg.ifEmpty { feed.labelBg },
        recommendRanking=series.recommendRanking.ifEmpty { feed.recommendRanking },
        recommendRankingBg=series.recommendRankingBg.ifEmpty { feed.recommendRankingBg },
        tags=series.tags.ifEmpty { feed.tags },
        // 热度同理：详情接口不给，只有推荐流那一份带着。
        heat=if(series.heat>0L) series.heat else feed.heat,
    ) else series
    val bar=column().apply {
        setPadding(dp(78),dp(28),dp(78),dp(56))
        background=TvStyle.gradient(Color.TRANSPARENT,0xCC000000.toInt())
        clipChildren=false
    }
    // 进度那一行：与正式播放的底部控制条同一套——`SeekIndicator` + 右侧 `00:04 / 01:08`，
    // 连左右内边距（右侧 26、时间不占权重）都一样，两页看上去是同一条。
    // 电视上这一行也在：这不是触屏专属的东西，遥控器快进同样要知道播到哪儿。
    // 与状态提示之间留一格：提示在上、进度在下，两行读起来是一组读数。
    val transport=row().apply { setPadding(0,dp(12),0,0) }
    val progress=com.hgtv.star.tv.SeekIndicator(this).apply {
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable=false
        progress=0f; bufferedProgress=0f
    }
    transport.addView(progress,lp(0,dp(43)).apply { weight=1f; rightMargin=dp(26) })
    // 与正式播放一样可拖：拖动期间只改显示，抬手才真跳（见 [onRecommendScrub]）。
    progress.onScrub={ fraction,finished -> onRecommendScrub(fraction,finished) }
    val span=text("",18f,TvStyle.playerText).apply { setShadowLayer(4f,0f,1f,0x80000000.toInt()) }
    transport.addView(span,lp(-2,-2))
    recommendProgress=progress; recommendSpan=span

    val titleLine=row()
    val label=info.label.trim()
    if(label.isNotEmpty()) {
        titleLine.addView(
            recommendChip(label,info.labelBg,16f),
            lp(-2,-2).apply { rightMargin=dp(12) },
        )
    }
    val title=text(series.title,32f,TvStyle.playerText).apply {
        setTypeface(typeface,Typeface.BOLD)
        maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END
        setShadowLayer(6f,0f,2f,0x99000000.toInt())
    }
    titleLine.addView(title,lp(0,-2).apply { weight=1f })
    val details=row()
    val categoryTags=info.tags.trim()
    if(categoryTags.isNotEmpty()) {
        val category=text(categoryTags,17f,TvStyle.playerDim).apply {
            maxLines=1
            ellipsize=android.text.TextUtils.TruncateAt.END
            // **不给 weight**：给了它会撑满整行，把后面的热度 / 排名角标顶到最右边。
            // 类型标签按内容宽排，长到一定宽度就省略，角标紧跟在它后面。
            maxWidth=(resources.displayMetrics.widthPixels*0.55f).toInt()
            setShadowLayer(5f,0f,2f,0x99000000.toInt())
        }
        details.addView(category,lp(-2,-2))
    }
    // 热度：紧跟在剧集类型标签后面（`🔥9014万`）。**不套角标底板**——它和类型标签是同一行文字，
    // 加上背景反而把它读成一枚「可点的按钮」。接口没给数值时整枚收起。
    val heat=HeatLabel.badge(null,info.heat)
    var hasBefore=categoryTags.isNotEmpty()
    if(heat.isNotEmpty()) {
        val heatLabel=text(heat,17f,TvStyle.playerDim).apply {
            maxLines=1
            setShadowLayer(5f,0f,2f,0x99000000.toInt())
        }
        details.addView(heatLabel,lp(-2,-2).apply { if(hasBefore) leftMargin=dp(12) })
        hasBefore=true
    }
    val ranking=info.recommendRanking.trim()
    if(ranking.isNotEmpty()) {
        details.addView(
            recommendChip(ranking,info.recommendRankingBg,16f),
            lp(-2,-2).apply { if(hasBefore) leftMargin=dp(12) },
        )
    }
    // 尾巴留一段弹性空白：这一组（类型 / 热度 / 排名）因此从左边起排，
    // 而不是被撑到行尾贴右边。
    details.addView(Space(this),lp(0,1).apply { weight=1f })
    val hint=text("确定 追剧 · 上下 换剧 · 左右 快进退 · 菜单 播放设置 · 返回 主页面",16f,TvStyle.playerDim).apply {
        setPadding(0,dp(14),0,0)
        setShadowLayer(5f,0f,2f,0x99000000.toInt())
    }
    // 自上而下的阅读顺序：剧名 → 类型 / 热度 / 排名 → 播到哪儿 → 怎么操作。
    //
    // **这里不再有「状态行」**（原来那行会写「正在加载…」/「已暂停」/ 临时提示）：
    // 这块浮层贴底（`Gravity.BOTTOM`），多一行它整体就往上长一截，标题和标签跟着被顶上去；
    // 那一行时有时无，用户读到的就是「界面在跳」。等待一律放到**屏幕正中**的三点
    // （`showCenterLoading`，与进入第一屏那个加载同一个样式），不占浮层的布局位置。
    bar.addView(titleLine,lp(-1,-2))
    if(categoryTags.isNotEmpty() || heat.isNotEmpty() || ranking.isNotEmpty()) {
        bar.addView(details,lp(-1,-2).apply { topMargin=dp(6) })
    }
    bar.addView(transport,lp(-1,-2))
    bar.addView(hint,lp(-1,-2))
    recommendTitle=title; recommendOverlay=bar
    // 播放设置开着时浮层让位（菜单贴底占了下三分之一），收起菜单时再露出来。
    bar.visibility=if(playbackMenu!=null) View.GONE else View.VISIBLE
    // 挂进「画面之外那一层」：竖屏短剧全屏把画面转 90° 时，这块浮层要跟着一起转，
    // 否则剧名 / 进度 / 操作提示会横在转了过来的画面上。电视上不旋转时两者完全等价。
    (overlayLayer ?: root).addView(bar,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
    // 首帧就把内容填满：时间、进度都在这一刻算出来，不等下一次每秒 tick。
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

/**
 * 推荐信息里的官方小标签。
 *
 * `bg` 是服务端原始颜色串（例如爆剧的
 * `#FF6868,#FF4C6DF7,#EFB662`），按从左到右渐变绘制；没有配色时用半透明深色兜底。
 */
private fun TvMainActivity.recommendChip(value: String,bg: String,size: Float): android.widget.TextView {
    val chip=text(value,size,Color.WHITE).apply {
        maxLines=1
        ellipsize=android.text.TextUtils.TruncateAt.END
        gravity=Gravity.CENTER
        setPadding(dp(10),dp(4),dp(10),dp(4))
        includeFontPadding=false
    }
    val colors=bg.split(',')
        .mapNotNull { runCatching { Color.parseColor(it.trim()) }.getOrNull() }
    chip.background=if(colors.isEmpty()) {
        GradientDrawable().apply {
            setColor(0xCC303030.toInt())
            cornerRadius=dp(6).toFloat()
        }
    } else if(colors.size==1) {
        GradientDrawable().apply {
            setColor(colors[0])
            cornerRadius=dp(6).toFloat()
        }
    } else {
        GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            colors.toIntArray(),
        ).apply {
            cornerRadius=dp(6).toFloat()
        }
    }
    return chip
}

fun TvMainActivity.dismissRecommendOverlay() {
    recommendOverlay?.let { overlay -> (overlay.parent as? android.view.ViewGroup)?.removeView(overlay) }
    recommendOverlay=null; recommendTitle=null
    // 进度条是浮层的子控件，随浮层一起消失；引用一并清掉，免得下一帧刷到已摘下的控件。
    recommendProgress=null; recommendSpan=null
}

/** 刷新浮层上的状态 / 进度那一行。 */
fun TvMainActivity.updateRecommendOverlay() {
    if(screen!="recommend" || detail==null) return
    val p=player
    // 触屏的推荐页没有浮层：它的读数（进度、时间、清晰度 / 倍速 / 画面 / 弹幕 / 定时）
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
    recommendProgress?.bufferedProgress=if(duration>0) {
        (p?.bufferedPosition ?: 0L).coerceIn(0,duration).toFloat()/duration
    } else 0f
    recommendSpan?.let { line ->
        val text=if(duration>0) "${formatTime(position)} / ${formatTime(duration)}" else "--:-- / --:--"
        if(line.text.toString()!=text) line.text=text
    }
    // 出画面了就把「正在加载」的框收掉：它浮在屏幕正中，不占浮层的位置。
    if(playbackReady) hideCenterLoading()
}

/**
 * 上下键换剧。
 *
 * 往下走到末尾时：还有下一页就先补一页再接着走（正常情况下这一页已经被
 * [ensureRecommendLookahead] 提前取好了，切换是即时的一帧）；真的取不到新内容就停在
 * 当前这一部并提示，**不环绕回第 1 部**——那会把刚刷过的那批从头重放一遍。
 * 往回按到头才环绕到末尾（往回刷重复一部不算问题，也不会打断观感）。
 */
fun TvMainActivity.switchRecommend(direction: Int) {
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
fun TvMainActivity.enterFormalPlayback(advanceNext: Boolean=false) {
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
    cancelRecommendBatchModels()
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
    recommendStore().markWatched(data.series)
    // 推荐页的「自动连播」只在播放器停在片尾时才有意义：如果这一集其实是**播到一半
    // 被别的路径（看门狗 / 队列故障）判成结束，`episodeIndex+1` 会直接跳掉剩下的内容。
    // 判据用播放器自己的位置，且只放行「确实走到结尾」这一种。
    val atEnd=runCatching {
        val p=player ?: return@runCatching false
        val duration=p.duration.coerceAtLeast(0)
        duration>0 && p.currentPosition>=duration-2_000
    }.getOrDefault(true)
    if(!advanceNext) {
        recommendPlaybackController.reportEventById(
            data.series.id,
            com.hgtv.star.core.RecommendBehavior.Event.CLICK,
            episodeId=data.episodes.getOrNull(episodeIndex).orEmpty(),
            episodeIndex=episodeIndex,
            positionMs=runCatching { player?.currentPosition ?: 0L }.getOrDefault(0L),
            durationMs=runCatching { player?.duration ?: 0L }.getOrDefault(0L),
        )
    }
    if(atEnd) {
        recommendPlaybackController.reportEventById(
            data.series.id,
            com.hgtv.star.core.RecommendBehavior.Event.PLAY_COMPLETE,
            episodeId=data.episodes.getOrNull(episodeIndex).orEmpty(),
            episodeIndex=episodeIndex,
            positionMs=runCatching { player?.currentPosition ?: 0L }.getOrDefault(0L),
            durationMs=runCatching { player?.duration ?: 0L }.getOrDefault(0L),
        )
    }
    if(advanceNext && atEnd) {
        if(episodeIndex<data.episodes.lastIndex) {
            // 上面那次 `saveProgress` 已经在「本集已播完」的状态下写了一条 completed 记录
            //（此刻播放器停在片尾、时长已知），因此这里只管起播下一集。
            playEpisode(episodeIndex+1,0L,autoplay=true)
        } else {
            advanceToNextSeasonOrFinish(data.series)
        }
    } else {
        // 停在当前这一集继续看：画面本来就没断，这里只把控制层交还给正式播放的按键。
        // 这一集已经播到结尾（手动按确定时正好赶上片尾）：从头再放一次这一集没有意义，
        // 直接按正片自己的连播规则往下走（有下一集就下一集，最后一集就交给「全剧已播完」）。
        val ended=runCatching { player?.playbackState==Player.STATE_ENDED }.getOrDefault(false)
        when {
            ended && episodeIndex<data.episodes.lastIndex -> playEpisode(episodeIndex+1,0L,autoplay=true)
            ended -> advanceToNextSeasonOrFinish(data.series)
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
fun TvMainActivity.seekRecommend(direction: Int,repeat: Int) {
    if(screen!="recommend") return
    val p=player ?: return
    if(!playbackReady) return
    val duration=p.duration
    if(duration<=0) return
    // 与正片同一份步长（`:core` 的 [SeekStep]，手机端也是它）：两页的左右键不能是两套手感。
    val step=SeekStep.stepMs(library.seekStep,repeat+1)
    val target=SeekStep.target(pendingSeek ?: p.currentPosition,direction,step,duration)
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
fun TvMainActivity.goHomeFromRecommend() {
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
fun TvMainActivity.exitRecommendToHome() {
    if(screen!="recommend") return
    goHomeFromRecommend()
}

/**
 * 从推荐进入的正式播放，按返回**直接回主页**（需求 4）。
 *
 * 先按正式播放的规则写一次进度（这一集确实看过了），再走与推荐页返回同一条收尾：
 * 不经过简介页、首页重新取数据。
 */
fun TvMainActivity.leaveRecommendPlaybackToHome() {
    if(screen!="player") return
    sleepTimer.cancel(); sleepChoice=0; sleepStopped=false
    saveProgress(); library.flush(); invalidatePage()
    goHomeFromRecommend()
}
