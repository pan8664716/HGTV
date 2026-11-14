// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone.page

import android.content.Context
import android.widget.LinearLayout
import com.hgtv.star.core.CatalogPage
import com.hgtv.star.core.CatalogFilter
import com.hgtv.star.core.CatalogPaging
import com.hgtv.star.core.RankBoard
import com.hgtv.star.core.rank
import com.hgtv.star.phone.PhoneServices
import com.hgtv.star.phone.phoneNav
import com.hgtv.star.shared.nav.Route
import com.hgtv.star.phone.ui.PhoneCardMenu
import com.hgtv.star.phone.ui.PhonePosterFeed
import com.hgtv.star.phone.ui.PhoneTopBar
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.tabBarInset

/**
 * 榜单页：四个榜单（推荐 / 漫剧 / AI剧 / 真人）+ 带名次的两列海报。
 *
 * 四个榜单就是 `RankBoard` 的四个枚举值，**与 TV 端完全同一套**：
 * 接口的 `genre` 与 `sort` 都写在枚举里，这里不再拼参数。
 * 「推荐榜」与首页同名栏目取同一份推荐热度榜，画名次。
 *
 * 顶栏与分类页一样固定常驻，不随海报列表滚动自动收起。
 */
class PhoneRankPage(
    context: Context,
    initialState: State? = null,
) : LinearLayout(context) {

    data class State(
        val selected: Int,
        val feed: PhonePosterFeed.Snapshot,
        val feeds: Map<RankBoard, PhonePosterFeed.Snapshot> = emptyMap(),
    )

    private val topBar = PhoneTopBar(context)
    private val feed = PhonePosterFeed(context)
    private var selected = initialState?.selected ?: 0
    private val feedCache = linkedMapOf<RankBoard, PhonePosterFeed.Snapshot>().apply {
        putAll(initialState?.feeds.orEmpty())
    }

    private val boards = RankBoard.navEntries

    init {
        orientation = VERTICAL
        setBackgroundColor(com.hgtv.star.phone.ui.PhoneStyle.background)

        feed.ranked = true
        // 分类 / 榜单默认 2 列：封面够大，一屏也能扫到几张。
        // 列数跟着设置：竖屏 1–3，横屏映射为 4–6，与分类页同一份。
        feed.applyColumnsFromSettings()
        feed.bottomInset = context.tabBarInset() + context.dp(8)
        // 点海报先进详情页（与 TV 一致）。
        feed.onOpen = { series ->
            context.phoneNav().open(Route.Detail(series.id, series.title))
        }
        // 长按：榜单多一项「不在热播榜中显示」（`library.hide`，与 TV 的隐藏同一份落盘值）。
        // 隐藏之后只把那一格从列表里摘掉（`removeItem`）：重取整页会闪一下、位置顶回顶部。
        // 「收藏 / 稍后看」只是状态变化，重绑可见项即可。
        feed.onLongPress = { series ->
            PhoneCardMenu.show(
                anchor = feed,
                series = series,
                hotList = true,
                onChanged = { selectBoard(selected) },
                onItemChanged = { feed.refreshItems() },
                onItemRemoved = { id -> feed.removeItem(id) },
            )
        }

        topBar.setTabs(boards.map { it.label }, selected) { index ->
            if(index!=selected) selectBoard(index)
        }
        topBar.onSearch = {
            context.phoneNav().open(Route.Search)
        }
        // 顶栏在上、网格在下：垂直排列，网格不会被顶栏盖住。
        addView(topBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(feed, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val restored = initialState?.feed
        if (restored?.loaded == true) {
            feedCache[boards[selected]] = restored
            restoreBoard(selected, restored)
        } else {
            selectBoard(selected)
        }
    }

    fun snapshotState(): State {
        rememberCurrentFeed()
        return State(
            selected = selected,
            feed = feed.snapshot(),
            feeds = feedCache.toMap(),
        )
    }

    private fun restoreBoard(index: Int, snapshot: PhonePosterFeed.Snapshot) {
        selected = index
        topBar.applyTabSelection(index)
        val board = boards[index]
        feed.ranked = true
        feed.restore(snapshot,loaderFor(board))
    }

    private fun selectBoard(index: Int) {
        rememberCurrentFeed()
        selected = index
        topBar.applyTabSelection(index)
        showSelectedBoard()
    }

    private fun showSelectedBoard() {
        val board = boards[selected]
        // 四个榜单都来自服务端热度榜，统一显示名次。
        feed.ranked = true
        val loader=loaderFor(board)
        val cachedFeed=feedCache[board]
        if(cachedFeed?.loaded==true) {
            feed.restore(cachedFeed,loader)
            return
        }
        val lib = PhoneServices.library(context)
        val cached=runCatching { lib.cachedHome(board) }.getOrNull()
        if(cached!=null) {
            val visible=CatalogFilter.visible(cached,runCatching { lib.hidden() }.getOrDefault(emptySet()))
            if(visible.items.isNotEmpty()) {
                feed.restore(
                    PhonePosterFeed.Snapshot(
                        items=visible.items,
                        // 旧缓存可能只有 20 条：下一次先补第 1 页；完整首屏则从第 2 页续接。
                        page=CatalogPaging.nextPageForRestored(visible.items.size,visible.hasMore),
                        ended=!visible.hasMore,
                        heatBy=visible.ranking?.positions?.mapValues { it.value.heat }.orEmpty(),
                        emptyVisible=false,
                        emptyText="",
                        loaded=true,
                    ),
                    loader=loader,
                )
                return
            }
        }
        feed.load(loader)
    }

    private fun rememberCurrentFeed() {
        val snapshot=feed.snapshot()
        if(!snapshot.loaded) return
        val board=boards.getOrNull(selected) ?: return
        feedCache.remove(board)
        feedCache[board]=snapshot
        while(feedCache.size>8) {
            feedCache.remove(feedCache.keys.firstOrNull() ?: break)
        }
    }

    /**
     * 榜单取数统一出口：第 1 页同步更新首页 / 榜单共用的持久缓存，
     * 后续页只负责续接当前 [PhonePosterFeed]。
     */
    private fun loaderFor(board: RankBoard): suspend (Int) -> CatalogPage? {
        val repo = PhoneServices.repository(context)
        val lib = PhoneServices.library(context)
        return { page ->
            repo.rank(page, board)?.let { fetched ->
                val visible=CatalogFilter.visible(
                    fetched,
                    runCatching { lib.hidden() }.getOrDefault(emptySet()),
                )
                if(page==1 && visible.items.isNotEmpty()) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                        lib.cacheHome(board,visible.items,visible.hasMore,visible.ranking)
                    }
                }
                visible
            }
        }
    }

    fun scrollToTop() = feed.scrollToTop()

    /** 从设置页返回时重新对齐「列表每行」。 */
    fun onShown() {
        feed.applyColumnsFromSettings()
    }
}
