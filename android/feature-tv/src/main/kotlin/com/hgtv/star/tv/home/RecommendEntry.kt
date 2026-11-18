// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import com.hgtv.star.shared.debug.StartupTrace

/**
 * 打开推荐页并自动播放一部推荐。
 *
 * 从任何页面都能进（导航栏里紧挨搜索按钮的那枚圆形「推荐」入口、冷启动）。当前已经在推荐页、
 * 而且列表已经到手时什么都不做——重复按入口不该把正在看的这一部换掉。
 *
 * 每次进来都取**新的一批**：从本机落盘的续流游标（`session_id` + `next_offset`）接着往下取，
 * 所以退出再进、甚至杀进程重开，看到的都是新的推荐，而不是同一套列表。
 */
fun TvMainActivity.openRecommend() {
    val traceStarted=StartupTrace.now()
    StartupTrace.mark("recommend.open.start")
    // 上一次停在推荐页那一部（没退出推荐页就又进了一次，例如回到前台）：先把停留时长结掉，
    // 否则「看了一分钟」与「一进来就划走」会混成同一条记录。
    settleRecommendWatch()
    // 冷启动那一次只消费一次：即使下面早退（已经在推荐页），也不能留着这个标记，
    // 否则下次回到前台又会被它拽进推荐页。
    initialCatalogPending=false
    // 已经在推荐页：列表非空（或请求在途）就什么都不做——重复按入口不该把正在看的这一部换掉。
    // 例外是「列表空、也没有请求在途」（上一次取推荐失败了，整屏只有一行提示）：
    // 这时按入口 / 按确定都应该是「重试」，不能被这一条早退挡住。
    if(screen=="recommend" && (recommendItems.isNotEmpty() || recommendLoading)) {
        StartupTrace.done("recommend.open.already",traceStarted)
        return
    }
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
    recommendWaiters.clear(); recommendAdvancePending=0; recommendAdvanceWaiterQueued=false
    // 起点来自落盘的游标（`session_id|next_offset`）：上次刷到哪儿，这次就从哪儿往下取。
    recommendStore().restoreCursor()
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
    // 现在用 `RecommendCache.lastPlayed(RECOMMEND_CACHE_NAMESPACE)` 记住停在哪，再 +1 往后走。
    //
    // 本机隐藏 / 已追完的剧不进列表（与取页时那两道过滤同一套判据，见 `loadRecommendPage`）：
    // 快照是上一轮写的，用户在这之间可能刚把某一部隐藏掉。
    recommendStarted=false
    // 旧版本把位置写在默认文件，快照已经切到命名空间；升级后先把这一行迁移过来，
    // 否则第一次冷启动仍会从头选，用户会误以为修复没有生效。
    com.hgtv.star.core.RecommendCache.migratePosition(toNamespace=RECOMMEND_CACHE_NAMESPACE)
    val hidden=library.hidden()
    val snapshot=if(retained) emptyList() else recommendStore().restoreSnapshot(hidden)
    recommendRestored=retained
    if(retained) {
        // 列表 / 详情 / 预热都还在：往后挪一条，放缓存里的下一部（见 [nextRecommendIndex]）。
        // 只换下标，**不重取流**：这一列还是同一条流，重取会把用户刚看过的那几部又排回来。
        val resume=nextRecommendIndex(recommendItems)
        recommendIndex=resume
        // 这一列是上一轮留下的快照/列表；补页时按「恢复列表」合并，不能把正在看的旧列整页替换。
        recommendStore().restored=true
        android.util.Log.i("HGTV","Recommend retained items=${recommendItems.size} index=$resume")
        recommendStarted=true
        playRecommendAt(resume)
        if(recommendItems.size<RECOMMEND_FIRST_ITEMS) {
            requestRecommendFeedAfterFirstFrame()
        }
    } else if(snapshot.isNotEmpty()) {
        val resume=resumeIndex(snapshot)
        recommendItems=snapshot
        recommendHasMore=true
        recommendRestored=true
        recommendIndex=resume
        recommendStarted=true
        android.util.Log.i("HGTV","Recommend resume items=${snapshot.size} index=$resume "+
            "last=${com.hgtv.star.core.RecommendCache.lastPlayed(RECOMMEND_CACHE_NAMESPACE).takeLast(6)} "+
            "disk=${if(mediaCached(snapshot[resume])) "full" else "partial"}")
        playRecommendAt(resume)
        // 流**先不取**：这一部的详情 / 地址 / 索引 / 首块都在盘上，首帧只等解码。
        // 这时候再插一个取流的 POST 只会和出帧抢那一点带宽与 CPU。等 READY 再取；
        // 8 秒还没出画面才按兜底超时取。
        requestRecommendFeedAfterFirstFrame()
    } else showRecommendMessage("正在加载推荐…",loading=true)
    if(snapshot.isEmpty()) {
        // 没有任何快照（第一次装 / 清过缓存）：整屏只有一行字，这一部只能由流决定。
        loadRecommendPage(append=false,minItemsPerLoad=1) {
            if(isDestroyed || screen!="recommend" || recommendStarted) return@loadRecommendPage
            if(recommendItems.isEmpty()) return@loadRecommendPage
            recommendStarted=true
            android.util.Log.i("HGTV","Recommend cold start play index=0 items=${recommendItems.size}")
            playRecommendAt(0)
            // 首条已经足够起播。保留现有列表作为恢复上下文，剩余首屏条目在首帧后补齐。
            recommendStore().restored=true
            recommendRestored=true
            if(recommendItems.size<RECOMMEND_FIRST_ITEMS) {
                requestRecommendFeedAfterFirstFrame()
            }
        }
    }
    StartupTrace.done(
        "recommend.open",
        traceStarted,
        "snapshot=${snapshot.size} retained=$retained items=${recommendItems.size}",
    )
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
private fun TvMainActivity.resumeIndex(snapshot: List<com.hgtv.star.core.Series>): Int {
    val last=com.hgtv.star.core.RecommendCache.lastPlayed(RECOMMEND_CACHE_NAMESPACE)
    val seen=runCatching { library.recommendSeen() }.getOrDefault(emptySet())
    fun fresh(series: com.hgtv.star.core.Series)=
        series.id !in seen && !library.recommendSeenTitle(series.title)
    val lastIndex=snapshot.indexOfFirst { it.id==last }
    // 快照里还没有走出第一条时，启动仍从第一条开始。否则 `last + 1` 会把用户
    // 刚看到的第 0 条当成“已消费”，直接跳到第 1 条——就是启动后第一条闪一下被略过。
    if(lastIndex==0) return 0
    val start=(if(lastIndex>=0) lastIndex+1 else 0).coerceIn(0,snapshot.lastIndex)
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
private fun TvMainActivity.nextRecommendIndex(items: List<com.hgtv.star.core.Series>): Int {
    if(items.isEmpty()) return 0
    val last=com.hgtv.star.core.RecommendCache.lastPlayed(RECOMMEND_CACHE_NAMESPACE)
    val at=if(last.isEmpty()) -1 else items.indexOfFirst { it.id==last }
    // 同 [resumeIndex]：第 0 条还没有真正离开时不要跳过它。
    if(at==0) return 0
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
private fun TvMainActivity.mediaCached(series: com.hgtv.star.core.Series): Boolean = runCatching {
    val detail=com.hgtv.star.core.DetailCache.load(series.id) ?: return false
    val episode=detail.episodes.firstOrNull() ?: return false
    // 索引键是「集 + 实际档位 + 编码」（见 `StreamInfo.mediaKey`），精确值要取一次地址才知道，
    // 这里只做「盘上有没有」的粗判：任意档位 / 任意编码命中就算有（见 `hasAnyMedia`）。
    // 查不到只是退回更高一级预热，不影响正确性。
    com.hgtv.star.core.PlaybackCache.hasAnyMedia(episode)
}.getOrDefault(false)
