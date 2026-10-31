// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone.page

import android.content.Context
import android.content.Intent
import android.widget.LinearLayout
import com.hgtv.star.core.CatalogFilter
import com.hgtv.star.core.RankBoard
import com.hgtv.star.phone.PhonePlayerActivity
import com.hgtv.star.phone.PhoneSearchActivity
import com.hgtv.star.phone.PhoneServices
import com.hgtv.star.phone.ui.PhoneCardMenu
import com.hgtv.star.phone.ui.PhonePosterFeed
import com.hgtv.star.phone.ui.PhoneTopBar
import com.hgtv.star.phone.ui.dp
import com.hgtv.star.phone.ui.tabBarInset

/**
 * 榜单页：四个榜单（漫剧 / 真人 / AI剧 / 上新）+ 带名次的两列海报。
 *
 * 四个榜单就是 `RankBoard` 的四个枚举值，**与 TV 端完全同一套**：
 * 接口的 `genre` 与 `sort` 都写在枚举里，这里不再拼参数。
 * 「上新」是时间序，不画名次（`RankBoard.ranked == false`），与 TV 一致。
 *
 * 顶栏与分类页一样固定常驻，不随海报列表滚动自动收起。
 */
class PhoneRankPage(context: Context) : LinearLayout(context) {

    private val topBar = PhoneTopBar(context)
    private val feed = PhonePosterFeed(context)
    private var selected = 0

    private val boards = RankBoard.entries

    init {
        orientation = VERTICAL
        setBackgroundColor(com.hgtv.star.phone.ui.PhoneStyle.background)

        feed.ranked = true
        // 分类 / 榜单默认 2 列：封面够大，一屏也能扫到几张。
        // 列数跟着设置（1 / 2 / 3），与分类页同一份。
        feed.applyColumnsFromSettings()
        feed.bottomInset = context.tabBarInset() + context.dp(8)
        // 点海报先进详情页（与 TV 一致）。
        feed.onOpen = { series ->
            context.startActivity(Intent(context, com.hgtv.star.phone.PhoneDetailActivity::class.java).apply {
                putExtra(com.hgtv.star.phone.PhoneDetailActivity.EXTRA_SERIES_ID, series.id)
                putExtra(com.hgtv.star.phone.PhoneDetailActivity.EXTRA_TITLE, series.title)
            })
        }
        // 长按：榜单多一项「不在热播榜中显示」（`library.hide`，与 TV 的隐藏同一份落盘值）。
        // 隐藏之后要重新取一遍——被隐藏的那部不该还留在这一页里。
        feed.onLongPress = { series ->
            PhoneCardMenu.show(feed, series, hotList = true) { selectBoard(selected) }
        }

        topBar.setTabs(boards.map { it.label }, 0) { index -> selectBoard(index) }
        topBar.onSearch = {
            context.startActivity(Intent(context, PhoneSearchActivity::class.java))
        }
        // 顶栏在上、网格在下：垂直排列，网格不会被顶栏盖住。
        addView(topBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(feed, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        selectBoard(0)
    }

    private fun selectBoard(index: Int) {
        selected = index
        topBar.applyTabSelection(index)
        val board = boards[index]
        // 「上新」不画名次，与 TV 的 RankBoard.ranked 判断一致。
        feed.ranked = board.ranked
        val repo = PhoneServices.repository(context)
        val lib = PhoneServices.library(context)
        // 长按菜单里的「不在热播榜中显示」写进的是 `library.hide(id)`：这里必须过滤，
        // 否则隐藏完再回到这一页，那部剧还在——与 TV 的 `SeriesGrid` / `ListPage` 同一条规则。
        feed.load { page ->
            repo.rank(page, board)?.let { CatalogFilter.visible(it, runCatching { lib.hidden() }.getOrDefault(emptySet())) }
        }
    }

    fun scrollToTop() = feed.scrollToTop()

    /** 从设置页返回时重新对齐「列表每行」。 */
    fun onShown() {
        feed.applyColumnsFromSettings()
    }
}
