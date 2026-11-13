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
/** 预热好的首集最多留几份（每份约 512KB）。8 份 = 连划八部仍然可以直接换源。 */
const val RECOMMEND_PREPARED_LIMIT=8
/** 同时在途的「相邻详情」请求上限：给当前这一部的详情留出线程（见 `prefetchRecommendNeighbors`）。 */
const val DETAIL_PREFETCH_LIMIT=3
/** 推荐页详情缓存留几部：左右相邻来回切够用，再多只是占内存。 */
const val RECOMMEND_DETAIL_LIMIT=8

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
